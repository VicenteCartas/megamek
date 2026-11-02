/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.Nullable;
import megamek.client.lobby.UnitEquipmentForm.Toggle;
import megamek.client.ui.Messages;
import megamek.common.enums.NeuralInterfaceMode;
import megamek.common.enums.ProstheticEnhancementType;
import megamek.common.options.GameOptions;
import megamek.common.options.IOption;
import megamek.common.options.IOptionGroup;
import megamek.common.options.OptionsConstants;
import megamek.common.options.PilotOptions;
import megamek.common.options.SpaCatalog;
import megamek.common.options.SpaImplementationStatus;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.PilotSPAHelper;

/**
 * The pilot abilities of a single unit as the unit bay edits them, the Swing {@code PilotOptionsPanel} with the rules
 * {@code CustomMekDialog} keeps across its rows: the option groups the game options allow, the options that fit the
 * unit, the choices of the specialist abilities, at most two sensory implants, one gas effuser, one neural interface
 * of a type the unit can take, the exclusive prosthetic and wing implants, and the prosthetic enhancements and
 * extraneous limbs of conventional infantry. A refused choice leaves a {@link #notice()} in place of the Swing warning
 * dialogs. Toggling a neural implant sets the matching cockpit fitting of the linked {@link UnitEquipmentForm}, as the
 * Swing equipment tab follows the pilot tab.
 * <p>
 * Read on the Swing thread, edited on the view's, applied on the Swing thread.
 */
public final class UnitAbilitiesForm {
    /** A group of abilities: its title and options in name order. */
    public record Group(String key, String title, List<OptionEntry> entries) {}

    /** The prosthetic slots of conventional infantry, shown with the option each belongs to. */
    public enum Slot {
        /** The single enhancement of Prosthetic Limbs, Enhanced. */
        STANDARD(OptionsConstants.MD_PL_ENHANCED),
        /** The first enhancement of Prosthetic Limbs, Improved Enhanced. */
        IMPROVED_1(OptionsConstants.MD_PL_I_ENHANCED),
        /** The second enhancement of Prosthetic Limbs, Improved Enhanced. */
        IMPROVED_2(OptionsConstants.MD_PL_I_ENHANCED),
        /** The first pair of extraneous limbs. */
        PAIR_1(OptionsConstants.MD_PL_EXTRA_LIMBS),
        /** The second pair of extraneous limbs, which wings rule out. */
        PAIR_2(OptionsConstants.MD_PL_EXTRA_LIMBS);

        private final String option;

        Slot(String option) {
            this.option = option;
        }

        /** The option the slot belongs to. */
        public String option() {
            return option;
        }

        /** Whether the slot has a count beside its enhancement; the limb pairs always hold two. */
        public boolean counted() {
            return this != PAIR_1 && this != PAIR_2;
        }
    }

    private static final List<String> SENSORY_IMPLANTS = List.of(OptionsConstants.MD_CYBER_IMP_AUDIO,
          OptionsConstants.MD_CYBER_IMP_VISUAL, OptionsConstants.MD_CYBER_IMP_LASER, OptionsConstants.MD_CYBER_IMP_TELE);
    private static final List<String> DNI = List.of(OptionsConstants.MD_VDNI, OptionsConstants.MD_BVDNI,
          OptionsConstants.MD_PROTO_DNI);
    private static final List<ProstheticEnhancementType> PROSTHETICS = prosthetics();

    private final boolean editable;
    private final boolean infantry;
    private final boolean protoDniFits;
    private final boolean vdniFits;
    private final List<Group> groups = new ArrayList<>();
    private final Map<Slot, Integer> prosthetic = new EnumMap<>(Slot.class);
    private final Map<Slot, Integer> count = new EnumMap<>(Slot.class);
    private @Nullable UnitEquipmentForm equipment;
    private String notice = "";

    private UnitAbilitiesForm(Entity entity, GameOptions gameOptions, boolean editable) {
        this.editable = editable;
        infantry = entity.isConventionalInfantry();
        protoDniFits = entity.isMek() && !entity.isIndustrialMek();
        vdniFits = entity.isMek() || entity.isBattleArmor() || entity.isCombatVehicle() || entity.isSupportVehicle()
              || entity.isAerospaceFighter() || entity.isConventionalFighter();
        PilotOptions options = entity.getCrew().getOptions();
        for (Enumeration<IOptionGroup> all = options.getGroups(); all.hasMoreElements(); ) {
            IOptionGroup group = all.nextElement();
            if (isGroupHidden(group, gameOptions)) {
                continue;
            }
            List<OptionEntry> entries = new ArrayList<>();
            for (Enumeration<IOption> each = group.getSortedOptions(); each.hasMoreElements(); ) {
                IOption option = each.nextElement();
                if (!isOptionHidden(option, entity)) {
                    entries.add(entry(option, entity, gameOptions));
                }
            }
            if (!entries.isEmpty()) {
                entries.sort(Comparator.comparing(entry -> entry.label().toLowerCase(Locale.ROOT)));
                groups.add(new Group(group.getKey(), group.getDisplayableName(), Collections.unmodifiableList(entries)));
            }
        }
        if (entity instanceof ConvInfantry trooper) {
            readSlot(Slot.STANDARD, trooper.getProstheticEnhancement1(), trooper.getProstheticEnhancement1Count());
            readSlot(Slot.IMPROVED_1, trooper.getProstheticEnhancement1(), trooper.getProstheticEnhancement1Count());
            readSlot(Slot.IMPROVED_2, trooper.getProstheticEnhancement2(), trooper.getProstheticEnhancement2Count());
            readSlot(Slot.PAIR_1, trooper.getExtraneousPair1(), 1);
            readSlot(Slot.PAIR_2, trooper.getExtraneousPair2(), 1);
            if (wings()) {
                prosthetic.put(Slot.PAIR_2, 0);
            }
        }
    }

    /**
     * The abilities of {@code entity}, empty when the game options and the unit leave none to choose, as for buildings
     * and gun emplacements.
     */
    public static Optional<UnitAbilitiesForm> of(Entity entity, GameOptions gameOptions, boolean editable) {
        UnitAbilitiesForm form = new UnitAbilitiesForm(entity, gameOptions, editable);
        return form.groups.isEmpty() ? Optional.empty() : Optional.of(form);
    }

    private static List<ProstheticEnhancementType> prosthetics() {
        List<ProstheticEnhancementType> types = new ArrayList<>();
        for (ProstheticEnhancementType.EnhancementCategory category :
              ProstheticEnhancementType.EnhancementCategory.values()) {
            for (ProstheticEnhancementType type : ProstheticEnhancementType.values()) {
                if (type.getCategory() == category) {
                    types.add(type);
                }
            }
        }
        return List.copyOf(types);
    }

    private void readSlot(Slot slot, @Nullable ProstheticEnhancementType type, int amount) {
        prosthetic.put(slot, (type == null) ? 0 : PROSTHETICS.indexOf(type) + 1);
        count.put(slot, (type == null) ? 1 : Math.max(1, Math.min(2, amount)));
    }

    private static boolean isGroupHidden(IOptionGroup group, GameOptions gameOptions) {
        if (group.getKey().equalsIgnoreCase(PilotOptions.LVL3_ADVANTAGES)) {
            return !gameOptions.booleanOption(OptionsConstants.RPG_PILOT_ADVANTAGES);
        }
        if (group.getKey().equalsIgnoreCase(PilotOptions.EDGE_ADVANTAGES)) {
            return !gameOptions.booleanOption(OptionsConstants.EDGE);
        }
        if (group.getKey().equalsIgnoreCase(PilotOptions.MD_ADVANTAGES)
              || group.getKey().equalsIgnoreCase(PilotOptions.EI_ADVANTAGES)) {
            return !NeuralInterfaceMode.from(gameOptions).allowsImplants();
        }
        return false;
    }

    private static boolean isOptionHidden(IOption option, Entity entity) {
        if (entity.isBuildingEntityOrGunEmplacement()) {
            return true;
        }
        String name = option.getName();
        if (entity.isConventionalInfantry()
              && (name.equals(OptionsConstants.MD_VDNI) || name.equals(OptionsConstants.MD_BVDNI))) {
            return true;
        }
        boolean infantryOnly = switch (name) {
            case OptionsConstants.MD_PL_ENHANCED, OptionsConstants.MD_PL_I_ENHANCED, OptionsConstants.MD_PL_MASC,
                 OptionsConstants.MD_CYBER_IMP_AUDIO, OptionsConstants.MD_CYBER_IMP_VISUAL,
                 OptionsConstants.MD_CYBER_IMP_LASER, OptionsConstants.MD_CYBER_IMP_TELE,
                 OptionsConstants.MD_GAS_EFFUSER_PHEROMONE, OptionsConstants.MD_GAS_EFFUSER_TOXIN -> true;
            default -> false;
        };
        return !entity.isConventionalInfantry() && infantryOnly;
    }

    private static OptionEntry entry(IOption option, Entity entity, GameOptions gameOptions) {
        String none = Messages.getString("CustomMekDialog.None");
        List<String> choices = new ArrayList<>();
        switch (option.getName()) {
            case OptionsConstants.GUNNERY_WEAPON_SPECIALIST -> {
                choices.add(none);
                choices.addAll(PilotSPAHelper.weaponSpecialistValidWeaponNames(entity, gameOptions));
            }
            case OptionsConstants.GUNNERY_SANDBLASTER -> {
                choices.add(none);
                choices.addAll(PilotSPAHelper.sandblasterValidWeaponNames(entity, gameOptions));
            }
            case OptionsConstants.GUNNERY_SPECIALIST -> choices.addAll(List.of(Crew.SPECIAL_NONE, Crew.SPECIAL_ENERGY,
                  Crew.SPECIAL_BALLISTIC, Crew.SPECIAL_MISSILE));
            case OptionsConstants.GUNNERY_RANGE_MASTER -> choices.addAll(List.of(Crew.RANGEMASTER_NONE,
                  Crew.RANGEMASTER_MEDIUM, Crew.RANGEMASTER_LONG, Crew.RANGEMASTER_EXTREME));
            case OptionsConstants.MISC_HUMAN_TRO -> choices.addAll(List.of(Crew.HUMAN_TRO_NONE, Crew.HUMAN_TRO_MEK,
                  Crew.HUMAN_TRO_AERO, Crew.HUMAN_TRO_VEE, Crew.HUMAN_TRO_BA));
            case OptionsConstants.MISC_ENV_SPECIALIST -> choices.addAll(List.of(Crew.ENVIRONMENT_SPECIALIST_NONE,
                  Crew.ENVIRONMENT_SPECIALIST_FOG, Crew.ENVIRONMENT_SPECIALIST_LIGHT, Crew.ENVIRONMENT_SPECIALIST_RAIN,
                  Crew.ENVIRONMENT_SPECIALIST_SNOW, Crew.ENVIRONMENT_SPECIALIST_WIND));
            default -> {
            }
        }
        boolean partial = SpaCatalog.getEntry(option.getName())
              .map(catalog -> catalog.status() == SpaImplementationStatus.PARTIAL).orElse(false);
        return OptionEntry.of(option, partial ? Messages.getString("CustomMekDialog.spaPartialSuffix") : "", choices,
              false);
    }

    /**
     * Links the equipment whose neural interface fittings follow the implants, as the Swing equipment tab follows the
     * pilot tab; none for a unit without equipment choices.
     */
    void link(@Nullable UnitEquipmentForm equipment) {
        this.equipment = equipment;
    }

    /** Whether the abilities may be changed. */
    public boolean editable() {
        return editable;
    }

    /** The ability groups, in the pilot options' order. */
    public List<Group> groups() {
        return groups;
    }

    /** The abilities in all groups. */
    public List<OptionEntry> entries() {
        return groups.stream().flatMap(group -> group.entries().stream()).toList();
    }

    /** The ability named {@code name}, if shown. */
    public Optional<OptionEntry> entry(String name) {
        return entries().stream().filter(entry -> entry.name().equals(name)).findFirst();
    }

    /** Why the last choice was refused or changed another, empty when it was simply taken. */
    public String notice() {
        return notice;
    }

    private boolean selected(String name) {
        return entry(name).map(OptionEntry::on).orElse(false);
    }

    private void deselect(String name) {
        entry(name).ifPresent(entry -> entry.on(false));
    }

    private boolean wings() {
        return selected(OptionsConstants.MD_PL_GLIDER) || selected(OptionsConstants.MD_PL_FLIGHT);
    }

    private static String message(String key) {
        return Messages.getString(key).replaceAll("\\s*\\n+\\s*", " ").trim();
    }

    /**
     * Turns the ability {@code entry} on or off under the Swing dialog's rules: a sensory implant beyond the second,
     * a second gas effuser or a neural interface the unit cannot take is refused; the enhanced prosthetics, the wings
     * and the neural interfaces put out their rivals; wings clear the second limb pair; the implants set the neural
     * interface fittings of the equipment.
     */
    public void set(OptionEntry entry, boolean on) {
        notice = "";
        if (!editable) {
            return;
        }
        String name = entry.name();
        if (on) {
            String refusal = refusal(name);
            if (refusal != null) {
                notice = message(refusal);
                return;
            }
        }
        entry.on(on);
        if (on) {
            switch (name) {
                case OptionsConstants.MD_PL_ENHANCED -> deselect(OptionsConstants.MD_PL_I_ENHANCED);
                case OptionsConstants.MD_PL_I_ENHANCED -> deselect(OptionsConstants.MD_PL_ENHANCED);
                case OptionsConstants.MD_PL_GLIDER -> deselect(OptionsConstants.MD_PL_FLIGHT);
                case OptionsConstants.MD_PL_FLIGHT -> deselect(OptionsConstants.MD_PL_GLIDER);
                default -> {
                }
            }
            if (DNI.contains(name)) {
                DNI.stream().filter(other -> !other.equals(name)).forEach(this::deselect);
            }
            if (infantry && wings() && prosthetic.getOrDefault(Slot.PAIR_2, 0) > 0) {
                prosthetic.put(Slot.PAIR_2, 0);
                notice = message("CustomMekDialog.GliderWingsLimitExtraneousLimbs");
            }
        }
        if (equipment != null) {
            if (name.equals(OptionsConstants.MD_EI_IMPLANT)) {
                equipment.value(Toggle.EI, on);
            } else if (DNI.contains(name)) {
                equipment.value(Toggle.DNI, DNI.stream().anyMatch(this::selected));
            }
        }
    }

    private @Nullable String refusal(String name) {
        if (infantry && SENSORY_IMPLANTS.contains(name)
              && SENSORY_IMPLANTS.stream().filter(other -> !other.equals(name)).filter(this::selected).count() >= 2) {
            return "CustomMekDialog.MaxSensoryImplants";
        }
        if (name.equals(OptionsConstants.MD_GAS_EFFUSER_PHEROMONE) || name.equals(OptionsConstants.MD_GAS_EFFUSER_TOXIN)) {
            if (!infantry) {
                return "CustomMekDialog.GasEffuserInfantryOnly";
            }
            String other = name.equals(OptionsConstants.MD_GAS_EFFUSER_PHEROMONE)
                  ? OptionsConstants.MD_GAS_EFFUSER_TOXIN : OptionsConstants.MD_GAS_EFFUSER_PHEROMONE;
            if (selected(other)) {
                return "CustomMekDialog.GasEffuserOnlyOne";
            }
        }
        if (name.equals(OptionsConstants.MD_PROTO_DNI) && !protoDniFits) {
            return "CustomMekDialog.ProtoDniBattleMekOnly";
        }
        if ((name.equals(OptionsConstants.MD_VDNI) || name.equals(OptionsConstants.MD_BVDNI)) && !vdniFits) {
            return "CustomMekDialog.VdniInvalidUnitType";
        }
        return null;
    }

    /** Sets a whole-number ability, such as the edge points. */
    public void number(OptionEntry entry, int value) {
        if (editable) {
            entry.number(value);
        }
    }

    /** Chooses a choice ability's value by its index. */
    public void choice(OptionEntry entry, int index) {
        if (editable) {
            entry.choice(index);
        }
    }

    // region Prosthetics

    /** The prosthetic slots shown with {@code entry} while it is on; only conventional infantry have them. */
    public List<Slot> slots(OptionEntry entry) {
        if (!infantry) {
            return List.of();
        }
        return Arrays.stream(Slot.values()).filter(slot -> slot.option().equals(entry.name())).toList();
    }

    /** The enhancements to choose for a slot, "None" first, each as "category: name". */
    public List<String> prostheticChoices() {
        List<String> choices = new ArrayList<>();
        choices.add(Messages.getString("CustomMekDialog.None"));
        PROSTHETICS.forEach(type -> choices.add(type.getCategory().getDisplayName() + ": " + type.getDisplayName()));
        return choices;
    }

    /** The index of the slot's enhancement in {@link #prostheticChoices()}, 0 for none. */
    public int prosthetic(Slot slot) {
        return prosthetic.getOrDefault(slot, 0);
    }

    /** Chooses the slot's enhancement; the second limb pair stays empty while the trooper has wings. */
    public void prosthetic(Slot slot, int index) {
        notice = "";
        if (!editable || index < 0 || index > PROSTHETICS.size()) {
            return;
        }
        if (slot == Slot.PAIR_2 && index > 0 && wings()) {
            notice = message("CustomMekDialog.GliderWingsLimitExtraneousLimbs");
            return;
        }
        prosthetic.put(slot, index);
    }

    /** How many of the slot's enhancement each trooper has, 1 or 2. */
    public int count(Slot slot) {
        return count.getOrDefault(slot, 1);
    }

    /** Sets how many of the slot's enhancement each trooper has, held to 1 or 2. */
    public void count(Slot slot, int value) {
        if (editable && slot.counted()) {
            count.put(slot, Math.max(1, Math.min(2, value)));
        }
    }

    /** Whether the slot's enhancement may be chosen: the second limb pair not while the trooper has wings. */
    public boolean prostheticEnabled(Slot slot) {
        return editable && !(slot == Slot.PAIR_2 && wings());
    }

    /** Whether the slot's count may be set: only with an enhancement chosen. */
    public boolean countEnabled(Slot slot) {
        return editable && slot.counted() && prosthetic(slot) > 0;
    }

    private @Nullable ProstheticEnhancementType type(Slot slot) {
        int index = prosthetic(slot);
        return (index <= 0) ? null : PROSTHETICS.get(index - 1);
    }

    // endregion

    /** Writes the abilities to the unit's crew, as the Swing {@code setOptions} does. */
    void apply(Entity entity) {
        PilotOptions options = entity.getCrew().getOptions();
        for (OptionEntry entry : entries()) {
            entry.write(options.getOption(entry.name()));
        }
    }

    /**
     * Writes the prosthetic enhancements and extraneous limbs of conventional infantry from the abilities just applied,
     * as the Swing {@code applyProstheticEnhancement} and {@code applyExtraneousLimbs} do.
     */
    void applyProsthetics(Entity entity) {
        if (!(entity instanceof ConvInfantry trooper)) {
            return;
        }
        if (entity.hasAbility(OptionsConstants.MD_PL_ENHANCED) && entry(OptionsConstants.MD_PL_ENHANCED).isPresent()) {
            writeSlot(trooper, Slot.STANDARD, true);
            trooper.setProstheticEnhancement2(null);
            trooper.setProstheticEnhancement2Count(0);
        } else if (entity.hasAbility(OptionsConstants.MD_PL_I_ENHANCED)
              && entry(OptionsConstants.MD_PL_I_ENHANCED).isPresent()) {
            writeSlot(trooper, Slot.IMPROVED_1, true);
            writeSlot(trooper, Slot.IMPROVED_2, false);
        } else {
            trooper.setProstheticEnhancement1(null);
            trooper.setProstheticEnhancement1Count(0);
            trooper.setProstheticEnhancement2(null);
            trooper.setProstheticEnhancement2Count(0);
        }
        if (entity.hasAbility(OptionsConstants.MD_PL_EXTRA_LIMBS)
              && entry(OptionsConstants.MD_PL_EXTRA_LIMBS).isPresent()) {
            trooper.setExtraneousPair1(type(Slot.PAIR_1));
            trooper.setExtraneousPair2(type(Slot.PAIR_2));
        } else {
            trooper.setExtraneousPair1(null);
            trooper.setExtraneousPair2(null);
        }
    }

    private void writeSlot(ConvInfantry trooper, Slot slot, boolean first) {
        ProstheticEnhancementType type = type(slot);
        int amount = (type == null) ? 0 : count(slot);
        if (first) {
            trooper.setProstheticEnhancement1(type);
            trooper.setProstheticEnhancement1Count(amount);
        } else {
            trooper.setProstheticEnhancement2(type);
            trooper.setProstheticEnhancement2Count(amount);
        }
    }
}
