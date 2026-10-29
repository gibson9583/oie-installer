package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** The install4j service definitions: one engine service per computer, with install4j's names, files and accounts. */
final class Services {
    static final String WINDOWS_NAME = "Open Integration Engine Service";
    static final String LAUNCHD_LABEL = "com.oie.oieservice";
    static final Path LAUNCHD_FILE = Path.of("/Library/LaunchDaemons", LAUNCHD_LABEL + ".plist");
    static final String UNIT = "oieservice.service";
    static final Path UNIT_FILE = Path.of("/etc/systemd/system", UNIT);
    static final Path INITD_FILE = Path.of("/etc/init.d/oieservice");

    /** install4j uses systemd when /run/systemd/system exists, otherwise /etc/init.d. */
    static String supported() {
        if (Model.MAC) return "launchd";
        if (Model.WINDOWS) return "Windows";
        if (Files.isDirectory(Path.of("/run/systemd/system"))) return "systemd";
        return Files.isDirectory(INITD_FILE.getParent()) ? "init.d" : "";
    }
    static String blocked(Path home) throws IOException {
        Path canonical = Model.canonical(home);
        if (Model.WINDOWS && canonical.toString().startsWith("\\\\")) return "Choose a local folder for a Windows service.";
        if (Model.MAC) for (String folder : new String[]{"Documents", "Desktop", "Downloads", "Library/Mobile Documents"})
            if (canonical.startsWith(Model.canonical(Privileges.invokingHome().resolve(folder))))
                return "macOS privacy protection prevents system services from reading ~/" + folder + ". Choose a folder outside Documents, Desktop and Downloads.";
        return "";
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    /** The engine service on this computer, installed by install4j or by this installer, with the program it runs. */
    static ObjectNode find() throws Exception {
        ObjectNode record = Json.object();
        if (Model.MAC) {
            if (!Files.isRegularFile(LAUNCHD_FILE, LinkOption.NOFOLLOW_LINKS)) return null;
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var strings = factory.newDocumentBuilder().parse(LAUNCHD_FILE.toFile()).getElementsByTagName("string");
            String program = "";
            for (int i = 0; i < strings.getLength(); i++) { String value = strings.item(i).getTextContent(); if (value.startsWith("/")) { program = value; break; } }
            record.put("kind", "launchd-system").put("name", LAUNCHD_LABEL).put("file", LAUNCHD_FILE.toString()).put("program", program)
                    .put("start", "sudo launchctl bootstrap system " + LAUNCHD_FILE).put("stop", "sudo launchctl bootout system/" + LAUNCHD_LABEL);
        } else if (Model.WINDOWS) {
            // Read the same ImagePath without importing the CIM management module.
            // HKLM\SYSTEM is shared between Windows architectures, including the x64 wizard on ARM64.
            String script = """
                $ErrorActionPreference='Stop'
                $p=[Microsoft.Win32.Registry]::GetValue('HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\%s','ImagePath',$null)
                if($null -ne $p){$p=([string]$p).Trim(); if($p.StartsWith('"')){$p.Split('"')[1]}else{($p -split '\\s+')[0]}}
                """.formatted(WINDOWS_NAME);
            String output = Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script));
            String program = output.lines().map(String::strip).filter(line -> !line.isEmpty()).reduce((first, last) -> last).orElse("");
            if (program.isEmpty()) return null;
            if (program.startsWith("\\\\?\\")) program = program.substring(4);
            record.put("kind", "windows").put("name", WINDOWS_NAME).put("program", program)
                    .put("start", "sc.exe start \"" + WINDOWS_NAME + "\"").put("stop", "sc.exe stop \"" + WINDOWS_NAME + "\"");
            Path xml = Path.of(program).resolveSibling("oie-service.xml"); if (Files.isRegularFile(xml)) record.put("file", xml.toString());
        } else if (Files.isRegularFile(UNIT_FILE, LinkOption.NOFOLLOW_LINKS)) {
            Matcher exec = Pattern.compile("(?m)^\\s*ExecStart=\\s*[-@+!]*(\"([^\"]+)\"|(\\S+))").matcher(Files.readString(UNIT_FILE, StandardCharsets.ISO_8859_1));
            record.put("kind", "systemd-system").put("name", UNIT).put("file", UNIT_FILE.toString()).put("program", exec.find() ? Objects.requireNonNullElse(exec.group(2), exec.group(3)) : "")
                    .put("start", "sudo systemctl start oieservice").put("stop", "sudo systemctl stop oieservice");
        } else if (Files.exists(INITD_FILE, LinkOption.NOFOLLOW_LINKS)) {
            record.put("kind", "initd").put("name", "oieservice").put("file", INITD_FILE.toString())
                    .put("program", Files.isSymbolicLink(INITD_FILE) ? INITD_FILE.toRealPath().toString() : INITD_FILE.toString())
                    .put("start", "sudo " + INITD_FILE + " start").put("stop", "sudo " + INITD_FILE + " stop");
        } else return null;
        return record;
    }
    static boolean belongs(Path home, ObjectNode record) {
        if (record == null) return false;
        String program = record.path("program").asText();
        for (String name : List.of("oieservice", "oieserver", "oieservice.exe", "oie-service.exe")) {
            String expected = home.resolve(name).toString();
            if (Model.WINDOWS ? program.equalsIgnoreCase(expected) : program.equals(expected)) return true;
        }
        return false;
    }
    /** The engine service that runs this engine folder, or null. */
    static ObjectNode detect(Path home) throws Exception { ObjectNode record = find(); return belongs(home, record) ? record : null; }
    /** install4j allows one engine service per computer. */
    static String conflict(Path home) throws Exception {
        ObjectNode record = find();
        if (record == null || belongs(home, record)) return "";
        return "The " + (Model.WINDOWS ? WINDOWS_NAME : record.path("name").asText()) + " already runs the engine in " + Path.of(record.path("program").asText()).getParent()
                + ". Remove that service first, or start this engine as a normal process.";
    }

    static ObjectNode install(Path home, java.util.function.Consumer<ObjectNode> registered) throws Exception {
        String reason = blocked(home); if (!reason.isEmpty()) throw new IOException(reason);
        reason = conflict(home); if (!reason.isEmpty()) throw new IOException(reason);
        if (!Privileges.administrator()) throw new IOException("Administrator privileges are required to install a system service.");
        String service = home.resolve("oieservice").toString();
        if (Model.MAC) {
            Files.writeString(LAUNCHD_FILE, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                         <string>start-launchd</string>
                    </array>
                    <key>KeepAlive</key>
                    <false/>
                    <key>RunAtLoad</key>
                    <true/>
                </dict>
                </plist>
                """.formatted(LAUNCHD_LABEL, xml(service)));
            Model.command("chown", "root:wheel", LAUNCHD_FILE.toString()); Model.command("chmod", "644", LAUNCHD_FILE.toString());
            registered.accept(find()); Model.command("launchctl", "bootstrap", "system", LAUNCHD_FILE.toString());
        } else if (Model.WINDOWS) {
            Path program = home.resolve("oieservice.exe");
            if (!Files.isRegularFile(program)) throw new IOException("The Windows service program is missing.");
            // install4j registers oieservice.exe as an automatic Local System service with no description and no failure actions.
            Model.command(program.toString(), "--create-service"); registered.accept(find()); Model.command("sc.exe", "start", WINDOWS_NAME);
        } else if (supported().equals("systemd")) {
            Files.writeString(UNIT_FILE, "[Unit]\nDescription=" + WINDOWS_NAME + "\nBefore=multi-user.target graphical.target\n"
                    + "After=network-online.target remote-fs.target time-sync.target\nWants=network-online.target\n\n[Service]\nType=" + (systemdVersion() >= 240 ? "exec" : "simple")
                    + "\nExecStart=\"" + service.replace("%", "%%") + "\" start-launchd\nSuccessExitStatus=0 143\nKillMode=process\n\n[Install]\nWantedBy=multi-user.target graphical.target\n");
            Model.command("chmod", "644", UNIT_FILE.toString());
            registered.accept(find()); Model.command("systemctl", "daemon-reload"); Model.command("systemctl", "enable", UNIT); Model.command("systemctl", "start", UNIT);
        } else if (supported().equals("init.d")) {
            Files.deleteIfExists(INITD_FILE); Files.createSymbolicLink(INITD_FILE, Path.of(service));
            if (Files.isExecutable(Path.of("/usr/sbin/update-rc.d"))) Model.command("/usr/sbin/update-rc.d", "oieservice", "defaults");
            else if (Files.isExecutable(Path.of("/sbin/chkconfig"))) Model.command("/sbin/chkconfig", "--add", "oieservice");
            registered.accept(find()); NativeProcesses.start(home, home.resolve("oieserver"));
        } else throw new IOException("This system has no supported service manager.");
        return find();
    }
    private static int systemdVersion() {
        try { Matcher m = Pattern.compile("systemd (\\d+)").matcher(Model.command("systemctl", "--version")); return m.find() ? Integer.parseInt(m.group(1)) : 0; }
        catch (Exception e) { return 0; }
    }

    /** install4j leaves file ownership and permissions to the installing administrator; a normal process runs as the installing user. */
    static void permissions(Path home, boolean service) throws Exception {
        if (!Privileges.administrator() || Model.WINDOWS) return;
        String owner = service ? "root" : Privileges.invokingUser();
        Model.command("chown", "-R", owner, home.toString());
    }
    /** Use the framework service API directly: management-module imports are costly on ARM64. */
    private static String windowsService(String action) throws IOException, InterruptedException {
        String script = """
            $ErrorActionPreference='Stop'
            [void][Reflection.Assembly]::Load('System.ServiceProcess, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a')
            $s=[System.ServiceProcess.ServiceController]::new()
            $s.ServiceName='%s'
            try{%s}catch{
              $e=$_.Exception; while($e.InnerException){$e=$e.InnerException}
              if($e -isnot [ComponentModel.Win32Exception] -or $e.NativeErrorCode -ne 1060){throw}
            }finally{$s.Dispose()}
            """.formatted(WINDOWS_NAME, action);
        return Model.command("powershell.exe", "-NoProfile", "-EncodedCommand", Privileges.encoded(script));
    }
    static void stop(Path home, boolean remove) throws Exception {
        ObjectNode record = detect(home);
        if (record == null) return;
        String kind = record.path("kind").asText();
        switch (kind) {
            case "windows" -> {
                windowsService("""
                    if($s.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped){
                      if($s.Status -ne [System.ServiceProcess.ServiceControllerStatus]::StopPending){$s.Stop()}
                      $s.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped,[TimeSpan]::FromSeconds(65))
                    }
                    """);
                if (remove) Model.command("sc.exe", "delete", WINDOWS_NAME);
            }
            case "launchd-system" -> {
                try { Model.command("launchctl", "bootout", "system/" + LAUNCHD_LABEL); }
                catch (IOException e) { if (!Model.engineProcesses(home).isEmpty()) throw e; }
            }
            case "systemd-system" -> {
                try { if (remove) Model.command("systemctl", "disable", "--now", UNIT); else Model.command("systemctl", "stop", UNIT); }
                catch (IOException e) { if (!Model.engineProcesses(home).isEmpty()) throw e; }
            }
            default -> NativeProcesses.stopOwned(home);
        }
        if (remove) {
            if (kind.equals("initd")) {
                if (Files.isExecutable(Path.of("/usr/sbin/update-rc.d"))) Model.command("/usr/sbin/update-rc.d", "-f", "oieservice", "remove");
                else if (Files.isExecutable(Path.of("/sbin/chkconfig"))) Model.command("/sbin/chkconfig", "--del", "oieservice");
            }
            if (record.hasNonNull("file")) Files.deleteIfExists(Path.of(record.path("file").asText()));
            if (kind.equals("systemd-system")) Model.command("systemctl", "daemon-reload");
        }
    }
    static void start(Path home) throws Exception {
        ObjectNode record = detect(home);
        if (record == null) throw new IOException("No engine service is installed for " + home);
        switch (record.path("kind").asText()) {
            case "windows" -> Model.command("sc.exe", "start", WINDOWS_NAME);
            case "launchd-system" -> {
                try { Model.command("launchctl", "print", "system/" + LAUNCHD_LABEL); Model.command("launchctl", "kickstart", "system/" + LAUNCHD_LABEL); }
                catch (IOException notLoaded) { Model.command("launchctl", "bootstrap", "system", LAUNCHD_FILE.toString()); }
            }
            case "systemd-system" -> Model.command("systemctl", "start", UNIT);
            default -> NativeProcesses.start(home, home.resolve("oieserver"));
        }
    }
    static boolean running(Path home) throws Exception {
        ObjectNode record = detect(home);
        if (record == null) return false;
        try {
            return switch (record.path("kind").asText()) {
                case "windows" -> windowsService("$s.Status.ToString()").equalsIgnoreCase("Running");
                case "launchd-system" -> Model.command("launchctl", "print", "system/" + LAUNCHD_LABEL).contains("state = running");
                case "systemd-system" -> Model.command("systemctl", "is-active", UNIT).equals("active");
                default -> !Model.engineProcesses(home).isEmpty();
            };
        } catch (IOException notRunning) { return false; }
    }
}
