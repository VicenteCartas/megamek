// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Injected into the lit vertex shader. Only roots/ranks are stored; blade shape and wind live on the GPU.
// Older running clients can reload this shader before their Java-side shared size constant is rebuilt.
#ifndef GRASS_MAX_HEIGHT
#define GRASS_MAX_HEIGHT .07
#endif
layout(location = 14) in vec4 a_coverRoot;
uniform float u_coverPixels;
// The shares its quality draws of a meadow's blades and of a field's plants (BoardQuality).
uniform vec2 u_coverShare;
uniform float u_coverHexWidth;
// The Grass height option: a multiple of every blade's height but a field's plants'.
uniform float u_grassHeight;
uniform float u_levelHeight;
uniform float u_metre;
// 1 for a field's plant, .25 for grass lit by its ground (a meadow's, tundra's sward and tussocks), 0 for a rim's blade;
// and how far up its sward a point stands.
out vec2 v_coverData;
out vec2 v_coverRoot;

float grassRandom(uint seed) {
    seed ^= seed >> 16;
    seed *= 0x7feb352du;
    seed ^= seed >> 15;
    seed *= 0x846ca68bu;
    seed ^= seed >> 16;
    return float(seed & 0x00ffffffu) / 16777216.0;
}

// False when this root is beyond the density its projected size allows: the caller skips the rest of the program.
// A chunk's buffer is drawn as a prefix by rank for its nearest hex; farther hexes need fewer of those roots.
bool grassBlade(vec3 samplePoint, out vec3 position, out vec3 normal, out vec4 color) {
    vec3 root = a_coverRoot.xyz;
    // A template's vertices carry their part in z (GpuGroundCover): 0 grass; 1 a wheat stalk of a field's stand
    // (GpuBiomeVegetation.plantStalks), upright and stiff, its ear between WHEAT_EAR and the awns closing it; 2 a maize
    // plant's stalk and tassel and 3 to 8 its leaves; 9 a bank rim's blade (GpuGroundCover.plantRim), whose rank's
    // fraction packs twelve bits: its contour (the middle shoulder's), its lean in eight steps and its heading in 256.
    int part = int(samplePoint.z + .5);
    // 10 a tundra tussock's blade (GpuGroundCover.tufts): its tuft's size in eight steps, its distance from the tuft's
    // centre in eight and its heading out from there in 64.
    bool rim = part == 9, tussock = part == 10, wheat = part == 1, crop = part >= 1 && part <= 8;
    // A rim's fringe and a tussock stand up and thin out alike.
    bool fringe = rim || tussock;
    float pixels = u_coverPixels / max(.001, abs((u_projViewTrans * vec4(root, 1.0)).w));
    // A field's plants thin out as they shrink, each survivor widening by as much, until they fade into the ground's
    // crop colour (terrain-crops.glsl); so does a rim's fringe once it has stood up. Grass only thins out, over the
    // ground's own grass.
    float kept = crop ? cropShare(!wheat, pixels) : 1.0;
    float share = crop ? u_coverShare.y * kept : fringe ? clamp(pixels / GRASS_RIM_KEEP, .001, 1.0) : u_coverShare.x;
    // Survivors widen by a far plant's or a rim's whole thinning, and by the root of what a level (or a meadow) leaves out.
    float widening = crop ? kept * sqrt(u_coverShare.y) : fringe ? share : sqrt(share);
    float density = (crop ? share * cropPlants(pixels) : fringe ? share * smoothstep(GRASS_RIM_START, GRASS_RIM_STAND, pixels)
          : share * smoothstep(GRASS_START_PIXELS, GRASS_FULL_PIXELS, pixels)) * GRASS_ROOTS_PER_HEX;
    if (density <= a_coverRoot.w) { return false; }
    uint seed = floatBitsToUint(root.x) ^ (floatBitsToUint(root.y) * 1664525u) ^ uint(a_coverRoot.w);
    // Reject the whole rooted blade, not fragments of a swaying tip. Buffers remain unchanged after an impact.
    float scar = max(groundScarCover(groundDamageAt(root.xy)), scorchedGrass(root.xy));
    if (scar > grassRandom(seed + 83u)) { return false; }
    int rimCode = int(fract(a_coverRoot.w) * 4096.0 + .5);
    // A grass root's rank carries its ground's slope (GpuGroundCover.slopeCode): its rise per metre in eight steps of .2
    // and its uphill heading in sixteen. Earth tundra's sward marks its roots with half a rank more.
    bool sward = part == 0 && rimCode >= 2048;
    vec2 slope = part == 0 ? float(rimCode >> 4 & 7) * .2 * vec2(cos(float(rimCode & 15) * .3926991), sin(float(rimCode & 15) * .3926991))
          : vec2(0.0);
    if (rim && rimCode >= 2048) {
        // The middle shoulder grows only the broken turf its ground draws (terrain-materials.glsl, cliffMaterial).
        vec3 metres = root / u_metre;
        float pockets = textureLod(u_surfaceNoise, rainNoiseAt((metres.xy + metres.z * vec2(.43, .27)) / 92.0 + .57), 0.0).g;
        float fine = textureLod(u_surfaceNoise, rainNoiseAt(metres.xy / 160.0 + .41), 0.0).b;
        if (smoothstep(.22, .55, pockets * .7 + fine * .3) < grassRandom(seed + 97u)) { return false; }
    }
    // A rim's blades fan out within some 40 degrees of their heading down the bank.
    float angle = (rim ? float(rimCode & 255) / 256.0 + (grassRandom(seed) - .5) * .22
          : tussock ? float(rimCode & 63) / 64.0 + (grassRandom(seed) - .5) * .1 : grassRandom(seed)) * 6.2831853;
    float variation = grassRandom(seed + 23u);
    vec2 direction = vec2(cos(angle), sin(angle));
    vec3 side = vec3(-direction.y, direction.x, 0.0);
    if (crop && !wheat) {
        // Maize, as tall as the stand its slab shows further away (GpuBiomeVegetation): a stalk swaying a little with
        // its tassel above .9, and six leaves alternating up it, arching out from their nodes, widest midway and
        // drooping at their tips, which flutter in the wind. A plant this size grows in over its last 512 ranks,
        // widening rather than appearing.
        float plant = u_levelHeight * CROP_HEIGHT * mix(.92, 1.06, variation) * (1.0 - scar * .5);
        float growth = clamp((density - a_coverRoot.w) / 512.0, 0.0, 1.0) / widening;
        float gust = vegetationGust(root.xy);
        // A blast's wind leans the stalk away from ground zero as its front passes (terrain-shocks.glsl).
        vec3 up = normalize(vec3(u_wind.xy * u_wind.z * (.04 + .04 * gust) + shockWind(root.xy) * .8, 1.0));
        float t = samplePoint.y;
        float tone = grassRandom(seed + 71u);
        if (part == 2) {
            position = root + up * plant * t + side * samplePoint.x * u_coverHexWidth * (t > .9 ? .0016 : .0032) * growth;
            normal = normalize(cross(side, up));
            color = vec4(t > .88 ? cropHead(true, tone) : cropStalk(true, tone), 1.0);
        } else {
            float leaf = float(part - 3);
            float turn = angle + leaf * 3.1415927 + (grassRandom(seed + uint(part) * 29u) - .5) * .9;
            vec2 outward = vec2(cos(turn), sin(turn));
            float reach = plant * mix(.30, .40, 1.0 - abs(leaf - 2.5) / 2.5);
            vec3 node = root + up * plant * mix(.16, .74, leaf / 5.0);
            vec3 lift = vec3(outward * reach * .45, reach * .40);
            vec3 droop = vec3(outward * reach * .95 + u_wind.xy * u_wind.z * reach * (.10 + .12 * gust), reach * -.05);
            vec3 curve = 2.0 * (1.0 - t) * t * lift + t * t * droop;
            vec3 tangent = 2.0 * (1.0 - t) * lift + 2.0 * t * (droop - lift);
            vec3 across = vec3(-outward.y, outward.x, 0.0);
            float width = reach * .10 * pow(sin(3.1415927 * clamp(t * .95 + .05, 0.0, 1.0)), .7);
            position = node + curve + across * samplePoint.x * width * growth;
            normal = normalize(cross(across, tangent));
            color = vec4(cropLeaf(tone, t), 1.0);
        }
        color.rgb = mix(color.rgb, toLinear(vec3(.18, .14, .08)), scar * .75);
        v_coverData = vec2(1.0, clamp((position.z - root.z) / plant, 0.0, 1.0));
        v_coverRoot = root.xy / u_worldMetre;
        return true;
    }
    float meadow = wheat || sward || tussock ? 1.0 : meadowCover(root.xy / u_worldMetre);
    float t = samplePoint.y;
    float height = wheat ? u_levelHeight * CROP_HEIGHT * mix(.9, 1.06, variation)
          : u_coverHexWidth * mix(.025, GRASS_MAX_HEIGHT, variation) * mix(.55, 1.0, meadow);
    float width = wheat ? u_coverHexWidth * (t > WHEAT_EAR.x && t < WHEAT_EAR.y ? .0026 : .0009)
                * mix(.85, 1.15, grassRandom(seed + 37u))
          // A meadow's blade is a pixel wide at its root or more: hairlines shimmer.
          : max(u_coverHexWidth * mix(.0015, .0028, grassRandom(seed + 37u)) * mix(.75, 1.0, meadow),
                .5 * u_coverHexWidth / pixels) * (1.0 - t);
    vec3 stands = vec3(0.0);
    if (sward) {
        // Long blades that lie over: `height` is their length. The straw grows longest, the russet sedge shortest. A
        // blade is a leaf, narrow at its root and widest below its middle, never narrower than two and a half pixels:
        // from above the sward is its blades' upper halves, and hairlines would shimmer. Each blade belongs to one
        // stand or the other, by its own chance.
        stands = tundraSwardStands(root.xy / u_worldMetre);
        stands.xy = smoothstep(-.3, .3, stands.xy - grassRandom(seed + 61u));
        height = u_coverHexWidth * GRASS_MAX_HEIGHT * mix(.65, 1.25, variation) * mix(mix(1.0, .6, stands.x), .8, stands.y * (1.0 - stands.x));
        width = max(u_coverHexWidth * mix(.0034, .0054, grassRandom(seed + 37u)), 1.25 * u_coverHexWidth / pixels)
              * (.35 + .65 * smoothstep(0.0, .45, t)) * (1.0 - t * t * t);
    }
    if (tussock) {
        // A tuft of the sward's grass in its stand's tones, paler: finer blades, standing taller than the sward's are long.
        stands = tundraSwardStands(root.xy / u_worldMetre) * vec3(.5, .5, 1.0);
        height = u_coverHexWidth * GRASS_MAX_HEIGHT * mix(.95, 1.5, float(rimCode >> 9 & 7) / 7.0) * mix(.75, 1.0, variation);
        width = max(u_coverHexWidth * mix(.0022, .0036, grassRandom(seed + 37u)), .75 * u_coverHexWidth / pixels)
              * (.4 + .6 * smoothstep(0.0, .25, t)) * (1.0 - t);
    }
    if (!wheat) { height *= u_grassHeight; }
    height *= 1.0 - scar * .5;
    // Fractional growth of the last blade keeps density transitions continuous, without shading invisible blades.
    float growth = clamp(density - a_coverRoot.w, 0.0, 1.0) / widening;
    float gust = vegetationGust(root.xy);
    vec3 tip, control;
    if (rim) {
        // Behind the lip a blade stands, leaning out; past it nothing holds a blade up: it arches out over the edge and
        // its free end hangs under its own weight, as far as the bank falls away below it (GpuGroundCover.lean),
        // swinging with the gusts.
        float lean = float(rimCode >> 8 & 7) / 7.0, hang = mix(.2, .8, grassRandom(seed + 51u));
        control = height * mix(vec3(direction * .1, .65), vec3(direction * .3, mix(.35, .6, grassRandom(seed + 59u))), lean);
        tip = height * (mix(vec3(direction * .5, .85), vec3(direction * (.85 - .3 * hang), -.75 * hang), lean)
              + vec3(u_wind.xy * u_wind.z * (.15 + .1 * gust), 0.0));
    } else if (tussock) {
        // Blades at the tuft's centre stand; towards its rim they lean out and arch over, their tips drooping to the ground.
        float lean = float(rimCode >> 6 & 7) / 7.0, droop = mix(.3, 1.0, grassRandom(seed + 51u));
        control = height * vec3(direction * mix(.05, .3, lean), .75);
        tip = height * (vec3(direction * mix(.15, .95, lean) * mix(.75, 1.0, droop), mix(1.0, mix(.6, .08, droop), lean))
              + vec3(u_wind.xy * u_wind.z * (.15 + .1 * gust), 0.0));
    } else {
        vec3 rest = vec3(direction * (wheat ? mix(.02, .10, grassRandom(seed + 51u)) : mix(.25, .65, grassRandom(seed + 51u))),
              1.0);
        // The sward lies over, combed one way by the prevailing wind, its tips a little above the ground.
        if (sward) {
            direction = normalize(direction * .75 + vec2(.84, -.54));
            side = vec3(-direction.y, direction.x, 0.0);
            rest = vec3(direction, mix(.2, .75, grassRandom(seed + 51u)));
        }
        float alongWind = dot(rest.xy, u_wind.xy);
        vec3 acrossWind = rest - vec3(u_wind.xy * alongWind, 0.0);
        float acrossLength = length(acrossWind);
        // Equal strength steps turn the blade through equal angles, retaining its length and both endpoint poses.
        // Scaling displacement before normalizing used up most of the visible bend near zero strength.
        float windAngle = mix(atan(alongWind, acrossLength), atan(alongWind + 12.5 + 9.0 * gust, acrossLength),
              u_wind.z * (wheat ? .5 : 1.0));
        tip = height * (acrossWind * (cos(windAngle) / acrossLength) + vec3(u_wind.xy * sin(windAngle), 0.0));
        control = vec3(0.0, 0.0, height * (wheat ? .5 : .65));
        // It leaves the ground at a slant and arches over.
        if (sward) { control = height * vec3(direction * .18, .62); }
    }
    // A blast's wind flattens the blade away from ground zero as its front passes, and lets it spring back
    // (terrain-shocks.glsl).
    vec2 blast = shockWind(root.xy);
    float flattened = min(length(blast), 1.0);
    if (flattened > 0.0) {
        vec2 away = blast / length(blast);
        tip = mix(tip, height * vec3(away * .96, .14), flattened);
        control = mix(control, height * vec3(away * .42, .32), flattened);
    }
    vec3 curve = 2.0 * (1.0 - t) * t * control + t * t * tip;
    vec3 tangent = 2.0 * (1.0 - t) * control + 2.0 * t * (tip - control);
    // The sward lies along its ground.
    if (sward) { curve.z += dot(slope, curve.xy); }
    position = root + curve + side * samplePoint.x * width * growth;
    // Grass is lit as its ground is, each blade tilted a little its own way: lit by their own faces, blades pepper a
    // meadow with dark pixels and stand out bright on a bank turned from the sun. A tussock is lit as a mound, its
    // blades' normals leaning out with them.
    normal = tussock ? normalize(vec3(direction * (.25 + .55 * float(rimCode >> 6 & 7) / 7.0), 1.0))
          : part == 0 ? normalize(vec3(-slope + .25 * vec2(cos(angle), sin(angle)), 1.0)) : normalize(cross(side, tangent));
    // A rim's blades hang half under the sward above them, in the meadow's darker tones.
    float tone = grassRandom(seed + 71u) * (rim ? .6 : 1.0);
    color = vec4(wheat ? (t > WHEAT_EAR.x ? cropHead(false, tone) : cropStalk(false, tone))
          // The first blades to stand, far apart, keep to their thatch's colour: sunlit tips come with the full sward.
          : sward ? tundraSwardColor(stands, mix(.55, .4 + .6 * smoothstep(0.0, .6, t),
                smoothstep(GRASS_START_PIXELS, GRASS_FULL_PIXELS, pixels)), tone)
          // A tuft is dark at its heart and pale at its tips.
          : tussock ? tundraSwardColor(stands, .2 + .8 * smoothstep(0.0, .7, t), tone) * mix(.6, 1.0, smoothstep(0.0, .35, t))
          : mix(toLinear(vec3(.24, .30, .10)), toLinear(vec3(.44, .48, .21)), tone), 1.0);
    color.rgb = mix(color.rgb, toLinear(vec3(.18, .14, .08)), scar * .75);
    v_coverData = vec2(wheat ? 1.0 : part == 0 || tussock ? .25 : 0.0, t);
    v_coverRoot = root.xy / u_worldMetre;
    return true;
}
