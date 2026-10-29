package org.openintegrationengine.installer;

import javafx.application.Application;
import java.nio.file.Path;

/** Separate launcher keeps the CLI independent of the JavaFX application lifecycle. */
public final class Main {
    public static void main(String[] args) {
        if (args.length > 0) {
            try {
                if (args.length == 2 && args[0].equals("--elevated-install")) { Privileges.helper(Path.of(args[1])); return; }
                if (args.length == 2 && args[0].equals("--unattended")) {
                    var plan = Model.Plan.from(Json.read(Path.of(args[1])));
                    String[] shown = {""};
                    var result = Privileges.install(Payload.load(), plan, event -> {
                        if (event.state().equals("active") && !event.label().equals(shown[0])) System.out.println(shown[0] = event.label());
                    }, System.out::println);
                    if (result.containsKey("adminPassword")) result.put("adminPassword", "<as in the plan>");
                    System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    return;
                }
                if ((args.length == 2 || args.length == 3) && args[0].equals("--uninstall")) {
                    if (args.length != 3 || !args[2].equals("--yes"))
                        throw new IllegalArgumentException("Use --uninstall <engine folder> --yes to stop and delete that engine, including its data and backups.");
                    Installer.uninstall(Model.expand(args[1]));
                    return;
                }
                throw new IllegalArgumentException("Usage: OIE Installer [--unattended plan.json | --uninstall <folder> --yes]");
            } catch (Exception e) {
                System.err.println(e.getMessage());
                if (System.getenv("OIE_INSTALLER_DEBUG") != null) e.printStackTrace();
                System.exit(e instanceof IllegalArgumentException ? 2 : 1);
            }
        } else {
            Application.launch(Wizard.class);
        }
    }
}
