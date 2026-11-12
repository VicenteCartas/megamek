/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.PlayerColour;
import megamek.common.OffBoardDirection;
import megamek.common.ResolvedAttack;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The G6 command dock in the component harness: every variant beside the hud-v3 shot it ports (01-06, 08-11, 15),
 * the states no shot shows (hold, auto-declare, confirmations, read-only drafts, generic phases, More), inside the
 * window and clear of the columns and the hint line at 900 x 600, 1280 x 720 and 1920 x 1080; and each button running
 * the command of its variant. The services are mocks that record the commands, phase commands record their ids, the
 * playback history plays scripted events, and More opens in the dock's drawer. The weapon declaration also
 * runs over the real FiringDisplay with its fire orders (GpuFiringFixture, scripted turns).
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuCommandDockSmokeTest {
    private static final GpuBattleStatus.Snapshot MOCK = GpuHudFixtures.status();
    private static final int ATLAS = GpuHudFixtures.ATLAS;
    /** The firing fixture's players, as GpuFireDraftsTest scripts their turns. */
    private static final int LOCAL_PLAYER = 0;
    private static final int ENEMY_PLAYER = 2;
    private static final int WARHAMMER = 2;
    private static final int MARAUDER = 3;
    private static final int PANTHER = 4;
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    private static final int ENEMY_LOCUST = 10;
    private static final String DONE = GpuBoardActions.DONE_ID;
    private static final String SKIP = GpuBoardActions.SKIP_ID;
    private static final String GET_UP = "moveGetUp";
    private static final String REROLL = "reportRerollInitiative";
    /** The hint line's gap above the dock, and its height (one 11-unit line). */
    private static final float HINT_GAP = 12;
    private static final float HINT_HEIGHT = 16;
    /** The board the scripted playback plays on: one hex, as the playback only needs the scene's board. */
    private static final BoardScene SCENE = UnitPlaybackFixture.scene(IntStream.concat(
          IntStream.rangeClosed(ATLAS, BATTLEMASTER), IntStream.of(ENEMY_LOCUST))
          .mapToObj(id -> UnitPlaybackFixture.unit(id, id)).toArray(BoardScene.Unit[]::new));

    private final GpuBoardSource source = mock(GpuBoardSource.class);
    private final GpuMovePlan moves = mock(GpuMovePlan.class);
    private final GpuFireOrders fire = mock(GpuFireOrders.class);
    private final GpuPhysicalOptions physical = mock(GpuPhysicalOptions.class);
    private final GpuToasts toasts = mock(GpuToasts.class);
    /** The ids of the phase commands the dock ran, in order. */
    private final List<String> ran = new ArrayList<>();

    /**
     * One frame's inputs of a shot: the status, the panels, the phase commands, the reports, and what the round's
     * playback history has played when the dock first shows it.
     */
    private record Shot(GpuBattleStatus.Snapshot status, GpuHudData panels, List<BoardScene.Command> commands,
          GpuReportLog.Snapshot reports, Consumer<GpuPlaybackHistory> playback,
          GpuBoardSource.UiPreferences preferences) {
        Shot(GpuBattleStatus.Snapshot status, GpuHudData panels, List<BoardScene.Command> commands) {
            this(status, panels, commands, GpuReportLog.Snapshot.EMPTY, history -> { }, GpuHudFixtures.preferences());
        }
    }

    /** A round's log and the combat events the board plays for it. */
    private record Round(GpuReportLog.Snapshot log, List<BoardScene.Animation> events) { }

    /** The dock under test, its HUD state, the harness camera and the context menu for its select lists. */
    private record Dock(GpuCommandDock dock, GpuHudState state, BoardCamera camera, GpuContextMenu menu) {
        Group root() {
            return (Group) dock.actor();
        }

        <T extends Actor> T find(String name) {
            T actor = root().findActor(name);
            return actor != null ? actor : dock.confirmation().actor().findActor(name);
        }

        Group drawer() {
            return (Group) dock.drawer().actor();
        }
    }

    @BeforeEach
    void wireServices() {
        when(source.moves()).thenReturn(moves);
        when(source.fire()).thenReturn(fire);
        when(source.physical()).thenReturn(physical);
        when(source.toasts()).thenReturn(toasts);
    }

    @Test
    void everyVariantBesideTheMockAndInsideTheWindowAtEverySize() {
        GpuHudTestStage.run(hud -> {
            List<Shot> layouts = new ArrayList<>();
            shoot(hud, "g6-initiative", initiative(false), "01-initiative.jpg", layouts);
            shoot(hud, "g6-movement", movement(atlasRoute(), ATLAS), "02-movement-fire-preview.jpg", layouts);
            shoot(hud, "g6-waypoint", movement(pantherRoute(), PANTHER), "03-waypoint-route.jpg", layouts);
            shoot(hud, "g6-opponent", opponent(GamePhase.MOVEMENT, GpuMovePlan.Snapshot.EMPTY,
                  GpuFireOrders.Snapshot.EMPTY), "04-opponent-turn.jpg", layouts);
            shoot(hud, "g6-weapons", weapons(2, true, true), "05-weapon-declaration.jpg", layouts);
            shoot(hud, "g6-three-targets", weapons(3, true, true), "06-three-targets.jpg", layouts);
            // Shot 07 shows the same declaration over the Tactical View, which leaves the dock as it is.
            Dock tactical = dock(hud, weapons(2, true, true));
            show(hud, tactical, weapons(2, true, true));
            compare(hud, tactical, "g6-tactical", "07-tactical-view.jpg");
            shoot(hud, "g6-playback", playback(GamePhase.FIRING_REPORT, round(29, false), landed(4)),
                  "08-weapon-playback.jpg", layouts);
            // Shot 09 with the left kick chosen.
            hud.size(1920, 1080);
            Dock kick = dock(hud, physicalShot(false));
            show(hud, kick, physicalShot(false));
            clickAndShow(hud, kick, physicalShot(false), "dock-option-kickLeft");
            // A clicked button looks pressed for a moment after the release; the chosen option's look comes after.
            Thread.sleep((long) (ClickListener.visualPressedDuration * 1000) + 50);
            show(hud, kick, physicalShot(false));
            compare(hud, kick, "g6-physical", "09-physical-attacks.jpg");
            layouts.add(physicalShot(false));
            shoot(hud, "g6-review", playback(GamePhase.END_REPORT, round(29, false),
                  GpuCommandDockSmokeTest::secondStep), "10-round-report.jpg", layouts);
            shoot(hud, "g6-no-route", movement(GpuMovePlan.Snapshot.EMPTY, ATLAS), "11-force-grid.jpg", layouts);

            // States no shot shows: hold and auto-declare modes, a read-only draft, holding fire with the last unit, a
            // turret's read-out, the opponent's physical turn, no adjacent enemy, generic phases, the steps, physical
            // playback and a quad's option row.
            List<String> states = List.of("hold", "auto-declare", "read-only-draft", "hold-fire", "turret",
                  "physical-opponent", "no-physical", "deployment", "non-planner", "steps", "physical-playback",
                  "physical-quad");
            List<Shot> extra = List.of(opponent(GamePhase.MOVEMENT, holding(2), GpuFireOrders.Snapshot.EMPTY),
                  opponent(GamePhase.FIRING, GpuMovePlan.Snapshot.EMPTY,
                        GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 3)),
                  opponent(GamePhase.FIRING, GpuMovePlan.Snapshot.EMPTY, draft(2)),
                  weapons(orders(0, 0, 0, "", true, true, 1, 0)),
                  weapons(orders(2, 1, -1, "Turret", true, true, 2, 0)),
                  opponent(GamePhase.PHYSICAL, GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY),
                  noPhysical(), deployment(), nonPlanner(), withSteps(movement(atlasRoute(), ATLAS)),
                  playback(GamePhase.PHYSICAL_REPORT, round(0, true), landed(1)),
                  quad());
            for (int i = 0; i < states.size(); i++) {
                Dock dock = dock(hud, extra.get(i));
                show(hud, dock, extra.get(i));
                hud.capture("g6-" + states.get(i)).dispose();
                layouts.add(extra.get(i));
            }
            Dock confirm = dock(hud, weapons(2, true, true));
            show(hud, confirm, weapons(2, true, true));
            confirm.dock().twist(-1);
            show(hud, confirm, weapons(2, true, true));
            hud.capture("g6-twist-confirm").dispose();
            clickAndShow(hud, confirm, weapons(2, true, true), "dock-confirm-no", "dock-resolve");
            show(hud, confirm, weapons(2, true, true));
            hud.capture("g6-resolve-confirm").dispose();
            // The weapon declaration's More: the unit's record and MegaMek's other firing commands (H38).
            Dock firingMore = dock(hud, weapons(2, true, true));
            show(hud, firingMore, weapons(2, true, true));
            click(hud, firingMore.find("dock-more"));
            show(hud, firingMore, weapons(2, true, true));
            hud.capture("g6-more-firing").dispose();
            // More in the dock's drawer.
            Dock more = dock(hud, movement(atlasRoute(), ATLAS));
            show(hud, more, movement(atlasRoute(), ATLAS));
            click(hud, more.find("dock-more"));
            show(hud, more, movement(atlasRoute(), ATLAS));
            hud.capture("g6-more-movement").dispose();

            // Shot 15 at 1280 x 720: the weapons dock at the narrow width.
            hud.size(1280, 720);
            Dock narrow = dock(hud, weapons(2, true, true));
            show(hud, narrow, weapons(2, true, true));
            compare(hud, narrow, "g6-weapons-1280x720", "15-compact-1280x720.jpg");

            // E rule 6: at each size every variant stays in the window, clear of the left and right columns and of
            // the hint line, and no row or label runs out of the dock sideways.
            for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                hud.size(size[0], size[1]);
                for (Shot shot : layouts) {
                    Dock dock = dock(hud, shot);
                    show(hud, dock, shot);
                    hud.assertLayout(dock.dock().actor(), neighbours(hud, dock));
                }
                // The fullest rows at this size: the plan's modes, the transport and the physical options.
                Map<String, Shot> fullest = Map.of("movement", movement(atlasRoute(), ATLAS), "playback",
                      playback(GamePhase.FIRING_REPORT, round(29, false), landed(4)), "physical", physicalShot(false));
                for (Map.Entry<String, Shot> shot : fullest.entrySet()) {
                    Dock dock = dock(hud, shot.getValue());
                    show(hud, dock, shot.getValue());
                    hud.capture("g6-layout-" + size[0] + "x" + size[1] + "-" + shot.getKey()).dispose();
                }
            }
        });
    }

    @Test
    void buttonsRunTheCommandsOfTheirVariant() {
        GpuHudTestStage.run(hud -> {
            // Movement plan: modes, turns, undo, confirm, hold and the waypoint reach the plan and the phase.
            Shot plan = movement(atlasRoute(), ATLAS);
            Dock dock = dock(hud, plan);
            show(hud, dock, plan);
            clickAndShow(hud, dock, plan, "dock-mode-walk", "dock-turn-left", "dock-undo", "dock-waypoint",
                  "dock-main", "dock-secondary");
            verify(moves).setMode(GpuMovePlan.Mode.WALK);
            verify(moves).turn(-1);
            verify(moves).undo();
            verify(moves).pinDestination();
            assertEquals(List.of(DONE, SKIP), ran);
            // The route walks, so the planner's choice outlines Walk and not Run (plan G2).
            TextButton walk = dock.find("dock-mode-walk");
            TextButton run = dock.find("dock-mode-run");
            assertNotSame(walk.getStyle(), run.getStyle());
            assertEquals("2 / 3 MP", label(dock, "dock-foot-bold"));
            assertEquals(" \u00B7 walk auto \u00B7 facing N \u00B7 heat +1", label(dock, "dock-foot"));

            // Boosted running must still outline Run while the planner chooses the mode.
            TextButton.TextButtonStyle automatic = walk.getStyle();
            show(hud, dock, movement(plan(ATLAS, GpuMovePlan.Band.BOOST, 4, 6, "run", 0, 2, List.of()), ATLAS));
            assertSame(automatic, run.getStyle());
            assertNotSame(walk.getStyle(), run.getStyle());

            // Without a route Confirm is ghosted and DONE says how to go on instead of committing.
            ran.clear();
            show(hud, dock, movement(GpuMovePlan.Snapshot.EMPTY, ATLAS));
            assertTrue(((TextButton) dock.find("dock-main")).isDisabled());
            dock.dock().main();
            verify(toasts).post(ToastLevel.INFO, "Plot a route first, or use Hold position");
            assertEquals("automatic \u00B7 facing N", label(dock, "dock-legend"));
            assertEquals(List.of(), ran);
            // An explicit mode names its own word in the legend, as walking backwards does (E2b gearLabel).
            show(hud, dock, movement(explicit(GpuMovePlan.Mode.BACK, "Walk backwards"), ATLAS));
            assertEquals("Walk backwards only \u00B7 facing N", label(dock, "dock-legend"));

            // With MegaMek's Skip hidden ("nag for no action" off), Hold position is Done, which then holds the unit
            // (GpuBoardActions.skipButton).
            Shot skipHidden = withInfo(movement(GpuMovePlan.Snapshot.EMPTY, ATLAS), DONE, DONE);
            show(hud, dock, skipHidden);
            clickAndShow(hud, dock, skipHidden, "dock-secondary");
            assertEquals(List.of(DONE), ran);

            // A unit down stands first: Stand up leads the modes in Jump's place, runs MegaMek's Get Up and leaves
            // More. Standing, Jump returns and Get Up waits among More's unavailable commands.
            ran.clear();
            Shot down = withGetUp(movement(GpuMovePlan.Snapshot.EMPTY, ATLAS), true);
            show(hud, dock, down);
            hud.capture("g6-stand-up").dispose();
            assertNotNull(dock.find("dock-stand-up"));
            assertEquals("Prone", button(dock, "dock-stand-up").details.getFirst().getText().toString());
            assertNull(dock.find("dock-mode-jump"));
            assertNotNull(dock.find("dock-mode-walk"));
            assertTrue(dock.dock().more().items().stream().noneMatch(command -> command.id().equals(GET_UP)));
            clickAndShow(hud, dock, down, "dock-stand-up");
            assertEquals(List.of(GET_UP), ran);
            Shot standing = withGetUp(movement(GpuMovePlan.Snapshot.EMPTY, ATLAS), false);
            show(hud, dock, standing);
            assertNull(dock.find("dock-stand-up"));
            assertNotNull(dock.find("dock-mode-jump"));
            assertTrue(dock.dock().more().commands().stream().anyMatch(command -> command.id().equals(GET_UP)));

            // Weapons (H3, H7): the orders' count and the own units left to declare.
            ran.clear();
            Shot weapons = weapons(2, true, true);
            show(hud, dock, weapons);
            assertEquals(List.of("ATLAS \u00B7 5 WEAPON ATTACKS \u00B7 2 TARGETS", "", "TORSO", "CENTER",
                  "4 more units to declare"), List.of(label(dock, "dock-title"), label(dock, "dock-span"),
                  label(dock, "dock-torso"), label(dock, "dock-torso-value"), label(dock, "dock-foot")));
            // H5: a twist with queued attacks asks first; Keep attacks and Esc keep them, Twist anyway twists.
            dock.dock().twist(-1);
            // The dock keeps its settled height while the question is in a separate overlay.
            dock.dock().update(inputs(hud, dock, weapons));
            float opening = ((Layout) dock.dock().actor()).getPrefHeight();
            show(hud, dock, weapons);
            assertEquals(dock.dock().actor().getHeight(), opening, .5f);
            assertEquals("Twisting clears 5 queued attacks.", label(dock, "dock-confirm-text"));
            clickAndShow(hud, dock, weapons, "dock-confirm-no");
            assertNull(dock.find("dock-confirm"));
            dock.dock().twist(1);
            assertTrue(dock.dock().cancel());
            assertFalse(dock.dock().cancel());
            verify(fire, never()).twist(anyInt());
            dock.dock().twist(-1);
            show(hud, dock, weapons);
            clickAndShow(hud, dock, weapons, "dock-confirm-yes");
            verify(fire).twist(-1);
            // The confirmation closes once nothing is queued any more (the display cleared the queue meanwhile).
            dock.dock().twist(1);
            show(hud, dock, weapons(orders(0, 0, 0, "", true, true, 5, 0)));
            assertNull(dock.find("dock-confirm"));
            // H6: Clear (the Delete key's call too) drops the orders and the armed weapon; Enter fires the weapons.
            show(hud, dock, weapons);
            dock.state().armedWeapon = 3;
            clickAndShow(hud, dock, weapons, "dock-clear");
            dock.state().armedWeapon = 3;
            dock.dock().clearOrders();
            verify(fire, times(2)).clearAll();
            assertEquals(-1, dock.state().armedWeapon);
            assertEquals("FIRE WEAPONS", button(dock, "dock-main").getText().toString());
            dock.dock().main();
            assertEquals(List.of(DONE), ran);
            // Resolve phase discloses the four other units first; Cancel resolves nothing, Resolve starts E3c's mode.
            clickAndShow(hud, dock, weapons, "dock-resolve");
            assertEquals("Resolve now: 4 other units declare saved drafts on your next turns; units without a draft"
                  + " hold fire.", label(dock, "dock-confirm-text"));
            clickAndShow(hud, dock, weapons, "dock-confirm-no");
            assertNull(dock.find("dock-confirm"));
            verify(fire, never()).resolvePhase();
            clickAndShow(hud, dock, weapons, "dock-resolve", "dock-confirm-yes");
            verify(fire).resolvePhase();
            // At the twist limit the key only says so.
            show(hud, dock, weapons(2, false, true));
            dock.dock().twist(-1);
            verify(toasts).post(ToastLevel.INFO, "Torso twist limit reached");
            assertFalse(dock.dock().cancel());

            // Without orders Enter holds fire: MegaMek's Skip, or Done while the display hides Skip; the last unit
            // to declare resolves at once (no disclosure).
            ran.clear();
            Shot holding = weapons(orders(0, 0, 0, "", true, true, 1, 0));
            show(hud, dock, holding);
            assertEquals(List.of("ATLAS \u00B7 0 WEAPON ATTACKS", "", "HOLD FIRE"),
                  List.of(label(dock, "dock-title"), label(dock, "dock-span"),
                        button(dock, "dock-main").getText().toString()));
            assertTrue(button(dock, "dock-clear").isDisabled());
            dock.dock().main();
            show(hud, dock, weapons(orders(0, 0, 0, "", true, true, 1, 0), false));
            dock.dock().main();
            assertEquals(List.of(SKIP, DONE), ran);
            clickAndShow(hud, dock, holding, "dock-resolve");
            assertNull(dock.find("dock-confirm"));
            verify(fire, times(2)).resolvePhase();
            // While the mode declares, Resolve is off and the span counts with Stop beside it.
            Shot resolving = weapons(orders(2, 1, 0, "", true, true, 3, 3));
            show(hud, dock, resolving);
            assertTrue(button(dock, "dock-resolve").isDisabled());
            assertEquals("Auto-declaring \u00B7 3 remaining", label(dock, "dock-span"));
            clickAndShow(hud, dock, resolving, "dock-stop");
            verify(fire).stopResolve();
            // H4: a turret reads "Turret" with its direction, a turret turned around reads its rear.
            show(hud, dock, weapons(orders(2, 1, 3, "Turret", true, true, 2, 0)));
            assertEquals(List.of("TURRET", UiTheme.upper(Messages.getString("GpuBoard.hud.dock.rear"))),
                  List.of(label(dock, "dock-torso"), label(dock, "dock-torso-value")));
            // H38: More holds the unit's record and MegaMek's other firing commands, which run from it.
            show(hud, dock, weapons);
            GpuCommandDock.More extras = dock.dock().more();
            assertEquals(List.of("Unit record"), extras.items().stream().map(BoardScene.Command::label).toList());
            assertEquals(List.of("Flip Arms", "Find Club"), extras.commands().stream()
                  .map(BoardScene.Command::label).toList());
            ran.clear();
            click(hud, button(dock, "dock-more"));
            show(hud, dock, weapons);
            click(hud, dock.drawer().findActor("dock-more-fireFlipArms"));
            assertEquals(List.of("fireFlipArms"), ran);

            // Physical: an unavailable option cannot be chosen; a kick declares alone, both punches together; the
            // option shows MegaMek's roll and its odds in percent.
            ran.clear();
            Shot kick = physicalShot(false);
            dock = dock(hud, kick);
            show(hud, dock, kick);
            assertEquals("4+ \u00B7 92% \u00B7 20 dmg", button(dock, "dock-option-kickLeft").details.getFirst()
                  .getText().toString());
            assertEquals("LA fired a weapon this turn", button(dock, "dock-option-punchLeft").details.getFirst()
                  .getText().toString());
            clickAndShow(hud, dock, kick, "dock-option-punchLeft");
            assertTrue(button(dock, "dock-main").isDisabled());
            clickAndShow(hud, dock, kick, "dock-option-kickLeft");
            assertEquals("DECLARE KICK", button(dock, "dock-main").getText().toString());
            assertEquals("Kick table \u00B7 miss \u2192 Atlas PSR 4+ \u00B7 hit \u2192 Timber Wolf PSR 4+",
                  label(dock, "dock-foot"));
            clickAndShow(hud, dock, kick, "dock-main", "dock-secondary");
            verify(physical).declare(List.of("kickLeft"));
            assertEquals(List.of(SKIP), ran);
            Shot punches = physicalShot(true);
            show(hud, dock, punches);
            clickAndShow(hud, dock, punches, "dock-option-punchLeft", "dock-option-punchRight");
            assertEquals("DECLARE PUNCHES", button(dock, "dock-main").getText().toString());
            // A punch knows no piloting roll (E4): its foot is the table alone, never "a miss has no effect".
            assertEquals("Punch table", label(dock, "dock-foot"));
            clickAndShow(hud, dock, punches, "dock-main", "dock-option-kickRight", "dock-main");
            verify(physical).declare(List.of("punchLeft", "punchRight"));
            verify(physical).declare(List.of("kickRight"));
            // A quad cannot punch: its four kicks fill the row and the punches move to More with their reason.
            Shot quad = quad();
            dock = dock(hud, quad);
            show(hud, dock, quad);
            assertEquals(List.of("dock-option-kickLeft", "dock-option-kickRight", "dock-option-muleKickRight",
                  "dock-option-muleKickLeft"), names(dock.root(), "dock-option-"));
            GpuCommandDock.More options = dock.dock().more();
            assertEquals(List.of("Punch L", "Punch R", "Unit record"), options.items().stream()
                  .map(BoardScene.Command::label).toList());
            assertEquals(List.of("Attacker is a quad", "Attacker is a quad"), options.items().subList(0, 2).stream()
                  .map(BoardScene.Command::detail).toList());
            assertEquals(List.of("Dodge", "Lay explosives"), options.commands().stream()
                  .map(BoardScene.Command::label).toList(), "Dodge and explosives stay MegaMek's commands (E4)");

            // No adjacent enemy: the main button ends the turn without an attack (MegaMek's skip), and More keeps
            // dodge reachable.
            ran.clear();
            Shot none = noPhysical();
            dock = dock(hud, none);
            show(hud, dock, none);
            clickAndShow(hud, dock, none, "dock-main");
            assertEquals(List.of(SKIP), ran);
            assertNotNull(dock.find("dock-more"));

            // Initiative: the result names the local side "you"; the reroll shows only while MegaMek offers it.
            ran.clear();
            dock = dock(hud, initiative(false));
            show(hud, dock, initiative(false));
            assertEquals("Princess won \u00B7 you move first", label(dock, "dock-span"));
            assertNull(dock.find("dock-secondary"));
            show(hud, dock, initiative(true));
            clickAndShow(hud, dock, initiative(true), "dock-secondary", "dock-main");
            assertEquals(List.of(REROLL, DONE), ran);

            // The hold and auto-declare modes show their count and stop through their own service.
            dock = dock(hud, initiative(false));
            Shot hold = opponent(GamePhase.MOVEMENT, holding(2), GpuFireOrders.Snapshot.EMPTY);
            show(hud, dock, hold);
            assertEquals("Holding \u00B7 2 remaining", label(dock, "dock-span"));
            clickAndShow(hud, dock, hold, "dock-stop");
            verify(moves).stopHolding();
            // E3c's idle snapshot on the opponent's turn: the Atlas's draft and the mode's count.
            Shot declaring = opponent(GamePhase.FIRING, GpuMovePlan.Snapshot.EMPTY,
                  GpuFireOrders.Snapshot.idle(Map.of(ATLAS, 1), 3));
            show(hud, dock, declaring);
            assertEquals("Auto-declaring \u00B7 3 remaining", label(dock, "dock-span"));
            clickAndShow(hud, dock, declaring, "dock-stop");
            verify(fire, times(2)).stopResolve();
            // H33: the own focus unit's draft, read-only on another player's firing turn, in the foot.
            show(hud, dock, opponent(GamePhase.FIRING, GpuMovePlan.Snapshot.EMPTY, draft(2)));
            assertEquals(List.of("OPPONENT TURN \u00B7 PRINCESS", "Timber Wolf", "Atlas \u00B7 2 weapon attacks"),
                  List.of(label(dock, "dock-title"), label(dock, "dock-span"), label(dock, "dock-foot-bold")));
            assertNull(dock.find("dock-stop"));

            // Generic phases: the first available commands in MegaMek's order (deployment shows two, and keeps
            // turning, the next unit and removal in More), Done with MegaMek's label, and the rest in More.
            ran.clear();
            Shot deploy = deployment();
            dock = dock(hud, deploy);
            show(hud, dock, deploy);
            assertEquals(List.of("dock-command-deployUnload", "dock-command-deployDock"),
                  names(dock.root(), "dock-command-"));
            assertEquals("DEPLOY", button(dock, "dock-main").getText().toString());
            clickAndShow(hud, dock, deploy, "dock-command-deployUnload", "dock-main");
            assertEquals(List.of("deployUnload", DONE), ran);
            GpuCommandDock.More more = dock.dock().more();
            assertNotNull(more, "Turning and removal stay in More");
            assertTrue(more.commands().stream().map(BoardScene.Command::id).toList()
                  .containsAll(List.of("deployTurn", "deployRemove")));
            // A display whose Done also skips shows it once.
            Shot generic = withInfo(nonPlanner(), DONE, DONE);
            show(hud, dock, generic);
            assertNull(dock.find("dock-secondary"));
            assertEquals("MOVE", button(dock, "dock-main").getText().toString());
        });
    }

    @Test
    void theTransportPlaysPausesStepsReplaysAndSkipsThroughThePlaybackHistory() {
        GpuHudTestStage.run(hud -> {
            // Shot 08: the board has presented the Atlas's volley of four and the player paused.
            Round round = round(29, false);
            Shot paused = playback(GamePhase.FIRING_REPORT, round, landed(4));
            Dock dock = dock(hud, paused);
            GpuPlaybackHistory history = dock.state().history;
            show(hud, dock, paused);
            assertEquals("WEAPON FIRE \u00B7 4 / 29 RESOLVED", label(dock, "dock-title"));
            assertEquals("Paused", label(dock, "dock-span"));
            assertEquals(Messages.getString("GpuBoard.hud.dock.reviewable", 4, 4), label(dock, "dock-counter"));
            assertEquals("PLAY", button(dock, "dock-play").getText().toString());
            assertEquals("SKIP TO RESULTS", button(dock, "dock-main").getText().toString());
            // J6: the foot names the event under the cursor as the log's card does (GpuEventLine).
            assertEquals("Atlas \u2192 King Crab \u00B7 Medium Laser LA \u00B7 HIT \u00B7 5 damage",
                  label(dock, "dock-foot"));

            // Play resumes the playback, Pause pauses it again; the speeds are the phase header's.
            clickAndShow(hud, dock, paused, "dock-play");
            assertFalse(history.paused());
            assertEquals(List.of("Playing", "PAUSE"), List.of(label(dock, "dock-span"),
                  button(dock, "dock-play").getText().toString()));
            clickAndShow(hud, dock, paused, "dock-play");
            assertTrue(history.paused());
            // The previous event moves the review cursor back among the presented steps.
            clickAndShow(hud, dock, paused, "dock-previous");
            assertEquals(history.played().get(2), history.current());
            assertEquals(Messages.getString("GpuBoard.hud.dock.reviewable", 3, 4), label(dock, "dock-counter"));
            assertEquals("Atlas \u2192 Locust \u00B7 SRM 6 LT \u00B7 MISS", label(dock, "dock-foot"));

            // Skip to results presents every attack at once; then the main button continues to the physical phase.
            clickAndShow(hud, dock, paused, "dock-main");
            history.advance(0, ignored -> true);
            show(hud, dock, paused);
            assertFalse(history.running());
            assertEquals(List.of("WEAPON FIRE \u00B7 29 / 29 RESOLVED", "Resolved", "CONTINUE \u00B7 PHYSICAL"),
                  List.of(label(dock, "dock-title"), label(dock, "dock-span"), button(dock, "dock-main").getText()
                        .toString()));
            assertEquals(List.of(), ran);
            // Replay replays the playback's steps, never the live events.
            clickAndShow(hud, dock, paused, "dock-replay");
            assertTrue(history.replaying());
            assertEquals(List.of("Replaying", "PAUSE"), List.of(label(dock, "dock-span"),
                  button(dock, "dock-play").getText().toString()));
            clickAndShow(hud, dock, paused, "dock-play", "dock-main");
            assertFalse(history.replaying());
            assertEquals(List.of(DONE), ran);
            // Follow camera is the camera's flag.
            boolean follow = dock.camera().animateCombatPlayback;
            clickAndShow(hud, dock, paused, "dock-follow");
            assertEquals(!follow, dock.camera().animateCombatPlayback);

            // Shot 10: the round's review, the cursor on its second step; Enter readies the next round.
            ran.clear();
            Shot review = playback(GamePhase.END_REPORT, round(29, false), GpuCommandDockSmokeTest::secondStep);
            dock = dock(hud, review);
            show(hud, dock, review);
            assertEquals(List.of("ROUND 3 REVIEW", "Next: initiative", "READY FOR NEXT ROUND"),
                  List.of(label(dock, "dock-title"), label(dock, "dock-span"), button(dock, "dock-main").getText()
                        .toString()));
            assertEquals(Messages.getString("GpuBoard.hud.dock.reviewable", 2, 31), label(dock, "dock-counter"));
            assertEquals("Atlas \u2192 Timber Wolf \u00B7 LRM 20 LT \u00B7 HIT \u00B7 12 damage",
                  label(dock, "dock-foot"));
            dock.dock().main();
            assertEquals(List.of(DONE), ran);
            // A piloting roll names its unit alone (the prototype's evLine).
            GpuPlaybackHistory reviewed = dock.state().history;
            reviewed.reviewTo(reviewed.played().get(29));
            show(hud, dock, review);
            assertEquals("King Crab \u00B7 PASSED \u00B7 took 20+ damage", label(dock, "dock-foot"));
        });
    }

    @Test
    void moreOpensInTheDrawerWithOnlyUsableCommands() {
        GpuHudTestStage.run(hud -> {
            Shot plan = movement(atlasRoute(), ATLAS);
            Dock dock = dock(hud, plan);
            show(hud, dock, plan);
            UiButton more = button(dock, "dock-more");
            click(hud, more);
            show(hud, dock, plan);
            Group drawer = dock.drawer();
            assertTrue(drawer.isVisible());
            // The panel's movement controls and unit record no longer appear in More.
            assertEquals(List.of("MORE MOVEMENT", "GO PRONE"), texts(drawer));
            hud.capture("g6-more-drawer-movement").dispose();
            click(hud, drawer.findActor("dock-more-moveGoProne"));
            assertEquals(List.of("moveGoProne"), ran);
            assertFalse(dock.dock().drawer().isOpen(), "choosing an item closes the drawer");
            assertEquals(List.of("Hull Down"), dock.dock().more().commands().stream()
                  .map(BoardScene.Command::label).toList());
            ran.clear();

            // Without an adjacent enemy, More closes the action row and lists the physical phase's own commands.
            Shot none = noPhysical();
            dock = dock(hud, none);
            show(hud, dock, none);
            click(hud, button(dock, "dock-more"));
            show(hud, dock, none);
            assertEquals(List.of("MORE", "UNIT RECORD", "DODGE"), texts(dock.drawer()));
            hud.capture("g6-more-drawer-physical").dispose();
            click(hud, dock.drawer().findActor("dock-more-dodge"));
            assertEquals(List.of("dodge"), ran);
        });
    }

    @Test
    void movementControlsFollowThePlanAndHoldAllRequiresConfirmation() {
        GpuHudTestStage.run(hud -> {
            Shot plan = movement(atlasRoute(), ATLAS);
            Dock dock = dock(hud, plan);
            show(hud, dock, plan);
            assertEquals("", label(dock, "dock-span"));
            UiKit.Checkbox backwards = dock.find("dock-backwards");
            assertFalse(backwards.isTicked());
            click(hud, backwards);
            verify(moves).setMode(GpuMovePlan.Mode.BACK);
            Shot back = movement(explicit(GpuMovePlan.Mode.BACK, "Walk backwards"), ATLAS);
            show(hud, dock, back);
            assertTrue(backwards.isTicked());
            click(hud, backwards);
            verify(moves, times(2)).setMode(GpuMovePlan.Mode.BACK);
            show(hud, dock, plan);
            assertFalse(backwards.isTicked());
            verify(moves, times(2)).setMode(GpuMovePlan.Mode.BACK);

            Rectangle hold = GpuHudTestStage.bounds(button(dock, "dock-hold-all"));
            Rectangle waypoint = GpuHudTestStage.bounds(button(dock, "dock-waypoint"));
            Rectangle clear = GpuHudTestStage.bounds(button(dock, "dock-clear-route"));
            assertTrue(hold.x + hold.width <= waypoint.x && waypoint.x + waypoint.width <= clear.x);
            assertEquals(waypoint.y, clear.y, .01f);
            clickAndShow(hud, dock, plan, "dock-clear-route");
            verify(moves).clearRoute();
            show(hud, dock, back);
            assertTrue(button(dock, "dock-clear-route").isDisabled());

            Shot noBack = new Shot(plan.status(), plan.panels(), plan.commands().stream()
                  .filter(command -> !command.id().equals("moveBackUp")).toList());
            show(hud, dock, noBack);
            assertNull(dock.find("dock-backwards"));
            assertEquals("", label(dock, "dock-span"));

            clickAndShow(hud, dock, plan, "dock-hold-all");
            verify(moves, never()).holdAll();
            assertEquals(Messages.getString("GpuBoard.hud.dock.holdAllConfirm"), label(dock, "dock-confirm-text"));
            hud.assertLayout(dock.dock().actor(), neighbours(hud, dock));
            hud.capture("g6-movement-hold-confirm").dispose();
            clickAndShow(hud, dock, plan, "dock-confirm-no");
            assertNull(dock.find("dock-confirm"));
            verify(moves, never()).holdAll();

            clickAndShow(hud, dock, plan, "dock-hold-all");
            assertTrue(dock.dock().cancel());
            show(hud, dock, plan);
            verify(moves, never()).holdAll();

            // An actor change invalidates the pending confirmation.
            clickAndShow(hud, dock, plan, "dock-hold-all");
            show(hud, dock, movement(pantherRoute(), PANTHER));
            assertNull(dock.find("dock-confirm"));
            verify(moves, never()).holdAll();

            show(hud, dock, plan);
            clickAndShow(hud, dock, plan, "dock-hold-all", "dock-confirm-yes");
            verify(moves).holdAll();
            assertNull(dock.find("dock-confirm"));
        });
    }

    @Test
    void confirmationsAnchorWithoutMovingControlsAndToggleSafely() {
        GpuHudTestStage.run(hud -> {
            Shot plan = movement(atlasRoute(), ATLAS);
            for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                hud.size(size[0], size[1]);
                Dock dock = dock(hud, plan);
                show(hud, dock, plan);
                Rectangle before = GpuHudTestStage.bounds(dock.root());
                UiButton trigger = button(dock, "dock-hold-all");
                Rectangle hold = GpuHudTestStage.bounds(trigger);
                clickAndShow(hud, dock, plan, "dock-hold-all");
                Rectangle card = GpuHudTestStage.bounds(dock.find("dock-confirm"));
                assertEquals(before, GpuHudTestStage.bounds(dock.root()), "confirmation leaves the dock in place");
                assertEquals(hold, GpuHudTestStage.bounds(trigger));
                assertEquals(hold.y + hold.height + 8, card.y, .01f);
                assertFalse(card.overlaps(hold), "a second click on the trigger cannot confirm");
                hud.assertLayout(dock.find("dock-confirm"));
                assertSame(button(dock, "dock-confirm-no"), hud.stage.getKeyboardFocus());
                for (String name : List.of("dock-confirm-no", "dock-confirm-yes")) {
                    Label label = button(dock, name).getLabel();
                    assertTrue(label.getWidth() >= label.getPrefWidth() - .5f, "confirmation labels stay readable");
                }
                hud.capture("g6-hold-confirm-" + size[0] + "x" + size[1]).dispose();

                // The trigger toggles the card closed without running or reopening the action.
                click(hud, trigger);
                assertFalse(dock.dock().confirmation().isOpen());
                assertSame(trigger, hud.stage.getKeyboardFocus());
                verify(moves, never()).holdAll();

                clickAndShow(hud, dock, plan, "dock-hold-all");
                hud.stage.keyDown(Input.Keys.ENTER);
                hud.stage.keyUp(Input.Keys.ENTER);
                assertFalse(dock.dock().confirmation().isOpen(), "Enter initially chooses Cancel");
                clickAndShow(hud, dock, plan, "dock-hold-all");
                hud.stage.keyDown(Input.Keys.TAB);
                assertSame(button(dock, "dock-confirm-yes"), hud.stage.getKeyboardFocus());
                hud.stage.keyDown(Input.Keys.TAB);
                assertSame(button(dock, "dock-confirm-no"), hud.stage.getKeyboardFocus(), "Tab stays in the card");
                hud.stage.keyDown(Input.Keys.ESCAPE);
                assertFalse(dock.dock().confirmation().isOpen());
                assertSame(trigger, hud.stage.getKeyboardFocus());

                // Near the top edge the card flips below its trigger, keeping both inside the window.
                dock.root().getParent().setY(hud.height() - dock.root().getHeight() - 10);
                click(hud, trigger);
                dock.dock().layoutConfirmation(hud.width(), hud.height());
                card = GpuHudTestStage.bounds(dock.find("dock-confirm"));
                hold = GpuHudTestStage.bounds(trigger);
                assertEquals(hold.y - 8, card.y + card.height, .01f);
                assertFalse(card.overlaps(hold));
                hud.assertLayout(dock.find("dock-confirm"));
                dock.dock().cancel();
            }

            Shot weapons = weapons(2, true, true);
            Dock dock = dock(hud, weapons);
            show(hud, dock, weapons);
            for (String trigger : List.of("dock-twist-left", "dock-twist-right", "dock-resolve")) {
                clickAndShow(hud, dock, weapons, trigger);
                Rectangle button = GpuHudTestStage.bounds(button(dock, trigger));
                Rectangle card = GpuHudTestStage.bounds(dock.find("dock-confirm"));
                assertEquals(button.y + button.height + 8, card.y, .01f);
                assertFalse(card.overlaps(button));
                hud.assertLayout(dock.find("dock-confirm"));
                dock.dock().cancel();
            }
        });
    }

    /**
     * The weapon declaration over the real FiringDisplay and its fire orders (A.8 H4-H7): Keep attacks keeps the
     * queue, Twist anyway twists and the display clears the queue, Clear (also the Delete key's call) clears it, Enter
     * fires the queued weapons through MegaMek's Done, and on the next own turn Enter holds the last unit's fire
     * through its Skip; the foot counts the own units left to declare.
     */
    @Test
    void theWeaponDockTwistsClearsFiresAndHoldsThroughTheFiringDisplay() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity sagittaire = GpuFireDraftsTest.own(firing);
            GpuFireDraftsTest.scriptTurns(firing, LOCAL_PLAYER, ENEMY_PLAYER, LOCAL_PLAYER);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int laser = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            GpuBattleStatus.Snapshot atlasTurn = status(GamePhase.FIRING, true, ATLAS, 0);
            GpuHudTestStage.run(hud -> {
                Dock dock = dock(hud, firing.board.source);
                GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
                GpuFiringFixture.command(firing, fire -> fire.assign(laser, TargetKey.unit(firing.ahead.getId())));
                Shot orders = settled(firing, atlasTurn);
                show(hud, dock, orders);
                assertEquals(List.of("ATLAS \u00B7 2 WEAPON ATTACKS \u00B7 1 TARGET", "1 more unit to declare"),
                      List.of(label(dock, "dock-title"), label(dock, "dock-foot")));

                // H5: Keep attacks keeps the queue.
                dock.dock().twist(-1);
                show(hud, dock, orders);
                assertEquals("Twisting clears 2 queued attacks.", label(dock, "dock-confirm-text"));
                clickAndShow(hud, dock, orders, "dock-confirm-no");
                settled(firing, atlasTurn);
                assertEquals(List.of("AC/20 RT@42", "Medium Laser LA@42"), GpuFiringFixture.queue(firing));
                // Twist anyway twists the torso, and the display clears the queue.
                dock.dock().twist(-1);
                show(hud, dock, orders);
                clickAndShow(hud, dock, orders, "dock-confirm-yes");
                Shot twisted = settled(firing, atlasTurn);
                show(hud, dock, twisted);
                assertEquals(List.of(), GpuFiringFixture.queue(firing));
                assertEquals(List.of("LEFT", "HOLD FIRE"), List.of(label(dock, "dock-torso-value"),
                      button(dock, "dock-main").getText().toString()));
                assertTrue(button(dock, "dock-twist-left").isDisabled(), "The Atlas twists one hex side");
                assertTrue(button(dock, "dock-clear").isDisabled());

                // H6: Clear, as the Delete key, drops the queue and the armed weapon.
                GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
                show(hud, dock, settled(firing, atlasTurn));
                dock.state().armedWeapon = laser;
                dock.dock().clearOrders();
                settled(firing, atlasTurn);
                assertEquals(List.of(), GpuFiringFixture.queue(firing));
                assertEquals(-1, dock.state().armedWeapon);

                // Enter fires the queued weapons through MegaMek's Done.
                GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
                show(hud, dock, settled(firing, atlasTurn));
                assertEquals("FIRE WEAPONS", button(dock, "dock-main").getText().toString());
                dock.dock().main();
                settled(firing, atlasTurn);
                assertEquals(List.of("AC/20 RT@42"), GpuFiringFixture.sent(firing, ATLAS));

                // The opponent's turn, then the Sagittaire's: as the last unit to declare, Enter holds its fire.
                GpuFireDraftsTest.nextTurn(firing, firing.attacker);
                GpuFireDraftsTest.nextTurn(firing);
                Shot last = settled(firing, status(GamePhase.FIRING, true, sagittaire.getId(), 2));
                show(hud, dock, last);
                assertEquals(List.of("HOLD FIRE"),
                      List.of(button(dock, "dock-main").getText().toString()));
                dock.dock().main();
                settled(firing, atlasTurn);
                assertEquals(List.of(), GpuFiringFixture.sent(firing, sagittaire.getId()));
            });
        }
    }

    /**
     * Resolve phase through the fire orders' mode (A.8 H6, E3c): the disclosure counts the other unit; Cancel resolves
     * nothing; Resolve declares the Sagittaire at once (it holds fire) and the dock shows the mode's count with Stop,
     * on the opponent's turn too, where the own focus unit's draft is read-only (H33); Stop ends the mode, and the
     * next own turn gives the Atlas its draft back, nothing declared for it.
     */
    @Test
    void resolveRunsThroughTheFireOrdersAndTheFocusUnitsDraftIsReadOnlyMeanwhile() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity sagittaire = GpuFireDraftsTest.own(firing);
            GpuFireDraftsTest.scriptTurns(firing, LOCAL_PLAYER, ENEMY_PLAYER, LOCAL_PLAYER);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            GpuBattleStatus.Snapshot ownTurn = status(GamePhase.FIRING, true, sagittaire.getId(), 0);
            GpuHudTestStage.run(hud -> {
                Dock dock = dock(hud, firing.board.source);
                firing.board.source.setFocusUnit(ATLAS);
                GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
                GpuFiringFixture.command(firing, fire -> fire.selectUnit(sagittaire.getId()));
                Shot declaring = settled(firing, ownTurn);
                show(hud, dock, declaring);
                clickAndShow(hud, dock, declaring, "dock-resolve");
                assertEquals("Resolve now: 1 other unit declares saved drafts on your next turns; units without a"
                      + " draft hold fire.", label(dock, "dock-confirm-text"));
                clickAndShow(hud, dock, declaring, "dock-confirm-no");
                assertNull(dock.find("dock-confirm"));
                assertEquals(0, settled(firing, ownTurn).panels().fire().autoDeclareRemaining());

                clickAndShow(hud, dock, declaring, "dock-resolve", "dock-confirm-yes");
                Shot resolving = settled(firing, ownTurn);
                show(hud, dock, resolving);
                assertEquals(List.of(), GpuFiringFixture.sent(firing, sagittaire.getId()), "It holds fire at once");
                assertEquals("Auto-declaring \u00B7 2 remaining", label(dock, "dock-span"));
                assertNotNull(dock.find("dock-stop"));

                GpuFireDraftsTest.nextTurn(firing, sagittaire);
                Shot waiting = settled(firing, opponentTurn(GamePhase.FIRING));
                show(hud, dock, waiting);
                assertEquals(List.of("Auto-declaring \u00B7 1 remaining", "Atlas \u00B7 1 weapon attack"),
                      List.of(label(dock, "dock-span"), label(dock, "dock-foot-bold")));
                clickAndShow(hud, dock, waiting, "dock-stop");
                Shot stopped = settled(firing, opponentTurn(GamePhase.FIRING));
                show(hud, dock, stopped);
                assertEquals(List.of(0, "Timber Wolf", "Atlas \u00B7 1 weapon attack"), List.of(
                      stopped.panels().fire().autoDeclareRemaining(), label(dock, "dock-span"),
                      label(dock, "dock-foot-bold")));
                assertNull(dock.find("dock-stop"));

                GpuFireDraftsTest.nextTurn(firing);
                Shot back = settled(firing, status(GamePhase.FIRING, true, ATLAS, 2));
                show(hud, dock, back);
                assertEquals(List.of("ATLAS \u00B7 1 WEAPON ATTACK \u00B7 1 TARGET"),
                      List.of(label(dock, "dock-title")));
                verify(firing.client, never()).sendAttackData(eq(ATLAS), any());
            });
        }
    }

    /**
     * TARGETING with an observed unit beyond the west edge (plan P7; GpuTargetingFixture: a real targeting display
     * whose Long Tom faces west): the firing dock offers the edge whose arrow the Legacy 2D board shows in More,
     * at 1920 x 1080 and 1280 x 720. A click queues the Long Tom's attack through the display: the edge leaves
     * More with the fired Long Tom, Fire turns on, and the display's Done sends it.
     */
    @Test
    void theTargetingDockOffersTheOffBoardTargetInMoreAndItsClickQueuesTheAttack() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            Entity archer = onSwing(() -> {
                targeting.battery("Crab CRB-20.mtf", 44, OffBoardDirection.EAST);
                return targeting.battery("Archer ARC-2R.mtf", 42, OffBoardDirection.WEST);
            });
            GpuBoardSource.Frame frame = onSwing(() -> {
                targeting.source.refresh();
                return targeting.source.takeFrame();
            });
            Shot offBoard = new Shot(frame.status(), frame.panels(), frame.scene().commands());
            GpuHudTestStage.run(hud -> {
                for (int[] size : new int[][] { { 1920, 1080 }, { 1280, 720 } }) {
                    hud.size(size[0], size[1]);
                    Dock dock = dock(hud, offBoard);
                    show(hud, dock, offBoard);
                    click(hud, button(dock, "dock-more"));
                    show(hud, dock, offBoard);
                    UiButton target = dock.drawer().findActor("dock-more-offboard.WEST");
                    assertNotNull(target, "The firing dock keeps the off-board attack in More");
                    assertEquals("OFF-BOARD WEST", target.getText().toString());
                    hud.capture("g6-targeting-offboard-" + size[0] + "x" + size[1]).dispose();
                    if (size[0] == 1280) {
                        click(hud, target);
                        GpuBoardSource.Frame fired = onSwing(() -> {
                            targeting.source.refresh();
                            return targeting.source.takeFrame();
                        });
                        show(hud, dock, new Shot(fired.status(), fired.panels(), fired.scene().commands()));
                        assertTrue(dock.dock().more() == null || dock.dock().more().commands().stream()
                              .noneMatch(command -> command.id().startsWith("offboard.")));
                        assertFalse(button(dock, "dock-main").isDisabled(), "Fire sends the queued attack");
                        hud.capture("g6-targeting-offboard-fired-1280x720").dispose();
                    }
                }
            });
            onSwing(() -> {
                targeting.display.ready();
                return null;
            });
            ArtilleryAttackAction sent = (ArtilleryAttackAction) targeting.sent(targeting.attacker.getId()).getFirst();
            assertEquals(List.of(archer.getId(), targeting.attacker.getEquipmentNum(targeting.longTom)),
                  List.of(sent.getTargetId(), sent.getWeaponId()));
        }
    }

    // ------------------------------------------------------------------ shots

    /** Shot 01: round 3's initiative report, Princess 10 against 3, the local side moving first. */
    private Shot initiative(boolean reroll) {
        List<GpuBattleStatus.InitiativeSide> sides = List.of(new GpuBattleStatus.InitiativeSide("Player",
                    PlayerColour.BLUE.getColour().getRGB(), OWN, 3, 3, 0, List.of(2, 1), List.of(),
                    List.of(GpuHudFixtures.LOCAL_PLAYER)),
              new GpuBattleStatus.InitiativeSide("Princess", PlayerColour.RED.getColour().getRGB(), ENEMY, 10, 10, 0,
                    List.of(5, 5), List.of(), List.of(GpuHudFixtures.ENEMY_PLAYER)));
        GpuBattleStatus.Snapshot status = new GpuBattleStatus.Snapshot(MOCK.round(), GamePhase.INITIATIVE_REPORT,
              false, MOCK.localPlayerId(), Entity.NONE, MOCK.turns(), 0, MOCK.units(), sides, false);
        List<BoardScene.Command> commands = new ArrayList<>(List.of(command(DONE, "Done", true)));
        if (reroll) {
            commands.add(command(REROLL, "Reroll Initiative", true));
        }
        return new Shot(status, panels(info(), GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY,
              GpuPhysicalOptions.Snapshot.EMPTY), commands);
    }

    /** Shots 02, 03 and 11: the local movement turn of {@code actor} with the plan's route (none in 11). */
    private Shot movement(GpuMovePlan.Snapshot plan, int actor) {
        GpuMovePlan.Snapshot move = plan.active() ? plan : explicit(GpuMovePlan.Mode.AUTO, "");
        return new Shot(status(GamePhase.MOVEMENT, true, actor, 0), panels(info(), move,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY), moveCommands());
    }

    /** The Atlas's plan without a route in {@code mode}, which is explicit unless automatic. */
    private static GpuMovePlan.Snapshot explicit(GpuMovePlan.Mode mode, String gearLabel) {
        return new GpuMovePlan.Snapshot(true, true, false, ATLAS, mode, mode != GpuMovePlan.Mode.AUTO, gearLabel,
              List.of(), List.of(), List.of(), null, 0, 0, 3, EntityMovementType.MOVE_NONE, "", false, 0, 0, true,
              List.of(), false, false, true, Map.of(), Set.of(), 0, false);
    }

    /** Shot 02's plan: the Atlas walks two hexes north, 2 of 3 MP, chosen by the planner. */
    private static GpuMovePlan.Snapshot atlasRoute() {
        return plan(ATLAS, GpuMovePlan.Band.WALK, 2, 3, "walk", 0, 1, List.of());
    }

    /** Shot 03's plan: the Panther runs to the north-east over one pinned waypoint, 5 of 6 MP. */
    private static GpuMovePlan.Snapshot pantherRoute() {
        return plan(PANTHER, GpuMovePlan.Band.RUN, 5, 6, "run", 1, 2, List.of(new Coords(17, 12)));
    }

    private static GpuMovePlan.Snapshot plan(int unit, GpuMovePlan.Band band, int cost, int budget, String type,
          int facing, int heat, List<Coords> pins) {
        List<GpuMovePlan.Step> route = IntStream.range(0, cost).mapToObj(step -> new GpuMovePlan.Step(
              new Coords(14, 12 - step), 0, 0, facing, band, false)).toList();
        List<GpuMovePlan.Step> pinned = pins.stream()
              .map(hex -> new GpuMovePlan.Step(hex, 0, 0, facing, band, false)).toList();
        return new GpuMovePlan.Snapshot(true, true, false, unit, GpuMovePlan.Mode.AUTO, false, "", route, List.of(),
              pinned, route.getLast().coords(), facing, cost, budget, EntityMovementType.MOVE_WALK, type, true, heat, 0,
              true, List.of(), true, true, true, Map.of(), Set.of(), 0, false);
    }

    /** A hold that still covers {@code remaining} own units on the next turns (G15, E2c). */
    private static GpuMovePlan.Snapshot holding(int remaining) {
        return GpuMovePlan.Snapshot.idle(remaining);
    }

    /** Shot 04 and its firing and physical counterparts: Princess acts with the identified Timber Wolf. */
    private Shot opponent(GamePhase phase, GpuMovePlan.Snapshot move, GpuFireOrders.Snapshot orders) {
        return new Shot(opponentTurn(phase), panels(info(), move, orders, GpuPhysicalOptions.Snapshot.EMPTY),
              List.of());
    }

    /** The mock roster's round 3 on Princess's turn with the Timber Wolf. */
    private static GpuBattleStatus.Snapshot opponentTurn(GamePhase phase) {
        List<GpuBattleStatus.Slot> turns = new ArrayList<>(MOCK.turns());
        GpuBattleStatus.Slot second = turns.get(1);
        turns.set(1, new GpuBattleStatus.Slot(second.playerId(), second.playerName(), second.rgb(), ENEMY,
              TIMBER_WOLF));
        return new GpuBattleStatus.Snapshot(MOCK.round(), phase, false, MOCK.localPlayerId(), Entity.NONE, turns, 1,
              MOCK.units(), List.of(), false);
    }

    /**
     * Shots 05 and 06: the Atlas declares five attacks on two targets (three with a third target), four more own
     * units to declare; the twist is free both ways unless a limit is reached.
     */
    private Shot weapons(int targets, boolean left, boolean right) {
        return weapons(orders(5, targets, 0, "", left, right, 5, 0));
    }

    private Shot weapons(GpuFireOrders.Snapshot orders) {
        return weapons(orders, true);
    }

    /**
     * The Atlas's local declaration with these orders: MegaMek's other firing commands, then Done and Skip as the
     * display enables them (ActionPhaseDisplay.updateDonePanelButtons): with its "nag for no action" ({@code nag})
     * Done with orders and Skip without; else Done alone, always enabled, which then also holds fire.
     */
    private Shot weapons(GpuFireOrders.Snapshot orders, boolean nag) {
        boolean queued = !orders.attacks().isEmpty();
        List<BoardScene.Command> commands = new ArrayList<>(List.of(command("fireFlipArms", "Flip Arms", true),
              command("fireFindClub", "Find Club", false), command(DONE, "Done", queued || !nag)));
        if (nag) {
            commands.add(command(SKIP, "Skip", !queued));
        }
        Shot shot = new Shot(status(GamePhase.FIRING, true, ATLAS, 0), panels(info(), GpuMovePlan.Snapshot.EMPTY,
              orders, GpuPhysicalOptions.Snapshot.EMPTY), commands);
        return nag ? shot : withInfo(shot, DONE, "");
    }

    /**
     * The Atlas's orders as E3b and E3c publish them: {@code attacks} attacks over {@code targets} lettered targets,
     * the read-out's word ("" = Torso) and twist, the twist limits, the own units left to declare (the Atlas
     * included) and those that Resolve phase has left to declare.
     */
    private static GpuFireOrders.Snapshot orders(int attacks, int targets, int twist, String torso, boolean left,
          boolean right, int pending, int autoDeclare) {
        List<Integer> ids = List.of(TIMBER_WOLF, BATTLEMASTER, KING_CRAB);
        List<GpuFireOrders.Target> listed = IntStream.range(0, targets).mapToObj(index -> new GpuFireOrders.Target(
              TargetKey.unit(ids.get(index)), (char) ('A' + index), name(ids.get(index)), index == 0,
              index == 0 ? 0 : 1, true, null)).toList();
        return new GpuFireOrders.Snapshot(true, true, ATLAS,
              new GpuFireOrders.Focus(TargetKey.unit(TIMBER_WOLF), name(TIMBER_WOLF), null), -1, List.of(), listed,
              lasers(attacks, ids.subList(0, Math.max(1, targets))), twist, left, right, torso, null, null, null,
              List.of(), null, Map.of(), pending, autoDeclare, null);
    }

    /** E3c's read-only draft of the own Atlas on another player's turn: its attacks with their rolls, no targets. */
    private static GpuFireOrders.Snapshot draft(int attacks) {
        return new GpuFireOrders.Snapshot(true, false, ATLAS, GpuFireOrders.Focus.NONE, -1, List.of(), List.of(),
              lasers(attacks, List.of(TIMBER_WOLF)), 0, false, false, "", null, null, null, List.of(), null, Map.of(),
              5, 0, null);
    }

    /** {@code count} medium laser attacks, at the targets in turn, each 7+ (58%). */
    private static List<GpuFireOrders.Attack> lasers(int count, List<Integer> targets) {
        return IntStream.range(0, count).mapToObj(index -> new GpuFireOrders.Attack(index,
              TargetKey.unit(targets.get(index % targets.size())), "Medium Laser", "RA", "Energy", "", -1, 7, 58.3,
              "")).toList();
    }

    /** The mock roster's name of a unit, as its card and the dock show it. */
    private static String name(int id) {
        return GpuHudState.unit(MOCK, id).chassis();
    }

    /**
     * Round 3 as the board plays it: {@code shots} weapon attacks, the Atlas's volley of shot 08 first (AC/20, LRM
     * 20, SRM 6 and a medium laser, the second and fourth hitting) and then one each from the other units in turn, a
     * kick of the Atlas when {@code kick}, and two piloting rolls; and the log that lists them, an entry for each.
     * The Atlas fires at a new target each time, so its shots land one after another in their order (the shots of
     * one target pass land on their own jittered clocks).
     */
    private static Round round(int shots, boolean kick) {
        int[] attackers = { WARHAMMER, KING_CRAB, MARAUDER, BATTLEMASTER, PANTHER, TIMBER_WOLF };
        int[] passes = { BATTLEMASTER, TIMBER_WOLF, ENEMY_LOCUST, KING_CRAB };
        List<List<String>> volley = List.of(List.of("AC/20", "RT"), List.of("LRM 20", "LT"), List.of("SRM 6", "LT"),
              List.of("Medium Laser", "LA"));
        List<BoardScene.Animation> events = new ArrayList<>();
        List<GpuReportLog.CombatEvent> combat = new ArrayList<>();
        List<GpuReportLog.Entry> entries = new ArrayList<>();
        for (int index = 0; index < shots + (kick ? 1 : 0); index++) {
            int attacker = index >= shots || index < 4 ? ATLAS : attackers[(index - 4) % attackers.length];
            int target = index >= shots ? TIMBER_WOLF : index < 4 ? passes[index]
                  : attacker == KING_CRAB || attacker == BATTLEMASTER || attacker == TIMBER_WOLF ? ATLAS : KING_CRAB;
            boolean physical = index >= shots;
            ResolvedAttack.Kind kind = physical ? ResolvedAttack.Kind.KICK : ResolvedAttack.Kind.SHOT;
            GamePhase phase = physical ? GamePhase.PHYSICAL : GamePhase.FIRING;
            List<String> weapon = physical ? List.of("", "LL") : index < 4 ? volley.get(index)
                  : List.of("Medium Laser", "LA");
            boolean hit = index % 2 == 1;
            BoardScene.Combat attack = UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(attacker, attacker),
                  UnitPlaybackFixture.unit(target, target), kind, hit);
            events.add(attack);
            combat.add(new GpuReportLog.CombatEvent(attack.result().id(), MOCK.round(), phase, kind, attacker,
                  target, weapon.get(0), weapon.get(1), hit, !hit ? 0 : index == 1 ? 12 : 5, List.of(), null, 0));
            entries.add(new GpuReportLog.Entry(MOCK.round(), "", phase, "", "", List.of(reported(attacker),
                  reported(target)), "", List.of(), physical ? GpuReportLog.Kind.PHYSICAL : GpuReportLog.Kind.WEAPON,
                  attacker, target, attack.result().id(), List.of(), false, false, null, null, null, null, null, null,
                  null, false, false));
        }
        List<GpuReportLog.PsrItem> rolls = List.of(new GpuReportLog.PsrItem(1, MOCK.round(), GamePhase.FIRING,
                    KING_CRAB, 5, 7, true, "took 20+ damage"),
              new GpuReportLog.PsrItem(2, MOCK.round(), GamePhase.FIRING, MARAUDER, 6, 4, false, "was kicked"));
        for (GpuReportLog.PsrItem roll : rolls) {
            entries.add(new GpuReportLog.Entry(MOCK.round(), "", GamePhase.FIRING, "", "",
                  List.of(reported(roll.entityId())), "", List.of(), GpuReportLog.Kind.PSR, Entity.NONE, Entity.NONE,
                  null, List.of(roll.id()), false, false, null, null, null, null, null, null, null, false, false));
        }
        return new Round(new GpuReportLog.Snapshot(MOCK.round(), GamePhase.FIRING_REPORT, entries, Map.of(), combat,
              rolls, List.of(), List.of()), events);
    }

    /** A unit as the report names it. */
    private static GpuReportLog.Unit reported(int id) {
        return new GpuReportLog.Unit(id, name(id));
    }

    /** Shot 08 or 10: weapon or physical playback, or the round's review, of {@code round} as {@code played}. */
    private Shot playback(GamePhase phase, Round round, BiConsumer<GpuPlaybackHistory, Round> played) {
        GpuReportLog.Snapshot log = new GpuReportLog.Snapshot(round.log().round(), phase, round.log().entries(),
              Map.of(), round.log().combat(), round.log().psr(), List.of(), List.of());
        Round shown = new Round(log, round.events());
        return new Shot(status(phase, false, Entity.NONE, 0), panels(info(), GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY), List.of(command(DONE, "Done", true)),
              log, history -> played.accept(history, shown), GpuHudFixtures.preferences());
    }

    /** A first frame primes the history at the round's start; then the round's events arrive. */
    private static void arrive(GpuPlaybackHistory history, Round round) {
        GpuReportLog.Snapshot start = new GpuReportLog.Snapshot(round.log().round(), round.log().phase(), List.of(),
              Map.of(), List.of(), List.of(), List.of(), List.of());
        history.accept(List.of(), SCENE, start, unit -> false);
        history.advance(0, ignored -> true);
        history.accept(round.events(), SCENE, round.log(), unit -> false);
    }

    /**
     * The board presents the round's first {@code count} steps, each as its shot lands (shot 08: the Atlas's volley
     * of four), and the player pauses.
     */
    private static BiConsumer<GpuPlaybackHistory, Round> landed(int count) {
        return (history, round) -> {
            arrive(history, round);
            for (int frame = 0; frame < 6000 && history.played().size() < count; frame++) {
                history.advance(.01, ignored -> true);
            }
            history.togglePaused();
        };
    }

    /** Everything presented at once (Skip to results), the review cursor on the round's second step (shot 10). */
    private static void secondStep(GpuPlaybackHistory history, Round round) {
        arrive(history, round);
        history.skip();
        history.advance(0, ignored -> true);
        history.reviewTo(history.played().get(1));
    }

    /**
     * Shot 09: the Atlas against the adjacent Timber Wolf, both punches blocked by the arms' weapons (or free) and
     * both kicks at 4+, with the piloting rolls MegaMek's shared helper knows for a kick and none for a punch.
     */
    private Shot physicalShot(boolean punches) {
        List<String> kick = List.of("miss \u2192 Atlas PSR 4+", "hit \u2192 Timber Wolf PSR 4+");
        List<GpuPhysicalOptions.Option> options = List.of(
              option("punchLeft", "Punch L", "L", punches, "LA fired a weapon this turn", 10, "Punch table", true,
                    List.of()),
              option("punchRight", "Punch R", "R", punches, "RA fired a weapon this turn", 10, "Punch table", true,
                    List.of()),
              option("kickLeft", "Kick L", "L", true, "", 20, "Kick table", false, kick),
              option("kickRight", "Kick R", "R", true, "", 20, "Kick table", false, kick));
        return physicalTurn(options, List.of(TIMBER_WOLF), List.of(command(DONE, "Done", true),
              command(SKIP, "Skip", true)));
    }

    /** A quad Mek's options: no punch (MegaMek's reason), and its four kicks. */
    private Shot quad() {
        List<GpuPhysicalOptions.Option> options = List.of(
              option("punchLeft", "Punch L", "L", false, "Attacker is a quad", 0, "", true, List.of()),
              option("punchRight", "Punch R", "R", false, "Attacker is a quad", 0, "", true, List.of()),
              option("kickLeft", "Kick L", "L", true, "", 14, "Kick table", false, List.of()),
              option("kickRight", "Kick R", "R", true, "", 14, "Kick table", false, List.of()),
              option("muleKickRight", "Mule kick R", "R", true, "", 14, "Rear Kick table", false, List.of()),
              option("muleKickLeft", "Mule kick L", "L", true, "", 14, "Rear Kick table", false, List.of()));
        return physicalTurn(options, List.of(TIMBER_WOLF), physicalCommands());
    }

    private Shot physicalTurn(List<GpuPhysicalOptions.Option> options, List<Integer> adjacent,
          List<BoardScene.Command> commands) {
        GpuPhysicalOptions.Snapshot attacks = new GpuPhysicalOptions.Snapshot(true, ATLAS,
              adjacent.isEmpty() ? Entity.NONE : adjacent.getFirst(), adjacent, options);
        return new Shot(status(GamePhase.PHYSICAL, true, ATLAS, 0), panels(info(), GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, attacks), commands);
    }

    private static GpuPhysicalOptions.Option option(String id, String label, String limb, boolean available,
          String reason, int damage, String table, boolean combinable, List<String> consequences) {
        return new GpuPhysicalOptions.Option(id, label, limb, available, available ? "" : reason, 4, 92, damage,
              table, consequences, combinable);
    }

    /** The physical turn of a unit with no adjacent identified enemy (A.9 I7), which could dodge. */
    private Shot noPhysical() {
        return physicalTurn(List.of(), List.of(), physicalCommands());
    }

    /** The physical display's buttons as the scene carries them, the attacks among them, then Done and Skip. */
    private List<BoardScene.Command> physicalCommands() {
        return List.of(command("punch", "Punch", false), command("kick", "Kick", false),
              command("dodge", "Dodge", true), command("explosives", "Lay explosives", false),
              command(DONE, "Done", false), command(SKIP, "Skip", true));
    }

    /** A generic phase: deployment with MegaMek's own buttons, some unavailable, and its two status lines. */
    private Shot deployment() {
        List<BoardScene.Command> commands = List.of(command("deployTurn", "Turn", true),
              command("deployLoad", "Load", false), command("deployUnload", "Unload", true),
              command("deployRemove", "Remove", true), command("deployAssaultDrop", "Assault drop", false),
              command("deployDock", "Dock", true), command("deployClimb", "Climb mode", true),
              command("deployHullDown", "Hull down", false), command(DONE, "Deploy", true));
        GpuBoardActions.PhaseInfo info = new GpuBoardActions.PhaseInfo("Deploy the Atlas\nSelect a hex to deploy",
              false, DONE, "", "", List.of(), List.of());
        return new Shot(status(GamePhase.DEPLOYMENT, true, ATLAS, 0), panels(info, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY), commands);
    }

    /** Movement without the planner (A.7 G20): MegaMek's own movement commands, Done and Skip. */
    private Shot nonPlanner() {
        List<BoardScene.Command> commands = List.of(command("MoveAccelerate", "Accelerate", true),
              command("MoveDecelerate", "Decelerate", true), command("MoveTurnLeft", "Turn Left", true),
              command("MoveTurnRight", "Turn Right", true), command("MoveThrust", "Thrust", true),
              command("MoveYaw", "Yaw", false), command("MoveRoll", "Roll", true), command(DONE, "Move", true),
              command(SKIP, "Skip", true));
        GpuBoardActions.PhaseInfo info = new GpuBoardActions.PhaseInfo("Plot the Atlas's vector", false, DONE, SKIP,
              "", List.of(), List.of());
        return new Shot(status(GamePhase.MOVEMENT, true, ATLAS, 0), panels(info, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY), commands);
    }

    /** The shot with the turn-details preference on and two steps of the local turn. */
    private static Shot withSteps(Shot shot) {
        GpuHudData panels = shot.panels();
        GpuBoardActions.PhaseInfo info = new GpuBoardActions.PhaseInfo("", false, DONE, SKIP, "",
              List.of("Walk 1 hex (1 MP)", "Walk 1 hex (2 MP)"), List.of());
        return new Shot(shot.status(), withPhase(panels, info), shot.commands(), shot.reports(), shot.playback(),
              GpuHudFixtures.with(GpuHudFixtures.preferences(), Map.of("turnDetails", true)));
    }

    /** The shot whose phase info names these Done and skip commands, as GpuBoardActions.phaseInfo derives them. */
    private static Shot withInfo(Shot shot, String doneId, String skipId) {
        GpuBoardActions.PhaseInfo info = shot.panels().phase();
        return new Shot(shot.status(), withPhase(shot.panels(), new GpuBoardActions.PhaseInfo(info.status(),
              info.blocking(), doneId, skipId, info.clearId(), info.turnDetails(), info.conditions())),
              shot.commands(), shot.reports(), shot.playback(), shot.preferences());
    }

    private static GpuHudData withPhase(GpuHudData panels, GpuBoardActions.PhaseInfo info) {
        return new GpuHudData(info, panels.move(), panels.fire(), panels.physical(), panels.record(),
              panels.preview(), panels.chat(), panels.toasts(), panels.los(), panels.players());
    }

    /** The movement display's buttons as the scene carries them, the ones More lists among them. */
    private List<BoardScene.Command> moveCommands() {
        return List.of(command("moveNext", "Next Unit", true), command("moveWalk", "Walk", true),
              command("moveJump", "Jump", true), command("moveBackUp", "Back Up", true),
              command("moveTurn", "Turn", true), command("moveGoProne", "Go Prone", true),
              command("moveHullDown", "Hull Down", false), command(DONE, "Done", true), command(SKIP, "Skip", true),
              command(GpuBoardActions.CLEAR_ID, "Clear", true));
    }

    /**
     * The shot with MegaMek's Get Up, available while the unit, or its plan's end, is down; the Atlas's board label
     * then says it is prone.
     */
    private Shot withGetUp(Shot shot, boolean down) {
        List<BoardScene.Command> commands = new ArrayList<>(shot.commands());
        commands.add(command(GET_UP, "Get Up", down));
        GpuBattleStatus.Snapshot status = shot.status();
        if (down) {
            List<UnitStatusWords.StatusWord> prone = List.of(new UnitStatusWords.StatusWord("PRONE", "PRONE",
                  UnitStatusWords.Severity.CAUTION));
            status = new GpuBattleStatus.Snapshot(status.round(), status.phase(), status.myTurn(),
                  status.localPlayerId(), status.actorId(), status.turns(), status.turnIndex(), status.units().stream()
                  .map(unit -> unit.id() == ATLAS ? prone(unit, prone) : unit).toList(), status.initiative(),
                  status.turnOrderHidden(), status.links(), status.arc());
        }
        return new Shot(status, shot.panels(), commands);
    }

    private static GpuBattleStatus.UnitStatus prone(GpuBattleStatus.UnitStatus unit,
          List<UnitStatusWords.StatusWord> words) {
        return new GpuBattleStatus.UnitStatus(unit.id(), unit.side(), unit.sensorContact(), unit.name(),
              unit.chassis(), unit.model(), unit.tons(), unit.weightClass(), unit.formation(), unit.pilot(),
              unit.gunnery(), unit.piloting(), unit.armor(), unit.structure(), unit.heat(), unit.heatRgb(),
              unit.heatCapacity(), unit.walk(), unit.run(), unit.jump(), unit.moved(), unit.mpUsed(),
              unit.hexesMoved(), unit.facing(), unit.tmm(), unit.canActNow(), unit.pending(), unit.done(),
              unit.destroyed(), unit.damageLevel(), unit.destroyedLocations(), unit.heatEffects(), unit.position(),
              unit.boardId(), unit.icon(), words, unit.weightClassIndex(), unit.declaredAttacks(), unit.ownerId(),
              unit.statusTiles());
    }

    private BoardScene.Command command(String id, String label, boolean enabled) {
        return new BoardScene.Command(id, label, "", enabled, false, List.of(), () -> ran.add(id));
    }

    private static GpuBoardActions.PhaseInfo info() {
        return new GpuBoardActions.PhaseInfo("", false, DONE, SKIP, GpuBoardActions.CLEAR_ID, List.of(), List.of());
    }

    private static GpuHudData panels(GpuBoardActions.PhaseInfo info, GpuMovePlan.Snapshot move,
          GpuFireOrders.Snapshot orders, GpuPhysicalOptions.Snapshot attacks) {
        return new GpuHudData(info, move, orders, attacks, GpuUnitRecord.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE,
              GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
    }

    /** The mock roster's round 3 in another phase and turn. */
    private static GpuBattleStatus.Snapshot status(GamePhase phase, boolean myTurn, int actor, int turnIndex) {
        return new GpuBattleStatus.Snapshot(MOCK.round(), phase, myTurn, MOCK.localPlayerId(), actor, MOCK.turns(),
              turnIndex, MOCK.units(), List.of(), false);
    }

    // ------------------------------------------------------------------ harness

    /**
     * A dock over the mocked services with its own HUD state and the context menu GpuHud builds before it; the
     * round's playback history has played what the shot says.
     */
    private Dock dock(GpuHudTestStage hud, Shot shot) {
        Dock dock = dock(hud, source);
        shot.playback().accept(dock.state().history);
        return dock;
    }

    /** A dock over that source, with its own HUD state and the context menu GpuHud builds before it. */
    private static Dock dock(GpuHudTestStage hud, GpuBoardSource source) {
        GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        BoardCamera camera = new BoardCamera();
        GpuContextMenu menu = new GpuContextMenu(hud.kit, source, state, id -> { });
        UiButton chat = new GpuChatPanel(hud.kit, source, state).button();
        return new Dock(new GpuCommandDock(hud.kit, source, state, camera, menu, chat), state, camera, menu);
    }

    /**
     * The real source's frame once its capture saw the last change and the events it asked for ran (a draft given
     * back, a declaration), shown with the mock roster's {@code status} as the native window presents it: the fire
     * orders, the phase info and MegaMek's own firing commands.
     */
    private static Shot settled(GpuFiringFixture firing, GpuBattleStatus.Snapshot status) throws Exception {
        GpuBoardSource source = firing.board.source;
        for (int pass = 0; pass < 3; pass++) {
            onSwing(() -> {
                source.refresh();
                return null;
            });
            for (int event = 0; event < 3; event++) {
                onSwing(() -> null);
            }
        }
        GpuBoardSource.Frame frame = onSwing(() -> {
            source.refresh();
            return source.takeFrame();
        });
        return new Shot(status, frame.panels(), frame.scene().commands());
    }

    /** Renders the shot in a new dock at 1920 x 1080, captures {name}.png and writes it beside the mock's dock. */
    private void shoot(GpuHudTestStage hud, String name, Shot shot, String mock, List<Shot> layouts) {
        hud.size(1920, 1080);
        Dock dock = dock(hud, shot);
        show(hud, dock, shot);
        compare(hud, dock, name, mock);
        layouts.add(shot);
    }

    /** Captures {name}.png and the same-size area of the mock whose dock also ends 30 units above the bottom. */
    private static void compare(GpuHudTestStage hud, Dock dock, String name, String mock) {
        Pixmap image = hud.capture(name);
        try {
            Rectangle area = GpuHudTestStage.bounds(dock.dock().actor());
            hud.compare(name, image, dock.dock().actor(), mock, Math.round(area.x),
                  Math.round(hud.height() - area.y - area.height));
        } finally {
            image.dispose();
        }
    }

    /**
     * One frame as GpuHud shows it: the HUD state takes the status, the dock and the context menu update, and the
     * dock's slot is placed as GpuHud.layout places it, with the width it gives the dock and by the height the dock
     * reports in that frame. Then the window is drawn, the context menu's window-wide slot above the dock.
     */
    private static void show(GpuHudTestStage hud, Dock dock, Shot shot) {
        GpuHud.Inputs inputs = inputs(hud, dock, shot);
        GpuHud.Metrics metrics = inputs.metrics();
        Actor focus = hud.stage.getKeyboardFocus();
        hud.window.clearChildren();
        Container<Actor> slot = new Container<>(dock.dock().actor()).bottom().fillX();
        hud.window.addActor(slot);
        Actor drawer = dock.drawer();
        hud.window.addActor(drawer);
        Actor menu = dock.menu().actor();
        menu.setBounds(0, 0, hud.width(), hud.height());
        hud.window.addActor(menu);
        hud.window.addActor(dock.dock().confirmation().actor());
        // Unlike the real HUD, this harness reattaches its actors each frame. Preserve their existing focus.
        if (focus != null && focus.getStage() == hud.stage) {
            hud.stage.setKeyboardFocus(focus);
        }
        float[] band = band(metrics, dock.state());
        float width = Math.min(metrics.dock(), band[1] - band[0]);
        float x = MathUtils.clamp((metrics.width() - width) / 2, band[0], band[1] - width);
        dock.dock().update(inputs);
        dock.menu().update(inputs);
        slot.setBounds(x, metrics.gap(), width, slot.getPrefHeight());
        slot.validate();
        // This component harness gives the drawer two columns beside the dock. GpuHudInputTest checks the HUD's
        // actual placement and overflow at narrow and wide window sizes.
        drawer.setBounds(slot.getX() + slot.getWidth() + HINT_GAP, slot.getY(), GpuMoreDrawer.width(2),
              Math.max(slot.getHeight(), dock.dock().drawer().height(2)));
        drawer.act(.2f);
        dock.dock().layoutConfirmation(hud.width(), hud.height());
        hud.draw();
    }

    /** The frame of the shot as GpuHud hands it on, after the HUD state took its status. */
    private static GpuHud.Inputs inputs(GpuHudTestStage hud, Dock dock, Shot shot) {
        // The dock reads only the scene's phase commands.
        BoardScene scene = mock(BoardScene.class);
        when(scene.commands()).thenReturn(shot.commands());
        GpuBoardSource.Frame frame = new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "",
              null, shot.reports(), shot.status(), shot.panels());
        boolean busy = dock.state().history.running();
        dock.state().update(shot.status(), GpuUnitRecord.Snapshot.EMPTY, busy);
        return new GpuHud.Inputs(frame, new GpuHud.HudView(false, busy, Map.of(), Map.of(), Map.of(), null,
              Entity.NONE, 0), null, shot.preferences(), GpuHud.Metrics.of(hud.width(), hud.height()), List.of());
    }

    /** GpuHud's middle band between the left column and the right column (the log's width while it is open). */
    private static float[] band(GpuHud.Metrics metrics, GpuHudState state) {
        float right = state.logOpen() ? metrics.log() : metrics.right();
        return new float[] { 2 * metrics.gap() + metrics.left(), metrics.width() - 2 * metrics.gap() - right };
    }

    /** The slots the dock must keep clear of (y up): both columns, and the hint line where it shows. */
    private static Rectangle[] neighbours(GpuHudTestStage hud, Dock dock) {
        GpuHud.Metrics metrics = GpuHud.Metrics.of(hud.width(), hud.height());
        float gap = metrics.gap();
        GpuHudState state = dock.state();
        float right = state.logOpen() ? metrics.log() : metrics.right();
        float[] band = band(metrics, state);
        List<Rectangle> slots = new ArrayList<>(List.of(
              new Rectangle(gap, gap, metrics.left(), metrics.height() - 2 * gap),
              new Rectangle(metrics.width() - gap - right, gap, right, metrics.height() - 2 * gap)));
        if (!metrics.narrow()) {
            slots.add(new Rectangle(band[0], gap + dock.root().getHeight() + HINT_GAP,
                  band[1] - band[0], HINT_HEIGHT));
        }
        return slots.toArray(Rectangle[]::new);
    }

    /**
     * Clicks each named actor of the dock in turn and shows the shot again, with the pointer moved off the dock so
     * that a pressed button shows its pressed look rather than the hover look that outranks it.
     */
    private static void clickAndShow(GpuHudTestStage hud, Dock dock, Shot shot, String... names) {
        for (String name : names) {
            click(hud, dock.find(name));
            hud.stage.mouseMoved(0, 0);
            show(hud, dock, shot);
        }
    }

    /** A press and release at the actor's centre through the stage, as the board view hands HUD input on. */
    private static void click(GpuHudTestStage hud, Actor actor) {
        assertNotNull(actor);
        Vector2 point = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    /** The text of the dock's label with this name. */
    private static String label(Dock dock, String name) {
        Label label = dock.find(name);
        assertNotNull(label, name);
        return label.getText().toString();
    }

    private static UiButton button(Dock dock, String name) {
        UiButton button = dock.find(name);
        assertNotNull(button, name);
        return button;
    }

    /** The names under {@code actor} that start with {@code prefix}, in their order. */
    private static List<String> names(Actor actor, String prefix) {
        List<String> names = new ArrayList<>();
        if (actor.getName() != null && actor.getName().startsWith(prefix)) {
            names.add(actor.getName());
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> names.addAll(names(child, prefix)));
        }
        return names;
    }

    /**
     * The texts of the visible, non-empty labels under {@code actor}, button captions and details included, top to
     * bottom: a table's in its cells' order (an open popover re-adds a header it hid at the end of its children).
     */
    private static List<String> texts(Actor actor) {
        if (actor == null || !actor.isVisible()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        if (actor instanceof Label label && label.getText().length() > 0) {
            texts.add(label.getText().toString());
        } else if (actor instanceof Table table && table.getCells().notEmpty()) {
            table.getCells().forEach(cell -> texts.addAll(texts(cell.getActor())));
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> texts.addAll(texts(child)));
        }
        return texts;
    }
}
