/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.List;
import java.util.function.IntConsumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;

/** A stage-owned question/report. The callback gets the button index, or -1 for Escape/close. No resources to dispose. */
public final class UiMessageDialog {
    private UiMessageDialog() { }

    public static UiModal show(UiKit ui, Stage stage, String title, String message, List<String> answers, IntConsumer done) {
        Actor previous = stage.getKeyboardFocus();
        UiModal[] layer = new UiModal[1];
        boolean[] answered = { false };
        IntConsumer finish = answer -> {
            if (answered[0]) { return; }
            answered[0] = true;
            layer[0].open(false);
            layer[0].remove();
            if (previous != null && previous.getStage() == stage && previous.ascendantsVisible()) { stage.setKeyboardFocus(previous); }
            done.accept(answer);
        };
        layer[0] = new UiModal(ui, () -> finish.accept(-1), false);
        layer[0].setName("message-dialog");
        layer[0].setFillParent(true);
        Table dialog = ui.dialog(title, () -> finish.accept(-1));
        dialog.setTouchable(Touchable.enabled);
        var label = ui.label(message, "hud-body", 14, UiTheme.TEXT);
        label.setWrap(true);
        dialog.add(ui.scrollList(label)).grow().minSize(0).padTop(10).row();
        Table buttons = new Table();
        buttons.right();
        for (int index = 0; index < answers.size(); index++) {
            int answer = index;
            UiButton button = ui.button("hud-mini", null, answers.get(index), null);
            var style = new TextButton.TextButtonStyle(button.getStyle());
            style.focused = ui.skin.getDrawable("button-auto");
            button.setStyle(style);
            button.setName("message-answer-" + index);
            UiKit.onChange(button, () -> finish.accept(answer));
            buttons.add(button).minHeight(34).padLeft(8);
        }
        dialog.add(buttons).growX().padTop(16);
        layer[0].add(dialog).grow().maxWidth(640).maxHeight(350).pad(16);
        // Start at the last (safe) answer. Enter is handled on release by the button, never by the opening key.
        layer[0].addCaptureListener(new InputListener() {
            private int activation = -1;
            @Override public boolean keyDown(InputEvent event, int key) {
                if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER || key == Input.Keys.SPACE) {
                    activation = key; return true;
                }
                if (key != Input.Keys.TAB && key != Input.Keys.LEFT && key != Input.Keys.RIGHT) { return false; }
                if (!buttons.hasChildren()) { return false; }
                activation = -1;
                int at = buttons.getChildren().indexOf(stage.getKeyboardFocus(), true);
                int direction = key == Input.Keys.LEFT || key == Input.Keys.TAB
                      && (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) ? -1 : 1;
                stage.setKeyboardFocus(buttons.getChildren().get(Math.floorMod(at + direction, buttons.getChildren().size)));
                return true;
            }
            @Override public boolean keyUp(InputEvent event, int key) {
                if (key != activation) { return false; }
                activation = -1;
                int at = buttons.getChildren().indexOf(stage.getKeyboardFocus(), true);
                if (at >= 0) { finish.accept(at); }
                return true;
            }
        });
        stage.addActor(layer[0]);
        layer[0].open(true);
        if (buttons.hasChildren()) { stage.setKeyboardFocus(buttons.getChildren().peek()); }
        else { layer[0].focus(); }
        return layer[0];
    }
}
