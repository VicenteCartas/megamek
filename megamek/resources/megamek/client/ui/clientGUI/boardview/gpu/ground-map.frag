#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The ground map's ground (GpuGroundMap): how much grows on the drawn ground, by what it is drawn as. Red is the
// meadow's share, green earth tundra's sward's, blue the ground a tundra tussock can stand on, alpha a field's crops.
// Its depth is the ground's height.
uniform float u_levelHeight;
uniform float u_metre;
// 1 for each family of the draw's palette (GpuSurfaceBlend), by slot, that is grass; that earth tundra's sward grows
// on; and that is snow, whose remnants the sward leaves. All 0 on ground of none.
uniform vec4 u_grass;
uniform vec4 u_earth;
uniform vec4 u_snow;
// The board's size in hexes.
uniform ivec2 u_board;
in vec3 v_world;
in vec3 v_normal;
in vec4 v_weights;
layout(location = 0) out vec4 fragColor;
// The board's hex table and biome stencil (terrain-hexes.glsl, terrain-biome-mask.glsl), which GpuGroundMap adds here.
// ground-map-functions

// The board hex holding a point, in metres: its column's, or the previous column's where that one's corner reaches in.
ivec2 boardHexAt(vec2 point) {
    const float width = 30.0, height = 30.0 * BOARD_HEX.y / BOARD_HEX.x;
    float across = point.x / (width * .75);
    int column = int(floor(across));
    ivec2 hex = ivec2(column, int(floor(-point.y / height - mod(float(column), 2.0) * .5)));
    if (fract(across) < 1.0 / 3.0) {
        ivec2 left = ivec2(column - 1, int(floor(-point.y / height - mod(float(column - 1), 2.0) * .5)));
        if (biomeHexDistance(point - boardHexCenter(left.x, left.y)) < biomeHexDistance(point - boardHexCenter(hex.x, hex.y))) {
            hex = left;
        }
    }
    return hex;
}

// Where earth tundra's sward grows between its openings of lichen, moss and gravel (as the ground's
// thatch in terrain-tundra.glsl).
float swardOpenings(vec2 p) {
    vec2 q = p + 6.0 * (vec2(biomeNoise(p / 19.0 + vec2(7.0, -3.0)), biomeNoise(p / 19.0 + vec2(-13.0, 5.0))) - .5);
    float field = .65 * biomeNoise(q / 13.0 + vec2(3.0, 11.0)) + .35 * biomeNoise(q / 4.1 + vec2(-17.0, 23.0));
    return smoothstep(.18, .5, field);
}

void main() {
    vec3 world = v_world / u_metre;
    // Its hex's ground may grow none (the table's blue, GpuBiomeSurface): frozen land is a coat of ice over it (1, or
    // black ice's 3), and bit 2 marks where a building stands or stood and scorched, airless or new-formed ground.
    ivec2 hex = boardHexAt(world.xy);
    if (all(greaterThanEqual(hex, ivec2(0))) && all(lessThan(hex, u_board))
          && (int(boardHex(hex).b * 255.0 + .5) & 7) != 0) {
        fragColor = vec4(0.0);
        return;
    }
    // The turf's own thresholds: cover thins where another ground mixes in and on steep faces.
    float up = normalize(v_normal).z, slope = smoothstep(.6, .95, up);
    float grass = dot(v_weights, u_grass);
    // A biome's ground lies over the turf: fields, marsh and mud grow their own cover, tundra its sward.
    vec4 cover, fringe;
    float tundra;
    biomeCoverage(world, 14.0, 0.0, cover, fringe, tundra);
    float meadow = grass * smoothstep(.55, .9, grass) * slope * (1.0 - clamp(dot(cover, vec4(1.0)) + tundra, 0.0, 1.0));
    // Earth tundra: its carpets where no pavement lies, clear of the Snow theme's remnants.
    float earth = tundra * dot(v_weights, u_earth)
          * (1.0 - dot(v_weights, u_snow) * smoothstep(.55, .72, biomeNoise(world.xy / 8.0 + 29.0)));
    float sward = earth * swardOpenings(world.xy);
    // A field's plants stand where it is cultivated and level.
    fragColor = vec4(meadow, sward * smoothstep(0.0, .6, sward) * slope, earth * slope, cover.x * smoothstep(.90, .94, up));
}
