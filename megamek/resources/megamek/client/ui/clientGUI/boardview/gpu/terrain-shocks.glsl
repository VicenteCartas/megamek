// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The live blasts' shock fronts (GpuArtilleryBlast#shocks): ground zero, the blast area's radius (negative for a
// conventional explosion's, which moves the plants alone and spreads no scorch) and the front's
// clock, on which every front reaches R (sqrt(1 + 3 t / SHOCK_SECONDS) - 1) (GpuArtilleryBlast#shockReach): plants bend,
// survivors sway and the scorch spreads as it passes.
#ifndef SHOCKS_DECLARED
#define SHOCKS_DECLARED
// SHOCK_SECONDS
uniform vec4 u_shocks[4];
uniform int u_shockCount;

// When the front of a blast of this radius reaches this distance, on its clock (GpuArtilleryBlast#shockArrival).
float shockArrival(float distanceAway, float radius) {
    float share = clamp(distanceAway / radius, 0.0, 1.0);
    return SHOCK_SECONDS * share * (2.0 + share) / 3.0;
}

// 0 where live blasts cover this point and none of their fronts has reached it yet, whose scorch is not there yet; else
// 1: an earlier blast's scorch stays once its front has passed.
float shockReached(vec2 world) {
    float reached = 1.0;
    for (int i = 0; i < u_shockCount; i++) {
        float d = distance(world, u_shocks[i].xy);
        if (d >= u_shocks[i].z) { continue; }
        if (u_shocks[i].w >= shockArrival(d, u_shocks[i].z)) { return 1.0; }
        reached = 0.0;
    }
    return reached;
}

// The blast wind here, horizontal: away from each ground zero within its area, strongest near it, a gust that rises
// within a tenth of a second of its front passing, swings back once toward ground zero and dies down over two seconds
// of the front's clock (sooner for a smaller blast).
// Its length is the share of a full flattening, about 3 near ground zero.
vec2 shockWind(vec2 world) {
    vec2 wind = vec2(0.0);
    for (int i = 0; i < u_shockCount; i++) {
        vec2 away = world - u_shocks[i].xy;
        float d = length(away);
        if (d >= abs(u_shocks[i].z) || d < 1e-3) { continue; }
        float since = u_shocks[i].w - shockArrival(d, abs(u_shocks[i].z));
        if (since <= 0.0) { continue; }
        float near = 1.0 - d / abs(u_shocks[i].z);
        float gust = (1.0 - exp(-since / .04)) * exp(-since / .9) * cos(since * 2.6);
        wind += away / d * 3.0 * near * near * gust;
    }
    return wind;
}
#endif
