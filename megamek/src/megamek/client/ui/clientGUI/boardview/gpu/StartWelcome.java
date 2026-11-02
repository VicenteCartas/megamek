/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiReveal;
import megamek.client.ui.gdx.UiTheme;

/**
 * The start screen's welcome, the libGDX form of the Swing {@code LicensingDialog} that MekHQ and MegaMekLab keep: a
 * kit dialog that unfolds over a darkening scrim, its sections then rising in one after another, as MekHQ's immersive
 * transmissions do. An accent callout says the suite is unofficial and fan-made; sections in overline headings cover
 * the licences and where to find help, each with its links; the trademarks follow in fine print. Acknowledge, with
 * "Do not bother me again" on the footer's leading side, carries on; Quit, Escape and the close button end MegaMek, as
 * closing the Swing form does.
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

    StartWelcome(UiKit kit, boolean motion, Answer answer) {
        this.kit = kit;
        this.motion = motion;
        dialog = new UiDialog(kit, Messages.getString("LicensingDialog.title"), answer::quit).width("dialog.md")
              .unfold(true);
        dialog.card().setName("start-welcome");
        bands = new UiReveal(motion, motion ? UiDialog.unfoldSeconds() : 0);
        dialog.body().add(bands).growX().minWidth(0);

        bands.band(kit.typeLabel(Messages.getString("StartWelcome.version", MMConstants.VERSION), "caption",
              "text.muted"));
        bands.band(callout(Messages.getString("StartWelcome.unofficial"),
              plain(Messages.getString("LicensingDialog.disclaimer")))).padTop(space("md"));
        bands.band(section(Messages.getString("StartWelcome.openSource"),
              Messages.getString("LicensingDialog.licensing") + " "
                    + Messages.getString("LicensingDialog.gameContentRulesText") + ".",
              link("welcome-rules", "open-in-new", Messages.getString("LicensingDialog.gameContentRulesText"),
                    Messages.getString("LicensingDialog.gameContentRulesUrl"), answer)))
              .padTop(space("xl"));
        bands.band(section(Messages.getString("StartWelcome.community"),
              sentence(Messages.getString("LicensingDialog.wiki")) + " "
                    + Messages.getString("LicensingDialog.community") + " "
                    + Messages.getString("LicensingDialog.discordText") + ".",
              link("welcome-wiki", "book", Messages.getString("StartWelcome.wikiLink"),
                    Messages.getString("LicensingDialog.wikiUrl"), answer),
              link("welcome-discord", "forum", Messages.getString("LicensingDialog.discordText"),
                    Messages.getString("LicensingDialog.discordUrl"), answer)))
              .padTop(space("xl"));
        bands.band(wrapped(Messages.getString("LicensingDialog.trademark"), "caption", "text.muted"))
              .padTop(space("xl"));
        bands.band(wrapped(Messages.getString("LicensingDialog.acknowledgment"), "body", "text.primary"))
              .padTop(space("lg"));

        dontShowAgain = kit.checkbox(Messages.getString("chkIgnore.text"), false, true);
        dontShowAgain.setName("welcome-dont-show");
        dialog.leading().add(dontShowAgain);
        acknowledge = dialog.primary(Messages.getString("LicensingDialog.acknowledge"), () -> {
            dialog.close();
            answer.acknowledged(dontShowAgain.isTicked());
        });
        acknowledge.setName("welcome-acknowledge");
        quit = dialog.secondary(Messages.getString("StartWelcome.quit"), answer::quit);
        quit.setName("welcome-quit");
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
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

    /** The disclaimer as plain text: the Swing form's HTML emphasis goes, the callout's heading carries it. */
    static String plain(String html) {
        return html.replaceAll("<[^>]+>", "").strip();
    }

    /** {@code text} as a sentence: a trailing colon, which led into a link in the Swing form, becomes a full stop. */
    static String sentence(String text) {
        String stripped = text.strip();
        return stripped.endsWith(":") ? stripped.substring(0, stripped.length() - 1) + "." : stripped;
    }

    /** The accent callout: a bar in accent.primary beside a heading in body.strong over its text. */
    private Table callout(String heading, String text) {
        Table callout = new Table();
        callout.setName("welcome-callout");
        callout.add(kit.solid("accent.primary")).width(space("xxs") * 2).growY().padRight(space("lg"));
        Table words = new Table();
        words.top().left();
        words.add(kit.typeLabel(heading, "body.strong", "text.primary")).left().row();
        words.add(wrapped(text, "body", "text.secondary")).growX().minWidth(0).padTop(space("xs"));
        callout.add(words).growX().minWidth(0);
        return callout;
    }

    /** A section: its heading in overline, its text, and its links in a row under it. */
    private Table section(String heading, String text, Actor... links) {
        Table section = new Table();
        section.top().left();
        section.add(kit.roleTypeLabel("overline", heading, "text.muted")).left().row();
        section.add(wrapped(text, "body", "text.secondary")).growX().minWidth(0).padTop(space("xs")).row();
        Table row = new Table();
        row.left();
        for (Actor link : links) {
            row.add(link).padRight(space("sm"));
        }
        section.add(row).left().padTop(space("sm"));
        return section;
    }

    private UiButton link(String name, String icon, String text, String url, Answer answer) {
        UiButton link = kit.button("button-quiet" + UiTheme.COMPACT, icon, text, null);
        link.setName(name);
        UiKit.onChange(link, () -> answer.browse(url));
        return link;
    }

    private Label wrapped(String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }
}
