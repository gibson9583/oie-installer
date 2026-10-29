package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One explicit OS elevation at Install; the JavaFX application stays in the user's session. */
final class Privileges {
    static boolean administrator() {
        try {
            return Model.WINDOWS ? Model.command("powershell.exe", "-NoProfile", "-Command", "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)").equalsIgnoreCase("true")
                    : Model.command("id", "-u").equals("0");
        } catch (Exception e) { return false; }
    }
    static boolean available() { return Model.MAC || Model.WINDOWS; }
    static Path invokingHome() { return Path.of(System.getProperty("oie.invoking.home", System.getProperty("user.home"))); }
    static String invokingUser() { return System.getProperty("oie.invoking.user", Model.WINDOWS ? System.getenv().getOrDefault("USERDOMAIN", ".") + "\\" + System.getProperty("user.name") : System.getProperty("user.name")); }
    static String invokingUid() throws Exception { return System.getProperty("oie.invoking.uid", Model.WINDOWS ? "" : Model.command("id", "-u")); }
    static Map<String, Object> install(Payload payload, Model.Plan plan, Consumer<Installer.Event> events, Consumer<String> log) throws Exception {
        if (!available() || administrator()) return new Installer(payload, events, log).run(plan);
        Path expectedHome = Model.MAC ? Model.canonical(plan.home()) : plan.home();
        Path jobs = Model.userHome().resolve(".cache/oie-installer/jobs"); Files.createDirectories(jobs);
        Path job = Files.createTempDirectory(jobs, "install-");
        Process elevation = null;
        try {
            if (Files.getFileStore(job).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(job, PosixFilePermissions.fromString("rwx------"));
            if (Model.WINDOWS) Model.command("icacls.exe", job.toString(), "/inheritance:r", "/grant:r", System.getProperty("user.name") + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F");
            ObjectNode input = Json.object();
            input.put("installDir", plan.home().toString()).put("javaCmd", plan.javaCmd()).put("httpPort", plan.httpPort()).put("httpsPort", plan.httpsPort())
                    .put("database", plan.database()).put("databaseUrl", plan.databaseUrl()).put("databaseUsername", plan.databaseUsername()).put("databasePassword", plan.databasePassword())
                    .put("adminPassword", plan.adminPassword()).put("service", plan.service()).put("expectedMode", plan.expectedMode()).put("expectedVersion", plan.expectedVersion());
            if (Model.MAC) {
                var caller = SessionStart.Caller.current();
                input.put("callerPid", caller.pid()).put("callerStarted", caller.started());
            }
            input.set("plugins", Json.MAPPER.valueToTree(plan.plugins()));
            input.put("invokingHome", Model.userHome().toString()).put("invokingUser", invokingUser());
            if (!Model.WINDOWS) input.put("invokingUid", Model.command("id", "-u"));
            Json.write(job.resolve("plan.json"), input);
            for (String file : List.of("events.jsonl", "outcome.json", "helper.log", "start-result.json")) { Files.createFile(job.resolve(file)); Json.privateFile(job.resolve(file)); }
            Path code = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // The wildcard is Java classpath syntax, not a Windows filesystem name.
            String classpath = code + File.pathSeparator + code.resolveSibling("lib") + File.separator + "*";
            Path java = Path.of(System.getProperty("java.home"), "bin", Model.WINDOWS ? "java.exe" : "java");
            if (!Files.isRegularFile(java)) throw new IOException("The installer runtime must include its Java command for the privileged helper. Rebuild with the current packaging script.");
            List<String> command = List.of(java.toString(), "-Doie.payload=" + payload.dir, "-cp", classpath, Main.class.getName(), "--elevated-install", job.toString());
            if (Model.MAC) {
                String shell = String.join(" ", command.stream().map(FilesSupport::quote).toList()) + " >" + FilesSupport.quote(job.resolve("helper.log").toString()) + " 2>&1";
                elevation = new ProcessBuilder("osascript", "-e", "with timeout of 3600 seconds\n do shell script " + apple(shell) + " with administrator privileges\nend timeout").redirectErrorStream(true).start();
            } else {
                String arguments = String.join(" ", command.subList(1, command.size()).stream().map(Privileges::windowsArgument).toList());
                String script = "$ErrorActionPreference='Stop'; $p=Start-Process -FilePath " + powershell(java.toString()) + " -ArgumentList " + powershell(arguments)
                        + " -Verb RunAs -WindowStyle Hidden -PassThru; $p.WaitForExit(); exit $p.ExitCode";
                elevation = new ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand", encoded(script)).redirectErrorStream(true).start();
            }
            int consumed = 0;
            while (elevation.isAlive()) {
                consumed = readEvents(job, consumed, events, log, payload, expectedHome, plan.service(), elevation); Thread.sleep(200);
            }
            readEvents(job, consumed, events, log, payload, expectedHome, plan.service(), elevation);
            if (Files.size(job.resolve("outcome.json")) == 0) {
                String detail = new String(elevation.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                throw new IOException("Administrator approval was cancelled or the installer helper could not start." + (detail.isBlank() ? "" : " " + detail));
            }
            JsonNode outcome = Json.read(job.resolve("outcome.json"));
            if (!outcome.path("ok").asBoolean()) throw new IOException(outcome.path("error").asText("Installation failed; see the details."));
            return Json.MAPPER.convertValue(outcome.path("result"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } finally {
            // The private job contains credentials. Progress already lives in the UI and receipt; remove this transient copy.
            if (elevation == null || !elevation.isAlive()) FilesSupport.deleteTree(job);
            else log.accept("The installer helper is still running; its private job was retained at " + job);
        }
    }
    private static int readEvents(Path job, int consumed, Consumer<Installer.Event> events, Consumer<String> log,
                                  Payload payload, Path expectedHome, boolean service, Process elevation) throws Exception {
        List<String> lines = new String(Files.readAllBytes(job.resolve("events.jsonl")), StandardCharsets.UTF_8).lines().toList();
        for (; consumed < lines.size(); consumed++) {
            JsonNode item;
            try { item = Json.MAPPER.readTree(lines.get(consumed)); } catch (IOException incomplete) { break; }
            if (item.has("sessionStart")) {
                if (!Model.MAC || service || !elevation.isAlive()) throw new IOException("The installation helper cannot request an engine start in this session.");
                SessionStart.respond(job, Path.of(item.path("sessionStart").asText()), expectedHome, payload, NativeProcesses::start);
            } else if (item.has("log")) log.accept(item.path("log").asText());
            else events.accept(Json.MAPPER.treeToValue(item, Installer.Event.class));
        }
        return consumed;
    }
    static void helper(Path job) throws Exception {
        if (!administrator()) throw new IOException("The installation helper requires administrator privileges.");
        for (String file : List.of("plan.json", "events.jsonl", "outcome.json", "start-result.json")) FilesSupport.safeTarget(job, file);
        JsonNode input = Json.read(job.resolve("plan.json"));
        System.setProperty("oie.invoking.home", input.path("invokingHome").asText());
        System.setProperty("oie.invoking.user", input.path("invokingUser").asText());
        System.setProperty("oie.invoking.uid", input.path("invokingUid").asText());
        ObjectNode outcome = Json.object();
        SessionStart.Caller caller = Model.MAC ? new SessionStart.Caller(input.path("callerPid").asLong(), input.path("callerStarted").asLong(-1)) : null;
        try {
            Installer.ProcessStarter starter = caller != null && !input.path("service").asBoolean()
                    ? (home, payload) -> SessionStart.await(job, home, caller, item -> append(job, item), java.time.Duration.ofMinutes(5))
                    : NativeProcesses::start;
            var installer = new Installer(Payload.load(), event -> append(job, Json.MAPPER.valueToTree(event)), line -> append(job, Json.object().put("log", line)), starter);
            outcome.put("ok", true).set("result", Json.MAPPER.valueToTree(installer.run(Model.Plan.from(input))));
        } catch (Exception e) { outcome.put("ok", false).put("error", e.getMessage() == null ? e.toString() : e.getMessage()); }
        try { Files.writeString(job.resolve("outcome.json"), Json.MAPPER.writeValueAsString(outcome), StandardOpenOption.TRUNCATE_EXISTING); }
        finally { if (caller != null && !caller.alive()) FilesSupport.deleteTree(job); }
    }
    private static void append(Path job, JsonNode item) {
        try { Files.writeString(job.resolve("events.jsonl"), Json.MAPPER.writeValueAsString(item) + "\n", StandardOpenOption.APPEND); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    static String powershell(String value) { return "'" + value.replace("'", "''") + "'"; }
    static String encoded(String value) {
        return Base64.getEncoder().encodeToString(("[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n$ProgressPreference = 'SilentlyContinue'\n" + value).getBytes(StandardCharsets.UTF_16LE));
    }
    static String windowsArgument(String value) {
        StringBuilder result = new StringBuilder("\""); int slashes = 0;
        for (char c : value.toCharArray()) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') result.append("\\".repeat(slashes * 2 + 1)).append('"');
            else result.append("\\".repeat(slashes)).append(c);
            slashes = 0;
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }
    private static String apple(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
