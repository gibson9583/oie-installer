package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static JsonNode read(Path path) throws IOException { return MAPPER.readTree(path.toFile()); }
    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static void write(Path path, Object value) throws IOException { write(path, value, true); }
    /** Private for job files with credentials; receipts hold no secrets and stay readable. */
    static void write(Path path, Object value, boolean secret) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), ".oie-write-", ".tmp");
        try {
            if (secret) privateFile(tmp);
            else if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    static void privateFile(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
}
