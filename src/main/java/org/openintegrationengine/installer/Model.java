package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

final class Model {
    static final String RECEIPT = "oie-installer.json";
    static final String BUNDLED = "@bundled";
    static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    static final List<String> DATABASES = List.of("derby", "postgres", "mysql", "sqlserver", "oracle");
    static Path userHome() { return Path.of(System.getProperty("user.home")); }
    static Path defaultHome() {
        return MAC ? Path.of("/Applications/OpenIntegrationEngine") : WINDOWS ? Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "OpenIntegrationEngine") : Path.of("/opt/openintegrationengine");
    }
    static Path expand(String text) {
        String value = text.trim();
        return value.equals("~") ? userHome() : value.startsWith("~/") ? userHome().resolve(value.substring(2)).normalize() : Path.of(value).normalize();
    }
    static Path canonical(Path path) throws IOException {
        Path abs = path.toAbsolutePath().normalize(), ancestor = abs;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        return ancestor.toRealPath().resolve(ancestor.relativize(abs));
    }
    static String password() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom(); StringBuilder value = new StringBuilder();
        for (int i = 0; i < 24; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.toString();
    }
    static boolean portFree(int port) {
        if (port < 1 || port > 65535) return false;
        // Like the engine's own listener, ignore closed connections in TIME_WAIT. Windows reuse would also share a live listener.
        boolean reuse = !WINDOWS;
        try (ServerSocket socket = new ServerSocket()) { socket.setReuseAddress(reuse); socket.bind(new InetSocketAddress("0.0.0.0", port)); }
        catch (IOException e) { return false; }
        try (ServerSocket socket = new ServerSocket()) { socket.setReuseAddress(reuse); socket.bind(new InetSocketAddress("::", port)); }
        catch (SocketException e) { return e.getMessage().contains("family") || e.getMessage().contains("supported"); }
        catch (IOException e) { return false; }
        return true;
    }
    static int freePort(int start) {
        for (int port = start; port < 65535; port++) if (portFree(port)) return port;
        throw new IllegalStateException("No available network port.");
    }
    static String command(String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var outputBytes = new java.util.concurrent.CompletableFuture<byte[]>();
        Thread.ofVirtual().start(() -> { try { outputBytes.complete(process.getInputStream().readAllBytes()); } catch (IOException e) { outputBytes.completeExceptionally(e); } });
        if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Command timed out: " + args[0]); }
        String output;
        try {
            // PowerShell reports module-loading progress as CLIXML on stderr, ahead of any script output.
            output = new String(outputBytes.get(5, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#< CLIXML") && !line.startsWith("<Objs ")).collect(java.util.stream.Collectors.joining("\n")).trim();
        }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { throw new IOException("Cannot read command output: " + args[0], e); }
        if (process.exitValue() != 0) throw new IOException(args[0] + " failed: " + output);
        return output;
    }
    record JavaRuntime(Path path, String version, int major, String vendor) {
        boolean supported() { return major >= 17; }
        boolean recommended() { return major == 21; }
        @Override public String toString() { return "Java " + version + (recommended() ? " · Recommended" : "") + (supported() ? "" : " · Requires 17+") + "\n" + path; }
    }
    static JavaRuntime probe(Path executable) {
        try {
            if (!Files.isExecutable(executable)) return null;
            Process process = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            String text = new String(process.getInputStream().readAllBytes());
            var match = Pattern.compile("version \\\"([^\\\"]+)\\\"").matcher(text);
            if (process.exitValue() != 0 || !match.find()) return null;
            String version = match.group(1); String[] parts = version.split("[.\\-+_]");
            int major = Integer.parseInt(parts[0].equals("1") ? parts[1] : parts[0]);
            return new JavaRuntime(executable.toRealPath(), version, major, text.lines().skip(1).findFirst().orElse("Java runtime"));
        } catch (Exception e) { return null; }
    }
    static List<JavaRuntime> runtimes() {
        Set<Path> candidates = new LinkedHashSet<>();
        String direct = System.getenv("OIE_JAVA_PATH"), home = System.getenv("JAVA_HOME");
        if (direct != null) candidates.add(expand(direct));
        if (home != null) candidates.add(Path.of(home, "bin", WINDOWS ? "java.exe" : "java"));
        if (WINDOWS) for (String folder : List.of("Java", "Eclipse Adoptium", "Microsoft", "Zulu")) {
            Path base = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), folder);
            if (Files.isDirectory(base)) try (var children = Files.list(base)) { children.forEach(p -> candidates.add(p.resolve("bin/java.exe"))); } catch (IOException ignored) {}
        }
        if (MAC) {
            try { candidates.add(Path.of(command("/usr/libexec/java_home"), "bin/java")); } catch (Exception ignored) {}
            for (Path base : List.of(Path.of("/Library/Java/JavaVirtualMachines"), userHome().resolve("Library/Java/JavaVirtualMachines")))
                if (Files.isDirectory(base)) try (var children = Files.list(base)) { children.forEach(p -> candidates.add(p.resolve("Contents/Home/bin/java"))); } catch (IOException ignored) {}
        }
        for (Path base : List.of(Path.of("/usr/lib/jvm"), Path.of("/opt/homebrew/opt"), Path.of("/usr/local/opt")))
            if (Files.isDirectory(base)) try (var children = Files.list(base)) {
                children.filter(p -> !base.toString().endsWith("opt") || p.getFileName().toString().startsWith("openjdk"))
                        .forEach(p -> candidates.add(p.resolve("bin/java")));
            } catch (IOException ignored) {}
        for (String folder : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path java = Path.of(folder, WINDOWS ? "java.exe" : "java");
            if (Files.isExecutable(java) && !(MAC && java.equals(Path.of("/usr/bin/java")))) candidates.add(java);
        }
        Map<Path, JavaRuntime> found = new LinkedHashMap<>();
        for (Path candidate : candidates) { JavaRuntime runtime = probe(candidate); if (runtime != null) found.put(runtime.path(), runtime); }
        return found.values().stream().sorted(Comparator.comparing(JavaRuntime::supported).reversed()
                .thenComparing(Comparator.comparing(JavaRuntime::recommended).reversed()).thenComparing(Comparator.comparingInt(JavaRuntime::major).reversed())).toList();
    }
    record Plan(Path home, String javaCmd, int httpPort, int httpsPort, String database, String databaseUrl,
                String databaseUsername, String databasePassword, String adminPassword, boolean service,
                Map<String, Boolean> plugins, String expectedMode, String expectedVersion) {
        static Plan from(JsonNode json) {
            if (!json.isObject()) throw new IllegalArgumentException("The installation plan must be a JSON object.");
            Map<String, Boolean> choices = new LinkedHashMap<>();
            JsonNode supplied = json.get("plugins");
            if (supplied != null) {
                if (!supplied.isObject()) throw new IllegalArgumentException("Add-on choices must be an object of true/false values.");
                supplied.fields().forEachRemaining(e -> {
                    if (!Payload.IDS.contains(e.getKey()) || !e.getValue().isBoolean()) throw new IllegalArgumentException("Invalid add-on choice: " + e.getKey());
                });
            }
            for (String id : Payload.IDS) choices.put(id, supplied == null || supplied.path(id).asBoolean(true));
            if (json.has("service") && !json.path("service").isBoolean()) throw new IllegalArgumentException("Service must be true or false.");
            return new Plan(expand(json.path("installDir").asText()), json.path("javaCmd").asText(BUNDLED), number(json, "httpPort"), number(json, "httpsPort"),
                    json.path("database").asText("derby"), json.path("databaseUrl").asText(), json.path("databaseUsername").asText(),
                    json.path("databasePassword").asText(), json.path("adminPassword").asText(), json.path("service").asBoolean(false),
                    Collections.unmodifiableMap(choices), json.path("expectedMode").asText(), json.path("expectedVersion").asText());
        }
        Plan withJava(String java) { return new Plan(home, java, httpPort, httpsPort, database, databaseUrl, databaseUsername, databasePassword, adminPassword, service, plugins, expectedMode, expectedVersion); }
        private static int number(JsonNode json, String key) {
            String text = json.path(key).asText();
            try { return Integer.parseInt(text); } catch (NumberFormatException e) { return 0; }
        }
    }
    record Existing(String version, int httpPort, int httpsPort, String database, String javaCmd, ObjectNode receipt, ObjectNode service, List<ProcessHandle> processes, long dataBytes, boolean serviceRunning) {
        /** Installed by this installer, or by install4j with its engine service. */
        boolean managed() { return receipt != null || service != null; }
        boolean running() { return serviceRunning || !processes.isEmpty(); }
        /** A failed operation was rolled back: user data remains, program files do not. */
        boolean rolledBack() { return receipt != null && receipt.path("rolledBack").asBoolean(false); }
    }
    record Destination(Path home, String mode, Existing existing, long free, long needed) {}
    static List<ProcessHandle> engineProcesses(Path home) {
        String launcher = home + "/mirth-server-launcher.jar", legacy = home + "/.install4j/";
        // Exclude this process and the launchers above it: a native launcher can host the JVM in a child process.
        Set<Long> self = new HashSet<>();
        for (Optional<ProcessHandle> p = Optional.of(ProcessHandle.current()); p.isPresent(); p = p.get().parent()) self.add(p.get().pid());
        try (var all = ProcessHandle.allProcesses()) {
            return all.filter(p -> !self.contains(p.pid())).filter(p -> {
                var info = p.info();
                if (info.command().isPresent()) {
                    Path command = Path.of(info.command().get()).toAbsolutePath().normalize();
                    String name = command.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (command.startsWith(home) && Set.of("oieserver", "oieservice", "oieserver.exe", "oieservice.exe", "oie-service.exe").contains(name)) return true;
                }
                if (info.arguments().isPresent()) return Arrays.stream(info.arguments().get()).anyMatch(a -> a.contains(launcher) || a.startsWith(legacy));
                return info.commandLine().map(s -> s.contains(launcher) || s.contains(legacy)).orElse(false);
            }).toList();
        }
    }
    static Existing detect(Path home) throws Exception {
        if (!Files.isRegularFile(home.resolve("conf/mirth.properties"))) return null;
        ObjectNode receipt = null;
        try { JsonNode n = Json.read(home.resolve(RECEIPT)); if (n.isObject() && n.has("archiveSha256") && n.has("launcherCommit")) receipt = (ObjectNode)n; } catch (IOException ignored) {}
        if (!Files.isDirectory(home.resolve("server-lib")) && (receipt == null || !receipt.path("rolledBack").asBoolean(false))) return null;
        ObjectNode service = Services.detect(home);
        var props = FilesSupport.properties(home.resolve("conf/mirth.properties")); String version = "unknown";
        try (JarFile jar = new JarFile(home.resolve("server-lib/mirth-client-core.jar").toFile())) {
            Properties p = new Properties(); try (InputStream input = jar.getInputStream(jar.getJarEntry("version.properties"))) { p.load(input); }
            version = p.getProperty("mirth.version", "unknown");
        } catch (Exception ignored) {}
        if (version.equals("unknown") && receipt != null) version = receipt.path("version").asText("unknown");
        String javaCmd = "";
        if (Files.exists(home.resolve("conf/custom.vmoptions"))) for (String line : Files.readAllLines(home.resolve("conf/custom.vmoptions")))
            if (line.strip().startsWith("-java-cmd ")) javaCmd = line.strip().substring(10).trim();
        return new Existing(version, Integer.parseInt(props.getProperty("http.port", "8080")), Integer.parseInt(props.getProperty("https.port", "8443")),
                props.getProperty("database", "derby"), javaCmd, receipt, service, engineProcesses(home),
                FilesSupport.size(home.resolve("appdata")) + FilesSupport.size(home.resolve("conf")), service != null && Services.running(home));
    }
    static int compare(String a, String b) {
        if (!a.matches("\\d+(?:\\.\\d+)*(?:[-+].*)?") || !b.matches("\\d+(?:\\.\\d+)*(?:[-+].*)?")) throw new IllegalArgumentException("Cannot determine engine version safely.");
        String[] x = a.split("[-+]", 2)[0].split("\\."), y = b.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int comparison = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (comparison != 0) return comparison;
        }
        return 0;
    }
    static Destination destination(String raw, Payload payload) throws Exception {
        Path entered = expand(raw);
        if (!entered.isAbsolute()) throw new IllegalArgumentException("Enter a full path, such as ~/OpenIntegrationEngine.");
        Path home = canonical(entered);
        if (home.getParent() == null || home.equals(userHome().toRealPath())) throw new IllegalArgumentException("Select a dedicated engine folder.");
        if (Files.exists(home) && !Files.isDirectory(home)) throw new IllegalArgumentException("This path is a file.");
        Existing existing = detect(home); String mode = "new";
        if (existing == null && Files.exists(home)) try (var entries = Files.list(home)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("This folder is not empty and has no engine.");
        }
        if (existing != null) {
            if (existing.running() && !existing.managed()) throw new IllegalArgumentException("This installer did not start the running engine. Stop it before continuing.");
            int version = compare(payload.version(), existing.version());
            if (version < 0) throw new IllegalArgumentException("Engine " + existing.version() + " is newer than this installer. Downgrades are not allowed.");
            mode = version == 0 ? "reinstall" : "upgrade";
            if (!existing.running() && !portFree(existing.httpsPort())) throw new IllegalArgumentException("Another program uses this engine's HTTPS port " + existing.httpsPort() + ".");
        }
        Path ancestor = home; while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!Files.isWritable(ancestor) && !Privileges.available()) throw new IllegalArgumentException("You cannot write to " + ancestor);
        long needed = payload.needed() + (existing == null ? 0 : existing.dataBytes());
        long free = Files.getFileStore(ancestor).getUsableSpace();
        if (free < needed) throw new IllegalArgumentException("Insufficient disk space. Need " + needed / 1048576 + " MB.");
        return new Destination(home, mode, existing, free, needed);
    }
}
