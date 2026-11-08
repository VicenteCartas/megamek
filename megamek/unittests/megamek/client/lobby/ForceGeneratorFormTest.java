/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static megamek.testUtilities.MMTestUtilities.getEntityForUnitTesting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import megamek.client.ratgenerator.ForceComposition;
import megamek.client.ratgenerator.ForceDescriptor;
import megamek.client.ratgenerator.ForceEdits;
import megamek.client.ratgenerator.Ruleset;
import megamek.common.Player;
import megamek.common.options.GameOptions;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Force Generator's toolkit-free parts that need no RAT Generator data: folding rolls, the tree's nodes, what goes
 * to the game.
 */
class ForceGeneratorFormTest {

    @BeforeAll
    static void nameFormationsWithoutRules() {
        // Force strings name each formation by its ruleset; without the rules the registry is at least not null
        if (!Ruleset.isInitialized()) {
            Ruleset.loadData();
        }
    }

    private static ForceDescriptor force(int echelon, String name) {
        ForceDescriptor force = new ForceDescriptor();
        force.setEchelon(echelon);
        force.setName(name);
        return force;
    }

    @Test
    void aRollFoldsIntoTheCommandByItsSize() {
        ForceDescriptor company = force(4, "Company");
        assertSame(company, ForceEdits.accumulate(null, company), "the first roll is the command");

        ForceDescriptor lance = force(3, "Lance");
        assertSame(company, ForceEdits.accumulate(company, lance), "a smaller roll goes under the command");
        assertTrue(company.getSubForces().contains(lance));

        ForceDescriptor battalion = force(5, "Battalion");
        assertSame(battalion, ForceEdits.accumulate(company, battalion), "a larger roll becomes the command");
        assertTrue(battalion.getSubForces().contains(company));
    }

    @Test
    void aFormationIsAddedOnlyBesideAnotherFormation() {
        ForceDescriptor company = force(4, "Company");
        ForceDescriptor unit = force(0, "Atlas");
        unit.setElement(true);
        company.addSubForce(unit);
        assertNull(ForceEdits.subForceTemplate(company), "a lone unit is no shape for a formation");
        assertFalse(ForceGeneratorForm.holdsFormations(company));

        ForceDescriptor lance = force(3, "Lance");
        lance.setEligibleFormations(Map.of("Battle Lance", 1));
        company.addSubForce(lance);
        assertSame(lance, ForceEdits.subForceTemplate(company), "a formation is the shape for another");
        assertTrue(ForceGeneratorForm.holdsFormations(company));
        assertTrue(ForceGeneratorForm.formationNode(lance), "a formation may be re-rolled as another");
        assertFalse(ForceGeneratorForm.formationNode(unit));
    }

    @Test
    void aUnitHasNoChildrenInTheTree() {
        ForceDescriptor lance = force(3, "Lance");
        ForceDescriptor unit = force(0, "Atlas");
        unit.setElement(true);
        unit.addSubForce(force(0, "Crew"));
        lance.addSubForce(unit);
        assertEquals(List.of(unit), ForceGeneratorForm.children(lance));
        assertTrue(ForceGeneratorForm.children(unit).isEmpty(), "a single unit opens no branch");
    }

    @Test
    void leftOutBranchesStayOutOfTheGame() {
        ForceDescriptor lance = force(3, "Lance");
        ForceDescriptor unit = force(0, "Atlas");
        lance.addSubForce(unit);
        lance.setIncludedRecursively(false);
        assertFalse(unit.isIncluded(), "leaving a formation out leaves its units out");
        lance.setIncludedRecursively(true);
        assertTrue(unit.isIncluded());
    }

    @Test
    void anEmptyCommandHasNoComposition() {
        assertTrue(ForceComposition.rows(null).isEmpty());
        assertTrue(ForceComposition.rows(force(3, "Lance")).isEmpty(), "no units, no rows and no total");
    }

    @Test
    void theSwingTextsLoseTheirMarkup() {
        assertEquals("One.\nTwo, and three.",
              ForceGeneratorForm.plainText("<html>One.<br/>Two, <b>and</b> three.</html>"));
        assertEquals(2, ForceGeneratorForm.insufficientData().size());
        assertFalse(ForceGeneratorForm.insufficientData().get(1).contains("<"), "the native message has no HTML");
    }

    /** A formation without an echelon, whose force string needs no ruleset to name it. */
    private static ForceDescriptor named(String name) {
        ForceDescriptor force = new ForceDescriptor();
        force.setName(name);
        return force;
    }

    /** {@code ForceDescriptor.entity} is only ever set by generation, so the test injects it. */
    private static ForceDescriptor unit(Entity entity) throws ReflectiveOperationException {
        ForceDescriptor unit = named(entity.getShortName());
        unit.setElement(true);
        Field field = ForceDescriptor.class.getDeclaredField("entity");
        field.setAccessible(true);
        field.set(unit, entity);
        return unit;
    }

    private static Entity load(String name) {
        Entity entity = getEntityForUnitTesting(name, true);
        assertNotNull(entity, name + " not found in the test data");
        entity.setOwner(new Player(0, "Owner"));
        return entity;
    }

    @Test
    void unitsAreMatchedByIdentityNotByTheirSharedUnsentId() {
        Entity kept = load("Cheetah F-11");
        Entity leftOut = load("Cheetah F-11");
        assertEquals(kept, leftOut, "generated units share the id -1, so equals cannot tell them apart");
        Predicate<Entity> among = ForceGeneratorForm.among(List.of(kept));
        assertTrue(among.test(kept));
        assertFalse(among.test(leftOut), "a left-out unit is not one of those going to the game");
    }

    @Test
    void aLeftOutFighterIsNotLoadedAboardItsCarrier() throws ReflectiveOperationException {
        Entity leopard = load("Leopard (2537)");
        Entity kept = load("Cheetah F-11");
        Entity leftOut = load("Cheetah F-11");
        ForceDescriptor ship = unit(leopard);
        ForceDescriptor flight = named("Flight");
        ForceDescriptor leftOutNode = unit(leftOut);
        flight.addSubForce(unit(kept));
        flight.addSubForce(leftOutNode);
        ship.addAttached(flight);
        ForceDescriptor command = named("Naval Units");
        command.addSubForce(ship);
        leftOutNode.setIncludedRecursively(false);
        ForceGeneratorForm form = new ForceGeneratorForm(command);

        List<Entity> units = form.included();
        assertEquals(2, units.size());
        form.wire(units);
        form.loadCarriers(units);
        assertEquals(leopard.getId(), kept.getTransportId());
        assertEquals(Entity.NONE, leftOut.getTransportId(), "the left-out fighter stays out of the Leopard");
    }

    @Test
    void theExportWritesOnlyTheIncludedUnits(@TempDir Path folder) throws Exception {
        Entity kept = load("Leopard (2537)");
        Entity leftOut = load("Cheetah F-11");
        ForceDescriptor command = named("Lance");
        ForceDescriptor leftOutNode = unit(leftOut);
        command.addSubForce(unit(kept));
        command.addSubForce(leftOutNode);
        leftOutNode.setIncludedRecursively(false);
        ForceGeneratorForm form = new ForceGeneratorForm(command);

        File file = folder.resolve("force.mul").toFile();
        assertEquals(1, form.export(file, new GameOptions()));
        String written = Files.readString(file.toPath());
        assertTrue(written.contains("Leopard"));
        assertFalse(written.contains("Cheetah"), "the left-out unit is not saved");
    }

    @Test
    void aRerolledShipForgetsWhatItLost() {
        ForceDescriptor ship = force(0, "Overlord");
        ship.setElement(true);
        ForceDescriptor flight = force(1, "Flight");
        ForceDescriptor fighter = force(0, "Sparrowhawk");
        flight.addSubForce(fighter);
        ship.addAttached(flight);
        ForceDescriptor command = force(3, "Naval Units");
        command.addSubForce(ship);
        ForceGeneratorForm form = new ForceGeneratorForm(command);
        String flightId = form.id(flight);
        String fighterId = form.id(fighter);

        assertFalse(form.rerollShip(ship, rerolled -> false));
        assertSame(fighter, form.node(fighterId), "a ship left as it was keeps its ids");

        assertTrue(form.rerollShip(ship, rerolled -> {
            // As the reroller does: the old complement comes off and a new one goes on
            rerolled.getAttached().clear();
            rerolled.addAttached(force(1, "New Flight"));
            return true;
        }));
        assertNull(form.node(flightId), "the detached flight is forgotten");
        assertNull(form.node(fighterId), "and the fighters in it");
    }
}
