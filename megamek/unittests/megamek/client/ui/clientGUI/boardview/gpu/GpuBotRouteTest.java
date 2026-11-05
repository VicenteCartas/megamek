/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.io.File;
import java.util.List;

import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.spriteHandler.BotRouteSpriteHandler;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import megamek.common.orders.UnitOrders;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** An allied bot's route on the native board: its waypoint is a flag marker and its trail lies on the hexes. */
class GpuBotRouteTest {
    @Test
    void anAlliedBotsRouteShowsItsFlagAndTrailOnTheNativeBoard() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Player princess = new Player(3, "Princess");
            princess.setTeam(1);
            princess.setBot(true);
            fixture.game.addPlayer(3, princess);
            Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
            atlas.setId(2);
            atlas.setOwner(princess);
            atlas.setPosition(new Coords(9, 9));
            atlas.setDeployed(true);
            Coords waypoint = new Coords(9, 4);
            atlas.setUnitOrders(UnitOrders.NONE.withRoute(List.of(waypoint)));
            fixture.game.addEntity(atlas, false);
            AbstractClientGUI gui = mock(AbstractClientGUI.class);
            when(gui.getBoardState(anyInt())).thenReturn(fixture.view);
            when(gui.boardStates()).thenReturn(List.of(fixture.view));
            BotRouteSpriteHandler routes = new BotRouteSpriteHandler(gui, fixture.game);

            BoardScene before = onSwing(() -> {
                fixture.source.refresh();
                return fixture.source.takeFrame().scene();
            });
            BoardScene shown = onSwing(() -> {
                routes.initialize();
                fixture.source.refresh();
                return fixture.source.takeFrame().scene();
            });
            List<BoardMarker> flags = shown.markers().stream()
                  .filter(marker -> marker.kind() == BoardMarker.Kind.OBJECTIVE).toList();
            assertEquals(List.of(waypoint), flags.stream().map(BoardMarker::coords).toList(), "one flag, at the waypoint");
            assertEquals(atlas.getShortName() + " 1", flags.getFirst().label(), "who follows the route, and its step");
            // The route's first colour (BotRouteSpriteHandler): one dot on each hex between the Atlas and the waypoint.
            int routeArgb = new Color(240, 140, 30).getRGB();
            assertTrue(before.tactical().fills().stream().noneMatch(fill -> fill.argb() == routeArgb));
            assertEquals(4, shown.tactical().fills().stream().filter(fill -> fill.argb() == routeArgb)
                  .map(BoardTactical.Fill::planeAnchor).distinct().count(), "the trail's dots lie on the hexes on the way");

            BoardScene gone = onSwing(() -> {
                routes.dispose();
                fixture.source.refresh();
                return fixture.source.takeFrame().scene();
            });
            assertEquals(before.markers(), gone.markers());
            assertEquals(before.tactical(), gone.tactical());
        }
    }
}
