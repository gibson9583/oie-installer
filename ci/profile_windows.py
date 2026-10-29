"""Time read-only Windows installer operations; no engine or service is installed.

Run after ``mvn -B package``. Each sample starts a fresh process. Java samples
include a separate in-process duration, so JVM startup can be distinguished from
installer code. Every completed sample is checkpointed in the JSON output.
"""

import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parent.parent
SERVICE = "Open Integration Engine Service"


class ProcessCleanupUncertain(RuntimeError):
    """An owned child tree could not be confirmed stopped."""


def pe_machine(path):
    """Report the executable's PE machine without loading or executing it."""
    try:
        with Path(path).open("rb") as stream:
            if stream.read(2) != b"MZ":
                return None
            stream.seek(0x3C)
            offset = struct.unpack("<I", stream.read(4))[0]
            stream.seek(offset)
            if stream.read(4) != b"PE\0\0":
                return None
            machine = struct.unpack("<H", stream.read(2))[0]
        return {0x14C: "x86", 0x8664: "x64", 0xAA64: "arm64",
                0xA641: "arm64ec", 0xA64E: "arm64x"}.get(machine, hex(machine))
    except (OSError, struct.error):
        return None


def powershells():
    """Keep distinct executable paths: System32/Sysnative may select different hosts."""
    candidates = [shutil.which("powershell.exe"), shutil.which("pwsh.exe")]
    windows = Path(os.environ.get("SystemRoot", r"C:\Windows"))
    for system in ("System32", "SysWOW64", "Sysnative"):
        candidates.append(windows / system / "WindowsPowerShell/v1.0/powershell.exe")
    for env in ("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)", "LOCALAPPDATA"):
        base = os.environ.get(env)
        if base:
            candidates.extend(Path(base).glob("PowerShell/*/pwsh.exe"))
    seen = set()
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            key = os.path.normcase(os.path.abspath(candidate))
            if key not in seen:
                seen.add(key)
                yield str(candidate)


def checkpoint(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def run_command(command, timeout, work, label):
    """Capture bounded output; on timeout terminate only the child and its tree."""
    started = time.perf_counter()
    result = {"label": label, "executable": command[0], "peMachine": pe_machine(command[0])}
    capture = work / "command-output.txt"
    process = None
    def terminate():
        # taskkill /T applies only to this probe-owned process tree.
        try:
            killed = subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, timeout=20)
            process.wait(timeout=20)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ProcessCleanupUncertain("Probe tree termination was not confirmed; retained " + str(work)) from error
        result["treeTerminationExitCode"] = killed.returncode
        if killed.returncode != 0:
            raise ProcessCleanupUncertain("Probe tree termination was not confirmed; retained " + str(work))

    try:
        with capture.open("wb") as output:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output,
                                       stderr=subprocess.STDOUT)
            result["pid"] = process.pid
            try:
                result["exitCode"] = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                result["timedOut"] = True
                terminate()
                result["exitCode"] = process.returncode
            except BaseException:
                terminate()
                raise
        # Commands are fixed read-only scripts; do not include their arguments in results.
        with capture.open("rb") as captured:
            raw = captured.read(16384)
        text = raw.decode("utf-8-sig", errors="replace").strip()
        result["outputBytes"] = capture.stat().st_size
        # sc qc can include arbitrary service arguments; retain timing/status only.
        if not label.startswith("sc_"):
            result["output"] = text
            try:
                result["details"] = json.loads(text)
            except (ValueError, TypeError):
                pass
    except OSError as error:
        result["error"] = type(error).__name__ + ": " + str(error)
    finally:
        result["seconds"] = time.perf_counter() - started
    return result


def profile(args):
    if sys.platform != "win32":
        raise SystemExit("This read-only probe requires Windows.")
    suffix = ".exe"
    java_home = os.environ.get("JAVA_HOME")
    executable = lambda name: str(Path(java_home) / "bin" / (name + suffix)) if java_home else shutil.which(name + suffix)
    java, javac = executable("java"), executable("javac")
    if not java or not javac or not (ROOT / "target/classes").is_dir():
        raise SystemExit("Build with Maven and provide JAVA_HOME (or java.exe and javac.exe on PATH).")
    classpath = os.pathsep.join([str(ROOT / "target/classes"), str(ROOT / "target/lib/*")])
    work = Path(tempfile.mkdtemp(prefix="oie-windows-profile-"))
    output = args.output.resolve()
    document = {
        "startedUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "readOnly": True, "timeoutSeconds": args.timeout, "budgetSeconds": args.budget, "samples": args.samples,
        "environment": {key: os.environ.get(key) for key in (
            "JAVA_HOME", "PROCESSOR_ARCHITECTURE", "PROCESSOR_ARCHITEW6432",
            "PROCESSOR_IDENTIFIER", "NUMBER_OF_PROCESSORS", "OS", "RUNNER_ARCH", "RUNNER_OS",
            "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_WORKFLOW")},
        "javaExecutable": java, "javaPeMachine": pe_machine(java), "measurements": [],
    }
    checkpoint(output, document)
    deadline = time.perf_counter() + args.budget
    safe_cleanup = True
    try:
        compiled = run_command([javac, "-cp", classpath, "-d", str(work),
                                str(ROOT / "ci/WindowsPerformanceProbe.java")],
                               args.timeout, work, "compile_probe")
        document["measurements"].append(compiled)
        checkpoint(output, document)
        if compiled.get("exitCode") != 0:
            raise RuntimeError("Performance probe compilation failed; see " + str(output))
        # This path is deliberately absent; enumeration cannot match an installed engine.
        nonexistent = work / "absent-engine"
        java_classpath = str(work) + os.pathsep + classpath
        commands = [("java_" + operation, [java, "-cp", java_classpath,
                     "org.openintegrationengine.installer.WindowsPerformanceProbe", operation, str(nonexistent)])
                    for operation in ("startup", "encoded_empty", "process_handles", "engine_processes", "administrator", "service_find")]
        commands.extend(("sc_" + verb, [shutil.which("sc.exe") or "sc.exe", verb, SERVICE])
                        for verb in ("query", "qc"))
        for index, shell in enumerate(powershells()):
            shell_label = "powershell_" + str(index)
            scripts = {
                "empty": "exit 0",
                "metadata": "$a='unknown'; try{$a=[Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()}catch{}; "
                            "[pscustomobject]@{version=$PSVersionTable.PSVersion.ToString();edition=$PSVersionTable.PSEdition;"
                            "processArchitecture=$a;pointerBytes=[IntPtr]::Size;home=$PSHOME}|ConvertTo-Json -Compress",
                "cim_service": "$ErrorActionPreference='Stop'; [bool](Get-CimInstance Win32_Service -Filter \"Name='" + SERVICE + "'\")",
                "get_service": "[bool](Get-Service -Name '" + SERVICE + "' -ErrorAction SilentlyContinue); exit 0",
                "service_controller": "Add-Type -AssemblyName System.ServiceProcess; $s=[System.ServiceProcess.ServiceController]::GetServices(); "
                                      "[bool]($s|Where-Object ServiceName -eq '" + SERVICE + "'); "
                                      "$s|ForEach-Object {$_.Dispose()}",
                "registry_direct": "$ErrorActionPreference='Stop'; "
                                   "[bool][Microsoft.Win32.Registry]::GetValue('HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\" + SERVICE + "','ImagePath',$null)",
                "controller_direct": "$ErrorActionPreference='Stop'; "
                                     "[void][Reflection.Assembly]::Load('System.ServiceProcess, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a'); "
                                     "$s=[System.ServiceProcess.ServiceController]::new(); $s.ServiceName='" + SERVICE + "'; "
                                     "try{[string]$s.Status}catch{$e=$_.Exception; while($e.InnerException){$e=$e.InnerException}; "
                                     "if($e -is [ComponentModel.Win32Exception] -and $e.NativeErrorCode -eq 1060){'absent'}else{throw}}finally{$s.Dispose()}",
            }
            if index > 0:
                scripts = {key: value for key, value in scripts.items() if key in ("empty", "metadata", "cim_service")}
            for operation, script in scripts.items():
                commands.append((shell_label + "_" + operation,
                                 [shell, "-NoProfile", "-NonInteractive", "-Command", script]))
        for label, command in commands:
            for sample in range(1, args.samples + 1):
                remaining = deadline - time.perf_counter()
                if remaining <= 1:
                    document["budgetExhausted"] = True
                    document.setdefault("skipped", []).append({"label": label, "sample": sample})
                    checkpoint(output, document)
                    continue
                document["currentMeasurement"] = {"label": label, "sample": sample}
                checkpoint(output, document)
                result = run_command(command, min(args.timeout, remaining), work, label)
                result["sample"] = sample
                document.pop("currentMeasurement", None)
                document["measurements"].append(result)
                checkpoint(output, document)
                outcome = "timeout" if result.get("timedOut") else "exit=" + str(result.get("exitCode", "error"))
                detail = result.get("details", {})
                inner = detail.get("operationSeconds") if isinstance(detail, dict) else None
                timing = " (method %.3fs)" % inner if inner is not None else ""
                print("%s [%s]: %.3fs%s %s" % (label, sample, result["seconds"], timing, outcome), flush=True)
        document["completedUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    except ProcessCleanupUncertain:
        # Do not remove even probe-owned files while a process may still use them.
        safe_cleanup = False
        document["retainedProbeDirectory"] = str(work)
        raise
    finally:
        if safe_cleanup:
            shutil.rmtree(work)
            document["temporaryResourcesRemoved"] = True
        checkpoint(output, document)
    print("Performance results: " + str(output), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "ci/performance-windows.json")
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--budget", type=float, default=420, help="Total seconds, excluding timeout tree cleanup")
    arguments = parser.parse_args()
    if not 1 <= arguments.samples <= 5 or not 1 <= arguments.timeout <= 180 or not 60 <= arguments.budget <= 600:
        parser.error("--samples must be 1..5, --timeout 1..180 seconds and --budget 60..600 seconds")
    profile(arguments)
