/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.units;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import megamek.common.Player;
import megamek.common.ToHitData;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.AimingMode;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.event.GameListener;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.rules.RulesManager;
import megamek.common.weapons.Weapon;
import megamek.common.weapons.other.innerSphere.ISAMS;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The fire preview puts the game back exactly as it found it, and refuses what it cannot preview exactly. */
class FirePreviewTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final Coords START = new Coords(7, 12);
    /** The Archer where it stands at START, against the Atlas and the Bulldog (characterized before E5). */
    private static final String CURRENT_HEX = "attacker 0 target 0"
          + " | 2 out [0:[8, 8, 6, 6, x, x], 5:[8, 8, 6, 6, x, x], 1:[8, 8, 6, 6, x, x]]"
          + " in [3:[8, 8, 6, 8, 8, x, x], 2:[8, 8, 6, 8, 8, x, x], 4:[8, 8, 6, 8, 8, x, x]]"
          + " | 3 out [0:[8, 8, 4, 4, x, x], 5:[x, 8, x, x, x, x], 1:[8, 8, 4, 4, x, x]]"
          + " in [4:[f, 8, 8, 6], 3:[f, 8, 8, 6], 5:[f, x, x, x], 2:[f, x, x, x], 0:[f, x, x, x], 1:[f, x, x, x]]";

    private RulesManager previousRules;
    private Game game;
    private Entity mover;
    private Entity atlas;
    private Entity bulldog;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() throws Exception {
        previousRules = Game.rulesManager;
        game = new Game();
        game.initializeRulesManager(OptionsConstants.RULES_CORE);
        for (int id = 0; id < 2; id++) {
            Player player = new Player(id, "Player " + id);
            player.setTeam(id + 1);
            game.addPlayer(id, player);
        }
        game.setBoard(board(hex(3, 11, 0, "water:2"), hex(5, 12, 0, "water:1"), hex(12, 10, 0, "fortified:1"),
              hex(1, 14, 3, "")));
        game.setPhase(GamePhase.MOVEMENT);
        mover = unit("Archer ARC-2R.mtf", 1, 0, START, 0);
        atlas = unit("Atlas AS7-D.mtf", 2, 1, new Coords(7, 4), 3);
        bulldog = unit("Bulldog Medium Tank.blk", 3, 1, new Coords(11, 7), 4);
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = previousRules;
    }

    @Test
    void previewLeavesTheGameExactlyAsItWas() throws Exception {
        Entity member = unit("Atlas AS7-D.mtf", 4, 0, new Coords(9, 5), 5);
        mover.addEquipment(EquipmentType.get("ISC3MasterComputer"), Mek.LOC_LEFT_TORSO);
        member.addEquipment(EquipmentType.get("ISC3SlaveUnit"), Mek.LOC_LEFT_ARM);
        member.setC3Master(mover.getId(), true);
        Entity wading = unit("Atlas AS7-D.mtf", 5, 1, new Coords(5, 12), 1);
        wading.setElevation(-1);
        List<Entity> enemies = List.of(atlas, bulldog, wading);
        MovePath plan = plan(mover, MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.TURN_RIGHT);
        Coords destination = plan.getFinalCoords();
        List<String> toHits = liveToHits(enemies);
        // A stale scratch value, as the C3 spotter search on the real path leaves one.
        member.setC3ecmAffected(true);
        Map<Integer, byte[]> units = serializedUnits();
        Map<Integer, Set<Coords>> positions = positions();
        List<Entity> atStart = game.getEntitiesVector(START);
        List<Entity> atDestination = game.getEntitiesVector(destination);
        AtomicInteger events = countEvents();

        FirePreview.Result result = FirePreview.compute(plan, enemies);

        assertNull(result.notPreviewed());
        assertEquals(3, result.exchanges().size());
        assertEquals(0, events.get(), "no game event");
        assertEquals(GamePhase.MOVEMENT, game.getPhase());
        assertTrue(game.getActionsVector().isEmpty());
        assertTrue(member.getC3ecmAffected());
        for (Entity unit : game.getEntitiesVector()) {
            assertArrayEquals(units.get(unit.getId()), serialize(unit), unit.getShortName());
        }
        assertEquals(positions, positions());
        assertEquals(atStart, game.getEntitiesVector(START));
        assertEquals(atDestination, game.getEntitiesVector(destination));
        assertEquals(toHits, liveToHits(enemies), "the real declaration path is unchanged");
        assertFalse(member.getC3ecmAffected(), "the real path writes the scratch flag the preview put back");
    }

    @Test
    void scopeMovesTheUnitInTheLookupAndPutsItBack() {
        MovePath plan = plan(mover, MoveStepType.FORWARDS, MoveStepType.FORWARDS);
        Coords destination = plan.getFinalCoords();

        try (HypotheticalState ignored = HypotheticalState.atEndOf(mover, FirePreview.End.of(plan))) {
            assertEquals(destination, mover.getPosition());
            assertTrue(game.getEntitiesVector(destination).contains(mover));
            assertFalse(game.getEntitiesVector(START).contains(mover));
        }

        assertEquals(START, mover.getPosition());
        assertTrue(game.getEntitiesVector(START).contains(mover));
        assertFalse(game.getEntitiesVector(destination).contains(mover));
        assertEquals(Set.of(START), game.getEntityPositions(mover));
    }

    @Test
    void exceptionInsideTheScopeRestores() throws Exception {
        byte[] before = serialize(mover);
        FirePreview.End end = FirePreview.End.of(plan(mover, MoveStepType.FORWARDS, MoveStepType.GO_PRONE));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> {
            try (HypotheticalState ignored = HypotheticalState.atEndOf(mover, end)) {
                throw new IllegalStateException("inside");
            }
        });

        assertEquals("inside", error.getMessage());
        assertArrayEquals(before, serialize(mover));
        assertEquals(Set.of(START), game.getEntityPositions(mover));
    }

    @Test
    void nestedMoverAndEnemyScopesRestoreInLifoOrder() throws Exception {
        byte[] moverBefore = serialize(mover);
        byte[] atlasBefore = serialize(atlas);
        atlas.setSecondaryFacing(4);

        try (HypotheticalState moved = HypotheticalState.atEndOf(mover,
              FirePreview.End.of(plan(mover, MoveStepType.FORWARDS)))) {
            moved.setSecondaryFacing(moved.legalSecondaryFacings().get(1));
            try (HypotheticalState twisted = HypotheticalState.inPlace(atlas)) {
                assertEquals(3, atlas.getSecondaryFacing(), "an enemy's twist resets when it moves");
                twisted.setSecondaryFacing(twisted.legalSecondaryFacings().get(2));
            }
            assertEquals(4, atlas.getSecondaryFacing());
        }

        assertArrayEquals(moverBefore, serialize(mover));
        atlas.setSecondaryFacing(3);
        assertArrayEquals(atlasBefore, serialize(atlas));
    }

    @Test
    void scopeRefusesOtherThreadsAndIllegalTwists() {
        try (HypotheticalState moved = HypotheticalState.atEndOf(mover, FirePreview.End.of(plan(mover)))) {
            int left = moved.legalSecondaryFacings().get(1);

            CompletionException error = assertThrows(CompletionException.class,
                  () -> CompletableFuture.runAsync(() -> moved.setSecondaryFacing(left)).join());
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertThrows(IllegalArgumentException.class, () -> moved.setSecondaryFacing(3));
        }
    }

    @Test
    void legalTwistsFollowTheFiringDisplayRule() {
        assertEquals(List.of(0, 5, 1), legalTwists(plan(mover)));
        assertEquals(List.of(0), legalTwists(plan(mover, MoveStepType.GO_PRONE)), "prone");
        assertEquals(List.of(4, 3, 5, 2, 0, 1), legalTwists(plan(bulldog)), "a turret turns all the way");

        game.getOptions().getOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS).setValue(true);
        mover.getQuirks().getOption(OptionsConstants.QUIRK_POS_EXT_TWIST).setValue(true);
        assertEquals(List.of(0, 5, 1, 4, 2), legalTwists(plan(mover)), "extended twist");

        game.setPhase(GamePhase.TARGETING);
        mover.setSecondaryFacing(1);
        game.setPhase(GamePhase.MOVEMENT);
        assertEquals(List.of(1), legalTwists(plan(mover, MoveStepType.TURN_LEFT)), "twisted before movement");
    }

    @Test
    void lockedTurretKeepsItsOffsetExactlyAsTheRealSetterDoes() {
        Tank tank = (Tank) bulldog;
        tank.setSecondaryFacing(0);
        tank.lockTurret(tank.getLocTurret());

        assertEquals(List.of(1), legalTwists(plan(tank, MoveStepType.TURN_RIGHT)));

        tank.setFacing(5);
        assertEquals(1, tank.getSecondaryFacing(), "the preview's forward is what the real setter gives");
    }

    @Test
    void unsupportedPlansFailClosed() throws Exception {
        assertEquals(FirePreview.Reason.UNSUPPORTED_STEP, reason(plan(mover, MoveStepType.SHUTDOWN)));
        assertEquals(FirePreview.Reason.UNSUPPORTED_STEP,
              reason(plan(mover, MoveStepType.JUMP_MEK_MECHANICAL_BOOSTER)));
        assertEquals(FirePreview.Reason.WALK_ON, reason(plan(mover, MoveStepType.DEPLOY, MoveStepType.FORWARDS)));
        for (String file : List.of("SOAR VTOL.blk", "Shadow Hawk LAM SHD-X2.mtf",
              "Light Missile Gun Emplacement 3039.blk")) {
            Entity unit = unit(file, 10, 0, new Coords(1, 1), 0);
            assertEquals(FirePreview.Reason.UNSUPPORTED_UNIT, reason(plan(unit)), file);
            game.removeEntity(unit.getId(), 0);
        }
        place(mover, new Coords(7, 0), 0);
        assertEquals(FirePreview.Reason.ILLEGAL_PATH, reason(plan(mover, MoveStepType.FORWARDS)), "off the board");
        place(mover, START, 0);

        mover.setHidden(true);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(plan(mover)), "hidden");
        mover.setHidden(false);
        mover.setStuck(true);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(plan(mover)), "stuck");
        mover.setStuck(false);
        mover.setTowing(bulldog.getId());
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(plan(mover)), "towing");
        mover.setTowing(Entity.NONE);
        mover.setSwarmAttackerId(atlas.getId());
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(plan(mover)), "swarmed");
        mover.setSwarmAttackerId(Entity.NONE);
        mover.setSwarmTargetId(atlas.getId());
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(plan(mover)), "swarming");
        mover.setSwarmTargetId(Entity.NONE);
        game.setPhase(GamePhase.FIRING);
        assertEquals(FirePreview.Reason.NOT_MOVEMENT_PHASE, reason(plan(mover)));
        game.setPhase(GamePhase.MOVEMENT);

        Infantry platoon = (Infantry) unit("Foot Platoon (AFFS) (Laser 3067+).blk", 11, 0, new Coords(2, 2), 3);
        platoon.setHitTheDeck(true);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(plan(platoon, MoveStepType.FORWARDS)));
        assertNull(reason(plan(platoon, MoveStepType.TURN_LEFT)), "turning keeps the posture");

        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN).setValue(true);
        place(bulldog, new Coords(12, 11), 3);
        MovePath backIntoHullDown = plan(bulldog, MoveStepType.BACKWARDS, MoveStepType.HULL_DOWN);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(backIntoHullDown), backIntoHullDown.toString());

        MovePath walk = plan(mover, MoveStepType.FORWARDS);
        mover.setPosition(new Coords(1, 3), false);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(walk), "position lookup out of sync");
        mover.setPosition(START, false);

        Entity moved = copy(mover);
        moved.setPosition(new Coords(2, 12), false);
        game.setEntity(mover.getId(), moved);
        assertEquals(FirePreview.Reason.STALE_PLAN, reason(walk), "the unit moved since the plan was made");
    }

    @Test
    void anUnchangedReplacementComputesOnTheLiveUnit() throws Exception {
        MovePath walk = plan(mover, MoveStepType.FORWARDS);
        byte[] planned = serialize(mover);
        Entity replacement = copy(mover);
        game.setEntity(mover.getId(), replacement);
        byte[] live = serialize(replacement);

        FirePreview.Result result = FirePreview.compute(walk, List.of(atlas));

        assertNull(result.notPreviewed());
        assertFalse(result.exchanges().getFirst().outgoing().salvos().isEmpty());
        assertArrayEquals(planned, serialize(mover));
        assertArrayEquals(live, serialize(replacement));
        assertSame(replacement, game.getEntity(mover.getId()));
    }

    @Test
    void weaponFiltersSkipTheRulesCall() throws Exception {
        game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_MANUAL_AMS).setValue(true);
        ISAMS amsType = new ISAMS();
        amsType.adaptToGameOptions(game.getOptions());
        WeaponMounted ams = (WeaponMounted) mover.addEquipment(amsType, Mek.LOC_RIGHT_ARM);
        mover.loadWeapon(ams);
        WeaponMounted lrm = mover.getWeaponList().stream()
              .filter(weapon -> weapon.getType().getAmmoType() == AmmoType.AmmoTypeEnum.LRM)
              .findFirst().orElseThrow();
        AmmoMounted lrmAmmo = lrm.getLinkedAmmo();
        game.initializeRulesManager(OptionsConstants.RULES_TW);
        lrmAmmo.setDumping(true);
        assertTrue(lrmAmmo.isDumping(), "Total Warfare allows dumping ammo");
        WeaponMounted artillery = (WeaponMounted) bulldog.addEquipment(EquipmentType.get("ISArrowIV"),
              Tank.LOC_BODY);

        FirePreview.Result result = FirePreview.compute(plan(mover), List.of(atlas));

        assertEquals(FirePreview.Reason.FIRES_AUTOMATICALLY, shot(result.exchanges().getFirst().outgoing(),
              mover, ams).notPreviewed());
        assertEquals(FirePreview.Reason.DUMPING_AMMO, shot(result.exchanges().getFirst().outgoing(), mover, lrm)
              .notPreviewed());
        assertSame(lrmAmmo, lrm.getLinkedAmmo(), "the rules call that re-links dumping ammo was skipped");
        FirePreview.Result fromTank = FirePreview.compute(plan(bulldog), List.of(mover));
        assertEquals(FirePreview.Reason.OTHER_PHASE_WEAPON, shot(fromTank.exchanges().getFirst().outgoing(),
              bulldog, artillery).notPreviewed());

        assertTrue(ams.setModeImmediately(Weapon.MODE_AMS_MANUAL) >= 0);
        FirePreview.Result manual = FirePreview.compute(plan(mover), List.of(atlas));
        assertNotNull(shot(manual.exchanges().getFirst().outgoing(), mover, ams).toHit(), "AMS used as a weapon");
    }

    @Test
    void targetsOnAnotherBoardOrNotValid() throws Exception {
        game.setBoard(1, board());
        Entity elsewhere = unit("Atlas AS7-D.mtf", 6, 1, new Coords(7, 8), 3);
        elsewhere.setBoardId(1);
        atlas.setHidden(true);

        FirePreview.Result result = FirePreview.compute(plan(mover), List.of(elsewhere, atlas));

        FirePreview.Exchange otherBoard = result.exchanges().get(0);
        assertEquals(FirePreview.Reason.OTHER_BOARD, otherBoard.outgoing().notPreviewed());
        assertEquals(FirePreview.Reason.OTHER_BOARD, otherBoard.incoming().notPreviewed());
        FirePreview.Exchange hidden = result.exchanges().get(1);
        assertEquals(FirePreview.Reason.NOT_A_TARGET, hidden.outgoing().notPreviewed());
        assertNull(hidden.incoming().notPreviewed());
        assertFalse(hidden.incoming().salvos().isEmpty());
    }

    @Test
    void theScopeWetsTheUnitWhereTheMoveEndsAndPutsTheExposureBack() throws Exception {
        place(mover, new Coords(3, 12), 0);
        MovePath intoDeepWater = plan(mover, MoveStepType.FORWARDS);
        assertEquals(-2, intoDeepWater.getFinalElevation());
        byte[] before = serialize(mover);

        try (HypotheticalState ignored = HypotheticalState.atEndOf(mover, FirePreview.End.of(intoDeepWater))) {
            assertExposure(mover, ILocationExposureStatus.WET);
        }
        FirePreview.Result result = FirePreview.compute(intoDeepWater, List.of(atlas));

        assertArrayEquals(before, serialize(mover), "the exposure is put back");
        assertTrue(result.wet());
        assertNull(result.exchanges().getFirst().outgoing().notPreviewed(), "fire from under water is previewed");
    }

    /** Water case 3: a unit whose top reaches the surface keeps the status the air gives it. */
    @Test
    void aUnitReachingTheSurfaceKeepsItsAirStatus() throws Exception {
        Entity tank = unit("Devastator II Superheavy Tank .blk", 9, 0, new Coords(4, 12), 0);
        assertInstanceOf(SuperHeavyTank.class, tank);
        FirePreview.End partial = new FirePreview.End(new Coords(5, 12), 0, 0, -1, false, false, false,
              Entity.LOC_NONE, EntityMovementType.MOVE_WALK, 1, 2);
        FirePreview.End deep = new FirePreview.End(new Coords(3, 11), 0, 0, -2, false, false, false,
              Entity.LOC_NONE, EntityMovementType.MOVE_WALK, 1, 2);

        try (HypotheticalState ignored = HypotheticalState.atEndOf(tank, partial)) {
            assertEquals(0, tank.relHeight());
            assertExposure(tank, ILocationExposureStatus.NORMAL);
        }
        try (HypotheticalState ignored = HypotheticalState.atEndOf(tank, deep)) {
            assertExposure(tank, ILocationExposureStatus.WET);
        }
        assertExposure(tank, ILocationExposureStatus.NORMAL);
    }

    @Test
    void inPlaceEvaluatesAnyUnitKindWhereItStandsAndPutsItBack() throws Exception {
        Entity vtol = unit("Cobra Transport VTOL.blk", 9, 0, new Coords(7, 9), 0);
        vtol.setElevation(1);
        assertEquals(FirePreview.Reason.UNSUPPORTED_UNIT, reason(plan(vtol)), "no plan preview for a VTOL");
        Map<Integer, byte[]> units = serializedUnits();
        AtomicInteger events = countEvents();

        FirePreview.Result result = FirePreview.computeInPlace(vtol, List.of(atlas, bulldog));

        assertNull(result.notPreviewed());
        assertEquals(FirePreview.End.current(vtol), result.end());
        assertEquals(0, events.get());
        for (Entity unit : game.getEntitiesVector()) {
            assertArrayEquals(units.get(unit.getId()), serialize(unit), unit.getShortName());
        }
        // Its medium laser is previewed; its two AMS fire automatically.
        assertEquals("attacker 0 target 1"
              + " | 2 out [0:[6, -, -]] in [3:[7, 7, 7, 7, 7, x, x], 2:[7, 7, 7, 7, 7, x, x], 4:[7, 7, 7, 7, 7, x, x]]"
              + " | 3 out [0:[6, -, -]]"
              + " in [4:[f, 7, 7, 5], 3:[f, 7, 7, 5], 5:[f, 7, 7, 5], 2:[f, x, x, x], 0:[f, x, x, x], 1:[f, x, x, x]]",
              numbers(result), "the VTOL's numbers where it hovers");
    }

    @Test
    void inPlaceRefusesHiddenUnitsUnitsOffTheBoardAndOtherPhases() {
        mover.setHidden(true);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, FirePreview.computeInPlace(mover, List.of(atlas))
              .notPreviewed(), "a hidden unit may only spot");
        mover.setHidden(false);
        game.setPhase(GamePhase.FIRING);
        assertEquals(FirePreview.Reason.NOT_MOVEMENT_PHASE, FirePreview.computeInPlace(mover, List.of(atlas))
              .notPreviewed());
        game.setPhase(GamePhase.MOVEMENT);
        mover.setPosition(null);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, FirePreview.computeInPlace(mover, List.of(atlas))
              .notPreviewed());
    }

    @Test
    void inPlaceKeepsTheLiveExposure() throws Exception {
        WeaponMounted legLaser = (WeaponMounted) mover.addEquipment(EquipmentType.get("ISMediumLaser"),
              Mek.LOC_LEFT_LEG);
        place(mover, new Coords(5, 12), 0);
        mover.setElevation(-1);
        // The exposure the server set when the Mek waded in: only the legs are under water.
        WaterExposure.apply(mover, game.getBoard().getHex(mover.getPosition()), false, -1,
              game.getPlanetaryConditions());

        FirePreview.Result result = FirePreview.computeInPlace(mover, List.of(atlas));

        assertTrue(result.wet());
        assertEquals(ILocationExposureStatus.WET, mover.getLocationStatus(Mek.LOC_LEFT_LEG));
        assertEquals("Weapon underwater, but not target.",
              shot(result.exchanges().getFirst().outgoing(), mover, legLaser).toHit().getDesc());
        // Fire at it has the +1 of a target in partial water cover (ComputeTerrainMods#getTerrainModifiers).
        assertEquals("attacker 0 target 0"
              + " | 2 out [0:[8, 8, 6, 6, x, x, x], 5:[x, 8, x, x, x, x, x], 1:[8, 8, 6, 6, x, x, x]]"
              + " in [3:[9, 9, 7, 9, 9, x, x], 2:[x, 9, x, x, x, x, x], 4:[9, 9, 7, 9, 9, x, x]]", numbers(result),
              "the arms and torsos fire from above the surface, the leg laser does not");
    }

    @Test
    void waterThatBreachesALocationWithoutARollFailsClosed() {
        place(mover, new Coords(5, 13), 0);
        MovePath wade = plan(mover, MoveStepType.FORWARDS, MoveStepType.FORWARDS);
        assertEquals(new Coords(5, 11), wade.getFinalCoords(), "through the depth-1 water onto dry land");
        assertNull(reason(wade));

        mover.setArmor(0, Mek.LOC_LEFT_LEG);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(wade), "the bare leg is breached in the water");
        mover.setArmor(mover.getOArmor(Mek.LOC_LEFT_LEG), Mek.LOC_LEFT_LEG);

        game.initializeRulesManager(OptionsConstants.RULES_TW);
        AmmoMounted ammo = mover.getAmmo().getFirst();
        ammo.setDumping(true);
        assertTrue(ammo.isDumping(), "Total Warfare allows dumping ammo");
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(wade), "dumping ammunition in the water");
        assertNull(reason(plan(mover, MoveStepType.TURN_LEFT)), "on dry land, dumping breaches nothing");
        place(mover, new Coords(5, 12), 0);
        mover.setElevation(-1);
        assertEquals(FirePreview.Reason.UNSUPPORTED_STATE, reason(plan(mover)), "standing in water while dumping");
        ammo.setDumping(false);
        assertNull(reason(plan(mover)), "standing in water");
    }

    @Test
    void tacOpsClimbingFailsClosed() {
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_CLIMBING).setValue(true);
        place(mover, new Coords(1, 15), 0);
        mover.setClimbingLevelsChosen(1);
        MovePath partialClimb = plan(mover, MoveStepType.CLIMB_MODE_ON, MoveStepType.FORWARDS);
        assertTrue(partialClimb.isMoveLegal() && partialClimb.getLastStep().isClimbing(), partialClimb.toString());
        assertEquals(FirePreview.Reason.UNSUPPORTED_STEP, reason(partialClimb), "the server leaves it clinging below");

        mover.setClimbingLevelsChosen(0);
        place(mover, new Coords(1, 14), 0);
        MovePath offTheEdge = plan(mover, MoveStepType.CLIMB_MODE_ON, MoveStepType.FORWARDS);
        assertTrue(offTheEdge.isMoveLegal(), offTheEdge.toString());
        assertEquals(FirePreview.Reason.UNSUPPORTED_STEP, reason(offTheEdge), "the server climbs down or dangles");
    }

    @Test
    void earlyStopOnlyDropsTwistsThatCannotWin() throws Exception {
        Entity dervish = unit("Dervish DV-11DK.mtf", 7, 0, new Coords(9, 12), 0);
        place(atlas, new Coords(9, 8), 3);
        Entity flank = unit("Atlas AS7-D.mtf", 8, 1, new Coords(13, 12), 4);
        boolean shortened = false;

        for (Entity attacker : List.of(mover, dervish)) {
            MovePath plan = plan(attacker, MoveStepType.FORWARDS);
            FirePreview.Result early = FirePreview.compute(plan, List.of(atlas, flank));
            FirePreview.Result full = FirePreview.compute(plan, List.of(atlas, flank), true);
            for (int i = 0; i < full.exchanges().size(); i++) {
                shortened |= assertPrefixThatCannotWin(early.exchanges().get(i).outgoing(),
                      full.exchanges().get(i).outgoing());
                shortened |= assertPrefixThatCannotWin(early.exchanges().get(i).incoming(),
                      full.exchanges().get(i).incoming());
            }
        }

        assertTrue(shortened, "the fixture must exercise the early stop");
    }

    @Test
    void emptyPlanEvaluatesTheCurrentHexWithTheLiveMovementState() throws Exception {
        // A unit that already ran this phase: its move set these and put the torso forward.
        mover.moved = EntityMovementType.MOVE_RUN;
        mover.delta_distance = 5;
        mover.mpUsed = 6;
        mover.setDone(true);
        MovePath none = plan(mover);
        Entity.MovementState live = mover.movementState();
        byte[] before = serialize(mover);

        assertEquals(new FirePreview.End(START, 0, 0, 0, false, false, false, Entity.LOC_NONE,
              EntityMovementType.MOVE_RUN, 5, 6), FirePreview.End.of(none));
        try (HypotheticalState ignored = HypotheticalState.atEndOf(mover, FirePreview.End.of(none))) {
            assertEquals(live, mover.movementState(), "the current hex writes nothing hypothetical");
        }
        FirePreview.Result result = FirePreview.compute(none, List.of(atlas));

        assertEquals(Compute.getAttackerMovementModifier(game, mover.getId()).getValue(),
              result.attackerMovement().getValue());
        assertEquals(Compute.getTargetMovementModifier(game, mover.getId()).getDesc(),
              result.targetMovement().getDesc());
        assertSalvoIsTheLiveDeclaration(result.exchanges().getFirst().outgoing().salvos().getFirst());
        assertArrayEquals(before, serialize(mover));
    }

    @Test
    void standingStillResetsOnlyATwistLeftFromTheLastFiringPhase() throws Exception {
        game.setPhase(GamePhase.FIRING);
        mover.setSecondaryFacing(1);
        game.setPhase(GamePhase.MOVEMENT);
        MovePath none = plan(mover);
        Entity.MovementState live = mover.movementState();

        try (HypotheticalState ignored = HypotheticalState.atEndOf(mover, FirePreview.End.of(none))) {
            assertEquals(live.withSecondaryFacing(0), mover.movementState(), "as the stand-still commit does");
        }
        List<FirePreview.Salvo> salvos = FirePreview.compute(none, List.of(atlas), true).exchanges().getFirst()
              .outgoing().salvos();

        assertEquals(List.of(0, 5, 1), salvos.stream().map(FirePreview.Salvo::secondaryFacing).toList());
        assertSalvoIsTheLiveDeclaration(salvos.get(2));
    }

    /** Characterization (written before the in-place entry and the water exposure): these numbers stay. */
    @Test
    void theNumbersOfAWalkAndOfTheCurrentHexStayAsTheyWere() {
        FirePreview.Result walk = FirePreview.compute(plan(mover, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.TURN_RIGHT), List.of(atlas, bulldog));
        FirePreview.Result here = FirePreview.compute(plan(mover), List.of(atlas, bulldog));

        assertEquals("attacker 1 target 0"
              + " | 2 out [1:[7, 7, 6, 6, x, x], 0:[7, 7, 6, 6, x, x], 2:[7, x, x, x, x, x]]"
              + " in [3:[6, 6, 5, 6, 6, x, x], 2:[6, 6, 5, 6, 6, x, x], 4:[6, 6, 5, 6, 6, x, x]]"
              + " | 3 out [1:[7, 7, 7, 7, x, x], 0:[7, 7, 7, 7, x, x], 2:[7, x, x, x, x, x]]"
              + " in [4:[f, 6, 6, 4], 3:[f, 6, 6, 4], 5:[f, x, x, x], 2:[f, x, x, x], 0:[f, x, x, x], 1:[f, x, x, x]]",
              numbers(walk));
        assertEquals(CURRENT_HEX, numbers(here));
    }

    @Test
    void inPlaceAUnitThatHasNotMovedHasTheNumbersOfTheCurrentHex() {
        assertEquals(CURRENT_HEX, numbers(FirePreview.computeInPlace(mover, List.of(atlas, bulldog))));
    }

    /** Characterization: fire into the water at the mover does not depend on the mover's own exposure. */
    @Test
    void incomingFireIntoWaterStaysAsItWas() {
        place(mover, new Coords(5, 13), 0);
        FirePreview.Result wading = FirePreview.compute(plan(mover, MoveStepType.FORWARDS), List.of(atlas, bulldog));
        place(mover, new Coords(3, 12), 0);
        FirePreview.Result deep = FirePreview.compute(plan(mover, MoveStepType.FORWARDS), List.of(atlas, bulldog));

        // Wading, the mover has partial water cover: +1 to direct fire at it.
        assertEquals("[2 in [3:[9, 9, 7, 9, 9, x, x], 2:[x, 9, x, x, x, x, x], 4:[9, 9, 7, 9, 9, x, x]],"
              + " 3 in [4:[f, 9, 9, 7], 3:[f, 9, 9, 7], 5:[f, x, x, x], 2:[f, x, x, x], 0:[f, x, x, x],"
              + " 1:[f, x, x, x]]]", incomingNumbers(wading));
        assertEquals("[2 in [3:[x, x, x, x, x, x, x], 2:[x, x, x, x, x, x, x], 4:[x, x, x, x, x, x, x]],"
              + " 3 in [4:[x, x, x, x], 3:[x, x, x, x], 5:[x, x, x, x], 2:[x, x, x, x], 0:[x, x, x, x],"
              + " 1:[x, x, x, x]]]", incomingNumbers(deep));
    }

    @Test
    void everyReasonHasText() {
        for (FirePreview.Reason reason : FirePreview.Reason.values()) {
            assertFalse(reason.description().startsWith("!"), reason.name());
        }
    }

    /** The early salvos are a prefix of all salvos, and no dropped salvo holds a better shot. */
    private static boolean assertPrefixThatCannotWin(FirePreview.Direction early, FirePreview.Direction full) {
        assertEquals(full.notPreviewed(), early.notPreviewed());
        List<FirePreview.Salvo> kept = early.salvos();
        assertTrue(kept.size() <= full.salvos().size());
        for (int i = 0; i < kept.size(); i++) {
            assertEquals(values(full.salvos().get(i)), values(kept.get(i)));
        }
        if (kept.size() == full.salvos().size()) {
            return false;
        }
        FirePreview.Salvo stop = kept.getLast();
        assertTrue(stop.allAvailable());
        for (FirePreview.Salvo dropped : full.salvos().subList(kept.size(), full.salvos().size())) {
            for (int i = 0; i < dropped.shots().size(); i++) {
                FirePreview.Shot shot = dropped.shots().get(i);
                assertTrue(!shot.available() || (shot.toHit().getValue() == stop.shots().get(i).toHit().getValue()),
                      "a dropped twist never has a better shot");
            }
        }
        return true;
    }

    /** Every shot of the mover's salvo against the Atlas equals the live declaration to-hit of the same weapon. */
    private void assertSalvoIsTheLiveDeclaration(FirePreview.Salvo salvo) {
        assertEquals(salvo.secondaryFacing(), mover.getSecondaryFacing());
        for (FirePreview.Shot shot : salvo.shots()) {
            ToHitData real = WeaponAttackAction.toHit(game, mover.getId(), atlas, shot.weaponId(), Entity.LOC_NONE,
                  AimingMode.NONE, false);
            assertEquals(real.getValue() + " " + real.getDesc(),
                  shot.toHit().getValue() + " " + shot.toHit().getDesc());
        }
    }

    /**
     * The movement modifiers and, per enemy, every salvo's values: "x" impossible, "f" automatic failure, "-" not
     * previewed.
     */
    private static String numbers(FirePreview.Result result) {
        StringBuilder text = new StringBuilder("attacker " + result.attackerMovement().getValue() + " target "
              + result.targetMovement().getValue());
        for (FirePreview.Exchange exchange : result.exchanges()) {
            text.append(" | ").append(exchange.enemyId()).append(" out ").append(numbers(exchange.outgoing()))
                  .append(" in ").append(numbers(exchange.incoming()));
        }
        return text.toString();
    }

    private static String incomingNumbers(FirePreview.Result result) {
        return result.exchanges().stream().map(exchange -> exchange.enemyId() + " in "
              + numbers(exchange.incoming())).toList().toString();
    }

    private static String numbers(FirePreview.Direction direction) {
        if (direction.notPreviewed() != null) {
            return direction.notPreviewed().name();
        }
        return direction.salvos().stream().map(salvo -> salvo.secondaryFacing() + ":" + salvo.shots().stream()
              .map(FirePreviewTest::number).toList()).toList().toString();
    }

    private static String number(FirePreview.Shot shot) {
        if (shot.toHit() == null) {
            return "-";
        }
        int value = shot.toHit().getValue();
        return (value == TargetRoll.IMPOSSIBLE) ? "x"
              : (value == TargetRoll.AUTOMATIC_FAIL) ? "f" : Integer.toString(value);
    }

    private static void assertExposure(Entity unit, int status) {
        for (int location = 0; location < unit.locations(); location++) {
            assertEquals(status, unit.getLocationStatus(location), unit.getLocationName(location));
        }
    }

    private static List<Integer> values(FirePreview.Salvo salvo) {
        return salvo.shots().stream().map(shot -> (shot.toHit() == null) ? null : shot.toHit().getValue()).toList();
    }

    private static List<Integer> legalTwists(MovePath plan) {
        try (HypotheticalState scope = HypotheticalState.atEndOf(plan.getEntity(), FirePreview.End.of(plan))) {
            return scope.legalSecondaryFacings();
        }
    }

    private static FirePreview.Reason reason(MovePath plan) {
        return FirePreview.unsupportedReason(plan);
    }

    private static FirePreview.Shot shot(FirePreview.Direction direction, Entity attacker, WeaponMounted weapon) {
        int weaponId = attacker.getEquipmentNum(weapon);
        return direction.salvos().getFirst().shots().stream().filter(shot -> shot.weaponId() == weaponId)
              .findFirst().orElseThrow();
    }

    /** Value and description of every live declaration to-hit between the mover and the enemies. */
    private List<String> liveToHits(List<Entity> enemies) {
        List<String> result = new ArrayList<>();
        for (Entity enemy : enemies) {
            for (Entity[] pair : List.of(new Entity[] { mover, enemy }, new Entity[] { enemy, mover })) {
                for (WeaponMounted weapon : pair[0].getWeaponList()) {
                    ToHitData toHit = WeaponAttackAction.toHit(game, pair[0].getId(), pair[1],
                          pair[0].getEquipmentNum(weapon), Entity.LOC_NONE, AimingMode.NONE, false);
                    result.add(toHit.getValue() + " " + toHit.getDesc());
                }
            }
        }
        return result;
    }

    private AtomicInteger countEvents() {
        AtomicInteger events = new AtomicInteger();
        game.addGameListener((GameListener) Proxy.newProxyInstance(getClass().getClassLoader(),
              new Class<?>[] { GameListener.class }, (proxy, method, arguments) -> {
                  if (method.getDeclaringClass() == Object.class) {
                      return switch (method.getName()) {
                          case "equals" -> proxy == arguments[0];
                          case "hashCode" -> java.lang.System.identityHashCode(proxy);
                          default -> "event counter";
                      };
                  }
                  events.incrementAndGet();
                  return null;
              }));
        return events;
    }

    private Map<Integer, byte[]> serializedUnits() throws IOException {
        Map<Integer, byte[]> result = new HashMap<>();
        for (Entity unit : game.getEntitiesVector()) {
            result.put(unit.getId(), serialize(unit));
        }
        return result;
    }

    private Map<Integer, Set<Coords>> positions() {
        Map<Integer, Set<Coords>> result = new HashMap<>();
        for (Entity unit : game.getEntitiesVector()) {
            result.put(unit.getId(), game.getEntityPositions(unit));
        }
        return result;
    }

    private static byte[] serialize(Entity unit) throws IOException {
        // The names are cached on first use; a filled cache does not change the unit.
        unit.getShortName();
        unit.getDisplayName();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(unit);
        }
        return bytes.toByteArray();
    }

    /** A copy as a server update delivers it. */
    private static Entity copy(Entity unit) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialize(unit)))) {
            return (Entity) in.readObject();
        }
    }

    private Entity unit(String file, int id, int owner, Coords position, int facing) throws Exception {
        Entity unit = new MekFileParser(new File(UNITS + file)).getEntity();
        unit.setId(id);
        unit.setOwner(game.getPlayer(owner));
        game.addEntity(unit);
        place(unit, position, facing);
        unit.setDeployed(true);
        return unit;
    }

    private static void place(Entity unit, Coords position, int facing) {
        unit.setPosition(position);
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);
    }

    private MovePath plan(Entity unit, MoveStepType... steps) {
        MovePath plan = new MovePath(game, unit);
        for (MoveStepType step : steps) {
            plan.addStep(step);
        }
        return plan;
    }

    private static String hex(int x, int y, int level, String terrain) {
        return String.format("hex %02d%02d %d \"%s\" \"\"", x + 1, y + 1, level, terrain);
    }

    /** A clear 16 x 17 board with the given special hexes. */
    private static Board board(String... special) {
        StringBuilder data = new StringBuilder("size 16 17\n");
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 16; x++) {
                String line = hex(x, y, 0, "");
                for (String hex : special) {
                    if (hex.startsWith(line.substring(0, 9))) {
                        line = hex;
                    }
                }
                data.append(line).append('\n');
            }
        }
        return BoardLoader.initializeBoard(data.append("end").toString());
    }
}
