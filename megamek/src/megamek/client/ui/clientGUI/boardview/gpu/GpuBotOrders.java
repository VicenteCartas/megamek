/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.bot.princess.BehaviorChange;
import megamek.client.bot.princess.BehaviorChange.Trait;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BotOrder;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.bot.princess.PrincessException;
import megamek.client.bot.princess.Tactic;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBots.Control;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.equipment.GunEmplacement;
import megamek.common.game.Game;
import megamek.common.units.BuildingEntity;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The bot sheet's commands. Each is called on the GL thread, runs on the EDT through {@link GpuBoardSource#command}
 * and goes to the bot as a typed message, never as chat; it carries only the value the player changed. The control
 * then shows as sending ({@link GpuBots#sending}) until the bot reports.
 */
final class GpuBotOrders {
    private static final MMLogger LOGGER = MMLogger.create(GpuBotOrders.class);

    /** Where the messages go: the client, or a test's recorder. */
    interface Sender {
        void behavior(int botId, List<BehaviorChange> changes);

        void order(int botId, BotOrder order);
    }

    private final GpuBoardSource source;
    private final GpuBots bots;
    private final Sender sender;

    GpuBotOrders(GpuBoardSource source, GpuBots bots, Sender sender) {
        this.source = source;
        this.bots = bots;
        this.sender = sender;
    }

    /** The client of {@code source}'s game, or a sender that drops the messages when there is none. */
    static Sender clientOf(GpuBoardSource source) {
        return new Sender() {
            @Override
            public void behavior(int botId, List<BehaviorChange> changes) {
                var gui = source.currentView().getClientgui();
                if (gui != null) {
                    gui.getClient().sendBotBehaviorChange(botId, changes);
                }
            }

            @Override
            public void order(int botId, BotOrder order) {
                var gui = source.currentView().getClientgui();
                if (gui != null) {
                    gui.getClient().sendBotOrder(botId, order);
                }
            }
        };
    }

    /** Takes {@code personality}'s behavior values; the posture, targets and retreat stay. */
    void personality(int botId, Personality personality) {
        source.command(() -> {
            BehaviorSettings preset = bots.presets().get(personality.preset());
            if (preset == null) {
                LOGGER.warn("No stored preset {} for the personality {}", personality.preset(), personality);
                return;
            }
            BehaviorSettings copy;
            try {
                copy = preset.getCopy();
            } catch (PrincessException failure) {
                LOGGER.error(failure, "Could not copy the preset {}", personality.preset());
                return;
            }
            Map<Trait, Integer> traits = new EnumMap<>(Trait.class);
            for (Trait trait : Trait.values()) {
                traits.put(trait, trait.of(copy));
            }
            bots.sending(botId, Control.PERSONALITY, asked -> asked.personality(personality, traits));
            send(botId, Control.PERSONALITY, new BehaviorChange.Style(copy));
        });
    }

    void posture(int botId, CombatPosture posture) {
        source.command(() -> {
            bots.sending(botId, Control.POSTURE, asked -> asked.posture(posture));
            send(botId, Control.POSTURE, new BehaviorChange.Posture(posture));
        });
    }

    /** Sets one behavior index, 0 to 10. */
    void trait(int botId, Trait trait, int value) {
        source.command(() -> {
            bots.sending(botId, Control.TRAITS, asked -> asked.trait(trait, value));
            send(botId, Control.TRAITS, new BehaviorChange.Index(trait, value));
        });
    }

    /**
     * Sets the indices of {@code tactic}. No Prisoners has none: it forgives no one, so the bot stops ignoring units
     * and holds a blood feud against every enemy player.
     */
    void tactic(int botId, Tactic tactic) {
        source.command(() -> {
            List<BehaviorChange> changes = new ArrayList<>(tactic.indices());
            if (tactic.dropsMercy()) {
                changes.add(new BehaviorChange.ClearIgnored());
            }
            bots.sending(botId, Control.TACTIC, asked -> tactic.indices()
                  .forEach(index -> asked.trait(index.trait(), index.value())));
            send(botId, Control.TACTIC, changes);
            if (tactic.dropsMercy()) {
                for (Player enemy : enemyPlayers(botId)) {
                    sender.order(botId, new BotOrder.BloodFeud(enemy.getId()));
                }
            }
        });
    }

    void hold(int botId, boolean hold) {
        source.command(() -> {
            bots.sending(botId, Control.HOLD, asked -> asked.hold(hold));
            sender.order(botId, new BotOrder.Hold(hold));
        });
    }

    /** Sends every unit off the board by {@code edge}, or calls the retreat off with {@link CardinalEdge#NONE}. */
    void retreat(int botId, CardinalEdge edge) {
        source.command(() -> {
            bots.sending(botId, Control.RETREAT, asked -> asked.retreat(edge));
            send(botId, Control.RETREAT, new BehaviorChange.Retreat(edge));
        });
    }

    /** Sets a unit's target priority, 1 (first) to 5, or takes it off the list with 0. */
    void priority(int botId, int unitId, int priority) {
        source.command(() -> {
            bots.sending(botId, Control.PRIORITY, asked -> asked.priority(unitId, priority));
            send(botId, Control.PRIORITY, new BehaviorChange.PriorityTarget(unitId, priority));
        });
    }

    /** Adds or removes a strategic hex. */
    void hex(int botId, Coords hex, boolean add) {
        source.command(() -> {
            bots.sending(botId, Control.HEX, asked -> { });
            send(botId, Control.HEX, new BehaviorChange.StrategicHex(hex, add));
        });
    }

    /** Adds units to the ignored units, or takes them off. */
    void ignore(int botId, Set<Integer> unitIds, boolean add) {
        source.command(() -> {
            bots.sending(botId, Control.IGNORE, asked -> { });
            send(botId, Control.IGNORE, new BehaviorChange.Ignore(unitIds, add));
        });
    }

    /** Ignores every enemy gun emplacement and building, as the Ignore Turrets command does. */
    void ignoreTurrets(int botId) {
        source.command(() -> {
            Game game = source.currentView().game;
            Player bot = game.getPlayer(botId);
            Set<Integer> turrets = new HashSet<>();
            for (Entity entity : game.getEntitiesVector()) {
                if ((entity instanceof GunEmplacement || entity instanceof BuildingEntity)
                      && (bot == null || bot.isEnemyOf(entity.getOwner()))) {
                    turrets.add(entity.getId());
                }
            }
            if (!turrets.isEmpty()) {
                bots.sending(botId, Control.IGNORE, asked -> { });
                send(botId, Control.IGNORE, new BehaviorChange.Ignore(turrets, true));
            }
        });
    }

    void clearIgnored(int botId) {
        source.command(() -> {
            bots.sending(botId, Control.IGNORE, asked -> { });
            send(botId, Control.IGNORE, new BehaviorChange.ClearIgnored());
        });
    }

    /** The bot holds a grudge against {@code playerId}, as if that player had broken the rules of honor. */
    void bloodFeud(int botId, int playerId) {
        source.command(() -> {
            bots.sending(botId, Control.BLOOD_FEUD, asked -> { });
            sender.order(botId, new BotOrder.BloodFeud(playerId));
        });
    }

    /** Has the bot's TAG-carrying units designate {@code unitId}. */
    void tag(int botId, int unitId) {
        source.command(() -> {
            bots.sending(botId, Control.TAG, asked -> { });
            sender.order(botId, new BotOrder.TagTarget(unitId));
        });
    }

    void clearTags(int botId) {
        source.command(() -> {
            bots.sending(botId, Control.TAG, asked -> { });
            sender.order(botId, new BotOrder.ClearTagTargets());
        });
    }

    private void send(int botId, Control control, BehaviorChange change) {
        send(botId, control, List.of(change));
    }

    private void send(int botId, Control control, List<BehaviorChange> changes) {
        LOGGER.debug("Bot {}: {} {}", botId, control, changes);
        sender.behavior(botId, new ArrayList<>(changes));
    }

    private List<Player> enemyPlayers(int botId) {
        Game game = source.currentView().game;
        Player bot = game.getPlayer(botId);
        return bot == null ? List.of() : game.getPlayersList().stream()
              .filter(player -> player.isEnemyOf(bot) && !player.isObserver()).toList();
    }
}
