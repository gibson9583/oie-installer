# OIE Installer — JavaFX + jpackage

A separate Java implementation of the OIE Installer, modeled on the sibling Tauri installer. The wizard uses native JavaFX controls and a Java installation backend. `jpackage` produces a self-contained application with its own runtime and a native distribution package.

This repository is independent of the Tauri app. It does not invoke Rust, Tauri, Node, a browser or a local web server at runtime. Python is used only by the build script to fetch and assemble release assets.

## Workflow

New installation:

**Welcome → License → Location → Java → Network → Database → Administrator → Service → Add-ons → Review → Install → Done**

Upgrade or reinstall:

**Welcome → License → Location → Java → Service → Add-ons → Review → Install → Done**

The destination determines the operation. A matching installed version means reinstall; an older engine means upgrade. Downgrades are refused. The wizard follows the existing installer’s dark/light palette, fonts and banner artwork, circular sidebar markers, sliding switches, Java/database selection cards, version badges, configuration fields, review/edit navigation and progress details. Controls use JavaFX skins while preserving native keyboard and accessibility behavior. Page and switch transitions can be disabled with the JVM option `-Doie.reduceMotion=true`.

The three bundled add-ons all default to **install**, independently selectable:

- **OIE Web Client**, delivered by Web Support. Its bundled WAR is served by the engine at `/oie-webadmin/`.
- **Role-Based Access Control (RBAC)**.
- **Simple Channel History**.

Review lists each choice. On upgrade or reinstall, selected add-ons replace their old directories after backing them up. Skipped add-ons remain installed if already present. Skipping does not uninstall them or guarantee that their existing versions support the new engine.

Welcome keeps hashes and archive/launcher provenance in collapsed Package details. License is a separate second step displaying the bundled MPL 2.0 terms; acceptance is required before continuing to Location. Done follows the Tauri layout with a success mark, Address beside Sign in or Backup, full-width lifecycle commands and Folder, and a right-aligned Close action. Both header columns share one border line.

See [packaging and service comparison](PACKAGING.md) for the current OIE install4j configuration, staged payloads, native launchers and system-service behavior.

## Build

Requirements: JDK 21 or later, Python 3.10+, and an engine source clone containing the matching release tag. Packaging must run on the target operating system. macOS builds also require the Xcode command-line tools.

```bash
python3 build.py --engine-repo ../oie --java-home /path/to/current/jdk-21/Contents/Home
```

By default the script resolves the latest published engine release and latest plugin releases, compares published checksums with GitHub asset digests, verifies the downloaded bytes, checks plugin engine compatibility, and records the engine release-tag commit. An incompatible latest plugin blocks the build.

The resulting payload manifest pins every staged engine/runtime file and add-on archive and records release provenance. The staged payload is bundled beside the application JAR. Packaging compiles with tests explicitly skipped.

Useful options:

```bash
# Pin an engine release; prepare only the payload.
python3 build.py --version 4.7.0 --engine-repo ../oie --payload-only

# Force fresh release downloads instead of verified cache reuse.
python3 build.py --engine-repo ../oie --refresh

# Repackage the existing pinned payload without fetching release metadata.
python3 build.py --app-only --java-home /path/to/jdk-21 --type dmg

# Produce only a runnable app image.
python3 build.py --app-only --type app-image
```

The Maven Wrapper (`mvnw`) supplies Maven 3.9.12. Each platform builds on that platform. The outputs match the installer platforms of the OIE install4j media:

| Platform | Output in `dist/` |
| --- | --- |
| macOS ARM64 and x64 | `OIE-Installer-JavaFX-<version>-macos-<arch>.dmg` |
| Windows x64 and ARM64 | `OIE-Installer-JavaFX-<version>-windows-<arch>.exe` |
| Linux x64 and ARM64 | `OIE-Installer-JavaFX-<version>-linux-<arch>.tar.gz` |

Each output has a `.sha256` file and `dist/build-receipt.json`.

Windows EXEs are an NSIS self-extracting wrapper around the jpackage app image. The first screen is the JavaFX wizard. NSIS 3 is a Windows build prerequisite. JavaFX 21 has no Windows ARM64 build. The Windows ARM64 wizard is x64 and runs under emulation. Its engine runtime is native ARM64; pass that JDK with `--engine-java-home`. The Linux archive holds the app image; run `bin/OIE Installer JavaFX` as root.

GitHub Actions builds all six outputs. Each job then installs the engine unattended as a service and as a process, runs `oieservice` and `oiecommand`, loads the OIE Web Client, and uninstalls it.

The build explicitly ad-hoc signs the macOS application, wraps the finished jpackage image with `hdiutil`, and verifies its signature and every payload hash inside the DMG before handoff. Developer ID signing and notarization are still needed for public distribution. DMG creation does not request Finder Automation access.

The build JDK becomes the installer’s bundled runtime. Use a currently patched JDK when rebuilding; jpackage does not update that embedded runtime later. The current local distribution was built using Temurin 21.0.13+9 and JavaFX 21.0.13.

## Running from source

Prepare the payload, then compile and launch with a system JDK:

```bash
mvn -Dmaven.test.skip=true package
java -jar target/oie-installer-jpackage.jar
```

Maven writes runtime dependencies to `target/lib/`; the application JAR’s manifest references them. JavaFX runs on the class path using a separate launcher class. No WebView is included. JavaFX may print its standard warning about class-path configuration when run this way.

## Engine runtime and native launchers

The engine has its **own bundled Java 21 runtime**, separate from the wizard. Removing the installer does not remove the engine runtime. Java defaults to the bundled runtime; an existing Java 17+ executable can be selected instead. The selection is written to `conf/custom.vmoptions`.

Jpackage generates native `oieserver`, `oieservice`, `oiecommand` and uninstaller launchers. `oieservice` accepts the install4j verbs `start`, `stop`, `restart`, `force-reload`, `status` and `run`. With a registered service, `start`, `stop` and `restart` use the service manager and need administrator rights. `status` exits 0 when the engine runs and 3 when it does not. Without a verb, `oieservice` runs the engine in the foreground for the service manager. Windows also receives the install4j Start Menu group: **Open Integration Engine** (the command line) and **Uninstall Open Integration Engine**. The engine bridge preserves the working directory and OIE's VM-options includes, classpath directives, Java override and command-line arguments. macOS uses a nested engine app with small top-level launcher scripts; Windows uses native EXEs in the engine folder.

The build extracts the verified official release **at build time**, removes install4j-generated launchers, uninstallers and `.install4j`, and generates the native engine image. A per-file hash/mode manifest covers the staged engine, its runtime, native launchers and three add-on ZIPs. The compressed source archive remains only in the download cache and provenance record.

## Installation behavior

The reviewed JavaFX UI collects choices and shows the complete Review before asking for OS administrator approval. A separate helper performs privileged actions; the GUI stays in the user's desktop session. The helper's private job files carry progress and the result, then are removed after it exits.

System-service installation defaults on and can be turned off. macOS registers a boot-time launch daemon under `/Library/LaunchDaemons`, running as the installing user. Windows registers an automatic service using the pinned WinSW 2.12.0 wrapper, running as Local Service. It also creates an Apps & Features entry for the engine. Existing install4j services are recognized only when their executable points to the selected engine folder. Previous per-user services can be migrated during upgrade. Service records are validated before stop/removal.

The destination determines new install, upgrade or reinstall. An action-time recheck rejects changed mode/version. A shared administrator lock serializes operations against a destination. Program replacement follows a complete stop and backup of configuration/data, receipt and service definition. Configuration, database, custom libraries and unselected add-ons are preserved; selected add-ons are backed up and replaced. No database rollback is attempted on failure.

The engine receives an independent uninstaller using its own runtime and backend. Windows removal waits for owned executables to exit before deleting locked files. Existing engine folders and shared resources are untouched by the build itself.

See [PACKAGING.md](PACKAGING.md) for architecture and [PARITY.md](PARITY.md) for the implementation and manual-validation matrix.

## Unattended installation

```bash
"target/image/OIE Installer JavaFX.app/Contents/MacOS/OIE Installer JavaFX" --unattended plan.json
```

```json
{
  "installDir": "/Applications/OpenIntegrationEngine",
  "javaCmd": "@bundled",
  "httpPort": 8080,
  "httpsPort": 8443,
  "database": "derby",
  "adminPassword": "replace-with-a-long-unique-password",
  "service": false,
  "plugins": {
    "websupport": true,
    "rbac": true,
    "simple-channel-history": true
  }
}
```

External databases use `databaseUrl`, `databaseUsername` and `databasePassword`. Missing add-on choices default to true. Invalid or unknown choices are rejected. Existing-engine upgrades use the existing database, network and administrator settings. The command prints progress and a result with the password redacted. Protect and delete your plan file when finished; it contains credentials.

Exit codes: `0` success; `1` operational failure; `2` invalid arguments or prerequisites.

## Uninstall

Each installed engine receives an `oie-uninstall` shell script, which can run independently of the installer app. It asks before removing the engine, its database, logs and backups. `--yes` skips that confirmation.

The application also supports `--uninstall <engine folder> --yes`. It verifies the installer receipt, stops the engine and removes its service before deleting the folder.

## Scope and limitations

- The installer always bundles Java. There are no separate install4j-style "without JRE" media; an existing Java 17+ can still run the engine.
- Linux has no administrator prompt. Run the Linux wizard or unattended install as root.
- Windows ARM64 uses the x64 WinSW service wrapper under emulation.
- On Windows, a process-mode engine ignores the console stop request. Stopping, upgrading or uninstalling it ends its process tree after 50 seconds, as the WinSW stop timeout does for the service.
- `oiecommand.exe` does not request elevation, and the launchers do not enforce install4j's single-instance check.
- External database connections are not tested by the wizard.
- A failed operation stops and reports its error, preserving completed changes and backups. There is no automatic whole-engine rollback, database rollback, interrupted-upgrade resumption or automatic update service.
- Archive paths and links are checked before extraction. Backups refuse links or special files when a complete backup cannot be established.
- Loopback readiness checks accept the engine’s self-signed certificate only for individual localhost HTTPS connections; global TLS settings are unchanged.
- Pinned hashes protect integrity. Public release authentication still requires properly signed/notarized installer distributions and a trusted release pipeline.
- The CI smoke test covers new installs and uninstall. Upgrades and the GUI are validated manually.

## Source map

- `Wizard.java`: native wizard, theme, forms, navigation and progress.
- `Ui.java`: switch and option-card skins, geometric icons and page transitions.
- `Installer.java`: new install, upgrade/reinstall, receipts, CLI lifecycle and uninstall.
- `Model.java`: plan validation, engine detection, processes, paths and Java discovery.
- `Payload.java` / `Archives.java`: pinned payload verification and safe extraction.
- `Privileges.java`: administrator helper and private progress/results channel.
- `Services.java` / `NativeProcesses.java`: system services, native startup, uninstall and Windows registration.
- `EngineBootstrap.java`: standalone entry point for the native engine launchers.
- `Readiness.java`: local engine startup checks.
- `build.py`: release acquisition, Maven compilation and jpackage distribution.

Licensed under MPL-2.0. The release acquisition and installation flow are adapted from the sibling OIE Installer; copied OIE artwork retains its original ownership.
