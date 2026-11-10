/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The suite's legal notice in libGDX, the Swing {@code LicensingDialog.buildLegalHtml} as the start screen's welcome
 * and the About card show it: an accent callout that says the suite is unofficial and fan-made, sections in overline
 * headings for the licences and for help, each with its links, and the trademarks in fine print.
 */
final class SuiteLegalNotice {
    private final UiKit kit;
    private final Consumer<String> browse;
    private final String prefix;

    /**
     * @param browse opens a link's address in the browser
     * @param prefix starts the names of the callout and the links, such as "welcome" for "welcome-wiki"
     */
    SuiteLegalNotice(UiKit kit, Consumer<String> browse, String prefix) {
        this.kit = kit;
        this.browse = browse;
        this.prefix = prefix;
    }

    /** Adds the notice's blocks, one by one, with {@code band}: a table's add, or a reveal's band. */
    void addTo(Function<Actor, Cell<?>> band) {
        band.apply(callout(Messages.getString("StartWelcome.unofficial"),
              plain(Messages.getString("LicensingDialog.disclaimer")))).padTop(space("md"));
        band.apply(section(Messages.getString("StartWelcome.openSource"),
              Messages.getString("LicensingDialog.licensing") + " "
                    + Messages.getString("LicensingDialog.gameContentRulesText") + ".",
              link("rules", "open-in-new", Messages.getString("LicensingDialog.gameContentRulesText"),
                    Messages.getString("LicensingDialog.gameContentRulesUrl"))))
              .padTop(space("xl"));
        band.apply(section(Messages.getString("StartWelcome.community"),
              sentence(Messages.getString("LicensingDialog.wiki")) + " "
                    + Messages.getString("LicensingDialog.community") + " "
                    + Messages.getString("LicensingDialog.discordText") + ".",
              link("wiki", "book", Messages.getString("StartWelcome.wikiLink"),
                    Messages.getString("LicensingDialog.wikiUrl")),
              link("discord", "forum", Messages.getString("LicensingDialog.discordText"),
                    Messages.getString("LicensingDialog.discordUrl"))))
              .padTop(space("xl"));
        band.apply(wrapped(Messages.getString("LicensingDialog.trademark"), "caption", "text.muted"))
              .padTop(space("xl"));
    }

    static float space(String name) {
        return DesignTokens.shipped().space().get(name);
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
        callout.setName(prefix + "-callout");
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

    private UiButton link(String name, String icon, String text, String url) {
        UiButton link = kit.button("button-quiet" + UiTheme.COMPACT, icon, text, null);
        link.setName(prefix + "-" + name);
        UiKit.onChange(link, () -> browse.accept(url));
        return link;
    }

    Label wrapped(String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }
}
