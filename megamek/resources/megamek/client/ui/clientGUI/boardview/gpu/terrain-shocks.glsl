// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The live nuclear blasts' shock fronts (GpuArtilleryBlast#shocks): ground zero, the blast area's radius and the blast's
// seconds. A front reaches R (1 - (1 - t / SHOCK_SECONDS)^2) (GpuArtilleryBlast#shockReach): plants bend, survivors
// sway and the scorch spreads as it passes.
#ifndef SHOCKS_DECLARED
#define SHOCKS_DECLARED
// SHOCK_SECONDS
uniform vec4 u_shocks[4];
uniform int u_shockCount;

// When the front of a blast of this radius reaches this distance, in blast seconds (GpuArtilleryBlast#shockArrival).
float shockArrival(float distanceAway, float radius) {
    return SHOCK_SECONDS * (1.0 - sqrt(1.0 - clamp(distanceAway / radius, 0.0, 1.0)));
}

// 0 where a live blast's front has yet to reach this point, whose scorch is not there yet; else 1.
float shockReached(vec2 world) {
    for (int i = 0; i < u_shockCount; i++) {
        float d = distance(world, u_shocks[i].xy);
        if (d < u_shocks[i].z && u_shocks[i].w < shockArrival(d, u_shocks[i].z)) { return 0.0; }
    }
    return 1.0;
}

// The blast wind here, horizontal: away from each ground zero within its area, strongest near it, a gust that rises
// within a tenth of a second of its front passing, swings back once toward ground zero and dies down over two seconds.
// Its length is the share of a full flattening, about 3 near ground zero.
vec2 shockWind(vec2 world) {
    vec2 wind = vec2(0.0);
    for (int i = 0; i < u_shockCount; i++) {
        vec2 away = world - u_shocks[i].xy;
        float d = length(away);
        if (d >= u_shocks[i].z || d < 1e-3) { continue; }
        float since = u_shocks[i].w - shockArrival(d, u_shocks[i].z);
        if (since <= 0.0) { continue; }
        float near = 1.0 - d / u_shocks[i].z;
        float gust = (1.0 - exp(-since / .04)) * exp(-since / .9) * cos(since * 2.6);
        wind += away / d * 3.0 * near * near * gust;
    }
    return wind;
}
#endif
