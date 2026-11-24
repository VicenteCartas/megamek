/*
 * Copyright (c) 2026 - The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.common.units;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Nullable;
import megamek.common.Hex;
import megamek.common.equipment.EquipmentActivation;
import megamek.common.equipment.EquipmentMode;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;

/**
 * How a unit starts the game, chosen as it deploys, whether it is placed or walks on: its stance, whether it is shut
 * down, how many heat sinks are active, and the mode of each piece of equipment it can switch off (activated
 * equipment). A unit enters with these as its controller wants them (Core Rulebook p. 224; p. 180 for activated
 * equipment). The lobby sets the defaults; deployment may change them, and the server applies them when the unit
 * deploys.
 *
 * @param stance      how the unit stands
 * @param shutdown    whether it starts shut down
 * @param activeSinks the heat sinks active, or -1 for a unit that doesn't switch them
 * @param modes       the mode index of each activated equipment, by equipment number
 */
public record DeploymentSetup(Stance stance, boolean shutdown, int activeSinks, Map<Integer, Integer> modes)
      implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** How a unit may start: standing (or normal), prone, hull down or dug in. */
    public enum Stance { STANDING, PRONE, HULL_DOWN, DUG_IN }

    /** Why a setup's activated equipment can't go together. */
    public enum Conflict {
        /** A unit may use only one ECM suite at a time (TM p.213). */
        TWO_ECM,
        /** Stealth armor needs an ECM suite that is on. */
        STEALTH_WITHOUT_ECM
    }

    /**
     * What a unit may choose as it deploys. {@code stances} is empty for a unit without a choice of stance, and
     * {@code sinks} is 0 for one that doesn't switch heat sinks.
     *
     * @param stances   the stances it may start in, standing first
     * @param shutdown  whether it may start shut down
     * @param sinks     the heat sinks it switches
     * @param equipment the equipment numbers of its activated equipment
     */
    public record Options(List<Stance> stances, boolean shutdown, int sinks, List<Integer> equipment) {
        public Options {
            stances = List.copyOf(stances);
            equipment = List.copyOf(equipment);
        }

        /** Whether there is nothing to choose. */
        public boolean isEmpty() {
            return stances.isEmpty() && !shutdown && (sinks == 0) && equipment.isEmpty();
        }
    }

    public DeploymentSetup {
        modes = Map.copyOf(modes);
    }

    /**
     * What {@code entity} may choose as it deploys into {@code hex} (null while it isn't placed), with the gating the
     * lobby uses.
     */
    public static Options options(Game game, Entity entity, @Nullable Hex hex) {
        List<Stance> stances = new ArrayList<>();
        boolean fortified = (hex != null) && hex.containsTerrain(Terrains.FORTIFIED);
        if (entity instanceof Mek) {
            boolean grounded = !(entity instanceof LandAirMek lam) || (lam.getConversionMode()
                  == LandAirMek.CONV_MODE_MEK);
            boolean walking = !(entity instanceof QuadVee quadVee) || (quadVee.getConversionMode()
                  == QuadVee.CONV_MODE_MEK);
            if (grounded && walking) {
                stances.addAll(List.of(Stance.STANDING, Stance.PRONE, Stance.HULL_DOWN));
            }
        } else if ((entity instanceof Tank tank) && tank.isHullDownCapable() && fortified
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN)) {
            stances.addAll(List.of(Stance.STANDING, Stance.HULL_DOWN));
        } else if ((entity instanceof Infantry infantry) && !infantry.isMechanized() && !fortified
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_TAC_OPS_DIG_IN)) {
            stances.addAll(List.of(Stance.STANDING, Stance.DUG_IN));
        }
        boolean shutdown = game.getOptions().booleanOption(OptionsConstants.RPG_BEGIN_SHUTDOWN)
              && !(entity instanceof Infantry) && !entity.isBuildingEntityOrGunEmplacement();
        int sinks = entity instanceof ActiveHeatSinkController controller ? controller.getSwitchableSinks() : 0;
        List<Integer> equipment = new ArrayList<>();
        for (Mounted<?> mounted : entity.getEquipment()) {
            if (isActivated(entity, mounted)) {
                equipment.add(entity.getEquipmentNum(mounted));
            }
        }
        return new Options(stances, shutdown, sinks, equipment);
    }

    /**
     * Whether the unit lists {@code mounted} among its activated equipment: equipment with an "Off" mode that works
     * and may be switched, but not heat sinks (set by their count) or a booby trap (armed only once).
     */
    private static boolean isActivated(Entity entity, Mounted<?> mounted) {
        if ((mounted.getType() == null) || !mounted.hasModes() || mounted.isInoperable()
              || !mounted.isModeSwitchable()
              || !(entity.getMisc().contains(mounted) || entity.getWeaponList().contains(mounted))) {
            return false;
        }
        if ((mounted.getType() instanceof MiscType miscType) && (miscType.hasFlag(MiscType.F_HEAT_SINK)
              || miscType.hasFlag(MiscType.F_DOUBLE_HEAT_SINK)
              || miscType.hasFlag(MiscType.F_IS_DOUBLE_HEAT_SINK_PROTOTYPE) || miscType.isBoobyTrap())) {
            return false;
        }
        return modeIndex(mounted, Mounted.MODE_OFF) >= 0;
    }

    /** The setup {@code entity} has now, for the choices {@link #options} gives it. */
    public static DeploymentSetup of(Game game, Entity entity, @Nullable Hex hex) {
        Options options = options(game, entity, hex);
        Stance stance = Stance.STANDING;
        if ((entity instanceof Infantry infantry) && (infantry.getDugIn() != Infantry.DUG_IN_NONE)) {
            stance = Stance.DUG_IN;
        } else if (entity.isProne()) {
            stance = Stance.PRONE;
        } else if (entity.isHullDown()) {
            stance = Stance.HULL_DOWN;
        }
        int sinks = entity instanceof ActiveHeatSinkController controller ? controller.getActiveSinksNextRound() : -1;
        Map<Integer, Integer> modes = new LinkedHashMap<>();
        for (int equipmentNum : options.equipment()) {
            Mounted<?> mounted = entity.getEquipment(equipmentNum);
            modes.put(equipmentNum, modeIndex(mounted, mounted.modeNextRound().getName()));
        }
        return new DeploymentSetup(stance, entity.isManualShutdown(), sinks, modes);
    }

    /** The index of the mount's mode named {@code name}, or -1. */
    public static int modeIndex(Mounted<?> mounted, String name) {
        for (int index = 0; index < mounted.getModesCount(); index++) {
            if (mounted.getMode(index).getName().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /** This setup in {@code stance}. */
    public DeploymentSetup withStance(Stance stance) {
        return new DeploymentSetup(stance, shutdown, activeSinks, modes);
    }

    /** This setup shut down or running. */
    public DeploymentSetup withShutdown(boolean shutdown) {
        return new DeploymentSetup(stance, shutdown, activeSinks, modes);
    }

    /** This setup with {@code activeSinks} heat sinks active. */
    public DeploymentSetup withActiveSinks(int activeSinks) {
        return new DeploymentSetup(stance, shutdown, activeSinks, modes);
    }

    /** This setup with the equipment {@code equipmentNum} in mode {@code mode}. */
    public DeploymentSetup withMode(int equipmentNum, int mode) {
        Map<Integer, Integer> changed = new LinkedHashMap<>(modes);
        changed.put(equipmentNum, mode);
        return new DeploymentSetup(stance, shutdown, activeSinks, changed);
    }

    /**
     * Whether {@code entity} may start with this setup in {@code hex}: each choice is one {@link #options} gives. A
     * stance the unit has no choice of is not refused but left alone, as the server clears a hull-down outside
     * fortifications.
     */
    public boolean isAllowed(Game game, Entity entity, @Nullable Hex hex) {
        Options options = options(game, entity, hex);
        if (!options.stances().isEmpty() && !options.stances().contains(stance)) {
            return false;
        }
        if (options.sinks() > 0 && (activeSinks < 0 || activeSinks > options.sinks())) {
            return false;
        }
        for (Map.Entry<Integer, Integer> entry : modes.entrySet()) {
            Mounted<?> mounted = entity.getEquipment(entry.getKey());
            if (!options.equipment().contains(entry.getKey()) || (mounted == null) || (entry.getValue() < 0)
                  || (entry.getValue() >= mounted.getModesCount())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts {@code entity} into this setup at once, as far as {@link #options} lets it choose; choices it has none of
     * are left alone.
     *
     * @return what puts it back as it was
     */
    public Runnable apply(Game game, Entity entity, @Nullable Hex hex) {
        DeploymentSetup previous = of(game, entity, hex);
        Options options = options(game, entity, hex);
        applyChoices(entity, options);
        return () -> previous.applyChoices(entity, options);
    }

    /**
     * Puts {@code entity} back into this setup, every choice in it included, even one its hex no longer offers: how a
     * unit the player picked and then left undeployed returns to how it was.
     */
    public void restore(Game game, Entity entity) {
        Options options = options(game, entity, null);
        applyChoices(entity, new Options(List.of(Stance.values()), options.shutdown(), options.sinks(),
              List.copyOf(modes.keySet())));
    }

    /** How many choices of this setup differ from {@code other}'s. */
    public int changesFrom(DeploymentSetup other) {
        int changes = (stance != other.stance ? 1 : 0) + (shutdown != other.shutdown ? 1 : 0)
              + (activeSinks != other.activeSinks ? 1 : 0);
        for (Map.Entry<Integer, Integer> entry : modes.entrySet()) {
            if (!entry.getValue().equals(other.modes.get(entry.getKey()))) {
                changes++;
            }
        }
        return changes;
    }

    private void applyChoices(Entity entity, Options options) {
        if (!options.stances().isEmpty()) {
            if (entity instanceof Infantry infantry) {
                infantry.setDugIn(stance == Stance.DUG_IN ? Infantry.DUG_IN_COMPLETE : Infantry.DUG_IN_NONE);
            } else {
                entity.setProne(stance == Stance.PRONE);
                entity.setHullDown(stance == Stance.HULL_DOWN);
            }
        }
        if (options.shutdown()) {
            if (shutdown) {
                entity.performManualShutdown();
            } else if (entity.isManualShutdown()) {
                entity.performManualStartup();
                entity.setStartupThisPhase(false);
            }
        }
        if ((options.sinks() > 0) && (activeSinks >= 0) && (entity instanceof ActiveHeatSinkController controller)) {
            controller.setActiveSinks(activeSinks);
        }
        for (Map.Entry<Integer, Integer> entry : modes.entrySet()) {
            Mounted<?> mounted = entity.getEquipment(entry.getKey());
            if (options.equipment().contains(entry.getKey()) && (mounted != null)
                  && (entry.getValue() >= 0) && (entry.getValue() < mounted.getModesCount())) {
                EquipmentMode mode = mounted.getMode(entry.getValue());
                mounted.setModeImmediately(mode.getName());
            }
        }
    }

    /** Why {@code entity}'s activated equipment can't be as it is now, or null when it can. */
    public static @Nullable Conflict conflict(Entity entity) {
        if (EquipmentActivation.ecmSuitesInUseNextRound(entity).size() > 1) {
            return Conflict.TWO_ECM;
        }
        if (EquipmentActivation.isStealthOnOrActivating(entity) && !EquipmentActivation.hasEcmAvailableForStealth(
              entity)) {
            return Conflict.STEALTH_WITHOUT_ECM;
        }
        return null;
    }
}
