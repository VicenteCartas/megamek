/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiReveal;

/**
 * The start screen's welcome, the libGDX form of the Swing {@code LicensingDialog}, for whichever program the screen
 * starts (MegaMek or MekHQ); MegaMekLab, and any start without the screen, keep the Swing form. A kit dialog that
 * unfolds over a darkening scrim, its sections then rising in one after another, as MekHQ's immersive transmissions
 * do. An accent callout says the suite is unofficial and fan-made; sections in overline headings cover the licences
 * and where to find help, each with its links; the trademarks follow in fine print. Acknowledge, with "Do not bother
 * me again" on the footer's leading side, carries on; Quit, Escape and the close button end the program, as closing
 * the Swing form does.
 */
final class StartWelcome {
    /** What the Commander's answers do. */
    interface Answer {
        /** Acknowledge: the welcome has closed; {@code dontShowAgain} when the box was ticked. */
        void acknowledged(boolean dontShowAgain);

        /** Quit, Escape or the close button. */
        void quit();

        /** A link: opens {@code url} in the browser. */
        void browse(String url);
    }

    private final UiKit kit;
    private final boolean motion;
    private final UiDialog dialog;
    private final UiReveal bands;
    private final UiKit.Checkbox dontShowAgain;
    private final UiButton acknowledge;
    private final UiButton quit;

    /** {@code title} heads the welcome, such as "Welcome to MekHQ"; {@code program} names what Quit ends. */
    StartWelcome(UiKit kit, boolean motion, String title, String program, Answer answer) {
        this.kit = kit;
        this.motion = motion;
        dialog = new UiDialog(kit, title, answer::quit).width("dialog.md").unfold(true);
        dialog.card().setName("start-welcome");
        bands = new UiReveal(motion, motion ? UiDialog.unfoldSeconds() : 0);
        dialog.body().add(bands).growX().minWidth(0);

        bands.band(kit.typeLabel(Messages.getString("StartWelcome.version", MMConstants.VERSION), "caption",
              "text.muted"));
        new SuiteLegalNotice(kit, answer::browse, "welcome").addTo(bands::band);
        bands.band(wrapped(Messages.getString("LicensingDialog.acknowledgment")))
              .padTop(space("lg"));

        dontShowAgain = kit.checkbox(Messages.getString("chkIgnore.text"), false, true);
        dontShowAgain.setName("welcome-dont-show");
        dialog.leading().add(dontShowAgain);
        acknowledge = dialog.primary(Messages.getString("LicensingDialog.acknowledge"), () -> {
            dialog.close();
            answer.acknowledged(dontShowAgain.isTicked());
        });
        acknowledge.setName("welcome-acknowledge");
        quit = dialog.secondary(Messages.getString("StartWelcome.quit", program), answer::quit);
        quit.setName("welcome-quit");
    }

    private static float space(String name) {
        return SuiteLegalNotice.space(name);
    }

    /** Shows the welcome over everything on {@code stage}. */
    void show(Stage stage) {
        dialog.show(stage, motion);
    }

    boolean isOpen() {
        return dialog.isOpen();
    }

    UiDialog dialog() {
        return dialog;
    }

    UiReveal bands() {
        return bands;
    }

    UiKit.Checkbox dontShowAgain() {
        return dontShowAgain;
    }

    UiButton acknowledge() {
        return acknowledge;
    }

    UiButton quit() {
        return quit;
    }

    private Label wrapped(String text) {
        Label label = kit.typeLabel(text, "body", "text.primary");
        label.setWrap(true);
        return label;
    }
}
