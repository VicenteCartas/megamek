// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// A tree or kit structure changing detail level draws at both for LEVEL_DISSOLVE seconds of u_levelClock from when it
// began (v_levelDissolve, tree-instances.glsl): the level it takes keeps the pixels of one screen pattern under its
// progress, the one it leaves the others, so each pixel shows one of them (and a tree's depth twin the same).
#ifdef levelDissolveFlag
flat in float v_levelDissolve;
uniform float u_levelClock;

// Discards this level's pixel where the other level keeps it.
void levelDissolve() {
    if (v_levelDissolve == 0.0) { return; }
    float progress = clamp((u_levelClock - abs(v_levelDissolve)) * (1.0 / LEVEL_DISSOLVE), 0.0, 1.0);
    // Interleaved gradient noise: an even spread of thresholds at every scale.
    float pattern = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(.06711056, .00583715))));
    if (v_levelDissolve > 0.0 ? pattern >= progress : pattern < progress) { discard; }
}
#endif
