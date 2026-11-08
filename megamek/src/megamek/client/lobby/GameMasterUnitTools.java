/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.compute.Compute;
import megamek.common.enums.ForcedWithdrawalOrder;
import megamek.common.equipment.Mounted;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Aero;
import megamek.common.units.AutomaticEjectionRules;
import megamek.common.units.Crew;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.TemporarySkillModifiers;

/**
 * The Game Master's in-play tools of a unit's damage card in a running game: the edits go to the server instead of
 * the local unit, and beside them the unit's owner, a bot's Forced Withdrawal order, the crew's temporary skill
 * modifiers, the target modifier, the ejection settings, and the server acts that end the edit: explode a piece of
 * equipment, withdraw the unit or destroy it. Read from the unit on the Swing thread with its {@link UnitDamageForm};
 * every server act is a command a {@link Host} sends, so it is tested without a client.
 */
public final class GameMasterUnitTools {
    static final String DESTROY_UNIT_COMMAND = "/kill %d %b";
    static final String WITHDRAW_UNIT_COMMAND = "/rescue %d";
    static final String CHANGE_OWNER_COMMAND = "/changeOwner %d %d";
    static final String EXPLODE_EQUIPMENT_COMMAND = "/explode %d %d";
    static final String WITHDRAW_ORDER_COMMAND = "/withdrawOrder %d %s";
    /** How long a new modifier lasts. */
    public static final int DEFAULT_MODIFIER_ROUNDS = 3;
    /** The longest a modifier can be set to last. */
    public static final int MAX_MODIFIER_ROUNDS = 100;
    /** How far the initiative modifier goes either way. */
    public static final int MAX_INITIATIVE_DELTA = 8;

    /** Where the tools' server acts go; called on the Swing thread. */
    public interface Host {
        /** Sends the edits to the server, which applies them to its own copy of the unit. */
        void sendDamageEdit(DamageEditSpec spec);

        /** Sends a Game Master chat command. */
        void sendChat(String command);
    }

    /**
     * A temporary modifier: a number from {@code min} to {@code max}, and for the timed ones how many rounds it lasts
     * or whether it lasts until cleared.
     */
    public static final class Modifier {
        private final String label;
        private final String tip;
        private final int min;
        private final int max;
        private final boolean timed;
        private final String note;
        private int value;
        private int rounds;
        private boolean permanent;

        private Modifier(String label, String tip, int value, int min, int max, int rounds, String note) {
            this.label = label;
            this.tip = tip;
            this.min = min;
            this.max = Math.max(min, max);
            this.value = Math.clamp(value, this.min, this.max);
            this.timed = rounds != 0;
            this.permanent = rounds == TemporarySkillModifiers.PERMANENT;
            this.rounds = Math.clamp(rounds > 0 ? rounds : DEFAULT_MODIFIER_ROUNDS, 1, MAX_MODIFIER_ROUNDS);
            this.note = note;
        }

        public String label() {
            return label;
        }

        public String tip() {
            return tip;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        public int value() {
            return value;
        }

        /** Sets the modifier, brought within its bounds. */
        public void value(int next) {
            value = Math.clamp(next, min, max);
        }

        /** Whether it lasts a number of rounds or until cleared; the target modifier lasts this round only. */
        public boolean timed() {
            return timed;
        }

        public int rounds() {
            return rounds;
        }

        /** Sets how many rounds it lasts, from 1 to {@link #MAX_MODIFIER_ROUNDS}. */
        public void rounds(int next) {
            rounds = Math.clamp(next, 1, MAX_MODIFIER_ROUNDS);
        }

        public boolean permanent() {
            return permanent;
        }

        public void permanent(boolean next) {
            permanent = next;
        }

        /** A line to show beside the modifier, such as what the unit earned by moving, or null. */
        public String note() {
            return note;
        }
    }

    /** An ejection setting, as the lobby's Configure dialog offers it. */
    public static final class Setting {
        private final String label;
        private final String tip;
        private final boolean original;
        private final BiConsumer<DamageEditSpec, Boolean> writer;
        private boolean on;

        private Setting(String label, String tip, boolean on, BiConsumer<DamageEditSpec, Boolean> writer) {
            this.label = label;
            this.tip = tip;
            this.original = on;
            this.on = on;
            this.writer = writer;
        }

        public String label() {
            return label;
        }

        public String tip() {
            return tip;
        }

        public boolean original() {
            return original;
        }

        public boolean on() {
            return on;
        }

        public void on(boolean next) {
            on = next;
        }
    }

    private final UnitDamageForm form;
    private final Entity entity;
    private final List<Player> owners = new ArrayList<>();
    private final int owner;
    private final List<ForcedWithdrawalOrder> orders = new ArrayList<>();
    private final ForcedWithdrawalOrder originalOrder;
    private ForcedWithdrawalOrder order;
    private final List<Modifier> modifiers = new ArrayList<>();
    private Modifier gunnery;
    private Modifier piloting;
    private Modifier initiative;
    private Modifier target;
    private final List<Setting> ejection = new ArrayList<>();

    /**
     * Reads the tools of the form's unit; call on the thread that owns the unit. A conventional platoon gets only the
     * server acts.
     */
    GameMasterUnitTools(UnitDamageForm form) {
        this.form = form;
        this.entity = form.entity();
        Game game = entity.getGame();
        boolean columns = game != null && !entity.isConventionalInfantry();
        Player current = entity.getOwner();
        if (columns) {
            for (Player player : game.getPlayersList()) {
                if (player.getTeam() != Player.TEAM_UNASSIGNED) {
                    owners.add(player);
                }
            }
            // there has to be a current owner to start from and at least one other player to hand the unit to
            if (current == null || owners.size() < 2 || owners.stream().noneMatch(p -> p.getId() == current.getId())) {
                owners.clear();
            }
        }
        owner = current == null ? -1 : indexOfOwner(current.getId());
        originalOrder = entity.getForcedWithdrawalOrder();
        order = originalOrder;
        // a human player decides for themselves when their units withdraw
        if (columns && current != null && current.isBot()) {
            orders.addAll(List.of(ForcedWithdrawalOrder.values()));
        }
        if (columns) {
            readModifiers(game);
            readEjection(game);
        }
    }

    // ------------------------------------------------------------------------------------------------ owner

    /** The players the unit can be handed to, the current owner among them; empty when it cannot change hands. */
    public List<String> ownerNames() {
        return owners.stream().map(Player::getName).toList();
    }

    /** The current owner's index in {@link #ownerNames()}, or -1. */
    public int owner() {
        return owner;
    }

    /** Asks before handing the unit to the {@code index}th player. */
    public String ownerConfirmation(int index) {
        return Messages.getString("Gamemaster.Traitor.confirmation", entity.getDisplayName(),
              owners.get(index).getName());
    }

    /** The command that hands the unit to the {@code index}th player, or empty for its current owner. */
    public Optional<String> changeOwnerCommand(int index) {
        if (index < 0 || index >= owners.size() || index == owner) {
            return Optional.empty();
        }
        return Optional.of(String.format(CHANGE_OWNER_COMMAND, entity.getId(), owners.get(index).getId()));
    }

    // ------------------------------------------------------------------------------------------------ withdrawal

    /** The Forced Withdrawal orders to choose from, for a bot's unit; empty for anyone else's. */
    public List<String> orderNames() {
        return orders.stream().map(each -> Messages.getString("ForcedWithdrawalOrder." + each.name())).toList();
    }

    /** The chosen order's index in {@link #orderNames()}. */
    public int order() {
        return Math.max(0, orders.indexOf(order));
    }

    public void order(int index) {
        if (index >= 0 && index < orders.size()) {
            order = orders.get(index);
        }
    }

    /** The order command OK sends after the edits, when a bot's unit was given another order. */
    public Optional<String> withdrawOrderCommand() {
        if (orders.isEmpty() || order == originalOrder) {
            return Optional.empty();
        }
        return Optional.of(String.format(WITHDRAW_ORDER_COMMAND, entity.getId(), order.name()));
    }

    // ------------------------------------------------------------------------------------------------ modifiers

    /** The crew's temporary modifiers and the target modifier, for a unit with a crew; else empty. */
    public List<Modifier> modifiers() {
        return Collections.unmodifiableList(modifiers);
    }

    private void readModifiers(Game game) {
        Crew crew = entity.getCrew();
        if (crew == null || crew.getSlotCount() < 1) {
            return;
        }
        TemporarySkillModifiers held = crew.getSkillModifiers();
        // the range runs from what improves the skill to 0 to what worsens it to 8, so every value applies in full
        int rawGunnery = crew.getGunnery() - crew.appliedGunneryModifier();
        gunnery = new Modifier(text("UnitEditorDialog.skillModifier.gunnery"),
              text("UnitEditorDialog.skillModifier.gunnery.tooltip"), crew.appliedGunneryModifier(), -rawGunnery,
              Crew.MAX_SKILL - rawGunnery, timed(held.getGunneryRounds()), null);
        modifiers.add(gunnery);
        int rawPiloting = crew.getPiloting() - crew.appliedPilotingModifier();
        piloting = new Modifier(text("UnitEditorDialog.skillModifier.piloting"),
              text("UnitEditorDialog.skillModifier.piloting.tooltip"), crew.appliedPilotingModifier(), -rawPiloting,
              Crew.MAX_SKILL - rawPiloting, timed(held.getPilotingRounds()), null);
        modifiers.add(piloting);
        // the initiative delta only does something where each unit rolls its own initiative
        if (game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            initiative = new Modifier(text("UnitEditorDialog.skillModifier.initiative"),
                  text("UnitEditorDialog.skillModifier.initiative.tooltip"), held.getInitiativeDelta(),
                  -MAX_INITIATIVE_DELTA, MAX_INITIATIVE_DELTA, timed(held.getInitiativeRounds()), null);
            modifiers.add(initiative);
        }
        int earned = Compute.getEarnedTargetMovementModifier(game, entity.getId()).getValue();
        target = new Modifier(text("UnitEditorDialog.skillModifier.target"),
              text("UnitEditorDialog.skillModifier.target.tooltip"), entity.getGamemasterTargetModifier(),
              Compute.minGamemasterTargetModifier(game, entity.getId()), Compute.MAX_GAMEMASTER_TARGET_MODIFIER, 0,
              Messages.getString("UnitEditorDialog.skillModifier.target.earned",
                    earned > 0 ? "+" + earned : String.valueOf(earned)));
        modifiers.add(target);
    }

    /** A timed modifier's rounds as read, never 0, which marks the untimed target modifier. */
    private static int timed(int rounds) {
        return rounds == 0 ? DEFAULT_MODIFIER_ROUNDS : rounds;
    }

    // ------------------------------------------------------------------------------------------------ ejection

    /** The unit's ejection settings, for a unit with an ejection system; else empty. */
    public List<Setting> ejection() {
        return Collections.unmodifiableList(ejection);
    }

    private void readEjection(Game game) {
        if (!AutomaticEjectionRules.hasEjectionSystem(entity)) {
            return;
        }
        boolean conditional = game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION);
        String disabled = text("UnitEditorDialog.ejection.autoDisabled.tooltip");
        String onTrigger = text("UnitEditorDialog.ejection.conditional.tooltip");
        if (entity instanceof Mek mek) {
            ejection.add(setting("CustomMekDialog.labAutoEject", disabled, !mek.isAutoEject(),
                  (spec, on) -> spec.autoEject = !on));
            if (conditional && mek.hasEjectSeat()) {
                ejection.add(setting("CustomMekDialog.labConditional_Ejection_Ammo", onTrigger, mek.isCondEjectAmmo(),
                      (spec, on) -> spec.conditionalEjectOnAmmoExplosion = on));
                ejection.add(setting("CustomMekDialog.labConditional_Ejection_Engine", onTrigger,
                      mek.isCondEjectEngine(), (spec, on) -> spec.conditionalEjectOnEngineExplosion = on));
                ejection.add(setting("CustomMekDialog.labConditional_Ejection_CT_Destroyed", onTrigger,
                      mek.isCondEjectCTDest(), (spec, on) -> spec.conditionalEjectOnCenterTorsoDestroyed = on));
                ejection.add(setting("CustomMekDialog.labConditional_Ejection_Headshot", onTrigger,
                      mek.isCondEjectHeadshot(), (spec, on) -> spec.conditionalEjectOnHeadshot = on));
            }
        } else if (entity instanceof Aero aero) {
            ejection.add(setting("CustomMekDialog.labAutoEject", disabled, !aero.isAutoEject(),
                  (spec, on) -> spec.autoEject = !on));
            if (conditional && aero.hasEjectSeat()) {
                ejection.add(setting("CustomMekDialog.labConditional_Ejection_Ammo", onTrigger,
                      aero.isCondEjectAmmo(), (spec, on) -> spec.conditionalEjectOnAmmoExplosion = on));
                ejection.add(setting("CustomMekDialog.labConditional_Ejection_Fuel", onTrigger,
                      aero.isCondEjectFuel(), (spec, on) -> spec.conditionalEjectOnFuelExplosion = on));
                ejection.add(setting("CustomMekDialog.labConditional_Ejection_SI_Destroyed", onTrigger,
                      aero.isCondEjectSIDest(),
                      (spec, on) -> spec.conditionalEjectOnStructuralIntegrityDestroyed = on));
            }
        }
    }

    private static Setting setting(String key, String tip, boolean on, BiConsumer<DamageEditSpec, Boolean> writer) {
        return new Setting(text(key), tip, on, writer);
    }

    // ------------------------------------------------------------------------------------------------ server acts

    /** Fills the spec with the modifiers and the ejection settings. */
    void write(DamageEditSpec spec) {
        if (gunnery != null) {
            spec.gunneryModifier = gunnery.value;
            spec.gunneryRounds = gunnery.rounds;
            spec.gunneryPermanent = gunnery.permanent;
            spec.pilotingModifier = piloting.value;
            spec.pilotingRounds = piloting.rounds;
            spec.pilotingPermanent = piloting.permanent;
        }
        if (initiative != null) {
            spec.initiativeModifier = initiative.value;
            spec.initiativeRounds = initiative.rounds;
            spec.initiativePermanent = initiative.permanent;
        }
        if (target != null) {
            spec.targetModifier = target.value;
        }
        for (Setting setting : ejection) {
            setting.writer.accept(spec, setting.on);
        }
    }

    /**
     * OK: the edits go to the server, which applies them to its own copy of the unit and sends the result to every
     * client, the unit here untouched; then a changed Forced Withdrawal order.
     */
    public void commit(Host host) {
        host.sendDamageEdit(form.spec());
        withdrawOrderCommand().ifPresent(host::sendChat);
    }

    /** Asks before exploding the equipment numbered {@code number}. */
    public String explodeConfirmation(int number) {
        Mounted<?> mounted = entity.getEquipment(number);
        return String.format(text("UnitEditorDialog.explode.confirm"),
              mounted == null ? "" : mounted.getName(), entity.getDisplayName());
    }

    /** The command that sets off the equipment numbered {@code number} as a critical hit would. */
    public String explodeCommand(int number) {
        return String.format(EXPLODE_EQUIPMENT_COMMAND, entity.getId(), number);
    }

    /** Asks before withdrawing the unit. */
    public String withdrawConfirmation() {
        return String.format(text("UnitEditorDialog.withdrawUnit.confirm"), entity.getDisplayName());
    }

    /** The command that takes the unit off the field intact, counted as retreated. */
    public String withdrawCommand() {
        return String.format(WITHDRAW_UNIT_COMMAND, entity.getId());
    }

    /** Asks before destroying the unit. */
    public String destroyConfirmation() {
        return String.format(text("UnitEditorDialog.destroyUnit.confirm"), entity.getDisplayName());
    }

    /** Whether the crew can be ejected before the unit is destroyed. */
    public boolean canEjectCrew() {
        return entity.canEjectCrew();
    }

    /** The command that destroys the unit, ejecting its crew first when asked and it can. */
    public String destroyCommand(boolean ejectCrew) {
        return String.format(DESTROY_UNIT_COMMAND, entity.getId(), ejectCrew && canEjectCrew());
    }

    private int indexOfOwner(int id) {
        for (int index = 0; index < owners.size(); index++) {
            if (owners.get(index).getId() == id) {
                return index;
            }
        }
        return -1;
    }

    private static String text(String key) {
        return Messages.getString(key);
    }
}
