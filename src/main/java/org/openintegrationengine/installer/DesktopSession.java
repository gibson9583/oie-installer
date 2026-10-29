package org.openintegrationengine.installer;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Desktop actions belong to the logged-in user, including when the Linux installer runs under sudo. */
final class DesktopSession {
    private static final List<String> DESKTOP_ENV = List.of("DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY", "DBUS_SESSION_BUS_ADDRESS",
            "XDG_CURRENT_DESKTOP", "DESKTOP_SESSION", "XDG_SESSION_TYPE", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_DATA_DIRS",
            "XDG_CONFIG_DIRS", "LANG", "LC_ALL", "LC_CTYPE");
    private static volatile Context context;
    record User(long uid, long gid, String name, Path home) {}
    record Context(long effectiveUid, User user) {}

    static boolean linux() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"); }

    /** Returns false on other platforms, whose existing desktop launchers remain in use. Run off the UI thread. */
    static boolean open(String target) throws IOException, InterruptedException {
        if (!linux()) return false;
        URI uri;
        try { uri = URI.create(target); }
        catch (IllegalArgumentException invalid) { throw new IOException("The desktop address is invalid.", invalid); }
        if (uri.getScheme() == null || !List.of("http", "https", "file").contains(uri.getScheme().toLowerCase(Locale.ROOT)))
            throw new IOException("The desktop address must be an HTTP, HTTPS or file URL.");
        Context current = current();
        if (current.effectiveUid == 0 && current.user == null)
            throw new IOException("Open this address or folder from a regular user's desktop session. The installer could not identify a user who invoked sudo.");
        if (System.getenv().getOrDefault("DISPLAY", "").isBlank() && System.getenv().getOrDefault("WAYLAND_DISPLAY", "").isBlank())
            throw new IOException("No desktop session is available. Open this address or folder from your desktop.");
        if (!Files.isExecutable(Path.of("/usr/bin/xdg-open")))
            throw new IOException("Opening the desktop requires xdg-open from xdg-utils. Install it or open this address or folder manually.");
        launch(desktopProcess(List.of("/usr/bin/xdg-open", target), current));
        return true;
    }
    static void launch(ProcessBuilder builder) throws IOException, InterruptedException {
        Process process = builder
                .redirectInput(new File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Some desktop handlers remain attached to a newly opened browser. Leave that requested application running.
        if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() != 0)
            throw new IOException("The desktop could not open this address or folder. Open it manually in your desktop session.");
    }

    /** Settings queries use the same user's HOME and session bus as desktop actions. */
    static ProcessBuilder process(List<String> command) throws IOException {
        return linux() ? desktopProcess(command, current()) : new ProcessBuilder(command);
    }
    private static ProcessBuilder desktopProcess(List<String> command, Context current) throws IOException {
        if (current.effectiveUid == 0 && current.user != null && !Files.isExecutable(Path.of("/usr/bin/setpriv")))
            throw new IOException("Opening the desktop as the original user requires setpriv from util-linux. Install it or open this address or folder from your desktop.");
        return process(command, current, System.getenv());
    }

    static ProcessBuilder process(List<String> command, Context current, Map<String, String> environment) throws IOException {
        if (current.effectiveUid != 0 || current.user == null) return new ProcessBuilder(command);
        User user = current.user;
        Map<String, String> desktop = new LinkedHashMap<>();
        desktop.put("HOME", user.home.toString()); desktop.put("USER", user.name); desktop.put("LOGNAME", user.name);
        desktop.put("PATH", user.home.resolve(".local/bin") + ":/usr/local/bin:/usr/bin:/bin:/snap/bin");
        for (String name : DESKTOP_ENV) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) desktop.put(name, value);
        }
        Path runtime = runtime(user, environment.get("XDG_RUNTIME_DIR"));
        if (runtime != null) {
            desktop.put("XDG_RUNTIME_DIR", runtime.toString());
            if (!desktop.containsKey("DBUS_SESSION_BUS_ADDRESS") && Files.exists(runtime.resolve("bus")))
                desktop.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + runtime.resolve("bus"));
        }
        // setpriv execs in place: bounded settings queries keep the same owned PID through the UID change.
        List<String> arguments = new ArrayList<>(List.of("/usr/bin/setpriv", "--reuid", Long.toString(user.uid),
                "--regid", Long.toString(user.gid), "--init-groups", "--inh-caps=-all", "--", "/usr/bin/env", "-i"));
        desktop.forEach((name, value) -> arguments.add(name + "=" + value)); arguments.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(user.home.toFile());
        // The user's desktop environment is applied by env after the UID/GID change.
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin");
        return builder;
    }

    private static Path runtime(User user, String preserved) {
        List<Path> candidates = new ArrayList<>();
        try { if (preserved != null && !preserved.isBlank()) candidates.add(Path.of(preserved)); }
        catch (java.nio.file.InvalidPathException ignored) {}
        candidates.add(Path.of("/run/user", Long.toString(user.uid)));
        for (Path path : candidates) try {
            if (!path.isAbsolute() || !Files.isDirectory(path)) continue;
            long owner = ((Number)Files.getAttribute(path, "unix:uid")).longValue();
            int mode = ((Number)Files.getAttribute(path, "unix:mode")).intValue();
            if (owner == user.uid && (mode & 0777) == 0700) return path;
        } catch (IOException | UnsupportedOperationException ignored) {}
        return null;
    }

    static Context current() throws IOException {
        Context loaded = context;
        if (loaded != null) return loaded;
        synchronized (DesktopSession.class) {
            if (context != null) return context;
            long uid = effectiveUid(Files.readString(Path.of("/proc/self/status")));
            String sudoUid = System.getenv("SUDO_UID");
            User user = null;
            if (uid == 0 && sudoUid != null && !sudoUid.isBlank() && !sudoUid.equals("0")) {
                long original = uid(sudoUid);
                user = user(read(List.of("/usr/bin/getent", "passwd", Long.toString(original))), original, System.getenv("SUDO_USER"));
            }
            return context = new Context(uid, user);
        }
    }

    static long effectiveUid(String status) throws IOException {
        for (String line : status.lines().toList()) if (line.startsWith("Uid:")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length == 5) return uid(fields[2]);
        }
        throw new IOException("Cannot determine the installer's Linux user ID.");
    }
    static long uid(String value) throws IOException {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            long uid = Long.parseLong(value);
            if (uid > 4_294_967_294L) throw new NumberFormatException();
            return uid;
        } catch (NumberFormatException invalid) { throw new IOException("Cannot determine the user who invoked sudo."); }
    }
    static User user(String passwd, long expectedUid, String expectedName) throws IOException {
        String[] lines = passwd.strip().split("\\R");
        String[] fields = lines.length == 1 ? lines[0].split(":", -1) : new String[0];
        if (fields.length != 7 || expectedUid == 0 || uid(fields[2]) != expectedUid || fields[0].isBlank()
                || expectedName != null && !expectedName.equals(fields[0]))
            throw new IOException("Cannot verify the user who invoked sudo.");
        Path home = Path.of(fields[5]);
        if (!home.isAbsolute() || !Files.isDirectory(home)) throw new IOException("The desktop user's home directory is unavailable.");
        return new User(expectedUid, uid(fields[3]), fields[0], home);
    }
    private static String read(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) throw new IOException("The desktop user lookup timed out.");
            if (process.exitValue() != 0) throw new IOException("Cannot look up the user who invoked sudo.");
            var output = process.getInputStream();
            return new String(output.readNBytes(Math.min(output.available(), 4_096)), StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("The desktop user lookup was interrupted.", interrupted); }
        finally {
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
        }
    }
}
