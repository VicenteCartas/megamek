/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.SplashArt;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Action;
import megamek.client.ui.clientGUI.boardview.gpu.StartMenuItem.Button;
import megamek.client.ui.clientGUI.boardview.gpu.StartMenuItem.Emphasis;
import megamek.common.Configuration;
import megamek.common.util.TipOfTheDay;
import megamek.logging.MMLogger;

/** MegaMek's own start screen: its art and greeting, and its game pages in place of a plain menu. */
final class MegaMekStartContent extends StartScreenContent {
    private static final MMLogger LOGGER = MMLogger.create(MegaMekStartContent.class);
    private static final String TIPS = "megamek.client.TipOfTheDay";

    private final Consumer<Action> actions;

    /** {@code actions} runs the help entries and the bottom row's buttons, on the Swing thread. */
    MegaMekStartContent(Consumer<Action> actions) {
        this.actions = actions;
    }

    @Override
    public String title() {
        return Messages.getString("StartScreen.title");
    }

    @Override
    public String welcomeTitle() {
        // The Swing form's heading, which has translations
        return Messages.getString("LicensingDialog.title");
    }

    @Override
    public File art() {
        return new File(Configuration.miscImagesDir(), "mm-background.jpg");
    }

    @Override
    public @Nullable BufferedImage logo() {
        File file = new File(Configuration.miscImagesDir(), "mm-logo.png");
        try {
            BufferedImage logo = ImageIO.read(file);
            return logo == null ? null : SplashArt.logoWithTag(logo);
        } catch (IOException failure) {
            LOGGER.error(failure, "The start screen could not read {}", file);
            return null;
        }
    }

    @Override
    public String greetingOverline() {
        return Messages.getString("StartScreen.link", MMConstants.VERSION);
    }

    @Override
    public String greeting() {
        return Messages.getString("StartScreen.welcome");
    }

    @Override
    public List<String> tips() {
        return TipOfTheDay.plainTips(TIPS);
    }

    @Override
    public String version() {
        return Messages.getString("MegaMek.Version", MMConstants.VERSION);
    }

    @Override
    public List<StartMenuItem> menu() {
        // The screen builds MegaMek's main page itself (gamePages)
        return List.of();
    }

    @Override
    public List<StartMenuItem> help() {
        return List.of(helpItem("StartScreen.helpContents", Action.HELP_CONTENTS),
              helpItem("StartScreen.helpSkinning", Action.HELP_SKINNING),
              helpItem("StartScreen.reportBug", Action.REPORT_BUG),
              helpItem("StartScreen.copySystemData", Action.COPY_SYSTEM_DATA),
              helpItem("StartScreen.resetPrompts", Action.RESET_PROMPTS),
              helpItem("StartScreen.about", Action.ABOUT));
    }

    private Button helpItem(String key, Action action) {
        return new Button(Messages.getString(key), Emphasis.QUIET, () -> actions.accept(action));
    }

    @Override
    public void settings() {
        actions.accept(Action.SETTINGS);
    }

    @Override
    public void quit() {
        actions.accept(Action.QUIT);
    }

    @Override
    boolean gamePages() {
        return true;
    }
}
