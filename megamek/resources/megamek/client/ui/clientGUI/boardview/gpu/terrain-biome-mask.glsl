// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// After terrain-hexes.glsl, which supplies the per-hex texture, hash, noise and hex distance.
// Same five noise evaluations as BoardBiome.wetness, with the domain warp's Jacobian carried through each octave.
// This can stay inside a spatial wetland branch; no quad derivatives or extra neighbouring field evaluations.
vec3 biomeWetnessGradient(vec2 p) {
#ifdef normalQualityFlag
    // Two octaves without the domain warp: the same broad pools.
    vec3 broad = biomeNoiseGradient(p / 5.7), middle = biomeNoiseGradient(p / 2.0 + vec2(19.0, -7.0));
    return vec3(.55 * broad.x + .30 * middle.x + .075, .55 / 5.7 * broad.yz + .30 / 2.0 * middle.yz);
#endif
    vec3 warpX = biomeNoiseGradient(p / 4.0 + vec2(31.0, 0.0));
    vec3 warpY = biomeNoiseGradient(p / 4.0 + vec2(0.0, -23.0));
    vec2 q = p + 1.3 * (vec2(warpX.x, warpY.x) - .5);
    vec3 a = biomeNoiseGradient(q / 5.7), b = biomeNoiseGradient(q / 2.0 + vec2(19.0, -7.0));
    vec3 c = biomeNoiseGradient(q / .65);
    vec2 gradient = .55 / 5.7 * a.yz + .30 / 2.0 * b.yz + .15 / .65 * c.yz;
    gradient = gradient.x * (vec2(1.0, 0.0) + 1.3 / 4.0 * warpX.yz)
          + gradient.y * (vec2(0.0, 1.0) + 1.3 / 4.0 * warpY.yz);
    return vec3(.55 * a.x + .30 * b.x + .15 * c.x, gradient);
}

// The existing per-hex metadata proves a dry, uniform biome at this plateau's height.
bool biomePlateau(vec4 tile, float z) {
    float height = (tile.a * 255.0 - 64.0) * u_levelHeight / u_metre;
    return (int(tile.g * 255.0 + .5) & 16) != 0 && abs(z - height) <= .15;
}

// Conservative direct-material preflight: only uniform wet interiors with full slope support can bury the base.
// Edges retain the complete substrate. The regular biome stage still evaluates its coverage once, at its old site.
int opaqueWetBiome(vec3 world, vec3 face) {
    if (u_biomeBoard.x < 1.0 || face.z < .92) return 0;
    const float width = 30.0, height = 30.0 * BOARD_HEX.y / BOARD_HEX.x;
    int column = int(floor(world.x / (width * .75)));
    float rows = -world.y / height - mod(float(column), 2.0) * .5;
    ivec2 cell = ivec2(column, int(floor(rows)));
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(u_biomeBoard)))) return 0;
    vec4 tile = texelFetch(u_biomeHexes, cell, 0);
    int flags = int(tile.r * 255.0 + .5), kind = flags & 15;
    int quarter = (fract(world.x / (width * .75)) < .5 ? 4 : 5) + (fract(rows) < .5 ? 0 : 2);
    return kind >= 2 && kind <= 4 && ((flags >> quarter) & 1) != 0 && biomePlateau(tile, world.z) ? kind : 0;
}

// One bounded stencil serves both the shoreline treatment and the connected liquid's optical mixture.
// A negative waterLevel disables the liquid work for dry terrain. No additional textures or per-hex draws.
void terrainCoverage(vec3 world, float edge, float waterLevel, float fineMix, out vec4 cover, out vec4 fringe, out vec4 liquids,
      out float tundra) {
    cover = vec4(0.0); fringe = vec4(0.0); liquids = vec4(0.0); tundra = 0.0;
    if (u_biomeBoard.x < 1.0) return;
    const float width = 30.0, height = 30.0 * BOARD_HEX.y / BOARD_HEX.x;
    int column = int(floor(world.x / (width * .75)));
    // Only the quarters of this cell (halves of the column and of the row) that a biome hex, or liquids of two
    // palettes, can reach receive coverage, and R bits 4..7 of the own hex's texel say which (GpuBiomeSurface): every
    // other fragment is spared the stencil below. The empty result is what the stencil computes there: no cover, and
    // liquid callers fall back to their own water's palette. A point beyond the board edge keeps the stencil.
    float rows = -world.y / height - mod(float(column), 2.0) * .5;
    int home = int(floor(rows));
    ivec2 hex = clamp(ivec2(column, home), ivec2(0), ivec2(u_biomeBoard) - 1);
    int quarter = (fract(world.x / (width * .75)) < .5 ? 4 : 5) + (fract(rows) < .5 ? 0 : 2);
    if (hex == ivec2(column, home)) {
        vec4 tile = texelFetch(u_biomeHexes, hex, 0);
        int flags = int(tile.r * 255.0 + .5);
        if (((flags >> quarter) & 1) == 0) return;
        // The CPU proves all stencil candidates are dry and share this biome/elevation. On their supported
        // plateau each numerator equals its denominator, including the narrow field and broad bank masks.
        if (waterLevel <= -1000.0 && edge == 14.0 && biomePlateau(tile, world.z)) {
            int kind = flags & 15;
            cover = vec4(kind == 1, kind == 2, kind == 3, kind == 4);
            fringe = cover;
            tundra = float(kind == 5);
            return;
        }
    }
    bool aqueous = waterLevel > -1000.0;
    vec2 mixed = world.xy;
    if (aqueous) {
        mixed += (vec2(biomeNoise(world.xy / 8.0), biomeNoise(world.xy / 7.1 + 17.0)) - .5) * 4.0;
        mixed += (vec2(biomeNoise(mixed / 2.7 + 31.0), biomeNoise(mixed / 2.1 - 9.0)) - .5) * 2.0;
    }
    float filament = aqueous ? biomeNoise(mixed / 1.3) * 7.0 : 0.0;
    // Soil/peat reaches onto the adjoining slope; crops and standing pools keep the narrower height support.
    // Both masks share this stencil, with no extra texture fetches.
    float edgeNoise = biomeNoise(world.xy / 3.2 + 41.0);
    // Drainage below a wet plateau follows uneven peat fingers, not an equipotential waterline.
    // Above it, keep the narrow bank fringe: standing water cannot climb into a higher hex.
    float drainNoise = mix(edgeNoise, biomeNoise(world.xy / .85 + 9.0), .35 * fineMix);
    float drainage = mix(1.4, max(3.6, u_levelHeight / u_metre * 1.4), smoothstep(.15, .85, drainNoise));
    vec3 wetTiles = vec3(0.0);
    float wetAbove = 0.0, wetBelow = 0.0;
    float tundraTiles = 0.0, tundraAbove = 0.0, tundraBelow = 0.0;
    // Like peat, low mats continue between covered terraces and taper down an isolated bank.
    // Keep this field independent of screen derivatives so vegetation can use it on the CPU as well.
    float tundraDown = mix(1.4, max(3.6, u_levelHeight / u_metre * 1.4), smoothstep(.15, .85, edgeNoise));
    float total = 0.0, fieldTotal = 0.0, fringeTotal = 0.0;
    for (int dx = -1; dx <= 1; dx++) {
        int x = column + dx;
        float parity = mod(float(x), 2.0);
        int row = int(floor(-world.y / height - parity * .5));
        for (int dy = -1; dy <= 1; dy++) {
            int y = row + dy;
            vec2 center = boardHexCenter(x, y);
            float distance = biomeHexDistance(world.xy - center);
            float w = 1.0 - smoothstep(-edge, edge, distance);
            float fw = 1.0 - smoothstep(-2.2, 2.2, distance);
            float bw = 1.0 - smoothstep(-4.5, 4.5, distance + (edgeNoise - .5) * 2.0);
            float lw = aqueous ? 1.0 - smoothstep(-5.0, 5.0, biomeHexDistance(mixed - center)) : 0.0;
            // Fold narrow pigment filaments through the mixing band; uniform interiors remain unchanged.
            lw = clamp(lw + 4.0 * lw * (1.0 - lw) * .13 * sin(lw * 42.0 + filament) * fineMix, 0.0, 1.0);
            total += w;
            fieldTotal += fw;
            fringeTotal += bw;
            if ((w > 0.0 || lw > 0.0) && x >= 0 && y >= 0 && float(x) < u_biomeBoard.x && float(y) < u_biomeBoard.y) {
                vec4 tile = texelFetch(u_biomeHexes, ivec2(x, y), 0);
                float level = tile.a * 255.0 - 64.0;
                float z = level * u_levelHeight / u_metre;
                int kind = int(tile.r * 255.0 + .5) & 15;
                float tw = float(kind == 5) * w;
                float tundraReach = world.z < z ? tundraDown : 2.8 + edgeNoise * .8;
                tundra += tw * (1.0 - smoothstep(.15, tundraReach, abs(world.z - z)));
                tundraTiles += tw;
                tundraAbove += tw * step(world.z + .15, z);
                tundraBelow += tw * step(z + .15, world.z);
                vec4 kinds = vec4(kind == 1, kind == 2, kind == 3, kind == 4);
                cover += kinds * vec4(fw, w, w, w) * (1.0 - smoothstep(.15, 1.25, abs(world.z - z)));
                vec4 reach = vec4(2.0, 2.8, 2.8, 2.8) + edgeNoise * .8;
                if (world.z < z) reach.yzw = vec3(drainage);
                fringe += kinds * vec4(bw, w, w, w) * (1.0 - smoothstep(vec4(.15),
                      reach, vec4(abs(world.z - z))));
                vec3 wet = kinds.yzw * w;
                wetTiles += wet;
                float wetWeight = dot(wet, vec3(1.0));
                wetAbove += wetWeight * step(world.z + .15, z);
                wetBelow += wetWeight * step(z + .15, world.z);
                int liquid = int(tile.g * 255.0 + .5) & 15;
                liquids += vec4(liquid == 1, liquid == 2, liquid == 3, liquid == 4) * lw
                      * (1.0 - smoothstep(.05, .25, abs(waterLevel - level)));
            }
        }
    }
    cover /= max(vec4(fieldTotal, total, total, total), vec4(.0001));
    tundra /= max(total, .0001);
    float tundraConnected = smoothstep(0.0, .18, min(tundraAbove, tundraBelow) / max(total, .0001));
    tundra = max(tundra, tundraTiles / max(total, .0001) * tundraConnected);
    fringe /= max(vec4(fringeTotal, total, total, total), vec4(.0001));
    // Wet neighbours on both sides of this height share a peat bank across the whole drop.
    // Only sediment connects: the narrower cover mask still confines pools and plants to their supported level.
    float connected = smoothstep(0.0, .18, min(wetAbove, wetBelow) / max(total, .0001));
    fringe.yzw = max(fringe.yzw, wetTiles / max(total, .0001) * connected);
    liquids /= max(dot(liquids, vec4(1.0)), .0001);
}

// Callers without an earlier spatial branch may capture their footprint here, before the per-cell shortcuts.
void terrainCoverage(vec3 world, float edge, float waterLevel, out vec4 cover, out vec4 fringe, out vec4 liquids,
      out float tundra) {
    float fineMix = 1.0 - smoothstep(.08, .4, length(fwidth(world.xy)));
    terrainCoverage(world, edge, waterLevel, fineMix, cover, fringe, liquids, tundra);
}

void biomeCoverage(vec3 world, float edge, float footprint, out vec4 cover, out vec4 fringe, out float tundra) {
    vec4 liquids;
    terrainCoverage(world, edge, -10000.0, 1.0 - smoothstep(.08, .4, footprint), cover, fringe, liquids, tundra);
}

void biomeCoverage(vec3 world, float edge, out vec4 cover, out vec4 fringe, out float tundra) {
    vec4 liquids;
    terrainCoverage(world, edge, -10000.0, cover, fringe, liquids, tundra);
}

void terrainCoverage(vec3 world, float edge, float waterLevel, out vec4 cover, out vec4 fringe, out vec4 liquids) {
    float tundra;
    terrainCoverage(world, edge, waterLevel, cover, fringe, liquids, tundra);
}

vec4 liquidCoverage(vec3 world, float level) {
#ifdef biomeMaskFlag
    vec4 cover, fringe, liquids;
    terrainCoverage(world, .01, level, cover, fringe, liquids);
    return liquids;
#else
    // Without biomes or a second palette (GpuBiomeSurface) the stencil finds no liquid.
    return vec4(0.0);
#endif
}
