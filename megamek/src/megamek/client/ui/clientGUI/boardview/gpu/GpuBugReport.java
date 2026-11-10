/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.Client;
import megamek.client.lobby.HelpDocument;
import megamek.client.ui.BugReportMessages;
import megamek.client.ui.BugReportPackager;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.common.util.BugReportBundle;
import megamek.common.util.IssueReportUrl;

/**
 * The libGDX bug report cards, which replace the Swing {@code BugReportDialog} and the result box of its package over
 * the battle, the lobby and the start screen: the instructions, Discord, Report a Bug, which saves the game and
 * gathers the logs into a zip ({@link BugReportPackager}), and the issue forms of the suite's repositories. The result
 * card then lists what the zip holds and opens the MegaMek issue form with the zip on the clipboard.
 */
public final class GpuBugReport {
    private static final BugReportMessages I18N = new BugReportMessages();
    static final String MM_DATA_ISSUES = "https://github.com/MegaMek/mm-data/issues/new";

    private GpuBugReport() { }

    /**
     * Where the card's work goes. The card asks it from the GPU thread; each method says where it runs.
     */
    interface Host {
        /** EDT: asks for the archive in the window's file dialog; {@code chosen} gets null on a cancel, on the EDT. */
        void choose(SystemFileDialog.Request request, Consumer<File> chosen);

        /** EDT: the client whose game to package, or null to package the logs alone. */
        @Nullable Client client();

        /** Any thread: runs {@code work} on the GPU thread, unless the window has gone. */
        void gpu(Runnable work);
    }

    /**
     * EDT: shows the bug report natively in the scene that shows: the battle, the lobby or the start screen. False,
     * showing nothing, when none shows, so the caller shows the Swing dialog.
     *
     * @param client the client whose game Report a Bug packages, read when it is pressed; may return null
     */
    public static boolean show(Supplier<Client> client) {
        return GpuBoardWindow.showBugReport(client) || GpuLobbyWindow.showBugReport(client)
              || GpuStartWindow.showBugReport(client);
    }

    /** The card's title, "Report a Bug". */
    static String title() {
        return I18N.get("title");
    }

    /** The result card's title. */
    static String resultTitle() {
        return I18N.get("package.result.title");
    }

    /**
     * GPU thread: fills {@code dialog}, a card titled {@link #title()}, with the bug report. Report a Bug asks
     * {@code host} for the archive and hands what packaging came to to {@code packaged} on the GPU thread.
     */
    static void fill(UiKit kit, UiDialog dialog, Host host, Consumer<BugReportPackager.Outcome> packaged,
          Runnable close) {
        dialog.card().setName("bug-report-card");
        Table body = column();
        text(kit, body, I18N.get("mainText"));
        text(kit, body, I18N.get("secondaryText"));

        UiButton report = kit.button("button-special", null, I18N.get("package.text"), null);
        report.setName("bug-report-package");
        kit.tip(report, true).getActor().setText(I18N.get("package.tooltip"));
        UiKit.onChange(report, () -> {
            if (report.isDisabled()) {
                return;
            }
            report.setDisabled(true);
            report.setText(I18N.get("package.working"));
            Runnable ready = () -> {
                report.setDisabled(false);
                report.setText(I18N.get("package.text"));
            };
            SwingUtilities.invokeLater(() -> packageReport(host, outcome -> {
                ready.run();
                if (outcome != null) {
                    packaged.accept(outcome);
                }
            }));
        });
        body.add(report).center().fill(false, false).expandX().padTop(space("lg")).row();

        Table links = new Table();
        links.defaults().padRight(space("sm")).padTop(space("sm"));
        links.add(link(kit, "bug-report-discord", I18N.get("discord.text"), MMConstants.DISCORD_LINK,
              MMConstants.DISCORD_LINK));
        links.row();
        Table repositories = new Table();
        repositories.defaults().padRight(space("sm"));
        repositories.add(issueLink(kit, "bug-report-mm", "mm.text", IssueReportUrl.MEGAMEK_ISSUES_URL));
        repositories.add(issueLink(kit, "bug-report-mml", "mml.text", IssueReportUrl.MEGAMEKLAB_ISSUES_URL));
        repositories.add(issueLink(kit, "bug-report-mhq", "mhq.text", IssueReportUrl.MEKHQ_ISSUES_URL));
        // mm-data has no suite bug report template, so its link is left alone
        repositories.add(link(kit, "bug-report-data", I18N.get("mmData.text"), MM_DATA_ISSUES, MM_DATA_ISSUES));
        links.add(repositories);
        body.add(links).center().fill(false, false).expandX().padTop(space("md")).row();

        dialog.body().add(body).growX().minWidth(0);
        dialog.primary(I18N.get("package.result.close"), close).setName("bug-report-close");
    }

    /**
     * GPU thread: fills {@code dialog}, a card titled {@link #resultTitle()}, with what packaging came to. MegaMek
     * opens the issue form with the archive on the clipboard and Copy File copies it; each closes the card, as does
     * Close.
     */
    static void result(UiKit kit, UiDialog dialog, BugReportPackager.Outcome outcome, Runnable close) {
        dialog.card().setName("bug-report-result");
        Table body = column();
        BugReportBundle.BundleResult result = outcome.result();
        if (outcome.caution() != null) {
            body.add(wrapped(kit, outcome.caution(), "body", "status.warning")).padBottom(space("md")).row();
        }
        if (result == null) {
            body.add(wrapped(kit, I18N.get("package.failed"), "body", "status.danger")).row();
            dialog.body().add(body).growX().minWidth(0);
            dialog.primary(I18N.get("package.result.close"), close).setName("bug-report-result-close");
            return;
        }
        File archive = result.archiveFile();
        body.add(wrapped(kit, I18N.get("package.result.included", archive.getPath()), "body", "text.primary")).row();
        bullets(kit, body, result.includedEntries());
        if (result.hasSkippedEntries()) {
            body.add(wrapped(kit, I18N.get("package.result.skipped"), "body", "text.primary")).padTop(space("md"))
                  .row();
            bullets(kit, body, result.skippedEntries());
        }
        body.add(wrapped(kit, I18N.get("package.result.next"), "body", "text.secondary")).padTop(space("md")).row();
        dialog.body().add(body).growX().minWidth(0);
        dialog.primary(I18N.get("mm.text"), () -> {
            close.run();
            SwingUtilities.invokeLater(() -> BugReportPackager.openIssueForm(archive));
        }).setName("bug-report-result-issue");
        dialog.secondary(I18N.get("package.result.copyFile"), () -> {
            close.run();
            SwingUtilities.invokeLater(() -> BugReportPackager.copyToClipboard(archive));
        }).setName("bug-report-result-copy");
        dialog.secondary(I18N.get("package.result.close"), close).setName("bug-report-result-close");
    }

    /**
     * EDT: asks {@code host} where the archive goes and packages it; {@code done} gets the outcome on the GPU thread,
     * or null when the player cancelled.
     */
    static void packageReport(Host host, Consumer<BugReportPackager.Outcome> done) {
        File directory = BugReportPackager.archiveDirectory();
        SystemFileDialog.Request request = new SystemFileDialog.Request(directory, I18N.get("package.chooser.title"),
              new FileNameExtensionFilter(I18N.get("package.chooser.filter"), "zip"), true,
              BugReportPackager.defaultArchiveName());
        host.choose(request, file -> {
            if (file == null) {
                host.gpu(() -> done.accept(null));
                return;
            }
            BugReportPackager.build(host.client(), BugReportPackager.archiveFile(file),
                  outcome -> host.gpu(() -> done.accept(outcome)));
        });
    }

    /**
     * Lays out the HTML of a bug report text as the card's paragraphs, headings and bullets, as the Swing dialog's
     * label rendered it.
     */
    private static void text(UiKit kit, Table body, String html) {
        for (HelpDocument.Chapter chapter : HelpDocument.guide(html).chapters()) {
            if (!chapter.title().isBlank()) {
                body.add(wrapped(kit, chapter.title(), "title", "text.primary")).padTop(space("md")).row();
            }
            for (HelpDocument.Block block : chapter.blocks()) {
                switch (block.kind()) {
                    case HEADING, SUBHEADING -> body.add(wrapped(kit, block.text(), "title", "text.primary"))
                          .padTop(space("md")).row();
                    case BULLET -> body.add(wrapped(kit, "\u2022  " + block.text(), "body", "text.primary"))
                          .padLeft(space("md") * (block.depth() + 1)).padTop(space("xs")).row();
                    default -> body.add(wrapped(kit, block.text(), "body", "text.primary")).padTop(space("sm"))
                          .row();
                }
            }
        }
    }

    private static void bullets(UiKit kit, Table body, List<String> entries) {
        for (String entry : entries) {
            body.add(wrapped(kit, "\u2022  " + entry, "small", "text.secondary")).padLeft(space("md"))
                  .padTop(space("xs")).row();
        }
    }

    /** A repository's issue button, which opens its form with the environment fields already filled in. */
    private static UiButton issueLink(UiKit kit, String name, String labelKey, String issuesUrl) {
        return link(kit, name, I18N.get(labelKey), IssueReportUrl.forIssueForm(issuesUrl), issuesUrl);
    }

    /**
     * @param shown the address the tooltip shows; a prefilled issue form's long query is of no use to the reader, so
     *              the plain repository link is shown instead
     */
    private static UiButton link(UiKit kit, String name, String text, String address, String shown) {
        UiButton button = kit.button("button-secondary", null, text, null);
        button.setName(name);
        kit.tip(button, true).getActor().setText(shown);
        UiKit.onChange(button, () -> GpuAbout.browse(address));
        return button;
    }

    private static Table column() {
        Table body = new Table();
        body.top().left();
        body.defaults().growX().minWidth(0).left();
        return body;
    }

    private static Label wrapped(UiKit kit, String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }
}
