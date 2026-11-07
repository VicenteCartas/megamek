/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ratgenerator;

import jakarta.annotation.Nullable;
import megamek.common.units.UnitType;
import megamek.logging.MMLogger;

/**
 * The edits a Force Generator makes to a rolled force, free of any toolkit so the Swing and the libGDX generators
 * build the same command: folding rolls into one command, naming it, and re-rolling or adding one formation.
 */
public final class ForceEdits {
    private static final MMLogger LOGGER = MMLogger.create(ForceEdits.class);

    /** How an edit to one formation came out. */
    public enum Result {
        /** The formation was built as asked. */
        CHANGED,
        /** Units were drawn, but the formation's requirements could not be met, so it became an ordinary one. */
        DROPPED,
        /** The unit tables had nothing for it; the force is as it was before. */
        INSUFFICIENT
    }

    private ForceEdits() {
    }

    /**
     * Folds a freshly rolled command into the running command by echelon, so it reads as one structure rather than
     * a pile of rolls: a smaller roll goes under the current top, a larger one becomes the new top over it, and two
     * of the same size are put under a new command one echelon up.
     *
     * @param currentTop the current top command, or {@code null} for the first roll
     * @param rolled     the newly rolled command
     *
     * @return the top command after folding {@code rolled} in
     */
    public static ForceDescriptor accumulate(@Nullable ForceDescriptor currentTop, ForceDescriptor rolled) {
        if (currentTop == null) {
            return rolled;
        }
        int topEchelon = echelonOf(currentTop);
        int newEchelon = echelonOf(rolled);
        if (newEchelon < topEchelon) {
            currentTop.addSubForce(rolled);
            return currentTop;
        }
        if (newEchelon > topEchelon) {
            rolled.addSubForce(currentTop);
            return rolled;
        }
        ForceDescriptor parent = parentCommand(currentTop, topEchelon + 1);
        parent.addSubForce(currentTop);
        parent.addSubForce(rolled);
        return parent;
    }

    private static int echelonOf(ForceDescriptor descriptor) {
        Integer echelon = descriptor.getEchelon();
        return (echelon == null) ? 0 : echelon;
    }

    /**
     * An empty command one echelon above two same-sized peers, borrowing the child's faction, unit type and year so
     * the ruleset names it for that faction; "Command" when the ruleset has no name at that level.
     */
    private static ForceDescriptor parentCommand(ForceDescriptor child, int echelon) {
        ForceDescriptor parent = new ForceDescriptor();
        parent.setEchelon(echelon);
        parent.setFaction(child.getFaction());
        parent.setUnitType(child.getUnitType());
        parent.setYear(child.getYear());
        ensureDescriptiveName(parent);
        if ((parent.getName() == null) || parent.getName().isBlank()) {
            parent.setName("Command");
        }
        return parent;
    }

    /**
     * Stamps the unit type onto a command whose resolved name is only its echelon, so it reads "Battle Armor
     * Battalion" rather than "Battalion". A name that already carries the unit type, or a command with no unit type
     * or echelon name, is left as it is.
     */
    public static void ensureDescriptiveName(ForceDescriptor descriptor) {
        Ruleset ruleset = Ruleset.findRuleset(descriptor);
        String echelonName = (ruleset == null) ? null : ruleset.getEschelonName(descriptor);
        Integer unitType = descriptor.getUnitType();
        if ((echelonName == null) || echelonName.isBlank() || (unitType == null)) {
            return;
        }
        String unitTypeName = UnitType.getTypeDisplayableName(unitType);
        String displayName = descriptor.parseName();
        if ((displayName != null) && displayName.contains(unitTypeName)) {
            return;
        }
        descriptor.setName(unitTypeName + " " + echelonName);
    }

    /**
     * A child of {@code parent} whose shape a new formation can copy: one that holds a formation, since the units
     * inside a lance are children too and copying one would add a single unit.
     *
     * @return the child to copy, or {@code null} when {@code parent} holds no formations
     */
    public static @Nullable ForceDescriptor subForceTemplate(ForceDescriptor parent) {
        for (ForceDescriptor child : parent.getSubForces()) {
            if (!child.getEligibleFormations().isEmpty() || (child.getFormation() != null)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Gives one formation a different type and draws only that branch again; when nothing can be drawn for the new
     * type it is drawn again as it was.
     */
    public static Result changeFormation(ForceDescriptor formation, FormationType formationType) {
        FormationType previousType = formation.getFormation();
        formation.setFormationType(formationType);
        formation.clearGeneratedUnits();
        formation.generateUnits(null, 0);
        if (!formation.hasLineUnits()) {
            LOGGER.info("[ForceGen][ChangeFormation] '{}': no units as {}; rebuilt as it was",
                  formation.parseName(), formationType.getName());
            formation.setFormationType(previousType);
            formation.clearGeneratedUnits();
            formation.generateUnits(null, 0);
            finish(formation);
            return Result.INSUFFICIENT;
        }
        finish(formation);
        return built(formation, formationType);
    }

    /**
     * Adds a formation of {@code formationType} under {@code parent}, shaped like {@code template} - its echelon,
     * unit slots and generation rules - so it is legal by construction, and draws it. When nothing can be drawn it is
     * taken back out.
     */
    public static Result addSubForce(ForceDescriptor parent, ForceDescriptor template, FormationType formationType) {
        ForceDescriptor added = parent.createChild(parent.getSubForces().size());
        // The parent's weight class would refuse any formation needing different units; its requirements decide
        added.setWeightClass(null);
        added.setEchelon(template.getEchelon());
        // The sibling's name pattern, which turns into "C-4 Recon Lance" once it has a position
        added.setName(template.getName());
        added.setEligibleFormations(template.getEligibleFormations());
        added.setGenerationRule(template.getGenerationRule());
        added.setBlockGenerationRule(template.getBlockGenerationRule());
        added.setFormationType(formationType);
        for (int slot = 0; slot < template.getSubForces().size(); slot++) {
            added.addSubForce(added.createChild(slot));
        }
        parent.addSubForce(added);
        parent.assignPositions();
        added.generateUnits(null, 0);
        if (!added.hasLineUnits()) {
            parent.getSubForces().remove(added);
            parent.assignPositions();
            LOGGER.info("[ForceGen][AddFormation] {} under '{}': no units; not added", formationType.getName(),
                  parent.parseName());
            return Result.INSUFFICIENT;
        }
        finish(added);
        return built(added, formationType);
    }

    /** Removes the slots the unit tables could not fill, then names commanders and builds the units. */
    private static void finish(ForceDescriptor formation) {
        formation.removeNodesWithoutUnits();
        formation.assignCommanders();
        formation.loadEntities(null, 0);
    }

    private static Result built(ForceDescriptor formation, FormationType requested) {
        boolean kept = (formation.getFormation() != null)
              && formation.getFormation().getName().equals(requested.getName());
        return kept ? Result.CHANGED : Result.DROPPED;
    }
}
