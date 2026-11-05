/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A billboard's settings: one picture per panel of its stack, from the top down (a decal of {@code decals/billboards},
 * empty for a blank panel), each panel's size in metres, and whether it stands on legs and carries its catwalk and lamps.
 * The size is the panels' alone: supports and lamps keep their real size and repeat along them. The object's anchor is the
 * middle of the lowest panel's lower edge, on the rear plane of its structure; its placement height lifts the stack, and
 * legs reach from there down to the support, so that height is also the legs' height.
 */
public record BoardBillboard(List<String> decals, double width, double height, boolean legs, boolean lights)
      implements Serializable {
    public static final String ASSET = "scenery/billboard";
    /** A brush's picture, or a request on a placed object: {@link BoardDecalArt#billboards} is picked from when it applies. */
    public static final String RANDOM = "random";
    public static final int MAX_PANELS = 8;
    /** Metres between two panels of a stack. */
    public static final double PADDING = .4;
    /** A 30 m hex is one {@link HexTile#MODEL_WIDTH} across. */
    public static final float UNITS_PER_METRE = HexTile.MODEL_WIDTH / 30;
    /** Metres of one board level, for a placement height shown as a leg height. */
    public static final double LEVEL_METRES = HexTile.LEVEL_HEIGHT / UNITS_PER_METRE;
    /** The panel stands one level over its support (about 21 feet under the panel, as a roadside billboard's does). */
    public static final double DEFAULT_LEVELS = 1;
    /** A blank US bulletin, the standard roadside billboard: 48 by 14 feet. */
    public static final BoardBillboard DEFAULT = new BoardBillboard(List.of(""), 14.63, 4.27, true, true);

    public BoardBillboard {
        decals = List.copyOf(decals);
        if (decals.isEmpty() || decals.size() > MAX_PANELS) {
            throw new IllegalArgumentException("A billboard has 1 to " + MAX_PANELS + " panels");
        }
        for (String decal : decals) {
            if (!decal.isEmpty() && !decal.equals(RANDOM) && (!decal.startsWith("decal/billboards/")
                  || decal.contains("..") || decal.contains("\\") || decal.contains(":"))) {
                throw new IllegalArgumentException("Choose a billboard decal");
            }
        }
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A billboard panel has a positive size");
        }
    }

    /**
     * These settings with one value changed: {@code decal0} to {@code decal7} is a panel's picture, {@code panels} their
     * number (panels go from the bottom of the stack; a new one takes {@link #RANDOM}), or {@code width}, {@code height},
     * {@code legs} and {@code lights}.
     */
    public BoardBillboard with(String property, String value) {
        var pictures = new ArrayList<>(decals);
        if (property.matches("decal[0-9]")) {
            int panel = property.charAt(5) - '0';
            if (panel >= decals.size()) { throw new IllegalArgumentException("No panel " + panel); }
            pictures.set(panel, value);
        } else if (property.equals("panels")) {
            int count = Integer.parseInt(value);
            if (count < 1 || count > MAX_PANELS) { throw new IllegalArgumentException("A billboard has 1 to " + MAX_PANELS + " panels"); }
            while (pictures.size() > count) { pictures.removeLast(); }
            while (pictures.size() < count) { pictures.add(RANDOM); }
        } else if (!List.of("width", "height", "legs", "lights").contains(property)) {
            throw new IllegalArgumentException("Unknown billboard property: " + property);
        }
        return new BoardBillboard(pictures, property.equals("width") ? Double.parseDouble(value) : width,
              property.equals("height") ? Double.parseDouble(value) : height,
              property.equals("legs") ? Boolean.parseBoolean(value) : legs,
              property.equals("lights") ? Boolean.parseBoolean(value) : lights);
    }

    public int panels() { return decals.size(); }

    /** The stack's height in metres: its panels and the padding between them. */
    public double stackHeight() { return panels() * height + (panels() - 1) * PADDING; }

    /** These settings with every panel blank. */
    public BoardBillboard blank() {
        return new BoardBillboard(Collections.nCopies(panels(), ""), width, height, legs, lights);
    }

    /** The drawn structure's bounds without its legs' run below the anchor: min x, y, z, max x, y, z in model units. */
    public float[] bounds() {
        float half = (float) width * UNITS_PER_METRE / 2;
        return new float[] { -half, -2.6f * UNITS_PER_METRE, 0, half, .7f * UNITS_PER_METRE, (float) stackHeight() * UNITS_PER_METRE };
    }
}
