/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorChange.Trait;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.bot.princess.PrincessException;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.lobby.BotSetupForm.Presets;
import megamek.common.Player;
import megamek.common.equipment.GunEmplacement;
import megamek.common.game.BotStatus;
import megamek.common.game.Game;
import megamek.common.units.BuildingEntity;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * EDT service for the bot sheet: the bots the local player may command ({@link Player#mayCommandBot}), allied bots
 * first, with the behavior and standing orders each last reported. While a command waits for the bot's report, the
 * row shows what was asked for and which controls are still sending; a bot that does not report within
 * {@link #NO_REPLY_NANOS} shows its reported values again, marked as not replying.
 */
final class GpuBots {
    private static final MMLogger LOGGER = MMLogger.create(GpuBots.class);
    /** How long a command waits for the bot's report before the sheet says the bot did not reply. */
    static final long NO_REPLY_NANOS = 10_000_000_000L;

    /** A part of the sheet a command changes, which shows "Sending…" until the bot reports. */
    enum Control { PERSONALITY, POSTURE, TACTIC, TRAITS, HOLD, RETREAT, PRIORITY, HEX, IGNORE, BLOOD_FEUD, TAG }

    /** An enemy unit the bot may target, prioritize or ignore. {@code turret}: a gun emplacement or building. */
    record Enemy(int id, String name, int ownerId, boolean turret) { }

    /** An enemy player of the bot; {@code dishonored} when the bot already holds a grudge against it. */
    record EnemyPlayer(int id, String name, int rgb, boolean dishonored) { }

    /**
     * The bot's behavior: the personality its settings match (null for a custom mix), its posture, the five indices a
     * player sets, the edge every unit leaves by ({@link CardinalEdge#NONE} while it fights on), the priority targets
     * by unit id (1 shoots first) and the ignored units.
     */
    record Behavior(Personality personality, CombatPosture posture, Map<Trait, Integer> traits,
          CardinalEdge retreat, Map<Integer, Integer> priorities, Set<Integer> ignored) {
        Behavior {
            traits = Map.copyOf(traits);
            priorities = Map.copyOf(priorities);
            ignored = Set.copyOf(ignored);
        }
    }

    /**
     * One bot. {@code allied}: not an enemy of the local player (else a game master commands it). {@code behavior} is
     * null until the bot reports its settings. {@code units}, {@code tag}, {@code enemies} and {@code enemyPlayers} are
     * captured only while the sheet is open (0, false and empty otherwise). {@code sending} lists the controls whose
     * command waits for the bot's report; {@code noReply} says the last one timed out.
     */
    record BotRow(int id, String name, AIType type, int rgb, boolean allied, int units, boolean tag,
          Behavior behavior, BotStatus status, List<Enemy> enemies, List<EnemyPlayer> enemyPlayers,
          Set<Control> sending, boolean noReply) {
        BotRow {
            enemies = List.copyOf(enemies);
            enemyPlayers = List.copyOf(enemyPlayers);
            sending = sending.isEmpty() ? Set.of() : Set.copyOf(sending);
        }

        /** Whether any command waits for the bot's report. */
        boolean isSending() {
            return !sending.isEmpty();
        }
    }

    /**
     * The commandable bots, allied first then by id, the personalities whose presets are stored, and copies of those
     * presets for the personality cards' meters.
     */
    record Snapshot(List<BotRow> bots, List<Personality> personalities, Map<Personality, BehaviorSettings> presets) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), Map.of());

        Snapshot {
            bots = List.copyOf(bots);
            personalities = List.copyOf(personalities);
            presets = Map.copyOf(presets);
        }

        /** The bot with {@code id}, or null when the local player may not command it (any more). */
        BotRow bot(int id) {
            return bots.stream().filter(row -> row.id() == id).findFirst().orElse(null);
        }
    }

    /**
     * What a bot was asked for and not yet reported, shown over its reported values. {@code status} is the report the
     * command went out after: a different instance in the game is the bot's answer, as every command ends with one.
     */
    private static final class Pending {
        final BotStatus status;
        final long sentAt;
        final EnumSet<Control> controls = EnumSet.noneOf(Control.class);
        Personality personality;
        CombatPosture posture;
        final EnumMap<Trait, Integer> traits = new EnumMap<>(Trait.class);
        Boolean hold;
        CardinalEdge retreat;
        final Map<Integer, Integer> priorities = new HashMap<>();

        Pending(BotStatus status, long sentAt) {
            this.status = status;
            this.sentAt = sentAt;
        }
    }

    private final GpuBoardSource source;
    // Resolved at the first commandable bot, so a board without bots never reads the preset file.
    private final Supplier<Presets> presetSource;
    private Presets presets;
    private List<Personality> personalities;
    private Map<Personality, BehaviorSettings> presetCopies = Map.of();
    private final LongSupplier clock;
    // GL thread writes, EDT reads at the next capture, as GpuPlayers' panel flag.
    private volatile boolean sheetOpen;
    private final Map<Integer, Pending> pending = new HashMap<>();
    // The report each bot that timed out had then; the bot's next report clears the mark.
    private final Map<Integer, BotStatus> unanswered = new HashMap<>();
    // The personality each reported settings instance matches; reports replace the instances, so this stays small.
    private final Map<BehaviorSettings, Optional<Personality>> matched = new IdentityHashMap<>();
    private Snapshot snapshot = Snapshot.EMPTY;

    GpuBots(GpuBoardSource source) {
        this(source, Presets::factory, System::nanoTime);
    }

    GpuBots(GpuBoardSource source, Supplier<Presets> presets, LongSupplier clock) {
        this.source = source;
        this.presetSource = presets;
        this.clock = clock;
    }

    /** GL thread: whether the bot sheet is open. Only then does the capture read units and enemies. */
    void setSheetOpen(boolean open) {
        sheetOpen = open;
    }

    /** EDT: the stored presets the sheet's personality cards load. */
    Presets presets() {
        if (presets == null) {
            presets = presetSource.get();
        }
        return presets;
    }

    /** EDT: the personalities with a stored preset, and copies of their presets, read once. */
    private List<Personality> personalities() {
        if (personalities == null) {
            personalities = BotSetupForm.personalities(presets());
            Map<Personality, BehaviorSettings> copies = new EnumMap<>(Personality.class);
            for (Personality personality : personalities) {
                BehaviorSettings preset = presets().get(personality.preset());
                if (preset != null) {
                    try {
                        copies.put(personality, preset.getCopy());
                    } catch (PrincessException failure) {
                        LOGGER.error(failure, "Could not copy the preset {}", personality.preset());
                    }
                }
            }
            presetCopies = Map.copyOf(copies);
        }
        return personalities;
    }

    /** EDT: the commandable bots; the previous instance while nothing changed. */
    Snapshot capture() {
        GpuBoardSource.requireSwingThread();
        Game game = source.currentView().game;
        Player local = source.currentView().getLocalPlayer();
        long now = clock.getAsLong();
        List<BotRow> rows = new ArrayList<>();
        if (local != null) {
            Map<BehaviorSettings, Optional<Personality>> reported = new IdentityHashMap<>();
            for (Player bot : game.getPlayersList()) {
                if (local.mayCommandBot(bot)) {
                    rows.add(row(game, local, bot, now, reported));
                }
            }
            matched.clear();
            matched.putAll(reported);
        }
        pending.keySet().removeIf(id -> rows.stream().noneMatch(row -> row.id() == id));
        unanswered.keySet().removeIf(id -> rows.stream().noneMatch(row -> row.id() == id));
        rows.sort(Comparator.comparing((BotRow row) -> !row.allied()).thenComparingInt(BotRow::id));
        Snapshot next = rows.isEmpty() ? new Snapshot(rows, List.of(), Map.of())
              : new Snapshot(rows, personalities(), presetCopies);
        if (!next.equals(snapshot)) {
            snapshot = next;
        }
        return snapshot;
    }

    /** EDT: the bot's current report, which a command sent now waits to see replaced. */
    BotStatus reported(int botId) {
        return source.currentView().game.getBotStatus(botId);
    }

    /** EDT: marks {@code control} of the bot as sending; the returned record takes what the command asked for. */
    void sending(int botId, Control control, Consumer<Asked> asked) {
        Pending entry = pending.computeIfAbsent(botId, id -> new Pending(reported(id), clock.getAsLong()));
        entry.controls.add(control);
        unanswered.remove(botId);
        asked.accept(new Asked(entry));
    }

    /** What a command asked for, shown until the bot reports. */
    static final class Asked {
        private final Pending entry;

        private Asked(Pending entry) {
            this.entry = entry;
        }

        void personality(Personality personality, Map<Trait, Integer> traits) {
            entry.personality = personality;
            entry.traits.putAll(traits);
        }

        void posture(CombatPosture posture) {
            entry.posture = posture;
        }

        void trait(Trait trait, int value) {
            entry.personality = null;
            entry.traits.put(trait, Math.max(0, Math.min(10, value)));
        }

        void hold(boolean hold) {
            entry.hold = hold;
        }

        void retreat(CardinalEdge edge) {
            entry.retreat = edge;
        }

        void priority(int unitId, int priority) {
            entry.priorities.put(unitId, priority);
        }
    }

    private BotRow row(Game game, Player local, Player bot, long now,
          Map<BehaviorSettings, Optional<Personality>> reported) {
        int id = bot.getId();
        BotStatus status = game.getBotStatus(id);
        Pending asked = pending.get(id);
        if (asked != null && asked.status != status) {
            pending.remove(id);
            asked = null;
        } else if (asked != null && now - asked.sentAt > NO_REPLY_NANOS) {
            pending.remove(id);
            unanswered.put(id, status);
            asked = null;
        }
        if (unanswered.containsKey(id) && unanswered.get(id) != status) {
            unanswered.remove(id);
        }
        BehaviorSettings settings = game.getBotSettings() == null ? null : game.getBotSettings().get(bot.getName());
        Behavior behavior = settings == null ? null : behavior(settings, asked, reported);
        if (asked != null && asked.hold != null) {
            status = new BotStatus(asked.hold, status.tagTargets(), status.strategicHexes());
        }
        int units = 0;
        boolean tag = false;
        List<Enemy> enemies = List.of();
        List<EnemyPlayer> enemyPlayers = List.of();
        if (sheetOpen) {
            enemies = new ArrayList<>();
            for (Entity entity : game.getEntitiesVector()) {
                Player owner = entity.getOwner();
                if (owner != null && owner.getId() == id) {
                    units++;
                    tag |= entity.hasTAG();
                } else if (owner != null && owner.isEnemyOf(bot)) {
                    enemies.add(new Enemy(entity.getId(), entity.getShortName(), owner.getId(),
                          entity instanceof GunEmplacement || entity instanceof BuildingEntity));
                }
            }
            enemies.sort(Comparator.comparingInt(Enemy::ownerId).thenComparing(Enemy::name)
                  .thenComparingInt(Enemy::id));
            enemyPlayers = game.getPlayersList().stream()
                  .filter(player -> player.isEnemyOf(bot) && !player.isObserver())
                  .sorted(Comparator.comparingInt(Player::getId))
                  .map(player -> new EnemyPlayer(player.getId(), player.getName(),
                        player.getColour().getColour().getRGB(), game.isPlayerDishonoredBy(id, player.getId())))
                  .toList();
        }
        AIType type = game.getBotTypes().getOrDefault(bot.getName(), AIType.PRINCESS);
        return new BotRow(id, bot.getName(), type, bot.getColour().getColour().getRGB(), !local.isEnemyOf(bot),
              units, tag, behavior, status, enemies, enemyPlayers,
              asked == null ? Set.of() : asked.controls, asked == null && unanswered.containsKey(id));
    }

    private Behavior behavior(BehaviorSettings settings, Pending asked,
          Map<BehaviorSettings, Optional<Personality>> reported) {
        Optional<Personality> match = matched.get(settings);
        if (match == null) {
            match = BotSetupForm.personalityOf(settings, presets());
        }
        reported.put(settings, match);
        Personality personality = match.orElse(null);
        CombatPosture posture = settings.getCombatPosture();
        EnumMap<Trait, Integer> traits = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            traits.put(trait, trait.of(settings));
        }
        CardinalEdge retreat = settings.shouldAutoFlee() ? settings.getDestinationEdge() : CardinalEdge.NONE;
        Map<Integer, Integer> priorities = new HashMap<>();
        for (int unitId : settings.getPriorityUnitTargets()) {
            priorities.put(unitId, settings.getTargetPriority(unitId));
        }
        if (asked != null) {
            if (asked.controls.contains(Control.PERSONALITY) || asked.controls.contains(Control.TRAITS)
                  || asked.controls.contains(Control.TACTIC)) {
                personality = asked.personality;
            }
            posture = asked.posture == null ? posture : asked.posture;
            traits.putAll(asked.traits);
            retreat = asked.retreat == null ? retreat : asked.retreat;
            asked.priorities.forEach((unitId, priority) -> {
                if (priority <= 0) {
                    priorities.remove(unitId);
                } else {
                    priorities.put(unitId, priority);
                }
            });
        }
        return new Behavior(personality, posture, traits, retreat, priorities, settings.getIgnoredUnitTargets());
    }
}
