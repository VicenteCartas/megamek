/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import megamek.client.ui.Messages;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.options.IOption;
import megamek.common.options.IOptionGroup;
import megamek.common.options.OptionsConstants;
import megamek.common.options.QuirkCatalog;
import megamek.common.options.QuirkImplementationStatus;
import megamek.common.options.QuirkKind;
import megamek.common.options.Quirks;
import megamek.common.options.WeaponQuirks;
import megamek.common.units.Aero;
import megamek.common.units.Entity;

/**
 * The quirks of a single unit as the unit bay edits them, the Swing {@code QuirksPanel}: the positive and the negative
 * chassis quirks the unit may have, and per weapon (and club or shield) the weapon quirks it may have, each in name
 * order and marked as the quirk catalog rates its implementation.
 * <p>
 * Read on the Swing thread, edited on the view's, applied on the Swing thread.
 */
public final class UnitQuirksForm {
    /** One weapon's quirks: its equipment number, its name with its location, and its quirks. */
    public record Weapon(int number, String title, List<OptionEntry> entries) {}

    private static final Comparator<OptionEntry> BY_LABEL = Comparator.comparing(entry ->
          entry.label().toLowerCase(Locale.ROOT));

    private final boolean editable;
    private final List<OptionEntry> positive = new ArrayList<>();
    private final List<OptionEntry> negative = new ArrayList<>();
    private final List<Weapon> weapons = new ArrayList<>();

    private UnitQuirksForm(Entity entity, boolean editable) {
        this.editable = editable;
        for (Enumeration<IOptionGroup> groups = entity.getQuirks().getGroups(); groups.hasMoreElements(); ) {
            IOptionGroup group = groups.nextElement();
            boolean isPositive = Quirks.POS_QUIRKS.equals(group.getKey());
            if (!isPositive && !Quirks.NEG_QUIRKS.equals(group.getKey())) {
                continue;
            }
            for (Enumeration<IOption> options = group.getSortedOptions(); options.hasMoreElements(); ) {
                IOption option = options.nextElement();
                if ((option != null) && !Quirks.isQuirkDisallowed(option, entity)) {
                    (isPositive ? positive : negative).add(entry(QuirkKind.UNIT, option));
                }
            }
        }
        positive.sort(BY_LABEL);
        negative.sort(BY_LABEL);
        List<Mounted<?>> mounts = new ArrayList<>(entity.getWeaponList());
        for (Mounted<?> mounted : entity.getMisc()) {
            if (mounted.getType().hasFlag(MiscType.F_CLUB) || mounted.getType().hasFlag(MiscType.F_SHIELD)) {
                mounts.add(mounted);
            }
        }
        mounts.sort(Comparator.comparingInt(entity::getEquipmentNum));
        for (Mounted<?> mounted : mounts) {
            List<OptionEntry> entries = new ArrayList<>();
            for (Enumeration<IOptionGroup> groups = mounted.getQuirks().getGroups(); groups.hasMoreElements(); ) {
                for (Enumeration<IOption> options = groups.nextElement().getSortedOptions();
                      options.hasMoreElements(); ) {
                    IOption option = options.nextElement();
                    if (!WeaponQuirks.isQuirkDisallowed(option, entity, mounted.getType())) {
                        entries.add(entry(QuirkKind.WEAPON, option));
                    }
                }
            }
            if (!entries.isEmpty()) {
                entries.sort(BY_LABEL);
                weapons.add(new Weapon(entity.getEquipmentNum(mounted),
                      Messages.getString("CustomMekDialog.quirksWeaponPanelTitle", mounted.getName(),
                            entity.getLocationName(mounted.getLocation())), Collections.unmodifiableList(entries)));
            }
        }
    }

    /** The quirks of {@code entity}, empty when it may have none. */
    public static Optional<UnitQuirksForm> of(Entity entity, boolean editable) {
        UnitQuirksForm form = new UnitQuirksForm(entity, editable);
        boolean none = form.positive.isEmpty() && form.negative.isEmpty() && form.weapons.isEmpty();
        return none ? Optional.empty() : Optional.of(form);
    }

    private static OptionEntry entry(QuirkKind kind, IOption option) {
        QuirkImplementationStatus status = QuirkCatalog.getEntry(kind, option.getName())
              .map(catalog -> catalog.status()).orElse(QuirkImplementationStatus.IMPLEMENTED);
        String suffix = switch (status) {
            case PARTIAL -> Messages.getString("CustomMekDialog.quirkPartialSuffix");
            case NOT_IMPLEMENTED -> Messages.getString("CustomMekDialog.quirkNotImplementedSuffix");
            default -> "";
        };
        return OptionEntry.of(option, suffix, List.of(), status == QuirkImplementationStatus.NOT_IMPLEMENTED);
    }

    /** Whether the quirks may be changed. */
    public boolean editable() {
        return editable;
    }

    /** The positive chassis quirks. */
    public List<OptionEntry> positive() {
        return Collections.unmodifiableList(positive);
    }

    /** The negative chassis quirks. */
    public List<OptionEntry> negative() {
        return Collections.unmodifiableList(negative);
    }

    /** The weapons with quirks they may have, in equipment order. */
    public List<Weapon> weapons() {
        return Collections.unmodifiableList(weapons);
    }

    /** Turns a yes-or-no quirk on or off. */
    public void set(OptionEntry entry, boolean on) {
        if (editable) {
            entry.on(on);
        }
    }

    /** Sets whether a directional torso mount faces {@code location}. */
    public void location(OptionEntry entry, String location, boolean on) {
        if (editable) {
            entry.location(location, on);
        }
    }

    /** Sets a whole-number quirk. */
    public void number(OptionEntry entry, int value) {
        if (editable) {
            entry.number(value);
        }
    }

    /** Sets a text quirk. */
    public void text(OptionEntry entry, String value) {
        if (editable) {
            entry.text(value);
        }
    }

    /**
     * Writes the quirks to the unit and its weapons as the Swing {@code QuirksPanel.setQuirks} does: an internal bomb
     * bay quirk renews an aerospace unit's bomb points, and the tech advancement is worked out again.
     */
    void apply(Entity entity) {
        for (OptionEntry entry : positive) {
            write(entity, entry);
        }
        for (OptionEntry entry : negative) {
            write(entity, entry);
        }
        entity.recalculateTechAdvancement();
        for (Weapon weapon : weapons) {
            Mounted<?> mounted = entity.getEquipment(weapon.number());
            for (OptionEntry entry : weapon.entries()) {
                entry.write(mounted.getQuirks().getOption(entry.name()));
            }
        }
    }

    private static void write(Entity entity, OptionEntry entry) {
        entry.write(entity.getQuirks().getOption(entry.name()));
        if (entry.name().equals(OptionsConstants.QUIRK_POS_INTERNAL_BOMB) && (entity instanceof Aero aero)) {
            aero.autoSetMaxBombPoints();
        }
    }
}
