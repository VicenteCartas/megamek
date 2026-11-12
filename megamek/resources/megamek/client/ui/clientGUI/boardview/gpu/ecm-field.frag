#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The board's E(C)CM (GpuElectronicWarfare, b11 design section 5). One texel per hex from BoardElectronicWarfare:
// red = coverage bits (own ECM 1, own ECCM 2, enemy ECM 4, enemy ECCM 8, jammed 16, ring 32), green = the number of
// displayed sources covering the hex, blue = the hex lies in the open (hovered or selected) source's field.
// Lines run along the hex edges where a side's coverage of one kind changes, each hex drawing its half: solid ECM,
// dashed ECCM, or the 3D view's mid-edge ticks. Under them the ground-space hatch of the jammed hexes, and under that
// the open field's pulse, which lights only the hexes no other displayed field covers. It decides no rule.
uniform sampler2D u_cells;
uniform vec4 u_board;          // board width and height in hexes, tile width and height in board pixels
uniform int u_tactical;        // 1: the Tactical View (option B), 0: the 3D view (option C)
uniform int u_fieldsAll;       // every field's outline: the Tactical View's Fields, or deployment's ECM overlay
uniform int u_hatchAll;        // the Tactical View hatches every jammed hex, not only the open field's
uniform int u_fill;            // deployment's ECM overlay: tint every covered hex and hatch every jammed one
uniform int u_openKinds;       // the open source's side and kind bits; 0 when no field is open
uniform float u_strength;      // the ECM Transparency setting / 128
uniform vec2 u_pulseCentre;    // the open source's hex centre, in board pixels
uniform float u_pulseBorder;   // the field's edge, in hexes from the source
uniform float u_pulseTime;     // seconds into the pulse's cycle; negative while it rests
uniform float u_pulsePeriod;   // seconds per cycle
uniform vec4 u_pulseColour;    // rgb, and the tuned intensity
uniform vec3 u_own;            // own and allied fields
uniform vec3 u_enemy;          // enemy fields and the jammed hatch
flat in ivec2 v_cell;
in vec2 v_local;

const int OWN_ECM = 1, OWN_ECCM = 2, ENEMY_ECM = 4, ENEMY_ECCM = 8, KINDS = 15, JAMMED = 16;
const vec3 INK = vec3(16.0, 22.0, 24.0) / 255.0;
// The hatch runs at 115 degrees on the board (y up), diagonal on screen in both the 3D and the top view.
const vec2 ACROSS_HATCH = vec2(0.9063077870366499, -0.42261826174069944);

/** Board pixels to screen pixels at this fragment, from the screen derivatives taken before any discard. */
mat2 toScreen;

struct Cell { int bits; int count; bool open; };

Cell cell(ivec2 c) {
    vec4 t = texelFetch(u_cells, c, 0);
    return Cell(int(t.r * 255.0 + 0.5), int(t.g * 255.0 + 0.5), t.b > 0.5);
}

bool onBoard(ivec2 c) {
    return c.x >= 0 && c.y >= 0 && c.x < int(u_board.x) && c.y < int(u_board.y);
}

/** Coords.translated: side 0 is north, then clockwise; odd columns sit half a hex lower. */
ivec2 neighbour(ivec2 c, int side) {
    int odd = c.x & 1;
    if (side == 0) { return c + ivec2(0, -1); }
    if (side == 1) { return c + ivec2(1, odd - 1); }
    if (side == 2) { return c + ivec2(1, odd); }
    if (side == 3) { return c + ivec2(0, 1); }
    if (side == 4) { return c + ivec2(-1, odd); }
    return c + ivec2(-1, odd - 1);
}

vec2 centre(ivec2 c) {
    return vec2((float(c.x) * 0.75 + 0.5) * u_board.z, (float(c.y) + float(c.x & 1) * 0.5 + 0.5) * u_board.w);
}

float screenLength(vec2 board) {
    return length(toScreen * board);
}

/** Screen pixels from p to the segment a-b, with round ends. */
float segment(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float t = clamp(dot(p - a, ab) / dot(ab, ab), 0.0, 1.0);
    return screenLength(p - a - ab * t);
}

/** 0 solid, 1 dashed with a dash centred on each corner, 2 the middle 40 % (a tick). */
float lineDistance(vec2 p, vec2 a, vec2 b, int pattern) {
    if (pattern == 0) { return segment(p, a, b); }
    if (pattern == 1) { return min(segment(p, a, mix(a, b, 0.34)), segment(p, mix(a, b, 0.66), b)); }
    return segment(p, mix(a, b, 0.3), mix(a, b, 0.7));
}

/** One line per edge: enemy ECM, own ECM, enemy ECCM, own ECCM. */
int strongest(int kinds) {
    if ((kinds & ENEMY_ECM) != 0) { return ENEMY_ECM; }
    if ((kinds & OWN_ECM) != 0) { return OWN_ECM; }
    if ((kinds & ENEMY_ECCM) != 0) { return ENEMY_ECCM; }
    return OWN_ECCM;
}

/** Paints straight-alpha {@code paint} over premultiplied {@code below}. */
vec4 over(vec4 below, vec4 paint) {
    return vec4(paint.rgb * paint.a + below.rgb * (1.0 - paint.a), paint.a + below.a * (1.0 - paint.a));
}

/** The wave leaves the source, crosses the field, flashes at its border, then rests (times scale with the period). */
float pulse(float d, float time) {
    float travel = u_pulsePeriod * (1.45 / 2.4), flash = u_pulsePeriod * (0.40 / 2.4);
    float a = 0.0;
    if (time < travel) {
        float x = u_pulseBorder * time / travel - d;          // > 0 behind the front
        float crest = exp(-x * x / (0.22 * 0.22));
        float trail = x > 0.0 ? 0.5 * exp(-x / 1.4) : 0.0;
        a = 0.62 * max(crest, trail) * (0.4 + 0.6 * clamp(d / 0.5, 0.0, 1.0));
    }
    float since = time - travel;
    if (since >= 0.0 && since < flash) {
        float fade = pow(1.0 - since / flash, 1.5);
        a = max(a, fade * max(0.80 * smoothstep(u_pulseBorder - 1.0, u_pulseBorder, d), 0.20));
    }
    return a;
}

void main() {
    // Derivatives first: they are undefined after a discard in non-uniform control flow.
    mat2 jacobian = mat2(dFdx(v_local), dFdy(v_local));
    toScreen = abs(determinant(jacobian)) > 1e-12 ? inverse(jacobian) : mat2(1e6);
    Cell self = cell(v_cell);
    if (self.bits == 0) { discard; }
    // Each carrier triangle may reach past its owner hex: keep the owner's footprint (as hex-mask.frag).
    float apothem = u_board.z * 0.4330127018922193;
    vec2 folded = abs(vec2(v_local.x, v_local.y * apothem / (u_board.w * 0.5)));
    if (apothem - max(folded.y, folded.x * 0.8660254037844386 + folded.y * 0.5) < 0.0) { discard; }
    float strength = u_strength;
    vec2 world = v_local + centre(v_cell);
    vec4 colour = vec4(0.0);

    if (u_fill == 1) {
        // The field's body: enemy ECM over own ECM, ECCM fainter, so where a field reaches reads without its edge.
        int kind = strongest(self.bits & KINDS);
        if ((self.bits & KINDS) != 0) {
            bool counter = kind == OWN_ECCM || kind == ENEMY_ECCM;
            vec3 body = kind == OWN_ECM || kind == OWN_ECCM ? u_own : u_enemy;
            colour = over(colour, vec4(body, (counter ? 0.10 : 0.20) * min(strength, 1.3)));
        }
    }
    if (u_pulseTime >= 0.0 && self.open && self.count == 1) {
        // Continuous cube distance on the board's hex lattice: the front keeps the field's hexagon.
        vec2 offset = world - u_pulseCentre;
        float q = offset.x / (0.75 * u_board.z);
        float r = -offset.y / u_board.w - q * 0.5;
        float d = max(max(abs(q), abs(r)), abs(q + r));
        colour = over(colour, vec4(u_pulseColour.rgb, pulse(d, u_pulseTime) * u_pulseColour.a * min(strength, 1.3)));
    }

    if ((self.bits & JAMMED) != 0 && (self.open || u_fill == 1 || u_tactical == 1 && u_hatchAll == 1)) {
        float spacing = u_board.z / 6.5;
        float across = (fract(dot(world, ACROSS_HATCH) / spacing + 0.5) - 0.5) * spacing;
        float apart = screenLength(ACROSS_HATCH * across);
        // Fade out before the stripes merge when zoomed far out.
        float alpha = min(strength, 1.4) * smoothstep(3.0, 6.0, screenLength(ACROSS_HATCH * spacing));
        colour = over(colour, vec4(INK, 0.28 * alpha * clamp(2.05 - apart, 0.0, 1.0)));
        colour = over(colour, vec4(u_enemy, 0.62 * alpha * clamp(1.25 - apart, 0.0, 1.0)));
    }

    vec2 corners[6] = vec2[6](vec2(-0.25, -0.5), vec2(0.25, -0.5), vec2(0.5, 0.0), vec2(0.25, 0.5),
          vec2(-0.25, 0.5), vec2(-0.5, 0.0));
    float widen = 1.0 + 0.3 * max(0.0, strength - 1.0), lineAlpha = min(strength, 1.0);
    float core = 0.0, casing = 0.0;
    vec3 tint = u_enemy;
    for (int side = 0; side < 6; side++) {
        ivec2 other = neighbour(v_cell, side);
        if (!onBoard(other)) { continue; }   // fields run on past the board edge
        Cell next = cell(other);
        int kinds = (self.bits ^ next.bits) & KINDS;
        if (kinds == 0) { continue; }
        int kind = strongest(kinds);
        bool counter = kind == OWN_ECCM || kind == ENEMY_ECCM;
        bool open = (kind & u_openKinds) != 0 && (self.open || next.open);
        int pattern = 2;
        float width = counter ? 2.4 : 3.0, alpha = counter ? 0.7 : 0.85;
        if (u_fieldsAll == 1 || open) {
            pattern = counter ? 1 : 0;
            width = u_tactical == 1 ? 3.6 : 4.2;
            alpha = 1.0;
        }
        vec2 a = corners[side] * u_board.zw, b = corners[(side + 1) % 6] * u_board.zw;
        // Zoomed far out the stroke and its casing keep within 8 % of the hex radius.
        float halfWidth = 0.5 * width * widen;
        float limit = 0.04 * u_board.z * screenLength(normalize(vec2(b.y - a.y, a.x - b.x)));
        float fit = min(1.0, limit / (halfWidth + 1.3));
        float d = lineDistance(v_local, a, b, pattern);
        float line = clamp(halfWidth * fit + 0.5 - d, 0.0, 1.0) * alpha * lineAlpha;
        if (line > core) {
            core = line;
            tint = kind == OWN_ECM || kind == OWN_ECCM ? u_own : u_enemy;
        }
        casing = max(casing, clamp((halfWidth + 1.3) * fit + 0.5 - d, 0.0, 1.0) * 0.55 * lineAlpha);
    }
    colour = over(colour, vec4(INK, casing));
    colour = over(colour, vec4(tint, core));
    if (colour.a < 0.002) { discard; }
    fragColor = vec4(colour.rgb / colour.a, colour.a);
}
