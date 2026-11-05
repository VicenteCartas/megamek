/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;
import megamek.SuiteOptions;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.board.Board;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.interfaces.IStartingPositions;
import megamek.common.loaders.MapSettings;
import megamek.common.options.OptionsConstants;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.units.Aero;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;
import megamek.common.units.IAero;
import megamek.common.units.Infantry;
import megamek.common.units.Jumpship;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.SmallCraft;
import megamek.common.units.Tank;
import megamek.common.units.UnitRole;
import megamek.common.units.VTOL;
import megamek.common.util.CrewSkillSummaryUtil;

/**
 * One unit's card in the lobby's forces: its name, a line of tonnage, weight class, role and start, the pilot and their
 * skills, the Battle Value, and tags for whatever else matters about it, problems first. A unit riding on another or
 * networked under a C3 master sits indented beneath it. A blind drop leaves an enemy's unit only a rough description,
 * as the Swing lobby's unit table words it.
 *
 * @param name        the unit's name (chassis and model), or what a blind drop lets the local player see of it
 * @param details     tonnage, weight class, role and start, joined; empty when hidden
 * @param weightClass the weight class, empty when hidden
 * @param tons        the unit's weight, 0 when hidden
 * @param bv          the Battle Value, 0 when hidden
 * @param bvText      the Battle Value as shown, empty when hidden
 * @param pilot       the pilot's nickname or name, or empty when hidden
 * @param skills      gunnery and piloting, such as "3/4", or empty
 * @param tags        every tag, in order of importance
 * @param depth       how far the card is indented, at most {@link #MAX_DEPTH}
 * @param link        what the indent means
 * @param hidden      a blind drop hides the unit from the local player
 * @param nameTone    colours the name for its worst problem, or null for none
 * @param forceId     the formation the unit is in, or {@link megamek.common.force.Force#NO_FORCE}
 * @param editable    whether the local player may configure the unit
 * @param c3          whether the unit has any C3 system
 * @param sheet       movement, protection, heat sinks and weapons, for a wide card's second block; null when hidden
 * @param damage      whether the local player may open the unit's status: every unit they configure, whose damage a
 *                    Game Master shows them read-only, but an asset only where they may edit its damage
 * @param statusDeploys whether the unit's status also sets its deployment, so its card's menu can leave Deploy out;
 *                    an asset's status is only its damage
 */
public record LobbyUnit(int id, int ownerId, String name, String details, String weightClass, double tons, int bv,
      String bvText, String pilot, String skills, List<Tag> tags, int depth, Link link, boolean hidden,
      @Nullable Tone nameTone, int forceId, boolean editable, boolean c3, @Nullable Sheet sheet, boolean damage,
      boolean statusDeploys) {

    /** A card whose status, where the local player may open it, also sets its deployment. */
    public LobbyUnit(int id, int ownerId, String name, String details, String weightClass, double tons, int bv,
          String bvText, String pilot, String skills, List<Tag> tags, int depth, Link link, boolean hidden,
          @Nullable Tone nameTone, int forceId, boolean editable, boolean c3, @Nullable Sheet sheet, boolean damage) {
        this(id, ownerId, name, details, weightClass, tons, bv, bvText, pilot, skills, tags, depth, link, hidden,
              nameTone, forceId, editable, c3, sheet, damage, damage);
    }

    /** A card whose damage the local player may edit exactly when they may configure the unit. */
    public LobbyUnit(int id, int ownerId, String name, String details, String weightClass, double tons, int bv,
          String bvText, String pilot, String skills, List<Tag> tags, int depth, Link link, boolean hidden,
          @Nullable Tone nameTone, int forceId, boolean editable, boolean c3, @Nullable Sheet sheet) {
        this(id, ownerId, name, details, weightClass, tons, bv, bvText, pilot, skills, tags, depth, link, hidden,
              nameTone, forceId, editable, c3, sheet, editable);
    }

    /** The most tags a full card shows before folding the rest into "+n". */
    public static final int MAX_TAGS = 3;
    /** The deepest indent; deeper chains line up at this depth. */
    public static final int MAX_DEPTH = 3;
    private static final String DOT = " \u00B7 ";

    /** How much a tag matters, most first; each has its colour token. */
    public enum Tone {
        DANGER("status.danger"),
        WARNING("status.warning"),
        SETUP("accent.primary"),
        FLAVOUR("text.secondary");

        private final String role;

        Tone(String role) {
            this.role = role;
        }

        /** @return the colour token of this tone */
        public String role() {
            return role;
        }
    }

    /** What an indent means: riding on the unit above, or in its C3 network. */
    public enum Link {
        NONE, TRANSPORT, C3
    }

    /** One tag on a card. */
    public record Tag(String text, Tone tone) {
        public Tag {
            Objects.requireNonNull(text);
            Objects.requireNonNull(tone);
        }
    }

    /**
     * What a wide card shows beside its text: the unit's movement, its armor and structure, its heat sinks and its
     * weapons.
     *
     * @param walk         walking (cruising, safe thrust) points
     * @param run          running (flank, maximum thrust) points
     * @param jump         jumping points, 0 without
     * @param armor        armor points at full
     * @param structure    internal structure points at full
     * @param heatSinks    how many heat sinks, or {@link #NO_HEAT} for a unit without heat
     * @param heatCapacity the heat they sink each turn
     * @param weapons      the weapons and how many of each, most numerous first
     */
    public record Sheet(int walk, int run, int jump, int armor, int structure, int heatSinks, int heatCapacity,
          List<RequisitionDossier.Line> weapons) {

        /** {@link #heatSinks()} of a unit that does not track heat. */
        public static final int NO_HEAT = -1;

        public Sheet {
            List<RequisitionDossier.Line> sorted = new ArrayList<>(weapons);
            sorted.sort(Comparator.comparingInt(RequisitionDossier.Line::count).reversed());
            weapons = List.copyOf(sorted);
        }

        /** The sheet of {@code entity}, at its full armor and its own movement. */
        public static Sheet of(Entity entity) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (WeaponMounted weapon : entity.getWeaponList()) {
                counts.merge(weapon.getType().getName(), 1, Integer::sum);
            }
            List<RequisitionDossier.Line> lines = new ArrayList<>();
            counts.forEach((weapon, count) -> lines.add(new RequisitionDossier.Line(weapon, count)));
            int sinks = NO_HEAT;
            if (entity instanceof Mek mek) {
                sinks = mek.heatSinks();
            } else if (entity instanceof Aero aero) {
                sinks = aero.getHeatSinks();
            }
            return new Sheet(entity.getOriginalWalkMP(), entity.getOriginalRunMP(), entity.getOriginalJumpMP(),
                  Math.max(0, entity.getTotalOArmor()), Math.max(0, entity.getTotalOInternal()), sinks,
                  (sinks == NO_HEAT) ? 0 : entity.getHeatCapacity(), lines);
        }

        /** "4 / 6 / 3", or "4 / 6" without jump. */
        public String move() {
            return (jump > 0) ? Messages.getString("Lobby.requisition.moveJump", walk, run, jump)
                  : Messages.getString("Lobby.requisition.move", walk, run);
        }

        /** "304 · IS 152": the armor and the internal structure. */
        public String protection() {
            return Messages.getString("Lobby.card.protection", number(armor), number(structure));
        }

        /** "10 · 20 / turn": how many heat sinks and what they sink; empty for a unit without heat. */
        public String heat() {
            return (heatSinks == NO_HEAT) ? "" : Messages.getString("Lobby.card.heatSinks", heatSinks, heatCapacity);
        }

        /**
         * The first {@code max} weapons, most numerous first, as "2× LRM 5, ER Small Laser", and "+n" for those left
         * out; empty without weapons.
         */
        public String weaponsSummary(int max) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(max, weapons.size()); i++) {
                parts.add(weapon(weapons.get(i)));
            }
            String summary = String.join(", ", parts);
            int left = weapons.size() - parts.size();
            return (left > 0) ? summary + " " + Messages.getString("Lobby.card.more", left) : summary;
        }

        /** Every weapon, one to a line, for the summary's tooltip. */
        public String weaponsAll() {
            return weapons.stream().map(Sheet::weapon).collect(Collectors.joining("\n"));
        }

        private static String weapon(RequisitionDossier.Line line) {
            return (line.count() > 1) ? Messages.getString("Lobby.card.weaponCount", line.count(), line.name())
                  : line.name();
        }
    }

    /**
     * The tags a card has room for and how many fold into "+n".
     *
     * @param folded how many tags did not fit
     */
    public record Shown(List<Tag> tags, int folded) { }

    public LobbyUnit {
        Objects.requireNonNull(name);
        Objects.requireNonNull(details);
        Objects.requireNonNull(weightClass);
        Objects.requireNonNull(bvText);
        Objects.requireNonNull(pilot);
        Objects.requireNonNull(skills);
        Objects.requireNonNull(link);
        tags = sorted(tags);
        depth = Math.max(0, Math.min(depth, MAX_DEPTH));
    }

    /** @return the first {@code max} tags, problems first, and how many are left over */
    public Shown shown(int max) {
        int count = Math.min(max, tags.size());
        return new Shown(tags.subList(0, count), tags.size() - count);
    }

    /** @return the tonnage and weight class, such as "65 t Heavy"; the class alone without a weight, empty when hidden */
    public String weight() {
        if (hidden || tons <= 0) {
            return hidden ? "" : weightClass;
        }
        String number = NumberFormat.getNumberInstance(SuiteOptions.getNumberFormatLocale()).format(tons);
        String weight = Messages.getString("Lobby.card.tons", number);
        return weightClass.isEmpty() ? weight : Messages.getString("Lobby.card.weight", weight, weightClass);
    }

    /** @return {@code tags} by tone, keeping their order within a tone */
    static List<Tag> sorted(List<Tag> tags) {
        List<Tag> result = new ArrayList<>(tags);
        result.sort(Comparator.comparing(Tag::tone));
        return List.copyOf(result);
    }

    /** @return whether a blind drop hides {@code owner}'s units from {@code local}, as the Swing unit table hides them */
    public static boolean hides(Game game, @Nullable Player local, @Nullable Player owner) {
        return local != null && owner != null && !local.isGameMaster() && local.isEnemyOf(owner)
              && game.getOptions().booleanOption(OptionsConstants.BASE_BLIND_DROP);
    }

    /**
     * Reads {@code entity}'s card as {@code local} sees it. Call it on the thread that changes the game.
     *
     * @param medium   the map's medium ({@link MapSettings#MEDIUM_GROUND} and the others)
     * @param editable whether the local player may configure the unit
     */
    public static LobbyUnit of(Entity entity, Game game, @Nullable Player local, int medium, boolean editable) {
        return of(entity, game, local, medium, editable, editable);
    }

    /**
     * As {@link #of(Entity, Game, Player, int, boolean)}.
     *
     * @param damage whether the local player may open the unit's status
     */
    public static LobbyUnit of(Entity entity, Game game, @Nullable Player local, int medium, boolean editable,
          boolean damage) {
        boolean hidden = hides(game, local, entity.getOwner());
        if (hidden) {
            return new LobbyUnit(entity.getId(), entity.getOwnerId(), blindDescription(entity), "", "", 0, 0, "",
                  "", "", List.of(), 0, Link.NONE, true, null, entity.getForceId(), editable, false, null, false);
        }
        List<Tag> tags = new ArrayList<>();
        problems(entity, game, medium, tags);
        setup(entity, game, medium, tags);
        flavour(entity, tags);

        int carried = carriedDepth(entity);
        int networked = entity.hasC3() ? c3Depth(entity) : 0;
        Link link = carried > 0 ? Link.TRANSPORT : (networked > 0 ? Link.C3 : Link.NONE);
        boolean danger = tags.stream().anyMatch(tag -> tag.tone() == Tone.DANGER);
        boolean caution = hasYellowWarning(entity) || entity instanceof BattlefieldSupportAsset;
        Tone nameTone = danger ? Tone.DANGER : (caution ? Tone.WARNING : null);
        int bv = entity.calculateBattleValue();
        return new LobbyUnit(entity.getId(), entity.getOwnerId(), entity.getShortNameRaw(), details(entity),
              entity.getWeightClassName(), entity.getWeight(), bv, number(bv), pilot(entity), skills(entity, game),
              tags, carried > 0 ? carried : networked, link, false, nameTone, entity.getForceId(), editable,
              entity.hasAnyC3System(), Sheet.of(entity), damage, damage && !(entity instanceof BattlefieldSupportAsset));
    }

    /** What a blind drop lets an enemy see of {@code entity}: its kind and weight class, as the Swing lobby words it. */
    public static String blindDescription(Entity entity) {
        if (entity instanceof Infantry) {
            return Messages.getString("ChatLounge.0");
        } else if (entity instanceof ProtoMek) {
            return Messages.getString("ChatLounge.1");
        } else if (entity.isBuildingEntityOrGunEmplacement()) {
            return Messages.getString("ChatLounge.2");
        } else if (entity.isSupportVehicle()) {
            return entity.getWeightClassName();
        } else if (entity.isFighter()) {
            return entity.getWeightClassName() + Messages.getString("ChatLounge.4");
        } else if (entity instanceof Mek) {
            return entity.getWeightClassName() + Messages.getString("ChatLounge.3");
        } else if (entity instanceof Tank) {
            return entity.getWeightClassName() + Messages.getString("ChatLounge.6");
        }
        return entity.getWeightClassName();
    }

    private static String details(Entity entity) {
        List<String> parts = new ArrayList<>();
        if (entity instanceof BattlefieldSupportAsset) {
            parts.add(Messages.getString("ChatLounge.BFSAsset"));
        } else {
            parts.add(Messages.getString("Lobby.card.tons",
                  NumberFormat.getNumberInstance(SuiteOptions.getNumberFormatLocale()).format(entity.getWeight())));
            parts.add(entity.getWeightClassName());
        }
        UnitRole role = entity.getRole();
        if (!entity.isUnitGroup() && role != null && role.hasRole() && role != UnitRole.NONE) {
            parts.add(role.toString());
        }
        int start = entity.getStartingPos(true);
        boolean riding = entity.getTransportId() != Entity.NONE || entity.getTowedBy() != Entity.NONE;
        if (!entity.isOffBoard() && !riding && start >= 0) {
            String where = start <= Board.NUM_ZONES ? IStartingPositions.START_LOCATION_NAMES[start]
                  : Messages.getString("Lobby.card.zone",
                        Integer.toString(Board.decodeCustomDeploymentZoneID(start)));
            parts.add(Messages.getString("Lobby.card.start", where));
        }
        return String.join(DOT, parts);
    }

    private static String pilot(Entity entity) {
        Crew crew = entity.getCrew();
        if (entity.isUncrewed() || crew == null) {
            return Messages.getString("ChatLounge.noCrew");
        } else if (crew.getSlotCount() > 1 || entity instanceof FighterSquadron) {
            return Messages.getString("ChatLounge.multipleCrew");
        } else if (crew.isMissing(0)) {
            return Messages.getString("Lobby.card.noPilot", crew.getCrewType().getRoleName(0));
        }
        String nickname = crew.getNickname(0);
        return nickname != null && !nickname.isBlank() ? "'" + nickname + "'" : crew.getDesc(0);
    }

    private static String skills(Entity entity, Game game) {
        if (entity.isUncrewed() || entity.getCrew() == null) {
            return "";
        } else if (entity instanceof BattlefieldSupportAsset asset) {
            return String.valueOf(asset.getCrewSkillLevel());
        }
        return CrewSkillSummaryUtil.getEffectiveSkillsAsString(entity,
              game.getOptions().booleanOption(OptionsConstants.RPG_RPG_GUNNERY));
    }

    /** Problems that stop the unit or need the player's eye: red, then yellow. */
    private static void problems(Entity entity, Game game, int medium, List<Tag> tags) {
        if (!entity.isDesignValid()) {
            tags.add(tag("ChatLounge.invalidDesign", Tone.DANGER));
        }
        PlanetaryConditions conditions = game.getPlanetaryConditions();
        if ((conditions != null && conditions.whyDoomed(entity, game) != null)
              || (entity.doomedInAtmosphere() && medium == MapSettings.MEDIUM_ATMOSPHERE)
              || (entity.doomedOnGround() && medium == MapSettings.MEDIUM_GROUND)
              || (entity.doomedInSpace() && medium == MapSettings.MEDIUM_SPACE)) {
            tags.add(tag("Lobby.card.doomed", Tone.DANGER));
        }
        if (entity.isShutDown()) {
            tags.add(tag("Lobby.card.shutdown", Tone.DANGER));
        }
        if (entity instanceof FighterSquadron && entity.getLoadedUnits().isEmpty()) {
            tags.add(tag("Lobby.card.emptySquadron", Tone.WARNING));
        }
        if (entity.getHeat() != 0 && entity.tracksHeat()) {
            tags.add(new Tag(Messages.getString("Lobby.card.heat", Integer.toString(entity.getHeat())),
                  Tone.WARNING));
        }
        if (entity.countPartialRepairs() > 0) {
            tags.add(tag("Lobby.card.repairs", Tone.WARNING));
        }
        boolean ejectionSeat = (entity instanceof Mek mek && mek.hasEjectSeat() && !mek.isAutoEject())
              || (entity instanceof Aero aero && !(entity instanceof Jumpship) && !(entity instanceof SmallCraft)
              && aero.hasEjectSeat() && !aero.isAutoEject());
        if (ejectionSeat) {
            tags.add(tag("Lobby.card.autoEject", Tone.WARNING));
        }
    }

    /** How the unit enters the battle: networks, transport, deployment and its starting state. */
    private static void setup(Entity entity, Game game, int medium, List<Tag> tags) {
        String network = network(entity);
        if (network != null) {
            tags.add(new Tag(network, hasYellowWarning(entity) ? Tone.WARNING : Tone.SETUP));
        }
        boolean carried = entity.getTransportId() != Entity.NONE;
        boolean towed = entity.getTowedBy() != Entity.NONE;
        if (carried && game.getEntity(entity.getTransportId()) instanceof Entity carrier) {
            tags.add(new Tag(Messages.getString("Lobby.card.aboard", carrier.getChassis()), Tone.SETUP));
        } else if (towed && game.getEntity(entity.getTowedBy()) instanceof Entity tractor) {
            tags.add(new Tag(Messages.getString("Lobby.card.towed", tractor.getChassis()), Tone.SETUP));
        } else if (!carried && !towed) {
            if (entity.isHidden() && medium == MapSettings.MEDIUM_GROUND) {
                tags.add(tag("Lobby.card.hidden", Tone.SETUP));
            }
            if (entity.isHullDown()) {
                tags.add(tag("Lobby.card.hullDown", Tone.SETUP));
            }
            if (entity.isProne()) {
                tags.add(tag("Lobby.card.prone", Tone.SETUP));
            }
            if (entity instanceof Infantry infantry && infantry.getDugIn() == Infantry.DUG_IN_COMPLETE) {
                tags.add(tag("Lobby.card.dugIn", Tone.SETUP));
            }
        }
        Entity trainHead = trainHeadOf(entity);
        if (entity.isOffBoard() || (trainHead != null && trainHead.isOffBoard())) {
            tags.add(tag("Lobby.card.offBoard", Tone.SETUP));
        }
        if (entity.getDeployRound() == Entity.DEPLOY_ROUND_PRE_GAME) {
            tags.add(tag("Lobby.card.preGame", Tone.SETUP));
        } else if (entity.getDeployRound() > 0) {
            tags.add(new Tag(Messages.getString("Lobby.card.round", Integer.toString(entity.getDeployRound())),
                  Tone.SETUP));
        }
        if (!carried) {
            if (entity.isAero() && entity instanceof IAero aero) {
                String flight = medium == MapSettings.MEDIUM_SPACE
                      ? Messages.getString("Lobby.card.velocity", Integer.toString(aero.getCurrentVelocity()))
                      : Messages.getString("Lobby.card.flight", Integer.toString(aero.getCurrentVelocity()),
                            Integer.toString(aero.getAltitude()));
                tags.add(new Tag(flight, Tone.SETUP));
            } else if (entity.getElevation() != 0 || entity instanceof VTOL) {
                tags.add(new Tag(Messages.getString("Lobby.card.elevation",
                      Integer.toString(entity.getElevation())), Tone.SETUP));
            }
        }
    }

    /** The unit's C3 role in a few words, or null when it has no C3. */
    private static @Nullable String network(Entity entity) {
        String peer = entity.hasC3i() ? "C3i" : entity.hasNavalC3() ? "NC3" : entity.hasNovaCEWS() ? "Nova CEWS" : null;
        if (peer != null) {
            return hasYellowWarning(entity) ? Messages.getString("Lobby.card.unlinked", peer)
                  : Messages.getString("Lobby.card.network", peer, entity.getC3NetId());
        } else if (!entity.hasC3()) {
            return null;
        }
        Entity master = entity.getC3Master();
        if (master == null) {
            return entity.hasC3S() ? Messages.getString("Lobby.card.unlinked", Messages.getString("ChatLounge.C3S"))
                  : Messages.getString("ChatLounge.C3Master");
        } else if (entity.C3MasterIs(entity)) {
            return Messages.getString("ChatLounge.C3CC");
        }
        return Messages.getString(entity.hasC3S() ? "Lobby.card.slaveOf" : "Lobby.card.masterUnder",
              master.getChassis());
    }

    /** Quirks, ECM and the pilot's abilities: worth knowing, never a problem. */
    private static void flavour(Entity entity, List<Tag> tags) {
        int quirks = entity.countQuirks() + entity.countWeaponQuirks();
        if (quirks > 0) {
            tags.add(new Tag(Messages.getString("Lobby.card.quirks", Integer.toString(quirks)), Tone.FLAVOUR));
        }
        if (entity.hasActiveECM()) {
            tags.add(tag("Lobby.card.ecm", Tone.FLAVOUR));
        }
        if (!entity.isUncrewed() && entity.getCrew() != null && entity.getCrew().countOptions() > 0) {
            tags.add(tag("Lobby.card.abilities", Tone.FLAVOUR));
        }
    }

    /**
     * Whether the unit has a setup problem the Swing lobby marks in yellow: an empty squadron or an unconnected C3
     * node.
     */
    static boolean hasYellowWarning(Entity entity) {
        return (entity instanceof FighterSquadron && entity.getLoadedUnits().isEmpty())
              || ((entity.hasC3i() || entity.hasNavalC3()) && (entity.calculateFreeC3Nodes() == 5))
              || (entity.hasNovaCEWS() && (entity.calculateFreeC3Nodes() == 2))
              || ((entity.getC3Master() == null) && entity.hasC3S());
    }

    /** How many carriers sit above this unit: 1 for a DropShip in a JumpShip, 2 for a Mek inside that DropShip. */
    static int carriedDepth(Entity entity) {
        int depth = 0;
        Entity current = entity;
        while (current != null && depth < MAX_DEPTH) {
            Entity carrier = carrierOf(current);
            if (carrier == null || carrier.getId() == current.getId()) {
                break;
            }
            depth++;
            current = carrier;
        }
        return depth;
    }

    /** The unit this one rides on, carried in a bay or towed behind, or null when neither. */
    private static @Nullable Entity carrierOf(Entity entity) {
        if (entity.getGame() == null) {
            return null;
        }
        if (entity.getTransportId() != Entity.NONE) {
            return entity.getGame().getEntity(entity.getTransportId());
        }
        if (entity.getTractor() != Entity.NONE) {
            return entity.getGame().getEntity(entity.getTractor());
        }
        return null;
    }

    /** The tractor heading the train this unit is towed by, or null when it is not towed. */
    private static @Nullable Entity trainHeadOf(Entity entity) {
        if (entity.getGame() == null || entity.getTractor() == Entity.NONE) {
            return null;
        }
        return entity.getGame().getEntity(entity.getTractor());
    }

    /** How many masters sit above this unit: 1 for a lance master under a company commander, 2 for its slaves. */
    static int c3Depth(Entity entity) {
        int depth = 0;
        Entity current = entity;
        while (depth < MAX_DEPTH) {
            Entity master = current.getC3Master();
            if (master == null || master.getId() == current.getId()) {
                break;
            }
            depth++;
            current = master;
        }
        return depth;
    }

    private static Tag tag(String key, Tone tone) {
        return new Tag(Messages.getString(key), tone);
    }

    /** {@code value} with the number format's grouping. */
    public static String number(int value) {
        return NumberFormat.getIntegerInstance(SuiteOptions.getNumberFormatLocale()).format(value);
    }
}
