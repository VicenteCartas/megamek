// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Unit wakes and waterfall impacts share the pool's noise, animation and surface slope.

// Units standing in the water: it piles against each in a broken, swelling collar, sends ripples out and,
// behind a moving one, spreads into a wake: two arms of foam at the angle every wake keeps, churned water
// right behind it. Unit positions and radii are in world units.
float waterUnitWakes(vec2 world, float noise, float effects, inout vec2 slope) {
    float wading = 0.0;
    for (int i = 0; i < 12; i++) {
        if (i >= u_waderCount) break;
        vec2 delta = world - u_waders[i].xy;
        float radius = u_waders[i].z;
        float apart = length(delta);
        float outside = apart - radius;
        float swell = 0.5 + 0.5 * sin(u_rainTime * 2.1 + float(i) * 2.3 + noise * 3.0);
        float collar = (1.0 - smoothstep(0.0, radius * (0.6 + 0.5 * swell) + 3.0, outside))
              * smoothstep(-radius * 0.5, 0.0, outside);
        // Each wave breaking against the unit sends a ring of foam out, which thins as it spreads.
        float wave = fract(u_rainTime * 0.6 + float(i) * 0.37);
        float ring = (1.0 - smoothstep(0.0, 1.5 + wave * 2.0, abs(outside - wave * (radius * 1.2 + 8.0))))
              * (1.0 - wave);
        collar = max(collar, ring * step(0.0, outside));
        float spread = step(0.0, outside) * exp(-outside / (radius * 2.0 + 6.0));
        slope += delta / max(apart, 0.001) * (sin(outside * 0.8 - u_rainTime * 4.5) * spread * 0.25 * effects);
        vec2 velocity = u_waderMotion[i].xy;
        float speed = length(velocity);
        vec2 heading = velocity / max(speed, 0.001);
        float behind = dot(delta, -heading);
        float across = abs(dot(delta, vec2(-heading.y, heading.x)));
        float moving = smoothstep(0.5, 4.0, speed);
        float trail = max(behind, 0.0) / (radius * 2.0 + speed * 3.0);
        float arm = abs(across - radius * 0.6 - behind * 0.36);
        float arms = (1.0 - smoothstep(0.0, radius * 0.3 + behind * 0.06, arm)) * step(0.0, behind)
              * (1.0 - smoothstep(0.3, 1.0, trail));
        float churned = (1.0 - smoothstep(radius * 0.6, radius * 1.1, across)) * step(0.0, behind)
              * (1.0 - smoothstep(0.0, 0.5, trail));
        wading = max(wading, max(collar, max(arms * 0.8, churned) * moving));
    }
    return wading * effects;
}

struct WaterImpact {
    float boil;
    float bubbles;
    float foam;
};

// A fall churns the pool it lands in: a boil round its landing line, rings pushed outward and the churned water
// flowing away. The landing lines of every fall nearby meet at the corners they share, so the boil runs on
// round those corners and across hexes without a seam; behind a curtain the water churns right to the wall.
// Position, shoreline distance and landing lines are in hex widths; phase and weights come from pool advection.
WaterImpact waterFallImpacts(vec2 position, float shore, float noise, float phase, vec2 weights,
      float effects, inout vec2 slope) {
    float boil = 0.0;
    vec2 push = vec2(0.0);
    for (int i = 0; i < 12; i++) {
        if (i >= u_splashCount) break;
        vec4 line = u_splashLines[i];
        float radius = u_splashRadii[i];
        vec2 along = line.zw - line.xy;
        float t = clamp(dot(position - line.xy, along) / max(dot(along, along), 1e-6), 0.0, 1.0);
        vec2 offset = position - (line.xy + along * t);
        float gap = length(offset);
        vec2 outward = offset / max(gap, 1e-4);
        // Between the landing and the wall behind the curtain, about as far as it is thrown, all churns.
        float behind = smoothstep(0.0, 0.5, -dot(outward, normalize(vec2(along.y, -along.x))));
        float reach = 1.0 - smoothstep(radius * 0.2, radius * 1.8,
              max(gap - 0.085 * behind, 0.0) + noise * radius * 0.6);
        float ring = sin(gap * 70.0 - u_rainTime * 8.0 + noise * 6.0);
        slope += outward * ring * reach * 0.22 * effects;
        boil = max(boil, reach);
        push += outward * reach;
    }
    // The impact breaks up before reaching a bank; shoreline foam still follows the actual waterline.
    boil *= effects * smoothstep(0.004, 0.06, shore);
    float bubbles = 0.0, impact = 0.0;
    // Without a nearby landing boil is zero, so neither bubbles nor impact contributes. The uniform branch
    // keeps mip selection defined for every fragment of materials that do have falling water nearby.
    if (u_splashCount > 0) {
        // Foam patches ride the churned water outward: advected in two phases like the current, each restarting
        // only while unseen, so the foam keeps moving away from the falls without ever shearing or pulsing.
        vec2 drift = push * (0.06 / max(length(push), 1.0));
        vec2 bubbleA = position - drift * ((phase - 0.5) * FLOW_CYCLE);
        vec2 bubbleB = position - drift * ((fract(phase + 0.5) - 0.5) * FLOW_CYCLE) + 0.37;
        bubbles = 0.5 + ((texture(u_surfaceNoise, vec3(bubbleA * 3.4 + 0.61, WATER_DETAIL)).b - 0.5) * weights.x
              + (texture(u_surfaceNoise, vec3(bubbleB * 3.4 + 0.61, WATER_DETAIL)).b - 0.5) * weights.y)
              * inversesqrt(weights.x * weights.x + weights.y * weights.y);
        // Solid white right below the fall, breaking into patches and then lace as the foam spreads.
        float settle = 1.0 - boil;
        impact = smoothstep(settle, settle + 0.15, bubbles * 0.6 + (noise + 0.5) * 0.4)
              * smoothstep(0.0, 0.15, boil);
    }
    return WaterImpact(boil, bubbles, impact);
}
