import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.zip.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.stage.Stage;
import org.openintegrationengine.installer.Wizard;

/**
 * Drives the packaged JavaFX wizard as a user does: every page, its validation, rail navigation and a real installation.
 * Usage: WizardEndToEnd <new engine folder> <http port> <https port> <administrator password> <screenshot folder>
 * Then opens the wizard again on the installed folder and checks the reinstall path without installing.
 */
public final class WizardEndToEnd {
    private static Path shots;
    private static Stage stage;
    private static int shot;

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        String http = args[1], https = args[2], password = args[3];
        shots = Files.createDirectories(Path.of(args[4]));
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        Platform.setImplicitExit(false);
        started.await();
        try {
            install(home, http, https, password);
            if (System.getenv("OIE_WIZARD_STOP_AT_REVIEW") == null) reinstall(home);
            System.out.println("WIZARD END-TO-END PASSED");
        } catch (Throwable failure) {
            capture("failure");
            System.out.println("Visible text at failure:\n  " + String.join("\n  ", texts()));
            throw failure;
        } finally {
            Platform.exit();
        }
    }

    private static void install(Path home, String http, String https, String password) throws Exception {
        open();
        page("Welcome");
        check(next().getText().equals("Start"), "Welcome offers Start");
        click(next());

        page("License");
        check(next().isDisabled(), "Next waits for the license to be accepted");
        click(find(CheckBox.class, box -> box.getText().startsWith("I accept")));
        until("Next after accepting the license", () -> !next().isDisabled());
        capture("license");
        click(next());

        page("Location");
        TextField folder = find(TextField.class, field -> "installDir".equals(field.getId()));
        Path occupied = Files.createTempDirectory("oie-wizard-occupied-");
        Files.writeString(occupied.resolve("notes.txt"), "not an engine");
        type(folder, occupied.toString());
        until("the occupied-folder error", () -> visible("This folder is not empty and has no engine.") && next().isDisabled());
        type(folder, "relative/folder");
        until("the relative-path error", () -> visible("Enter a full path") && next().isDisabled());
        Files.delete(occupied.resolve("notes.txt")); Files.delete(occupied);
        type(folder, home.toString());
        until("the new folder to be accepted", () -> visible(" GB free") && !next().isDisabled());
        capture("location");
        click(next());

        page("Java");
        // The portable installer has no bundled Java: it selects an installed one.
        boolean bundled = System.getenv("OIE_WIZARD_INSTALLED_JAVA") == null;
        until("the Java runtime", () -> (!bundled || visible("Use the engine's bundled Java 21 runtime.")) && !next().isDisabled());
        click(next());

        page("Network");
        TextField httpPort = find(TextField.class, field -> "httpPort".equals(field.getId()));
        TextField httpsPort = find(TextField.class, field -> "httpsPort".equals(field.getId()));
        type(httpPort, https); type(httpsPort, https);
        until("equal ports to be refused", () -> next().isDisabled());
        type(httpPort, "70000");
        until("an out-of-range port to be refused", () -> next().isDisabled());
        type(httpPort, http);
        until("valid ports", () -> !next().isDisabled());
        capture("network");
        click(next());

        page("Database");
        click(find(RadioButton.class, option -> "PostgreSQL".equals(option.getAccessibleText())));
        TextField url = find(TextField.class, field -> "databaseUrl".equals(field.getId()));
        until("the PostgreSQL URL template", () -> url.getText().startsWith("jdbc:postgresql://") && !next().isDisabled());
        type(url, "");
        until("an empty JDBC URL to be refused", () -> next().isDisabled());
        click(find(RadioButton.class, option -> "Derby".equals(option.getAccessibleText())));
        until("Derby", () -> !next().isDisabled());
        click(next());

        page("Administrator");
        PasswordField secret = find(PasswordField.class, field -> "adminPassword".equals(field.getId()));
        type(secret, "too-short");
        until("a short password to be refused", () -> next().isDisabled());
        type(secret, password);
        until("the password", () -> !next().isDisabled());
        capture("administrator");
        click(next());

        page("Service");
        check(fx(() -> find(ToggleButton.class, toggle -> "Run the engine as a service".equals(toggle.getAccessibleText())).isSelected()), "the service is selected by default");
        until("the service option", () -> !next().isDisabled());
        click(next());

        page("Choose add-ons");
        for (String id : Payload.IDS) {
            check(fx(() -> find(ToggleButton.class, toggle -> ("install-" + id).equals(toggle.getId())).isSelected()), id + " starts selected");
        }
        ToggleButton rbac = find(ToggleButton.class, toggle -> "install-rbac".equals(toggle.getId()));
        check(fx(rbac::isSelected), "RBAC starts selected");
        click(rbac);
        check(!fx(rbac::isSelected), "RBAC is cleared");
        click(next());

        page("Review and install");
        until("the review rows", () -> visible(home.toString()) && visible("HTTPS " + https + " · HTTP " + http) && visible("Role-Based Access Control (RBAC)"));
        check(texts().stream().anyMatch(text -> text.contains("Role-Based Access Control (RBAC)") && text.contains("· Skip")), "Review shows RBAC skipped");
        check(texts().stream().anyMatch(text -> text.contains("OIE Web Client") && text.contains("· Install")), "Review shows the Web Client installed");
        // The rail returns to an earlier page; choices made there are kept.
        click(find(Button.class, button -> "Network".equals(button.getAccessibleText())));
        page("Network");
        check(fx(() -> httpPort(http)), "the HTTP port is kept");
        for (String title : List.of("Database", "Administrator", "Service", "Choose add-ons")) { click(next()); page(title); }
        check(!fx(() -> find(ToggleButton.class, toggle -> "install-rbac".equals(toggle.getId())).isSelected()), "the RBAC choice is kept");
        click(next());
        page("Review and install");
        check(next().getText().equals("Install"), "Review offers Install");
        capture("review");
        if (System.getenv("OIE_WIZARD_STOP_AT_REVIEW") != null) { fx(() -> { stage.close(); return null; }); return; }
        click(next());

        page("Install");
        until("installation progress", () -> visible("Verify the payload"), Duration.ofMinutes(1));
        capture("install");
        until("the Done page", () -> "Done".equals(title()) || visible("Check location again"), Duration.ofMinutes(10));
        check("Done".equals(title()), "the installation finished: " + String.join(" | ", texts()));

        until("the completion details", () -> visible("is running"));
        check(visible("https://localhost:" + https + "/oie-webadmin/"), "Done shows the OIE Web Client address");
        check(visible("Service · systemd"), "Done shows the systemd service");
        Label masked = find(Label.class, label -> label.getStyleClass().contains("secret-value"));
        check(!fx(masked::getText).equals(password), "the password starts hidden");
        click(find(ToggleButton.class, toggle -> "Show administrator password".equals(toggle.getAccessibleText())));
        check(fx(masked::getText).equals(password), "Show reveals the administrator password");
        capture("done");
        fx(() -> { stage.close(); return null; });
    }

    private static boolean httpPort(String expected) {
        return expected.equals(find(TextField.class, field -> "httpPort".equals(field.getId())).getText());
    }

    private static void reinstall(Path home) throws Exception {
        open();
        page("Welcome");
        click(next());
        page("License");
        click(find(CheckBox.class, box -> box.getText().startsWith("I accept")));
        until("Next after accepting the license", () -> !next().isDisabled());
        click(next());
        page("Location");
        type(find(TextField.class, field -> "installDir".equals(field.getId())), home.toString());
        until("the installed engine", () -> visible("This installer will reinstall it.") && !next().isDisabled());
        check(visible("Installer-managed"), "the engine is recognized as installer-managed");
        check(texts().stream().noneMatch("Network"::equals), "a reinstall skips the network, database and administrator pages");
        capture("reinstall-location");
        click(next());
        page("Java");
        until("the Java runtime", () -> !next().isDisabled());
        click(next());
        page("Service");
        check(fx(() -> find(ToggleButton.class, toggle -> "Run the engine as a service".equals(toggle.getAccessibleText())).isSelected()), "the existing service stays selected");
        click(next());
        page("Choose add-ons");
        click(next());
        page("Review and reinstall");
        check(next().getText().equals("Reinstall"), "Review offers Reinstall");
        check(visible("Existing database, users, channels"), "Review says what a reinstall keeps");
        capture("reinstall-review");
        fx(() -> { stage.close(); return null; });
    }

    private static void open() throws Exception {
        fx(() -> { stage = new Stage(); new Wizard().start(stage); return null; });
        until("the wizard window", () -> stage.isShowing() && !texts().isEmpty(), Duration.ofSeconds(60));
    }

    private static void page(String title) throws Exception {
        until("the " + title + " page", () -> title.equals(title()));
    }

    private static String title() {
        return fx(() -> labels().stream().filter(label -> label.getStyleClass().contains("view-title")).map(Label::getText).findFirst().orElse(""));
    }

    private static Button next() {
        return fx(() -> nodes(stage.getScene().getRoot()).stream().filter(node -> node instanceof Button button && button.isDefaultButton()).map(Button.class::cast).findFirst().orElseThrow());
    }

    private static <T extends Node> T find(Class<T> type, Predicate<T> match) {
        return fx(() -> nodes(stage.getScene().getRoot()).stream().filter(type::isInstance).map(type::cast).filter(node -> node.isVisible() && match.test(node)).findFirst()
                .orElseThrow(() -> new AssertionError("No matching " + type.getSimpleName() + " on " + title())));
    }

    private static List<Label> labels() {
        return nodes(stage.getScene().getRoot()).stream().filter(Label.class::isInstance).map(Label.class::cast).filter(Node::isVisible).toList();
    }

    private static List<String> texts() {
        return fx(() -> {
            List<String> texts = new ArrayList<>();
            for (Node node : nodes(stage.getScene().getRoot())) {
                if (!node.isVisible()) continue;
                if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) texts.add(labeled.getText());
                if (node instanceof javafx.scene.text.Text text && !text.getText().isBlank()) texts.add(text.getText());
            }
            return texts;
        });
    }

    private static boolean visible(String fragment) { return texts().stream().anyMatch(text -> text.contains(fragment)); }

    private static List<Node> nodes(Node root) {
        List<Node> found = new ArrayList<>(); Deque<Node> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            Node node = pending.pop(); found.add(node);
            if (node instanceof Parent parent) pending.addAll(parent.getChildrenUnmodifiable());
        }
        return found;
    }

    private static void click(ButtonBase control) {
        fx(() -> { if (control.isDisabled()) throw new AssertionError("Disabled: " + control.getText() + " on " + title()); control.fire(); return null; });
    }

    private static void type(TextInputControl field, String text) { fx(() -> { field.setText(text); return null; }); }

    private static void check(boolean condition, String what) { if (!condition) throw new AssertionError("Failed: " + what); }

    private static void until(String what, Callable<Boolean> condition) throws Exception { until(what, condition, Duration.ofSeconds(30)); }

    private static void until(String what, Callable<Boolean> condition, Duration limit) throws Exception {
        Instant deadline = Instant.now().plus(limit);
        while (Instant.now().isBefore(deadline)) {
            if (fx(condition)) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for " + what + " on " + title());
    }

    private static <T> T fx(Callable<T> work) {
        if (Platform.isFxApplicationThread()) {
            try { return work.call(); } catch (RuntimeException | Error e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        }
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        try { return task.get(30, TimeUnit.SECONDS); }
        catch (ExecutionException e) { if (e.getCause() instanceof RuntimeException r) throw r; if (e.getCause() instanceof Error r) throw r; throw new RuntimeException(e.getCause()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** A PNG of the window, for the CI artifact. */
    private static void capture(String name) {
        try {
            WritableImage image = fx(() -> stage == null || stage.getScene() == null ? null : stage.getScene().snapshot(null));
            if (image == null) return;
            int width = (int) image.getWidth(), height = (int) image.getHeight();
            PixelReader pixels = image.getPixelReader();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            for (int y = 0; y < height; y++) {
                raw.write(0);
                for (int x = 0; x < width; x++) { int argb = pixels.getArgb(x, y); raw.write(argb >> 16); raw.write(argb >> 8); raw.write(argb); }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream(); DataOutputStream ihdrData = new DataOutputStream(ihdr);
            ihdrData.writeInt(width); ihdrData.writeInt(height); ihdrData.write(new byte[]{8, 2, 0, 0, 0});
            chunk(png, "IHDR", ihdr.toByteArray());
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)) { raw.writeTo(deflate); }
            chunk(png, "IDAT", compressed.toByteArray());
            chunk(png, "IEND", new byte[0]);
            Files.write(shots.resolve(String.format("%02d-%s.png", ++shot, name)), png.toByteArray());
        } catch (Exception e) { System.out.println("Screenshot " + name + " failed: " + e); }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(out);
        stream.writeInt(data.length);
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        stream.write(name); stream.write(data);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(data);
        stream.writeInt((int) crc.getValue());
    }
}
