package org.openintegrationengine.installer;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class NativeProcesses {
    private static final String START_MENU = "(Join-Path $env:ProgramData 'Microsoft\\Windows\\Start Menu\\Programs\\Open Integration Engine')";
    static long start(Path home, Payload payload) throws Exception { return start(home, payload.launcher(home, "oieserver")); }
    static long start(Path home, Path launcher) throws Exception {
        Files.createDirectories(home.resolve("logs"));
        if (Model.WINDOWS) {
            String script = "$ErrorActionPreference='Stop'; (New-Object -ComObject Shell.Application).ShellExecute("
                    + Privileges.powershell(launcher.toString()) + ",''," + Privileges.powershell(home.toString()) + ",'open',0)";
            Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script));
            Instant until = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(until)) {
                var processes = Model.engineProcesses(home); if (!processes.isEmpty()) return processes.getFirst().pid(); Thread.sleep(200);
            }
            throw new IOException("The engine launcher did not start in the desktop session.");
        }
        List<String> command = new ArrayList<>();
        if (Privileges.administrator() && !Privileges.invokingUser().equals("root")) command.addAll(List.of("/usr/bin/sudo", "-n", "-u", Privileges.invokingUser(), "--"));
        if (Model.MAC) {
            // macOS nohup aborts when its private launchd console-detach call fails in a GUI session.
            command.addAll(List.of("/bin/sh", "-c", "trap '' HUP; exec \"$@\"", "oie-engine", launcher.toString()));
        } else command.addAll(List.of("/usr/bin/nohup", launcher.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile()).redirectInput(new File("/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("logs/oieserver.out").toFile())).redirectErrorStream(true);
        // A jpackage launcher (oieservice start) marks its own relaunch in the environment; the engine launcher must not inherit it.
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).contains("JPACKAGE"));
        return builder.start().pid();
    }
    static void stopOwned(Path home) throws Exception {
        List<ProcessHandle> processes = Model.engineProcesses(home);
        if (processes.isEmpty()) return;
        // install4j cannot stop a console engine on Windows either; it asks the user to close it.
        if (Model.WINDOWS) throw new IOException("Close the running engine (oieserver.exe). Without the service, Windows stops the engine only when its window closes.");
        Set<Long> owned = new HashSet<>(); processes.forEach(p -> owned.add(p.pid()));
        for (ProcessHandle process : processes) if (process.parent().map(p -> !owned.contains(p.pid())).orElse(true)) process.destroy();
        // An engine started with SIGTERM ignored still handles SIGINT, which also runs its shutdown hooks.
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline) && processes.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(250);
        for (ProcessHandle process : processes) if (process.isAlive()) {
            try { Model.command("/bin/kill", "-INT", Long.toString(process.pid())); }
            catch (IOException e) { if (process.isAlive()) throw e; }
        }
    }
    static String backendClasspath(Path home) {
        Path app = Model.MAC ? home.resolve(".launcher/OIE Engine.app/Contents/app") : Model.WINDOWS ? home.resolve("app") : home.resolve(".launcher/oieserver/lib/app");
        return app.resolve("oie-installer-jpackage.jar") + File.pathSeparator + app.resolve("lib") + File.separator + "*";
    }
    static void tools(Path home, Payload payload, String version) throws Exception {
        Path java = payload.engineJava(home); String classpath = backendClasspath(home);
        if (Model.WINDOWS) {
            String script = """
                param([switch]$Yes,[switch]$DeleteData)
                $ErrorActionPreference='Stop'
                if(-not $Yes){
                  if((Read-Host 'Remove Open Integration Engine from this computer? Type yes') -ne 'yes'){exit 1}
                  $DeleteData=((Read-Host 'Also delete the configuration, database, logs and backups? Type delete to delete them, or press Enter to keep them') -eq 'delete')
                }
                $admin=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
                if(-not $admin){
                  $arguments='-NoProfile -ExecutionPolicy Bypass -File "'+$PSCommandPath+'" -Yes'; if($DeleteData){$arguments+=' -DeleteData'}
                  $p=Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments -Wait -PassThru
                  exit $p.ExitCode
                }
                $data=@(); if($DeleteData){$data=@('--delete-data')}
                & %s -cp %s org.openintegrationengine.installer.Main --uninstall %s --yes @data
                exit $LASTEXITCODE
                """.formatted(Privileges.powershell(java.toString()), Privileges.powershell(classpath), Privileges.powershell(home.toString()));
            Files.writeString(FilesSupport.safeTarget(home, "uninstall.ps1"), script);
            // install4j's Apps & Features entry, under its application ID.
            long size = FilesSupport.size(home) / 1024;
            String[] parts = version.split("\\.");
            String register = "$ErrorActionPreference='Stop'; $k=" + Privileges.powershell(REGISTRY_KEY) + "; New-Item -Path $k -Force | Out-Null; "
                    + "New-ItemProperty $k DisplayName -Value " + Privileges.powershell("Open Integration Engine " + version) + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k DisplayIcon -Value " + Privileges.powershell(home.resolve("oieserver.exe") + ",0") + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k UninstallString -Value " + Privileges.powershell("\"" + home.resolve("uninstall.exe") + "\"") + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k Publisher -Value 'Open Integration Engine Project' -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k URLInfoAbout -Value 'https://openintegrationengine.com' -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k InstallLocation -Value " + Privileges.powershell(home.toString()) + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k EstimatedSize -Value " + size + " -PropertyType DWord -Force | Out-Null; "
                    + "New-ItemProperty $k DisplayVersion -Value " + Privileges.powershell(version) + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k VersionMajor -Value " + parts[0] + " -PropertyType DWord -Force | Out-Null; "
                    + "New-ItemProperty $k VersionMinor -Value " + parts[1] + " -PropertyType DWord -Force | Out-Null";
            Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(register));
            // install4j's program group: the service launcher and the command line under the product name.
            String menu = "$ErrorActionPreference='Stop'; $engine=" + Privileges.powershell(home.toString()) + "; $menu=" + START_MENU + "; New-Item -ItemType Directory -Path $menu -Force | Out-Null; $shell=New-Object -ComObject WScript.Shell; "
                    + "foreach($entry in @(@('Open Integration Engine Service','oieservice.exe'),@('Open Integration Engine','oiecommand.exe'))){ $target=(Join-Path $engine $entry[1]); $link=$shell.CreateShortcut((Join-Path $menu ($entry[0]+'.lnk'))); $link.TargetPath=$target; $link.WorkingDirectory=$engine; $link.IconLocation=($target+',0'); $link.Save() }";
            Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(menu));
        } else {
            String invoke = FilesSupport.quote(java.toString()) + " -cp " + FilesSupport.quote(classpath) + " " + Main.class.getName() + " --uninstall " + FilesSupport.quote(home.toString()) + " --yes";
            String script = unixUninstaller(home, invoke);
            Path uninstaller = FilesSupport.safeTarget(home, "uninstall"); Files.writeString(uninstaller, script); FilesSupport.executable(uninstaller);
            // install4j links the service and command-line launchers into /usr/local/bin on Linux.
            if (!Model.MAC && Privileges.administrator() && Files.isDirectory(LINKS)) for (String name : LINKED) {
                Path link = LINKS.resolve(name);
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(link)) continue;
                Files.deleteIfExists(link); Files.createSymbolicLink(link, home.resolve(name));
            }
        }
    }
    /** The uninstall script for macOS and Linux: asks to confirm, then whether to delete the data, and runs the uninstaller as root. */
    static String unixUninstaller(Path home, String invoke) {
        return """
            #!/bin/sh
            set -eu
            data=
            if [ "${1:-}" = --yes ]; then
              if [ "${2:-}" = --delete-data ]; then data=--delete-data; fi
            else
              printf 'Remove Open Integration Engine from %%s? Type yes: ' %s
              read -r answer
              [ "$answer" = yes ] || exit 1
              printf 'Also delete the configuration, database, logs and backups? Type delete to delete them, or press Enter to keep them: '
              read -r answer
              if [ "$answer" = delete ]; then data=--delete-data; fi
            fi
            if [ "$(id -u)" != 0 ]; then exec sudo %s ${data:+"$data"}; fi
            exec %s ${data:+"$data"}
            """.formatted(FilesSupport.quote(home.toString()), invoke, invoke);
    }
    private static final Path LINKS = Path.of("/usr/local/bin");
    private static final List<String> LINKED = List.of("oieservice", "oiecommand");
    /** install4j's application ID, which keys its Apps & Features entry. */
    private static final String REGISTRY_KEY = "HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\4145-9206-7630-8076";
    /** Remove the registrations made by tools(): Apps & Features and the program group, or the Linux links. */
    static void removeTools(Path home) throws Exception {
        if (Model.WINDOWS) {
            String remove = "$engine=" + Privileges.powershell(home.toString()) + "; $k=" + Privileges.powershell(REGISTRY_KEY) + "; "
                    + "if((Test-Path -LiteralPath $k) -and ([string](Get-ItemProperty -LiteralPath $k).InstallLocation).TrimEnd('\\') -ieq $engine.TrimEnd('\\')){ Remove-Item -LiteralPath $k -Recurse -Force }; "
                    + "$menu=" + START_MENU + "; $shell=New-Object -ComObject WScript.Shell; "
                    + "foreach($file in @(Get-ChildItem -LiteralPath $menu -Filter *.lnk -ErrorAction SilentlyContinue)){ if($shell.CreateShortcut($file.FullName).TargetPath.StartsWith($engine+'\\',[StringComparison]::OrdinalIgnoreCase)){ Remove-Item -LiteralPath $file.FullName -Force } }; "
                    + "if((Test-Path -LiteralPath $menu) -and -not (Get-ChildItem -LiteralPath $menu)){ Remove-Item -LiteralPath $menu -Force }";
            Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(remove));
            Files.deleteIfExists(home.resolve("uninstall.ps1"));
        } else {
            for (String name : LINKED) {
                Path link = LINKS.resolve(name);
                if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link).startsWith(home)) Files.delete(link);
            }
            Files.deleteIfExists(home.resolve("uninstall"));
        }
    }
    static void retireLegacyRegistration(Path home, Path backup) throws Exception {
        if (!Model.WINDOWS || !Files.isDirectory(home.resolve(".install4j"))) return;
        String script = """
            $ErrorActionPreference='Stop'
            $homeDir=%s
            $found=@()
            foreach($base in @('HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall','HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall')){
              foreach($key in Get-ChildItem -LiteralPath $base -ErrorAction SilentlyContinue){
                $p=Get-ItemProperty -LiteralPath $key.PSPath
                $cmd=[string]$p.UninstallString
                $exe=if($cmd.StartsWith('"')){($cmd -split '"')[1]}else{($cmd -split '\\s+')[0]}
                if(($p.InstallLocation -and ([string]$p.InstallLocation).TrimEnd('\\') -ieq $homeDir.TrimEnd('\\')) -or $exe -ieq ($homeDir+'\\uninstall.exe')){
                  # Plain registry values only: the provider object graph makes ConvertTo-Json run for minutes.
                  $values=[ordered]@{}; foreach($name in $key.GetValueNames()){ $values[$name]=[string]$key.GetValue($name) }
                  $found+=[pscustomobject]@{path=$key.Name;values=$values}
                }
              }
            }
            if($found.Count -gt 0){
              ConvertTo-Json -InputObject @($found) -Depth 4 | Set-Content -LiteralPath %s -Encoding UTF8
              foreach($item in $found){Remove-Item -LiteralPath ('Registry::'+$item.path) -Recurse -Force}
            }
            """.formatted(Privileges.powershell(home.toString()), Privileges.powershell(backup.resolve("previous-uninstall-registration.json").toString()));
        Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script));
    }
    /** The uninstaller runs from the engine folder's runtime, so the files go after it exits. */
    static void windowsRemove(Path home, List<Path> targets) throws Exception {
        Path script = Files.createTempFile("oie-uninstall-", ".ps1"); Json.privateFile(script);
        StringJoiner paths = new StringJoiner(",", "@(", ")");
        for (Path target : targets) paths.add(Privileges.powershell(target.toString()));
        String cleanup = """
            $ErrorActionPreference='Stop'
            $root=%s
            for($i=0;$i -lt 90;$i++){
              $owned=Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -and $_.ExecutablePath.StartsWith($root+'\\',[StringComparison]::OrdinalIgnoreCase)}
              if(-not $owned){break}; Start-Sleep -Seconds 1
            }
            if($owned){throw 'An engine executable is still running. The engine files were retained.'}
            foreach($target in %s){ if(Test-Path -LiteralPath $target){ Remove-Item -LiteralPath $target -Recurse -Force } }
            $extensions=Join-Path $root 'extensions'
            if((Test-Path -LiteralPath $extensions) -and -not (Get-ChildItem -LiteralPath $extensions -Force)){ Remove-Item -LiteralPath $extensions -Force }
            Remove-Item -LiteralPath $PSCommandPath -Force
            """.formatted(Privileges.powershell(home.toString()), paths);
        Files.writeString(script, cleanup);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(script.resolveSibling(script.getFileName() + ".log").toFile())).redirectErrorStream(true).start();
    }
}
