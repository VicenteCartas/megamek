/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import megamek.client.ui.Messages;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;

/**
 * The movement plan's context command: the one MegaMek movement command the dock's context slot and the route's
 * on-board chip offer besides the gaits. A unit's own situation leads (standing up, unjamming, shaking off a swarm,
 * unloading); without one, its stance (going prone or hull down, an infantry unit's cover). A unit with a stance keeps
 * the slot for the turn, the stance disabled when nothing applies; one without a stance (a VTOL) shows the slot only
 * while a situation is at hand.
 */
final class GpuMoveContext {
    static final String GET_UP = MoveCommand.MOVE_GET_UP.getCmd();
    /** What the unit's situation asks for, most pressing first. */
    private static final List<String> SITUATIONS = Stream.of(MoveCommand.MOVE_GET_UP, MoveCommand.MOVE_UNJAM,
          MoveCommand.MOVE_SHAKE_OFF, MoveCommand.MOVE_UNLOAD, MoveCommand.MOVE_DROP_CARGO)
          .map(MoveCommand::getCmd).toList();
    /** The stances a unit may end its move in. */
    private static final List<String> STANCES = Stream.of(MoveCommand.MOVE_GO_PRONE, MoveCommand.MOVE_HULL_DOWN,
          MoveCommand.MOVE_HIT_DECK, MoveCommand.MOVE_TAKE_COVER, MoveCommand.MOVE_DIG_IN)
          .map(MoveCommand::getCmd).toList();
    private static final Map<String, String> ICONS = Map.of(GET_UP, "stand-up",
          MoveCommand.MOVE_UNJAM.getCmd(), "build", MoveCommand.MOVE_SHAKE_OFF.getCmd(), "shake-off",
          MoveCommand.MOVE_UNLOAD.getCmd(), "unload", MoveCommand.MOVE_DROP_CARGO.getCmd(), "drop-cargo",
          MoveCommand.MOVE_GO_PRONE.getCmd(), "prone", MoveCommand.MOVE_HULL_DOWN.getCmd(), "hull-down",
          MoveCommand.MOVE_HIT_DECK.getCmd(), "prone", MoveCommand.MOVE_TAKE_COVER.getCmd(), "cover",
          MoveCommand.MOVE_DIG_IN.getCmd(), "dig-in");

    private GpuMoveContext() { }

    /**
     * The context slot's glyph for a command: the Tactical View's stance badges where they exist, a wrench for Unjam.
     */
    static String icon(String id) {
        return ICONS.getOrDefault(id, "move");
    }

    /** The command's short caption under its glyph ("Prone", "Unjam"), or MegaMek's label for another command. */
    static String caption(BoardScene.Command command) {
        return ICONS.containsKey(command.id()) ? Messages.getString("GpuBoard.hud.dock.context." + command.id())
              : command.label();
    }

    /**
     * The context command among the phase commands: the first available situation, else the first available stance,
     * else the first stance the unit has at all, disabled; null when the unit has none of them. A situation the unit
     * isn't in (a VTOL's Unjam with nothing jammed) never takes the slot.
     */
    static BoardScene.Command pick(List<BoardScene.Command> commands) {
        BoardScene.Command found = first(commands, SITUATIONS, true);
        if (found == null) {
            found = first(commands, STANCES, true);
        }
        return found != null ? found : first(commands, STANCES, false);
    }

    /** Whether the command answers the unit's situation, which the dock outlines; a stance is a plain choice. */
    static boolean pressing(BoardScene.Command command) {
        return command != null && command.enabled() && SITUATIONS.contains(command.id());
    }

    private static BoardScene.Command first(List<BoardScene.Command> commands, List<String> ids, boolean enabled) {
        for (String id : ids) {
            for (BoardScene.Command command : commands) {
                if (command.id().equals(id) && (!enabled || command.enabled())) {
                    return command;
                }
            }
        }
        return null;
    }
}
