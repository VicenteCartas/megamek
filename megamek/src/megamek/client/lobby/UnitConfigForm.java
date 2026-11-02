/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.generator.RandomCallsignGenerator;
import megamek.client.generator.RandomGenderGenerator;
import megamek.client.generator.RandomNameGenerator;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.TechConstants;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.ArtilleryRange;
import megamek.common.enums.Gender;
import megamek.common.equipment.EquipmentMode;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.exceptions.LocationFullException;
import megamek.common.game.Game;
import megamek.common.icons.Portrait;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Aero;
import megamek.common.units.Crew;
import megamek.common.units.CrewArmorKitRules;
import megamek.common.units.CrewSidearmRules;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.FighterSquadron;
import megamek.common.units.IAero;
import megamek.common.units.Infantry;
import megamek.common.units.Jumpship;
import megamek.common.units.LAMPilot;
import megamek.common.units.LandAirMek;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadVee;
import megamek.common.units.SmallCraft;
import megamek.common.units.Tank;
import megamek.common.verifier.TestEntity;
import megamek.common.weapons.bayWeapons.ArtilleryBayWeapon;
import megamek.common.weapons.bayWeapons.capital.CapitalMissileBayWeapon;
import megamek.common.weapons.infantry.InfantryWeapon;
import megamek.server.ServerBoardHelper;

/**
 * The values of the unit configuration (the Swing {@code CustomMekDialog}'s crew and deployment tabs) for one unit or
 * several: each crew member's identity, skills, aptitudes and personal equipment for a single unit; the command
 * controls; and the deployment and starting state. Read from the units on the Swing thread, edited by the view on its
 * own, and written back by {@link #apply} on the Swing thread as the dialog's OK writes them. Numbers are held as the
 * texts the view shows, so a wrong one is an {@link #error} rather than a lost edit; OK waits for {@link #valid}.
 */
public final class UnitConfigForm {
    /** A crew member's numbers. */
    public enum Skill {
        GUNNERY, GUNNERY_L, GUNNERY_M, GUNNERY_B, GUNNERY_AERO, GUNNERY_AERO_L, GUNNERY_AERO_M, GUNNERY_AERO_B,
        PILOTING, PILOTING_AERO, ARTILLERY, TOUGHNESS, FATIGUE, SMALL_ARMS
    }

    /** The unit's numbers. */
    public enum Field {
        INIT, COMMAND, VELOCITY, ALTITUDE, FUEL, HEIGHT, OFFSET, WIDTH
    }

    /** A wrong number: the message key and its arguments, such as a range's ends. */
    public record Problem(String key, List<Integer> values) {
        public Problem {
            values = List.copyOf(values);
        }
    }

    /** A piece of personal equipment to choose: its name, and its internal name, null for none. */
    public record Choice(String name, @Nullable String internalName) { }

    /** A ProtoMek of another point to swap unit numbers with. */
    public record Swap(int id, String label) { }

    /** What the bay's header shows of a unit. */
    public record Summary(int id, String chassis, String model, int battleValue, double tons) { }

    /** The highest a crew skill may be. */
    public static final int MAX_SKILL = 8;
    /** The least distance an off board unit stands away, one map sheet. */
    public static final int MIN_OFF_BOARD = 17;
    /** The last round the deployment round offers. */
    public static final int LAST_ROUND = 40;
    /** The off board directions in the order the view offers them. */
    public static final List<OffBoardDirection> DIRECTIONS = List.of(OffBoardDirection.NORTH, OffBoardDirection.SOUTH,
          OffBoardDirection.EAST, OffBoardDirection.WEST);
    /** The compass zones by row from the north, then by column from the west. */
    private static final int[][] COMPASS = { { Board.START_NW, Board.START_N, Board.START_NE },
                                             { Board.START_W, Board.START_CENTER, Board.START_E },
                                             { Board.START_SW, Board.START_S, Board.START_SE } };

    private final Client client;
    private final Client human;
    private final List<Entity> units;
    private final List<Integer> ids = new ArrayList<>();
    private final List<Summary> summaries = new ArrayList<>();
    private final boolean editable;
    private final GameOptions options;
    private final boolean space;
    private final int boardWidth;
    private final int boardHeight;
    private final List<Integer> customZones = new ArrayList<>();
    private final List<Member> members = new ArrayList<>();
    private final Map<Field, String> texts = new EnumMap<>(Field.class);
    private final List<String> modes = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private @Nullable UnitEquipmentForm equipment;
    private @Nullable UnitAbilitiesForm abilities;
    private @Nullable UnitQuirksForm quirks;
    private @Nullable PartialRepairsForm repairs;

    private final boolean multiple;
    private final boolean showMek;
    private final boolean showShip;
    private final boolean showAero;
    private final boolean showVtol;
    private final boolean showWige;
    private final boolean showQuadVee;
    private final boolean showLam;
    private final boolean showGlider;
    private final boolean showStealth;
    private final boolean showOffBoard;
    private final boolean showHidden;
    private final boolean showScanTarget;
    private final boolean showShutdown;
    private final boolean showTankHullDown;
    private final boolean showDugIn;
    private final boolean canHide;
    private final boolean modeLocked;
    private final boolean roundLocked;
    private final boolean preGame;
    private final boolean bimodal;
    private final int maxVelocity;
    private final int maxFuel;
    private final int maxOffBoard;
    private final boolean hasPrevious;
    private final boolean hasNext;

    private boolean commander;
    private int mode;
    private int zone;
    /** The board the zone picture shows, or null when there is none to show. */
    private final @Nullable Board board;
    /** The owner's own zone, as {@link #zoneHexes} reads a zone: position, width, offset, then the Any box. */
    private final int[] ownersZone;
    private int round;
    private int anyNwX;
    private int anyNwY;
    private int anySeX;
    private int anySeY;
    private boolean shutdown;
    private boolean prone;
    private boolean hullDown;
    private boolean dugIn;
    private boolean hidden;
    private boolean scanTarget;
    private boolean stealth;
    private boolean offBoard;
    private int direction;
    private int distance;
    private boolean airborne;

    /**
     * Reads the units; Swing thread.
     *
     * @param client   the client of the units' owner, a local bot's for its units
     * @param human    the player's own client, whose map settings, skill generator and Game Master seat count
     * @param units    the units, all of one owner
     * @param editable whether the player may change them
     * @param board    the board the custom deployment zones come from, or null when there are none to offer
     */
    public UnitConfigForm(Client client, Client human, List<Entity> units, boolean editable, @Nullable Board board) {
        if (units.isEmpty()) {
            throw new IllegalArgumentException("The unit configuration needs a unit");
        }
        this.client = client;
        this.human = human;
        this.units = List.copyOf(units);
        this.editable = editable;
        this.board = (board != null && board.getWidth() > 0 && board.getHeight() > 0) ? board : null;
        Game game = client.getGame();
        options = human.getGame().getOptions();
        MapSettings mapSettings = human.getMapSettings();
        space = mapSettings.getMedium() == MapSettings.MEDIUM_SPACE;
        boardWidth = mapSettings.getBoardWidth() * mapSettings.getMapWidth();
        boardHeight = mapSettings.getBoardHeight() * mapSettings.getMapHeight();
        for (Entity unit : units) {
            ids.add(unit.getId());
            summaries.add(new Summary(unit.getId(), unit.getChassis(), unit.getModel(), unit.calculateBattleValue(),
                  unit.getWeight()));
        }
        Entity first = units.get(0);
        multiple = units.size() > 1 || first instanceof FighterSquadron;
        Player owner = first.getOwner();
        ownersZone = owner == null
              ? new int[] { Board.START_ANY, 0, 0, Entity.STARTING_ANY_NONE, Entity.STARTING_ANY_NONE,
                            Entity.STARTING_ANY_NONE, Entity.STARTING_ANY_NONE }
              : new int[] { owner.getStartingPos(), owner.getStartWidth(), owner.getStartOffset(),
                            owner.getStartingAnyNWx(), owner.getStartingAnyNWy(), owner.getStartingAnySEx(),
                            owner.getStartingAnySEy() };

        boolean mek = true;
        boolean ship = true;
        boolean aero = true;
        boolean vtol = true;
        boolean wige = true;
        boolean quadVee = true;
        boolean lam = true;
        boolean glider = true;
        boolean stealthy = true;
        boolean eligible = true;
        for (Entity unit : units) {
            mek &= unit instanceof Mek;
            ship &= unit.isLargeAerospace();
            aero &= unit.isAero() && !unit.isLargeAerospace();
            vtol &= unit.getMovementMode().isVTOL();
            wige &= (unit instanceof Tank) && unit.getMovementMode().isWiGE();
            quadVee &= unit instanceof QuadVee;
            lam &= unit instanceof LandAirMek;
            glider &= (unit instanceof ProtoMek) && unit.getMovementMode().isWiGE();
            stealthy &= unit.hasStealth();
            eligible &= !space && unit.getAltitude() == 0 && !unit.isBuildingEntityOrGunEmplacement()
                  && unit.getTractor() == Entity.NONE && trainCarriesArtillery(unit);
        }
        showMek = mek;
        showShip = ship;
        showAero = aero;
        showVtol = vtol;
        showWige = wige;
        showQuadVee = quadVee;
        showLam = lam;
        showGlider = glider;
        showStealth = stealthy;
        showOffBoard = eligible;
        showHidden = options.booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS);
        Player keyboard = human.getLocalPlayer();
        showScanTarget = keyboard != null && keyboard.isGameMaster()
              && options.booleanOption(OptionsConstants.VICTORY_USE_OBJECTIVES);
        showShutdown = options.booleanOption(OptionsConstants.RPG_BEGIN_SHUTDOWN) && !(first instanceof Infantry)
              && !first.isBuildingEntityOrGunEmplacement();
        showTankHullDown = (first instanceof Tank tank) && tank.isHullDownCapable()
              && options.booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN);
        showDugIn = (first instanceof Infantry infantry) && !infantry.isMechanized()
              && options.booleanOption(OptionsConstants.ADVANCED_TAC_OPS_DIG_IN);
        canHide = first.canHide();
        bimodal = (first instanceof LandAirMek landAirMek) && landAirMek.getLAMType() == LandAirMek.LAM_BIMODAL;
        modeLocked = first.getTransportId() != Entity.NONE;
        roundLocked = first.getTransportId() != Entity.NONE;
        preGame = Game.rulesManager.getRulesGame().isWalkOnDeployment();
        maxVelocity = 2 * first.getWalkMP();
        maxFuel = (first instanceof IAero flyer) ? flyer.getFuel() : 0;
        maxOffBoard = showOffBoard ? offBoardReach() : 0;

        for (int slot = 0; slot < first.getCrew().getSlotCount(); slot++) {
            members.add(new Member(first, slot, game));
        }
        texts.put(Field.INIT, Integer.toString(first.getCrew().getInitBonus()));
        texts.put(Field.COMMAND, Integer.toString(first.getCrew().getCommandBonus()));
        commander = first.isCommander();
        readDeployment(first, board, game);
        readSingle(first);
        hasPrevious = next(false) != null;
        hasNext = next(true) != null;
    }

    /** Reads the units for the lobby, the custom zones from the board the map settings make; Swing thread. */
    public static UnitConfigForm of(Client client, Client human, List<Entity> units, boolean editable) {
        Board board = client.getGame().getPhase().isLounge()
              ? ServerBoardHelper.getPossibleGameBoard(human.getMapSettings(), true) : null;
        return new UnitConfigForm(client, human, units, editable, board);
    }

    private void readDeployment(Entity first, @Nullable Board board, Game game) {
        if (showQuadVee) {
            modes.add("CustomMekDialog.ModeQuad");
            modes.add("CustomMekDialog.ModeVehicle");
            mode = first.getConversionMode() == QuadVee.CONV_MODE_VEHICLE ? 1 : 0;
        } else if (showLam) {
            modes.add("CustomMekDialog.ModeBiped");
            if (!bimodal) {
                modes.add("CustomMekDialog.ModeAirMek");
            }
            modes.add("CustomMekDialog.ModeFighter");
            if (first.getConversionMode() == LandAirMek.CONV_MODE_AIR_MEK) {
                mode = 1;
            } else if (first.getConversionMode() == LandAirMek.CONV_MODE_FIGHTER) {
                mode = modes.size() - 1;
            }
        }
        if (board != null && game.getPhase().isLounge()) {
            customZones.addAll(board.getCustomDeploymentZones().stream().sorted().toList());
        }
        int startingPos = first.getStartingPos(false);
        if (startingPos < Board.NUM_ZONES) {
            zone = startingPos;
        } else {
            // A zone the board no longer has falls back to the owner's, as the Swing chooser's selection does
            zone = customZones.contains(Board.decodeCustomDeploymentZoneID(startingPos)) ? startingPos
                  : Board.START_NONE;
        }
        int deployRound = first.getDeployRound();
        round = (deployRound >= 0 && deployRound <= LAST_ROUND) ? deployRound : firstRound();
        texts.put(Field.OFFSET, Integer.toString(first.getStartingOffset(false)));
        texts.put(Field.WIDTH, Integer.toString(first.getStartingWidth(false)));
        anyNwX = Math.min(first.getStartingAnyNWx(false) + 1, boardWidth);
        anyNwY = Math.min(first.getStartingAnyNWy(false) + 1, boardHeight);
        anySeX = Math.min(first.getStartingAnySEx(false) + 1, boardWidth);
        anySeY = Math.min(first.getStartingAnySEy(false) + 1, boardHeight);

        shutdown = showShutdown && first.isManualShutdown();
        if (showMek) {
            hullDown = first.isHullDown() && !first.isProne();
            prone = first.isProne() && !first.isHullDown();
        }
        if (showTankHullDown) {
            hullDown = first.isHullDown();
        }
        dugIn = showDugIn && ((Infantry) first).getDugIn() == Infantry.DUG_IN_COMPLETE;
        hidden = showHidden && first.isHidden();
        scanTarget = showScanTarget && first.isDesignatedScanTarget();
        stealth = showStealth && first.isStealthOn();
        offBoard = showOffBoard && first.isOffBoard();
        OffBoardDirection facing = first.getOffBoardDirection();
        direction = Math.max(0, DIRECTIONS.indexOf(facing == OffBoardDirection.NONE ? OffBoardDirection.NORTH
              : facing));
        distance = Math.max(MIN_OFF_BOARD, Math.min(first.getOffBoardDistance(), maxOffBoard));

        IAero flyer = (first instanceof IAero aero) ? aero : null;
        boolean flies = showAero || showLam || showShip;
        texts.put(Field.VELOCITY, Integer.toString(flies && flyer != null ? flyer.getCurrentVelocity() : 0));
        texts.put(Field.ALTITUDE, Integer.toString(flies ? first.getAltitude() : 0));
        texts.put(Field.FUEL, Integer.toString(flies && flyer != null ? flyer.getCurrentFuel() : 0));
        texts.put(Field.HEIGHT, Integer.toString(showVtol || showLam || showGlider ? first.getElevation() : 0));
        airborne = showWige && first.getElevation() > 0;
    }

    // region The units

    /** The units, in the order they were given; read them on the Swing thread only. */
    public List<Entity> units() {
        return units;
    }

    /** The units' ids. */
    public List<Integer> ids() {
        return List.copyOf(ids);
    }

    /** What the header shows of each unit, read with the units. */
    public List<Summary> summaries() {
        return List.copyOf(summaries);
    }

    /** Whether several units are edited at once, or a squadron: the crew is then not shown. */
    public boolean multiple() {
        return multiple;
    }

    public boolean editable() {
        return editable;
    }

    /** Whether a unit before these, of the same player, may be configured next. */
    public boolean hasPrevious() {
        return hasPrevious;
    }

    /** Whether a unit after these, of the same player, may be configured next. */
    public boolean hasNext() {
        return hasNext;
    }

    /**
     * The next unit to configure after these, or before them: the first in the game's order that is not one of them
     * and is the local player's, or, without a blind drop, of the same owner as these. Swing thread.
     */
    public @Nullable Entity next(boolean forward) {
        Game game = client.getGame();
        boolean blind = game.getOptions().booleanOption(OptionsConstants.BASE_BLIND_DROP)
              || game.getOptions().booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP);
        Player local = client.getLocalPlayer();
        List<Entity> current = current();
        Entity from = forward ? current.get(current.size() - 1) : current.get(0);
        Entity candidate = forward ? game.getNextEntityFromList(from) : game.getPreviousEntityFromList(from);
        while (candidate != null && !containsId(candidate.getId())) {
            if (candidate.getOwner().equals(local) || (!blind && candidate.getOwner().equals(from.getOwner()))) {
                return candidate;
            }
            candidate = forward ? game.getNextEntityFromList(candidate) : game.getPreviousEntityFromList(candidate);
        }
        return null;
    }

    private boolean containsId(int id) {
        return ids.contains(id);
    }

    // endregion

    // region The crew

    /** The crew members of a single unit, by slot; none for several units. */
    public List<Member> members() {
        return multiple ? List.of() : List.copyOf(members);
    }

    /** The crew member in {@code slot}. */
    public Member member(int slot) {
        return members.get(slot);
    }

    /** Whether the crew shows: one unit with a crew. */
    public boolean showsCrew() {
        return !multiple && !members.isEmpty();
    }

    /** Whether the crew's command controls stand in a tab of their own, as for a crew of several. */
    public boolean commandTab() {
        return showsCrew() && members.size() > 1;
    }

    public boolean commander() {
        return commander;
    }

    public void commander(boolean value) {
        commander = value;
    }

    /** Whether the RPG gunnery skills, laser, missile and ballistic, take the single gunnery's place. */
    public boolean rpgGunnery() {
        return options.booleanOption(OptionsConstants.RPG_RPG_GUNNERY);
    }

    // endregion

    // region The numbers

    public String text(Field field) {
        return texts.getOrDefault(field, "");
    }

    public void text(Field field, String text) {
        texts.put(field, text == null ? "" : text);
    }

    /** Whether the field shows. */
    public boolean shows(Field field) {
        return switch (field) {
            case INIT -> showsCrew() && options.booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE);
            case COMMAND -> showsCrew() && options.booleanOption(OptionsConstants.RPG_COMMAND_INIT);
            case VELOCITY, FUEL -> showAero || showLam || showShip;
            case ALTITUDE -> (showAero || showLam || showShip) && !space;
            case HEIGHT -> showVtol || showLam || showGlider;
            case OFFSET, WIDTH -> true;
        };
    }

    /** Whether the field may be changed now: shown, editable, and allowed by the starting mode and zone. */
    public boolean enabled(Field field) {
        if (!shows(field)) {
            return false;
        }
        return switch (field) {
            case INIT, COMMAND, FUEL -> editable;
            case VELOCITY, ALTITUDE -> editable && (!showLam || lamMode() == LandAirMek.CONV_MODE_FIGHTER);
            case HEIGHT -> editable && (!showLam || lamMode() == LandAirMek.CONV_MODE_AIR_MEK);
            case OFFSET -> edgeZone();
            case WIDTH -> edgeZone() && !widthRestricted();
        };
    }

    /** Whether the deployment width is fixed by the rules for the chosen zone. */
    public boolean widthRestricted() {
        return Game.rulesManager.getRulesGame().restrictDeploymentWidth(null, zone);
    }

    private boolean edgeZone() {
        return zone >= Board.START_ANY && zone < Board.NUM_ZONES - 1;
    }

    /** The field's problem, or empty: only the fields the OK checks, in their ranges. */
    public Optional<Problem> error(Field field) {
        if (!checks(field)) {
            return Optional.empty();
        }
        Integer value = parse(texts.get(field));
        if (value == null) {
            return Optional.of(new Problem("Lobby.settings.wholeNumber", List.of()));
        }
        return switch (field) {
            case INIT, COMMAND -> Optional.empty();
            case OFFSET, WIDTH -> value < 0 ? Optional.of(new Problem("Lobby.settings.notNegative", List.of()))
                  : Optional.empty();
            case VELOCITY -> range(value, 0, maxVelocity);
            case ALTITUDE -> range(value, 0, 10);
            case FUEL -> range(value, 0, maxFuel);
            case HEIGHT -> range(value, 0, maxHeight());
        };
    }

    private boolean checks(Field field) {
        return switch (field) {
            case INIT, COMMAND -> shows(field);
            case VELOCITY, ALTITUDE, FUEL -> aeroApplies() && shows(field);
            case HEIGHT -> heightApplies() && shows(field);
            case OFFSET, WIDTH -> enabled(field);
        };
    }

    private int maxHeight() {
        if (allMatch(unit -> unit.getMovementMode() == EntityMovementMode.VTOL)) {
            return 50;
        }
        return airMekApplies() ? 25 : 12;
    }

    private static Optional<Problem> range(int value, int least, int most) {
        return (value < least || value > most) ? Optional.of(new Problem("Lobby.bay.range", List.of(least, most)))
              : Optional.empty();
    }

    /** Whether every shown number is acceptable. */
    public boolean valid() {
        for (Field field : Field.values()) {
            if (error(field).isPresent()) {
                return false;
            }
        }
        for (Member member : members()) {
            for (Skill skill : Skill.values()) {
                if (member.error(skill).isPresent()) {
                    return false;
                }
            }
        }
        return (equipment == null) || equipment.valid();
    }

    private static @Nullable Integer parse(@Nullable String text) {
        try {
            return text == null ? null : Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int value(Field field) {
        Integer value = parse(texts.get(field));
        return value == null ? 0 : value;
    }

    // endregion

    // region Deployment

    /** The starting modes' message keys, for a QuadVee or a LAM; empty for other units. */
    public List<String> modes() {
        return List.copyOf(modes);
    }

    public int mode() {
        return mode;
    }

    public void mode(int value) {
        if (value >= 0 && value < modes.size()) {
            mode = value;
        }
    }

    /** Whether the starting mode may be changed: not for a unit loaded in a transport. */
    public boolean modeEnabled() {
        return !modeLocked;
    }

    /** The LAM's conversion mode the chosen starting mode stands for. */
    private int lamMode() {
        return (bimodal && mode == LandAirMek.CONV_MODE_AIR_MEK) ? LandAirMek.CONV_MODE_FIGHTER : mode;
    }

    /** The deployment zone: {@link Board#START_NONE} for the owner's, a start position, or an encoded custom zone. */
    public int zone() {
        return zone;
    }

    public void zone(int value) {
        zone = value;
    }

    /** The custom zones of the board, by number, which {@link Board#encodeCustomDeploymentZoneID} makes a zone. */
    public List<Integer> customZones() {
        return List.copyOf(customZones);
    }

    /** The deployment round: -1 before the game (walk-on), 0 at its start, else the round. */
    public int round() {
        return round;
    }

    public void round(int value) {
        round = Math.max(firstRound(), Math.min(LAST_ROUND, value));
    }

    /** The earliest round to offer: before the game when the rules deploy by walking on, else its start. */
    public int firstRound() {
        return preGame ? Entity.DEPLOY_ROUND_PRE_GAME : 0;
    }

    /** Whether the round may be changed: editable, and not for a unit loaded in a transport. */
    public boolean roundEnabled() {
        return editable && !roundLocked;
    }

    public int boardWidth() {
        return boardWidth;
    }

    public int boardHeight() {
        return boardHeight;
    }

    /** The board the zone picture shows: the one the map settings make, in the lobby; null elsewhere. */
    public @Nullable Board board() {
        return board;
    }

    /**
     * The hexes the chosen zone covers on {@link #board}, by column then row, with its offset, width and Any box as they
     * stand; the owner's zone for {@link Board#START_NONE}. Null without a board.
     */
    public @Nullable boolean[][] zoneHexes() {
        if (board == null) {
            return null;
        }
        int[] chosen = zone == Board.START_NONE ? ownersZone
              : new int[] { zone, value(Field.WIDTH), value(Field.OFFSET), Math.min(anyNwX, anySeX) - 1,
                            Math.min(anyNwY, anySeY) - 1, Math.max(anyNwX, anySeX) - 1,
                            Math.max(anyNwY, anySeY) - 1 };
        if (chosen[0] == Board.START_NONE) {
            chosen[0] = Board.START_ANY;
        }
        boolean[][] hexes = new boolean[board.getWidth()][board.getHeight()];
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                hexes[x][y] = board.isLegalDeployment(new Coords(x, y), chosen[0], chosen[1], chosen[2], chosen[3],
                      chosen[4], chosen[5], chosen[6]);
            }
        }
        return hexes;
    }

    /**
     * The compass zone under a point of the board picture, {@code across} and {@code down} its share of the picture's
     * width from the left and of its height from the top: the board in thirds, its middle the centre.
     */
    public static int compassZone(float across, float down) {
        int column = Math.max(0, Math.min(2, (int) (across * 3)));
        int row = Math.max(0, Math.min(2, (int) (down * 3)));
        return COMPASS[row][column];
    }

    /** The custom box for the "Any" zone, its corners counted from 1: north-west x and y, south-east x and y. */
    public int[] anyBox() {
        return new int[] { anyNwX, anyNwY, anySeX, anySeY };
    }

    /** Sets a corner's coordinate, {@code index} as {@link #anyBox}, kept on the board. */
    public void anyBox(int index, int value) {
        int most = (index % 2 == 0) ? boardWidth : boardHeight;
        int kept = Math.max(0, Math.min(most, value));
        switch (index) {
            case 0 -> anyNwX = kept;
            case 1 -> anyNwY = kept;
            case 2 -> anySeX = kept;
            case 3 -> anySeY = kept;
            default -> throw new IndexOutOfBoundsException(index);
        }
    }

    public boolean showsShutdown() {
        return showShutdown;
    }

    public boolean shutdown() {
        return shutdown;
    }

    public void shutdown(boolean value) {
        shutdown = value;
    }

    /** Whether prone shows: for Meks. */
    public boolean showsProne() {
        return showMek;
    }

    /** Whether hull-down shows: for Meks, and for vehicles that can go hull-down when the rules allow it. */
    public boolean showsHullDown() {
        return showMek || showTankHullDown;
    }

    public boolean proneEnabled() {
        if (!editable) {
            return false;
        }
        if (showQuadVee) {
            return mode == 0;
        }
        return !showLam || lamMode() < LandAirMek.CONV_MODE_FIGHTER;
    }

    public boolean hullDownEnabled() {
        return editable && (!showLam || lamMode() == LandAirMek.CONV_MODE_MEK);
    }

    public boolean prone() {
        return prone;
    }

    /** Sets prone; a prone Mek is not hull-down. */
    public void prone(boolean value) {
        prone = value;
        if (value) {
            hullDown = false;
        }
    }

    public boolean hullDown() {
        return hullDown;
    }

    /** Sets hull-down; a hull-down Mek is not prone. */
    public void hullDown(boolean value) {
        hullDown = value;
        if (value) {
            prone = false;
        }
    }

    /** Whether dug in shows: for infantry that is not mechanized, when the rules allow digging in. */
    public boolean showsDugIn() {
        return showDugIn;
    }

    public boolean dugIn() {
        return dugIn;
    }

    public void dugIn(boolean value) {
        dugIn = value;
    }

    public boolean showsHidden() {
        return showHidden;
    }

    /** Whether hidden may be changed: editable, and the unit can hide. */
    public boolean hiddenEnabled() {
        return editable && canHide;
    }

    public boolean hidden() {
        return hidden;
    }

    public void hidden(boolean value) {
        hidden = value;
    }

    /** Whether the scan target shows: for a Game Master in a game with objectives. */
    public boolean showsScanTarget() {
        return showScanTarget;
    }

    public boolean scanTarget() {
        return scanTarget;
    }

    public void scanTarget(boolean value) {
        scanTarget = value;
    }

    /** Whether deploying stealthed shows: when every unit has stealth armor. */
    public boolean showsStealth() {
        return showStealth;
    }

    public boolean stealth() {
        return stealth;
    }

    public void stealth(boolean value) {
        stealth = value;
    }

    /** Whether WiGE airborne shows: for WiGE vehicles. */
    public boolean showsAirborne() {
        return showWige;
    }

    public boolean airborne() {
        return airborne;
    }

    public void airborne(boolean value) {
        airborne = value;
    }

    /** Whether off board deployment shows: units with artillery off the ground, not towed, outside space. */
    public boolean showsOffBoard() {
        return showOffBoard;
    }

    public boolean offBoard() {
        return offBoard;
    }

    public void offBoard(boolean value) {
        offBoard = value;
    }

    /** The off board direction, an index into {@link #DIRECTIONS}. */
    public int direction() {
        return direction;
    }

    public void direction(int value) {
        if (value >= 0 && value < DIRECTIONS.size()) {
            direction = value;
        }
    }

    public int distance() {
        return distance;
    }

    public void distance(int value) {
        distance = Math.max(MIN_OFF_BOARD, Math.min(maxDistance(), value));
    }

    /** The farthest off board the units may stand and still reach the board, at least one map sheet. */
    public int maxDistance() {
        return Math.max(MIN_OFF_BOARD, maxOffBoard);
    }

    // endregion

    // region Apply

    /**
     * Writes the values to the units as the Swing dialog's OK does, crew first for a single unit, then the deployment
     * of each unit, and checks each design's validity; swaps unit numbers with another ProtoMek and sends that one.
     * The caller sends the units. Swing thread.
     *
     * @return false, changing nothing, when a number is wrong
     */
    public boolean apply() {
        if (!valid()) {
            return false;
        }
        warnings.clear();
        List<Entity> current = current();
        boolean aero = aeroApplies();
        boolean ship = allMatch(unit -> unit instanceof SmallCraft || unit instanceof Jumpship);
        boolean vtol = allMatch(unit -> unit.getMovementMode() == EntityMovementMode.VTOL);
        boolean wige = allMatch(unit -> unit instanceof Tank && unit.getMovementMode() == EntityMovementMode.WIGE);
        boolean airMek = airMekApplies();
        boolean glider = allMatch(unit -> unit instanceof ProtoMek
              && unit.getMovementMode() == EntityMovementMode.WIGE);
        boolean quadVee = allMatch(unit -> unit instanceof QuadVee);
        boolean lam = allMatch(unit -> unit instanceof LandAirMek);
        int velocity = (aero || ship) ? value(Field.VELOCITY) : 0;
        int altitude = (aero || ship) ? value(Field.ALTITUDE) : 0;
        int fuel = (aero || ship) ? value(Field.FUEL) : 0;
        int height = (vtol || airMek || glider) ? value(Field.HEIGHT) : 0;
        if (wige) {
            height = airborne ? 1 : 0;
        }

        if (current.size() == 1) {
            applyCrew(current.get(0));
        }
        for (Entity unit : current) {
            applyDeployment(unit, aero, ship, vtol || wige || airMek || glider, height, velocity, altitude, fuel,
                  quadVee, lam);
        }
        Game game = client.getGame();
        for (Entity unit : current) {
            TestEntity test = TestEntity.getEntityVerifier(unit);
            int level = TechConstants.getGameTechLevel(game, unit.isClan());
            unit.setDesignValid(test == null || test.correctEntity(new StringBuffer(), level));
        }
        if (!multiple) {
            // The equipment numbers may have moved, and the rows name them
            readSingle(current.get(0));
        }
        return true;
    }

    /**
     * Reads what only a single unit shows, as the Swing dialog does: its equipment, its pilot abilities, and with their
     * game options its quirks and partial repairs.
     */
    private void readSingle(Entity unit) {
        if (multiple) {
            return;
        }
        GameOptions options = client.getGame().getOptions();
        equipment = UnitEquipmentForm.of(unit, client, editable).orElse(null);
        abilities = UnitAbilitiesForm.of(unit, options, editable).orElse(null);
        if (abilities != null) {
            abilities.link(equipment);
        }
        quirks = options.booleanOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS)
              ? UnitQuirksForm.of(unit, editable).orElse(null) : null;
        repairs = options.booleanOption(OptionsConstants.ADVANCED_STRATOPS_PARTIAL_REPAIRS)
              ? PartialRepairsForm.of(unit, editable).orElse(null) : null;
    }

    /** The pilot abilities of a single unit; none when several are edited or the game options allow none. */
    public Optional<UnitAbilitiesForm> abilities() {
        return Optional.ofNullable(abilities);
    }

    /** The quirks of a single unit, when the quirks option is on. */
    public Optional<UnitQuirksForm> quirks() {
        return Optional.ofNullable(quirks);
    }

    /** The partial repairs of a single unit, when the partial repairs option is on. */
    public Optional<PartialRepairsForm> repairs() {
        return Optional.ofNullable(repairs);
    }

    /** The warnings of the last {@link #apply}, such as a C3 network grown too big; cleared by the next. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** The equipment of a single unit; none when several are edited, as the Swing dialog applies none then. */
    public Optional<UnitEquipmentForm> equipment() {
        return Optional.ofNullable(equipment);
    }

    /** The units as the game now has them: the server's echo of a change replaces the objects read at first. */
    private List<Entity> current() {
        List<Entity> found = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Entity now = client.getGame().getEntity(ids.get(i));
            found.add(now != null ? now : units.get(i));
        }
        return found;
    }

    /** The units as {@link #apply} last wrote them; Swing thread. */
    public List<Entity> applied() {
        return current();
    }

    private boolean allMatch(java.util.function.Predicate<Entity> test) {
        for (Entity unit : units) {
            if (!test.test(unit)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the units fly as aerospace in their chosen mode: fighters, and LAMs starting as fighters. */
    private boolean aeroApplies() {
        return allMatch(unit -> ((unit instanceof Aero) && !(unit instanceof SmallCraft || unit instanceof Jumpship))
              || ((unit instanceof LandAirMek landAirMek) && (mode == 2
              || (landAirMek.getLAMType() == LandAirMek.LAM_BIMODAL && mode == 1))))
              || allMatch(unit -> unit instanceof SmallCraft || unit instanceof Jumpship);
    }

    private boolean airMekApplies() {
        return allMatch(unit -> (unit instanceof LandAirMek landAirMek)
              && landAirMek.getLAMType() == LandAirMek.LAM_STANDARD && mode == 1);
    }

    private boolean heightApplies() {
        return allMatch(unit -> unit.getMovementMode() == EntityMovementMode.VTOL) || airMekApplies()
              || allMatch(unit -> unit instanceof ProtoMek && unit.getMovementMode() == EntityMovementMode.WIGE);
    }

    private void applyCrew(Entity unit) {
        Crew crew = unit.getCrew();
        boolean rpg = rpgGunnery();
        for (Member member : members) {
            int slot = member.slot;
            if (slot >= crew.getSlotCount()) {
                continue;
            }
            Gender gender = member.gender == Gender.RANDOMIZE ? crew.getGender(slot) : member.gender;
            int gunnery = member.value(Skill.GUNNERY);
            int gunneryL = member.value(Skill.GUNNERY_L);
            int gunneryM = member.value(Skill.GUNNERY_M);
            int gunneryB = member.value(Skill.GUNNERY_B);
            int gunneryAero = member.value(Skill.GUNNERY_AERO);
            int gunneryAeroL = member.value(Skill.GUNNERY_AERO_L);
            int gunneryAeroM = member.value(Skill.GUNNERY_AERO_M);
            int gunneryAeroB = member.value(Skill.GUNNERY_AERO_B);
            int rounded = (int) Math.round((gunneryL + gunneryB + gunneryM) / 3.0);
            if (crew instanceof LAMPilot pilot) {
                if (rpg) {
                    pilot.setGunneryMekL(gunneryL);
                    pilot.setGunneryMekB(gunneryB);
                    pilot.setGunneryMekM(gunneryM);
                    pilot.setGunneryMek(rounded);
                    pilot.setGunneryAeroL(gunneryAeroL);
                    pilot.setGunneryAeroB(gunneryAeroB);
                    pilot.setGunneryAeroM(gunneryAeroM);
                    pilot.setGunneryAero((int) Math.round((gunneryAeroL + gunneryAeroB + gunneryAeroM) / 3.0));
                } else {
                    pilot.setGunneryMekL(gunnery);
                    pilot.setGunneryMekB(gunnery);
                    pilot.setGunneryMekM(gunnery);
                    pilot.setGunneryMek(gunnery);
                    pilot.setGunneryAeroL(gunneryAero);
                    pilot.setGunneryAeroB(gunneryAero);
                    pilot.setGunneryAeroM(gunneryAero);
                    pilot.setGunneryAero(gunneryAero);
                }
                pilot.setPilotingMek(member.value(Skill.PILOTING));
                pilot.setPilotingAero(member.value(Skill.PILOTING_AERO));
            } else {
                if (rpg) {
                    crew.setGunneryL(gunneryL, slot);
                    crew.setGunneryB(gunneryB, slot);
                    crew.setGunneryM(gunneryM, slot);
                    crew.setGunnery(rounded, slot);
                } else {
                    crew.setGunnery(gunnery, slot);
                    crew.setGunneryL(gunnery, slot);
                    crew.setGunneryB(gunnery, slot);
                    crew.setGunneryM(gunnery, slot);
                }
                crew.setPiloting(member.value(Skill.PILOTING), slot);
            }
            if (options.booleanOption(OptionsConstants.RPG_ARTILLERY_SKILL)) {
                crew.setArtillery(member.value(Skill.ARTILLERY), slot);
            } else {
                crew.setArtillery(crew.getGunnery(slot), slot);
            }
            member.applyAptitudes(crew);
            crew.setMissing(member.missing, slot);
            crew.setToughness(member.value(Skill.TOUGHNESS), slot);
            crew.setCrewFatigue(member.value(Skill.FATIGUE), slot);
            crew.setName(member.name, slot);
            crew.setNickname(member.nickname, slot);
            crew.setGender(gender, slot);
            crew.setClanPilot(member.clanPilot, slot);
            // Personal equipment is written only when it shows, so a hidden choice never clears what was recorded
            if (member.personalEquipment) {
                crew.setArmorKitName(member.armorKit, slot);
                crew.setSidearmName(member.sidearm, slot);
                crew.setSmallArms(member.smallArms(), slot);
            }
            crew.setPortrait(member.portrait.clone(), slot);
            if (member.backup >= 0) {
                if (slot == crew.getCrewType().getPilotPos()) {
                    crew.setBackupPilotPos(member.backup);
                } else if (slot == crew.getCrewType().getGunnerPos()) {
                    crew.setBackupGunnerPos(member.backup);
                }
            }
            Entity other = member.swap > 0 ? client.getGame().getEntity(member.swaps.get(member.swap - 1).id()) : null;
            if (other != null) {
                short number = unit.getUnitNumber();
                unit.setUnitNumber(other.getUnitNumber());
                other.setUnitNumber(number);
                client.sendUpdateEntity(other);
            }
            // Swapped once: a second apply would swap back
            member.swap = 0;
        }
        crew.setInitBonus(value(Field.INIT));
        crew.setCommandBonus(value(Field.COMMAND));
        unit.setCommander(commander);
        if (abilities != null) {
            abilities.apply(unit);
        }
        if (quirks != null) {
            quirks.apply(unit);
        }
        if (repairs != null) {
            repairs.apply(unit);
        }
        if (equipment != null) {
            warnings.addAll(equipment.apply(unit, client));
        }
        if (abilities != null) {
            abilities.applyProsthetics(unit);
        }
        if (unit instanceof BattleArmor battleArmor) {
            // The internals follow the dermal armor ability
            battleArmor.setInternal(unit.hasAbility(OptionsConstants.MD_DERMAL_ARMOR) ? 2 : 1);
        }
        if (canHaveEiInterface(unit)) {
            setEiInterface(unit, unit.hasAbility(OptionsConstants.MD_EI_IMPLANT));
        }
    }

    private void applyDeployment(Entity unit, boolean aero, boolean ship, boolean height, int elevation, int velocity,
          int altitude, int fuel, boolean quadVee, boolean lam) {
        unit.setHidden(hidden);
        if (showScanTarget) {
            unit.setDesignatedScanTarget(scanTarget);
        }
        setStealth(unit, stealth);
        if (offBoard) {
            unit.setOffBoard(distance, DIRECTIONS.get(direction));
        } else {
            unit.setOffBoard(0, OffBoardDirection.NONE);
        }
        if ((aero || ship) && unit instanceof IAero flyer) {
            flyer.setCurrentVelocity(velocity);
            flyer.setNextVelocity(velocity);
            flyer.setCurrentFuel(fuel);
            if (!space) {
                // In an atmosphere altitude 0 need not mean landed: say which, for the deployment's terrain checks
                if (altitude == 0) {
                    flyer.land();
                } else {
                    flyer.liftOff(altitude);
                }
            }
        }
        if (height) {
            unit.setElevation(elevation);
        }
        if (quadVee) {
            unit.setConversionMode(mode);
        } else if (lam) {
            if (mode == 2) {
                unit.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);
            } else if (mode == 1) {
                unit.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);
                unit.setConversionMode(((LandAirMek) unit).getLAMType() == LandAirMek.LAM_BIMODAL
                      ? LandAirMek.CONV_MODE_FIGHTER : LandAirMek.CONV_MODE_AIR_MEK);
            } else {
                unit.setConversionMode(LandAirMek.CONV_MODE_MEK);
            }
        }
        unit.setStartingPos(zone);
        unit.setDeployRound(round);
        unit.setStartingOffset(value(Field.OFFSET));
        unit.setStartingWidth(value(Field.WIDTH));
        unit.setStartingAnyNWx(Math.min(anyNwX, anySeX) - 1);
        unit.setStartingAnyNWy(Math.min(anyNwY, anySeY) - 1);
        unit.setStartingAnySEx(Math.max(anyNwX, anySeX) - 1);
        unit.setStartingAnySEy(Math.max(anyNwY, anySeY) - 1);
        if (shutdown && options.booleanOption(OptionsConstants.RPG_BEGIN_SHUTDOWN)) {
            unit.performManualShutdown();
        } else {
            unit.performManualStartup();
        }
        // LAMs flying as fighters or airborne AirMeks ignore prone and hull-down
        if (!lam || (!aero && unit.getElevation() == 0)) {
            unit.setProne(prone);
            unit.setHullDown(hullDown);
        }
        if ((unit instanceof Infantry infantry) && !infantry.isMechanized()
              && options.booleanOption(OptionsConstants.ADVANCED_TAC_OPS_DIG_IN)) {
            infantry.setDugIn(dugIn ? Infantry.DUG_IN_COMPLETE : Infantry.DUG_IN_NONE);
        }
    }

    private static void setStealth(Entity unit, boolean on) {
        EquipmentMode wanted = EquipmentMode.getMode(on ? "On" : "Off");
        for (MiscMounted mounted : unit.getMiscEquipment(MiscType.F_STEALTH)) {
            if (mounted.curMode() == wanted) {
                continue;
            }
            mounted.setMode(on ? 1 : 0);
            mounted.newRound(-1);
        }
    }

    /** Whether the unit may carry an EI interface: a Clan or mixed tech Mek or battle armor, in a year it exists. */
    private boolean canHaveEiInterface(Entity unit) {
        EquipmentType interfaceType = EquipmentType.get("EIInterface");
        int year = client.getGame().getOptions().intOption(OptionsConstants.ALLOWED_YEAR);
        int introduced = (interfaceType != null) ? interfaceType.getIntroductionDate(true) : 3040;
        if (year < introduced || unit.isProtoMek()) {
            return false;
        }
        if (!unit.isMek() && !(unit instanceof BattleArmor)) {
            return false;
        }
        return unit.isClan() || unit.isMixedTech();
    }

    /** Adds the EI interface when the pilot has the implant, and takes it out when they have not. */
    private static void setEiInterface(Entity unit, boolean enabled) {
        boolean has = unit.hasEiCockpit();
        if (enabled && !has) {
            EquipmentType interfaceType = EquipmentType.get("EIInterface");
            if (interfaceType != null) {
                try {
                    Mounted<?> mounted = unit.addEquipment(interfaceType, Entity.LOC_NONE);
                    mounted.setMode(MiscType.MODE_EI_ON);
                } catch (LocationFullException ignored) {
                    // It takes no slots
                }
            }
        } else if (!enabled && has) {
            List<Mounted<?>> removed = new ArrayList<>();
            for (Mounted<?> mounted : unit.getEquipment()) {
                if (mounted.getType() instanceof MiscType && mounted.getType().hasFlag(MiscType.F_EI_INTERFACE)) {
                    removed.add(mounted);
                }
            }
            unit.getEquipment().removeAll(removed);
            unit.getMisc().removeAll(removed);
        }
    }

    // endregion

    // region Off board reach

    private static boolean trainCarriesArtillery(Entity unit) {
        if (carriesOffBoardWeapon(unit)) {
            return true;
        }
        for (int towed : unit.getAllTowedUnits()) {
            Entity trailer = (unit.getGame() == null) ? null : unit.getGame().getEntity(towed);
            if (trailer != null && carriesOffBoardWeapon(trailer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean carriesOffBoardWeapon(Entity unit) {
        for (WeaponMounted weapon : unit.getWeaponList()) {
            WeaponType type = weapon.getType();
            if (type.hasFlag(WeaponType.F_ARTILLERY) || type instanceof CapitalMissileBayWeapon) {
                return true;
            }
        }
        return false;
    }

    /** The farthest the units' longest reaching weapon lets them stand off board, in hexes, as the Swing slider. */
    private int offBoardReach() {
        int most = 0;
        for (Entity unit : units) {
            for (WeaponMounted weapon : unit.getWeaponList()) {
                WeaponType type = weapon.getType();
                int hexes = 0;
                if (type.hasFlag(WeaponType.F_ARTILLERY)) {
                    int sheets = (type instanceof ArtilleryBayWeapon) ? shortestInBay(weapon) : type.getLongRange();
                    int reach = ArtilleryRange.extendedRangeInHexes(sheets,
                          ArtilleryRange.isExtendedByObliqueArtilleryman(unit, type));
                    hexes = Math.max(reach - Board.DEFAULT_BOARD_HEIGHT, 0);
                } else if (type.isCapital() || type.isSubCapital()) {
                    int sheets = switch (type.getMaxRange(weapon)) {
                        case WeaponType.RANGE_EXT -> 50;
                        case WeaponType.RANGE_LONG -> 40;
                        case WeaponType.RANGE_MED -> 24;
                        case WeaponType.RANGE_SHORT -> 12;
                        default -> 0;
                    };
                    hexes = sheets * Board.DEFAULT_BOARD_HEIGHT;
                }
                most = Math.max(most, hexes);
            }
        }
        return most;
    }

    private static int shortestInBay(WeaponMounted bay) {
        int shortest = 150;
        for (WeaponMounted weapon : bay.getBayWeapons()) {
            shortest = Math.min(shortest, weapon.getType().getLongRange());
        }
        return shortest;
    }

    // endregion

    // region Generators

    /** Gives the crew member a random gender and a name for it, as the Swing Random Name; Swing thread. */
    public void randomName(int slot) {
        Member member = members.get(slot);
        member.gender = RandomGenderGenerator.generate();
        member.name = RandomNameGenerator.getInstance().generate(member.gender, member.clanPilot,
              units.get(0).getOwner().getName());
    }

    /** Gives the crew member a random callsign; Swing thread. */
    public void randomCallsign(int slot) {
        members.get(slot).nickname = RandomCallsignGenerator.getInstance().generate();
    }

    /**
     * Rolls the crew member's gunnery and piloting with the player's skill generator, and a LAM pilot's aerospace
     * skills with a second roll; the RPG gunnery skills take the rolled gunnery too. Swing thread.
     */
    public void randomSkills(int slot) {
        Member member = members.get(slot);
        Entity unit = units.get(0);
        int[] skills = human.getSkillGenerator().generateRandomSkills(unit);
        for (Skill skill : List.of(Skill.GUNNERY, Skill.GUNNERY_L, Skill.GUNNERY_M, Skill.GUNNERY_B)) {
            member.text(skill, Integer.toString(skills[0]));
        }
        member.text(Skill.PILOTING, Integer.toString(skills[1]));
        if (unit.getCrew() instanceof LAMPilot) {
            skills = human.getSkillGenerator().generateRandomSkills(unit);
            for (Skill skill : List.of(Skill.GUNNERY_AERO, Skill.GUNNERY_AERO_L, Skill.GUNNERY_AERO_M,
                  Skill.GUNNERY_AERO_B)) {
                member.text(skill, Integer.toString(skills[0]));
            }
            member.text(Skill.PILOTING_AERO, Integer.toString(skills[1]));
        }
    }

    // endregion

    /** One crew member of a single unit: their identity, skills, aptitudes and equipment as the view edits them. */
    public final class Member {
        private final int slot;
        private final String role;
        private final String pilotingKey;
        private final boolean lamPilot;
        private final boolean personalEquipment;
        private final Map<Skill, String> skills = new EnumMap<>(Skill.class);
        private final List<Choice> armorKits = new ArrayList<>();
        private final List<Choice> sidearms = new ArrayList<>();
        private final List<Integer> backups = new ArrayList<>();
        private final List<Swap> swaps = new ArrayList<>();
        private final String callsign;
        private String name;
        private String nickname;
        private Gender gender = Gender.RANDOMIZE;
        private boolean missing;
        private boolean clanPilot;
        private boolean aptitudePiloting;
        private boolean aptitudePilotingAero;
        private boolean aptitudeGunnery;
        private boolean aptitudeGunneryAero;
        private boolean aptitudeArtillery;
        private boolean aptitudeSmallArms;
        private @Nullable String armorKit;
        private @Nullable String sidearm;
        private Portrait portrait;
        private int backup = -1;
        private int swap;

        private Member(Entity unit, int slot, Game game) {
            this.slot = slot;
            Crew crew = unit.getCrew();
            role = crew.getCrewType().getRoleName(slot);
            pilotingKey = (unit instanceof Tank) ? "CustomMekDialog.labDriving"
                  : (unit instanceof Infantry) ? "CustomMekDialog.labAntiMek" : "CustomMekDialog.labPiloting";
            lamPilot = crew instanceof LAMPilot;
            personalEquipment = CrewArmorKitRules.isRuleInPlay(game) && CrewArmorKitRules.canWearArmorKit(unit);
            if (crew instanceof LAMPilot pilot) {
                skills.put(Skill.PILOTING, Integer.toString(pilot.getPilotingMek()));
                skills.put(Skill.PILOTING_AERO, Integer.toString(pilot.getPilotingAero()));
                skills.put(Skill.GUNNERY_L, Integer.toString(pilot.getGunneryMekL()));
                skills.put(Skill.GUNNERY_M, Integer.toString(pilot.getGunneryMekM()));
                skills.put(Skill.GUNNERY_B, Integer.toString(pilot.getGunneryMekB()));
                skills.put(Skill.GUNNERY, Integer.toString(pilot.getGunneryMek()));
                skills.put(Skill.GUNNERY_AERO_L, Integer.toString(pilot.getGunneryAeroL()));
                skills.put(Skill.GUNNERY_AERO_M, Integer.toString(pilot.getGunneryAeroM()));
                skills.put(Skill.GUNNERY_AERO_B, Integer.toString(pilot.getGunneryAeroB()));
                skills.put(Skill.GUNNERY_AERO, Integer.toString(pilot.getGunneryAero()));
                aptitudePilotingAero = pilot.isHasNaturalAptitudePilotingAero();
                aptitudeGunneryAero = pilot.isHasNaturalAptitudeGunneryAero();
            } else {
                skills.put(Skill.PILOTING, Integer.toString(crew.getPiloting(slot)));
                skills.put(Skill.PILOTING_AERO, "0");
                skills.put(Skill.GUNNERY_L, Integer.toString(crew.getGunneryL(slot)));
                skills.put(Skill.GUNNERY_M, Integer.toString(crew.getGunneryM(slot)));
                skills.put(Skill.GUNNERY_B, Integer.toString(crew.getGunneryB(slot)));
                skills.put(Skill.GUNNERY, Integer.toString(crew.getGunnery(slot)));
                for (Skill skill : List.of(Skill.GUNNERY_AERO_L, Skill.GUNNERY_AERO_M, Skill.GUNNERY_AERO_B,
                      Skill.GUNNERY_AERO)) {
                    skills.put(skill, "0");
                }
            }
            skills.put(Skill.ARTILLERY, Integer.toString(crew.getArtillery(slot)));
            skills.put(Skill.TOUGHNESS, Integer.toString(crew.getToughness(slot)));
            skills.put(Skill.FATIGUE, Integer.toString(crew.getCrewFatigue(slot)));
            name = crew.getName(slot);
            nickname = crew.getNickname(slot);
            missing = crew.isMissing(slot);
            clanPilot = crew.isClanPilot(slot);
            aptitudePiloting = crew.isHasNaturalAptitudePiloting(slot);
            aptitudeGunnery = crew.isHasNaturalAptitudeGunnery(slot);
            aptitudeArtillery = crew.isHasNaturalAptitudeArtillery(slot);
            aptitudeSmallArms = crew.isHasNaturalAptitudeSmallArms(slot);
            portrait = crew.getPortrait(slot);
            if (personalEquipment) {
                readPersonalEquipment(unit, crew, game);
            } else {
                skills.put(Skill.SMALL_ARMS, "");
            }
            if (crew.getSlotCount() > 2) {
                for (int other = 0; other < crew.getSlotCount(); other++) {
                    if (other != slot) {
                        backups.add(other);
                    }
                }
                if (slot == crew.getCrewType().getPilotPos()) {
                    backup = crew.getBackupPilotPos();
                } else if (slot == crew.getCrewType().getGunnerPos()) {
                    backup = crew.getBackupGunnerPos();
                }
                if (!backups.contains(backup)) {
                    backup = backups.isEmpty() ? -1 : backups.get(0);
                }
            }
            char start = PreferenceManager.getClientPreferences().getUnitStartChar();
            if (unit instanceof ProtoMek) {
                callsign = (char) (start + unit.getUnitNumber()) + "-" + unit.getId();
                Iterator<Entity> others = game.getSelectedEntities(other -> other instanceof ProtoMek
                      && other.getOwnerId() == unit.getOwnerId() && other.getUnitNumber() != unit.getUnitNumber());
                while (others.hasNext()) {
                    Entity other = others.next();
                    swaps.add(new Swap(other.getId(), other.getDisplayName() + " ("
                          + (char) (start + other.getUnitNumber()) + '-' + other.getId() + ')'));
                }
            } else {
                callsign = "";
            }
        }

        private void readPersonalEquipment(Entity unit, Crew crew, Game game) {
            armorKits.add(new Choice("", null));
            for (EquipmentType kit : CrewArmorKitRules.availableArmorKits()) {
                if (CrewArmorKitRules.isAvailableIn(kit, unit, game)) {
                    armorKits.add(new Choice(kit.getName(), kit.getInternalName()));
                }
            }
            String worn = crew.getArmorKitName(slot);
            EquipmentType wornKit = (worn == null) ? null : EquipmentType.get(worn);
            armorKit = (wornKit != null && hasChoice(armorKits, wornKit.getInternalName()))
                  ? wornKit.getInternalName() : null;
            sidearms.add(new Choice("", null));
            for (InfantryWeapon weapon : CrewSidearmRules.availableSidearms()) {
                if (CrewSidearmRules.isAvailableIn(weapon, unit, game)) {
                    sidearms.add(new Choice(weapon.getName(), weapon.getInternalName()));
                }
            }
            String carried = crew.getSidearmName(slot);
            if (carried == null || carried.isBlank()) {
                carried = CrewSidearmRules.defaultSidearmName(unit);
            }
            EquipmentType carriedType = (carried == null) ? null : EquipmentType.get(carried);
            sidearm = (carriedType != null && hasChoice(sidearms, carriedType.getInternalName()))
                  ? carriedType.getInternalName() : null;
            int shown = crew.hasSmallArms(slot) ? crew.getSmallArms(slot) : CrewSidearmRules.defaultSmallArms(unit);
            skills.put(Skill.SMALL_ARMS, shown == Crew.SMALL_ARMS_UNSET ? "" : Integer.toString(shown));
        }

        private static boolean hasChoice(List<Choice> choices, String internalName) {
            for (Choice choice : choices) {
                if (internalName.equals(choice.internalName())) {
                    return true;
                }
            }
            return false;
        }

        public int slot() {
            return slot;
        }

        /** The member's role in the crew, such as Pilot or Gunner. */
        public String role() {
            return role;
        }

        /** The message key of the piloting skill's name: driving for vehicles, anti-Mek for infantry. */
        public String pilotingKey() {
            return pilotingKey;
        }

        /** Whether the member is a LAM pilot, with aerospace skills of their own. */
        public boolean lamPilot() {
            return lamPilot;
        }

        public String text(Skill skill) {
            return skills.getOrDefault(skill, "");
        }

        public void text(Skill skill, String text) {
            skills.put(skill, text == null ? "" : text);
        }

        /** Whether the skill shows, as the game's options and the unit have it. */
        public boolean shows(Skill skill) {
            boolean rpg = rpgGunnery();
            return switch (skill) {
                case GUNNERY -> !rpg;
                case GUNNERY_L, GUNNERY_M, GUNNERY_B -> rpg;
                case GUNNERY_AERO -> lamPilot && !rpg;
                case GUNNERY_AERO_L, GUNNERY_AERO_M, GUNNERY_AERO_B -> lamPilot && rpg;
                case PILOTING -> true;
                case PILOTING_AERO -> lamPilot;
                case ARTILLERY -> options.booleanOption(OptionsConstants.RPG_ARTILLERY_SKILL);
                case TOUGHNESS -> options.booleanOption(OptionsConstants.RPG_TOUGHNESS);
                case FATIGUE -> options.booleanOption(OptionsConstants.ADVANCED_TAC_OPS_FATIGUE);
                case SMALL_ARMS -> personalEquipment;
            };
        }

        /** Whether the member's details may be changed: editable, and the member is present. */
        public boolean enabled() {
            return editable && !missing;
        }

        /** The skill's problem, or empty; only shown skills are checked. */
        public Optional<Problem> error(Skill skill) {
            if (!shows(skill)) {
                return Optional.empty();
            }
            String text = text(skill);
            if (skill == Skill.SMALL_ARMS && text.isBlank()) {
                return Optional.empty();
            }
            Integer value = parse(text);
            if (value == null) {
                return Optional.of(new Problem("Lobby.settings.wholeNumber", List.of()));
            }
            return switch (skill) {
                case TOUGHNESS -> Optional.empty();
                case FATIGUE -> value < 0 ? Optional.of(new Problem("Lobby.settings.notNegative", List.of()))
                      : Optional.empty();
                case SMALL_ARMS -> range(value, 0, Crew.MAX_SKILL);
                default -> range(value, 0, MAX_SKILL);
            };
        }

        private int value(Skill skill) {
            Integer value = parse(skills.get(skill));
            return value == null ? 0 : value;
        }

        private int smallArms() {
            String text = text(Skill.SMALL_ARMS);
            return text.isBlank() ? Crew.SMALL_ARMS_UNSET : value(Skill.SMALL_ARMS);
        }

        public String name() {
            return name;
        }

        public void name(String value) {
            name = value == null ? "" : value;
        }

        public String nickname() {
            return nickname;
        }

        public void nickname(String value) {
            nickname = value == null ? "" : value;
        }

        public Gender gender() {
            return gender;
        }

        /** Whether the absent checkbox shows: for a crew of several. */
        public boolean showsMissing() {
            return members.size() > 1;
        }

        /** Whether the member may be marked absent or present: one member at least stays. */
        public boolean missingEnabled() {
            int present = 0;
            for (Member member : members) {
                present += member.missing ? 0 : 1;
            }
            return missing || present > 1;
        }

        public boolean missing() {
            return missing;
        }

        public void missing(boolean value) {
            missing = value;
        }

        public boolean clanPilot() {
            return clanPilot;
        }

        public void clanPilot(boolean value) {
            clanPilot = value;
        }

        /** Whether the Natural Aptitude for {@code skill} shows: piloting and gunnery always, the rest as used. */
        public boolean showsAptitude(Skill skill) {
            return switch (skill) {
                case PILOTING, GUNNERY -> true;
                case PILOTING_AERO, GUNNERY_AERO -> lamPilot;
                case ARTILLERY -> options.booleanOption(OptionsConstants.RPG_ARTILLERY_SKILL);
                case SMALL_ARMS -> personalEquipment;
                default -> false;
            };
        }

        public boolean aptitude(Skill skill) {
            return switch (skill) {
                case PILOTING -> aptitudePiloting;
                case PILOTING_AERO -> aptitudePilotingAero;
                case GUNNERY -> aptitudeGunnery;
                case GUNNERY_AERO -> aptitudeGunneryAero;
                case ARTILLERY -> aptitudeArtillery;
                case SMALL_ARMS -> aptitudeSmallArms;
                default -> false;
            };
        }

        public void aptitude(Skill skill, boolean value) {
            switch (skill) {
                case PILOTING -> aptitudePiloting = value;
                case PILOTING_AERO -> aptitudePilotingAero = value;
                case GUNNERY -> aptitudeGunnery = value;
                case GUNNERY_AERO -> aptitudeGunneryAero = value;
                case ARTILLERY -> aptitudeArtillery = value;
                case SMALL_ARMS -> aptitudeSmallArms = value;
                default -> { }
            }
        }

        private void applyAptitudes(Crew crew) {
            crew.setHasNaturalAptitudePiloting(aptitudePiloting, slot);
            crew.setHasNaturalAptitudeGunnery(aptitudeGunnery, slot);
            // Without the artillery skill, artillery is fired with gunnery and its aptitude follows
            crew.setHasNaturalAptitudeArtillery(showsAptitude(Skill.ARTILLERY) ? aptitudeArtillery : aptitudeGunnery,
                  slot);
            if (personalEquipment) {
                crew.setHasNaturalAptitudeSmallArms(aptitudeSmallArms, slot);
            }
            if (crew instanceof LAMPilot pilot) {
                pilot.setHasNaturalAptitudePilotingAero(aptitudePilotingAero);
                pilot.setHasNaturalAptitudeGunneryAero(aptitudeGunneryAero);
            }
        }

        /** Whether the armor kit, sidearm and Small Arms show: the rule is on and the crew can leave on foot. */
        public boolean showsPersonalEquipment() {
            return personalEquipment;
        }

        /** The armor kits to choose, None first with no internal name. */
        public List<Choice> armorKits() {
            return List.copyOf(armorKits);
        }

        public @Nullable String armorKit() {
            return armorKit;
        }

        public void armorKit(@Nullable String internalName) {
            armorKit = internalName;
        }

        /** The sidearms to choose, None first with no internal name. */
        public List<Choice> sidearms() {
            return List.copyOf(sidearms);
        }

        public @Nullable String sidearm() {
            return sidearm;
        }

        public void sidearm(@Nullable String internalName) {
            sidearm = internalName;
        }

        public Portrait portrait() {
            return portrait;
        }

        public void portrait(Portrait value) {
            portrait = value;
        }

        /** The other slots that may back this member up, for a pilot or gunner in a crew of more than two. */
        public List<Integer> backups() {
            return isBackedUp() ? List.copyOf(backups) : List.of();
        }

        private boolean isBackedUp() {
            Crew crew = units.get(0).getCrew();
            return !backups.isEmpty() && (slot == crew.getCrewType().getPilotPos()
                  || slot == crew.getCrewType().getGunnerPos());
        }

        /** Whether this member is the pilot, whose backup is the backup pilot; else the gunner. */
        public boolean pilot() {
            return slot == units.get(0).getCrew().getCrewType().getPilotPos();
        }

        /** The role name of another slot, for the backup choice. */
        public String roleOf(int other) {
            return units.get(0).getCrew().getCrewType().getRoleName(other);
        }

        public int backup() {
            return backup;
        }

        public void backup(int value) {
            backup = value;
        }

        /** The ProtoMek's callsign, its point letter and id; empty for other units. */
        public String callsign() {
            return callsign;
        }

        /** The ProtoMeks of the owner's other points to swap unit numbers with. */
        public List<Swap> swaps() {
            return List.copyOf(swaps);
        }

        /** The chosen swap: 0 for none, else one more than its index in {@link #swaps}. */
        public int swap() {
            return swap;
        }

        public void swap(int value) {
            swap = Math.max(0, Math.min(swaps.size(), value));
        }
    }
}
