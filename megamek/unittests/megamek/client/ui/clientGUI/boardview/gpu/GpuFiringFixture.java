/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.io.File;
import java.util.List;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.util.KeyBindReceiver;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Player;
import megamek.common.actions.EntityAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * A real FiringDisplay over the board fixture on the local player's firing turn, with the fixture's Atlas AS7-D at
 * (5, 5) facing north selected and two identified enemies: an Archer ARC-2R at {@link #AHEAD} (north-east) and a
 * Hachiwara HCA-6P at {@link #LEFT} (north-west). The shared unit selection state is real; no Swing inspector is
 * constructed, and the client records what the display sends. Prompts go through the routing client (GpuDialogRoutingTest); a
 * yes/no question answers yes and is recorded by the mock. Fire does not end the turn by itself and no searchlight is
 * declared automatically unless a test switches that on (both preferences are restored on close). Build and close it
 * on the test thread; the display runs on the EDT.
 */
final class GpuFiringFixture implements AutoCloseable {
    static final Coords AHEAD = new Coords(7, 3);
    static final Coords LEFT = new Coords(3, 3);
    private static final String UNITS = "testresources/megamek/common/units/";
    final GpuBoardFixture board;
    final ClientGUI gui = GpuDialogFixture.routingClient();
    final Client client = mock(Client.class);
    final MegaMekController controller = mock(MegaMekController.class);
    final Player enemy = new Player(2, "Enemy");
    final Entity attacker;
    final Entity ahead;
    final Entity left;
    final UnitDisplayState unitDisplay;
    final FiringDisplay display;
    private final MockedStatic<MegaMekGUI> keys;
    private final boolean autoEndFiring = GUIPreferences.getInstance().getAutoEndFiring();
    private final boolean autoSearchlight = GUIPreferences.getInstance().getAutoDeclareSearchlight();

    private GpuFiringFixture(GpuBoardFixture board) throws Exception {
        this.board = board;
        attacker = board.entity;
        ahead = unit("Archer ARC-2R.mtf", 42, AHEAD);
        left = unit("Hachiwara HCA-6P.mtf", 43, LEFT);
        CommonMenuBar menu = mock(CommonMenuBar.class);
        when(menu.getComponents()).thenReturn(new Component[0]);
        when(client.getGame()).thenReturn(board.game);
        when(client.getLocalPlayer()).thenReturn(board.player);
        when(client.isMyTurn()).thenReturn(true);
        when(client.getMyTurn()).thenReturn(new GameTurn(board.player.getId()));
        when(gui.getClient()).thenReturn(client);
        when(gui.getMenuBar()).thenReturn(menu);
        when(gui.boardStates()).thenReturn(List.of(board.view));
        when(gui.getBoardState()).thenReturn(board.view);
        when(gui.getBoardState(any(Targetable.class))).thenReturn(board.view);
        when(gui.getBoardState(anyInt())).thenReturn(board.view);
        doReturn(true).when(gui).doYesNoDialog(anyString(), anyString());
        gui.controller = controller;
        // The key dispatcher mock is thread-local: the display registers and uses its keys on the EDT only.
        keys = onSwing(() -> mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS));
        try {
            unitDisplay = new UnitDisplayState(gui);
            display = onSwing(() -> {
                GUIPreferences.getInstance().setAutoEndFiring(false);
                GUIPreferences.getInstance().setAutoDeclareSearchlight(false);
                keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                when(gui.getUnitDisplayState()).thenReturn(unitDisplay);
                when(gui.getDisplayedUnit()).thenAnswer(invocation -> unitDisplay.getCurrentEntity());
                enemy.setTeam(2);
                board.game.addPlayer(enemy.getId(), enemy);
                for (Entity target : List.of(ahead, left)) {
                    target.setOwner(enemy);
                    board.game.addEntity(target, false);
                    target.addBeenSeenBy(board.player);
                }
                attacker.setFacing(0);
                attacker.setSecondaryFacing(0);
                board.game.setPhase(GamePhase.FIRING);
                board.game.setTurnVector(List.of(new GameTurn(board.player.getId())));
                board.game.setTurnIndex(0, board.player.getId());
                FiringDisplay created = new FiringDisplay(gui);
                board.view.addBoardViewListener(created);
                created.selectEntity(attacker.getId());
                return created;
            });
        } catch (Exception | Error failure) {
            onSwing(() -> {
                keys.close();
                restorePreferences();
                return null;
            });
            throw failure;
        }
    }

    static GpuFiringFixture create() throws Exception {
        GpuBoardFixture board = GpuBoardFixture.create();
        try {
            return new GpuFiringFixture(board);
        } catch (Exception | Error failure) {
            board.close();
            throw failure;
        }
    }

    /** A unit of the test resources with the given id, deployed at the hex and facing north; not yet in the game. */
    static Entity unit(String file, int id, Coords hex) throws Exception {
        Entity unit = new MekFileParser(new File(UNITS + file)).getEntity();
        unit.setId(id);
        unit.setPosition(hex);
        unit.setFacing(0);
        unit.setDeployed(true);
        return unit;
    }

    /** The unit's weapon of the given name in the given location. */
    static WeaponMounted weapon(Entity unit, String name, int location) {
        return unit.getWeaponList().stream()
              .filter(weapon -> weapon.getName().equals(name) && (weapon.getLocation() == location))
              .findFirst().orElseThrow();
    }

    /** EDT: selects the weapon in the Unit Display and fires it at the target, as the Fire button does. */
    void fire(WeaponMounted weapon, Targetable target) {
        unitDisplay.selectWeapon(weapon);
        display.target(target);
        display.fire();
    }

    /** EDT: the Backspace key binding (undo the last step), as Swing registered it. */
    void undoLastStep() {
        ArgumentCaptor<MegaMekController.KeyBindAction> undo =
              ArgumentCaptor.forClass(MegaMekController.KeyBindAction.class);
        verify(controller).registerCommandAction(eq(KeyCommandBind.UNDO_LAST_STEP), any(KeyBindReceiver.class),
              undo.capture());
        undo.getValue().execute();
    }

    /** The attacks the display sent with Done, for the unit with the given id (fails unless sent once). */
    List<EntityAction> sent(int unitId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Vector<EntityAction>> attacks = ArgumentCaptor.forClass(Vector.class);
        verify(client).sendAttackData(eq(unitId), attacks.capture());
        return List.copyOf(attacks.getValue());
    }

    private void restorePreferences() {
        GUIPreferences.getInstance().setAutoEndFiring(autoEndFiring);
        GUIPreferences.getInstance().setAutoDeclareSearchlight(autoSearchlight);
    }

    @Override
    public void close() throws Exception {
        try {
            onSwing(() -> {
                board.view.removeBoardViewListener(display);
                display.removeAllListeners();
                keys.close();
                restorePreferences();
                return null;
            });
        } finally {
            board.close();
        }
    }

    static void assertSameActions(List<EntityAction> expected, List<EntityAction> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), actual.get(index), "The same action object at " + index);
        }
    }

    /** Posts the command as the render thread does and returns the orders the source captures after it ran. */
    static GpuFireOrders.Snapshot command(GpuFiringFixture firing, Consumer<GpuFireOrders> command)
          throws Exception {
        command.accept(firing.board.source.fire());
        return onSwing(() -> firing.board.source.fire().capture(firing.display, null));
    }

    /** An identified enemy unit of the test resources at the hex, facing north. */
    static Entity enemy(GpuFiringFixture firing, String file, int id, Coords hex) throws Exception {
        Entity unit = GpuFiringFixture.unit(file, id, hex);
        onSwing(() -> {
            unit.setOwner(firing.enemy);
            firing.board.game.addEntity(unit, false);
            unit.addBeenSeenBy(firing.board.player);
            firing.board.source.refresh();
            return null;
        });
        return unit;
    }

    static int eqNum(GpuFiringFixture firing, String name, int location) {
        return firing.attacker.getEquipmentNum(weapon(firing.attacker, name, location));
    }

    // ------------------------------------------------------------------ helpers

    /** The firing fixture with the board source capturing its FiringDisplay, as the native window's source does. */
    static GpuFiringFixture firing() throws Exception {
        GpuFiringFixture firing = GpuFiringFixture.create();
        firing.board.panel = firing.display;
        onSwing(() -> {
            // The secondary target modifiers below are the default rules'
            firing.board.game.initializeRulesManager(OptionsConstants.RULES_CORE);
            firing.board.source.refresh();
            return null;
        });
        return firing;
    }

    /** The weapon attacks among the actions: "weapon location@target". */
    static List<String> orders(Game game, List<EntityAction> actions) {
        return actions.stream().filter(WeaponAttackAction.class::isInstance).map(WeaponAttackAction.class::cast)
              .map(attack -> {
                  Entity carrier = attack.getEntity(game);
                  Mounted<?> weapon = carrier.getEquipment(attack.getWeaponId());
                  return weapon.getName() + " " + carrier.getLocationAbbr(weapon.getLocation()) + "@"
                        + attack.getTargetId();
              }).toList();
    }

    /** The display's weapon attacks in fire order: "weapon location@target". */
    static List<String> queue(GpuFiringFixture firing) throws Exception {
        return onSwing(() -> GpuFiringFixture.orders(firing.board.game, firing.display.getAttacks()));
    }

    /** The weapon attacks Done sent for the unit (fails unless sent once): "weapon location@target". */
    static List<String> sent(GpuFiringFixture firing, int unitId) {
        return GpuFiringFixture.orders(firing.board.game, firing.sent(unitId));
    }

    static List<String> targets(GpuFireOrders.Snapshot fire) {
        return fire.targets().stream().map(target -> target.letter() + " " + target.key().id()
              + (target.primary() ? " primary" : " secondary +" + target.secondaryModifier())
              + (target.frontArc() ? " front" : "")).toList();
    }

    /** The Atlas's weapons marked as fired this round. */
    static String used(GpuFiringFixture firing) throws Exception {
        return onSwing(() -> "used: " + firing.attacker.getWeaponList().stream().filter(Mounted::isUsedThisRound)
              .map(weapon -> weapon.getName() + " " + firing.attacker.getLocationAbbr(weapon.getLocation()))
              .sorted().collect(Collectors.joining(", ")));
    }

    static final Coords EAST = new Coords(8, 5);

    static final Coords NORTH = new Coords(5, 2);
}
