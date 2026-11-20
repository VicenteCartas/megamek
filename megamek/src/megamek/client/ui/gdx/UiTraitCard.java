/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A card picked from a set, such as a bot's personality (docs/design-system/tokens.md, Trait cards): its name, a line
 * about it and a meter for each of its traits, so the set compares at a glance. The picked card takes the selectable
 * panel's look ({@link UiTheme#TOKEN_CHOICE_CARD}) and a check by its name, so the pick never rests on colour alone.
 * A click, or Enter or Space with the focus on it, fires its ChangeEvent; the view decides what is picked through
 * {@link #chosen(boolean)}, which fires nothing.
 * <p>
 * A picked card whose values were changed ({@link #modified(String)}) shows a tag by its name and, where the check
 * was, a reset button, whose ChangeEvent reaches the card's listeners as the card's own click would. Its meters then
 * show the changed values ({@link #current(int...)}): a step added to the card's own value lights in
 * {@code selected.primary}, a step taken away stays as an {@code accent.primary} outline. The name row is as high as
 * the reset button whether or not it shows, so the card never moves.
 */
public final class UiTraitCard extends Button {
    private final UiKit kit;
    private final Table traits = new Table();
    private final List<Meter> meters = new ArrayList<>();
    private final UiKit.Icon check;
    private final Label tag;
    private final UiButton reset;
    private final Drawable ring;
    private boolean chosen;
    private boolean modified;

    UiTraitCard(UiKit kit, String title, String blurb) {
        super(kit.skin, UiTheme.TOKEN_CHOICE_CARD);
        this.kit = kit;
        ring = kit.skin.getDrawable("token-card-focus");
        float icon = size("icon.sm");
        top().left();
        pad(space("md"), space("lg"), space("lg"), space("md"));
        Table head = new Table();
        Label name = kit.typeLabel(title, "body.strong", "text.primary");
        name.setEllipsis(true);
        head.add(name).minWidth(0).left();
        tag = kit.typeLabel("", "small", "text.muted");
        tag.setEllipsis(true);
        head.add(tag).growX().minWidth(0).left().padLeft(space("xs"));
        check = kit.icon("check-circle", icon, UiTheme.tokenColor("selected.primary"));
        check.setVisible(false);
        reset = kit.button("button-icon-compact", "undo", null, null);
        reset.setVisible(false);
        // The card would take the press as its own click as well; the reset's change still reaches it
        reset.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                event.stop();
                return false;
            }
        });
        Table checkCell = new Table();
        checkCell.add(check).size(icon).right();
        Stack corner = new Stack(checkCell, reset);
        head.add(corner).right().padLeft(space("xs"));
        add(head).growX().row();
        Label line = kit.typeLabel(blurb, "small", "text.secondary");
        line.setWrap(true);
        line.setAlignment(Align.topLeft);
        add(line).growX().minWidth(0).padTop(space("xxs")).row();
        // The meters sit at the foot, so cards of one row line them up whatever their lines' lengths
        add().expandY().row();
        traits.defaults().padTop(space("xs"));
        add(traits).growX().padTop(space("sm"));
    }

    /** Adds a meter for {@code name}, lit {@code value} steps of {@code max}; {@code value} is the card's own. */
    public UiTraitCard trait(String name, int value, int max) {
        Label label = kit.typeLabel(name, "small", "text.muted");
        label.setEllipsis(true);
        traits.add(label).growX().minWidth(0).left();
        Meter meter = new Meter(Math.max(0, Math.min(max, value)), max);
        meters.add(meter);
        traits.add(meter).right().padLeft(space("sm")).row();
        return this;
    }

    /** Shows {@code values} on the meters, in their order, against the card's own values; those show it unchanged. */
    public void current(int... values) {
        for (int index = 0; index < meters.size() && index < values.length; index++) {
            Meter meter = meters.get(index);
            meter.value = Math.max(0, Math.min(meter.max, values[index]));
        }
    }

    /** Marks this card as the picked one, or not, without an event. */
    public void chosen(boolean value) {
        chosen = value;
        corner();
    }

    /** Marks the picked card's values as changed, with {@code text} by its name, or as its own with {@code null}. */
    public void modified(String text) {
        modified = text != null;
        tag.setText(modified ? text : "");
        corner();
    }

    /** The reset button, for its name and tooltip; its click reaches the card's change listeners. */
    public UiButton resetButton() {
        return reset;
    }

    private void corner() {
        check.setVisible(chosen && !modified);
        reset.setVisible(chosen && modified);
        tag.setVisible(chosen && modified);
    }

    /** Whether this card is the picked one; a click alone leaves that to the view. */
    @Override
    public boolean isChecked() {
        return chosen;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        super.draw(batch, parentAlpha);
        if (hasKeyboardFocus()) {
            float out = UiTheme.CHOICE_RING;
            Color previous = batch.getColor().cpy();
            batch.setColor(1, 1, 1, getColor().a * parentAlpha);
            ring.draw(batch, getX() - out, getY() - out, getWidth() + 2 * out, getHeight() + 2 * out);
            batch.setColor(previous);
        }
    }

    private static float space(String token) {
        return DesignTokens.shipped().space().get(token);
    }

    private static float size(String token) {
        return DesignTokens.shipped().size().get(token);
    }

    /**
     * A row of steps: lit up to the value in accent.primary and the rest in border.control, against the card's own
     * value: a step above it lights in selected.primary, a step below it that is no longer lit keeps an accent outline.
     */
    private final class Meter extends Widget {
        private static final float STEP = 6;
        private final int base;
        private final int max;
        private final Drawable white;
        private int value;

        private Meter(int base, int max) {
            this.base = base;
            this.max = max;
            value = base;
            white = kit.skin.getDrawable("white");
        }

        @Override
        public float getPrefWidth() {
            return max * (STEP + space("xxs")) - space("xxs");
        }

        @Override
        public float getPrefHeight() {
            return space("sm");
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float alpha = getColor().a * parentAlpha;
            Color previous = batch.getColor().cpy();
            Color on = UiTheme.tokenColor("accent.primary");
            Color added = UiTheme.tokenColor("selected.primary");
            Color off = UiTheme.tokenColor("border.control");
            float gap = space("xxs");
            float step = (getWidth() - (max - 1) * gap) / max;
            float height = getHeight();
            for (int index = 0; index < max; index++) {
                float x = getX() + index * (step + gap);
                boolean lit = index < value;
                boolean own = index < base;
                if (!lit && own) {
                    batch.setColor(on.r, on.g, on.b, on.a * alpha);
                    white.draw(batch, x, getY(), step, 1);
                    white.draw(batch, x, getY() + height - 1, step, 1);
                    white.draw(batch, x, getY(), 1, height);
                    white.draw(batch, x + step - 1, getY(), 1, height);
                    continue;
                }
                Color color = lit ? (own ? on : added) : off;
                batch.setColor(color.r, color.g, color.b, color.a * alpha);
                white.draw(batch, x, getY(), step, height);
            }
            batch.setColor(previous);
        }
    }
}
