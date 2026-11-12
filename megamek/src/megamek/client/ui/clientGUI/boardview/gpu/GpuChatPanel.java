/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * Chat panel attached above the command dock: the game's chat lines
 * (GpuChat), each sender over its consecutive messages, mint for a player and muted for MegaMek's own lines, then
 * the message field and Send. Enter or Send posts the draft through the board chat's send, which keeps it in the
 * chat history, and the field keeps the focus. The draft stays while the panel closes and opens again. The list shows the
 * newest line; while the panel is closed, a newer line than the last one seen puts the unread dot on the button.
 */
final class GpuChatPanel implements GpuHud.Component {
    /** The send row's field and button (#chat .send .b: min-height 34). */
    private static final float SEND_HEIGHT = 34;

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final Table root;
    private final UiButton button;
    private final UiButton queueButton;
    private final UiButton cornerButton;
    private final Table corner;
    private final Table lines = new Table();
    private final ScrollPane scroll;
    private final TextField field;
    private GpuChat.Snapshot shown;
    /** The id of the newest line shown while the panel was open; newer lines are unread. */
    private long seen;
    private boolean open;
    /** The list scrolls to its newest line once it is laid out. */
    private boolean scrollDown;
    /** Preferred height survives closing and temporary window constraints; the bottom stays attached to the dock. */
    private float height = 320;
    private float maximumHeight = 320;

    GpuChatPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        root = ui.panel();
        root.setName("chat-panel");
        Image grip = new Image(ui.skin.get("hud-split", SplitPane.SplitPaneStyle.class).handle);
        grip.setName("chat-resize");
        ui.tip(grip).getActor().setText(text("GpuBoard.hud.chat.resize"));
        grip.addListener(new InputListener() {
            private float startY;
            private float startHeight;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT) { return false; }
                startY = event.getStageY();
                startHeight = root.getHeight();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                height = MathUtils.clamp(startHeight + event.getStageY() - startY,
                      Math.min(144, maximumHeight), maximumHeight);
            }
        });
        root.add(grip).growX().height(9).row();
        root.add(ui.header(text("GpuBoard.hud.chat.title"), text("GpuBoard.hud.chat.allPlayers"),
              ui.closeButton(() -> state.chatOpen = false))).growX().row();
        // #chat .msgs: the list takes the free height, padded 4 14.
        lines.top().left().pad(4, 14, 4, 14);
        lines.defaults().growX();
        scroll = ui.scrollList(lines);
        root.add(scroll).grow().minHeight(0).row();
        field = new TextField("", ui.skin, "hud");
        field.setName("chat-input");
        field.setMessageText(text("GpuBoard.hud.chat.placeholder"));
        // Tab (MegaMek's next unit) neither moves the focus to another panel's field nor types into the message.
        field.setFocusTraversal(false);
        field.setTextFieldFilter((input, character) -> character != '\t');
        field.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                    send();
                    return true;
                }
                return false;
            }
        });
        UiButton send = ui.button("hud", null, text("GpuBoard.hud.chat.send"), null);
        send.setName("chat-send");
        UiKit.onChange(send, this::send);
        // #chat .send: padding 8 12 12, the field takes the width, 6 before the button.
        Table sendRow = new Table();
        sendRow.pad(8, 12, 12, 12);
        sendRow.add(field).growX().height(SEND_HEIGHT);
        sendRow.add(send).height(SEND_HEIGHT).padLeft(6);
        root.add(sendRow).growX();
        // The last mini button in every command-dock footer, pressed while the panel is open.
        button = toggle("chat-button");
        // Deployment hides the dock; the force's queue carries its own.
        queueButton = toggle("deploy-chat-button");
        cornerButton = toggle("corner-chat-button");
        corner = ui.panel();
        corner.setName("corner-chat");
        corner.pad(6);
        corner.add(cornerButton);
    }

    private UiButton toggle(String name) {
        UiButton toggle = ui.button("hud-mini", "chat", null, null);
        toggle.setName(name);
        ui.tip(toggle).getActor().setText(text("GpuBoard.hud.chat.title"));
        UiKit.onChange(toggle, () -> {
            state.chatOpen = !state.chatOpen;
            if (state.chatOpen) {
                focus();
            }
        });
        return toggle;
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The Chat button at the right end of the dock's footer. */
    UiButton button() {
        return button;
    }

    /** The Chat button in the deployment queue's header. */
    UiButton queueButton() {
        return queueButton;
    }

    /** The Chat button standing alone at the window's bottom right while neither the dock nor the queue shows. */
    Actor corner() {
        return corner;
    }

    /** The chosen height within the space above the dock in this frame. */
    float height(float available) {
        maximumHeight = Math.max(0, available);
        return Math.min(height, maximumHeight);
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuChat.Snapshot chat = inputs.frame().panels().chat();
        if (chat != shown) {
            shown = chat;
            lines.clearChildren();
            GpuChat.ChatLine previous = null;
            for (GpuChat.ChatLine line : chat.lines()) {
                boolean continued = previous != null && !previous.system() && !line.system()
                      && previous.sender().equals(line.sender());
                line(line, !continued);
                previous = line;
            }
            scrollDown = true;
        }
        if (state.chatOpen != open) {
            open = state.chatOpen;
            scrollDown |= open;
        }
        long newest = chat.lines().isEmpty() ? seen : chat.lines().getLast().id();
        if (open) {
            seen = Math.max(seen, newest);
        } else {
            blur();
        }
        button.pressed(open).dot(newest > seen);
        queueButton.pressed(open).dot(newest > seen);
        cornerButton.pressed(open).dot(newest > seen);
        if (scrollDown && open && root.getHeight() > 0) {
            // The prototype keeps the newest line in view (#chatlog scrollTop = scrollHeight).
            scrollDown = false;
            root.validate();
            scroll.setScrollPercentY(1);
            scroll.updateVisualScroll();
        }
    }

    /** One message: show the sender above the first message of each player group and every system notice. */
    private void line(GpuChat.ChatLine line, boolean showSender) {
        Table message = new Table();
        message.pad(showSender ? 5 : 0, 0, 5, 0);
        message.defaults().left().growX();
        if (showSender) {
            message.add(ui.label(line.sender(), "hud-name", 12, line.system() ? UiTheme.MUTED : UiTheme.MINT)).row();
        }
        Label text = ui.label(line.text(), "hud-body", 12.5f, UiTheme.TEXT);
        text.setWrap(true);
        message.add(text);
        lines.add(message).row();
    }

    /** Opens with the input focused (TOGGLE_CHAT, TOGGLE_CHAT_CMD); the typed "/" follows as a character. */
    void focus() {
        Stage stage = root.getStage();
        if (stage != null) {
            stage.setKeyboardFocus(field);
        }
    }

    /** A closed panel keeps no keyboard focus; its draft stays in the field. */
    private void blur() {
        Stage stage = root.getStage();
        if (stage != null && stage.getKeyboardFocus() != null && stage.getKeyboardFocus().isDescendantOf(root)) {
            stage.setKeyboardFocus(null);
        }
    }

    /** Posts the draft unless it is blank and empties the field, which keeps the focus (Enter or Send). */
    private void send() {
        String draft = field.getText();
        if (!draft.isBlank()) {
            source.chat().send(draft);
            field.setText("");
        }
        focus();
    }
}
