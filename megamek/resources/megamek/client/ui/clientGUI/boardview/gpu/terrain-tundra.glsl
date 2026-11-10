// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Reference-authored lichen/tussocks, fungal colonies and barren mineral crust in the shared texture array.
// Preserve actual image pigments and internal detail instead of thresholding them into flat coloured spots.
// earth (dry fell-field), fungus, mineral, wet earth (autumn sedge); normal/AO at colour layer + 1
uniform vec4 u_tundraLayers, u_tundraTiles;
// The earth carpets at their own full size: colour/height and normal/AO, dry layer 0 and wet layer 1.
// FULL_SIZE_MAPS

// The ground's distance in metres to the nearest drop (x) and rise (y), set by the sculpt before biomeSurface.
vec2 tundraLandform = vec2(99.0);

// How wet the tundra stands, which sets its sedge share: in the hollows at the foot of rises, unless a drop is near.
// The fell-field carpet carries its own lichen, moss and gravel everywhere else. The transitions are metres wide, so
// the two carpets meet as tones, never as patches.
float tundraWetness() {
    float hollow = 1.0 - smoothstep(1.0, 9.0, tundraLandform.y);
    float dry = 1.0 - smoothstep(1.0, 6.0, tundraLandform.x);
    return hollow * .85 * (1.0 - .8 * dry);
}

// BoardBiome.sward: earth tundra's grass covers most of its carpet and leaves openings of lichen, moss and gravel some
// metres across. The CPU plants the sward's roots by this field, and their ground takes its colour (tundraSurface).
float tundraSward(vec2 metres) {
    vec2 p = metres + 6.0 * (vec2(biomeNoise(metres / 19.0 + vec2(7.0, -3.0)),
          biomeNoise(metres / 19.0 + vec2(-13.0, 5.0))) - .5);
    float field = .65 * biomeNoise(p / 13.0 + vec2(3.0, 11.0)) + .35 * biomeNoise(p / 4.1 + vec2(-17.0, 23.0));
    return smoothstep(.18, .50, field);
}

vec3 tundraFamily(float f) {
    if (abs(f - FUNGUS_FAMILY) < .5) return vec3(0.0, 1.0, 0.0);
    if (abs(f - MARS_FAMILY) < .5 || abs(f - LUNAR_FAMILY) < .5 || abs(f - VOLCANO_FAMILY) < .5) {
        return vec3(0.0, 0.0, 1.0);
    }
    return vec3(1.0, 0.0, 0.0);
}

// Tundra mats: the top projection's two reads (topCoordinates), two offsets of one repeat, or for the earth carpets
// (scales) the ground's two repeats, the second 2.37 times larger and turned. Volcanic programs, whose natural
// material draws every face, add the steep sides with one read per projection.
vec4 tundraTexel(sampler2DArray maps, float layer, float tile, MaterialProjection p, float variation, bool scales) {
    vec2 a, b;
    mat2 gradientB;
    float t;
    topCoordinates(p, tile, variation, !scales, a, b, gradientB, t);
    vec4 top = mixedTexel(maps, layer, a, p.topGradient / tile, b, gradientB, t);
#ifdef volcanicFlag
    if (p.lying < 1.0) {
        vec4 steep = mix(materialTexel(maps, layer, p.y / tile + .37, p.yGradient / tile),
              materialTexel(maps, layer, p.x / tile, p.xGradient / tile), p.side);
        top = mix(steep, top, p.lying);
    }
#endif
    return top;
}

// A mat's authored finish from its colour's reads: roughness, metallic and scattering, and emission.
Finish tundraFinish(float layer, float tile, MaterialProjection p, float variation, bool scales) {
    int material = int(layer * .5);
    vec4 surface = u_finishSurface[material], emission = u_finishEmission[material];
    Finish texel = meanFinish(material);
    if (surface.a >= 0.0) {
        texel = finishOf(tundraTexel(FINISH_LAYERS, surface.a, tile, p, variation, scales).rgb, texel.emission);
    }
    if (emission.a >= 0.0) {
        texel.emission += emission.rgb * toLinear(tundraTexel(FINISH_LAYERS, emission.a, tile, p, variation, scales).rgb);
    }
    return texel;
}

void tundraSurface(vec3 world, vec3 face, float amount, inout vec3 color, inout vec3 normal,
      inout float cavity, inout Finish finish, inout float grass, inout vec3 bounce) {
    MaterialProjection projection = materialProjection(world, face);
    amount *= smoothstep(.08, .65, face.z);
    if (amount < .0001) return;
    vec4 kinds = vec4(tundraFamily(sculptFamily), 0.0);
    float meadow = family(0.0) || family(TROPICAL_FAMILY) ? 1.0 : 0.0;
    float snow = family(5.0) ? 1.0 : 0.0;
    float concrete = family(4.0) ? 1.0 : 0.0;
#ifdef terrainBlendFlag
    vec4 weights = max(v_coverWeights, vec4(0.0));
    weights /= max(dot(weights, vec4(1.0)), .0001);
    kinds = vec4(0.0); meadow = 0.0; snow = 0.0; concrete = 0.0;
    for (int i = 0; i < 4; i++) {
        float f = coverFamilies[i];
        kinds.xyz += tundraFamily(f) * weights[i];
        meadow += (abs(f) < .5 || abs(f - TROPICAL_FAMILY) < .5 ? 1.0 : 0.0) * weights[i];
        snow += (abs(f - 5.0) < .5 ? 1.0 : 0.0) * weights[i];
        concrete += (abs(f - 4.0) < .5 ? 1.0 : 0.0) * weights[i];
    }
#endif
    if (kinds.x > .0001) {
        kinds.w = kinds.x * tundraWetness();
        kinds.x -= kinds.w;
    }
    // Keep the mats' colonies intact over broad regions; colour and relief use the same slow translation field. The
    // fine-mixed earth carpets blend their two scales by a quicker field, as ordinary ground does, so their repeat
    // never shows.
    float variation = biomeNoise(world.xy / 80.0 + 29.0);
    float scaleMix = variation * .6 + texture(u_surfaceNoise, rainNoiseAt(world.xy / 92.0 + .57)).g * .4;
    vec3 treated = vec3(0.0), mappedNormal = vec3(0.0);
    float mappedCavity = 0.0;
    Finish mappedFinish = Finish(0.0, 0.0, 0.0, vec3(0.0));
    float concreteLichen = 0.0;
    for (int i = 0; i < 4; i++) {
        if (kinds[i] < .0001) continue;
        bool carpet = i == 0 || i == 3;
        // The earth carpets keep their own repeat foremost, for their crisp detail, the larger read only varying it.
        float mixer = carpet ? scaleMix * .5 : variation;
        vec2 carpetLayers = i == 0 ? vec2(FULL_SIZE_CARPET_COLOR.x, FULL_SIZE_CARPET_DATA.x)
              : vec2(FULL_SIZE_CARPET_COLOR.y, FULL_SIZE_CARPET_DATA.y);
        vec4 source = carpet ? tundraTexel(u_fullSizeColor, carpetLayers.x, u_tundraTiles[i], projection, mixer, true)
              : tundraTexel(u_terrainLayers, u_tundraLayers[i] * .5, u_tundraTiles[i], projection, mixer, false);
        float light = dot(source.rgb, vec3(.2126, .7152, .0722));
        vec3 pigment = source.rgb;
        if (i == 0 || i == 3) {
            // Keep a little local geology, without turning fine pigment into another patch coverage mask.
            float cover = max(meadow + snow, .88);
            pigment = mix(color, source.rgb, clamp(cover, 0.0, 1.0));
            // Retain the source's pale lichen branches, leaving concrete visible through the warm soil/tussocks.
            // Relative linear chroma separates pale lichen from warm soil without encoding the sampled pigment.
            float warmth = (source.r - source.b) / max(source.r + source.b, .001);
            float lichen = smoothstep(toLinear(vec3(.30)).r, toLinear(vec3(.48)).r, light)
                  * (1.0 - smoothstep(.25, .55, warmth));
            concreteLichen += lichen * kinds[i];
            pigment = mix(pigment, source.rgb * toLinear(vec3(.72, .82, .64)), concrete);
        } else if (i == 1) {
            // Fungal mats retain their authored lilac/cyan/copper detail and the local plum ground.
            pigment = mix(color, source.rgb, .88);
        } else {
            float localLight = max(dot(color, vec3(.2126, .7152, .0722)), toLinear(vec3(.15)).r);
            vec3 mineralTint = clamp(color / localLight, vec3(.65), vec3(1.35));
            pigment = mix(color, source.rgb * mineralTint * 1.12, .75);
        }
        treated += pigment * kinds[i];
        mappedFinish = addFinish(mappedFinish,
              tundraFinish(u_tundraLayers[i], u_tundraTiles[i], projection, mixer, carpet), kinds[i]);
        if (u_normalMaps > .5) {
            vec4 n = carpet ? tundraTexel(u_fullSizeData, carpetLayers.y, u_tundraTiles[i], projection, mixer, true)
                  : tundraTexel(u_terrainData, u_tundraLayers[i] * .5, u_tundraTiles[i], projection, mixer, false);
            vec3 relief = upNormal(n.rgb * 2.0 - 1.0, face);
#ifdef volcanicFlag
            if (projection.lying < 1.0) {
                relief = normalize(mix(wallNormal(n, n, face, projection.side), relief, projection.lying));
            }
#endif
            mappedNormal += relief * kinds[i];
            mappedCavity += mix(.55, 1.0, n.a) * kinds[i];
        }
    }
    // Earth's carpets grow a sward (BoardBiome.sward). Where its blades root, the ground is their thatch: the sward's
    // stands' colours over the grain of the theme's own ground (its colour against its mean, the bounce), so the sward
    // keeps its look where the blades are too small to draw.
    float sward = (kinds.x + kinds.w) * (1.0 - concrete);
    if (sward > .0001) {
        sward *= tundraSward(world.xy);
        const vec3 LUMA = vec3(.2126, .7152, .0722);
        float grain = clamp(dot(color, LUMA) / max(dot(bounce, LUMA), .001), .45, 1.7);
        // The thatch is paler in its stands' colours than the blades standing over it.
        vec3 stands = tundraSwardStands(world.xy) * vec3(.85, .85, 1.0);
        treated = mix(treated, tundraSwardColor(stands, .55, .5) * mix(1.0, grain, .6), sward * .9);
    }
    amount *= mix(1.0, concreteLichen / max(kinds.x + kinds.w, .0001), concrete);
    // The Snow theme retains quiet snow remnants, free of lichen speckling. Explicit SNOW wins at capture.
    float remnant = smoothstep(.55, .72, biomeNoise(world.xy / 8.0 + 29.0)) * snow;
    amount *= 1.0 - remnant;
    // Under the sward the theme's ground keeps its own finish and relief.
    float carpet = amount * (1.0 - .8 * sward);
    color = mix(color, treated, amount);
    finish = mixFinish(finish, mappedFinish, carpet);
    if (u_normalMaps > .5) {
        normal = normalize(mix(normal, normalize(mappedNormal), carpet));
        cavity = mix(cavity, mappedCavity, carpet);
    }
    grass *= 1.0 - amount * (1.0 - sward);
    bounce = mix(bounce, treated * .25, amount);
}
