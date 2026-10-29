package org.openintegrationengine.installer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Consumer;

/** The helper retains its transaction while the original session launches a process-mode engine. */
final class SessionStart {
    record Caller(long pid, long started) {
        static Caller current() throws IOException {
            var process = ProcessHandle.current();
            return new Caller(process.pid(), process.info().startInstant()
                    .orElseThrow(() -> new IOException("Cannot identify the installer session.")).toEpochMilli());
        }
        boolean alive() {
            if (pid <= 0 || started < 0) return false;
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .flatMap(process -> process.info().startInstant()).map(time -> time.toEpochMilli() == started).orElse(false);
        }
    }

    static long await(Path job, Path home, Caller caller, Consumer<ObjectNode> request, Duration timeout) throws Exception {
        Path reply = FilesSupport.safeTarget(job, "start-result.json");
        if (!caller.alive()) throw new IOException("The installer session ended before the engine could start.");
        request.accept(Json.object().put("sessionStart", home.toString()));
        long deadline = System.nanoTime() + timeout.toNanos();
        try (FileChannel channel = FileChannel.open(reply, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            while (true) {
                FileLock gate;
                try { gate = channel.tryLock(); }
                catch (OverlappingFileLockException startingInThisProcess) { gate = null; }
                if (gate != null) try (FileLock held = gate) {
                    JsonNode result = null;
                    try { result = read(channel); }
                    catch (JsonProcessingException incomplete) { /* Retry an incomplete reply. */ }
                    if (result != null && result.has("ok")) {
                        if (!result.path("ok").asBoolean()) throw new IOException(result.path("error").asText("The engine could not start in the user session."));
                        long pid = result.path("pid").asLong();
                        if (pid <= 0) throw new IOException("The installer session returned an invalid engine process ID.");
                        return pid;
                    }
                    String error = !caller.alive() ? "The installer session ended before the engine could start."
                            : System.nanoTime() >= deadline ? "The installer session did not respond to the engine start request." : null;
                    if (error != null) {
                        // Fence a late caller before rollback can remove its executable or settings.
                        write(channel, Json.object().put("ok", false).put("error", error));
                        throw new IOException(error);
                    }
                }
                // A caller holding the gate is inside launch. Let it finish or exit before rollback.
                Thread.sleep(100);
            }
        }
    }

    static void respond(Path job, Path requested, Path expected, Payload payload, Installer.ProcessStarter starter) throws IOException {
        if (!requested.equals(expected)) throw new IOException("The helper requested a different engine folder.");
        try (FileChannel channel = FileChannel.open(FilesSupport.safeTarget(job, "start-result.json"), StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock held = channel.lock()) {
            JsonNode previous = read(channel);
            if (previous != null && previous.has("ok")) {
                if (!previous.path("ok").asBoolean()) throw new IOException(previous.path("error").asText("The start request expired."));
                return; // A repeated event must not launch a second engine.
            }
            ObjectNode reply = Json.object();
            try { reply.put("ok", true).put("pid", starter.start(expected, payload)); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                reply.put("ok", false).put("error", failure.getMessage() == null ? failure.toString() : failure.getMessage());
            }
            write(channel, reply);
        }
    }
    // Use the locked descriptor: closing another descriptor can release POSIX record locks.
    private static JsonNode read(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size == 0) return null;
        if (size > 16_384) throw new IOException("The engine start reply is too large.");
        ByteBuffer bytes = ByteBuffer.allocate((int)size); channel.position(0);
        while (bytes.hasRemaining() && channel.read(bytes) >= 0) {}
        return Json.MAPPER.readTree(bytes.array());
    }
    private static void write(FileChannel channel, ObjectNode reply) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Json.MAPPER.writeValueAsBytes(reply)); channel.truncate(0); channel.position(0);
        while (bytes.hasRemaining()) channel.write(bytes);
    }
}
