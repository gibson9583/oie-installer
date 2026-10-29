package org.openintegrationengine.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Verifies desktop launch plans and Linux UID transitions; no real browser or desktop opener is launched. */
public final class DesktopSessionCheck {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void invalid(Checked action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); } catch (IOException expected) {}
    }
    private interface Checked { void run() throws Exception; }
    private static List<String> python(String source, Path receipt) {
        return List.of(System.getProperty("oie.check.python"), "-c", source, receipt.toString());
    }
    private static Path receipt(Path directory, String name) { return directory.resolve(name + ".pid"); }
    private static long pid(Path receipt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Files.exists(receipt) || Files.size(receipt) == 0) {
            require(System.nanoTime() < deadline, "Probe receipt was not written: " + receipt);
            Thread.sleep(5);
        }
        return Long.parseLong(Files.readString(receipt));
    }
    private static void exited(Path receipt) throws Exception {
        long pid = pid(receipt), deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            require(System.nanoTime() < deadline, "Owned desktop probe remains alive: " + pid + "; receipt: " + receipt);
            Thread.sleep(5);
        }
        System.out.println("Verified owned desktop probe " + pid + " exited: " + receipt.getFileName());
        Files.delete(receipt);
    }
    private static void unit() throws Exception {
        if (Model.WINDOWS) {
            require(!DesktopSession.open("https://localhost:8443/"), "Windows desktop behavior was intercepted");
            System.out.println("PASS: Windows uses its existing native desktop launcher"); return;
        }
        require(DesktopSession.effectiveUid("Name: java\nUid:\t1000\t0\t0\t1000\n") == 0, "Used real UID instead of effective UID");
        invalid(() -> DesktopSession.effectiveUid("Uid: broken"), "Malformed kernel identity was accepted");
        invalid(() -> DesktopSession.uid("-1"), "Negative sudo UID was accepted");
        invalid(() -> DesktopSession.uid("1000;touch /tmp/unowned"), "Shell text was accepted as a UID");
        Path home = Files.createTempDirectory("oie-desktop-plan-");
        Path runtime = Files.createDirectory(home.resolve("runtime")); Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(runtime.resolve("bus"), "isolated fixture; not a real session bus");
        try {
            long uid = ((Number)Files.getAttribute(home, "unix:uid")).longValue();
            long gid = ((Number)Files.getAttribute(home, "unix:gid")).longValue();
            // Root CI can still check parsing with a nonzero synthetic account; no process executes this plan.
            long targetUid = uid == 0 ? 1001 : uid;
            var user = DesktopSession.user("desktop:x:" + targetUid + ":" + gid + ":Desktop:" + home + ":/bin/sh", targetUid, "desktop");
            invalid(() -> DesktopSession.user("root:x:0:0:Root:" + home + ":/bin/sh", 0, "root"), "Root was accepted as a sudo desktop invoker");
            invalid(() -> DesktopSession.user("desktop:x:1001:1001:Desktop:" + home + ":/bin/sh", 1002, "desktop"), "Mismatched sudo UID was accepted");
            invalid(() -> DesktopSession.user("desktop:x:1001:1001:Desktop:" + home + ":/bin/sh", 1001, "other"), "Mismatched sudo user was accepted");
            String url = "https://localhost:8443/a%20b/?literal=$(touch%20/tmp/unowned)&x=1";
            var planned = DesktopSession.process(List.of("/usr/bin/xdg-open", url), new DesktopSession.Context(0, user), Map.of(
                    "HOME", "/root", "DISPLAY", ":0", "WAYLAND_DISPLAY", "wayland-0", "XDG_RUNTIME_DIR", runtime.toString(),
                    "XDG_CURRENT_DESKTOP", "GNOME", "XDG_CONFIG_HOME", home.resolve(".config").toString(), "LD_PRELOAD", "/tmp/unowned.so"));
            List<String> arguments = planned.command();
            require(arguments.subList(0, 2).equals(List.of("/usr/bin/setpriv", "--reuid")), "Desktop action does not drop privileges with direct exec");
            require(arguments.contains("--regid") && arguments.contains("--init-groups") && arguments.contains("--inh-caps=-all"), "Desktop action did not reset group/capability state");
            require(arguments.contains("HOME=" + home) && arguments.contains("USER=desktop") && arguments.contains("LOGNAME=desktop"), "Desktop user environment was lost");
            require(arguments.contains("DISPLAY=:0") && arguments.contains("WAYLAND_DISPLAY=wayland-0"), "Desktop display routing was lost");
            if (uid != 0) require(arguments.contains("DBUS_SESSION_BUS_ADDRESS=unix:path=" + runtime.resolve("bus")), "Owned runtime session bus was not restored");
            require(!arguments.contains("HOME=/root") && arguments.stream().noneMatch(value -> value.startsWith("LD_PRELOAD=")), "Root environment leaked to the desktop context");
            require(arguments.getLast().equals(url) && !arguments.contains("--no-sandbox") && !arguments.contains("-c"), "Address was altered or shell/browser sandbox options were introduced");
            require(planned.environment().equals(Map.of("PATH", "/usr/bin:/bin")), "Root-side launcher environment is not restricted");
            require(DesktopSession.process(List.of("native-opener", url), new DesktopSession.Context(targetUid, null), Map.of()).command().equals(List.of("native-opener", url)), "An ordinary desktop launch was elevated");
            Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-xr-x"));
            var insecure = DesktopSession.process(List.of("native-opener"), new DesktopSession.Context(0, user), Map.of("XDG_RUNTIME_DIR", runtime.toString()));
            require(!insecure.command().contains("XDG_RUNTIME_DIR=" + runtime), "Insecure runtime directory was propagated");
        } finally { Files.delete(runtime.resolve("bus")); Files.delete(runtime); Files.delete(home); }
        if (Model.MAC) require(!DesktopSession.open("https://localhost:8443/"), "macOS desktop behavior was intercepted");
        System.out.println("PASS: desktop identity, literal arguments, environment and native-platform fallback");
    }
    private static void nativeLinux() throws Exception {
        var current = DesktopSession.current();
        require(current.effectiveUid() == 0 && current.user() != null && current.user().uid() > 0, "Native check must run through sudo from a regular user");
        String inspect = "import os,json\ns={k:v.split()[0] for k,v in (line.split(':',1) for line in open('/proc/self/status') if line.startswith(('CapPrm:','CapEff:','CapInh:','CapAmb:')))}\nprint(json.dumps({'pid':os.getpid(),'ppid':os.getppid(),'uid':os.getuid(),'euid':os.geteuid(),'gid':os.getgid(),'home':os.environ.get('HOME'),'user':os.environ.get('USER'),'logname':os.environ.get('LOGNAME'),'caps':[s.get(k) for k in ('CapPrm','CapEff','CapInh','CapAmb')]}))";
        Process probe = DesktopSession.process(python(inspect, Path.of("unused"))).start();
        try {
            require(probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0, "Native desktop identity probe failed");
            var result = Json.MAPPER.readTree(new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            require(result.path("pid").asLong() == probe.pid() && result.path("ppid").asLong() == ProcessHandle.current().pid(), "UID transition introduced a supervising process");
            require(result.path("uid").asLong() == current.user().uid() && result.path("euid").asLong() == current.user().uid() && result.path("gid").asLong() == current.user().gid(), "Desktop child retained root UID/GID");
            require(result.path("home").asText().equals(current.user().home().toString()) && result.path("user").asText().equals(current.user().name()) && result.path("logname").asText().equals(current.user().name()), "Native desktop child has the wrong user environment");
            for (var caps : result.path("caps")) require(Long.parseUnsignedLong(caps.asText(), 16) == 0, "Desktop child retained root capabilities");
            System.out.println("PASS: native root-to-desktop UID/GID/environment and direct-exec PID " + probe.pid());
        } finally { if (probe.isAlive()) { probe.destroyForcibly(); require(probe.waitFor(2, TimeUnit.SECONDS), "Owned identity probe remains alive: " + probe.pid()); } }

        Path work = Files.createTempDirectory("oie-desktop-native-");
        Files.setAttribute(work, "unix:uid", (int)current.user().uid()); Files.setAttribute(work, "unix:gid", (int)current.user().gid());
        String sleeper = "import os,sys,time\nwith open(sys.argv[1], 'x') as receipt: receipt.write(str(os.getpid()))\ntime.sleep(30)";
        try {
            Path timeout = receipt(work, "timeout");
            try (SystemAppearance appearance = new SystemAppearance(value -> {}, java.util.Optional::empty, Runnable::run, 300_000)) {
                require(appearance.query(python(sleeper, timeout)).status() == -1, "Native invoker settings query did not time out");
            }
            exited(timeout);
            Path cancelled = receipt(work, "cancelled");
            try (SystemAppearance appearance = new SystemAppearance(value -> {}, java.util.Optional::empty, Runnable::run, 300_000)) {
                Thread query = Thread.ofVirtual().start(() -> appearance.query(python(sleeper, cancelled)));
                long child = pid(cancelled);
                require(ProcessHandle.of(child).flatMap(ProcessHandle::parent).map(ProcessHandle::pid).orElse(-1L) == ProcessHandle.current().pid(), "Settings probe is not the directly owned child");
                appearance.close(); query.join(2_000); require(!query.isAlive(), "Query thread remained alive after close");
            }
            exited(cancelled);
            try {
                DesktopSession.launch(DesktopSession.process(python("import sys; sys.exit(7)", Path.of("unused"))));
                throw new AssertionError("Immediate desktop launcher failure was ignored");
            } catch (IOException expected) { System.out.println("PASS: immediate launcher failure is reported"); }
            Path persistent = receipt(work, "persistent-launcher");
            try {
                DesktopSession.launch(DesktopSession.process(python(sleeper, persistent)));
                require(ProcessHandle.of(pid(persistent)).map(ProcessHandle::isAlive).orElse(false), "A persistent requested desktop application was killed");
                System.out.println("PASS: requested persistent desktop application survives the launch deadline");
            } finally {
                long child = pid(persistent);
                ProcessHandle process = ProcessHandle.of(child).orElse(null);
                if (process != null && process.isAlive()) {
                    require(process.parent().map(ProcessHandle::pid).orElse(-1L) == ProcessHandle.current().pid(), "Refusing to signal an unowned desktop probe: " + child);
                    process.destroyForcibly();
                }
                exited(persistent);
            }
            System.out.println("PASS: sudo invoker query timeout/cancellation and opener lifecycle without browser launches");
        } finally {
            boolean empty; try (var files = Files.list(work)) { empty = files.findAny().isEmpty(); }
            if (empty) Files.delete(work); else System.err.println("Retained desktop probe receipts pending termination verification: " + work);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--native-linux")) nativeLinux(); else unit();
    }
}
