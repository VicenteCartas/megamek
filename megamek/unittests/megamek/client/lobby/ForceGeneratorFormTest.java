/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.client.ratgenerator.ForceComposition;
import megamek.client.ratgenerator.ForceDescriptor;
import megamek.client.ratgenerator.ForceEdits;
import org.junit.jupiter.api.Test;

/** The Force Generator's toolkit-free parts that need no RAT Generator data: folding rolls, the tree's nodes. */
class ForceGeneratorFormTest {

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
}
