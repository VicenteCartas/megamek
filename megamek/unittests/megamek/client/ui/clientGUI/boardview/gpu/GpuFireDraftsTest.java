/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.awaitDialog;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.EAST;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.NORTH;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.assertSameActions;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.command;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.enemy;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.eqNum;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.firing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.orders;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.queue;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.sent;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.targets;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.used;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.ActionEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay.FiringCommand;
import megamek.common.Player;
import megamek.common.actions.EntityAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.AimingMode;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Drafts and Resolve phase (stage E3c) over a real FiringDisplay (GpuFiringFixture) with a second own unit, the
 * Sagittaire SGT-14D at (6, 6), and scripted turns instead of a server: the client follows the game's turn as the real
 * one does, and the server's answer to a declaration is scripted as the unit done and the next turn. A declaration is
 * what the display sends with Done (the recording client's sendAttackData).
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuFireDraftsTest {
    private static final int LOCAL = 0;
    private static final int ENEMY = 2;
    private static final int ATLAS = 1;
    private static final int SAGITTAIRE = 46;

    /**
     * H33: switching units keeps the actor's orders as its draft, and switching back gives the display the same action
     * objects in their order (the twist, the ATM with its extended-range bin, the aimed AC/20), with the same rolls,
     * heat and letters (the Crab, B, made primary). The display's own Next unit (its button and key binding) keeps and
     * restores drafts as the native selection does.
     */
    @Test
    void switchingUnitsKeepsEachUnitsDraft() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            Entity crab = enemy(firing, "Crab CRB-20.mtf", 44, NORTH);
            Entity sagittaire = own(firing);
            scriptTurns(firing, LOCAL);
            int[] atm = addAtm(firing, Mek.LOC_LEFT_ARM);
            int cannon = eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            command(firing, fire -> fire.twist(1));
            command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            command(firing, fire -> fire.assign(atm[0], TargetKey.unit(crab.getId())));
            command(firing, fire -> fire.setAmmo(atm[0], bin(firing, atm[1])));
            command(firing, fire -> fire.setPrimary(TargetKey.unit(crab.getId())));
            onSwing(() -> {
                // An aimed shot at the centre torso, as the aimed shot handler declares it with a targeting computer
                WeaponAttackAction attack = (WeaponAttackAction) firing.display.getAttacks().get(2);
                attack.setAimedLocation(Mek.LOC_CENTER_TORSO);
                attack.setAimingMode(AimingMode.TARGETING_COMPUTER);
                return null;
            });
            GpuFireOrders.Snapshot atlas = captured(firing);
            List<EntityAction> atlasQueue = onSwing(firing.display::getAttacks);
            assertEquals(List.of("TorsoTwistAction", "ATM 6 LA@44 ammo " + atm[1] + " aim -1 NONE",
                  "AC/20 RT@42 ammo 16 aim 1 TARGETING_COMPUTER"), actions(firing, atlasQueue));
            assertEquals(List.of("A 42 secondary +1 front", "B 44 primary front"), targets(atlas));
            assertEquals(List.of(1, 2, 2), List.of(atlas.twist(), atlas.attacks().size(), atlas.pendingUnits()));

            // The display's own Next unit selects the Sagittaire
            onSwing(() -> {
                firing.display.actionPerformed(new ActionEvent(firing.display, ActionEvent.ACTION_PERFORMED,
                      FiringCommand.FIRE_NEXT.getCmd()));
                return null;
            });
            GpuFireOrders.Snapshot switched = settled(firing);
            assertEquals(List.of(SAGITTAIRE, 0, Map.of(ATLAS, 2)), List.of(switched.actorId(),
                  switched.attacks().size(), switched.drafted()));
            assertEquals(List.of("used: ", 0), List.of(used(firing), firing.attacker.getSecondaryFacing()),
                  "The display released the Atlas's orders");
            int large = equipment(sagittaire, "ER Large Laser", Mek.LOC_LEFT_TORSO);
            int reengineered = equipment(sagittaire, "Large Re-engineered Laser", Mek.LOC_LEFT_ARM);
            command(firing, fire -> fire.assign(large, TargetKey.unit(firing.ahead.getId())));
            command(firing, fire -> fire.assign(reengineered, TargetKey.unit(crab.getId())));
            GpuFireOrders.Snapshot sagittaireOrders = command(firing,
                  fire -> fire.setPrimary(TargetKey.unit(crab.getId())));
            List<EntityAction> sagittaireQueue = onSwing(firing.display::getAttacks);

            GpuFireOrders.Snapshot back = command(firing, fire -> fire.selectUnit(ATLAS));
            assertSameActions(atlasQueue, onSwing(firing.display::getAttacks));
            assertEquals(List.of(atlas.attacks(), atlas.targets(), atlas.twist(), atlas.heat()),
                  List.of(back.attacks(), back.targets(), back.twist(), back.heat()));
            assertEquals(List.of(1, Map.of(SAGITTAIRE, 2)), List.of(firing.attacker.getSecondaryFacing(),
                  back.drafted()));

            onSwing(() -> {
                firing.display.actionPerformed(new ActionEvent(firing.display, ActionEvent.ACTION_PERFORMED,
                      FiringCommand.FIRE_NEXT.getCmd()));
                return null;
            });
            GpuFireOrders.Snapshot next = settled(firing);
            assertSameActions(sagittaireQueue, onSwing(firing.display::getAttacks));
            assertEquals(List.of(sagittaireOrders.attacks(), sagittaireOrders.targets(), Map.of(ATLAS, 2)),
                  List.of(next.attacks(), next.targets(), next.drafted()));
            assertEquals(List.of("A 42 secondary +1 front", "B 44 primary front"), targets(next));
        }
    }

    /**
     * H33: a drafted shot that can no longer be declared stays out when its unit's draft comes back: the Crab moved out
     * of the laser's range, and the Quickdraw left the game. A toast counts them; their weapons are not fired.
     */
    @Test
    void aDraftShotThatCanNoLongerBeDeclaredIsDropped() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            Entity crab = enemy(firing, "Crab CRB-20.mtf", 44, NORTH);
            Entity quickdraw = enemy(firing, "Quickdraw QKD-8X.mtf", 45, EAST);
            own(firing);
            int cannon = eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int left = eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            int right = eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            command(firing, fire -> fire.assign(left, TargetKey.unit(crab.getId())));
            command(firing, fire -> fire.assign(right, TargetKey.unit(quickdraw.getId())));
            assertEquals(List.of("AC/20 RT@42", "Medium Laser LA@44", "Medium Laser RA@45"), queue(firing));
            command(firing, fire -> fire.selectUnit(SAGITTAIRE));
            onSwing(() -> {
                crab.setPosition(new Coords(15, 16));
                firing.board.game.removeEntity(quickdraw.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                return null;
            });
            GpuFireOrders.Snapshot back = command(firing, fire -> fire.selectUnit(ATLAS));
            assertEquals(List.of("AC/20 RT@42"), queue(firing));
            assertEquals(List.of("A 42 primary front"), targets(back));
            assertEquals("used: AC/20 RT", used(firing));
            verify(firing.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.fire.draftDropped",
                  firing.attacker.getShortName(), 2));
        }
    }

    /**
     * H33: the drafts belong to one firing phase of one round: a new round (here without a capture between) and a
     * phase change forget them.
     */
    @Test
    void draftsAreForgottenWhenThePhaseOrTheRoundChanges() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            own(firing);
            scriptTurns(firing, LOCAL, ENEMY);
            draftTheAtlas(firing);
            onSwing(() -> {
                Game game = firing.board.game;
                game.setRoundCount(game.getRoundCount() + 1);
                return null;
            });
            assertEquals(Map.of(), captured(firing).drafted());
            command(firing, fire -> fire.selectUnit(ATLAS));
            assertEquals(List.of(), queue(firing), "No draft came back in the next round");

            draftTheAtlas(firing);
            assertEquals(Map.of(ATLAS, 1), captured(firing).drafted());
            onSwing(() -> {
                firing.board.game.setPhase(GamePhase.PHYSICAL);
                return null;
            });
            assertSame(GpuFireOrders.Snapshot.EMPTY, captured(firing));
            onSwing(() -> {
                Game game = firing.board.game;
                game.setRoundCount(game.getRoundCount() + 1);
                game.setPhase(GamePhase.FIRING);
                game.setTurnIndex(0, ENEMY);
                return null;
            });
            GpuFireOrders.Snapshot nextRound = settled(firing);
            assertEquals(List.of(ATLAS, List.of(), Map.of()), List.of(nextRound.actorId(), nextRound.attacks(),
                  nextRound.drafted()), "MegaMek selects the Atlas, which has no orders");
        }
    }

    /**
     * H6: Resolve declares the actor at once (the Sagittaire, without orders, holds fire) and the other own unit on the
     * next own turn with its draft; nothing on the opponent's turn. The count shows on every turn, and the mode ends
     * with the last own unit.
     */
    @Test
    void resolveDeclaresTheDraftsOnTheOwnTurnsOnly() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            Entity sagittaire = own(firing);
            scriptTurns(firing, LOCAL, ENEMY, LOCAL);
            draftTheAtlas(firing);
            firing.board.source.fire().resolvePhase();
            assertEquals(GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 2), settled(firing),
                  "Declared; both units count until the server answers");
            assertEquals(List.of(), sent(firing, SAGITTAIRE), "The Sagittaire holds fire");
            // The client keeps the turn until the server answers: a unit the display selects meanwhile (as the HUD's
            // selection at a turn's start may) gets no orders, and nothing more is declared in that turn.
            firing.board.source.selectUnit(ATLAS);
            assertEquals(GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 2), settled(firing));

            nextTurn(firing, sagittaire);
            assertEquals(GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 1), settled(firing));
            verify(firing.client, never()).sendAttackData(eq(ATLAS), any());

            nextTurn(firing);
            settled(firing);
            assertEquals(List.of("AC/20 RT@42"), sent(firing, ATLAS), "MegaMek selected the Atlas: its draft");

            nextTurn(firing, firing.attacker);
            assertSame(GpuFireOrders.Snapshot.EMPTY, settled(firing));
        }
    }

    /**
     * After Done the client keeps the turn until the server answers, and nothing acts in it; the server sends that same
     * turn again after a declaration it refused (TWGameManager.receiveAttack), and then it is the player's again.
     */
    @Test
    void aTurnTheServerSendsAgainIsLiveAgain() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            scriptTurns(firing, LOCAL, ENEMY);
            command(firing, fire -> fire.assign(eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO),
                  TargetKey.unit(firing.ahead.getId())));
            onSwing(() -> {
                firing.display.getButDone().doClick(0);
                return null;
            });
            assertEquals(List.of("AC/20 RT@42"), sent(firing, ATLAS));
            assertSame(GpuFireOrders.Snapshot.EMPTY, settled(firing));
            onSwing(() -> {
                Game game = firing.board.game;
                game.setTurnIndex(game.getTurnIndex(), LOCAL);
                return null;
            });
            GpuFireOrders.Snapshot again = settled(firing);
            assertEquals(List.of(true, ATLAS), List.of(again.active(), again.actorId()));
        }
    }

    /** H6: the mode ends with its firing phase; the next round's firing turns are the player's again. */
    @Test
    void resolveEndsWithThePhase() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            Entity sagittaire = own(firing);
            resolveTheFirstTurn(firing, sagittaire);
            onSwing(() -> {
                firing.board.game.setPhase(GamePhase.PHYSICAL);
                return null;
            });
            assertSame(GpuFireOrders.Snapshot.EMPTY, settled(firing));

            onSwing(() -> {
                Game game = firing.board.game;
                game.setRoundCount(game.getRoundCount() + 1);
                sagittaire.setDone(false);
                game.setPhase(GamePhase.FIRING);
                game.setTurnVector(List.of(new GameTurn(LOCAL), new GameTurn(ENEMY)));
                game.setTurnIndex(0, ENEMY);
                return null;
            });
            GpuFireOrders.Snapshot nextRound = settled(firing);
            assertEquals(List.of(true, ATLAS, 0), List.of(nextRound.active(), nextRound.actorId(),
                  nextRound.autoDeclareRemaining()));
            verify(firing.client, never()).sendAttackData(eq(ATLAS), any());
        }
    }

    /** H6: Stop on the opponent's turn ends the mode; on the next own turn the Atlas's draft is the player's. */
    @Test
    void stopEndsTheResolve() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            resolveTheFirstTurn(firing, own(firing));
            firing.board.source.fire().stopResolve();
            assertEquals(GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 0), settled(firing));
            nextTurn(firing);
            GpuFireOrders.Snapshot own = settled(firing);
            assertEquals(List.of(true, ATLAS, 0), List.of(own.active(), own.actorId(), own.autoDeclareRemaining()));
            assertEquals(List.of("AC/20 RT@42"), queue(firing));
            verify(firing.client, never()).sendAttackData(eq(ATLAS), any());
        }
    }

    /**
     * H6, B.1 rule 4: while the display ignores input (as under the bot's hex picker) or a native dialog waits, the
     * mode declares nothing and stays on; after the answer it declares the Atlas.
     */
    @Test
    void aPendingDialogPausesTheResolve() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            resolveTheFirstTurn(firing, own(firing));
            onSwing(() -> {
                Game game = firing.board.game;
                game.setTurnIndex(game.getTurnIndex() + 1, ENEMY);
                firing.display.setIgnoringEvents(true);
                return null;
            });
            assertEquals(1, settled(firing).autoDeclareRemaining());
            GpuBoardSource source = firing.board.source;
            DialogRequest request = new DialogRequest(0, DialogKind.MESSAGE, "Confirm", "Continue?", false,
                  List.of("Yes", "No"), 0, 1, List.of(), List.of(), "", false, "", null, null, List.of());
            FutureTask<DialogAnswer> asked = new FutureTask<>(() -> source.ask(request));
            SwingUtilities.invokeLater(asked);
            DialogRequest shown = awaitDialog(source);
            onSwing(() -> {
                firing.display.setIgnoringEvents(false);
                return null;
            });
            assertEquals(1, settled(firing).autoDeclareRemaining());
            verify(firing.client, never()).sendAttackData(eq(ATLAS), any());

            source.answer(shown.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
            asked.get(20, SECONDS);
            settled(firing);
            assertEquals(List.of("AC/20 RT@42"), sent(firing, ATLAS));
        }
    }

    /**
     * Auto-end firing (E3b hand-off): Fire ends the turn once no weapon is left, so an attack declared again (another
     * target, other ammunition) used to be sent last. With every weapon declared, an attack declared again before
     * others in the canonical order keeps its place and the turn goes on; one declared again last ends the turn, and
     * the display sends the canonical order.
     */
    @Test
    void anAttackDeclaredAgainKeepsItsPlaceWhenFireEndsTheTurn() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            Entity crab = enemy(firing, "Crab CRB-20.mtf", 44, NORTH);
            int[] atm = addAtm(firing, Mek.LOC_LEFT_ARM);
            onSwing(() -> {
                // Swing's Fire declares the rear lasers too (they cannot hit the Archer ahead)
                for (WeaponMounted weapon : firing.attacker.getWeaponList()) {
                    if (weapon.isRearMounted()) {
                        firing.fire(weapon, firing.ahead);
                    }
                }
                firing.board.source.refresh();
                return null;
            });
            int left = eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            int right = eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            for (int weapon : new int[] { eqNum(firing, "LRM 20", Mek.LOC_LEFT_TORSO),
                                          eqNum(firing, "SRM 6", Mek.LOC_LEFT_TORSO), right,
                                          eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO) }) {
                command(firing, fire -> fire.assign(weapon, TargetKey.unit(firing.ahead.getId())));
            }
            command(firing, fire -> fire.assign(left, TargetKey.unit(crab.getId())));
            command(firing, fire -> fire.assign(atm[0], TargetKey.unit(crab.getId())));
            onSwing(() -> {
                GUIPreferences.getInstance().setAutoEndFiring(true);
                return null;
            });
            command(firing, fire -> fire.retarget(left, TargetKey.unit(firing.ahead.getId())));
            command(firing, fire -> fire.retarget(right, TargetKey.unit(crab.getId())));
            verify(firing.client, never()).sendAttackData(anyInt(), any());
            List<String> canonical = List.of("Medium Laser CT@42", "Medium Laser CT@42", "LRM 20 LT@42",
                  "SRM 6 LT@42", "AC/20 RT@42", "Medium Laser LA@42", "Medium Laser RA@44", "ATM 6 LA@44");
            assertEquals(canonical, queue(firing), "Each attack declared again kept its place; the turn goes on");

            assertSame(GpuFireOrders.Snapshot.EMPTY,
                  command(firing, fire -> fire.setAmmo(atm[0], bin(firing, atm[1]))));
            assertEquals(canonical, sent(firing, ATLAS), "Sent once, in the canonical order");
            assertEquals(atm[1], firing.sent(ATLAS).stream().map(WeaponAttackAction.class::cast)
                  .filter(attack -> attack.getWeaponId() == atm[0]).findFirst().orElseThrow().getAmmoId(),
                  "With its new bin");
        }
    }

    /**
     * H33: on another player's firing turn the own focus unit's draft is shown read-only: the attack lines it showed
     * as the actor, with their own rolls; a focus without a draft shows the counts alone, and the next own turn gives
     * the draft back. H1: the first local declaration of a firing phase begins with the entry toast, once per phase.
     */
    @Test
    void theFocusUnitsDraftIsShownReadOnlyOnAnotherPlayersTurn() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            Entity sagittaire = own(firing);
            scriptTurns(firing, LOCAL, ENEMY, LOCAL);
            int cannon = eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            GpuFireOrders.Snapshot atlas = command(firing,
                  fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            command(firing, fire -> fire.selectUnit(SAGITTAIRE));
            firing.board.source.setFocusUnit(ATLAS);
            onSwing(() -> {
                GpuBoardActions.skipButton(firing.display).doClick(0);
                return null;
            });
            assertEquals(List.of(), sent(firing, SAGITTAIRE), "The Sagittaire holds fire");

            nextTurn(firing, sagittaire);
            GpuFireOrders.Snapshot draft = settled(firing, ATLAS);
            assertEquals(List.of(true, false, ATLAS, Map.of(), 0), List.of(draft.active(), draft.editable(),
                  draft.actorId(), draft.drafted(), draft.autoDeclareRemaining()));
            assertEquals(List.of(1, atlas.attacks()), List.of(atlas.attacks().size(), draft.attacks()),
                  "The line the Atlas showed, with the same roll");
            assertEquals(GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 0), captured(firing, SAGITTAIRE),
                  "The Sagittaire has no draft");
            assertEquals(GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 0), captured(firing, Entity.NONE));

            nextTurn(firing);
            GpuFireOrders.Snapshot back = settled(firing, ATLAS);
            assertEquals(List.of(true, true, ATLAS, atlas.attacks()), List.of(back.active(), back.editable(),
                  back.actorId(), back.attacks()));
            assertEquals(List.of("AC/20 RT@42"), queue(firing));
            String entry = Messages.getString("GpuBoard.hud.fire.entry");
            verify(firing.gui, times(1)).addToast(ToastLevel.INFO, entry);

            onSwing(() -> {
                Game game = firing.board.game;
                game.setRoundCount(game.getRoundCount() + 1);
                game.setTurnIndex(0, ENEMY);
                return null;
            });
            assertEquals(ATLAS, settled(firing, ATLAS).actorId());
            verify(firing.gui, times(2)).addToast(ToastLevel.INFO, entry);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The Sagittaire SGT-14D of the local player at (6, 6), facing north. */
    static Entity own(GpuFiringFixture firing) throws Exception {
        Entity unit = GpuFiringFixture.unit("Sagittaire SGT-14D.mtf", SAGITTAIRE, new Coords(6, 6));
        onSwing(() -> {
            unit.setOwner(firing.board.player);
            firing.board.game.addEntity(unit, false);
            firing.board.source.refresh();
            return null;
        });
        return unit;
    }

    private static int equipment(Entity unit, String name, int location) {
        return unit.getEquipmentNum(GpuFiringFixture.weapon(unit, name, location));
    }

    /**
     * A Clan ATM 6 with a standard and an extended-range bin, added to the Atlas at the location: bins the Unit
     * Display's ammunition list tells apart (the Atlas's own weapons have two bins of one name each). Returns the
     * launcher's and the extended-range bin's equipment numbers.
     */
    private static int[] addAtm(GpuFiringFixture firing, int location) throws Exception {
        return onSwing(() -> {
            Entity atlas = firing.attacker;
            Mounted<?> launcher = atlas.addEquipment(EquipmentType.get("CLATM6"), location);
            atlas.addEquipment(EquipmentType.get("CLATM6 Ammo"), location);
            Mounted<?> extended = atlas.addEquipment(EquipmentType.get("CLATM6 ER Ammo"), location);
            atlas.loadAllWeapons();
            firing.display.selectEntity(atlas.getId());
            firing.board.source.refresh();
            return new int[] { atlas.getEquipmentNum(launcher), atlas.getEquipmentNum(extended) };
        });
    }

    /** The Atlas's bin with that number, as its ammunition list names it (H30). */
    private static GpuUnitRecord.AmmoChoice bin(GpuFiringFixture firing, int eqNum) {
        return GpuUnitRecord.AmmoChoice.of(firing.attacker, (AmmoMounted) firing.attacker.getEquipment(eqNum));
    }

    /** The Atlas's draft: its AC/20 at the Archer, kept as the Sagittaire is selected. */
    private static void draftTheAtlas(GpuFiringFixture firing) throws Exception {
        int cannon = eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
        command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
        command(firing, fire -> fire.selectUnit(SAGITTAIRE));
    }

    /**
     * Turns own, opponent, own: the Atlas drafts its AC/20, the Sagittaire is selected, Resolve holds its fire, and the
     * server answers with the opponent's turn.
     */
    private static void resolveTheFirstTurn(GpuFiringFixture firing, Entity sagittaire) throws Exception {
        scriptTurns(firing, LOCAL, ENEMY, LOCAL);
        draftTheAtlas(firing);
        firing.board.source.fire().resolvePhase();
        settled(firing);
        assertEquals(List.of(), sent(firing, SAGITTAIRE));
        nextTurn(firing, sagittaire);
        assertEquals(GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 1), settled(firing));
    }

    /**
     * Scripted firing turns instead of a server: the client follows the game's current turn as Client.isMyTurn,
     * getMyTurn, getFirstEntityNum and getNextEntityNum do, and the turns go to these players in order; the first
     * begins now (the display keeps the unit it has).
     */
    static void scriptTurns(GpuFiringFixture firing, int... players) throws Exception {
        onSwing(() -> {
            Game game = firing.board.game;
            game.setTurnVector(Arrays.stream(players).mapToObj(GameTurn::new).toList());
            Client client = firing.client;
            when(client.isMyTurn()).thenAnswer(invocation -> (game.getTurn() != null)
                  && game.getTurn().isValid(LOCAL, game));
            when(client.getMyTurn()).thenAnswer(invocation -> game.getTurn());
            when(client.getFirstEntityNum()).thenAnswer(invocation -> game.getFirstEntityNum(game.getTurn()));
            when(client.getNextEntityNum(anyInt()))
                  .thenAnswer(invocation -> game.getNextEntityNum(game.getTurn(), invocation.getArgument(0)));
            game.setTurnIndex(0, Player.PLAYER_NONE);
            return null;
        });
    }

    /** The server's answer to a declaration: the units it declared for are done, and the next turn begins. */
    static void nextTurn(GpuFiringFixture firing, Entity... declared) throws Exception {
        onSwing(() -> {
            Game game = firing.board.game;
            for (Entity unit : declared) {
                unit.setDone(true);
            }
            game.setTurnIndex(game.getTurnIndex() + 1, game.getTurn().playerId());
            return null;
        });
    }

    /** The orders the source captures now. */
    private static GpuFireOrders.Snapshot captured(GpuFiringFixture firing) throws Exception {
        return captured(firing, Entity.NONE);
    }

    /** The orders the source captures now with that own focus unit. */
    private static GpuFireOrders.Snapshot captured(GpuFiringFixture firing, int focus) throws Exception {
        return onSwing(() -> firing.board.source.fire().capture(firing.display, null, focus));
    }

    /**
     * The orders once a capture saw the change and the events it asked for (a draft back, a declaration) and their
     * republish ran.
     */
    private static GpuFireOrders.Snapshot settled(GpuFiringFixture firing) throws Exception {
        return settled(firing, Entity.NONE);
    }

    /** {@link #settled(GpuFiringFixture)} with that own focus unit, which the source's own captures also use. */
    private static GpuFireOrders.Snapshot settled(GpuFiringFixture firing, int focus) throws Exception {
        for (int pass = 0; pass < 2; pass++) {
            captured(firing, focus);
            for (int event = 0; event < 3; event++) {
                onSwing(() -> null);
            }
        }
        return captured(firing, focus);
    }

    /** Each action: a weapon attack as "weapon location@target ammo {id} aim {location} {mode}", else its class. */
    private static List<String> actions(GpuFiringFixture firing, List<EntityAction> actions) throws Exception {
        Game game = firing.board.game;
        return onSwing(() -> actions.stream().map(action -> (action instanceof WeaponAttackAction attack)
              ? orders(game, List.of(attack)).getFirst() + " ammo " + attack.getAmmoId() + " aim "
                    + attack.getAimedLocation() + " " + attack.getAimingMode()
              : action.getClass().getSimpleName()).toList());
    }
}
