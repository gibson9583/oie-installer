"""Compare the Gradle engine build with the published release archive.

python3 ci/check_engine_parity.py <oie-engine-<version>.tar.gz> <oie_unix_<version>.tar.gz>

The installer builds the engine from its release tag. This check fails when that build differs from the
release in anything except:
- install4j's own files and launchers, which the installer replaces with its native launchers;
- the engine's portable launcher script, oieserver, and its PowerShell form;
- jar signatures (META-INF/*.SF, *.RSA, *.DSA, *.EC and the digest sections of MANIFEST.MF);
- the build date in version.properties.
"""

from __future__ import annotations

import hashlib
import io
import sys
import tarfile
import zipfile

INSTALL4J_ONLY = {"oiecommand", "oieservice", "oieserver"}
BUILD_ONLY = {"oieserver", "oieserver.ps1"}
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def files(path: str) -> dict[str, bytes]:
    """Regular files under the archive's single root folder."""
    found = {}
    with tarfile.open(path) as archive:
        for entry in archive:
            if not entry.isfile():
                continue
            name = entry.name.split("/", 1)[1] if "/" in entry.name else ""
            if name:
                found[name] = archive.extractfile(entry).read()
    return found


def manifest_main(data: bytes) -> list[str]:
    """MANIFEST.MF without per-entry digest sections, which signing adds."""
    text = data.decode("utf-8", "replace").replace("\r\n", "\n").replace("\n ", "")
    return [line for line in text.split("\n\n", 1)[0].splitlines() if line and not line.startswith(("Created-By:",))]


def jar_entries(data: bytes) -> dict[str, str]:
    entries = {}
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for info in jar.infolist():
            if info.is_dir():
                continue
            name = info.filename
            upper = name.upper()
            if upper.startswith("META-INF/") and upper.endswith(SIGNATURE_SUFFIXES):
                continue
            content = jar.read(info)
            if upper == "META-INF/MANIFEST.MF":
                entries[name] = "\n".join(manifest_main(content))
            elif name == "version.properties":
                entries[name] = "\n".join(line for line in content.decode("latin-1").splitlines() if not line.startswith(("mirth.date", "#")))
            else:
                entries[name] = hashlib.sha256(content).hexdigest()
    return entries


def main() -> int:
    built, release = files(sys.argv[1]), files(sys.argv[2])
    release = {name: data for name, data in release.items() if not name.startswith(".install4j/") and name not in INSTALL4J_ONLY}
    built = {name: data for name, data in built.items() if name not in BUILD_ONLY}
    problems = [f"only in the build: {name}" for name in sorted(built.keys() - release.keys())]
    problems += [f"only in the release: {name}" for name in sorted(release.keys() - built.keys())]
    identical = signed = 0
    for name in sorted(built.keys() & release.keys()):
        if built[name] == release[name]:
            identical += 1
        elif name.endswith(".jar"):
            a, b = jar_entries(built[name]), jar_entries(release[name])
            if a == b:
                signed += 1
            else:
                changed = sorted(key for key in a.keys() | b.keys() if a.get(key) != b.get(key))
                problems.append(f"{name}: {len(changed)} entries differ, for example {changed[:3]}")
        else:
            problems.append(f"{name}: content differs")
    print(f"{identical} files identical, {signed} jars identical apart from signatures or build date, {len(problems)} differences")
    for problem in problems:
        print("  " + problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
