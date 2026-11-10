/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.client.ui;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.ui.util.UIUtil;
import megamek.common.util.BugReportBundle;
import megamek.logging.MMLogger;

/**
 * Asks where to save the bug report archive, has {@link BugReportPackager} build it, and tells the player what it
 * holds, in Swing; the libGDX screens show their own card instead.
 *
 * @see BugReportPackager
 */
public class PackageBugReportAction extends AbstractAction {
    private static final MMLogger LOGGER = MMLogger.create(PackageBugReportAction.class);
    private static final BugReportMessages I18N = new BugReportMessages();

    /** Width of the result message before the user's GUI scale is applied, matching {@code BugReportDialog}. */
    private static final int UNSCALED_MESSAGE_WIDTH = 420;

    private final Component parent;
    private final Supplier<Client> clientSupplier;

    /**
     * Creates the action.
     *
     * @param parent         the component to parent the file chooser and result dialogs to, or {@code null} to centre
     *                       them on screen
     * @param clientSupplier supplies the client whose game should be saved, resolved when the action is invoked
     *                       rather than when it is created, because the menu bar is built before any game exists. May
     *                       be {@code null}, and may return {@code null}; both mean "no game running".
     */
    public PackageBugReportAction(@Nullable Component parent, @Nullable Supplier<Client> clientSupplier) {
        super(I18N.get("package.text"));
        this.parent = parent;
        this.clientSupplier = clientSupplier;
        putValue(AbstractAction.SHORT_DESCRIPTION, I18N.get("package.tooltip"));
    }

    /**
     * Copies this action, matching the behaviour of the other actions in the suite.
     *
     * <p>Both fields are immutable references that the copy is meant to share - the same parent window and the same
     * client supplier - so the shallow copy {@link Object#clone()} makes is exactly what is wanted here.</p>
     *
     * @return the copy, or {@code null} if it could not be made
     */
    @Override
    public @Nullable PackageBugReportAction clone() {
        try {
            return (PackageBugReportAction) super.clone();
        } catch (CloneNotSupportedException cloneNotSupportedException) {
            LOGGER.error("Failed to clone PackageBugReportAction. State of the object: {}", this,
                  cloneNotSupportedException);
            return null;
        }
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        File archiveFile = chooseArchiveFile();
        if (archiveFile == null) {
            LOGGER.debug("[BugReport] Player cancelled the archive location chooser");
            return;
        }
        Client client = (clientSupplier == null) ? null : clientSupplier.get();
        BugReportPackager.build(client, archiveFile, this::showOutcome);
    }

    /**
     * Asks the player where the archive should go.
     *
     * @return the chosen file, with a {@code .zip} extension guaranteed, or {@code null} if the player cancelled
     */
    private @Nullable File chooseArchiveFile() {
        File saveGameDirectory = BugReportPackager.archiveDirectory();
        JFileChooser fileChooser = new JFileChooser(saveGameDirectory);
        fileChooser.setDialogTitle(I18N.get("package.chooser.title"));
        fileChooser.setFileFilter(new FileNameExtensionFilter(I18N.get("package.chooser.filter"), "zip"));
        fileChooser.setSelectedFile(new File(saveGameDirectory, BugReportPackager.defaultArchiveName()));

        if (fileChooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        File chosenFile = fileChooser.getSelectedFile();
        return (chosenFile == null) ? null : BugReportPackager.archiveFile(chosenFile);
    }

    private void showOutcome(BugReportPackager.Outcome outcome) {
        if (outcome.failed()) {
            JOptionPane.showMessageDialog(parent, I18N.get("package.failed"), I18N.get("package.result.title"),
                  JOptionPane.ERROR_MESSAGE);
        } else {
            showResult(outcome.result(), outcome.caution());
        }
    }

    /**
     * Tells the player what was collected and offers to open the folder or put the archive on the clipboard.
     *
     * @param result        what the bundle wrote
     * @param cautionNotice a note about anything that did not go to plan, or {@code null}
     */
    private void showResult(BugReportBundle.BundleResult result, @Nullable String cautionNotice) {
        int messageWidth = UIUtil.scaleForGUI(UNSCALED_MESSAGE_WIDTH);
        StringBuilder message = new StringBuilder("<html><body width=%d>".formatted(messageWidth));
        if (cautionNotice != null) {
            message.append("<p><b>").append(cautionNotice).append("</b></p>");
        }
        message.append("<p>").append(I18N.get("package.result.included", result.archiveFile().getPath()))
              .append("</p><ul>");
        for (String entryName : result.includedEntries()) {
            message.append("<li>").append(entryName).append("</li>");
        }
        message.append("</ul>");
        if (result.hasSkippedEntries()) {
            message.append("<p>").append(I18N.get("package.result.skipped")).append("</p><ul>");
            for (String entryName : result.skippedEntries()) {
                message.append("<li>").append(entryName).append("</li>");
            }
            message.append("</ul>");
        }
        message.append("<p>").append(I18N.get("package.result.next")).append("</p>");
        message.append("</body></html>");

        Object[] options = { I18N.get("mm.text"), I18N.get("package.result.copyFile"),
                             I18N.get("package.result.close") };
        int choice = JOptionPane.showOptionDialog(parent, message.toString(), I18N.get("package.result.title"),
              JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);

        if (choice == 0) {
            BugReportPackager.openIssueForm(result.archiveFile());
        } else if (choice == 1) {
            BugReportPackager.copyToClipboard(result.archiveFile());
        }
    }
}
