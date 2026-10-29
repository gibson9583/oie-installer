package org.openintegrationengine.installer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.function.BooleanSupplier;

/** Confirms startup from the engine's own log and its HTTPS port; the installer never signs in. */
final class Readiness {
    static long offset(Path home) throws IOException { Path log = home.resolve("logs/mirth.log"); return Files.exists(log) ? Files.size(log) : 0; }
    static void started(Path home, long offset, String version, int port, BooleanSupplier alive) throws Exception {
        Instant deadline = Instant.now().plusSeconds(300); Path log = home.resolve("logs/mirth.log");
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(log)) try (RandomAccessFile input = new RandomAccessFile(log.toFile(), "r")) {
                input.seek(input.length() >= offset ? offset : 0); String line;
                while ((line = input.readLine()) != null) if (line.contains("server successfully started")) {
                    if (!line.contains("Open Integration Engine " + version + " ")) throw new IOException("A different engine version started: " + line);
                    try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 2000); return; }
                    catch (IOException ignored) {}
                }
            }
            if (!alive.getAsBoolean()) throw new IOException("The engine stopped during startup. See logs/oieserver.out.");
            Thread.sleep(2500);
        }
        throw new IOException("The engine did not start within five minutes. See logs/mirth.log.");
    }
}
