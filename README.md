# Open Integration Engine Installer

The Open Integration Engine Installer installs, upgrades and removes Open Integration Engine (OIE). It produces the same media, launchers, services and registrations as the OIE install4j installers. It is a Java application with a JavaFX wizard, packaged with `jpackage`.

![Welcome](docs/images/welcome.png)

## Downloads

Each release has two media for each platform. The `-with-JRE` media include a Java 21 runtime for the engine. The other media use a Java 17 or later that is already installed.

| Platform | Without Java | With Java |
| --- | --- | --- |
| Windows x64 | `oie_windows-x64_<version>.exe` | `oie_windows-x64_<version>-with-JRE.exe` |
| Windows ARM64 | `oie_windows-arm64_<version>.exe` | `oie_windows-arm64_<version>-with-JRE.exe` |
| macOS Apple silicon | `oie_macos_<version>-aarch.dmg` | `oie_macos_<version>-aarch-with-JRE.dmg` |
| macOS Intel | `oie_macos_<version>-x86.dmg` | `oie_macos_<version>-x86-with-JRE.dmg` |
| Linux x64 | `oie_unix_<version>.sh` | `oie_unix_<version>-with-JRE.sh` |
| Linux ARM64 | `oie_unix_<version>-aarch64.sh` | `oie_unix_<version>-aarch64-with-JRE.sh` |

`<version>` uses underscores, for example `4_7_0`. Each file has a `.sha256` file.

## Install

1. Start the installer:
   - Windows: run the `.exe`.
   - macOS: open the `.dmg`, then open **Open Integration Engine Installer**.
   - Linux: run `sudo sh oie_unix_<version>.sh`.
2. Read the license, then select **I accept**.
3. Select the engine folder. For an upgrade, select the folder of the installed engine.
4. Select the Java runtime that runs the engine.
5. For a new engine, set the network ports, the database and the administrator password.
6. Select **Run the engine as a service**, or clear it to start the engine as a normal process.
7. Select the add-ons.
8. Check the review, then select **Install**. The operating system asks for administrator approval.

![Location](docs/images/location.png)
![Service](docs/images/service.png)
![Add-ons](docs/images/plugins.png)
![Review](docs/images/review.png)

The installer then:

1. Verifies the SHA-256 hash of every bundled file.
2. Installs the engine files and the selected add-ons.
3. Registers the service, the uninstaller and the launchers.
4. Starts the engine.
5. Waits for the engine to log `server successfully started` and open its HTTPS port.
6. Removes the initial administrator password from `conf/mirth.properties`.

![Done](docs/images/done.png)

The installer does not sign in to the engine. The engine sets the administrator password once, when it creates its database.

### Add-ons

All three add-ons are bundled and selected by default:

- **OIE Web Client**, served by the Web Support add-on at `/oie-webadmin/`.
- **Role-Based Access Control (RBAC)**.
- **Simple Channel History**.

### Unattended installation

The installer accepts the install4j options:

```bash
sudo sh oie_unix_4_7_0.sh -q -dir /opt/openintegrationengine
```

`-q` installs with the default settings. It runs the engine as a service and prints the generated administrator password once.

For other settings, write a plan file and run the installer with `--unattended plan.json`:

```json
{
  "installDir": "/opt/openintegrationengine",
  "javaCmd": "@bundled",
  "httpPort": 8080,
  "httpsPort": 8443,
  "database": "derby",
  "adminPassword": "replace-with-a-long-unique-password",
  "service": true,
  "plugins": { "websupport": true, "rbac": true, "simple-channel-history": true }
}
```

- `javaCmd` is `@bundled` or the path to a `java` executable. Media without Java select an installed Java 17 or later when `javaCmd` is `@bundled`.
- External databases use `databaseUrl`, `databaseUsername` and `databasePassword`.
- Delete the plan file after the installation. It contains passwords.

Exit codes: `0` success, `1` failure, `2` invalid arguments.

## Upgrade

Run the installer and select the folder of the installed engine. The installer upgrades engines installed by install4j and engines installed by this installer.

1. The installer stops the engine and its service.
2. It copies `conf` and `appdata` to `backups/<date>-<version>`.
3. It removes the previous program files, service and registrations, as install4j does.
4. It installs the new program files. It keeps the configuration, database, logs, custom libraries and other extensions.

Settings files that differ from the new defaults stay in place. The new defaults go to the backup folder.

### Rollback

When the installation fails before the engine starts, the installer rolls back like install4j:

- It removes the new program files, the service and the registrations.
- It keeps the settings, the database and the logs.
- The previous program files stay in the backup folder.

Run the installer again on the same folder to complete the upgrade.

## Installed engine

| Item | Windows | macOS | Linux |
| --- | --- | --- | --- |
| Launchers | `oieserver.exe`, `oieservice.exe`, `oiecommand.exe` | `oieserver`, `oieservice`, `oiecommand` | `oieserver`, `oieservice`, `oiecommand` |
| Service | `Open Integration Engine Service`, Local System, automatic | `/Library/LaunchDaemons/com.oie.oieservice.plist`, root | `/etc/systemd/system/oieservice.service`, root |
| Shortcuts | Start Menu group **Open Integration Engine** | None | `/usr/local/bin/oieservice`, `/usr/local/bin/oiecommand` |
| Uninstaller | `uninstall.exe`, in Apps & Features | `uninstall` | `uninstall` |

- One engine service exists on each computer, as with install4j.
- Linux systems without systemd get `/etc/init.d/oieservice`.
- `oiecommand.exe` asks for administrator approval.
- On Windows, the service runs `oieservice.exe` through the WinSW 2.12.0 service wrapper, `oie-service.exe`.

### oieservice

`oieservice` accepts the install4j commands:

| Command | Action |
| --- | --- |
| `start` | Starts the engine service, or starts the engine in the background |
| `stop` | Stops the engine service or the background engine |
| `restart`, `force-reload` | Stops, then starts |
| `status` | Exits `0` when the engine runs, `3` when it does not |
| `run`, `start-launchd` | Runs the engine in the foreground |
| `/install`, `/uninstall` | Registers or removes the engine service |

Service commands need administrator rights. On Windows, `oieservice.exe` controls only the service. An engine started with `oieserver.exe` stops when you close its window.

### Java

The engine runs on the Java runtime in `conf/custom.vmoptions`, set by the line `-java-cmd <path>`. Change the line to use a different Java 17 or later.

## Uninstall

1. Run the uninstaller:
   - Windows: open **Apps & Features**, then uninstall **Open Integration Engine**, or run `uninstall.exe`.
   - macOS and Linux: run `sudo <engine folder>/uninstall`.
2. Type `yes` to confirm. `--yes` skips the confirmation.

The uninstaller stops the engine, removes its service and registrations, and deletes the engine folder. This includes the database, the logs and the backups.

## Build

Requirements:

- JDK 21 or later. Maven comes from the Maven Wrapper (`mvnw`).
- Python 3.10 or later.
- A clone of the engine repository with the release tag.
- Windows: NSIS 3 and the Windows SDK (`mt.exe`).
- macOS: the Xcode command-line tools.

Each platform builds its own media.

```bash
python3 build.py --engine-repo ../oie --java-home /path/to/jdk-21
```

The build:

1. Downloads the engine release and the three add-ons.
2. Checks every download against the published checksums and the GitHub digests.
3. Removes the install4j launchers and runtime from the release.
4. Creates the native launchers with `jpackage`.
5. Writes `payload/manifest.json`, which pins the hash of every file.
6. Writes both media and their `.sha256` files to `dist/`.

Options:

| Option | Effect |
| --- | --- |
| `--version 4.7.0` | Builds a specific engine release |
| `--media with-jre`, `--media no-jre` | Builds one media variant |
| `--app-only` | Reuses the existing payload |
| `--type app-image` | Builds the application folders only |
| `--engine-java-home <jdk>` | Uses a different JDK for the engine launchers and runtime |
| `--refresh` | Downloads the release files again |

On Windows ARM64, the installer application uses an x64 JDK, because JavaFX 21 has no Windows ARM64 build. Pass an ARM64 JDK with `--engine-java-home` for the engine.

### Continuous integration

GitHub Actions builds all media on six runners. Each job then tests the media:

1. Installs a new engine as a service and as a normal process.
2. Checks the install4j launchers, service, links and Start Menu entries.
3. Runs `oieservice` and `oiecommand`, and loads the OIE Web Client.
4. Reinstalls the engine.
5. Upgrades a 4.6.0 install4j engine, including a failed upgrade and its rollback.
6. Upgrades an engine installed by the 4.6.0 install4j installer, with its service.
7. Installs the media without Java with `-q -dir`.
8. Uninstalls each engine.

The **Record install4j installer output** workflow runs an install4j installer and records the files, services and registrations it creates.

## Source map

| File | Content |
| --- | --- |
| `Wizard.java`, `Ui.java`, `installer.css` | Wizard screens, controls and theme |
| `Installer.java` | Install, upgrade, rollback and uninstall |
| `Services.java` | Service definitions |
| `NativeProcesses.java` | Processes, uninstaller, Start Menu, Apps & Features and links |
| `ServiceControl.java`, `EngineBootstrap.java` | The engine launchers |
| `Model.java`, `Payload.java`, `Archives.java` | Engine detection, payload verification and extraction |
| `Readiness.java` | Engine startup check |
| `Privileges.java` | Administrator approval |
| `build.py` | Release download and packaging |
| `ci/smoke_install.py` | Installation tests |

## License

MPL-2.0. The OIE artwork belongs to its owners.
