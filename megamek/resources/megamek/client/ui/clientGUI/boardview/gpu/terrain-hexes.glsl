// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One texel per board hex (GpuBiomeSurface) and the world fields every lit surface can share with it.
// R biome/stencil quarters, low G aqueous palette + 1 (bit 4 uniform biome), B ice, A elevation + 64.
// BOARD_HEX
uniform vec2 u_biomeBoard;
// The table's first row in the local lights' texture (GpuLavaLighting), which it shares so that ground and water keep
// within their texture units, and the board's width.
uniform ivec2 u_hexTable;
vec4 boardHex(ivec2 hex) {
    int index = hex.y * u_hexTable.y + hex.x;
    return texelFetch(u_lavaLights, ivec2(index & 1023, u_hexTable.x + (index >> 10)), 0);
}

// BoardRelief.hash/noise in GLSL: roots and wetland material evaluate the same world field.
float biomeHash(ivec2 p) {
    uint h = uint(p.x) * 374761393u + uint(p.y) * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return float((h ^ (h >> 16)) & 0x00ffffffu) / 16777216.0;
}
vec4 biomeNoiseCorners(vec2 p, out vec2 f) {
    ivec2 cell = ivec2(floor(p));
    f = fract(p);
    return vec4(biomeHash(cell), biomeHash(cell + ivec2(1, 0)),
          biomeHash(cell + ivec2(0, 1)), biomeHash(cell + ivec2(1, 1)));
}
float biomeNoise(vec2 p) {
    vec2 f;
    vec4 h = biomeNoiseCorners(p, f);
    vec2 t = f * f * (3.0 - 2.0 * f);
    return mix(mix(h.x, h.y, t.x), mix(h.z, h.w, t.x), t.y);
}

// Value and its analytic XY gradient. Keeping the scalar interpolation identical preserves vegetation's field.
vec3 biomeNoiseGradient(vec2 p) {
    vec2 f;
    vec4 h = biomeNoiseCorners(p, f);
    vec2 t = f * f * (3.0 - 2.0 * f), dt = 6.0 * f * (1.0 - f);
    float lower = mix(h.x, h.y, t.x), upper = mix(h.z, h.w, t.x);
    return vec3(mix(lower, upper, t.y), mix(h.y - h.x, h.w - h.z, t.y) * dt.x, (upper - lower) * dt.y);
}

// BoardSurfaceBlend.sandExposure: sparse windows through windblown sand into the local substrate. This field
// also selects grass roots on the CPU, so living tufts occupy exposed turf instead of the uninterrupted drifts.
float sandExposure(vec2 metres) {
    float field = .75 * biomeNoise(metres / 6.0) + .25 * biomeNoise(metres / 1.7 + vec2(19.0, -7.0));
    return smoothstep(.60, .74, field);
}

// Signed distance in metres from a hex's centre offset to its outline; negative inside.
float biomeHexDistance(vec2 p) {
    p = abs(p);
    const float a = 30.0 * BOARD_HEX.y / BOARD_HEX.x * .5, b = 7.5;
    return max(p.y - a, (a * p.x + b * p.y - 15.0 * a) / sqrt(a*a + b*b));
}

// The centre, in metres, of the board hex at column x and row y.
vec2 boardHexCenter(int x, int y) {
    const float width = 30.0, height = 30.0 * BOARD_HEX.y / BOARD_HEX.x;
    float parity = mod(float(x), 2.0);
    return vec2((3.0 * float(x) + 2.0) * width * .25, -(2.0 * float(y) + parity + 1.0) * height * .5);
}
