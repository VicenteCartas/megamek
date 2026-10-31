/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.actions.SequenceAction;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyGameMaster.Ballot;
import megamek.client.lobby.LobbyGameMaster.Choice;
import megamek.client.lobby.LobbyGameMaster.Seat;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTheme;

/**
 * The Game Master's seat at the head of the lobby table, in the top bar: a framed special button with the violet shield
 * that reads the seat at a glance. Empty, it offers the seat; while a vote runs it shows one pip per voter, filling in
 * as the ballots come; held, it names who sits there, lit for the local player. A click opens the seat's card with
 * what can be done from where the player stands: ask for the seat, vote on someone's request, withdraw one's own, or
 * step down. The card opens on its own when a vote waits for the local player, and the seat flashes when it changes
 * hands, unless motion is reduced. A game that allows no Game Master shows the seat locked.
 */
final class LobbyGameMasterSeat extends Table {
    /** The width of the seat card's text, so its lines wrap. */
    static final float CARD_TEXT_WIDTH = 300;

    /** What the seat's controls do; any thread may be called. */
    interface SeatActions {
        /** Asks for the seat, or gives it up while holding it. */
        void claim();

        /** Votes in the running vote: for the request when {@code allow}, else against it. */
        void vote(boolean allow);

        /** Withdraws the local player's own request, which ends its vote. */
        void withdraw();
    }

    private final UiKit kit;
    private final SeatActions actions;
    private final boolean motion;
    private final UiPopover card;
    private final Color violet = UiTheme.tokenColor("status.gamemaster");
    private LobbyGameMaster shown = LobbyGameMaster.OFF;
    private UiButton seat;
    private TextTooltip tip;
    private boolean voteOpened;
    private boolean everShown;

    LobbyGameMasterSeat(UiKit kit, SeatActions actions, boolean motion) {
        this.kit = kit;
        this.actions = actions;
        this.motion = motion;
        card = new UiPopover(kit, () -> { }, UiTheme.TOKEN);
        card.setName("lobby-gm-card");
        rebuild();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    /** Adds the seat card to {@code layer}, above the columns; once, before the seat shows a snapshot. */
    void attach(Group layer) {
        layer.addActor(card);
    }

    /** One Escape step: closes the open seat card; true when it was open. */
    boolean back() {
        return card.back();
    }

    /** For tests and the probe: the seat's button. */
    UiButton button() {
        return seat;
    }

    /** For tests and the probe: the seat card. */
    UiPopover card() {
        return card;
    }

    /** Shows {@code gameMaster}: rebuilds the seat when its face changes, flashes it when it changes hands. */
    void show(LobbyGameMaster gameMaster) {
        LobbyGameMaster before = shown;
        shown = gameMaster;
        if (!face(before).equals(face(gameMaster))) {
            rebuild();
        }
        if (card.isVisible()) {
            if (gameMaster.seat() == Seat.OFF) {
                card.cancel();
            } else {
                fillCard();
            }
        }
        boolean handedOver = !gameMaster.holder().isEmpty() && !gameMaster.holder().equals(before.holder());
        if (everShown && handedOver) {
            flash();
        }
        if (gameMaster.seat() != Seat.VOTE) {
            voteOpened = false;
        } else if (!voteOpened && gameMaster.awaitsSelf()) {
            voteOpened = true;
            open();
        }
        everShown = true;
    }

    /**
     * What the seat's button shows; equal faces need no rebuild, so a refresh that changes nothing here keeps the
     * button and its hover.
     */
    private static List<Object> face(LobbyGameMaster gameMaster) {
        List<Object> face = new ArrayList<>();
        face.add(gameMaster.seat());
        face.add(gameMaster.holder());
        gameMaster.ballots().forEach(ballot -> face.add(ballot.choice()));
        return face;
    }

    private void rebuild() {
        clearChildren();
        Seat state = shown.seat();
        String icon = state == Seat.OFF ? "lock" : "shield-person";
        String text = switch (state) {
            case VOTE -> Messages.getString("Lobby.gm.vote");
            case HELD -> Messages.getString("Lobby.gm.held", shown.holder());
            default -> Messages.getString("Lobby.gm");
        };
        seat = kit.button("button-special", icon, text, null);
        seat.setName("lobby-gm-seat");
        if (state != Seat.OFF) {
            // The shield keeps the Game Master's violet on every face but the lit one, whose ink it takes
            Actor shield = seat.icons.removeFirst();
            if (state != Seat.MINE) {
                seat.iconColors.put(shield, violet);
            } else {
                seat.icons.add(shield);
            }
        }
        if (state == Seat.VOTE) {
            Table pips = new Table();
            for (Ballot ballot : shown.ballots()) {
                pips.add(pip(ballot.choice(), size("icon.sm"))).size(size("icon.sm")).padLeft(space("xxs"));
            }
            seat.add(pips).padLeft(space("xs"));
        }
        seat.setChecked(state == Seat.MINE);
        seat.setDisabled(state == Seat.OFF);
        tip = kit.tip(seat, true);
        tip.getActor().setText(tooltip());
        UiKit.onChange(seat, this::open);
        add(seat);
        invalidateHierarchy();
    }

    private String tooltip() {
        return switch (shown.seat()) {
            case OFF -> Messages.getString("Lobby.gm.off.tooltip");
            case OPEN -> Messages.getString("Lobby.gm.open.tooltip");
            case VOTE -> Messages.getString("Lobby.gm.vote.tooltip", shown.requester(),
                  shown.count(Choice.YES), shown.count(Choice.NO), shown.count(Choice.PENDING));
            case MINE -> Messages.getString("Lobby.gm.mine.tooltip");
            case HELD -> Messages.getString("Lobby.gm.held.tooltip", shown.holder());
        };
    }

    /** A ballot's pip: a violet check for yes, a red cross for no, an empty ring while it waits. */
    private UiKit.Icon pip(Choice choice, float size) {
        return switch (choice) {
            case YES -> kit.icon("check-circle", size, violet);
            case NO -> kit.icon("cancel", size, UiTheme.tokenColor("status.danger"));
            case PENDING -> kit.icon("radio-unchecked", size, UiTheme.tokenColor("text.muted"));
        };
    }

    /** Opens the seat card under the seat, or closes it when it is open. */
    private void open() {
        if (card.isVisible()) {
            card.cancel();
            return;
        }
        if (card.getStage() == null || shown.seat() == Seat.OFF) {
            return;
        }
        fillCard();
        card.openedFrom(seat);
        Vector2 corner = seat.localToStageCoordinates(new Vector2(0, 0));
        card.showAt(corner.x, corner.y);
    }

    private void fillCard() {
        Table body = new Table();
        body.pad(space("sm"), space("md"), space("md"), space("md"));
        body.defaults().left().growX();
        switch (shown.seat()) {
            case OPEN -> {
                card.header(Messages.getString("Lobby.gm.card.title"), Messages.getString("Lobby.gm.open.detail"));
                body.add(text(Messages.getString("Lobby.gm.open.tooltip"))).width(CARD_TEXT_WIDTH).row();
                body.add(buttons(action("button-primary", "shield-person", "Lobby.gm.claim", "lobby-gm-claim",
                      actions::claim)));
            }
            case VOTE -> fillVote(body);
            case MINE -> {
                card.header(Messages.getString("Lobby.gm.card.title"), Messages.getString("Lobby.gm.mine.detail"));
                body.add(text(Messages.getString("Lobby.gm.powers"))).width(CARD_TEXT_WIDTH).row();
                body.add(buttons(action("button-danger", null, "Lobby.gm.stepDown", "lobby-gm-step-down",
                      actions::claim)));
            }
            case HELD -> {
                card.header(Messages.getString("Lobby.gm.card.title"),
                      Messages.getString("Lobby.gm.held.detail", shown.holder()));
                body.add(text(Messages.getString("Lobby.gm.held.tooltip", shown.holder()))).width(CARD_TEXT_WIDTH);
            }
            default -> { }
        }
        card.content(body);
    }

    private void fillVote(Table body) {
        String detail = shown.requestedBySelf() ? Messages.getString("Lobby.gm.vote.detailSelf")
              : Messages.getString("Lobby.gm.vote.detail", shown.requester());
        card.header(Messages.getString("Lobby.gm.vote.title"), detail);
        Table ballots = new Table();
        ballots.defaults().left().padTop(space("xs"));
        for (Ballot ballot : shown.ballots()) {
            ballots.add(pip(ballot.choice(), size("icon.md"))).size(size("icon.md")).padRight(space("sm"));
            String name = ballot.name().isEmpty() ? Messages.getString("GameMasterVoteDialog.unknownPlayer")
                  : ballot.name();
            Label voter = kit.typeLabel(ballot.self() ? Messages.getString("Lobby.gm.you", name) : name, "body",
                  "text.primary");
            voter.setEllipsis(true);
            ballots.add(voter).growX().minWidth(0);
            String choice = switch (ballot.choice()) {
                case YES -> "GameMasterVoteDialog.vote.yes";
                case NO -> "GameMasterVoteDialog.vote.no";
                case PENDING -> "Lobby.gm.waiting";
            };
            ballots.add(kit.typeLabel(Messages.getString(choice), "small", ballot.choice() == Choice.PENDING
                  ? "text.muted" : "text.secondary")).right().padLeft(space("md")).row();
        }
        body.add(ballots).width(CARD_TEXT_WIDTH).row();
        body.add(kit.typeLabel(Messages.getString(shown.majority() ? "GameMasterVoteDialog.threshold.majority"
              : "GameMasterVoteDialog.threshold.unanimous"), "small", "text.muted")).padTop(space("sm")).row();
        if (shown.awaitsSelf()) {
            body.add(buttons(action("button-danger", null, "Lobby.gm.deny", "lobby-gm-deny",
                        () -> actions.vote(false)),
                  action("button-primary", "check", "Lobby.gm.allow", "lobby-gm-allow", () -> actions.vote(true))));
        } else if (shown.requestedBySelf()) {
            body.add(buttons(action("button-secondary", null, "Lobby.gm.withdraw", "lobby-gm-withdraw",
                  actions::withdraw)));
        }
    }

    private Label text(String text) {
        Label label = kit.typeLabel(text, "body", "text.secondary");
        label.setWrap(true);
        // A wrapped label's height follows its width, which the card measures before laying out its cells
        label.setWidth(CARD_TEXT_WIDTH);
        return label;
    }

    /** The card's buttons, at its trailing edge as a dialog's footer has them. */
    private Table buttons(UiButton... buttons) {
        Table row = new Table();
        row.right();
        for (UiButton button : buttons) {
            row.add(button).padLeft(space("sm"));
        }
        row.padTop(space("md"));
        return row;
    }

    /** A card button that closes the card and does {@code action}. */
    private UiButton action(String style, String icon, String key, String name, Runnable action) {
        UiButton button = kit.button(style, icon, Messages.getString(key), null);
        button.setName(name);
        UiKit.onChange(button, () -> {
            card.cancel();
            action.run();
        });
        return button;
    }

    /** Flashes the seat a few times as it changes hands, ending on its resting face. */
    private void flash() {
        if (!motion || seat == null) {
            return;
        }
        UiButton target = seat;
        boolean rest = shown.seat() == Seat.MINE;
        target.clearActions();
        float step = LobbyTopBar.PULSE_SECONDS / (2 * LobbyTopBar.PULSE_FLASHES);
        SequenceAction flashes = Actions.sequence();
        for (int flash = 0; flash < LobbyTopBar.PULSE_FLASHES; flash++) {
            flashes.addAction(Actions.run(() -> target.setChecked(!rest)));
            flashes.addAction(Actions.delay(step));
            flashes.addAction(Actions.run(() -> target.setChecked(rest)));
            flashes.addAction(Actions.delay(step));
        }
        target.addAction(flashes);
    }
}
