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
            // macOS nohup aborts when its private launchd console-detach call fails
            // in a GUI/elevated helper session. Inherit ignored SIGHUP through exec;
            // stdin and both output streams are already redirected below.
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
        if (!Model.WINDOWS) {
            Set<Long> owned = new HashSet<>(); processes.forEach(p -> owned.add(p.pid()));
            for (ProcessHandle process : processes) if (process.parent().map(p -> !owned.contains(p.pid())).orElse(true)) process.destroy();
            return;
        }
        long pid = processes.getFirst().pid();
        String script = """
            $ErrorActionPreference='Stop'
            Add-Type -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            public static class OieConsole {
              [DllImport("kernel32.dll",SetLastError=true)] public static extern bool FreeConsole();
              [DllImport("kernel32.dll",SetLastError=true)] public static extern bool AttachConsole(uint processId);
              [DllImport("kernel32.dll",SetLastError=true)] public static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent,uint group);
              [DllImport("kernel32.dll",SetLastError=true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler,bool add);
            }
            '@
            [OieConsole]::FreeConsole() | Out-Null
            if(-not [OieConsole]::AttachConsole(%d)){throw 'Cannot attach to the engine console. Stop the engine manually before upgrading.'}
            try {
              [OieConsole]::SetConsoleCtrlHandler([IntPtr]::Zero,$true) | Out-Null
              if(-not [OieConsole]::GenerateConsoleCtrlEvent(0,0)){throw 'Could not request graceful engine shutdown.'}
              Start-Sleep -Milliseconds 1000
            } finally { [OieConsole]::FreeConsole() | Out-Null }
            """.formatted(pid);
        try { Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script)); }
        catch (IOException e) { System.err.println(e.getMessage()); }
        // Like the WinSW stop timeout: when the console request is not honored, end the launcher's process tree.
        Instant deadline = Instant.now().plusSeconds(50);
        while (Instant.now().isBefore(deadline) && !Model.engineProcesses(home).isEmpty()) Thread.sleep(500);
        List<ProcessHandle> remaining = Model.engineProcesses(home);
        Set<Long> owned = new HashSet<>(); remaining.forEach(p -> owned.add(p.pid()));
        for (ProcessHandle process : remaining) if (process.parent().map(p -> !owned.contains(p.pid())).orElse(true)) {
            System.err.println("The engine did not stop on request; ending process tree " + process.pid());
            try { Model.command("taskkill.exe", "/PID", String.valueOf(process.pid()), "/T", "/F"); }
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
                param([switch]$Yes)
                $ErrorActionPreference='Stop'
                if(-not $Yes -and (Read-Host 'Delete this engine, its database, logs and backups? Type yes') -ne 'yes'){exit 1}
                $admin=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
                if(-not $admin){
                  $p=Start-Process powershell.exe -Verb RunAs -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "'+$PSCommandPath+'" -Yes') -Wait -PassThru
                  exit $p.ExitCode
                }
                & %s -cp %s org.openintegrationengine.installer.Main --uninstall %s --yes
                exit $LASTEXITCODE
                """.formatted(Privileges.powershell(java.toString()), Privileges.powershell(classpath), Privileges.powershell(home.toString()));
            Files.writeString(FilesSupport.safeTarget(home, "oie-uninstall.ps1"), script);
            String key = registryKey(home);
            String register = "$ErrorActionPreference='Stop'; $k=" + Privileges.powershell(key) + "; New-Item -Path $k -Force | Out-Null; "
                    + "New-ItemProperty $k DisplayName -Value 'Open Integration Engine' -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k DisplayVersion -Value " + Privileges.powershell(version) + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k Publisher -Value 'Open Integration Engine contributors' -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k InstallLocation -Value " + Privileges.powershell(home.toString()) + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k UninstallString -Value " + Privileges.powershell("\"" + home.resolve("oie-uninstall.exe") + "\"") + " -PropertyType String -Force | Out-Null; "
                    + "New-ItemProperty $k NoModify -Value 1 -PropertyType DWord -Force | Out-Null; New-ItemProperty $k NoRepair -Value 1 -PropertyType DWord -Force | Out-Null";
            Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(register));
            // The install4j program group: the command line under the product name, and the uninstaller.
            String menu = "$ErrorActionPreference='Stop'; $engine=" + Privileges.powershell(home.toString()) + "; $menu=" + START_MENU + "; New-Item -ItemType Directory -Path $menu -Force | Out-Null; $shell=New-Object -ComObject WScript.Shell; "
                    + "foreach($entry in @(@('Open Integration Engine','oiecommand.exe'),@('Uninstall Open Integration Engine','oie-uninstall.exe'))){ $link=$shell.CreateShortcut((Join-Path $menu ($entry[0]+'.lnk'))); $link.TargetPath=(Join-Path $engine $entry[1]); $link.WorkingDirectory=$engine; $link.IconLocation=(Join-Path $engine 'oieserver.exe'); $link.Save() }";
            Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(menu));
        } else {
            String invoke = FilesSupport.quote(java.toString()) + " -cp " + FilesSupport.quote(classpath) + " " + Main.class.getName() + " --uninstall " + FilesSupport.quote(home.toString()) + " --yes";
            String script = """
                #!/bin/sh
                set -eu
                if [ "$#" -eq 0 ] || [ "$1" != --yes ]; then
                  printf 'This deletes the engine, database, logs and backups in %%s. Type yes: ' %s
                  read -r answer
                  [ "$answer" = yes ] || exit 1
                fi
                if [ "$(id -u)" != 0 ]; then exec sudo %s; fi
                exec %s
                """.formatted(FilesSupport.quote(home.toString()), invoke, invoke);
            Path uninstaller = FilesSupport.safeTarget(home, "oie-uninstall"); Files.writeString(uninstaller, script); FilesSupport.executable(uninstaller);
        }
    }
    private static String registryKey(Path home) throws Exception {
        return "HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\OIE-" + Services.label(home);
    }
    static void retireLegacyRegistration(Path home, Path backup) throws Exception {
        if (!Model.WINDOWS || !Files.isDirectory(home.resolve(".install4j"))) return;
        String script = """
            $ErrorActionPreference='Stop'
            $homeDir=%s
            $ownKey=%s
            $found=@()
            foreach($base in @('HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall','HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall')){
              foreach($key in Get-ChildItem -LiteralPath $base -ErrorAction SilentlyContinue){
                $p=Get-ItemProperty -LiteralPath $key.PSPath
                $cmd=[string]$p.UninstallString
                $exe=if($cmd.StartsWith('"')){($cmd -split '"')[1]}else{($cmd -split '\\s+')[0]}
                if(($p.InstallLocation -and ([string]$p.InstallLocation).TrimEnd('\\') -ieq $homeDir.TrimEnd('\\')) -or $exe -ieq ($homeDir+'\\uninstall.exe')){
                  if($key.Name -ne $ownKey.Replace('HKLM:','HKEY_LOCAL_MACHINE')){
                    # Plain registry values only: the provider object graph makes ConvertTo-Json run for minutes.
                    $values=[ordered]@{}; foreach($name in $key.GetValueNames()){ $values[$name]=[string]$key.GetValue($name) }
                    $found+=[pscustomobject]@{path=$key.Name;values=$values}
                  }
                }
              }
            }
            if($found.Count -gt 0){
              ConvertTo-Json -InputObject @($found) -Depth 4 | Set-Content -LiteralPath %s -Encoding UTF8
              foreach($item in $found){Remove-Item -LiteralPath ('Registry::'+$item.path) -Recurse -Force}
            }
            """.formatted(Privileges.powershell(home.toString()), Privileges.powershell(registryKey(home)), Privileges.powershell(backup.resolve("previous-uninstall-registration.json").toString()));
        Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script));
    }
    static void windowsRemove(Path home) throws Exception {
        String remove = "Remove-Item -LiteralPath " + Privileges.powershell(registryKey(home)) + " -Recurse -Force -ErrorAction SilentlyContinue; "
                + "$engine=" + Privileges.powershell(home.toString()) + "; $menu=" + START_MENU + "; $shell=New-Object -ComObject WScript.Shell; "
                + "foreach($file in @(Get-ChildItem -LiteralPath $menu -Filter *.lnk -ErrorAction SilentlyContinue)){ if($shell.CreateShortcut($file.FullName).TargetPath.StartsWith($engine+'\\',[StringComparison]::OrdinalIgnoreCase)){ Remove-Item -LiteralPath $file.FullName -Force } }; "
                + "if((Test-Path -LiteralPath $menu) -and -not (Get-ChildItem -LiteralPath $menu)){ Remove-Item -LiteralPath $menu -Force }";
        Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(remove));
        Path script = Files.createTempFile("oie-uninstall-", ".ps1"); Json.privateFile(script);
        String cleanup = """
            $ErrorActionPreference='Stop'
            $root=%s
            for($i=0;$i -lt 90;$i++){
              $owned=Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -and $_.ExecutablePath.StartsWith($root+'\\',[StringComparison]::OrdinalIgnoreCase)}
              if(-not $owned){break}; Start-Sleep -Seconds 1
            }
            if($owned){throw 'An engine executable is still running. The engine folder was retained.'}
            Remove-Item -LiteralPath $root -Recurse -Force
            Remove-Item -LiteralPath $PSCommandPath -Force
            """.formatted(Privileges.powershell(home.toString()));
        Files.writeString(script, cleanup);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(script.resolveSibling(script.getFileName() + ".log").toFile())).redirectErrorStream(true).start();
    }
}
