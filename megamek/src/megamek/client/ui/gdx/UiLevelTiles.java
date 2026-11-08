/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;

/**
 * A row of level tiles (docs/design-system/tokens.md, Level tiles): one tile per step of a scale, such
 * as how much damage to roll, each with its name and a pip meter of how far up the scale it is, lit in the step's own
 * colour role. The picked tile lights up: a wash and an edge in its colour, its name in text.primary. A click or Enter
 * on a tile fires its ChangeEvent; {@link #select} does not.
 */
public final class UiLevelTiles extends Table {
    /** The tiles, in the order of their names. */
    public final List<Tile> tiles = new ArrayList<>();
    private int selected = -1;

    /**
     * Tiles named {@code names}, the first with no pip lit and the last with all of them; {@code roles} are the colour
     * roles of the steps, such as accent.primary for none and status.danger for the most.
     */
    UiLevelTiles(UiKit kit, String[] names, String[] roles) {
        float gap = space("xs");
        for (int index = 0; index < names.length; index++) {
            Tile tile = new Tile(kit, names[index], roles[index], index, names.length - 1);
            tiles.add(tile);
            add(tile).growX().uniformX().height(size("control.md")).padLeft(index == 0 ? 0 : gap);
        }
    }

    /** Lights tile {@code index}, or none for a negative one, without an event. */
    public void select(int index) {
        selected = index;
    }

    /** The lit tile, or -1. */
    public int selected() {
        return selected;
    }

    private static float space(String token) {
        return DesignTokens.shipped().space().get(token);
    }

    private static float size(String token) {
        return DesignTokens.shipped().size().get(token);
    }

    /** One step: its name at the left and its pips at the right, over the token surface. */
    public final class Tile extends Button {
        private final Drawable white;
        private final Label name;
        private final Color color;
        private final int index;
        private final int pips;

        private Tile(UiKit kit, String text, String role, int index, int pips) {
            super(new ButtonStyle());
            white = kit.skin.getDrawable("white");
            color = UiTheme.tokenColor(role);
            this.index = index;
            this.pips = pips;
            name = kit.roleTypeLabel("button", text, "text.secondary");
            add(name).expandX().left().padLeft(space("md"));
            add(new Pips()).width(pips * (space("sm") + space("xxs"))).height(size("control.md") / 2)
                  .padRight(space("md"));
        }

        /** Whether this tile is the lit one; a click alone leaves that to the view. */
        @Override
        public boolean isChecked() {
            return selected == index;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            validate();
            boolean lit = isChecked();
            name.getStyle().fontColor = UiTheme.tokenColor(lit || isOver() ? "text.primary" : "text.secondary");
            float alpha = getColor().a * parentAlpha;
            float previous = batch.getPackedColor();
            float x = getX();
            float y = getY();
            float width = getWidth();
            float height = getHeight();
            paint(batch, UiTheme.tokenColor("surface.raised"), alpha);
            white.draw(batch, x, y, width, height);
            if (lit) {
                paint(batch, color, alpha * DesignTokens.shipped().opacity().get("tint.pressed"));
                white.draw(batch, x, y, width, height);
            } else if (isOver()) {
                paint(batch, UiTheme.tokenColor("surface.hover"), alpha);
                white.draw(batch, x, y, width, height);
            }
            Color edge = lit ? color : UiTheme.tokenColor(isOver() ? "border.strong" : "border.control");
            float line = lit ? 2 : 1;
            paint(batch, edge, alpha);
            frame(batch, x, y, width, height, line);
            if (lit) {
                // a bar along the bottom edge, as a lit indicator on a console
                white.draw(batch, x, y, width, space("xxs") + line);
            }
            if (hasKeyboardFocus()) {
                paint(batch, UiTheme.tokenColor("focus.ring"), alpha);
                float out = space("xxs") + 1;
                frame(batch, x - out, y - out, width + 2 * out, height + 2 * out, 1);
            }
            batch.setPackedColor(previous);
            super.draw(batch, parentAlpha);
        }

        private void frame(Batch batch, float x, float y, float width, float height, float line) {
            white.draw(batch, x, y, width, line);
            white.draw(batch, x, y + height - line, width, line);
            white.draw(batch, x, y, line, height);
            white.draw(batch, x + width - line, y, line, height);
        }

        private static void paint(Batch batch, Color color, float alpha) {
            batch.setColor(color.r, color.g, color.b, color.a * alpha);
        }

        /** The meter: {@code pips} bars rising in height, lit up to the tile's step. */
        private final class Pips extends Widget {
            @Override
            public void draw(Batch batch, float parentAlpha) {
                float alpha = getColor().a * parentAlpha;
                float previous = batch.getPackedColor();
                float bar = space("sm");
                float gap = space("xxs");
                Color off = UiTheme.tokenColor("border.control");
                for (int pip = 0; pip < pips; pip++) {
                    boolean on = pip < index;
                    // the bars rise as a signal meter's do, so the scale reads at a glance
                    float height = getHeight() * (pip + 2) / (pips + 1);
                    paint(batch, on ? color : off, alpha * (on ? 1 : .5f));
                    white.draw(batch, getX() + pip * (bar + gap), getY(), bar,
                          height);
                }
                batch.setPackedColor(previous);
            }
        }
    }
}
