// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The compact natural-ground material: one evaluator for every surface family, the families' rules as numbers in the
// board's material table (BoardMaterials, GroundFamilies.java), two roles a pixel, one projection a role (two only in
// the narrow bands where a face turns between them), a height blend between the roles and the family's tone. It returns
// the material before the live layers (weathering, damage, scorch, rain, the grid and the level grade) and the light.
// Behind -Dmegamek.gpu.compactGround (GroundFamilies.ON, compactGroundFlag); the includer names its texture arrays and noise:
// GROUND_COLOUR and GROUND_DATA (colour/height and normal/AO, the same layer), GROUND_NOISE(uv) the tiling 64-texel
// noise read at uv in repeats; GROUND_FAMILIES the table's rows (BoardScene.Surface's count); authoredPigmentGain
// (terrain-ground-color.glsl); optionally BOARD_MATERIALS_LAYOUT, the block's layout with an explicit binding where the
// language has one.
#ifdef compactGroundFlag
#ifndef BOARD_MATERIALS_LAYOUT
#define BOARD_MATERIALS_LAYOUT layout(std140)
#endif

// One family's rules (GroundFamilies.Row): 19 vec4, 304 bytes. Roles in order cover, mantle, deposit, rock.
struct GroundFamily {
    vec4 layers;       // colour/height layer of each role (its normal/AO: the same layer of GROUND_DATA)
    vec4 tiles;        // metres per repeat of each role
    vec4 roughness;    // each role map's mean perceptual roughness
    vec4 cover;        // the cover's smoothstep of the slope: low, high, jitter by variation, lift by a foot deposit
    vec4 trim;         // what thins the cover: rim scour, thin patches (only on an exposed rim where z is 1), crest shoulder
    vec4 mantle;       // the bare share's mantle: bank weight (by 1 - exposure), its fade on level ground, rim share, crest cap
    vec4 deposit;      // the foot deposit's share, its share on banks (mix(y, 1, rock)), dust on level stone, wear-through
    vec4 edges;        // two-level turf lip (0/1), crest cornice (0/1), where soil banks end the cover, soil river banks (0/1)
    vec4 shore;        // sediment exponent on shallow coasts, the shallow arid profile (0/1), wall weathering, rim abrasion
    vec4 toneTintA;    // a tone patch: rgb gain where it is whole, a amount
    vec4 toneFieldA;   // its field: weights of broad, region and fine, w the smoothstep's low
    vec4 toneEdgeA;    // x the smoothstep's high, y rim weight, z foot weight, w drift ripples (for A only)
    vec4 toneTintB;    // a second tone patch, as A
    vec4 toneFieldB;
    vec4 toneEdgeB;
    vec4 floor;        // a sunken floor from level -1 down: colour layer (< 0 none), repeat; z crust colonies (0/1)
    vec4 rootMat;      // rgb the tint a two-level lip's sod leaves on the bank below it
    vec4 abrasionLow;  // rgb a rim's abraded rock below its crest, a unused
    vec4 abrasionHigh; // rgb the same at the crest
};

BOARD_MATERIALS_LAYOUT uniform BoardMaterials {
    GroundFamily groundFamilies[GROUND_FAMILIES];
    vec4 groundRiverBank; // a soil river bank's layers: soil layer, repeat, gravel layer, repeat
    vec4 groundFlatRock;  // the shared cliff's layer, the rock tops' layer and repeat, which level bedrock shows instead
};

// What the caller knows at the pixel. Gradients come from before any varying branch.
struct GroundPixel {
    vec3 world;              // metres
    vec3 face;               // unit smooth normal
    vec2 top, x, y;          // the projections' coordinates in metres: the plane, and the two sides (MaterialProjection)
    mat2 topGradient, xGradient, yGradient;
    float side;              // the share of the x side's projection (MaterialProjection.side)
    vec2 facing;             // the side projections' signs along X and Y
    float aboveFoot, belowRim; // metres
    float rock;              // 0 an earth bank .. 1 a rock cliff
    float hardness;          // bed hardness (walls), .5 on ground
    float sediment;          // 0 dry .. 1 a water bed (the caller's shore rule)
    float shallowSediment;   // a depth-zero arid shore's halfway profile, < 0 elsewhere
    bool riverBank;          // a river's ordinary bank
    float role;              // 0 top cover .. 1 step face (walls, steep banks, bedrock), continuous across their edge
    bool covered;            // a biome deposit replaces the colour and normal (terrainCovered)
    float levelMetres;       // metres a level
    float pixelMetres;       // metres a screen pixel
    bool normalMaps;
};

struct GroundSurface {
    vec3 albedo;
    vec3 normal;
    float cavity;
    float height;
    float roughness;
    float relief;            // the agreement of the normals under the pixel (sunOnRelief)
    vec4 roles;              // the final share of cover, mantle, deposit and rock
};

// The ground's fields (broad, fine, region) from three noise reads, one a scale: broad patches (~9 m cells) as two
// independent channels mixed, which keeps the spread of today's two-scale mix, fine ones (~2.5 m) and the region (~40 m).
vec3 compactFields(vec3 world) {
    vec4 broad = GROUND_NOISE(world.xy / 600.0 + .19);
    return vec3(broad.g * .6 + broad.b * .4, GROUND_NOISE(world.xy / 160.0 + .41).b, GROUND_NOISE(world.xy / 2600.0 + .73).r);
}

float groundSpread(vec4 texel) { return .5 * (1.0 - min(length(texel.rgb * 2.0 - 1.0), 1.0)); }

// One role's colour/height and normal/AO, cavity in data.a. Tops read the plane. Step faces read the side they face,
// both sides only where the face turns between them, and the plane too where they lie back; between a top and a face
// (role between 0 and 1) both.
void groundRead(GroundPixel px, float layer, float tile, out vec4 colour, out vec3 normal, out float cavity,
      out float spread) {
    tile = max(tile, 1e-4);
    float lying = 1.0 - px.role * (1.0 - smoothstep(.6, .8, px.face.z));
    float side = smoothstep(.3, .7, px.side);
    colour = vec4(0.0);
    vec3 n = vec3(0.0);
    vec4 data = vec4(0.0);
    cavity = 0.0;
    spread = 0.0;
    if (lying > 0.0) {
        vec3 uv = vec3(px.top / tile, layer);
        vec4 c = textureGrad(GROUND_COLOUR, uv, px.topGradient[0] / tile, px.topGradient[1] / tile);
        vec4 d = px.normalMaps ? textureGrad(GROUND_DATA, uv, px.topGradient[0] / tile, px.topGradient[1] / tile)
              : vec4(.5, .5, 1.0, 1.0);
        vec3 detail = d.rgb * 2.0 - 1.0;
        colour += c * lying;
        n += normalize(vec3(detail.x + px.face.x, px.face.y - detail.y, detail.z * px.face.z)) * lying;
        cavity += d.a * lying;
        spread += groundSpread(d) * lying * lying;
    }
    if (lying < 1.0) {
        float weight = 1.0 - lying;
        if (side > 0.0) {
            vec3 uv = vec3(px.x / tile, layer);
            vec4 c = textureGrad(GROUND_COLOUR, uv, px.xGradient[0] / tile, px.xGradient[1] / tile);
            vec4 d = px.normalMaps ? textureGrad(GROUND_DATA, uv, px.xGradient[0] / tile, px.xGradient[1] / tile)
                  : vec4(.5, .5, 1.0, 1.0);
            vec3 detail = d.rgb * 2.0 - 1.0;
            float w = weight * side;
            colour += c * w;
            n += normalize(vec3(detail.z * px.face.x, detail.x * px.facing.x + px.face.y, px.face.z - detail.y)) * w;
            cavity += d.a * w;
            spread += groundSpread(d) * w * w;
        }
        if (side < 1.0) {
            vec3 uv = vec3(px.y / tile + .37, layer);
            vec4 c = textureGrad(GROUND_COLOUR, uv, px.yGradient[0] / tile, px.yGradient[1] / tile);
            vec4 d = px.normalMaps ? textureGrad(GROUND_DATA, uv, px.yGradient[0] / tile, px.yGradient[1] / tile)
                  : vec4(.5, .5, 1.0, 1.0);
            vec3 detail = d.rgb * 2.0 - 1.0;
            float w = weight * (1.0 - side);
            colour += c * w;
            n += normalize(vec3(-detail.x * px.facing.y + px.face.x, detail.z * px.face.y, px.face.z - detail.y)) * w;
            cavity += d.a * w;
            spread += groundSpread(d) * w * w;
        }
    }
    normal = px.normalMaps && !px.covered ? normalize(n) : px.face;
    cavity = px.normalMaps && !px.covered ? mix(.55, 1.0, cavity) : 1.0;
}

// A tone patch's share at the pixel: its field's smoothstep times its amount, or its rim or foot weight there.
float groundPatch(vec4 tint, vec4 field, vec4 edge, vec3 fields, float rim, float foot) {
    float spread = smoothstep(field.w, edge.x, dot(field.xyz, fields.xzy)) * tint.a;
    return max(spread, max(edge.y * rim * smoothstep(.3, .75, fields.y), edge.z * foot));
}

// Broad variation of the ground's tone, so a field reads neither flat nor tiled, and the family's own patches.
vec3 groundTone(GroundFamily f, vec3 colour, vec3 world, vec3 fields, float rim, float foot) {
    vec3 gain = vec3(mix(.94, 1.06, fields.x) * mix(.95, 1.05, fields.z) * mix(.96, 1.04, fields.y));
    if (f.toneEdgeA.w > 0.0) {
        gain *= 1.0 + f.toneEdgeA.w * sin(dot(world.xy, vec2(.8, .6)) / 19.0 + fields.x * 5.0 + fields.z * 3.0)
              * smoothstep(.2, .6, fields.z + .3 * fields.y);
    }
    gain *= mix(vec3(1.0), f.toneTintA.rgb, groundPatch(f.toneTintA, f.toneFieldA, f.toneEdgeA, fields, rim, foot));
    gain *= mix(vec3(1.0), f.toneTintB.rgb, groundPatch(f.toneTintB, f.toneFieldB, f.toneEdgeB, fields, rim, foot));
    return colour * authoredPigmentGain(gain);
}

GroundSurface groundSurface(GroundPixel px, int family, vec3 fields) {
    GroundFamily f = groundFamilies[family];
    float broad = fields.x, fine = fields.y, region = fields.z;
    float up = clamp(px.face.z, 0.0, 1.0);
    float variation = broad * .4 + fine * .6;
    float sediment = px.sediment;
    if (px.shallowSediment >= 0.0) {
        if (f.shore.y > .5) sediment = px.shallowSediment;
        sediment = pow(sediment, f.shore.x);
    }
    float foot = exp(-max(px.aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    float rock = px.rock;
    float exposure = clamp(rock + (variation - .5) * 1.2 * (4.0 * rock * (1.0 - rock)), 0.0, 1.0);
    float rim = 0.0, scour = 0.0, shoulder = 0.0;
    if (px.belowRim < 7.0) {
        rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, px.belowRim);
        scour = (1.0 - smoothstep(.1, 3.0 + 4.0 * broad, px.belowRim)) * exposure * smoothstep(.30, .65, broad * .6 + fine * .4);
        shoulder = (1.0 - smoothstep(.1, 1.3 + 1.6 * broad, px.belowRim)) * smoothstep(.28, .65, fine) * smoothstep(.55, .94, up);
    }
    float coverUp = up;
    if (up < .985) coverUp -= .2 * (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up)) * smoothstep(.32, .68, fine);
    float thin = smoothstep(.50, .72, region * .6 + broad * .3 + fine * .1) * smoothstep(.82, .97, up)
          * (1.0 - deposit) * (1.0 - .75 * foot);
    float cover = smoothstep(f.cover.x, f.cover.y, coverUp + (variation - .5) * f.cover.z + deposit * f.cover.w);
    cover *= 1.0 - max(f.trim.x * scour, max(f.trim.y * thin * mix(1.0, rim * exposure, f.trim.z), f.trim.w * shoulder));
    if (f.edges.y > 0.0 && px.role > 0.0) {
        // A crest's ragged cornice: the cover caps a bank's rim, the rock breaks out below it; rock cliffs keep their edge.
        float lip = mix(.5, 2.2, fine);
        cover = max(cover, f.edges.y * px.role * (1.0 - smoothstep(lip * .4, lip, px.belowRim)) * (1.0 - exposure));
    }
    float sod = 0.0;
    if (f.edges.x > 0.0) {
        // A two-level bank keeps a ragged turf lip and broken turf on its intermediate shoulder.
        float twoLevels = 1.0 - smoothstep(.05, .3, abs((px.aboveFoot + px.belowRim) / px.levelMetres - 2.0));
        if (twoLevels > 0.0) {
            float fray = GROUND_NOISE((px.world.xy + px.world.z * vec2(.29, .41)) / 28.0 + .217).r;
            float lipDepth = px.levelMetres * (mix(.2, .65, fine) + .14 * (fray - .5));
            float lip = 1.0 - smoothstep(.05, lipDepth, px.belowRim);
            float middle = abs(px.aboveFoot / px.levelMetres - 1.0 - (broad - .5) * .18);
            float width = mix(.1, .24, fine) * mix(.55, 1.45, fray);
            float turf = (1.0 - smoothstep(width * .25, width, middle)) * smoothstep(.35, .73, up)
                  * smoothstep(.22, .55, fine);
            cover = max(cover, f.edges.x * max(lip, turf) * twoLevels * (1.0 - exposure));
            float edge = .52 * lipDepth, depth = px.levelMetres * mix(.08, .16, fray);
            sod = (1.0 - smoothstep(edge + .4 * depth, edge + depth, px.belowRim)) * twoLevels * (1.0 - exposure)
                  * (1.0 - smoothstep(.85, .97, up));
        }
    }
    deposit *= mix(f.deposit.y, 1.0, rock);
    // Organic wear opens level cover onto its soil.
    float aged = smoothstep(.45, .68, broad * .5 + region * .35 + fine * .15) * smoothstep(.88, .98, up) * (1.0 - sediment);
    float worn = cover * aged * f.deposit.w;
    cover -= worn;
    float bare = 1.0 - cover;
    float mantle = bare * (f.mantle.x * (1.0 - exposure) * (1.0 - f.mantle.y * smoothstep(.90, .99, up))
          + f.mantle.z * rim * smoothstep(.25, .7, variation));
    mantle = max(mantle, bare * f.mantle.w * max(smoothstep(.82, .97, up), rim * smoothstep(.25, .90, up)));
    mantle = max(mantle, worn);
    deposit *= f.deposit.x;
    if (f.floor.z > 0.0) {
        // Crust colonies: lowland skin below level zero, crust colonies above it and along raised rims.
        float rise = smoothstep(.25, .9, (px.aboveFoot + px.belowRim) / px.levelMetres);
        float raised = smoothstep(.15, 1.3, px.world.z / px.levelMetres);
        float lip = 1.0 - smoothstep(.1, 1.4 + 1.6 * fine, px.belowRim);
        float growth = max(raised, lip * rise);
        float colonies = smoothstep(.28, .69, region * .5 + broad * .3 + fine * .2);
        deposit = max(deposit, smoothstep(.48, .88, up) * mix(.10 * colonies, .28 + .70 * colonies, growth)
              * (1.0 - aged * .55));
        cover = smoothstep(.76, .97, up) * (1.0 - .90 * thin * (1.0 - growth));
        mantle = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    }
    vec4 layers = f.layers, tiles = f.tiles, roughness = f.roughness;
    // A family's floor takes its cover's place from level -1 down, along contours its fields break.
    float floorShare = 0.0;
    if (f.floor.x >= 0.0) {
        float contour = .36 * (broad - .5) + .2 * (fine - .5);
        floorShare = smoothstep(.3, .72, contour - px.world.z / px.levelMetres) * smoothstep(.55, .85, up);
        if (floorShare > 0.0) cover = mix(cover, 1.0, .5 * floorShare * smoothstep(.82, .97, up));
    }
    // Level bedrock of the shared cliff shows the rock tops' map: its joints stand upright.
    if (px.role < .5 && abs(layers.w - groundFlatRock.x) < .5) {
        layers.w = groundFlatRock.y;
        tiles.w = groundFlatRock.z;
    }
    vec4 roles = vec4(cover, clamp(mantle, 0.0, 1.0 - cover), deposit, 0.0);
    roles.xy *= 1.0 - deposit;
    roles.w = max(0.0, 1.0 - roles.x - roles.y - roles.z);
    float bedRock = rock * (1.0 - smoothstep(.2, .8, up)) * smoothstep(.05, 2.2, px.aboveFoot);
    float bankBlend = 0.0;
    if (px.riverBank && f.edges.w > .5) {
        // An ordinary river's bank: native cover feathers into soil, then gravel, before the water.
        float bankRock = bedRock > 0.0 ? roles.w : 0.0;
        roles = mix(roles, vec4(1.0 - bankRock, 0.0, 0.0, bankRock), smoothstep(0.0, .03, sediment));
        bankBlend = clamp((sediment - .03) / .97, 0.0, 1.0);
        if (bankBlend > 0.0) {
            layers.y = groundRiverBank.x; tiles.y = groundRiverBank.y;
            layers.z = groundRiverBank.z; tiles.z = groundRiverBank.w;
            roles = mix(roles, vec4(0.0, 1.0 - bedRock, 0.0, bedRock), clamp(bankBlend / f.edges.z, 0.0, 1.0));
            roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), clamp((bankBlend - .9) / .1, 0.0, 1.0));
        }
    } else {
        roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), sediment);
    }
    // The two strongest roles; a dusty level top keeps its cover as the second.
    float first = max(max(roles.x, roles.y), max(roles.z, roles.w));
    vec4 isFirst = step(vec4(first), roles);
    isFirst *= 1.0 - step(.5, vec4(0.0, isFirst.x, isFirst.x + isFirst.y, isFirst.x + isFirst.y + isFirst.z));
    float dust = f.deposit.z * smoothstep(.88, .98, up) * (1.0 - sediment);
    if (bankBlend > 0.0) dust *= 1.0 - clamp(bankBlend / f.edges.z, 0.0, 1.0);
    vec4 rest = roles * (1.0 - isFirst);
    if (dust > 0.0) rest.x += 2.0 * (1.0 - isFirst.x);
    float pick = max(max(rest.x, rest.y), max(rest.z, rest.w));
    vec4 isSecond = step(vec4(pick), rest) * (1.0 - isFirst);
    isSecond *= 1.0 - step(.5, vec4(0.0, isSecond.x, isSecond.x + isSecond.y, isSecond.x + isSecond.y + isSecond.z));
    float second = dot(isSecond, roles);
    // The floor stands in for the cover role where it lies whole; inside its fringe the pair is ground and floor.
    bool floorPair = floorShare > 0.0 && floorShare < 1.0 && isFirst.x > .5;
    if (floorShare >= 1.0) { layers.x = f.floor.x; tiles.x = f.floor.y; }
    float layerA = dot(isFirst, layers), tileA = dot(isFirst, tiles);
    vec4 a;
    vec3 na;
    float cavityA, spreadA;
    groundRead(px, layerA, tileA, a, na, cavityA, spreadA);
    vec4 b = a;
    vec3 nb = na;
    float cavityB = cavityA, spreadB = spreadA, share = 0.0;
    float roughA = dot(isFirst, roughness), roughB = roughA;
    float wa = first + a.a * .38 * 4.0 * first * (1.0 - first);
    float blendWidth = .13 + min(.2, px.pixelMetres * .16);
    if (floorPair) {
        groundRead(px, f.floor.x, f.floor.y, b, nb, cavityB, spreadB);
        float spreading = .25 * 4.0 * floorShare * (1.0 - floorShare);
        float ga = 1.0 - floorShare + a.a * spreading, gb = floorShare + b.a * spreading;
        float width = 1.6 * blendWidth;
        float ka = max(ga - max(ga, gb) + width, 0.0), kb = max(gb - max(ga, gb) + width, 0.0);
        share = kb / max(ka + kb, .00001);
    } else if ((second > .02 || dust > 0.0 || bankBlend > 0.0)
          && (bankBlend > 0.0 || dust > 0.0 || second + .38 * 4.0 * second * (1.0 - second) > wa - blendWidth - 1e-4)) {
        // The second role shows where its weight at full relief comes within the blend's width of the first's.
        groundRead(px, dot(isSecond, layers), dot(isSecond, tiles), b, nb, cavityB, spreadB);
        roughB = dot(isSecond, roughness);
        float wb = second + b.a * .38 * 4.0 * second * (1.0 - second);
        float ka = max(wa - max(wa, wb) + blendWidth, 0.0), kb = max(wb - max(wa, wb) + blendWidth, 0.0);
        share = kb / max(ka + kb, .00001);
        if (bankBlend > 0.0) share = mix(share, second / max(first + second, .00001), smoothstep(0.0, .03, bankBlend));
        if (dust > 0.0) share = isFirst.x > .5 ? share * (1.0 - dust) : share + dust * (1.0 - share);
    }
    vec4 roleShare = isFirst * (1.0 - share) + (floorPair ? isFirst : isSecond) * share;
    GroundSurface result;
    result.albedo = vec3(0.0);
    if (!px.covered) {
        vec3 colour = mix(a.rgb, b.rgb, share);
        if (px.role > 0.0) {
            // Strata: harder beds weather paler and slightly cooler than the softer beds between them.
            float rockShare = (roleShare.w + roleShare.y * f.mantle.w) * px.role;
            colour *= mix(vec3(1.0), mix(vec3(.93, .92, .91), vec3(1.04, 1.04, 1.05), px.hardness), rockShare);
        }
        if (f.shore.w > 0.0) {
            // A rim's abraded rock pales at its crest and keeps its warmer face below.
            float abrasion = (1.0 - smoothstep(.15, 2.2, px.belowRim)) * smoothstep(.45, .95, up);
            colour *= mix(vec3(1.0), authoredPigmentGain(mix(f.abrasionLow.rgb, f.abrasionHigh.rgb, abrasion)),
                  roleShare.w * f.shore.w);
        } else if (px.role > 0.0 && f.shore.z > 0.0) {
            // A wall weathers by its height: its rim bleached, streaks down from it, grime toward its foot.
            float faceHeight = max(px.aboveFoot + px.belowRim, 1.0);
            float low = (1.0 - smoothstep(0.0, .45, max(px.aboveFoot, 0.0) / faceHeight)) * (.6 + .4 * variation);
            float bleach = (1.0 - smoothstep(.3, 3.0 + 3.0 * broad, px.belowRim)) * (1.0 - .5 * up);
            vec2 stretch = vec2(1.0 / 40.0, 1.0 / 400.0);
            vec2 along = px.side > .5 ? px.x : px.y;
            float streak = GROUND_NOISE(along * stretch + .41).r;
            float streaks = smoothstep(.5, .8, streak) * (1.0 - smoothstep(0.0, .6 * faceHeight + 4.0, max(px.belowRim, 0.0)))
                  * (1.0 - smoothstep(.3, .7, up));
            vec3 weathering = mix(vec3(1.0), vec3(1.10, 1.10, 1.09), bleach) * mix(vec3(1.0), vec3(.84, .84, .86), streaks)
                  * mix(vec3(1.0), vec3(.84, .85, .88), low);
            colour *= mix(vec3(1.0), authoredPigmentGain(weathering), roleShare.w * f.shore.z * px.role);
        }
        if (roleShare.x > 0.0) colour = mix(colour, groundTone(f, colour, px.world, fields, rim, foot), roleShare.x);
        colour *= mix(vec3(1.0), f.rootMat.rgb, sod * (1.0 - roleShare.x));
        result.albedo = colour;
    }
    vec3 normalA = na * (1.0 - spreadA), normalB = nb * (1.0 - spreadB);
    result.normal = normalize(mix(normalA, normalB, share));
    result.cavity = mix(cavityA, cavityB, share);
    result.relief = px.normalMaps ? mix(length(normalA), length(normalB), share) : 1.0;
    result.height = mix(a.a, b.a, share);
    result.roughness = mix(roughA, roughB, share);
    result.roles = roleShare;
    return result;
}
#endif
