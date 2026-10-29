package org.openintegrationengine.installer;

import javafx.application.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Follows the desktop's application appearance without blocking the JavaFX thread. */
final class SystemAppearance implements AutoCloseable {
    private static final Pattern WINDOWS_LIGHT = Pattern.compile("(?im)^\\s*AppsUseLightTheme\\s+REG_DWORD\\s+0x([01])\\s*$");
    private static final Pattern PORTAL_SCHEME = Pattern.compile("\\buint32\\s+([0-9]+)\\b");
    private final ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "oie-system-appearance"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<Boolean> onLight;
    private final Supplier<Optional<Boolean>> detect;
    private final Consumer<Runnable> dispatch;
    private final long pollMillis;
    private volatile Process active;
    private Boolean light;

    /** Call from Application.start, then close from Application.stop. Light is the unavailable-setting fallback. */
    static SystemAppearance follow(Consumer<Boolean> onLight) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Appearance must be observed from the JavaFX thread");
        SystemAppearance appearance = new SystemAppearance(onLight, null, Platform::runLater, 2_000);
        appearance.observer.execute(appearance::refresh);
        return appearance;
    }

    // The separate source and dispatcher let the lifecycle check exercise failures and queued UI callbacks.
    SystemAppearance(Consumer<Boolean> onLight, Supplier<Optional<Boolean>> detect, Consumer<Runnable> dispatch, long pollMillis) {
        this.onLight = onLight; this.detect = detect == null ? this::detect : detect;
        this.dispatch = dispatch; this.pollMillis = pollMillis;
    }

    void refresh() {
        if (closed.get()) return;
        Optional<Boolean> preference;
        try {
            preference = detect.get();
        } catch (RuntimeException ignored) {
            // A transient desktop/settings failure keeps the last observed appearance.
            preference = Optional.empty();
        }
        if (closed.get()) return;
        boolean value = preference.orElse(light == null || light);
        if (light == null || value != light) {
            light = value;
            dispatch.accept(() -> { if (!closed.get()) onLight.accept(value); });
        }
        if (!closed.get()) {
            try { observer.schedule(this::refresh, preference.isPresent() ? pollMillis : Math.max(pollMillis, 10_000), TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { /* Closed while a query completed. */ }
        }
    }

    private Optional<Boolean> detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            Query result = query(List.of("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle"));
            // macOS removes this optional key in light mode.
            if (result.status == 1) return Optional.of(true);
            return result.status == 0 ? mac(result.output) : Optional.empty();
        }
        if (os.contains("win")) {
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            Query result = query(List.of(Path.of(systemRoot, "System32", "reg.exe").toString(), "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"));
            return result.status == 0 ? windows(result.output) : Optional.empty();
        }
        Query portal = query(List.of("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
                "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.Read",
                "org.freedesktop.appearance", "color-scheme"));
        Optional<Boolean> scheme = portal.status == 0 ? portal(portal.output) : Optional.empty();
        if (scheme.isPresent() || closed.get()) return scheme;
        Query gnome = query(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
        scheme = gnome.status == 0 ? gnome(gnome.output) : Optional.empty();
        if (scheme.isPresent() || closed.get()) return scheme;
        Query gtk = query(List.of("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"));
        if (gtk.status == 0 && !gtk.output.isBlank()) return Optional.of(!gtk.output.toLowerCase(Locale.ROOT).contains("dark"));
        return Optional.empty();
    }

    static Optional<Boolean> mac(String output) {
        return switch (output.strip().toLowerCase(Locale.ROOT)) {
            case "dark" -> Optional.of(false);
            case "light" -> Optional.of(true);
            default -> Optional.empty();
        };
    }
    static Optional<Boolean> windows(String output) {
        var match = WINDOWS_LIGHT.matcher(output);
        return match.find() ? Optional.of(match.group(1).equals("1")) : Optional.empty();
    }
    static Optional<Boolean> portal(String output) {
        var match = PORTAL_SCHEME.matcher(output);
        if (!match.find()) return Optional.empty();
        return switch (match.group(1)) { case "1" -> Optional.of(false); case "2" -> Optional.of(true); default -> Optional.empty(); };
    }
    static Optional<Boolean> gnome(String output) {
        return switch (output.strip().replace("'", "")) {
            case "prefer-dark" -> Optional.of(false);
            case "prefer-light", "default" -> Optional.of(true);
            default -> Optional.empty();
        };
    }

    record Query(int status, String output) {}
    Query query(List<String> command) {
        if (closed.get()) return new Query(-1, "");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start(); active = process;
            if (closed.get() || !process.waitFor(1, TimeUnit.SECONDS)) return new Query(-1, "");
            // These scalar settings fit in a few lines. Bound output and never wait on inherited pipes.
            var output = process.getInputStream();
            return new Query(process.exitValue(), new String(output.readNBytes(Math.min(output.available(), 4_096)), StandardCharsets.UTF_8));
        } catch (IOException ignored) { return new Query(-1, ""); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return new Query(-1, ""); }
        finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
            }
            active = null;
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        observer.shutdownNow();
        Process process = active;
        if (process != null && process.isAlive()) process.destroyForcibly();
    }
}
