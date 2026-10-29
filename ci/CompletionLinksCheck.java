package org.openintegrationengine.installer;

import java.nio.file.*;
import java.util.*;

/** Checks completion links against deployed files, without creating or starting an engine. */
public final class CompletionLinksCheck {
    private static final String BASE = "https://localhost:18443";
    private static void requireUrl(Path home, String expected, String reason) throws Exception {
        String actual = Installer.webClientUrl(home, BASE);
        if (!Objects.equals(expected, actual)) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) throws Exception {
        Path home = Files.createDirectory(Path.of(args[0]));
        try {
            requireUrl(home, null, "An omitted client has no link");
            Path bundled = Files.createDirectories(home.resolve("extensions/websupport")).resolve("oie-webadmin.war");
            Files.writeString(bundled, "Bundled WAR fixture; never executed");
            Files.writeString(home.resolve(Model.RECEIPT), "{\"pluginSelection\":{\"websupport\":true}}");
            requireUrl(home, null, "Selecting Web Support does not prove its WAR was deployed");

            Path deployed = Files.createDirectories(home.resolve("webapps")).resolve("oie-webadmin.war");
            Files.copy(bundled, deployed);
            requireUrl(home, BASE + "/oie-webadmin/", "A new deployed client has a link");
            Files.writeString(home.resolve(Model.RECEIPT), "{\"pluginSelection\":{\"websupport\":false},\"plugins\":{\"websupport\":{\"version\":\"1.2.1\"}}}");
            requireUrl(home, BASE + "/oie-webadmin/", "An unselected client retained during upgrade or reinstall has a link");

            Files.delete(deployed);
            requireUrl(home, null, "A removed client has no link despite a stale receipt and bundled WAR");
            Files.createDirectory(deployed);
            requireUrl(home, null, "A directory named as the WAR is not a deployed client");
            Files.delete(deployed);
            Files.copy(bundled, deployed);
            System.out.println("PASS: client links follow deployed files, including retained and removed clients");

            Path properties = Files.createDirectories(home.resolve("conf")).resolve("mirth.properties");
            Map<String, String> contexts = new LinkedHashMap<>();
            contexts.put("", "/oie-webadmin/");
            contexts.put("/", "/oie-webadmin/");
            contexts.put("engine", "/engine/oie-webadmin/");
            contexts.put("/engine/", "/engine/oie-webadmin/");
            contexts.put("nested/context/", "/nested/context/oie-webadmin/");
            contexts.put("/engine path/", "/engine%20path/oie-webadmin/");
            contexts.put("/café/", "/caf%C3%A9/oie-webadmin/");
            contexts.put("/engine%20path/", "/engine%2520path/oie-webadmin/");
            contexts.put("/engine;path/", "/engine%3Bpath/oie-webadmin/");
            contexts.put("/engine?query/", "/engine%3Fquery/oie-webadmin/");
            contexts.put("/engine#fragment/", "/engine%23fragment/oie-webadmin/");
            contexts.put("/engine//", "/engine//oie-webadmin/");
            for (var entry : contexts.entrySet()) {
                Properties configuration = new Properties();
                configuration.setProperty("http.contextpath", entry.getKey());
                try (var output = Files.newOutputStream(properties)) { configuration.store(output, "Owned test configuration"); }
                requireUrl(home, BASE + entry.getValue(), "Configured context " + entry.getKey());
            }
            Files.writeString(properties, "https.port = 18443\n");
            requireUrl(home, BASE + "/oie-webadmin/", "An absent context property uses the root");
            System.out.println("PASS: engine context normalization and URL quoting (13 configurations)");

            Map<String, String> configurations = new LinkedHashMap<>();
            configurations.put("http.contextpath = /  \n", "/oie-webadmin/");
            configurations.put("http.contextpath = /engine  \n", "/engine/oie-webadmin/");
            configurations.put("http.contextpath = /first\nhttp.contextpath = /second\n", "/first/oie-webadmin/");
            configurations.put("http.contextpath = /en\\\n gine/  \n", "/engine/oie-webadmin/");
            configurations.put("http.contextpath = /en\\\n # ignored comment\n ! also ignored\n gine/\n", "/engine/oie-webadmin/");
            configurations.put("http.contextpath = /engine\\u0020\n", "/engine%20/oie-webadmin/");
            configurations.put("http.contextpath = \\u0020/engine\\u0020\n", "/%20/engine%20/oie-webadmin/");
            configurations.put("http.contextpath = /engine\\t\n", "/engine%09/oie-webadmin/");
            configurations.put("http.contextpath = /engine\\ \n", "/oie-webadmin/");
            configurations.put("http.contextpath = /unfinished\\\n", "/oie-webadmin/");
            configurations.put("http.contextpath = /first\nhttp.contextpath = ${sys:engine.context}\n", "/first/oie-webadmin/");
            configurations.put("http.contextpath = /first,/second\n", "/first,/second/oie-webadmin/");
            configurations.put("http.contextpath = \"/engine path/\"\n", "/%22/engine%20path/%22/oie-webadmin/");
            for (var entry : configurations.entrySet()) {
                Files.writeString(properties, entry.getKey());
                requireUrl(home, BASE + entry.getValue(), "Literal engine property parsing: " + entry.getKey());
            }
            for (String interpolated : List.of("${sys:engine.context}", "${env:ENGINE_CONTEXT}", "/${other.property}/", "\\u0024{sys:engine.context}")) {
                Files.writeString(properties, "http.contextpath = " + interpolated + "\n");
                requireUrl(home, null, "Runtime interpolation must not produce a guessed link");
            }
            for (String unsupportedEscape : List.of("/engine\\,path/", "/engine\\x/", "/engine\\ /", "/engine\\b/")) {
                Files.writeString(properties, "http.contextpath = " + unsupportedEscape + "\n");
                requireUrl(home, null, "Nonstandard value escapes must not produce a guessed link");
            }
            for (String include : List.of("include", "INCLUDEOPTIONAL")) {
                Files.writeString(properties, include + " = /not-read-by-installer.properties\nhttp.contextpath = /engine\n");
                requireUrl(home, null, "Included settings can supply the engine's first context value");
            }
            System.out.println("PASS: literal whitespace, first value, escapes, continuations and safe configuration fallback");
        } finally {
            // Independent cleanup also runs if the implementation under test fails.
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
