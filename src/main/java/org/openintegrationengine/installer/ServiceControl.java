package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

/** The install4j oieservice verbs: start, stop, restart and status. Exit codes follow LSB init scripts. */
final class ServiceControl {
    private static final class NotAdministrator extends IOException { NotAdministrator(String message) { super(message); } }

    static int run(Path home, String verb) {
        try {
            JsonNode service = service(home);
            return switch (verb) {
                case "status" -> status(home, service);
                case "start" -> start(home, service);
                case "stop" -> stop(home, service, verb);
                default -> { int stopped = stop(home, service, verb); yield stopped != 0 ? stopped : start(home, service); }
            };
        } catch (NotAdministrator e) { System.err.println(e.getMessage()); return 4; }
        catch (Exception e) { System.err.println(e.getMessage() == null ? e.toString() : e.getMessage()); return 1; }
    }
    private static JsonNode service(Path home) throws IOException {
        Path file = home.resolve(Model.RECEIPT);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        JsonNode receipt = Json.read(file);
        // Start the engine as the user who installed it, as the installer did.
        for (String key : new String[]{"invokingHome", "invokingUser", "invokingUid"})
            if (receipt.hasNonNull(key)) System.setProperty("oie.invoking." + key.substring(8).toLowerCase(), receipt.path(key).asText());
        JsonNode service = receipt.get("service");
        return service == null || service.isNull() ? null : service;
    }
    private static boolean running(Path home, JsonNode service) throws Exception {
        return !Model.engineProcesses(home).isEmpty() || service != null && Services.running(home, service);
    }
    private static void administrator(String verb) throws IOException {
        if (!Privileges.administrator())
            throw new NotAdministrator("Controlling the system service requires administrator privileges. Run: " + (Model.WINDOWS ? "oieservice.exe " + verb + " from an elevated prompt" : "sudo ./oieservice " + verb));
    }
    private static int status(Path home, JsonNode service) throws Exception {
        boolean running = running(home, service);
        System.out.println("oieservice is " + (running ? "running" : "not running"));
        return running ? 0 : 3;
    }
    private static int start(Path home, JsonNode service) throws Exception {
        if (running(home, service)) { System.out.println("oieservice is already running"); return 0; }
        System.out.println("Starting oieservice");
        if (service != null) { administrator("start"); Services.start(home, service); }
        else NativeProcesses.start(home, home.resolve(Model.WINDOWS ? "oieserver.exe" : "oieserver"));
        return 0;
    }
    private static int stop(Path home, JsonNode service, String verb) throws Exception {
        if (!running(home, service)) { System.out.println("oieservice is not running"); return 0; }
        System.out.println("Shutting down oieservice");
        if (service != null) { administrator(verb); Services.stop(home, service, false); }
        else NativeProcesses.stopOwned(home);
        Instant deadline = Instant.now().plusSeconds(70);
        while (Instant.now().isBefore(deadline)) {
            if (!running(home, service)) return 0;
            Thread.sleep(500);
        }
        System.err.println("The engine did not stop within 70 seconds.");
        for (ProcessHandle process : Model.engineProcesses(home))
            System.err.println("  still running: " + process.pid() + " " + process.info().command().orElse("?"));
        return 1;
    }
}
