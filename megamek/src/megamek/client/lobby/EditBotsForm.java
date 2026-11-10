/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.Princess;
import megamek.client.bot.princess.PrincessException;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.logging.MMLogger;

/**
 * The game's bots and dropped-out players, and what to do with each, as the Edit Bots card fills it in: a ghost may be
 * replaced by Princess (or CASPAR, when that experimental bot is switched on), a local bot may have its settings edited
 * or be kicked, and a remote bot may be kicked. Each bot that will run here carries the settings it will run with,
 * starting from its current ones or, for a ghost that was a bot before the save, the saved ones.
 *
 * <p>The result is what the Swing {@code EditBotsDialog} returned: the ghosts to replace with their settings and AI,
 * the local bots whose settings changed, and the bots to kick.</p>
 */
public final class EditBotsForm {
    private static final MMLogger LOGGER = MMLogger.create(EditBotsForm.class);

    /** What a seat is. */
    public enum Kind {
        /** A player who dropped out. */
        GHOST,
        /** A Princess (or CASPAR) bot running on this client. */
        LOCAL_BOT,
        /** A local bot of another kind, which can only be shown. */
        LOCAL_OTHER,
        /** A bot running on another client. */
        REMOTE_BOT
    }

    /** What to do with a seat. */
    public enum Action {
        KEEP("EditBotsDialog.optionNone"),
        KEEP_GHOST("EditBotsDialog.optionDoNotReplace"),
        REPLACE_PRINCESS("EditBotsDialog.optionReplace"),
        REPLACE_CASPAR("EditBotsDialog.optionReplaceCaspar"),
        EDIT("EditBotsDialog.optionEdit"),
        KICK("EditBotsDialog.optionKick");

        private final String key;

        Action(String key) {
            this.key = key;
        }

        /** The action's message key. */
        public String key() {
            return key;
        }
    }

    /**
     * What a seat is before anything is chosen, read from the client.
     *
     * @param current the settings a local bot runs with, or a default for anybody else
     * @param saved   the settings the save remembers for this seat, or null
     * @param type    the AI a local bot runs, or the one the save remembers for a ghost, or null
     * @param detail  a local bot of another kind's class name, or null
     */
    record Seat(String name, String label, Kind kind, BehaviorSettings current, @Nullable BehaviorSettings saved,
          @Nullable AIType type, @Nullable String detail) { }

    /** One seat and what is chosen for it. */
    public static final class Row {
        private final Seat seat;
        private final List<Action> actions;
        private Action action;
        private BehaviorSettings settings;

        private Row(Seat seat, boolean caspar) {
            this.seat = seat;
            settings = seat.current();
            actions = switch (seat.kind()) {
                case GHOST -> caspar
                      ? List.of(Action.KEEP_GHOST, Action.REPLACE_PRINCESS, Action.REPLACE_CASPAR)
                      : List.of(Action.KEEP_GHOST, Action.REPLACE_PRINCESS);
                case LOCAL_BOT -> List.of(Action.KEEP, Action.KICK, Action.EDIT);
                case REMOTE_BOT -> List.of(Action.KEEP, Action.KICK);
                case LOCAL_OTHER -> List.of();
            };
            action = actions.isEmpty() ? Action.KEEP : actions.get(0);
            if (seat.kind() == Kind.GHOST && seat.saved() != null) {
                // A ghost with saved bot settings was a bot before the save, so it is replaced by default with the
                // kind of bot the save remembers; older saves fall back on CASPAR when it is switched on.
                boolean casparLeads = caspar && (seat.type() == null || seat.type() == AIType.CASPAR);
                action = casparLeads ? Action.REPLACE_CASPAR : Action.REPLACE_PRINCESS;
                settings = copy(seat.saved(), settings);
            }
        }

        public String name() {
            return seat.name();
        }

        /** The player as the game names them. */
        public String label() {
            return seat.label();
        }

        public Kind kind() {
            return seat.kind();
        }

        /** What runs this seat, for its type tag: "Ghost", "Local Princess", "Remote bot" and so on. */
        public String typeText() {
            return switch (seat.kind()) {
                case GHOST -> Messages.getString("EditBotsDialog.ghost");
                case LOCAL_BOT -> Messages.getString("EditBotsDialog.localBot",
                      seat.type() == AIType.CASPAR ? "CASPAR" : "Princess");
                case LOCAL_OTHER -> Messages.getString("EditBotsDialog.localBot",
                      seat.detail() == null ? "Null Bot" : seat.detail());
                case REMOTE_BOT -> Messages.getString("EditBotsDialog.remoteBot");
            };
        }

        /** The actions this seat offers, the first the default; empty for a seat that can only be shown. */
        public List<Action> actions() {
            return actions;
        }

        public Action action() {
            return action;
        }

        public void action(Action value) {
            if (actions.contains(value)) {
                action = value;
            }
        }

        /** Whether the bot settings apply: a ghost to be replaced, or a local bot that stays. */
        public boolean configurable() {
            return switch (seat.kind()) {
                case GHOST -> action == Action.REPLACE_PRINCESS || action == Action.REPLACE_CASPAR;
                case LOCAL_BOT -> action != Action.KICK;
                default -> false;
            };
        }

        /** The AI the bot settings are for: a ghost's chosen replacement or the running bot's own. */
        public AIType configuredType() {
            if (seat.kind() == Kind.GHOST) {
                return action == Action.REPLACE_CASPAR ? AIType.CASPAR : AIType.PRINCESS;
            }
            return seat.type() == null ? AIType.PRINCESS : seat.type();
        }

        /** The settings the bot will run with. */
        public BehaviorSettings settings() {
            return settings;
        }

        /** Takes the settings the bot setup confirmed; a local bot is then set to Edit, as the Swing dialog did. */
        public void configured(BehaviorSettings value) {
            settings = value;
            if (seat.kind() == Kind.LOCAL_BOT) {
                action = Action.EDIT;
            }
        }

        /** Whether the save remembers settings for this seat. */
        public boolean hasSaved() {
            return seat.saved() != null;
        }

        /** Whether the saved settings may be restored now: the save has some and the bot settings apply. */
        public boolean restorable() {
            return hasSaved() && configurable();
        }

        /** Goes back to the saved settings, leaving the action as it is, as the Swing dialog did. */
        public void restore() {
            if (seat.saved() != null) {
                settings = copy(seat.saved(), settings);
            }
        }
    }

    /** What the card's OK hands over, as the Swing dialog's getters did; never null, possibly empty. */
    public record Result(Map<String, BehaviorSettings> newBots, Map<String, AIType> newBotTypes,
          Map<String, BehaviorSettings> changedBots, Set<String> kickBots) {
        public boolean isEmpty() {
            return newBots.isEmpty() && changedBots.isEmpty() && kickBots.isEmpty();
        }
    }

    private final List<Row> rows = new ArrayList<>();

    EditBotsForm(List<Seat> seats, boolean caspar) {
        for (Seat seat : seats) {
            rows.add(new Row(seat, caspar));
        }
    }

    /**
     * Swing thread: the game's ghosts and bots in player id order, read from the client as the Swing dialog reads
     * them.
     *
     * @param caspar whether the experimental CASPAR bot is offered as a replacement
     */
    public static EditBotsForm of(Client client, boolean caspar) {
        Game game = client.getGame();
        Map<String, BehaviorSettings> saved = game.getBotSettings();
        Map<String, AIType> savedTypes = game.getBotTypes();
        List<Seat> seats = new ArrayList<>();
        List<Player> players = new ArrayList<>(game.getPlayersList());
        players.sort(Comparator.comparingInt(Player::getId));
        for (Player player : players) {
            if (!player.isGhost() && !player.isBot()) {
                continue;
            }
            String name = player.getName();
            BehaviorSettings savedSettings = saved == null ? null : saved.get(name);
            if (player.isGhost()) {
                seats.add(new Seat(name, player.toString(), Kind.GHOST, new BehaviorSettings(), savedSettings,
                      savedTypes == null ? null : savedTypes.get(name), null));
            } else if (client.isLocalBot(player)) {
                AbstractClient bot = client.getBotClient(player);
                if (bot instanceof Princess princess) {
                    seats.add(new Seat(name, player.toString(), Kind.LOCAL_BOT,
                          copy(princess.getBehaviorSettings(), new BehaviorSettings()), savedSettings,
                          princess.getAIType(), null));
                } else {
                    seats.add(new Seat(name, player.toString(), Kind.LOCAL_OTHER, new BehaviorSettings(), null, null,
                          bot == null ? null : bot.getClass().getSimpleName()));
                }
            } else {
                seats.add(new Seat(name, player.toString(), Kind.REMOTE_BOT, new BehaviorSettings(), null, null,
                      null));
            }
        }
        return new EditBotsForm(seats, caspar);
    }

    /** The seats, in player id order. */
    public List<Row> rows() {
        return rows;
    }

    /** What is chosen, as the Swing dialog's getters returned it. */
    public Result result() {
        Map<String, BehaviorSettings> newBots = new HashMap<>();
        Map<String, AIType> newBotTypes = new HashMap<>();
        Map<String, BehaviorSettings> changedBots = new HashMap<>();
        Set<String> kickBots = new HashSet<>();
        for (Row row : rows) {
            switch (row.action()) {
                case REPLACE_PRINCESS, REPLACE_CASPAR -> {
                    newBots.put(row.name(), row.settings());
                    newBotTypes.put(row.name(), row.configuredType());
                }
                case EDIT -> changedBots.put(row.name(), row.settings());
                case KICK -> kickBots.add(row.name());
                default -> {
                }
            }
        }
        return new Result(newBots, newBotTypes, changedBots, kickBots);
    }

    /** A copy of {@code settings}, so the card never changes the saved or running ones, or {@code fallback}. */
    private static BehaviorSettings copy(BehaviorSettings settings, BehaviorSettings fallback) {
        try {
            return settings.getCopy();
        } catch (PrincessException | RuntimeException failure) {
            LOGGER.error(failure, "The bot settings could not be copied");
            return fallback;
        }
    }
}
