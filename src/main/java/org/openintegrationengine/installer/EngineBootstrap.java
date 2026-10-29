package org.openintegrationengine.installer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Standalone entry point for the jpackage-generated engine launchers. No UI dependencies. */
public final class EngineBootstrap {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("oie.engine.home")).toRealPath();
        String role = System.getProperty("oie.engine.role", "server");
        if (role.equals("uninstall")) {
            List<String> uninstall = WINDOWS ? new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", home.resolve("uninstall.ps1").toString()))
                    : new ArrayList<>(List.of("/bin/bash", home.resolve("uninstall").toString()));
            for (String argument : args) uninstall.add(WINDOWS && argument.equals("--yes") ? "-Yes" : argument);
            System.exit(new ProcessBuilder(uninstall).inheritIO().start().waitFor()); return;
        }
        if (role.equals("service") && args.length > 0) {
            // install4j's oieservice verbs; with no verb a service manager runs the engine in the foreground.
            String verb = args[0].replaceFirst("^[-/]+", "").toLowerCase(Locale.ROOT);
            switch (verb) {
                case "run", "run-redirect", "start-launchd" -> args = Arrays.copyOfRange(args, 1, args.length);
                case "start", "stop", "restart", "force-reload", "status", "install", "uninstall" -> { System.exit(ServiceControl.run(home, verb)); return; }
                default -> { System.err.println("Usage: oieservice {start|stop|run|run-redirect|status|restart|force-reload" + (WINDOWS ? "|/install|/uninstall" : "") + "}"); System.exit(1); return; }
            }
        }
        boolean cli = role.equals("command");
        String jar = cli ? "mirth-cli-launcher.jar" : "mirth-server-launcher.jar";
        Options options = new Options(home, home.resolve(jar).toString());
        Path config = home.resolve(cli ? "oiecommand.vmoptions" : role.equals("service") ? "oieservice.vmoptions" : "oieserver.vmoptions");
        options.read(config, new HashSet<>());
        String override = System.getenv("OIE_JAVA_PATH");
        Path java = override != null && !override.isBlank() ? options.resolve(override) : options.java;
        if (java == null) java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) throw new IOException("Java executable is unavailable: " + java);
        List<String> command = new ArrayList<>(List.of(java.toString())); command.addAll(options.vm);
        command.addAll(List.of("-cp", home.resolve(jar) + File.pathSeparator + options.classpath,
                cli ? "com.mirth.connect.cli.launcher.CommandLineLauncher" : "com.mirth.connect.server.launcher.MirthLauncher"));
        command.addAll(Arrays.asList(args));
        Process engine = new ProcessBuilder(command).directory(home.toFile()).inheritIO().start();
        Thread shutdown = new Thread(() -> {
            // Windows console control reaches both JVMs; let the engine's shutdown hook finish.
            if (!WINDOWS) engine.destroy();
            try { if (!engine.waitFor(50, TimeUnit.SECONDS)) engine.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "oie-engine-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        int status = engine.waitFor();
        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
        System.exit(status);
    }
    private static final class Options {
        private final Path home;
        private final List<String> vm = new ArrayList<>();
        private String classpath;
        private Path java;
        Options(Path home, String classpath) { this.home = home; this.classpath = classpath; }
        Path resolve(String value) {
            String text = unquote(value.trim());
            if (text.equals("~")) return Path.of(System.getProperty("user.home"));
            if (text.startsWith("~/")) return Path.of(System.getProperty("user.home")).resolve(text.substring(2));
            Path path = Path.of(text); return path.isAbsolute() ? path : home.resolve(path).normalize();
        }
        void read(Path file, Set<Path> stack) throws IOException {
            Path canonical = file.toRealPath();
            if (stack.size() >= 32 || !stack.add(canonical)) throw new IOException("Recursive VM options include: " + file);
            for (String raw : Files.readAllLines(canonical)) {
                String line = raw.strip(); if (line.isEmpty() || line.startsWith("#")) continue;
                Matcher variables = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z_0-9]*)}").matcher(line); StringBuilder expanded = new StringBuilder();
                while (variables.find()) variables.appendReplacement(expanded, Matcher.quoteReplacement(variables.group(1).equals("OIE_HOME") ? home.toString() : System.getenv().getOrDefault(variables.group(1), "")));
                variables.appendTail(expanded); line = expanded.toString();
                if (line.startsWith("-include-options ")) {
                    String value = unquote(line.substring(17).strip()); Path include = Path.of(value);
                    // OIE's shipped top-level includes are relative to the engine folder; nested includes use their file's folder.
                    if (!include.isAbsolute()) {
                        Path beside = canonical.getParent().resolve(include).normalize();
                        include = Files.isRegularFile(beside) ? beside : home.resolve(include).normalize();
                    }
                    read(include, stack);
                } else if (line.startsWith("-java-cmd ")) java = resolve(line.substring(10));
                else if (line.startsWith("-classpath/a ")) classpath += File.pathSeparator + unquote(line.substring(13).strip());
                else if (line.startsWith("-classpath/p ")) classpath = unquote(line.substring(13).strip()) + File.pathSeparator + classpath;
                else if (line.startsWith("-classpath ")) classpath = unquote(line.substring(11).strip());
                else vm.add(line);
            }
            stack.remove(canonical);
        }
        private static String unquote(String text) {
            if (text.length() > 1 && ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'")))) return text.substring(1, text.length() - 1);
            return text;
        }
    }
}
