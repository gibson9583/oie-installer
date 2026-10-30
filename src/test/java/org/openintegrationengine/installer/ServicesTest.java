package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;

/** The macOS and Linux service definitions match the files the install4j 4.6.0 installer wrote. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "launchd and systemd paths use forward slashes")
class ServicesTest {
    @Test void systemdUnitMatchesInstall4j() {
        assertEquals("""
                [Unit]
                Description=Open Integration Engine Service
                Before=multi-user.target graphical.target
                After=network-online.target remote-fs.target time-sync.target
                Wants=network-online.target

                [Service]
                Type=exec
                ExecStart="/opt/openintegrationengine/oieservice" start-launchd
                SuccessExitStatus=0 143
                KillMode=process

                [Install]
                WantedBy=multi-user.target graphical.target
                """, Services.systemdUnit(Path.of("/opt/openintegrationengine"), 252));
    }

    @Test void olderSystemdUsesSimpleAndPercentSignsAreEscaped() {
        String unit = Services.systemdUnit(Path.of("/opt/100%oie"), 239);
        assertTrue(unit.contains("\nType=simple\n"));
        assertTrue(unit.contains("\nExecStart=\"/opt/100%%oie/oieservice\" start-launchd\n"));
    }

    @Test void launchDaemonMatchesInstall4j() {
        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>com.oie.oieservice</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>/Applications/OpenIntegrationEngine/oieservice</string>
                         <string>start-launchd</string>
                    </array>
                    <key>KeepAlive</key>
                    <false/>
                    <key>RunAtLoad</key>
                    <true/>
                </dict>
                </plist>
                """, Services.launchdPlist(Path.of("/Applications/OpenIntegrationEngine")));
    }

    @Test void launchDaemonPathsAreXmlEscaped() {
        assertTrue(Services.launchdPlist(Path.of("/opt/R&D <oie>")).contains("<string>/opt/R&amp;D &lt;oie&gt;/oieservice</string>"));
    }

    @Test void aServiceBelongsToTheEngineFolderItRuns() {
        Path home = Path.of("/opt/oie");
        assertTrue(Services.belongs(home, Json.object().put("program", "/opt/oie/oieservice")));
        assertFalse(Services.belongs(home, Json.object().put("program", "/opt/oie-other/oieservice")));
        assertFalse(Services.belongs(home, Json.object().put("program", "/opt/oie/bin/oieservice")));
        assertFalse(Services.belongs(home, null));
    }
}
