/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.routingClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay.PhysicalCommand;
import megamek.client.ui.util.MegaMekController;
import megamek.common.CriticalSlot;
import megamek.common.Player;
import megamek.common.actions.EntityAction;
import megamek.common.actions.KickAttackAction;
import megamek.common.actions.PunchAttackAction;
import megamek.common.actions.PushAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.utils.ClearBoard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * Physical attacks of a real PhysicalDisplay on a clear board, where an Atlas faces an enemy Atlas one hex north. The
 * first five tests characterize the display as it was before the native options: the Swing buttons' automatic arm and
 * leg, the rolls of each limb and the rule questions of a punch reaching the native modal bridge. The others cover the
 * native HUD's options, target switch and declarations, and when the informational to-hit question is asked (U3).
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuPhysicalOptionsTest {
    private static final String ATLAS = "testresources/megamek/common/units/Atlas AS7-D.mtf";
    private static final String BARGHEST = "testresources/megamek/common/units/Barghest BGS-1T.mtf";
    private static final Coords ATTACKER_HEX = new Coords(5, 5);
    private static final Coords ENEMY_HEX = new Coords(5, 4);
    private static final int NORTH = 0;
    private static final int SOUTH = 3;
    private static final String MISS_PSR = "GpuBoard.hud.physical.missPsr";
    private static final String HIT_PSR = "GpuBoard.hud.physical.hitPsr";

    @Test
    void theRollsOfEachLimbAgainstTheAdjacentEnemy() throws Exception {
        try (Scene scene = Scene.create(swingClient(), ATLAS, NORTH)) {
            // Piloting 5; under the Core Rules (the game's default) a punch and a kick each subtract 1, and no
            // other modifier applies on the clear board.
            assertEquals(List.of(4, 4, 4, 4), onSwing(() -> List.of(
                  PunchAttackAction.toHit(scene.game(), scene.attacker.getId(), scene.enemy, PunchAttackAction.LEFT,
                        false).getValue(),
                  PunchAttackAction.toHit(scene.game(), scene.attacker.getId(), scene.enemy, PunchAttackAction.RIGHT,
                        false).getValue(),
                  KickAttackAction.toHit(scene.game(), scene.attacker.getId(), scene.enemy, KickAttackAction.LEFT)
                        .getValue(),
                  KickAttackAction.toHit(scene.game(), scene.attacker.getId(), scene.enemy, KickAttackAction.RIGHT)
                        .getValue())));
        }
    }

    @Test
    void theSwingPunchButtonPunchesWithBothArmsElseWithTheArmThatCan() throws Exception {
        try (Scene scene = Scene.create(swingClient(), ATLAS, NORTH)) {
            assertEquals(List.of(PunchAttackAction.BOTH), scene.press(PhysicalCommand.PHYSICAL_PUNCH));

            scene.fireArmWeapon(Mek.LOC_LEFT_ARM);
            assertEquals(List.of(PunchAttackAction.RIGHT), scene.press(PhysicalCommand.PHYSICAL_PUNCH),
                  "The left arm fired a weapon this turn");

            scene.fireArmWeapon(Mek.LOC_RIGHT_ARM);
            scene.unfireArmWeapon(Mek.LOC_LEFT_ARM);
            assertEquals(List.of(PunchAttackAction.LEFT), scene.press(PhysicalCommand.PHYSICAL_PUNCH));
        }
    }

    @Test
    void theSwingKickButtonKicksWithTheLegOfTheLowestRoll() throws Exception {
        try (Scene scene = Scene.create(swingClient(), ATLAS, NORTH)) {
            assertEquals(List.of(KickAttackAction.LEFT), scene.press(PhysicalCommand.PHYSICAL_KICK),
                  "Equal rolls kick with the left leg");

            scene.hitFootActuator(Mek.LOC_LEFT_LEG);
            assertEquals(List.of(KickAttackAction.RIGHT), scene.press(PhysicalCommand.PHYSICAL_KICK),
                  "A destroyed left foot actuator adds 1 to the left leg's roll");
        }
    }

    @Test
    void theSwingKickButtonOfAQuadWithTheEnemyBehindItKicksWithTheRightRearLeg() throws Exception {
        try (Scene scene = Scene.create(swingClient(), BARGHEST, SOUTH)) {
            assertEquals(List.of(KickAttackAction.RIGHT_MULE), scene.press(PhysicalCommand.PHYSICAL_KICK),
                  "The front legs cannot reach; the rear legs' equal rolls kick with the right rear leg");
        }
    }

    @Test
    void theBladeAndZweihanderQuestionsOfAPunchReachTheNativeDialog() throws Exception {
        try (Scene scene = Scene.create(routingClient(), ATLAS, NORTH)) {
            scene.addRetractableBlade(Mek.LOC_LEFT_ARM);
            scene.attacker.getCrew().getOptions().getOption(OptionsConstants.PILOT_ZWEIHANDER).setValue(true);
            present(scene.gui, scene.view.getClientState(), scene.source);
            try {
                String blade = Messages.getString("PhysicalDisplay.ExtendBladeDialog.title");
                String zweihander = Messages.getString("PhysicalDisplay.ZweihanderPunchDialog.title");
                List<String> asked = scene.answered(() -> scene.click(PhysicalCommand.PHYSICAL_PUNCH),
                      Map.of(blade, 0, zweihander, 1));
                assertEquals(List.of(blade, zweihander), asked.stream()
                      .filter(title -> !title.equals(scene.punchTitle())).toList());
                PunchAttackAction punch = (PunchAttackAction) scene.lastAttack();
                assertEquals(PunchAttackAction.BOTH, punch.getArm(), "No zweihander: both arms punch");
                assertTrue(punch.isBladeExtended(PunchAttackAction.LEFT), "Yes extends the left arm's blade");
                assertFalse(punch.isZweihandering());

                asked = scene.answered(() -> scene.click(PhysicalCommand.PHYSICAL_PUNCH),
                      Map.of(blade, 1, zweihander, 0));
                assertEquals(List.of(blade, zweihander), asked.stream()
                      .filter(title -> !title.equals(scene.punchTitle())).toList());
                punch = (PunchAttackAction) scene.lastAttack();
                assertTrue(punch.isZweihandering());
                // The zweihander question shows the arm of the higher expected damage, the right arm on this tie;
                // that arm's roll is then made impossible, so the other arm is the one declared.
                assertEquals(PunchAttackAction.LEFT, punch.getArm());
                assertFalse(punch.isBladeExtended(PunchAttackAction.LEFT));
            } finally {
                dismiss();
            }
        }
    }

    // The native HUD's service.

    @Test
    void eachLimbIsAnOptionWithItsRollAndTheOtherAttacksFollow() throws Exception {
        try (Scene scene = Scene.create(swingClient(), ATLAS, NORTH)) {
            GpuPhysicalOptions.Snapshot snapshot = scene.captured();
            assertTrue(snapshot.active());
            assertEquals(scene.attacker.getId(), snapshot.actorId());
            assertEquals(scene.enemy.getId(), snapshot.targetId());
            assertEquals(List.of(scene.enemy.getId()), snapshot.adjacentTargets());
            assertEquals(List.of("punchLeft", "punchRight", "kickLeft", "kickRight", "brushOff", "thrash", "push",
                  "trip", "grapple", "jumpjet", "protoPhysical", "vibro", "pheromone", "toxin"), ids(snapshot));
            // The literal rolls of theRollsOfEachLimbAgainstTheAdjacentEnemy; 4+ is 91.6 % on 2d6.
            assertEquals(new GpuPhysicalOptions.Option("punchLeft", "Punch L", "L", true, "", 4, 91.6, 10,
                  "Punch table", List.of(), true), option(snapshot, "punchLeft"));
            // The kicker's piloting 5 if it misses, the kicked enemy's piloting 4 if it hits.
            assertEquals(new GpuPhysicalOptions.Option("kickRight", "Kick R", "R", true, "", 4, 91.6, 20,
                  "Kick table", List.of(Messages.getString(MISS_PSR, "Atlas", 5),
                  Messages.getString(HIT_PSR, "Atlas", 4)), false), option(snapshot, "kickRight"));
            assertEquals(List.of(Messages.getString(HIT_PSR, "Atlas", 4)), option(snapshot, "push").consequences());
            assertSame(snapshot, scene.captured(), "An unchanged capture keeps its instance");

            scene.fireArmWeapon(Mek.LOC_LEFT_ARM);
            assertEquals(new GpuPhysicalOptions.Option("punchLeft", "Punch L", "L", false,
                  "Weapons fired from arm this turn", TargetRoll.IMPOSSIBLE, 0, 0, "", List.of(), true),
                  option(scene.captured(), "punchLeft"), "Unavailable options stay, with the reason");
        }
    }

    @Test
    void aQuadKicksAnEnemyBehindItWithItsRearLegs() throws Exception {
        try (Scene scene = Scene.create(swingClient(), BARGHEST, SOUTH)) {
            GpuPhysicalOptions.Snapshot snapshot = scene.captured();
            assertEquals(List.of("punchLeft", "punchRight", "kickLeft", "kickRight", "muleKickRight", "muleKickLeft"),
                  ids(snapshot).subList(0, 6));
            assertEquals("Attacker is a quad", option(snapshot, "punchLeft").reason());
            assertEquals("Target not in arc", option(snapshot, "kickLeft").reason());
            // Miss: the Barghest's piloting 5 with MegaMek's quad bonus, -2 while all four legs are intact
            // (QuadMek.addEntityBonuses); hit: the kicked Atlas's piloting 4.
            assertEquals(new GpuPhysicalOptions.Option("muleKickLeft", "Mule kick L", "L", true, "", 4, 91.6, 14,
                  "Kick table", List.of(Messages.getString(MISS_PSR, "Barghest", 3),
                  Messages.getString(HIT_PSR, "Atlas", 4)), false), option(snapshot, "muleKickLeft"));

            assertEquals(List.of(KickAttackAction.LEFT_MULE), scene.declare(true, "muleKickLeft"));
        }
    }

    @Test
    void withoutATargetTheFirstAdjacentEnemyIsTheOneShownAndDeclaredAgainst() throws Exception {
        try (Scene scene = Scene.create(swingClient(), ATLAS, NORTH)) {
            onSwing(() -> {
                scene.display.target(null);
                return null;
            });
            GpuPhysicalOptions.Snapshot snapshot = scene.captured();
            assertEquals(scene.enemy.getId(), snapshot.targetId(), "The prototype's default: the first adjacent enemy");
            assertTrue(option(snapshot, "kickRight").available());

            assertEquals(List.of(KickAttackAction.RIGHT), scene.declare(false, "kickRight"));
            assertEquals(scene.enemy.getId(), ((KickAttackAction) scene.lastAttack()).getTargetId());
        }
    }

    @Test
    void bothPunchesDeclareThePunchWithBothArmsAndEveryOtherOptionItsOwnAttack() throws Exception {
        try (Scene scene = Scene.create(swingClient(), ATLAS, NORTH)) {
            assertEquals(List.of(PunchAttackAction.BOTH), scene.declare(true, "punchLeft", "punchRight"));
            assertEquals(List.of(PunchAttackAction.LEFT), scene.declare(true, "punchLeft"));
            assertEquals(List.of(PunchAttackAction.RIGHT), scene.declare(true, "punchRight"));
            assertEquals(List.of(KickAttackAction.LEFT), scene.declare(true, "kickLeft"));
            assertEquals(List.of(), scene.declare(true, "punchLeft", "kickLeft"), "A kick does not combine");
            assertEquals(List.of(), scene.declare(true, "punchLeft", "punchLeft"));
            assertEquals(List.of(), scene.declare(true, "headbutt"), "An unknown option declares nothing");

            int sent = scene.sent.size();
            scene.declare(true, "push");
            assertEquals(sent + 1, scene.sent.size());
            assertEquals(PushAttackAction.class, scene.lastAttack().getClass(), "The push button's own declaration");

            scene.fireArmWeapon(Mek.LOC_LEFT_ARM);
            assertEquals(List.of(), scene.declare(true, "punchLeft"), "An option no longer available is not declared");
            assertEquals(List.of(), scene.declare(true, "punchLeft", "punchRight"));
            assertEquals(List.of(PunchAttackAction.RIGHT), scene.declare(true, "punchRight"));
        }
    }

    @Test
    void aTargetIsOnlyChosenAmongTheAdjacentEnemies() throws Exception {
        try (Scene scene = Scene.create(swingClient(), ATLAS, NORTH)) {
            Entity flank = onSwing(() -> scene.enemy(4, ATTACKER_HEX.translated(1)));
            Entity distant = onSwing(() -> scene.enemy(5, new Coords(12, 12)));
            GpuPhysicalOptions.Snapshot snapshot = scene.captured();
            assertEquals(List.of(scene.enemy.getId(), flank.getId()), snapshot.adjacentTargets());

            scene.source.physical().target(distant.getId());
            assertEquals(scene.enemy.getId(), scene.captured().targetId(), "A distant enemy is not a physical target");
            scene.source.physical().target(flank.getId());
            assertEquals(flank.getId(), scene.captured().targetId());
            assertEquals(flank, onSwing(scene.display::getTarget), "The physical display's own target");
        }
    }

    @Test
    void whilePresentedTheDocksDeclareIsTheConfirmationAndOnlyTheRuleQuestionsAreAsked() throws Exception {
        try (Scene scene = Scene.create(routingClient(), ATLAS, NORTH)) {
            scene.addRetractableBlade(Mek.LOC_LEFT_ARM);
            scene.attacker.getCrew().getOptions().getOption(OptionsConstants.PILOT_ZWEIHANDER).setValue(true);
            present(scene.gui, scene.view.getClientState(), scene.source);
            try {
                String blade = Messages.getString("PhysicalDisplay.ExtendBladeDialog.title");
                String zweihander = Messages.getString("PhysicalDisplay.ZweihanderPunchDialog.title");
                assertEquals(List.of(blade, zweihander), scene.answered(
                      () -> scene.display.punch(PunchAttackAction.LEFT), Map.of(zweihander, 1)),
                      "No to-hit question before the rule questions");
                assertEquals(PunchAttackAction.LEFT, ((PunchAttackAction) scene.lastAttack()).getArm());
                assertEquals(List.of(zweihander), scene.answered(() -> scene.display.punch(PunchAttackAction.RIGHT),
                      Map.of(zweihander, 1)), "The left arm's blade is not asked about for a right punch");
                assertEquals(List.of(), scene.answered(() -> scene.display.kick(KickAttackAction.RIGHT), Map.of()));
                assertEquals(KickAttackAction.RIGHT, ((KickAttackAction) scene.lastAttack()).getLeg());
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void outsideAPresentedWindowThatDrawsDialogsTheAttackIsConfirmedAsBefore() throws Exception {
        ClientGUI gui = swingClient();
        try (Scene scene = Scene.create(gui, ATLAS, NORTH)) {
            String punch = scene.punchTitle();
            when(gui.doYesNoDialog(eq(punch), anyString())).thenReturn(false);
            assertEquals(List.of(), scene.declare(true, "punchLeft"), "No declares nothing");
            verify(gui).doYesNoDialog(eq(punch), anyString());

            GpuBoardWindow window = present(gui, scene.view.getClientState(), scene.source);
            try {
                GpuDialogFixture.set(window, "presented", false);
                assertEquals(List.of(), scene.declare(true, "punchLeft"),
                      "A native window that is not presented yet keeps the question");
                verify(gui, times(2)).doYesNoDialog(eq(punch), anyString());

                GpuDialogFixture.set(window, "presented", true);
                assertEquals(List.of(PunchAttackAction.LEFT), scene.declare(true, "punchLeft"));
                verify(gui, times(2)).doYesNoDialog(eq(punch), anyString());
            } finally {
                dismiss();
            }
        }
    }

    private static List<String> ids(GpuPhysicalOptions.Snapshot snapshot) {
        return snapshot.options().stream().map(GpuPhysicalOptions.Option::id).toList();
    }

    private static GpuPhysicalOptions.Option option(GpuPhysicalOptions.Snapshot snapshot, String id) {
        return snapshot.options().stream().filter(option -> option.id().equals(id)).findFirst().orElseThrow();
    }

    /** A client whose yes/no questions are answered Yes, as a player confirming every Swing dialog. */
    private static ClientGUI swingClient() {
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
        return gui;
    }

    /**
     * The local player's physical turn on a clear board: their attacker (piloting 5) on {@link #ATTACKER_HEX} with
     * the given facing, an enemy Atlas (piloting 4) facing it from {@link #ENEMY_HEX}, a real physical display with
     * the attacker selected and the enemy targeted, and a board view of the client with its native source.
     */
    static final class Scene implements AutoCloseable {
        final GpuBoardFixture fixture;
        final ClientGUI gui;
        final Client client = mock(Client.class);
        final List<List<EntityAction>> sent = new ArrayList<>();
        Entity attacker;
        Entity enemy;
        BoardView view;
        GpuBoardSource source;
        PhysicalDisplay display;
        private MockedStatic<MegaMekGUI> keys;
        private final RulesManager rules = Game.rulesManager;

        private Scene(GpuBoardFixture fixture, ClientGUI gui) {
            this.fixture = fixture;
            this.gui = gui;
        }

        static Scene create(ClientGUI gui, String attackerFile, int facing) throws Exception {
            Scene scene = new Scene(GpuBoardFixture.create(ClearBoard.of(16, 17)), gui);
            try {
                onSwing(() -> {
                    scene.build(attackerFile, facing);
                    return null;
                });
            } catch (Exception | Error failure) {
                scene.close();
                throw failure;
            }
            return scene;
        }

        /** EDT. */
        private void build(String attackerFile, int facing) throws Exception {
            var game = fixture.game;
            game.initializeRulesManager(OptionsConstants.RULES_CORE);
            game.setPhase(GamePhase.PHYSICAL);
            game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RETRACTABLE_BLADES).setValue(true);
            // The fixture's own unit stays out of reach.
            fixture.entity.setPosition(new Coords(15, 16));
            Player opponent = new Player(1, "Opponent");
            opponent.setTeam(2);
            game.addPlayer(opponent.getId(), opponent);
            attacker = unit(attackerFile, 2, fixture.player, ATTACKER_HEX, facing);
            enemy = unit(ATLAS, 3, opponent, ENEMY_HEX, SOUTH);
            enemy.getCrew().setPiloting(4, 0);
            game.setTurnVector(List.of(new GameTurn(fixture.player.getId())));
            game.setTurnIndex(0, Player.PLAYER_NONE);

            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(client.getMyTurn()).thenReturn(new GameTurn(fixture.player.getId()));
            doAnswer(invocation -> {
                Vector<EntityAction> attacks = invocation.getArgument(1);
                sent.add(List.copyOf(attacks));
                return null;
            }).when(client).sendAttackData(anyInt(), any());
            CommonMenuBar menu = mock(CommonMenuBar.class);
            when(menu.getComponents()).thenReturn(new Component[0]);
            UnitDisplayState unitDisplay = mock(UnitDisplayState.class);
            // The display enables its buttons only while the unit display shows its unit.
            when(unitDisplay.getCurrentEntity()).thenAnswer(invocation -> attacker);
            when(gui.getClient()).thenReturn(client);
            when(gui.getMenuBar()).thenReturn(menu);
            when(gui.getUnitDisplayState()).thenReturn(unitDisplay);
            gui.controller = mock(MegaMekController.class);

            view = new BoardView(game, null, gui, 0);
            view.setLocalPlayer(fixture.player.getId());
            when(gui.boardViews()).thenReturn(List.of(view));
            when(gui.getBoardState()).thenReturn(view.getClientState());
            when(gui.getBoardState(any(Targetable.class))).thenReturn(view.getClientState());
            when(gui.getBoardState(anyInt())).thenReturn(view.getClientState());
            // The key dispatcher is only used while the display registers its commands.
            keys = mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS);
            keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(gui.controller);
            display = new PhysicalDisplay(gui);
            source = new GpuBoardSource(view.getClientState(), () -> display);
            select();
        }

        private Entity unit(String file, int id, Player owner, Coords hex, int facing) throws Exception {
            Entity unit = new MekFileParser(new File(file)).getEntity();
            unit.setId(id);
            unit.setOwner(owner);
            unit.setPosition(hex);
            unit.setFacing(facing);
            unit.setSecondaryFacing(facing);
            unit.setDeployed(true);
            fixture.game.addEntity(unit, false);
            return unit;
        }

        Game game() {
            return fixture.game;
        }

        /** EDT: adds an enemy Atlas facing the attacker's hex. */
        Entity enemy(int id, Coords hex) throws Exception {
            return unit(ATLAS, id, enemy.getOwner(), hex, hex.direction(ATTACKER_HEX));
        }

        /**
         * Declares options through the native source as the dock does, after a capture of the attacker's turn (with
         * the enemy targeted when {@code target}, else with the display's target kept), and returns the arms or legs
         * of the punches or kicks then sent; empty when nothing was sent.
         */
        List<Integer> declare(boolean target, String... ids) throws Exception {
            int before = sent.size();
            onSwing(() -> {
                display.selectEntity(attacker.getId());
                if (target) {
                    display.target(enemy);
                }
                source.refresh();
                return null;
            });
            source.physical().declare(List.of(ids));
            onSwing(() -> null);
            assertTrue(sent.size() <= (before + 1), "At most one declaration is sent");
            return (sent.size() == before) ? List.of() : limbs(sent.getLast());
        }

        /** The physical options of a fresh capture of the native source. */
        GpuPhysicalOptions.Snapshot captured() throws Exception {
            return onSwing(() -> {
                source.refresh();
                return source.takeFrame().panels().physical();
            });
        }

        /** EDT: selects the attacker and targets the enemy, as the start of its turn and a click on the enemy do. */
        void select() {
            display.selectEntity(attacker.getId());
            display.target(enemy);
        }

        /** EDT: presses a button of the physical display. */
        void click(PhysicalCommand command) {
            display.actionPerformed(new ActionEvent(display, ActionEvent.ACTION_PERFORMED, command.getCmd()));
        }

        /**
         * Selects and targets, presses the button with every question answered Yes, and returns the arms or legs of
         * the punches or kicks it sent.
         */
        List<Integer> press(PhysicalCommand command) throws Exception {
            int before = sent.size();
            onSwing(() -> {
                select();
                click(command);
                return null;
            });
            assertEquals(before + 1, sent.size(), "One declaration was sent");
            return limbs(sent.getLast());
        }

        /** The arms or legs of the punches and kicks among the attacks. */
        private static List<Integer> limbs(List<EntityAction> attacks) {
            return attacks.stream().map(action -> switch (action) {
                case PunchAttackAction punch -> punch.getArm();
                case KickAttackAction kick -> kick.getLeg();
                default -> -1;
            }).filter(limb -> limb >= 0).toList();
        }

        /** The last attack sent, after the searchlight the display may add. */
        EntityAction lastAttack() {
            List<EntityAction> attacks = sent.getLast();
            return attacks.get(attacks.size() - 1);
        }

        String punchTitle() {
            return Messages.getString("PhysicalDisplay.PunchDialog.title", enemy.getDisplayName());
        }

        /**
         * Selects and targets, runs the declaration on the EDT and answers each native dialog it shows as the render
         * thread would: the button that {@code answers} gives for the dialog's title, else button 0 (Yes). Returns
         * the titles in the order asked.
         */
        List<String> answered(Runnable declaration, Map<String, Integer> answers) throws Exception {
            FutureTask<Void> task = new FutureTask<>(() -> {
                select();
                declaration.run();
                return null;
            });
            SwingUtilities.invokeLater(task);
            List<String> titles = new ArrayList<>();
            long answeredId = -1;
            long deadline = System.nanoTime() + SECONDS.toNanos(20);
            while (!task.isDone()) {
                DialogRequest shown = source.dialog();
                if ((shown != null) && (shown.id() != answeredId)) {
                    answeredId = shown.id();
                    titles.add(shown.title());
                    source.answer(shown.id(), new DialogAnswer(answers.getOrDefault(shown.title(), 0), List.of(),
                          null, false, List.of()));
                }
                if (System.nanoTime() > deadline) {
                    fail("The declaration did not end; asked " + titles);
                }
                Thread.sleep(5);
            }
            task.get();
            return titles;
        }

        /** A weapon in that arm fired this turn. Unit changes run on the EDT, which captures them. */
        void fireArmWeapon(int location) throws Exception {
            setFired(location, true);
        }

        void unfireArmWeapon(int location) throws Exception {
            setFired(location, false);
        }

        private void setFired(int location, boolean fired) throws Exception {
            onSwing(() -> {
                attacker.getWeaponList().stream().filter(weapon -> weapon.getLocation() == location).findFirst()
                      .orElseThrow().setUsedThisRound(fired);
                return null;
            });
        }

        void hitFootActuator(int location) throws Exception {
            onSwing(() -> {
                for (int slot = 0; slot < attacker.getNumberOfCriticalSlots(location); slot++) {
                    CriticalSlot critical = attacker.getCritical(location, slot);
                    if ((critical != null) && (critical.getType() == CriticalSlot.TYPE_SYSTEM)
                          && (critical.getIndex() == Mek.ACTUATOR_FOOT)) {
                        critical.setHit(true);
                    }
                }
                return null;
            });
        }

        void addRetractableBlade(int location) throws Exception {
            onSwing(() -> attacker.addEquipment(EquipmentType.get("Retractable Blade"), location));
        }

        @Override
        public void close() throws Exception {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (source != null) {
                        source.close();
                    }
                    if (display != null) {
                        display.removeAllListeners();
                    }
                    if (view != null) {
                        view.dispose();
                    }
                    if (keys != null) {
                        keys.close();
                    }
                });
            } finally {
                Game.rulesManager = rules;
                fixture.close();
            }
        }
    }
}
