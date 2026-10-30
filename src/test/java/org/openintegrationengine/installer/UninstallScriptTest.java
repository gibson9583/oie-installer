package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** The macOS and Linux uninstall script is valid shell, also for folder names with quotes and spaces. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows uses uninstall.ps1")
class UninstallScriptTest {
    @TempDir Path folder;

    private Path script() throws IOException {
        String invoke = FilesSupport.quote("/opt/java home/bin/java") + " -cp " + FilesSupport.quote("/opt/it's oie/app/*") + " Main --uninstall "
                + FilesSupport.quote("/opt/it's oie") + " --yes";
        Path file = folder.resolve("uninstall");
        Files.writeString(file, NativeProcesses.unixUninstaller(Path.of("/opt/it's oie"), invoke));
        return file;
    }

    private static int run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).inheritIO().start();
        return process.waitFor();
    }

    @Test void theScriptIsValidShell() throws Exception {
        assertEquals(0, run("sh", "-n", script().toString()));
    }

    @Test void shellcheckFindsNothing() throws Exception {
        Optional<Path> shellcheck = Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                .map(dir -> Path.of(dir, "shellcheck")).filter(Files::isExecutable).findFirst();
        assumeTrue(shellcheck.isPresent(), "shellcheck is not installed");
        assertEquals(0, run(shellcheck.get().toString(), "--shell=sh", script().toString()));
    }

    @Test void theFolderIsQuotedForTheShell() throws Exception {
        assertTrue(Files.readString(script()).contains("' '/opt/it'\\''s oie'"));
    }
}
