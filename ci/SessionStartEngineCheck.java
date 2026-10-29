package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Real macOS engines and AppleScript IPC, using only owned temporary installations. No OS approval dialog. */
public final class SessionStartEngineCheck {
    private static Path evidence;
    private static Payload payload;
    private static final List<ObjectNode> observations = new ArrayList<>();
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Path java() { return Path.of(System.getProperty("java.home"), "bin/java"); }
    private static void helper(String[] args) throws Exception {
        Path job = Path.of(args[1]), home = Path.of(args[2]);
        Files.writeString(job.resolve("helper.pid"), Long.toString(ProcessHandle.current().pid()));
        long pid = SessionStart.await(job, home, new SessionStart.Caller(Long.parseLong(args[3]), Long.parseLong(args[4])), item -> {
            try { Files.writeString(job.resolve("events.jsonl"), Json.MAPPER.writeValueAsString(item) + "\n", StandardOpenOption.APPEND); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }, Duration.ofSeconds(30));
        Json.write(job.resolve("helper-result.json"), Json.object().put("pid", pid));
    }
    private static String apple(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static void prepare(Path home) throws Exception {
        Path app = home.resolve(".launcher/OIE Engine.app");
        Path backend = app.resolve("Contents/app/oie-installer-jpackage.jar");
        Files.copy(Path.of(System.getProperty("oie.check.backend")), backend, StandardCopyOption.REPLACE_EXISTING);
        Model.command("/usr/bin/codesign", "--force", "--deep", "--sign", "-", app.toString());
        Model.command("/usr/bin/codesign", "--verify", "--deep", "--strict", app.toString());
        observations.add(Json.object().put("home", home.toString()).put("testedBackendSha256", Payload.sha256(backend)));
    }
    private static long sessionStart(Path home, Payload p) throws Exception {
        prepare(home);
        Path job = Files.createTempDirectory("oie-session-ipc-");
        for (String file : List.of("events.jsonl", "start-result.json")) { Files.createFile(job.resolve(file)); Json.privateFile(job.resolve(file)); }
        var caller = SessionStart.Caller.current();
        List<String> command = List.of("/bin/sh", "-c", "trap '' TERM; exec \"$@\"", "ignored-term-fixture", java().toString(), "-cp", System.getProperty("java.class.path"),
                SessionStartEngineCheck.class.getName(), "--helper", job.toString(), home.toString(), Long.toString(caller.pid()), Long.toString(caller.started()));
        String shell = String.join(" ", command.stream().map(FilesSupport::quote).toList());
        Process helper = new ProcessBuilder("/usr/bin/osascript", "-e", "do shell script " + apple(shell))
                .redirectErrorStream(true).redirectOutput(job.resolve("helper.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(job.resolve("helper.pid"))) {
                require(helper.isAlive() && System.nanoTime() < deadline, "AppleScript fixture did not start; see " + job);
                Thread.sleep(25);
            }
            long helperPid = Long.parseLong(Files.readString(job.resolve("helper.pid")));
            String signals = Model.command(java().resolveSibling("jcmd").toString(), Long.toString(helperPid), "VM.info");
            Files.writeString(evidence.resolve("ignored-helper-" + helperPid + ".txt"), signals);
            require(signals.lines().anyMatch(line -> line.contains("SIGTERM:") && line.contains("SIG_IGN")), "Helper did not inherit ignored SIGTERM");
            // The real Installer.run must still hold its lock while the helper requests a session launch.
            Class<?> lock = Class.forName(Installer.class.getName() + "$EngineLock");
            Method acquire = lock.getDeclaredMethod("acquire", Path.class); acquire.setAccessible(true);
            try {
                AutoCloseable unexpected = (AutoCloseable)acquire.invoke(null, home); unexpected.close();
                throw new AssertionError("Installation lock was released during handoff");
            } catch (InvocationTargetException expected) {
                require(expected.getCause() instanceof IOException, "Unexpected lock failure");
            }
            Method read = Privileges.class.getDeclaredMethod("readEvents", Path.class, int.class, Consumer.class, Consumer.class, Payload.class, Path.class, boolean.class, Process.class);
            read.setAccessible(true); int consumed = 0;
            while (helper.isAlive()) {
                try { consumed = (Integer)read.invoke(null, job, consumed, (Consumer<Installer.Event>)event -> {}, (Consumer<String>)line -> {}, p, home, false, helper); }
                catch (InvocationTargetException e) { throw new IOException("Caller event dispatch failed", e.getCause()); }
                Thread.sleep(25);
            }
            require(helper.exitValue() == 0, "AppleScript helper failed; see " + job);
            long pid = Json.read(job.resolve("helper-result.json")).path("pid").asLong();
            require(pid == Json.read(job.resolve("start-result.json")).path("pid").asLong(), "Helper lost the acknowledged PID");
            require(!ProcessHandle.of(helperPid).map(ProcessHandle::isAlive).orElse(false), "Helper fixture still runs");
            System.out.println("PASS: ignored-SIGTERM AppleScript helper requested caller launch; lock held; helper exited");
            return pid;
        } finally {
            if (helper.isAlive()) {
                Files.writeString(job.resolve("start-result.json"), "{\"ok\":false,\"error\":\"fixture aborted\"}");
                require(helper.waitFor(35, TimeUnit.SECONDS), "Helper still runs; retained " + job);
            }
            Files.copy(job.resolve("helper.log"), evidence.resolve("helper-" + job.getFileName() + ".log"), StandardCopyOption.REPLACE_EXISTING);
            FilesSupport.deleteTree(job);
        }
    }
    private static void log(String line) { if (!line.startsWith("Checking SHA-256")) System.out.println(line); }
    private static Model.Plan plan(Path home, int http, int https) {
        ObjectNode node = Json.object().put("installDir", home.toString()).put("javaCmd", payload.bundledJava() ? Model.BUNDLED : java().toString())
                .put("httpPort", http).put("httpsPort", https).put("database", "derby").put("adminPassword", "Owned-session-fixture-password").put("service", false);
        ObjectNode plugins = node.putObject("plugins"); Payload.IDS.forEach(id -> plugins.put(id, false));
        return Model.Plan.from(node);
    }
    private static Map<String, Object> install(Path home, Model.Plan plan, Installer.ProcessStarter starter) throws Exception {
        return new Installer(payload, event -> {}, SessionStartEngineCheck::log, starter).run(plan);
    }
    private static void completed(Path home, Map<String, Object> result) throws Exception {
        require(Json.read(home.resolve(Model.RECEIPT)).path("pid").asLong() == ((Number)result.get("pid")).longValue(), "Receipt PID was not saved");
        require(!FilesSupport.properties(home.resolve("conf/mirth.properties")).containsKey("server.initialadminpassword"), "Initial password remains after startup");
        for (ProcessHandle process : Model.engineProcesses(home)) {
            String signals = Model.command(java().resolveSibling("jcmd").toString(), Long.toString(process.pid()), "VM.info");
            Files.writeString(evidence.resolve("session-engine-" + process.pid() + ".txt"), signals);
            require(signals.lines().noneMatch(line -> line.contains("SIGTERM:") && line.contains("SIG_IGN")), "Session engine retained ignored SIGTERM");
        }
        long before = System.nanoTime();
        String stopped = Model.command(home.resolve("oieservice").toString(), "stop");
        Files.writeString(evidence.resolve(home.getFileName() + "-native-stop.log"), stopped);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        require(elapsed < 10_000, "Normal stop needed the SIGINT fallback: " + elapsed + "ms");
        require(Model.engineProcesses(home).isEmpty(), "Stopped session engine still runs");
        observations.add(Json.object().put("home", home.toString()).put("gracefulStopMillis", elapsed).put("receiptPidSaved", true).put("passwordRemoved", true));
        System.out.println("PASS: actual engine ready, PID persisted, password removed, normal SIGTERM stop " + elapsed + "ms");
    }
    private static void clean(Path home) throws Exception {
        if (!Files.exists(home)) return;
        if (!Model.engineProcesses(home).isEmpty()) {
            require(ServiceControl.run(home, "stop") == 0, "Cannot clean running owned engine: " + home);
        }
        require(Model.engineProcesses(home).isEmpty(), "Refusing cleanup while an owned engine runs: " + home);
        Path saved = evidence.resolve(home.getFileName()); Files.createDirectories(saved);
        if (Files.isDirectory(home.resolve("logs"))) FilesSupport.copyTree(home.resolve("logs"), saved.resolve("logs"));
        if (Files.exists(home.resolve(Model.RECEIPT))) Files.copy(home.resolve(Model.RECEIPT), saved.resolve(Model.RECEIPT), StandardCopyOption.REPLACE_EXISTING);
        FilesSupport.deleteTree(home);
        observations.add(Json.object().put("home", home.toString()).put("processesExited", true).put("installationAndDatabaseRemoved", !Files.exists(home)));
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--helper")) { helper(args); return; }
        require(Model.MAC && !Privileges.administrator(), "Run this check in an unelevated macOS session");
        evidence = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(evidence); payload = Payload.load();
        Path root = Files.createTempDirectory("oie-session-engines-").toRealPath();
        Path normal = root.resolve("normal"), legacy = root.resolve("legacy"), failed = root.resolve("failed");
        try {
            int http = Model.freePort(58080), https = Model.freePort(58443);
            completed(normal, install(normal, plan(normal, http, https), SessionStartEngineCheck::sessionStart));
            clean(normal);
            Map<String, Object> old = install(legacy, plan(legacy, http, https), (home, p) -> {
                prepare(home);
                Files.createDirectories(home.resolve("logs"));
                return new ProcessBuilder("/bin/sh", "-c", "trap '' TERM; exec \"$@\"", "legacy-engine-fixture", home.resolve("oieserver").toString())
                        .directory(home.toFile()).redirectInput(new java.io.File("/dev/null")).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("logs/oieserver.out").toFile())).start().pid();
            });
            require(((Number)old.get("pid")).longValue() > 0, "Legacy engine did not start");
            for (ProcessHandle process : Model.engineProcesses(legacy)) {
                String signals = Model.command(java().resolveSibling("jcmd").toString(), Long.toString(process.pid()), "VM.info");
                Files.writeString(evidence.resolve("legacy-engine-" + process.pid() + ".txt"), signals);
                require(signals.lines().anyMatch(line -> line.contains("SIGTERM:") && line.contains("SIG_IGN")), "Legacy fixture did not ignore SIGTERM");
            }
            completed(legacy, install(legacy, plan(legacy, http, https), SessionStartEngineCheck::sessionStart));
            require(Files.readString(legacy.resolve("logs/mirth.log")).contains("shutting down mirth due to normal request"), "Legacy engine did not run graceful shutdown");
            System.out.println("PASS: actual ignored-SIGTERM engine stopped during reinstall and replacement handles SIGTERM");
            clean(legacy);
            try {
                install(failed, plan(failed, http, https), (home, p) -> SessionStart.await(Files.createTempDirectory(root, "dead-caller-"), home,
                        new SessionStart.Caller(0, -1), request -> { throw new AssertionError("Dead caller requested startup"); }, Duration.ofSeconds(1)));
                throw new AssertionError("Lost caller did not fail installation");
            } catch (IOException expected) { require(expected.getMessage().contains("session ended"), "Unexpected caller-loss failure: " + expected); }
            require(!Files.exists(failed) && Model.engineProcesses(failed).isEmpty(), "Caller-loss rollback retained a runnable installation or password");
            System.out.println("PASS: caller loss rolls back without leaving an engine or initial password");
        } finally {
            try { clean(normal); clean(legacy); clean(failed); }
            finally {
                Json.write(evidence.resolve("observations.json"), observations);
                boolean empty;
                try (var children = Files.list(root)) {
                    for (Path child : children.toList()) if (child.getFileName().toString().startsWith("dead-caller-") && Files.isDirectory(child)) FilesSupport.deleteTree(child);
                }
                try (var children = Files.list(root)) { empty = children.findAny().isEmpty(); }
                if (empty) Files.delete(root); else System.err.println("Retained owned resources: " + root);
            }
        }
    }
}
