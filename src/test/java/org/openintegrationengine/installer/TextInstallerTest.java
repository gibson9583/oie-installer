package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Text-mode questions: defaults, invalid answers asked again, and the end of input. */
class TextInstallerTest {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private TextInstaller answers(String... lines) {
        BufferedReader in = new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
        return new TextInstaller(in, new PrintStream(output, true, StandardCharsets.UTF_8), null);
    }

    private String printed() { return output.toString(StandardCharsets.UTF_8); }

    @Test void yesUsesTheDefaultAndAsksAgainAfterOtherAnswers() throws IOException {
        TextInstaller text = answers("", "", "maybe", "N", "YES");
        assertTrue(text.yes("Service?", true));
        assertFalse(text.yes("Accept?", false));
        assertFalse(text.yes("Install?", true));
        assertTrue(printed().contains("Answer y or n."));
        assertTrue(text.yes("Again?", false));
    }

    @Test void askReturnsThePresetForAnEmptyAnswerAndTrimsInput() throws IOException {
        TextInstaller text = answers("", "  /opt/oie  ");
        assertEquals("/opt/openintegrationengine", text.ask("Engine folder", "/opt/openintegrationengine"));
        assertEquals("/opt/oie", text.ask("Engine folder", "/opt/openintegrationengine"));
        assertTrue(printed().contains("Engine folder [/opt/openintegrationengine]: "));
    }

    @Test void theEndOfInputStopsTheInstaller() {
        TextInstaller text = new TextInstaller(new BufferedReader(new StringReader("")), new PrintStream(output), null);
        assertThrows(EOFException.class, () -> text.ask("Engine folder", ""));
    }

    @Test void choicesAcceptOnlyListedNumbers() throws IOException {
        TextInstaller text = answers("0", "three", "4", "2", "");
        List<String> values = List.of("derby", "postgres", "mysql");
        assertEquals("postgres", text.choose("Database", values, List.of("Derby", "PostgreSQL", "MySQL"), 0));
        assertTrue(printed().contains("Enter a number from 1 to 3."));
        assertEquals("derby", text.choose("Database", values, List.of("Derby", "PostgreSQL", "MySQL"), 0));
    }

    @Test void portsMustBeFreeNumbersAndDifferFromTheOtherPort() throws IOException {
        try (ServerSocket busy = new ServerSocket(0)) {
            int free = Model.freePort(20000 + busy.getLocalPort() % 20000);
            TextInstaller text = answers("http", String.valueOf(busy.getLocalPort()), "8443", String.valueOf(free));
            assertEquals(free, text.port("HTTP port", 8080, 8443));
            String printed = printed();
            assertTrue(printed.contains("Enter a port number from 1 to 65535."));
            assertTrue(printed.contains("Port " + busy.getLocalPort() + " is in use or invalid."));
            assertTrue(printed.contains("HTTP and HTTPS ports must be different."));
        }
    }

    @Test void anEmptyAdministratorPasswordGeneratesOne() throws IOException {
        String password = answers("").administrator();
        assertEquals(24, password.length());
    }

    @Test void administratorPasswordsNeedFifteenCharactersAndAMatchingConfirmation() throws IOException {
        TextInstaller text = answers("short", "fifteen-chars-ok", "different-value", "fifteen-chars-ok", "fifteen-chars-ok");
        assertEquals("fifteen-chars-ok", text.administrator());
        assertTrue(printed().contains("Use at least 15 characters."));
        assertTrue(printed().contains("The passwords do not match."));
    }

    @Test void theLicenseCanBeSkippedToTheEndAndMustBeAccepted() throws IOException {
        TextInstaller declined = answers("a", "");
        assertFalse(declined.license());
        assertTrue(printed().contains("Mozilla Public License"));
        assertTrue(answers("", "a", "y").license());
    }
}
