/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static com.badlogic.gdx.scenes.scene2d.actions.Actions.forever;
import static com.badlogic.gdx.scenes.scene2d.actions.Actions.run;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.ChatFold;
import megamek.client.lobby.ChatHistory;
import megamek.client.lobby.ChatLine;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbySnapshot.PlayerRow;
import megamek.client.lobby.LobbySnapshot.Relation;
import megamek.client.lobby.LobbySnapshot.TeamGroup;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCountBadge;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.Player;
import megamek.common.board.Board;

/**
 * The lobby's left column, as the briefing room's roster and chat dock: the players by team, each team under a header
 * with its colour bar, name and totals, and ending with a button that adds a bot to it; then the chat, below a
 * hairline. A player's card shows their colour, name and role chips, deployment area, forces (unless a blind drop hides
 * them) and ready state. The local player's own cards (themselves and their bots) act in place, with no menu: the
 * swatch opens the camouflage, the Team and deployment buttons open a flat picker each (the deployment as the
 * compass grid of start positions), and the icons at the forces line's end open the player settings, a bot's own
 * settings, or remove the bot; a disconnected player's card offers a bot to take their place. A double click on such a
 * card opens its player settings. The Game Master's seat is in the top bar. The chat input sends on Enter, as the chat box does (commands such as /who included), and calls
 * back earlier lines with Up and Down. The fold button at the input's end folds the chat to its header, which then shows
 * the latest line; lines from others tint the header and count on its badge until the chat opens again. The input row
 * stays at the column's foot either way, so the button never moves under the pointer.
 */
final class LobbyPlayersPanel {
    /** How many lines of chat show at once. */
    static final int CHAT_LINES = 7;
    /** The chat follows new lines while it is scrolled to within this many units of its end. */
    private static final float FOLLOW = 4;
    /** For how many frames the chat stays at its end after its lines change. */
    private static final int END_FRAMES = 3;
    /** How long a revealed card stays lit, in seconds: the reference's pulse. */
    static final float REVEAL_SECONDS = 0.7f;

    /** What the panel's controls do; each takes effect when the window next reads the lobby. */
    interface Actions {
        void chat(String text);

        void ready();

        void playerSettings(int id);

        void camouflage(int id);

        void changeTeam(int id, int team);

        void changeDeployment(int id, int position);

        void botSettings(int id);

        void removeBot(int id);

        void replaceWithBot(int id);

        void addBot(int team);

        /** Pings player {@code id}; the local player's own id pings everyone. */
        default void ping(int id) { }

        /** Keeps whether the chat dock is open or folded (a client preference). */
        default void chatOpen(boolean open) { }

        /** Player {@code id}'s card was clicked: while units are being added, they go to that player if they may. */
        default void pick(int id) { }

        /** The picture of the camo {@link LobbySnapshot#camoKey} names, once it has been drawn; else null. */
        default @Nullable Drawable camo(String key) {
            return null;
        }

        /** The existing lobby sprite of unit {@code id}, once loaded; else null. */
        default @Nullable Drawable sprite(int id) {
            return null;
        }
    }

    private final UiKit kit;
    private final Actions actions;
    private final Table panel;
    private final Label playerCount;
    private final Table playerList = new Table();
    private final ScrollPane roster;
    private final Table chatList = new Table();
    private final ScrollPane chatScroll;
    private final UiTextField input;
    private final ChatHistory history = new ChatHistory();
    private final ChatFold chat;
    private final Label chatTitle;
    private final UiCountBadge chatBadge;
    private final Label chatPreview;
    private final Cell<ScrollPane> chatCell;
    private final float chatHeight;
    private final UiButton foldButton;
    private final Label foldTip;
    /** The open picker: a player's team or deployment area. */
    private final UiPopover menu;
    /** Each shown local player's deployment button, by player id. */
    private final Map<Integer, Actor> menuButtons = new HashMap<>();
    private LobbySnapshot shown = LobbySnapshot.EMPTY;
    /** The player whose card is marked as receiving the units being added; -1 for none. */
    private int recipient = -1;
    /** Whether the chat scrolls to its end on its next lines, as after sending one. */
    private boolean followChat = true;
    private int chatEndFrames;

    LobbyPlayersPanel(UiKit kit, Actions actions, boolean chatOpen) {
        this.kit = kit;
        this.actions = actions;
        chat = new ChatFold(chatOpen);
        menu = new UiPopover(kit, () -> { }, UiTheme.TOKEN);
        menu.setName("lobby-player-menu");

        panel = kit.panel(true);
        Table header = kit.header(Messages.getString("Lobby.players"), "", true);
        playerCount = header.findActor(UiKit.HEADER_COUNT);
        panel.add(header).growX().row();
        playerList.top();
        roster = kit.scrollList(playerList, true, false);
        panel.add(roster).grow().row();

        panel.add(new Image(kit.skin.getDrawable("token-divider"))).growX().height(size("border"))
              .padTop(space("md")).row();
        chatTitle = kit.caption(Messages.getString("Lobby.chat"), true);
        chatBadge = kit.countBadge(0);
        chatPreview = kit.typeLabel("", "small", "text.muted");
        chatPreview.setEllipsis(true);
        Table chatHeader = new Table();
        chatHeader.add(chatTitle).left();
        chatHeader.add(chatBadge).padLeft(space("xs"));
        chatHeader.add(chatPreview).growX().minWidth(0).left().padLeft(space("sm"));
        panel.add(chatHeader).growX().left().padTop(space("sm")).row();
        chatList.top().left();
        chatScroll = kit.scrollList(chatList, true);
        chatScroll.setName("lobby-chat-log");
        chatScroll.addAction(forever(run(this::keepChatAtEnd)));
        Label sample = kit.typeLabel("", "body", "text.secondary");
        Drawable frame = chatScroll.getStyle().background;
        chatHeight = CHAT_LINES * sample.getStyle().font.getLineHeight() * sample.getFontScaleY()
              + (frame == null ? 0 : frame.getTopHeight() + frame.getBottomHeight());
        chatCell = panel.add(chatScroll).growX().height(chatHeight).padTop(space("xs"));
        chatCell.row();
        input = kit.input("", true);
        input.setMessageText(Messages.getString("Lobby.chatHint"));
        input.setTextFieldListener((field, typed) -> {
            if (typed == '\r' || typed == '\n') {
                send();
            }
        });
        input.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                String line = switch (keycode) {
                    case Input.Keys.UP -> history.older();
                    case Input.Keys.DOWN -> history.newer();
                    default -> null;
                };
                if (line == null) {
                    return false;
                }
                input.setText(line);
                input.setCursorPosition(line.length());
                return true;
            }
        });
        input.setName("lobby-chat-input");
        foldButton = kit.button("button-icon" + UiTheme.COMPACT, "chevron-down", null, null);
        foldButton.setName("lobby-chat-fold");
        foldTip = kit.tip(foldButton, true).getActor();
        UiKit.onChange(foldButton, () -> openChat(!chat.open()));
        Table inputRow = new Table();
        inputRow.add(input).growX();
        inputRow.add(foldButton).padLeft(space("xs"));
        panel.add(inputRow).growX().padTop(space("sm"));
        refreshChatFold();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    Table panel() {
        return panel;
    }

    /** Adds the player pickers to {@code layer}, above the columns; once, before the panel shows a snapshot. */
    void attach(Group layer) {
        layer.addActor(menu);
    }

    /** One Escape step in the player pickers: closes the open one; true when one was open. */
    boolean back() {
        return menu.back();
    }

    private void send() {
        String text = input.getText();
        input.setText("");
        if (text.isBlank()) {
            return;
        }
        history.sent(text);
        followChat = true;
        actions.chat(text);
    }

    /** Shows {@code snapshot}'s players and chat; {@code before} is the snapshot shown until now, or null. */
    void show(LobbySnapshot snapshot, LobbySnapshot before) {
        shown = snapshot;
        if (playerCount != null) {
            playerCount.setText(String.valueOf(snapshot.playerCount()));
        }
        if (before == null || !before.teams().equals(snapshot.teams())
              || !before.forces().units().equals(snapshot.forces().units())
              || before.gameMasterAvailable() != snapshot.gameMasterAvailable()) {
            fillPlayers(snapshot);
        }
        if (before == null || !before.chat().equals(snapshot.chat())) {
            fillChat(snapshot);
            // Until the lobby knows the local player, its chat is what was said before it opened
            chat.show(snapshot.chat(), before == null || before.localPlayerId() == Player.PLAYER_NONE);
            refreshChatFold();
        }
    }

    // ---- The roster ----

    /**
     * Scrolls the roster to player {@code id}'s card and lights it for a moment, as when a warning in the top bar
     * points at them; nothing when they are not shown.
     */
    void reveal(int id) {
        Actor found = playerList.findActor("lobby-player-" + id);
        if (!(found instanceof Table card)) {
            return;
        }
        roster.layout();
        Vector2 at = card.localToAscendantCoordinates(playerList, new Vector2());
        roster.scrollTo(at.x, at.y, card.getWidth(), card.getHeight(), false, true);
        card.clearActions();
        card.setBackground(kit.skin.getDrawable("token-card-selected"));
        card.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.sequence(
              com.badlogic.gdx.scenes.scene2d.actions.Actions.delay(REVEAL_SECONDS),
              com.badlogic.gdx.scenes.scene2d.actions.Actions.run(() -> card.setBackground(cardBackground(id)))));
    }

    /** Marks player {@code id}'s card as receiving the units being added; -1 marks none. */
    void recipient(int id) {
        if (id == recipient) {
            return;
        }
        int was = recipient;
        recipient = id;
        for (int changed : new int[] { was, id }) {
            if (playerList.findActor("lobby-player-" + changed) instanceof Table card) {
                card.setBackground(cardBackground(changed));
            }
        }
    }

    int recipient() {
        return recipient;
    }

    /** For the developer switch: the first local bot's card, or null before one shows. */
    @Nullable Actor probeBotCard() {
        for (TeamGroup team : shown.teams()) {
            for (PlayerRow player : team.players()) {
                if (player.bot() && player.local()) {
                    return playerList.findActor("lobby-player-" + player.id());
                }
            }
        }
        return null;
    }

    private Drawable cardBackground(int id) {
        return kit.skin.getDrawable(id == recipient ? "token-card-selected" : "token-card");
    }

    /** Draws the cards again as their camo swatches and unit sprites arrive. */
    void imagesArrived() {
        if (shown != null) {
            fillPlayers(shown);
        }
    }

    private void fillPlayers(LobbySnapshot snapshot) {
        playerList.clearChildren();
        menuButtons.clear();
        boolean first = true;
        for (TeamGroup team : snapshot.teams()) {
            playerList.add(teamHeader(team)).growX().padTop(first ? 0 : space("lg")).row();
            first = false;
            for (PlayerRow player : team.players()) {
                playerList.add(card(player)).growX().padTop(space("sm")).row();
            }
            String add = team.ownTeam() ? "Lobby.addAlly" : "Lobby.addOpponent";
            playerList.add(addButton(add, team.id())).left().minWidth(0).padTop(space("xs")).row();
        }
        int free = snapshot.freeTeam();
        if (free != Player.TEAM_NONE) {
            playerList.add(addButton("Lobby.addOpponentNewTeam", free)).left().minWidth(0).padTop(space("lg")).row();
        }
    }

    /** A team's header: its colour bar (own team, enemy, or no team), its name, and its totals unless hidden. */
    private Table teamHeader(TeamGroup team) {
        String bar = switch (team.relation()) {
            case OWN -> "accent.primary";
            case ENEMY -> "status.danger";
            default -> "border.control";
        };
        Table header = new Table();
        header.add(kit.solid(bar)).width(size("selection.width")).growY().padRight(space("sm"));
        Table text = new Table();
        text.left().defaults().left();
        text.add(kit.caption(team.name(), true)).row();
        if (team.id() != Player.TEAM_NONE) {
            String totals = team.hidden() ? Messages.getString("Lobby.hidden")
                  : Messages.getString("Lobby.totals", team.bv(), team.tons(), team.units());
            Label line = kit.typeLabel(totals, "small", "text.secondary");
            line.setEllipsis(true);
            text.add(line).growX().minWidth(0).padTop(space("xxs"));
        }
        header.add(text).growX().minWidth(0);
        return header;
    }

    private Actor addButton(String key, int team) {
        UiButton add = kit.button("button-quiet" + UiTheme.COMPACT, "add", Messages.getString(key), null);
        // A narrow column cuts the caption short rather than the list running out of it
        add.getLabel().setEllipsis(true);
        add.getCell(add.getLabel()).minWidth(0);
        UiKit.onChange(add, () -> actions.addBot(team));
        return add;
    }

    private Table card(PlayerRow player) {
        Table card = kit.card();
        card.setName("lobby-player-" + player.id());
        card.setBackground(cardBackground(player.id()));
        card.top().left();

        Drawable camo = player.camo().isEmpty() ? null : actions.camo(player.camo());
        Image swatch = camo == null ? kit.solid(new Color(player.colour() << 8 | 0xFF)) : new Image(camo, Scaling.fill);
        swatch.setName("lobby-player-swatch-" + player.id());
        float swatchSize = size("icon.lg");
        card.add(swatch).size(swatchSize).padRight(space("md"));
        if (player.local()) {
            kit.tip(swatch, true).getActor().setText(Messages.getString("Lobby.camo"));
            swatch.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    actions.camouflage(player.id());
                }
            });
        }

        // The swatch, the name and the ready mark share the first line; the rows under it run the card's full width
        card.add(nameRow(player)).growX().minWidth(0);
        card.add(readyMark(player)).size(size("control.sm")).padLeft(space("xs")).row();
        card.add(player.local() ? placeRow(player) : startRow(player)).colspan(3).growX().minWidth(0)
              .padTop(space("xs")).row();
        // The totals, with the card's tools at the end of their line
        Table third = new Table();
        third.left();
        String forces = player.hidden() ? Messages.getString("Lobby.hidden")
              : Messages.getString("Lobby.totals", player.bv(), player.tons(), player.units());
        Label forcesLabel = kit.typeLabel(forces, "small", "text.secondary");
        forcesLabel.setEllipsis(true);
        third.add(forcesLabel).growX().minWidth(0);
        Table tools = new Table();
        tools.setName("lobby-player-tools-" + player.id());
        cardActions(tools, player);
        if (tools.hasChildren()) {
            third.add(tools).right().padLeft(space("xs"));
        }
        card.add(third).colspan(3).growX().minWidth(0).padTop(space("xxs"));

        if (!player.hidden()) {
            HorizontalGroup icons = unitIcons(player.id());
            if (icons.hasChildren()) {
                card.row();
                card.add(icons).colspan(3).growX().minWidth(0).padTop(space("sm"));
            }
        }

        // A table takes only its children's hits; the whole card takes clicks, not only its labels
        card.setTouchable(Touchable.enabled);
        card.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (inButton(event.getTarget()) || event.getTarget() == swatch) {
                    return;
                }
                actions.pick(player.id());
                if (player.local() && getTapCount() == 2) {
                    actions.playerSettings(player.id());
                }
            }
        });
        return card;
    }

    /** Full-size sprites on one row; halve them before allowing the row to wrap, without shrinking again. */
    private HorizontalGroup unitIcons(int ownerId) {
        float normalSize = size("control.md");
        float gap = space("xs");
        HorizontalGroup icons = new HorizontalGroup() {
            private void fitIcons() {
                int count = getChildren().size;
                float fullWidth = count * normalSize + Math.max(0, count - 1) * gap;
                float side = getWidth() > 0 && fullWidth > getWidth() ? normalSize / 2 : normalSize;
                boolean changed = false;
                for (Actor child : getChildren()) {
                    Container<?> icon = (Container<?>) child;
                    if (icon.getPrefWidth() != side) {
                        icon.size(side);
                        changed = true;
                    }
                }
                if (changed) {
                    invalidateHierarchy();
                }
            }

            @Override
            public float getPrefHeight() {
                fitIcons();
                return super.getPrefHeight();
            }

            @Override
            public void layout() {
                fitIcons();
                super.layout();
            }
        };
        icons.setName("lobby-player-units-" + ownerId);
        icons.wrap().left().rowLeft().space(gap).wrapSpace(gap);
        for (LobbyUnit unit : shown.forces().units()) {
            if (unit.ownerId() != ownerId || unit.hidden()) {
                continue;
            }
            Image image = new Image(actions.sprite(unit.id()), Scaling.fit);
            image.setName("lobby-player-unit-" + unit.id());
            icons.addActor(new Container<>(image).size(normalSize).fill());
        }
        return icons;
    }

    private static boolean inButton(Actor target) {
        for (Actor actor = target; actor != null; actor = actor.getParent()) {
            if (actor instanceof Button) {
                return true;
            }
        }
        return false;
    }

    /** The player's name, then their chips: YOU, BOT, GM, observer, and disconnected. */
    private Table nameRow(PlayerRow player) {
        Table row = new Table();
        row.left();
        Label name = kit.typeLabel(player.name(), "body.strong", "text.primary");
        name.setEllipsis(true);
        row.add(name).left().minWidth(0).growX();
        if (player.self()) {
            row.add(kit.chip(Messages.getString("Lobby.you"), Tone.NORMAL, true)).padLeft(space("xs"));
        }
        if (player.bot()) {
            row.add(kit.chip(Messages.getString("Lobby.bot"), Tone.NORMAL, true)).padLeft(space("xs"));
        }
        if (player.gameMaster()) {
            Actor chip = kit.gameMasterChip(Messages.getString("Lobby.gameMaster"));
            chip.setName("lobby-gm-chip");
            row.add(chip).padLeft(space("xs"));
        }
        if (player.observer()) {
            row.add(kit.chip(Messages.getString("Lobby.observer"), Tone.NORMAL, true)).padLeft(space("xs"));
        }
        if (player.ghost()) {
            row.add(kit.chip(Messages.getString("Lobby.ghost"), Tone.WARN, true)).padLeft(space("xs"));
        }
        return row;
    }

    /** Where the player deploys, with a warning when their area overlaps another's where the rules forbid it. */
    private Table startRow(PlayerRow player) {
        Table row = new Table();
        row.left();
        if (!player.startValid()) {
            UiKit.Icon warn = kit.icon("warn", size("icon.sm"), UiTheme.tokenColor("status.warning"));
            kit.tip(warn, true).getActor().setText(Messages.getString("Lobby.startInvalid"));
            row.add(warn).size(size("icon.sm")).padRight(space("xs"));
        }
        String start = player.start().isEmpty() ? "" : Messages.getString("Lobby.start", player.start());
        Label label = kit.typeLabel(start, "small", player.startValid() ? "text.secondary" : "status.warning");
        label.setEllipsis(true);
        row.add(label).left().growX().minWidth(0);
        return row;
    }

    /**
     * The local player's or their bot's team and deployment area, each a quiet button that opens a flat picker under
     * it; a deployment area the rules forbid shows the warning icon.
     */
    private Table placeRow(PlayerRow player) {
        Table row = new Table();
        row.left();
        UiButton team = pickerButton("group", LobbySnapshot.teamName(player.team()),
              Messages.getString("Lobby.assignTeam"));
        team.setName("lobby-player-team-" + player.id());
        UiKit.onChange(team, () -> openTeams(player.id(), team));
        // The quiet button's inset would push its icon right of the swatch's edge, so the row starts that far left
        row.add(team).left().minWidth(0).padLeft(-team.getPadLeft());
        String start = player.start().isEmpty() ? Messages.getString("Lobby.deployment") : player.start();
        String tip = Messages.getString("Lobby.deployment")
              + (player.startValid() ? "" : "\n\n" + Messages.getString("Lobby.startInvalid"));
        UiButton deploy = pickerButton(player.startValid() ? "locate" : "warn", start, tip);
        deploy.setName("lobby-player-start-" + player.id());
        UiKit.onChange(deploy, () -> openDeployment(player.id(), deploy));
        menuButtons.put(player.id(), deploy);
        row.add(deploy).left().minWidth(0).padLeft(space("xxs"));
        row.add().growX();
        return row;
    }

    private UiButton pickerButton(String icon, String text, String tip) {
        UiButton button = kit.button("button-quiet" + UiTheme.COMPACT, icon, text, null);
        // A narrow column cuts the caption short rather than the card running out of it
        button.getLabel().setEllipsis(true);
        button.getCell(button.getLabel()).minWidth(0);
        kit.tip(button, true).getActor().setText(tip);
        return button;
    }

    /**
     * The icons at the end of the totals line: the player settings for the local player and their bots, a
     * bot's own settings and its removal, for a disconnected player, a bot to take their place, and for any other
     * player a ping (on the local player's own card, a ping to everyone).
     */
    private void cardActions(Table strip, PlayerRow player) {
        int id = player.id();
        if (player.localBot()) {
            cardAction(strip, "bot", "Lobby.botSettings", "lobby-player-bot-" + id, () -> actions.botSettings(id));
        }
        if (player.local()) {
            cardAction(strip, "settings", "Lobby.playerSettings", "lobby-player-settings-" + id,
                  () -> actions.playerSettings(id));
        }
        if (player.localBot()) {
            cardAction(strip, "close", "Lobby.removeBot", "lobby-player-remove-" + id, () -> actions.removeBot(id));
        }
        if (player.ghost()) {
            cardAction(strip, "bot", "Lobby.replaceBot", "lobby-player-replace-" + id,
                  () -> actions.replaceWithBot(id));
        } else if (!player.localBot()) {
            cardAction(strip, "volume", player.self() ? "PlayerPing.menu.pingEveryone" : "PlayerPing.menu.ping",
                  "lobby-player-ping-" + id, () -> actions.ping(id));
        }
    }

    private void cardAction(Table strip, String icon, String tipKey, String name, Runnable action) {
        UiButton button = kit.button("button-icon" + UiTheme.COMPACT, icon, null, null);
        button.setName(name);
        kit.tip(button, true).getActor().setText(Messages.getString(tipKey));
        UiKit.onChange(button, () -> choose(action));
        strip.add(button).size(size("control.sm")).padLeft(strip.hasChildren() ? space("xxs") : 0);
    }

    /** The local player's ready toggle; another player's ready mark. */
    private Actor readyMark(PlayerRow player) {
        String icon = player.ready() ? "check-circle" : player.self() ? "radio-unchecked" : "hourglass";
        if (player.self()) {
            UiButton toggle = kit.button("button-icon" + UiTheme.COMPACT, icon, null, null);
            if (player.ready()) {
                // The check keeps the success color it has on the other cards rather than the label's
                Actor check = toggle.icons.removeFirst();
                toggle.iconColors.put(check, UiTheme.tokenColor("status.success"));
            }
            kit.tip(toggle, true).getActor().setText(Messages.getString("Lobby.readyToggle"));
            UiKit.onChange(toggle, actions::ready);
            return toggle;
        }
        UiKit.Icon mark = kit.icon(icon, size("icon.md"),
              UiTheme.tokenColor(player.ready() ? "status.success" : "text.muted"));
        kit.tip(mark, true).getActor().setText(Messages.getString(player.ready() ? "Lobby.isReady" : "Lobby.notReady"));
        Table holder = new Table();
        holder.add(mark).size(size("icon.md"));
        return holder;
    }

    // ---- The team and deployment pickers ----

    /** For the developer switch: opens the first local player's deployment picker; false when none shows. */
    boolean openFirstMenu() {
        PlayerRow chosen = shown.players().stream().filter(PlayerRow::local).findFirst().orElse(null);
        Actor button = chosen == null ? null : menuButtons.get(chosen.id());
        if (button == null || button.getStage() == null) {
            return false;
        }
        openDeployment(chosen.id(), button);
        return true;
    }

    /** Opens player {@code id}'s teams under {@code button}, a flat list with their team checked. */
    private void openTeams(int id, Actor button) {
        PlayerRow player = shown.player(id);
        if (closedPicker() || player == null || menu.getStage() == null) {
            return;
        }
        UiMenuList list = new UiMenuList(kit, UiTheme.TOKEN_MENU_ROW);
        for (int team = 0; team < LobbySnapshot.TEAM_COUNT; team++) {
            int chosen = team;
            list.item(LobbySnapshot.teamName(team), null, player.team() == team, false, true,
                  () -> choose(() -> actions.changeTeam(id, chosen))).setName("lobby-team-" + team);
        }
        showPicker(Messages.getString("Lobby.assignTeam"), player.name(), list, button);
    }

    /**
     * Opens player {@code id}'s deployment areas under {@code button}: the start positions as a compass, as the player
     * settings lay them out, their own area checked, then the map's custom zones.
     */
    private void openDeployment(int id, Actor button) {
        PlayerRow player = shown.player(id);
        if (closedPicker() || player == null || menu.getStage() == null) {
            return;
        }
        Table grid = new Table();
        grid.defaults().growX().uniformX().pad(space("xxs"));
        for (int[] line : PlayerSettingsForm.GRID) {
            for (int position : line) {
                if (position < 0) {
                    grid.add();
                } else {
                    grid.add(deployButton(id, position, LobbySnapshot.deploymentName(position), player.start()));
                }
            }
            grid.row();
        }
        for (int zone : shown.customZones()) {
            int position = Board.encodeCustomDeploymentZoneID(zone);
            grid.add(deployButton(id, position, LobbySnapshot.zoneName(zone), player.start())).colspan(3).row();
        }
        showPicker(Messages.getString("Lobby.deployment"), player.name(), grid, button);
    }

    private UiButton deployButton(int id, int position, String name, String start) {
        UiButton button = kit.button("button-toggle-compact", null, name, null);
        button.setName("lobby-deploy-" + position);
        button.setProgrammaticChangeEvents(false);
        // The card's line names the area, then any offset and width, or the "Any" area's box
        button.setChecked(start.equals(name) || start.startsWith(name + ",")
              || position == 0 && start.startsWith(name + " ("));
        UiKit.onChange(button, () -> choose(() -> actions.changeDeployment(id, position)));
        return button;
    }

    /** Closes an open picker, so a second press on its button only closes it; true when one was open. */
    private boolean closedPicker() {
        return menu.isVisible() && menu.cancel();
    }

    private void showPicker(String title, String detail, Actor content, Actor button) {
        menu.header(title, detail).content(content);
        menu.openedFrom(button);
        Vector2 corner = button.localToStageCoordinates(new Vector2(0, 0));
        menu.showAt(corner.x, corner.y);
    }

    /** Closes the picker, then runs {@code action}, so a dialog it opens does not find it open. */
    private void choose(Runnable action) {
        menu.cancel();
        action.run();
    }

    // ---- The chat ----

    /**
     * Opens the chat's log, which clears its unread count and brings it to its latest line, or folds it to the header
     * and the input line, the players taking the freed height; the client keeps the choice.
     */
    void openChat(boolean open) {
        if (open == chat.open()) {
            return;
        }
        chat.open(open);
        actions.chatOpen(open);
        refreshChatFold();
        if (open) {
            chatEndFrames = END_FRAMES;
        }
    }

    boolean chatOpen() {
        return chat.open();
    }

    int chatUnread() {
        return chat.unread();
    }

    UiButton chatFoldButton() {
        return foldButton;
    }

    UiCountBadge chatBadge() {
        return chatBadge;
    }

    /** The folded header's latest line. */
    String chatLatest() {
        return chatPreview.getText().toString();
    }

    /** Shows the log or folds it away, and the header's tint, badge and latest line, and the fold button's face. */
    private void refreshChatFold() {
        boolean open = chat.open();
        chatScroll.setVisible(open);
        chatCell.height(open ? chatHeight : 0).padTop(open ? space("xs") : 0);
        chatPreview.setVisible(!open);
        ChatLine latest = chat.latest();
        chatPreview.setText(latest == null ? "" : latest.kind() == ChatLine.Kind.PLAYER
              ? Messages.getString("Lobby.chat.line", latest.sender(), latest.text()) : latest.text());
        chatBadge.count(chat.unread());
        Label.LabelStyle titleStyle = chatTitle.getStyle();
        titleStyle.fontColor = UiTheme.tokenColor(chat.unread() > 0 ? "accent.primary" : "text.muted");
        chatTitle.setStyle(titleStyle);
        ((Image) foldButton.icons.getFirst()).setDrawable(kit.skin.getDrawable(open ? "icon-chevron-down"
              : "icon-chevron-up"));
        String tip = Messages.getString(open ? "Lobby.chat.fold" : "Lobby.chat.open");
        foldTip.setText(chat.unread() > 0 ? Messages.getString("Lobby.chat.withUnread", tip,
              Messages.getString("Lobby.chat.unread", chat.unread())) : tip);
        panel.invalidateHierarchy();
    }

    private void fillChat(LobbySnapshot snapshot) {
        float bottom = chatScroll.getMaxY() - chatScroll.getScrollY();
        boolean follow = followChat || bottom <= FOLLOW;
        followChat = false;
        chatList.clearChildren();
        for (ChatLine line : snapshot.chat()) {
            chatList.add(chatRow(line)).growX().left().padBottom(space("xxs")).row();
        }
        if (follow) {
            chatEndFrames = END_FRAMES;
        }
    }

    /**
     * Each frame: keeps the chat at its end for a few frames after its lines change, as the wrapped lines take their
     * height over the layouts that follow.
     */
    private void keepChatAtEnd() {
        if (chatEndFrames > 0) {
            chatEndFrames--;
            chatScroll.setScrollPercentY(1);
            chatScroll.updateVisualScroll();
        }
    }

    /** A chat line: a player's name in their relation's colour before their words, or a server notice, muted. */
    private Actor chatRow(ChatLine line) {
        if (line.kind() != ChatLine.Kind.PLAYER) {
            boolean notice = line.kind() == ChatLine.Kind.NOTICE;
            Label text = kit.typeLabel(line.text(), notice ? "small" : "body", notice ? "text.muted" : "text.secondary");
            text.setWrap(true);
            text.setAlignment(Align.topLeft);
            return text;
        }
        Table row = new Table();
        row.top().left();
        String role = line.relation() == Relation.ENEMY ? "status.danger"
              : line.relation() == Relation.NONE ? "text.secondary" : "accent.primary";
        row.add(kit.typeLabel(line.sender(), "body.strong", role)).top().left().padRight(space("sm"));
        Label text = kit.typeLabel(line.text(), "body", "text.primary");
        text.setWrap(true);
        text.setAlignment(Align.topLeft);
        row.add(text).growX().minWidth(0).top().left();
        return row;
    }
}
