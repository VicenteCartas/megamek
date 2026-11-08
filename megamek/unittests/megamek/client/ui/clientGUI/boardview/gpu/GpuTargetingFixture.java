/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Vector;
import javax.swing.JFrame;

import megamek.client.Client;
import megamek.client.event.BoardViewEvent;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.OffBoardTargetOverlay;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.panels.phaseDisplay.TargetingPhaseDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.actions.EntityAction;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * A real TargetingPhaseDisplay on the local player's TARGETING turn over the board fixture's game, set up as ClientGUI
 * sets a phase display up and started by the turn's event. The fixture's Atlas at (5, 5), facing north, carries a Long
 * Tom in its left arm with a ton of its ammunition, is selected, and has the Long Tom selected in the shared unit selection state, without constructing a Swing inspector. The client's board state carries the off-board target overlay, as
 * ClientGUI adds one to every board state, and {@link #source} captures that state with the display as its phase
 * panel. {@link #battery} puts enemy units off a board edge. The client records what the display sends; prompts go
 * through the routing client (GpuDialogRoutingTest), whose frame leads back to it. Fire does not end the turn by itself
 * (the preference is restored on close). Build and close it on the test thread; the display runs on the EDT.
 */
final class GpuTargetingFixture implements AutoCloseable {
    private static final String UNITS = "testresources/megamek/common/units/";
    final GpuBoardFixture board;
    final ClientGUI gui = GpuDialogFixture.routingClient();
    final Client client = mock(Client.class);
    final MegaMekController controller = mock(MegaMekController.class);
    final Player enemy = new Player(2, "Enemy");
    final Entity attacker;
    final BoardClientState view;
    final UnitDisplayState unitDisplay;
    final WeaponMounted longTom;
    final TargetingPhaseDisplay display;
    final OffBoardTargetOverlay overlay;
    final GpuBoardSource source;
    private final JFrame frame = new JFrame();
    private final boolean autoEndFiring = GUIPreferences.getInstance().getAutoEndFiring();

    private GpuTargetingFixture(GpuBoardFixture board) throws Exception {
        this.board = board;
        attacker = board.entity;
        // The client's stubs come before anything on the EDT calls the client; the ones that need the board state
        // follow on the EDT, where the state and the display call it (a stub made on another thread meanwhile could
        // take the answer of a concurrent call).
        CommonMenuBar menu = mock(CommonMenuBar.class);
        when(menu.getComponents()).thenReturn(new Component[0]);
        when(client.getGame()).thenReturn(board.game);
        when(client.getLocalPlayer()).thenReturn(board.player);
        when(client.isMyTurn()).thenReturn(true);
        when(client.getMyTurn()).thenReturn(new GameTurn(board.player.getId()));
        when(client.getFirstEntityNum()).thenReturn(attacker.getId());
        when(gui.getClient()).thenReturn(client);
        when(gui.getMenuBar()).thenReturn(menu);
        // Dialogs that know only their parent frame find their client through it (ClientGUI.forFrame).
        frame.getRootPane().putClientProperty(ClientGUI.class, gui);
        when(gui.getFrame()).thenReturn(frame);
        gui.controller = controller;
        view = onSwing(() -> {
            BoardClientState state = new BoardClientState(board.game, null, gui, 0, null);
            state.setLocalPlayer(board.player.getId());
            when(gui.boardStates()).thenReturn(List.of(state));
            when(gui.getBoardState()).thenReturn(state);
            when(gui.getCurrentBoardState()).thenReturn(Optional.of(state));
            when(gui.getBoardState(any(Targetable.class))).thenReturn(state);
            when(gui.getBoardState(anyInt())).thenReturn(state);
            return state;
        });
        unitDisplay = onSwing(() -> {
            UnitDisplayState panel = new UnitDisplayState(gui);
            when(gui.getUnitDisplayState()).thenReturn(panel);
            when(gui.getDisplayedUnit()).thenAnswer(invocation -> panel.getCurrentEntity());
            when(gui.getDisplayedWeapon()).thenAnswer(invocation ->
                  Optional.ofNullable(panel.getSelectedWeapon()));
            return panel;
        });
        longTom = onSwing(() -> {
            WeaponMounted weapon = (WeaponMounted) attacker.addEquipment(EquipmentType.get("ISLongTom"),
                  Mek.LOC_LEFT_ARM);
            attacker.addEquipment(EquipmentType.get("ISLongTomAmmo"), Mek.LOC_LEFT_TORSO);
            attacker.loadWeapon(weapon);
            enemy.setTeam(2);
            board.game.addPlayer(enemy.getId(), enemy);
            attacker.setFacing(0);
            attacker.setSecondaryFacing(0);
            board.game.setPhase(GamePhase.TARGETING);
            board.game.setTurnVector(List.of(new GameTurn(board.player.getId())));
            board.game.setTurnIndex(0, board.player.getId());
            return weapon;
        });
        display = onSwing(() -> {
            GUIPreferences.getInstance().setAutoEndFiring(false);
            TargetingPhaseDisplay created;
            // The display's constructor registers keys with the static dispatcher. A static mock lives on the thread
            // that made it, and AWT replaces an event thread that went idle, so it lives only for the construction.
            try (MockedStatic<MegaMekGUI> keys = mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS)) {
                keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                created = new TargetingPhaseDisplay(gui, false);
            }
            when(gui.getCurrentPanel()).thenReturn(created);
            view.addBoardViewListener(created);
            created.initializeListeners();
            // The turn's event starts the display's turn; the selection does not depend on the auto-select preference.
            board.game.setTurnIndex(0, board.player.getId());
            created.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, attacker.getId()));
            unitDisplay.selectWeapon(longTom);
            view.addOverlay(new OffBoardTargetOverlay(gui));
            return created;
        });
        overlay = view.getOverlay(OffBoardTargetOverlay.class);
        source = onSwing(() -> new GpuBoardSource(view, () -> display));
    }

    static GpuTargetingFixture create() throws Exception {
        GpuBoardFixture board = GpuBoardFixture.create();
        boolean autoEndFiring = GUIPreferences.getInstance().getAutoEndFiring();
        try {
            return new GpuTargetingFixture(board);
        } catch (Exception | Error failure) {
            GUIPreferences.getInstance().setAutoEndFiring(autoEndFiring);
            board.close();
            throw failure;
        }
    }

    /**
     * EDT: an enemy unit of the test resources with the given id, deployed 20 hexes off the board in {@code direction}
     * and observed there by the local player's team (its rounds were seen to land), so the overlay offers it.
     */
    Entity battery(String file, int id, OffBoardDirection direction) throws Exception {
        Entity unit = new MekFileParser(new File(UNITS + file)).getEntity();
        unit.setId(id);
        unit.setOwner(enemy);
        unit.setOffBoard(20, direction);
        board.game.addEntity(unit, false);
        // Where the server places an off-board unit: 20 hexes beyond the middle of that edge.
        unit.deployOffBoard(board.game.getRoundCount());
        unit.setDeployed(true);
        unit.addOffBoardObserver(board.player.getTeam());
        return unit;
    }

    /** The attacks the display sent with Done, for the unit with the given id (fails unless sent once). */
    List<EntityAction> sent(int unitId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Vector<EntityAction>> attacks = ArgumentCaptor.forClass(Vector.class);
        verify(client).sendAttackData(eq(unitId), attacks.capture());
        return List.copyOf(attacks.getValue());
    }

    @Override
    public void close() throws Exception {
        try {
            onSwing(() -> {
                source.close();
                view.removeBoardViewListener(display);
                display.removeAllListeners();
                GUIPreferences.getInstance().setAutoEndFiring(autoEndFiring);
                view.close();
                frame.dispose();
                return null;
            });
        } finally {
            board.close();
        }
    }
}
