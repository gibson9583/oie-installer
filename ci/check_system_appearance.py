"""Run read-only desktop appearance and observer lifecycle checks against compiled classes."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def check() -> None:
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    executable = lambda name: str(Path(java_home) / "bin" / (name + suffix)) if java_home else shutil.which(name)
    classpath = os.pathsep.join([str(ROOT / "target/classes"), str(ROOT / "target/lib/*")])
    with tempfile.TemporaryDirectory(prefix="oie-system-appearance-") as work:
        subprocess.run([executable("javac"), "-cp", classpath, "-d", work,
                        str(ROOT / "ci/SystemAppearanceCheck.java")], check=True, timeout=60)
        subprocess.run([executable("java"), "-Doie.check.python=" + sys.executable, "-cp", work + os.pathsep + classpath,
                        "org.openintegrationengine.installer.SystemAppearanceCheck"], check=True, timeout=20)


if __name__ == "__main__":
    check()
