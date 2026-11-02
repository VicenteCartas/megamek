/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.common.SimpleTechLevel;
import megamek.common.TechConstants;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.enums.TechBase;
import megamek.common.enums.VariableRangeTargetingMode;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.BombLoadout;
import megamek.common.equipment.EquipmentActivation;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.EquipmentTypeLookup;
import megamek.common.equipment.HandheldWeapon;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.Sensor;
import megamek.common.equipment.SensorChoiceHandler;
import megamek.common.equipment.SensorFamily;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.equipment.enums.AmmoTypeFlag;
import megamek.common.equipment.enums.BombType.BombTypeEnum;
import megamek.common.equipment.enums.MiscTypeFlag;
import megamek.common.exceptions.LocationFullException;
import megamek.common.game.Game;
import megamek.common.options.IGameOptions;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Aero;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.BaConstructionUtil;
import megamek.common.units.ConstructionUtil;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.IBomber;
import megamek.common.units.Infantry;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.util.RoundWeight;
import megamek.common.verifier.TestBattleArmor;
import megamek.common.verifier.TestBattleArmor.BAManipulator;
import megamek.common.verifier.TestEntity;
import megamek.common.weapons.infantry.InfantryWeapon;
import megamek.logging.MMLogger;

/**
 * The values of a single unit's equipment configuration (the Swing {@code EquipChoicePanel} and its choices): C3, BA
 * AP weapons and manipulators, carried munitions, the weapons' ammo feeds or a bay unit's bay munitions, small support
 * vehicle and infantry SRM munitions, bombs, rapid-fire MGs, variable range targeting, mines, infantry armor, the
 * starting sensor, the electronics' modes and the misc toggles. Read on the Swing thread, edited by the view on its
 * own and written by {@link #apply} on the Swing thread into the unit as it is then: every row holds equipment numbers
 * rather than mounts, since the server's echo replaces the unit.
 */
public final class UnitEquipmentForm {
    private static final MMLogger LOGGER = MMLogger.create(UnitEquipmentForm.class);

    /** The yes-or-no choices of the misc section and the ejection seats. */
    public enum Toggle {
        AUTO_EJECT("CustomMekDialog.labAutoEject"),
        EJECT_AMMO("CustomMekDialog.labConditional_Ejection_Ammo"),
        EJECT_ENGINE("CustomMekDialog.labConditional_Ejection_Engine"),
        EJECT_CT("CustomMekDialog.labConditional_Ejection_CT_Destroyed"),
        EJECT_HEAD("CustomMekDialog.labConditional_Ejection_Headshot"),
        EJECT_FUEL("CustomMekDialog.labConditional_Ejection_Fuel"),
        EJECT_SI("CustomMekDialog.labConditional_Ejection_SI_Destroyed"),
        SEARCHLIGHT("CustomMekDialog.labSearchlight"),
        SEARCHLIGHT_ON("CustomMekDialog.labSearchlightStatus"),
        DNI("CustomMekDialog.labDNICockpitMod"),
        EI("CustomMekDialog.labEICockpit"),
        DIC("CustomMekDialog.labDamageInterruptCircuit");

        private final String key;

        Toggle(String key) {
            this.key = key;
        }

        /** The label's message key. */
        public String key() {
            return key;
        }
    }

    /** A C3 network choice: its label and the unit it names, -1 for none (an independent master). */
    public record C3Choice(String label, int id) { }

    /** The mine types offered, in their order; command-detonated mines are not implemented and not offered. */
    public static final List<Integer> MINES = List.of(MiscMounted.MINE_CONVENTIONAL, MiscMounted.MINE_VIBRABOMB,
          MiscMounted.MINE_ACTIVE, MiscMounted.MINE_INFERNO, MiscMounted.MINE_EMP);
    private static final List<String> MINE_KEYS = List.of("CustomMekDialog.Conventional", "CustomMekDialog.Vibrabomb",
          "CustomMekDialog.Active", "CustomMekDialog.Inferno", "CustomMekDialog.EMP");

    private final boolean editable;
    private final Map<Toggle, Boolean> toggles = new EnumMap<>(Toggle.class);
    private final Set<Toggle> shown = EnumSet.noneOf(Toggle.class);
    private final boolean searchlightLocked;
    private final boolean searchlightAuto;
    private final boolean searchlightsDefault;
    private final List<C3Choice> c3 = new ArrayList<>();
    private int c3Index = -1;
    /** The network the unit is on when read; OK leaves it alone unless another is chosen. */
    private final int c3Initial;
    private final boolean c3Network;
    private final List<WeaponType> apWeapons = new ArrayList<>();
    private final List<ApMount> apMounts = new ArrayList<>();
    private @Nullable Manipulators manipulators;
    private final List<AmmoBin> ammo = new ArrayList<>();
    private final List<WeaponFeed> feeds = new ArrayList<>();
    private final List<BayRow> bays = new ArrayList<>();
    private final List<SmallSvRow> smallSv = new ArrayList<>();
    private @Nullable InfantrySrm infantrySrm;
    private final List<BombBay> bombs = new ArrayList<>();
    private final List<RapidFire> rapidFire = new ArrayList<>();
    private @Nullable Vrt vrt;
    private @Nullable InfantryArmor infantryArmor;
    private final List<Mine> mines = new ArrayList<>();
    private @Nullable SensorChoice sensor;
    private final List<ModeRow> modes = new ArrayList<>();
    private final boolean stealthOn;

    /** The form for a unit, or empty for a handheld weapon, which has nothing to configure. */
    public static Optional<UnitEquipmentForm> of(Entity entity, Client client, boolean editable) {
        return (entity instanceof HandheldWeapon) ? Optional.empty()
              : Optional.of(new UnitEquipmentForm(entity, client, editable));
    }

    UnitEquipmentForm(Entity entity, Client client, boolean editable) {
        this.editable = editable;
        Game game = client.getGame();
        IGameOptions options = game.getOptions();
        c3Network = entity.hasC3() || entity.hasC3i() || entity.hasNavalC3();
        if (c3Network) {
            readC3(entity, client);
            if ((c3Index < 0) && !c3.isEmpty()) {
                // Swing's drop-down falls on its first network here, and OK would join it; this keeps the slave free
                c3.add(0, new C3Choice(Messages.getString("Lobby.bay.c3Unconnected"), Entity.NONE));
                c3Index = 0;
            }
        }
        c3Initial = c3Index;
        if (entity instanceof BattleArmor battleArmor) {
            readBattleArmor(battleArmor, game);
        }
        if (setsUpMunitions(entity)) {
            readMunitions(entity, game);
            readFeeds(entity);
        }
        if ((entity instanceof ConvInfantry infantry) && infantry.hasSrmLauncher()) {
            infantrySrm = new InfantrySrm(infantry);
        }
        if (entity.isBomber()) {
            readBombs((IBomber) entity, options);
        }
        if (options.booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_BURST) && !(entity instanceof Infantry)) {
            for (WeaponMounted weapon : entity.getWeaponList()) {
                if (weapon.getType().hasFlag(WeaponType.F_MG)) {
                    rapidFire.add(new RapidFire(entity, weapon));
                }
            }
        }
        if (entity.hasVariableRangeTargeting()) {
            vrt = new Vrt(entity.getVariableRangeTargetingMode().isLong());
        }
        if (entity instanceof ConvInfantry infantry) {
            infantryArmor = new InfantryArmor(infantry);
        }
        for (MiscMounted misc : entity.getMisc()) {
            if (misc.getType().hasFlag(MiscType.F_MINE) || misc.getType().hasFlag(MiscType.F_VEHICLE_MINE_DISPENSER)) {
                mines.add(new Mine(entity, misc));
            }
        }
        if (entity.getSensors().size() >= 2) {
            sensor = new SensorChoice(entity);
        }
        stealthOn = EquipmentActivation.isStealthOnOrActivating(entity);
        readModes(entity, options);
        searchlightsDefault = options.booleanOption(OptionsConstants.SEARCHLIGHTS_ON);
        searchlightAuto = entity.getsAutoExternalSearchlight();
        searchlightLocked = entity.hasQuirk(OptionsConstants.QUIRK_POS_SEARCHLIGHT);
        readToggles(entity, game);
    }

    /** Whether the munitions sections apply: not to conventional infantry without a field weapon. */
    static boolean setsUpMunitions(Entity entity) {
        return entity.isBattleArmor() || !(entity instanceof ConvInfantry infantry) || infantry.hasFieldWeapon();
    }

    /** Whether the choices may be changed. */
    public boolean editable() {
        return editable;
    }

    /** Whether there is nothing to configure. */
    public boolean isEmpty() {
        return c3.isEmpty() && apMounts.isEmpty() && (manipulators == null) && ammo.isEmpty() && feeds.isEmpty()
              && bays.isEmpty() && smallSv.isEmpty() && (infantrySrm == null) && bombs.isEmpty()
              && rapidFire.isEmpty() && (vrt == null) && (infantryArmor == null) && mines.isEmpty()
              && (sensor == null) && modes.isEmpty() && shown.isEmpty();
    }

    // region Toggles

    private void readToggles(Entity entity, Game game) {
        IGameOptions options = game.getOptions();
        if (game.getPlanetaryConditions().getLight().isDuskOrFullMoonOrMoonlessOrPitchBack()) {
            boolean has = false;
            if (!searchlightAuto) {
                has = entity.hasSearchlight() || searchlightLocked;
                show(Toggle.SEARCHLIGHT, has);
            }
            boolean on = false;
            if (searchlightAuto || has) {
                on = entity.getSearchlightOverride() != searchlightsDefault;
            }
            show(Toggle.SEARCHLIGHT_ON, on);
        }
        IOption neural = options.getOption(OptionsConstants.ADVANCED_NEURAL_INTERFACE_MODE);
        boolean fullTracking = (neural != null)
              && OptionsConstants.NEURAL_INTERFACE_MODE_FULL_TRACKING.equals(neural.stringValue());
        int year = options.intOption(OptionsConstants.ALLOWED_YEAR);
        if (fullTracking && (entity.isMek() || entity.isCombatVehicle() || entity.isFighter()
              || entity.isSupportVehicle() || (entity instanceof BattleArmor))
              && (!entity.isClan() || entity.isMixedTech())
              && (year >= introduced("DNICockpitModification", false, 3052))) {
            show(Toggle.DNI, entity.hasDNICockpitMod() || entity.hasDNIImplant());
        }
        if (fullTracking && (entity.isMek() || (entity instanceof BattleArmor) || entity.isProtoMek())
              && (entity.isClan() || entity.isMixedTech())
              && (year >= introduced("EIInterface", true, 3040))) {
            show(Toggle.EI, entity.hasEiCockpit() || entity.hasAbility(OptionsConstants.MD_EI_IMPLANT));
        }
        boolean conditional = options.booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION);
        if (entity instanceof Mek mek) {
            if (mek.hasEjectSeat()) {
                show(Toggle.AUTO_EJECT, !mek.isAutoEject());
                if (conditional) {
                    show(Toggle.EJECT_AMMO, mek.isCondEjectAmmo());
                    show(Toggle.EJECT_ENGINE, mek.isCondEjectEngine());
                    show(Toggle.EJECT_CT, mek.isCondEjectCTDest());
                    show(Toggle.EJECT_HEAD, mek.isCondEjectHeadshot());
                }
            }
        } else if ((entity instanceof AeroSpaceFighter fighter) && fighter.hasEjectSeat()) {
            show(Toggle.AUTO_EJECT, !fighter.isAutoEject());
            if (conditional) {
                show(Toggle.EJECT_AMMO, fighter.isCondEjectAmmo());
                show(Toggle.EJECT_FUEL, fighter.isCondEjectFuel());
                show(Toggle.EJECT_SI, fighter.isCondEjectSIDest());
            }
        }
        EquipmentType circuit = EquipmentType.get("DamageInterruptCircuit");
        if ((entity instanceof Mek mek) && (!entity.isClan() || entity.isMixedTech()) && (circuit != null)
              && (year >= circuit.getIntroductionDate(false))) {
            show(Toggle.DIC, mek.hasDamageInterruptCircuit());
        }
    }

    private static int introduced(String name, boolean clan, int fallback) {
        EquipmentType type = EquipmentType.get(name);
        return (type == null) ? fallback : type.getIntroductionDate(clan);
    }

    private void show(Toggle toggle, boolean value) {
        shown.add(toggle);
        toggles.put(toggle, value);
    }

    /** The toggles shown, in their order. */
    public List<Toggle> toggles() {
        return Arrays.stream(Toggle.values()).filter(shown::contains).toList();
    }

    /** Whether the toggle is shown. */
    public boolean shows(Toggle toggle) {
        return shown.contains(toggle);
    }

    /** The toggle's value. */
    public boolean value(Toggle toggle) {
        return toggles.getOrDefault(toggle, false);
    }

    /** Whether the toggle may be changed: the searchlight quirk fixes the searchlight, which a light needs on. */
    public boolean enabled(Toggle toggle) {
        return editable && shows(toggle) && switch (toggle) {
            case SEARCHLIGHT -> !searchlightLocked;
            case SEARCHLIGHT_ON -> searchlightAuto || value(Toggle.SEARCHLIGHT);
            default -> true;
        };
    }

    /** Sets a shown toggle; the pilot's neural implants set DNI and EI as the Swing dialog's do. */
    public void value(Toggle toggle, boolean value) {
        if (shows(toggle)) {
            toggles.put(toggle, value);
        }
    }

    // endregion

    // region C3

    private void readC3(Entity entity, Client client) {
        if (entity.hasC3i() || entity.hasNavalC3()) {
            c3.add(new C3Choice(Messages.getString("CustomMekDialog.CreateNewNetwork"), entity.getId()));
            if (entity.getC3Master() == null) {
                c3Index = 0;
            }
        } else if (entity.hasC3MM()) {
            int sNodes = entity.calculateFreeC3Nodes();
            c3.add(new C3Choice(Messages.getString("CustomMekDialog.setCompanyMaster",
                  entity.calculateFreeC3MNodes(), sNodes), entity.getId()));
            masters(entity, sNodes);
        } else if (entity.hasC3M()) {
            int nodes = entity.calculateFreeC3Nodes();
            c3.add(new C3Choice(Messages.getString("CustomMekDialog.setCompanyMaster1", nodes), entity.getId()));
            masters(entity, nodes);
        }
        for (Entity other : client.getEntitiesVector()) {
            if (entity.isEnemyOf(other) || entity.equals(other) || (entity.hasC3i() != other.hasC3i())
                  || (entity.hasNavalC3() != other.hasNavalC3()) || (entity.hasNovaCEWS() != other.hasNovaCEWS())) {
                continue;
            }
            Entity company = other.getC3Master();
            if ((company != null) && (company.getC3Master() != company)) {
                continue;
            }
            int nodes = other.calculateFreeC3Nodes();
            if (entity.hasC3M() && other.C3MasterIs(other)) {
                nodes = other.calculateFreeC3MNodes();
            }
            if (entity.C3MasterIs(other)) {
                nodes++;
            }
            if ((entity.hasC3i() || entity.hasNavalC3()) && entity.onSameC3NetworkAs(other)) {
                nodes++;
            }
            if (nodes == 0) {
                continue;
            }
            String name = other.getDisplayName();
            if (other.hasC3i() || other.hasNavalC3()) {
                if (entity.onSameC3NetworkAs(other)) {
                    c3Index = c3.size();
                    c3.add(new C3Choice(Messages.getString("CustomMekDialog.join1", name, other.getC3NetId(),
                          nodes - 1), other.getId()));
                } else {
                    c3.add(new C3Choice(Messages.getString("CustomMekDialog.join2", name, other.getC3NetId(),
                          nodes), other.getId()));
                }
            } else if (other.C3MasterIs(other) && other.hasC3MM()) {
                if (entity.C3MasterIs(other)) {
                    c3Index = c3.size();
                }
                c3.add(new C3Choice(Messages.getString("CustomMekDialog.connect2", name, other.getC3NetId(), nodes),
                      other.getId()));
            } else if (other.C3MasterIs(other) != entity.hasC3M()) {
                // A slave connects only to sub-masters, and a master only to company masters
                continue;
            } else if (entity.C3MasterIs(other)) {
                c3Index = c3.size();
                c3.add(new C3Choice(Messages.getString("CustomMekDialog.connect1", name, other.getC3NetId(),
                      nodes - 1), other.getId()));
            } else {
                c3.add(new C3Choice(Messages.getString("CustomMekDialog.connect2", name, other.getC3NetId(), nodes),
                      other.getId()));
            }
        }
    }

    private void masters(Entity entity, int nodes) {
        if (entity.C3MasterIs(entity)) {
            c3Index = 0;
        }
        c3.add(new C3Choice(Messages.getString("CustomMekDialog.setIndependentMaster", nodes), -1));
        if (entity.getC3Master() == null) {
            c3Index = 1;
        }
    }

    /** The C3 network choices; empty when the unit has no C3. */
    public List<C3Choice> c3() {
        return List.copyOf(c3);
    }

    /** The chosen C3 network's index, -1 for none. */
    public int c3Index() {
        return c3Index;
    }

    /** Chooses a C3 network. */
    public void c3Index(int index) {
        if ((index >= 0) && (index < c3.size())) {
            c3Index = index;
        }
    }

    // endregion

    // region Battle armor

    private void readBattleArmor(BattleArmor battleArmor, Game game) {
        IGameOptions options = game.getOptions();
        boolean disposables = options.booleanOption(OptionsConstants.ADVANCED_COMBAT_DISPOSABLE_INFANTRY_WEAPONS);
        int year = options.intOption(OptionsConstants.ALLOWED_YEAR);
        SimpleTechLevel level = SimpleTechLevel.getGameTechLevel(game);
        boolean extinct = options.booleanOption(OptionsConstants.ALLOWED_SHOW_EXTINCT);
        List<WeaponType> gloveWeapons = new ArrayList<>();
        for (EquipmentType type : EquipmentType.allTypes()) {
            if (!(type instanceof InfantryWeapon weapon) || !weapon.hasFlag(WeaponType.F_INFANTRY)
                  || weapon.hasFlag(WeaponType.F_INF_POINT_BLANK) || weapon.hasFlag(WeaponType.F_INF_ARCHAIC)
                  || !type.isLegal(year, level, battleArmor.isClan(), battleArmor.isMixedTech(), extinct)) {
                continue;
            }
            boolean disposable = weapon.hasFlag(WeaponType.F_INF_DISPOSABLE);
            if (!disposable || disposables) {
                if (!weapon.hasFlag(WeaponType.F_INF_SUPPORT) || disposable) {
                    apWeapons.add(weapon);
                }
                if (weapon.getCrew() < 2) {
                    gloveWeapons.add(weapon);
                }
            }
        }
        for (MiscMounted misc : battleArmor.getMisc()) {
            if ((misc.getLinked() == null) || !(misc.getLinked().getType() instanceof WeaponType linked)) {
                continue;
            }
            if (misc.is(EquipmentTypeLookup.BA_APM) && !apWeapons.contains(linked)) {
                apWeapons.add(linked);
            }
            if (misc.getType().hasFlag(MiscType.F_ARMORED_GLOVE) && !gloveWeapons.contains(linked)) {
                gloveWeapons.add(linked);
            }
        }
        apWeapons.sort(Comparator.comparing(EquipmentType::getName));
        gloveWeapons.sort(Comparator.comparing(EquipmentType::getName));
        for (MiscMounted misc : battleArmor.getMisc()) {
            if (misc.is(EquipmentTypeLookup.BA_APM)) {
                apMounts.add(new ApMount(battleArmor, misc, apWeapons));
            }
        }
        if (battleArmor.hasWorkingMisc(MiscType.F_BA_MEA) || battleArmor.hasMisc(MiscTypeFlag.F_ARMORED_GLOVE)) {
            manipulators = new Manipulators(battleArmor, gloveWeapons);
        }
    }

    /** A weapon choice's label: the name, marked when disposable. */
    static String weaponLabel(EquipmentType type) {
        if ((type instanceof WeaponType weapon) && weapon.hasFlag(WeaponType.F_INF_DISPOSABLE)) {
            return Messages.getString("CustomMekDialog.disposableWeaponLabel", type.getName());
        }
        return type.getName();
    }

    private static List<String> weaponLabels(List<? extends EquipmentType> types) {
        List<String> labels = new ArrayList<>();
        labels.add(Messages.getString("CustomMekDialog.infDisposableNone"));
        types.forEach(type -> labels.add(weaponLabel(type)));
        return labels;
    }

    /** A BA anti-personnel weapon mount's weapons: None and then the weapons, by index. */
    public List<ApMount> apMounts() {
        return List.copyOf(apMounts);
    }

    /** The BA manipulators, when the suits have modular equipment adaptors or armored gloves. */
    public Optional<Manipulators> manipulators() {
        return Optional.ofNullable(manipulators);
    }

    /** An AP mount's weapon. */
    public final class ApMount {
        private final int number;
        private final String label;
        private final List<WeaponType> weapons;
        private int index;

        ApMount(Entity entity, MiscMounted mount, List<WeaponType> weapons) {
            number = entity.getEquipmentNum(mount);
            label = BattleArmor.MOUNT_LOC_NAMES[mount.getBaMountLoc()];
            this.weapons = weapons;
            index = (mount.getLinked() == null) ? 0 : Math.max(0, weapons.indexOf(mount.getLinked().getType()) + 1);
        }

        /** Where the mount sits on the suit. */
        public String label() {
            return label;
        }

        /** None, then the weapons. */
        public List<String> choices() {
            return weaponLabels(weapons);
        }

        /** The chosen index, 0 for none. */
        public int index() {
            return index;
        }

        /** Chooses a weapon by index, 0 for none. */
        public void index(int value) {
            if ((value >= 0) && (value <= weapons.size())) {
                index = value;
            }
        }

        private @Nullable WeaponType chosen() {
            return (index > 0) ? weapons.get(index - 1) : null;
        }
    }

    /** The left and right arm manipulators, the cargo lifter's capacity and the armored glove's weapon. */
    public final class Manipulators {
        /** The cargo lifter's capacity steps: half a ton each, from half a ton to 80 tons. */
        public static final int MIN_CARGO = 1;
        public static final int MAX_CARGO = 160;

        private final List<BAManipulator> choices;
        private final boolean leftAdaptor;
        private final boolean rightAdaptor;
        private final List<WeaponType> gloveWeapons;
        private final String free;
        private final MiscMounted cargoLifter;
        private int left;
        private int right;
        private int cargo = MIN_CARGO;
        private int glove;

        Manipulators(BattleArmor battleArmor, List<WeaponType> gloveWeapons) {
            this.gloveWeapons = gloveWeapons;
            leftAdaptor = battleArmor.hasMiscInMountLocation(EquipmentTypeLookup.BA_MODULAR_EQUIPMENT_ADAPTOR,
                  BattleArmor.MOUNT_LOC_LEFT_ARM);
            rightAdaptor = battleArmor.hasMiscInMountLocation(EquipmentTypeLookup.BA_MODULAR_EQUIPMENT_ADAPTOR,
                  BattleArmor.MOUNT_LOC_RIGHT_ARM);
            double free = Math.round(battleArmor.getTrooperWeight() * 1000 - heaviestTrooper(battleArmor)
                  + manipulatorKg(battleArmor));
            this.free = (leftAdaptor || rightAdaptor) ? Messages.getString("CustomMekDialog.ba.freeWeight", free)
                  : "";
            List<BAManipulator> valid = new ArrayList<>(Arrays.asList(BAManipulator.values()));
            if (battleArmor.countMisc(EquipmentTypeLookup.BA_MODULAR_EQUIPMENT_ADAPTOR) == 1) {
                valid.removeIf(manipulator -> manipulator.pairMounted);
            }
            choices = List.copyOf(valid);
            left = Math.max(0, choices.indexOf(BAManipulator.getManipulator(battleArmor.getLeftManipulatorName())));
            right = Math.max(0, choices.indexOf(BAManipulator.getManipulator(battleArmor.getRightManipulatorName())));
            battleArmor.getMisc().stream()
                  .filter(misc -> misc.getType().hasFlag(MiscTypeFlag.F_ARMORED_GLOVE) && (misc.getLinked() != null))
                  .findFirst()
                  .ifPresent(misc -> glove = Math.max(0, gloveWeapons.indexOf(misc.getLinked().getType()) + 1));
            MiscMounted lifter = battleArmor.getManipulator(BattleArmor.MOUNT_LOC_LEFT_ARM);
            if ((lifter != null) && lifter.is(EquipmentTypeLookup.BA_MANIPULATOR_CARGO_LIFTER)) {
                cargo = (int) Math.round(Math.max(0.5, Math.min(80, lifter.getSize())) * 2);
            }
            cargoLifter = new MiscMounted(battleArmor,
                  (MiscType) EquipmentType.get(EquipmentTypeLookup.BA_MANIPULATOR_CARGO_LIFTER));
        }

        private static double heaviestTrooper(BattleArmor battleArmor) {
            TestBattleArmor test = (TestBattleArmor) TestEntity.getEntityVerifier(battleArmor);
            double heaviest = 0;
            for (int trooper = 1; trooper <= battleArmor.getSquadSize(); trooper++) {
                heaviest = Math.max(heaviest, test.calculateWeight(trooper));
            }
            return heaviest * 1000;
        }

        private static double manipulatorKg(BattleArmor battleArmor) {
            return battleArmor.getMisc().stream()
                  .filter(misc -> misc.getType().hasFlag(MiscType.F_BA_MANIPULATOR))
                  .mapToDouble(misc -> misc.getTonnage() * 1000)
                  .sum();
        }

        /** The manipulators' names, for both arms. */
        public List<String> choices() {
            return choices.stream().map(manipulator -> manipulator.displayName).toList();
        }

        /** The left arm's manipulator index. */
        public int left() {
            return left;
        }

        /** The right arm's manipulator index. */
        public int right() {
            return right;
        }

        /** Whether the left arm's manipulator may be changed: it needs an adaptor. */
        public boolean leftEnabled() {
            return editable && leftAdaptor;
        }

        /** Whether the right arm's may: it needs an adaptor and no pair on the left. */
        public boolean rightEnabled() {
            return editable && rightAdaptor && !choices.get(left).pairMounted;
        }

        /** Chooses the left arm's manipulator; a pair takes both arms, and replacing one frees the other arm. */
        public void left(int index) {
            left = choose(index, left, true);
        }

        /** Chooses the right arm's manipulator. */
        public void right(int index) {
            right = choose(index, right, false);
        }

        private int choose(int index, int current, boolean isLeft) {
            if ((index < 0) || (index >= choices.size())) {
                return current;
            }
            int other = isLeft ? right : left;
            if (choices.get(index).pairMounted) {
                other = index;
            } else if (choices.get(other).pairMounted) {
                other = 0;
            }
            if (isLeft) {
                right = other;
            } else {
                left = other;
            }
            return index;
        }

        /** The manipulator weight still free, or empty without adaptors. */
        public String free() {
            return free;
        }

        /** Whether the left arm carries a cargo lifter, whose capacity is chosen. */
        public boolean showsCargo() {
            return choices.get(left) == BAManipulator.CARGO_LIFTER;
        }

        /** The cargo lifter's capacity in half tons. */
        public int cargo() {
            return cargo;
        }

        /** Sets the cargo lifter's capacity in half tons. */
        public void cargo(int halfTons) {
            cargo = Math.max(MIN_CARGO, Math.min(MAX_CARGO, halfTons));
        }

        /** The capacity's text and the lifters' weight. */
        public String cargoText() {
            cargoLifter.setSize(cargo / 2.0);
            return Messages.getString("CustomMekDialog.ba.cargoLifterWeight",
                  cargoLifter.getTonnage(RoundWeight.NEAREST_KG) * 2 * 1000);
        }

        /** Whether an arm has an armored glove, whose weapon is chosen. */
        public boolean showsGlove() {
            return (choices.get(left) == BAManipulator.ARMORED_GLOVE)
                  || (choices.get(right) == BAManipulator.ARMORED_GLOVE);
        }

        /** None, then the glove's weapons. */
        public List<String> gloves() {
            return weaponLabels(gloveWeapons);
        }

        /** The glove weapon's index, 0 for none. */
        public int glove() {
            return glove;
        }

        /** Chooses the glove's weapon. */
        public void glove(int index) {
            if ((index >= 0) && (index <= gloveWeapons.size())) {
                glove = index;
            }
        }

        private void apply(BattleArmor battleArmor) {
            place(battleArmor, choices.get(left), BattleArmor.MOUNT_LOC_LEFT_ARM);
            place(battleArmor, choices.get(right), BattleArmor.MOUNT_LOC_RIGHT_ARM);
            WeaponType chosen = (glove > 0) ? gloveWeapons.get(glove - 1) : null;
            Optional<MiscMounted> armed = armedGlove(battleArmor);
            if (armed.isPresent() && (armed.get().getLinked().getType() != chosen)) {
                ConstructionUtil.removeMounted(battleArmor, armed.get().getLinked());
            }
            if (armedGlove(battleArmor).isEmpty() && (chosen != null)) {
                battleArmor.getMisc().stream()
                      .filter(misc -> misc.getType().hasFlag(MiscTypeFlag.F_ARMORED_GLOVE))
                      .findFirst()
                      .ifPresent(misc -> mountOnApm(battleArmor, chosen, misc));
            }
        }

        private static Optional<MiscMounted> armedGlove(BattleArmor battleArmor) {
            return battleArmor.getMisc().stream()
                  .filter(misc -> misc.getType().hasFlag(MiscTypeFlag.F_ARMORED_GLOVE) && (misc.getLinked() != null))
                  .findFirst();
        }

        private void place(BattleArmor battleArmor, BAManipulator manipulator, int mount) {
            MiscMounted current = battleArmor.getManipulator(mount);
            if ((current != null) && !current.is(manipulator.internalName)) {
                if (current.getLinked() != null) {
                    ConstructionUtil.removeMounted(battleArmor, current.getLinked());
                }
                ConstructionUtil.removeMounted(battleArmor, current);
            }
            if ((battleArmor.getManipulator(mount) == null) && (manipulator != BAManipulator.NONE)) {
                try {
                    battleArmor.addEquipment(EquipmentType.get(manipulator.internalName), BattleArmor.LOC_SQUAD)
                          .setBaMountLoc(mount);
                } catch (LocationFullException e) {
                    LOGGER.debug("No room for {}: {}", manipulator.internalName, e.getMessage());
                }
            }
            if ((manipulator == BAManipulator.CARGO_LIFTER) && (battleArmor.getManipulator(mount) != null)) {
                battleArmor.getManipulator(mount).setSize(cargo / 2.0);
            }
        }
    }

    private static void mountOnApm(Entity entity, WeaponType weapon, Mounted<?> mount) {
        try {
            BaConstructionUtil.mountOnApm(entity.addEquipment(weapon, mount.getLocation()), mount);
        } catch (LocationFullException e) {
            LOGGER.debug("No room for {}: {}", weapon.getName(), e.getMessage());
        }
    }

    // endregion

    // region Munitions

    private void readMunitions(Entity entity, Game game) {
        if (entity.usesWeaponBays() || (entity instanceof Dropship)) {
            readBays(entity, game);
            return;
        }
        if (entity.getWeightClass() == EntityWeightClass.WEIGHT_SMALL_SUPPORT) {
            for (WeaponMounted weapon : entity.getWeaponList()) {
                if ((weapon.getType() instanceof InfantryWeapon infantryWeapon) && infantryWeapon.hasInfernoAmmo()) {
                    smallSv.add(new SmallSvRow(entity, weapon));
                }
            }
            return;
        }
        IGameOptions options = game.getOptions();
        boolean keepsEmpty = options.booleanOption(OptionsConstants.BASE_LOBBY_AMMO_DUMP)
              || options.booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HOT_LOAD);
        for (AmmoMounted mount : entity.getAmmo()) {
            AmmoType type = mount.getType();
            List<AmmoType> all = AmmoType.getMunitionsFor(type.getAmmoType());
            if ((all == null) || ((entity instanceof Aero) && !AERO_SWITCHABLE.contains(type.getAmmoType()))) {
                continue;
            }
            List<AmmoType> munitions = all.stream().filter(check -> offers(entity, game, type, check)).toList();
            if (munitions.isEmpty() && !keepsEmpty) {
                continue;
            }
            ammo.add(new AmmoBin(entity, mount, munitions, options));
        }
    }

    private static final Set<AmmoType.AmmoTypeEnum> AERO_SWITCHABLE = EnumSet.of(AmmoType.AmmoTypeEnum.MML,
          AmmoType.AmmoTypeEnum.SRM, AmmoType.AmmoTypeEnum.LRM, AmmoType.AmmoTypeEnum.MRM, AmmoType.AmmoTypeEnum.ATM,
          AmmoType.AmmoTypeEnum.IATM);

    /** Whether a bin of the given munition may switch to the checked one, as the Swing munitions choice decides. */
    static boolean offers(Entity entity, Game game, AmmoType type, AmmoType check) {
        IGameOptions options = game.getOptions();
        if (entity.hasETypeFlag(Entity.ETYPE_AERO)
              && !check.canAeroUse(options.booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AERO_ARTILLERY_MUNITIONS))) {
            return false;
        }
        boolean allMixed = options.booleanOption(OptionsConstants.ALLOWED_ALL_AMMO_MIXED_TECH);
        SimpleTechLevel legalLevel = SimpleTechLevel.getGameTechLevel(game);
        boolean techMatch;
        if (options.booleanOption(OptionsConstants.ALLOWED_ERA_BASED)) {
            techMatch = check.isLegal(options.intOption(OptionsConstants.ALLOWED_YEAR), legalLevel, entity.isClan(),
                  entity.isMixedTech(), options.booleanOption(OptionsConstants.ALLOWED_SHOW_EXTINCT));
        } else {
            boolean canUseIs = entity.isMixedTech() || !entity.isClan() || allMixed;
            boolean canUseClan = entity.isMixedTech() || entity.isClan() || allMixed;
            boolean clanTech = check.isClan() || check.isMixedTech();
            boolean isTech = !check.isClan() || check.isMixedTech();
            techMatch = (check.getStaticTechLevel().ordinal() <= legalLevel.ordinal())
                  && ((canUseIs && isTech) || (canUseClan && clanTech));
        }
        EnumSet<AmmoType.Munitions> munitions = check.getMunitionType();
        IOption rules = options.getOption(OptionsConstants.RULES_SYSTEM);
        if ((rules != null) && OptionsConstants.RULES_TW.equals(rules.stringValue())) {
            if ((check.getAmmoType() == AmmoType.AmmoTypeEnum.AC_ROTARY)
                  && munitions.contains(AmmoType.Munitions.M_CASELESS)) {
                return false;
            }
            if (EnumSet.of(AmmoType.AmmoTypeEnum.TBOLT_5, AmmoType.AmmoTypeEnum.TBOLT_10,
                  AmmoType.AmmoTypeEnum.TBOLT_15, AmmoType.AmmoTypeEnum.TBOLT_20).contains(check.getAmmoType())
                  && (munitions.contains(AmmoType.Munitions.M_SEMIGUIDED)
                  || munitions.contains(AmmoType.Munitions.M_NARC_CAPABLE)
                  || munitions.contains(AmmoType.Munitions.M_THUNDER))) {
                return false;
            }
        }
        if (!allMixed && entity.isClan() && check.notAllowedByClanRules()) {
            techMatch = false;
        }
        if (munitions.contains(AmmoType.Munitions.M_ARTEMIS_CAPABLE) && !entity.hasWorkingMisc(MiscType.F_ARTEMIS)
              && !entity.hasWorkingMisc(MiscType.F_ARTEMIS_PROTO)) {
            return false;
        }
        if (munitions.contains(AmmoType.Munitions.M_ARTEMIS_V_CAPABLE) && !entity.hasWorkingMisc(MiscType.F_ARTEMIS_V)
              && !entity.hasWorkingMisc(MiscType.F_ARTEMIS_PROTO)) {
            return false;
        }
        if (!Game.rulesManager.getRulesGame().allowMinefields(options.booleanOption(OptionsConstants.ADVANCED_MINEFIELDS))
              && AmmoType.canDeliverMinefield(check)) {
            return false;
        }
        if (check.hasFlag(AmmoType.F_PROTOMEK) && !(entity instanceof ProtoMek)) {
            return false;
        }
        if ((entity instanceof ProtoMek) && check.hasFlag(AmmoType.F_MG) && !check.hasFlag(AmmoType.F_PROTOMEK)) {
            return false;
        }
        if (Set.of(AmmoType.AmmoTypeEnum.LRM, AmmoType.AmmoTypeEnum.SRM).contains(check.getAmmoType())
              && entity.isBattleArmor() && !check.hasFlag(AmmoTypeFlag.F_BATTLEARMOR)) {
            return false;
        }
        if (entity.isProtoMek() && !AmmoType.isAllowedForBattleArmorAndProtoMeks(check)) {
            return false;
        }
        return techMatch && (check.getRackSize() == type.getRackSize())
              && (check.hasFlag(AmmoType.F_BATTLEARMOR) == type.hasFlag(AmmoType.F_BATTLEARMOR))
              && (check.hasFlag(AmmoType.F_ENCUMBERING) == type.hasFlag(AmmoType.F_ENCUMBERING))
              && (check.getTonnage(entity) == type.getTonnage(entity));
    }

    private void readFeeds(Entity entity) {
        if (entity.usesWeaponBays() || (entity instanceof Dropship)
              || (entity.getWeightClass() == EntityWeightClass.WEIGHT_SMALL_SUPPORT)) {
            return;
        }
        for (WeaponMounted weapon : entity.getWeaponList()) {
            if (weapon.getType().getAmmoType() != AmmoType.AmmoTypeEnum.NA) {
                WeaponFeed feed = new WeaponFeed(entity, weapon);
                if (!feed.bins.isEmpty()) {
                    feeds.add(feed);
                }
            }
        }
    }

    /** The ammo bins whose munitions may be chosen. */
    public List<AmmoBin> ammo() {
        return List.copyOf(ammo);
    }

    /** The ammo-fed weapons and the bins that may feed them. */
    public List<WeaponFeed> feeds() {
        return List.copyOf(feeds);
    }

    /** An ammo bin's munition, shots, half load and hot loading. */
    public final class AmmoBin {
        private final int number;
        private final String location;
        private final String group;
        private final List<AmmoType> munitions;
        private final boolean oneShot;
        private final int constructionShots;
        private final int step;
        private final boolean originallyHalf;
        private final boolean hotLoadable;
        private int index;
        private int shots;
        private boolean half;
        private boolean hotLoad;
        private boolean shotsChanged;

        AmmoBin(Entity entity, AmmoMounted mount, List<AmmoType> offered, IGameOptions options) {
            number = entity.getEquipmentNum(mount);
            AmmoType type = mount.getType();
            List<AmmoType> list = new ArrayList<>(offered);
            if (!list.contains(type)) {
                list.add(0, type);
            }
            munitions = List.copyOf(list);
            index = munitions.indexOf(type);
            int location = mount.getLocation();
            Mounted<?> root = mount;
            boolean isOneShot = false;
            if (location == Entity.LOC_NONE) {
                while (root.getLinkedBy() != null) {
                    root = root.getLinkedBy();
                }
                location = root.getLocation();
                isOneShot = root.isOneShot();
            }
            oneShot = isOneShot;
            this.location = entity.getLocationName(location);
            group = (root != mount) ? root.getShortName() : weaponsFor(entity, type);
            boolean perConstruction = (entity instanceof BattleArmor) || (entity instanceof ProtoMek);
            constructionShots = perConstruction ? mount.getOriginalShots() : -1;
            step = (perConstruction && (type.getAmmoType() == AmmoType.AmmoTypeEnum.BA_TUBE)) ? 2 : 1;
            originallyHalf = mount.isHalfLoadAmmo();
            half = originallyHalf;
            hotLoadable = type.hasFlag(AmmoType.F_HOTLOAD)
                  && options.booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HOT_LOAD);
            hotLoad = mount.isHotLoaded();
            shots = Math.max(0, Math.min(maxShots(), mount.getBaseShotsLeft()));
        }

        private static String weaponsFor(Entity entity, AmmoType type) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (WeaponMounted weapon : entity.getWeaponList()) {
                if (AmmoType.canSwitchToAmmo(weapon, type)) {
                    counts.merge(weapon.getShortName(), 1, Integer::sum);
                }
            }
            List<String> names = new ArrayList<>();
            counts.forEach((name, count) -> names.add((count > 1) ? name + " \u00d7" + count : name));
            return String.join(", ", names);
        }

        /** The equipment number of the bin. */
        public int number() {
            return number;
        }

        /** Where the bin sits. */
        public String location() {
            return location;
        }

        /** The weapons the bin may feed, as a heading for the bins that share them. */
        public String group() {
            return group;
        }

        /** The munitions offered, by name. */
        public List<String> munitions() {
            return munitions.stream().map(type -> type.getName().replace(" Ammo", "")).toList();
        }

        /** The chosen munition's index. */
        public int munition() {
            return index;
        }

        /** The chosen munition. */
        public AmmoType selected() {
            return munitions.get(index);
        }

        /** Whether the munition may be changed: it needs a choice. */
        public boolean munitionEnabled() {
            return editable && (munitions.size() > 1);
        }

        /** Chooses a munition; the shots fill the bin unless set by hand, and a half load is cleared. */
        public void munition(int value) {
            if ((value < 0) || (value >= munitions.size()) || (value == index)) {
                return;
            }
            index = value;
            int max = maxShots();
            shots = (shotsChanged && (shots <= max)) ? shots : max;
            if (half) {
                half = false;
                if (originallyHalf) {
                    fill(selected().getShots());
                }
            }
        }

        /** Whether the bin holds a one-shot weapon's ammo, whose shots and load are fixed. */
        public boolean oneShot() {
            return oneShot;
        }

        /** The shots loaded. */
        public int shots() {
            return shots;
        }

        /** The most shots the bin holds: a ton of the munition, or what battle armor and ProtoMeks were built with. */
        public int maxShots() {
            return (constructionShots >= 0) ? constructionShots : selected().getShots();
        }

        /** The shots step: battle armor tube artillery comes in pairs. */
        public int step() {
            return step;
        }

        /** Sets the shots loaded, which clears a half load. */
        public void shots(int value) {
            shots = Math.max(0, Math.min(maxShots(), value));
            shotsChanged = true;
            half = false;
        }

        /** Whether the bin carries a half ton. */
        public boolean half() {
            return half;
        }

        /** Sets a half or full load, which loads half or all of a ton's shots. */
        public void half(boolean value) {
            half = value;
            AmmoType type = selected();
            if (value && !originallyHalf) {
                fill(halfShots(type));
            } else if (!value && originallyHalf) {
                fill(type.getShots());
            }
        }

        private void fill(int value) {
            if ((value >= 0) && (value <= maxShots())) {
                shots = value;
            }
        }

        private static int halfShots(AmmoType type) {
            if (type.getMutatorName() != null) {
                double baseKgPerShot = type.getBaseAmmo().getKgPerShot();
                double kgPerShot = type.getKgPerShot();
                if (baseKgPerShot != kgPerShot) {
                    int mutated = (int) Math.floor(type.getBaseAmmo().getShots() / 2.0);
                    return (int) Math.floor(mutated / (kgPerShot / baseKgPerShot));
                }
            }
            return type.getShots() / 2;
        }

        /** Whether the munition may be hot-loaded in this game. */
        public boolean hotLoadable() {
            return hotLoadable;
        }

        /** Whether the bin is hot-loaded. */
        public boolean hotLoad() {
            return hotLoad;
        }

        /** Sets hot loading. */
        public void hotLoad(boolean value) {
            hotLoad = value;
        }

        private void apply(List<Mounted<?>> equipment) {
            if (!(equipment.get(number) instanceof AmmoMounted mount)) {
                return;
            }
            mount.changeAmmoType(selected());
            if (mount.getLocation() != Entity.LOC_NONE) {
                mount.setShotsLeft(shots);
            }
            if (half != mount.isHalfLoadAmmo()) {
                mount.setHalfLoadAmmo(half);
            }
            if (hotLoadable && (hotLoad != mount.isHotLoaded())) {
                mount.setHotLoad(hotLoad);
                for (int mode = 0; mode < mount.getModesCount(); mode++) {
                    if (mount.getType().getMode(mode).getName().equals("HotLoad")) {
                        mount.setMode(mode);
                    }
                }
            }
        }
    }

    /** An ammo-fed weapon and the bin feeding it. */
    public final class WeaponFeed {
        private final int number;
        private final String label;
        private final List<Integer> bins = new ArrayList<>();
        private final List<String> prefixes = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private int index;
        /** The bin feeding the weapon when read, -1 for none; OK keeps the feed unless another bin is chosen. */
        private final int initial;

        WeaponFeed(Entity entity, WeaponMounted weapon) {
            number = entity.getEquipmentNum(weapon);
            label = "(%s) %s".formatted(entity.getLocationAbbr(weapon.getLocation()), weapon.getShortName());
            List<AmmoMounted> matching = new ArrayList<>();
            if (weapon.isOneShot() || (entity.isSupportVehicle() && (weapon.getType() instanceof InfantryWeapon))) {
                if (weapon.getLinked() instanceof AmmoMounted first) {
                    matching.add(first);
                    if ((first.getLinked() instanceof AmmoMounted second)
                          && !first.getType().getMunitionType().equals(second.getType().getMunitionType())) {
                        matching.add(second);
                    }
                }
            } else if (weapon.hasQuirk(OptionsConstants.QUIRK_WEAPON_NEG_STATIC_FEED)
                  && (weapon.getLinkedAmmo() != null)) {
                matching.add(weapon.getLinkedAmmo());
            } else {
                entity.getAmmo().stream()
                      .filter(bin -> bin.getLocation() != Entity.LOC_NONE)
                      .filter(bin -> AmmoType.canSwitchToAmmo(weapon, bin.getType()))
                      .forEach(matching::add);
            }
            for (AmmoMounted bin : matching) {
                bins.add(entity.getEquipmentNum(bin));
                boolean internal = bin.isOneShotAmmo() || bin.isOneShot() || (bin.getLocation() == -1);
                prefixes.add(internal ? "(Internal) " : "(" + entity.getLocationAbbr(bin.getLocation()) + ") ");
                names.add(bin.getName());
            }
            initial = matching.indexOf(weapon.getLinked());
            index = Math.max(0, initial);
        }

        /** The weapon. */
        public String label() {
            return label;
        }

        /** The bins, named after the munitions chosen for them. */
        public List<String> bins() {
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < bins.size(); i++) {
                int bin = bins.get(i);
                String name = ammo.stream().filter(row -> row.number == bin).findFirst()
                      .map(row -> row.selected().getName()).orElse(names.get(i));
                labels.add(prefixes.get(i) + name);
            }
            return labels;
        }

        /** The chosen bin's index. */
        public int index() {
            return index;
        }

        /** Whether the bin may be changed: it needs a choice. */
        public boolean enabled() {
            return editable && (bins.size() > 1);
        }

        /** Chooses the feeding bin. */
        public void index(int value) {
            if ((value >= 0) && (value < bins.size())) {
                index = value;
            }
        }

        private void apply(Entity entity, List<Mounted<?>> equipment) {
            if ((index != initial) && (equipment.get(number) instanceof WeaponMounted weapon)
                  && (equipment.get(bins.get(index)) instanceof AmmoMounted bin)) {
                entity.loadWeapon(weapon, bin);
            }
        }
    }

    // endregion

    // region Bays, small support vehicles, infantry SRMs and bombs

    private void readBays(Entity entity, Game game) {
        for (WeaponMounted bay : entity.getWeaponBayList()) {
            Map<String, List<AmmoMounted>> byType = new LinkedHashMap<>();
            for (AmmoMounted mount : bay.getBayAmmo()) {
                byType.computeIfAbsent(mount.getType().getAmmoType() + "/" + mount.getType().getRackSize(),
                      key -> new ArrayList<>()).add(mount);
            }
            byType.values().forEach(mounts -> bays.add(new BayRow(entity, game, bay, mounts)));
        }
    }

    /** The bay unit's munitions, by bay and ammo kind. */
    public List<BayRow> bays() {
        return List.copyOf(bays);
    }

    /** The small support vehicle weapons' standard and inferno shots. */
    public List<SmallSvRow> smallSv() {
        return List.copyOf(smallSv);
    }

    /** The infantry SRM launcher's munition, when the platoon has one. */
    public Optional<InfantrySrm> infantrySrm() {
        return Optional.ofNullable(infantrySrm);
    }

    /** The bomb bays, internal first, that may carry bombs. */
    public List<BombBay> bombs() {
        return List.copyOf(bombs);
    }

    /** A bay's munitions of one kind, sharing the bay's ammo tonnage. */
    public final class BayRow {
        private final int bay;
        private final String label;
        private final List<Integer> mounts = new ArrayList<>();
        private final List<AmmoType> munitions;
        private final List<String> labels = new ArrayList<>();
        private final double[] tons;
        private final int[] shots;
        private final double tonnage;

        BayRow(Entity entity, Game game, WeaponMounted bay, List<AmmoMounted> ammoMounts) {
            this.bay = entity.getEquipmentNum(bay);
            ammoMounts.forEach(mount -> mounts.add(entity.getEquipmentNum(mount)));
            Optional<WeaponType> weaponType = bay.getBayWeapons().stream().map(Mounted::getType).findAny();
            TechBase techBase = weaponType.map(EquipmentType::getTechBase).orElse(TechBase.ALL);
            label = "(%s) %s".formatted(entity.getLocationAbbr(bay.getLocation()),
                  weaponType.map(EquipmentType::getName).orElse("?"));
            AmmoType first = ammoMounts.get(0).getType();
            IGameOptions options = game.getOptions();
            boolean artillery = options.booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AERO_ARTILLERY_MUNITIONS);
            munitions = AmmoType.getMunitionsFor(first.getAmmoType()).stream()
                  .filter(type -> includes(entity, game, type, first, techBase))
                  .toList();
            tonnage = ammoMounts.stream().mapToDouble(Mounted::getSize).sum();
            Map<String, Integer> starting = new HashMap<>();
            ammoMounts.forEach(mount -> starting.merge(mount.getType().getInternalName(), mount.getBaseShotsLeft(),
                  Integer::sum));
            tons = new double[munitions.size()];
            shots = new int[munitions.size()];
            for (int i = 0; i < munitions.size(); i++) {
                AmmoType type = munitions.get(i);
                tons[i] = type.getTonnage(entity);
                shots[i] = starting.getOrDefault(type.getInternalName(), 0);
                labels.add(munitionLabel(type, artillery));
            }
        }

        private static boolean includes(Entity entity, Game game, AmmoType type, AmmoType first, TechBase techBase) {
            IGameOptions options = game.getOptions();
            if (!type.canAeroUse(options.booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AERO_ARTILLERY_MUNITIONS))
                  || (type.getAmmoType() != first.getAmmoType()) || (type.getRackSize() != first.getRackSize())
                  || ((type.getTechBase() != techBase) && (type.getTechBase() != TechBase.ALL)
                  && (techBase != TechBase.ALL))
                  || !type.isLegal(options.intOption(OptionsConstants.ALLOWED_YEAR),
                  SimpleTechLevel.getGameTechLevel(game), techBase == TechBase.CLAN, techBase == TechBase.ALL,
                  options.booleanOption(OptionsConstants.ALLOWED_SHOW_EXTINCT))) {
                return false;
            }
            if (type.hasFlag(AmmoType.F_NUCLEAR)
                  && !options.booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AT2_NUKES)) {
                return false;
            }
            if (type.getMunitionType().contains(AmmoType.Munitions.M_ARTEMIS_CAPABLE)) {
                return entity.hasWorkingMisc(MiscType.F_ARTEMIS) || entity.hasWorkingMisc(MiscType.F_ARTEMIS_PROTO);
            }
            if (type.getMunitionType().contains(AmmoType.Munitions.M_ARTEMIS_V_CAPABLE)) {
                return entity.hasWorkingMisc(MiscType.F_ARTEMIS_V);
            }
            return !type.hasFlag(AmmoType.F_BATTLEARMOR) && !type.hasFlag(AmmoType.F_PROTOMEK);
        }

        private static String munitionLabel(AmmoType type, boolean artillery) {
            EnumSet<AmmoType.Munitions> kinds = type.getMunitionType();
            boolean artemis = kinds.contains(AmmoType.Munitions.M_ARTEMIS_CAPABLE)
                  || kinds.contains(AmmoType.Munitions.M_ARTEMIS_V_CAPABLE);
            if (type.getAmmoType() == AmmoType.AmmoTypeEnum.MML) {
                boolean lrm = type.hasFlag(AmmoType.F_MML_LRM);
                return Messages.getString(artemis ? (lrm ? "CustomMekDialog.LRMArtemis" : "CustomMekDialog.SRMArtemis")
                      : (lrm ? "CustomMekDialog.LRM" : "CustomMekDialog.SRM"));
            }
            if (type.hasFlag(AmmoType.F_CAP_MISSILE)) {
                String tele = type.hasFlag(AmmoType.F_TELE_MISSILE) ? "-T" : "";
                if (type.hasFlag(AmmoType.F_PEACEMAKER)) {
                    return Messages.getString("CustomMekDialog.Peacemaker") + tele;
                } else if (type.hasFlag(AmmoType.F_SANTA_ANNA)) {
                    return Messages.getString("CustomMekDialog.SantaAnna") + tele;
                } else if (type.hasFlag(AmmoType.F_AR10_KILLER_WHALE)) {
                    return Messages.getString("CustomMekDialog.KillerWhale") + tele;
                } else if (type.hasFlag(AmmoType.F_AR10_WHITE_SHARK)) {
                    return Messages.getString("CustomMekDialog.WhiteShark") + tele;
                } else if (type.hasFlag(AmmoType.F_AR10_BARRACUDA)) {
                    return Messages.getString("CustomMekDialog.Barracuda") + tele;
                }
            }
            if (artemis) {
                return Messages.getString("CustomMekDialog.Artemis");
            }
            if (kinds.contains(AmmoType.Munitions.M_HIGH_EXPLOSIVE)
                  || kinds.contains(AmmoType.Munitions.M_EXTENDED_RANGE)) {
                return type.getDesc();
            }
            if (artillery && EnumSet.of(AmmoType.AmmoTypeEnum.ARROW_IV, AmmoType.AmmoTypeEnum.LONG_TOM,
                  AmmoType.AmmoTypeEnum.SNIPER, AmmoType.AmmoTypeEnum.THUMPER,
                  AmmoType.AmmoTypeEnum.CRUISE_MISSILE).contains(type.getAmmoType())
                  && !kinds.contains(AmmoType.Munitions.M_STANDARD)) {
                return type.getShortName();
            }
            return Messages.getString("CustomMekDialog.StandardMunition");
        }

        /** The bay. */
        public String label() {
            return label;
        }

        /** The munitions offered, by label. */
        public List<String> munitions() {
            return List.copyOf(labels);
        }

        /** The munition's weight per shot or shots per ton. */
        public String tooltip(int munition) {
            AmmoType type = munitions.get(munition);
            return (tons[munition] > 1)
                  ? Messages.getString("CustomMekDialog.formatMissileTonnage", type.getName(), tons[munition])
                  : Messages.getString("CustomMekDialog.formatShotsPerTon", type.getName(), type.getShots());
        }

        /** The shots of a munition. */
        public int shots(int munition) {
            return shots[munition];
        }

        private double weight(int munition) {
            return Math.ceil(tons[munition] * (shots[munition] / (double) munitions.get(munition).getShots()));
        }

        private double remaining() {
            double remaining = tonnage;
            for (int i = 0; i < shots.length; i++) {
                remaining -= weight(i);
            }
            return remaining;
        }

        /** The most shots of a munition the bay's remaining tonnage holds. */
        public int maxShots(int munition) {
            return (int) Math.floor((weight(munition) + remaining()) / tons[munition]
                  * munitions.get(munition).getShots());
        }

        /** Whether a munition may be loaded. */
        public boolean enabled(int munition) {
            return editable && (maxShots(munition) > 0);
        }

        /** Sets the shots of a munition, within what the bay holds. */
        public void shots(int munition, int value) {
            shots[munition] = Math.max(0, Math.min(Math.max(shots[munition], maxShots(munition)), value));
        }

        /** The tonnage used and the bay's ammo tonnage. */
        public String tonnageText() {
            return Messages.getString("CustomMekDialog.formatAmmoTonnage", tonnage - remaining(), tonnage);
        }

        /** The share of the bay's ammo tonnage used. */
        public float used() {
            return (tonnage <= 0) ? 0 : (float) Math.min(1, (tonnage - remaining()) / tonnage);
        }

        private void apply(Entity entity, List<Mounted<?>> equipment) {
            if (!(equipment.get(bay) instanceof WeaponMounted weaponBay)) {
                return;
            }
            List<AmmoMounted> ammoMounts = new ArrayList<>();
            mounts.forEach(number -> ammoMounts.add((AmmoMounted) equipment.get(number)));
            int mountIndex = 0;
            double remainingWeight = tonnage;
            for (int i = 0; i < munitions.size(); i++) {
                if (shots[i] <= 0) {
                    continue;
                }
                AmmoType type = munitions.get(i);
                AmmoMounted mounted;
                if (mountIndex >= ammoMounts.size()) {
                    mounted = (AmmoMounted) Mounted.createMounted(entity, type);
                    try {
                        entity.addEquipment(mounted, weaponBay.getLocation(), weaponBay.isRearMounted());
                        weaponBay.addAmmoToBay(entity.getEquipmentNum(mounted));
                    } catch (LocationFullException e) {
                        LOGGER.error(e, "apply");
                    }
                } else {
                    mounted = ammoMounts.get(mountIndex);
                    mounted.changeAmmoType(type);
                }
                mounted.setShotsLeft(shots[i]);
                int slots = (int) Math.ceil((double) shots[i] / type.getShots());
                mounted.setOriginalShots(slots * type.getShots());
                mounted.setSize(slots * type.getTonnage(entity));
                remainingWeight -= mounted.getSize();
                mountIndex++;
            }
            for (; mountIndex < ammoMounts.size(); mountIndex++) {
                AmmoMounted mount = ammoMounts.get(mountIndex);
                mount.setSize(0);
                mount.setOriginalShots(0);
                mount.setShotsLeft(0);
            }
            if (remainingWeight > 0) {
                AmmoMounted first = ammoMounts.get(0);
                first.setSize(first.getSize() + remainingWeight);
                first.setOriginalShots((int) Math.floor(first.getSize()
                      / (first.getType().getShots() * first.getTonnage())));
            }
        }
    }

    /** A small support vehicle weapon's standard and inferno shots, which share its clips. */
    public final class SmallSvRow {
        private final int weapon;
        private final String label;
        private final int perClip;
        private final int maxShots;
        private int standard;
        private int inferno;

        SmallSvRow(Entity entity, WeaponMounted mount) {
            weapon = entity.getEquipmentNum(mount);
            label = "(%s) %s".formatted(entity.getLocationAbbr(mount.getLocation()), mount.getName());
            perClip = ((InfantryWeapon) mount.getType()).getShots();
            maxShots = (int) mount.getSize() * perClip;
            Mounted<?> standardAmmo = mount.getLinked();
            Mounted<?> infernoAmmo = (standardAmmo == null) ? null : standardAmmo.getLinked();
            standard = (standardAmmo == null) ? maxShots : standardAmmo.getBaseShotsLeft();
            inferno = (infernoAmmo == null) ? 0 : infernoAmmo.getBaseShotsLeft();
        }

        /** The weapon. */
        public String label() {
            return label;
        }

        /** The standard shots. */
        public int standard() {
            return standard;
        }

        /** The inferno shots. */
        public int inferno() {
            return inferno;
        }

        private int clips(int value) {
            return (int) Math.ceil(value / (double) perClip);
        }

        private int remainingClips() {
            return (maxShots / perClip) - clips(standard) - clips(inferno);
        }

        /** The most standard shots the remaining clips allow. */
        public int maxStandard() {
            return (clips(standard) + remainingClips()) * perClip;
        }

        /** The most inferno shots. */
        public int maxInferno() {
            return (clips(inferno) + remainingClips()) * perClip;
        }

        /** Sets the standard shots. */
        public void standard(int value) {
            standard = Math.max(0, Math.min(maxStandard(), value));
        }

        /** Sets the inferno shots. */
        public void inferno(int value) {
            inferno = Math.max(0, Math.min(maxInferno(), value));
        }

        /** The shots as text. */
        public String text() {
            return Messages.getString("CustomMekDialog.formatSmSVAmmoShots", standard + inferno, maxShots);
        }

        private void apply(List<Mounted<?>> equipment) {
            Mounted<?> mount = equipment.get(weapon);
            if (mount.getLinked() == null) {
                mount.setLinked(ammoMount(mount, EquipmentTypeLookup.INFANTRY_AMMO, maxShots));
            }
            Mounted<?> standardAmmo = mount.getLinked();
            if (standardAmmo.getLinked() == null) {
                standardAmmo.setLinked(ammoMount(mount, EquipmentTypeLookup.INFANTRY_INFERNO_AMMO, 0));
            }
            Mounted<?> infernoAmmo = standardAmmo.getLinked();
            standardAmmo.setShotsLeft(standard);
            infernoAmmo.setShotsLeft(inferno);
            int infernoClips = clips(infernoAmmo.getBaseShotsLeft());
            infernoAmmo.setOriginalShots(infernoClips * perClip);
            standardAmmo.setOriginalShots(((int) mount.getSize() - infernoClips) * perClip);
        }

        private static Mounted<?> ammoMount(Mounted<?> weapon, String type, int shots) {
            Mounted<?> mount = Mounted.createMounted(weapon.getEntity(), EquipmentType.get(type));
            mount.setShotsLeft(shots);
            mount.setOriginalShots(shots);
            try {
                weapon.getEntity().addEquipment(mount, Entity.LOC_NONE, false);
            } catch (LocationFullException e) {
                LOGGER.debug("No room for {}: {}", type, e.getMessage());
            }
            return mount;
        }
    }

    /** A conventional infantry platoon's SRM munition: standard or inferno. */
    public final class InfantrySrm {
        private final String label;
        private boolean inferno;

        InfantrySrm(ConvInfantry infantry) {
            InfantryWeapon secondary = infantry.getSecondaryWeapon();
            label = ((secondary != null) && secondary.hasFlag(WeaponType.F_SRM)) ? secondary.getName()
                  : infantry.getPrimaryWeapon().getName();
            inferno = infantry.isInfernoSrmsDeclared();
        }

        /** The launcher. */
        public String label() {
            return label;
        }

        /** Standard and inferno, by name. */
        public List<String> choices() {
            return List.of(Messages.getString("CustomMekDialog.srmMunitionStandard"),
                  Messages.getString("CustomMekDialog.srmMunitionInferno"));
        }

        /** Whether infernos are loaded. */
        public boolean inferno() {
            return inferno;
        }

        /** Loads infernos or standard missiles. */
        public void inferno(boolean value) {
            inferno = value;
        }
    }

    private void readBombs(IBomber bomber, IGameOptions options) {
        int level = Arrays.binarySearch(TechConstants.T_SIMPLE_NAMES,
              options.stringOption(OptionsConstants.ALLOWED_TECH_LEVEL));
        boolean nukes = options.booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AT2_NUKES);
        boolean advanced = level >= TechConstants.T_SIMPLE_ADVANCED;
        if (bomber.getMaxIntBombPoints() > 0) {
            bombs.add(new BombBay(true, bomber.getMaxIntBombPoints(), bomber.getMaxIntBombSize(),
                  bomber.getIntBombChoices(), nukes, advanced));
        }
        if (bomber.getMaxExtBombPoints() > 0) {
            bombs.add(new BombBay(false, bomber.getMaxExtBombPoints(), bomber.getMaxExtBombSize(),
                  bomber.getExtBombChoices(), nukes, advanced));
        }
    }

    /** An internal or external bomb bay's loadout within its bomb points. */
    public final class BombBay {
        private final boolean internal;
        private final int maxPoints;
        private final int maxSize;
        private final boolean nukes;
        private final boolean advanced;
        private final Map<BombTypeEnum, Integer> counts = new EnumMap<>(BombTypeEnum.class);

        BombBay(boolean internal, int maxPoints, int maxSize, BombLoadout loadout, boolean nukes, boolean advanced) {
            this.internal = internal;
            this.maxPoints = maxPoints;
            this.maxSize = maxSize;
            this.nukes = nukes;
            this.advanced = advanced;
            for (BombTypeEnum type : types()) {
                int count = loadout.getCount(type);
                if (count * type.getCost() > maxSize) {
                    count = maxSize / type.getCost();
                }
                counts.put(type, count);
            }
        }

        /** Whether this is the internal bay. */
        public boolean internal() {
            return internal;
        }

        /** The bomb types, in their order. */
        public List<BombTypeEnum> types() {
            return Arrays.stream(BombTypeEnum.values()).filter(type -> type != BombTypeEnum.NONE).toList();
        }

        /** The bombs of a type. */
        public int count(BombTypeEnum type) {
            return counts.getOrDefault(type, 0);
        }

        /** The bomb points used. */
        public int used() {
            return counts.entrySet().stream().mapToInt(entry -> entry.getKey().getCost() * entry.getValue()).sum();
        }

        /** The bay's bomb points. */
        public int maxPoints() {
            return maxPoints;
        }

        /** The most bombs of a type the remaining points allow. */
        public int max(BombTypeEnum type) {
            int count = count(type);
            int max = (type.getCost() > maxSize) ? 0 : ((maxPoints - used()) / type.getCost()) + count;
            return Math.min(maxSize, Math.max(0, Math.max(count, max)));
        }

        /** Whether a type may be loaded: nuclear and advanced bombs need their rules. */
        public boolean enabled(BombTypeEnum type) {
            return editable && (max(type) > 0) && ((type != BombTypeEnum.ALAMO) || nukes)
                  && (!type.isAdvancedAmmo() || advanced);
        }

        /** Sets the bombs of a type, within the points left. */
        public void count(BombTypeEnum type, int value) {
            counts.put(type, Math.max(0, Math.min(max(type), value)));
        }

        private BombLoadout loadout() {
            BombLoadout loadout = new BombLoadout();
            counts.forEach((type, count) -> {
                if (count > 0) {
                    loadout.put(type, count);
                }
            });
            return loadout;
        }
    }

    // endregion

    // region Rapid fire, VRT, mines, infantry armor, sensors and modes

    /** The machine guns that may fire bursts. */
    public List<RapidFire> rapidFire() {
        return List.copyOf(rapidFire);
    }

    /** The variable range targeting mode, when the unit has it. */
    public Optional<Vrt> vrt() {
        return Optional.ofNullable(vrt);
    }

    /** The mines and vehicle mine dispensers' mine types. */
    public List<Mine> mines() {
        return List.copyOf(mines);
    }

    /** The conventional infantry armor, specializations and disposable weapon. */
    public Optional<InfantryArmor> infantryArmor() {
        return Optional.ofNullable(infantryArmor);
    }

    /** The starting sensor, when the unit has a choice. */
    public Optional<SensorChoice> sensor() {
        return Optional.ofNullable(sensor);
    }

    /** The electronics whose starting modes may be chosen. */
    public List<ModeRow> modes() {
        return List.copyOf(modes);
    }

    /** A machine gun's burst fire. */
    public final class RapidFire {
        private final int number;
        private final String label;
        private boolean on;

        RapidFire(Entity entity, WeaponMounted weapon) {
            number = entity.getEquipmentNum(weapon);
            label = "(%s) %s".formatted(entity.getLocationAbbr(weapon.getLocation()), weapon.getName());
            on = weapon.isRapidFire();
        }

        /** The machine gun. */
        public String label() {
            return label;
        }

        /** Whether it fires bursts. */
        public boolean on() {
            return on;
        }

        /** Sets burst fire. */
        public void on(boolean value) {
            on = value;
        }
    }

    /** The variable range targeting mode: long or short. */
    public final class Vrt {
        private boolean longRange;

        Vrt(boolean longRange) {
            this.longRange = longRange;
        }

        /** Whether long range is favoured. */
        public boolean longRange() {
            return longRange;
        }

        /** Favours long or short range. */
        public void longRange(boolean value) {
            longRange = value;
        }
    }

    /** A mine or mine dispenser's mine type. */
    public final class Mine {
        private final int number;
        private final String label;
        private int index;

        Mine(Entity entity, MiscMounted mount) {
            number = entity.getEquipmentNum(mount);
            label = "(%s) %s".formatted(entity.getLocationAbbr(mount.getLocation()), mount.getName());
            index = Math.max(0, MINES.indexOf(mount.getMineType()));
        }

        /** The mine. */
        public String label() {
            return label;
        }

        /** The mine types, by name. */
        public List<String> choices() {
            return MINE_KEYS.stream().map(Messages::getString).toList();
        }

        /** The mine type's index. */
        public int index() {
            return index;
        }

        /** Chooses the mine type. */
        public void index(int value) {
            if ((value >= 0) && (value < MINES.size())) {
                index = value;
            }
        }
    }

    /** A conventional infantry platoon's armor kit or custom armor, specializations and disposable weapon. */
    public final class InfantryArmor {
        private final List<EquipmentType> kits = new ArrayList<>();
        private final List<InfantryWeapon> disposables = new ArrayList<>();
        private final boolean detail;
        private final boolean disposable;
        private int kit;
        private String divisor;
        private boolean encumbering;
        private boolean spaceSuit;
        private boolean dest;
        private boolean sneakCamo;
        private boolean sneakIr;
        private boolean sneakEcm;
        private int specializations;
        private int weapon;

        InfantryArmor(ConvInfantry infantry) {
            Game game = (Game) infantry.getGame();
            IGameOptions options = game.getOptions();
            SimpleTechLevel level = SimpleTechLevel.getGameTechLevel(game);
            detail = (level != SimpleTechLevel.STANDARD) && (level != SimpleTechLevel.INTRO);
            int year = options.intOption(OptionsConstants.ALLOWED_YEAR);
            boolean extinct = options.booleanOption(OptionsConstants.ALLOWED_SHOW_EXTINCT);
            for (EquipmentType type : MiscType.allTypes()) {
                if ((type instanceof MiscType misc) && misc.hasFlag(MiscType.F_ARMOR_KIT)
                      && type.isLegal(year, level, infantry.isClan(), infantry.isMixedTech(), extinct)) {
                    kits.add(type);
                }
            }
            kits.sort(Comparator.comparing(EquipmentType::getName));
            kit = (infantry.getArmorKit() == null) ? 0 : kits.indexOf(infantry.getArmorKit()) + 1;
            divisor = Double.toString(infantry.calcDamageDivisor());
            encumbering = infantry.isArmorEncumbering();
            spaceSuit = infantry.hasSpaceSuit();
            dest = infantry.hasDEST();
            sneakCamo = infantry.hasSneakCamo();
            sneakIr = infantry.hasSneakIR();
            sneakEcm = infantry.hasSneakECM();
            for (int i = 0; i < ConvInfantry.NUM_SPECIALIZATIONS; i++) {
                if (infantry.hasSpecialization(1 << i)) {
                    specializations |= 1 << i;
                }
            }
            disposable = options.booleanOption(OptionsConstants.ADVANCED_COMBAT_DISPOSABLE_INFANTRY_WEAPONS);
            if (disposable) {
                for (EquipmentType type : EquipmentType.allTypes()) {
                    if ((type instanceof InfantryWeapon infantryWeapon)
                          && infantryWeapon.hasFlag(WeaponType.F_INF_DISPOSABLE)
                          && type.isLegal(year, level, infantry.isClan(), infantry.isMixedTech(), extinct)) {
                        disposables.add(infantryWeapon);
                    }
                }
                disposables.sort(Comparator.comparing(EquipmentType::getName));
                InfantryWeapon current = infantry.getDisposableWeapon();
                weapon = (current == null) ? 0 : disposables.indexOf(current) + 1;
            }
        }

        /** Whether the armor itself is offered: only above the standard rules level. */
        public boolean showsArmor() {
            return detail;
        }

        /** Custom, then the armor kits. */
        public List<String> kits() {
            List<String> names = new ArrayList<>();
            names.add(Messages.getString("CustomMekDialog.Custom"));
            kits.forEach(type -> names.add(type.getName()));
            return names;
        }

        /** The kit's index, 0 for custom armor. */
        public int kit() {
            return kit;
        }

        /** Chooses a kit, which fixes the armor's values, or custom armor. */
        public void kit(int value) {
            if ((value < 0) || (value > kits.size())) {
                return;
            }
            kit = value;
            if (kit > 0) {
                EquipmentType type = kits.get(kit - 1);
                divisor = Double.toString(((MiscType) type).getDamageDivisor());
                encumbering = type.hasFlag(MiscTypeFlag.S_ENCUMBERING);
                spaceSuit = type.hasFlag(MiscTypeFlag.S_SPACE_SUIT);
                dest = type.hasFlag(MiscTypeFlag.S_DEST);
                sneakCamo = type.hasFlag(MiscTypeFlag.S_SNEAK_CAMO);
                sneakIr = type.hasFlag(MiscTypeFlag.S_SNEAK_IR);
                sneakEcm = type.hasFlag(MiscTypeFlag.S_SNEAK_ECM);
            }
        }

        /** Whether the custom armor's values may be changed. */
        public boolean custom() {
            return editable && (kit == 0);
        }

        /** Whether the sneak suits may be changed: DEST includes them. */
        public boolean sneakEnabled() {
            return custom() && !dest;
        }

        /** The damage divisor as typed. */
        public String divisor() {
            return divisor;
        }

        /** Types the damage divisor. */
        public void divisor(String value) {
            divisor = (value == null) ? "" : value;
        }

        /** The divisor's error, or empty when it is a number of at least zero. */
        public String divisorError() {
            try {
                return (Double.parseDouble(divisor.trim()) >= 0) ? ""
                      : Messages.getString("Lobby.bay.invalidDivisor");
            } catch (NumberFormatException e) {
                return Messages.getString("Lobby.bay.invalidDivisor");
            }
        }

        public boolean encumbering() {
            return encumbering;
        }

        public void encumbering(boolean value) {
            encumbering = value;
        }

        public boolean spaceSuit() {
            return spaceSuit;
        }

        public void spaceSuit(boolean value) {
            spaceSuit = value;
        }

        public boolean dest() {
            return dest;
        }

        public void dest(boolean value) {
            dest = value;
        }

        public boolean sneakCamo() {
            return sneakCamo;
        }

        public void sneakCamo(boolean value) {
            sneakCamo = value;
        }

        public boolean sneakIr() {
            return sneakIr;
        }

        public void sneakIr(boolean value) {
            sneakIr = value;
        }

        public boolean sneakEcm() {
            return sneakEcm;
        }

        public void sneakEcm(boolean value) {
            sneakEcm = value;
        }

        /** Whether the platoon has the specialization of the given index. */
        public boolean specialization(int index) {
            return (specializations & (1 << index)) != 0;
        }

        /** Sets a specialization. */
        public void specialization(int index, boolean value) {
            specializations = value ? (specializations | (1 << index)) : (specializations & ~(1 << index));
        }

        /** Whether a disposable weapon may be chosen: it needs its rule. */
        public boolean showsDisposable() {
            return disposable;
        }

        /** None, then the disposable weapons. */
        public List<String> disposables() {
            List<String> names = new ArrayList<>();
            names.add(Messages.getString("CustomMekDialog.infDisposableNone"));
            disposables.forEach(type -> names.add(type.getName()));
            return names;
        }

        /** The disposable weapon's index, 0 for none. */
        public int disposable() {
            return weapon;
        }

        /** Chooses the disposable weapon. */
        public void disposable(int value) {
            if ((value >= 0) && (value <= disposables.size())) {
                weapon = value;
            }
        }

        private void apply(ConvInfantry infantry) {
            if (kit > 0) {
                infantry.setArmorKit(kits.get(kit - 1));
            } else {
                infantry.setArmorKit(null);
                infantry.setCustomArmorDamageDivisor(divisorError().isEmpty() ? Double.parseDouble(divisor.trim())
                      : 0.0);
                infantry.setArmorEncumbering(encumbering);
                infantry.setSpaceSuit(spaceSuit);
                infantry.setDEST(dest);
                if (!dest) {
                    infantry.setSneakCamo(sneakCamo);
                    infantry.setSneakIR(sneakIr);
                    infantry.setSneakECM(sneakEcm);
                }
            }
            infantry.setSpecializations(specializations);
            if (disposable) {
                infantry.equipDisposableWeapon((weapon > 0) ? disposables.get(weapon - 1) : null);
            }
        }
    }

    /** The sensor the unit starts on and whether every unit of the design should. */
    public final class SensorChoice {
        private final List<String> sensors = new ArrayList<>();
        private final boolean remembers;
        private int index;
        private boolean remember;

        SensorChoice(Entity entity) {
            List<Sensor> list = entity.getSensors();
            for (int i = 0; i < list.size(); i++) {
                Sensor candidate = list.get(i);
                String condition = (candidate.isBAP() && !entity.hasBAP(false))
                      ? Messages.getString("CustomMekDialog.sensorDisabled") : "";
                sensors.add(candidate.getDisplayName() + condition);
                if ((entity.getNextSensor() != null) && (candidate.type() == entity.getNextSensor().type())) {
                    index = i;
                }
            }
            remembers = !entity.getChassis().isBlank() && !entity.getModel().isBlank();
            remember = remembers && (SensorChoiceHandler.getSensorChoice(entity.getChassis(), entity.getModel())
                  != null);
        }

        /** The sensors, by name. */
        public List<String> sensors() {
            return List.copyOf(sensors);
        }

        /** The starting sensor's index. */
        public int index() {
            return index;
        }

        /** Chooses the starting sensor. */
        public void index(int value) {
            if ((value >= 0) && (value < sensors.size())) {
                index = value;
            }
        }

        /** Whether the choice may be remembered for the design: it needs a chassis and model. */
        public boolean remembers() {
            return remembers;
        }

        /** Whether the choice is remembered. */
        public boolean remember() {
            return remember;
        }

        /** Remembers or forgets the choice for the design. */
        public void remember(boolean value) {
            remember = remembers && value;
        }

        private void apply(Entity entity) {
            if (index >= entity.getSensors().size()) {
                return;
            }
            Sensor chosen = entity.getSensors().get(index);
            Sensor current = entity.getNextSensor();
            if ((current == null) || (current.type() != chosen.type())) {
                entity.setNextSensor(chosen);
                entity.setCustomSensorChoice(true);
            }
            if (remembers) {
                SensorChoiceHandler.setSensorChoice(entity.getChassis(), entity.getModel(),
                      remember ? SensorFamily.familyOf(chosen) : null);
            }
        }
    }

    private void readModes(Entity entity, IGameOptions options) {
        boolean eccm = options.booleanOption(OptionsConstants.ADVANCED_TAC_OPS_ECCM);
        boolean ghosts = options.booleanOption(OptionsConstants.ADVANCED_TAC_OPS_GHOST_TARGET);
        for (MiscMounted equipment : entity.getMisc()) {
            MiscType type = equipment.getType();
            if (equipment.isInoperable() || (type == null)) {
                continue;
            }
            List<String> choices = new ArrayList<>();
            if (type.hasFlag(MiscType.F_ECM) && !type.hasFlag(MiscType.F_NOVA)) {
                boolean angel = type.hasFlag(MiscType.F_ANGEL_ECM);
                choices.add(MiscType.MODE_ECM);
                if (eccm) {
                    choices.add("ECCM");
                    if (angel) {
                        choices.add("ECM & ECCM");
                    }
                }
                if (ghosts) {
                    if (angel) {
                        choices.add("ECM & Ghost Targets");
                        if (eccm) {
                            choices.add("ECCM & Ghost Targets");
                        }
                    } else {
                        choices.add("Ghost Targets");
                    }
                }
                if (!stealthOn) {
                    choices.add(Mounted.MODE_OFF);
                }
            } else if (ghosts && type.hasFlag(MiscType.F_COMMUNICATIONS) && (entity.getTotalCommGearTons() >= 7)) {
                choices.add("Default");
                if (eccm) {
                    choices.add("ECCM");
                }
                choices.add("Ghost Targets");
            } else if (ghosts && type.hasFlag(MiscType.F_COMMAND_CONSOLE)) {
                choices.add("Default");
                choices.add("Ghost Targets");
            } else if ((type.hasFlag(MiscType.F_BAP) || type.hasFlag(MiscTypeFlag.ANY_C3)
                  || type.hasFlag(MiscType.F_ARTEMIS) || type.hasFlag(MiscType.F_ARTEMIS_V)
                  || type.hasFlag(MiscType.F_ARTEMIS_PROTO) || type.hasFlag(MiscType.F_APOLLO))
                  && (type.getModesCount() > 1)) {
                for (int mode = 0; mode < type.getModesCount(); mode++) {
                    choices.add(type.getMode(mode).getName());
                }
            }
            if (choices.size() > 1) {
                modes.add(new ModeRow(entity.getEquipmentNum(equipment), equipment.getName(), choices,
                      equipment.curMode().getName(),
                      (type.hasFlag(MiscType.F_ECM) && (equipment instanceof MiscMounted suite)) ? suite : null));
            }
        }
    }

    /** Whether more than one ECM suite would start in use, which the game does not allow. */
    public boolean ecmConflict() {
        return !stealthOn && (modes.stream().filter(row -> row.ecm && row.inUse()).count() > 1);
    }

    /**
     * The ECM suites in use while more than one is, for the player to choose the one left on, as Swing's
     * {@code EcmSuiteChoiceDialog} asks; empty without a conflict.
     */
    public List<ModeRow> ecmInUse() {
        return ecmConflict() ? modes.stream().filter(row -> row.ecm && row.inUse()).toList() : List.of();
    }

    /** The suite OK leaves on when the player has not chosen, as Swing's dialog does when cancelled at OK. */
    public Optional<ModeRow> ecmDefault() {
        List<ModeRow> inUse = ecmInUse();
        MiscMounted kept = EquipmentActivation.preferredEcmSuite(inUse.stream().map(row -> row.suite).toList());
        return inUse.stream().filter(row -> row.suite == kept).findFirst();
    }

    /** Leaves {@code kept} on and switches the other ECM suites off. */
    public void keepEcm(ModeRow kept) {
        if (editable && kept.ecm && !stealthOn) {
            modes.stream().filter(row -> row.ecm && (row != kept)).forEach(ModeRow::off);
        }
    }

    /** An electronic device's starting mode. */
    public final class ModeRow {
        private final int number;
        private final String label;
        private final List<String> choices;
        private final boolean ecm;
        private final @Nullable MiscMounted suite;
        private int index;

        ModeRow(int number, String label, List<String> choices, String current, @Nullable MiscMounted suite) {
            this.number = number;
            this.label = label;
            this.choices = List.copyOf(choices);
            this.suite = suite;
            ecm = suite != null;
            index = Math.max(0, choices.indexOf(current));
        }

        /** The device. */
        public String label() {
            return label;
        }

        /** The modes, by name. */
        public List<String> choices() {
            return choices;
        }

        /** The chosen mode's index. */
        public int index() {
            return index;
        }

        /** Chooses a mode; a second ECM suite switched on leaves the player to choose one ({@link #ecmInUse}). */
        public void index(int value) {
            if ((value >= 0) && (value < choices.size())) {
                index = value;
            }
        }

        /** The mode's name. */
        public String mode() {
            return choices.get(index);
        }

        private boolean inUse() {
            return !Mounted.MODE_OFF.equals(choices.get(index));
        }

        private void off() {
            int off = choices.indexOf(Mounted.MODE_OFF);
            if (off >= 0) {
                index = off;
            }
        }

        private void apply(List<Mounted<?>> equipment) {
            Mounted<?> mount = equipment.get(number);
            for (int mode = 0; mode < mount.getType().getModesCount(); mode++) {
                if (mount.getType().getMode(mode).getName().equals(choices.get(index))) {
                    mount.setMode(mode);
                    break;
                }
            }
        }
    }

    // endregion

    // region Validity and apply

    /** Whether every typed value is valid. */
    public boolean valid() {
        return (infantryArmor == null) || !infantryArmor.custom() || infantryArmor.divisorError().isEmpty();
    }

    /**
     * Writes the choices into the unit as it is now, on the Swing thread, in the Swing dialog's order. Returns the
     * warnings to show, such as a C3 network that would grow too big.
     */
    public List<String> apply(Entity entity, Client client) {
        List<String> warnings = new ArrayList<>();
        if (!editable) {
            return warnings;
        }
        List<Mounted<?>> equipment = List.copyOf(entity.getEquipment());
        applyEject(entity);
        if (entity instanceof BattleArmor battleArmor) {
            for (ApMount mount : apMounts) {
                Mounted<?> apMount = equipment.get(mount.number);
                WeaponType chosen = mount.chosen();
                if ((apMount.getLinked() != null) && (apMount.getLinked().getType() != chosen)) {
                    ConstructionUtil.removeMounted(entity, apMount.getLinked());
                }
                if ((chosen != null) && (apMount.getLinked() == null)) {
                    mountOnApm(entity, chosen, apMount);
                }
            }
            if (manipulators != null) {
                manipulators.apply(battleArmor);
            }
        }
        ammo.forEach(bin -> bin.apply(equipment));
        smallSv.forEach(row -> row.apply(equipment));
        if ((infantrySrm != null) && (entity instanceof ConvInfantry infantry)) {
            infantry.setInfernoSrmsDeclared(infantrySrm.inferno);
        }
        if (!bays.isEmpty()) {
            bays.forEach(row -> row.apply(entity, equipment));
        } else {
            feeds.forEach(feed -> feed.apply(entity, equipment));
        }
        rapidFire.forEach(row -> equipment.get(row.number).setRapidFire(row.on));
        if (vrt != null) {
            entity.setVariableRangeTargetingMode(vrt.longRange ? VariableRangeTargetingMode.LONG
                  : VariableRangeTargetingMode.SHORT);
        }
        for (Mine mine : mines) {
            if (equipment.get(mine.number) instanceof MiscMounted misc) {
                misc.setMineType(MINES.get(mine.index));
            }
        }
        if (!bombs.isEmpty() && (entity instanceof IBomber bomber)) {
            BombLoadout empty = new BombLoadout();
            bomber.setIntBombChoices(bombs.stream().filter(BombBay::internal).findFirst().map(BombBay::loadout)
                  .orElse(empty));
            bomber.setExtBombChoices(bombs.stream().filter(bay -> !bay.internal()).findFirst()
                  .map(BombBay::loadout).orElse(new BombLoadout()));
        }
        if ((infantryArmor != null) && (entity instanceof ConvInfantry infantry)) {
            infantryArmor.apply(infantry);
        }
        applySearchlight(entity);
        applyFitting(entity, Toggle.DNI, "DNICockpitModification", MiscType.F_DNI_COCKPIT_MOD,
              entity.hasDNICockpitMod(), false);
        applyFitting(entity, Toggle.EI, "EIInterface", MiscType.F_EI_INTERFACE, entity.hasEiCockpit(), false);
        if (entity instanceof Mek mek) {
            applyFitting(entity, Toggle.DIC, "DamageInterruptCircuit", MiscType.F_DAMAGE_INTERRUPT_CIRCUIT,
                  mek.hasDamageInterruptCircuit(), true);
        }
        applyModes(equipment);
        if (sensor != null) {
            sensor.apply(entity);
        }
        applyC3(entity, client, warnings);
        return warnings;
    }

    private void applyEject(Entity entity) {
        if (!shows(Toggle.AUTO_EJECT)) {
            return;
        }
        boolean autoEject = !value(Toggle.AUTO_EJECT);
        if (entity instanceof Mek mek) {
            mek.setAutoEject(autoEject);
            if (shows(Toggle.EJECT_AMMO)) {
                mek.setCondEjectAmmo(value(Toggle.EJECT_AMMO));
                mek.setCondEjectEngine(value(Toggle.EJECT_ENGINE));
                mek.setCondEjectCTDest(value(Toggle.EJECT_CT));
                mek.setCondEjectHeadshot(value(Toggle.EJECT_HEAD));
            }
        } else if (entity instanceof Aero aero) {
            aero.setAutoEject(autoEject);
            if (shows(Toggle.EJECT_AMMO)) {
                aero.setCondEjectAmmo(value(Toggle.EJECT_AMMO));
                aero.setCondEjectFuel(value(Toggle.EJECT_FUEL));
                aero.setCondEjectSIDest(value(Toggle.EJECT_SI));
            }
        }
    }

    private void applySearchlight(Entity entity) {
        if (!shows(Toggle.SEARCHLIGHT_ON)) {
            return;
        }
        if (!entity.getsAutoExternalSearchlight()) {
            entity.setExternalSearchlight(value(Toggle.SEARCHLIGHT));
        }
        entity.setSearchlightOverride(searchlightsDefault != value(Toggle.SEARCHLIGHT_ON));
    }

    private void applyFitting(Entity entity, Toggle toggle, String name, MiscTypeFlag flag, boolean has,
          boolean byInternalName) {
        if (!shows(toggle)) {
            return;
        }
        boolean wants = value(toggle);
        if (wants && !has) {
            EquipmentType type = EquipmentType.get(name);
            if (type != null) {
                try {
                    entity.addEquipment(type, Entity.LOC_NONE);
                } catch (LocationFullException e) {
                    LOGGER.debug("Failed to add {} to {}: {}", name, entity.getDisplayName(), e.getMessage());
                }
            }
        } else if (!wants && has) {
            entity.getMisc().stream().filter(mount -> mount.getType().hasFlag(flag)).findFirst()
                  .ifPresent(mount -> entity.removeMisc(byInternalName ? mount.getType().getInternalName()
                        : mount.getName()));
        }
    }

    private void applyModes(List<Mounted<?>> equipment) {
        ecmDefault().ifPresent(this::keepEcm);
        modes.forEach(row -> row.apply(equipment));
    }

    private void applyC3(Entity entity, Client client, List<String> warnings) {
        if (!c3Network || (c3Index < 0) || (c3Index >= c3.size()) || (c3Index == c3Initial)) {
            return;
        }
        int id = c3.get(c3Index).id();
        if (entity.hasC3()) {
            Entity chosen = client.getEntity(id);
            int entityNodes = client.getGame().getC3SubNetworkMembers(entity).size();
            int chosenNodes = client.getGame().getC3NetworkMembers(chosen).size();
            boolean changes = (chosen == null) || (entity.getC3MasterId() != chosen.getId());
            if (((entityNodes + chosenNodes) <= Entity.MAX_C3_NODES) && changes) {
                entity.setC3Master(chosen, true);
            } else if ((chosen != null) && changes) {
                warnings.add(Messages.getString("CustomMekDialog.NetworkTooBig.message", entity.getShortName(),
                      chosen.getShortName(), entityNodes, chosenNodes, Entity.MAX_C3_NODES));
            }
        } else if (entity.hasC3i() || entity.hasNavalC3()) {
            entity.setC3NetId(client.getEntity(id));
        }
    }

    // endregion
}
