package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

final class Payload {
    static final List<String> IDS = List.of("websupport", "rbac", "simple-channel-history");
    final Path dir;
    final JsonNode manifest;
    final List<Plugin> plugins;
    record Plugin(String id, String name, String version, String archive, long size, String sha256, JsonNode metadata) {}

    Payload(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        manifest = Json.read(this.dir.resolve("manifest.json"));
        if (!manifest.path("version").asText().matches("\\d+\\.\\d+\\.\\d+")) throw new IOException("Invalid payload engine version.");
        safe(manifest.path("archive").asText());
        plugins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode p : manifest.path("plugins")) {
            String id = p.path("id").asText(), archive = p.path("archive").asText();
            boolean compatible = false;
            for (JsonNode v : p.path("engineVersions")) compatible |= version().equals(v.asText());
            if (!IDS.contains(id) || !seen.add(id) || !compatible
                    || !archive.equals("plugins/" + id + "-" + p.path("version").asText() + ".zip")
                    || !manifest.path("files").path(archive).asText().equals(p.path("sha256").asText()))
                throw new IOException("Invalid or incompatible bundled add-on: " + id);
            plugins.add(new Plugin(id, p.path("name").asText(), p.path("version").asText(), archive,
                    p.path("size").asLong(), p.path("sha256").asText(), p));
        }
        if (seen.size() != IDS.size()) throw new IOException("The payload must bundle all three add-ons.");
    }
    static Payload load() throws Exception {
        String override = System.getProperty("oie.payload", System.getenv("OIE_INSTALLER_PAYLOAD"));
        if (override != null) return new Payload(Path.of(override));
        Path code = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (Path path : List.of(code.resolveSibling("payload"), Path.of("payload"), Path.of("../payload")))
            if (Files.isRegularFile(path.resolve("manifest.json"))) return new Payload(path);
        throw new IOException("No bundled engine payload was found. Run build.py before opening the installer.");
    }
    String version() { return manifest.path("version").asText(); }
    boolean nativeEngine() { return manifest.path("format").asInt() == 2; }
    /** The -with-JRE media bundle a Java runtime for the engine; the others use an installed Java 17 or later. */
    boolean bundledJava() { return manifest.path("bundledJava").asBoolean(true); }
    Path engine() throws IOException { return safe("engine"); }
    Path engineJava(Path home) throws IOException { return FilesSupport.safeTarget(home, manifest.path("engineJava").asText()); }
    Path launcher(Path home, String role) throws IOException { return FilesSupport.safeTarget(home, manifest.path("engineLaunchers").path(role).asText()); }
    String sourceHash() { return nativeEngine() ? manifest.path("sourceArchive").path("sha256").asText() : manifest.path("files").path(manifest.path("archive").asText()).asText(); }
    Path archive() throws IOException { return safe(manifest.path("archive").asText()); }
    long size() { return manifest.path("size").asLong(); }
    long needed() { return (size() + plugins.stream().mapToLong(Plugin::size).sum()) * 26 / 10; }
    Path safe(String name) throws IOException { return FilesSupport.safeTarget(dir, name); }
    void verify(Consumer<String> log) throws Exception {
        JsonNode files = manifest.path("files");
        if (!files.isObject() || !nativeEngine() && !files.has(manifest.path("archive").asText())) throw new IOException("Payload has no pinned engine files.");
        for (var it = files.fields(); it.hasNext();) {
            var f = it.next(); Path path = safe(f.getKey());
            log.accept("Checking SHA-256 of " + f.getKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !sha256(path).equals(f.getValue().asText()))
                throw new IOException("Missing or damaged payload: " + f.getKey());
        }
        if (nativeEngine()) {
            if (!files.has("engine/" + manifest.path("engineJava").asText())) throw new IOException("The engine runtime has no pinned hash.");
            for (String role : List.of("oieserver", "oieservice", "oiecommand"))
                if (!files.has("engine/" + manifest.path("engineLaunchers").path(role).asText())) throw new IOException("A native launcher has no pinned hash: " + role);
        } else for (JsonNode name : manifest.path("launcher").path("files"))
            if (!files.has("launcher/" + name.asText())) throw new IOException("The launcher has no pinned hash.");
    }
    static String sha256(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024]; int n;
            while ((n = input.read(buffer)) >= 0) hash.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
