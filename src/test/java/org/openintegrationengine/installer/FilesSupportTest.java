package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** conf/mirth.properties editing: the installer writes settings in place and always removes the initial password. */
class FilesSupportTest {
    @TempDir Path folder;

    private Path properties(String... lines) throws IOException {
        Path file = folder.resolve("mirth.properties");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
        return file;
    }

    @Test void updatesKeysInPlaceAndKeepsEverythingElse() throws IOException {
        Path file = properties("# Engine settings", "http.port = 8080", "", "! old style comment", "https.port=8443", "database = derby", "smoke.marker = kept");
        FilesSupport.properties(file, new LinkedHashMap<>(Map.of("http.port", "18080", "https.port", "18443")), Set.of());
        assertEquals(List.of("# Engine settings", "http.port = 18080", "", "! old style comment", "https.port = 18443", "database = derby", "smoke.marker = kept"),
                Files.readAllLines(file, StandardCharsets.ISO_8859_1));
    }

    @Test void appendsMissingKeysOnce() throws IOException {
        Path file = properties("http.port = 8080", "http.port = 9090");
        FilesSupport.properties(file, new LinkedHashMap<>(Map.of("http.port", "18080", "keystore.storepass", "secret")), Set.of());
        assertEquals(List.of("http.port = 18080", "keystore.storepass = secret"), Files.readAllLines(file));
    }

    @Test void removesTheInitialPasswordInEveryPropertiesSyntax() throws IOException {
        Path file = properties("server.initialadminpassword = one", "server.initialadminpassword=two", "  server.initialadminpassword: three",
                "server.initialadminpassword\tfour", "# server.initialadminpassword = comment stays", "server.initialadminpasswordx = other key stays");
        FilesSupport.properties(file, Map.of(), Set.of("server.initialadminpassword"));
        assertEquals(List.of("# server.initialadminpassword = comment stays", "server.initialadminpasswordx = other key stays"), Files.readAllLines(file));
        assertNull(FilesSupport.properties(file).getProperty("server.initialadminpassword"));
    }

    @Test void valuesRoundTripThroughJavaProperties() throws IOException {
        Random random = new Random(42);
        String alphabet = "abcXYZ019 =:#!\\\t\"'%$/{}é€\u0000\u007f";
        for (int i = 0; i < 500; i++) {
            StringBuilder value = new StringBuilder();
            for (int n = random.nextInt(30); n > 0; n--) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            Properties loaded = new Properties();
            loaded.load(new StringReader("key = " + FilesSupport.propertyValue(value.toString())));
            assertEquals(value.toString(), loaded.getProperty("key"), "value " + i);
        }
    }

    @Test void valuesWithLineBreaksAreRejected() {
        assertThrows(IOException.class, () -> FilesSupport.propertyValue("a\nb"));
        assertThrows(IOException.class, () -> FilesSupport.propertyValue("a\rb"));
    }

    @Test void archivePathsStayInsideTheirRoot() throws IOException {
        assertEquals(folder.resolve("conf/mirth.properties"), FilesSupport.safeTarget(folder, "conf/mirth.properties"));
        for (String unsafe : List.of("", " ", "../escape", "conf/../../escape", Model.WINDOWS ? "C:/Windows" : "/etc/passwd", "conf\\windows", "nul\0byte", ".", "./"))
            assertThrows(IOException.class, () -> FilesSupport.safeTarget(folder, unsafe), unsafe);
    }

    @Test @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Creating links needs a Windows privilege")
    void archivePathsDoNotFollowLinks() throws IOException {
        Path outside = Files.createDirectories(folder.resolve("outside"));
        Path root = Files.createDirectories(folder.resolve("root"));
        Files.createSymbolicLink(root.resolve("conf"), outside);
        assertThrows(IOException.class, () -> FilesSupport.safeTarget(root, "conf/mirth.properties"));
    }

    @Test void shellQuotingSurvivesQuotes() {
        assertEquals("'/opt/it'\\''s here'", FilesSupport.quote("/opt/it's here"));
    }
}
