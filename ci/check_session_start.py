"""Check session startup; --engine also exercises the real macOS engine and AppleScript handoff."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def check(engine: bool) -> None:
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    executable = lambda name: str(Path(java_home) / "bin" / (name + suffix)) if java_home else shutil.which(name)
    classpath = os.pathsep.join([str(ROOT / "target/classes"), str(ROOT / "target/lib/*")])
    evidence = ROOT / "target/session-start-check"
    evidence.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="oie-session-classes-") as work:
        sources = [str(ROOT / "ci/SessionStartCheck.java")]
        if engine:
            sources.append(str(ROOT / "ci/SessionStartEngineCheck.java"))
        subprocess.run([executable("javac"), "-cp", classpath, "-d", work, *sources], check=True, timeout=60)
        runtime = work + os.pathsep + classpath
        subprocess.run([executable("java"), "-cp", runtime, "org.openintegrationengine.installer.SessionStartCheck"], check=True, timeout=20)
        if engine:
            backend = Path(work) / "oie-installer-jpackage.jar"
            shutil.copy2(ROOT / "target/oie-installer-jpackage.jar", backend)
            shutil.copytree(ROOT / "target/lib", Path(work) / "lib")
            runtime = os.pathsep.join([work, str(backend), str(Path(work) / "lib/*")])
            with (evidence / "check.log").open("w") as log:
                process = subprocess.Popen([executable("java"), "-Doie.payload=" + str(ROOT / "payload"),
                                            "-Doie.check.backend=" + str(backend), "-cp", runtime,
                                            "org.openintegrationengine.installer.SessionStartEngineCheck", str(evidence)],
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                for line in process.stdout:
                    print(line, end="", flush=True)
                    log.write(line)
                    log.flush()
                if process.wait() != 0:
                    raise subprocess.CalledProcessError(process.returncode, process.args)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", action="store_true")
    check(parser.parse_args().engine)
