/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.client.lobby.HelpDocument;
import megamek.client.ui.Messages;
import org.junit.jupiter.api.Test;

/** The map setup's help, which the lobby shows in its native document dialog, reads as chapters of text. */
class MapSetupHelpTest {

    @Test
    void theHelpReadsAsChaptersOfText() throws IOException {
        Path help = Path.of(Messages.getString("Lobby.help.mapSetupPath"));
        HelpDocument document = HelpDocument.guide(Files.readString(help));

        List<String> titles = document.titles();
        assertTrue(titles.contains("Stacks and Surprise Boards"), titles::toString);
        assertTrue(titles.contains("Finding Boards"), titles::toString);
        assertTrue(document.chapters().stream().mapToLong(chapter -> chapter.blocks().size()).sum() > 10,
              "the help's paragraphs and list items read as blocks");
    }
}
