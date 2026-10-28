# Native packaging and install4j parity

The implementation follows the released OIE **4.7.0** configuration (install4j **13.1.1**), retaining the reviewed JavaFX UI. The local engine working-copy configuration is older, so the release tag is the authority.

## Build and distribution

1. Download and verify the current engine archive and all three add-on ZIPs against published release checksums and GitHub digests.
2. Extract the engine **at build time**, preserving the published engine JARs, configuration and built-in extensions.
3. Remove install4j launchers, runtime metadata and uninstallers. Generate native server/service/CLI launchers with jpackage, plus a separate complete Java 21 runtime for the engine.
4. Pin every staged engine/runtime file and its executable mode, plus add-on ZIP hashes, in the payload manifest. Keep the original release archive digest and commit as provenance.
5. Bundle the staged payload in the JavaFX installer. macOS receives a DMG containing the app; Windows receives an NSIS self-extracting EXE containing the jpackage app image and opening the custom UI directly.

On macOS, sign the wizard before copying in the already-signed, pinned engine image, then seal only the outer wizard bundle. Recursive signing after embedding the payload would change engine binaries and invalidate their recorded hashes. Apple's `hdiutil` wraps that final jpackage app image in a DMG without changing its contents; jpackage's DMG bundler would recursively re-sign an ad-hoc image. The build verifies every pinned file in the final application image. It also mounts the completed DMG read-only, checks its app signature and verifies every payload hash before publishing the artifact; only that temporary mount is detached.

No tar.gz is shipped inside the new installer. The verified source archives remain in the build download cache. Engine files are copied from the staged tree at installation time; plugin ZIPs are unpacked only when selected.

The engine's runtime lives in its installation, independently of the wizard. Both runtimes use the selected build JDK: the local macOS build uses Temurin 21.0.13+9, while Windows CI resolves the latest available Temurin Java 21 for Windows using setup-java v5. Platform releases can arrive at different times. The exact JDK version is recorded in the build receipt. The engine image includes the full JDK module set and the Java command. This avoids guessing which modules existing extensions need.

## Native launcher behavior

Jpackage generates `oieserver`, `oieservice`, `oiecommand` and the uninstaller entry point (native EXEs on Windows). The standalone EngineBootstrap resolves the engine folder, reads OIE's VM-options files, honors includes and classpath directives, and launches the real engine/CLI main class in the correct working directory. A child JVM lets changes to `conf/custom.vmoptions` and external Java selection take effect before the engine JVM starts. macOS exposes small top-level shell entry points into the nested native engine app.

Unlike changing `user.dir` in a running JVM, ProcessBuilder's working directory also governs native relative filesystem calls. The released `MirthLauncher` and `CommandLineLauncher` use relative configuration/library paths, so this is required.

## Service ownership and elevation

The UI requests OS administrator approval only after Review. A separate Java helper performs installation, system registration, stop/replace and uninstall actions. The desktop UI remains in the user's session. Private transient job files carry credentials, progress and the result; they are removed after the helper exits.

macOS uses a root-owned plist in `/Library/LaunchDaemons`, starting at boot under the installing user's identity. Windows uses a pinned WinSW 2.12.0 wrapper and automatic SCM registration, running as **Local Service**, with access to engine configuration, logs and data. This deliberately avoids running the engine as root/LocalSystem by default. External database integrated authentication and network-resource access must fit that service identity. See the [WinSW 2.12 account contract](https://github.com/winsw/winsw/blob/v2.12.0/doc/xmlConfigFile.md#service-account).

Legacy install4j services are recognized only when their executable points to the selected engine folder. The existing service is stopped, its definition backed up, and its registration removed before replacement. Earlier launch agents/systemd user units are also recognized. Unknown or mismatched service ownership stops the operation.

Windows registers an engine entry in Apps & Features with its own independent uninstaller. Removal stops the service/process, removes registration and waits for engine-owned executables before deleting locked files. macOS supplies an independent uninstaller using the installed engine runtime and backend.

## Package-manager distinction

Jpackage provides native application images and launchers here. The Java backend owns engine deployment, configuration, migration and service registration, as install4j actions did. It supports the selected engine folder and preservation of mutable files. The engine is **not** represented as a separately installed macOS PKG or Windows MSI.

Jpackage can register services while installing a native engine package, but applying that flag to the wizard would register the wizard itself. Native PKG/MSI ownership would require a different location/data/upgrade model. The current Windows EXE and macOS DMG retain one custom installer workflow. See the [jpackage command reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html).

## Upgrade and release boundaries

Upgrades stop the selected engine before backing up configuration, data, receipt and service definition. They preserve mutable configuration, database, logs, custom libraries and unselected add-ons; selected add-ons are replaced with backed-up copies. New engine defaults are retained in the backup when existing configuration differs. Global destination locks and an action-time mode/version check protect concurrent/stale installer actions.

There is no automatic database rollback, interrupted-upgrade resume or background update feed. Install4j's current configuration does not supply an OIE background updater either; installing a newer release remains an explicit operation. Public distribution still requires publisher signing/notarization and manual installation validation. Compilation, native packaging and static UI verification do not establish production runtime parity.

Evidence: [OIE 4.7.0 configuration](https://github.com/OpenIntegrationEngine/engine/blob/v4.7.0/tools/install4j/oie-installer-config.install4j), actual released Unix/Windows archive layouts, release-tag engine/CLI launchers, JDK 21.0.13 jpackage sources/templates and [PARITY.md](PARITY.md).
