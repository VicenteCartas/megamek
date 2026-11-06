/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.client.lobby.RequisitionReadout;
import megamek.client.ui.Messages;
import megamek.client.ui.entityreadout.EntityReadout;
import org.junit.jupiter.api.Test;

/** The map setup's help, which the lobby shows in its native help dialog, reads as headings and text. */
class MapSetupHelpTest {

    @Test
    void theHelpReadsAsHeadingsAndText() throws IOException {
        Path help = Path.of(Messages.getString("Lobby.help.mapSetupPath"));
        List<EntityReadout.Row> rows = RequisitionReadout.fromHtml(
              GpuLobbyTools.unwrapLayoutTables(Files.readString(help)));

        List<String> titles = rows.stream()
              .filter(row -> row.kind() == EntityReadout.Row.Kind.TITLE)
              .map(row -> String.join(" ", row.cells()))
              .toList();
        assertTrue(titles.contains("Stacks and Surprise Boards"), titles::toString);
        assertTrue(titles.contains("Finding Boards"), titles::toString);
        assertTrue(rows.stream().filter(row -> row.kind() == EntityReadout.Row.Kind.TEXT).count() > 10,
              "the help's paragraphs and list items read as text");
    }
}
