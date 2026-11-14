/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.client.Client;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.ToHitData;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Tactical View on the real capture. T turns every unit into its icon on its GpuUnitBases
 * base over the ground layer, on the terrain the graphics setting chose, lit by the fixed north-west light without
 * grass; the bases show the side, the facing, the done and destroyed states; units are picked by their icons; T
 * again restores the 3D view's models and its exact pose. A second test captures the mock-up battle in the movement and
 * firing phases (b11 tactical mock-ups) for review, a third drives its overlays through the Overlays panel and
 * captures the panel's places. The terrain draws no scatter or grass there (GpuTerrain#setScatterHidden), in either
 * graphics, and still picks and drapes as before.
 */
@Tag("on-demand")
class GpuTacticalViewSmokeTest {
    private static final int WIDTH = 12;
    private static final int HEIGHT = 10;
    private static final Coords VIEWER = new Coords(1, 5);
    private static final Coords BUILDING = new Coords(2, 7);
    private static final Coords BRIDGE = new Coords(7, 3);
    private static final Coords FUEL_TANK = new Coords(5, 1);
    /** The fixture's own Atlas, facing north. */
    private static final Coords OWN = new Coords(5, 5);
    /** The enemy Warwolf, facing south-east. */
    private static final Coords ENEMY = new Coords(8, 6);
    private static final int ENEMY_FACING = 2;
    private static final String MOCK_BOARD = "testresources/megamek/client/ui/clientGUI/boardview/gpu/tactical-mock.board";
    /** The mock-up battle's hovered Bulldog, VTOL (elevation 5, spotting the Exterminator when firing), Atlas. */
    private static final Coords BULLDOG = new Coords(17, 7);
    private static final Coords VTOL = new Coords(12, 6);
    private static final Coords EXTERMINATOR = new Coords(14, 8);
    private static final Coords ATLAS = new Coords(6, 9);
    /** A level-3 hex of the mock board's plateau (1603) and a level-0 hex (0803), the board's lowest level. */
    private static final Coords PLATEAU = new Coords(15, 2);
    private static final Coords LOWLAND = new Coords(7, 2);
    /**
     * The client settings the Overlays panel shows, and the View menu's coordinates and hex types, which the overlay
     * test sets.
     */
    private static final List<String> OVERLAY_SETTINGS = List.of(GUIPreferences.TACTICAL_OVERLAYS_OPEN,
          GUIPreferences.TACTICAL_STATUS_BADGES, GUIPreferences.TACTICAL_REAR_ARCS,
          GUIPreferences.BOARD_ECM_TACTICAL_FIELDS, GUIPreferences.BOARD_ECM_TACTICAL_HATCH,
          GUIPreferences.TACTICAL_LEVEL_SHADING, GUIPreferences.SHOW_UNIT_LINKS, GUIPreferences.SHOW_COORDS,
          GUIPreferences.SHOW_HEX_TYPES, GUIPreferences.MINI_MAP_ENABLED, GUIPreferences.GPU_CONTACTS_ENABLED);

    @Test
    void turnsTheUnitsIntoIconsOnBasesOverTheGroundLayerAndRestoresTheThreeDimensionalView() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getBoardUiScale();
        BoardGraphics originalGraphics = preferences.getBoardGraphics();
        try (var options = new GpuFieldOfViewTest.Options(); var fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Player enemy = new Player(2, "Enemy");
                    enemy.setTeam(2);
                    enemy.setColour(PlayerColour.RED);
                    fixture.game.addPlayer(enemy.getId(), enemy);
                    Entity warwolf = new MekFileParser(new File("testresources/megamek/common/units/Warwolf A.mtf"))
                          .getEntity();
                    warwolf.setId(2);
                    warwolf.setOwner(enemy);
                    warwolf.setPosition(ENEMY);
                    warwolf.setFacing(ENEMY_FACING);
                    warwolf.setSecondaryFacing(ENEMY_FACING);
                    warwolf.setDeployed(true);
                    fixture.game.addEntity(warwolf, false);
                    fixture.entity.setFacing(0);
                    fixture.entity.setSecondaryFacing(0);
                    fixture.view.select(VIEWER);
                    fixture.source.setVisibleArea(new Rectangle(0, 0, WIDTH, HEIGHT));
                    fixture.source.refresh();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            run(fixture, failure, view -> verify(view, fixture));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardUiScale(originalScale);
                preferences.setBoardGraphics(originalGraphics);
            });
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    /**
     * View > Hex Types on a board of the user's types (b11 scope, "Hex TYPE labels"): the published labels carry the
     * type block instead of the DEPTH, HEIGHT and FOLIAGE labels, and only those labels once it is off again; every
     * label stands on its outline. Captures for review: the 3D view in Enhanced and Classic, angled and from the top,
     * the Tactical View in both (no large depth numbers; the roofs' CF stays), the Legacy 2D board, Save Map as Image
     * (coordinates and types black on a white outline), and with Hex Types off the Legacy 2D board, the Tactical View
     * and the 3D view from the top ({@code hex-types-off-*.png}).
     */
    @Test
    void hexTypesLabelTheHexesInEveryView() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getBoardUiScale();
        BoardGraphics originalGraphics = preferences.getBoardGraphics();
        boolean coords = preferences.getCoordsEnabled();
        boolean types = preferences.getHexTypesEnabled();
        try (var fixture = GpuBoardFixture.create(typesBoard())) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    if (!preferences.getCoordsEnabled()) { preferences.toggleCoords(); }
                    if (preferences.getHexTypesEnabled()) { preferences.toggleHexTypes(); }
                    File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(directory.isDirectory() || directory.mkdirs());
                    ImageIO.write(fixture.legacy2DView().getEntireBoardImage(true, true), "png",
                          new File(directory, "hex-types-off-legacy-2d.png"));
                    preferences.toggleHexTypes();
                    preferences.setBoardGraphics(BoardGraphics.ENHANCED);
                    fixture.source.setVisibleArea(new Rectangle(0, 0, WIDTH, HEIGHT));
                    fixture.source.refresh();
                    ImageIO.write(fixture.legacy2DView().getEntireBoardImage(true, true), "png",
                          new File(directory, "hex-types-legacy-2d.png"));
                    ImageIO.write(fixture.view.getEntireBoardImage(true), "png",
                          new File(directory, "hex-types-export.png"));
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            run(fixture, failure, view -> hexTypes(view, fixture));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (preferences.getCoordsEnabled() != coords) { preferences.toggleCoords(); }
                if (preferences.getHexTypesEnabled() != types) { preferences.toggleHexTypes(); }
                preferences.setBoardUiScale(originalScale);
                preferences.setBoardGraphics(originalGraphics);
            });
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    /**
     * {@link #board()} with a hex of each type the user listed, an ice-covered river hex, a depth-0 pool, a sublevel
     * (0603), a sublevel pool (0905), a paved building (0706), and a hex of each word added after (swamp, quicksand
     * in water, geysers, magma, rapids and torrent, snow, tundra, fields, black ice, a hazardous liquid, impassable,
     * fortified, an elevator, space, ultra rough and rubble, a gun emplacement, fire in woods).
     */
    private static Board typesBoard() {
        Board board = board();
        String[][] types = { { "0,1", "rough:1" }, { "1,1", "rubble:1" }, { "2,1", "woods:1;foliage_elev:1" },
              { "3,1", "woods:3;foliage_elev:3" }, { "0,3", "pavement:1" }, { "1,3", "sand:1" }, { "2,3", "mud:1" },
              { "1,5", "woods:2;foliage_elev:2;rough:1;mud:1" }, { "7,6", "ice:1" }, { "8,1", "water:0" },
              { "0,9", "ultra_sublevel:1" }, { "3,5", "building:5;bldg_elev:1;bldg_cf:120" }, { "8,4", "water:1" },
              { "6,5", "pavement:1" }, { "0,5", "swamp:1" }, { "8,6", "water:1;swamp:2" }, { "0,7", "geyser:1" },
              { "5,8", "geyser:3" }, { "5,4", "magma:1" }, { "5,6", "magma:2" }, { "7,1", "rapids:1" },
              { "7,8", "rapids:2" }, { "3,3", "snow:1" }, { "3,7", "snow:2" }, { "6,0", "tundra:1" },
              { "6,2", "planted_fields:1" }, { "6,4", "pavement:1;black_ice:1" }, { "6,6", "black_ice:1" },
              { "8,8", "hazardous_liquid:1" }, { "11,1", "impassable:1" }, { "11,3", "fortified:1" },
              { "11,5", "solaris_elevator:1" }, { "11,7", "space:1" }, { "10,1", "rough:2" }, { "9,6", "rubble:6" },
              { "10,3", "building:4;bldg_elev:1;bldg_cf:150;bldg_class:3" }, { "1,2", "fire:1" } };
        for (String[] type : types) {
            String[] at = type[0].split(",");
            Hex hex = board.getHex(Integer.parseInt(at[0]), Integer.parseInt(at[1]));
            for (String terrain : type[1].split(";")) {
                hex.addTerrain(new Terrain(terrain));
            }
        }
        board.getHex(5, 2).setLevel(-2);
        board.getHex(8, 4).setLevel(-1);
        // Detected black ice is written in the views (0705), never in the saved image; undetected (0707) nowhere.
        board.getHex(6, 4).getTerrain(Terrains.BLACK_ICE).detectBlackIce();
        return board;
    }

    private static void hexTypes(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        GpuBoardTestUi.<Slider>tuning(view, "Time of day").setValue(13);
        render(view);
        var scene = (BoardScene) field(view, "scene");
        List<String> bridge = scene.tile(BRIDGE).text().stream().map(BoardHexText::text).toList();
        assertEquals(List.of("DEPTH 1", "BRIDGE▲1"), bridge.subList(1, bridge.size()), "The bridge's type block");
        assertEquals(List.of("0705", "PAVED", "BLACK ICE"), scene.tile(new Coords(6, 4)).text().stream()
              .map(BoardHexText::text).toList(), "Detected black ice in the views");
        // The structure lines take HEIGHT's place on the roofs in 3D, where the models would hide the ground.
        assertTrue(scene.tiles().stream().flatMap(tile -> tile.text().stream())
              .noneMatch(label -> label.text().startsWith("HEIGHT")), "No HEIGHT label");
        assertEquals(1, scene.tile(BRIDGE).text().getLast().elevation(), "BRIDGE▲1 on its deck");
        assertEquals(List.of(0, 0, 1), scene.tile(new Coords(10, 3)).text().stream().map(BoardHexText::elevation)
              .toList(), "HARDENED▲1 on its roof, the coordinates and LEVEL 1 at the ground");
        view.boardCamera.fit(scene);
        render(view);
        capture("hex-types-3d-enhanced.png");
        graphics(view, BoardGraphics.CLASSIC);
        capture("hex-types-3d-classic.png");
        view.boardCamera.orbit(0, -BoardCamera.MAX_TILT);
        render(view);
        capture("hex-types-3d-classic-top.png");
        graphics(view, BoardGraphics.ENHANCED);
        capture("hex-types-3d-enhanced-top.png");
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertTrue(view.boardCamera.tactical(), "T enters the Tactical View");
        view.boardCamera.fit(scene);
        render(view);
        capture("hex-types-tactical-enhanced.png");
        graphics(view, BoardGraphics.CLASSIC);
        capture("hex-types-tactical-classic.png");
        graphics(view, BoardGraphics.ENHANCED);
        // The gun emplacement's roof chip leaves its height to HARDENED▲1.
        Coords emplacement = new Coords(10, 3);
        BoardScene.Tile typed = scene.tile(emplacement);
        List<String> chip = GpuHexText.labels(typed, true, true, false).stream().map(BoardHexText::text).toList();
        assertEquals(List.of("HARDENED▲1", "CF 150"), chip.subList(chip.size() - 2, chip.size()),
              "The height once in the Tactical View with Hex Types");
        // Off again: the same labels in the same words, without the types.
        SwingUtilities.invokeAndWait(GUIPreferences.getInstance()::toggleHexTypes);
        await(view, fixture, () -> !fixture.source.uiPreferences().overlays().hexTypes(), "Hex Types off");
        scene = (BoardScene) field(view, "scene");
        assertEquals(List.of("DEPTH 1", "HEIGHT 1"), scene.tile(BRIDGE).text().stream().skip(1)
              .map(BoardHexText::text).toList(), "The bridge's labels without Hex Types");
        // The chip replaces the roof's HEIGHT 1. The captured labels, not the setting, decide its height, so a tile
        // still holding the other setting's capture shows it once too.
        for (boolean types : new boolean[] { false, true }) {
            assertEquals("CF 150 ▲1", GpuHexText.labels(scene.tile(emplacement), true, types, false).getLast().text());
            assertEquals("CF 150", GpuHexText.labels(typed, true, types, false).getLast().text());
        }
        capture("hex-types-off-tactical-enhanced.png");
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        view.boardCamera.fit(scene);
        render(view);
        capture("hex-types-off-3d-enhanced-top.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    /** The view under test, created and presented on a hidden 1920 x 1080 window at one HUD unit per pixel. */
    interface Check {
        void run(GpuBattleView view) throws Exception;
    }

    private static void run(GpuBoardFixture fixture, AtomicReference<Throwable> failure, Check check) {
        run(fixture, failure, 1920, 1080, check);
    }

    /** As {@link #run(GpuBoardFixture, AtomicReference, Check)} on a window of the given size. */
    static void run(GpuBoardFixture fixture, AtomicReference<Throwable> failure, int width, int height,
          Check check) {
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(width, height);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuBattleView view = null;
                try {
                    float preference = .1f / DisplayScale.read(.1f, new GpuDisplayScale().contentScale());
                    SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance()
                          .setBoardUiScale(preference));
                    view = new GpuBattleView(fixture.source);
                    view.create();
                    // The board arrives once its terrain is built behind the loading screen.
                    GpuBoardTestUi.present(view);
                    check.run(view);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    if (view != null) { view.dispose(); }
                    Gdx.app.exit();
                }
            }
        }, configuration);
    }

    /** Woods, a container yard, jungle, buildings, a fuel tank, industry, a bridged river, a cliff, a step, smoke and fire. */
    private static Board board() {
        Hex[] hexes = new Hex[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Hex hex = new Hex(x == 4 ? 3 : x >= 9 ? 1 : 0);
                if (x == 7) {
                    hex.addTerrain(new Terrain(Terrains.WATER, y >= 7 ? 2 : 1));
                }
                hexes[y * WIDTH + x] = hex;
            }
        }
        forest(hexes, 1, 2, Terrains.WOODS, 2);
        forest(hexes, 2, 2, Terrains.WOODS, 1);
        forest(hexes, 6, 8, Terrains.WOODS, 2);
        forest(hexes, 10, 8, Terrains.JUNGLE, 2);
        // A light container yard (b12): rows its woods own, which the Tactical View keeps as the cover's look.
        forest(hexes, 3, 0, Terrains.WOODS, 1);
        hexes[3].setAppearance(Map.of("vegetation", new megamek.common.board.HexAppearance("woods/container-yard/rows", null, null, null)));
        building(hexes, BUILDING, 2, 2, 40);
        building(hexes, new Coords(6, 5), 1, 1, 15);
        building(hexes, new Coords(9, 2), 3, 3, 90);
        Hex tank = hexes[FUEL_TANK.getY() * WIDTH + FUEL_TANK.getX()];
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK, 2, true, 0));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_ELEV, 2));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_CF, 40));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 100));
        hexes[5 * WIDTH + 10].addTerrain(new Terrain(Terrains.INDUSTRIAL, 3));
        hexes[8 * WIDTH + 2].addTerrain(new Terrain(Terrains.SMOKE, 2));
        hexes[8 * WIDTH + 3].addTerrain(new Terrain(Terrains.FIRE, 1));
        Hex bridge = hexes[BRIDGE.getY() * WIDTH + BRIDGE.getX()];
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, (1 << 1) | (1 << 4)));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
        // A built bridge carrying a paved road: a bridge with no stored type and no road, building or pavement beside
        // it decodes as a natural rock arch (BridgeSpan.decode), whose deck is the arch's rock.
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, 1, true, (1 << 1) | (1 << 4)));
        bridge.setAppearance(Map.of("bridge", megamek.common.board.HexAppearance.BUILT_BRIDGE));
        return new Board(WIDTH, HEIGHT, hexes);
    }

    private static void forest(Hex[] hexes, int x, int y, int type, int density) {
        hexes[y * WIDTH + x].addTerrain(new Terrain(type, density));
        hexes[y * WIDTH + x].addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
    }

    private static void building(Hex[] hexes, Coords coords, int type, int height, int cf) {
        Hex hex = hexes[coords.getY() * WIDTH + coords.getX()];
        hex.addTerrain(new Terrain(Terrains.BUILDING, type, true, 0));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, height));
        hex.addTerrain(new Terrain(Terrains.BLDG_CF, cf));
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        view.render();
        GpuBoardTestUi.<Slider>tuning(view, "Time of day").setValue(13);
        // The 3D view in its 3D Models display, which the Tactical View overrides with icons.
        @SuppressWarnings("unchecked")
        var display = (SelectBox<UnitDisplayMode>) field(GpuBoardTestUi.tuning(view), "unitDisplayMode");
        display.setSelected(UnitDisplayMode.MODELS);
        render(view);
        var terrain = (GpuTerrain) field(view, "terrain");
        var bases = (GpuUnitBases) field(view, "unitBases");
        var drawn3d = audit(view);
        assertTrue(drawn3d.getOrDefault("instanced-trees", 0) > 0, "The 3D view draws its woods: " + drawn3d);
        assertTrue(drawn3d.keySet().stream().anyMatch(family -> family.startsWith("water-")), "and its river: " + drawn3d);
        var scene = (BoardScene) field(view, "scene");
        var models = (Map<String, ModelInstance>) field(view, "unitInstances");
        var model = models.get("1:-1");
        assertNotNull(model, "The own Atlas is a 3D model");
        assertFalse(MeepleVisual.isMeeple(model), "The 3D view shows the Atlas's model");
        assertEquals(2, scene.units().size());
        // A manual 3D pose away from the fit: leaving must return this saved pose, not fit the board again.
        view.boardCamera.orbit(20, 5);
        view.boardCamera.pan(40, -25);
        view.boardCamera.zoom(.8f);
        render(view);
        Vector3 focus = view.boardCamera.focus.cpy();
        float zoom = view.boardCamera.camera.zoom;
        float azimuth = view.boardCamera.azimuth();
        float tilt = view.boardCamera.tilt();
        assertTrue(tilt > 0, "The 3D view starts at an angle");
        capture("tactical-view-3d.png");

        var unitPrograms = (List<?>) field(((ModelBatch) field(view, "unitBatch")).getShaderProvider(), "handles");
        int linked = unitPrograms.size();
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertTrue(view.boardCamera.tactical(), "T enters the Tactical View");
        assertEquals(linked, unitPrograms.size(), "The icons' program linked behind the loading stage");
        view.boardCamera.fit(scene);
        render(view);
        assertTrue(view.boardCamera.camera.direction.epsilonEquals(0, 0, -1, .0001f));
        assertTrue(view.boardCamera.camera.up.epsilonEquals(0, 1, 0, .0001f), "North stays up");
        for (BoardScene.Unit unit : scene.units()) {
            assertTrue(MeepleVisual.isMeeple(models.get(unit.id() + ":" + unit.part())),
                  "The Tactical View shows " + unit.name() + " as its icon");
            assertEquals(1, models.get(unit.id() + ":" + unit.part()).getNode(MeepleVisual.ROOT).parts.size,
                  "An icon is flat, the artwork alone");
        }
        // The fixed light with the sky environment generated for it (GpuAtmosphere#updateLight).
        var lighting = ((GpuAtmosphere) field(view, "atmosphere")).lighting();
        assertEquals(BoardAtmosphere.TACTICAL.direction(), lighting.direction(), "The fixed north-west light");
        assertEquals(BoardAtmosphere.TACTICAL.skySource(), lighting.skySource(), "and its sky");
        assertTrue(((List<?>) field(field(terrain, "shadowMap"), "shadowModels")).isEmpty(), "The units' icons cast no shadow");
        assertFalse(terrain.columns(), "T leaves the terrain to the graphics setting");
        assertTrue(terrain.scatterHidden(), "The Tactical View's draw rule");
        verifyScatterHidden(view, terrain, scene);
        verifyBases(view, scene, bases);
        capture("tactical-view-top-enhanced.png");
        verifyEffectsOverIcons(view, scene);
        verifyAttackLineOverIcons(view, fixture);
        verifyMarkerOverIcons(view, fixture);
        graphics(view, BoardGraphics.CLASSIC);
        assertTrue(terrain.columns(), "The Classic graphics build the tileset columns");
        verifyScatterHidden(view, terrain, scene);
        capture("tactical-view-top-classic.png");
        verifyFieldOfView(view, scene);
        verifyPicking(view, scene);
        verifyStates(view, fixture, bases);

        graphics(view, BoardGraphics.ENHANCED);
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertFalse(view.boardCamera.tactical(), "T again returns to the 3D view");
        assertEquals(focus, view.boardCamera.focus, "The exact 3D pose returns, not a new fit of the board");
        assertEquals(zoom, view.boardCamera.camera.zoom);
        assertEquals(azimuth, view.boardCamera.azimuth());
        assertEquals(tilt, view.boardCamera.tilt());
        assertFalse(MeepleVisual.isMeeple(models.get("1:-1")), "The Atlas's model is back");
        assertFalse(terrain.columns());
        assertFalse(terrain.scatterHidden(), "Leaving the Tactical View shows the scatter again");
        capture("tactical-view-restored.png");

        // Straight down and zoomed far out, the 3D view still shows its models: only T switches to the icons.
        view.boardCamera.orbit(0, -BoardCamera.MAX_TILT);
        render(view);
        assertEquals(0, view.boardCamera.tilt());
        view.boardCamera.zoom(20 / view.boardCamera.camera.zoom);
        render(view);
        assertFalse(view.boardCamera.tactical());
        assertFalse(MeepleVisual.isMeeple(models.get("1:-1")), "No zoom or angle turns the 3D models into icons");
        assertFalse(terrain.columns(), "No zoom or angle turns the terrain into tileset columns");
        assertFalse(terrain.scatterHidden(), "No zoom or angle hides the scatter");
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(OWN, fixture.entity.getPosition());
            assertEquals(0, fixture.entity.getFacing());
        });
        verifyWreck(view, fixture, bases);
        assertEquals(0, fixture.clicks.get(), "Switching views and picking issue no game command");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    /**
     * Each unit stands on its base: centred on its animated pose, sized in apothems of the hex, its notch turned to
     * the facing; its labels hang from the ring's north point and its screen rectangle is the ring's square.
     */
    @SuppressWarnings("unchecked")
    private static void verifyBases(GpuBattleView view, BoardScene scene, GpuUnitBases bases) throws Exception {
        var poses = (Map<BoardScene.Unit, UnitFootprint.Pose>) field(view, "unitFootprints");
        var anchors = (Map<BoardScene.Unit, Vector3> ) field(view, "unitAnchors");
        float apothem = BoardGeometry.height() / 2;
        for (BoardScene.Unit unit : scene.units()) {
            ModelInstance base = bases.base(unit);
            assertNotNull(base, "A base for " + unit.name());
            Vector3 at = base.transform.getTranslation(new Vector3());
            Vector3 pose = poses.get(unit).position();
            assertEquals(pose.x, at.x, .001f);
            assertEquals(pose.y, at.y, .001f);
            assertEquals(apothem, base.transform.getScaleX(), .001f, "Sized in the hex's apothems");
            float turn = base.getNode("facing").rotation.getAngleAround(Vector3.Z);
            assertEquals((360 - poses.get(unit).facing()) % 360, turn, .01f, "The notch turns with the facing");
            Vector3 head = anchors.get(unit);
            assertEquals(pose.x, head.x, .001f, "The head of " + unit.name() + " is straight north of its base");
            assertEquals(pose.y + GpuUnitBases.RING * apothem, head.y, .001f, "on the ring's north point");
        }
        ModelInstance kept = bases.base(unit(scene, OWN));
        render(view);
        assertSame(kept, bases.base(unit(scene, OWN)), "An unchanged status keeps the base's models");
        GpuUnitBases.BaseSpec own = bases.spec(unit(scene, OWN));
        GpuUnitBases.BaseSpec enemy = bases.spec(unit(scene, ENEMY));
        assertEquals(GpuUnitBases.Look.FRIEND, own.look(), "The own Atlas's ring is mint");
        assertEquals(GpuUnitBases.Look.ENEMY, enemy.look(), "The Warwolf's ring is coral");
        assertTrue(own.facing() && enemy.facing(), "Both show their facing notch");
        assertEquals(0, poses.get(unit(scene, OWN)).facing(), .01f);
        assertEquals(ENEMY_FACING * 60, poses.get(unit(scene, ENEMY)).facing(), .01f);
    }

    private static BoardScene.Unit unit(BoardScene scene, Coords coords) {
        return scene.units().stream().filter(unit -> unit.location().coords().equals(coords)).findFirst().orElseThrow();
    }

    /**
     * The client's selection, an own unit that has already moved and a doomed enemy, through the real capture: the
     * own base dims, the doomed one turns grey and crossed out.
     */
    private static void verifyStates(GpuBattleView view, GpuBoardFixture fixture, GpuUnitBases bases)
          throws Exception {
        JComponent phase = fixture.panel;
        Entity enemy = fixture.game.getEntity(2);
        SwingUtilities.invokeAndWait(() -> {
            var moving = mock(MovementDisplay.class);
            when(moving.currentEntity()).thenReturn(fixture.entity);
            when(moving.getComponents()).thenReturn(new Component[0]);
            when(moving.getActionButtons()).thenReturn(List.of());
            when(moving.getCompletionButtons()).thenReturn(List.of());
            fixture.panel = moving;
            fixture.entity.setDone(true);
            enemy.setDoomed(true);
            fixture.source.refresh();
        });
        render(view);
        var scene = (BoardScene) field(view, "scene");
        assertEquals(fixture.entity.getId(), scene.selectedId(), "The client selects the Atlas");
        assertTrue(bases.spec(unit(scene, OWN)).done(), "The moved Atlas's base dims");
        assertTrue(bases.spec(unit(scene, ENEMY)).destroyed(), "A doomed unit is crossed out");
        assertEquals(GpuUnitBases.Look.NEUTRAL, bases.spec(unit(scene, ENEMY)).look(), "on a grey ring");
        capture("tactical-view-states.png");

        SwingUtilities.invokeAndWait(() -> {
            fixture.panel = phase;
            fixture.entity.setDone(false);
            enemy.setDoomed(false);
            fixture.source.refresh();
        });
        render(view);
        scene = (BoardScene) field(view, "scene");
        assertFalse(bases.spec(unit(scene, OWN)).done());
        assertFalse(bases.spec(unit(scene, ENEMY)).destroyed(), "The enemy is whole again");
    }

    /** Death shows a crossed-out ring, which fades with the body instead of leaving a permanent wreck. */
    private static void verifyWreck(GpuBattleView view, GpuBoardFixture fixture, GpuUnitBases bases)
          throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            fixture.game.removeEntity(2, IEntityRemovalConditions.REMOVE_SALVAGEABLE);
            fixture.source.refresh();
        });
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        view.boardCamera.center(BoardGeometry.center(OWN, 0).lerp(BoardGeometry.center(ENEMY, 0), .5f));
        view.boardCamera.zoom(BoardGeometry.WIDTH / (160 * layoutScale(view)) / view.boardCamera.camera.zoom);
        render(view);
        assertTrue(view.boardCamera.tactical());
        var scene = (BoardScene) field(view, "scene");
        GpuUnitBases.BaseSpec wreck = bases.spec(unit(scene, ENEMY));
        assertTrue(wreck.destroyed(), "The wreck is crossed out");
        assertFalse(wreck.facing(), "The wreck has no facing notch");
        assertFalse(bases.spec(unit(scene, OWN)).destroyed(), "The own unit stays whole");
        capture("tactical-view-wreck.png");
        var victim = unit(scene, ENEMY);
        ((MeepleCasualties) field(view, "casualties")).advance(MeepleCasualties.DEBRIS_SECONDS + UnitAttack.FADE_SECONDS,
              (MeepleDebris.Ground) field(view, "debrisGround"), 1);
        render(view);
        assertNull(bases.base(victim), "The ring fades away with the unit");
        assertNull(bases.badges(victim), "The cross fades away with the unit");
        assertFalse(((Map<?, ?>) field(view, "unitInstances")).containsKey(victim.id() + ":" + victim.part()));
    }

    private static float layoutScale(GpuBattleView view) throws Exception {
        return (float) field(view, "layoutScale");
    }

    /** Window coordinates (y down) of a world point. */
    private static Vector3 screen(GpuBattleView view, Vector3 world) {
        BoardProjectionCamera camera = view.boardCamera.camera;
        Vector3 point = camera.project(world.cpy(), 0, 0, camera.viewportWidth, camera.viewportHeight);
        point.y = Gdx.graphics.getHeight() - point.y;
        return point;
    }

    static void render(GpuBattleView view) {
        for (int frame = 0; frame < 3; frame++) {
            view.render();
        }
    }

    /**
     * Chooses the graphics on the Swing thread, as the menus do, and renders until the rebuild the choice starts behind
     * the loading stage commits.
     */
    static void graphics(GpuBattleView view, BoardGraphics graphics) throws Exception {
        SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setBoardGraphics(graphics));
        var terrain = (GpuTerrain) field(view, "terrain");
        long deadline = System.nanoTime() + 120_000_000_000L;
        // The choice reaches the terrain on a later frame; then its rebuild runs behind the loading stage.
        while (terrain.columns() != (graphics == BoardGraphics.CLASSIC)) {
            assertTrue(System.nanoTime() < deadline, "The graphics switch reaches the terrain");
            view.render();
        }
        GpuBoardTestUi.present(view);
        render(view);
    }

    /** The terrain's draws, by source and family, over one frame of the view ({@link GpuDrawCallAudit#totals}). */
    private static Map<String, Integer> audit(GpuBattleView view) {
        try (var audit = new GpuDrawCallAudit(view)) {
            view.render();
            return audit.totals();
        }
    }

    /**
     * In the Tactical View the terrain draws no scatter, grass, water, placed plant or other placed object, building
     * model or packed prop (fuel tank, industry), only the kept ones and a woods cover's look (its trees and its skin), while the Enhanced bridge, relief geometry
     * (GpuTerrain#builtBridge), stays drawn with the opaque terrain; a click on any hex picks the same hex and floor, and
     * the overlays and labels drape on the same surfaces, as with the fluff shown: the rule hides at draw time only.
     */
    /** A laser's impact shows on the icon it strikes: the scene's effects see the board beneath the icons. */
    private static void verifyEffectsOverIcons(GpuBattleView view, BoardScene scene) {
        Vector3 icon = screen(view, ENEMY, new Vector3());
        Pixmap plain = frame(view, true);
        assertEquals(0, glowing(plain, icon), "Nothing glows on the icon before the shot");
        plain.dispose();
        var playback = (UnitPlayback) field(view, "playback");
        playback.accept(List.of(UnitPlaybackFixture.attack(unit(scene, OWN), unit(scene, ENEMY), ResolvedAttack.Kind.SHOT, true)),
              scene, ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        int lit = 0;
        // Inside create() a frame's delta stays 0: the test steps the clock.
        while (playback.attack() != null) {
            playback.advance(.05, UnitMotion.Speed.NORMAL);
            Pixmap shot = frame(view, true);
            int glow = glowing(shot, icon);
            if (glow > lit) {
                lit = glow;
                capture("tactical-view-shot.png");
            }
            shot.dispose();
        }
        render(view);
        assertTrue(lit > 10, "The impact shows on the icon: " + lit);
    }

    /** A collapse warning on a unit's hex shows, on the unit's ring. */
    private static void verifyMarkerOverIcons(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        Pixmap plain = frame(view, true);
        var warning = List.of(new TacticalMark.CollapseWarning(ENEMY));
        SwingUtilities.invokeAndWait(() -> {
            fixture.view.addMarks(warning);
            fixture.source.refresh();
        });
        Pixmap marked = frame(view, true);
        capture("tactical-view-marker.png");
        Vector3 coin = view.boardCamera.camera.project(((GpuMarkers) field(view, "markers")).locatedInstances().values()
              .iterator().next().transform.getTranslation(new Vector3()));
        int shown = difference(plain, marked, new Vector3(coin.x, Gdx.graphics.getHeight() - coin.y, 0));
        plain.dispose();
        marked.dispose();
        SwingUtilities.invokeAndWait(() -> {
            fixture.view.removeMarks(warning);
            fixture.source.refresh();
        });
        render(view);
        assertTrue(shown > 40, "The warning shows on an occupied hex: " + shown);
    }

    /** A declared attack's line ends on top of its target's icon. */
    private static void verifyAttackLineOverIcons(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        Pixmap plain = frame(view, true);
        SwingUtilities.invokeAndWait(() -> {
            fixture.game.addAction(new WeaponAttackAction(fixture.entity.getId(), 2,
                  fixture.entity.getEquipmentNum(fixture.entity.getWeaponList().getFirst())));
            fixture.view.refreshAttacks();
            fixture.source.refresh();
        });
        Pixmap lined = frame(view, true);
        capture("tactical-view-attack-line.png");
        int shown = difference(plain, lined, screen(view, ENEMY, new Vector3()));
        plain.dispose();
        lined.dispose();
        SwingUtilities.invokeAndWait(() -> {
            fixture.game.clearActions();
            fixture.view.refreshAttacks();
            fixture.source.refresh();
        });
        render(view);
        assertTrue(shown > 40, "The attack line shows over the icon: " + shown);
    }

    /** The pixels within 24 of a window point (y down), inside a unit's base, in the fire colours no icon or ground has. */
    private static int glowing(Pixmap frame, Vector3 point) {
        int count = 0;
        for (int dy = -24; dy <= 24; dy++) {
            for (int dx = -24; dx <= 24; dx++) {
                int rgba = pixel(frame, point.x + dx, Gdx.graphics.getHeight() - point.y + dy);
                int red = rgba >>> 24, green = (rgba >>> 16) & 255, blue = (rgba >>> 8) & 255;
                if (red > green + 60 && red > blue + 60) { count++; }
            }
        }
        return count;
    }

    private static void verifyScatterHidden(GpuBattleView view, GpuTerrain terrain, BoardScene scene) throws Exception {
        Map<String, Integer> drawn;
        int decks;
        try (var audit = new GpuDrawCallAudit(view)) {
            view.render();
            drawn = audit.totals();
            decks = audit.draws("opaque", Attribute.getAttributeType("boardBridgeDeck"));
        }
        for (String source : List.of("grass", "scatter")) {
            assertEquals(0, drawn.getOrDefault(source, 0), "The Tactical View draws no " + source + ": " + drawn);
        }
        if (!terrain.columns()) {
            assertTrue(decks > 0, "The bridge's deck is drawn with the opaque terrain: " + drawn);
            assertTrue(drawn.getOrDefault("instanced-trees", 0) > 0, "The Tactical View draws the woods' trees: " + drawn);
        }
        List<Object> hidden = new ArrayList<>(), shown = new ArrayList<>();
        for (boolean clean : new boolean[] { true, false }) {
            terrain.setScatterHidden(clean);
            for (BoardScene.Tile tile : scene.tiles()) {
                Vector3 centre = BoardGeometry.center(tile.coords(), 0);
                var down = new Ray(new Vector3(centre.x, centre.y, 1000), new Vector3(0, 0, -1));
                List<Object> picks = clean ? hidden : shown;
                picks.add(terrain.selectionHit(scene, down));
                picks.add(terrain.walkableHit(scene, down));
                picks.add(terrain.tacticalSurface(tile.coords()));
            }
        }
        terrain.setScatterHidden(true);
        assertEquals(shown, hidden, "Hiding the scatter changes no pick and no draped surface");
    }

    private static void capture(String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }

    /** The columns are lit before the atmosphere composite, so the shared field of view darkens only unseen art. */
    private static void verifyFieldOfView(GpuBattleView view, BoardScene scene) {
        Slider darkness = GpuBoardTestUi.tuning(view, "FoV darkness");
        float[] darkened = artLuminance(view, scene);
        darkness.setValue(0);
        render(view);
        float[] plain = artLuminance(view, scene);
        darkness.setValue(GpuFieldOfView.FOV_DARKNESS * 100);
        render(view);
        System.out.printf("Tileset art luminance with/without FoV darkness: seen %.3f/%.3f, unseen %.3f/%.3f on %s%n",
              darkened[0], plain[0], darkened[1], plain[1], Gdx.gl.glGetString(GL20.GL_RENDERER));
        assertTrue(plain[0] > 0 && plain[1] > 0, "Both seen and unseen hexes must be sampled");
        assertEquals(plain[0], darkened[0], .02f, "Seen hexes keep their lit colors");
        assertTrue(darkened[1] < plain[1] * .9f, "Unseen hexes darken");
        assertTrue(darkened[1] < darkened[0], "An unseen hex is darker than a seen one");
    }

    /** Mean luminance of opaque tileset art pixels inside their hexes: {seen, unseen}, away from units and labels. */
    private static float[] artLuminance(GpuBattleView view, BoardScene scene) {
        Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight());
        try {
            float[] sum = new float[2];
            int[] count = new int[2];
            for (BoardScene.Tile tile : scene.tiles()) {
                var art = tile.tileset();
                var visibility = GpuFieldOfViewTest.at(scene.fieldOfView(), tile.coords()).visibility();
                boolean seen = visibility == BoardFieldOfView.Visibility.VISIBLE;
                if (art == null || (!seen && visibility != BoardFieldOfView.Visibility.BLOCKED) || scene.units()
                      .stream().anyMatch(unit -> unit.location().coords().distance(tile.coords()) <= 1)) {
                    continue;
                }
                for (int y = 0; y < art.height() * 2 / 3; y += 2) {
                    for (int x = 0; x < art.width(); x += 2) {
                        Vector3 offset = offset(art, x, y);
                        if (!opaque(art, x, y) || !inside(tile.coords(), offset, 1)) {
                            continue;
                        }
                        Vector3 point = screen(view, tile.coords(), offset);
                        int rgba = pixel(frame, point.x, Gdx.graphics.getHeight() - point.y);
                        int index = seen ? 0 : 1;
                        sum[index] += (.2126f * ((rgba >>> 24) & 255) + .7152f * ((rgba >>> 16) & 255)
                              + .0722f * ((rgba >>> 8) & 255)) / 255f;
                        count[index]++;
                    }
                }
            }
            return new float[] { count[0] == 0 ? 0 : sum[0] / count[0], count[1] == 0 ? 0 : sum[1] / count[1] };
        } finally {
            frame.dispose();
        }
    }

    /** Board offset from the hex centre of a pixel of its tileset art, which spans the hex's tile rectangle. */
    private static Vector3 offset(BoardScene.Pixels art, int x, int y) {
        return new Vector3(((x + .5f) / art.width() - .5f) * BoardGeometry.WIDTH,
              (.5f - (y + .5f) / art.height()) * BoardGeometry.HEIGHT, 0);
    }

    private static boolean opaque(BoardScene.Pixels art, int x, int y) {
        return (art.rgba(y * art.width() + x) & 255) >= 240;
    }

    private static boolean inside(Coords coords, Vector3 offset, float margin) {
        return BoardGeometry.contains(coords, BoardGeometry.centerX(coords) + offset.x * margin,
              BoardGeometry.centerY(coords) + offset.y * margin);
    }

    /** The back-buffer pixel that contains a point in window coordinates with y up. */
    private static int pixel(Pixmap frame, float x, float y) {
        return frame.getPixel((int) (x * frame.getWidth() / Gdx.graphics.getWidth()),
              (int) (y * frame.getHeight() / Gdx.graphics.getHeight()));
    }

    /** Window coordinates (y down) of a board offset from a hex centre; the north-up top view maps it linearly. */
    private static Vector3 screen(GpuBattleView view, Coords coords, Vector3 offset) {
        float zoom = view.boardCamera.camera.zoom;
        return view.screenPosition(coords).add(offset.x / zoom, -offset.y / zoom, 0);
    }

    /** Art pixels of the Classic columns pick their own hex; a click on an icon picks its unit. */
    private static void verifyPicking(GpuBattleView view, BoardScene scene) throws Exception {
        for (Coords coords : List.of(BUILDING, BRIDGE, FUEL_TANK)) {
            var art = scene.tile(coords).tileset();
            // The opaque art pixel farthest from the centre that still lies well inside its own hex.
            Vector3 edge = null;
            for (int y = 0; y < art.height(); y++) {
                for (int x = 0; x < art.width(); x++) {
                    Vector3 offset = offset(art, x, y);
                    boolean farther = edge == null || offset.len2() > edge.len2();
                    if (farther && opaque(art, x, y) && inside(coords, offset, 1.1f)) {
                        edge = offset;
                    }
                }
            }
            assertNotNull(edge, "Opaque tileset art at " + coords);
            assertEquals(coords, pick(view, screen(view, coords, edge)), "An art pixel picks its own hex");
            assertEquals(coords, pick(view, view.screenPosition(coords)));
        }
        for (Coords coords : List.of(OWN, ENEMY)) {
            assertEquals(coords, pick(view, view.screenPosition(coords)), "A click on the icon picks its unit");
        }
    }

    private static Coords pick(GpuBattleView view, Vector3 point) throws Exception {
        Object input = field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pick", int.class, int.class);
        pick.setAccessible(true);
        return (Coords) pick.invoke(input, Math.round(point.x), Math.round(point.y));
    }

    /**
     * The b11 mock-up battle (S/b11/tactical/mock-*.png) on its board: own Atlas (shut down, hot, damaged, done),
     * Archer, Quickdraw (the actor) and a SOAR VTOL at elevation 5 (spotting in the firing phase), enemy Warwolf with
     * its Guardian ECM on, a prone, damaged Dervish, an Exterminator, a Bulldog with its turret turned and a destroyed
     * Storm Raider. Captures the movement phase and the firing phase in the Tactical View, both graphics, with the
     * Bulldog hovered: its rear arc, its tag with weight class and skills. For review against the mock-ups; the
     * movement plan's route and the to-hit badges need the phase panels this fixture does not drive.
     */
    @Test
    void capturesTheMockUpBattleInTheMovementAndFiringPhases() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getBoardUiScale();
        BoardGraphics originalGraphics = preferences.getBoardGraphics();
        for (GamePhase phase : List.of(GamePhase.MOVEMENT, GamePhase.FIRING)) {
            try (var fixture = GpuBoardFixture.create(BoardFile.read(Path.of(MOCK_BOARD)))) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        battle(fixture, phase);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                String name = phase.isFiring() ? "firing" : "movement";
                run(fixture, failure, view -> {
                    GpuBoardTestUi.<Slider>tuning(view, "Time of day").setValue(13);
                    render(view);
                    // The 3D view for comparison: its nameplates carry the E(C)CM chip, its route the jump colour.
                    capture("tactical-mock-" + name + "-3d.png");
                    GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
                    render(view);
                    var scene = (BoardScene) field(view, "scene");
                    view.boardCamera.fit(scene);
                    render(view);
                    hover(view, fixture, BULLDOG);
                    assertEquals(8, status(fixture).arc().unitId(), "The hovered Bulldog's arcs");
                    for (BoardGraphics graphics : List.of(BoardGraphics.ENHANCED, BoardGraphics.CLASSIC)) {
                        graphics(view, graphics);
                        render(view);
                        capture("tactical-mock-" + name + "-" + graphics.name().toLowerCase() + ".png");
                    }
                    graphics(view, BoardGraphics.ENHANCED);
                    // A close look at the bases: zoomed in on the middle of the battle.
                    view.boardCamera.center(BoardGeometry.center(new Coords(12, 6), 0));
                    view.boardCamera.zoom(.45f);
                    render(view);
                    capture("tactical-mock-" + name + "-detail.png");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                });
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    preferences.setBoardUiScale(originalScale);
                    preferences.setBoardGraphics(originalGraphics);
                });
            }
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        }
    }

    /**
     * The Tactical View's Overlays panel on the mock-up battle in the firing phase, with the game's View menu (b11
     * overlay panel; the user's placement of 2026-10-10). Its View rows find the menu's items, Unit Links among them,
     * and each row drives its overlay: Status badges off keeps the VTOL's elevation pill and drops the Atlas's heat
     * pip, bars and conditions; Rear arcs off clears the hovered Bulldog's amber; Links off (the View menu's item, run
     * by the row) removes the spotting link; Level shading lightens the level-3 plateau and leaves level 0; H collapses
     * the panel. Captures the panel beside the minimap and, with the minimap closed, at the right edge, as the firing
     * phase has no fire preview and so no contacts panel, at 1920 x 1080 and 1280 x 800, and a player's choice with
     * the panel collapsed (the overlay-panel mock's frames A, C and B).
     */
    @Test
    void theOverlaysPanelDrivesItsOverlaysAndStandsBesideTheRightColumn() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getBoardUiScale();
        BoardGraphics originalGraphics = preferences.getBoardGraphics();
        Map<String, Boolean> original = new java.util.LinkedHashMap<>();
        OVERLAY_SETTINGS.forEach(key -> original.put(key, preferences.getBoolean(key)));
        try {
            for (int[] size : new int[][] { { 1920, 1080 }, { 1280, 800 } }) {
                CommonMenuBar[] menu = new CommonMenuBar[1];
                try (var fixture = GpuBoardFixture.create(BoardFile.read(Path.of(MOCK_BOARD)))) {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            // The game's menu bar, whose View items reach the client's real action handling.
                            menu[0] = CommonMenuBar.getMenuBarForGame();
                            menu[0].setPhase(GamePhase.FIRING);
                            // The defaults: every overlay as approved, coordinates, minimap and contacts panel on (a
                            // new menu bar closes the minimap, as the client opens it for the phase).
                            OVERLAY_SETTINGS.forEach(key -> preferences.setValue(key,
                                  preferences.getDefaultBoolean(key)));
                            preferences.setBoardGraphics(BoardGraphics.ENHANCED);
                            battle(fixture, GamePhase.FIRING);
                            ClientGUI gui = mock(ClientGUI.class);
                            Client client = mock(Client.class);
                            when(client.getGame()).thenReturn(fixture.game);
                            when(client.getLocalPlayer()).thenReturn(fixture.player);
                            when(gui.getClient()).thenReturn(client);
                            doCallRealMethod().when(gui).actionPerformed(any());
                            menu[0].addActionListener(gui);
                            when(gui.getMenuBar()).thenReturn(menu[0]);
                            GpuHudParitySmokeTest.attach(fixture, gui);
                            fixture.source.refresh();
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
                    run(fixture, failure, size[0], size[1], view -> overlays(view, fixture, size[0]));
                } finally {
                    SwingUtilities.invokeAndWait(() -> {
                        if (menu[0] != null) { menu[0].die(); }
                    });
                }
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                original.forEach(preferences::setValue);
                preferences.setBoardUiScale(originalScale);
                preferences.setBoardGraphics(originalGraphics);
            });
        }
    }

    private static void overlays(GpuBattleView view, GpuBoardFixture fixture, int width) throws Exception {
        GpuBoardTestUi.<Slider>tuning(view, "Time of day").setValue(13);
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        view.boardCamera.fit((BoardScene) field(view, "scene"));
        render(view);
        hover(view, fixture, BULLDOG);
        assertTrue(GpuBoardTestUi.shown(actor("tactical-overlays")), "The Tactical View shows the Overlays panel");
        for (String row : List.of("links", "envelope", "losShading", "sensorRanges", "coordinates", "types",
              "distanceRings")) {
            assertEquals(Touchable.enabled, actor("overlay-" + row).getTouchable(), row + " finds its View menu item");
        }
        assertTrue(box("links").isTicked() && box("coordinates").isTicked() && !box("levelShading").isTicked());
        if (width == 1920) {
            drive(view, fixture);
        }
        // Beside the minimap's top; at the right edge once it closes, as the contacts show only with a fire preview.
        assertBeside("minimap");
        capture("tactical-overlays-" + width + "-minimap.png");
        SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setMinimapEnabled(false));
        await(view, fixture, () -> !fixture.source.uiPreferences().minimapEnabled(), "The minimap closes");
        render(view);
        assertFalse(GpuBoardTestUi.shown(actor("contacts-panel")), "No contacts panel without a fire preview");
        var panel = stageBounds(actor("tactical-overlays"));
        assertEquals(GpuBoardTestUi.stage().getWidth() - GpuBoardHud.GAP, panel.x + panel.width, .5f,
              "At the right edge without a right-hand panel");
        capture("tactical-overlays-" + width + "-right-edge.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    /** Each Tactical View row drives its overlay; then a player's choice with the panel collapsed, and the defaults. */
    private static void drive(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        var bases = (GpuUnitBases) field(view, "unitBases");
        var scene = (BoardScene) field(view, "scene");
        GpuBattleStatus.Snapshot status = status(fixture);
        assertFalse(status.links().isEmpty(), "The VTOL spots the Exterminator");
        Coords rear = status.arc().sides().entrySet().stream()
              .filter(side -> side.getValue() == ToHitData.SIDE_REAR && side.getKey().distance(BULLDOG) == 2
                    && scene.units().stream().noneMatch(unit -> unit.location().coords().equals(side.getKey())))
              .map(Map.Entry::getKey).findFirst().orElseThrow();
        assertTrue(bases.spec(unit(scene, ATLAS)).marks().shown(), "The Atlas shows its bars");
        Pixmap arcs = frame(view, true);
        Pixmap linked = frame(view, false);

        row(view, fixture, "statusBadges", overlays -> !overlays.statusBadges());
        GpuUnitBases.BaseSpec atlas = bases.spec(unit(scene, ATLAS));
        assertEquals(5, bases.spec(unit(scene, VTOL)).elevation(), "Status badges off keeps the elevation pill");
        assertTrue(atlas.heatRgb() == 0 && !atlas.marks().shown() && atlas.conditions().isEmpty(),
              "Status badges off drops the heat pip, the bars and the conditions: " + atlas);

        row(view, fixture, "rearArcs", overlays -> !overlays.rearArcs());
        assertEquals(GpuBattleStatus.Arc.NONE, status(fixture).arc(), "Rear arcs off: no arcs");
        Pixmap plain = frame(view, true);
        int amber = difference(arcs, plain, screen(view, rear, new Vector3()));
        System.out.printf("Overlays: rear hex %s changes by %d without the arcs%n", rear, amber);
        assertTrue(amber > 20, "Rear arcs off clears the amber at " + rear + ": " + amber);

        row(view, fixture, "links", overlays -> !overlays.unitLinks());
        assertFalse(GUIPreferences.getInstance().getShowUnitLinks(), "The row ran View > Unit Links");
        Vector3 middle = view.screenPosition(VTOL).lerp(view.screenPosition(EXTERMINATOR), .5f);
        int link = difference(linked, frame(view, false), middle);
        System.out.printf("Overlays: the spotting link's mark changes by %d without links%n", link);
        assertTrue(link > 40, "Links off removes the spotting link: " + link);

        Vector3 south = new Vector3(-BoardGeometry.WIDTH * .12f, -BoardGeometry.HEIGHT * .28f, 0);
        Pixmap flat = frame(view, true);
        row(view, fixture, "levelShading", BoardSource.UiPreferences.Overlays::levelShading);
        Pixmap shaded = frame(view, true);
        float high = luminance(shaded, screen(view, PLATEAU, south)) - luminance(flat, screen(view, PLATEAU, south));
        float low = luminance(shaded, screen(view, LOWLAND, south)) - luminance(flat, screen(view, LOWLAND, south));
        System.out.printf("Overlays: level shading lightens level 3 by %.3f and level 0 by %.3f%n", high, low);
        assertTrue(high > .05f, "Level shading lightens the level-3 plateau: " + high);
        assertEquals(0, low, .01f, "and leaves the board's lowest level");
        for (Pixmap frame : List.of(arcs, linked, plain, flat, shaded)) {
            frame.dispose();
        }

        // The mock's frame B: Status badges, Rear arcs, Fields, Jamming and Coordinates off, Level shading on, and H.
        row(view, fixture, "fields", overlays -> !overlays.ecmFields());
        row(view, fixture, "jamming", overlays -> !overlays.ecmHatch());
        row(view, fixture, "links", BoardSource.UiPreferences.Overlays::unitLinks);
        GpuBoardTestUi.click("overlay-coordinates");
        await(view, fixture, () -> !GUIPreferences.getInstance().getCoordsEnabled(), "The row runs View > Coordinates");
        GpuBoardTestUi.press(KeyCommandBind.TACTICAL_OVERLAYS);
        await(view, fixture, () -> !fixture.source.uiPreferences().overlays().panelOpen(), "H collapses the panel");
        assertFalse(expanded(), "Collapsed: the header only");
        assertBeside("minimap");
        capture("tactical-overlays-1920-choice.png");

        GUIPreferences preferences = GUIPreferences.getInstance();
        SwingUtilities.invokeAndWait(() -> OVERLAY_SETTINGS.forEach(key -> preferences.setValue(key,
              preferences.getDefaultBoolean(key))));
        await(view, fixture, () -> fixture.source.uiPreferences().overlays().equals(
              BoardSource.UiPreferences.Overlays.DEFAULTS), "The defaults return");
        assertTrue(expanded(), "Expanded again");
    }

    /** Clicks a row of the Overlays panel and waits until the board's published preferences show its change. */
    private static void row(GpuBattleView view, GpuBoardFixture fixture, String row,
          Predicate<BoardSource.UiPreferences.Overlays> changed) throws Exception {
        GpuBoardTestUi.click("overlay-" + row);
        await(view, fixture, () -> changed.test(fixture.source.uiPreferences().overlays()), "The " + row + " row");
    }

    /** Publishes the board's frames until {@code changed}, as the source's timer would, then one more. */
    private static void await(GpuBattleView view, GpuBoardFixture fixture, BooleanSupplier changed, String what)
          throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!changed.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, what);
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            render(view);
        }
        SwingUtilities.invokeAndWait(fixture.source::refresh);
        render(view);
    }

    /** The Overlays panel stands left of the right column's panel {@code name}, level with its top. */
    private static void assertBeside(String name) {
        assertTrue(GpuBoardTestUi.shown(actor(name)), name + " is shown");
        var panel = stageBounds(actor("tactical-overlays"));
        var column = stageBounds(actor(name));
        assertEquals(column.y + column.height, panel.y + panel.height, .5f, "Level with the top of " + name);
        assertEquals(column.x - GpuBoardHud.GAP, panel.x + panel.width, .5f, "Left of " + name);
    }

    private static Actor actor(String name) {
        Actor actor = GpuBoardTestUi.stage().getRoot().findActor(name);
        assertNotNull(actor, name);
        return actor;
    }

    /** Whether the Overlays panel shows its rows: a collapsed panel leaves them out. */
    private static boolean expanded() {
        return GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("overlay-envelope"));
    }

    private static UiKit.Checkbox box(String row) {
        return (UiKit.Checkbox) actor("overlay-" + row);
    }

    private static com.badlogic.gdx.math.Rectangle stageBounds(Actor actor) {
        Vector2 corner = actor.localToStageCoordinates(new Vector2());
        return new com.badlogic.gdx.math.Rectangle(corner.x, corner.y, actor.getWidth(), actor.getHeight());
    }

    /** Hovers the unit at {@code coords}, as the pointer over it does, and publishes the board's next frame. */
    private static void hover(GpuBattleView view, GpuBoardFixture fixture, Coords coords) throws Exception {
        Vector3 at = view.screenPosition(coords);
        ((InputProcessor) field(view, "boardInput")).mouseMoved(Math.round(at.x), Math.round(at.y));
        SwingUtilities.invokeAndWait(fixture.source::refresh);
        render(view);
        render(view);
    }

    private static GpuBattleStatus.Snapshot status(GpuBoardFixture fixture) throws Exception {
        return ((BoardSource.Frame) field(fixture.source, "frame")).status();
    }

    /** The rendered frame, with the HUD or without it (the board alone). */
    private static Pixmap frame(GpuBattleView view, boolean board) {
        GpuBoardTestUi.stage().getRoot().setVisible(!board);
        try {
            render(view);
            return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
                  Gdx.graphics.getBackBufferHeight());
        } finally {
            GpuBoardTestUi.stage().getRoot().setVisible(true);
        }
    }

    /** Mean luminance of the 9 x 9 pixels around a window point (y down). */
    private static float luminance(Pixmap frame, Vector3 point) {
        float sum = 0;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int rgba = pixel(frame, point.x + dx, Gdx.graphics.getHeight() - point.y + dy);
                sum += (.2126f * ((rgba >>> 24) & 255) + .7152f * ((rgba >>> 16) & 255)
                      + .0722f * ((rgba >>> 8) & 255)) / 255f;
            }
        }
        return sum / 81;
    }

    /** The largest colour channel difference between two frames in the 9 x 9 pixels around a window point (y down). */
    private static int difference(Pixmap one, Pixmap other, Vector3 point) {
        int largest = 0;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                float y = Gdx.graphics.getHeight() - point.y + dy;
                int a = pixel(one, point.x + dx, y);
                int b = pixel(other, point.x + dx, y);
                for (int shift = 8; shift <= 24; shift += 8) {
                    largest = Math.max(largest, Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)));
                }
            }
        }
        return largest;
    }

    /** The mock-up's units and states on the fixture's game (S/b11/tactical/harness TacShots.setup). */
    private static void battle(GpuBoardFixture fixture, GamePhase phase) throws Exception {
        boolean fire = phase.isFiring();
        fixture.game.setPhase(phase);
        fixture.player.setColour(PlayerColour.BLUE);
        Player enemy = new Player(2, "Enemy");
        enemy.setTeam(2);
        enemy.setColour(PlayerColour.RED);
        fixture.game.addPlayer(enemy.getId(), enemy);
        Entity atlas = fixture.entity;
        atlas.setPosition(new Coords(6, 9));
        atlas.setFacing(1);
        atlas.setSecondaryFacing(1);
        atlas.setShutDown(true);
        atlas.heat = 16;
        atlas.setDone(true);
        damage(atlas, .25);
        Entity archer = unit(fixture, "Archer ARC-2R.mtf", fixture.player, 2, 5, 4, 1);
        archer.heat = 7;
        Entity quickdraw = unit(fixture, "Quickdraw QKD-8X.mtf", fixture.player, 3, fire ? 9 : 7, fire ? 7 : 10, 1);
        Entity vtol = unit(fixture, "SOAR VTOL.blk", fixture.player, 4, 12, 6, 1);
        vtol.setElevation(5);
        moved(vtol, EntityMovementType.MOVE_VTOL_RUN, 9, 12);
        vtol.setDone(true);
        Entity warwolf = unit(fixture, "Warwolf A.mtf", enemy, 5, 16, 2, 4);
        warwolf.heat = 3;
        Entity dervish = unit(fixture, "Dervish DV-11DK.mtf", enemy, 6, 12, 3, 3);
        dervish.setProne(true);
        dervish.heat = 6;
        moved(dervish, EntityMovementType.MOVE_RUN, 6, 8);
        dervish.setDone(true);
        damage(dervish, .55);
        Entity exterminator = unit(fixture, "Exterminator EXT-4A.mtf", enemy, 7, 14, 8, 4);
        Entity bulldog = unit(fixture, "Bulldog Medium Tank.blk", enemy, 8, 17, 7, 4);
        bulldog.setSecondaryFacing(5);
        moved(bulldog, EntityMovementType.MOVE_WALK, 4, 4);
        bulldog.setDone(true);
        damage(bulldog, .15);
        Entity raider = unit(fixture, "Storm Raider STM-R4.mtf", enemy, 9, 11, 9, 2);
        raider.setDoomed(true);
        raider.setDestroyed(true);
        MiscMounted suite = (MiscMounted) warwolf.addEquipment(EquipmentType.get("ISGuardianECMSuite"),
              Mek.LOC_LEFT_TORSO);
        warwolf.setGameOptions();
        suite.setModeImmediately("ECM");
        // Only the Warwolf's field: the Quickdraw's own suite off.
        for (MiscMounted misc : quickdraw.getMisc()) {
            if (misc.getType().hasFlag(MiscType.F_ECM)) {
                quickdraw.setGameOptions();
                misc.setModeImmediately("Off");
            }
        }
        if (fire) {
            moved(quickdraw, EntityMovementType.MOVE_JUMP, 4, 5);
            quickdraw.setSecondaryFacing(2);
            quickdraw.heat = 5;
            vtol.setSpotting(true);
            vtol.setSpotTargetId(exterminator.getId());
            for (Entity entity : fixture.game.getEntitiesVector()) {
                entity.setDone(entity == atlas || entity == raider);
            }
        }
        fixture.view.updateEcmList();
        fixture.view.select(quickdraw.getPosition());
        fixture.source.setVisibleArea(new Rectangle(0, 0, fixture.game.getBoard().getWidth(),
              fixture.game.getBoard().getHeight()));
        fixture.source.refresh();
    }

    private static Entity unit(GpuBoardFixture fixture, String file, Player owner, int id, int x, int y, int facing)
          throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        entity.setId(id);
        entity.setOwner(owner);
        entity.setDeployed(true);
        entity.setPosition(new Coords(x, y));
        entity.setFacing(facing);
        entity.setSecondaryFacing(facing);
        fixture.game.addEntity(entity, false);
        return entity;
    }

    private static void moved(Entity entity, EntityMovementType type, int hexes, int mp) {
        entity.moved = type;
        entity.delta_distance = hexes;
        entity.mpUsed = mp;
    }

    private static void damage(Entity entity, double share) {
        for (int location = 0; location < entity.locations(); location++) {
            int armor = entity.getArmor(location);
            if (armor > 0) { entity.setArmor((int) Math.round(armor * (1 - share)), location); }
        }
    }
}
