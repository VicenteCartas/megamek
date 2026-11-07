// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Natural surfaces: tops, walls, banks and bedrock (cliffMaterial); volcanic programs keep naturalMaterialFor.
// Inserted after the lighting helpers.

// Medium quality skips arithmetic that comes out exactly zero there; High runs the block every time.
#ifdef mediumQualityFlag
#define IF_MEDIUM(condition) if (condition)
#else
#define IF_MEDIUM(condition)
#endif

uniform vec4 u_bankMaterials; // existing temperate-bank and dirt-debris: colour layer / repeat in metres
uniform vec3 u_flatRock; // the shared cliff's colour layer, then the rock tops' colour layer and repeat in metres
// A full biome deposit replaces substrate colour, finish and normal direction. Its height still contributes
// weathering and shadow lift, and its filtered normal length still contributes distant lighting.
bool terrainCovered = false;
bool riverBank = false; // set by the water-owned ground, before evaluating its material roles
float sunkenCover = 0.0; // 1 while the legacy volcanic ordinary evaluator shades a family's floor
float shallowSediment = -1.0; // Halfway depth-zero arid shore profile; negative on deeper water and dry land.

struct TerrainMaterial {
    vec3 color; // linear reflectance; color maps are decoded at upload, before filtering
    vec3 normal;
    float cavity;
    float height;
    Finish finish;
    float volcanic;
    float relief; // how well its normals under the pixel agree, their mean's length: 1 for one (sunOnRelief)
};

// Whether the evaluator's family is this one. A blend picks its family per pixel, but a family program's palette is
// constant (GpuFamilyPrograms), so a family outside it folds away with its paths.
#ifdef COVER_FAMILIES
#define isFamily(id, f) (any(lessThan(abs(COVER_FAMILIES - (f)), vec4(.5))) && abs((id) - (f)) < .5)
#else
#define isFamily(id, f) (abs((id) - (f)) < .5)
#endif

float materialBlendWidth() { return .13 + min(.2, pixelMetres * .16); }

#ifdef sublevelFloorFlag
// Each family's floor from level -1 down (GpuTerrain.SUBLEVEL_FLOORS): its colour layer and metres per repeat; a
// negative layer where the family keeps its ground.
uniform vec2 u_floorMaterials[FLOOR_FAMILIES];

// How far the family's floor replaces its ground cover at a point (world in metres): none above level -.3, all from
// about -.72 down, along a contour that 6 m and 2 m patches break by up to .28 of a level (level 0 never, level -1
// always); level ground and gentle lower slopes only, so banks and walls keep their own materials. The rain noise is 64
// texels of white noise: a field of features F metres wide reads it at world / (64 F).
float floorShare(vec3 world, vec3 face, float familyId, out vec2 floorMaterial) {
    int family = int(familyId + .5);
    floorMaterial = family >= 0 && family < FLOOR_FAMILIES ? u_floorMaterials[family] : vec2(-1.0);
    if (floorMaterial.x < 0.0) return 0.0;
    float contour = .36 * (textureLod(u_surfaceNoise, rainNoiseAt(world.xy / 384.0 + .63), 0.0).b - .5)
          + .2 * (textureLod(u_surfaceNoise, rainNoiseAt(world.xy / 128.0 + .29), 0.0).g - .5);
    return smoothstep(.3, .72, contour - world.z * u_metre / u_levelHeight) * smoothstep(.55, .85, face.z);
}

// Ground and floor meet by height, as materialWeights blends roles, but feathered wider: a fringe, not a stipple.
float floorRoleWeight(float ground, float floorHeight, float share) {
    float spread = .25 * 4.0 * share * (1.0 - share);
    float wa = 1.0 - share + ground * spread, wb = share + floorHeight * spread;
    float width = 1.6 * materialBlendWidth();
    float ka = max(wa - max(wa, wb) + width, 0.0), kb = max(wb - max(wa, wb) + width, 0.0);
    return kb / max(ka + kb, .00001);
}

TerrainMaterial floorBlend(TerrainMaterial ground, TerrainMaterial sunken, float share) {
    float t = floorRoleWeight(ground.height, sunken.height, share);
    return TerrainMaterial(mix(ground.color, sunken.color, t), normalize(mix(ground.normal, sunken.normal, t)),
          mix(ground.cavity, sunken.cavity, t), mix(ground.height, sunken.height, t),
          mixFinish(ground.finish, sunken.finish, t), mix(ground.volcanic, sunken.volcanic, t),
          mix(ground.relief, sunken.relief, t));
}
#endif

// Natural wear follows the existing relief, modulated by continuous world fields instead of repeated stamps.
// Channels are recesses, low grains, chipped edges and high grains. Loose/organic covers favour the height
// channels; stone favours cavities and normal-map edges.
vec4 terrainWear(vec3 fields, float height, float cavity, vec3 face, vec3 normal) {
    float exposure = smoothstep(.30, .67, fields.x * .65 + fields.z * .35);
    float abrasion = smoothstep(.32, .73, fields.y * .7 + fields.z * .3);
    float low = (1.0 - smoothstep(.24, .53, height)) * .75;
    float recess = max(low, clamp((1.0 - cavity) * 2.2, 0.0, 1.0));
    float high = smoothstep(.40, .65, height);
    float edge = clamp((1.0 - dot(face, normal)) * 5.0, 0.0, 1.0) * high;
    return vec4(recess * exposure, low * exposure, edge * abrasion, high * abrasion);
}

// Authored pigment-control deltas, calibrated once at the final albedo multiplication. Roughness keeps
// these original control strengths; changing pigment contrast must not change the physical surface response.
// Keep the softened desert baseline: the previous .30 darkening was too strong. These are visual material
// estimates, not simulated erosion or a second terrain layer. No new maps, geometry or sampling are needed.
vec3 materialWeathering(float familyId, vec4 wear, vec3 fields, float cavity) {
    vec3 recess, crest;
    vec2 grain;
    if (abs(familyId) < .5) { // Grass: soil in worn turf, straw-coloured tips.
        recess = vec3(.12, .24, .18); crest = vec3(.24, .11, .025); grain = vec2(.25, .70);
    } else if (abs(familyId - 1.0) < .5) { // Dirt: compacted pockets and dry, crumbling grains.
        recess = vec3(.21, .24, .27); crest = vec3(.18, .15, .09); grain = vec2(.15, .35);
    } else if (abs(familyId - 2.0) < .5) {
        return vec3(0.0); // SAND is a tile cover, not a theme. Only exposed substrate weathers.
    } else if (abs(familyId - 3.0) < .5) { // Rock: fissures and pale mineral edges.
        recess = vec3(.27, .28, .28); crest = vec3(.22, .21, .18); grain = vec2(0.0, .04);
    } else if (abs(familyId - 4.0) < .5) {
        // Ground concrete collects a light, uneven film of grime, strongest in pores. Its height is a float finish,
        // not rocky relief: brightening its crests made false raised outlines across the pavement.
        float grime = smoothstep(.32, .70, fields.x * .5 + fields.y * .2 + fields.z * .3);
        float pores = smoothstep(.005, .08, 1.0 - cavity);
        return -vec3(.15, .135, .115) * grime * (.4 + .6 * pores);
    } else if (abs(familyId - 5.0) < .5) { // Snow: irregular grey-blue refrozen crust, without drawn wave motifs.
        recess = vec3(.16, .12, .075); crest = vec3(.025, .03, .035); grain = vec2(.30, .65);
    } else if (abs(familyId - LUNAR_FAMILY) < .5) { // Lunar: neutral dust pockets and pitted mineral rims.
        recess = vec3(.15); crest = vec3(.10); grain = vec2(.15, .20);
    } else if (abs(familyId - FUNGUS_FAMILY) < .5) { // Fungus: mottled ageing tissue and pale raised skin.
        recess = vec3(.15, .24, .17); crest = vec3(.18, .13, .17); grain = vec2(.15, .65);
    } else if (abs(familyId - DESERT_FAMILY) < .5) { // Desert: soft sandstone varnish and abraded plates.
        recess = vec3(.20); crest = vec3(.085, .08, .07); grain = vec2(0.0);
    } else if (abs(familyId - MARS_FAMILY) < .5) { // Mars: rusty dust hollows and lighter mineral ridges.
        recess = vec3(.14, .17, .18); crest = vec3(.095, .075, .06); grain = vec2(.25, .35);
    } else if (abs(familyId - VOLCANO_FAMILY) < .5) { // Cold basalt: ash in pores, cooler exposed edges.
        recess = vec3(.17, .17, .16); crest = vec3(.11, .115, .12); grain = vec2(0.0, .05);
    } else if (abs(familyId - TROPICAL_FAMILY) < .5) { // Tropical: organic hollows and restrained dry litter.
        recess = vec3(.12, .23, .18); crest = vec3(.22, .12, .035); grain = vec2(.30, .65);
    } else {
        return vec3(0.0); // Active magma and cooling crust have their own surface treatment.
    }
    return crest * mix(wear.z, wear.w, grain.y) - recess * mix(wear.x, wear.y, grain.x);
}

// Competing covers retain their texture rather than becoming a muddy colour crossfade. All channels use the same
// weights; filtering broadens the contact only when its detail becomes subpixel.
vec4 materialWeights(vec4 coverage, vec4 height) {
    vec4 present = step(vec4(.0001), coverage);
    vec4 score = coverage + height * .38 * (4.0 * coverage * (1.0 - coverage)) - (1.0 - present) * 2.0;
    float highest = max(max(score.x, score.y), max(score.z, score.w));
    float width = materialBlendWidth();
    vec4 weights = max(score - highest + width, vec4(0.0)) * present;
    return weights / max(dot(weights, vec4(1.0)), .00001);
}

// Calculate derivatives before branching on material weights. Implicit mip derivatives inside those branches
// are undefined when neighbouring fragments choose different layers, producing speckles at their boundaries.
struct MaterialProjection {
    vec2 top, x, y;
    mat2 topGradient, xGradient, yGradient;
    float side, lying;
    vec2 facing; // the side projections' signs along X and Y
};

// Cut walls keep one texture frame across their facets; zero leaves natural landforms unchanged.
vec3 terrainWallProjection = vec3(0.0);

MaterialProjection materialProjection(vec3 world, vec3 face) {
    vec3 basis = dot(terrainWallProjection, terrainWallProjection) > .5 ? terrainWallProjection : face;
    MaterialProjection p;
    p.top = vec2(world.x, -world.y);
    p.x = vec2(world.y * sign(basis.x), -world.z);
    p.y = vec2(-world.x * sign(basis.y), -world.z);
    p.topGradient = mat2(dFdx(p.top), dFdy(p.top));
    p.xGradient = mat2(dFdx(p.x), dFdy(p.x));
    p.yGradient = mat2(dFdx(p.y), dFdy(p.y));
    vec2 axes = pow(abs(basis.xy), vec2(4.0));
    p.side = axes.x / max(axes.x + axes.y, .00001);
    p.lying = smoothstep(.25, .87, basis.z);
    p.facing = sign(basis.xy);
    return p;
}

// Godot's heightmap parallax as StandardMaterial3D draws it without deep parallax: every read of a role moves against
// the view, in its projection's frame, by the role's depth below the heightmap's white at its unmoved reads. Each
// projection is the plane it is read in (a top the ground's, a side the wall's); the depth is the heightmap's
// (u_finishRelief, in repeats) in metres. A relief that would move less than half a pixel moves nothing visible and
// costs no read. The Terrain Parallax switch turns it off, apart from the normal maps.
vec3 terrainView = vec3(0.0); // from the surface to the eye, which lit programs' main sets; zero moves nothing

// A projection's move at the full depth, in its coordinates: the view along its U and V axes times the depth. Zero
// where that is under half a pixel.
vec2 projectedReach(vec2 view, vec2 depth, mat2 gradient) {
    vec2 reach = view * depth;
    return dot(reach, reach) < .25 * max(dot(gradient[0], gradient[0]), dot(gradient[1], gradient[1])) ? vec2(0.0) : reach;
}

// A role's move at the full depth in one projection, at a repeat of tile metres, given the view along its U and V axes
// and the projection's gradient in metres.
vec2 planeReach(float layer, float tile, vec2 view, mat2 gradient) {
    if (u_terrainParallax <= .5) return vec2(0.0);
    return projectedReach(view, u_finishRelief[int(layer + .5)] * tile, gradient);
}

// A role's moves at the full depth in the projections it reads (tops, sides) at a repeat of tile metres; false where
// none moves visibly. A top's U runs along X and its V down Y; a side's U across the wall and its V down it.
bool reliefReach(float layer, float tile, MaterialProjection p, bool tops, bool sides, out vec2 top, out vec2 x,
      out vec2 y) {
    top = vec2(0.0);
    x = vec2(0.0);
    y = vec2(0.0);
    if (tops) top = planeReach(layer, tile, vec2(terrainView.x, -terrainView.y), p.topGradient);
    if (sides) {
        x = planeReach(layer, tile, vec2(terrainView.y * p.facing.x, -terrainView.z), p.xGradient);
        y = planeReach(layer, tile, vec2(-terrainView.x * p.facing.y, -terrainView.z), p.yGradient);
    }
    return dot(top, top) + dot(x, x) + dot(y, y) > 0.0;
}

// The projection moved by a role's moves, sunk by its depth below white.
MaterialProjection relieved(MaterialProjection p, vec2 top, vec2 x, vec2 y, float height) {
    float sink = 1.0 - height;
    p.top -= top * sink;
    p.x -= x * sink;
    p.y -= y * sink;
    return p;
}

// Projection is shared; callers select colour or numerical storage explicitly.
vec4 materialTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
    return textureGrad(maps, vec3(uv, layer), gradient[0], gradient[1]);
}

// How far the close view's normals under a filtered normal/AO read disagree: about half their slopes' variance, from
// 1 less the length of their mean, which the mips keep (GpuAssets.mipmaps). Zero within a texel. The factor .5 is an
// empirical calibration, fitted on NVIDIA to GpuMaterialLodSmokeTest's seven materials (far ground at 0.8 m a pixel
// against the 0.1 m close view averaged): with the read's full shortening rock and dirt came out 9.5% and 6.0% darker,
// with half of it 3.3% and 3.8%; HEAD's fixed distant relief was 5.5% and 3.4% brighter. Mixing independent reads
// with weights w before normalising mixes their slopes, so the result's spread is the sum of w^2 times theirs: the
// close view's normals agree better than any one map's (relief, sunOnRelief). The reads that take a spread give it
// out.
float normalSpread(vec4 texel) { return .5 * (1.0 - min(length(texel.rgb * 2.0 - 1.0), 1.0)); }

// Two reads mixed by t, without the read whose weight is exactly zero: it would change nothing. Gradients are explicit,
// so the reads may sit in varying branches.
vec4 mixedTexel(sampler2DArray maps, float layer, vec2 a, mat2 gradientA, vec2 b, mat2 gradientB, float t,
      out float spread) {
    if (t <= 0.0 || t >= 1.0) {
        vec4 only = t <= 0.0 ? materialTexel(maps, layer, a, gradientA) : materialTexel(maps, layer, b, gradientB);
        spread = normalSpread(only);
        return only;
    }
    vec4 first = materialTexel(maps, layer, a, gradientA), second = materialTexel(maps, layer, b, gradientB);
    spread = (1.0 - t) * (1.0 - t) * normalSpread(first) + t * t * normalSpread(second);
    return mix(first, second, t);
}

vec4 mixedTexel(sampler2DArray maps, float layer, vec2 a, mat2 gradientA, vec2 b, mat2 gradientB, float t) {
    float spread;
    return mixedTexel(maps, layer, a, gradientA, b, gradientB, t, spread);
}

// Overlapping translated samples break the fungal source's recognisable repeats. Use identical offsets for
// colour/height and normal/AO; translations keep tangent normals aligned and leave vertical cliff grain upright.
vec2 materialOffset(vec2 cell) {
    return fract(sin(vec2(dot(cell, vec2(127.1, 311.7)), dot(cell, vec2(269.5, 183.3)))) * 43758.5453) * 8.0;
}

vec4 fungusTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, out float spread) {
    vec2 cell = floor(uv), blend = smoothstep(.15, .85, fract(uv));
    vec4 a = materialTexel(maps, layer, uv + materialOffset(cell), gradient);
    vec4 b = materialTexel(maps, layer, uv + materialOffset(cell + vec2(1.0, 0.0)), gradient);
    vec4 c = materialTexel(maps, layer, uv + materialOffset(cell + vec2(0.0, 1.0)), gradient);
    vec4 d = materialTexel(maps, layer, uv + materialOffset(cell + vec2(1.0, 1.0)), gradient);
    vec2 keep = 1.0 - blend;
    vec4 weights = vec4(keep.x * keep.y, blend.x * keep.y, keep.x * blend.y, blend.x * blend.y);
    spread = dot(weights * weights, vec4(normalSpread(a), normalSpread(b), normalSpread(c), normalSpread(d)));
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

vec4 fungusTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
    float spread;
    return fungusTexel(maps, layer, uv, gradient, spread);
}

// fungusTexel's height, for the parallax.
float fungusHeight(float layer, vec2 uv, mat2 gradient) {
    vec2 cell = floor(uv), blend = smoothstep(.15, .85, fract(uv));
    float a = heightTexel(layer, uv + materialOffset(cell), gradient);
    float b = heightTexel(layer, uv + materialOffset(cell + vec2(1.0, 0.0)), gradient);
    float c = heightTexel(layer, uv + materialOffset(cell + vec2(0.0, 1.0)), gradient);
    float d = heightTexel(layer, uv + materialOffset(cell + vec2(1.0, 1.0)), gradient);
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

// Select neighbouring source offsets from a continuous world field, not the UV tile or hex ID. Two reads keep the
// original feature size and wind/bedding direction without the recognisable motif repeating on a square grid.
// Color/height and normal/AO must use the same selector; a translation does not rotate or resize their normals.
vec4 translatedTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation, out float spread) {
    float choice = variation * 8.0, index = floor(choice);
    return mixedTexel(maps, layer, uv + materialOffset(vec2(index, 0.0)), gradient,
          uv + materialOffset(vec2(index + 1.0, 0.0)), gradient, smoothstep(.2, .8, fract(choice)), spread);
}

vec4 translatedTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation) {
    float spread;
    return translatedTexel(maps, layer, uv, gradient, variation, spread);
}

// translatedTexel's height, for the parallax.
float translatedHeight(float layer, vec2 uv, mat2 gradient, float variation) {
    float choice = variation * 8.0, index = floor(choice);
    return mixedHeight(layer, uv + materialOffset(vec2(index, 0.0)), gradient,
          uv + materialOffset(vec2(index + 1.0, 0.0)), gradient, smoothstep(.2, .8, fract(choice)));
}

// The ground's variation field is constant down a vertical face. Advance the source selection with projected
// height as well, otherwise a tall cliff repeats the same two patches at every map period. Blends use a different
// interval from the source repeat; both maps keep their scale and upright grain, with the same two texture reads.
vec4 cliffTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation, out float spread) {
    return translatedTexel(maps, layer, uv, gradient, variation + uv.y * .173, spread);
}

vec4 cliffTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation) {
    float spread;
    return cliffTexel(maps, layer, uv, gradient, variation, spread);
}

vec4 materialTexel(float layer, vec2 uv, mat2 gradient) {
    return materialTexel(u_terrainLayers, layer * .5, uv, gradient);
}
vec4 fungusTexel(float layer, vec2 uv, mat2 gradient) {
    return fungusTexel(u_terrainLayers, layer * .5, uv, gradient);
}
vec4 translatedTexel(float layer, vec2 uv, mat2 gradient, float variation) {
    return translatedTexel(u_terrainLayers, layer * .5, uv, gradient, variation);
}
vec4 cliffTexel(float layer, vec2 uv, mat2 gradient, float variation) {
    return cliffTexel(u_terrainLayers, layer * .5, uv, gradient, variation);
}

#ifdef volcanicFlag
// Volcanic programs' natural material (naturalMaterialFor), on every face; elsewhere cliffMaterial draws them all.
// Every role shares projection and metre scale through a bend. No UV origin at an individual hex's rim or foot.
// One projection implementation for color/height and authored scalar maps. No pigment transforms here.
vec4 sampleMaterialMap(sampler2DArray maps, float layer, float tile, MaterialProjection p,
      float variation, bool fungus, bool singleScale) {
    vec4 pigment = vec4(0.0);
    if (p.lying > 0.0) {
        pigment = fungus ? fungusTexel(maps, layer, p.top / tile, p.topGradient / tile)
              : singleScale
              ? translatedTexel(maps, layer, p.top / tile, p.topGradient / tile, variation)
              : mixedTexel(maps, layer, p.top / tile, p.topGradient / tile,
                    TURN * p.top / (tile * 2.37) + .31, TURN * p.topGradient / (tile * 2.37), variation);
    }
    if (p.lying < 1.0) {
        vec4 steep = fungus ? mix(fungusTexel(maps, layer, p.y / tile + .37, p.yGradient / tile),
              fungusTexel(maps, layer, p.x / tile, p.xGradient / tile), p.side)
              : mix(cliffTexel(maps, layer, p.y / tile + .37, p.yGradient / tile, variation),
                    cliffTexel(maps, layer, p.x / tile, p.xGradient / tile, variation), p.side);
        pigment = mix(steep, pigment, p.lying);
    }
    return pigment;
}

// sampleMaterialMap's height at its near reads, for the parallax.
float sampledHeight(float layer, float tile, MaterialProjection p, float variation, bool fungus, bool singleScale) {
    float height = 0.0;
    if (p.lying > 0.0) {
        vec2 uv = p.top / tile;
        mat2 gradient = p.topGradient / tile;
        height = fungus ? fungusHeight(layer, uv, gradient)
              : singleScale ? translatedHeight(layer, uv, gradient, variation)
              : mixedHeight(layer, uv, gradient, TURN * p.top / (tile * 2.37) + .31,
                    TURN * p.topGradient / (tile * 2.37), variation);
    }
    if (p.lying < 1.0) {
        vec2 y = p.y / tile + .37, x = p.x / tile;
        float steep = fungus
              ? mix(fungusHeight(layer, y, p.yGradient / tile), fungusHeight(layer, x, p.xGradient / tile), p.side)
              : mix(translatedHeight(layer, y, p.yGradient / tile, variation + y.y * .173),
                    translatedHeight(layer, x, p.xGradient / tile, variation + x.y * .173), p.side);
        height = mix(steep, height, p.lying);
    }
    return height;
}

// A volcanic role's projection, moved by its parallax (relievedProjection).
MaterialProjection relievedMaterial(float colorMap, float tile, float amount, MaterialProjection p, float variation,
      bool fungus, bool singleScale) {
    vec2 top, x, y;
    if (amount < .0001 || !reliefReach(colorMap * .5, tile, p, p.lying > 0.0, p.lying < 1.0, top, x, y)) return p;
    return relieved(p, top, x, y, sampledHeight(colorMap * .5, tile, p, variation, fungus, singleScale));
}

vec4 sampleMaterial(float colorMap, float tile, float amount, MaterialProjection p,
      vec3 world, float variation, float fine, float region, bool groundMap, bool singleScale,
      float familyId, vec2 contact) {
    if (amount < .0001) return vec4(0.0);
    bool fungus = abs(familyId - FUNGUS_FAMILY) < .5;
    vec4 pigment = sampleMaterialMap(u_terrainLayers, colorMap * .5, tile, p, variation, fungus, singleScale);
    vec3 color = pigment.rgb;
    if (groundMap) color = groundToneFor(familyId, color, world, variation, fine, region, contact.x, contact.y);
    if (abs(familyId - 2.0) < .5) {
        float mineral = dot(color, vec3(.2126, .7152, .0722));
        color = mix(color, mineral * toLinear(vec3(.86, .92, .98)), greySand());
    }
    return vec4(color, pigment.a);
}

// A role's finish, as rockTexel's: roughness, metallic and scattering from its map, emission from its map. A role
// without weight contributes nothing.
Finish materialFinish(float colorMap, float tile, float weight, MaterialProjection p,
      float variation, bool fungus, bool singleScale) {
    if (weight <= 0.0) return Finish(0.0, 0.0, 0.0, vec3(0.0));
    int material = int(colorMap * .5);
    vec4 surface = u_finishSurface[material], emission = u_finishEmission[material];
    Finish texel = meanFinish(material);
    if (surface.a >= 0.0) {
        texel = finishOf(sampleMaterialMap(FINISH_LAYERS, surface.a, tile, p, variation, fungus, singleScale).rgb,
              texel.emission);
    }
    if (emission.a >= 0.0) {
        texel.emission += emission.rgb
              * toLinear(sampleMaterialMap(FINISH_LAYERS, emission.a, tile, p, variation, fungus, singleScale).rgb);
    }
    return texel;
}
#endif

// Direct sunlight on flattened relief whose normals average `relief`, relative to its flat face lit at
// s = cos(incidence): facets tilted acos(relief) every way round, those turned from the sun taking none. A high sun
// loses the tilted share; a low one, caught by the facets facing it, loses less, and never gains (nor does the close
// view).
float sunOnRelief(float relief, float s) {
    if (relief > .999 || s <= 0.0) return 1.0;
    float a = sqrt((1.0 - relief * relief) * (1.0 - s * s)), b = relief * s;
    if (b >= a) return relief;
    float turn = acos(-b / a);
    return min(1.0, (a * sin(turn) + b * turn) / (PI * s));
}

// Legacy planar/slab/rock paths retain their own colour-map projection.
Finish planarFinish(float layer, vec2 p, mat2 gradient, float tile, float mixer) {
    return mixFinish(finishTexel(layer, p / tile, gradient / tile),
          finishTexel(layer, TURN * p / (tile * 2.37) + .31, TURN * gradient / (tile * 2.37)), mixer);
}

Finish translatedFinish(float layer, vec2 uv, mat2 gradient, float variation) {
    int material = int(layer * .5);
    vec4 surface = u_finishSurface[material], emission = u_finishEmission[material];
    Finish texel = meanFinish(material);
    if (surface.a >= 0.0) {
        texel = finishOf(translatedTexel(FINISH_LAYERS, surface.a, uv, gradient, variation).rgb, texel.emission);
    }
    if (emission.a >= 0.0) {
        texel.emission += emission.rgb * toLinear(translatedTexel(FINISH_LAYERS, emission.a, uv, gradient, variation).rgb);
    }
    return texel;
}

Finish wallFinish(float layer, vec2 x, vec2 y, mat2 dx, mat2 dy, float side) {
    return mixFinish(finishTexel(layer, y, dy), finishTexel(layer, x, dx), side);
}

// The root mat a meadow's turf lip leaves on its two-level bank, lipDepth metres deep: a band of dark organic topsoil,
// half a metre to a metre, where the turf ends and before the paler subsoil of the face. Its share at belowRim metres.
const vec3 ROOT_MAT = vec3(.45, .41, .37);
float rootMat(float belowRim, float lipDepth, float fray) {
    float edge = .52 * lipDepth, depth = u_levelHeight / u_metre * mix(.08, .16, fray);
    return 1.0 - smoothstep(edge + .4 * depth, edge + depth, belowRim);
}

#ifdef volcanicFlag
// Height blending often discards a present layer entirely. Its normal/cavity cannot affect the result then.
// Defer those texture reads until the colour/height samples have determined the final weights. The normal's length is
// its relief: the agreement of the normals under the pixel (normalSpread).
vec4 materialNormal(float normalMap, float tile, float weight, MaterialProjection p, vec3 face, float variation,
      bool fungus, bool singleScale) {
    if (weight <= 0.0 || u_normalMaps <= .5) return vec4(face, 1.0);
    float layer = (normalMap - 1.0) * .5;
    vec3 normal = face;
    float cavity = 1.0, spread = 0.0;
    if (p.lying > 0.0) {
        vec4 near;
        vec3 detail;
        if (fungus) {
            near = fungusTexel(u_terrainData, layer, p.top / tile, p.topGradient / tile, spread);
        } else if (singleScale) {
            near = translatedTexel(u_terrainData, layer, p.top / tile, p.topGradient / tile, variation, spread);
        } else {
            near = materialTexel(u_terrainData, layer, p.top / tile, p.topGradient / tile);
        }
        if (fungus || singleScale) {
            detail = near.rgb * 2.0 - 1.0;
            cavity = near.a;
        } else {
            vec4 far = variation <= 0.0 ? near : materialTexel(u_terrainData, layer, TURN * p.top / (tile * 2.37) + .31,
                  TURN * p.topGradient / (tile * 2.37));
            detail = planarNormal(near, far, variation).rgb;
            cavity = mix(near.a, far.a, variation);
            // The enlarged repeat's slopes are 2.37 times shallower (planarNormal): its spread 2.37^2 times smaller.
            spread = (1.0 - variation) * (1.0 - variation) * normalSpread(near)
                  + variation * variation * .178 * normalSpread(far);
        }
        normal = upNormal(detail, face);
    }
    if (p.lying < 1.0) {
        vec4 nx, ny;
        float spreadX, spreadY;
        if (fungus) {
            nx = fungusTexel(u_terrainData, layer, p.x / tile, p.xGradient / tile, spreadX);
            ny = fungusTexel(u_terrainData, layer, p.y / tile + .37, p.yGradient / tile, spreadY);
        } else {
            nx = cliffTexel(u_terrainData, layer, p.x / tile, p.xGradient / tile, variation, spreadX);
            ny = cliffTexel(u_terrainData, layer, p.y / tile + .37, p.yGradient / tile, variation, spreadY);
        }
        vec3 x = nx.rgb * 2.0 - 1.0, y = ny.rgb * 2.0 - 1.0;
        vec3 wall = normalize(mix(vec3(-y.x * sign(face.y) + face.x, y.z * face.y, face.z - y.y),
              vec3(x.z * face.x, x.x * sign(face.x) + face.y, face.z - x.y), p.side));
        normal = normalize(mix(wall, normal, p.lying));
        cavity = mix(mix(ny.a, nx.a, p.side), cavity, p.lying);
        float wallSpread = p.side * p.side * spreadX + (1.0 - p.side) * (1.0 - p.side) * spreadY;
        spread = (1.0 - p.lying) * (1.0 - p.lying) * wallSpread + p.lying * p.lying * spread;
    }
    return vec4(normalize(normal) * (1.0 - spread), mix(.55, 1.0, cavity));
}

TerrainMaterial volcanicMaterial(Volcanic material, float strength) {
    return TerrainMaterial(material.albedo, material.normal, material.surface.b, material.surface.r,
          Finish(material.surface.g, 0.0, 0.0, magmaEmission(material.heat, strength)), 1.0, 1.0);
}

TerrainMaterial volcanicMaterial(Volcanic material, float familyId, float bankHeat) {
    return volcanicMaterial(material, familyId > VOLCANIC_BANK_FAMILY - .5 ? bankHeat : 1.0);
}

// Shared world fields are sampled before varying palette coverage branches. The family test is draw-uniform.
vec2 materialNoise(vec3 world, vec4 families) {
    float pockets = texture(u_surfaceNoise, rainNoiseAt((world.xy + world.z * vec2(.43, .27)) / 92.0 + .57)).g;
    float fray = 0.0;
    if (any(lessThan(abs(families), vec4(.5)))
          || any(lessThan(abs(families - TROPICAL_FAMILY), vec4(.5)))) {
        fray = texture(u_surfaceNoise, rainNoiseAt((world.xy + world.z * vec2(.29, .41)) / 28.0 + .217)).r;
    }
    return vec2(pockets, fray);
}

// Ordinary families only: blendCovers handles volcanic slots with one shared surface sample.
TerrainMaterial naturalMaterialOf(MaterialProjection projection, vec2 noise, vec3 world, vec3 face,
      float aboveFoot, float belowRim, float rock, float hardness, float broad, float fine, float region,
      float familyId, vec4 tiles, vec4 layers, float sediment) {
    float up = clamp(face.z, 0.0, 1.0);
    float pockets = noise.x;
    float variation = broad * .4 + fine * .25 + pockets * .35;
    float foot = exp(-max(aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, belowRim);
    // Deposits occupy pockets and suitable slopes; a foot is an opportunity, not a mandatory painted ring.
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    // Break a bank-to-cliff contact into soil pockets and exposed rock. Both sides use the same world field;
    // pure banks/cliffs stay pure, and distance filtering already controls the final height blend.
    float exposure = clamp(rock + (variation - .5) * 1.2 * (4.0 * rock * (1.0 - rock)), 0.0, 1.0);
    // Exposed rock shoulders lose loose cover, while sheltered patches retain it. Plateau and face use the
    // same rim distance and world field, so the weathered contact continues over the crest.
    float scour = (1.0 - smoothstep(.1, 3.0 + 4.0 * broad, belowRim))
          * exposure * smoothstep(.30, .65, broad * .6 + pockets * .4);
    // A sparse bank mesh carries its broad slope, not every eroded patch. Expose its mantle with the existing
    // world-space detail field, so sand and turf do not hide all the material detail when the mesh is simplified.
    // Level ground keeps its cover; hard cliffs already get their exposed rock from the existing material mix.
    float bank = (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up));
    float wear = bank * smoothstep(.32, .68, fine * .65 + pockets * .35);
    float coverUp = up - .2 * wear;
    float cover = smoothstep(.4, .96, coverUp + (variation - .5) * .55);
    float soil = 0.0;
    float dust = 0.0;
    float sod = 0.0;
    bool desert = abs(familyId - DESERT_FAMILY) < .5;
    bool arid = desert || abs(familyId - MARS_FAMILY) < .5 || abs(familyId - VOLCANO_FAMILY) < .5;
    bool plates = desert || abs(familyId - VOLCANO_FAMILY) < .5;
    // A continuous exposure field crosses plateau interiors as well as their rims. Each material retains its
    // own substrate and response: this is not a new terrain type or the same pale patch painted on every biome.
    float thin = smoothstep(.50, .72, region * .6 + broad * .3 + fine * .1);
    thin *= smoothstep(.82, .97, up) * (1.0 - deposit) * (1.0 - .75 * foot);
    // Organic weathering also opens the existing cover onto its own soil/tissue. A tint alone disappears
    // in busy turf. Use continuous fields and existing material roles, not a separate decal or cut-out shape.
    float aged = smoothstep(.45, .68, broad * .5 + region * .35 + fine * .15) * smoothstep(.88, .98, up)
          * (1.0 - sediment);
    if (abs(familyId) < .5 || abs(familyId - TROPICAL_FAMILY) < .5) {
        // The two-level earth bank keeps a ragged turf lip and broken turf on its real intermediate shoulder.
        // Color remains legible from above when individual blades are too small to draw.
        float levelMetres = u_levelHeight / u_metre;
        float twoLevels = 1.0 - smoothstep(.05, .3, abs((aboveFoot + belowRim) / levelMetres - 2.0));
        float fray = noise.y;
        float lipDepth = levelMetres * (mix(.2, .65, fine * .45 + pockets * .55) + .14 * (fray - .5));
        float lip = 1.0 - smoothstep(.05, lipDepth, belowRim);
        float middle = abs(aboveFoot / levelMetres - 1.0 - (pockets - .5) * .18);
        float width = mix(.1, .24, fine) * mix(.55, 1.45, fray);
        float shoulder = 1.0 - smoothstep(width * .25, width, middle);
        shoulder *= smoothstep(.35, .73, up) * smoothstep(.22, .55, pockets * .7 + fine * .3);
        cover = max(cover, max(lip, shoulder) * twoLevels * (1.0 - exposure));
        sod = rootMat(belowRim, lipDepth, fray) * twoLevels * (1.0 - exposure) * (1.0 - smoothstep(.85, .97, up));
        float wornCover = cover * aged * .48;
        cover -= wornCover;
        soil = (1.0 - cover) * ((1.0 - exposure) * .9 + rim * .5 * smoothstep(.25, .7, variation));
        soil = max(soil, wornCover); // Worn organic cover reveals soil even on a tall plateau, not pale cliff rock.
        deposit *= mix(.25, 1.0, rock);
    } else if (abs(familyId - FUNGUS_FAMILY) < .5) {
        // Preserve the two fungal skins: mauve lowlands, cyan crust above level zero. Weather each in its own
        // material: lowland skin wears through to violet bedrock, while gaps in the upper crust expose mauve tissue.
        float levelMetres = u_levelHeight / u_metre;
        float rise = smoothstep(.25, .9, (aboveFoot + belowRim) / levelMetres);
        float raised = smoothstep(.15, 1.3, v_cloudPosition.z / u_levelHeight);
        float lip = 1.0 - smoothstep(.1, 1.4 + 1.6 * pockets, belowRim);
        float growth = max(raised, lip * rise);
        float colonies = smoothstep(.28, .69, region * .5 + broad * .3 + pockets * .2);
        deposit = smoothstep(.48, .88, up) * mix(.10 * colonies, .28 + .70 * colonies, growth);
        deposit *= 1.0 - aged * .55;
        cover = smoothstep(.76, .97, up);
        cover *= 1.0 - .90 * thin * (1.0 - growth);
        soil = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    } else if (abs(familyId - 5.0) < .5) {
        cover = smoothstep(.36, .92, coverUp + (variation - .5) * .5 + deposit * .18);
        cover *= 1.0 - max(.85 * scour, .88 * thin * rim);
        deposit *= .35;
    } else if (abs(familyId - 2.0) < .5) {
        cover = smoothstep(.38, .94, coverUp + (variation - .5) * .45 + deposit * .15);
        cover *= 1.0 - .92 * scour;
        deposit *= .55;
    } else if (arid) {
        // Firm crust wears back to exposed rock on banks and a broken mineral shoulder at the crest.
        cover = smoothstep(.38, .96, coverUp + (variation - .5) * .22);
        float shoulder = (1.0 - smoothstep(.1, 1.3 + 1.6 * broad, belowRim))
              * smoothstep(.28, .65, pockets) * smoothstep(.55, .94, up);
        // Thin hardpan/dust opens onto flush bedrock in broad connected patches, including plateau interiors.
        // Reuse the existing regional fields: no hex-local mask, new noise lookup or displaced playing surface.
        // Deposited material shelters the foot; small height-map detail breaks the final material contact.
        cover *= 1.0 - max(.90 * scour, max(.85 * shoulder, .92 * thin));
        // Horizontal cap maps show fractured plates; the wall maps keep their upright bedding or basalt columns.
        // Carry the cap around the shoulder, then fade with slope and depth below the rim. Distant faces keep
        // their wall material; level interiors keep the cap. Reuse the existing metre-scale rim field.
        float cap = smoothstep(.82, .97, up);
        if (plates) cap = max(cap, rim * smoothstep(.25, .90, up));
        soil = (1.0 - cover) * cap;
        // A light film of the local hardpan/ash remains on exposed horizontal stone. Keep its complete material,
        // rather than painting dark cliff patches onto a flat plain; submerged beds keep their sediment instead.
        dust = .24 * smoothstep(.88, .98, up) * (1.0 - sediment);
        deposit *= .55;
    } else if (abs(familyId - 1.0) < .5) {
        cover = smoothstep(.28, .9, coverUp + (variation - .5) * .16);
        cover *= 1.0 - .9 * thin;
        soil = (1.0 - cover) * (1.0 - exposure);
        // Weathered compact soil gives way to flush bedrock on level ground; its bank keeps its earthen mantle.
        soil *= 1.0 - smoothstep(.90, .99, up);
        dust = .18 * smoothstep(.88, .98, up) * (1.0 - sediment);
        deposit *= .6;
    } else if (abs(familyId - 3.0) < .5 || abs(familyId - 6.0) < .5) {
        // Rocky ground and lunar regolith retain their own loose fines between broad exposed rock plates.
        // Lunar coverage is visual only; it does not create gravity-driven deposits or extra rubble geometry.
        cover *= 1.0 - .92 * thin;
        dust = .12 * smoothstep(.88, .98, up) * (1.0 - sediment);
    }
#ifdef sublevelFloorFlag
    // Sediment half fills a sunken mineral floor, as in cliffMaterial; fungal felt grows over its weathering.
    float settled = abs(familyId - FUNGUS_FAMILY) < .5 ? 0.0 : sunkenCover * smoothstep(.82, .97, up);
    cover = mix(cover, 1.0, .5 * settled);
    deposit *= 1.0 - .5 * settled;
#endif
    soil = clamp(soil, 0.0, 1.0 - cover);
    vec4 roles = vec4(cover, soil, deposit, max(0.0, 1.0 - cover - soil));
    roles.xyw *= 1.0 - deposit;
    // Concrete transported onto another surface is loose aggregate, never a second paved slab.
    // The constructed face itself keeps the existing panel material, supplied by blendCovers below.
    if (abs(familyId - 4.0) < .5) roles = vec4(0.0, 0.0, 1.0, 0.0);
    // Submerged contacts exchange bed sediment and exposed stone, never living turf or windblown surface sand.
    float bedRock = rock * (1.0 - smoothstep(.2, .8, up)) * smoothstep(.05, 2.2, aboveFoot);
    roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), sediment);
    // Cap and wall are orientations of the same rock, not competing covers. Let their combined height compete
    // with hardpan/deposits, then distribute the winning rock weight smoothly across all material channels.
    float capShare = plates ? roles.y / max(roles.y + roles.w, .00001) : 0.0;
    if (plates) roles = vec4(roles.x, 0.0, roles.z, roles.y + roles.w);
    // Height maps are in [0,1]. Even their largest possible score cannot rescue these layers, so avoid all
    // their texture work while retaining the original roles for the exact final blend calculation.
    float highestMinimum = max(max(roles.x, roles.y), max(roles.z, roles.w));
    float width = materialBlendWidth();
    vec4 candidates = step(vec4(highestMinimum - width), roles + .38 * (4.0 * roles * (1.0 - roles)));
    if (dust > 0.0) candidates.x = 1.0;
    bool sand = abs(familyId - 2.0) < .5;
    // These authored plates have one physical size. Reuse sand's translated sampling for color and normals;
    // mixing an enlarged copy makes overlapping fractures look like faint stains. Select across broad regions.
    float mantleVariation = plates ? broad : fine;
    bool fungus = abs(familyId - FUNGUS_FAMILY) < .5;
    float capAmount = plates ? roles.w * candidates.w * capShare : roles.y * candidates.y;
    float rockAmount = roles.w * candidates.w * (1.0 - capShare);
    MaterialProjection pa = relievedMaterial(layers.x, tiles.x, roles.x * candidates.x, projection, broad, fungus, sand);
    MaterialProjection pb = relievedMaterial(layers.w, tiles.w, capAmount, projection, mantleVariation, fungus, plates);
    MaterialProjection pc = relievedMaterial(layers.y, tiles.y, roles.z * candidates.z, projection, fine, fungus, false);
    MaterialProjection pd = relievedMaterial(layers.z, tiles.z, rockAmount, projection, broad, fungus, false);
    vec4 a = sampleMaterial(layers.x, tiles.x, roles.x * candidates.x, pa,
          world, broad, fine, region, true, sand, familyId, vec2(rim, foot));
    vec4 b = sampleMaterial(layers.w, tiles.w, capAmount, pb,
          world, mantleVariation, fine, region, false, plates, familyId, vec2(0.0));
    b.rgb *= mix(vec3(1.0), ROOT_MAT, sod);
    vec4 c = sampleMaterial(layers.y, tiles.y, roles.z * candidates.z, pc,
          world, fine, fine, region, false, false, familyId, vec2(0.0));
    vec4 d = sampleMaterial(layers.z, tiles.z, rockAmount, pd,
          world, broad, fine, region, false, false, familyId, vec2(0.0));
    if (desert) {
        // Pale abraded sandstone at the actual rim reads from above; deeper faces retain their warm brown.
        float abrasion = (1.0 - smoothstep(.15, 2.2, belowRim)) * smoothstep(.45, .95, up);
        vec3 weathering = mix(vec3(.98, .91, .85), vec3(1.14, 1.15, 1.13), abrasion);
        d.rgb *= authoredPigmentGain(weathering);
    }
    float rockHeight = plates ? mix(d.a, b.a, capShare) : d.a;
    vec4 weights = materialWeights(roles, vec4(a.a, b.a, c.a, rockHeight));
    if (plates) weights.yw = weights.w * vec2(capShare, 1.0 - capShare);
    vec2 coating = weights.yw * dust;
    weights += vec4(coating.x + coating.y, -coating.x, 0.0, -coating.y);
    vec4 na = materialNormal(layers.x + 1.0, tiles.x, weights.x, pa, face, broad, fungus, sand);
    vec4 nb = materialNormal(layers.w + 1.0, tiles.w, weights.y, pb, face, mantleVariation, fungus, plates);
    vec4 nc = materialNormal(layers.y + 1.0, tiles.y, weights.z, pc, face, fine, fungus, false);
    vec4 nd = materialNormal(layers.z + 1.0, tiles.z, weights.w, pd, face, broad, fungus, false);
    TerrainMaterial result;
    result.color = a.rgb * weights.x + b.rgb * weights.y + c.rgb * weights.z + d.rgb * weights.w;
    result.normal = normalize(na.rgb * weights.x + nb.rgb * weights.y + nc.rgb * weights.z + nd.rgb * weights.w);
    result.cavity = dot(vec4(na.a, nb.a, nc.a, nd.a), weights);
    result.relief = dot(vec4(length(na.rgb), length(nb.rgb), length(nc.rgb), length(nd.rgb)), weights);
    result.height = dot(vec4(a.a, b.a, c.a, d.a), weights);
    result.finish = weighFinish(materialFinish(layers.x, tiles.x, weights.x, pa, broad, fungus, sand),
          materialFinish(layers.w, tiles.w, weights.y, pb, mantleVariation, fungus, plates),
          materialFinish(layers.y, tiles.y, weights.z, pc, fine, fungus, false),
          materialFinish(layers.z, tiles.z, weights.w, pd, broad, fungus, false), weights);
    result.volcanic = 0.0;
    return result;
}

// The family's floor takes its ground's place from level -1 down (floorShare), as in cliffMaterial.
TerrainMaterial naturalMaterialFor(MaterialProjection projection, vec2 noise, vec3 world, vec3 face,
      float aboveFoot, float belowRim, float rock, float hardness, float broad, float fine, float region,
      float familyId, vec4 tiles, vec4 layers, float sediment) {
#ifdef sublevelFloorFlag
    vec2 floorMaterial;
    float share = floorShare(world, face, familyId, floorMaterial);
    TerrainMaterial ground, sunken;
    int passes = share > 0.0 && share < 1.0 ? 2 : 1;
    for (int pass = 0; pass < passes; pass++) {
        bool floored = share >= 1.0 || pass == 1;
        sunkenCover = floored ? 1.0 : 0.0;
        TerrainMaterial m = naturalMaterialOf(projection, noise, world, face, aboveFoot, belowRim, rock, hardness,
              broad, fine, region, familyId, floored ? vec4(floorMaterial.y, tiles.yzw) : tiles,
              floored ? vec4(floorMaterial.x, layers.yzw) : layers, sediment);
        if (pass == 0) ground = m; else sunken = m;
    }
    sunkenCover = 0.0;
    return passes == 2 ? floorBlend(ground, sunken, share) : ground;
#else
    return naturalMaterialOf(projection, noise, world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region,
          familyId, tiles, layers, sediment);
#endif
}

// Volcanic programs without a palette draw sculpt materials only for crust and bank families.
TerrainMaterial crustOrBankMaterial(vec3 world, vec3 face) {
    Volcanic material = magmaSurface(world, face, -viewDirection(), vec3(0.0), 1.0, vec4(0.0));
    return volcanicMaterial(material,
          sculptFamily > VOLCANIC_BANK_FAMILY - .5 ? magmaBankHeat(v_cloudPosition.xy) : 1.0);
}
#endif

TerrainMaterial cliffMaterial(MaterialProjection p, vec3 world, vec3 face, float aboveFoot, float belowRim,
      float rock, float hardness, float broad, float fine, float region, float sediment, vec4 palette, int slot,
      vec4 tiles, vec4 layers, bool sides, bool single);

#ifdef terrainBlendFlag
vec4 coverTilesAt(int i);
vec4 coverLayersAt(int i);
vec4 secondTexel(sampler2DArray maps, float layer, float tile, MaterialProjection p, bool sides);

#ifdef mediumQualityFlag
// Medium quality's cover that is not the pixel's strongest: one layer's colour and height, over the strongest's normal.
TerrainMaterial coverLayer(TerrainMaterial strongest, MaterialProjection p, float layer, float tile, bool sides) {
    vec4 texel = secondTexel(u_terrainLayers, layer * .5, tile, p, sides);
    TerrainMaterial cover = strongest;
    cover.color = texel.rgb;
    cover.height = texel.a;
    cover.finish = meanFinish(int(layer * .5 + .5));
    return cover;
}

// A level top's cover slot: what the slot shows, given the strongest cover in full.
TerrainMaterial coverSlot(int slot, int first, TerrainMaterial strongest, TerrainMaterial incoming, MaterialProjection p,
      vec4 raw) {
    if (slot == first || coverFamilies[slot] == coverFamilies[first] && coverLayersAt(slot) == coverLayersAt(first)
          && coverTilesAt(slot) == coverTilesAt(first)) {
        return strongest;
    }
    if (abs(coverFamilies[slot] - 4.0) < .5) return incoming;
    // An absent cover cannot affect height competition; retain any substrate sand can expose later.
    if (raw[slot] <= 0.0 && abs(coverFamilies[slot] - sculptFamily) >= .5) return strongest;
    return coverLayer(strongest, p, coverLayersAt(slot).x, coverTilesAt(slot).x, false);
}
#endif

float coverPatch(vec3 world, float familyId) {
    if (familyId >= VOLCANIC_CRUST_FAMILY) familyId = 11.0 + familyId - VOLCANIC_CRUST_FAMILY;
    vec2 offset = familyId * vec2(.17, .31);
    vec2 contact = world.xy + world.z * vec2(.47, .29);
    return .5 + (texture(u_surfaceNoise, rainNoiseAt(contact / 21.0 + offset)).r - .5) * .8;
}

// How far the covers' plain proportions replace their height-blended patches: fully in an authored transition
// (v_coverInterpolation), whose mixture is the hex's own look, except a tropical mixture's dark interior litter; about
// half between neighbouring covers on level ground, softening them without losing their interlocking texture. Banks
// take an authored transition's part (blendCliff), so a bank meets the ground at its foot in the same mixture.
float coverGradient(vec4 proportions) {
    float authored = clamp(v_coverInterpolation, 0.0, 1.0);
    float tropical = dot(proportions, vec4(lessThan(abs(coverFamilies - TROPICAL_FAMILY), vec4(.5))));
    return mix(.55, 1.0, authored) * (1.0 - authored * smoothstep(0.0, .15, tropical));
}

// What a pixel's final cover weights make of its grass, sand, bounce light and rain response.
void coverEffects(vec4 weights, float sediment, out float grass, out float sand, out vec3 bounce, out float response,
      out float rainCover) {
    grass = dot(vec4(1.0) - step(vec4(.5), abs(coverFamilies)), weights) * (1.0 - sediment);
    sand = dot(vec4(1.0) - step(vec4(.5), abs(coverFamilies - 2.0)), weights);
    bounce = groundBounceFor(coverFamilies.x) * weights.x + groundBounceFor(coverFamilies.y) * weights.y
          + groundBounceFor(coverFamilies.z) * weights.z + groundBounceFor(coverFamilies.w) * weights.w;
    response = dot(max(u_coverResponses, vec4(0.0)), weights);
    rainCover = dot(step(vec4(0.0), u_coverResponses), weights);
}

void blendCovers(vec3 world, vec3 face, float foot, float rim, float rock, float hardness, float sediment,
      float broad, float fine, float region,
      inout vec3 color, inout vec3 normal, inout float cavity, inout float relief, inout float height,
      inout float grass, inout float sand, inout vec3 bounce, inout float response, inout float rainCover,
      inout Finish finish, out float volcanic, out vec4 weights) {
    vec4 raw = max(v_coverWeights, vec4(0.0));
#ifndef volcanicFlag
    // A slot under 2 % coverage is left out; the remaining weights renormalize below.
    raw *= step(vec4(.02), raw);
#endif
    MaterialProjection projection = materialProjection(world, face);
#ifdef volcanicFlag
    // naturalMaterialFor's shared world fields, sampled before the varying coverage branches.
    vec2 noise = vec2(0.0);
    if (any(lessThan(coverFamilies, vec4(VOLCANIC_CRUST_FAMILY - .5)))) noise = materialNoise(world, coverFamilies);
    MagmaProjection magmaCoordinates = magmaProjectionFor(world);
    // A bank can acquire coverage here even if its incoming vertex weight was zero. Sample the field
    // before varying coverage branches; bank emission reuses the same derivative-safe shore sample.
    vec4 bank = vec4(greaterThan(coverFamilies, vec4(VOLCANIC_BANK_FAMILY - .5)));
    float shore = -.4;
    if (u_magmaFieldMap.x > 0.0 && dot(bank, vec4(1.0)) > 0.0) {
        shore = magmaShore(magmaField(v_cloudPosition.xy));
        raw = mix(raw, bank, 1.0 - smoothstep(0.0, MAGMA_MARGIN, -shore));
    }
    float bankHeat = magmaBankHeat(shore);
    // Crust and bank use this draw's same relief, maps and normal; only their emission strength differs.
    // Without sand transfer, zero coverage cannot become visible; skip its shared material entirely.
    vec4 volcanicNeeded = raw;
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY == 2
    volcanicNeeded = max(raw, vec4(lessThan(abs(coverFamilies - sculptFamily), vec4(.5))));
    volcanicNeeded.x = 1.0;
#endif
    Volcanic magmaMaterial = Volcanic(vec3(0.0), face, vec4(0.0), vec2(0.0), vec3(0.0));
    if (dot(volcanicNeeded, vec4(greaterThan(coverFamilies, vec4(VOLCANIC_CRUST_FAMILY - .5)))) > 0.0) {
        magmaMaterial = magmaSurface(magmaCoordinates, face, -viewDirection(), vec3(0.0), 1.0, vec4(0.0));
    }
#endif
#if defined(VOLCANIC_ORDINARY_FAMILY) && VOLCANIC_ORDINARY_FAMILY >= 0
    // This draw has one ordinary material identity. Its family is constant for compilation; maps/scales
    // remain the original uniforms. Repeated matching slots share all sampled material channels.
    vec4 ordinaryTiles = u_coverTiles0, ordinaryLayers = u_coverLayers0;
    if (coverFamilies.x != float(VOLCANIC_ORDINARY_FAMILY)) {
        if (coverFamilies.y == float(VOLCANIC_ORDINARY_FAMILY)) {
            ordinaryTiles = u_coverTiles1; ordinaryLayers = u_coverLayers1;
        } else if (coverFamilies.z == float(VOLCANIC_ORDINARY_FAMILY)) {
            ordinaryTiles = u_coverTiles2; ordinaryLayers = u_coverLayers2;
        } else {
            ordinaryTiles = u_coverTiles3; ordinaryLayers = u_coverLayers3;
        }
    }
    // Projection gradients and noise were captured above, before this varying coverage guard.
    vec4 ordinaryNeeded = raw;
#if VOLCANIC_ORDINARY_FAMILY == 2
    // Sand can expose a zero-weight substrate after height blending; retain the original base too.
    ordinaryNeeded = max(raw, vec4(lessThan(abs(coverFamilies - sculptFamily), vec4(.5))));
    ordinaryNeeded.x = 1.0;
#endif
    TerrainMaterial ordinary = TerrainMaterial(vec3(0.0), face, 1.0, 0.0, Finish(1.0, 0.0, 0.0, vec3(0.0)), 0.0, 1.0);
    if (dot(ordinaryNeeded, vec4(equal(coverFamilies, vec4(float(VOLCANIC_ORDINARY_FAMILY))))) > 0.0) {
        ordinary = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine,
              region, float(VOLCANIC_ORDINARY_FAMILY), ordinaryTiles, ordinaryLayers, sediment);
    }
#endif
#if defined(mediumQualityFlag) && !defined(volcanicFlag)
    int first = 0;
    for (int i = 1; i < 4; i++) if (raw[i] > raw[first]) first = i;
    TerrainMaterial incoming = TerrainMaterial(color, normal, cavity, height, finish, 0.0, relief);
    TerrainMaterial strongest = abs(coverFamilies[first] - 4.0) < .5 ? incoming
          : cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                coverFamilies, first, coverTilesAt(first), coverLayersAt(first), false, first != 0);
    TerrainMaterial a = coverSlot(0, first, strongest, incoming, projection, raw);
    // Slots repeating the base take its result rather than read its map again.
#ifdef COVER_B_IS_BASE
    TerrainMaterial b = a;
#else
    TerrainMaterial b = coverSlot(1, first, strongest, incoming, projection, raw);
#endif
#ifdef COVER_C_IS_BASE
    TerrainMaterial c = a;
#else
    TerrainMaterial c = coverSlot(2, first, strongest, incoming, projection, raw);
#endif
    TerrainMaterial d = coverFamilies.w != coverFamilies.x ? coverSlot(3, first, strongest, incoming, projection, raw) : a;
#elif defined(volcanicFlag) && !defined(VOLCANIC_ORDINARY_FAMILY)
    // One ordinary evaluator call site for the generic volcanic palette. The loop bound is draw-uniform.
    bool separateB = false, separateC = false;
#ifndef COVER_B_IS_BASE
    separateB = coverFamilies.y != coverFamilies.x || abs(coverFamilies.x - 4.0) < .5
          || any(notEqual(u_coverTiles1, u_coverTiles0)) || any(notEqual(u_coverLayers1, u_coverLayers0));
#endif
#ifndef COVER_C_IS_BASE
    separateC = coverFamilies.z != coverFamilies.x || abs(coverFamilies.x - 4.0) < .5
          || any(notEqual(u_coverTiles2, u_coverTiles0)) || any(notEqual(u_coverLayers2, u_coverLayers0));
#endif
    bool separateD = coverFamilies.w != coverFamilies.x;
    int slots = separateD ? 4 : separateC ? 3 : separateB ? 2 : 1;
    TerrainMaterial a = TerrainMaterial(color, normal, cavity, height, finish, 0.0, relief);
    TerrainMaterial b = a, c = a, d = a;
    for (int slot = 0; slot < slots; slot++) {
        float selectedFamily = coverFamilies[slot];
        vec4 selectedTiles = slot == 0 ? u_coverTiles0 : slot == 1 ? u_coverTiles1
              : slot == 2 ? u_coverTiles2 : u_coverTiles3;
        vec4 selectedLayers = slot == 0 ? u_coverLayers0 : slot == 1 ? u_coverLayers1
              : slot == 2 ? u_coverLayers2 : u_coverLayers3;
        bool distinct = slot == 0 || (slot == 1 ? separateB : slot == 2 ? separateC : separateD);
        bool needed = slot == 0 || (distinct && (raw[slot] > 0.0 || abs(selectedFamily - sculptFamily) < .5));
        TerrainMaterial value = a;
        if (needed && !(slot == 0 && abs(selectedFamily - 4.0) < .5)) {
            if (selectedFamily > VOLCANIC_CRUST_FAMILY - .5) {
                value = volcanicMaterial(magmaMaterial, selectedFamily, bankHeat);
            } else {
                value = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine,
                      region, selectedFamily, selectedTiles, selectedLayers, sediment);
            }
        }
        if (slot == 0) { a = value; b = value; c = value; d = value; }
        else if (slot == 1) b = value;
        else if (slot == 2) c = value;
        else d = value;
    }
#else
    TerrainMaterial a;
#if defined(VOLCANIC_ORDINARY_FAMILY) && VOLCANIC_ORDINARY_FAMILY < 0
    a = volcanicMaterial(magmaMaterial, coverFamilies.x, bankHeat);
#else
    if (abs(coverFamilies.x - 4.0) < .5) {
        a = TerrainMaterial(color, normal, cavity, height, finish, 0.0, relief);
#ifdef volcanicFlag
    } else if (coverFamilies.x > VOLCANIC_CRUST_FAMILY - .5) {
        a = volcanicMaterial(magmaMaterial, coverFamilies.x, bankHeat);
#endif
    } else {
#ifdef VOLCANIC_ORDINARY_FAMILY
        a = ordinary;
#else
#ifdef volcanicFlag
        a = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
              coverFamilies.x, u_coverTiles0, u_coverLayers0, sediment);
#else
        // An absent base may still serve an aliased slot or receive sand's exposed substrate.
        bool baseNeeded = dot(raw, vec4(equal(coverFamilies, vec4(coverFamilies.x)))) > 0.0
              || (any(lessThan(abs(coverFamilies - 2.0), vec4(.5)))
                    && abs(coverFamilies.x - sculptFamily) < .5);
        a = TerrainMaterial(vec3(0.0), face, 1.0, 0.0, Finish(1.0, 0.0, 0.0, vec3(0.0)), 0.0, 1.0);
        if (baseNeeded) {
            a = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                  coverFamilies, 0, u_coverTiles0, u_coverLayers0, false, false);
        }
#endif
#endif
    }
#endif
    // Unused palette slots repeat the base family. Reuse its complete material instead of evaluating its
    // textures and volcanic relief again. Constructed concrete differs from transported aggregate; keep that
    // distinction, and retain any explicit per-slot texture/scale overrides. These branches are draw-uniform.
    TerrainMaterial b = a;
#ifndef COVER_B_IS_BASE
    if (coverFamilies.y != coverFamilies.x || abs(coverFamilies.x - 4.0) < .5
          || any(notEqual(u_coverTiles1, u_coverTiles0)) || any(notEqual(u_coverLayers1, u_coverLayers0))) {
#ifdef volcanicFlag
        if (coverFamilies.y > VOLCANIC_CRUST_FAMILY - .5) {
            // Zero coverage stays zero unless later sand exposure transfers weight into the substrate.
            if (raw.y > 0.0 || abs(coverFamilies.y - sculptFamily) < .5) {
                b = volcanicMaterial(magmaMaterial, coverFamilies.y, bankHeat);
            }
        }
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        else
#endif
#endif
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        if (raw.y > 0.0 || abs(coverFamilies.y - sculptFamily) < .5) {
            // An absent cover cannot affect height competition; retain any substrate sand can expose later.
#ifdef VOLCANIC_ORDINARY_FAMILY
            b = ordinary;
#else
#ifdef volcanicFlag
            b = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
                  coverFamilies.y, u_coverTiles1, u_coverLayers1, sediment);
#else
            b = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                  coverFamilies, 1, u_coverTiles1, u_coverLayers1, false, true);
#endif
#endif
        }
#endif
    }
#endif
    TerrainMaterial c = a;
#ifndef COVER_C_IS_BASE
    if (coverFamilies.z != coverFamilies.x || abs(coverFamilies.x - 4.0) < .5
          || any(notEqual(u_coverTiles2, u_coverTiles0)) || any(notEqual(u_coverLayers2, u_coverLayers0))) {
#ifdef volcanicFlag
        if (coverFamilies.z > VOLCANIC_CRUST_FAMILY - .5) {
            // Zero coverage stays zero unless later sand exposure transfers weight into the substrate.
            if (raw.z > 0.0 || abs(coverFamilies.z - sculptFamily) < .5) {
                c = volcanicMaterial(magmaMaterial, coverFamilies.z, bankHeat);
            }
        }
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        else
#endif
#endif
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        if (raw.z > 0.0 || abs(coverFamilies.z - sculptFamily) < .5) {
            // An absent cover cannot affect height competition; retain any substrate sand can expose later.
#ifdef VOLCANIC_ORDINARY_FAMILY
            c = ordinary;
#else
#ifdef volcanicFlag
            c = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
                  coverFamilies.z, u_coverTiles2, u_coverLayers2, sediment);
#else
            c = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                  coverFamilies, 2, u_coverTiles2, u_coverLayers2, false, true);
#endif
#endif
        }
#endif
    }
#endif
    TerrainMaterial d = a;
    if (coverFamilies.w != coverFamilies.x) {
#ifdef volcanicFlag
        if (coverFamilies.w > VOLCANIC_CRUST_FAMILY - .5) {
            // Zero coverage stays zero unless later sand exposure transfers weight into the substrate.
            if (raw.w > 0.0 || abs(coverFamilies.w - sculptFamily) < .5) {
                d = volcanicMaterial(magmaMaterial, coverFamilies.w, bankHeat);
            }
        }
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        else
#endif
#endif
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        if (raw.w > 0.0 || abs(coverFamilies.w - sculptFamily) < .5) {
            // An absent cover cannot affect height competition; retain any substrate sand can expose later.
#ifdef VOLCANIC_ORDINARY_FAMILY
            d = ordinary;
#else
#ifdef volcanicFlag
            d = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
                  coverFamilies.w, u_coverTiles3, u_coverLayers3, sediment);
#else
            d = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                  coverFamilies, 3, u_coverTiles3, u_coverLayers3, false, true);
#endif
#endif
        }
#endif
    }
#endif
    // Broad coverage is shared with vegetation. Height adds small interlocking edges, never a new family.
    // Unused slots repeat the base family; their contact noise is identical regardless of material overrides.
    // Family comparisons are draw-uniform, so the retained texture derivatives remain defined.
    vec4 patches = vec4(coverPatch(world, coverFamilies.x));
    if (coverFamilies.y != coverFamilies.x) patches.y = coverPatch(world, coverFamilies.y);
    if (coverFamilies.z != coverFamilies.x) patches.z = coverPatch(world, coverFamilies.z);
    if (coverFamilies.w != coverFamilies.x) patches.w = coverPatch(world, coverFamilies.w);
    float patchStrength = mix(1.5, 2.4, 1.0 - smoothstep(.35, .85, face.z));
    weights = materialWeights(raw, vec4(a.height, b.height, c.height, d.height) * .45 + patches * patchStrength);
    // The tropical exception fades with its actual contribution, never the triangle's palette membership, so a
    // zero-weight tropical slot cannot introduce a material seam.
    vec4 proportions = raw / max(dot(raw, vec4(1.0)), .00001);
    weights = mix(weights, proportions, coverGradient(proportions));
    // Loose deposits leave a few windows onto this tile's actual substrate. Transfer coverage, not just colour,
    // so exposed turf/rock keeps its own normals, height, cavity and wet response. The CPU uses this same field
    // for grass roots; neither the board's SAND flag nor the terrain/support mesh changes.
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY == 2
    // Without a sand slot the transfer is exactly zero. Skip its two noise fields; specialized palettes
    // can contain sand only when their sole ordinary family is SAND. Generic presence is draw-uniform.
    vec4 sandy = vec4(lessThan(abs(coverFamilies - 2.0), vec4(.5)));
    vec4 substrate = vec4(lessThan(abs(coverFamilies - sculptFamily), vec4(.5)));
    float substrateCount = dot(substrate, vec4(1.0));
    if (dot(sandy, vec4(1.0)) > 0.0 && abs(sculptFamily - 2.0) > .5 && substrateCount > 0.0) {
        float exposed = sandExposure(world.xy) * (1.0 - sediment);
        float amount = dot(weights, sandy) * exposed;
        weights = weights * (1.0 - sandy * exposed) + substrate * (amount / substrateCount);
    }
#endif
    color = a.color * weights.x + b.color * weights.y + c.color * weights.z + d.color * weights.w;
    normal = normalize(a.normal * weights.x + b.normal * weights.y + c.normal * weights.z + d.normal * weights.w);
    cavity = dot(vec4(a.cavity, b.cavity, c.cavity, d.cavity), weights);
    relief = dot(vec4(a.relief, b.relief, c.relief, d.relief), weights);
    height = dot(vec4(a.height, b.height, c.height, d.height), weights);
    finish = weighFinish(a.finish, b.finish, c.finish, d.finish, weights);
    volcanic = dot(vec4(a.volcanic, b.volcanic, c.volcanic, d.volcanic), weights);
    coverEffects(weights, sediment, grass, sand, bounce, response, rainCover);
}
#endif

// ---- Cliffs and tops: one small natural material ------------------------------------------------------------------
// Natural tops, walls, banks, drowned walls and bedrock share one material: the family's wall rock, its ground on
// ledges and tops, its mantle on low banks (or its cap plates at an arid rim) and its debris at the foot. Only the two
// strongest roles are sampled. Walls read one texel per side projection; tops read the top projection twice (two
// repeats, or two offsets for translated materials). The geometry carries the landform; the bed hardness it stores
// per cliff vertex tints the strata on walls.

// Each family's cliff roles as data, from the role formulas of naturalMaterialFor.
// ramp: the ledge-cover smoothstep (low, high), its jitter, and how much a foot deposit raises it.
// trim: how much rim scour, thin cover (times a rock crest when z is 1) and an arid crest shoulder remove from the cover.
// kind: x the mantle (0 none, 1 meadow soil, 2 bank soil, 3 arid cap plates), y the foot deposit share, z 1 for a
// meadow's turf lip and shoulder on two-level banks.
void cliffFamily(float familyId, out vec4 ramp, out vec4 trim, out vec3 kind) {
    // Rock and lunar.
    ramp = vec4(.4, .96, .55, 0.0); trim = vec4(0.0, .92, 0.0, 0.0); kind = vec3(0.0, 1.0, 0.0);
    if (isFamily(familyId, 0.0) || isFamily(familyId, TROPICAL_FAMILY)) {
        trim = vec4(0.0); kind = vec3(1.0, 1.0, 1.0);
    } else if (isFamily(familyId, 1.0)) { // dirt
        ramp = vec4(.28, .9, .16, 0.0); trim = vec4(0.0, .9, 0.0, 0.0); kind = vec3(2.0, .6, 0.0);
    } else if (isFamily(familyId, 2.0)) { // sand
        ramp = vec4(.38, .94, .45, .15); trim = vec4(.92, 0.0, 0.0, 0.0); kind = vec3(0.0, .55, 0.0);
    } else if (isFamily(familyId, 5.0)) { // snow
        ramp = vec4(.36, .92, .5, .18); trim = vec4(.85, .88, 1.0, 0.0); kind = vec3(0.0, .35, 0.0);
    } else if (isFamily(familyId, DESERT_FAMILY) || isFamily(familyId, MARS_FAMILY)
          || isFamily(familyId, VOLCANO_FAMILY)) {
        ramp = vec4(.38, .96, .22, 0.0); trim = vec4(.9, .92, 0.0, .85); kind = vec3(3.0, .55, 0.0);
    } else if (isFamily(familyId, FUNGUS_FAMILY)) {
        ramp = vec4(.76, .97, 0.0, 0.0); trim = vec4(0.0, .9, 0.0, 0.0); kind = vec3(2.0, .3, 0.0);
    }
}

// A top's two reads: two repeats of the map (the second 2.37 times larger and turned) mixed by a world field, or for
// translated materials (sand, cap plates, fungus) two offsets of one repeat, which keeps their single feature size.
bool oneRepeat = false; // Medium quality: this role's tops read their first repeat alone (cliffMaterialOf).

void topCoordinates(MaterialProjection p, float tile, float variation, bool translated,
      out vec2 a, out vec2 b, out mat2 gradientB, out float t) {
    vec2 uv = p.top / tile;
    if (translated) {
        float choice = variation * 8.0, index = floor(choice);
        a = uv + materialOffset(vec2(index, 0.0));
        b = uv + materialOffset(vec2(index + 1.0, 0.0));
        gradientB = p.topGradient / tile;
        t = smoothstep(.2, .8, fract(choice));
    } else {
        a = uv;
        b = TURN * uv / 2.37 + .31;
        gradientB = TURN * p.topGradient / (tile * 2.37);
        t = oneRepeat ? 0.0 : variation;
    }
}

// Authored packed layers share translated coordinates for colour/height and normal XY/roughness/AO.
// Keep the reads separate so height competition can reject a layer before its surface data is fetched.
struct PackedTopCoordinates { vec2 a; vec2 b; mat2 gradient; float blend; };

PackedTopCoordinates packedTopCoordinates(MaterialProjection p, float tile, float variation) {
    PackedTopCoordinates uv;
    topCoordinates(p, tile, variation, true, uv.a, uv.b, uv.gradient, uv.blend);
    return uv;
}

vec4 packedTopColor(float layer, PackedTopCoordinates uv) {
    return mixedTexel(u_terrainLayers, layer * .5, uv.a, uv.gradient, uv.b, uv.gradient, uv.blend);
}

// The packed layer's finish from its colour's reads.
Finish packedTopFinish(float layer, PackedTopCoordinates uv) {
    if (uv.blend <= 0.0) return finishTexel(layer, uv.a, uv.gradient);
    if (uv.blend >= 1.0) return finishTexel(layer, uv.b, uv.gradient);
    return mixFinish(finishTexel(layer, uv.a, uv.gradient), finishTexel(layer, uv.b, uv.gradient), uv.blend);
}

// Returns RMS roughness and AO. Numeric maps remain necessary when normal maps are off.
vec2 packedTopSurface(float layer, PackedTopCoordinates uv, out vec3 normal) {
    // Without the read whose weight is exactly zero: the other stands for both.
    vec4 a = uv.blend < 1.0 ? materialTexel(u_terrainData, layer * .5, uv.a, uv.gradient) : vec4(0.0);
    vec4 b = uv.blend > 0.0 ? materialTexel(u_terrainData, layer * .5, uv.b, uv.gradient) : a;
    if (uv.blend >= 1.0) a = b;
    normal = vec3(0.0, 0.0, 1.0);
    if (u_normalMaps > .5) {
        vec2 xyA = a.rg * 2.0 - 1.0, xyB = b.rg * 2.0 - 1.0;
        vec3 nA = normalize(vec3(xyA, sqrt(max(1.0 - dot(xyA, xyA), .001))));
        vec3 nB = normalize(vec3(xyB, sqrt(max(1.0 - dot(xyB, xyB), .001))));
        normal = normalize(mix(nA, nB, uv.blend));
    }
    return vec2(sqrt(mix(a.b * a.b, b.b * b.b, uv.blend)), mix(a.a, b.a, uv.blend));
}

// The repeat a role's maps are read at. A pixel that skips a read (a role or cover it does not show) still computes the
// read's coordinates beside the quad's pixels that take it, from a repeat that is 0 when chosen in a branch the pixel
// skipped. That result is discarded, but llvmpipe's anisotropic filtering differences all four quad pixels'
// coordinates, even for textureGrad, so the infinite one turns its neighbours' samples into NaN: dotted black lines
// along the roles' boundaries. Authored repeats are metres; only such skipped pixels change.
float sampledTile(float tile) { return max(tile, 1e-4); }

// The role's height at its colour's coordinates (rockTexel), without the far reads, which are flat.
float rockHeight(float layer, float tile, MaterialProjection p, bool sides, float variation, bool translated) {
    if (!sides) {
        vec2 a, b;
        mat2 gradientB;
        float t;
        topCoordinates(p, tile, variation, translated, a, b, gradientB, t);
        return mixedHeight(layer, a, p.topGradient / tile, b, gradientB, t);
    }
    float height = mix(heightTexel(layer, p.y / tile + .37, p.yGradient / tile),
          heightTexel(layer, p.x / tile, p.xGradient / tile), p.side);
    if (p.lying > 0.0) height = mix(height, heightTexel(layer, p.top / tile, p.topGradient / tile), p.lying);
    return height;
}

// The projection a role's reads take (rockTexel, rockNormal): moved by its parallax (relieved).
MaterialProjection relievedProjection(float layer, float tile, MaterialProjection p, bool sides, float variation,
      bool translated) {
#ifdef mediumQualityFlag
    return p;
#endif
    tile = sampledTile(tile);
    vec2 top, x, y;
    if (!reliefReach(layer, tile, p, !sides || p.lying > 0.0, sides, top, x, y)) return p;
    return relieved(p, top, x, y, rockHeight(layer, tile, p, sides, variation, translated));
}

// Sides: one read per projection, the two side projections always and the top only where the face lies back.
// Tops (no sides): the top projection's two reads.
// The role's authored surface comes from the same coordinates: roughness, metallic and scattering from its map, whose
// mips widen the roughness by the relief's spread (GpuAssets.mipmaps), and emission from its map. One call reads them
// all with the colour.
vec4 rockTexel(float layer, float tile, MaterialProjection p, bool sides, float variation, bool translated,
      out Finish surface) {
    tile = sampledTile(tile);
    int material = int(layer + .5);
    vec4 authored = u_finishSurface[material], glow = u_finishEmission[material];
#ifdef mediumQualityFlag
    bool grain = false; // Its mean finish (authored.rgb).
#else
    bool grain = !terrainCovered && authored.a >= 0.0;
#endif
    bool emits = !terrainCovered && glow.a >= 0.0;
    vec3 mapped = authored.rgb, emitted = vec3(0.0);
    vec4 pigment;
    if (!sides) {
        vec2 a, b;
        mat2 gradientB;
        float t;
        topCoordinates(p, tile, variation, translated, a, b, gradientB, t);
        pigment = mixedTexel(u_terrainLayers, layer, a, p.topGradient / tile, b, gradientB, t);
        if (grain) mapped = mixedTexel(FINISH_LAYERS, authored.a, a, p.topGradient / tile, b, gradientB, t).rgb;
        if (emits) emitted = mixedTexel(FINISH_LAYERS, glow.a, a, p.topGradient / tile, b, gradientB, t).rgb;
    } else {
        pigment = mix(materialTexel(u_terrainLayers, layer, p.y / tile + .37, p.yGradient / tile),
              materialTexel(u_terrainLayers, layer, p.x / tile, p.xGradient / tile), p.side);
        if (grain) {
            mapped = mix(materialTexel(FINISH_LAYERS, authored.a, p.y / tile + .37, p.yGradient / tile).rgb,
                  materialTexel(FINISH_LAYERS, authored.a, p.x / tile, p.xGradient / tile).rgb, p.side);
        }
        if (emits) {
            emitted = mix(materialTexel(FINISH_LAYERS, glow.a, p.y / tile + .37, p.yGradient / tile).rgb,
                  materialTexel(FINISH_LAYERS, glow.a, p.x / tile, p.xGradient / tile).rgb, p.side);
        }
        if (p.lying > 0.0) {
            pigment = mix(pigment, materialTexel(u_terrainLayers, layer, p.top / tile, p.topGradient / tile), p.lying);
            if (grain) {
                mapped = mix(mapped, materialTexel(FINISH_LAYERS, authored.a, p.top / tile, p.topGradient / tile).rgb,
                      p.lying);
            }
            if (emits) {
                emitted = mix(emitted, materialTexel(FINISH_LAYERS, glow.a, p.top / tile, p.topGradient / tile).rgb,
                      p.lying);
            }
        }
    }
    // Emission layers hold sRGB bytes (finish-arrays.glsl).
    surface = finishOf(mapped, u_finishGlow[material] + glow.rgb * toLinear(emitted));
    return pigment;
}

// A second role's reads (cliffMaterial): one per projection at the base repeat, without parallax, from one of its layers
// in maps.
// Its finish and emission maps take the same reads (as rockTexel).
// Its normal is rockNormal's at the same single scale (t 0), also without parallax.
vec4 secondTexel(sampler2DArray maps, float layer, float tile, MaterialProjection p, bool sides) {
    tile = sampledTile(tile);
    vec4 top = !sides || p.lying > 0.0 ? materialTexel(maps, layer, p.top / tile, p.topGradient / tile) : vec4(0.0);
    if (!sides) return top;
    vec4 pigment = mix(materialTexel(maps, layer, p.y / tile + .37, p.yGradient / tile),
          materialTexel(maps, layer, p.x / tile, p.xGradient / tile), p.side);
    return p.lying > 0.0 ? mix(pigment, top, p.lying) : pigment;
}

// The matching world normal and cavity from the normal/AO array; a top uses the same two reads as its colour. The
// normal's length is its relief: the agreement of the normals under the pixel (normalSpread).
vec4 rockNormal(float layer, float tile, MaterialProjection p, vec3 face, bool sides, float variation,
      bool translated) {
    tile = sampledTile(tile);
    if (u_normalMaps <= .5 || (terrainCovered && pixelMetres <= CLOSE_PIXEL)) return vec4(face, 1.0);
    vec3 normal;
    float cavity, spread;
    if (!sides) {
        vec2 a, b;
        mat2 gradientB;
        float t;
        topCoordinates(p, tile, variation, translated, a, b, gradientB, t);
#ifdef mediumQualityFlag
        if (!translated) t = 0.0;
#endif
        vec4 n = t < 1.0 ? materialTexel(u_terrainData, layer, a, p.topGradient / tile) : vec4(0.0);
        vec4 m = t > 0.0 ? materialTexel(u_terrainData, layer, b, gradientB) : n;
        if (t >= 1.0) n = m;
        // An enlarged repeat's slopes are 2.37 times shallower (planarNormal): its spread 2.37^2 times smaller.
        spread = (1.0 - t) * (1.0 - t) * normalSpread(n) + t * t * (translated ? 1.0 : .178) * normalSpread(m);
        if (terrainCovered) return vec4(face * (1.0 - spread), 1.0);
        if (translated) {
            n = mix(n, m, t);
            normal = upNormal(n.rgb * 2.0 - 1.0, face);
            cavity = n.a;
        } else {
            // The enlarged repeat carries the broad fractures and plates: its relief must stay.
            vec4 relief = planarNormal(n, m, t);
            normal = upNormal(relief.rgb, face);
            cavity = relief.a;
        }
    } else {
        vec4 nx = materialTexel(u_terrainData, layer, p.x / tile, p.xGradient / tile);
        vec4 ny = materialTexel(u_terrainData, layer, p.y / tile + .37, p.yGradient / tile);
        normal = !terrainCovered ? wallNormal(nx, ny, face, p.side) : face;
        cavity = !terrainCovered ? mix(ny.a, nx.a, p.side) : 1.0;
        spread = p.side * p.side * normalSpread(nx) + (1.0 - p.side) * (1.0 - p.side) * normalSpread(ny);
        if (p.lying > 0.0) {
            vec4 t = materialTexel(u_terrainData, layer, p.top / tile, p.topGradient / tile);
            if (!terrainCovered) {
                normal = normalize(mix(normal, upNormal(t.rgb * 2.0 - 1.0, face), p.lying));
                cavity = mix(cavity, t.a, p.lying);
            }
            spread = (1.0 - p.lying) * (1.0 - p.lying) * spread + p.lying * p.lying * normalSpread(t);
        }
    }
    return vec4(normalize(normal) * (1.0 - spread), mix(.55, 1.0, cavity));
}

// Families whose river banks expose earth and gravel: snow and dune sand must not become the submerged bed.
bool soilBankFamily(float familyId) {
    return familyId < 2.5 || isFamily(familyId, 5.0) || isFamily(familyId, TROPICAL_FAMILY);
}

// A light film of the local hardpan, ash or fines left on exposed level stone.
float familyDust(float familyId) {
    if (isFamily(familyId, DESERT_FAMILY) || isFamily(familyId, MARS_FAMILY)
          || isFamily(familyId, VOLCANO_FAMILY)) {
        return .24;
    }
    if (isFamily(familyId, 1.0)) return .18;
    if (isFamily(familyId, 3.0) || isFamily(familyId, LUNAR_FAMILY)) return .12;
    return 0.0;
}

// Keep the normal direction and filtered relief length separate when the cover spans ground and floor maps.
vec4 mixRoleNormal(vec4 a, vec4 b, float t) {
    float la = length(a.rgb), lb = length(b.rgb);
    vec3 n = normalize(mix(a.rgb / max(la, 1e-5), b.rgb / max(lb, 1e-5), t));
    return vec4(n * mix(la, lb, t), mix(a.a, b.a, t));
}

Finish secondFinish(float layer, float tile, MaterialProjection p, bool sides) {
    if (terrainCovered) return Finish(.9, 0.0, 0.0, vec3(0.0));
    int material = int(layer + .5);
    vec4 authored = u_finishSurface[material], e = u_finishEmission[material];
    vec3 mapped = authored.a >= 0.0 ? secondTexel(FINISH_LAYERS, authored.a, tile, p, sides).rgb : authored.rgb;
    vec3 emitted = e.a >= 0.0 ? toLinear(secondTexel(FINISH_LAYERS, e.a, tile, p, sides).rgb) : vec3(0.0);
    return finishOf(mapped, u_finishGlow[material] + e.rgb * emitted);
}

// The projection's gradients must come from before the caller's varying branches; the noise reads use explicit LOD.
TerrainMaterial cliffMaterialOf(MaterialProjection p, vec3 world, vec3 face, float aboveFoot, float belowRim,
      float rock, float hardness, float broad, float fine, float region, float sediment, float familyId,
      vec4 tiles, vec4 layers, bool sides, bool single, vec2 floorMaterial, float floorMix) {
    // The shared cliff's joints stand upright: level bedrock of its families shows the rock tops' map instead.
    if (!sides && abs(layers.z - u_flatRock.x) < .5) {
        layers.z = u_flatRock.y;
        tiles.z = u_flatRock.z;
    }
    // Desert/Mars depth-zero bars blend the original soft deposit edge halfway toward the broader shore deposit.
    if (shallowSediment >= 0.0 && (isFamily(familyId, DESERT_FAMILY) || isFamily(familyId, MARS_FAMILY))) {
        sediment = shallowSediment;
    }
    // Shallow grassy coasts keep the adjoining turf across more of the exposed bank. The wet toe and submerged
    // bed still reach full sediment; the existing world-space variation breaks up the advancing green edge.
    if (shallowSediment >= 0.0 && (isFamily(familyId, 0.0) || isFamily(familyId, TROPICAL_FAMILY))) {
        sediment = sediment * sediment * sediment;
    }
    vec4 ramp, trim;
    vec3 kind;
    cliffFamily(familyId, ramp, trim, kind);
    bool fungus = isFamily(familyId, FUNGUS_FAMILY);
#ifdef mediumQualityFlag
    oneRepeat = !isFamily(familyId, DESERT_FAMILY);
#endif
    float up = clamp(face.z, 0.0, 1.0);
    float pockets = textureLod(u_surfaceNoise, rainNoiseAt((world.xy + world.z * vec2(.43, .27)) / 92.0 + .57), 0.0).g;
    float variation = broad * .4 + fine * .25 + pockets * .35;
    float foot = exp(-max(aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    // Banks (rock 0) keep their mantle; rock cliffs (rock 1) expose the wall; a contact breaks into patches.
    float exposure = clamp(rock + (variation - .5) * 1.2 * (4.0 * rock * (1.0 - rock)), 0.0, 1.0);
    // Exposed shoulders lose loose cover at the rim; sheltered patches keep it. A bank's mantle wears open.
    float rim = 0.0, scour = 0.0, shoulder = 0.0;
    IF_MEDIUM(belowRim < 7.0) {
        rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, belowRim);
        scour = (1.0 - smoothstep(.1, 3.0 + 4.0 * broad, belowRim)) * exposure
              * smoothstep(.30, .65, broad * .6 + pockets * .4);
        shoulder = (1.0 - smoothstep(.1, 1.3 + 1.6 * broad, belowRim)) * smoothstep(.28, .65, pockets)
              * smoothstep(.55, .94, up);
    }
    float coverUp = up;
    IF_MEDIUM(up < .985) {
        float bank = (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up));
        coverUp -= .2 * bank * smoothstep(.32, .68, fine * .65 + pockets * .35);
    }
    float thin = smoothstep(.50, .72, region * .6 + broad * .3 + fine * .1) * smoothstep(.82, .97, up)
          * (1.0 - deposit) * (1.0 - .75 * foot);
    float cover = smoothstep(ramp.x, ramp.y, coverUp + (variation - .5) * ramp.z + deposit * ramp.w);
    cover *= 1.0 - max(trim.x * scour, max(trim.y * thin * mix(1.0, rim * exposure, trim.z), trim.w * shoulder));
    if (isFamily(familyId, 5.0) && sides) {
        // Snow caps a bank's crest as a ragged cornice; rock breaks out only below it. Rock cliffs keep their edge.
        float lip = mix(.5, 2.2, fine * .4 + pockets * .6);
        cover = max(cover, (1.0 - smoothstep(lip * .4, lip, belowRim)) * (1.0 - exposure));
    }
    float sod = 0.0;
    if (kind.z > 0.0) {
        // A meadow's two-level bank keeps a ragged turf lip and broken turf on its intermediate shoulder.
        float levelMetres = u_levelHeight / u_metre;
        float twoLevels = 1.0 - smoothstep(.05, .3, abs((aboveFoot + belowRim) / levelMetres - 2.0));
        IF_MEDIUM(twoLevels > 0.0) {
            float fray = textureLod(u_surfaceNoise, rainNoiseAt((world.xy + world.z * vec2(.29, .41)) / 28.0 + .217), 0.0).r;
            float lipDepth = levelMetres * (mix(.2, .65, fine * .45 + pockets * .55) + .14 * (fray - .5));
            float lip = 1.0 - smoothstep(.05, lipDepth, belowRim);
            float middle = abs(aboveFoot / levelMetres - 1.0 - (pockets - .5) * .18);
            float width = mix(.1, .24, fine) * mix(.55, 1.45, fray);
            float turf = (1.0 - smoothstep(width * .25, width, middle)) * smoothstep(.35, .73, up)
                  * smoothstep(.22, .55, pockets * .7 + fine * .3);
            cover = max(cover, max(lip, turf) * twoLevels * (1.0 - exposure));
            sod = rootMat(belowRim, lipDepth, fray) * twoLevels * (1.0 - exposure) * (1.0 - smoothstep(.85, .97, up));
        }
        deposit *= mix(.25, 1.0, rock);
    }
    // Organic weathering opens a meadow's level cover onto its soil and thins fungal crust.
    float aged = smoothstep(.45, .68, broad * .5 + region * .35 + fine * .15) * smoothstep(.88, .98, up)
          * (1.0 - sediment);
    float worn = kind.z > 0.0 ? cover * aged * .48 : 0.0;
    cover -= worn;
    float mantle = 0.0;
    if (kind.x > .5 && kind.x < 1.5) {
        mantle = (1.0 - cover) * ((1.0 - exposure) * .9 + rim * .5 * smoothstep(.25, .7, variation));
    } else if (kind.x > 1.5 && kind.x < 2.5) {
        mantle = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    } else if (kind.x > 2.5) {
        mantle = (1.0 - cover) * max(smoothstep(.82, .97, up), rim * smoothstep(.25, .90, up));
    }
    mantle = max(mantle, worn);
    deposit *= kind.y;
    if (fungus) {
        // Mauve lowlands; cyan crust colonies above level zero and along raised rims.
        float levelMetres = u_levelHeight / u_metre;
        float rise = smoothstep(.25, .9, (aboveFoot + belowRim) / levelMetres);
        float raised = smoothstep(.15, 1.3, v_cloudPosition.z / u_levelHeight);
        float lip = 1.0 - smoothstep(.1, 1.4 + 1.6 * pockets, belowRim);
        float growth = max(raised, lip * rise);
        float colonies = smoothstep(.28, .69, region * .5 + broad * .3 + pockets * .2);
        deposit = max(deposit, smoothstep(.48, .88, up) * mix(.10 * colonies, .28 + .70 * colonies, growth)
              * (1.0 - aged * .55));
        cover = smoothstep(.76, .97, up) * (1.0 - .90 * thin * (1.0 - growth));
        mantle = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    }
#ifdef sublevelFloorFlag
    // Sediment half fills a sunken mineral floor: broad bare patches shrink to plates, soil and stones breaking through.
    // Fungal felt grows over its weathering instead.
    float settled = fungus ? 0.0 : floorMix * smoothstep(.82, .97, up);
    cover = mix(cover, 1.0, .5 * settled);
    deposit *= 1.0 - .5 * settled;
#endif
    float capPlates = step(2.5, kind.x);
    vec4 roles = vec4(cover, clamp(mantle, 0.0, 1.0 - cover), deposit, 0.0);
    roles.xy *= 1.0 - deposit;
    roles.w = max(0.0, 1.0 - roles.x - roles.y - roles.z);
    // Concrete carried onto another surface is loose aggregate, never a second paved slab.
    if (isFamily(familyId, 4.0)) roles = vec4(0.0, 0.0, 1.0, 0.0);
    // Ordinary river shores expose earth and gravel before reaching water. Reassign the existing mantle/deposit
    // slots, rather than sampling an extra layer; snow and dune sand must not become the submerged bed.
    bool soilBank = riverBank && soilBankFamily(familyId);
    float coverEnd = isFamily(familyId, 0.0) || isFamily(familyId, TROPICAL_FAMILY) ? .6 : .9;
    float bedRock = rock * (1.0 - smoothstep(.2, .8, up)) * smoothstep(.05, 2.2, aboveFoot);
    float bankBlend = 0.0;
    if (soilBank) {
        // Keep the original surface at the dry edge. Empty the two reused slots before changing their maps,
        // then feather native cover into soil and gravel; replacing live slots makes a hard material seam.
        float bankRock = bedRock > 0.0 ? roles.w : 0.0;
        roles = mix(roles, vec4(1.0 - bankRock, 0.0, 0.0, bankRock), smoothstep(0.0, .03, sediment));
        bankBlend = clamp((sediment - .03) / .97, 0.0, 1.0);
        if (bankBlend > 0.0) {
            layers.w = u_bankMaterials.x; tiles.w = u_bankMaterials.y;
            layers.y = u_bankMaterials.z; tiles.y = u_bankMaterials.w;
            // Grass gives way to soil earlier; gravel still starts beside the water. Keep only two roles on earth banks.
            // Rock cliffs retain their existing strongest-two-role treatment.
            roles = mix(roles, vec4(0.0, 1.0 - bedRock, 0.0, bedRock), clamp(bankBlend / coverEnd, 0.0, 1.0));
            roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), clamp((bankBlend - .9) / .1, 0.0, 1.0));
        }
    } else {
        roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), sediment);
    }
    // The two strongest roles only (the first of equals); weaker ones cannot show through a height blend.
    float first = max(max(roles.x, roles.y), max(roles.z, roles.w));
    vec4 isFirst = step(vec4(first), roles);
    isFirst *= 1.0 - step(.5, vec4(0.0, isFirst.x, isFirst.x + isFirst.y, isFirst.x + isFirst.y + isFirst.z));
    // Exposed level stone keeps a film of its cover: a dusty top always samples the cover as one of its two roles.
    float dust = single ? 0.0 : familyDust(familyId) * smoothstep(.88, .98, up) * (1.0 - sediment);
    if (soilBank) dust *= 1.0 - clamp(bankBlend / coverEnd, 0.0, 1.0);
    vec4 rest = roles * (1.0 - isFirst);
    if (dust > 0.0) rest.x += 2.0 * (1.0 - isFirst.x);
    float pick = max(max(rest.x, rest.y), max(rest.z, rest.w));
    vec4 isSecond = step(vec4(pick), rest) * (1.0 - isFirst);
    isSecond *= 1.0 - step(.5, vec4(0.0, isSecond.x, isSecond.x + isSecond.y, isSecond.x + isSecond.y + isSecond.z));
    float second = dot(isSecond, roles);
    // Roles in layer order: cover is the ground layer, mantle the mantle, deposit the debris, rock the wall.
    // Tops: cover and rock vary over broad regions, the deposit finely, a mantle finely (cap plates broadly).
    // Sand covers, the desert's and volcano's cap plates and fungus take translated reads of one repeat.
    float plates = isFamily(familyId, DESERT_FAMILY) || isFamily(familyId, VOLCANO_FAMILY) ? 1.0 : 0.0;
    vec4 variations = vec4(broad, mix(fine, broad, plates), fine, broad);
    vec4 translated = fungus ? vec4(1.0) : vec4(isFamily(familyId, 2.0) ? 1.0 : 0.0, plates, 0.0, 0.0);
    float layerA = dot(isFirst, vec4(layers.x, layers.w, layers.y, layers.z)) * .5;
    float tileA = dot(isFirst, vec4(tiles.x, tiles.w, tiles.y, tiles.z));
    float variationA = dot(isFirst, variations);
    bool translatedA = dot(isFirst, translated) > .5;
    Finish surfaceA, surfaceB;
    MaterialProjection pa = relievedProjection(layerA, tileA, p, sides, variationA, translatedA);
    vec4 a = rockTexel(layerA, tileA, pa, sides, variationA, translatedA, surfaceA);
    vec4 na = rockNormal(layerA, tileA, pa, face, sides, variationA, translatedA);
#ifdef sublevelFloorFlag
    if (floorMix > 0.0 && floorMix < 1.0 && isFirst.x > .5) {
        MaterialProjection pf = relievedProjection(floorMaterial.x * .5, floorMaterial.y, p, sides,
              variationA, translatedA);
        Finish sf;
        vec4 f = rockTexel(floorMaterial.x * .5, floorMaterial.y, pf, sides, variationA, translatedA, sf);
        float t = floorRoleWeight(a.a, f.a, floorMix);
        a = mix(a, f, t);
        surfaceA = mixFinish(surfaceA, sf, t);
        vec4 nf = rockNormal(floorMaterial.x * .5, floorMaterial.y, pf, face, sides, variationA, translatedA);
        na = mixRoleNormal(na, nf, t);
    }
#endif
    vec4 b = a;
    surfaceB = surfaceA;
    float share = 0.0;
    float layerB = layerA, tileB = tileA;
    float floorB = 0.0;
    // Height blend (materialWeights): where both are wanted, the layer whose relief stands higher shows.
    float wa = first + a.a * .38 * 4.0 * first * (1.0 - first);
    float width = materialBlendWidth();
    // The second role shows only where its weight at full relief (b.a 1) comes within the blend's width of the first's;
    // elsewhere its share stays 0 and its reads change nothing, unless the bank fade or the dust film set the share. The
    // 1e-4 margin covers rounding.
    bool reaches = bankBlend > 0.0 || dust > 0.0 || second + .38 * 4.0 * second * (1.0 - second) > wa - width - 1e-4;
    if ((!single || bankBlend > 0.0) && (second > .02 || dust > 0.0) && reaches) {
        layerB = dot(isSecond, vec4(layers.x, layers.w, layers.y, layers.z)) * .5;
        tileB = dot(isSecond, vec4(tiles.x, tiles.w, tiles.y, tiles.z));
        b = secondTexel(u_terrainLayers, layerB, tileB, p, sides);
#ifdef sublevelFloorFlag
        if (floorMix > 0.0 && floorMix < 1.0 && isSecond.x > .5) {
            vec4 f = secondTexel(u_terrainLayers, floorMaterial.x * .5, floorMaterial.y, p, sides);
            floorB = floorRoleWeight(b.a, f.a, floorMix);
            b = mix(b, f, floorB);
        }
#endif
        float wb = second + b.a * .38 * 4.0 * second * (1.0 - second);
        float ka = max(wa - max(wa, wb) + width, 0.0), kb = max(wb - max(wa, wb) + width, 0.0);
        share = kb / max(ka + kb, .00001);
        // The dry bank needs a visible fade, including blended-cover draws that normally pick one role.
        if (bankBlend > 0.0) {
            float feather = smoothstep(0.0, .03, bankBlend);
            share = mix(share, second / max(first + second, .00001), feather);
            if (single) share *= feather;
        }
        // The film: a share of the stone's weight goes to the cover, after the height blend.
        if (dust > 0.0) share = isFirst.x > .5 ? share * (1.0 - dust) : share + dust * (1.0 - share);
    }
    vec4 nb = na;
#ifdef mediumQualityFlag
    // The first role's normal, and the second's mean finish.
    if (share > 0.0) surfaceB = meanFinish(int(layerB + .5));
#else
    if (share > 0.0) {
        surfaceB = secondFinish(layerB, tileB, p, sides);
        nb = rockNormal(layerB, tileB, p, face, sides, 0.0, false);
#ifdef sublevelFloorFlag
        if (floorB > 0.0) {
            surfaceB = mixFinish(surfaceB, secondFinish(floorMaterial.x * .5, floorMaterial.y, p, sides), floorB);
            nb = mixRoleNormal(nb, rockNormal(floorMaterial.x * .5, floorMaterial.y, p, face, sides, 0.0, false), floorB);
        }
#endif
    }
#endif
    vec4 roleShare = isFirst * (1.0 - share) + isSecond * share;
    TerrainMaterial result;
    result.color = vec3(0.0);
    if (!terrainCovered) {
        result.color = mix(a.rgb, b.rgb, share);
        // Strata: harder beds weather paler and slightly cooler than the softer beds between them.
        if (sides) {
            float rockShare = roleShare.w + roleShare.y * capPlates;
            result.color *= mix(vec3(1.0), mix(vec3(.93, .92, .91), vec3(1.04, 1.04, 1.05), hardness), rockShare);
        }
        if (isFamily(familyId, DESERT_FAMILY)) {
            // Pale abraded sandstone at the actual rim reads from above; deeper faces keep their warm brown.
            float abrasion = (1.0 - smoothstep(.15, 2.2, belowRim)) * smoothstep(.45, .95, up);
            vec3 weathering = mix(vec3(.98, .91, .85), vec3(1.14, 1.15, 1.13), abrasion);
            result.color *= mix(vec3(1.0), authoredPigmentGain(weathering), roleShare.w);
        } else if (sides && !isFamily(familyId, LUNAR_FAMILY)
              && !isFamily(familyId, FUNGUS_FAMILY)
              && !isFamily(familyId, MARS_FAMILY)) {
            // A wall weathers by height, over its own height: sun and wind bleach its rim, water leaving the top streaks
            // the face down from it, and grime and moisture darken its lower part towards the foot. Desert sandstone keeps
            // its abraded rim above; airless and alien walls neither.
            float faceHeight = max(aboveFoot + belowRim, 1.0);
            float low = (1.0 - smoothstep(0.0, .45, max(aboveFoot, 0.0) / faceHeight)) * (.6 + .4 * variation);
            float bleach = (1.0 - smoothstep(.3, 3.0 + 3.0 * broad, belowRim)) * (1.0 - .5 * up);
            // The streaks are upright stripes about .6 m wide and 6 m long in the side projections' frames, blended as the
            // rock's own maps are (p.side), with explicit gradients: they hold still on curved faces and fade with distance.
            vec2 stretch = vec2(1.0 / 40.0, 1.0 / 400.0);
            float streak = mix(
                  textureGrad(u_surfaceNoise, rainNoiseAt(p.y * stretch + .41), (p.yGradient[0] * stretch) * RAIN_NOISE_SCALE, (p.yGradient[1] * stretch) * RAIN_NOISE_SCALE).r,
                  textureGrad(u_surfaceNoise, rainNoiseAt(p.x * stretch + .41), (p.xGradient[0] * stretch) * RAIN_NOISE_SCALE, (p.xGradient[1] * stretch) * RAIN_NOISE_SCALE).r,
                  p.side);
            // Upright faces only: a sloping shoulder would stretch them sideways.
            float streaks = smoothstep(.5, .8, streak) * (1.0 - smoothstep(0.0, .6 * faceHeight + 4.0, max(belowRim, 0.0)))
                  * (1.0 - smoothstep(.3, .7, up));
            vec3 weathering = mix(vec3(1.0), vec3(1.10, 1.10, 1.09), bleach) * mix(vec3(1.0), vec3(.84, .84, .86), streaks)
                  * mix(vec3(1.0), vec3(.84, .85, .88), low);
            result.color *= mix(vec3(1.0), authoredPigmentGain(weathering), roleShare.w);
        }
        if (isFamily(familyId, 2.0)) {
            float mineral = dot(result.color, vec3(.2126, .7152, .0722));
            result.color = mix(result.color, mineral * toLinear(vec3(.86, .92, .98)), greySand());
        }
        if (roleShare.x > 0.0) {
            bvec4 tone = bvec4(isFamily(familyId, 2.0), isFamily(familyId, DESERT_FAMILY),
                  isFamily(familyId, MARS_FAMILY), isFamily(familyId, 0.0));
            result.color = mix(result.color, groundToneOf(tone, result.color, world, broad, fine, region, rim, foot),
                  roleShare.x);
        }
        result.color *= mix(vec3(1.0), ROOT_MAT, sod * (1.0 - roleShare.x));
    }
    result.normal = normalize(mix(na.rgb, nb.rgb, share));
    result.cavity = mix(na.a, nb.a, share);
    result.relief = mix(length(na.rgb), length(nb.rgb), share);
    result.height = mix(a.a, b.a, share);
    result.finish = mixFinish(surfaceA, surfaceB, share);
    result.volcanic = 0.0;
#ifdef mediumQualityFlag
    oneRepeat = false; // Other materials read both repeats.
#endif
    return result;
}

// Select the underground cover once. Other roles and weathering are evaluated once. Partial floors blend the cover
// during height competition instead of blending two finished surfaces; pure ground/floor endpoints keep the same path.
TerrainMaterial cliffMaterial(MaterialProjection p, vec3 world, vec3 face, float aboveFoot, float belowRim,
      float rock, float hardness, float broad, float fine, float region, float sediment, vec4 palette, int slot,
      vec4 tiles, vec4 layers, bool sides, bool single) {
    float familyId = palette[slot];
    vec2 floorMaterial = vec2(-1.0);
    float share = 0.0;
#ifdef sublevelFloorFlag
    share = floorShare(world, face, familyId, floorMaterial);
    if (share >= 1.0) {
        tiles.x = floorMaterial.y;
        layers.x = floorMaterial.x;
    }
#endif
    return cliffMaterialOf(p, world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region, sediment,
          familyId, tiles, layers, sides, single, floorMaterial, share);
}

#ifdef terrainBlendFlag
vec4 coverTilesAt(int i) {
    return i == 0 ? u_coverTiles0 : i == 1 ? u_coverTiles1 : i == 2 ? u_coverTiles2 : u_coverTiles3;
}
vec4 coverLayersAt(int i) {
    return i == 0 ? u_coverLayers0 : i == 1 ? u_coverLayers1 : i == 2 ? u_coverLayers2 : u_coverLayers3;
}

// BoardSurfaceBlend.footTongue/footReach per pixel: the ground below a bank climbs it in tongues. The bank's vertices carry
// that ground's share of their cover uncut (v_coverFoot), and each pixel cuts it back by its height above the foot, so
// the tongues keep their shape at any mesh density.
vec4 footCover(vec4 raw, vec3 world, float aboveFoot) {
    vec4 foot = clamp(v_coverFoot, vec4(0.0), raw);
    if (dot(foot, vec4(1.0)) <= 0.0) return raw;
    float tongue = .7 * biomeNoise(world.xy / 5.3 + vec2(37.0, -23.0)) + .3 * biomeNoise(world.xy / 1.7 + vec2(-11.0, 29.0));
    vec4 kept = raw - foot * smoothstep(0.0, 1.0, max(aboveFoot, 0.0) / (FOOT_METRES * (.2 + .8 * tongue)));
    return kept / max(dot(kept, vec4(1.0)), .00001);
}

// A blended cliff: its two strongest palette slots, each as one cliff material, meeting in broken patches.
TerrainMaterial blendCliff(MaterialProjection p, vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,
      float hardness, float broad, float fine, float region, float sediment, out vec4 weights) {
    vec4 raw = footCover(max(v_coverWeights, vec4(0.0)), world, aboveFoot);
    int first = 0;
    for (int i = 1; i < 4; i++) if (raw[i] > raw[first]) first = i;
    int second = first == 0 ? 1 : 0;
    for (int i = 0; i < 4; i++) if (i != first && raw[i] > raw[second]) second = i;
    float share = raw[second] / max(raw[first] + raw[second], .00001);
    if (coverFamilies[second] == coverFamilies[first]) share = 0.0;
    TerrainMaterial a = cliffMaterial(p, world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region,
          sediment, coverFamilies, first, coverTilesAt(first), coverLayersAt(first), true, false);
    weights = vec4(0.0);
    weights[first] = 1.0;
    if (share < .02) return a;
#ifdef mediumQualityFlag
    TerrainMaterial b = riverBank && soilBankFamily(coverFamilies[second]) ? coverLayer(a, p, u_bankMaterials.x, u_bankMaterials.y, true)
          : rock > .5 ? coverLayer(a, p, coverLayersAt(second).z, coverTilesAt(second).z, true)
          : coverLayer(a, p, coverLayersAt(second).w, coverTilesAt(second).w, true);
#else
    TerrainMaterial b = cliffMaterial(p, world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region,
          sediment, coverFamilies, second, coverTilesAt(second), coverLayersAt(second), true, false);
#endif
    float patches = textureLod(u_surfaceNoise, rainNoiseAt((world.xy + world.z * vec2(.47, .29)) / 9.5), 0.0).b;
    // Neighbouring families interlock, the one standing higher in its own relief showing: snow and dust fill the
    // hollows of a bank's soil. They soften as on level ground (coverGradient), so a bank and the ground at its foot
    // and crest meet in the same faded contact; an authored mixture mixes in proportion.
    float t = smoothstep(.4, .6, share + (b.height - a.height) * .45 + (patches - .5) * .3);
    t = mix(t, share, coverGradient(raw / max(dot(raw, vec4(1.0)), .00001)));
    weights[first] = 1.0 - t;
    weights[second] += t;
    a.color = mix(a.color, b.color, t);
    a.normal = normalize(mix(a.normal, b.normal, t));
    a.cavity = mix(a.cavity, b.cavity, t);
    a.relief = mix(a.relief, b.relief, t);
    a.height = mix(a.height, b.height, t);
    a.finish = mixFinish(a.finish, b.finish, t);
    return a;
}
#endif
