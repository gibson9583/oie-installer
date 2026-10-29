package org.openintegrationengine.installer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only measurements of the actual Maven-built installer classes. */
public final class WindowsPerformanceProbe {
    public static void main(String[] args) throws Exception {
        String operation = args[0];
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        long started = System.nanoTime();
        switch (operation) {
            case "startup" -> result.put("result", "ready");
            case "engine_processes" -> {
                Path home = Path.of(args[1]).toAbsolutePath().normalize();
                result.put("matches", Model.engineProcesses(home).size());
            }
            case "administrator" -> result.put("administrator", Privileges.administrator());
            case "service_find" -> {
                var service = Services.find();
                result.put("found", service != null);
                // Record presence, never the service's command line or configuration.
                result.put("programPresent", service != null && !service.path("program").asText().isBlank());
            }
            case "process_handles" -> {
                try (var processes = ProcessHandle.allProcesses()) { result.put("count", processes.count()); }
            }
            default -> throw new IllegalArgumentException("Unknown probe operation");
        }
        result.put("operationSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
        result.put("osName", System.getProperty("os.name"));
        result.put("osArch", System.getProperty("os.arch"));
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("javaHome", System.getProperty("java.home"));
        System.out.println(Json.MAPPER.writeValueAsString(result));
    }
}
