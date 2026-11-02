/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.BasementType;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BuildingTerrain;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.IAero;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Movement clicks into occupied hexes: the picks the 3D board hands the HUD
 * ({@code GpuBattleView.touchUp}: the hex and the unit picked there) go through the real GpuHud routing into the real
 * GpuBoardSource, GpuMovePlan and MovementDisplay. In the planning turn the end destination wins: a click on a hex in the
 * plan's envelope plans as in an empty hex, on a unit's model as on the ground; elsewhere a click selects or inspects,
 * repeated clicks on a stack take its units in turn, and the unit menus start with a Select or Inspect item for every
 * unit of the hex the real source's scene draws. No GL context: the HUD runs on mocked Gdx statics as GpuHudInputTest
 * does, and only the source's movement commands reach the real source.
 */
@Timeout(300)
class GpuMoveClickRoutingTest {
    private static final int LOCAL = 0;
    private static final int OPPONENT = 1;
    private static final int ATLAS = 1;
    private static final int SAGITTAIRE = 2;
    private static final int ELEMENTALS = 20;
    private static final int PLATOON = 21;
    private static final int BULLDOG = 25;
    private static final int ARCHER = 30;
    private static final int ENEMY_PLATOON = 31;
    private static final int UNION = 40;
    private static final int MOWANG = 41;
    private static File originalDataDir;

    private Application application;
    private com.badlogic.gdx.Files files;
    private Graphics graphics;
    private GL20 gl;
    private GL20 gl20;
    private Input input;
    private GpuBoardSkin theme;
    private GpuHud hud;
    private final GpuBoardSource hudSource = mock(GpuBoardSource.class);
    private GpuMovementFixture moving;
    /** The charge and DFA confirmations asked: the title and the path at that moment; each is answered No. */
    private final List<String> dialogs = new ArrayList<>();

    @BeforeAll
    static void setUpAll() {
        GdxNativesLoader.load();
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    @BeforeEach
    void setUp() throws Exception {
        application = Gdx.app;
        files = Gdx.files;
        graphics = Gdx.graphics;
        gl = Gdx.gl;
        gl20 = Gdx.gl20;
        input = Gdx.input;
        Gdx.app = mock(Application.class);
        Gdx.files = mock(com.badlogic.gdx.Files.class);
        when(Gdx.files.classpath(anyString())).thenAnswer(call -> new Lwjgl3Files().classpath(call.getArgument(0)));
        Gdx.graphics = mock(Graphics.class);
        Gdx.gl = mock(GL20.class);
        AtomicInteger textures = new AtomicInteger();
        when(Gdx.gl.glGenTexture()).thenAnswer(call -> textures.incrementAndGet());
        Gdx.gl20 = Gdx.gl;
        Gdx.input = mock(Input.class);

        moving = GpuMovementFixture.create();
        GpuBoardSource real = moving.board.source;
        // The HUD's movement-phase calls reach the real source; nothing else is needed for a board click.
        when(hudSource.moves()).thenAnswer(call -> real.moves());
        doAnswer(call -> {
            real.selectUnit(call.getArgument(0));
            return null;
        }).when(hudSource).selectUnit(anyInt());
        doAnswer(call -> {
            real.hover(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(hudSource).hover(any(), anyInt());
        doAnswer(call -> {
            real.click(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
        }).when(hudSource).click(any(), anyBoolean(), anyInt());
        when(hudSource.fire()).thenReturn(mock(GpuFireOrders.class));
        when(hudSource.physical()).thenReturn(mock(GpuPhysicalOptions.class));
        when(hudSource.record()).thenReturn(mock(GpuUnitRecord.class));
        theme = new GpuBoardSkin();
        hud = new GpuHud(hudSource, theme.skin, mock(Batch.class), new BoardCamera(), mock(GpuBoardTuning.class),
              new GpuPlaybackHistory(new UnitPlayback()));
        hud.resize(1920, 1080, 1);

        doAnswer(call -> {
            dialogs.add(call.getArgument(0) + " " + steps(moving.display.getPlannedMovement()));
            return false;
        }).when(moving.gui).doYesNoDialog(anyString(), anyString());
        // MapMenu's charge and DFA click the hex on the board of their location.
        when(moving.gui.getBoardState(any(BoardLocation.class))).thenReturn(moving.board.view);
        scriptTurns();
        moving.board.panel = moving.display;
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (hud != null) {
                hud.dispose();
            }
            if (theme != null) {
                theme.dispose();
            }
            if (moving != null) {
                moving.close();
            }
        } finally {
            Gdx.app = application;
            Gdx.files = files;
            Gdx.graphics = graphics;
            Gdx.gl = gl;
            Gdx.gl20 = gl20;
            Gdx.input = input;
        }
    }

    /**
     * Battle armor in jump mode, two hexes from the own Sagittaire and next to an enemy Archer, both in its envelope:
     * a click on the Sagittaire's model plans the jump into the Mek's hex, as the Archer's model or the ground does,
     * and the hover shows that (no unit, so the move cursor and the route preview). The menu still reaches both units.
     */
    @Test
    void battleArmorJumpsIntoOccupiedHexesByClickingTheirModels() throws Exception {
        Coords baHex = new Coords(11, 9);
        Coords sagHex = GpuMovementFixture.START;
        Coords archerHex = baHex.translated(1);
        place(moving.board.entity, baHex.translated(3));
        addUnit("Elemental BA [Laser] (Sqd5).blk", ELEMENTALS, LOCAL, baHex);
        addUnit("Archer ARC-2R.mtf", ARCHER, OPPONENT, archerHex);

        selectAndMode(ELEMENTALS, GpuMovePlan.Mode.JUMP);
        assertTrue(snapshot().envelope().containsKey(sagHex), "the rules let the battle armor land with the Mek");
        assertEquals(Entity.NONE, hud.boardUnit(sagHex, SAGITTAIRE), "the model plans: no hovered unit");
        assertEquals(ELEMENTALS, hud.boardUnit(baHex, ELEMENTALS), "the actor's own hex stays its own");
        onSwing(() -> {
            moving.board.source.setHover(sagHex, Float.NaN);
            return null;
        });
        snapshot();
        assertEquals(sagHex, snapshot().hover().getLast().coords(), "the route preview over the model");
        onSwing(() -> {
            moving.board.source.setHover(null, Float.NaN);
            return null;
        });

        click(sagHex, SAGITTAIRE, 0, Float.NaN);
        assertPlanned(ELEMENTALS, sagHex, EntityMovementType.MOVE_JUMP);
        verify(hudSource, never()).selectUnit(anyInt());

        selectAndMode(ELEMENTALS, GpuMovePlan.Mode.JUMP);
        click(archerHex, ARCHER, 0, Float.NaN);
        assertPlanned(ELEMENTALS, archerHex, EntityMovementType.MOVE_JUMP);
        assertEquals(Entity.NONE, hud.state.inspected, "planning into the enemy's hex inspects nothing");

        selectAndMode(ELEMENTALS, GpuMovePlan.Mode.JUMP);
        click(archerHex, ARCHER, 0, ground(archerHex));
        assertPlanned(ELEMENTALS, archerHex, EntityMovementType.MOVE_JUMP);

        // The envelope is the actor's board's: the same hex on another board picks its unit.
        BoardScene otherBoard = mock(BoardScene.class);
        when(otherBoard.boardId()).thenReturn(1);
        hud.update(frame(null).withScene(otherBoard), GpuHud.HudView.EMPTY, null, GpuHudInputTest.preferences());
        assertEquals(SAGITTAIRE, hud.boardUnit(sagHex, SAGITTAIRE), "another board's hex");

        // Inside the envelope the menu inspects the Archer and selects the Sagittaire.
        selectAndMode(ELEMENTALS, GpuMovePlan.Mode.JUMP);
        menuItem(archerHex, ARCHER, inspect(ARCHER));
        assertEquals(List.of(ARCHER, ELEMENTALS), List.of(hud.state.inspected, actor()));
        assertEquals("[START_JUMP@11,9]", onSwing(() -> steps(moving.display.getPlannedMovement())),
              "inspecting leaves the plan");
        menuItem(sagHex, SAGITTAIRE, select(SAGITTAIRE));
        assertEquals(SAGITTAIRE, actor(), "the menu switches to the own unit");
    }

    /**
     * A Mek may end in an own tank's hex: a click on the tank's model plans the move there. Hexes holding a Mek stay
     * outside the Mek's envelope, so there a click selects or inspects as before, and a Ctrl+click that the rules cut
     * there says the hex is occupied.
     */
    @Test
    void aMekPlansIntoATanksHexButNotIntoAMeksHex() throws Exception {
        Coords tankHex = GpuMovementFixture.START.translated(0);
        Coords atlasHex = tankHex.translated(0);
        Coords archerHex = new Coords(12, 10);
        addUnit("Bulldog Medium Tank.blk", BULLDOG, LOCAL, tankHex);
        place(moving.board.entity, atlasHex);
        addUnit("Archer ARC-2R.mtf", ARCHER, OPPONENT, archerHex);

        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        assertEquals(Entity.NONE, hud.boardUnit(tankHex, BULLDOG), "the tank's model plans");
        click(tankHex, BULLDOG, 0, Float.NaN);
        assertPlanned(SAGITTAIRE, tankHex, EntityMovementType.MOVE_WALK);
        verify(hudSource, never()).selectUnit(anyInt());

        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        assertFalse(snapshot().envelope().containsKey(archerHex), "a walking Mek cannot enter an enemy Mek's hex");
        assertEquals(ARCHER, hud.boardUnit(archerHex, ARCHER), "outside the envelope the hex picks its unit");
        click(archerHex, ARCHER, 0, ground(archerHex));
        assertEquals(ARCHER, hud.state.inspected);
        assertEquals("[]", onSwing(() -> steps(moving.display.getPlannedMovement())));

        // Ctrl pins a waypoint on a unit too: the rules cut the walk where it would enter the enemy Mek's hex.
        click(archerHex, ARCHER, InputEvent.CTRL_DOWN_MASK, Float.NaN);
        verify(moving.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.move.hexOccupied",
              archerHex.getBoardNum()));
        verify(moving.gui, never()).addToast(ToastLevel.WARNING,
              Messages.getString("GpuBoard.hud.move.beyondRunningMp"));

        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        click(atlasHex, ATLAS, 0, ground(atlasHex));
        assertEquals(ATLAS, actor(), "a Mek's hex is no end hex for a Mek: a click there selects the own Atlas");
    }

    /**
     * Repeated clicks on a stack take its units in turn: two own units switch the acting unit back and forth, and an
     * enemy stack is inspected unit by unit without changing the actor or the plan, whichever model the pick hit. Where
     * the stack is a legal end position of the acting unit's mode, the click plans the move there instead.
     */
    @Test
    void repeatedClicksOnAStackTakeItsUnitsInTurnUnlessItIsADestination() throws Exception {
        Coords baHex = new Coords(11, 9);
        Coords enemyStack = baHex.translated(1);
        Coords ownStack = new Coords(5, 5);
        addUnit("Elemental BA [Laser] (Sqd5).blk", ELEMENTALS, LOCAL, baHex);
        addUnit("Bulldog Medium Tank.blk", BULLDOG, LOCAL, ownStack);
        addUnit("Archer ARC-2R.mtf", ARCHER, OPPONENT, enemyStack);
        addUnit("Foot Platoon (AFFS) (Laser 3067+).blk", ENEMY_PLATOON, OPPONENT, enemyStack);
        assertEquals(ownStack, onSwing(moving.board.entity::getPosition), "the Atlas stands with the Bulldog");

        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        assertFalse(snapshot().envelope().containsKey(ownStack), "two own units fill the hex");
        click(ownStack, ATLAS, 0, Float.NaN);
        assertEquals(ATLAS, actor(), "the picked unit first");
        click(ownStack, ATLAS, 0, Float.NaN);
        assertEquals(BULLDOG, actor(), "the next click: the other unit acts");
        click(ownStack, BULLDOG, 0, Float.NaN);
        assertEquals(ATLAS, actor());

        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        assertFalse(snapshot().envelope().containsKey(enemyStack), "a walking Mek cannot enter an enemy Mek's hex");
        List<Integer> inspected = new ArrayList<>();
        for (int count = 0; count < 3; count++) {
            click(enemyStack, ARCHER, 0, Float.NaN);
            inspected.add(hud.state.inspected);
        }
        assertEquals(List.of(ARCHER, ENEMY_PLATOON, ARCHER), inspected);
        assertEquals(SAGITTAIRE, actor(), "inspecting keeps the actor");
        assertEquals("[]", onSwing(() -> steps(moving.display.getPlannedMovement())), "and plans nothing");

        selectAndMode(ELEMENTALS, GpuMovePlan.Mode.JUMP);
        assertTrue(snapshot().envelope().containsKey(enemyStack), "the battle armor may jump into the stack");
        hud.state.inspected = ARCHER;
        click(enemyStack, ARCHER, 0, Float.NaN);
        assertPlanned(ELEMENTALS, enemyStack, EntityMovementType.MOVE_JUMP);
        assertEquals(ARCHER, hud.state.inspected, "the destination wins over the next unit");
    }

    /**
     * A stack's shared menu lists each unit once per action: Select switches the acting unit, and View inspects the
     * unit while keeping the actor and the plan.
     */
    @Test
    void aStacksMenuStartsWithAnItemPerUnit() throws Exception {
        Coords enemyStack = new Coords(11, 9);
        Coords ownStack = new Coords(5, 5);
        addUnit("Bulldog Medium Tank.blk", BULLDOG, LOCAL, ownStack);
        addUnit("Archer ARC-2R.mtf", ARCHER, OPPONENT, enemyStack);
        addUnit("Foot Platoon (AFFS) (Laser 3067+).blk", ENEMY_PLATOON, OPPONENT, enemyStack);

        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        List<String> rows = unitMenu(enemyStack, ARCHER, GamePhase.MOVEMENT);
        assertTrue(rows.containsAll(List.of(inspect(ARCHER), inspect(ENEMY_PLATOON))), rows.toString());
        assertEquals(1, Collections.frequency(rows, inspect(ARCHER)), rows.toString());
        menuItem(enemyStack, ENEMY_PLATOON, inspect(ENEMY_PLATOON));
        assertEquals(List.of(ENEMY_PLATOON, SAGITTAIRE), List.of(hud.state.inspected, actor()));
        verify(hudSource, never()).selectUnit(anyInt());

        rows = unitMenu(ownStack, ATLAS, GamePhase.MOVEMENT);
        assertEquals(Set.of(select(ATLAS), select(BULLDOG)), Set.copyOf(rows.subList(0, 2)), rows.toString());
        assertEquals(1, Collections.frequency(rows, select(ATLAS)), rows.toString());
        menuItem(ownStack, ATLAS, select(BULLDOG));
        assertEquals(BULLDOG, actor());

        // In another player's turn no own unit may act: the menu inspects them.
        rows = unitMenu(ownStack, ATLAS, GamePhase.FIRING);
        assertTrue(rows.containsAll(List.of(inspect(ATLAS), inspect(BULLDOG))), rows.toString());
    }

    /**
     * The occupied toast names only a destination the route was cut entering because a unit stands there, within the
     * MP. A route the MP cut on the way, through an occupied hex, or where it enters an occupied destination keeps the
     * MP toast. The Sagittaire (run 5) walks north along a clear column.
     */
    @Test
    void theOccupiedToastNamesOnlyAnOccupiedDestinationWithinTheMp() throws Exception {
        Coords far = GpuMovementFixture.START.translated(0, 8);
        onSwing(() -> {
            for (int distance = 0; distance <= 8; distance++) {
                moving.board.game.getBoard().setHex(GpuMovementFixture.START.translated(0, distance), new Hex(0));
            }
            return null;
        });
        String beyond = Messages.getString("GpuBoard.hud.move.beyondRunningMp");
        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        click(far, Entity.NONE, 0, ground(far));
        verify(moving.gui).addToast(ToastLevel.WARNING, beyond);
        Coords reached = onSwing(() -> moving.display.getPlannedMovement().getFinalCoords());
        assertEquals(GpuMovementFixture.START.translated(0, 5), reached, "run MP along the column");
        Coords next = reached.translated(0);
        String nextOccupied = Messages.getString("GpuBoard.hud.move.hexOccupied", next.getBoardNum());

        // The MP run out entering an own tank's hex on the way.
        Entity tank = addUnit("Bulldog Medium Tank.blk", BULLDOG, LOCAL, next);
        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        click(far, Entity.NONE, 0, ground(far));
        verify(moving.gui).addToast(ToastLevel.WARNING, beyond);
        verify(moving.gui, never()).addToast(ToastLevel.WARNING, nextOccupied);

        // The MP run out entering the own Atlas's hex, the destination.
        place(tank, new Coords(3, 3));
        place(moving.board.entity, next);
        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        click(next, ATLAS, InputEvent.CTRL_DOWN_MASK, Float.NaN);
        verify(moving.gui).addToast(ToastLevel.WARNING, beyond);
        verify(moving.gui, never()).addToast(ToastLevel.WARNING, nextOccupied);

        // Within the MP a Mek may not end in the own Atlas's hex.
        place(moving.board.entity, reached);
        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        click(reached, ATLAS, InputEvent.CTRL_DOWN_MASK, Float.NaN);
        verify(moving.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.move.hexOccupied",
              reached.getBoardNum()));
        verify(moving.gui, never()).addToast(ToastLevel.WARNING, beyond);
    }

    /**
     * Foot infantry inside a building, an own platoon on the ground floor of the next building hex: the pointed ground
     * floor plans the move to that floor, as in an empty hex, and so does the platoon's model, at MegaMek's own floor.
     */
    @Test
    void infantryPlansToThePointedFloorOfAnOccupiedBuildingHex() throws Exception {
        Coords next = GpuMovementFixture.START.translated(0);
        onSwing(() -> {
            var board = moving.board.game.getBoard();
            for (Coords coords : List.of(GpuMovementFixture.START, next)) {
                board.setHex(coords, new Hex(0, "building:2;bldg_elev:3;bldg_cf:120;bldg_basement_type:1", ""));
            }
            for (Coords coords : List.of(GpuMovementFixture.START, next)) {
                if (board.getBuildingAt(coords) == null) {
                    board.addBuildingToBoard(new BuildingTerrain(coords, board, Terrains.BUILDING, BasementType.NONE));
                }
            }
            moving.unit.setPosition(new Coords(14, 14));
            return null;
        });
        Entity mover = addUnit("Foot Platoon (AFFS) (Laser 3067+).blk", PLATOON, LOCAL, GpuMovementFixture.START);
        Entity other = addUnit("Foot Platoon (AFFS) (Laser 3067+).blk", PLATOON + 1, LOCAL, next);
        onSwing(() -> {
            mover.setOriginalWalkMP(3);
            mover.setElevation(0);
            other.setElevation(0);
            return null;
        });

        selectAndMode(PLATOON, GpuMovePlan.Mode.AUTO);
        assertTrue(snapshot().envelope().containsKey(next));
        // pickSelection gives the platoon for its own floor: the ground floor the pointer hit.
        click(next, PLATOON + 1, 0, floorZ(0));
        assertPlanned(PLATOON, next, EntityMovementType.MOVE_WALK);
        assertEquals(0, (int) onSwing(() -> moving.display.getPlannedMovement().getFinalElevation()));
        assertEquals(Entity.NONE, hud.state.inspected);

        selectAndMode(PLATOON, GpuMovePlan.Mode.AUTO);
        click(next, PLATOON + 1, 0, Float.NaN);
        assertPlanned(PLATOON, next, EntityMovementType.MOVE_WALK);
        verify(hudSource, never()).selectUnit(anyInt());
    }

    /**
     * A click on a transport the Mek can board, its model as well, plans the path beside it as MegaMek's click does,
     * so the Mount command boards it, without a toast; the click does not select the DropShip.
     */
    @Test
    void aClickOnAMountableTransportPlansThePathBesideIt() throws Exception {
        Coords centre = new Coords(11, 8);
        Entity union = new MekFileParser(new File("testresources/megamek/common/units/Union (3055).blk")).getEntity();
        onSwing(() -> {
            union.setId(UNION);
            union.setOwner(moving.board.player);
            union.setDeployed(true);
            union.setFacing(0);
            moving.board.game.addEntity(union, false);
            // Grounded, so it stands in seven hexes (Dropship.setPosition).
            ((IAero) union).land();
            union.setPosition(centre);
            return null;
        });

        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        assertTrue(snapshot().mounts().contains(UNION), "the Sagittaire can board the grounded Union");
        assertEquals(Entity.NONE, hud.boardUnit(centre, UNION), "the transport's model plans");
        click(centre, UNION, 0, Float.NaN);
        assertPlanned(SAGITTAIRE, new Coords(11, 10), EntityMovementType.MOVE_WALK);
        verify(hudSource, never()).selectUnit(anyInt());
        verify(moving.gui, never()).addToast(any(), anyString());
        assertTrue(onSwing(() -> moving.display.getActionButtons().stream()
              .filter(button -> MoveCommand.MOVE_MOUNT.getCmd().equals(button.getActionCommand()))
              .findFirst().orElseThrow().isEnabled()), "Mount boards the Union from there");
    }

    /**
     * Battle armor may stand with a Small Craft, so MegaMek's click would not stop beside it (MountPathHelper trims
     * only a path that cannot end there): out of the battle armor's reach the craft's model inspects it as other units
     * are and plans nothing.
     */
    @Test
    void aTransportTheUnitMayStandWithIsInspectedOutOfReach() throws Exception {
        Coords baHex = new Coords(8, 8);
        Coords craftHex = baHex.translated(0, 2);
        Entity elementals = addUnit("Elemental BA [Laser] (Sqd5).blk", ELEMENTALS, LOCAL, baHex);
        Entity craft = addUnit("Mowang Courier (Clandestine).blk", MOWANG, LOCAL, craftHex);
        onSwing(() -> {
            ((IAero) craft).land();
            craft.setDone(true);
            return null;
        });
        assertTrue(onSwing(() -> Compute.canMount(elementals, craft)), "the craft's bay takes the battle armor");

        selectAndMode(ELEMENTALS, GpuMovePlan.Mode.AUTO);
        assertFalse(snapshot().mounts().contains(MOWANG), "no Mount click: the battle armor may stand with the craft");
        assertFalse(snapshot().envelope().containsKey(craftHex), "out of its reach");
        assertEquals(MOWANG, hud.boardUnit(craftHex, MOWANG), "the model keeps its unit: the hand cursor");
        click(craftHex, MOWANG, 0, Float.NaN);
        assertEquals(MOWANG, hud.state.inspected);
        assertEquals("[]", onSwing(() -> steps(moving.display.getPlannedMovement())), "no plan into its hex");
        verify(moving.gui, never()).addToast(any(), anyString());
    }

    /**
     * The hint line names the left click's gesture: plan move (a unit out of reach is selected or inspected) while
     * planning, and the target choice in the charge and DFA gears.
     */
    @Test
    void theHintLineNamesPlanningAndTheTargetChoice() throws Exception {
        selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
        assertEquals(UiKit.text("GpuBoard.hud.hint.planMove"), hint());
        for (MoveCommand attack : List.of(MoveCommand.MOVE_CHARGE, MoveCommand.MOVE_DFA)) {
            selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
            moving.command(attack);
            assertEquals(GpuMovePlan.Mode.TARGET, snapshot().mode(), attack.name());
            assertEquals(UiKit.text("GpuBoard.hud.hint.chooseTarget"), hint(), attack.name());
        }
    }

    /**
     * The enemy's right-click menu lists MegaMek's own Charge and D.F.A. of its hex once, in the map menu's Physicals
     * section, once the hex's map menu arrives; each reaches the same confirmation, with the same path, as a click on
     * the enemy's model after the dock's Charge or D.F.A.
     */
    @Test
    void chargeAndDfaFromTheEnemyMenuAskTheSameAsAClickInTheirGear() throws Exception {
        Coords archerHex = new Coords(11, 9);
        Entity archer = addUnit("Archer ARC-2R.mtf", ARCHER, OPPONENT, archerHex);
        // A charge or DFA needs a target that is done with movement.
        onSwing(() -> {
            archer.setDone(true);
            return null;
        });
        for (MoveCommand attack : List.of(MoveCommand.MOVE_CHARGE, MoveCommand.MOVE_DFA)) {
            selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
            moving.command(attack);
            hudUpdate(null);
            dialogs.clear();
            click(archerHex, ARCHER, 0, Float.NaN);
            settle();
            assertEquals(1, dialogs.size(), attack.name() + " by a model click: " + dialogs);
            String byClick = dialogs.getFirst();

            selectAndMode(SAGITTAIRE, GpuMovePlan.Mode.AUTO);
            dialogs.clear();
            hud.boardClick(archerHex, ARCHER, Input.Buttons.RIGHT, 0, 300, 300, Float.NaN);
            verify(hudSource).inspect(archerHex, archer.getId(), false);
            hudUpdate(new BoardScene.Context(archerHex, ARCHER, mapMenu(archerHex, ARCHER)));
            String label = Messages.getString(attack == MoveCommand.MOVE_CHARGE ? "MovementDisplay.butCharge"
                  : "MovementDisplay.butDfa");
            UiButton item = find(hud.stage.getRoot().findActor("context-menu-popover"), label);
            assertNotNull(item, label + " in the enemy menu");
            List<String> rows = new ArrayList<>();
            rows(hud.stage.getRoot().findActor("context-menu-popover"), rows);
            assertTrue(rows.indexOf(label) > rows.indexOf(readout(ARCHER)),
                  label + " follows the shared unit actions: " + rows);
            assertEquals(1, Collections.frequency(rows, label), label + " once: " + rows);
            item.fire(new ChangeListener.ChangeEvent());
            settle();
            assertEquals(List.of(byClick), dialogs, attack.name() + " from the menu");
            clearInvocations(hudSource);
        }
    }

    /**
     * Every unit menu ends with MegaMek's full map menu of the unit's hex, in every phase: 
     * a gamemaster's lists the Gamemaster commands, for an enemy and an own unit alike, and they act on
     * that hex's units; another player's lists none.
     */
    @Test
    void aGamemastersUnitMenusListTheGamemasterCommandsOfTheUnitsHex() throws Exception {
        Coords archerHex = new Coords(11, 9);
        Entity archer = addUnit("Archer ARC-2R.mtf", ARCHER, OPPONENT, archerHex);
        onSwing(() -> {
            moving.board.game.getOptions().getOption(OptionsConstants.VICTORY_USE_OBJECTIVES).setValue(true);
            return null;
        });
        String editDamage = Messages.getString("Gamemaster.EditDamage");
        String changeTerrain = Messages.getString("Gamemaster.cmd.changeTerrain.longName");
        String scan = Messages.getString("Gamemaster.cmd.scanTarget");

        List<String> rows = unitMenu(archerHex, ARCHER, GamePhase.FIRING);
        assertEquals(inspect(ARCHER), rows.getFirst(), rows.toString());
        assertTrue(rows.stream().noneMatch(List.of(editDamage, changeTerrain, scan)::contains),
              "not a gamemaster: " + rows);

        onSwing(() -> {
            moving.board.game.getOptions().getOption(OptionsConstants.GAME_MASTER_ALLOW).setValue(true);
            moving.board.player.setGameMaster(true);
            return null;
        });
        rows = unitMenu(archerHex, ARCHER, GamePhase.FIRING);
        assertTrue(rows.containsAll(List.of(editDamage, changeTerrain, scan)), "the enemy's menu: " + rows);
        assertTrue(rows.indexOf(editDamage) > rows.indexOf(readout(ARCHER)),
              "after the shared unit actions: " + rows);
        rows = unitMenu(GpuMovementFixture.START, SAGITTAIRE, GamePhase.MOVEMENT);
        assertTrue(rows.containsAll(List.of(editDamage, changeTerrain, scan)), "the own unit's menu: " + rows);

        // A gamemaster command on the units of the menu's hex: the Archer there is wanted for scanning.
        unitMenu(archerHex, ARCHER, GamePhase.FIRING);
        find(hud.stage.getRoot().findActor("context-menu-popover"), scan).fire(new ChangeListener.ChangeEvent());
        Actor submenu = hud.stage.getRoot().findActor("context-submenu-1");
        List<String> units = new ArrayList<>();
        rows(submenu, units);
        String archerName = onSwing(archer::getShortName);
        assertEquals(List.of(archerName), units, "the units in the Archer's hex");
        find(submenu, archerName).fire(new ChangeListener.ChangeEvent());
        settle();
        verify(moving.client).sendScanDesignation(ARCHER, true);
        verify(moving.client, never()).sendScanDesignation(eq(SAGITTAIRE), anyBoolean());
    }

    // ------------------------------------------------------------------ clicks and state

    /** A 3D left click as GpuBattleView.touchUp hands it to the HUD. */
    private void click(Coords coords, int unitId, int modifiers, float pointedZ) throws Exception {
        hudUpdate(null);
        hud.boardClick(coords, unitId, Input.Buttons.LEFT, modifiers, 100, 100, pointedZ);
        settle();
    }

    private void assertPlanned(int actor, Coords end, EntityMovementType type) throws Exception {
        String path = onSwing(() -> steps(moving.display.getPlannedMovement())) + " toasts: " + toasts();
        assertEquals(actor, actor(), path);
        assertEquals(List.of(end, true, type), onSwing(() -> {
            MovePath planned = moving.display.getPlannedMovement();
            return List.of(planned.getFinalCoords(), planned.isMoveLegal(), planned.getLastStepMovementType());
        }), path);
    }

    private int actor() throws Exception {
        return onSwing(() -> moving.display.currentEntity() == null ? Entity.NONE
              : moving.display.currentEntity().getId());
    }

    /** The hint line's text for the left click with the HUD's last frame. */
    private String hint() throws Exception {
        hudUpdate(null);
        return GpuHintLine.items(new GpuHud.Inputs(frame(null), GpuHud.HudView.EMPTY, null,
              GpuHudInputTest.preferences(), GpuHud.Metrics.of(1920, 1080), List.of())).get(1);
    }

    /** MegaMek's map menu at the hex, as the source captures it for an open menu (GpuBoardActions.contextCommands). */
    private List<BoardScene.Command> mapMenu(Coords hex, int unitId) throws Exception {
        return onSwing(() -> {
            BoardClientState view = spy(moving.board.view);
            doReturn(moving.gui).when(view).getClientgui();
            return new GpuBoardActions(view, () -> moving.display, () -> false, () -> { }).contextCommands(hex, unitId);
        });
    }

    /**
     * The HUD's next frame: the real plan, the units the real scene draws, and the units' sides, readiness and
     * positions, with the map menu context if any. No unit is pending, so the HUD's turn-start focus selects none of
     * them.
     */
    private void hudUpdate(BoardScene.Context context) throws Exception {
        hudUpdate(context, GamePhase.MOVEMENT);
    }

    /** The HUD's next frame in the phase; the local player's turn in the movement phase only. */
    private void hudUpdate(BoardScene.Context context, GamePhase phase) throws Exception {
        hud.update(frame(context, phase), GpuHud.HudView.EMPTY, null, GpuHudInputTest.preferences());
        clearInvocations(hudSource);
    }

    /** Right-clicks the unit in its hex in the phase: the menu's rows once MegaMek's map menu of the hex arrives. */
    private List<String> unitMenu(Coords hex, int unitId, GamePhase phase) throws Exception {
        hudUpdate(null, phase);
        hud.boardClick(hex, unitId, Input.Buttons.RIGHT, 0, 300, 300, Float.NaN);
        verify(hudSource).inspect(hex, unitId, false);
        hudUpdate(new BoardScene.Context(hex, unitId, mapMenu(hex, unitId)), phase);
        List<String> rows = new ArrayList<>();
        rows(hud.stage.getRoot().findActor("context-menu-popover"), rows);
        return rows;
    }

    /** Right-clicks the unit in its hex in the movement turn and chooses the menu's item with this text. */
    private void menuItem(Coords hex, int unitId, String text) throws Exception {
        List<String> rows = unitMenu(hex, unitId, GamePhase.MOVEMENT);
        UiButton item = find(hud.stage.getRoot().findActor("context-menu-popover"), text);
        assertNotNull(item, text + " in " + rows);
        item.fire(new ChangeListener.ChangeEvent());
        settle();
    }

    /** The shared menu's View item uses the game entity's name. */
    private String inspect(int unitId) throws Exception {
        return Messages.getString("ClientGUI.viewMenuItem")
              + onSwing(() -> moving.board.game.getEntity(unitId).getDisplayName());
    }

    private String readout(int unitId) throws Exception {
        return Messages.getString("ClientGUI.viewReadoutMenuItem")
              + onSwing(() -> moving.board.game.getEntity(unitId).getDisplayName());
    }

    /** The shared menu's Select item uses the game entity's name. */
    private String select(int unitId) throws Exception {
        return Messages.getString("ClientGUI.selectMenuItem")
              + onSwing(() -> moving.board.game.getEntity(unitId).getDisplayName());
    }

    private GpuBoardSource.Frame frame(BoardScene.Context context) throws Exception {
        return frame(context, GamePhase.MOVEMENT);
    }

    private GpuBoardSource.Frame frame(BoardScene.Context context, GamePhase phase) throws Exception {
        GpuBoardSource.Frame captured = captured();
        GpuMovePlan.Snapshot move = captured.panels().move();
        BoardScene scene = mock(BoardScene.class);
        when(scene.units()).thenReturn(captured.scene() == null ? List.of() : captured.scene().units());
        Game game = moving.board.game;
        List<GpuBattleStatus.UnitStatus> units = onSwing(() -> {
            GameTurn turn = game.getTurn();
            List<GpuBattleStatus.UnitStatus> list = new ArrayList<>();
            for (Entity entity : game.getEntitiesVector()) {
                boolean own = entity.getOwnerId() == LOCAL;
                boolean ready = own && turn != null && turn.isValidEntity(entity, game);
                list.add(new GpuBattleStatus.UnitStatus(entity.getId(), own ? GpuBattleStatus.Side.OWN
                      : GpuBattleStatus.Side.ENEMY, false, "Unit " + entity.getId(), "", "", 0, "", "", "", 0, 0, 1,
                      1, 0, 0, "", 0, "", 0, "", 0, 0, 0, 0, ready, false, true, false, Entity.DMG_NONE, List.of(),
                      "", entity.getPosition(), 0, null, List.of(), 0, 0, Player.PLAYER_NONE, List.of()));
            }
            return list;
        });
        GpuBattleStatus.Snapshot status = GpuHudInputTest.status(1, phase, phase == GamePhase.MOVEMENT, actor(), 0,
              units.toArray(GpuBattleStatus.UnitStatus[]::new));
        return new GpuBoardSource.Frame(scene, List.of(), context, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, GpuHudInputTest.panels(move, GpuFireOrders.Snapshot.EMPTY,
              GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
    }

    private GpuMovePlan.Snapshot snapshot() throws Exception {
        return captured().panels().move();
    }

    /** The real source's newest frame, once the EDT ran what the commands posted. */
    private GpuBoardSource.Frame captured() throws Exception {
        settle();
        return onSwing(() -> {
            moving.board.source.refresh();
            return moving.board.source.takeFrame();
        });
    }

    /**
     * Selects the unit afresh in the mode. The plan keeps a unit's envelope while its gear and start stay, and the
     * source may have searched it while the test still placed units, so another unit is selected first: a unit switch
     * discards the plan.
     */
    private void selectAndMode(int id, GpuMovePlan.Mode mode) throws Exception {
        for (int unit : new int[] { id == ATLAS ? SAGITTAIRE : ATLAS, id }) {
            onSwing(() -> {
                moving.display.selectEntity(unit);
                return null;
            });
            snapshot();
        }
        if (mode != GpuMovePlan.Mode.AUTO) {
            moving.board.source.moves().setMode(mode);
        }
        // The envelope is searched in its own event after a capture.
        snapshot();
        snapshot();
        hud.state.inspected = Entity.NONE;
        hudUpdate(null);
        clearInvocations(moving.gui);
    }

    private Entity addUnit(String file, int id, int owner, Coords position) throws Exception {
        Entity unit = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        onSwing(() -> {
            unit.setId(id);
            unit.setOwner(moving.board.game.getPlayer(owner));
            unit.setPosition(position);
            unit.setFacing(0);
            unit.setDeployed(true);
            moving.board.game.addEntity(unit, false);
            return null;
        });
        return unit;
    }

    private void place(Entity entity, Coords position) throws Exception {
        onSwing(() -> {
            entity.setPosition(position);
            return null;
        });
    }

    private float ground(Coords coords) throws Exception {
        return onSwing(() -> moving.board.game.getBoard().getHex(coords).getLevel() * BoardGeometry.level());
    }

    /** The world height of a floor of the test's buildings on level 0 hexes. */
    private static float floorZ(int elevation) {
        return elevation * BoardGeometry.level();
    }

    /** The opponent and the turn order: the local player moves first. */
    private void scriptTurns() throws Exception {
        onSwing(() -> {
            Game game = moving.board.game;
            Player opponent = new Player(OPPONENT, "Opponent");
            opponent.setTeam(2);
            game.addPlayer(OPPONENT, opponent);
            game.setTurnVector(List.of(new GameTurn(LOCAL), new GameTurn(OPPONENT)));
            Client client = moving.client;
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

    /** Lets the EDT run what the commands posted, and the plan's searches after them. */
    private static void settle() throws Exception {
        for (int pass = 0; pass < 4; pass++) {
            onSwing(() -> null);
        }
    }

    private static String steps(MovePath path) {
        return path.getStepVector().stream().map(step -> step.getType().name() + "@" + step.getPosition().getX()
              + "," + step.getPosition().getY() + (step.getElevation() != 0 ? "e" + step.getElevation() : ""))
              .collect(Collectors.joining(" ", "[", "]"));
    }

    /** The menu item with this text, or null. */
    private static UiButton find(Actor actor, String text) {
        if (actor instanceof UiButton item) {
            return item.getText().toString().equals(text) ? item : null;
        } else if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                UiButton found = find(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The menu items' texts from top to bottom. */
    private static void rows(Actor actor, List<String> rows) {
        if (actor instanceof UiButton item) {
            rows.add(item.getText().toString());
        } else if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                rows(child, rows);
            }
        }
    }

    /** The toasts the gui was asked to show, for the assertions' messages. */
    private String toasts() {
        return mockingDetails(moving.gui).getInvocations().stream()
              .filter(invocation -> invocation.getMethod().getName().equals("addToast"))
              .map(invocation -> String.valueOf(invocation.getArguments()[1])).collect(Collectors.joining(", "));
    }
}
