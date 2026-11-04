/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import com.badlogic.gdx.scenes.scene2d.utils.Disableable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.design.DesignTokens;

/**
 * The Token v0 dialog (tokens.md "Dialogs"): a card over a {@code surface.scrim} layer ({@link UiModal}) that blocks
 * everything behind it, with a title and close button, a body that scrolls, and a footer whose main button comes first.
 * The card is as wide as its owner asks ({@code dialog.sm}, {@code dialog.md}, {@code dialog.lg} or wider), never
 * wider than the window less {@code xxxl} on each side, and as high as its body, never higher than the window less the
 * same margin; the body scrolls in what is left.
 * <p>
 * Keys: Escape and the close button cancel; Enter presses the focused button, or the main one when the focus is
 * elsewhere (a field, or nothing); Space presses the focused button; Tab and Shift+Tab move the focus through the
 * card's controls only, round and round. A click on the scrim does nothing: a dialog wants an answer. The card fades
 * in over {@code motion.fast} unless the player reduces motion, and the first field (or else the main button) takes
 * the focus. The dialog closes at once and gives the focus back to what had it.
 */
public final class UiDialog {
    /** A footer button's least width (tokens.md). */
    public static final float MIN_BUTTON_WIDTH = 88;
    /** How high an unfolding card starts, as a share of its height: a line across the screen. */
    public static final float FOLDED = .02f;

    private final UiKit kit;
    private final UiModal layer;
    private final Table card;
    private final Table body = new Table();
    private final ScrollPane scroll;
    private final Table leading = new Table();
    private final Table actions = new Table();
    private final List<UiButton> footerButtons = new ArrayList<>();
    private final Runnable cancel;
    private UiButton primary;
    private float width = size("dialog.md");
    private Actor focusBefore;
    private boolean unfold;

    /**
     * @param title  the card's title
     * @param cancel runs on Escape, the close button and a Cancel button; it should close the dialog
     */
    public UiDialog(UiKit kit, String title, Runnable cancel) {
        this.kit = kit;
        this.cancel = cancel;
        layer = new UiModal(kit, cancel, false);
        layer.setName("dialog-layer");
        layer.setFillParent(true);
        card = kit.dialog(title, cancel, true);
        card.setName("dialog");
        card.setTouchable(Touchable.enabled);
        body.top().left();
        scroll = kit.scrollList(body, true, false);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        card.add(scroll).grow().minHeight(0).row();
        Table foot = kit.footer(card, true);
        // The buttons sit centred on the card while the leading side leaves them xxl clear; else they go right
        foot.add(leading).expandX().left();
        foot.add(actions);
        foot.add(new Actor()).width(new Value() {
            @Override
            public float get(Actor context) {
                float half = (foot.getWidth() - foot.getPadX() - actions.getPrefWidth()) / 2;
                return (leading.getPrefWidth() + space("xxl") <= half) ? Math.max(0, half) : 0;
            }
        });
        layer.add(card).width(new Value() {
            @Override
            public float get(Actor context) {
                return Math.min(width, Math.max(0, layer.getWidth() - 2 * space("xxxl")));
            }
        }).maxHeight(new Value() {
            @Override
            public float get(Actor context) {
                return Math.max(0, layer.getHeight() - 2 * space("xxxl"));
            }
        });
        card.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return !event.isHandled() && key(keycode);
            }
        });
        layer.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return event.getTarget() == layer && key(keycode);
            }
        });
        layer.addCaptureListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode != Keys.TAB) {
                    return false;
                }
                event.cancel();
                cycle(Gdx.input.isKeyPressed(Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Keys.SHIFT_RIGHT) ? -1 : 1);
                return true;
            }
        });
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    /** Makes the card {@code token} wide ({@code dialog.sm}, {@code dialog.md} or {@code dialog.lg}). */
    public UiDialog width(String token) {
        return width(size(token));
    }

    /** Makes the card {@code value} wide, or the window less its margins when that is narrower. */
    public UiDialog width(float value) {
        width = value;
        layer.invalidateHierarchy();
        return this;
    }

    /**
     * Makes the card unfold as it opens, an immersive moment such as the welcome: it opens from a line across its
     * middle to its full height over {@code motion.medium} on the token easing, as a screen powering on, while the scrim
     * darkens over the same time. Without motion it opens at once as any dialog.
     */
    public UiDialog unfold(boolean unfold) {
        this.unfold = unfold;
        return this;
    }

    /** How long the card takes to unfold when it does, in seconds; {@link UiReveal} bands can wait for it. */
    public static float unfoldSeconds() {
        return DesignTokens.shipped().duration("medium") / 1000f;
    }

    /** The body, which scrolls; the owner fills it. */
    public Table body() {
        return body;
    }

    /** The scroll pane round the body. */
    public ScrollPane scroll() {
        return scroll;
    }

    /** The card. */
    public Table card() {
        return card;
    }

    /** The scrim layer that holds the card, as the stage has it. */
    public UiModal layer() {
        return layer;
    }

    /** The footer's leading side, left of the buttons, for a status line or tools. */
    public Table leading() {
        return leading;
    }

    /** Adds the main button, which comes first and which Enter presses: the primary button style. */
    public UiButton primary(String text, Runnable action) {
        primary = footerButton("button-primary", text, action, true);
        return primary;
    }

    /** Adds a secondary button after the main one; {@link #cancelButton} is the usual one. */
    public UiButton secondary(String text, Runnable action) {
        return footerButton("button-secondary", text, action, false);
    }

    /** Adds a Cancel button after the main one, which runs the dialog's cancel. */
    public UiButton cancelButton(String text) {
        return secondary(text, cancel);
    }

    /** Adds a quiet tool on the footer's leading side, such as Refresh or Reset. */
    public UiButton tool(String text, Runnable action) {
        UiButton button = kit.button("button-quiet", null, text, null);
        UiKit.onChange(button, action);
        leading.add(button).minWidth(MIN_BUTTON_WIDTH).padRight(space("md"));
        return button;
    }

    /** Adds a footer button; the main one goes first whenever it is added, so every footer reads main, then the rest. */
    private UiButton footerButton(String style, String text, Runnable action, boolean main) {
        UiButton button = kit.button(style, null, text, null);
        UiKit.onChange(button, action);
        footerButtons.add(main ? 0 : footerButtons.size(), button);
        float widest = MIN_BUTTON_WIDTH;
        for (UiButton each : footerButtons) {
            widest = Math.max(widest, each.getPrefWidth());
        }
        actions.clearChildren();
        for (UiButton each : footerButtons) {
            float gap = actions.getCells().isEmpty() ? 0 : space("md");
            actions.add(each).width(widest).padLeft(gap);
        }
        return button;
    }

    /** Shows the dialog over everything on {@code stage}; {@code motion} false opens it at once. */
    public void show(Stage stage, boolean motion) {
        focusBefore = stage.getKeyboardFocus();
        boolean unfolds = motion && unfold;
        layer.fadeIn(motion ? (unfolds ? unfoldSeconds() : DesignTokens.shipped().duration("fast") / 1000f) : 0);
        stage.addActor(layer);
        layer.open(true);
        layer.validate();
        if (unfolds) {
            unfoldCard();
        }
        Actor first = firstFocus();
        stage.setKeyboardFocus(first == null ? layer : first);
        stage.setScrollFocus(scroll);
    }

    private void unfoldCard() {
        float[] easing = DesignTokens.shipped().easing();
        card.setTransform(true);
        card.setOrigin(Align.center);
        card.setScaleY(FOLDED);
        card.addAction(new TemporalAction(unfoldSeconds()) {
            @Override
            protected void update(float percent) {
                card.setScaleY(FOLDED + (1 - FOLDED) * UiReveal.ease(percent, easing));
                if (Gdx.graphics != null) {
                    Gdx.graphics.requestRendering();
                }
            }

            @Override
            protected void end() {
                card.setScaleY(1);
                card.setTransform(false);
            }
        });
    }

    /** Whether the card is still unfolding. */
    public boolean unfolding() {
        return card.getScaleY() < 1;
    }

    /** Whether the dialog shows. */
    public boolean isOpen() {
        return layer.getStage() != null && layer.isVisible();
    }

    /** Closes the dialog at once and gives the focus back to what had it before. */
    public void close() {
        Stage stage = layer.getStage();
        layer.open(false);
        layer.remove();
        if (stage != null && focusBefore != null && focusBefore.getStage() == stage) {
            stage.setKeyboardFocus(focusBefore);
        }
        focusBefore = null;
    }

    private boolean key(int keycode) {
        Stage stage = layer.getStage();
        Actor focused = stage == null ? null : stage.getKeyboardFocus();
        if (keycode == Keys.ENTER || keycode == Keys.NUMPAD_ENTER) {
            if (focused instanceof Button button && inside(button)) {
                return press(button);
            }
            return primary != null && press(primary);
        }
        if (keycode == Keys.SPACE && focused instanceof Button button && inside(button)) {
            return press(button);
        }
        return false;
    }

    /** Presses {@code button} as a click would, unless it is disabled; true when it was pressed. */
    public static boolean press(Button button) {
        if (button.isDisabled() || !button.isTouchable()) {
            return false;
        }
        ChangeEvent change = Pools.obtain(ChangeEvent.class);
        try {
            button.fire(change);
        } finally {
            Pools.free(change);
        }
        return true;
    }

    private boolean inside(Actor actor) {
        return actor != null && actor.isDescendantOf(card);
    }

    /** The first field, or else the main button, or else the first control. */
    private Actor firstFocus() {
        List<Actor> controls = focusables();
        for (Actor control : controls) {
            if (control instanceof TextField) {
                return control;
            }
        }
        if (primary != null && !primary.isDisabled()) {
            return primary;
        }
        return controls.isEmpty() ? null : controls.getFirst();
    }

    private void cycle(int step) {
        List<Actor> controls = focusables();
        if (controls.isEmpty()) {
            return;
        }
        Stage stage = layer.getStage();
        int at = controls.indexOf(stage.getKeyboardFocus());
        int next = at < 0 ? (step > 0 ? 0 : controls.size() - 1) : Math.floorMod(at + step, controls.size());
        Actor target = controls.get(next);
        stage.setKeyboardFocus(target);
        if (target instanceof TextField field) {
            field.selectAll();
        }
        scroll.layout();
        if (target.isDescendantOf(body)) {
            Vector2 spot = target.localToAscendantCoordinates(body, new Vector2());
            scroll.scrollTo(spot.x, spot.y, target.getWidth(), target.getHeight());
        }
    }

    /** The card's controls that take the focus, in reading order: enabled buttons, fields, selects and sliders. */
    public List<Actor> focusables() {
        List<Actor> found = new ArrayList<>();
        collect(card, found);
        return found;
    }

    private static void collect(Actor actor, List<Actor> found) {
        if (!actor.isVisible() || actor.getTouchable() == Touchable.disabled) {
            return;
        }
        boolean disabled = actor instanceof Disableable disableable && disableable.isDisabled();
        if (actor instanceof TextField field) {
            field.setFocusTraversal(false);
            if (!field.isDisabled()) {
                found.add(field);
            }
            return;
        }
        if ((actor instanceof Button || actor instanceof UiSelect || actor instanceof Slider) && !disabled) {
            found.add(actor);
            return;
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                collect(child, found);
            }
        }
    }
}
