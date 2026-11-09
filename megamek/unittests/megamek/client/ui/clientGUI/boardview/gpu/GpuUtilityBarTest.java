/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.boardview.NameplateMode;
import megamek.client.ui.util.KeyCommandBind;
import org.junit.jupiter.api.Test;

/** The Menu utility's tooltip names the keys that open the Menu (no GL). */
class GpuUtilityBarTest {

    @Test
    void theMenuTipNamesTheBoundKeysAndLeavesOutAnUnboundOne() {
        assertEquals(Messages.getString("GpuBoard.hud.menu.tip", "Esc / F10"), GpuUtilityBar.menuTip(preferences(true, true)));
        assertEquals(Messages.getString("GpuBoard.hud.menu.tip", "Esc"), GpuUtilityBar.menuTip(preferences(true, false)));
        assertEquals(Messages.getString("GpuBoard.hud.menu.title"), GpuUtilityBar.menuTip(preferences(false, false)));
    }

    /** Preferences with the CANCEL and GAME_MENU binds bound or not; an unbound one has MegaMek's text for code 0. */
    private static GpuBoardSource.UiPreferences preferences(boolean cancel, boolean gameMenu) {
        List<GpuBoardSource.Bind> binds = Stream.of(KeyCommandBind.values()).map(bind -> {
            boolean bound = switch (bind) {
                case CANCEL -> cancel;
                case GAME_MENU -> gameMenu;
                default -> true;
            };
            String text = bind == KeyCommandBind.CANCEL ? "Esc" : bind == KeyCommandBind.GAME_MENU ? "F10" : "";
            return bound ? new GpuBoardSource.Bind(bind, bind.keyDefault, bind.modifiersDefault, text)
                  : new GpuBoardSource.Bind(bind, 0, 0, "Unknown keyCode: 0x0");
        }).toList();
        GpuBoardSource.UiPreferences captured = GpuBoardSource.UiPreferences.capture();
        return new GpuBoardSource.UiPreferences(1, "", "", true, true, false, false, binds, BoardGraphics.ENHANCED,
              GpuBoardSource.UiPreferences.Overlays.DEFAULTS, captured.movementColors(), NameplateMode.HOLD,
              captured.display());
    }
}
