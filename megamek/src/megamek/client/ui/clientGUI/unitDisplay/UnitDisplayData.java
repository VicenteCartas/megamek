/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.unitDisplay;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.common.Player;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.Coords;
import megamek.common.compute.ComputeECM;
import megamek.common.compute.VirtualRealityPilotingPod;
import megamek.common.compute.VirtualRealityPilotingPod.Interference;
import megamek.common.compute.VirtualRealityPilotingPod.InterferenceState;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.ICarryable;
import megamek.common.equipment.INarcPod;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.Sensor;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.options.OptionsConstants;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;

/** Unit status, crew actions and weapon-row presentation shared by unit views. */
public final class UnitDisplayData {
    public static final String CHANGE_SINKS = "changeSinks";
    private UnitDisplayData() { }

    public static final List<GamePhase> HIDDEN_ACTIVATION_PHASES = List.of(GamePhase.UNKNOWN, GamePhase.MOVEMENT,
          GamePhase.FIRING, GamePhase.PHYSICAL);

    /**
     * The Extras tab's "Affected by" list for the unit: attached (i)Narc pods, a burning inferno or fire, interference,
     * an enemy ECM field, active stealth and other effects, jammed weapons and breached locations; empty when nothing
     * affects it. The Unit Display and the GPU record sheet both use it.
     */
    public static List<String> affectedBy(Game game, Entity en) {
        List<String> affected = new ArrayList<>();
        // Walk through the list of teams. There
        // can't be more teams than players.
        StringBuilder buff;
        for (Player player : game.getPlayersList()) {
            int team = player.getTeam();
            // The messages end before the player's name ("NARCed by Team of"), so a space separates them
            if (en.isNarcedBy(team) && !player.isObserver()) {
                buff = new StringBuilder(Messages.getString("MekDisplay.NARCedBy"));
                buff.append(' ').append(player.getName())
                      .append(" [").append(player.getTeamName()).append(']');
                affected.add(buff.toString());
            }

            if (en.isINarcedBy(team) && !player.isObserver()) {
                buff = new StringBuilder(Messages.getString("MekDisplay.INarcHoming"));
                buff.append(' ').append(player.getName()).append(" [")
                      .append(player.getTeamName()).append("] ")
                      .append(Messages.getString("MekDisplay.attached"))
                      .append('.');
                affected.add(buff.toString());
            }
        }

        if (en.isINarcedWith(INarcPod.ECM)) {
            affected.add(Messages.getString("MekDisplay.iNarcECMPodAttached"));
        }

        if (en.isINarcedWith(INarcPod.HAYWIRE)) {
            affected.add(Messages.getString("MekDisplay.iNarcHaywirePodAttached"));
        }

        if (en.isINarcedWith(INarcPod.NEMESIS)) {
            affected.add(Messages.getString("MekDisplay.iNarcNemesisPodAttached"));
        }

        // Show inferno track.
        if (en.infernos.isStillBurning()) {
            affected.add(Messages.getString("MekDisplay.InfernoBurnRemaining") + en.infernos.getTurnsLeftToBurn());
        }

        if ((en instanceof Tank) && ((Tank) en).isOnFire()) {
            affected.add(Messages.getString("MekDisplay.OnFire"));
        }

        // Show electromagnetic interference.
        if (en.isSufferingEMI()) {
            affected.add(Messages.getString("MekDisplay.IsEMId"));
        }

        // Show ECM affect.
        Coords pos = en.getPosition();
        if (ComputeECM.isAffectedByAngelECM(en, pos, pos)) {
            affected.add(Messages.getString("MekDisplay.InEnemyAngelECMField"));
        } else if (ComputeECM.isAffectedByECM(en, pos, pos)) {
            affected.add(Messages.getString("MekDisplay.InEnemyECMField"));
        }

        // Virtual Reality Piloting Pod under hostile interference (IO:AE p.63)
        if (en instanceof Mek mek && mek.hasVirtualRealityPilotingPod()) {
            Interference podInterference = VirtualRealityPilotingPod.getInterference(mek);
            if (podInterference.isBlinded()) {
                affected.add(Messages.getString("MekDisplay.VrppBlinded", podInterference.source()));
            } else if (podInterference.state() == InterferenceState.DEGRADED) {
                affected.add(Messages.getString("MekDisplay.VrppDegraded", podInterference.source()));
            }
        }

        // Active Stealth Armor? If yes, we're under ECM
        if (en.isStealthActive()
              && ((en instanceof Mek) || (en instanceof Tank))) {
            affected.add(Messages.getString("MekDisplay.UnderStealth"));
        }

        // burdened due to unjettisoned body-mounted missiles on BA?
        if ((en instanceof BattleArmor) && ((BattleArmor) en).isBurdened()) {
            affected.add(Messages.getString("MekDisplay.Burdened"));
        }

        // suffering from taser feedback?
        if (en.getTaserFeedBackRounds() > 0) {
            affected.add(en.getTaserFeedBackRounds()
                  + " " + Messages.getString("MekDisplay.TaserFeedBack"));
        }

        // taser interference?
        if (en.getTaserInterference() > 0) {
            affected.add("+"
                  + en.getTaserInterference() + " "
                  + Messages.getString("MekDisplay.TaserInterference"));
        }

        // suffering from TSEMP Interference?
        if (en.getTsempEffect() == MMConstants.TSEMP_EFFECT_INTERFERENCE) {
            affected.add(Messages.getString("MekDisplay.TSEMPInterference"));
        }

        // suffering from EMP Mine Interference?
        if (en.getEMPInterferenceRounds() > 0) {
            affected.add(Messages.getString("MekDisplay.EMPInterference",
                  en.getEMPInterferenceRounds()));
        }

        // suffering from EMP Mine Shutdown?
        if (en.getEMPShutdownRounds() > 0) {
            affected.add(Messages.getString("MekDisplay.EMPShutdown",
                  en.getEMPShutdownRounds()));
        }

        if (en.hasDamagedRHS()) {
            affected.add(Messages.getString("MekDisplay.RHSDamaged"));
        }

        // Show Turret Locked.
        if ((en instanceof Tank) && !((Tank) en).hasNoTurret()
              && !en.canChangeSecondaryFacing()) {
            affected.add(Messages.getString("MekDisplay.Turretlocked"));
        }

        // Show jammed weapons.
        for (Mounted<?> weapon : en.getWeaponList()) {
            if (weapon.isJammed()) {
                affected.add(weapon.getName() + Messages.getString("MekDisplay.isJammed"));
            }
        }

        // Show breached locations.
        for (int loc = 0; loc < en.locations(); loc++) {
            if (en.getLocationStatus(loc) == ILocationExposureStatus.BREACHED) {
                affected.add(en.getLocationName(loc) + Messages.getString("MekDisplay.Breached"));
            }
        }
        return affected;
    }

    /**
     * The Extras tab's "Carrying" lines for the unit: its loaded units, clubs, cargo and picked-up MekWarriors, one
     * per line. The Unit Display and the GPU record sheet both use it.
     */
    public static List<String> carried(Game game, Entity en) {
        List<String> carried = new ArrayList<>();
        for (Entity other : en.getLoadedUnits()) {
            carried.add(other.getShortName());
        }

        // Show club(s).
        for (Mounted<?> club : en.getClubs()) {
            carried.add(club.getName());
        }

        // show cargo.
        for (ICarryable cargo : en.getDistinctCarriedObjects()) {
            carried.add(cargo.specificName());
        }

        // We may not be saving captured pilots correctly on game save; some valid pilots don't have
        // entities.
        for (int pickedUpID : en.getPickedUpMekWarriors()) {
            Entity pickedUp = game.getEntity(pickedUpID);
            carried.add((pickedUp == null) ? "(ID " + pickedUpID + ")" : pickedUp.getShortName());
        }
        return carried;
    }

    /** The state of the unit's searchlight as the Extras tab shows it below what the unit carries; "" without one. */
    public static String searchlightText(Entity en) {
        if (!en.hasSearchlight()) {
            return "";
        }
        return Messages.getString(en.isUsingSearchlight() ? "MekDisplay.SearchlightOn" : "MekDisplay.SearchlightOff");
    }

    /** The text of the hidden activation list for a phase. */
    public static String hiddenActivationLabel(GamePhase phase) {
        return phase.isUnknown() ? Messages.getString("MekDisplay.ActivateHidden.StopActivating")
              : phase.localizedName();
    }

    /** The unit's sensors as the sensor list names them, in the unit's sensor order. */
    public static List<String> sensorLabels(Entity en) {
        List<String> labels = new ArrayList<>();
        for (Sensor sensor : en.getSensors()) {
            String condition = "";
            if (sensor.isBAP() && !en.hasBAP(false)) {
                condition = " (Disabled)";
            }
            labels.add(sensor.getDisplayName() + condition);
        }
        return labels;
    }

    /** The index of the sensor the unit uses from the end of the turn (the last one of its type), or -1 for none. */
    public static int nextSensorIndex(Entity en) {
        int next = -1;
        for (int i = 0; i < en.getSensors().size(); i++) {
            if ((en.getNextSensor() != null) && (en.getSensors().elementAt(i).type() == en.getNextSensor().type())) {
                next = i;
            }
        }
        return next;
    }

    /**
     * Switches the unit to one of its sensors at the end of the turn and tells the player and the server; the sensor
     * list's action. The Unit Display and the GPU record sheet both use it.
     */
    public static void setNextSensor(ClientGUI clientgui, Entity entity, int sensorIdx) {
        Sensor sensor = entity.getSensors().elementAt(sensorIdx);
        entity.setNextSensor(sensor);
        // The player picked this themselves, so their sensor preference must not override it later
        entity.setCustomSensorChoice(true);
        String sensorMsg = Messages.getString("MekDisplay.willSwitchAtEnd",
              "Active Sensors",
              sensor.getDisplayName());
        clientgui.systemMessage(sensorMsg);
        clientgui.getClient().sendSensorChange(entity.getId(), sensorIdx);
    }

    /** The panel's text for a number of active heat sinks of the Mek, such as "4 (8) Double Heat Sink(s) active". */
    public static String activeSinksText(Mek mek, int sinks) {
        return mek.hasDoubleHeatSinks() ? Messages.getString("MekDisplay.activeSinksTextDouble", sinks, sinks * 2)
              : Messages.getString("MekDisplay.activeSinksTextSingle", sinks);
    }

    /**
     * Sets how many heat sinks the Mek keeps active from the next round, as the heat sink control does: the client's
     * menu bar first hands the control's action to its listeners, among them the phase display, which in the firing
     * phase clears its unsent attacks (FiringDisplay, PointblankShotDisplay); then the Mek and the server take the
     * number. The Unit Display and the GPU unit record both use it.
     */
    public static void setActiveSinks(ClientGUI clientgui, Mek mek, int activeSinks) {
        clientgui.getMenuBar().actionPerformed(new ActionEvent(mek, ActionEvent.ACTION_PERFORMED, CHANGE_SINKS));
        mek.setActiveSinksNextRound(activeSinks);
        clientgui.getClient().sendSinksChange(mek.getId(), activeSinks);
    }

    /** Whether the unit's crew sits at a command console with both seats active, so the two can swap roles. */
    public static boolean canSwapConsoleRoles(Entity entity) {
        return entity.getCrew().getCrewType().equals(CrewType.COMMAND_CONSOLE)
              && entity.getCrew().isActive(0) && entity.getCrew().isActive(1);
    }

    /**
     * Schedules (or cancels) the command console crew's role swap at the end of the turn and sends the unit; the swap
     * button's action. The Unit Display and the GPU record sheet both use it.
     */
    public static void swapConsoleRoles(Client client, Entity entity, boolean swap) {
        entity.getCrew().setSwapConsoleRoles(swap);
        client.sendUpdateEntity(entity);
    }

    /**
     * The parts of one weapon's row in the weapon list, in their order.
     *
     * @param techTag     "(C) " or "(IS) " on a mixed-tech unit, else ""
     * @param desc        the weapon's description with its state mark ({@link Mounted#getDesc()})
     * @param riscModule  the short name of a linked RISC laser pulse module, else ""
     * @param location    the location, with the second location of a split weapon ("LT/CT")
     * @param loadedShots the shots of the loaded ammunition (0 while it dumps; a double one-shot launcher's usable
     *                    shots of its loaded munition), -1 when the row shows no shots
     * @param totalShots  the usable shots of every bin the weapon can switch to (a double one-shot launcher's original
     *                    shots of its loaded munition), -1 when the row shows no shots
     * @param rapidFire   whether a machine gun fires rapidly
     * @param hotLoaded   whether a launcher is hot-loaded
     * @param mode        the state name of the current mode, null for a weapon without modes
     * @param pendingMode the state name of the mode queued for the end of the turn, null when none is queued
     * @param chargeState a Bombast laser's charge state, null for other weapons
     * @param calledShot  the weapon's called shot, null while the TacOps called shots option is off
     */
    public record RowParts(String techTag, String desc, String riscModule, String location, int loadedShots,
          int totalShots, boolean rapidFire, boolean hotLoaded, @Nullable String mode, @Nullable String pendingMode,
          @Nullable String chargeState, @Nullable String calledShot) {

        /** The row's text in the weapon list. */
        public String text() {
            StringBuilder row = new StringBuilder(techTag).append(desc);
            if (!riscModule.isEmpty()) {
                row.append('+').append(riscModule);
            }
            row.append(" [").append(location).append(']');
            if (totalShots >= 0) {
                row.append(" (").append(loadedShots).append('/').append(totalShots).append(')');
            }
            if (rapidFire) {
                row.append(Messages.getString("MekDisplay.rapidFire"));
            }
            if (hotLoaded) {
                row.append(Messages.getString("MekDisplay.isHotLoaded"));
            }
            // Both describe what the weapon is set to, or will be set to, so they take the state label
            if (mode != null) {
                row.append(' ').append(mode);
                if (pendingMode != null) {
                    row.append(" (next turn, ").append(pendingMode).append(')');
                }
            }
            if (chargeState != null) {
                row.append(' ').append(chargeState);
            }
            if (calledShot != null) {
                row.append(' ').append(calledShot);
            }
            return row.toString();
        }
    }

    /**
     * The parts of one weapon's row in the weapon list; the game is the client's, null without a client. The Unit
     * Display and the GPU unit record both use it.
     */
    public static RowParts rowParts(@Nullable Game game, WeaponMounted mounted) {
        WeaponType weaponType = mounted.getType();
        Entity entityMounted = mounted.getEntity();
        Mounted<?> pulseModule = mounted.getEnhancement(MiscType.F_RISC_LASER_PULSE_MODULE);
        String riscModule = pulseModule == null ? "" : pulseModule.getShortName();
        String location = entityMounted.getLocationAbbr(mounted.getLocation());
        if (mounted.isSplit()) {
            location += "/" + entityMounted.getLocationAbbr(mounted.getSecondLocation());
        }
        String techTag = entityMounted.isMixedTech() ? (weaponType.isClan() ? "(C) " : "(IS) ") : "";
        int loadedShots = -1;
        int totalShots = -1;
        if ((weaponType.getAmmoType() != AmmoType.AmmoTypeEnum.NA)
              && (!weaponType.hasFlag(WeaponType.F_ONE_SHOT) || weaponType.hasFlag(WeaponType.F_BA_INDIVIDUAL))
              && (weaponType.getAmmoType() != AmmoType.AmmoTypeEnum.INFANTRY)) {
            loadedShots = ((mounted.getLinked() != null) && !mounted.getLinked().isDumping())
                  ? mounted.getLinked().getUsableShotsLeft() : 0;
            totalShots = entityMounted.getTotalMunitionsOfType(mounted);
        } else if (weaponType.hasFlag(WeaponType.F_DOUBLE_ONE_SHOT)
              || (entityMounted.isSupportVehicle() && (weaponType.getAmmoType() == AmmoType.AmmoTypeEnum.INFANTRY))) {
            loadedShots = 0;
            totalShots = 0;
            EnumSet<AmmoType.Munitions> munition = ((AmmoType) mounted.getLinked().getType()).getMunitionType();
            for (Mounted<?> current = mounted.getLinked(); current != null; current = current.getLinked()) {
                if (((AmmoType) current.getType()).getMunitionType().equals(munition)) {
                    loadedShots += current.getUsableShotsLeft();
                    totalShots += current.getOriginalShots();
                }
            }
        }
        boolean modes = mounted.hasModes();
        String mode = modes ? mounted.curMode().getStateName(weaponType) : null;
        String pendingMode = (modes && !mounted.pendingMode().equals(Mounted.MODE_NONE))
              ? mounted.pendingMode().getStateName(weaponType) : null;
        String chargeState = weaponType.hasFlag(WeaponType.F_BOMBAST_LASER)
              ? mounted.getChargeState().getDescription() : null;
        String calledShot = ((game != null)
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_CALLED_SHOTS))
              ? mounted.getCalledShot().getDisplayableName() : null;
        return new RowParts(techTag, mounted.getDesc(), riscModule, location, loadedShots, totalShots,
              mounted.isRapidFire(), mounted.isHotLoaded(), mode, pendingMode, chargeState, calledShot);
    }
}
