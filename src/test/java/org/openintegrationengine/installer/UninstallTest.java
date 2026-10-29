package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Without --delete-data the uninstaller removes the program files and keeps the settings and data, like install4j. */
class UninstallTest {
    @TempDir Path home;

    @Test void programFilesExcludeSettingsDataAndThirdPartyAddOns() throws IOException {
        for (String folder : List.of("conf", "appdata/mirthdb", "logs", "backups/20261029-4.6.0", "custom-lib", "custom-extensions", "server-launcher-lib",
                "server-lib", "client-lib", ".launcher/oieserver", "extensions/http", "extensions/websupport", "extensions/acme-connector", "webapps"))
            Files.createDirectories(home.resolve(folder));
        for (String file : List.of("oieserver", "oieservice", "oiecommand", "oieserver.vmoptions", "mirth-server-launcher.jar", "conf/mirth.properties"))
            Files.writeString(home.resolve(file), "");
        ObjectNode receipt = Json.object();
        receipt.putArray("programExtensions").add("http");
        receipt.putObject("plugins").putObject("websupport");
        Files.writeString(home.resolve(Model.RECEIPT), receipt.toString());

        List<String> remove = Installer.programFiles(home, receipt).stream().map(p -> home.relativize(p).toString().replace('\\', '/')).sorted().toList();
        assertEquals(List.of(".launcher", "client-lib", "extensions/http", "extensions/websupport", "mirth-server-launcher.jar",
                "oiecommand", "oieserver", "oieserver.vmoptions", "oieservice", "server-lib", "webapps"), remove);
    }

    @Test void aReceiptWithoutExtensionsKeepsTheExtensionsFolder() throws IOException {
        Files.createDirectories(home.resolve("extensions/http"));
        Files.createDirectories(home.resolve("conf"));
        assertEquals(List.of(), Installer.programFiles(home, Json.object()));
    }
}
