package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

/** install4j's oieservice verbs. Exit codes follow LSB init scripts. */
final class ServiceControl {
    private static final class NotAdministrator extends IOException { NotAdministrator(String message) { super(message); } }

    static int run(Path home, String verb) {
        try {
            invokingUser(home);
            ObjectNode service = Services.detect(home);
            // An init.d service runs this script itself, so it controls the engine process directly.
            if (service != null && service.path("kind").asText().equals("initd")) service = null;
            return switch (verb) {
                case "status" -> status(home, service);
                case "start" -> start(home, service);
                case "stop" -> stop(home, service, verb);
                case "install" -> install(home);
                case "uninstall" -> uninstall(home, service);
                default -> { int stopped = stop(home, service, verb); yield stopped != 0 ? stopped : start(home, service); }
            };
        } catch (NotAdministrator e) { System.err.println(e.getMessage()); return 4; }
        catch (Exception e) { System.err.println(e.getMessage() == null ? e.toString() : e.getMessage()); return 1; }
    }
    /** Start a normal process as the user who installed it, as the installer did. */
    private static void invokingUser(Path home) throws IOException {
        Path file = home.resolve(Model.RECEIPT);
        if (!Files.isReadable(file)) return;
        JsonNode receipt = Json.read(file);
        for (String key : new String[]{"invokingHome", "invokingUser", "invokingUid"})
            if (receipt.hasNonNull(key)) System.setProperty("oie.invoking." + key.substring(8).toLowerCase(), receipt.path(key).asText());
    }
    private static boolean running(Path home, ObjectNode service) throws Exception {
        return !Model.engineProcesses(home).isEmpty() || service != null && Services.running(home);
    }
    private static void administrator(String verb) throws IOException {
        if (!Privileges.administrator())
            throw new NotAdministrator("Controlling the engine service requires administrator privileges. Run: " + (Model.WINDOWS ? "oieservice.exe /" + verb + " from an elevated prompt" : "sudo ./oieservice " + verb));
    }
    private static int status(Path home, ObjectNode service) throws Exception {
        boolean running = running(home, service);
        System.out.println("oieservice is " + (running ? "running" : "not running"));
        return running ? 0 : 3;
    }
    private static int start(Path home, ObjectNode service) throws Exception {
        if (running(home, service)) { System.out.println("oieservice is already running"); return 0; }
        if (service == null && Model.WINDOWS) throw new IOException("The " + Services.WINDOWS_NAME + " is not installed. Run oieserver.exe, or install the service with oieservice.exe /install.");
        System.out.println("Starting oieservice");
        if (service != null) { administrator("start"); Services.start(home); }
        else NativeProcesses.start(home, home.resolve("oieserver"));
        return 0;
    }
    private static int stop(Path home, ObjectNode service, String verb) throws Exception {
        if (!running(home, service)) { System.out.println("oieservice is not running"); return 0; }
        System.out.println("Shutting down oieservice");
        if (service != null) { administrator(verb); Services.stop(home, false); }
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
    private static int install(Path home) throws Exception {
        administrator("install");
        if (Services.detect(home) != null) { System.out.println("The engine service is already installed"); return 0; }
        ObjectNode[] installed = {null};
        Services.install(home, record -> installed[0] = record);
        record(home, installed[0]);
        System.out.println("Installed and started the engine service");
        return 0;
    }
    private static int uninstall(Path home, ObjectNode service) throws Exception {
        administrator("uninstall");
        if (Services.detect(home) == null) { System.out.println("The engine service is not installed"); return 0; }
        Services.stop(home, true);
        record(home, null);
        System.out.println("Removed the engine service");
        return 0;
    }
    private static void record(Path home, ObjectNode service) throws IOException {
        Path file = home.resolve(Model.RECEIPT);
        if (!Files.isRegularFile(file)) return;
        ObjectNode receipt = (ObjectNode) Json.read(file);
        if (service == null) receipt.putNull("service"); else receipt.set("service", service);
        Json.write(file, receipt, false);
    }
}
