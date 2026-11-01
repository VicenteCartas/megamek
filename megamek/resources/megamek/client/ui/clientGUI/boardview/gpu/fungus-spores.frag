#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Soft luminous spore wisps. The shared effects batch supplies a seeded age and lifetime opacity per puff.
in vec2 v_uv;
in vec2 v_effect;
// LIGHT_MODEL

float sporeHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float sporeNoise(vec2 p) {
    vec2 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(sporeHash(cell), sporeHash(cell + vec2(1, 0)), f.x),
          mix(sporeHash(cell + vec2(0, 1)), sporeHash(cell + vec2(1, 1)), f.x), f.y);
}

void main() {
    float age = fract(v_effect.x), seed = floor(v_effect.x);
    vec2 p = v_uv;
    float radial = dot(p, p);
    float envelope = exp(-radial * 1.6) * (1.0 - smoothstep(.45, 1.0, radial));
    vec2 curl = vec2(sin(p.y * 4.0 + age * 3.0 + seed), cos(p.x * 3.0 - age * 2.0 + seed));
    vec2 q = p * 3.0 + curl * .45 + vec2(seed, -age * 2.0);
    float wisps = sporeNoise(q) * .65 + sporeNoise(q * 2.13 + 3.7) * .35;
    float density = smoothstep(.14, .72, wisps);
    // Magenta-violet, cooler than the orange-red cups so they stand apart from it, as in the map's concept art.
    vec3 mist = mix(toLinear(vec3(.60, .24, .72)), toLinear(vec3(.90, .70, 1.0)), wisps);
    fragColor = vec4(mist, envelope * density * v_effect.y);
}
