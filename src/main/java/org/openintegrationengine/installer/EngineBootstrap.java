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
            for (String argument : args) uninstall.add(!WINDOWS ? argument : argument.equals("--yes") ? "-Yes" : argument.equals("--delete-data") ? "-DeleteData" : argument);
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
        List<String> command = command(home, role, args, System.getenv(), Path.of(System.getProperty("java.home")));
        Process engine = new ProcessBuilder(command).directory(home.toFile()).inheritIO().start();
        Thread shutdown = new Thread(() -> {
            // Windows console control reaches both JVMs; let the engine's shutdown hook finish.
            if (!WINDOWS) engine.destroy();
            try {
                // An engine started with SIGTERM ignored still shuts down on SIGINT.
                if (!WINDOWS && !engine.waitFor(10, TimeUnit.SECONDS)) new ProcessBuilder("/bin/kill", "-INT", Long.toString(engine.pid())).start().waitFor();
                if (!engine.waitFor(40, TimeUnit.SECONDS)) engine.destroyForcibly();
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException e) { engine.destroyForcibly(); }
        }, "oie-engine-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        int status = engine.waitFor();
        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
        System.exit(status);
    }
    /** The engine's Java command line, from the role's .vmoptions file and its includes, as install4j's launchers build it. */
    static List<String> command(Path home, String role, String[] args, Map<String, String> env, Path launcherJava) throws IOException {
        boolean cli = role.equals("command");
        String jar = cli ? "mirth-cli-launcher.jar" : "mirth-server-launcher.jar";
        Options options = new Options(home, home.resolve(jar).toString(), env);
        Path config = home.resolve(cli ? "oiecommand.vmoptions" : role.equals("service") ? "oieservice.vmoptions" : "oieserver.vmoptions");
        options.read(config, new HashSet<>());
        Path java = java(options, env, launcherJava);
        List<String> command = new ArrayList<>(List.of(java.toString())); command.addAll(options.vm);
        command.addAll(List.of("-cp", home.resolve(jar) + File.pathSeparator + options.classpath,
                cli ? "com.mirth.connect.cli.launcher.CommandLineLauncher" : "com.mirth.connect.server.launcher.MirthLauncher"));
        command.addAll(Arrays.asList(args));
        return command;
    }
    /**
     * OIE_JAVA_PATH, then -java-cmd from the options. The installer always writes -java-cmd; an engine unpacked from an archive has none,
     * so like install4j it uses JAVA_HOME, then java on the PATH, and only then the launcher's own runtime.
     */
    static Path java(Options options, Map<String, String> env, Path launcherJava) throws IOException {
        String override = env.get("OIE_JAVA_PATH");
        Path java = override != null && !override.isBlank() ? options.resolve(override) : options.java;
        if (java == null) java = installedJava(env);
        if (java == null) java = launcherJava.resolve("bin").resolve(WINDOWS ? "java.exe" : "java");
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) throw new IOException("Java executable is unavailable: " + java + ". Install Java 17 or later and set JAVA_HOME, or add -java-cmd to conf/custom.vmoptions.");
        return java;
    }
    /** The java executable in JAVA_HOME, or else the first one on the PATH; null when there is none. */
    static Path installedJava(Map<String, String> env) {
        String executable = WINDOWS ? "java.exe" : "java";
        String javaHome = env.get("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", executable);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        for (String folder : env.getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (folder.isBlank()) continue;
            Path candidate = Path.of(folder, executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }
    /** The -java-cmd that the role's options set, or null. */
    static Path configuredJava(Path home, String role) throws IOException {
        String jar = role.equals("command") ? "mirth-cli-launcher.jar" : "mirth-server-launcher.jar";
        Options options = new Options(home, home.resolve(jar).toString(), System.getenv());
        options.read(home.resolve(role.equals("command") ? "oiecommand.vmoptions" : role.equals("service") ? "oieservice.vmoptions" : "oieserver.vmoptions"), new HashSet<>());
        return options.java;
    }
    static final class Options {
        private final Path home;
        private final Map<String, String> env;
        final List<String> vm = new ArrayList<>();
        String classpath;
        Path java;
        Options(Path home, String classpath, Map<String, String> env) { this.home = home; this.classpath = classpath; this.env = env; }
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
                while (variables.find()) variables.appendReplacement(expanded, Matcher.quoteReplacement(variables.group(1).equals("OIE_HOME") ? home.toString() : env.getOrDefault(variables.group(1), "")));
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
