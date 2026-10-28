package org.openintegrationengine.installer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.*;
import javafx.util.Duration;
import java.awt.Desktop;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.Preferences;

/** Native JavaFX controls; no embedded browser, JavaScript bridge or localhost UI server. */
public final class Wizard extends Application {
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("welcome", "Welcome"), Map.entry("license", "License"), Map.entry("location", "Location"), Map.entry("java", "Java"), Map.entry("network", "Network"),
            Map.entry("database", "Database"), Map.entry("admin", "Administrator"), Map.entry("service", "Service"), Map.entry("plugins", "Add-ons"),
            Map.entry("review", "Review"), Map.entry("install", "Install"), Map.entry("done", "Done"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "oie-installer-worker"); thread.setDaemon(true); return thread; });
    private final ExecutorService checks = Executors.newFixedThreadPool(2, r -> { Thread thread = new Thread(r, "oie-installer-check"); thread.setDaemon(true); return thread; });
    private final Map<String, String> form = new HashMap<>();
    private final Map<String, Boolean> plugins = new LinkedHashMap<>();
    private final Set<String> visited = new HashSet<>();
    private final Preferences preferences = Preferences.userNodeForPackage(Wizard.class);
    private final BorderPane root = new BorderPane();
    private final VBox rail = new VBox(2), page = new VBox(18);
    private final Label title = new Label(), globalError = new Label();
    private final Button next = new Button("Start"), back = new Button("Back");
    private final HBox footer = new HBox(12);
    private final Map<String, Label> jobRows = new LinkedHashMap<>();
    private final Map<String, StackPane> jobMarks = new LinkedHashMap<>();
    private final ProgressBar progress = new ProgressBar(0);
    private final TextArea logs = new TextArea();
    private final Label phase = new Label();
    private final Label percentage = new Label("0%");
    private Payload payload;
    private List<Model.JavaRuntime> runtimes = List.of();
    private Model.Destination destination;
    private String current = "welcome", mode = "new", checkedPath = "", verifiedJava = "";
    private String locationError = "", javaError = "", installError = "";
    private boolean locationBusy, javaBusy, service = true, running, done, light, licenseAccepted, licenseLoaded;
    private long locationGeneration, javaGeneration;
    private Label locationStatus, javaStatus;
    private VBox existingPanel;
    private Stage stage;
    private ImageView heroLogo;
    private ScrollPane scroll;
    private Button themeButton;
    private Map<String, Object> result;

    @Override public void start(Stage stage) {
        for (String font : List.of("Inter-Regular", "Inter-Medium", "Inter-SemiBold", "Inter-Bold", "JetBrainsMono-Regular")) {
            try (var input = getClass().getResourceAsStream("/fonts/" + font + ".ttf")) { if (input != null) Font.loadFont(input, 12); }
            catch (java.io.IOException ignored) {}
        }
        this.stage = stage; stage.setTitle("Open Integration Engine Installer · JavaFX");
        stage.setMinWidth(960); stage.setMinHeight(640);
        stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/assets/oie.png"))));
        root.getStyleClass().add("app"); light = preferences.getBoolean("light", false); applyTheme();
        Scene scene = new Scene(root, 1120, 760); scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/installer.css")).toExternalForm());
        root.setCenter(new StackPane(label("Reading bundled release…", "muted"))); stage.setScene(scene); stage.show();
        stage.setOnCloseRequest(e -> { if (running) { e.consume(); globalError.setText("Keep this window open while the engine is being installed."); } });
        worker.submit(() -> {
            try {
                Payload loaded = Payload.load(); List<Model.JavaRuntime> java = Model.runtimes();
                int http = Model.freePort(8080), https = Model.freePort(8443);
                Platform.runLater(() -> {
                    payload = loaded; runtimes = java;
                    form.putAll(Map.of("installDir", Model.defaultHome().toString(), "httpPort", "" + http,
                            "httpsPort", "" + https, "database", "derby", "databaseUrl", "", "databaseUsername", "", "databasePassword", "", "adminPassword", Model.password()));
                    String choice = loaded.nativeEngine() ? Model.BUNDLED : java.stream().filter(Model.JavaRuntime::supported).map(j -> j.path().toString()).findFirst().orElse("");
                    form.put("javaCmd", choice); verifiedJava = choice;
                    Payload.IDS.forEach(id -> plugins.put(id, true));
                    shell(); show("welcome");
                });
            } catch (Exception e) { Platform.runLater(() -> root.setCenter(new StackPane(message(e.getMessage(), "error")))); }
        });
    }
    private void shell() {
        VBox sidebar = new VBox(12); sidebar.getStyleClass().add("sidebar"); sidebar.setPrefWidth(222); sidebar.setMinWidth(222);
        ImageView brand = brandImage(true, 190);
        VBox brandBlock = new VBox(7, brand, label("I N S T A L L E R", "brand-caption")); brandBlock.getStyleClass().add("brand-block");
        sidebar.getChildren().addAll(brandBlock, rail); root.setLeft(sidebar);
        BorderPane body = new BorderPane(); HBox topbar = new HBox(12); topbar.getStyleClass().add("topbar");
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS); title.getStyleClass().add("view-title");
        themeButton = new Button(); themeButton.getStyleClass().addAll("quiet", "icon-button"); themeButton.setAccessibleText("Switch color theme");
        themeButton.setTooltip(new Tooltip("Switch theme")); themeButton.setGraphic(Ui.icon(light ? "sun" : "moon", 16));
        themeButton.setOnAction(e -> { light = !light; preferences.putBoolean("light", light); applyTheme(); });
        Region pip = new Region(); pip.getStyleClass().add("host-pip");
        HBox host = new HBox(7, pip, label((Model.MAC ? "macos" : System.getProperty("os.name").toLowerCase()) + " " + System.getProperty("os.arch"), "host")); host.getStyleClass().add("host-chip"); host.setAlignment(Pos.CENTER);
        topbar.getChildren().addAll(title, space, host, themeButton);
        body.setTop(topbar);
        page.getStyleClass().add("page"); page.setFillWidth(true);
        page.getChildren().addListener((javafx.collections.ListChangeListener<Node>) change -> {
            while (change.next()) for (Node child : change.getAddedSubList()) if (child instanceof Region region) region.setMaxWidth(720);
        });
        scroll = new ScrollPane(page); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("page-scroll");
        body.setCenter(scroll); root.setCenter(body);
        globalError.getStyleClass().add("error"); globalError.setWrapText(true); globalError.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(globalError, Priority.ALWAYS);
        next.getStyleClass().add("primary"); next.setDefaultButton(true); back.getStyleClass().add("quiet");
        back.setOnAction(e -> { List<String> ids = steps(); int index = ids.indexOf(current); if (index > 0) show(ids.get(index - 1)); });
        next.setOnAction(e -> {
            if (!canNext()) return;
            if (current.equals("review")) install(); else { List<String> ids = steps(); show(ids.get(ids.indexOf(current) + 1)); }
        });
        footer.getStyleClass().add("footer"); footer.setAlignment(Pos.CENTER_LEFT); footer.getChildren().addAll(back, globalError, next); body.setBottom(footer);
    }
    private void applyTheme() {
        if (light) { if (!root.getStyleClass().contains("light")) root.getStyleClass().add("light"); } else root.getStyleClass().remove("light");
        if (heroLogo != null) heroLogo.setImage(brandAsset(!light));
        if (themeButton != null) themeButton.setGraphic(Ui.icon(light ? "sun" : "moon", 16));
    }
    private Image brandAsset(boolean white) { return new Image(Objects.requireNonNull(getClass().getResourceAsStream("/assets/brand-" + (white ? "white" : "dark") + ".png"))); }
    private ImageView brandImage(boolean white, double width) { ImageView view = new ImageView(brandAsset(white)); view.setFitWidth(width); view.setPreserveRatio(true); return view; }
    private List<String> steps() {
        List<String> ids = new ArrayList<>(List.of("welcome", "license", "location", "java"));
        if (mode.equals("new")) ids.addAll(List.of("network", "database", "admin"));
        ids.addAll(List.of("service", "plugins", "review", "install", "done")); return ids;
    }
    private String verb() { return mode.equals("new") ? "Install" : mode.equals("upgrade") ? "Upgrade" : "Reinstall"; }
    private void rail() {
        rail.getChildren().clear(); List<String> ids = steps();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i); int index = i;
            if (id.equals("install")) { Separator separator = new Separator(); VBox.setMargin(separator, new Insets(12, 16, 12, 16)); rail.getChildren().add(separator); }
            boolean completed = done || i < ids.indexOf(current);
            StackPane badge = new StackPane(completed ? Ui.icon("check", 12) : label(String.valueOf(i + 1), "step-number")); badge.getStyleClass().add("step-badge");
            if (completed) badge.getStyleClass().add("completed-badge");
            HBox content = new HBox(10, badge, label(NAMES.get(id), "step-label")); content.setAlignment(Pos.CENTER_LEFT);
            Button button = new Button(); button.setGraphic(content); button.setAccessibleText(NAMES.get(id));
            button.setMaxWidth(Double.MAX_VALUE); button.getStyleClass().add("step");
            if (id.equals(current)) button.getStyleClass().add("active-step");
            button.setDisable(running || done || !visited.contains(id) || id.equals("install") || id.equals("done")
                    || i > ids.indexOf("license") && (!licenseAccepted || !licenseLoaded));
            button.setOnAction(e -> show(steps().get(index))); rail.getChildren().add(button);
        }
    }
    private void show(String id) {
        boolean backwards = steps().indexOf(id) < steps().indexOf(current);
        current = id; visited.add(id); page.getChildren().clear(); globalError.setText("");
        footer.setVisible(!id.equals("install") && !id.equals("done")); footer.setManaged(footer.isVisible());
        title.setText(id.equals("review") ? "Review and " + verb().toLowerCase() : id.equals("plugins") ? "Choose add-ons" : NAMES.get(id));
        switch (id) {
            case "welcome" -> welcome(); case "license" -> license(); case "location" -> location(); case "java" -> java(); case "network" -> network();
            case "database" -> database(); case "admin" -> admin(); case "service" -> service(); case "plugins" -> plugins();
            case "review" -> review(); case "install" -> progress(); case "done" -> complete();
        }
        rail(); updateNext(); if (scroll != null) scroll.setVvalue(0); Ui.enter(page, backwards);
    }
    private void updateNext() {
        if (payload == null) return;
        back.setVisible(!current.equals("welcome")); next.setText(current.equals("welcome") ? "Start" : current.equals("review") ? verb() : "Next"); next.setDisable(!canNext());
    }
    private boolean canNext() {
        if (running || done || payload == null) return false;
        if (!current.equals("welcome") && !current.equals("license") && (!licenseAccepted || !licenseLoaded)) return false;
        return switch (current) {
            case "license" -> licenseAccepted && licenseLoaded;
            case "location" -> !locationBusy && destination != null && checkedPath.equals(form.get("installDir")) && locationError.isEmpty();
            case "java" -> !javaBusy && !verifiedJava.isEmpty() && verifiedJava.equals(form.get("javaCmd"));
            case "network" -> validPort("httpPort") && validPort("httpsPort") && !form.get("httpPort").equals(form.get("httpsPort"));
            case "database" -> form.get("database").equals("derby") || !form.get("databaseUrl").isBlank();
            case "admin" -> form.get("adminPassword").codePointCount(0, form.get("adminPassword").length()) >= 15;
            case "service" -> !service || serviceReason().isEmpty();
            case "review" -> destination != null && checkedPath.equals(form.get("installDir")) && !locationBusy
                    && verifiedJava.equals(form.get("javaCmd")) && !verifiedJava.isEmpty() && !javaBusy
                    && (!service || serviceReason().isEmpty())
                    && (!mode.equals("new") || validPort("httpPort") && validPort("httpsPort")
                    && !form.get("httpPort").equals(form.get("httpsPort"))
                    && (form.get("database").equals("derby") || !form.get("databaseUrl").isBlank())
                    && form.get("adminPassword").codePointCount(0, form.get("adminPassword").length()) >= 15);
            default -> true;
        };
    }
    private boolean validPort(String key) { try { int port = Integer.parseInt(form.get(key)); return port > 0 && port <= 65535; } catch (Exception e) { return false; } }
    private void heading(String text, String description) {
        Label intro = message(description, "intro"); intro.setMaxWidth(600); VBox heading = new VBox(7, label(text, "heading"), intro); heading.getStyleClass().add("page-heading"); page.getChildren().add(heading);
    }
    private Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    private Label message(String text, String style) { Label label = label(text, style); label.setWrapText(true); label.setMaxWidth(Double.MAX_VALUE); return label; }
    private VBox panel(Node... nodes) { VBox box = new VBox(14, nodes); box.getStyleClass().add("panel"); return box; }
    private Button button(String name, Runnable action) { Button b = new Button(name); b.setOnAction(e -> action.run()); return b; }
    private TextField input(String key) {
        TextField field = new TextField(form.getOrDefault(key, "")); field.setId(key); field.setMaxWidth(Double.MAX_VALUE);
        field.textProperty().addListener((o, old, value) -> { form.put(key, value); globalError.setText(""); updateNext(); });
        return field;
    }
    private VBox field(String name, Control control, String hint) {
        Label label = label(name, "field-label"); label.setLabelFor(control);
        VBox box = new VBox(7, label, control); if (!hint.isBlank()) box.getChildren().add(message(hint, "muted")); return box;
    }
    private VBox secret(String name, String key, String hint) {
        PasswordField masked = new PasswordField(); masked.setText(form.get(key)); masked.setId(key);
        TextField shown = new TextField(form.get(key)); shown.setVisible(false); shown.setManaged(false);
        masked.textProperty().addListener((o, old, value) -> { form.put(key, value); if (!shown.getText().equals(value)) shown.setText(value); updateNext(); });
        shown.textProperty().addListener((o, old, value) -> { form.put(key, value); if (!masked.getText().equals(value)) masked.setText(value); updateNext(); });
        StackPane input = new StackPane(masked, shown); HBox.setHgrow(input, Priority.ALWAYS);
        ToggleButton reveal = new ToggleButton("Show"); reveal.setAccessibleText("Show " + name.toLowerCase());
        reveal.setGraphic(Ui.icon("eye", 13));
        reveal.setOnAction(e -> { boolean show = reveal.isSelected(); masked.setVisible(!show); masked.setManaged(!show); shown.setVisible(show); shown.setManaged(show); reveal.setText(show ? "Hide" : "Show"); });
        Label label = label(name, "field-label"); label.setLabelFor(masked);
        HBox row = new HBox(7, input, reveal); row.setAlignment(Pos.CENTER_LEFT);
        if (key.equals("adminPassword")) row.getChildren().add(button("New", () -> masked.setText(Model.password())));
        VBox box = new VBox(7, label, row); if (!hint.isBlank()) box.getChildren().add(message(hint, "muted")); return box;
    }
    private void welcome() {
        heroLogo = brandImage(!light, 210); VBox.setMargin(heroLogo, new Insets(6, 0, 4, 0)); page.getChildren().add(heroLogo);
        Text name = new Text("Install Open Integration Engine "); name.getStyleClass().add("hero-title");
        Text version = new Text(payload.version()); version.getStyleClass().add("hero-version");
        page.getChildren().add(new VBox(9, new TextFlow(name, version), message("Install a new engine, or upgrade an engine already on this computer.", "intro")));
        VBox introduction = panel(label("Set up your engine", "option-title"),
                message("Choose an installation folder, Java runtime and startup options. For an existing engine, the installer keeps your settings and data and creates a backup before replacing program files.", "intro"),
                new Separator(), label("Choose your add-ons", "option-title"),
                message("OIE Web Client, Role-Based Access Control and Simple Channel History are included. All three are selected by default; you can change each choice before installing.", "intro"));
        page.getChildren().add(introduction);
        GridPane provenance = grid();
        row(provenance, 0, "Release source", payload.manifest.path("archive").asText(), null);
        row(provenance, 1, "Payload size", Math.round(payload.size() / 1048576.0) + " MB", null);
        row(provenance, 2, "SHA-256", payload.sourceHash(), null);
        row(provenance, 3, "Launcher", payload.manifest.path("launcher").path("ref").asText() + " · " + payload.manifest.path("launcher").path("commit").asText(), null);
        row(provenance, 4, "Built", payload.manifest.path("builtAt").asText(), null);
        TitledPane details = new TitledPane("Package details", provenance); details.setExpanded(false); details.getStyleClass().add("package-details");
        page.getChildren().add(details);
    }
    private void license() {
        heading("License terms", "Read the Mozilla Public License 2.0 below. Accept the terms to continue.");
        licenseLoaded = false;
        try (var input = getClass().getResourceAsStream("/LICENSE.txt")) {
            if (input == null) throw new java.io.IOException("The bundled license is missing.");
            TextArea text = new TextArea(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            text.setEditable(false); text.setWrapText(true); text.setMinHeight(180); text.setPrefHeight(280); text.setMaxHeight(360);
            text.getStyleClass().add("license-text"); text.setAccessibleText("Mozilla Public License 2.0 license terms");
            CheckBox accept = new CheckBox("I accept the terms of the Mozilla Public License 2.0.");
            accept.setWrapText(true); accept.setSelected(licenseAccepted);
            accept.setOnAction(e -> { licenseAccepted = accept.isSelected(); rail(); updateNext(); });
            page.getChildren().addAll(text, accept); licenseLoaded = true;
        } catch (java.io.IOException error) { licenseAccepted = false; globalError.setText(error.getMessage()); }
    }
    private void location() {
        heading("Where is the engine?", "Select a new or empty folder for a new engine. To upgrade, select the folder of the engine you have now.");
        TextField path = input("installDir"); HBox.setHgrow(path, Priority.ALWAYS);
        Button browse = button("Browse…", () -> {
            DirectoryChooser picker = new DirectoryChooser(); picker.setTitle("Choose an engine folder");
            try { Path p = Model.expand(form.get("installDir")); if (Files.isDirectory(p)) picker.setInitialDirectory(p.toFile()); } catch (Exception ignored) {}
            var chosen = picker.showDialog(stage); if (chosen != null) path.setText(chosen.toString());
        });
        Label folder = label("Folder", "field-label"); folder.setLabelFor(path); locationStatus = message("Checking folder…", "muted");
        page.getChildren().add(panel(folder, new HBox(8, path, browse), locationStatus, message("A new engine needs about " + payload.needed() / 1048576 + " MB of disk space.", "muted")));
        existingPanel = new VBox(); page.getChildren().add(existingPanel);
        PauseTransition delay = new PauseTransition(Duration.millis(350)); delay.setOnFinished(e -> validateLocation());
        path.textProperty().addListener((o, old, value) -> {
            locationGeneration++; locationBusy = true; locationStatus.setText("Checking folder…");
            visited.removeAll(List.of("java", "network", "database", "admin", "service", "plugins", "review"));
            rail(); updateNext(); delay.playFromStart();
        });
        validateLocation();
    }
    private void validateLocation() {
        String raw = form.get("installDir"); long generation = ++locationGeneration; locationBusy = true; updateNext();
        checks.submit(() -> {
            try { var found = Model.destination(raw, payload); Platform.runLater(() -> {
                if (generation != locationGeneration || !raw.equals(form.get("installDir"))) return;
                String previousMode = mode; destination = found; mode = found.mode(); checkedPath = raw; locationError = ""; locationBusy = false;
                if (!mode.equals(previousMode) && found.existing() != null) {
                    service = found.existing().service() != null && !found.existing().service().isNull();
                    if (!found.existing().javaCmd().isBlank()) {
                        String previousJava = found.existing().javaCmd();
                        if (previousJava.startsWith(found.home().resolve("jre").toString())) previousJava = Model.BUNDLED;
                        form.put("javaCmd", previousJava); verifiedJava = "";
                    }
                    visited.removeAll(List.of("network", "database", "admin", "service", "plugins", "review"));
                }
                if (current.equals("location")) {
                    locationStatus.setText(found.existing() == null ? found.free() / 1073741824 + " GB free" : "Engine " + found.existing().version() + " found. This installer will " + mode + " it.");
                    locationStatus.getStyleClass().remove("error"); renderExisting(); rail();
                }
                updateNext();
            }); } catch (Exception e) { Platform.runLater(() -> {
                if (generation != locationGeneration) return;
                destination = null; locationBusy = false; locationError = e.getMessage();
                if (current.equals("location")) { locationStatus.setText(locationError); if (!locationStatus.getStyleClass().contains("error")) locationStatus.getStyleClass().add("error"); existingPanel.getChildren().clear(); }
                updateNext();
            }); }
        });
    }
    private void renderExisting() {
        existingPanel.getChildren().clear(); if (destination == null || destination.existing() == null) return;
        var ex = destination.existing(); GridPane grid = grid();
        row(grid, 0, "Installed", ex.version() + "  →  " + payload.version(), null);
        row(grid, 1, "Database", ex.database() + " · HTTPS " + ex.httpsPort() + " / HTTP " + ex.httpPort(), null);
        row(grid, 2, "Status", (ex.running() ? "Running" : "Stopped") + " · " + (ex.managed() ? "Installer-managed" : "Installed by another tool"), null);
        existingPanel.getChildren().add(panel(label("Engine in this folder", "section-title"), grid,
                message("The installer backs up conf/ and appdata/, replaces program files and keeps your configuration, database, logs, custom libraries and other extensions.", "muted")));
    }
    private void java() {
        heading("Which Java runtime runs the engine?", "The engine includes its own Java 21 runtime. Use it, or choose an installed Java 17 or later.");
        ToggleGroup group = new ToggleGroup(); VBox choices = new VBox(10); Map<String, RadioButton> known = new LinkedHashMap<>();
        TextField path = input("javaCmd"); path.setPromptText("/path/to/jdk/bin/java");
        javaStatus = message("", "muted"); VBox custom = panel(field("Path to Java", path, "Enter the path to its java executable."), javaStatus);
        custom.getStyleClass().add("custom-runtime"); custom.managedProperty().bind(custom.visibleProperty());
        boolean isCustom = !form.get("javaCmd").equals(Model.BUNDLED) && runtimes.stream().noneMatch(j -> j.path().toString().equals(form.get("javaCmd"))); custom.setVisible(isCustom);
        if (payload.nativeEngine()) {
            RadioButton bundled = option("Bundled Java 21", "Installed with the engine. Continues working after this installer is removed.", "", "Recommended");
            bundled.setToggleGroup(group); bundled.setSelected(form.get("javaCmd").equals(Model.BUNDLED));
            bundled.setOnAction(e -> { custom.setVisible(false); path.setText(Model.BUNDLED); }); choices.getChildren().add(bundled); known.put(Model.BUNDLED, bundled);
        }
        for (var runtime : runtimes) {
            RadioButton choice = option("Java " + runtime.version(), runtime.vendor(), runtime.path().toString(), runtime.recommended() ? "Recommended" : !runtime.supported() ? "Needs 17+" : "");
            choice.setToggleGroup(group); choice.setDisable(!runtime.supported()); choice.setSelected(runtime.path().toString().equals(form.get("javaCmd")));
            choice.setOnAction(e -> { custom.setVisible(false); path.setText(runtime.path().toString()); }); choices.getChildren().add(choice); known.put(runtime.path().toString(), choice);
        }
        RadioButton other = option("Another Java runtime", "Enter the path to its java executable.", "", ""); other.setToggleGroup(group); other.setSelected(isCustom);
        other.setOnAction(e -> { custom.setVisible(true); if (known.containsKey(path.getText())) path.clear(); path.requestFocus(); }); choices.getChildren().add(other);
        page.getChildren().addAll(choices, custom);
        PauseTransition delay = new PauseTransition(Duration.millis(350)); delay.setOnFinished(e -> validateJava());
        path.textProperty().addListener((o, old, value) -> { javaGeneration++; verifiedJava = ""; javaBusy = true; javaStatus.setText("Checking Java…"); updateNext(); delay.playFromStart(); }); validateJava();
    }
    private void validateJava() {
        String raw = form.get("javaCmd"); long generation = ++javaGeneration; javaBusy = true; updateNext();
        if (raw.equals(Model.BUNDLED) && payload.nativeEngine()) {
            javaBusy = false; javaError = ""; verifiedJava = raw; javaStatus.setText("Use the engine's bundled Java 21 runtime."); updateNext(); return;
        }
        checks.submit(() -> { var java = raw.isBlank() ? null : Model.probe(Model.expand(raw)); Platform.runLater(() -> {
            if (generation != javaGeneration || !raw.equals(form.get("javaCmd"))) return;
            javaBusy = false; verifiedJava = java != null && java.supported() ? raw : "";
            javaError = java == null ? "Enter the path to a Java executable." : !java.supported() ? "Java 17 or later is required." : "";
            if (current.equals("java")) { javaStatus.setText(javaError.isEmpty() ? "Java " + java.version() + " · " + java.vendor() : javaError); javaStatus.getStyleClass().remove("error"); if (!javaError.isEmpty()) javaStatus.getStyleClass().add("error"); }
            updateNext();
        }); });
    }
    private void network() {
        heading("Network ports", "Choose the HTTP and HTTPS ports for this engine. The installer checks availability again before installing.");
        TextField http = input("httpPort"), https = input("httpsPort");
        VBox a = field("HTTP port", http, "Default: 8080"), b = field("HTTPS port", https, "Default: 8443"); HBox.setHgrow(a, Priority.ALWAYS); HBox.setHgrow(b, Priority.ALWAYS);
        page.getChildren().add(panel(new HBox(18, a, b)));
    }
    private static final Map<String, String> JDBC = Map.of("postgres", "jdbc:postgresql://localhost:5432/mirthdb", "mysql", "jdbc:mysql://localhost:3306/mirthdb", "sqlserver", "jdbc:sqlserver://localhost:1433;databaseName=mirthdb", "oracle", "jdbc:oracle:thin:@localhost:1521:DB");
    private static final Map<String, String> DATABASE_NAMES = Map.of("derby", "Derby", "postgres", "PostgreSQL", "mysql", "MySQL", "sqlserver", "SQL Server", "oracle", "Oracle");
    private RadioButton option(String title, String description, String path, String badge) {
        Label name = message(title, "option-title"); HBox.setHgrow(name, Priority.ALWAYS);
        HBox heading = new HBox(8, name); heading.setAlignment(Pos.CENTER_LEFT); if (!badge.isEmpty()) heading.getChildren().add(label(badge, "tag"));
        VBox body = new VBox(4, heading, message(description, "option-description")); body.setMinWidth(0);
        if (!path.isEmpty()) body.getChildren().add(message(path, "option-path"));
        RadioButton choice = new Ui.Option(body);
        choice.setAccessibleText(title); choice.setAccessibleHelp(description + (path.isEmpty() ? "" : " " + path)); return choice;
    }
    private void database() {
        heading("Which database stores your engine data?", "Derby is built in and needs no setup. Select an external database for a production deployment.");
        TextField url = input("databaseUrl"); VBox external = panel(field("JDBC URL", url, "The external database and user must already exist."), field("Username", input("databaseUsername"), ""), secret("Password", "databasePassword", ""));
        external.setVisible(!form.get("database").equals("derby")); external.managedProperty().bind(external.visibleProperty());
        ToggleGroup group = new ToggleGroup(); TilePane choices = new TilePane(10, 10); choices.getStyleClass().add("database-options");
        choices.prefTileWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(() -> {
            double width = choices.getWidth(); int columns = Math.max(1, (int)((width + 10) / 210)); return Math.max(180, Math.floor((width - (columns - 1) * 10) / columns));
        }, choices.widthProperty()));
        Map<String, String> descriptions = Map.of("derby", "Built in. No setup.", "postgres", "Recommended for production.", "mysql", "MySQL 8 or MariaDB.", "sqlserver", "Microsoft SQL Server.", "oracle", "Oracle Database.");
        for (String id : Model.DATABASES) {
            RadioButton choice = option(DATABASE_NAMES.get(id), descriptions.get(id), "", id.equals("derby") ? "Default" : ""); choice.getStyleClass().add("database-option");
            choice.setToggleGroup(group); choice.setSelected(form.get("database").equals(id));
            choice.setOnAction(e -> { form.put("database", id); if (!id.equals("derby")) url.setText(JDBC.get(id)); external.setVisible(!id.equals("derby")); updateNext(); });
            choices.getChildren().add(choice);
        }
        page.getChildren().addAll(choices, external, message("The installer does not test an external database connection.", "muted"));
    }
    private void admin() {
        heading("Set the administrator password", "The engine sets this password on the admin user when it creates its database. The installer removes it from the settings file after the first sign-in.");
        TextField user = new TextField("admin"); user.setEditable(false);
        page.getChildren().add(panel(field("Username", user, ""), secret("Password", "adminPassword", "Use at least 15 characters. Save the password shown when installation finishes.")));
        if (!form.get("database").equals("derby")) page.getChildren().add(message("If the external database already contains engine tables, the engine keeps its existing administrator password.", "callout"));
    }
    private String serviceReason() {
        if (Services.supported().isEmpty()) return "This system has no supported service manager.";
        try { return Services.blocked(Model.expand(form.get("installDir"))); } catch (Exception e) { return e.getMessage(); }
    }
    private void service() {
        heading("Keep the engine running", "A system service starts the engine when this computer starts and restarts it if it fails. You do not need to sign in.");
        Ui.Switch choice = new Ui.Switch("Run the engine as a service", service); choice.setDisable(Services.supported().isEmpty());
        Label explanation = message(service ? serviceReason().isEmpty() ? "Administrator approval is requested when you install." : serviceReason()
                : "The installer starts the engine as a normal process. The engine stops when you restart the computer.", "muted");
        String registration = Model.MAC ? "Starts at boot under your user account, using a system launch daemon." : Model.WINDOWS ? "Starts automatically under the Windows Local Service account." : "Starts at boot using a systemd system service.";
        HBox row = switchRow(choice, "Run the engine as a service", registration, "");
        choice.setOnAction(e -> { service = choice.isSelected(); explanation.setText(service ? serviceReason().isEmpty() ? "Administrator approval is requested when you install." : serviceReason() : "The installer starts the engine as a normal process. The engine stops when you restart the computer."); updateNext(); });
        page.getChildren().add(panel(row, explanation));
        if (!mode.equals("new") && destination.existing().service() != null && !destination.existing().service().isNull())
            page.getChildren().add(message("This engine already has a service. Turn the option off to remove it and start the engine as a normal process.", "callout"));
    }
    private void plugins() {
        heading("Which add-ons would you like?", "All three are bundled with this installer. Choose the ones to install.");
        Map<String, String> descriptions = Map.of("websupport", "Use the engine in your browser. Web Support includes and serves the OIE Web Client.",
                "rbac", "Manage roles, permissions and channel access for engine users.", "simple-channel-history", "Track, compare and restore channel and code template revisions.");
        VBox options = panel(); options.setSpacing(0); int index = 0;
        for (var plugin : payload.plugins) {
            if (index++ > 0) options.getChildren().add(new Separator());
            Ui.Switch choice = new Ui.Switch(plugin.name(), plugins.get(plugin.id())); choice.setId("install-" + plugin.id()); choice.setAccessibleHelp(descriptions.get(plugin.id()));
            choice.setOnAction(e -> plugins.put(plugin.id(), choice.isSelected()));
            HBox row = switchRow(choice, plugin.name(), descriptions.get(plugin.id()), plugin.version()); row.getStyleClass().add("addon-row"); options.getChildren().add(row);
        }
        page.getChildren().add(options);
        if (!mode.equals("new")) page.getChildren().add(message("Selected add-ons replace their installed versions, which are backed up first. Turning one off keeps any existing copy installed.", "callout"));
    }
    private HBox switchRow(Ui.Switch control, String name, String description, String version) {
        Label title = message(name, "option-title"); HBox heading = new HBox(8, title); heading.setAlignment(Pos.CENTER_LEFT);
        if (!version.isEmpty()) heading.getChildren().add(label(version, "tag"));
        VBox body = new VBox(5, heading, message(description, "option-description")); body.getStyleClass().add("switch-label");
        body.setOnMouseClicked(e -> { if (!control.isDisabled()) { control.fire(); control.requestFocus(); } });
        HBox row = new HBox(control, body); Ui.alignControlRow(row, body); return row;
    }
    private GridPane grid() {
        GridPane grid = new GridPane(); grid.setHgap(18); grid.setVgap(18);
        ColumnConstraints a = new ColumnConstraints(115), b = new ColumnConstraints(), c = new ColumnConstraints(); b.setHgrow(Priority.ALWAYS); b.setFillWidth(true);
        grid.getColumnConstraints().addAll(a, b, c); return grid;
    }
    private void row(GridPane grid, int index, String name, String value, String edit) {
        boolean review = grid.getStyleClass().contains("review-grid");
        int position = review ? index * 2 : index;
        Label k = label(name, "row-key"), v = message(value, "row-value"); v.setMinWidth(0); v.setMaxWidth(Double.MAX_VALUE);
        grid.add(k, 0, position); grid.add(v, 1, position); GridPane.setValignment(k, VPos.TOP);
        if (review) { GridPane.setMargin(k, new Insets(15, 0, 15, 0)); GridPane.setMargin(v, new Insets(15, 0, 15, 0)); if (index > 0) grid.add(new Separator(), 0, position - 1, 3, 1); }
        else if (name.equals("SHA-256") || name.equals("Launcher")) v.getStyleClass().add("mono");
        if (edit != null) { Button button = button("Edit", () -> show(edit)); button.getStyleClass().add("quiet"); grid.add(button, 2, position); GridPane.setValignment(button, VPos.TOP); if (review) GridPane.setMargin(button, new Insets(10, 0, 10, 0)); }
    }
    private void review() {
        heading("Review the " + (mode.equals("new") ? "installation" : mode), "Administrator approval is requested when you install. The installer registers an uninstaller" + (service ? " and a system service." : "."));
        if (!installError.isEmpty()) page.getChildren().add(message(installError, "error"));
        GridPane grid = grid(); grid.getStyleClass().add("review-grid"); grid.setVgap(0); int i = 0;
        row(grid, i++, "Folder", form.get("installDir"), "location");
        if (!mode.equals("new") && destination != null && destination.existing() != null) { row(grid, i++, verb(), destination.existing().version() + " → " + payload.version(), null);
            row(grid, i++, "Kept", "Existing database, users, channels, credentials, configuration and other extensions", null);
            row(grid, i++, "Backup", "conf/ and appdata/ are copied into backups/ before program files are replaced", null); }
        row(grid, i++, "Java", form.get("javaCmd").equals(Model.BUNDLED) ? "Bundled Java 21 · installed with the engine" : form.get("javaCmd"), "java");
        if (mode.equals("new")) {
            row(grid, i++, "Ports", "HTTPS " + form.get("httpsPort") + " · HTTP " + form.get("httpPort"), "network");
            row(grid, i++, "Database", DATABASE_NAMES.get(form.get("database")) + (form.get("database").equals("derby") ? "" : " · " + form.get("databaseUrl")), "database");
            row(grid, i++, "Sign in", "admin with the password from the Administrator step", "admin");
        }
        row(grid, i++, "Startup", service ? Services.supported() + " system service · starts at boot" : "Start once now", "service");
        List<String> choices = new ArrayList<>(); for (var p : payload.plugins) choices.add(p.name() + " " + p.version() + " · " + (plugins.get(p.id()) ? "Install" : mode.equals("new") ? "Skip" : "Skip; keep existing copy"));
        row(grid, i, "Add-ons", String.join("\n", choices), "plugins"); page.getChildren().add(panel(grid));
    }
    private void install() {
        ObjectNode data = Json.object(); form.forEach(data::put); data.put("service", service); data.set("plugins", Json.MAPPER.valueToTree(plugins));
        data.put("expectedMode", mode); if (destination != null && destination.existing() != null) data.put("expectedVersion", destination.existing().version());
        Model.Plan plan;
        try { plan = Model.Plan.from(data); } catch (Exception e) { globalError.setText(e.getMessage()); return; }
        running = true; installError = ""; jobRows.clear(); jobMarks.clear(); logs.clear(); show("install");
        worker.submit(() -> {
            try {
                Map<String, Object> completed = Privileges.install(payload, plan, event -> Platform.runLater(() -> updateProgress(event)),
                        line -> Platform.runLater(() -> { logs.appendText(line + "\n"); logs.setScrollTop(Double.MAX_VALUE); }));
                Platform.runLater(() -> { running = false; done = true; result = completed; show("done"); });
            } catch (Exception e) { Platform.runLater(() -> {
                running = false; installError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                destination = null; checkedPath = ""; visited.retainAll(Set.of("welcome", "license", "location"));
                page.getChildren().addAll(message(installError, "error"), message("Any completed changes and backups remain in the engine folder. Resolve the problem and check the location before trying again. Database migrations are not automatically rolled back.", "muted"),
                        button("Check location again", () -> { done = false; show("location"); }));
                rail();
            }); }
        });
    }
    private void progress() {
        heading((mode.equals("upgrade") ? "Upgrading" : mode.equals("reinstall") ? "Reinstalling" : "Installing") + " Open Integration Engine " + payload.version(), "Keep this window open. The engine can take a few minutes to start.");
        progress.setMaxWidth(Double.MAX_VALUE); progress.setProgress(0); phase.setText("Preparing installation…");
        percentage.setText("0%"); percentage.getStyleClass().setAll("progress-percent"); phase.getStyleClass().setAll("muted"); phase.setWrapText(true);
        HBox progressHead = new HBox(14, percentage, phase); progressHead.setAlignment(Pos.CENTER_LEFT);
        VBox rows = new VBox(9); rows.setId("progress-rows");
        for (String id : Installer.steps(!mode.equals("new"))) {
            Label row = message(Installer.stepLabel(id), "job-pending"); jobRows.put(id, row);
            StackPane mark = new StackPane(); mark.getStyleClass().addAll("job-mark", "pending-mark"); jobMarks.put(id, mark);
            HBox item = new HBox(10, mark, row); item.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(row, Priority.ALWAYS); rows.getChildren().add(item);
        }
        logs.setEditable(false); logs.setWrapText(true); logs.setPrefRowCount(9); logs.getStyleClass().add("log-text");
        TitledPane details = new TitledPane("Show details", logs); details.setExpanded(false);
        page.getChildren().add(panel(progressHead, progress, rows, details));
    }
    private void updateProgress(Installer.Event event) {
        if (!current.equals("install")) return;
        Label row = jobRows.get(event.id());
        if (row != null) {
            row.setText(event.label() + (event.detail().isBlank() ? "" : " · " + event.detail())); row.getStyleClass().setAll("job-" + event.state());
            StackPane mark = jobMarks.get(event.id()); mark.getStyleClass().setAll("job-mark", event.state() + "-mark"); mark.getChildren().clear();
            if (event.state().equals("done")) mark.getChildren().add(Ui.icon("check", 12));
        }
        if (event.state().equals("active") || event.state().equals("done")) {
            progress.setProgress(Math.max(progress.getProgress(), event.progress())); percentage.setText(Math.round(progress.getProgress() * 100) + "%"); phase.setText(event.label());
        }
    }
    private void complete() {
        String completedMode = String.valueOf(result.get("mode"));
        String lead = completedMode.equals("upgrade") ? "Upgraded from " + result.get("from") + ". The installer kept your settings, database and extensions."
                : completedMode.equals("reinstall") ? "The installer replaced the program files and kept your settings, database and extensions."
                : "The engine accepted the admin sign-in. The installer removed the initial password from the settings file.";
        VBox heading = new VBox(6, message("Open Integration Engine " + result.get("version") + " is running", "heading"), message(lead, "intro"));
        heading.setMinWidth(0); HBox.setHgrow(heading, Priority.ALWAYS);
        HBox hero = new HBox(18, Ui.successMark(), heading); hero.setAlignment(Pos.CENTER_LEFT); hero.getStyleClass().add("done-hero");
        page.getChildren().add(hero);
        GridPane cards = new GridPane(); cards.setHgap(12); cards.setVgap(12);
        for (int i = 0; i < 2; i++) { ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(50); column.setFillWidth(true); cards.getColumnConstraints().add(column); }
        Button open = button("Open", () -> getHostServices().showDocument(String.valueOf(result.get("url")))); open.getStyleClass().addAll("primary", "small");
        HBox openActions = new HBox(7, open); openActions.setAlignment(Pos.CENTER_LEFT);
        VBox address = panel(label("Address", "section-title"), message(String.valueOf(result.get("url")), "address"),
                message(completedMode.equals("new") ? "The engine uses a new self-signed certificate." : "The engine keeps its certificate.", "muted"), openActions);
        address.setMinWidth(0); cards.add(address, 0, 0);
        if (result.containsKey("webClientUrl")) {
            Button web = button("Open OIE Web Client", () -> getHostServices().showDocument(String.valueOf(result.get("webClientUrl")))); web.getStyleClass().add("small");
            // Keep the two actions readable in the narrower two-column cards.
            address.getChildren().add(web);
        }
        if (result.containsKey("adminPassword")) {
            String password = String.valueOf(result.get("adminPassword")); Label secret = label("••••••••••••••••••••••••", "mono");
            secret.getStyleClass().add("secret-value"); secret.setMinWidth(0); secret.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(secret, Priority.ALWAYS);
            ToggleButton reveal = new ToggleButton("Show"); reveal.setGraphic(Ui.icon("eye", 12)); reveal.getStyleClass().add("small"); reveal.setMinWidth(Region.USE_PREF_SIZE); reveal.setAccessibleText("Show administrator password");
            reveal.setOnAction(e -> { secret.setText(reveal.isSelected() ? password : "••••••••••••••••••••••••"); reveal.setText(reveal.isSelected() ? "Hide" : "Show"); reveal.setAccessibleText(reveal.isSelected() ? "Hide administrator password" : "Show administrator password"); });
            Button copy = smallCopy("Copy", password); copy.setAccessibleText("Copy administrator password");
            HBox passwordRow = new HBox(7, secret, reveal, copy); passwordRow.setAlignment(Pos.CENTER_LEFT);
            VBox signIn = panel(label("Sign in", "section-title"), message("User admin", "muted"), passwordRow, message("Save this password now. The installer does not store it.", "muted"));
            signIn.setMinWidth(0); cards.add(signIn, 1, 0);
        }
        if (result.containsKey("backup")) {
            String backup = String.valueOf(result.get("backup"));
            VBox backupCard = panel(label("Backup", "section-title"), message("Settings and data from before the " + completedMode + ":", "muted"), pathRow(backup, () -> openFolder(backup)));
            var changed = Json.MAPPER.valueToTree(result.get("changedConf"));
            if (changed != null && changed.isArray() && !changed.isEmpty()) {
                List<String> names = new ArrayList<>(); changed.forEach(item -> names.add(item.asText()));
                backupCard.getChildren().add(message("These settings files differ from the " + result.get("version") + " defaults: " + String.join(", ", names) + ". The defaults are in the backup folder.", "muted"));
            }
            var kept = Json.MAPPER.valueToTree(result.get("thirdParty"));
            if (kept != null && kept.isArray() && !kept.isEmpty()) {
                List<String> names = new ArrayList<>(); kept.forEach(item -> names.add(item.asText()));
                backupCard.getChildren().add(message("Kept extensions: " + String.join(", ", names) + ". Check that they support " + result.get("version") + ".", "muted"));
            }
            backupCard.setMinWidth(0); cards.add(backupCard, 1, 0);
        }
        cards.add(serviceDetails(), 0, 1, 2, 1);
        String home = String.valueOf(result.get("home")); cards.add(panel(label("Folder", "section-title"), pathRow(home, () -> openFolder(home))), 0, 2, 2, 1);
        page.getChildren().add(cards);
        Button close = button("Close the installer", stage::close); HBox closeRow = new HBox(close); closeRow.setAlignment(Pos.CENTER_RIGHT); page.getChildren().add(closeRow);
    }
    private VBox serviceDetails() {
        var serviceNode = Json.MAPPER.valueToTree(result.get("service"));
        VBox details;
        if (serviceNode != null && !serviceNode.isNull()) details = panel(label("Service · " + serviceNode.path("kind").asText(), "section-title"),
                commandRow("Start", serviceNode.path("start").asText()), commandRow("Stop", serviceNode.path("stop").asText()));
        else {
            String launcher = Path.of(String.valueOf(result.get("home"))).resolve(Model.WINDOWS ? "oieservice.exe" : "oieservice").toString();
            String command = Model.WINDOWS ? "& " + Privileges.powershell(launcher) : FilesSupport.quote(launcher);
            details = panel(label("Process", "section-title"), message("The engine runs as process " + result.get("pid") + ".", "muted"),
                    commandRow("Start", command + " start"), commandRow("Stop", command + " stop"));
        }
        details.getChildren().add(commandRow("Remove", String.valueOf(result.get("uninstall")))); details.setSpacing(9); return details;
    }
    private TextField commandValue(String value) {
        TextField text = new TextField(value); text.setEditable(false); text.getStyleClass().add("command-value"); text.setMinWidth(0); HBox.setHgrow(text, Priority.ALWAYS); return text;
    }
    private Button smallCopy(String name, String value) {
        Button button = button(name, () -> copy(value)); button.getStyleClass().add("small"); button.setMinWidth(Region.USE_PREF_SIZE); return button;
    }
    private HBox commandRow(String name, String value) {
        Label key = label(name, "muted"); key.setMinWidth(44); key.setPrefWidth(44);
        Button copy = smallCopy("Copy", value); copy.setAccessibleText("Copy " + name.toLowerCase() + " command");
        HBox row = new HBox(8, key, commandValue(value), copy); row.setAlignment(Pos.CENTER_LEFT); return row;
    }
    private HBox pathRow(String path, Runnable action) {
        Button show = button("Show", action); show.getStyleClass().add("small"); show.setAccessibleText("Show " + path);
        HBox row = new HBox(8, commandValue(path), show); row.setAlignment(Pos.CENTER_LEFT); return row;
    }
    private void copy(String text) { ClipboardContent content = new ClipboardContent(); content.putString(text); Clipboard.getSystemClipboard().setContent(content); }
    private void openFolder(String path) { try { Desktop.getDesktop().open(Path.of(path).toFile()); } catch (Exception e) { new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK).show(); } }
    @Override public void stop() { worker.shutdown(); checks.shutdownNow(); }
}
