package org.openintegrationengine.installer;

import javafx.animation.*;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.control.skin.ToggleButtonSkin;
import javafx.scene.control.skin.RadioButtonSkin;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.util.Duration;

/** Small native components that follow the original installer's visual language. */
final class Ui {
    static Node successMark() {
        javafx.scene.shape.Circle ring = new javafx.scene.shape.Circle(26); ring.getStyleClass().add("success-ring");
        Node check = icon("check", 28);
        StackPane mark = new StackPane(ring, check); mark.setMinSize(58, 58); mark.setPrefSize(58, 58); mark.setMaxSize(58, 58);
        mark.getStyleClass().add("success-mark"); mark.setAccessibleText("Installation complete"); return mark;
    }
    static final boolean REDUCED_MOTION = Boolean.getBoolean("oie.reduceMotion");
    static final Interpolator EASE = Interpolator.SPLINE(.2, .8, .3, 1);

    static StackPane icon(String name, double size) {
        String path = switch (name) {
            case "check" -> "M5 12.5L9.5 17L19 7";
            case "moon" -> "M21 12.8A9 9 0 1 1 11.2 3A7 7 0 0 0 21 12.8Z";
            case "sun" -> "M16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12M12 2V4M12 20V22M2 12H4M20 12H22M5 5L6.5 6.5M17.5 17.5L19 19M5 19L6.5 17.5M17.5 6.5L19 5";
            case "eye" -> "M2 12S5.6 5 12 5S22 12 22 12S18.4 19 12 19S2 12 2 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12";
            default -> throw new IllegalArgumentException("Unknown icon: " + name);
        };
        SVGPath glyph = new SVGPath(); glyph.setContent(path); glyph.getStyleClass().add("icon-path");
        glyph.setStrokeLineCap(StrokeLineCap.ROUND); glyph.setStrokeLineJoin(StrokeLineJoin.ROUND);
        Group group = new Group(glyph); group.setScaleX(size / 24); group.setScaleY(size / 24);
        StackPane box = new StackPane(group); box.setMinSize(size, size); box.setPrefSize(size, size); box.setMaxSize(size, size); box.setMouseTransparent(true);
        return box;
    }
    static final class Switch extends ToggleButton {
        Switch(String accessibleName, boolean selected) {
            setSelected(selected); setAccessibleText(accessibleName); getStyleClass().add("switch");
            setMinSize(38, 22); setPrefSize(38, 22); setMaxSize(38, 22);
        }
        @Override protected Skin<?> createDefaultSkin() { return new SwitchSkin(this); }
    }
    private static final class SwitchSkin extends ToggleButtonSkin {
        private final Region track = new Region(), thumb = new Region();
        private Timeline motion;
        SwitchSkin(Switch control) {
            super(control); track.getStyleClass().add("switch-track"); thumb.getStyleClass().add("switch-thumb");
            track.setMouseTransparent(true); thumb.setMouseTransparent(true); getChildren().addAll(track, thumb);
            thumb.setTranslateX(control.isSelected() ? 16 : 0);
            control.selectedProperty().addListener((o, old, selected) -> {
                if (motion != null) motion.stop();
                if (REDUCED_MOTION) thumb.setTranslateX(selected ? 16 : 0);
                else { motion = new Timeline(new KeyFrame(Duration.millis(190), new KeyValue(thumb.translateXProperty(), selected ? 16 : 0, EASE))); motion.play(); }
            });
        }
        @Override protected void layoutChildren(double x, double y, double width, double height) {
            track.resizeRelocate(x, y, 38, 22); thumb.resizeRelocate(x + 3, y + 3, 16, 16);
        }
        @Override public void dispose() { if (motion != null) motion.stop(); super.dispose(); }
    }
    static final class Option extends RadioButton {
        private final VBox body;
        Option(VBox body) {
            this.body = body; getStyleClass().add("option-card"); setMinWidth(0); setMaxWidth(Double.MAX_VALUE);
        }
        @Override public javafx.geometry.Orientation getContentBias() { return javafx.geometry.Orientation.HORIZONTAL; }
        @Override protected Skin<?> createDefaultSkin() { return new OptionSkin(this, body); }
    }
    private static final class OptionSkin extends RadioButtonSkin {
        private final VBox body;
        private final StackPane ring = new StackPane();
        OptionSkin(Option control, VBox body) {
            super(control); this.body = body; ring.getStyleClass().add("radio");
            Region dot = new Region(); dot.getStyleClass().add("dot"); dot.setMinSize(7,7); dot.setPrefSize(7,7); dot.setMaxSize(7,7);
            ring.getChildren().add(dot); ring.setMouseTransparent(true); getChildren().setAll(ring, body);
        }
        @Override protected double computePrefWidth(double height, double top, double right, double bottom, double left) { return left + 280 + right; }
        @Override protected double computeMinWidth(double height, double top, double right, double bottom, double left) { return left + 150 + right; }
        @Override protected double computePrefHeight(double width, double top, double right, double bottom, double left) {
            double content = Math.max(120, (width < 0 ? 320 : width) - left - right - 27);
            return top + Math.max(16, body.prefHeight(content)) + bottom;
        }
        @Override protected void layoutChildren(double x, double y, double width, double height) {
            ring.resizeRelocate(x, y + 2, 15, 15);
            double content = Math.max(0, width - 27); body.resizeRelocate(x + 27, y, content, body.prefHeight(content));
        }
    }
    static void enter(Node node, boolean backwards) {
        if (REDUCED_MOTION) return;
        node.setOpacity(.88); node.setTranslateX(backwards ? -12 : 12);
        FadeTransition fade = new FadeTransition(Duration.millis(220), node); fade.setToValue(1); fade.setInterpolator(EASE);
        TranslateTransition slide = new TranslateTransition(Duration.millis(240), node); slide.setToX(0); slide.setInterpolator(EASE);
        new ParallelTransition(fade, slide).play();
    }
    static void alignControlRow(HBox row, Region body) {
        row.setAlignment(Pos.TOP_LEFT); row.setSpacing(14); HBox.setHgrow(body, Priority.ALWAYS); body.setMinWidth(0);
    }
}
