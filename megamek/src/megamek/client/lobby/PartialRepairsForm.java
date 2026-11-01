/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

import megamek.common.options.IOption;
import megamek.common.options.IOptionGroup;
import megamek.common.options.PartialRepairs;
import megamek.common.units.Entity;

/**
 * The partial repairs of a single unit as the unit bay edits them, the Swing {@code CustomMekDialog} partial repairs
 * tab: per group the repairs legal for the unit.
 * <p>
 * Read on the Swing thread, edited on the view's, applied on the Swing thread.
 */
public final class PartialRepairsForm {
    /** A group of repairs: its title and repairs. */
    public record Group(String title, List<OptionEntry> entries) {}

    private final boolean editable;
    private final List<Group> groups = new ArrayList<>();

    private PartialRepairsForm(Entity entity, boolean editable) {
        this.editable = editable;
        for (Enumeration<IOptionGroup> all = entity.getPartialRepairs().getGroups(); all.hasMoreElements(); ) {
            IOptionGroup group = all.nextElement();
            List<OptionEntry> entries = new ArrayList<>();
            for (Enumeration<IOption> options = group.getSortedOptions(); options.hasMoreElements(); ) {
                IOption option = options.nextElement();
                if (PartialRepairs.isPartRepLegalFor(option, entity)) {
                    entries.add(OptionEntry.of(option, "", List.of(), false));
                }
            }
            if (!entries.isEmpty()) {
                groups.add(new Group(group.getDisplayableName(), Collections.unmodifiableList(entries)));
            }
        }
    }

    /** The partial repairs of {@code entity}, empty when none is legal for it. */
    public static Optional<PartialRepairsForm> of(Entity entity, boolean editable) {
        PartialRepairsForm form = new PartialRepairsForm(entity, editable);
        return form.groups.isEmpty() ? Optional.empty() : Optional.of(form);
    }

    /** Whether the repairs may be changed. */
    public boolean editable() {
        return editable;
    }

    /** The groups of repairs. */
    public List<Group> groups() {
        return Collections.unmodifiableList(groups);
    }

    /** Turns a repair on or off. */
    public void set(OptionEntry entry, boolean on) {
        if (editable) {
            entry.on(on);
        }
    }

    /** Writes the repairs to the unit, as the Swing {@code setPartReps} does. */
    void apply(Entity entity) {
        for (Group group : groups) {
            for (OptionEntry entry : group.entries()) {
                entry.write(entity.getPartialRepairs().getOption(entry.name()));
            }
        }
    }
}
