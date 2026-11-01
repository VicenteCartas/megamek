/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import megamek.common.Configuration;
import megamek.common.units.Terrains;

/**
 * The board assets a generator builds instead of a GLB in {@code models/board}, each a name or a pattern of names the
 * tileset, the editor and saved boards use like a model's path, for one use: a structure terrain's model or a placed
 * object's. {@link #exists} counts them with the GLB files, so a generated asset needs no file, and a saved board's id
 * ({@link BoardModelIds}) may name one. A new generated asset is a constant here and its generator in each view that
 * draws it.
 */
public enum BoardGenerator {
    /** A heavy industrial plant, assembled from its hex's equipment (the 3D board's BoardIndustrial). */
    HEAVY_INDUSTRIAL(Terrains.INDUSTRIAL, "buildings/(?:saxarba/)?misc/heavy_industrial_[a-d]"),
    /** A building generated over its whole footprint: habitat, dome or biodome (BoardMultiHexBuilding). */
    MULTI_HEX_BUILDING(Terrains.BUILDING, "buildings/multihex/(?:dome|biodome)"),
    /** A wall's kit pieces for its exits, 0 to 63 (BoardWall). */
    WALL(Terrains.BUILDING, "buildings/wall-(?:[0-9]|[1-5][0-9]|6[0-3])"),
    /** A rail route's marker: the route builder lays its track (BoardRail). */
    RAIL_ROUTE(Pattern.quote(RailRoute.Kind.RAIL.asset)),
    /** A maglev route's marker (BoardRail). */
    MAGLEV_ROUTE(Pattern.quote(RailRoute.Kind.MAGLEV.asset));

    /** {@link #exists}'s use for a placed object's model; a structure's is its terrain. */
    public static final int OBJECT = -1;

    /** Each asset name's generator, matched once: features ask per hex and per build. */
    private static final Map<String, Optional<BoardGenerator>> MATCHED = new ConcurrentHashMap<>();

    private final int use;
    private final Pattern names;

    /** A placed object's generator. */
    BoardGenerator(String names) { this(OBJECT, names); }

    /** A generator of {@code structure}'s model (Terrains.BUILDING, FUEL_TANK or INDUSTRIAL). */
    BoardGenerator(int structure, String names) {
        use = structure;
        this.names = Pattern.compile(names);
    }

    /** The generator that builds {@code asset}, or null when a GLB (or nothing) is its model. */
    public static BoardGenerator of(String asset) {
        if (asset == null) { return null; }
        return MATCHED.computeIfAbsent(asset, name -> Arrays.stream(values())
              .filter(generator -> generator.names.matcher(name).matches()).findFirst()).orElse(null);
    }

    /** Whether it builds its assets for {@code use}: a structure terrain (Terrains) or {@link #OBJECT}. */
    public boolean builds(int use) { return this.use == use; }

    /**
     * Whether {@code asset} has a model for {@code use} (a structure terrain, or {@link #OBJECT}): its GLB
     * ({@link BoardModelBounds#file}) or a generator that builds it for that use.
     */
    public static boolean exists(String asset, int use) {
        var generator = of(asset);
        return generator != null && generator.builds(use)
              || BoardModelBounds.file(new File(Configuration.dataDir(), "models/board"), asset).isFile();
    }
}
