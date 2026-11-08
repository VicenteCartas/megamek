// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by puddles and horizontal water. No scene capture, marching, or per-drop geometry.
// SURFACE_NOISE
uniform float u_wetness;
uniform float u_rainScale;
uniform float u_rainTime;
#ifdef waterSurfaceFlag
// A water page spans many chunks. Use each pixel's projected scale, never the page centre's distance.
#define u_rippleDetail clamp((u_wavePixels * gl_FragCoord.w * abs(u_viewDirection.z) - 28.0) / 60.0, 0.0, 1.0)
#define u_rainDetail clamp((u_wavePixels * gl_FragCoord.w - 12.0) / 28.0, 0.0, 1.0)
#else
uniform float u_rippleDetail;
uniform float u_rainDetail;
#endif
uniform vec3 u_viewDirection;
uniform vec3 u_viewPosition;
uniform float u_perspective;

vec3 viewDirection() {
    return u_perspective > 0.5 ? normalize(v_cloudPosition - u_viewPosition) : u_viewDirection;
}
uniform vec3 u_rainSky;     // linear atmosphere radiance
uniform vec3 u_rainHorizon;
const int RAIN_IMPACT_LAYERS = 6;

// Wet pigment: a susceptible fraction takes one additional internal scattering event.
// This cheap two-path approximation acts on linear pigment, not incoming light or the water-film BRDF.
// Susceptibility is an artistic material estimate, not a measured porosity or a gamma-space conversion.
vec3 wetAlbedo(vec3 dry, float absorption) {
    return dry * (1.0 - clamp(absorption, 0.0, 1.0) * (1.0 - clamp(dry, 0.0, 1.0)));
}

// The existing response measures surface water retention: loose absorbent ground retains less film.
float groundAbsorption(float wet, float response) {
    return clamp(wet, 0.0, 1.0) * mix(.5, .25, clamp(response, 0.0, 1.0));
}

vec3 rainHash(vec3 value) {
    value = fract(value * vec3(0.1031, 0.1030, 0.0973));
    value += dot(value, value.yxz + 33.33);
    return fract((value.xxy + value.yzz) * value.zyx);
}

// XY is the surface slope; Z is the signed crest, used for subtle refractive contrast on open water.
vec3 rainRippleField(vec2 position) {
    if (u_rippleDetail <= 0.0 || u_wetness <= 0.0) return vec3(0.0);
    vec3 result = vec3(0.0);
    // Six independently staggered layers retain readable ring sizes: six times the former downpour density.
    for (int layer = 0; layer < RAIN_IMPACT_LAYERS; layer++) {
        vec2 point = position * 4.0 + vec2(0.7549, 0.5698) * float(layer);
        vec2 cell = floor(point);
        vec3 seed = rainHash(vec3(cell, float(layer) + 7.0));
        float cycle = u_rainTime * mix(0.8, 1.5, u_wetness) + seed.z;
        float age = fract(cycle);
        vec3 impact = rainHash(vec3(cell + float(layer) * 19.19, floor(cycle)));
        if (impact.z > u_wetness) continue;
        vec2 delta = fract(point) - (0.32 + impact.xy * 0.36);
        float distance = length(delta);
        float front = distance - age * mix(0.21, 0.26, u_wetness);
        float envelope = (1.0 - smoothstep(0.02, 0.065, abs(front)))
              * smoothstep(0.0, 0.12, age) * (1.0 - smoothstep(0.65, 1.0, age));
        float wave = sin(front * 75.0) * envelope;
        result += vec3(delta / max(distance, 0.001) * wave * mix(0.16, 0.28, u_wetness), wave);
    }
    return result * u_rippleDetail;
}

vec2 rainRipples(vec2 position) { return rainRippleField(position).xy; }

float rainPuddle(vec2 position, float wet, float response) {
    // Broad basins keep a stable shape as rain enlarges their edges and joins nearby patches.
    float field = texture(u_surfaceNoise, rainNoiseAt(position / 72.0)).r * 0.75
          + texture(u_surfaceNoise, rainNoiseAt(position / 24.0 + 0.37)).r * 0.25;
    float threshold = mix(0.80, 0.34, wet * mix(0.35, 1.0, response));
    return smoothstep(threshold, threshold + 0.08, field) * wet;
}
