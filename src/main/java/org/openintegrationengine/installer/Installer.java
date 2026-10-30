package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

/** Engine lifecycle shared by the JavaFX wizard and unattended CLI. No UI dependencies. */
final class Installer {
    record Event(String id, String label, String state, String detail, double progress) {}
    private final Payload payload;
    private final Consumer<Event> events;
    private final Consumer<String> log;
    private List<String> steps;
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("verify", "Verify the payload"), Map.entry("stop", "Stop the engine"), Map.entry("backup", "Back up settings and data"),
            Map.entry("unpack", "Install the engine files"), Map.entry("replace", "Replace the program files"), Map.entry("launcher", "Install the native launchers"),
            Map.entry("java", "Set the Java runtime"), Map.entry("configure", "Write the engine settings"), Map.entry("plugins", "Install the selected add-ons"),
            Map.entry("start", "Start the engine"), Map.entry("ready", "Wait for the engine to start"), Map.entry("secure", "Remove the initial password"));
    private String active;
    @FunctionalInterface interface ProcessStarter { long start(Path home, Payload payload) throws Exception; }
    private final ProcessStarter processStarter;
    static String stepLabel(String id) { return LABELS.getOrDefault(id, id); }
    Installer(Payload payload, Consumer<Event> events, Consumer<String> log) { this(payload, events, log, NativeProcesses::start); }
    Installer(Payload payload, Consumer<Event> events, Consumer<String> log, ProcessStarter processStarter) {
        this.payload = payload; this.events = events; this.log = log; this.processStarter = processStarter;
    }
    private void event(String id, String state, String detail, double fraction) {
        double progress = (steps.indexOf(id) + fraction) / steps.size();
        events.accept(new Event(id, stepLabel(id), state, detail, Math.max(0, Math.min(1, progress))));
    }
    private void begin(String id) { active = id; event(id, "active", "", 0); }
    private void done(String id, String detail) { event(id, "done", detail, 1); log.accept(stepLabel(id) + (detail.isEmpty() ? "" : ": " + detail)); }
    static List<String> steps(boolean upgrade) {
        return upgrade ? List.of("verify", "stop", "backup", "replace", "launcher", "java", "configure", "plugins", "start", "ready")
                : List.of("verify", "unpack", "launcher", "java", "configure", "plugins", "start", "ready", "secure");
    }
    static void validate(Model.Plan plan, Model.Destination destination) throws Exception {
        if (!plan.home().isAbsolute()) throw new IllegalArgumentException("Select an absolute engine path.");
        if (!plan.javaCmd().equals(Model.BUNDLED)) {
            Model.JavaRuntime java = Model.probe(Model.expand(plan.javaCmd()));
            if (java == null || !java.supported()) throw new IllegalArgumentException("Select a Java 17 or later executable, or use the bundled runtime.");
        }
        if (!plan.expectedMode().isEmpty() && !plan.expectedMode().equals(destination.mode())) throw new IllegalArgumentException("The destination changed since Review. Check the location and review again.");
        if (!plan.expectedVersion().isEmpty() && (destination.existing() == null || !plan.expectedVersion().equals(destination.existing().version())))
            throw new IllegalArgumentException("The installed engine changed since Review. Check the location and review again.");
        if (plan.service()) {
            if (Services.supported().isEmpty()) throw new IllegalArgumentException("This system has no supported service manager.");
            String reason = Services.blocked(destination.home()); if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
            reason = Services.conflict(destination.home()); if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
        }
        if (destination.mode().equals("new")) {
            if (!Model.DATABASES.contains(plan.database())) throw new IllegalArgumentException("Select a supported database.");
            if (!plan.database().equals("derby") && plan.databaseUrl().isBlank()) throw new IllegalArgumentException("Enter the database JDBC URL.");
            if (plan.adminPassword().codePointCount(0, plan.adminPassword().length()) < 15) throw new IllegalArgumentException("Use an administrator password of at least 15 characters.");
            if (plan.httpPort() == plan.httpsPort()) throw new IllegalArgumentException("HTTP and HTTPS ports must be different.");
            if (!Model.portFree(plan.httpPort()) || !Model.portFree(plan.httpsPort())) throw new IllegalArgumentException("The selected network ports are no longer available.");
            for (String value : List.of(plan.adminPassword(), plan.databaseUrl(), plan.databaseUsername(), plan.databasePassword()))
                if (value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Settings cannot contain line breaks.");
        }
    }
    Map<String, Object> run(Model.Plan plan) throws Exception {
        if (!payload.nativeEngine()) throw new IOException("Rebuild this installer with the native engine payload.");
        Path home = Model.canonical(plan.home());
        if (plan.javaCmd().equals(Model.BUNDLED) && !payload.bundledJava()) {
            var java = Model.runtimes().stream().filter(Model.JavaRuntime::supported).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("This installer has no bundled Java. Install Java 17 or later, or use the -with-JRE installer."));
            plan = plan.withJava(java.path().toString());
        }
        try (EngineLock held = EngineLock.acquire(home)) {
            Model.Destination destination = Model.destination(home.toString(), payload);
            validate(plan, destination);
            steps = steps(destination.existing() != null);
            for (String id : steps) event(id, "pending", "", 0);
            try { return install(plan, destination); }
            catch (FileSystemException e) {
                // A bare path is the whole message of most file errors; say what happened to it.
                String reason = e instanceof AccessDeniedException ? "Access was denied to " + e.getFile()
                        : e instanceof NoSuchFileException ? "Missing " + e.getFile() : e.getMessage();
                IOException readable = new IOException(reason, e);
                if (active != null) event(active, "error", reason, 0);
                throw readable;
            }
            catch (Exception e) { if (active != null) event(active, "error", e.getMessage(), 0); throw e; }
        }
    }
    private record EngineLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        static EngineLock acquire(Path home) throws Exception {
            String lockPath = Model.WINDOWS ? home.toString().toLowerCase(Locale.ROOT) : home.toString();
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(lockPath.getBytes(StandardCharsets.UTF_8)));
            Path locks = Privileges.administrator() ? (Model.WINDOWS ? Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "OIEInstaller/locks") : Path.of("/var/run/oie-installer/locks"))
                    : Model.userHome().resolve(".cache/oie-installer/locks"); Files.createDirectories(locks);
            // Retain the stable file: deleting it could allow different jobs to lock different inodes.
            FileChannel channel = FileChannel.open(locks.resolve(key + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock;
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { lock = null; }
                if (lock == null) throw new IOException("Another Java installer is working on this engine folder.");
                return new EngineLock(channel, lock);
            } catch (Exception e) { channel.close(); throw e; }
        }
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    private Map<String, Object> install(Model.Plan plan, Model.Destination destination) throws Exception {
        Path home = destination.home(); var existing = destination.existing(); boolean upgrade = existing != null, homeExisted = Files.exists(home);
        begin("verify"); payload.verify(log); Archives.Layout layout = Archives.inspect(payload); Archives.verifyPlugins(payload);
        done("verify", payload.manifest.path("files").size() + " files match their pinned hashes");
        Path backup = null;
        if (upgrade) {
            begin("stop"); stopExisting(home, existing, log); done("stop", "Engine is stopped");
            begin("backup");
            backup = home.resolve("backups").resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now()) + "-" + existing.version());
            Files.createDirectories(backup.getParent()); Files.createDirectory(backup);
            for (String folder : List.of("conf", "appdata")) if (Files.exists(home.resolve(folder), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(home.resolve(folder))) throw new IOException("Cannot safely back up a linked " + folder + " folder.");
                FilesSupport.copyTree(home.resolve(folder), backup.resolve(folder));
            }
            if (Files.isRegularFile(home.resolve(Model.RECEIPT), LinkOption.NOFOLLOW_LINKS)) Files.copy(home.resolve(Model.RECEIPT), backup.resolve(Model.RECEIPT));
            if (existing.service() != null && existing.service().hasNonNull("file")) {
                Path serviceFile = Path.of(existing.service().path("file").asText());
                if (Files.isRegularFile(serviceFile, LinkOption.NOFOLLOW_LINKS)) Files.copy(serviceFile, backup.resolve("previous-service" + (Model.WINDOWS ? ".xml" : Model.MAC ? ".plist" : ".service")));
            }
            NativeProcesses.retireLegacyRegistration(home, backup);
            // Like install4j's UninstallPreviousAction: the previous service and program files go before the new ones are installed.
            Services.stop(home, true);
            ObjectNode stopped = existing.receipt() == null ? Json.object() : existing.receipt().deepCopy();
            stopped.put("archiveSha256", stopped.path("archiveSha256").asText("")).put("launcherCommit", stopped.path("launcherCommit").asText("install4j"))
                    .put("version", existing.version()).put("pendingPayloadSha256", payload.sourceHash()).put("home", home.toString()).put("migrationPending", true).putNull("service");
            Json.write(home.resolve(Model.RECEIPT), stopped, false);
            done("backup", backup.toString());
            begin("replace"); Archives.retire(home, layout, backup.resolve("program"));
        } else { begin("unpack"); Files.createDirectories(home); }
        Path properties = FilesSupport.safeTarget(home, "conf/mirth.properties");
        boolean passwordPending = false;
        List<String> installedPlugins = new ArrayList<>();
        ObjectNode receipt = upgrade && existing.receipt() != null ? existing.receipt().deepCopy() : Json.object();
        List<String> changed; Path selectedJava; BooleanSupplier alive; long offset;
        int https = upgrade ? existing.httpsPort() : plan.httpsPort();
        try {
            try {
                String unpack = upgrade ? "replace" : "unpack";
                changed = Archives.extract(payload, home, backup == null ? null : backup.resolve("defaults-" + payload.version()), layout.count(),
                        (detail, fraction) -> event(unpack, "active", detail, fraction));
                done(unpack, upgrade ? "Program files replaced; configuration and data kept" : "Engine unpacked");
                begin("launcher");
                for (String name : List.of("oieserver", "oieservice", "oiecommand"))
                    if (!Files.isRegularFile(payload.launcher(home, name))) throw new IOException("Missing native launcher: " + name);
                done("launcher", "Server, service and command line");
                begin("java");
                Path options = FilesSupport.safeTarget(home, "conf/custom.vmoptions");
                List<String> lines = new ArrayList<>(Files.readAllLines(options).stream().filter(line -> !line.strip().startsWith("-java-cmd")).toList());
                selectedJava = plan.javaCmd().equals(Model.BUNDLED) ? payload.engineJava(home) : Model.expand(plan.javaCmd()).toRealPath();
                int marker = lines.indexOf("# Add custom options above this comment."); lines.add(marker < 0 ? lines.size() : marker, "-java-cmd " + selectedJava);
                Files.writeString(options, String.join("\n", lines) + "\n"); done("java", plan.javaCmd().equals(Model.BUNDLED) ? "Bundled Java" : selectedJava.toString());
                Files.createDirectories(home.resolve("logs")); Files.createDirectories(home.resolve("appdata"));
                begin("configure");
                Map<String, String> settings = new LinkedHashMap<>(); settings.put("version", payload.version());
                if (!upgrade) {
                    settings.put("http.port", String.valueOf(plan.httpPort())); settings.put("https.port", String.valueOf(plan.httpsPort()));
                    String keystore = Model.password(); settings.put("keystore.storepass", keystore); settings.put("keystore.keypass", keystore);
                    settings.put("server.initialadminpassword", plan.adminPassword()); passwordPending = true;
                    if (!plan.database().equals("derby")) {
                        settings.put("database", plan.database()); settings.put("database.url", plan.databaseUrl());
                        settings.put("database.username", plan.databaseUsername()); settings.put("database.password", plan.databasePassword());
                    }
                }
                FilesSupport.properties(properties, settings, Set.of()); done("configure", upgrade ? "Existing database, ports and credentials kept" : "Database, ports and initial password written");
                String now = Instant.now().toString(); receipt.put("version", payload.version()).put("archive", payload.manifest.path("archive").asText())
                        .put("archiveSha256", payload.sourceHash())
                        .put("launcherCommit", payload.manifest.path("launcher").path("commit").asText()).put("updatedAt", now)
                        .put("installer", "jpackage").put("home", home.toString()).put("nativeEngine", true).put("javaCmd", selectedJava.toString())
                        .put("invokingUser", Privileges.invokingUser()).put("invokingHome", Privileges.invokingHome().toString()).put("invokingUid", Privileges.invokingUid());
                if (!receipt.has("installedAt")) receipt.put("installedAt", now);
                receipt.remove(List.of("service", "migrationPending", "pendingPayloadSha256", "legacyInstall4j", "rolledBack"));
                receipt.put("httpsPort", https); receipt.putNull("pid");
                receipt.set("programExtensions", Json.MAPPER.valueToTree(layout.extensions()));
                if (upgrade) {
                    var history = receipt.has("history") && receipt.path("history").isArray() ? receipt.withArray("history") : receipt.putArray("history");
                    history.addObject().put("from", existing.version()).put("to", payload.version()).put("at", now).put("backup", home.relativize(backup).toString());
                }
                writeTools(home, receipt);
                begin("plugins");
                ObjectNode installed = receipt.path("plugins").isObject() ? (ObjectNode)receipt.path("plugins") : receipt.putObject("plugins");
                for (var plugin : payload.plugins) if (plan.plugins().get(plugin.id())) {
                    Archives.plugin(payload, plugin, home, backup == null ? null : backup.resolve("program/extensions"));
                    installedPlugins.add(plugin.id());
                    installed.set(plugin.id(), plugin.metadata()); receipt.set("plugins", installed);
                    writeTools(home, receipt); log.accept("Installed " + plugin.name() + " " + plugin.version());
                }
                receipt.set("pluginSelection", Json.MAPPER.valueToTree(plan.plugins())); writeTools(home, receipt);
                long selected = plan.plugins().values().stream().filter(Boolean::booleanValue).count();
                done("plugins", selected + " selected; existing unselected add-ons kept");
                Services.permissions(home, plan.service());
                begin("start"); offset = Readiness.offset(home);
                if (plan.service()) {
                    receipt.set("service", Services.install(home, record -> {
                        receipt.set("service", record);
                        try { writeTools(home, receipt); } catch (Exception e) { throw new RuntimeException("Cannot save the service receipt", e); }
                    })); alive = () -> true;
                } else {
                    receipt.putNull("service"); long pid = processStarter.start(home, payload);
                    receipt.put("pid", pid); alive = () -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                }
                writeTools(home, receipt); done("start", plan.service() ? "Registered the " + Services.supported() + " service" : "Process " + receipt.path("pid").asLong());
            } catch (Exception failure) {
                rollback(home, existing, homeExisted, backup, layout, installedPlugins);
                throw failure;
            }
            begin("ready");
            Readiness.started(home, offset, payload.version(), https, alive);
            done("ready", "Open Integration Engine " + payload.version() + " is running");
            if (passwordPending) {
                begin("secure"); FilesSupport.properties(properties, Map.of(), Set.of("server.initialadminpassword")); passwordPending = false;
                done("secure", "Removed the initial password from the settings file");
            }
        } finally {
            // The engine reads the initial password once, when it creates its database. Never leave it on disk.
            if (passwordPending && Files.isRegularFile(properties)) FilesSupport.properties(properties, Map.of(), Set.of("server.initialadminpassword"));
        }
        String base = "https://localhost:" + https;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", destination.mode()); result.put("version", payload.version()); result.put("url", base); result.put("home", home.toString());
        result.put("service", receipt.get("service")); result.put("pid", receipt.path("pid").asLong());
        result.put("uninstall", Model.WINDOWS ? "& " + Privileges.powershell(home.resolve("uninstall.exe").toString()) : FilesSupport.quote(home.resolve("uninstall").toString()));
        result.put("plugins", receipt.get("plugins"));
        if (upgrade && Files.isDirectory(home.resolve("extensions"))) {
            try (var entries = Files.list(home.resolve("extensions"))) {
                result.put("thirdParty", entries.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                        .filter(id -> !layout.extensions().contains(id) && !plan.plugins().getOrDefault(id, false)).sorted().toList());
            }
        }
        try {
            String webClientUrl = webClientUrl(home, base);
            if (webClientUrl != null) result.put("webClientUrl", webClientUrl);
            else if (Files.isRegularFile(home.resolve("webapps/oie-webadmin.war")))
                log.accept("Could not determine the configured OIE Web Client address; using the engine address.");
        } catch (IOException | IllegalArgumentException e) {
            // Optional completion metadata must not turn a running installation into a failed one.
            log.accept("Could not determine the OIE Web Client address; using the engine address. " + e.getMessage());
        }
        if (!upgrade) result.put("adminPassword", plan.adminPassword());
        else { result.put("from", existing.version()); result.put("backup", backup.toString()); result.put("changedConf", changed); }
        return result;
    }
    /** Jetty serves the deployed WAR, including one retained from an earlier installation. */
    static String webClientUrl(Path home, String base) throws IOException {
        if (!Files.isRegularFile(home.resolve("webapps/oie-webadmin.war"))) return null;
        String context = literalWebContext(home.resolve("conf/mirth.properties"));
        // Runtime interpolation can differ in the engine's JVM or service account. Keep the engine address instead of guessing.
        if (context == null || context.contains("${")) return null;
        // Match MirthWebServer's normalization: add a leading slash and remove one trailing slash.
        if (!context.startsWith("/")) context = "/" + context;
        if (context.endsWith("/")) context = context.substring(0, context.length() - 1);
        try {
            // Context paths are decoded paths. Quote literal %, ?, # and Unicode; Jetty also quotes path parameters.
            return base + new URI(null, null, context + "/oie-webadmin/", null, null).toASCIIString().replace(";", "%3B");
        } catch (URISyntaxException e) { throw new IOException("Cannot form the OIE Web Client address.", e); }
    }
    /** Read one literal setting using the shipped engine's physical-line trimming and first-value rules. */
    private static String literalWebContext(Path properties) throws IOException {
        if (!Files.exists(properties)) return "";
        StringBuilder logical = new StringBuilder();
        for (String physical : Files.readAllLines(properties, StandardCharsets.ISO_8859_1)) {
            String line = physical.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int backslashes = 0;
            for (int end = line.length(); end > 0 && line.charAt(end - 1) == '\\'; end--) backslashes++;
            boolean continued = backslashes % 2 != 0;
            logical.append(continued ? line.substring(0, line.length() - 1) : line);
            if (continued) continue;
            // Decode standard escapes after physical whitespace was trimmed. This is a literal-setting reader, not a full engine parser.
            Properties entry = new Properties(); entry.load(new StringReader(logical.toString()));
            if (entry.stringPropertyNames().stream().anyMatch(key -> key.equalsIgnoreCase("include") || key.equalsIgnoreCase("includeoptional"))) return null;
            if (entry.containsKey("http.contextpath")) {
                // Commons preserves unknown value escapes that Properties drops; decline rather than guess those paths.
                for (int i = 0; i < logical.length(); i++) if (logical.charAt(i) == '\\') {
                    if (++i == logical.length() || "trnfu\\\"':=#!".indexOf(logical.charAt(i)) < 0) return null;
                }
                return entry.getProperty("http.contextpath");
            }
            logical.setLength(0);
        }
        // The engine ignores an unfinished continuation at EOF.
        return "";
    }
    /**
     * install4j's rollback: remove the new program files and registrations, keep the user's settings and data.
     * The previous program files were already removed, as install4j's UninstallPreviousAction does; they remain in the backup.
     */
    private void rollback(Path home, Model.Existing existing, boolean homeExisted, Path backup, Archives.Layout layout, List<String> installedPlugins) {
        log.accept("Rolling back: removing the new program files and registrations");
        List<String> problems = new ArrayList<>();
        try { Services.stop(home, true); } catch (Exception e) { problems.add(e.getMessage()); }
        try { NativeProcesses.removeTools(home); } catch (Exception e) { problems.add(e.getMessage()); }
        try {
            if (existing == null) {
                if (homeExisted) { try (var entries = Files.list(home)) { for (Path entry : entries.toList()) FilesSupport.deleteTree(entry); } }
                else FilesSupport.deleteTree(home);
            } else {
                for (String top : layout.top()) if (!Archives.KEEP.contains(top) && !top.equals("extensions")) FilesSupport.deleteTree(home.resolve(top));
                for (String extension : layout.extensions()) FilesSupport.deleteTree(home.resolve("extensions").resolve(extension));
                // Only the add-ons this run installed: install4j's rollback removes only what it installed.
                for (String id : installedPlugins) FilesSupport.deleteTree(home.resolve("extensions").resolve(id));
                // The configuration returns to its state before the upgrade.
                if (backup != null && Files.isDirectory(backup.resolve("conf"))) { FilesSupport.deleteTree(home.resolve("conf")); FilesSupport.copyTree(backup.resolve("conf"), home.resolve("conf")); }
                ObjectNode receipt = Json.object().put("version", existing.version()).put("archiveSha256", "").put("launcherCommit", "").put("home", home.toString())
                        .put("rolledBack", true).put("backup", backup == null ? "" : backup.toString());
                Json.write(home.resolve(Model.RECEIPT), receipt, false);
            }
        } catch (Exception e) { problems.add(e.getMessage()); }
        if (!problems.isEmpty()) log.accept("Rollback incomplete: " + String.join("; ", problems));
        else log.accept(existing == null ? "Rolled back: the new engine was removed" : "Rolled back: settings and data are kept; the previous program files are in " + backup);
    }
    private static void stopExisting(Path home, Model.Existing existing, Consumer<String> log) throws Exception {
        if (!existing.running()) {
            if (existing.service() != null) Services.stop(home, false);
            return;
        }
        if (!existing.managed()) throw new IOException("Stop the unmanaged engine before continuing.");
        Services.stop(home, false);
        if (!Model.engineProcesses(home).isEmpty()) {
            // install4j asks the user to close a running engine on Windows; it cannot stop a console window.
            if (Model.WINDOWS) throw new IOException("Close the running engine (oieserver.exe) and try again. No program files were replaced.");
            log.accept("Requesting graceful engine shutdown"); NativeProcesses.stopOwned(home);
        }
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            if (Model.engineProcesses(home).isEmpty() && Model.portFree(existing.httpsPort())) return;
            Thread.sleep(500);
        }
        throw new IOException("The engine did not stop within 60 seconds. No program files were replaced.");
    }
    private boolean toolsWritten;
    private void writeTools(Path home, ObjectNode receipt) throws Exception {
        Json.write(home.resolve(Model.RECEIPT), receipt, false);
        if (!toolsWritten) { NativeProcesses.tools(home, payload, payload.version(), Path.of(receipt.path("javaCmd").asText())); toolsWritten = true; }
    }
    /** Settings and data the uninstaller keeps unless asked to delete them, like install4j, which removes only the files it installed. */
    static final Set<String> USER_DATA = Set.of("conf", "appdata", "logs", "backups", "custom-lib", "custom-extensions", "server-launcher-lib", Model.RECEIPT);
    /** The program files to remove: everything except user data, the engine's own extensions and the add-ons this installer added. */
    static List<Path> programFiles(Path home, JsonNode receipt) throws IOException {
        List<Path> remove = new ArrayList<>();
        try (var entries = Files.list(home)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (!USER_DATA.contains(name) && !name.equals("extensions")) remove.add(entry);
            }
        }
        Set<String> extensions = new TreeSet<>();
        receipt.path("programExtensions").forEach(id -> extensions.add(id.asText()));
        receipt.path("plugins").fieldNames().forEachRemaining(extensions::add);
        for (String id : extensions) {
            Path extension = FilesSupport.safeTarget(home, "extensions/" + id);
            if (Files.exists(extension, LinkOption.NOFOLLOW_LINKS)) remove.add(extension);
        }
        return remove;
    }
    static void uninstall(Path path, boolean deleteData) throws Exception {
        if (!Privileges.administrator()) throw new IOException("Run the engine's uninstaller with administrator privileges.");
        Path home = Model.canonical(path);
        if (home.getParent() == null || home.equals(Model.userHome().toRealPath())) throw new IOException("Refusing to remove this path.");
        try (EngineLock held = EngineLock.acquire(home)) {
            JsonNode owner = Json.read(home.resolve(Model.RECEIPT));
            if (owner.hasNonNull("invokingHome")) System.setProperty("oie.invoking.home", owner.path("invokingHome").asText());
            if (owner.hasNonNull("invokingUser")) System.setProperty("oie.invoking.user", owner.path("invokingUser").asText());
            if (owner.hasNonNull("invokingUid")) System.setProperty("oie.invoking.uid", owner.path("invokingUid").asText());
            var existing = Model.detect(home);
            if (existing == null || !existing.managed()) throw new IOException("No recognized installer receipt. Nothing removed.");
            if (existing.receipt().hasNonNull("invokingHome")) System.setProperty("oie.invoking.home", existing.receipt().path("invokingHome").asText());
            if (existing.receipt().hasNonNull("invokingUser")) System.setProperty("oie.invoking.user", existing.receipt().path("invokingUser").asText());
            if (existing.receipt().hasNonNull("invokingUid")) System.setProperty("oie.invoking.uid", existing.receipt().path("invokingUid").asText());
            stopExisting(home, existing, System.out::println);
            Services.stop(home, true);
            if (!Model.engineProcesses(home).isEmpty()) throw new IOException("The engine still runs. Nothing removed.");
            NativeProcesses.removeTools(home);
            List<Path> remove = deleteData ? List.of(home) : programFiles(home, existing.receipt());
            if (!deleteData) {
                // A later installation into this folder continues with the kept settings and data.
                ObjectNode kept = Json.object().put("version", existing.version()).put("archiveSha256", "").put("launcherCommit", "").put("home", home.toString())
                        .put("rolledBack", true).put("uninstalledAt", Instant.now().toString());
                Json.write(home.resolve(Model.RECEIPT), kept, false);
            }
            String kept = deleteData ? "" : " The configuration, database, logs and backups remain in " + home + ".";
            if (Model.WINDOWS) { NativeProcesses.windowsRemove(home, remove); System.out.println("Removal scheduled after the uninstaller exits." + kept); }
            else {
                for (Path target : remove) FilesSupport.deleteTree(target);
                Path extensions = home.resolve("extensions");
                if (!deleteData && Files.isDirectory(extensions, LinkOption.NOFOLLOW_LINKS)) try (var left = Files.list(extensions)) { if (left.findAny().isEmpty()) Files.delete(extensions); }
                System.out.println("Removed the engine program files." + kept);
            }
        }
    }
}
