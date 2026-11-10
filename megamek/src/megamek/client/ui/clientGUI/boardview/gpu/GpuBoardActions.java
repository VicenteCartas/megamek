/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GameCommandsMenu;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.AbstractBoardViewOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.OffBoardTargetOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.PlanetaryConditionsOverlay;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.client.ui.panels.phaseDisplay.AbstractPhaseDisplay;
import megamek.client.ui.panels.phaseDisplay.ActionPhaseDisplay;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.panels.phaseDisplay.StatusBarPhaseDisplay;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/** Read-only descriptions of existing controls. Every execution rechecks current Swing/game state. */
final class GpuBoardActions {
    private record Turn(JComponent panel, GamePhase phase, int index, int actor) { }

    private final BoardClientState view;
    private final Supplier<JComponent> panel;
    private final BooleanSupplier ignoringInput;
    private final GpuMenuCommands menus;

    /** {@code ignoringInput} is the owner's EDT guard: closed, replaced, or not accepting input (modal shown). */
    GpuBoardActions(BoardClientState view, Supplier<JComponent> panel, BooleanSupplier ignoringInput, Runnable changed) {
        this.view = view;
        this.panel = panel;
        this.ignoringInput = ignoringInput;
        menus = new GpuMenuCommands(ignoringInput, changed);
    }

    /** Phase command ids of the phase display's Done and Skip buttons and of the clear command. */
    static final String DONE_ID = "phase.done";
    static final String SKIP_ID = "phase.skip";
    static final String CLEAR_ID = "clear";
    /** The id of the game commands group (the Commands button's menu) among the global commands. */
    static final String GAME_COMMANDS_ID = "game-commands";

    /**
     * The phase panel's status and the ids of its completion commands in the phase commands, "" when absent.
     * {@code turnDetails} and {@code conditions} are the lines of the turn-details and planetary-conditions overlays;
     * {@code notable} are the short phrases of the conditions that differ from the standard ones.
     */
    record PhaseInfo(String status, boolean blocking, String doneId, String skipId, String clearId,
          List<String> turnDetails, List<String> conditions, List<String> notable) {
        static final PhaseInfo EMPTY = new PhaseInfo("", false, "", "", "", List.of(), List.of());

        PhaseInfo {
            turnDetails = List.copyOf(turnDetails);
            conditions = List.copyOf(conditions);
            notable = List.copyOf(notable);
        }

        /** The info without notable conditions. */
        PhaseInfo(String status, boolean blocking, String doneId, String skipId, String clearId,
              List<String> turnDetails, List<String> conditions) {
            this(status, blocking, doneId, skipId, clearId, turnDetails, conditions, List.of());
        }
    }

    /**
     * EDT: the phase info for the phase panel's status ({@link #phaseStatus}) and the captured phase commands. The turn
     * details are the lines the phase display gives its turn-details overlay while it acts
     * ({@code ActionPhaseDisplay.getTurnDetails}); the conditions are the lines of the planetary-conditions overlay for
     * the shown board. Both are plain text: the colour codes the overlays add (a dimmed colour for an illegal step
     * group, the hot or cold colour of an extreme temperature) are not kept.
     */
    PhaseInfo phaseInfo(List<BoardScene.Command> commands) {
        var status = phaseStatus(panel.get());
        List<String> turnDetails = panel.get() instanceof ActionPhaseDisplay action ? action.getTurnDetails().stream()
              .map(line -> AbstractBoardViewOverlay.cleanedLine(line).strip()).toList() : List.of();
        List<String> conditions = PlanetaryConditionsOverlay.conditionLines(view.game.getPlanetaryConditions(),
              view.getBoard().isSpace()).stream().map(String::strip).toList();
        List<String> notable = PlanetaryConditionsOverlay.notableConditions(view.game.getPlanetaryConditions(),
              view.getBoard().isSpace());
        return new PhaseInfo(status.text(), status.blocking(), present(commands, DONE_ID), skipId(commands),
              present(commands, CLEAR_ID), turnDetails, conditions, notable);
    }

    /** The id of the phase command that presses {@link #skipButton}, "" without one. */
    private String skipId(List<BoardScene.Command> commands) {
        if (!(panel.get() instanceof AbstractPhaseDisplay phase)) {
            return "";
        }
        MegaMekButton skip = skipButton(phase);
        return skip == null ? "" : present(commands, skip == phase.getButDone() ? DONE_ID : SKIP_ID);
    }

    /**
     * The phase display's button that ends the unit's turn without an action: its Skip while MegaMek shows it; else,
     * in movement, Done while the planned path is empty (with MegaMek's "nag for no action" preference off the
     * display hides Skip, and Done, labelled Skip, holds the unit until a path exists); else null. The dock's Hold
     * position and the hold of every remaining unit press it.
     */
    static MegaMekButton skipButton(AbstractPhaseDisplay phase) {
        MegaMekButton done = phase.getButDone();
        MegaMekButton skip = phase.getCompletionButtons().stream().filter(button -> button != done).findFirst()
              .orElse(null);
        if (skip == null && phase instanceof MovementDisplay movement && (movement.getPlannedMovement() == null
              || movement.getPlannedMovement().length() == 0)) {
            return done;
        }
        return skip;
    }

    private static String present(List<BoardScene.Command> commands, String id) {
        return commands.stream().anyMatch(command -> command.id().equals(id)) ? id : "";
    }

    /** Read presentation text from the existing phase controller on the EDT, including startup and waiting panels. */
    static BoardSource.PhaseStatus phaseStatus(JComponent panel) {
        if (panel instanceof StatusBarPhaseDisplay phase) {
            return new BoardSource.PhaseStatus(plainText(phase.getStatusBarText()), false);
        }
        List<String> labels = new ArrayList<>();
        collectStatusLabels(panel, labels);
        String text = String.join("\n", labels);
        return new BoardSource.PhaseStatus(text, !text.isEmpty());
    }

    private static void collectStatusLabels(Container panel, List<String> labels) {
        if (panel == null) {
            return;
        }
        for (Component child : panel.getComponents()) {
            if (child.isVisible() && child instanceof JLabel label) {
                String text = plainText(label.getText());
                if (text.codePoints().anyMatch(Character::isLetter)) {
                    labels.add(text);
                }
            } else if (child.isVisible() && child instanceof Container nested) {
                collectStatusLabels(nested, labels);
            }
        }
    }

    int actorId() {
        Entity actor = panel.get() instanceof ActionPhaseDisplay action ? action.currentEntity()
              : panel.get() instanceof DeploymentDisplay deployment ? deployment.currentEntity() : view.getSelectedEntity();
        return actor == null ? Entity.NONE : actor.getId();
    }

    private Turn turn() {
        return new Turn(panel.get(), view.game.getPhase(), view.game.getTurnIndex(), actorId());
    }

    private boolean current(Turn expected) {
        return !ignoringInput.getAsBoolean() && expected.equals(turn())
              && (!(expected.panel() instanceof AbstractPhaseDisplay phase) || !phase.isIgnoringEvents());
    }

    List<BoardScene.Command> phaseCommands() {
        Turn owner = turn();
        if (owner.panel() == null) {
            return List.of();
        }
        Set<AbstractButton> buttons = new LinkedHashSet<>();
        if (owner.panel() instanceof StatusBarPhaseDisplay phase) {
            buttons.addAll(phase.getActionButtons());
        }
        collectButtons(owner.panel(), buttons);
        // The off-board targets lead: they show only while they can be used, as the Legacy 2D board's arrows do.
        List<BoardScene.Command> result = new ArrayList<>(offBoardTargets(owner));
        for (AbstractButton button : buttons) {
            String id = Objects.toString(button.getActionCommand(), button.getText());
            if (id == null || id.toLowerCase(java.util.Locale.ROOT).endsWith("more")) {
                continue;
            }
            boolean completion = false;
            if (owner.panel() instanceof AbstractPhaseDisplay phase && phase.getCompletionButtons().contains(button)) {
                // The Done button's text changes with the plan; the HUD finds Done and Skip by stable ids.
                completion = true;
                id = button == phase.getButDone() ? DONE_ID : SKIP_ID;
            }
            result.add(describe(id, button, completion, List.of(), () -> {
                if (current(owner) && button.isEnabled() && available(owner.panel(), button)) {
                    button.doClick(0);
                }
            }));
        }
        if (owner.panel() instanceof StatusBarPhaseDisplay phase) {
            result.add(new BoardScene.Command(CLEAR_ID, Messages.getString("GpuBoard.clear"),
                  Messages.getString("GpuBoard.clearHelp"), phase.shouldReceiveKeyCommands(), false, List.of(),
                  dispatch(() -> {
                      if (current(owner) && phase.shouldReceiveKeyCommands()) {
                          phase.clear();
                      }
                  })));
        }
        return result;
    }

    /**
     * EDT: the arrows of the client's off-board target overlay (plan P7) as commands, one per board edge whose arrow
     * the overlay shows now (the local targeting turn with an artillery weapon selected). Each runs that arrow's click
     * while the turn it was captured in lasts; the overlay keeps every rule.
     */
    private List<BoardScene.Command> offBoardTargets(Turn owner) {
        OffBoardTargetOverlay overlay = view.getOverlay(OffBoardTargetOverlay.class);
        if (overlay == null) {
            return List.of();
        }
        return overlay.shownDirections().stream().map(direction -> new BoardScene.Command("offboard." + direction,
              Messages.getString("GpuBoard.hud.dock.offBoardTarget", edge(direction)), "", true, false, List.of(),
              dispatch(() -> {
                  if (current(owner)) {
                      overlay.target(direction);
                  }
              }))).toList();
    }

    /** MegaMek's name of a board edge. */
    private static String edge(OffBoardDirection direction) {
        return Messages.getString(switch (direction) {
            case NORTH -> "MovementDisplay.Edge.North";
            case SOUTH -> "MovementDisplay.Edge.South";
            case EAST -> "MovementDisplay.Edge.East";
            default -> "MovementDisplay.Edge.West";
        });
    }

    private static boolean available(JComponent owner, AbstractButton button) {
        return owner instanceof StatusBarPhaseDisplay phase && phase.getActionButtons().contains(button)
              || button.isVisible() && SwingUtilities.isDescendingFrom(button, owner);
    }

    private static void collectButtons(Container container, Set<AbstractButton> result) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof AbstractButton button && button.getText() != null && !button.getText().isBlank()) {
                result.add(button);
            } else if (child instanceof Container nested) {
                collectButtons(nested, result);
            }
        }
    }

    /** EDT: MegaMek's map menu at a hex, as commands that recheck the turn they were captured in. */
    List<BoardScene.Command> contextCommands(Coords coords) {
        return contextCommands(coords, Entity.NONE);
    }

    List<BoardScene.Command> contextCommands(Coords coords, int unitId) {
        return contextCommands(coords, unitId, MapMenu.ContextTools.NONE);
    }

    List<BoardScene.Command> contextCommands(Coords coords, int unitId, MapMenu.ContextTools tools) {
        if (view.getClientgui() == null) {
            return List.of();
        }
        Turn owner = turn();
        Supplier<Container> menu = () -> contextMenu(coords, unitId, owner.panel(), tools);
        return menuCommands(menu.get(), menu, owner);
    }

    private Container contextMenu(Coords coords, int unitId, JComponent phasePanel, MapMenu.ContextTools tools) {
        if (unitId == Entity.NONE && (coords == null || !view.getBoard().contains(coords))) {
            return new JPopupMenu();
        }
        Entity entity = view.game.getEntity(unitId);
        int boardId = entity == null ? view.getBoardId() : entity.getBoardId();
        return new MapMenu(coords, boardId, phasePanel, view.getClientgui(), unitId, tools);
    }

    List<BoardScene.Command> globalCommands() {
        ClientGUI gui = view.getClientgui();
        if (gui == null || gui.getMenuBar() == null) {
            return List.of();
        }
        List<BoardScene.Command> result = menuCommands(gui.getMenuBar(), gui::getMenuBar, null);
        if (gui.getClient() instanceof megamek.client.Client) {
            Supplier<Container> commands = () -> view.game.getPhase().isOnMap() || view.game.getPhase().isReport()
                  ? new GameCommandsMenu(gui).createPopup() : new JPopupMenu();
            List<BoardScene.Command> children = menuCommands(commands.get(), commands, null);
            result.add(new BoardScene.Command(GAME_COMMANDS_ID, Messages.getString("GameCommands.title"),
                  Messages.getString("GameCommands.tooltip"), !children.isEmpty(), false, children, () -> { }));
        }
        return result;
    }

    /**
     * EDT: one bot's commands from the bot commands panel: each popup button as a group of the items its popup lists
     * for that bot, with MegaMek's labels and availability. A button whose popup lists nothing for the bot is left
     * out, so a player the local player does not command gets none. Empty without the panel.
     */
    List<BoardScene.Command> botCommands(Player bot) {
        BotCommandsPanel bots = botCommandsPanel();
        if (bots == null) {
            return List.of();
        }
        List<BoardScene.Command> result = new ArrayList<>();
        for (BotCommandsPanel.PopupCommand command : bots.popupCommands(bot)) {
            Supplier<Container> popup = () -> command.popup().get();
            List<BoardScene.Command> items = menuCommands(popup.get(), popup, null);
            AbstractButton button = command.button();
            if (!items.isEmpty()) {
                result.add(describe(button.getActionCommand(), button, false, items, () -> { }));
            }
        }
        return result;
    }

    /** EDT: the bot commands panel's Pause/Continue button as a plain command, or null without the panel. */
    BoardScene.Command pauseCommand() {
        BotCommandsPanel bots = botCommandsPanel();
        if (bots == null) {
            return null;
        }
        AbstractButton pause = bots.pauseCommand();
        return describe("bot-pause", pause, false, List.of(), () -> {
            if (pause.isEnabled()) {
                pause.doClick(0);
            }
        });
    }

    private BotCommandsPanel botCommandsPanel() {
        return view.getClientgui() == null ? null : view.getClientgui().getBotCommandsPanel();
    }

    /**
     * The native window cannot trigger Swing accelerators, so invoke the current menu item on the EDT. The caller
     * ({@code GpuBoardSource.key}) has already dropped the key if the client ignores hotkeys.
     */
    boolean menuShortcut(KeyStroke key) {
        var gui = view.getClientgui();
        return !ignoringInput.getAsBoolean() && gui != null && gui.getMenuBar() != null
              && menuShortcut(gui.getMenuBar(), key);
    }

    static boolean menuShortcut(Container menu, KeyStroke key) { return GpuMenuCommands.menuShortcut(menu, key); }

    private List<BoardScene.Command> menuCommands(Container menu, Supplier<Container> refresh, Turn owner) {
        return menus.capture(menu, refresh, () -> owner == null || current(owner));
    }

    /**
     * The menu item among {@code commands}, or in their groups, whose action command is {@code actionCommand}; null
     * when there is none. The last key of an item's id is its own.
     */
    static BoardScene.Command menuItem(List<BoardScene.Command> commands, String actionCommand) {
        for (BoardScene.Command command : commands) {
            String id = command.id();
            int key = id.lastIndexOf('/') + 1;
            if (command.children().isEmpty() && id.startsWith(actionCommand, key)
                  && id.startsWith(":", key + actionCommand.length())) {
                return command;
            }
            BoardScene.Command item = menuItem(command.children(), actionCommand);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    /** A menu item also carries its accelerator text and, for a check or radio item, its selection. */
    private BoardScene.Command describe(String id, AbstractButton button, boolean commit,
          List<BoardScene.Command> children, Runnable action) {
        return menus.describe(id, button, commit, children, action);
    }

    /** Runs on the EDT unless the owner ignores input by then: a command queued before a dialog opened is dropped. */
    private Runnable dispatch(Runnable action) { return menus.dispatch(action); }

    static String plainText(String text) { return GpuMenuCommands.plainText(text); }
}
