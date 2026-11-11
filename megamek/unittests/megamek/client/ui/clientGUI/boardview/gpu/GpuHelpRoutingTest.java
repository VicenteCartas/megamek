/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

import megamek.MMConstants;
import megamek.client.lobby.HelpDocument;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import org.junit.jupiter.api.Test;

/** The help the start screen, the lobby and the battle show natively, in the shared document card. */
class GpuHelpRoutingTest {

    @Test
    void theHelpContentsAndTheSkinningHowToReadAsGuidesWithChapters() {
        for (File file : List.of(new File(MMConstants.MEGAMEK_README_FILE_PATH),
              new File(Messages.getString("ClientGUI.skinningHelpPath")))) {
            HelpDocument document = GpuLobbyTools.readDocument(file, HelpDocument::guide);
            assertFalse(document.chapters().isEmpty(), file + " has chapters");
            assertTrue(document.chapters().stream().anyMatch(chapter -> !chapter.blocks().isEmpty()),
                  file + " has text");
        }
    }

    @Test
    void aMissingGuideReadsEmpty() {
        assertEquals(List.of(), GpuLobbyTools.readDocument(new File("no/such/help.html"), HelpDocument::guide)
              .chapters());
    }

    @Test
    void withoutItsSceneEachHelpEntryShowsNothingSoTheSwingHelpShows() throws Exception {
        AtomicBoolean shown = new AtomicBoolean(true);
        SwingUtilities.invokeAndWait(() -> shown.set(GpuStartWindow.isOpen()
              || GpuStartWindow.showHelp("Help", new File(MMConstants.MEGAMEK_README_FILE_PATH))
              || GpuLobbyWindow.showHelp(mock(ClientGUI.class), "Help",
              new File(MMConstants.MEGAMEK_README_FILE_PATH))));
        assertFalse(shown.get());
    }
}
