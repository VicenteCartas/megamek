/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.NuclearDetonation;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.event.GameNuclearDetonationEvent;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Tank;
import megamek.common.units.Targetable;
import megamek.common.units.Terrains;
import megamek.common.units.UnitLocation;
import megamek.common.util.BoardUtilities;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Actual nuclear terrain resolution on a shipped wooded map, presented by the live battle renderer. The server
 * game contains no units: this measures terrain/effects, not unit damage resolution or network latency. Its changed
 * hexes cross the ordinary client-board/source boundary at detonation. The visual event represents an observed
 * off-board weapon launch; ammo capture and NukeStats are production code. No damage or crater rule is copied here.
 *
 * Timings are synchronized CPU+GPU frame wall times (glFinish), not GPU-only queries or uncapped throughput. Loading
 * and screenshots are excluded. Terrain installation and asynchronous rebuild frames remain in the measurements.
 */
@Tag("on-demand")
class GpuNuclearImpactSmokeTest {
    private static final String BOARD = "AGoAC Maps/16x17 Grassland 3.board";
    private static final float STEP = 1 / 30f;
    private static File originalDataDir;

    record Weapon(String name, String weapon, int type, boolean scheduled) {
        Weapon(String name, String weapon, int type) { this(name, weapon, type, false); }

        @Override
        public String toString() { return name; }
    }

    static Stream<Weapon> weapons() {
        String selected = System.getProperty("megamek.gpu.nuclearWeapons", "");
        return Stream.of(new Weapon("davy-crockett-m", "ISLongTom", 1),
              new Weapon("davy-crockett-aiv", "ISArrowIV", 1),
              new Weapon("alamo-type-ii", "AlamoMissile", 2),
              new Weapon("santa-ana-type-iii", "WhiteShark", 3),
              new Weapon("scheduled-type-ii", "", 2, true))
              .filter(weapon -> selected.isBlank() || List.of(selected.split(",")).contains(weapon.name()));
    }

    @BeforeAll
    static void stagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
        EquipmentType.initializeTypes();
    }

    @AfterAll
    static void restoreData() { Configuration.setDataDir(originalDataDir); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weapons")
    void resolvesAndRendersTheRealNuclearImpact(Weapon weapon) throws Exception {
        Board before = board();
        Coords impact = hexes(before).filter(hex -> hex.containsTerrain(Terrains.WOODS))
              .map(Hex::getCoords).min(Comparator.comparingInt(coords -> coords.distance(new Coords(16, 17))))
              .orElseThrow();
        var server = new ResolutionServer();
        server.getGame().setBoard(board());
        server.getGame().addPlayer(0, new Player(0, "Nuclear review"));
        server.getGame().initializeRulesManager(OptionsConstants.RULES_CORE);
        var reports = new Vector<Report>();
        long started = System.nanoTime();
        if (weapon.scheduled()) {
            server.addScheduledNuke(new int[] { impact.getX(), impact.getY(), weapon.type() });
            var resolve = TWGameManager.class.getDeclaredMethod("resolveScheduledNukes");
            resolve.setAccessible(true);
            resolve.invoke(server);
            reports.addAll(server.getMainPhaseReport());
            assertNotNull(server.detonation, "The actual scheduled command emits its animation event");
            assertEquals(impact, server.detonation.coords(), "The scheduled strike retains its exact hex");
        } else {
            server.doNuclearExplosion(impact, weapon.type(), reports);
        }
        double resolveMs = elapsed(started);
        Board after = server.getGame().getBoard();
        var stats = AreaEffectHelper.getNukeStats(weapon.type());
        assertEquals(stats.craterDepth, before.getHex(impact).getLevel() - after.getHex(impact).getLevel(),
              "The authoritative crater uses this weapon's stock depth");
        assertTrue(woods(after) < woods(before), "Actual server resolution clears woods");
        assertFalse(server.changed.isEmpty(), "The server reports its terrain changes");
        assertFalse(reports.isEmpty(), "The real nuclear resolution produces reports");
        Map<BoardLocation, Hex> updates = new HashMap<>();
        for (Coords coords : server.changed) {
            updates.put(BoardLocation.of(coords, before.getBoardId()), after.getHex(coords).duplicate());
        }
        try (GpuBoardFixture fixture = GpuBoardFixture.create(before)) {
            // The benchmark is a terrain workload; the fixture's unrelated Atlas must not survive a fake blast.
            onSwing(() -> {
                fixture.game.removeEntity(fixture.entity.getId(), IEntityRemovalConditions.REMOVE_NEVER_JOINED);
                return null;
            });
            Launcher launcher = weapon.scheduled() ? null : onSwing(() -> launcher(fixture, weapon));
            GpuLiveBoardSpaceSmokeTest.run(fixture.source, live ->
                  measure(live, fixture, weapon, launcher, impact, before, after, updates, resolveMs, server.detonation));
        }
    }

    private static Board board() {
        Board[] sheets = new Board[4];
        for (int index = 0; index < sheets.length; index++) {
            sheets[index] = BoardFile.loadSheet(new File(Configuration.boardsDir(), BOARD), false, true);
        }
        var board = BoardUtilities.combine(16, 17, 2, 2, sheets, MapSettings.MEDIUM_GROUND);
        assertEquals(32, board.getWidth());
        assertEquals(34, board.getHeight());
        return board;
    }

    /** Only transport is replaced; all terrain mutation remains inside TWGameManager. */
    private static final class ResolutionServer extends TWGameManager {
        final Set<Coords> changed = new HashSet<>();
        NuclearDetonation detonation;

        @Override
        public void sendChangedHex(Coords coords, int boardId) { changed.add(coords); }

        @Override
        public void send(Packet packet) {
            if (packet.command() == PacketCommand.NUCLEAR_DETONATION) {
                detonation = (NuclearDetonation) packet.getObject(0);
            }
        }
    }

    record Launcher(BoardScene.Unit unit, int equipment, ResolvedAttack.Shot profile) { }

    /** Keep the real observed off-board launcher in the source so its sprite/model is prepared during loading. */
    static Launcher launcher(GpuBoardFixture fixture, Weapon entry) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/LRM Carrier.blk")).getEntity();
        entity.setId(900);
        entity.setOwner(fixture.player);
        var weapon = (WeaponType) EquipmentType.get(entry.weapon());
        assertNotNull(weapon, entry.weapon());
        AmmoType ammo = AmmoType.getMunitionsFor(weapon.getAmmoType()).stream().filter(candidate ->
              entry.type() == 1 ? candidate.getMunitionType().contains(AmmoType.Munitions.M_DAVY_CROCKETT_M)
                    : entry.type() == 2 ? candidate.getAmmoType() == AmmoType.AmmoTypeEnum.ALAMO
                          : candidate.hasFlag(AmmoType.F_SANTA_ANNA)).findFirst().orElseThrow();
        var gun = entity.addEquipment(weapon, Tank.LOC_BODY);
        gun.setLinked(entity.addEquipment(ammo, Tank.LOC_BODY));
        var profile = ResolvedAttack.Shot.capture(gun);
        assertNotNull(profile);
        assertEquals(entry.type(), profile.nuclearType(), "Actual weapon/ammo capture retains the nuclear type");
        entity.setOffBoard(20, OffBoardDirection.WEST);
        fixture.game.addEntity(entity, false);
        entity.deployOffBoard(fixture.game.getRoundCount());
        entity.setDeployed(true);
        entity.addOffBoardObserver(fixture.player.getTeam());
        fixture.source.refresh();
        // The source currently captures visible off-board positions in the scene itself. Reserve excludes those IDs.
        BoardScene.Unit unit = Stream.concat(fixture.source.takeFrame().scene().units().stream(),
                    fixture.source.reserveUnits().stream())
              .filter(candidate -> candidate.id() == entity.getId()).findFirst()
              .orElseThrow(() -> new AssertionError("The source did not capture launcher " + entity.getId()));
        assertNotNull(unit.image(), "The observed launcher has its real captured sprite");
        return new Launcher(unit, entity.getEquipmentNum(gun), profile);
    }

    private static BoardScene.Combat incoming(Weapon weapon, Launcher launcher, Coords impact, BoardScene scene) {
        Coords origin = new Coords(-10, impact.getY());
        int height = scene.tile(impact).elevation();
        var launch = new UnitLocation(900, origin, 2, height + 6, scene.boardId());
        var landing = new UnitLocation(Entity.NONE, impact, 0, height, scene.boardId());
        var captured = launcher.profile();
        // Preserve the observed weapon family: a nuclear capital missile is not an artillery shell.
        var profile = new ResolvedAttack.Shot(captured.mode(), captured.munitions(), captured.artillery(),
              captured.defensive(), captured.shots(), captured.missiles(), captured.indirect(),
              captured.missileHits(), launch, landing, captured.ballistic(), captured.rackSize(), captured.ppc(),
              captured.machineGun(), captured.rapidFire(), captured.interception(), captured.damagePerHit(),
              captured.plasma(), captured.nuclearType());
        var shooter = launcher.unit().at(new BoardScene.Waypoint(origin, height + 6, 2));
        var attack = new ResolvedAttack(UUID.nameUUIDFromBytes(weapon.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              ResolvedAttack.Kind.SHOT, launch, landing, Targetable.TYPE_HEX_ARTILLERY, launcher.equipment(), weapon.weapon(),
              0, true, List.of(new ResolvedAttack.Mount(shooter.id(), launcher.equipment(), profile)), profile);
        return new BoardScene.Combat(attack, shooter, null, new BoardScene.Waypoint(impact, height, 0), false, true);
    }

    private record Sample(String stage, float seconds, double frameMs, boolean rebuilding) { }

    private static void measure(GpuLiveBoardSpaceSmokeTest.Live live, GpuBoardFixture fixture, Weapon weapon, Launcher launcher,
          Coords impact, Board before, Board after, Map<BoardLocation, Hex> updates, double resolveMs,
          NuclearDetonation scheduled) throws Exception {
        var folder = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), weapon.name());
        assertTrue(folder.isDirectory() || folder.mkdirs());
        var samples = new ArrayList<Sample>();
        // Capture the actual board differences before the client installs those same authoritative hexes.
        Map<Coords, Integer> levelChanges = hexes(before)
              .filter(hex -> hex.getLevel() != after.getHex(hex.getCoords()).getLevel())
              .collect(Collectors.toMap(Hex::getCoords,
                    hex -> after.getHex(hex.getCoords()).getLevel() - hex.getLevel()));
        live.camera().setIsometric(true);
        live.camera().fit(live.frame.get().scene());
        warm(live);
        var terrain = (GpuTerrain) field(live.view, "terrain");
        int woodsBefore = woods(before);
        long treesBefore = trees(live.frame.get().scene());
        long plantsBefore = plants(terrain.presentation(live.frame.get().scene()));
        // A board without fire or smoke links their programs when a nuke launches (GpuBattleView), not at load.
        var fire = (GpuTerrainEffects) field(live.view, "terrainEffects");
        boolean fireLinked = field(fire, "shader") != null;
        int programsBefore = GpuGlsl.compilesAfterLoading();
        int deferredBefore = GpuGlsl.deferredAfterLoading();
        double installMs = 0;
        boolean complete = false;
        try {
            for (int i = 0; i < 60; i++) { sample(live, samples, "before", 0, STEP); }
            capture(folder, "01-before");
            var effects = (GpuAttackEffects) field(live.view, "attackEffects");
            if (weapon.scheduled()) {
                long installStarted = System.nanoTime();
                onSwing(() -> {
                    fixture.game.processGameEvent(new GameNuclearDetonationEvent(fixture.game, scheduled, false));
                    fixture.game.getBoard().setHexes(updates);
                    fixture.game.processGameEvent(new GameNuclearDetonationEvent(fixture.game, scheduled, true));
                    return null;
                });
                live.publish();
                installMs = elapsed(installStarted);
                assertTrue(live.frame.get().timeline().stream().anyMatch(BoardScene.Detonation.class::isInstance),
                      "The real source converts the scheduled packet event into its timeline");
                sample(live, samples, "terrain-install", 0, STEP);
            } else {
                var event = incoming(weapon, launcher, impact, live.frame.get().scene());
                live.frame.set(live.frame.get().withTimeline(List.of(event)));
                sample(live, samples, "incoming", 0, STEP);
            }
            live.frame.set(live.frame.get().withTimeline(List.of()));
            for (int i = 0; effects.artilleryBlastCount() == 0 && i < 180; i++) {
                sample(live, samples, "incoming", 0, STEP);
            }
            assertEquals(1, effects.artilleryBlastCount(), "The actual UnitPlayback impact starts one nuclear blast");
            // The emitter starts in the transparent pass. Next frame presents its flash and one-shot ground scorch.
            sample(live, samples, "flash", STEP, STEP);
            capture(folder, "02-flash");
            if (!weapon.scheduled()) {
                long installStarted = System.nanoTime();
                onSwing(() -> { fixture.game.getBoard().setHexes(updates); return null; });
                live.publish();
                GpuBoardSource.Frame converted = live.frame.get();
                live.frame.set(converted.withTimeline(List.of(new BoardScene.SceneUpdate(converted.scene()))));
                installMs = elapsed(installStarted);
                sample(live, samples, "terrain-install", STEP * 2, STEP);
                live.frame.set(converted.withTimeline(List.of()));
            }
            capture(folder, "03-terrain-conversion");
            boolean blastCaptured = false;
            boolean smokeCaptured = false;
            for (int i = 3; i <= Math.ceil((GpuArtilleryBlast.NUCLEAR_SECONDS + 1) / STEP); i++) {
                float seconds = i * STEP;
                String stage = seconds < .3f ? "flash" : seconds < 4 ? "explosion" : "aftermath";
                sample(live, samples, stage, seconds, STEP);
                if (!blastCaptured && seconds >= 1) {
                    capture(folder, "04-explosion");
                    blastCaptured = true;
                }
                if (!smokeCaptured && seconds >= 6) {
                    capture(folder, "05-smoke-and-crater");
                    smokeCaptured = true;
                }
            }
            assertEquals(0, effects.artilleryBlastCount(), "The complete nuclear visual clears");
            settle(live, samples, "rebuild-tail", GpuArtilleryBlast.NUCLEAR_SECONDS + 1);
            for (int i = 0; i < 60; i++) { sample(live, samples, "settled", 20, STEP); }
            capture(folder, "06-settled");
            BoardScene scene = live.frame.get().scene();
            assertEquals(after.getHex(impact).getLevel(), scene.tile(impact).elevation(), "The rendered scene has the crater");
            assertTrue(trees(scene) < treesBefore, "The source removes game trees when authoritative woods disappear");
            assertTrue(terrain.ready(scene), "Crater checks require installed terrain, not the source-scene fallback");
            BoardScene installed = terrain.presentation(scene);
            for (Coords coords : levelChanges.keySet()) {
                assertNotNull(installed.tile(coords), "The installed terrain retains crater hex " + coords);
                assertEquals(after.getHex(coords).getLevel(), installed.tile(coords).elevation(),
                      "Installed crater elevation must match the actual server result at " + coords);
            }
            assertTrue(plantsBefore > 0, "The before scene includes decorative vegetation");
            assertTrue(plants(installed) < plantsBefore, "Installed terrain removes scorched decorative vegetation");
            assertTrue(installed.tiles().stream().filter(tile -> terrain.groundDamage().scorched(scene, tile))
                  .flatMap(tile -> tile.features().stream()).noneMatch(GpuScatter::vegetation),
                  "No decorative plant remains in an installed scorched tile");
            assertNotNull(field(fire, "shader"), "The launch linked the fire and smoke programs");
            assertTrue(GpuGlsl.compilesAfterLoading() - programsBefore <= (fireLinked ? 0 : 2),
                  "Beyond the launch's fire and smoke, impact and rebuilding must not compile new programs");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            complete = true;
        } finally {
            BoardScene scene = live.frame.get().scene();
            write(folder, samples, weapon, impact, woodsBefore, woods(after), treesBefore, trees(scene),
                  hexes(after).filter(hex -> hex.containsTerrain(Terrains.ROUGH)).count(),
                  updates.size(), resolveMs, installMs, plantsBefore, plants(terrain.presentation(scene)),
                  GpuGlsl.compilesAfterLoading() - programsBefore, GpuGlsl.deferredAfterLoading() - deferredBefore,
                  levelChanges, complete);
        }
    }

    /** Let the entrance camera advance: zero-delta loading waits otherwise leave LOD work for the baseline. */
    private static void warm(GpuLiveBoardSpaceSmokeTest.Live live) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        int stable = 0;
        long revision = live.camera().revision();
        while (stable < 30) {
            assertTrue(System.nanoTime() < deadline, () -> GpuBoardTestUi.loadingState(live.view));
            live.draw(1, STEP);
            long next = live.camera().revision();
            stable = next == revision && !live.camera().isFraming() && !GpuBoardTestUi.busy(live.view)
                  && !GpuBoardTestUi.loading(live.view) ? stable + 1 : 0;
            revision = next;
        }
        Gdx.gl.glFinish();
    }

    private static void settle(GpuLiveBoardSpaceSmokeTest.Live live, List<Sample> samples, String stage, float seconds) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        while (GpuBoardTestUi.busy(live.view) || GpuBoardTestUi.loading(live.view)) {
            assertTrue(System.nanoTime() < deadline, () -> GpuBoardTestUi.loadingState(live.view));
            sample(live, samples, stage, seconds, 0);
        }
    }

    private static void sample(GpuLiveBoardSpaceSmokeTest.Live live, List<Sample> samples, String stage,
          float seconds, float delta) {
        Gdx.gl.glFinish();
        long start = System.nanoTime();
        live.draw(1, delta);
        Gdx.gl.glFinish();
        samples.add(new Sample(stage, seconds, elapsed(start), GpuBoardTestUi.busy(live.view)));
    }

    private static void capture(File folder, String name) {
        GpuBoardTestUi.capture(new File(folder, name + ".png"));
    }

    private static int woods(Board board) {
        return (int) hexes(board).filter(hex -> hex.containsTerrain(Terrains.WOODS)
              || hex.containsTerrain(Terrains.JUNGLE)).count();
    }

    private static Stream<Hex> hexes(Board board) {
        return java.util.stream.IntStream.range(0, board.getWidth() * board.getHeight())
              .mapToObj(index -> board.getHex(index % board.getWidth(), index / board.getWidth()));
    }

    private static long trees(BoardScene scene) {
        return scene.tiles().stream().flatMap(tile -> tile.features().stream())
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).count();
    }

    private static long plants(BoardScene scene) {
        return scene.tiles().stream().flatMap(tile -> tile.features().stream()).filter(GpuScatter::vegetation).count();
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000d; }

    private static void write(File folder, List<Sample> samples, Weapon weapon, Coords impact, int woodsBefore,
          int woodsAfter, long treesBefore, long treesAfter, long rough, int changed, double resolveMs,
          double installMs, long plantsBefore, long plantsAfter, int programs, int deferred,
          Map<Coords, Integer> levelChanges, boolean complete)
          throws Exception {
        var csv = new StringBuilder("stage,simulation_seconds,frame_ms,terrain_busy\n");
        for (Sample sample : samples) {
            csv.append(String.format(Locale.ROOT, "%s,%.3f,%.4f,%s%n", sample.stage(), sample.seconds(),
                  sample.frameMs(), sample.rebuilding()));
        }
        Files.writeString(new File(folder, "frames.csv").toPath(), csv);
        var stats = AreaEffectHelper.getNukeStats(weapon.type());
        var report = new StringBuilder(String.format(Locale.ROOT,
              "Status: %s%nWeapon: %s; stock nuclear type %d%nBoard: 2x2 %s; impact %s%nGPU: %s%nResolution: %dx%d%n"
                    + "Base damage %d; degradation %d; secondary radius %d; crater depth %d%n"
                    + "Woods/jungle hexes: %d -> %d; rendered tree features: %d -> %d; rough hexes after: %d%n"
                    + "Installed decorative plants: %d -> %d; programs compiled after loading: %d; deferred programs: %d%n"
                    + "Changed hexes: %d; server resolution %.3f ms; client install + source capture %.3f ms%n"
                    + "Actual board elevation changes: %d lowered; %d raised; maximum level drop %d%n"
                    + "Times below are synchronized full-frame CPU+GPU wall times. Screenshots/loading excluded.%n"
                    + "Rebuild frames included. This is not a throughput/FPS measurement.%n"
                    + "Stock blast radius is not reduced to fit the map. Cooling uses the game's wall clock.%n"
                    + "Unit damage and network latency are outside this terrain/effect fixture.%n%n",
              complete ? "COMPLETE" : "INCOMPLETE (a later check or rendering stage failed)",
              weapon.name(), weapon.type(), BOARD, impact, Gdx.gl.glGetString(GL20.GL_RENDERER),
              Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), stats.baseDamage, stats.degradation,
              stats.secondaryRadius, stats.craterDepth, woodsBefore, woodsAfter, treesBefore, treesAfter,
              rough, plantsBefore, plantsAfter, programs, deferred, changed, resolveMs, installMs,
              levelChanges.values().stream().filter(delta -> delta < 0).count(),
              levelChanges.values().stream().filter(delta -> delta > 0).count(),
              levelChanges.values().stream().mapToInt(delta -> Math.max(0, -delta)).max().orElse(0)));
        report.append("stage,frames,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,rebuild_frames\n");
        Map<String, List<Sample>> groups = samples.stream().collect(Collectors.groupingBy(Sample::stage,
              java.util.LinkedHashMap::new, Collectors.toList()));
        for (var entry : groups.entrySet()) {
            double[] times = entry.getValue().stream().mapToDouble(Sample::frameMs).sorted().toArray();
            report.append(String.format(Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d%n",
                  entry.getKey(), times.length, java.util.Arrays.stream(times).average().orElseThrow(),
                  percentile(times, .5), percentile(times, .95), percentile(times, .99), times[times.length - 1],
                  entry.getValue().stream().filter(Sample::rebuilding).count()));
        }
        report.append(String.format(Locale.ROOT, "Maximum measured frame: %.3f ms%n",
              samples.stream().mapToDouble(Sample::frameMs).max().orElse(0)));
        report.append(String.format(Locale.ROOT, "Client install + first conversion frame: %.3f ms%n",
              installMs + samples.stream().filter(sample -> sample.stage().equals("terrain-install"))
                    .mapToDouble(Sample::frameMs).findFirst().orElse(0)));
        Files.writeString(new File(folder, "timings.txt").toPath(), report);
        System.out.println(report);
    }

    private static double percentile(double[] values, double share) {
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * share) - 1)];
    }
}
