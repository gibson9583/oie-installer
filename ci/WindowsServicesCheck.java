package org.openintegrationengine.installer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Exercises the compiled service implementation against a real, CI-owned SCM service. */
public final class WindowsServicesCheck {
    private static final List<Map<String, Object>> timings = new ArrayList<>();

    private static <T> T timed(String name, Callable<T> action) throws Exception {
        long started = System.nanoTime();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("operation", name);
        try {
            T result = action.call();
            item.put("ok", true);
            return result;
        } finally {
            item.put("seconds", (System.nanoTime() - started) / 1_000_000_000.0);
            timings.add(item);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void registered(Path home, boolean running) throws Exception {
        var record = timed("find", Services::find);
        require(record != null, "Registered service was not found");
        require(record.path("program").asText().equalsIgnoreCase(home.resolve("oieservice.exe").toString()),
                "Quoted service ImagePath with arguments did not yield the executable path");
        require(timed("detect", () -> Services.detect(home)) != null, "Service ownership was not recognized");
        require(timed("conflict_owned", () -> Services.conflict(home)).isEmpty(), "Owned service conflicts with itself");
        require(timed("running", () -> Services.running(home)) == running, "Incorrect service running state");
    }

    public static void main(String[] args) {
        int status = 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", args[0]);
        result.put("javaArch", System.getProperty("os.arch"));
        Path home = Path.of(args[1]).toAbsolutePath().normalize();
        try {
            require(Model.WINDOWS, "This check requires Windows");
            switch (args[0]) {
                case "missing" -> {
                    require(timed("find", Services::find) == null, "Missing service was reported present");
                    require(!timed("running", () -> Services.running(home)), "Missing service was reported running");
                    require(timed("conflict", () -> Services.conflict(home)).isEmpty(), "Missing service caused a conflict");
                    timed("stop_missing", () -> { Services.stop(home, false); return null; });
                    timed("remove_missing", () -> { Services.stop(home, true); return null; });
                }
                case "stopped" -> registered(home, false);
                case "running" -> registered(home, true);
                case "unrelated" -> {
                    Path unrelated = home.resolveSibling("unrelated engine");
                    require(timed("detect_unrelated", () -> Services.detect(unrelated)) == null, "Unrelated home owns the service");
                    require(!timed("running_unrelated", () -> Services.running(unrelated)), "Unrelated home reports the service running");
                    require(!timed("conflict_unrelated", () -> Services.conflict(unrelated)).isEmpty(), "Unrelated service caused no conflict");
                    timed("stop_unrelated", () -> { Services.stop(unrelated, false); return null; });
                    timed("remove_unrelated", () -> { Services.stop(unrelated, true); return null; });
                    require(timed("find_after_unrelated", Services::find) != null, "Unrelated operation removed the service");
                }
                case "stop" -> timed("stop", () -> { Services.stop(home, false); return null; });
                case "remove" -> timed("remove", () -> { Services.stop(home, true); return null; });
                default -> throw new IllegalArgumentException("Unknown check case");
            }
            result.put("ok", true);
        } catch (Throwable error) {
            result.put("ok", false);
            result.put("error", error.toString());
            status = 1;
        } finally {
            result.put("timings", timings);
            try { System.out.println(Json.MAPPER.writeValueAsString(result)); }
            catch (Exception error) { error.printStackTrace(); status = 1; }
        }
        System.exit(status);
    }
}
