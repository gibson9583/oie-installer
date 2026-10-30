package org.openintegrationengine.installer;

import javafx.application.Application;

/** Keeps JavaFX out of Main, so the CLI and text installer run on Java without JavaFX. */
final class WizardLauncher {
    static void launch() { Application.launch(Wizard.class); }
}
