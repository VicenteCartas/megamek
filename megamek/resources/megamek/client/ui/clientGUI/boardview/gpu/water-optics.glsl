// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One optical model for the water surface and everything seen through it. The bed keeps the hue a column of water
// passes; the surface removes the rest of what the column absorbs and adds its in-scattered light.
// SURFACE_NOISE
// GpuOcean's swell, chop and ripple cascades: RG slope, B height in metres, A persistent breaking foam (none on the
// ripples).
uniform sampler2D u_waterOcean0;
uniform sampler2D u_waterOcean1;
uniform sampler2D u_waterOcean2;
uniform vec3 u_waterOceanScale;  // world XY to each cascade's UV; zero without the simulation
uniform vec3 u_waterWind;        // GpuOcean's eased wind: unit direction XY, strength Z; turns with the waves
uniform vec2 u_waterDrift;       // how far that wind has carried the surface: seconds along its direction
uniform float u_waterStorm;      // GpuOcean.gain: the art-directed gain the sea is drawn with, 1 in calm
// A fixed turn for detail maps, so their wave trains cross the simulated ones. Fixed in the world: a map turning with
// the wind would sweep across the whole board whenever the wind changed.
const mat2 CROSSING = mat2(0.52, 0.85, -0.85, 0.52);
// Depth in levels is encoded over this range, in the bed's vertex color and in the surface field alike.
const float WATER_DEPTH_RANGE = 4.0;
// Light crosses the column down to the bed and back up to the board camera: optical path per level of depth.
const float WATER_PATH = 1.7;
// The surface never hides more than this share of what lies below it, so units stay readable in deep water. Beds
// take the rest of their column's absorption themselves, which keeps the look of the water physical.
const float WATER_MAX_OPACITY = 0.5;

// Absorption per level of optical path. Red goes first, which turns shallow water over pale gravel turquoise.
vec4 waterPalette(float palette) {
    return vec4(lessThan(abs(vec4(0.0, 1.0, 2.0, 3.0) - palette), vec4(.5)));
}
vec3 waterAbsorption(vec4 weights) {
    return weights.x * vec3(1.9, .5, .33) + weights.y * vec3(.75, 1.25, 2.4)
          + weights.z * vec3(.7, 1.4, 2.2) + weights.w * vec3(1.8, .9, 2.8);
}
vec3 waterAbsorption(float palette) { return waterAbsorption(waterPalette(palette)); }

// Linear colour of light scattered back out of a deep column lit by white light. Decode the authored palette
// before mixing its constituents; optical transmission and scattered light then share the scene's linear space.
vec3 waterScatter(vec4 weights) {
    return weights.x * toLinear(vec3(.012, .13, .26)) + weights.y * toLinear(vec3(.46, .26, .10))
          + weights.z * toLinear(vec3(.40, .16, .065)) + weights.w * toLinear(vec3(.16, .24, .06));
}
vec3 waterScatter(float palette) { return waterScatter(waterPalette(palette)); }

// Shallow water over a pale bed glows with the bed's light scattered back through it: clear water turquoise.
vec3 waterShallows(vec4 weights) {
    return weights.x * toLinear(vec3(.08, .66, .62)) + weights.y * toLinear(vec3(.66, .44, .20))
          + weights.z * toLinear(vec3(.64, .30, .12)) + weights.w * toLinear(vec3(.30, .40, .14));
}
vec3 waterShallows(float palette) { return waterShallows(waterPalette(palette)); }

// Even depth-zero water has only about .056 levels over its bed. Build the tint within that thin column so it
// still reads as water, while fading continuously to clear at the actual waterline. The bed shares this curve.
vec3 waterTransmission(vec4 palette, float levels) {
    float optical = max(levels, 0.0) + 0.16 * smoothstep(0.0, 0.05, levels);
    return exp(-waterAbsorption(palette) * (optical * WATER_PATH));
}
vec3 waterTransmission(float palette, float levels) { return waterTransmission(waterPalette(palette), levels); }

// Two drifting copies of one network; their minimum is a sharp web of focused light that keeps re-forming.
float waterCaustics(vec2 position) {
    vec2 drift = vec2(0.021, 0.013) * u_rainTime;
    float first = texture(u_surfaceNoise, vec3(position * 1.35 + drift, WATER_DETAIL)).a;
    float second = texture(u_surfaceNoise, vec3(position * 1.1 - drift.yx * 1.3 + 0.41, WATER_DETAIL)).a;
    return min(first, second);
}

// What a submerged surface keeps of its own color: the hue its column passes, less the brightness the surface above
// removes, which is all of it up to WATER_MAX_OPACITY.
vec3 waterBedTint(vec4 palette, float levels) {
    vec3 kept = waterTransmission(palette, levels);
    return kept / max(max(max(kept.r, kept.g), kept.b), 1.0 - WATER_MAX_OPACITY);
}
vec3 waterBedTint(float palette, float levels) { return waterBedTint(waterPalette(palette), levels); }

// Light on a submerged surface: the water scatters daylight in every direction, so below the surface orientation and
// shadows soften with depth toward the surface's own colour under that scattered light.
vec3 submergedLight(vec3 lit, vec3 pigment, vec3 scattered, float levels) {
    return mix(lit, pigment * scattered, smoothstep(0.0, 0.8, levels) * 0.75);
}

// Extra direct light focused onto submerged ground: none at the water line, strongest in the shallows.
float waterBedCaustics(vec2 position, float levels) {
    return waterCaustics(position) * 2.6 * exp(-levels * 1.4) * smoothstep(0.0, 0.05, levels);
}
