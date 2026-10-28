package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Native boot-time services, with migration of install4j and earlier user services. */
final class Services {
    static String supported() { return Model.MAC ? "launchd" : Model.WINDOWS ? "Windows" : Files.isDirectory(Path.of("/run/systemd/system")) ? "systemd" : ""; }
    static String blocked(Path home) throws IOException {
        Path canonical = Model.canonical(home);
        if (Model.WINDOWS && canonical.toString().startsWith("\\\\")) return "Choose a local folder for a Windows service.";
        if (Model.MAC) for (String folder : new String[]{"Documents", "Desktop", "Downloads", "Library/Mobile Documents"})
            if (canonical.startsWith(Model.canonical(Privileges.invokingHome().resolve(folder))))
                return "macOS privacy protection prevents system services from reading ~/" + folder + ". Choose a folder outside Documents, Desktop and Downloads.";
        return "";
    }
    static String label(Path home) throws Exception {
        String path = home.toString(); if (Model.WINDOWS) path = path.toLowerCase(Locale.ROOT);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 8);
        return Model.WINDOWS ? "oie" + hash : "org.openintegrationengine.engine." + hash;
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String systemd(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%") + "\""; }
    static ObjectNode install(Path home, java.util.function.Consumer<ObjectNode> registered) throws Exception {
        String reason = blocked(home); if (!reason.isEmpty()) throw new IOException(reason);
        if (!Privileges.administrator()) throw new IOException("Administrator privileges are required to install a system service.");
        String name = label(home); ObjectNode result = Json.object();
        if (Model.MAC) {
            Path file = Path.of("/Library/LaunchDaemons", name + ".plist");
            FilesSupport.safeTarget(file.getParent(), file.getFileName().toString());
            Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                <key>Label</key><string>%s</string>
                <key>ProgramArguments</key><array><string>%s/oieservice</string></array>
                <key>WorkingDirectory</key><string>%s</string><key>UserName</key><string>%s</string>
                <key>RunAtLoad</key><true/><key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
                <key>StandardOutPath</key><string>%s/logs/oieserver.out</string>
                <key>StandardErrorPath</key><string>%s/logs/oieserver.out</string>
                </dict></plist>
                """.formatted(name, xml(home.toString()), xml(home.toString()), xml(Privileges.invokingUser()), xml(home.toString()), xml(home.toString())));
            Model.command("chown", "root:wheel", file.toString()); Model.command("chmod", "644", file.toString());
            result.put("kind", "launchd-system").put("name", name).put("file", file.toString()).put("user", Privileges.invokingUser())
                    .put("start", "sudo launchctl bootstrap system " + FilesSupport.quote(file.toString())).put("stop", "sudo launchctl bootout system/" + name);
            registered.accept(result); Model.command("launchctl", "bootstrap", "system", file.toString());
        } else if (Model.WINDOWS) {
            Path wrapper = home.resolve("oie-service.exe"), file = FilesSupport.safeTarget(home, "oie-service.xml");
            if (!Files.isRegularFile(wrapper)) throw new IOException("The Windows service wrapper is missing.");
            Files.writeString(file, """
                <service><id>%s</id><name>Open Integration Engine · %s</name>
                <description>Open Integration Engine</description>
                <executable>%s</executable><workingdirectory>%s</workingdirectory>
                <startmode>Automatic</startmode><delayedAutoStart>true</delayedAutoStart>
                <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user></serviceaccount>
                <stoptimeout>60 sec</stoptimeout><stopparentprocessfirst>true</stopparentprocessfirst><onfailure action="restart" delay="10 sec"/>
                <logpath>%s</logpath><log mode="roll"/>
                </service>
                """.formatted(name, name.substring(3), xml(home.resolve("oieservice.exe").toString()), xml(home.toString()), xml(home.resolve("logs").toString())));
            result.put("kind", "windows").put("name", name).put("file", file.toString()).put("user", "NT AUTHORITY\\LocalService")
                    .put("start", "sc.exe start " + name).put("stop", "sc.exe stop " + name);
            registered.accept(result); Model.command(wrapper.toString(), "install"); Model.command(wrapper.toString(), "start");
        } else if (supported().equals("systemd")) {
            Path file = Path.of("/etc/systemd/system", name + ".service");
            FilesSupport.safeTarget(file.getParent(), file.getFileName().toString());
            Files.writeString(file, "[Unit]\nDescription=Open Integration Engine\nAfter=network-online.target\n\n[Service]\nType=simple\nUser=" + Privileges.invokingUser()
                    + "\nWorkingDirectory=" + home.toString().replace("%", "%%") + "\nExecStart=" + systemd(home.resolve("oieservice").toString())
                    + "\nRestart=on-failure\nTimeoutStopSec=60\n\n[Install]\nWantedBy=multi-user.target\n");
            result.put("kind", "systemd-system").put("name", name + ".service").put("file", file.toString())
                    .put("start", "sudo systemctl start " + name).put("stop", "sudo systemctl stop " + name);
            registered.accept(result); Model.command("systemctl", "daemon-reload"); Model.command("systemctl", "enable", "--now", name + ".service");
        } else throw new IOException("This system has no supported service manager.");
        return result;
    }
    static void permissions(Path home, boolean service) throws Exception {
        if (!Privileges.administrator()) return;
        if (Model.MAC) {
            Model.command("chown", "-R", Privileges.invokingUser(), home.toString());
            for (String name : List.of("conf", "appdata", "logs", "backups")) if (Files.isDirectory(home.resolve(name))) Model.command("chmod", "700", home.resolve(name).toString());
        } else if (Model.WINDOWS) {
            Model.command("icacls.exe", home.toString(), "/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F", "*S-1-5-32-545:(OI)(CI)RX", "/Q");
            if (service) Model.command("icacls.exe", home.toString(), "/grant:r", "*S-1-5-19:(OI)(CI)RX", "/Q");
            inherit(home);
            // The engine writes conf, appdata and logs; Web Support deploys into webapps.
            for (String name : List.of("conf", "appdata", "logs", "backups", "webapps")) {
                Path folder = home.resolve(name); Files.createDirectories(folder);
                List<String> args = new ArrayList<>(List.of("icacls.exe", folder.toString(), "/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F", Privileges.invokingUser() + (service ? ":(OI)(CI)R" : ":(OI)(CI)M")));
                if (service && !name.equals("backups")) args.add("*S-1-5-19:(OI)(CI)M");
                args.add("/Q"); Model.command(args.toArray(String[]::new));
                inherit(folder);
            }
            if (service) {
                // Derby opens derby.log in the working directory before the engine redirects it.
                Path derby = home.resolve("derby.log"); if (!Files.exists(derby)) Files.createFile(derby);
                Model.command("icacls.exe", derby.toString(), "/grant:r", "*S-1-5-19:M", "/Q");
            }
        }
    }
    /** Replace the contents' ACLs with the folder's inheritable entries. icacls /T with /inheritance:r would leave files with empty ACLs. */
    private static void inherit(Path folder) throws Exception {
        try (var children = Files.list(folder)) {
            for (Path child : children.toList()) Model.command("icacls.exe", child.toString(), "/reset", "/T", "/C", "/Q");
        }
    }
    static void verifyRecord(Path home, JsonNode record) throws Exception {
        String kind = record.path("kind").asText(), name = record.path("name").asText(); Path expected;
        if (kind.equals("install4j-launchd") || kind.equals("install4j-windows")) {
            ObjectNode detected = legacy(home);
            if (detected == null || !detected.path("name").asText().equals(name)) throw new IOException("The legacy service no longer belongs to this engine.");
            return;
        }
        String label = label(home);
        switch (kind) {
            case "launchd-system" -> expected = Path.of("/Library/LaunchDaemons", label + ".plist");
            case "systemd-system" -> expected = Path.of("/etc/systemd/system", label + ".service");
            case "windows" -> expected = home.resolve("oie-service.xml");
            case "launchd" -> expected = Privileges.invokingHome().resolve("Library/LaunchAgents/" + label + ".plist");
            case "systemd" -> expected = Privileges.invokingHome().resolve(".config/systemd/user/" + label + ".service");
            default -> throw new IOException("Unsupported service receipt: " + kind);
        }
        String expectedName = label + (kind.startsWith("systemd") ? ".service" : "");
        if (!name.equals(expectedName) || !Path.of(record.path("file").asText()).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize())) throw new IOException("The recorded service does not belong to this engine.");
        if (kind.equals("windows")) {
            String actual = queryWindowsBinary(name);
            if (!actual.isEmpty() && !actual.equalsIgnoreCase(home.resolve("oie-service.exe").toString())) throw new IOException("Another service uses this name.");
        }
    }
    static void stop(Path home, JsonNode record, boolean remove) throws Exception {
        if (record == null || record.isNull()) return;
        verifyRecord(home, record); String kind = record.path("kind").asText(), name = record.path("name").asText();
        if (kind.equals("windows") || kind.equals("install4j-windows")) {
            String script = "$ErrorActionPreference='Stop'; $s=Get-Service -Name " + Privileges.powershell(name) + " -ErrorAction SilentlyContinue; if($s){if($s.Status -ne 'Stopped'){ $s.Stop(); $s.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(65)) }}";
            Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script));
            if (remove && !queryWindowsBinary(name).isEmpty()) Model.command("sc.exe", "delete", name);
        } else if (kind.contains("launchd")) {
            String domain = kind.equals("launchd") ? "gui/" + Privileges.invokingUid() : "system";
            try { Model.command("launchctl", "bootout", domain + "/" + name); }
            catch (IOException e) { if (!Model.engineProcesses(home).isEmpty()) throw e; }
        } else {
            List<String> args = new ArrayList<>(List.of("systemctl")); if (kind.equals("systemd")) args.add("--user");
            args.addAll(remove ? List.of("disable", "--now", name) : List.of("stop", name));
            try { Model.command(args.toArray(String[]::new)); }
            catch (IOException e) { if (!Model.engineProcesses(home).isEmpty()) throw e; }
        }
        if (remove) {
            if (record.hasNonNull("file")) Files.deleteIfExists(Path.of(record.path("file").asText()));
            if (kind.startsWith("systemd")) Model.command("systemctl", "daemon-reload");
        }
    }
    static void start(Path home, JsonNode record) throws Exception {
        verifyRecord(home, record); String kind = record.path("kind").asText(), name = record.path("name").asText();
        if (kind.equals("windows") || kind.equals("install4j-windows")) Model.command("sc.exe", "start", name);
        else if (kind.contains("launchd")) {
            String domain = kind.equals("launchd") ? "gui/" + Privileges.invokingUid() : "system";
            try { Model.command("launchctl", "print", domain + "/" + name); Model.command("launchctl", "kickstart", domain + "/" + name); }
            catch (IOException notLoaded) { Model.command("launchctl", "bootstrap", domain, record.path("file").asText()); }
        } else Model.command("systemctl", kind.equals("systemd") ? "--user" : "--system", "start", name);
    }
    private static String queryWindowsBinary(String name) throws Exception {
        String script = "$s=Get-CimInstance Win32_Service -Filter " + Privileges.powershell("Name='" + name.replace("'", "''") + "'")
                + "; if($s){$p=$s.PathName.Trim(); if($p.StartsWith('\"')){$p.Split('\"')[1]}else{($p -split '\\s+')[0]}}";
        return Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script)).strip();
    }
    static boolean running(Path home, JsonNode record) throws Exception {
        if (record == null || record.isNull()) return false;
        verifyRecord(home, record);
        String kind = record.path("kind").asText(), name = record.path("name").asText();
        try {
            if (kind.equals("windows") || kind.equals("install4j-windows"))
                return Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded("(Get-Service -Name " + Privileges.powershell(name) + " -ErrorAction SilentlyContinue).Status")).equalsIgnoreCase("Running");
            if (kind.contains("launchd")) return Model.command("launchctl", "print", (kind.equals("launchd") ? "gui/" + Privileges.invokingUid() : "system") + "/" + name).contains("state = running");
            return Model.command("systemctl", kind.equals("systemd") ? "--user" : "--system", "is-active", name).equals("active");
        } catch (IOException notRunning) { return false; }
    }
    static ObjectNode legacy(Path home) throws Exception {
        if (Model.MAC) {
            Path file = Path.of("/Library/LaunchDaemons/com.oie.oieservice.plist");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var strings = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("string"); boolean belongs = false;
            for (int i = 0; i < strings.getLength(); i++) {
                String value = strings.item(i).getTextContent();
                if (value.equals(home.resolve("oieservice").toString()) || value.equals(home.resolve("oieserver").toString())) belongs = true;
            }
            if (belongs) return Json.object().put("kind", "install4j-launchd").put("name", "com.oie.oieservice").put("file", file.toString());
        } else if (Model.WINDOWS) {
            String script = "$target=" + Privileges.powershell(home.resolve("oieservice.exe").toString())
                    + "; Get-CimInstance Win32_Service | Where-Object { $p=$_.PathName.Trim(); if($p.StartsWith('\"')){$p=$p.Split('\"')[1]}else{$p=($p -split '\\s+')[0]}; $p -ieq $target } | Select-Object -First 1 -ExpandProperty Name";
            String name = Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script));
            if (!name.isEmpty()) return Json.object().put("kind", "install4j-windows").put("name", name);
        }
        return null;
    }
}
