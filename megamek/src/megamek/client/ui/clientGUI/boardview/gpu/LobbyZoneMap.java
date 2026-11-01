/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.UnitConfigForm;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Board;

/**
 * The unit bay's deployment zone as a picture of the game's board: its terrain, the chosen zone's hexes tinted over
 * it as its offset and width make them, and the board in thirds, each the compass zone a click there chooses
 * ({@link UnitConfigForm#compassZone}), the one under the pointer lit. Each third is named {@code bay-zone-<id>} for
 * the probes.
 */
final class LobbyZoneMap extends Stack {
    /** The ratio of a flat-topped hex's height to its width, corner to corner. */
    private static final float HEX_HEIGHT = (float) Math.sqrt(3) / 2;
    /** The rows of thirds from the north, as {@link UnitConfigForm#compassZone} reads them. */
    private static final int[][] THIRDS = { { Board.START_NW, Board.START_N, Board.START_NE },
                                            { Board.START_W, Board.START_CENTER, Board.START_E },
                                            { Board.START_SW, Board.START_S, Board.START_SE } };
    /** How strongly the third under the pointer is lit. */
    private static final float HOVER_ALPHA = .22f;
    private static final int NONE = Integer.MIN_VALUE;
    /** The dark tag under a third's name: its padding and opacity. */
    private static final float TAG_PAD = 6;
    private static final float TAG_ALPHA = .72f;

    private final UiKit kit;
    private final IntSupplier chosen;
    private final Supplier<boolean[][]> hexes;
    private final float width;
    private final float height;
    private final Image overlay = new Image();
    private final Color light = UiTheme.tokenColor("accent.primary");
    private final Color shade = UiTheme.tokenColor("surface.base");
    private @Nullable Texture terrain;
    private @Nullable Texture zone;
    private int hovered = NONE;
    private boolean[][] shownHexes;

    /**
     * @param picture the board's terrain as {@link LobbyMapThumbnail#bake} draws it, or null to show the board bare
     * @param columns the board's width in hexes
     * @param rows    the board's height in hexes
     * @param side    the picture's longer side on screen
     * @param chosen  the chosen zone
     * @param hexes   the chosen zone's hexes, by column then row
     * @param choose  chooses a zone
     * @param names   the zones' names by start position
     */
    LobbyZoneMap(UiKit kit, @Nullable BufferedImage picture, int columns, int rows, float side, IntSupplier chosen,
          Supplier<boolean[][]> hexes, IntConsumer choose, String[] names) {
        this.kit = kit;
        this.chosen = chosen;
        this.hexes = hexes;
        float aspect = (columns * .75f + .25f) / Math.max(1, (rows + .5f) * HEX_HEIGHT);
        width = aspect >= 1 ? side : side * aspect;
        height = aspect >= 1 ? side / aspect : side;
        Image base;
        if (picture != null) {
            terrain = GpuStartScreen.texture(picture, false);
            base = new Image(new TextureRegionDrawable(terrain), Scaling.stretch);
        } else {
            base = kit.solid("surface.base");
        }
        add(base);
        overlay.setScaling(Scaling.stretch);
        add(overlay);
        Table thirds = new Table();
        // Each third takes the pointer, so a click on it is its own; the map's listener reads where it fell
        thirds.setTouchable(Touchable.childrenOnly);
        for (int[] row : THIRDS) {
            for (int id : row) {
                thirds.add(third(id, names[id])).expand().fill().uniform();
            }
            thirds.row();
        }
        add(thirds);
        setTouchable(Touchable.enabled);
        addListener(new ClickListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                hovered = zoneAt(x, y);
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, @Nullable Actor toActor) {
                super.exit(event, x, y, pointer, toActor);
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(LobbyZoneMap.this))) {
                    hovered = NONE;
                }
            }

            @Override
            public void clicked(InputEvent event, float x, float y) {
                choose.accept(zoneAt(x, y));
            }
        });
    }

    private int zoneAt(float x, float y) {
        return UnitConfigForm.compassZone(x / Math.max(1, getWidth()), 1 - y / Math.max(1, getHeight()));
    }

    private Actor third(int id, String name) {
        Label label = kit.typeLabel(name, "small", "text.secondary");
        label.setTouchable(Touchable.disabled);
        Container<Label> third = new Container<>(label) {
            @Override
            public void draw(Batch batch, float parentAlpha) {
                boolean picked = chosen.getAsInt() == id;
                if (hovered == id && !picked) {
                    kit.fill(batch, light, parentAlpha * HOVER_ALPHA, getX(), getY(), getWidth(), getHeight());
                }
                // A dark tag under the name keeps it legible on any terrain and over the zone's tint
                float tagWidth = label.getPrefWidth() + 2 * TAG_PAD;
                float tagHeight = label.getPrefHeight() + TAG_PAD;
                kit.fill(batch, shade, parentAlpha * TAG_ALPHA, getX() + (getWidth() - tagWidth) / 2,
                      getY() + (getHeight() - tagHeight) / 2, tagWidth, tagHeight);
                label.setColor(UiTheme.tokenColor(picked || hovered == id ? "text.primary" : "text.secondary"));
                super.draw(batch, parentAlpha);
            }
        };
        third.setTouchable(Touchable.enabled);
        third.setName("bay-zone-" + id);
        return third;
    }

    /** GPU thread: draws the chosen zone again when it changed. */
    void refresh() {
        boolean[][] shown = hexes.get();
        if (shownHexes != null && Arrays.deepEquals(shown, shownHexes)) {
            return;
        }
        shownHexes = shown == null ? new boolean[0][] : shown;
        if (zone != null) {
            zone.dispose();
            zone = null;
        }
        if (shown == null || shown.length == 0) {
            overlay.setDrawable(null);
            return;
        }
        int longSide = Math.round(Math.max(width, height) * 2);
        zone = GpuStartScreen.texture(LobbyMapThumbnail.bakeZone(shown, LobbyMapThumbnail.chosenStyle(), longSide),
              false);
        overlay.setDrawable(new TextureRegionDrawable(zone));
    }

    @Override
    public float getPrefWidth() {
        return width;
    }

    @Override
    public float getPrefHeight() {
        return height;
    }

    /** GPU thread: frees the pictures. */
    void dispose() {
        if (terrain != null) {
            terrain.dispose();
            terrain = null;
        }
        if (zone != null) {
            zone.dispose();
            zone = null;
        }
    }
}
