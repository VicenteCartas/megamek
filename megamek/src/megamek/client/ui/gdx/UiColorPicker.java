/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.Locale;
import java.util.function.IntConsumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;

/**
 * A reusable colour input: a saturation and brightness field over a hue strip, both drawn on the GPU as shaded quads,
 * the colour as a hex text, and a row of presets. Dragging in the field or the strip previews each colour and commits
 * the one it is let go at; a typed hex colour or a preset commits at once. Colours are 0xRRGGBB.
 */
public final class UiColorPicker extends Table {
    /** The skin's white swatch, tinted to a colour; its frame; and the field's and strip's handle ring. */
    public static final String SWATCH = "color-swatch", FRAME = "color-frame", RING = "color-ring";
    /** The handle ring's diameter, in stage units. */
    public static final float HANDLE = 14;
    private static final float FIELD_WIDTH = 248, FIELD_HEIGHT = 148, STRIP_HEIGHT = 14, PRESET = 22;

    private final UiKit kit;
    private final IntConsumer preview;
    private final IntConsumer commit;
    private final Texture white;
    private final Drawable ring;
    private final Drawable frame;
    private final float[] vertices = new float[20];
    private final Image swatch;
    private final UiTextField hex;
    private final Table presets = new Table();
    /** The colour as hue (0..1), saturation and brightness, which keeps the hue of a grey while it is dragged. */
    private float hue, saturation, brightness;
    private boolean typing;

    /**
     * @param preview hears each colour while the field or the strip is dragged
     * @param commit  hears the colour the player settled on
     */
    public UiColorPicker(UiKit kit, IntConsumer preview, IntConsumer commit) {
        this.kit = kit;
        this.preview = preview;
        this.commit = commit;
        white = kit.skin.get("white", Texture.class);
        ring = kit.skin.getDrawable(RING);
        frame = kit.skin.getDrawable(FRAME);
        float gap = DesignTokens.shipped().space().get("sm");

        Field field = new Field();
        field.setName("color-field");
        Strip strip = new Strip();
        strip.setName("color-hue");
        swatch = new Image(kit.skin.getDrawable(SWATCH));
        swatch.setName("color-swatch");
        hex = kit.input("", true);
        hex.setName("color-hex");
        hex.setMaxLength(7);
        hex.setTextFieldFilter((current, typed) -> typed == '#' || Character.digit(typed, 16) >= 0);
        UiKit.onChange(hex, () -> {
            int rgb = parse(hex.getText());
            if (rgb >= 0 && rgb != value()) {
                typing = true;
                try {
                    value(rgb);
                } finally {
                    typing = false;
                }
                commit.accept(rgb);
            }
        });

        top().left().defaults().left();
        setTouchable(Touchable.enabled);
        add(field).size(FIELD_WIDTH, FIELD_HEIGHT).row();
        add(strip).size(FIELD_WIDTH, STRIP_HEIGHT).padTop(gap).row();
        Table readout = new Table();
        readout.add(swatch).size(2 * PRESET, PRESET + 6);
        readout.add(hex).growX().minWidth(0).padLeft(gap);
        add(readout).width(FIELD_WIDTH).padTop(gap).row();
        presets.left().defaults().left();
        add(presets).width(FIELD_WIDTH).padTop(gap).row();
        value(0);
    }

    /** The colours to offer as presets, each a swatch that picks it; replaces the earlier ones. */
    public UiColorPicker presets(int... colors) {
        presets.clearChildren();
        float gap = DesignTokens.shipped().space().get("xs");
        int perRow = Math.max(1, (int) ((FIELD_WIDTH + gap) / (PRESET + gap)));
        for (int index = 0; index < colors.length; index++) {
            int rgb = colors[index] & 0xFFFFFF;
            Image preset = new Image(kit.skin.getDrawable(SWATCH));
            preset.setColor(color(rgb, new Color()));
            preset.setName("color-preset-" + index);
            preset.setTouchable(Touchable.enabled);
            preset.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    value(rgb);
                    commit.accept(rgb);
                }
            });
            presets.add(preset).size(PRESET).padRight(gap).padBottom(gap);
            if ((index + 1) % perRow == 0) {
                presets.row();
            }
        }
        return this;
    }

    /** Shows {@code rgb} without telling of it. */
    public void value(int rgb) {
        float[] hsv = new float[3];
        color(rgb & 0xFFFFFF, new Color()).toHsv(hsv);
        // A grey or black has no hue of its own; it keeps the one shown
        if (hsv[1] > 0 && hsv[2] > 0) {
            hue = hsv[0] / 360f;
        }
        saturation = hsv[1];
        brightness = hsv[2];
        show();
    }

    /** The colour shown, as 0xRRGGBB. */
    public int value() {
        return rgb(new Color().fromHsv(hue * 360, saturation, brightness));
    }

    /** The hex text field, for the keyboard focus. */
    public UiTextField hex() {
        return hex;
    }

    private void show() {
        int rgb = value();
        swatch.setColor(color(rgb, new Color()));
        if (!typing) {
            hex.setText(text(rgb));
            hex.setCursorPosition(hex.getText().length());
        }
    }

    /** The colour as "#rrggbb". */
    public static String text(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    /** The colour of a "#rrggbb" or "rrggbb" text, -1 for any other. */
    public static int parse(String text) {
        String digits = text.strip().startsWith("#") ? text.strip().substring(1) : text.strip();
        if (digits.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static Color color(int rgb, Color into) {
        return into.set((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1);
    }

    private static int rgb(Color color) {
        return Math.round(color.r * 255) << 16 | Math.round(color.g * 255) << 8 | Math.round(color.b * 255);
    }

    /** Draws a quad over the white texel, each corner its own colour: bottom left, top left, top right, bottom right. */
    private void quad(Batch batch, float x, float y, float width, float height, float bottomLeft, float topLeft,
          float topRight, float bottomRight) {
        float u = .5f / white.getWidth(), v = .5f / white.getHeight();
        float[] corners = { x, y, bottomLeft, x, y + height, topLeft, x + width, y + height, topRight, x + width, y,
              bottomRight };
        for (int corner = 0; corner < 4; corner++) {
            vertices[corner * 5] = corners[corner * 3];
            vertices[corner * 5 + 1] = corners[corner * 3 + 1];
            vertices[corner * 5 + 2] = corners[corner * 3 + 2];
            vertices[corner * 5 + 3] = u;
            vertices[corner * 5 + 4] = v;
        }
        batch.draw(white, vertices, 0, vertices.length);
    }

    private static float packed(Color color, float alpha) {
        return Color.toFloatBits(color.r, color.g, color.b, color.a * alpha);
    }

    /** A drag in a part of the picker: each point previews, the release commits. */
    private abstract class Drag extends Widget {
        Drag() {
            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    if (button != Input.Buttons.LEFT || pointer != 0) {
                        return false;
                    }
                    point(x, y);
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    point(x, y);
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                    commit.accept(value());
                }
            });
        }

        private void point(float x, float y) {
            pick(MathUtils.clamp(x / getWidth(), 0, 1), MathUtils.clamp(y / getHeight(), 0, 1));
            show();
            preview.accept(value());
        }

        /** Takes the point the pointer is at, each a share of the part's width and height. */
        abstract void pick(float across, float up);

        void handle(Batch batch, float x, float y, float alpha) {
            batch.setColor(1, 1, 1, alpha);
            ring.draw(batch, x - HANDLE / 2, y - HANDLE / 2, HANDLE, HANDLE);
        }
    }

    /** Saturation grows to the right and brightness upward, in the hue of the strip. */
    private final class Field extends Drag {
        @Override
        void pick(float across, float up) {
            saturation = across;
            brightness = up;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float saved = batch.getPackedColor();
            Color pure = new Color().fromHsv(hue * 360, 1, 1);
            float plain = packed(Color.WHITE, parentAlpha);
            float tint = packed(pure, parentAlpha);
            quad(batch, getX(), getY(), getWidth(), getHeight(), plain, plain, tint, tint);
            float clear = Color.toFloatBits(0, 0, 0, 0);
            float black = Color.toFloatBits(0, 0, 0, parentAlpha);
            quad(batch, getX(), getY(), getWidth(), getHeight(), black, clear, clear, black);
            batch.setColor(1, 1, 1, parentAlpha);
            frame.draw(batch, getX(), getY(), getWidth(), getHeight());
            handle(batch, getX() + saturation * getWidth(), getY() + brightness * getHeight(), parentAlpha);
            batch.setPackedColor(saved);
        }
    }

    /** The hues from red around to red again, left to right. */
    private final class Strip extends Drag {
        @Override
        void pick(float across, float up) {
            hue = Math.min(across, .999f);
            if (saturation == 0 && brightness == 0) {
                saturation = 1;
                brightness = 1;
            }
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float saved = batch.getPackedColor();
            Color from = new Color(), to = new Color();
            float step = getWidth() / 6;
            for (int piece = 0; piece < 6; piece++) {
                float left = packed(from.fromHsv(piece * 60, 1, 1), parentAlpha);
                float right = packed(to.fromHsv((piece + 1) * 60 % 360, 1, 1), parentAlpha);
                quad(batch, getX() + piece * step, getY(), step, getHeight(), left, left, right, right);
            }
            batch.setColor(1, 1, 1, parentAlpha);
            frame.draw(batch, getX(), getY(), getWidth(), getHeight());
            handle(batch, getX() + hue * getWidth(), getY() + getHeight() / 2, parentAlpha);
            batch.setPackedColor(saved);
        }
    }

    @Override
    public float getPrefWidth() {
        return Math.max(FIELD_WIDTH, super.getPrefWidth());
    }
}
