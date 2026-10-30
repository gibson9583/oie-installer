package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Settings that the installer refuses before it changes anything. */
class InstallerValidateTest {
    @TempDir Path home;
    private int http, https;

    @BeforeEach void ports() { http = Model.freePort(21080); https = Model.freePort(http + 1); }

    private ObjectNode plan() {
        return Json.object().put("installDir", home.toString()).put("httpPort", http).put("httpsPort", https)
                .put("database", "derby").put("adminPassword", "a-long-enough-password");
    }

    private void validate(ObjectNode plan) throws Exception {
        Installer.validate(Model.Plan.from(plan), new Model.Destination(home, "new", null, 0, 0));
    }

    private String refused(ObjectNode plan) {
        return assertThrows(IllegalArgumentException.class, () -> validate(plan)).getMessage();
    }

    @Test void aCompleteNewInstallationIsAccepted() throws Exception {
        validate(plan());
    }

    @Test void theEngineFolderMustBeAbsolute() {
        assertEquals("Select an absolute engine path.", refused(plan().put("installDir", "relative/oie")));
    }

    @Test void theAdministratorPasswordNeedsFifteenCharacters() {
        assertTrue(refused(plan().put("adminPassword", "fourteen-chars")).contains("at least 15 characters"));
        // Characters, not bytes or UTF-16 units: 15 emoji pass.
        assertDoesNotThrow(() -> validate(plan().put("adminPassword", "🔒".repeat(15))));
    }

    @Test void settingsCannotContainLineBreaks() {
        assertEquals("Settings cannot contain line breaks.", refused(plan().put("adminPassword", "a-long-enough-password\nhttp.port=1")));
        assertEquals("Settings cannot contain line breaks.", refused(plan().put("database", "postgres").put("databaseUrl", "jdbc:postgresql://db/x\r")));
    }

    @Test void portsMustDifferAndBeFree() throws Exception {
        assertEquals("HTTP and HTTPS ports must be different.", refused(plan().put("httpsPort", http)));
        try (var busy = new java.net.ServerSocket(https)) {
            assertEquals("The selected network ports are no longer available.", refused(plan()));
        }
    }

    @Test void externalDatabasesNeedAUrlAndAKnownType() {
        assertEquals("Enter the database JDBC URL.", refused(plan().put("database", "postgres")));
        assertEquals("Select a supported database.", refused(plan().put("database", "sqlite")));
    }

    @Test void aSelectedJavaMustBeJava17OrLater() {
        assertTrue(refused(plan().put("javaCmd", home.resolve("missing/java").toString())).contains("Java 17 or later"));
    }

    @Test void reviewedChoicesMustStillMatchTheFolder() {
        assertTrue(refused(plan().put("expectedMode", "upgrade")).contains("The destination changed since Review"));
        assertTrue(refused(plan().put("expectedVersion", "4.6.0")).contains("The installed engine changed since Review"));
    }
}
