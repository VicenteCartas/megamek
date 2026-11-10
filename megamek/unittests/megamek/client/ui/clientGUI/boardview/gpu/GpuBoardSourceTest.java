/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.ECMEffects;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Hex;
import megamek.common.NuclearDetonation;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.SpecialHexDisplay;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.CubeCoords;
import megamek.common.board.HexTile;
import megamek.common.enums.BasementType;
import megamek.common.enums.BuildingType;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.WeaponMounted;
import megamek.common.event.GameAttackResolvedEvent;
import megamek.common.event.GameNuclearDetonationEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Aero;
import megamek.common.units.BuildingEntity;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.ProneCause;
import megamek.common.units.Targetable;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.units.UnitLocation;
import megamek.common.weapons.lasers.innerSphere.medium.ISLaserMedium;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpuBoardSourceTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void scheduledStrikePublishesOnlyItsCompleteTerrainAndPreservesPublicFlashAcrossConcealment(boolean concealed)
          throws Exception {
        Board board = new Board(8, 8, Stream.generate(Hex::new).limit(64).toArray(Hex[]::new));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            Coords coords = fixture.entity.getPosition();
            var strike = new NuclearDetonation(UUID.randomUUID(), 0, coords, 0, 10000, 109, 184, 3);
            fixture.source.takeFrame();
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.fireGameEvent(new GameNuclearDetonationEvent(fixture.game, strike, false));
                board.setHex(coords, new Hex(-1));
                fixture.source.refresh();
            });
            var during = fixture.source.takeFrame();
            assertEquals(0, during.scene().tiles().stream().filter(tile -> tile.coords().equals(coords))
                  .findFirst().orElseThrow().elevation(), "Intermediate damage must not escape the packet boundary");
            assertTrue(during.timeline().stream().noneMatch(BoardScene.Detonation.class::isInstance));
            SwingUtilities.invokeAndWait(() -> {
                var location = new UnitLocation(fixture.entity.getId(), coords, 0, 0, 0);
                var death = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.DEATH, location, location,
                      Targetable.TYPE_ENTITY, -1, "", Entity.LOC_NONE, true);
                fixture.game.fireGameEvent(new GameAttackResolvedEvent(fixture.game, death, fixture.entity, fixture.entity));
                if (concealed) {
                    fixture.game.removeEntity(fixture.entity.getId(), IEntityRemovalConditions.REMOVE_UNKNOWN);
                } else {
                    fixture.entity.setDoomed(true);
                }
                board.setHex(coords, new Hex(-3));
                fixture.source.refresh();
                fixture.game.fireGameEvent(new GameNuclearDetonationEvent(fixture.game, strike, true));
            });
            var completed = fixture.source.takeFrame();
            var timeline = completed.timeline();
            int impact = -1;
            for (int i = 0; i < timeline.size(); i++) {
                if (timeline.get(i) instanceof BoardScene.Detonation) { impact = i; }
            }
            assertTrue(impact >= 0);
            assertTrue(timeline.get(impact + 1) instanceof BoardScene.SceneUpdate);
            assertEquals(!concealed, timeline.stream().anyMatch(BoardScene.Combat.class::isInstance),
                  "Visible deaths remain queued; a concealment must not restore a hidden unit");
            var playback = new UnitPlayback();
            playback.accept(timeline, completed.scene(), ignored -> false);
            playback.advance(1f, UnitMotion.Speed.NORMAL);
            assertEquals(List.of(strike), playback.takeDetonations().stream().map(BoardScene.Detonation::result).toList(),
                  "Public flash survives the visibility reset");
            assertEquals(-3, playback.present(completed.scene()).tiles().stream().filter(tile -> tile.coords().equals(coords))
                  .findFirst().orElseThrow().elevation());
            if (concealed) {
                assertTrue(playback.present(completed.scene()).units().isEmpty());
            }
            SwingUtilities.invokeAndWait(() -> fixture.game.fireGameEvent(
                  new GameNuclearDetonationEvent(fixture.game, strike, true)));
            assertTrue(fixture.source.takeFrame().timeline().isEmpty(), "Duplicate completion must not replay the blast");
        }
    }

    @Test
    void aDeathTheServerReportsBeforeTheFallThatCausedItPlaysAsTheFallLands() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            int id = fixture.entity.getId();
            Coords start = fixture.entity.getPosition();
            Coords end = start.translated(0);
            fixture.source.takeFrame();
            SwingUtilities.invokeAndWait(() -> {
                var location = new UnitLocation(id, start, 0, 0, 0);
                fixture.game.fireGameEvent(new GameAttackResolvedEvent(fixture.game, new ResolvedAttack(UUID.randomUUID(),
                      ResolvedAttack.Kind.DEATH, location, location, Targetable.TYPE_ENTITY, -1, "", Entity.LOC_NONE, true),
                      fixture.entity, fixture.entity));
            });
            // A refresh before the movement arrives, as the source's timer makes, leaves the death waiting for it.
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(end);
                fixture.entity.setProne(ProneCause.FORCED);
                fixture.entity.setDoomed(true);
                fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, fixture.entity,
                      new Vector<>(List.of(new UnitLocation(id, end, 0, 0, 0)))));
            });
            SwingUtilities.invokeAndWait(() -> { });
            var timeline = fixture.source.takeFrame().timeline().stream()
                  .filter(event -> !(event instanceof BoardScene.SceneUpdate)).toList();
            assertEquals(2, timeline.size(), timeline.toString());
            assertTrue(timeline.getFirst() instanceof BoardScene.Movement move && move.path().getLast().coords().equals(end));
            assertTrue(timeline.getLast() instanceof BoardScene.Combat death && death.result().kind() == ResolvedAttack.Kind.DEATH
                  && death.attacker().location().coords().equals(end), "The death follows its fall, where the fall left it");
        }
    }

    @Test
    void movementPaletteCapturesEveryClientColorWithoutSharingMutableState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GUIPreferences gui = GUIPreferences.getInstance();
            Map<GpuMovePlan.Band, String> settings = Map.of(
                  GpuMovePlan.Band.WALK, GUIPreferences.BOARD_MOVE_DEFAULT_COLOR,
                  GpuMovePlan.Band.BACK, GUIPreferences.BOARD_MOVE_BACK_COLOR,
                  GpuMovePlan.Band.RUN, GUIPreferences.BOARD_MOVE_RUN_COLOR,
                  GpuMovePlan.Band.BOOST, GUIPreferences.BOARD_MOVE_MASC_COLOR,
                  GpuMovePlan.Band.SPRINT, GUIPreferences.BOARD_MOVE_SPRINT_COLOR,
                  GpuMovePlan.Band.JUMP, GUIPreferences.BOARD_MOVE_JUMP_COLOR,
                  GpuMovePlan.Band.ILLEGAL, GUIPreferences.BOARD_MOVE_ILLEGAL_COLOR);
            Map<String, Color> saved = new HashMap<>();
            Map<GpuMovePlan.Band, Integer> expected = new HashMap<>();
            try {
                settings.forEach((band, setting) -> {
                    saved.put(setting, gui.getColor(setting));
                    Color color = new Color(30 + band.ordinal() * 25, 170 - band.ordinal() * 15, 90);
                    gui.setColor(setting, color);
                    expected.put(band, color.getRGB());
                });
                BoardSource.UiPreferences captured = BoardSource.UiPreferences.capture();
                assertEquals(expected, captured.movementColors());
                settings.values().forEach(setting -> gui.setColor(setting, Color.PINK));
                assertEquals(expected, captured.movementColors(), "Published colours stay immutable");
                assertTrue(BoardSource.UiPreferences.capture().movementColors().values().stream()
                      .allMatch(rgb -> rgb == Color.PINK.getRGB()), "Every changed preference is captured");
            } finally {
                saved.forEach(gui::setColor);
            }
        });
    }

    @Test
    void armedBuildingsShowEachArmedHexOnItsRoof() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var fort = threeHexBuilding(2, new Coords(8, 8));
            var fortHexes = fort.getSecondaryPositions();
            var turret = new WeaponMounted(fort, new ISLaserMedium());
            fort.addEquipment(turret, fort.getLocationsAt(fortHexes.get(0)).getLast(), false);
            turret.setMekTurretMounted(true);
            var wall = new WeaponMounted(fort, new ISLaserMedium());
            fort.addEquipment(wall, fort.getLocationsAt(fortHexes.get(1)).getFirst(), false);
            wall.setFacing(2);
            var bunker = threeHexBuilding(3, new Coords(12, 8));
            var bunkerHexes = bunker.getSecondaryPositions();
            var gun = new WeaponMounted(bunker, new ISLaserMedium());
            bunker.addEquipment(gun, bunker.getLocationsAt(bunkerHexes.get(2)).getLast(), false);
            gun.setMekTurretMounted(true);
            SwingUtilities.invokeAndWait(() -> {
                for (var building : List.of(fort, bunker)) {
                    building.setOwner(fixture.player);
                    building.setDeployed(true);
                    fixture.game.addEntity(building, false);
                }
                fixture.source.refresh();
            });
            var scene = fixture.source.takeFrame().scene();
            assertEquals(List.of(fortHexes.get(0), fortHexes.get(1)), hexes(scene, 2),
                  "Each hex holding weapons is drawn on its own; an unarmed hex is only the board's building");
            assertEquals(List.of(bunkerHexes.get(2)), hexes(scene, 3), "A building armed in one hex is that hex alone");
            for (var unit : scene.units().stream().filter(unit -> unit.id() > 1).toList()) {
                assertEquals(List.of(unit.location().coords()), unit.footprint(), "A hex's turret, never stretched over the building");
                assertEquals(fixture.game.getBoard().getHex(unit.location().coords()).getLevel() + 2,
                      unit.location().elevation(), 1e-6, "Its roof turret stands, and its casemates hang, from its roof");
            }
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                shoot(fixture, fort, wall.getEquipmentNum(), fixture.entity, fixture.entity.getPosition());
                shoot(fixture, fixture.entity, 0, fort, fortHexes.get(0));
                shoot(fixture, fixture.entity, 0, fort, fortHexes.get(2));
                fixture.source.refresh();
            });
            var frame = fixture.source.takeFrame();
            assertTrue(frame.timeline().stream().noneMatch(BoardScene.Movement.class::isInstance),
                  "Parts in different hexes are one standing unit, never a move from one hex to another");
            var shots = frame.timeline().stream().filter(BoardScene.Combat.class::isInstance).map(BoardScene.Combat.class::cast)
                  .toList();
            assertEquals(1, shots.get(0).attacker().part(), "A wall weapon fires from its own hex");
            assertEquals(0, shots.get(1).target().part(), "A shot at an armed hex hits that hex");
            assertNull(shots.get(2).target(), "A shot at an unarmed hex has only its impact point");
            var playback = new UnitPlayback();
            playback.accept(frame.timeline(), frame.scene(), ignored -> false);
            playback.advance(.01f, UnitMotion.Speed.NORMAL);
            assertEquals(List.of(fortHexes.get(0), fortHexes.get(1)), hexes(playback.present(frame.scene()), 2),
                  "While the volley plays, the firing hex stands in for itself, never for the building's other hex");
        }
    }

    private static BuildingEntity threeHexBuilding(int id, Coords position) {
        var building = new BuildingEntity(BuildingType.MEDIUM, 1);
        building.getInternalBuilding().setBuildingHeight(2);
        for (var hex : List.of(CubeCoords.ZERO, new CubeCoords(0, 1, -1), new CubeCoords(1, 0, -1))) {
            building.getInternalBuilding().addHex(hex, 40, 0, BasementType.NONE, false);
        }
        building.refreshLocations();
        building.refreshAdditionalLocations();
        building.setId(id);
        building.setPosition(position);
        return building;
    }

    private static List<Coords> hexes(BoardScene scene, int id) {
        return scene.units().stream().filter(unit -> unit.id() == id).map(unit -> unit.location().coords()).toList();
    }

    private static void shoot(GpuBoardFixture fixture, Entity attacker, int equipment, Entity target, Coords hex) {
        var shot = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(attacker.getId(), attacker.getPosition(), 0, 0, 0), new UnitLocation(target.getId(), hex, 0, 0, 0),
              Targetable.TYPE_ENTITY, equipment, "ISMediumLaser", Entity.LOC_NONE, true);
        fixture.game.fireGameEvent(new GameAttackResolvedEvent(fixture.game, shot, attacker, target));
    }

    @Test
    void heatSnapshotsFollowGameHeatOnlyForUnitsThatTrackIt() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Entity fighter = new MekFileParser(new File("testresources/megamek/common/units/Cheetah F-11.blk")).getEntity();
            Entity supportFighter = new MekFileParser(new File("testresources/megamek/common/units/Mosquito Light Fighter.blk")).getEntity();
            var infantry = new ConvInfantry();
            infantry.setChassis("Thermal infantry");
            fighter.setId(2);
            infantry.setId(3);
            supportFighter.setId(4);
            infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
            SwingUtilities.invokeAndWait(() -> {
                for (Entity entity : List.of(fighter, infantry, supportFighter)) {
                    entity.setOwner(fixture.player);
                    entity.setPosition(new Coords(entity.getId() + 3, 6));
                    entity.setDeployed(true);
                    fixture.game.addEntity(entity, false);
                }
            });
            assertTrue(fixture.entity.tracksHeat());
            assertTrue(fighter.tracksHeat());
            assertFalse(infantry.tracksHeat());
            assertFalse(supportFighter.tracksHeat());
            for (int heat : new int[] { 0, 17, 35 }) {
                SwingUtilities.invokeAndWait(() -> {
                    fixture.entity.heat = fighter.heat = infantry.heat = supportFighter.heat = heat;
                    fixture.source.refresh();
                });
                BoardScene scene = fixture.source.takeFrame().scene();
                for (int id : new int[] { 1, 2 }) {
                    var captured = unit(scene, id);
                    assertEquals(heat, captured.heat());
                    assertEquals(heat, captured.at(captured.location()).heat());
                    assertEquals(heat, captured.withAttachment(null).heat());
                }
                assertEquals(-1, unit(scene, 3).heat(), "A non-tracking unit's heat field must not drive its appearance");
                assertEquals(-1, unit(scene, 4).heat(), "Non-tracking aircraft also use the fixed signature");
            }
        }
    }

    @Test
    void minimapNavigationAndViewportRecreationDoNotDependOnAnInactiveRenderer() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                BoardView legacy2D = fixture.legacy2DView();
                try {
                    assertNull(legacy2D.getPanel().getParent());
                    legacy2D.centerOnPointRel(1, 1);
                    Coords corner = new Coords(fixture.game.getBoard().getWidth() - 1,
                          fixture.game.getBoard().getHeight() - 1);
                    assertEquals(corner, fixture.view.getCenterRequest().coords());
                    fixture.source.refresh();
                    assertEquals(fixture.view.getCenterRequest(), fixture.source.takeFrame().centerRequest());
                    assertNull(legacy2D.getPanel().getParent());
                    var viewport = legacy2D.getComponent();
                    legacy2D.releaseLegacy2DView();
                    assertNull(legacy2D.getPanel().getParent());
                    assertEquals(corner, fixture.view.getCenterRequest().coords());
                    assertNotSame(viewport, legacy2D.getComponent(), "Switching back creates a new Legacy 2D viewport");
                } finally {
                    legacy2D.dispose();
                }
            });
        }
    }

    @Test
    void captureNavigationAndBoardExportsDoNotNeedALegacy2DViewport() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean softCenter = preferences.getSoftCenter();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                for (boolean smooth : new boolean[] { false, true }) {
                    preferences.setSoftCenter(smooth);
                    fixture.view.centerOn(fixture.entity);
                    fixture.source.refresh();
                    var frame = fixture.source.takeFrame();
                    assertEquals(fixture.entity.getPosition(), frame.centerRequest().coords());
                    assertEquals(fixture.entity.getId(), frame.centerRequest().entityId());
                    assertFalse(frame.scene().tiles().isEmpty());
                    assertFalse(frame.scene().units().isEmpty());
                }
                BufferedImage image = fixture.view.getEntireBoardImage(false);
                assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setSoftCenter(softCenter));
        }
    }

    @Test
    void deploymentEcmReachesTheSnapshotWithItsSideForFriendlyAndEnemyUnits() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            fixture.addEcm();
            Player enemy = new Player(1, "Enemy deployment");
            enemy.setTeam(2);
            for (Player owner : List.of(fixture.player, enemy)) {
                SwingUtilities.invokeAndWait(() -> {
                    if (owner == enemy) {
                        fixture.game.addPlayer(enemy.getId(), enemy);
                    }
                    fixture.entity.setOwner(owner);
                    owner.setStartingPos(Board.START_ANY);
                    fixture.game.setPhase(GamePhase.DEPLOYMENT);
                    fixture.view.markDeploymentHexesFor(fixture.entity);
                    fixture.view.updateEcmList();
                    fixture.source.refresh();
                });
                BoardScene scene = fixture.source.takeFrame().scene();
                int kind = owner == enemy ? BoardElectronicWarfare.ENEMY_ECM : BoardElectronicWarfare.OWN_ECM;
                assertEquals(kind, scene.electronicWarfare().hex(new Coords(4, 4)) & 15, owner.getName());
                assertEquals(owner != enemy, scene.electronicWarfare().sources().getFirst().own());
                assertTrue(scene.tactical().fills().stream().noneMatch(fill ->
                      fill.argb() == ECMEffects.getECMColor(owner).getRGB()), "No player-colour wash on the 3D board");
            }
        }
    }

    @Test
    void measurementOutlineMatchesTheMarkedHexAtCaptureResolution() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords coords = new Coords(4, 4);
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.checkLOS(coords);
                fixture.source.refresh();
            });
            java.awt.geom.Rectangle2D outline = null;
            for (var fill : fixture.source.takeFrame().scene().tactical().fills()) {
                if ((fill.argb() & 0xFFFFFF) == (Color.RED.getRGB() & 0xFFFFFF)) {
                    var bounds = fill.shape().getBounds2D();
                    outline = outline == null ? bounds : outline.createUnion(bounds);
                }
            }
            assertTrue(outline != null, "The measurement cursor must be captured as native geometry");
            assertEquals(coords.getX() * .75f * BoardGeometry.TILE_WIDTH, outline.getX(), 1);
            assertEquals(coords.getY() * BoardGeometry.TILE_HEIGHT, outline.getY(), 1);
            assertTrue(outline.getWidth() >= BoardGeometry.TILE_WIDTH - 1, "The red cursor must reach both sides of its hex");
            assertTrue(outline.getHeight() >= 71, "The red cursor must reach the top and bottom edges");
        }
    }

    /** A generic building's kit follows its weight and class, whatever roof art a tileset has for it: here, level 4. */
    private static final String HARDENED_KIT = "buildings/standard/building-hard";

    @Test
    void aBuildingsKitIgnoresTheTwoDTilesetAndItsHeightLabelIsRaised() throws Exception {
        var preferences = PreferenceManager.getClientPreferences();
        String originalTileset = preferences.getMapTileset();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setMapTileset("legacy2D.tileset");
                Coords coords = new Coords(2, 2);
                Hex hex = new Hex(0);
                hex.addTerrain(new Terrain(Terrains.BUILDING, 4, true, 0));
                hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 3));
                hex.addTerrain(new Terrain(Terrains.BLDG_CF, 100));
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
                var tile = fixture.source.takeFrame().scene().tile(coords);
                assertEquals(1, tile.features().size());
                assertEquals(HARDENED_KIT, tile.features().getFirst().asset());
                assertTrue(tile.text().stream().anyMatch(label -> label.elevation() == 3));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setMapTileset(originalTileset));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "desert", "snow", "volcano", "dirt", "lunar" })
    void coexistingStructuresUseTheirOwnKitsAndHeights(String theme) throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords coords = new Coords(2, 2);
                Hex hex = new Hex(0);
                hex.addTerrain(new Terrain(Terrains.BUILDING, 4, true, 0));
                hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 4));
                hex.addTerrain(new Terrain(Terrains.BLDG_CF, 100));
                hex.addTerrain(new Terrain(Terrains.FUEL_TANK, 2, true, 0));
                hex.addTerrain(new Terrain(Terrains.FUEL_TANK_ELEV, 2));
                hex.addTerrain(new Terrain(Terrains.FUEL_TANK_CF, 40));
                hex.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 100));
                hex.addTerrain(new Terrain(Terrains.INDUSTRIAL, 3));
                hex.setTheme(theme);
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
                var tile = fixture.source.takeFrame().scene().tile(coords);
                assertEquals(3, tile.features().size());
                assertTrue(tile.detailedGround(), "Modeled structures must preserve native ground in theme: " + theme);
                assertTrue(tile.features().stream().anyMatch(feature -> HARDENED_KIT.equals(feature.asset())
                      && feature.height() == 4 && feature.kind() == BoardScene.FeatureKind.BUILDING));
                assertTrue(tile.features().stream().anyMatch(feature -> feature.asset().contains("/fuel_tanks/fuel_tank_medium_")
                      && feature.height() == 2 && feature.kind() == BoardScene.FeatureKind.FUEL_TANK));
                assertTrue(tile.features().stream().anyMatch(feature -> feature.asset().contains("/misc/heavy_industrial_")
                      && feature.height() == 3 && feature.kind() == BoardScene.FeatureKind.INDUSTRIAL));
            });
        }
    }

    @Test
    void hexTextIsSeparateFromTerrainPixelsAndTracksPreferences() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean coords = preferences.getCoordsEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setCoordsEnabled(true);
                fixture.source.refresh();
            });
            BoardScene.Tile labeled = fixture.source.takeFrame().scene().tile(new Coords(0, 0));
            assertTrue(labeled.text().stream().anyMatch(label -> label.text().equals("0101")));
            SwingUtilities.invokeAndWait(() -> {
                preferences.setCoordsEnabled(false);
                fixture.source.refresh();
            });
            BoardScene.Tile unlabeled = fixture.source.takeFrame().scene().tile(new Coords(0, 0));
            assertFalse(unlabeled.text().stream().anyMatch(label -> label.text().equals("0101")));
            assertTrue(samePixels(labeled.tileset(), unlabeled.tileset()));
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setCoordsEnabled(coords));
        }
    }

    /**
     * View > Hex Types reaches the game's 3D board the way Hex Coords does: the published labels of a level-1
     * building trade its roof's HEIGHT label for the type block, and the render thread's snapshot carries the setting.
     */
    @Test
    void hexTypesReplaceTheBottomLabelsOfTheGameBoardAndTrackTheSetting() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean types = preferences.getHexTypesEnabled();
        Board board = Board.createEmptyBoard(8, 8);
        Coords building = new Coords(2, 2);
        board.setHex(building, new Hex(1, "building:1;bldg_elev:2;bldg_cf:15", ""));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            for (boolean on : new boolean[] { false, true, false }) {
                SwingUtilities.invokeAndWait(() -> {
                    if (preferences.getHexTypesEnabled() != on) { preferences.toggleHexTypes(); }
                    fixture.source.refresh();
                });
                var frame = fixture.source.takeFrame();
                List<String> labels = frame.scene().tile(building).text().stream()
                      .map(BoardHexText::text)
                      .filter(text -> !text.equals(building.getBoardNum())).toList();
                assertEquals(on ? List.of("LEVEL 1", "LIGHT▲2") : List.of("LEVEL 1", "HEIGHT 2"), labels);
                assertEquals(on, fixture.source.uiPreferences().overlays().hexTypes());
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (preferences.getHexTypesEnabled() != types) { preferences.toggleHexTypes(); }
            });
        }
    }

    @Test
    void cliffExitsSurviveTacticalArtworkRefreshes() throws Exception {
        Board board = new Board();
        board.load(new File("testresources/megamek/client/ui/clientGUI/boardview/gpu/cliffs.board"));
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean coords = preferences.getCoordsEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            for (boolean labels : new boolean[] { true, false }) {
                SwingUtilities.invokeAndWait(() -> {
                    preferences.setCoordsEnabled(labels);
                    fixture.source.refresh();
                });
                BoardScene scene = fixture.source.takeFrame().scene();
                assertEquals(56, scene.tile(new Coords(8, 12)).cliffTopExits());
                assertEquals(24, scene.tile(new Coords(9, 12)).cliffTopExits());
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setCoordsEnabled(coords));
        }
    }

    @Test
    void gpuArtworkDoesNotIncludeLegacy2DGeneratedShadows() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean shadows = preferences.getShadowMap();
        boolean ambient = preferences.getAOHexShadows();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setShadowMap(true);
                preferences.setAOHexShadows(true);
                fixture.source.refresh();
            });
            BoardScene before = fixture.source.takeFrame().scene();
            SwingUtilities.invokeAndWait(() -> {
                preferences.setShadowMap(false);
                preferences.setAOHexShadows(false);
                fixture.source.refresh();
            });
            BoardScene after = fixture.source.takeFrame().scene();
            assertTrue(samePixels(before.units().getFirst().image(), after.units().getFirst().image()));
            assertTrue(samePixels(before.tile(new Coords(0, 0)).tileset(), after.tile(new Coords(0, 0)).tileset()));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setShadowMap(shadows);
                preferences.setAOHexShadows(ambient);
            });
        }
    }

    @Test
    void tileResolutionDoesNotDependOnLegacy2DZoom() throws Exception {
        int originalZoom = GUIPreferences.getInstance().getMapZoomIndex();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardScene.Tile tile = fixture.source.takeFrame().scene().tile(new Coords(0, 0));
            SwingUtilities.invokeAndWait(() -> {
                BoardView legacy2D = fixture.legacy2DView();
                try {
                    legacy2D.getComponent();
                    legacy2D.zoomOut();
                    float scale = legacy2D.getScale();
                    Dimension size = new Dimension(legacy2D.getHexSize());
                    fixture.source.refresh();
                    assertEquals(scale, legacy2D.getScale());
                    assertEquals(size, legacy2D.getHexSize());
                } finally {
                    legacy2D.dispose();
                }
            });
            BoardScene.Tile after = fixture.source.takeFrame().scene().tile(new Coords(0, 0));
            assertEquals(HexTile.WIDTH, after.tileset().width());
            assertEquals(HexTile.HEIGHT, after.tileset().height());
            assertTrue(samePixels(tile.tileset(), after.tileset()));
        } finally {
            SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setMapZoomIndex(originalZoom));
        }
    }

    @Test
    void hullDownIsCapturedAsPoseWithoutChangingModelStructure() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var before = fixture.source.takeFrame().scene().units().getFirst();
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setHullDown(true);
                fixture.source.refresh();
            });
            var after = fixture.source.takeFrame().scene().units().getFirst();
            assertEquals(false, before.location().hullDown());
            assertEquals(true, after.location().hullDown());
            assertTrue(after.model().state().pose().hullDown());
            assertEquals(before.model().state().structure(), after.model().state().structure());
        }
    }

    @Test
    void capturesCurrentOccupiedHeightWithoutChangingArtwork() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardScene.Unit standing = fixture.source.takeFrame().scene().units().getFirst();
            assertEquals(2, standing.height());
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setProne(true);
                fixture.source.refresh();
            });
            BoardScene.Unit prone = fixture.source.takeFrame().scene().units().getFirst();
            assertEquals(fixture.entity.height() + 1, prone.height());
            assertEquals(1, prone.height());
            assertSame(standing.image(), prone.image());
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setProne(false);
                fixture.entity.setWeight(150);
                fixture.source.refresh();
            });
            assertEquals(3, fixture.source.takeFrame().scene().units().getFirst().height());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { -2, 0, 2, 10 })
    void airborneUnitsFloatAboveTheirHex(int groundLevel) throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Entity vtol = new MekFileParser(new File("testresources/megamek/common/units/Cobra Transport VTOL.blk"))
                  .getEntity();
            Entity fighter = new MekFileParser(new File("testresources/megamek/common/units/Cheetah F-11.blk"))
                  .getEntity();
            vtol.setId(2);
            fighter.setId(3);
            SwingUtilities.invokeAndWait(() -> {
                for (Entity entity : List.of(vtol, fighter)) {
                    entity.setOwner(fixture.player);
                    entity.setDeployed(true);
                    fixture.game.addEntity(entity, false);
                }
                vtol.setPosition(new Coords(4, 4));
                fixture.game.getBoard().setHex(vtol.getPosition(), new Hex(groundLevel));
                vtol.setElevation(3);
                fighter.setPosition(new Coords(6, 6));
                fixture.game.getBoard().setHex(fighter.getPosition(), new Hex(groundLevel));
                fighter.setAltitude(1);
                fixture.source.refresh();
            });
            BoardScene scene = fixture.source.takeFrame().scene();
            BoardScene.Unit flyingVtol = unit(scene, 2);
            assertTrue(flyingVtol.airborne(), "A VTOL at elevation 3 flies");
            assertEquals(1, flyingVtol.height(), "A flying VTOL keeps its own one-level token");
            assertEquals(3 + fixture.game.getBoard().getHex(new Coords(4, 4)).getLevel(),
                  flyingVtol.location().elevation(), 0.001f, "A VTOL floats on its hex-relative elevation");
            BoardScene.Unit flyingFighter = unit(scene, 3);
            assertTrue(flyingFighter.airborne(), "A fighter at altitude 1 flies");
            assertEquals(1, flyingFighter.height());
            assertEquals(groundLevel + 1, flyingFighter.location().elevation(), 0.001f,
                  "Aerospace visuals float above the hex; the airborne elevation sentinel is never drawn");

            SwingUtilities.invokeAndWait(() -> {
                vtol.setElevation(0);
                ((Aero) fighter).land();
                fixture.source.refresh();
            });
            BoardScene landed = fixture.source.takeFrame().scene();
            assertFalse(unit(landed, 2).airborne(), "A landed VTOL does not float");
            assertFalse(unit(landed, 3).airborne(), "A landed fighter does not float");
            assertEquals(fixture.game.getBoard().getHex(new Coords(4, 4)).getLevel(),
                  unit(landed, 2).location().elevation(), 0.001f);
            assertEquals(fixture.game.getBoard().getHex(new Coords(6, 6)).getLevel(),
                  unit(landed, 3).location().elevation(), 0.001f);
        }
    }

    private static BoardScene.Unit unit(BoardScene scene, int id) {
        return scene.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    @Test
    void airborneFighterMovementPlaysAtItsAltitude() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Entity fighter = new MekFileParser(new File("testresources/megamek/common/units/Cheetah F-11.blk"))
                  .getEntity();
            fighter.setId(4);
            SwingUtilities.invokeAndWait(() -> {
                fighter.setOwner(fixture.player);
                fighter.setDeployed(true);
                fixture.game.addEntity(fighter, false);
                fighter.setPosition(new Coords(6, 6));
                fixture.game.getBoard().setHex(new Coords(6, 6), new Hex(2));
                fixture.game.getBoard().setHex(new Coords(7, 6), new Hex(10));
                fighter.setAltitude(5);
                fixture.source.refresh();
            });
            // Path steps of a flying aerospace carry the airborne elevation sentinel, as the server sends them.
            Vector<UnitLocation> path = new Vector<>();
            path.add(new UnitLocation(4, new Coords(6, 6), 0, Aero.AERO_EFFECTIVE_ELEVATION, 0));
            path.add(new UnitLocation(4, new Coords(7, 6), 1, Aero.AERO_EFFECTIVE_ELEVATION, 0));
            SwingUtilities.invokeAndWait(() -> {
                fighter.setPosition(new Coords(7, 6));
                fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, fighter, path));
            });
            SwingUtilities.invokeAndWait(() -> { });
            GpuBoardSource.Frame frame = fixture.source.takeFrame();
            assertEquals(1, frame.movements().size());
            for (BoardScene.Waypoint point : frame.movements().getFirst().path()) {
                assertEquals(5 + fixture.game.getBoard().getHex(point.coords()).getLevel(), point.elevation(), 0.001f,
                      "Each flight waypoint adds its own hex elevation");
                assertEquals(BoardScene.AeroState.AIRBORNE, point.aeroState());
            }
        }
    }

    /** A move that ends in its own hex plays, though the scene shows the unit there already (the late walk rule). */
    @Test
    void aTurnInPlacePlaysWhereTheSceneAlreadyShowsItsEnd() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Entity mek = fixture.entity;
            Coords at = mek.getPosition();
            Entity before = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
            before.setId(mek.getId());
            before.setPosition(at);
            before.setFacing(0);
            SwingUtilities.invokeAndWait(() -> {
                mek.setFacing(3);
                mek.setSecondaryFacing(3);
                fixture.source.refresh();
            });
            fixture.source.takeFrame();
            Vector<UnitLocation> path = new Vector<>();
            for (int facing = 1; facing <= 3; facing++) { path.add(new UnitLocation(mek.getId(), at, facing, 0, 0)); }
            SwingUtilities.invokeAndWait(() -> fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, mek, path, before)));
            SwingUtilities.invokeAndWait(() -> { });
            GpuBoardSource.Frame frame = fixture.source.takeFrame();
            assertEquals(1, frame.movements().size(), "The turn plays");
            assertEquals(3, frame.movements().getFirst().path().getLast().facing(), .001f);
        }
    }

    @Test
    void landingFighterPlaybackDescendsFromItsFlightAltitude() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Entity fighter = new MekFileParser(new File("testresources/megamek/common/units/Cheetah F-11.blk"))
                  .getEntity();
            Entity flying = new MekFileParser(new File("testresources/megamek/common/units/Cheetah F-11.blk"))
                  .getEntity();
            fighter.setId(5);
            flying.setId(5);
            flying.setPosition(new Coords(5, 5));
            flying.setAltitude(5);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getBoard().setHex(new Coords(5, 5), new Hex(10));
                fixture.game.getBoard().setHex(new Coords(6, 5), new Hex(10));
                fixture.game.getBoard().setHex(new Coords(6, 6), new Hex(10));
                fighter.setOwner(fixture.player);
                fighter.setDeployed(true);
                fixture.game.addEntity(fighter, false);
                fighter.setPosition(new Coords(6, 6));
                ((Aero) fighter).land();
                fixture.source.refresh();
            });
            fixture.source.takeFrame(); // Discard setup events from raising the terrain under the fixture's Atlas.
            // The whole move carries the airborne sentinel except the step that sets the fighter down.
            Vector<UnitLocation> path = new Vector<>();
            path.add(new UnitLocation(5, new Coords(5, 5), 0, Aero.AERO_EFFECTIVE_ELEVATION, 0));
            path.add(new UnitLocation(5, new Coords(6, 5), 1, Aero.AERO_EFFECTIVE_ELEVATION, 0));
            path.add(new UnitLocation(5, new Coords(6, 6), 1, 0, 0));
            SwingUtilities.invokeAndWait(() -> fixture.game.fireGameEvent(
                  new GameEntityChangeEvent(fixture.game, fighter, path, flying)));
            SwingUtilities.invokeAndWait(() -> { });
            GpuBoardSource.Frame frame = fixture.source.takeFrame();
            assertEquals(1, frame.movements().size());
            List<BoardScene.Waypoint> played = frame.movements().getFirst().path();
            assertEquals(15, played.getFirst().elevation(), 0.001f,
                  "A landing move starts at the flight altitude above its starting hex");
            for (BoardScene.Waypoint point : played) {
                assertTrue(point.elevation() < Aero.AERO_EFFECTIVE_ELEVATION,
                      "No playback point keeps the airborne sentinel");
            }
            assertEquals(fixture.game.getBoard().getHex(new Coords(6, 6)).getLevel(),
                  played.getLast().elevation(), 0.001f, "The landing step plays at its hex elevation");
            assertEquals(BoardScene.AeroState.LANDED, played.getLast().aeroState());
            assertTrue(played.subList(0, played.size() - 1).stream()
                  .allMatch(point -> point.aeroState() == BoardScene.AeroState.AIRBORNE),
                  "A final landed Entity cannot deploy supports during its earlier flight steps");
        }
    }

    @Test
    void unitArtworkIsNotBakedIntoTiles() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords original = fixture.entity.getPosition();
            BoardScene.Tile occupied = fixture.source.takeFrame().scene().tile(original);
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(new Coords(10, 10));
                fixture.view.redrawEntity(fixture.entity);
                fixture.source.refresh();
            });
            BoardScene.Tile empty = fixture.source.takeFrame().scene().tile(original);
            assertTrue(samePixels(occupied.tileset(), empty.tileset()));
        }
    }

    @Test
    void movingUnitGhostIsNotBakedIntoTiles() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean moveStep = preferences.getShowMoveStep();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords destination = new Coords(6, 5);
            BoardScene.Tile before = fixture.source.takeFrame().scene().tile(destination);
            Vector<UnitLocation> path = new Vector<>();
            path.add(new UnitLocation(fixture.entity.getId(), fixture.entity.getPosition(), 0, 0, 0));
            path.add(new UnitLocation(fixture.entity.getId(), destination, 1, 0, 0));
            SwingUtilities.invokeAndWait(() -> {
                preferences.setShowMoveStep(true);
                fixture.entity.setPosition(destination);
                fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, fixture.entity, path));
                fixture.source.refresh();
            });
            BoardScene.Tile after = fixture.source.takeFrame().scene().tile(destination);
            assertTrue(samePixels(before.tileset(), after.tileset()),
                  "The Legacy 2D moving-unit ghost must not be baked into the captured terrain");
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setShowMoveStep(moveStep));
        }
    }

    @Test
    void carriesRangeDeploymentAndStrafingPaintersAndRemovesStaleMarkings() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords coords = new Coords(3, 4);
            BoardScene.Tile before = fixture.source.takeFrame().scene().tile(coords);
            var range = new TacticalMark.Envelope(coords, 63, Color.MAGENTA);
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.addMarks(List.of(range));
                fixture.source.refresh();
            });
            BoardScene.Tile ranged = fixture.source.takeFrame().scene().tile(coords);
            assertSame(before.tileset(), ranged.tileset());
            assertFalse(fixture.source.takeFrame().scene().tactical().fills().isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.removeMarks(List.of(range));
                fixture.player.setStartingPos(Board.START_ANY);
                fixture.view.markDeploymentHexesFor(fixture.entity);
                fixture.source.refresh();
            });
            assertFalse(fixture.source.takeFrame().scene().tactical().fills().isEmpty(),
                  "Legal deployment borders belong to the native tactical geometry");
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.markDeploymentHexesFor(null);
                fixture.game.setPhase(GamePhase.FIRING);
                fixture.view.addStrafingCoords(coords);
                fixture.source.refresh();
            });
            assertFalse(fixture.source.takeFrame().scene().tactical().fills().isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.clearStrafingCoords();
                fixture.source.refresh();
            });
            BoardScene.Tile cleared = fixture.source.takeFrame().scene().tile(coords);
            assertTrue(fixture.source.takeFrame().scene().tactical().fills().isEmpty());
            assertTrue(samePixels(before.tileset(), cleared.tileset()));
        }
    }

    @Test
    void waterDepthIsCopiedWithoutBakingTheWaterIntoTheGround() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords coords = new Coords(3, 2);
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene.Tile tile = fixture.source.takeFrame().scene().tile(coords);
            assertTrue(tile.water());
            assertEquals(fixture.game.getBoard().getHex(coords).terrainLevel(Terrains.WATER), tile.waterDepth());
            float bed = BoardGeometry.groundZ(tile);
            SwingUtilities.invokeAndWait(() -> {
                Hex hex = fixture.game.getBoard().getHex(coords).duplicate();
                hex.addTerrain(new Terrain(Terrains.ICE, 1));
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
            });
            BoardScene.Tile frozen = fixture.source.takeFrame().scene().tile(coords);
            assertTrue(frozen.frozen());
            assertEquals(tile.waterDepth(), frozen.waterDepth(), "Ice must preserve the riverbed");
            assertEquals(bed, BoardGeometry.groundZ(frozen));
            assertEquals(frozen.elevation() * BoardGeometry.LEVEL, BoardGeometry.surfaceZ(frozen));
        }
    }

    @Test
    void specialHexMarkersRemainTacticalAndClearWithoutChangingTheTerrain() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords coords = new Coords(3, 4);
            BoardScene.Tile before = fixture.source.takeFrame().scene().tile(coords);
            SpecialHexDisplay marker = SpecialHexDisplay.createArtyAutoHit(fixture.player);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getBoard().addSpecialHexDisplay(coords, marker, true);
                fixture.source.refresh();
            });
            BoardScene markedScene = fixture.source.takeFrame().scene();
            BoardScene.Tile marked = markedScene.tile(coords);
            assertTrue(samePixels(before.tileset(), marked.tileset()), "Tactical-only refreshes retain the captured art");
            assertTrue(markedScene.markers().stream().anyMatch(symbol -> symbol.coords().equals(coords)
                  && symbol.kind() == megamek.client.ui.clientGUI.boardview.BoardMarker.Kind.ARTILLERY_AUTO_HIT));
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getBoard().removeSpecialHexDisplay(coords, marker, true);
                fixture.source.refresh();
            });
            assertTrue(fixture.source.takeFrame().scene().markers().isEmpty(), "Removed symbols must leave no stale marker");
        }
    }

    /**
     * Feature heights belong to the feature that carries them: a building's stories and a woods canopy raise
     * their own models, while painted incline and cliff shading is replaced by actual terrain faces.
     */
    @Test
    void featuresUseAuthoredModelsAndTheirOwnHeights() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords building = new Coords(0, 0);
            Coords woods = new Coords(1, 0);
            Coords cliff = new Coords(2, 0);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getBoard().setHex(building, hexWith(fixture, building,
                      new Terrain(Terrains.BUILDING, 1), new Terrain(Terrains.BLDG_ELEV, 3),
                      new Terrain(Terrains.BLDG_CF, 15)));
                fixture.game.getBoard().setHex(woods, hexWith(fixture, woods, new Terrain(Terrains.WOODS, 2),
                      new Terrain(Terrains.FOLIAGE_ELEV, 2)));
                fixture.game.getBoard().setHex(cliff, new Hex(2));
                fixture.source.refresh();
            });
            assertTrue(fixture.game.getBoard().getHex(cliff).containsAnyTerrainOf(Terrains.INCLINE_TOP,
                  Terrains.INCLINE_BOTTOM, Terrains.INCLINE_HIGH_TOP, Terrains.INCLINE_HIGH_BOTTOM,
                  Terrains.CLIFF_BOTTOM), "The board must shade the hex beside a drop by itself");
            BoardScene scene = fixture.source.takeFrame().scene();
            assertTrue(scene.tile(cliff).features().stream()
                  .allMatch(feature -> feature.kind() == BoardScene.FeatureKind.SCATTER));
            assertFalse(hasOpaque(scene.tile(cliff).decals()), "Painted slopes must not duplicate the 3D faces");
            assertTrue(scene.tile(building).features().stream()
                  .allMatch(feature -> feature.asset().startsWith("building") && feature.height() == 3));
            assertTrue(scene.tile(woods).features().size() >= 4);
            assertTrue(scene.tile(woods).features().stream()
                  .allMatch(feature -> feature.height() >= 2 && feature.height() <= 2.2f
                        && feature.kind() == BoardScene.FeatureKind.TREE),
                  "Trees reach the board's foliage height, with a small variation above it");
        }
    }

    private static boolean hasOpaque(BoardScene.Pixels pixels) {
        if (pixels == null) {
            return false;
        }
        for (int index = 0; index < pixels.width() * pixels.height(); index++) {
            if ((pixels.rgba(index) & 0xff) > 0) {
                return true;
            }
        }
        return false;
    }

    /** The hex's own terrain plus the given layers, so the capture sees them without a board listener. */
    private static Hex hexWith(GpuBoardFixture fixture, Coords coords, Terrain... terrains) {
        Hex hex = fixture.game.getBoard().getHex(coords).duplicate();
        for (Terrain terrain : terrains) {
            hex.addTerrain(terrain);
        }
        return hex;
    }

    private static boolean samePixels(BoardScene.Pixels first, BoardScene.Pixels second) {
        if (first == null || second == null) {
            return first == second;
        }
        if (first.width() != second.width() || first.height() != second.height()) {
            return false;
        }
        for (int i = 0; i < first.width() * first.height(); i++) {
            if (first.rgba(i) != second.rgba(i)) {
                return false;
            }
        }
        return true;
    }
    @Test
    void usesTilesetFacingWithoutCopyingArtworkForTorsoTwists() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardScene.Unit before = fixture.source.takeFrame().scene().units().getFirst();
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setSecondaryFacing(1);
                fixture.source.refresh();
            });
            BoardScene.Unit after = fixture.source.takeFrame().scene().units().getFirst();
            assertEquals(fixture.view.getTilesetManager().facingFor(fixture.entity), after.location().facing());
            assertEquals(1, after.location().facing());
            assertSame(before.image(), after.image());
        }
    }

    @Test
    void reusesAssetsUntilTerrainChangesAndCopiesPixels() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardScene first = fixture.source.takeFrame().scene();
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene second = fixture.source.takeFrame().scene();
            assertSame(first.tiles(), second.tiles());
            assertSame(first.units().getFirst().image(), second.units().getFirst().image());
            assertTrue(second.units().getFirst().image().width() > 1);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getBoard().setHex(new Coords(0, 0), new Hex(5));
                fixture.source.refresh();
            });
            BoardScene changed = fixture.source.takeFrame().scene();
            assertNotSame(first.tiles(), changed.tiles());
            assertEquals(5, changed.tile(new Coords(0, 0)).elevation());
            assertEquals(0, first.tile(new Coords(0, 0)).elevation());
        }
    }

    @Test
    void unitOutlinesFollowTeamAndPlayerColorPreferences() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean teamColoring = preferences.getTeamColoring();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Player ally = new Player(1, "Ally");
                ally.setTeam(fixture.player.getTeam());
                Player enemy = new Player(2, "Enemy");
                enemy.setTeam(2);
                enemy.setColour(PlayerColour.RED);
                fixture.game.addPlayer(ally.getId(), ally);
                fixture.game.addPlayer(enemy.getId(), enemy);
                preferences.setTeamColoring(true);
                for (Player owner : List.of(fixture.player, ally, enemy)) {
                    fixture.entity.setOwner(owner);
                    fixture.source.refresh();
                    Color expected = owner == fixture.player ? preferences.getMyUnitColor()
                          : owner == ally ? preferences.getAllyUnitColor() : preferences.getEnemyUnitColor();
                    assertEquals(expected.getRGB(), fixture.source.takeFrame().scene().units().getFirst().outlineRgb());
                }
                preferences.setTeamColoring(false);
                fixture.source.refresh();
                assertEquals(enemy.getColour().getColour(false).getRGB(),
                      fixture.source.takeFrame().scene().units().getFirst().outlineRgb());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setTeamColoring(teamColoring));
        }
    }

    @Test
    void hiddenEnemiesAndSensorContactsUseExistingVisibilityRules() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Player enemy = new Player(1, "Opponent");
                enemy.setTeam(2);
                fixture.game.addPlayer(enemy.getId(), enemy);
                fixture.entity.setOwner(enemy);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                fixture.source.refresh();
            });
            assertTrue(fixture.source.takeFrame().scene().units().isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.addBeenDetectedBy(fixture.player);
                fixture.source.refresh();
            });
            BoardScene.Unit contact = fixture.source.takeFrame().scene().units().getFirst();
            assertTrue(contact.sensorContact());
            assertEquals(-1, contact.heat(), "A sensor blip must not reveal heat");
            assertEquals(1, contact.height());
            assertEquals(Messages.getString("BoardView1.sensorReturn"), contact.name());
            assertEquals(0, contact.location().facing());
            assertEquals(new Color(GpuMarkers.SENSOR_RGB).getRGB(), contact.outlineRgb(),
                  "Sensor contacts use a fixed red outline without revealing team colors");
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
                fixture.entity.setHidden(true);
                fixture.source.refresh();
            });
            assertTrue(fixture.source.takeFrame().scene().units().isEmpty());
        }
    }

    @Test
    void actionsUseOriginalButtonsAndRejectDisabledOrOldPhaseControls() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Runnable action = fixture.source.takeFrame().scene().commands().getFirst().action();
            action.run();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, fixture.clicks.get());
            SwingUtilities.invokeAndWait(() -> fixture.button.setEnabled(false));
            action.run();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, fixture.clicks.get());
            SwingUtilities.invokeAndWait(() -> {
                fixture.button.setEnabled(true);
                fixture.panel = new JPanel();
            });
            action.run();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, fixture.clicks.get());
        }
    }

    @Test
    void forwardsBoardCoordinatesAndDoesNotDispatchAfterClose() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<BoardViewEvent> received = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> fixture.view.addBoardViewListener(new BoardViewListenerAdapter() {
                @Override
                public void hexMoused(BoardViewEvent event) {
                    received.set(event);
                }
            }));
            Coords target = new Coords(3, 4);
            fixture.source.click(target, false, 0);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(target, received.get().getCoords());
            assertEquals(BoardViewEvent.BOARD_HEX_CLICKED, received.get().getType());
            assertEquals(0, received.get().getModifiers());
            received.set(null);
            SwingUtilities.invokeAndWait(fixture.source::close);
            fixture.source.click(target, false, 0);
            SwingUtilities.invokeAndWait(() -> { });
            assertTrue(fixture.source.isClosed());
            assertEquals(null, received.get());
        }
    }

    @Test
    void publishesMovementOnceAndDoesNotConsumeTheGamePath() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Vector<UnitLocation> path = new Vector<>();
            path.add(new UnitLocation(fixture.entity.getId(), new Coords(5, 5), 0, 0, 0));
            path.add(new UnitLocation(fixture.entity.getId(), new Coords(6, 5), 1, 0, 0));
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.moved = EntityMovementType.MOVE_JUMP;
                fixture.game.getPlanetaryConditions().setGravity(.5f);
                fixture.entity.setPosition(new Coords(6, 5));
                fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, fixture.entity, path));
            });
            SwingUtilities.invokeAndWait(() -> { });
            GpuBoardSource.Frame frame = fixture.source.takeFrame();
            assertEquals(1, frame.movements().size());
            assertEquals(EntityMovementType.MOVE_JUMP, frame.movements().getFirst().type());
            assertEquals(.5f, frame.movements().getFirst().gravity());
            SwingUtilities.invokeAndWait(() -> fixture.game.getPlanetaryConditions().setGravity(2));
            assertEquals(.5f, frame.movements().getFirst().gravity(), "Queued jumps retain their captured planetary gravity");
            assertEquals(2, frame.movements().getFirst().path().size());
            assertEquals(2, path.size());
            assertEquals(new Coords(6, 5), frame.scene().units().getFirst().location().coords());
            assertTrue(fixture.source.takeFrame().movements().isEmpty());
            assertFalse(frame.movements().getFirst().path().isEmpty());
            assertEquals(new Coords(6, 5), fixture.entity.getPosition());
        }
    }

    @Test
    void deploymentPlacementsReachPlaybackImmediately() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setPhase(GamePhase.DEPLOYMENT);
                fixture.entity.setDeployed(false);
                fixture.source.refresh();
            });
            var initial = fixture.source.takeFrame();
            var playback = new UnitPlayback();
            playback.accept(initial.animations(), initial.scene(), ignored -> false);
            for (var destination : List.of(new Coords(6, 5), new Coords(12, 12), new Coords(12, 11), new Coords(12, 11))) {
                SwingUtilities.invokeAndWait(() -> {
                    fixture.entity.setPosition(destination);
                    fixture.entity.setElevation(fixture.entity.getElevation() + 1);
                    fixture.source.refresh();
                });
                var frame = fixture.source.takeFrame();
                var placed = unit(frame.scene(), fixture.entity.getId());
                assertEquals(destination, placed.location().coords());
                assertTrue(frame.movements().isEmpty(), "Deployment must not queue travel or elevation animation");
                playback.accept(frame.animations(), frame.scene(), ignored -> false);
                assertEquals(placed.location(), unit(playback.present(frame.scene()), fixture.entity.getId()).location());
                assertFalse(playback.busy(), "Deployment must reach its selected position without advancing the clock");
            }
        }
    }

    @Test
    void publishesForcedDisplacementAndFallsOnceWithoutAMovementPath() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            fixture.source.takeFrame();
            var before = fixture.entity.getPosition();
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(before.translated(0));
                fixture.entity.setProne(megamek.common.units.ProneCause.FORCED);
                fixture.source.refresh();
            });
            var frame = fixture.source.takeFrame();
            assertEquals(1, frame.movements().size());
            var movement = frame.movements().getFirst();
            assertTrue(movement.forced());
            assertEquals(before, movement.path().getFirst().coords());
            assertEquals(before.translated(0), movement.path().getLast().coords());
            assertEquals(megamek.common.units.ProneCause.FORCED, movement.path().getLast().proneCause());
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            assertTrue(fixture.source.takeFrame().movements().isEmpty());
        }
    }

    @Test
    void anElevatorTheServerMovesPlaysBeforeItsCheckpointAndItsRiderRidesIt() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var coords = fixture.entity.getPosition();
            SwingUtilities.invokeAndWait(() -> {
                var hex = fixture.game.getBoard().getHex(coords).duplicate();
                hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.SOLARIS_ELEVATOR,
                      hex.getLevel() + 2, true, 1));
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
            });
            fixture.source.takeFrame();
            // The end phase swaps the hex's level with the elevator's; the unit keeps its elevation above the hex.
            SwingUtilities.invokeAndWait(() -> {
                var hex = fixture.game.getBoard().getHex(coords).duplicate();
                int level = hex.getLevel();
                hex.setLevel(hex.getTerrain(megamek.common.units.Terrains.SOLARIS_ELEVATOR).getLevel());
                hex.removeTerrain(megamek.common.units.Terrains.SOLARIS_ELEVATOR);
                hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.SOLARIS_ELEVATOR, level, true, 1));
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
            });
            var timeline = fixture.source.takeFrame().timeline();
            var lift = timeline.stream().filter(BoardScene.TileTransition.class::isInstance)
                  .map(BoardScene.TileTransition.class::cast).findFirst().orElseThrow();
            assertEquals(List.of(coords), lift.changes().stream().map(BoardScene.TileTransition.Change::coords).toList());
            assertEquals(2, lift.changes().getFirst().after().elevation() - lift.changes().getFirst().before().elevation());
            assertTrue(timeline.get(timeline.indexOf(lift) + 1) instanceof BoardScene.SceneUpdate,
                  "Its checkpoint follows it, so that it applies as the elevator starts to move");
            assertTrue(timeline.stream().noneMatch(BoardScene.Movement.class::isInstance),
                  "The unit on the platform rides it: no forced move");
        }
    }
}
