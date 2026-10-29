package org.openintegrationengine.installer;

import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.function.BiConsumer;

final class Archives {
    static final Set<String> KEEP = Set.of("conf", "appdata", "logs", "custom-extensions", "server-launcher-lib", "backups");
    static final Set<String> ENGINE_CONF = Set.of("conf/base_includes.vmoptions", "conf/default_modules.vmoptions");
    record Layout(Set<String> top, Set<String> extensions, int count) {}
    static Layout inspect(Payload payload) throws IOException {
        if (!payload.nativeEngine()) return inspect(payload.archive());
        Set<String> top = new TreeSet<>(), extensions = new TreeSet<>(); int count = 0;
        for (var fields = payload.manifest.path("files").fieldNames(); fields.hasNext();) {
            String name = fields.next(); if (!name.startsWith("engine/")) continue;
            String relative = name.substring(7); FilesSupport.safeTarget(payload.engine(), relative); String[] parts = relative.split("/");
            top.add(parts[0]); if (parts[0].equals("extensions") && parts.length > 1) extensions.add(parts[1]); count++;
        }
        if (!top.contains("server-lib") || !top.contains("conf")) throw new IOException("The staged engine is incomplete.");
        return new Layout(top, extensions, count);
    }
    static List<String> extract(Payload payload, Path home, Path defaults, int total, BiConsumer<String, Double> progress) throws IOException {
        if (!payload.nativeEngine()) return extract(payload.archive(), home, defaults, total, progress);
        List<String> changed = new ArrayList<>(); int count = 0;
        for (JsonNode directory : payload.manifest.path("directories")) Files.createDirectories(FilesSupport.safeTarget(home, directory.asText()));
        for (var fields = payload.manifest.path("files").fieldNames(); fields.hasNext();) {
            String name = fields.next(); if (!name.startsWith("engine/")) continue;
            String relative = name.substring(7); Path original = FilesSupport.safeTarget(home, relative), target = original;
            boolean keep = defaults != null && relative.startsWith("conf/") && Files.exists(target) && !ENGINE_CONF.contains(relative);
            if (keep) target = FilesSupport.safeTarget(defaults, relative);
            Files.createDirectories(target.getParent()); Files.copy(payload.safe(name), target, StandardCopyOption.REPLACE_EXISTING);
            if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                int mode = payload.manifest.path("modes").path(name).asInt(0644);
                Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString((mode & 0100) != 0 ? "rwxr-xr-x" : "rw-r--r--"));
            }
            if (keep) { if (Files.mismatch(target, original) == -1) Files.delete(target); else changed.add(relative); }
            if (++count % 40 == 0) progress.accept(count + " of " + total + " files", (double)count / Math.max(1, total));
        }
        return changed;
    }
    static TarArchiveInputStream tar(Path path) throws IOException {
        return new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(path))));
    }
    private static String relative(TarArchiveEntry entry) throws IOException {
        String name = entry.getName();
        if (name.equals("oie") || name.equals("oie/")) return "";
        if (!name.startsWith("oie/")) throw new IOException("Unexpected engine archive path: " + name);
        String rel = name.substring(4);
        FilesSupport.safeTarget(Path.of("/archive-check"), rel);
        if (!entry.isDirectory() && !entry.isFile() || entry.isSymbolicLink() || entry.isLink())
            throw new IOException("The engine archive contains a link or special file: " + name);
        return rel;
    }
    static Layout inspect(Path path) throws IOException {
        Set<String> top = new TreeSet<>(), extensions = new TreeSet<>(); int count = 0;
        try (var input = tar(path)) {
            for (TarArchiveEntry entry; (entry = input.getNextEntry()) != null;) {
                String rel = relative(entry); count++; if (rel.isEmpty()) continue;
                String[] parts = rel.split("/"); top.add(parts[0]);
                if (parts[0].equals("extensions") && parts.length > 1) extensions.add(parts[1]);
            }
        }
        if (!top.contains("server-lib") || !top.contains("conf")) throw new IOException("The engine archive is incomplete.");
        return new Layout(top, extensions, count);
    }
    static List<String> extract(Path path, Path home, Path defaults, int total, BiConsumer<String, Double> progress) throws IOException {
        List<String> changed = new ArrayList<>(); int count = 0;
        try (var input = tar(path)) {
            for (TarArchiveEntry entry; (entry = input.getNextEntry()) != null;) {
                String rel = relative(entry); if (rel.isEmpty()) continue;
                Path target = FilesSupport.safeTarget(home, rel);
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                boolean keep = defaults != null && rel.startsWith("conf/") && Files.exists(target) && !ENGINE_CONF.contains(rel);
                Path original = target;
                if (keep) target = FilesSupport.safeTarget(defaults, rel);
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) { input.transferTo(out); }
                if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                    var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                    if ((entry.getMode() & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
                    if ((entry.getMode() & 0044) != 0) { permissions.add(PosixFilePermission.GROUP_READ); permissions.add(PosixFilePermission.OTHERS_READ); }
                    if ((entry.getMode() & 0011) != 0) { permissions.add(PosixFilePermission.GROUP_EXECUTE); permissions.add(PosixFilePermission.OTHERS_EXECUTE); }
                    Files.setPosixFilePermissions(target, permissions);
                }
                if (keep) { if (Files.mismatch(target, original) == -1) Files.delete(target); else changed.add(rel); }
                if (++count % 40 == 0) progress.accept(count + " of about " + total + " files", (double)count / Math.max(1, total));
            }
        }
        return changed;
    }
    static void retire(Path home, Layout layout, Path backup) throws IOException {
        for (String top : layout.top()) if (!KEEP.contains(top) && !top.equals("extensions")) moveIfPresent(home.resolve(top), backup.resolve(top));
        for (String extension : layout.extensions()) moveIfPresent(home.resolve("extensions").resolve(extension), backup.resolve("extensions").resolve(extension));
        // These files belong to the old install4j distribution, or the earlier WinSW service wrapper, and are not part of the native engine image.
        for (String legacy : List.of(".install4j", "jre", "oieserver.ps1", "oiemanager", "oiemanager.exe", "uninstall", "uninstall.exe", "uninstall.dat", "oie-service.exe", "oie-service.xml"))
            if (!layout.top().contains(legacy)) moveIfPresent(home.resolve(legacy), backup.resolve(legacy));
    }
    static void moveIfPresent(Path from, Path into) throws IOException {
        if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(into, LinkOption.NOFOLLOW_LINKS)) throw new IOException("A backup already exists: " + into);
            Files.createDirectories(into.getParent()); Files.move(from, into);
        }
    }
    private static Path pluginPath(Payload.Plugin plugin, ZipArchiveEntry entry, Path stage) throws IOException {
        String name = entry.getName(); Path path = FilesSupport.safeTarget(stage, name);
        if (!name.equals(plugin.id() + "/") && !name.startsWith(plugin.id() + "/")) throw new IOException("Unexpected add-on path: " + name);
        int kind = entry.getUnixMode() & 0170000;
        if (entry.isUnixSymlink() || kind != 0 && kind != 0100000 && kind != 0040000) throw new IOException("Add-on contains a link or special file: " + name);
        return path;
    }
    static void verifyPlugins(Payload payload) throws IOException {
        for (var plugin : payload.plugins) {
            try (var zip = ZipFile.builder().setPath(payload.safe(plugin.archive())).get()) {
                Set<String> seen = new HashSet<>(); boolean descriptor = false;
                var entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement(); Path path = pluginPath(plugin, entry, Path.of("/archive-check"));
                    if (!seen.add(path.toString())) throw new IOException("Duplicate add-on path: " + entry.getName());
                    descriptor |= entry.getName().equals(plugin.id() + "/plugin.xml") && !entry.isDirectory();
                }
                if (!descriptor) throw new IOException(plugin.name() + " has no plugin.xml.");
            }
        }
    }
    static void plugin(Payload payload, Payload.Plugin plugin, Path home, Path backup) throws IOException {
        Path extensions = FilesSupport.safeTarget(home, "extensions"); Files.createDirectories(extensions);
        Path target = FilesSupport.safeTarget(extensions, plugin.id());
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || backup == null)) throw new IOException("Cannot replace add-on without a safe backup: " + target);
        Path stage = Files.createTempDirectory(home, ".oie-addon-");
        try {
            try (var zip = ZipFile.builder().setPath(payload.safe(plugin.archive())).get()) {
                var entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement(); Path path = pluginPath(plugin, entry, stage);
                    if (entry.isDirectory()) Files.createDirectories(path);
                    else {
                        Files.createDirectories(path.getParent());
                        try (InputStream input = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) { input.transferTo(out); }
                    }
                }
            }
            Path previous = backup == null ? null : backup.resolve(plugin.id());
            if (exists) moveIfPresent(target, previous);
            try { Files.move(stage.resolve(plugin.id()), target); }
            catch (IOException e) {
                if (exists) try { Files.move(previous, target); } catch (IOException restore) { e.addSuppressed(restore); }
                throw e;
            }
        } finally { FilesSupport.deleteTree(stage); }
    }
}
