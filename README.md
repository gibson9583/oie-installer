# Open Integration Engine Installer

The Open Integration Engine Installer installs, upgrades and removes Open Integration Engine (OIE) on Windows, macOS and Linux.

The installer uses JavaFX for its desktop wizard and `jpackage` for its application runtime and native launchers. Each package contains the engine and optional add-ons. Installation runs without downloading additional files.

![Welcome](docs/images/welcome.png)

## Packages

The build produces two packages for each platform:

- **With Java:** includes a Java 21 runtime for the engine. The filename includes `-with-JRE`.
- **Without engine Java:** uses an installed Java 17 or later. The installer and its launchers include their own application runtimes.

| Platform | Without engine Java | With Java |
| --- | --- | --- |
| Windows x64 | `oie_windows-x64_<version>.exe` | `oie_windows-x64_<version>-with-JRE.exe` |
| Windows ARM64 | `oie_windows-arm64_<version>.exe` | `oie_windows-arm64_<version>-with-JRE.exe` |
| macOS Apple silicon | `oie_macos_<version>-aarch.dmg` | `oie_macos_<version>-aarch-with-JRE.dmg` |
| macOS Intel | `oie_macos_<version>-x86.dmg` | `oie_macos_<version>-x86-with-JRE.dmg` |
| Linux x64 | `oie_unix_<version>.sh` | `oie_unix_<version>-with-JRE.sh` |
| Linux ARM64 | `oie_unix_<version>-aarch64.sh` | `oie_unix_<version>-aarch64-with-JRE.sh` |

`<version>` uses underscores, for example `4_7_0`. Each package has a `.sha256` checksum file.

On Windows ARM64, the wizard runs as an x64 application under emulation. The bundled engine runtime is ARM64.

macOS packages use ad hoc signing. Windows packages are unsigned.

## Install

1. Start the package for your operating system:
   - Windows: run the `.exe`.
   - macOS: open the `.dmg`, then open **Open Integration Engine Installer**.
   - Linux: run `sudo sh oie_unix_<version>.sh` from a desktop session.
2. Read the license and select **I accept**.
3. Select the engine folder.
4. Select the Java runtime for the engine.
5. Set the network ports and database.
6. Generate an administrator password or enter one with at least 15 characters.
7. Select **Run the engine as a service** to start it at boot. Clear this option to run it as a normal process.
8. Select the add-ons.
9. Check the settings on the review screen and select **Install**.
10. Approve the request for administrator access when prompted.

![Location](docs/images/location.png)
![Service](docs/images/service.png)
![Add-ons](docs/images/plugins.png)
![Review](docs/images/review.png)

The installer verifies each bundled file against its SHA-256 hash. It then installs the engine files, selected add-ons, launchers and uninstaller. Service installations also register a system service.

The installer starts the engine and waits for two startup checks:

- A new `server successfully started` entry in `logs/mirth.log`.
- An open HTTPS port.

The completion screen shows the engine address, installation folder and administrator credentials. Save the generated password before closing the installer.

![Done](docs/images/done.png)

### Database and administrator account

New installations support Derby, PostgreSQL, MySQL, Microsoft SQL Server and Oracle. External databases require a JDBC URL and database credentials.

The engine applies the initial administrator password when it creates the database. The installer supplies this password through `server.initialadminpassword` in `conf/mirth.properties`. It removes the setting after the startup check, including when startup fails.

### Add-ons

These add-ons are bundled and selected by default:

- **OIE Web Client:** provides the web administrator through the Web Support add-on at `/oie-webadmin/`.
- **Role-Based Access Control (RBAC):** provides role and permission management.
- **Simple Channel History:** records channel revisions.

The review screen shows the version of each selected add-on.

### Unattended installation

On Linux, use `-q` to install with the default settings:

```bash
sudo sh oie_unix_4_7_0-with-JRE.sh -q -dir /opt/openintegrationengine
```

This command uses Derby and selects available HTTP and HTTPS ports, starting at 8080 and 8443. It selects all bundled add-ons and registers a service when supported. The output includes the generated administrator password.

For custom settings, save an installation plan as JSON:

```json
{
  "installDir": "/opt/openintegrationengine",
  "javaCmd": "@bundled",
  "httpPort": 8080,
  "httpsPort": 8443,
  "database": "derby",
  "adminPassword": "replace-with-a-long-unique-password",
  "service": true,
  "plugins": {
    "websupport": true,
    "rbac": true,
    "simple-channel-history": true
  }
}
```

Run the installer with the plan:

```bash
sudo sh oie_unix_4_7_0-with-JRE.sh --unattended /path/to/plan.json
```

- `installDir` is the full path to the engine folder.
- `javaCmd` is `@bundled` or the full path to a `java` executable.
- Packages without engine Java select an installed Java 17 or later when `javaCmd` is `@bundled`.
- `database` accepts `derby`, `postgres`, `mysql`, `sqlserver` or `oracle`.
- External databases also require `databaseUrl`, `databaseUsername` and `databasePassword`.
- `service` selects service installation. Set it to `false` to start a normal process.
- Set an add-on to `false` to exclude it.

Delete the plan after installation. It contains passwords.

The installer exits with `0` for success, `1` for an installation error and `2` for invalid arguments or settings.

## Upgrade and reinstall

Run the installer and select the existing engine folder. The installer detects the engine version and preserves its database settings, network ports and credentials. Select the Java runtime, service option and add-ons for the installation.

Close any Windows console engine before starting the upgrade.

During an upgrade or reinstall, the installer:

1. Stops the engine and its service.
2. Copies `conf` and `appdata` to `backups/<timestamp>-<version>`.
3. Moves the previous program files to the backup and removes the service registration.
4. Installs the new program files and selected add-ons.
5. Preserves configuration, database files, logs, custom libraries and unselected add-ons.
6. Registers the service if selected and starts the engine.

Preserved configuration files stay in place. Their new defaults go to `backups/<timestamp>-<version>/defaults-<new-version>/conf/` for review. The installer updates the engine's VM option includes and selected Java path.

### Rollback

Errors during file installation, configuration or service setup trigger rollback. The installer removes the new program files and registrations added during that operation.

For an upgrade, rollback preserves the configuration, database and logs. The previous program files remain in `backups/<timestamp>-<version>/program/`. Retry the installation on the same folder to complete the upgrade.

Startup verification errors leave the installation in place. Check `logs/mirth.log` and the service logs to diagnose the startup error.

## Run the engine

The engine folder contains three launchers:

| Launcher | Purpose |
| --- | --- |
| `oieserver` | Starts the engine in the foreground |
| `oieservice` | Controls the engine service or background process |
| `oiecommand` | Runs the engine command-line client |

Windows launchers use the `.exe` extension. `oiecommand.exe` requests administrator access.

### Services and shortcuts

| Item | Windows | macOS | Linux |
| --- | --- | --- | --- |
| Service | `Open Integration Engine Service` | `com.oie.oieservice` | `oieservice.service` |
| Account | Local System | root | root |
| Service definition | `oie-service.xml` in the engine folder | `/Library/LaunchDaemons/com.oie.oieservice.plist` | `/etc/systemd/system/oieservice.service` |
| Shortcuts | Start Menu group **Open Integration Engine** | None | `/usr/local/bin/oieservice`, `/usr/local/bin/oiecommand` |

The installer supports one registered engine service per computer. Services start automatically at boot.

Windows uses the WinSW 2.12.0 wrapper, `oie-service.exe`, to run `oieservice.exe`. Linux uses systemd when `/run/systemd/system` exists. Systems with an init.d service manager use `/etc/init.d/oieservice`.

### Service commands

| Command | Action |
| --- | --- |
| `start` | Starts the registered service or a background engine process |
| `stop` | Stops the registered service or background engine process |
| `restart`, `force-reload` | Stops the engine, then starts it |
| `status` | Exits `0` when the engine runs or `3` when it is stopped |
| `run`, `start-launchd` | Runs the engine in the foreground |
| `/install`, `/uninstall` | Registers and starts the service, or stops and removes it |

Use administrator privileges to change a registered service. On macOS and Linux, run service commands with `sudo`.

On Windows, `oieservice.exe` controls the registered service. Close the console window to stop an engine started with `oieserver.exe`.

### Java runtime

The installer records the engine's Java executable in `conf/custom.vmoptions`:

```text
-java-cmd /path/to/java
```

To change the runtime, stop the engine, update this path and restart the engine. The selected runtime must be Java 17 or later.

## Uninstall

The uninstaller deletes the engine folder, including its local database files, logs and backups.

1. Close a Windows console engine if one is running.
2. Start the uninstaller:
   - Windows: uninstall **Open Integration Engine** through **Apps & Features**, or run `uninstall.exe` in the engine folder.
   - macOS and Linux: run `sudo "/path/to/engine/uninstall"`.
3. Type `yes` to confirm removal. Use `--yes` to skip confirmation.

The uninstaller stops the registered engine service and removes its service definition, shortcuts and application registration.

## Build from source

Requirements:

- JDK 21 or later, including `jpackage`.
- Python 3.12 or later.
- An engine repository clone with the requested release tag.
- Windows: NSIS 3 and the Windows SDK with `mt.exe`.
- macOS: the Xcode command-line tools.

The Maven Wrapper supplies Maven. Build on the operating system and architecture of the target package.

```bash
python3 build.py --engine-repo ../oie --java-home /path/to/jdk-21
```

The build downloads the engine release and compatible add-ons. It verifies the downloaded files against published SHA-256 checksums and checks available GitHub asset digests.

It stages the engine files and generates native launchers with `jpackage`. On Windows, it sets and verifies the command-line launcher's administrator execution level.

The build writes file hashes to `payload/manifest.json`. It verifies these hashes after packaging the application image. Both package variants and their `.sha256` files go to `dist/`.

| Option | Effect |
| --- | --- |
| `--version 4.7.0` | Builds a specific engine release; the default is the latest release |
| `--media with-jre`, `--media no-jre` | Builds one package variant |
| `--app-only` | Reuses the prepared payload inputs |
| `--type app-image` | Builds application folders without package wrappers |
| `--engine-java-home <jdk>` | Selects the JDK for the engine launchers and bundled runtime |
| `--refresh` | Downloads the release files again |

Windows ARM64 builds use an x64 JDK for the JavaFX wizard. Pass an ARM64 JDK through `--engine-java-home` for the engine.

### Validation

Run the installer support regression tests:

```bash
python3 -m unittest discover -s tests -v
```

Windows tests check the installed JDK's launcher through the Windows SDK. They also check application registration using temporary registry keys. Other platforms skip these native tests.

After building the installer, run the filesystem cleanup checks:

```bash
python3 ci/check_file_cleanup.py
```

These checks cover recursive deletion, read-only files, symbolic links and repeated cleanup. Windows checks also cover locked files and retry after closing a file handle.

[GitHub Actions](.github/workflows/build.yml) builds both package variants for all six platform and architecture combinations. Each job runs installation tests against the packaged application images.

The tests cover:

- New installations as a service and a normal process.
- Launchers, service definitions, links and Windows Start Menu entries.
- Service operations, command-line access and the OIE Web Client.
- Reinstallation over an existing engine.
- Upgrades from OIE 4.6.0, including an existing running service.
- Failed upgrades, rollback and retry.
- Installation with an external Java runtime.
- Engine removal.

Desktop wizard interaction, external databases and Linux init.d service operation require separate validation.

## Implementation

The wizard collects the installation plan and passes it to the installation backend. The backend uses an elevated helper when it needs administrator approval. Engine launchers start the selected Java runtime with the engine's VM options.

| Source | Responsibility |
| --- | --- |
| [Wizard.java](src/main/java/org/openintegrationengine/installer/Wizard.java), [Ui.java](src/main/java/org/openintegrationengine/installer/Ui.java), [installer.css](src/main/resources/installer.css) | Wizard screens, controls and theme |
| [Installer.java](src/main/java/org/openintegrationengine/installer/Installer.java) | Installation, upgrades, rollback and removal |
| [Services.java](src/main/java/org/openintegrationengine/installer/Services.java) | Service definitions and registration |
| [NativeProcesses.java](src/main/java/org/openintegrationengine/installer/NativeProcesses.java) | Engine processes, uninstaller, shortcuts and application registration |
| [ServiceControl.java](src/main/java/org/openintegrationengine/installer/ServiceControl.java), [EngineBootstrap.java](src/main/java/org/openintegrationengine/installer/EngineBootstrap.java) | Service commands and engine launchers |
| [Model.java](src/main/java/org/openintegrationengine/installer/Model.java), [Payload.java](src/main/java/org/openintegrationengine/installer/Payload.java), [Archives.java](src/main/java/org/openintegrationengine/installer/Archives.java) | Engine detection, payload verification and extraction |
| [Readiness.java](src/main/java/org/openintegrationengine/installer/Readiness.java) | Startup checks |
| [Privileges.java](src/main/java/org/openintegrationengine/installer/Privileges.java) | Administrator approval and helper process |
| [build.py](build.py) | Release downloads and package creation |
| [windows_manifest.py](windows_manifest.py) | Windows launcher elevation and manifest verification |
| [ci/smoke_install.py](ci/smoke_install.py) | Installation tests |
| [ci/check_file_cleanup.py](ci/check_file_cleanup.py) | Filesystem cleanup checks |

## License

MPL-2.0. The OIE artwork belongs to its owners.
