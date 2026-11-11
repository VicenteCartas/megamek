/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.Client;
import megamek.client.lobby.LobbyFiles;
import megamek.client.ui.BugReportMessages;
import megamek.client.ui.Messages;
import megamek.client.ui.PackageBugReportAction;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.common.util.BugReportBundle;
import megamek.common.util.IssueReportUrl;

/**
 * The libGDX Report a Bug card, which replaces the Swing {@code BugReportDialog} on the start screen, in the lobby and
 * over the battle: the steps to report a bug, the Report a Bug button that packages the game and the logs, which
 * repository each kind of bug goes to with a button for each, and Discord. The packaging is
 * {@link PackageBugReportAction}'s, asking for the archive through the scene's file routes and answering with cards.
 */
final class GpuBugReport {
    private static final BugReportMessages I18N = new BugReportMessages();
    private static final String MM_DATA_ISSUES = "https://github.com/MegaMek/mm-data/issues/new";
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern STEP_NUMBER = Pattern.compile("^\\d+\\)\\s*");
    private static final Pattern BLOCKQUOTE = Pattern.compile("(?is)<blockquote>(.*?)</blockquote>");
    private static final Pattern HEADING = Pattern.compile("(?is)<h\\d[^>]*>(.*?)</h\\d>");

    /**
     * The guide's text without its markup.
     *
     * @param intro  what comes before the steps
     * @param steps  the steps, without their numbers
     * @param where  the heading over which bug goes where
     * @param places each kind of bug and where it goes
     */
    record Guide(String intro, List<String> steps, String where, List<String> places) {
        /** The guide of the player's language. */
        static Guide shipped() {
            return of(I18N.get("mainText"), I18N.get("secondaryText"));
        }

        /** Reads the Swing dialog's HTML: a paragraph and a block quote of numbered steps, a heading and a list. */
        static Guide of(String mainText, String secondaryText) {
            Matcher quote = BLOCKQUOTE.matcher(mainText);
            String intro = plain(quote.find() ? mainText.substring(0, quote.start()) : mainText);
            List<String> steps = new ArrayList<>();
            if (quote.find(0)) {
                for (String step : quote.group(1).split("(?i)<br\\s*/?>")) {
                    String text = STEP_NUMBER.matcher(plain(step)).replaceFirst("");
                    if (!text.isEmpty()) {
                        steps.add(text);
                    }
                }
            }
            Matcher heading = HEADING.matcher(secondaryText);
            String where = heading.find() ? plain(heading.group(1)) : "";
            List<String> places = new ArrayList<>();
            String[] items = secondaryText.split("(?i)<li[^>]*>");
            for (int i = 1; i < items.length; i++) {
                String text = plain(items[i]);
                if (!text.isEmpty()) {
                    places.add(text);
                }
            }
            return new Guide(intro, List.copyOf(steps), where, List.copyOf(places));
        }
    }

    private GpuBugReport() { }

    /** The card's title, "Report a Bug". */
    static String title() {
        return I18N.get("title");
    }

    /**
     * Fills {@code dialog}, a card titled {@link #title()}: the guide, the Report a Bug button, which runs
     * {@code report}, the repositories' buttons and Discord; Close runs {@code close}.
     */
    static void fill(UiKit kit, UiDialog dialog, Guide guide, Runnable report, Runnable close) {
        dialog.card().setName("bug-report-card");
        float gap = DesignTokens.shipped().space().get("md");
        Table body = new Table();
        body.top().left();
        body.defaults().growX().minWidth(0).left();
        body.add(wrapped(kit.typeLabel(guide.intro(), "body", "text.primary"))).row();
        Table steps = new Table();
        steps.defaults().top().left();
        for (int i = 0; i < guide.steps().size(); i++) {
            steps.add(kit.typeLabel(String.valueOf(i + 1), "title", "accent.primary"))
                  .padRight(gap).minWidth(DesignTokens.shipped().size().get("control.sm"));
            steps.add(wrapped(kit.typeLabel(guide.steps().get(i), "body", "text.primary"))).growX().minWidth(0)
                  .padBottom(gap / 2).row();
        }
        body.add(steps).padTop(gap).row();

        UiButton reportButton = kit.button("button-primary", null, I18N.get("package.reportBug"), null);
        reportButton.setName("bug-report-package");
        UiKit.onChange(reportButton, report);
        body.add(reportButton).center().fill(false, false).expandX().minWidth(DesignTokens.shipped().size().get("control.sm") * 6)
              .padTop(gap).row();
        Label packaging = wrapped(kit.typeLabel(I18N.get("package.tooltip"), "small", "text.muted"));
        packaging.setAlignment(Align.center);
        body.add(packaging).padTop(gap / 2).padBottom(gap).row();

        if (!guide.where().isEmpty()) {
            body.add(kit.typeLabel(guide.where(), "headline", "text.primary")).padTop(gap).row();
        }
        for (String place : guide.places()) {
            body.add(wrapped(kit.typeLabel("\u2022 " + place, "body", "text.muted"))).padTop(gap / 4).row();
        }
        Table repositories = new Table();
        repositories.defaults().growX().uniformX().padRight(gap / 2);
        repositories.add(link(kit, "bug-report-mm", I18N.get("mm.text"),
              IssueReportUrl.forIssueForm(IssueReportUrl.MEGAMEK_ISSUES_URL)));
        repositories.add(link(kit, "bug-report-mml", I18N.get("mml.text"),
              IssueReportUrl.forIssueForm(IssueReportUrl.MEGAMEKLAB_ISSUES_URL)));
        repositories.add(link(kit, "bug-report-mhq", I18N.get("mhq.text"),
              IssueReportUrl.forIssueForm(IssueReportUrl.MEKHQ_ISSUES_URL)));
        repositories.add(link(kit, "bug-report-data", I18N.get("mmData.text"), MM_DATA_ISSUES)).padRight(0);
        body.add(repositories).padTop(gap).row();

        dialog.body().add(body).growX().minWidth(0);
        dialog.secondary(I18N.get("discord.text"), () -> GpuAbout.browse(MMConstants.DISCORD_LINK))
              .setName("bug-report-discord");
        dialog.primary(Messages.getString("Close"), close).setName("bug-report-close");
    }

    private static UiButton link(UiKit kit, String name, String text, String url) {
        UiButton button = kit.button("button-secondary", null, text, null);
        button.setName(name);
        UiKit.onChange(button, () -> GpuAbout.browse(url));
        return button;
    }

    private static Label wrapped(Label label) {
        label.setWrap(true);
        label.setAlignment(Align.left);
        return label;
    }

    /** {@code html} as one line of plain text. */
    static String plain(String html) {
        return TAG.matcher(html).replaceAll(" ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
              .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replaceAll("\\s+", " ").trim();
    }

    /**
     * The packaging of a scene: it asks for the archive through {@code files}, the scene's native file dialog, and
     * shows what came of it in the scene's cards, which {@code cards} opens on the GPU thread.
     *
     * @param client the client whose game goes in the archive, read as the player presses the button; null for none
     */
    static PackageBugReportAction packaging(Supplier<Client> client, LobbyFiles files,
          Consumer<Consumer<LobbyNativeDialogs>> cards) {
        return new PackageBugReportAction(null, client, new PackageBugReportAction.Prompts() {
            @Override
            public void chooseArchive(Consumer<File> chosen) {
                files.saveBugReport(chosen);
            }

            @Override
            public void failed(String title, String text) {
                cards.accept(dialogs -> dialogs.error(title, text, Messages.getString("Ok.text"), null, null));
            }

            @Override
            public void result(BugReportBundle.BundleResult result, @Nullable String caution, Runnable issueForm,
                  Runnable copyFile) {
                String text = PackageBugReportAction.resultText(result, caution);
                cards.accept(dialogs -> dialogs.bugReportPackaged(I18N.get("package.result.title"), text,
                      I18N.get("mm.text"), () -> SwingUtilities.invokeLater(issueForm),
                      I18N.get("package.result.copyFile"), () -> SwingUtilities.invokeLater(copyFile),
                      I18N.get("package.result.close")));
            }
        });
    }

    /** GPU thread: opens the card in {@code dialogs}; Report a Bug runs {@code packaging} on the Swing thread. */
    static void open(LobbyNativeDialogs dialogs, PackageBugReportAction packaging) {
        dialogs.bugReport(() -> SwingUtilities.invokeLater(() -> packaging.actionPerformed(null)));
    }
}
