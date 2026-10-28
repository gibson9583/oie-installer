# Install4j behavior and validation matrix

Scope: macOS ARM64 and Windows x64. OIE 4.7.0/install4j 13.1.1 is the source baseline. The JavaFX wizard, add-on choices and revised Welcome/Done styling are retained. This matrix describes implemented call paths; **engine installation, service and upgrade tests are intentionally not run at the user's request**. Those rows require manual validation.

| Behavior | Implementation and expected outcome | Evidence / validation |
| --- | --- | --- |
| License | Separate step 2 displays the bundled MPL 2.0 text and requires acceptance before Location; acceptance is retained during navigation and later steps are blocked if revoked | Released install4j LicenseScreen; native UI source/static render |
| New install | Choose folder/runtime/ports/database/admin/service/add-ons, Review, elevate once, install/start/verify | Compilation and source tracing; manual execution pending |
| Engine runtime | Independent bundled Java 21 default; external Java 17+ remains selectable | Native image/config and runtime file inspection |
| Native launchers | Server/service/CLI launchers use the real released main classes and working directory | Release-tag source and generated launcher configs |
| Service default | On, independently switchable; starts at boot without interactive sign-in | System launchd/SCM code; manual boot validation pending |
| Service identity | macOS installing user; Windows Local Service; independent of wizard lifetime | Plist/WinSW contracts and ownership code; manual validation pending |
| Add-ons | All three bundled/default on, independent switches, selections included in Review | Release descriptor/version checks and static UI |
| Reinstall/upgrade | Detect version, stop exact owned engine/service, back up conf/data/service/receipt, replace owned programs, keep configuration and custom extensions | Released layouts/source; manual migration validation pending |
| Legacy service migration | Recognize install4j only by exact executable path; stop/back up/remove before system-service registration | Release configuration identifier/executable definitions; manual validation pending |
| Stale Review | Reject changed destination mode or version before mutation | Action-time backend check |
| Concurrent installers | Shared administrator destination lock; case-folded Windows lock key | Source inspection; manual concurrent execution pending |
| Payload damage | Check every pinned file before stopping/replacing an engine | Build provenance and backend verification path |
| Denied elevation | Return a readable cancellation/start error; no backend install begins. Private job cleanup also covers preparation failures | Helper protocol; manual OS-dialog validation pending |
| Windows classpaths | Append Java's wildcard as classpath text after resolving the real lib directory; applies to elevation and independent uninstall | Windows forbids wildcard characters in Path components; source tracing |
| Service-start failure | Pending service receipt is persisted before registration/start so retry/uninstall can find partial completion | Source ordering; manual failure validation pending |
| Program/add-on replacement failure | Stop with an error; retain backup/completed changes; plugin move failure restores previous plugin directory | Source error flow; manual failure validation pending |
| Readiness failure | Return an error with progress/log details; preserve installed state and backups | Actual engine login/version/startup-log contracts; manual validation pending |
| Retry | Recheck location and Review; use installed version/receipt and a new backup, without assuming rollback | Source navigation/backend state; manual validation pending |
| No-service mode | Start once in desktop user context; no boot registration. macOS uses a hangup-ignoring exec wrapper with redirected I/O, avoiding nohup's console-detach failure | Native process paths; manual engine validation pending |
| Uninstall | Independent runtime/backend; confirmation, exact service ownership checks and graceful stop; Windows defers locked-file deletion | Source tracing; manual deletion validation pending |
| UI | Shared header line, reviewed fonts/colors/controls, license, bundled runtime and system-service wording, matching Done composition | Static JavaFX scene verification |
| Distribution | Native macOS build and Windows Actions build; checksums/receipt; tests skipped | Build logs and generated artifacts |

The installer does not claim database rollback, automatic background updates, every install4j media format/architecture, or publisher-signing parity. Those are separate from retaining the installation workflow and implementing native runtime/launcher/system-service/uninstall behavior on these two targets.
