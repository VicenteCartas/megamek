#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Falling curtains and their rounded crests; the crest shares the pool material.
// water-uniforms
// water-lighting-functions
// biome-water-functions
// water-pool-functions

void main() {
    // Capture before coverage rejection, shared by geometric facing and the reflected cloud-plane footprint.
    waterPositionDx = dFdx(v_cloudPosition);
    waterPositionDy = dFdy(v_cloudPosition);
    vec3 geometric = visibleFace(waterPositionDx, waterPositionDy, -viewDirection());
    vec3 surfaceNormal = normalize(v_normal);
    surfaceVariance = normalVariance(dFdx(surfaceNormal), dFdy(surfaceNormal));
    vec4 habitat, mixture;
    waterHabitat(habitat, mixture);
    vec3 tint = waterTint(mixture);
    bool procedural = u_waterMaterial.y > 0.5;
#ifdef authoredWaterFlag
    vec3 authored = procedural ? vec3(0.0) : texture(u_diffuseTexture, v_diffuseUV).rgb;
#else
    vec3 authored = vec3(0.0);
#endif
    WaterLighting illumination = waterLighting(false);
    float palette = u_waterMaterial.x, effects = u_waterEffects;
    vec2 position = v_cloudPosition.xy * u_rainScale;
    vec3 view = -viewDirection();
    vec3 scatter = waterScatter(mixture), froth = waterFroth(mixture, tint);
    float drop = v_color.r;    // 0 where the sheet leaves the pool, 1 where it lands
    float fray = v_color.g;    // 0 at a free side, 1 inside it and where the next fall carries on
    float height = v_color.b;  // drop height, as a fraction of four levels
    // X along the crest, running on round corners shared with the next fall, where it agrees to a whole unit:
    // every pattern across the sheet repeats a whole number of times per unit, so none breaks at a corner.
    vec2 uv = v_diffuseUV;
    // Time of flight: water crosses the crest at about 1.5 m/s and then falls freely, so every feature speeds
    // up and stretches as it falls, while one steady clock scrolls them all without ever shearing the pattern.
    float metres = height * 4.0 * u_levelHeight / max(u_metre, 0.001);
    float fallen = drop * metres;
    float flow = 2.0 * fallen / (sqrt(2.25 + 2.0 * u_gravity * fallen) + 1.5) - u_rainTime;
    // The streaks wander slowly across the sheet as they fall, by where they are, so neighbouring sheets agree.
    float wander = texture(u_surfaceNoise, rainNoiseAt(position * 1.4 + vec2(0.13, flow * 0.05))).r - 0.5;
    float x = uv.x + wander * 0.36;
    // Long ribbons and fine threads, stretched far along the flow so the white spreads as streaks rather than
    // blobs, and the clumps tumbling inside them, each at its own scale.
    float ribbons = texture(u_surfaceNoise, vec3(vec2(x, flow * 0.07), WATER_DETAIL)).b;
    float threads = texture(u_surfaceNoise, vec3(vec2(x * 3.0 + 0.71, flow * 0.22), WATER_DETAIL)).b;
    float clumps = texture(u_surfaceNoise, vec3(vec2(x + 0.37, flow * 0.9), WATER_DETAIL)).b;
    // Toward the foot the sheet breaks up into tumbling, rounded billows.
    float billows = texture(u_surfaceNoise, vec3(vec2(x + 0.19, flow * 1.8 + drop * 2.0), WATER_DETAIL)).b;
    float foot = smoothstep(0.55, 0.95, drop);
    float foamy = mix(ribbons * 0.52 + threads * 0.36 + clumps * 0.12, billows * 0.6 + clumps * 0.4, foot);
    // Water leaves the crest clear and blue and gathers air all the way down: the white spreads from a few
    // streaks under the crest, through streaks with blue gaps, to the whole sheet at the foot. Air mixes in
    // over the metres fallen, so a tall fall is white over most of its height and a short one stays bluer.
    float aerate = max(pow(drop, 1.3) * 0.9, 1.0 - exp(-fallen / 5.0));
    float threshold = mix(0.85, 0.02, aerate);
    float white = smoothstep(threshold, threshold + 0.22, foamy);
    white = max(white, smoothstep(0.85, 1.0, drop) * aerate) * mix(0.35, 1.0, effects);
    // Ragged free sides; where the next fall carries on round a corner the sheet stays whole.
    float sides = smoothstep(0.05, 0.9, fray + (clumps - 0.5) * 0.5);
    // The white foot plunges whole into the boil it raises and only vanishes, raggedly, in its last metre.
    float dissolve = smoothstep(0.0, 0.6, metres - fallen - 0.8 * (1.0 - billows));
    float sheetRoughness = mix(.16, .45, aerate);
    // Preserve the geometric lip and volume-scattering normal; only the shared optical reflection frame is repaired.
    vec3 reflectionNormal = shadingNormal(surfaceNormal, geometric, view);
    sheetRoughness = antialiasedRoughness(sheetRoughness, surfaceVariance);
    // The crest's pool completes the illumination with the local lights, and mirrors them for the sheet too.
    vec3 sheetGlint;
    vec4 pool = waterPool(true, geometric, habitat, mixture, tint, authored, illumination, reflectionNormal,
          sheetRoughness, sheetGlint);
    float sheetFresnel = specularAlbedo(max(0.0, dot(reflectionNormal, view)), sheetRoughness, vec3(.02037)).r;
    vec3 sheetReflection = waterEnvironmentReflection(reflectionNormal, view, sheetRoughness, illumination);
    // Clear water pouring over the crest shows the pool's blue, lit through and mirroring the sky.
    vec3 glassPigment = procedural ? mix(waterShallows(palette), scatter, 0.35) * 1.1 : authored * tint;
    vec3 glass = materialReflectance(glassPigment * (0.85 + 0.35 * ribbons))
          * illumination.light * (1.0 - sheetFresnel) + sheetReflection;
    vec3 fallLight = illumination.whiteLight;
    // Only water still smooth mirrors a light: bubbles scatter the glint away as the sheet aerates.
    float glassy = (1.0 - aerate) * (1.0 - aerate);
#if defined(lightingFlag) && numDirectionalLights > 0
    vec3 sun = -u_dirLights[0].direction;
    float facingSun = dot(surfaceNormal, sun);
    // White water scatters light through its thickness: faces turned to the sun glow, and light passing through
    // lifts the shaded side. The glassy crest mirrors the sun in a bright line.
    fallLight = max(illumination.whiteLight, illumination.ambient + illumination.sunlight * (0.3 * max(sun.z, 0.0)
          + 0.7 * max(facingSun, 0.0) + 0.3 * max(-facingSun, 0.0)));
    glass += illumination.sunlight * float(dot(geometric, sun) > 0.0)
          * waterDirectReflection(reflectionNormal, sun, view, sheetRoughness) * glassy;
#endif
    glass += sheetGlint * glassy;
    // Between the streaks the water itself turns milky and opaque as bubbles fill it.
    glass = mix(glass, froth * fallLight * 0.8, aerate * 0.5);
    vec3 sheet = mix(glass, materialReflectance(froth * mix(0.85, 1.35, foamy)) * fallLight, white);
    float cover = mix(mix(0.55, 0.85, aerate) + 0.15 * ribbons, 0.97, white) * sides * dissolve;
    // Still level where it leaves the pool it keeps the pool's look; once it curves over, the fall's own.
    float lipping = procedural && drop < 0.5 ? smoothstep(0.88, 0.995, surfaceNormal.z) : 0.0;
    waterOutput(mix(vec4(sheet * cover, cover), pool, lipping));
}
