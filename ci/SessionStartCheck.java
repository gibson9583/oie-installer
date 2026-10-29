package org.openintegrationengine.installer;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Protocol checks use private files and never start an engine. */
public final class SessionStartCheck {
    private interface Checked { void run() throws Exception; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void fails(Checked action, String text) throws Exception {
        try { action.run(); throw new AssertionError("Expected: " + text); }
        catch (IOException expected) { require(expected.getMessage().contains(text), "Wrong failure: " + expected); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--caller-fixture")) { Thread.sleep(30_000); return; }
        Path job = Files.createTempDirectory("oie-session-protocol-");
        Path reply = Files.createFile(job.resolve("start-result.json")), home = job.resolve("engine");
        var caller = SessionStart.Caller.current();
        try {
            long pid = SessionStart.await(job, home, caller, request -> {
                try { SessionStart.respond(job, Path.of(request.path("sessionStart").asText()), home, null, (h, p) -> caller.pid()); }
                catch (IOException e) { throw new RuntimeException(e); }
            }, Duration.ofSeconds(2));
            require(pid == caller.pid(), "PID was lost across the handoff");
            Files.writeString(reply, "");
            fails(() -> SessionStart.await(job, home, caller, request -> {
                try { SessionStart.respond(job, home, home, null, (h, p) -> { throw new IOException("launch fixture failed"); }); }
                catch (IOException e) { throw new RuntimeException(e); }
            }, Duration.ofSeconds(2)), "launch fixture failed");
            Files.writeString(reply, "");
            fails(() -> SessionStart.respond(job, job.resolve("other"), home, null, (h, p) -> { throw new AssertionError("Wrong folder launched"); }), "different engine folder");
            fails(() -> SessionStart.await(job, home, new SessionStart.Caller(caller.pid(), caller.started() + 1), request -> {
                throw new AssertionError("Reused PID accepted");
            }, Duration.ofSeconds(1)), "session ended");
            fails(() -> SessionStart.await(job, home, new SessionStart.Caller(0, -1), request -> {
                throw new AssertionError("Missing caller accepted");
            }, Duration.ofSeconds(1)), "session ended");
            Process peer = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", Model.WINDOWS ? "java.exe" : "java").toString(),
                    "-cp", System.getProperty("java.class.path"), SessionStartCheck.class.getName(), "--caller-fixture")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                var owner = new SessionStart.Caller(peer.pid(), peer.info().startInstant().orElseThrow().toEpochMilli());
                require(owner.alive(), "Owned caller fixture did not start");
                peer.destroyForcibly(); require(peer.waitFor(5, TimeUnit.SECONDS), "Owned caller fixture did not exit");
                fails(() -> SessionStart.await(job, home, owner, request -> { throw new AssertionError("An exited caller requested startup"); }, Duration.ofSeconds(1)), "session ended");
            } finally {
                if (peer.isAlive()) { peer.destroyForcibly(); require(peer.waitFor(5, TimeUnit.SECONDS), "Retained owned caller fixture: " + peer.pid()); }
            }
            fails(() -> SessionStart.await(job, home, caller, request -> {}, Duration.ofMillis(150)), "did not respond");
            fails(() -> SessionStart.respond(job, home, home, null, (h, p) -> {
                throw new AssertionError("Expired request launched an engine");
            }), "did not respond");
            Files.writeString(reply, "");
            AtomicInteger launches = new AtomicInteger();
            AtomicReference<Throwable> starterFailure = new AtomicReference<>();
            CountDownLatch launching = new CountDownLatch(1);
            AtomicReference<Thread> responder = new AtomicReference<>();
            long slowPid = SessionStart.await(job, home, caller, request -> {
                responder.set(Thread.ofVirtual().start(() -> {
                    try { SessionStart.respond(job, home, home, null, (h, p) -> {
                        launches.incrementAndGet(); launching.countDown(); Thread.sleep(300); return caller.pid();
                    }); } catch (Throwable e) { starterFailure.set(e); launching.countDown(); }
                }));
                try { require(launching.await(2, TimeUnit.SECONDS), "Launch did not acquire its gate"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }, Duration.ofMillis(50));
            responder.get().join();
            require(starterFailure.get() == null && slowPid == caller.pid(), "Rollback raced an active launch");
            SessionStart.respond(job, home, home, null, (h, p) -> { launches.incrementAndGet(); return caller.pid(); });
            require(launches.get() == 1, "Duplicate event launched a second engine");
            Files.writeString(reply, "{\"ok\":");
            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            Thread writer = Thread.ofVirtual().start(() -> {
                try { Thread.sleep(150); Files.writeString(reply, "{\"ok\":true,\"pid\":" + caller.pid() + "}"); }
                catch (Throwable e) { writerFailure.set(e); }
            });
            require(SessionStart.await(job, home, caller, request -> {}, Duration.ofSeconds(2)) == caller.pid(), "Partial response was not retried");
            writer.join(); require(writerFailure.get() == null, "Reply writer failed");
            Files.writeString(reply, "{\"ok\":true,\"pid\":0}");
            fails(() -> SessionStart.await(job, home, caller, request -> {}, Duration.ofSeconds(1)), "invalid engine process ID");
            System.out.println("PASS: session PID, launch errors, wrong folder, caller loss/reuse, timeout fencing, slow launches, duplicate events and partial replies");
        } finally { FilesSupport.deleteTree(job); }
    }
}
