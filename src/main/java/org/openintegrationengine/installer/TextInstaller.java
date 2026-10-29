package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** install4j's console mode (-c): the wizard's steps as terminal questions. No JavaFX dependencies. */
final class TextInstaller {
    private static final Map<String, String> DATABASES = new LinkedHashMap<>();
    private static final Map<String, String> JDBC = Map.of("postgres", "jdbc:postgresql://localhost:5432/mirthdb", "mysql", "jdbc:mysql://localhost:3306/mirthdb",
            "sqlserver", "jdbc:sqlserver://localhost:1433;databaseName=mirthdb", "oracle", "jdbc:oracle:thin:@localhost:1521:DB");
    static {
        DATABASES.put("derby", "Derby (built in)"); DATABASES.put("postgres", "PostgreSQL"); DATABASES.put("mysql", "MySQL");
        DATABASES.put("sqlserver", "SQL Server"); DATABASES.put("oracle", "Oracle");
    }
    private final BufferedReader in;
    private final PrintStream out;
    private final Console console;
    TextInstaller(BufferedReader in, PrintStream out, Console console) { this.in = in; this.out = out; this.console = console; }

    static int run(String[] args) throws Exception {
        String dir = Model.defaultHome().toString();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-c")) continue;
            if (args[i].equals("-dir") && i + 1 < args.length) { dir = args[++i]; continue; }
            throw new IllegalArgumentException("Text mode accepts -c and -dir <folder>.");
        }
        Console console = System.console();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new TextInstaller(in, System.out, console).install(Payload.load(), dir);
    }

    int install(Payload payload, String defaultDir) throws Exception {
        out.println("Open Integration Engine " + payload.version() + " installer");
        out.println();
        if (!license()) { out.println("The license was not accepted. Nothing was installed."); return 1; }

        Model.Destination destination;
        while (true) {
            String entered = ask("Engine folder", defaultDir);
            try { destination = Model.destination(entered, payload); break; }
            catch (IllegalArgumentException e) { out.println(e.getMessage()); }
        }
        boolean fresh = destination.mode().equals("new");
        if (!fresh) out.println("Found engine " + destination.existing().version() + ". This " + destination.mode() + " keeps its database, ports and credentials.");

        ObjectNode plan = Json.object().put("installDir", destination.home().toString()).put("expectedMode", destination.mode());
        if (!fresh) plan.put("expectedVersion", destination.existing().version());
        plan.put("javaCmd", java(payload));

        if (fresh) {
            int http = port("HTTP port", Model.freePort(8080), -1);
            int https = Model.freePort(8443);
            if (https == http) https = Model.freePort(http + 1);
            plan.put("httpPort", http).put("httpsPort", port("HTTPS port", https, http));
            String database = choose("Database", new ArrayList<>(DATABASES.keySet()), new ArrayList<>(DATABASES.values()), 0);
            plan.put("database", database);
            if (!database.equals("derby")) {
                String url;
                while ((url = ask("JDBC URL", JDBC.get(database))).isBlank()) out.println("Enter the database JDBC URL.");
                plan.put("databaseUrl", url).put("databaseUsername", ask("Database user name", "")).put("databasePassword", secret("Database password"));
            }
            plan.put("adminPassword", administrator());
        }

        String reason = Services.supported().isEmpty() ? "This system has no supported service manager." : Services.blocked(destination.home());
        if (reason.isEmpty()) reason = Services.conflict(destination.home());
        boolean service = false;
        if (reason.isEmpty()) service = yes("Run the engine as a " + Services.supported() + " service that starts at boot?", true);
        else out.println("The engine runs as a normal process. " + reason);
        plan.put("service", service);

        ObjectNode plugins = plan.putObject("plugins");
        for (var plugin : payload.plugins) plugins.put(plugin.id(), yes("Install " + plugin.name() + " " + plugin.version() + "?", true));

        Model.Plan selected = Model.Plan.from(plan);
        out.println();
        out.println("Review");
        out.println("  Folder:    " + selected.home() + " (" + destination.mode() + ")");
        out.println("  Java:      " + (selected.javaCmd().equals(Model.BUNDLED) ? "Bundled Java" : selected.javaCmd()));
        if (fresh) {
            out.println("  Ports:     HTTP " + selected.httpPort() + ", HTTPS " + selected.httpsPort());
            out.println("  Database:  " + DATABASES.get(selected.database()) + (selected.database().equals("derby") ? "" : " " + selected.databaseUrl()));
        }
        out.println("  Startup:   " + (service ? Services.supported() + " service" : "Normal process"));
        for (var plugin : payload.plugins) out.println("  " + plugin.name() + ": " + (selected.plugins().get(plugin.id()) ? "install" : "skip"));
        if (!yes("Install now?", true)) { out.println("Nothing was installed."); return 1; }

        String[] shown = {""};
        var result = Privileges.install(payload, selected, event -> {
            if (event.state().equals("active") && !event.label().equals(shown[0])) out.println(shown[0] = event.label());
        }, line -> {});
        out.println();
        out.println("Open Integration Engine " + result.get("version") + " is running.");
        out.println("  Address:   " + result.getOrDefault("webClientUrl", result.get("url")));
        out.println("  Folder:    " + result.get("home"));
        if (result.containsKey("adminPassword")) out.println("  Sign in:   admin / " + result.get("adminPassword"));
        if (result.containsKey("backup")) out.println("  Backup:    " + result.get("backup"));
        out.println("  Uninstall: " + result.get("uninstall"));
        return 0;
    }

    private boolean license() throws IOException {
        String text;
        try (InputStream stream = TextInstaller.class.getResourceAsStream("/LICENSE.txt")) {
            text = new String(Objects.requireNonNull(stream, "LICENSE.txt").readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            out.println(lines.get(i));
            if ((i + 1) % 40 == 0 && i + 1 < lines.size()) {
                String answer = ask("Press Enter to continue, or type a to skip to the end", "");
                if (answer.equalsIgnoreCase("a")) { lines.subList(i + 1, lines.size()).forEach(out::println); break; }
            }
        }
        out.println();
        return yes("Do you accept the Mozilla Public License 2.0?", false);
    }

    private String java(Payload payload) throws IOException {
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        if (payload.bundledJava()) { values.add(Model.BUNDLED); labels.add("Bundled Java (recommended)"); }
        for (Model.JavaRuntime runtime : Model.runtimes()) if (runtime.supported()) { values.add(runtime.path().toString()); labels.add("Java " + runtime.version() + "  " + runtime.path()); }
        if (values.isEmpty()) throw new IllegalArgumentException("This installer has no bundled Java. Install Java 17 or later, or use the -with-JRE installer.");
        return choose("Java runtime for the engine", values, labels, 0);
    }

    private int port(String label, int suggested, int other) throws IOException {
        while (true) {
            String text = ask(label, String.valueOf(suggested));
            try {
                int port = Integer.parseInt(text);
                if (port == other) out.println("HTTP and HTTPS ports must be different.");
                else if (!Model.portFree(port)) out.println("Port " + port + " is in use or invalid.");
                else return port;
            } catch (NumberFormatException e) { out.println("Enter a port number from 1 to 65535."); }
        }
    }

    private String administrator() throws IOException {
        while (true) {
            String password = secret("Administrator password (at least 15 characters; press Enter to generate one)");
            if (password.isEmpty()) return Model.password();
            if (password.codePointCount(0, password.length()) < 15) { out.println("Use at least 15 characters."); continue; }
            if (password.equals(secret("Confirm the administrator password"))) return password;
            out.println("The passwords do not match.");
        }
    }

    private String choose(String label, List<String> values, List<String> labels, int preset) throws IOException {
        out.println(label + ":");
        for (int i = 0; i < labels.size(); i++) out.println("  " + (i + 1) + ") " + labels.get(i));
        while (true) {
            String text = ask("Select", String.valueOf(preset + 1));
            try { int index = Integer.parseInt(text) - 1; if (index >= 0 && index < values.size()) return values.get(index); }
            catch (NumberFormatException ignored) {}
            out.println("Enter a number from 1 to " + values.size() + ".");
        }
    }

    boolean yes(String question, boolean preset) throws IOException {
        while (true) {
            String answer = ask(question + (preset ? " [Y/n]" : " [y/N]"), null).toLowerCase(Locale.ROOT);
            if (answer.isEmpty()) return preset;
            if (answer.equals("y") || answer.equals("yes")) return true;
            if (answer.equals("n") || answer.equals("no")) return false;
            out.println("Answer y or n.");
        }
    }

    String ask(String label, String preset) throws IOException {
        out.print(label + (preset == null || preset.isEmpty() ? "" : " [" + preset + "]") + (preset == null ? " " : ": "));
        out.flush();
        String line = in.readLine();
        if (line == null) throw new EOFException("The input ended before the installation was configured.");
        line = line.strip();
        return line.isEmpty() && preset != null ? preset : line;
    }

    private String secret(String label) throws IOException {
        if (console == null) return ask(label, "");
        char[] value = console.readPassword("%s: ", label);
        if (value == null) throw new EOFException("The input ended before the installation was configured.");
        String text = new String(value); Arrays.fill(value, '\0'); return text;
    }
}
