package org.openintegrationengine.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

final class FilesSupport {
    static Path safeTarget(Path root, String name) throws IOException {
        if (name.isBlank() || name.contains("\\") || name.indexOf('\0') >= 0) throw new IOException("Unsafe archive path: " + name);
        Path relative = Path.of(name);
        if (relative.isAbsolute()) throw new IOException("Absolute archive path: " + name);
        if (relative.normalize().toString().isEmpty() || relative.normalize().toString().equals(".")) throw new IOException("Archive path names its destination root: " + name);
        for (Path part : relative) if (part.toString().equals("..")) throw new IOException("Unsafe archive path: " + name);
        Path base = root.toAbsolutePath().normalize(), target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IOException("Archive escapes its destination: " + name);
        for (Path p = target; p != null && p.startsWith(base); p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Refusing to follow symbolic link: " + p);
        return target;
    }
    static void copyTree(Path from, Path into) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(into.resolve(from.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // A backup must be complete. Refuse an unclear link rather than silently omitting data.
                if (!attrs.isRegularFile()) throw new IOException("Cannot make a complete backup of a link or special file: " + file);
                Files.copy(file, into.resolve(from.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
    static long size(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var paths = Files.walk(path)) {
            long total = 0;
            for (Path p : paths.toList()) if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) total += Files.size(p);
            return total;
        }
    }
    static Properties properties(Path path) throws IOException {
        Properties props = new Properties();
        if (Files.exists(path)) try (Reader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) { props.load(reader); }
        return props;
    }
    static void properties(Path path, Map<String, String> updates, Set<String> remove) throws IOException {
        List<String> out = new ArrayList<>(); Set<String> written = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.ISO_8859_1)) {
            String s = line.stripLeading();
            String key = s.isEmpty() || s.startsWith("#") || s.startsWith("!") ? "" : s.split("[\\s=:]", 2)[0];
            if (remove.contains(key)) continue;
            if (updates.containsKey(key)) {
                if (written.add(key)) out.add(key + " = " + propertyValue(updates.get(key)));
            } else out.add(line);
        }
        for (var entry : updates.entrySet()) if (written.add(entry.getKey())) out.add(entry.getKey() + " = " + propertyValue(entry.getValue()));
        Path target = safeTarget(path.getParent(), path.getFileName().toString());
        Path tmp = Files.createTempFile(target.getParent(), ".properties-", ".tmp");
        try {
            Json.privateFile(tmp);
            Files.writeString(tmp, String.join("\n", out) + "\n", StandardCharsets.ISO_8859_1);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    static String propertyValue(String value) throws IOException {
        if (value.contains("\n") || value.contains("\r")) throw new IOException("Property values cannot contain line breaks.");
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c > 126 || c < 32) escaped.append(String.format("\\u%04x", (int)c));
            else {
                if (c == '\\' || c == ' ' || c == '=' || c == ':' || c == '#' || c == '!') escaped.append('\\');
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
    static void executable(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
