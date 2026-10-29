package org.openintegrationengine.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises appearance selection and observer shutdown without changing desktop preferences. */
public final class SystemAppearanceCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void expect(Optional<Boolean> actual, Boolean light, String message) {
        require(actual.equals(Optional.ofNullable(light)), message + ": " + actual);
    }
    private static List<String> child(Path receipt) {
        String python = System.getProperty("oie.check.python");
        require(python != null && !python.isBlank(), "Run this check through ci/check_system_appearance.py");
        return List.of(python, "-c", "import os, sys, time\nwith open(sys.argv[1], 'w') as receipt: receipt.write(str(os.getpid()))\ntime.sleep(30)", receipt.toString());
    }
    private static void exited(Path receipt) throws Exception {
        require(Files.exists(receipt), "Probe child did not start; receipt: " + receipt);
        long pid = Long.parseLong(Files.readString(receipt));
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            require(System.nanoTime() < deadline, "Owned probe child remains alive: " + pid + "; receipt: " + receipt);
            Thread.sleep(10);
        }
        System.out.println("Verified owned appearance probe child " + pid + " exited");
        Files.delete(receipt);
    }
    private static void windowsRegistry() {
        if (!Model.WINDOWS) { System.out.println("SKIP: native Windows registry appearance fixture"); return; }
        String reg = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "reg.exe").toString();
        String key = "HKCU\\Software\\OIEInstallerAppearanceTest-" + UUID.randomUUID();
        try (SystemAppearance observer = new SystemAppearance(value -> {}, Optional::empty, Runnable::run, 300_000)) {
            require(observer.query(List.of(reg, "query", key)).status() == 1, "Refusing to reuse a registry fixture key: " + key);
            boolean commandsExited = true;
            try {
                for (int value : List.of(0, 1)) {
                    var added = observer.query(List.of(reg, "add", key, "/v", "AppsUseLightTheme", "/t", "REG_DWORD", "/d", Integer.toString(value), "/f"));
                    commandsExited &= added.status() >= 0;
                    require(added.status() == 0, "Cannot create owned registry fixture " + key + ": " + added);
                    var queried = observer.query(List.of(reg, "query", key, "/v", "AppsUseLightTheme"));
                    commandsExited &= queried.status() >= 0;
                    require(queried.status() == 0, "Cannot query owned registry fixture " + key + ": " + queried);
                    expect(SystemAppearance.windows(queried.output()), value == 1, "Native reg.exe output/encoding for DWORD " + value + ": " + queried.output());
                }
            } catch (AssertionError failure) {
                System.err.println("Native registry fixture failure: " + failure.getMessage());
                throw failure;
            } finally {
                if (!commandsExited) System.err.println("Retained owned registry key because a fixture command's termination is uncertain: " + key);
                else {
                    var removed = observer.query(List.of(reg, "delete", key, "/f"));
                    require(removed.status() == 0 || removed.status() == 1, "Owned registry fixture cleanup failed: " + key + ": " + removed);
                    require(observer.query(List.of(reg, "query", key)).status() == 1, "Owned registry fixture remains after cleanup: " + key);
                    System.out.println("Verified owned registry fixture removed: " + key);
                }
            }
        }
        System.out.println("PASS: native reg.exe output/encoding for both light and dark settings");
    }
    public static void main(String[] args) throws Exception {
        expect(SystemAppearance.mac("Dark\n"), false, "macOS dark appearance");
        expect(SystemAppearance.mac("Light\n"), true, "macOS light appearance");
        expect(SystemAppearance.mac("unexpected"), null, "Unknown macOS setting");
        expect(SystemAppearance.windows("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n    AppsUseLightTheme    REG_DWORD    0x0\r\n"), false, "Windows dark application setting");
        expect(SystemAppearance.windows("    AppsUseLightTheme    REG_DWORD    0x1\r\n"), true, "Windows light application setting");
        expect(SystemAppearance.windows("    SystemUsesLightTheme    REG_DWORD    0x0\r\n"), null, "Windows shell theme is not the application setting");
        expect(SystemAppearance.windows("    AppsUseLightTheme    REG_DWORD    0x10\r\n"), null, "Unknown Windows value");
        expect(SystemAppearance.portal("(<uint32 1>,)"), false, "Desktop portal dark preference");
        expect(SystemAppearance.portal("(<<uint32 2>>,)"), true, "Desktop portal light preference");
        expect(SystemAppearance.portal("(<uint32 0>,)"), null, "Desktop portal has no preference");
        expect(SystemAppearance.portal("(<uint32 3>,)"), null, "Unknown portal value means no preference");
        expect(SystemAppearance.gnome("'prefer-dark'\n"), false, "GNOME dark preference");
        expect(SystemAppearance.gnome("'prefer-light'\n"), true, "GNOME light preference");
        expect(SystemAppearance.gnome("'default'\n"), true, "GNOME default preference");
        System.out.println("PASS: native setting formats and unknown-value fallbacks");
        windowsRegistry();

        List<Boolean> applied = new ArrayList<>();
        List<Runnable> queued = new ArrayList<>();
        AtomicReference<Optional<Boolean>> setting = new AtomicReference<>(Optional.of(false));
        try (SystemAppearance observer = new SystemAppearance(applied::add, setting::get, queued::add, 300_000)) {
            require(applied.isEmpty(), "A theme was applied before the first system read");
            observer.refresh();
            require(applied.isEmpty() && queued.size() == 1, "Initial system preference did not use the UI dispatcher");
            queued.removeFirst().run();
            require(applied.equals(List.of(false)), "Initial dark system preference flashed light");
            observer.refresh();
            require(queued.isEmpty(), "Unchanged preference triggered another UI update");
            setting.set(Optional.empty()); observer.refresh();
            require(queued.isEmpty(), "Unavailable settings replaced the last appearance");
            setting.set(Optional.of(true)); observer.refresh(); queued.removeFirst().run();
            require(applied.equals(List.of(false, true)), "Runtime system change was not applied");
            setting.set(Optional.of(false)); observer.refresh();
            require(queued.size() == 1, "Expected a pending UI update");
            observer.close(); observer.close(); queued.removeFirst().run(); observer.refresh();
            require(applied.equals(List.of(false, true)) && queued.isEmpty(), "Closed observer delivered a stale UI update");
        }
        try (SystemAppearance observer = new SystemAppearance(applied::add, () -> { throw new IllegalStateException("Desktop unavailable"); }, Runnable::run, 300_000)) {
            observer.refresh();
            require(applied.getLast(), "Initial settings failure did not use light fallback");
        }
        System.out.println("PASS: initial detection, live changes, transient failures, queued callbacks and repeated close");

        List<Boolean> nativeResult = new ArrayList<>();
        long started = System.nanoTime();
        try (SystemAppearance observer = new SystemAppearance(nativeResult::add, null, Runnable::run, 300_000)) { observer.refresh(); }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        require(nativeResult.size() == 1, "Native read supplied no initial appearance");
        require(elapsedMillis < 5_000, "Desktop queries exceeded their bounded startup budget: " + elapsedMillis + "ms");
        System.out.println("PASS: read-only native desktop query (" + elapsedMillis + "ms, " + (nativeResult.getFirst() ? "light" : "dark") + ")");

        Path receipts = Files.createTempDirectory("oie-appearance-probes-");
        try {
            Path timedOut = receipts.resolve("timed-out.pid");
            try (SystemAppearance observer = new SystemAppearance(value -> {}, Optional::empty, Runnable::run, 300_000)) {
                started = System.nanoTime();
                require(observer.query(child(timedOut)).status() == -1, "An unresponsive query did not time out");
                require(System.nanoTime() - started < 2_000_000_000L, "An unresponsive query blocked past its deadline");
            }
            exited(timedOut);
            Path cancelled = receipts.resolve("cancelled.pid");
            try (SystemAppearance observer = new SystemAppearance(value -> {}, Optional::empty, Runnable::run, 300_000)) {
                Thread query = Thread.ofVirtual().start(() -> observer.query(child(cancelled)));
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!Files.exists(cancelled)) {
                    require(System.nanoTime() < deadline, "Cancellable query did not start; receipt: " + cancelled);
                    Thread.sleep(5);
                }
                observer.close(); query.join(2_000);
                require(!query.isAlive(), "Closing the observer left its query blocked");
            }
            exited(cancelled);
            System.out.println("PASS: query deadline and close terminate only their owned child processes");
        } finally {
            boolean empty;
            try (var remaining = Files.list(receipts)) { empty = remaining.findAny().isEmpty(); }
            if (empty) Files.delete(receipts);
            else System.err.println("Retained process receipts pending ownership/termination verification: " + receipts);
        }
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.isAlive() && thread.getName().equals("oie-system-appearance"))) {
            require(System.nanoTime() < deadline, "Appearance observer remained alive after close");
            Thread.sleep(10);
        }
        System.out.println("PASS: observer resources exited after close");
    }
}
