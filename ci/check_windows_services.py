"""Check actual installer service methods against an isolated real Windows service.

Run after Maven package, as administrator, on an otherwise idle Windows CI host.
The canonical engine service name is required by the production implementation.
If that name is already registered, this check refuses to change it. No engine
or database is installed. Final observations and timings survive fixture cleanup.
"""

import argparse
import ctypes
from ctypes import wintypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parent.parent
SERVICE = "Open Integration Engine Service"
STOPPED, STOP_PENDING, RUNNING = 1, 3, 4


class CleanupUncertain(RuntimeError):
    pass


class ServiceStatus(ctypes.Structure):
    _fields_ = [(name, wintypes.DWORD) for name in (
        "serviceType", "state", "controls", "win32ExitCode", "serviceExitCode",
        "checkpoint", "waitHint", "pid", "flags")]


class ServiceConfig(ctypes.Structure):
    _fields_ = [("serviceType", wintypes.DWORD), ("startType", wintypes.DWORD),
                ("errorControl", wintypes.DWORD), ("binary", wintypes.LPWSTR),
                ("group", wintypes.LPWSTR), ("tag", wintypes.DWORD),
                ("dependencies", wintypes.LPWSTR), ("account", wintypes.LPWSTR),
                ("displayName", wintypes.LPWSTR)]


class WindowsServices:
    def __init__(self):
        self.api = ctypes.WinDLL("advapi32", use_last_error=True)
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.OpenSCManagerW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
        self.api.OpenSCManagerW.restype = wintypes.HANDLE
        self.api.OpenServiceW.argtypes = [wintypes.HANDLE, wintypes.LPCWSTR, wintypes.DWORD]
        self.api.OpenServiceW.restype = wintypes.HANDLE
        self.api.CloseServiceHandle.argtypes = [wintypes.HANDLE]
        self.api.CloseServiceHandle.restype = wintypes.BOOL
        self.api.QueryServiceConfigW.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD,
                                                ctypes.POINTER(wintypes.DWORD)]
        self.api.QueryServiceConfigW.restype = wintypes.BOOL
        self.api.QueryServiceStatusEx.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
                                                 wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
        self.api.QueryServiceStatusEx.restype = wintypes.BOOL
        self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel.OpenProcess.restype = wintypes.HANDLE
        self.kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                          wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
        self.kernel.QueryFullProcessImageNameW.restype = wintypes.BOOL
        self.kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.kernel.WaitForSingleObject.restype = wintypes.DWORD
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel.CloseHandle.restype = wintypes.BOOL
        self.processes = {}

    def snapshot(self):
        manager = self.api.OpenSCManagerW(None, None, 1)  # SC_MANAGER_CONNECT
        if not manager:
            raise ctypes.WinError(ctypes.get_last_error())
        service = None
        try:
            service = self.api.OpenServiceW(manager, SERVICE, 1 | 4)  # QUERY_CONFIG | QUERY_STATUS
            if not service:
                error = ctypes.get_last_error()
                if error == 1060:
                    return None
                if error == 1072:
                    return {"markedForDeletion": True}
                raise ctypes.WinError(error)
            needed = wintypes.DWORD()
            self.api.QueryServiceConfigW(service, None, 0, ctypes.byref(needed))
            if ctypes.get_last_error() != 122 or needed.value == 0:  # ERROR_INSUFFICIENT_BUFFER
                raise ctypes.WinError(ctypes.get_last_error())
            buffer = ctypes.create_string_buffer(needed.value)
            if not self.api.QueryServiceConfigW(service, buffer, len(buffer), ctypes.byref(needed)):
                raise ctypes.WinError(ctypes.get_last_error())
            config = ctypes.cast(buffer, ctypes.POINTER(ServiceConfig)).contents
            status = ServiceStatus()
            if not self.api.QueryServiceStatusEx(service, 0, ctypes.byref(status), ctypes.sizeof(status), ctypes.byref(needed)):
                raise ctypes.WinError(ctypes.get_last_error())
            return {"binary": config.binary, "state": status.state, "pid": status.pid,
                    "checkpoint": status.checkpoint, "waitHint": status.waitHint,
                    "startType": config.startType, "account": config.account}
        finally:
            if service:
                self.api.CloseServiceHandle(service)
            self.api.CloseServiceHandle(manager)

    def capture_process(self, snapshot, expected_executable):
        pid = snapshot["pid"]
        if not pid or pid in self.processes:
            return
        handle = self.kernel.OpenProcess(0x100000 | 0x1000, False, pid)  # SYNCHRONIZE | QUERY_LIMITED_INFORMATION
        if not handle:
            raise CleanupUncertain("Cannot obtain an exit-verification handle for fixture PID " + str(pid))
        try:
            size = wintypes.DWORD(32768)
            image = ctypes.create_unicode_buffer(size.value)
            if not self.kernel.QueryFullProcessImageNameW(handle, 0, image, ctypes.byref(size)):
                raise ctypes.WinError(ctypes.get_last_error())
            if os.path.normcase(image.value) != os.path.normcase(str(expected_executable)):
                raise CleanupUncertain("SCM process image does not match the owned fixture")
            self.processes[pid] = handle
        except BaseException:
            self.kernel.CloseHandle(handle)
            raise

    def confirm_process_exits(self, timeout=20):
        for pid, handle in self.processes.items():
            if self.kernel.WaitForSingleObject(handle, int(timeout * 1000)) != 0:
                raise CleanupUncertain("Fixture PID " + str(pid) + " has not been confirmed exited")

    def close(self):
        for handle in self.processes.values():
            self.kernel.CloseHandle(handle)


def checkpoint(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def check(args):
    if sys.platform != "win32":
        raise SystemExit("This service regression check requires Windows.")
    document = {"startedUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "cases": [], "observations": [], "engineInstalled": False}
    output = args.output.resolve()
    log = output.with_suffix(".log")
    output.parent.mkdir(parents=True, exist_ok=True)
    log.write_text("Owned SCM service regression checks\n", encoding="utf-8")
    checkpoint(output, document)
    scm = WindowsServices()
    work = None
    image_path = None
    child_cleanup_uncertain = False

    def observe(label):
        snapshot = scm.snapshot()
        document["observations"].append({"label": label, "service": snapshot,
                                        "utc": datetime.datetime.now(datetime.timezone.utc).isoformat()})
        checkpoint(output, document)
        return snapshot

    def owned(label):
        snapshot = observe(label)
        if snapshot is None or snapshot.get("binary") != image_path:
            raise CleanupUncertain("Canonical service ownership changed; no service mutation is permitted")
        if snapshot["pid"]:
            scm.capture_process(snapshot, fixture)
        return snapshot

    def wait_for(predicate, what, seconds=30):
        deadline = time.monotonic() + seconds
        while True:
            snapshot = scm.snapshot()
            if snapshot and snapshot.get("pid"):
                if snapshot.get("binary") != image_path:
                    raise CleanupUncertain("Service ownership changed while waiting; retaining the fixture")
                scm.capture_process(snapshot, fixture)
            if predicate(snapshot):
                document["observations"].append({"label": what, "service": snapshot})
                checkpoint(output, document)
                return snapshot
            if time.monotonic() >= deadline:
                raise AssertionError("Timed out waiting for " + what)
            time.sleep(0.1)

    def run(label, command, timeout=120):
        nonlocal child_cleanup_uncertain
        started = time.perf_counter()
        capture = work / "command-output.log"
        document["currentOperation"] = label
        checkpoint(output, document)
        process = None
        result = {"case": label}
        failure = None
        try:
            with capture.open("wb") as stream:
                process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=stream,
                                           stderr=subprocess.STDOUT)
                result["pid"] = process.pid
                try:
                    result["exitCode"] = process.wait(timeout=timeout)
                except BaseException:
                    try:
                        killed = subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                                stderr=subprocess.DEVNULL, timeout=20)
                        result["treeTerminationExitCode"] = killed.returncode
                        process.wait(timeout=20)
                        if killed.returncode != 0:
                            raise CleanupUncertain("Test helper tree termination was not confirmed")
                    except BaseException:
                        child_cleanup_uncertain = True
                        raise
                    raise
        except BaseException as error:
            if process is not None:
                result["exitCode"] = process.poll()
            result["errorType"] = type(error).__name__
            result["timedOut"] = isinstance(error, subprocess.TimeoutExpired)
            failure = error
            result["error"] = str(error)
        finally:
            # Preserve even failed/timed-out helper output before fixture cleanup.
            text = capture.read_text(encoding="utf-8-sig", errors="replace").strip() if capture.is_file() else ""
            result["seconds"] = time.perf_counter() - started
            with log.open("a", encoding="utf-8") as stream:
                stream.write("\n%s: %.3fs, exit %s\n%s\n" % (label, result["seconds"], result.get("exitCode"), text))
            if label.startswith("java_"):
                try:
                    result["details"] = json.loads(text)
                except ValueError:
                    result["output"] = text
            document["cases"].append(result)
            document.pop("currentOperation", None)
            checkpoint(output, document)
            print("%s: %.3fs, exit %s" % (label, result["seconds"], result.get("exitCode")), flush=True)
        if failure is not None:
            raise failure
        if result["exitCode"] != 0:
            raise AssertionError(label + " failed; see " + str(log))
        return result

    def fixture_control(verb):
        owned("before_sc_" + verb)
        return run("sc_" + verb, ["sc.exe", verb, SERVICE], timeout=40)

    def java_check(case):
        return run("java_" + case, [java, "-cp", java_classpath,
                   "org.openintegrationengine.installer.WindowsServicesCheck", case, str(home)])

    def cleanup():
        if child_cleanup_uncertain:
            raise CleanupUncertain("A test helper may still be running; retaining " + str(work))
        snapshot = observe("cleanup_initial")
        if snapshot and snapshot.get("markedForDeletion"):
            wait_for(lambda item: item is None, "canonical service deletion", seconds=30)
            snapshot = None
        if snapshot is not None:
            snapshot = owned("cleanup_ownership")
            if snapshot["state"] not in (STOPPED, STOP_PENDING):
                fixture_control("stop")
            wait_for(lambda item: item is not None and item.get("state") == STOPPED,
                     "fixture stopped during cleanup", seconds=30)
            scm.confirm_process_exits()
            fixture_control("delete")
            wait_for(lambda item: item is None, "fixture removed during cleanup", seconds=30)
        scm.confirm_process_exits()
        document["confirmedExitedPids"] = list(scm.processes)
        if work is not None:
            shutil.rmtree(work)
        document["temporaryResourcesRemoved"] = True

    try:
        # Refuse a shared or reference engine before creating any fixture resources.
        if observe("preflight") is not None:
            raise CleanupUncertain("The canonical engine service already exists; no changes were made")
        if not (ROOT / "target/classes").is_dir():
            raise RuntimeError("Build the installer with Maven package before this check")
        java_home = os.environ.get("JAVA_HOME")
        executable = lambda name: str(Path(java_home) / "bin" / (name + ".exe")) if java_home else shutil.which(name + ".exe")
        java, javac = executable("java"), executable("javac")
        if not java or not javac:
            raise RuntimeError("Provide JAVA_HOME or java.exe/javac.exe on PATH")
        windows = Path(os.environ.get("SystemRoot", r"C:\Windows"))
        csc = next((windows / item / "v4.0.30319/csc.exe" for item in (
            "Microsoft.NET/Framework64", "Microsoft.NET/Framework")
                    if (windows / item / "v4.0.30319/csc.exe").is_file()), None)
        if csc is None:
            raise RuntimeError("The Windows .NET Framework C# compiler is required for the service fixture")
        work = Path(tempfile.mkdtemp(prefix="oie-windows-service-check-"))
        home = work / "engine with spaces"
        home.mkdir()
        fixture = home / "oieservice.exe"
        image_path = '"' + str(fixture) + '" --fixture "argument with spaces"'
        document["fixtureDirectory"] = str(work)
        document["expectedImagePath"] = image_path
        classpath = os.pathsep.join([str(ROOT / "target/classes"), str(ROOT / "target/lib/*")])
        java_classpath = str(work) + os.pathsep + classpath
        run("compile_java", [javac, "-cp", classpath, "-d", str(work), str(ROOT / "ci/WindowsServicesCheck.java")], timeout=60)
        run("compile_fixture", [str(csc), "/nologo", "/target:exe", "/platform:anycpu", "/reference:System.ServiceProcess.dll",
                                "/out:" + str(fixture), str(ROOT / "ci/WindowsServiceFixture.cs")], timeout=60)
        run("java_runtime", [java, "-version"], timeout=30)
        document["javaExecutable"] = java
        document["javaExecutableSha256"] = hashlib.sha256(Path(java).read_bytes()).hexdigest()
        provenance = output.parent / "provenance"
        provenance.mkdir(exist_ok=True)
        audit_inputs = [fixture, work / "org/openintegrationengine/installer/WindowsServicesCheck.class",
                        ROOT / "ci/WindowsServiceFixture.cs", ROOT / "ci/WindowsServicesCheck.java",
                        ROOT / "ci/check_windows_services.py"]
        for name in ("Services", "Model", "Privileges"):
            audit_inputs.extend([ROOT / ("src/main/java/org/openintegrationengine/installer/" + name + ".java"),
                                 ROOT / ("target/classes/org/openintegrationengine/installer/" + name + ".class")])
        document["auditInputs"] = []
        for source in audit_inputs:
            contents = source.read_bytes()
            digest = hashlib.sha256(contents).hexdigest()
            artifact = provenance / (digest + source.suffix)
            if artifact.exists():
                if hashlib.sha256(artifact.read_bytes()).hexdigest() != digest:
                    raise AssertionError("Content-addressed audit input hash mismatch")
            else:
                artifact.write_bytes(contents)
            document["auditInputs"].append({"source": str(source), "sha256": digest,
                                            "artifact": str(artifact.relative_to(output.parent))})
        checkpoint(output, document)
        java_check("missing")
        # sc create is atomic: it fails if another process registered this name.
        run("sc_create", ["sc.exe", "create", SERVICE, "binPath=", image_path,
                          "start=", "demand", "type=", "own"])
        owned("registered")
        java_check("stopped")
        java_check("unrelated")
        if owned("after_unrelated_stopped")["state"] != STOPPED:
            raise AssertionError("Unrelated-home operations changed the stopped service")
        fixture_control("start")
        snapshot = wait_for(lambda item: item is not None and item.get("state") == RUNNING, "fixture running")
        scm.capture_process(snapshot, fixture)
        java_check("running")
        java_check("unrelated")
        if owned("after_unrelated_running")["state"] != RUNNING:
            raise AssertionError("Unrelated-home operations stopped or removed the running service")
        fixture_control("stop")
        wait_for(lambda item: item is not None and item.get("state") == STOP_PENDING, "fixture STOP_PENDING", seconds=3)
        owned("before_pending_stop")
        java_check("stop")
        if owned("after_pending_stop")["state"] != STOPPED:
            raise AssertionError("Services.stop returned before STOP_PENDING completed")
        scm.confirm_process_exits()
        java_check("stop")
        java_check("stopped")
        fixture_control("start")
        snapshot = wait_for(lambda item: item is not None and item.get("state") == RUNNING, "fixture running again")
        scm.capture_process(snapshot, fixture)
        owned("before_remove")
        java_check("remove")
        wait_for(lambda item: item is None, "production service removal")
        scm.confirm_process_exits()
        java_check("missing")
        document["passed"] = True
    except BaseException as error:
        document["passed"] = False
        document["error"] = str(error)
        raise
    finally:
        try:
            if work is not None:
                cleanup()
        except BaseException as error:
            document["passed"] = False
            document["cleanupError"] = str(error)
            document["retainedFixtureDirectory"] = str(work)
            raise
        finally:
            scm.close()
            document["finishedUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            checkpoint(output, document)
    print("PASS: native Windows service checks; results " + str(output), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "ci/performance-windows-services.json")
    check(parser.parse_args())
