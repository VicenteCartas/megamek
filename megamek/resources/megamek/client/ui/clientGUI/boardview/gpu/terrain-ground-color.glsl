// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by the terrain surface and the turf growing from it. All material colors are linear reflectance.
// COLOR_SPACE
// The second repeat of every map is 2.37 times larger and turned by 34 degrees.
const mat2 TURN = mat2(.8253, .5646, -.5646, .8253);

// The inherited pigment controls multiplied display RGB before the former gamma-2.2 lighting boundary.
// Their equivalent linear coefficient is gain^2.2. This cubic response around gain=1 stays within .17%
// over the supported [.6,1.4] range (actual combined controls [.627,1.332]); no pixel is encoded or decoded.
// Calibrate authored tint/contrast only: radiance, physical reflectance, coverage and roughness do not use it.
vec3 authoredPigmentGain(vec3 gain) {
    vec3 change = gain - 1.0;
    return 1.0 + change * (2.2 + change * (1.32 + .088 * change));
}

vec3 groundFields(sampler2D noiseMap, vec3 world) {
    return vec3(texture(noiseMap, world.xy / 700.0).g * .6 + texture(noiseMap, world.xy / 430.0 + .19).b * .4,
          texture(noiseMap, world.xy / 160.0 + .41).b, texture(noiseMap, world.xy / 2600.0 + .73).r);
}

// The intentional elevation cue operates on completed linear reflectance in [0,1]. It is a presentation
// treatment, not illumination. Apply after all other pigment gains so no later bound can flatten its detail.
// Downhill every eased level darkens the ground by an eighth of its reflectance: 12.5% at level -1, building to
// 50% at level -6 and below, so each level of a deep pit reads apart. A bed under water (dry 0), which its depth
// already darkens, keeps the gentle .06 above level -1.5.
vec3 levelGrade(vec3 c, float level, float fungus, float dry) {
    if (level == 0.0) return c;
    level = max(level, mix(-1.5, -6.0, dry));
    float up = 6.0 * (1.0 - exp(-max(level, 0.0) / 6.0));
    float down = 6.0 * (1.0 - exp(-max(-level, 0.0) / 6.0));
    // Every family darkens and warms alike downhill; fungus keeps its stronger, paler lift uphill.
    c *= vec3(1.0, 1.0 - .01 * down, 1.0 - .04 * down);
    float luma = dot(c, vec3(.2126, .7152, .0722));
    c = mix(vec3(luma), c, 1.0 - fungus * .04 * up);
    float exposure = exp2(mix(.10, .16, fungus) * up);
    float peak = max(c.r, max(c.g, c.b));
    // Map peak p to e*p/(1+(e-1)*p), with positive derivative even at bright texture samples.
    // The one scalar preserves RGB ratios; it does not clamp separate channels or force a new white ceiling.
    c *= exposure / (1.0 + (exposure - 1.0) * peak);
    return c * (1.0 - mix(.06, .127, dry) * down);
}

vec3 levelGrade(vec3 c, float level) { return levelGrade(c, level, 0.0, 1.0); }

// Near-white snow barely lifts uphill under the exposure curve, so the common downhill grade and step would mark a
// snowfield's hollows far more than its hills: snow darkens 5% for the first level down instead, easing on down to
// level -6 as the common grade does.
vec3 snowLevelGrade(vec3 c, float level) {
    if (level >= 0.0) return levelGrade(c, level);
    float down = (1.0 - exp(max(level, -6.0) / 6.0)) / (1.0 - exp(-1.0 / 6.0));
    return c * (1.0 - .05 * down);
}

// Broad variations in the ground's tone, so a large field reads neither as one flat colour nor as tiles: patches, the
// desert's compact ochre earth, loose sand's drifts, a meadow's dry and lush turf. rim and foot weigh the
// nearness of a drop and of a rise. The ground and the cover drifted onto slopes and ledges share it, so they match.
// The family arrives as its four tests (sand, desert, Mars, grass), so a caller can select them per pixel.
vec3 groundToneOf(bvec4 tests, vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    vec3 gain = vec3(mix(.94, 1.06, broad) * mix(.95, 1.05, region) * mix(.96, 1.04, fine));
    if (tests.x) {
        // Shared loose sand over any theme. No drift pattern is added to clear desert or Martian hardpan.
        float dune = sin(dot(world.xy, vec2(.8, .6)) / 19.0 + broad * 5.0 + region * 3.0);
        gain *= 1.0 + .04 * dune * smoothstep(.2, .6, region + .3 * fine);
    }
    if (tests.y) {
        gain *= mix(vec3(1.0), vec3(1.03, .91, .85), smoothstep(.4, .75, broad * .6 + region * .4) * .45);
    }
    if (tests.z) {
        gain *= mix(vec3(1.0), vec3(.92, .82, .77), smoothstep(.55, .8, region * .6 + fine * .4) * .3);
    }
    if (tests.w) {
        // Thin, dry turf in sunny patches and in patches along convex rims, not as a band tracing every edge; lush,
        // dark grass where water gathers below cliffs.
        float dry = max(smoothstep(.55, .85, broad * .7 + fine * .3) * .5, rim * .45 * smoothstep(.3, .75, fine));
        gain *= mix(vec3(1.0), vec3(1.25, 1.12, .7), dry);
        gain *= mix(vec3(1.0), vec3(.78, .95, .82), max(foot * .6, (1.0 - smoothstep(.2, .45, broad)) * .4));
    }
    return albedo * authoredPigmentGain(gain);
}

vec3 groundToneFor(float f, vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    bvec4 tests = lessThan(abs(vec4(f) - vec4(2.0, DESERT_FAMILY, MARS_FAMILY, 0.0)), vec4(.5));
    return groundToneOf(tests, albedo, world, broad, fine, region, rim, foot);
}
