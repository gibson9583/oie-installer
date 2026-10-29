"""Read and update the Windows launcher's embedded UAC manifest with the SDK."""

from __future__ import annotations

import os
from pathlib import Path
import stat
import subprocess
import tempfile
from xml.dom import minidom


UAC_NAMESPACE = "urn:schemas-microsoft-com:asm.v3"


def _manifest_tool() -> str:
    kits = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Windows Kits/10/bin"
    tools = sorted(kits.glob("10.*/x64/mt.exe")) or sorted(kits.glob("10.*/x86/mt.exe"))
    if not tools:
        raise RuntimeError("mt.exe from the Windows SDK is required to verify oiecommand.exe administrator elevation")
    return str(tools[-1])


def _execution_level(document: minidom.Document) -> minidom.Element:
    nodes = document.getElementsByTagNameNS(UAC_NAMESPACE, "requestedExecutionLevel")
    if len(nodes) != 1:
        raise RuntimeError("Expected exactly one UAC requestedExecutionLevel in the launcher manifest")
    return nodes[0]


def _extract(mt: str, exe: Path, destination: Path) -> minidom.Document:
    subprocess.run([mt, "-nologo", f"-inputresource:{exe};#1", f"-out:{destination}"], check=True)
    # Parsing bytes honors the XML encoding, including UTF-16 manifests.
    return minidom.parseString(destination.read_bytes())


def _verify(document: minidom.Document) -> None:
    level = _execution_level(document)
    if level.getAttribute("level") != "requireAdministrator" or level.getAttribute("uiAccess") != "false":
        raise RuntimeError("oiecommand.exe must request requireAdministrator with uiAccess=false, like install4j")


def verify_administrator(exe: Path) -> None:
    """Verify resource #1, which Windows uses when launching the executable."""
    mt = _manifest_tool()
    with tempfile.TemporaryDirectory(prefix="oie-uac-") as work:
        _verify(_extract(mt, exe, Path(work) / "embedded.manifest"))


def require_administrator(exe: Path) -> None:
    """Give oiecommand.exe install4j's execution level, preserving other metadata."""
    mt = _manifest_tool()
    with tempfile.TemporaryDirectory(prefix="oie-uac-") as work:
        manifest = Path(work) / "launcher.manifest"
        document = _extract(mt, exe, manifest)
        level = _execution_level(document)
        level.setAttribute("level", "requireAdministrator")
        level.setAttribute("uiAccess", "false")
        manifest.write_bytes(document.toxml(encoding="utf-8"))
        # jpackage marks its branded executables read-only. Restore that flag
        # after editing, including when mt.exe fails.
        mode = exe.stat().st_mode
        try:
            exe.chmod(mode | stat.S_IWRITE)
            subprocess.run([mt, "-nologo", "-manifest", str(manifest), f"-outputresource:{exe};#1"], check=True)
        finally:
            exe.chmod(mode)
        _verify(_extract(mt, exe, Path(work) / "verified.manifest"))
