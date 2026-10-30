# install4j parity

The baseline is the OIE 4.7.0 install4j 13.1.1 configuration and the output of the 4.6.0 install4j installers. The **Record install4j installer output** workflow records that output. The CI smoke test checks this installer on each platform.

## Matches install4j

| Item | install4j and this installer | Checked by |
| --- | --- | --- |
| Media | `oie_windows-<arch>_<v>[-with-JRE].exe`, `oie_macos_<v>-<aarch\|x86>[-with-JRE].dmg`, `oie_unix_<v>[-with-JRE].sh` | Build |
| Archives | `oie_windows-<arch>_<v>.zip`, `oie_unix_<v>.tar.gz`, with the `oie` folder, for an installed Java | Smoke test |
| Portable Unix media | `oie_unix_<v>.sh` and `.tar.gz` run on an installed Java 17 or later, on any CPU; the `.tar.gz` also on macOS | Containers and smoke test |
| Engine files | The same files as the release archive, built from the release tag | Build |
| License | The engine's MPL 2.0 `LICENSE.txt`, accepted before the folder step | Wizard |
| Launchers | `oieserver`, `oieservice`, `oiecommand`; `.exe` on Windows | Smoke test |
| `oieservice` commands | `start`, `stop`, `restart`, `force-reload`, `status`, `run`, `start-launchd`; `/install`, `/uninstall` on Windows | Smoke test |
| `oiecommand.exe` | Requires administrator elevation | Smoke test |
| Windows service | `Open Integration Engine Service` running `<engine>\oieservice.exe`, no description, automatic, Local System, no failure actions | Smoke test |
| macOS service | `/Library/LaunchDaemons/com.oie.oieservice.plist`: `<engine>/oieservice start-launchd`, `RunAtLoad` true, `KeepAlive` false, root | Smoke test |
| Linux service | `/etc/systemd/system/oieservice.service`, byte for byte, enabled for `multi-user.target` and `graphical.target`, root | Smoke test |
| Linux without systemd | `/etc/init.d/oieservice`, linked to `<engine>/oieservice`, with install4j's init header | Debian container in CI |
| Services per computer | One | Wizard and validation |
| Start Menu | Group **Open Integration Engine**: **Open Integration Engine Service** and **Open Integration Engine** | Smoke test |
| Apps & Features | Key `4145-9206-7630-8076`, `Open Integration Engine <version>`, publisher `Open Integration Engine Project` | Smoke test |
| Linux links | `/usr/local/bin/oieservice`, `/usr/local/bin/oiecommand` | Smoke test |
| macOS links | None | Smoke test |
| Windows permissions | Unchanged; the folder inherits its parent's permissions | Code |
| Uninstaller | `uninstall.exe` on Windows, `uninstall` on macOS and Linux | Smoke test |
| Unattended mode | `-q -dir <folder>` on the `.exe`, `.dmg` and `.sh` | Smoke test |
| Text mode | `-c`; Linux without a desktop uses it automatically | Smoke test on Linux |
| Uninstall | Removes the program files, service and registrations; keeps the configuration and data | Smoke test |
| Upgrade | Removes the previous program files and service, then installs; keeps the configuration and data | Smoke test |
| Rollback | Removes the new files and registrations; keeps the configuration and data | Smoke test |
| Running console engine on Windows | The user closes it; the installer does not end it | Smoke test |
| Installer signing | Ad hoc on macOS, unsigned on Windows, as the 4.7.0 release media | Release media inspection |

## Differs from install4j

| Item | install4j | This installer |
| --- | --- | --- |
| Installer interface | install4j wizard | JavaFX wizard with network, database, administrator, service and add-on steps |
| Engine start | Does not start the engine | Starts the engine and waits for `server successfully started` |
| Administrator password | Engine default | Generated or entered; removed from `conf/mirth.properties` after startup |
| Add-ons | None | OIE Web Client, RBAC and Simple Channel History |
| Backup | None | `conf` and `appdata` copied to `backups/<date>-<version>` before an upgrade |
| Media without Java | Use an installed Java for the installer and the engine | The installer application has its own runtime; the engine uses an installed Java 17 or later |
| Uninstall data | Kept | Kept, or deleted when the user types `delete` or passes `--delete-data` |
| Linux ARM64 with Java | None | `oie_unix_<v>-aarch64-with-JRE.sh` |
| Wizard in `oie_unix_<v>.sh` | Any system with a display | Linux x64 and ARM64 with glibc and GTK; text mode elsewhere |
| Archive Java | install4j's Java search | `JAVA_HOME`, then `PATH`; service installation records it in `conf/custom.vmoptions` |
| Engine build | Release pipeline | `gradlew build -PdisableTests=true` from the release tag |
| Single-instance check | install4j launchers allow one running copy | The engine fails to start when its ports are in use |
| Engine jar signing | Release code-signing certificate | The engine repository's self-signed key, unless the build passes the release keystore to Gradle |

## Not tested in CI

- The wizard on macOS and Windows. CI runs it on Linux under a virtual display.
- The administrator approval prompts.
- External databases.
