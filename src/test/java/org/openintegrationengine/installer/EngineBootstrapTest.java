package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The engine command line that EngineBootstrap builds from the vmoptions files shipped with engine 4.7.0. */
class EngineBootstrapTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final String JAVA = WINDOWS ? "java.exe" : "java";
    @TempDir Path home;
    @TempDir Path elsewhere;

    @BeforeEach void engine() throws IOException {
        for (String name : List.of("oieserver.vmoptions", "oieservice.vmoptions", "conf/base_includes.vmoptions", "conf/default_modules.vmoptions", "conf/custom.vmoptions")) {
            try (InputStream shipped = getClass().getResourceAsStream("/engine-4.7.0/" + name)) {
                Files.createDirectories(home.resolve(name).getParent());
                Files.copy(Objects.requireNonNull(shipped, name), home.resolve(name));
            }
        }
        Files.writeString(home.resolve("oiecommand.vmoptions"), "-include-options conf/default_modules.vmoptions\n-include-options conf/custom.vmoptions\n");
    }

    private Path java(Path folder) throws IOException {
        Path java = folder.resolve("bin").resolve(JAVA);
        Files.createDirectories(java.getParent());
        Files.writeString(java, "");
        java.toFile().setExecutable(true);
        return java;
    }

    private void custom(String... lines) throws IOException {
        Path file = home.resolve("conf/custom.vmoptions");
        List<String> content = new ArrayList<>(Files.readAllLines(file));
        int marker = content.indexOf("# Add custom options above this comment.");
        content.addAll(marker, List.of(lines));
        Files.write(file, content);
    }

    private List<String> command(String role, Map<String, String> env, String... args) throws IOException {
        return EngineBootstrap.command(home, role, args, env, elsewhere.resolve("launcher-runtime"));
    }

    @Test void serverUsesTheShippedOptionsInIncludeOrder() throws IOException {
        Path java = java(elsewhere.resolve("jdk"));
        custom("-java-cmd " + java, "-Xmx2g");
        List<String> command = command("server", Map.of(), "extra");
        assertEquals(java.toString(), command.get(0));
        List<String> expected = new ArrayList<>(List.of("-server", "-Xmx256m", "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true", "--add-modules=java.sql.rowset"));
        assertEquals(expected, command.subList(1, 6));
        assertTrue(command.contains("--add-opens=java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED"));
        // Custom options come last, so they override the defaults.
        assertTrue(command.indexOf("-Xmx2g") > command.indexOf("-Xmx256m"));
        int cp = command.indexOf("-cp");
        assertEquals(home.resolve("mirth-server-launcher.jar") + File.pathSeparator + home.resolve("mirth-server-launcher.jar"), command.get(cp + 1));
        assertEquals(List.of("com.mirth.connect.server.launcher.MirthLauncher", "extra"), command.subList(cp + 2, command.size()));
    }

    @Test void serviceAndCommandRolesUseTheirOwnFiles() throws IOException {
        java(elsewhere.resolve("jdk"));
        Map<String, String> env = Map.of("JAVA_HOME", elsewhere.resolve("jdk").toString());
        Files.writeString(home.resolve("oieservice.vmoptions"), "-Dservice.marker=1\n", StandardOpenOption.APPEND);
        assertTrue(command("service", env).contains("-Dservice.marker=1"));
        assertFalse(command("server", env).contains("-Dservice.marker=1"));
        List<String> cli = command("command", env, "-a", "https://localhost:8443");
        assertFalse(cli.contains("-server"), "oiecommand does not read base_includes.vmoptions");
        assertTrue(cli.contains(home.resolve("mirth-cli-launcher.jar") + File.pathSeparator + home.resolve("mirth-cli-launcher.jar")));
        assertEquals(List.of("com.mirth.connect.cli.launcher.CommandLineLauncher", "-a", "https://localhost:8443"), cli.subList(cli.size() - 3, cli.size()));
    }

    @Test void relativeAndQuotedJavaCommandsResolveAgainstTheEngineFolder() throws IOException {
        Path java = java(home.resolve("my java"));
        custom("-java-cmd \"my java/bin/" + JAVA + "\"");
        assertEquals(java.toString(), command("server", Map.of()).get(0));
    }

    @Test void oieJavaPathOverridesTheOptions() throws IOException {
        java(elsewhere.resolve("configured"));
        Path override = java(elsewhere.resolve("override"));
        custom("-java-cmd " + elsewhere.resolve("configured/bin/" + JAVA));
        assertEquals(override.toString(), command("server", Map.of("OIE_JAVA_PATH", override.toString())).get(0));
    }

    @Test void archiveEnginesUseJavaHomeThenPathThenTheLauncherRuntime() throws IOException {
        Path javaHome = java(elsewhere.resolve("home"));
        Path onPath = java(elsewhere.resolve("path"));
        Path launcher = java(elsewhere.resolve("launcher-runtime"));
        String path = elsewhere.resolve("missing") + File.pathSeparator + onPath.getParent();
        assertEquals(javaHome.toString(), command("server", Map.of("JAVA_HOME", elsewhere.resolve("home").toString(), "PATH", path)).get(0));
        assertEquals(onPath.toString(), command("server", Map.of("JAVA_HOME", elsewhere.resolve("missing").toString(), "PATH", path)).get(0));
        assertEquals(launcher.toString(), command("server", Map.of()).get(0));
    }

    @Test void environmentVariablesAndOieHomeExpand() throws IOException {
        java(elsewhere.resolve("jdk"));
        custom("-java-cmd ${JDK}/bin/" + JAVA, "-Dlogs=${OIE_HOME}/logs", "-Dunset=${NOT_SET}");
        List<String> command = command("server", Map.of("JDK", elsewhere.resolve("jdk").toString()));
        assertTrue(command.contains("-Dlogs=" + home + "/logs"));
        assertTrue(command.contains("-Dunset="));
    }

    @Test void classpathDirectivesPrependAppendAndReplace() throws IOException {
        java(elsewhere.resolve("jdk"));
        Map<String, String> env = Map.of("JAVA_HOME", elsewhere.resolve("jdk").toString());
        custom("-classpath/a after.jar", "-classpath/p \"before.jar\"");
        List<String> command = command("server", env);
        String jar = home.resolve("mirth-server-launcher.jar").toString();
        assertEquals(jar + File.pathSeparator + "before.jar" + File.pathSeparator + jar + File.pathSeparator + "after.jar", command.get(command.indexOf("-cp") + 1));
        custom("-classpath only.jar");
        command = command("server", env);
        assertEquals(jar + File.pathSeparator + "only.jar", command.get(command.indexOf("-cp") + 1));
    }

    @Test void nestedIncludesAreRelativeToTheirFile() throws IOException {
        java(elsewhere.resolve("jdk"));
        Files.createDirectories(home.resolve("conf/extra"));
        Files.writeString(home.resolve("conf/extra/one.vmoptions"), "-include-options two.vmoptions\n");
        Files.writeString(home.resolve("conf/extra/two.vmoptions"), "-Dnested=yes\n");
        custom("-include-options conf/extra/one.vmoptions");
        assertTrue(command("server", Map.of("JAVA_HOME", elsewhere.resolve("jdk").toString())).contains("-Dnested=yes"));
    }

    @Test void recursiveIncludesFail() throws IOException {
        java(elsewhere.resolve("jdk"));
        custom("-include-options conf/custom.vmoptions");
        IOException error = assertThrows(IOException.class, () -> command("server", Map.of("JAVA_HOME", elsewhere.resolve("jdk").toString())));
        assertTrue(error.getMessage().startsWith("Recursive VM options include"));
    }

    @Test void aMissingJavaFailsWithAHint() throws IOException {
        custom("-java-cmd " + elsewhere.resolve("gone/bin/" + JAVA));
        IOException error = assertThrows(IOException.class, () -> command("server", Map.of()));
        assertTrue(error.getMessage().contains("Install Java 17 or later"));
    }
}
