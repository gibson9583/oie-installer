package org.openintegrationengine.installer;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

final class Readiness {
    record Response(int status, String body, List<String> cookies) {}
    /** Certificate exception is scoped to this individual loopback connection, never global. */
    private static Response request(String base, String path, String method, String body, String accept, List<String> cookies) throws Exception {
        URI uri = URI.create(base + path);
        if (!uri.getScheme().equals("https") || !Set.of("localhost", "127.0.0.1").contains(uri.getHost())) throw new IOException("Readiness checks require a local HTTPS origin.");
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String type) {}
            public void checkServerTrusted(X509Certificate[] chain, String type) {}
        }}, null);
        HttpsURLConnection c = (HttpsURLConnection)uri.toURL().openConnection();
        try {
            c.setSSLSocketFactory(context.getSocketFactory()); c.setHostnameVerifier((host, session) -> host.equals("localhost") || host.equals("127.0.0.1"));
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setRequestMethod(method);
            c.setRequestProperty("X-Requested-With", "OIE-Installer"); c.setRequestProperty("Accept", accept);
            if (!cookies.isEmpty()) c.setRequestProperty("Cookie", String.join("; ", cookies));
            if (body != null) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", path.endsWith("_login") ? "application/x-www-form-urlencoded" : "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = c.getResponseCode(); InputStream stream = status < 400 ? c.getInputStream() : c.getErrorStream();
            String text = ""; if (stream != null) try (stream) { text = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
            List<String> set = new ArrayList<>(); c.getHeaderFields().forEach((key, values) -> { if (key != null && key.equalsIgnoreCase("Set-Cookie")) values.forEach(v -> set.add(v.split(";", 2)[0])); });
            return new Response(status, text, set);
        } finally { c.disconnect(); }
    }
    static String login(String base, String password, String expectedVersion, BooleanSupplier alive) throws Exception {
        Instant deadline = Instant.now().plusSeconds(300);
        while (Instant.now().isBefore(deadline)) {
            Response login;
            try { login = request(base, "/api/users/_login", "POST", "username=admin&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), "application/json", List.of()); }
            catch (IOException e) { login = null; }
            if (login != null) {
                if (login.status() == 401 || login.status() == 200 && !login.body().contains("SUCCESS")) throw new IOException("The engine rejected the initial administrator password.");
                if (login.status() == 200) {
                    try {
                        Response version = request(base, "/api/server/version", "GET", null, "text/plain", login.cookies());
                        String text = version.body().strip().replace("\"", "");
                        if (version.status() == 200) {
                            if (!text.equals(expectedVersion)) throw new IOException("The running engine reports " + text + ", expected " + expectedVersion);
                            return text;
                        }
                    } finally { try { request(base, "/api/users/_logout", "POST", "", "application/json", login.cookies()); } catch (Exception ignored) {} }
                }
            }
            if (!alive.getAsBoolean()) throw new IOException("The engine stopped during startup. See logs/oieserver.out.");
            Thread.sleep(2500);
        }
        throw new IOException("The engine did not accept the first administrator sign-in within five minutes.");
    }
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
