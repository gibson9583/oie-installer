package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The Done screen opens the deployed OIE Web Client under the engine's literal http.contextpath, as MirthWebServer serves it. */
class WebClientUrlTest {
    private static final String BASE = "https://localhost:8443";
    @TempDir Path home;

    @BeforeEach void engine() throws IOException {
        Files.createDirectories(home.resolve("webapps"));
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("webapps/oie-webadmin.war"), "");
    }

    private String url(String... lines) throws IOException {
        Files.writeString(home.resolve("conf/mirth.properties"), String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
        return Installer.webClientUrl(home, BASE);
    }

    @Test void withoutTheWebClientThereIsNoAddress() throws IOException {
        Files.delete(home.resolve("webapps/oie-webadmin.war"));
        assertNull(url("http.contextpath = /"));
    }

    @Test void theDefaultContextIsTheRoot() throws IOException {
        assertEquals(BASE + "/oie-webadmin/", url("http.port = 8080"));
        assertEquals(BASE + "/oie-webadmin/", url("http.contextpath = /"));
        assertEquals(BASE + "/oie-webadmin/", url("http.contextpath ="));
    }

    @Test void contextsGetOneLeadingSlashAndLoseOneTrailingSlash() throws IOException {
        assertEquals(BASE + "/engine/oie-webadmin/", url("http.contextpath = engine"));
        assertEquals(BASE + "/engine/oie-webadmin/", url("http.contextpath = /engine/"));
        assertEquals(BASE + "/a/b/oie-webadmin/", url("http.contextpath=/a/b"));
    }

    @Test void specialCharactersAreQuoted() throws IOException {
        assertEquals(BASE + "/100%25/oie-webadmin/", url("http.contextpath = /100%"));
        assertEquals(BASE + "/a%3Fb%23c/oie-webadmin/", url("http.contextpath = /a?b#c"));
        assertEquals(BASE + "/a%3Bb/oie-webadmin/", url("http.contextpath = /a;b"));
        assertEquals(BASE + "/%C3%A9/oie-webadmin/", url("http.contextpath = /\\u00e9"));
    }

    @Test void theFirstValueAndContinuationLinesFollowTheEngine() throws IOException {
        assertEquals(BASE + "/first/oie-webadmin/", url("http.contextpath = /first", "http.contextpath = /second"));
        assertEquals(BASE + "/joined/oie-webadmin/", url("http.contextpath = /jo\\", "    ined"));
        assertEquals(BASE + "/oie-webadmin/", url("# http.contextpath = /commented", "! http.contextpath = /bang"));
    }

    @Test void dynamicContextsUseTheEngineAddress() throws IOException {
        assertNull(url("http.contextpath = ${env:OIE_CONTEXT}"));
        assertNull(url("include = other.properties", "http.contextpath = /engine"));
        assertNull(url("http.contextpath = /odd\\qescape"));
    }
}
