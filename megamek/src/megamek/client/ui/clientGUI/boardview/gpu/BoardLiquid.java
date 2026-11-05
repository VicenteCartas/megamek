/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Locale;

import com.badlogic.gdx.graphics.Color;
import megamek.common.Hex;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.units.Terrains;

/**
 * Immutable liquid/crust appearance captured from terrain; depth and elevation stay on the tile. {@code separated}: the
 * sides the author parted from the water beside them ({@link HexAppearance#separated}), which {@link BoardScene.Tile#joins}
 * reads.
 */
record BoardLiquid(Kind kind, String theme, int rapids, int separated) {
    BoardLiquid(Kind kind, String theme, int rapids) { this(kind, theme, rapids, 0); }

    enum Kind { NONE, WATER, HAZARDOUS, MAGMA, MAGMA_CRUST }

    static final BoardLiquid NONE = new BoardLiquid(Kind.NONE, "", 0);
    static final BoardLiquid WATER = new BoardLiquid(Kind.WATER, "", 0);
    /** The green tint of a hazardous liquid's water art, in the board's liquid material and on the minimap. */
    static final Color HAZARDOUS_TINT = new Color(0.4f, 1, 0.12f, 1);

    /**
     * A themed water's colour: its frames are the plain water's, each linear channel their linear luminance times this,
     * fitted to the mean colours of the themes' former animations.
     */
    record Tone(float red, float green, float blue) { }

    private static final Tone MARS = new Tone(3.93f, 2.06f, .43f), VOLCANO = new Tone(1.44f, .51f, .61f);

    /** {@code tone}: the colour its water frames take ({@link Tone}), null for the plain water's own. */
    record Textures(String base, String foam, Tone tone) { }

    static BoardLiquid capture(Hex hex) {
        // Which hexes hold a liquid is common (HexTile.liquid), the legacy conversion's statement too.
        if (!HexTile.liquid(hex)) {
            return hex.terrainLevel(Terrains.MAGMA) == 1 ? new BoardLiquid(Kind.MAGMA_CRUST, "", 0) : NONE;
        }
        if (hex.terrainLevel(Terrains.MAGMA) == 2) {
            return new BoardLiquid(Kind.MAGMA, "", 0);
        }
        boolean hazardous = hex.containsTerrain(Terrains.HAZARDOUS_LIQUID);
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        // Hazardous pools use the ordinary water animation with a green material tint.
        if (hazardous || !(theme.equals("mars") || theme.equals("volcano"))) { theme = ""; }
        return new BoardLiquid(hazardous ? Kind.HAZARDOUS : Kind.WATER, theme,
              Math.clamp(hex.terrainLevel(Terrains.RAPIDS), 0, 2), HexAppearance.separated(hex.getAppearance(), "water"));
    }

    boolean present() { return kind != Kind.NONE && kind != Kind.MAGMA_CRUST; }

    boolean molten() { return kind == Kind.MAGMA; }

    boolean volcanic() { return molten() || kind == Kind.MAGMA_CRUST; }

    boolean connects(BoardLiquid other) {
        return present() && other.present() && molten() == other.molten();
    }

    /**
     * The liquid's Classic animations at {@code depth}, the same at every level; themed water is the plain water toned.
     */
    Textures textures(int depth) {
        String base = molten() ? "terrain/ground/base_magma_anim.gif"
              : "terrain/water/anim_water_" + Math.clamp(depth, 0, 4) + ".gif";
        String foam = rapids == 0 || molten() ? ""
              : "terrain/water/" + (rapids == 1 ? "rapids_anim.gif" : "torrent_anim.gif");
        Tone tone = switch (theme) {
            case "mars" -> MARS;
            case "volcano" -> VOLCANO;
            default -> null;
        };
        return new Textures(base, foam, tone);
    }
}
