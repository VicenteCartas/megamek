/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.List;

/** Flag artwork in normalized cloth coordinates; the local left edge is the attachment. */
public record BoardFlag(String pattern, String emblem, double x, double y, double scale, double rotation)
      implements Serializable {
    public static final String ASSET = "scenery/flag";
    public static final float WIDTH = 18, HEIGHT = 12;
    public static final List<String> PATTERNS = List.of("solid", "horizontal-2", "vertical-2", "horizontal-3",
          "vertical-3", "sectors-3", "diagonal", "diagonal-reverse", "cross", "nordic-cross", "saltire",
          "quarters", "chevron", "canton");
    public static final List<String> COLOURS = List.of("#c62828", "#ffffff", "#1565c0", "#ffd54f");
    public static final BoardFlag DEFAULT = new BoardFlag("solid", "", 0, 0, 1, 0);

    public BoardFlag {
        if (!PATTERNS.contains(pattern)) { throw new IllegalArgumentException("Unknown flag pattern: " + pattern); }
        if (emblem == null || !emblem.isEmpty() && (!emblem.startsWith("decal/emblems/")
              || emblem.contains("..") || emblem.contains("\\") || emblem.contains(":"))) {
            throw new IllegalArgumentException("Choose an emblem decal");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(scale) || scale <= 0
              || !Double.isFinite(rotation)) { throw new IllegalArgumentException("Invalid emblem transform"); }
    }

    public BoardFlag with(String property, String value) {
        if (!List.of("pattern", "emblem", "x", "y", "scale", "rotation").contains(property)) {
            throw new IllegalArgumentException("Unknown flag property: " + property);
        }
        return new BoardFlag(property.equals("pattern") ? value : pattern, property.equals("emblem") ? value : emblem,
              property.equals("x") ? Double.parseDouble(value) : x, property.equals("y") ? Double.parseDouble(value) : y,
              property.equals("scale") ? Double.parseDouble(value) : scale,
              property.equals("rotation") ? Double.parseDouble(value) : rotation);
    }

    /** Colour slot at u (left to right), v (top to bottom). */
    public int slot(double u, double v) {
        return switch (pattern) {
            case "horizontal-2" -> v < .5 ? 0 : 1;
            case "vertical-2" -> u < .5 ? 0 : 1;
            case "horizontal-3" -> Math.min(2, (int) (v * 3));
            case "vertical-3" -> Math.min(2, (int) (u * 3));
            case "sectors-3" -> u < 1 - Math.abs(2 * v - 1) ? 0 : v < .5 ? 1 : 2;
            case "diagonal" -> u < v ? 0 : 1;
            case "diagonal-reverse" -> u < 1 - v ? 0 : 1;
            case "cross", "nordic-cross" -> Math.abs(u - (pattern.equals("cross") ? .5 : .35)) < .065
                  || Math.abs(v - .5) < .1 ? 1 : 0;
            case "saltire" -> Math.abs(u - v) < .09 || Math.abs(u + v - 1) < .09 ? 1 : 0;
            case "quarters" -> (u < .5 ? 0 : 1) + (v < .5 ? 0 : 2);
            case "chevron" -> u < .5 * (1 - Math.abs(2 * v - 1)) ? 2 : v < .5 ? 0 : 1;
            case "canton" -> u < .45 && v < .5 ? 2 : Math.min(1, (int) (v * 2));
            default -> 0;
        };
    }
}
