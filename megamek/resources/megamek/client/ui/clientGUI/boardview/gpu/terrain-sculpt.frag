#version 330 core
#define SCORCH_IN_MATERIAL
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Sculpted tops, cliffs and the rock kit. Geometry supplies the landforms; this shader layers each surface family's
// four materials (ground, debris, wall, mantle), mapped in world space, and lights them with the board's one light
// model (shadeSurface, surface-shading.glsl): linear albedo times linear light. Scene radiance stays linear
// through blending and atmosphere; the final composite tones and encodes the whole frame once.
in vec2 v_diffuseUV;   // dry ground: rim / foot distance; water-tagged ground: foot / rim distance;
                            // drowned cliff: height above bed / foot distance;
                            // depth-zero water tags encode the second distance as -1 - distance;
                            // cliff: height above foot / depth below rim; rock: above root
                            // / below top. All in metres. Wet rock: above root / pigment variation.
                            // Plants: authored red / green.
in vec3 v_normal;
in vec4 v_color;       // r: occlusion (mud-draining shell: local drop / 8 m). g: game level (+64)/255, or for cliffs how much rock the face is (0
                            // bank, 1 cliff). b: kind (0 ground, .1 a natural bridge's muddy deck top
                            // (GpuTerrain.naturalBridge), .3 plant, .45 panel, .5 cliff (byte 140: mud-draining shell), .6 abyss wall, .75 tree pit,
                            // 1 rock). a: bed
                            // hardness (cliff), authored blue (plant), variation (rock, a pit's earth below .5; its kerb is 1),
                            // nearest step height (.3 + .1 per level, dry ground), or below .25 on a water hex's banks
                            // and bed its water's palette and that height packed (GpuTerrain.shoreTint).
                            // Panels use alpha for a fixed texture azimuth, independent of the lighting normal.
// Aligned colour/height and normal/AO arrays; semantic IDs are mapped by the sampling helpers.
uniform sampler2DArray u_terrainLayers;
uniform sampler2DArray u_terrainData;
// The finish layers follow its normal/AO layers (finish-arrays.glsl).
#define FINISH_LAYERS u_terrainData
uniform vec4 u_sculptLayers; // this family's colour/height layers: ground, debris, wall, mantle; normal/AO one up
uniform vec4 u_sculptTiles; // metres per repeat: ground, debris, wall, mantle
uniform vec2 u_treePitMaterial; // authored mulch colour layer and metres per repeat
uniform vec2 u_scorchedMaterial; // colour/height layer and metres per repeat; data packs normal XY, roughness, AO
uniform float u_metre;      // world units per metre
// How deep the ground's visible grain (its height map) stands, in metres, for the shadow lookup (sculptShadow).
const float RELIEF_METRES = .2;
// How far, in metres, what grows and lies along a natural shadow's edge frays it (sculptShadow).
const float FRAY_METRES = .25;
uniform float u_normalMaps;
uniform float u_terrainParallax; // the GPU tuning panel's Terrain Parallax switch (terrain-materials.glsl)
uniform vec4 u_materialCacheDebug; // draw-path tint; alpha is zero unless the session's Debug switch is on
uniform float u_groundResponse;
uniform float u_sculptFamily; // BoardScene.Surface: GRASS 0, DIRT 1, SAND 2, ROCK 3, CONCRETE 4, SNOW 5, LUNAR 6
// A draw's families are compile-time constants in its family program (GpuFamilyPrograms), so the compiler
// keeps only their paths; the generic program of the same flags reads the same values from the uniforms.
#ifdef SCULPT_FAMILY
#define sculptFamily SCULPT_FAMILY
#else
#define sculptFamily u_sculptFamily
#endif
uniform float u_levelHeight;
uniform vec3 u_wind;        // direction in xy, strength in z
uniform float u_waterEffects;
uniform float u_waterLine;  // world units the water surface lies below its hex's level
uniform float u_gravity;    // scenario acceleration; without it there is no water (GpuTerrain.waterVisible)
#ifdef terrainBlendFlag
in vec4 v_coverWeights;
in float v_coverInterpolation;
in vec4 v_coverFoot; // a bank's share of its cover from the ground below, before footCover cuts it by height
uniform vec4 u_coverFamilies;
#ifdef COVER_FAMILIES
const vec4 coverFamilyConstants = COVER_FAMILIES;
#define coverFamilies coverFamilyConstants
#else
#define coverFamilies u_coverFamilies
#endif
uniform vec4 u_coverResponses;
uniform vec4 u_coverTiles0, u_coverTiles1, u_coverTiles2, u_coverTiles3;
uniform vec4 u_coverLayers0, u_coverLayers1, u_coverLayers2, u_coverLayers3;
#endif
// The ground material cache (GpuMaterialCache): a fill program (materialFillFlag) writes the canonical material of a top
// as the close view shows it (the evaluator, lunar craters, a field's ground and ambient weathering) into world-aligned
// base tiles, whose exact averages make the pages of a mip pyramid; a cached-lit program (materialCachedFlag) reads it
// there instead and runs only the live layers and the lighting. Without either flag the program evaluates the material itself (direct).
#ifdef materialFillFlag
// The fill's other targets; fragColor takes the colour and material height. Every value is a plain sum's term, so the
// pyramid's means and the reads' filtering stay exact averages: the unit normal (XY biased to 0..1) with the coverage,
// and the intrinsic GGX alpha^2 (roughness^4, light-model.glsl). Cached ground has no metallic or scattering.
layout(location = 1) out vec4 fillMoments; // normal XYZ, coverage
layout(location = 2) out vec4 fillDetail;  // cavity, relief, alpha^2, a field's share (cachedField)
uniform float u_cacheClosePixel; // metres: the close view's pixel, the base tiles' texel
#endif
#ifdef materialCachedFlag
// One texture-array layer per page. Every value is premultiplied by the coverage (the moments' alpha: 1 where a top
// was drawn, 0 elsewhere), which averaging and filtering carry along, so dividing by it keeps empty texels out.
uniform sampler2DArray u_cacheColour;
uniform sampler2DArray u_cacheMoments;
uniform sampler2DArray u_cacheDetail;
// GpuPageCache's page table: the window of pages of the level the frame reads, each entry a layer, four to an ivec4.
layout(std140) uniform MaterialCachePages {
    vec4 cacheGrid;   // the window's first corner (metres), a page's interior (metres), its texels with both gutters
    vec4 cacheWindow; // pages across and down, gutter texels
    ivec4 cachePages[1020];
};

// The cached material at a point of the pages' plane (metres: a top's XY, or a wall's chart): colour and height;
// the mean of the unit normals (its length l is their agreement); cavity, relief, their mean alpha^2 and a field's share. The page's
// own mips are the close view's material averaged (colour, height, cavity and relief sharpened so the trilinear read
// lands on the plain average): they filter it trilinearly and anisotropically from this pixel's derivatives.
vec4 cachedMaterial(vec2 at, vec2 atDx, vec2 atDy, out vec3 meanNormal, out vec4 detail) {
    vec2 cell = (at - cacheGrid.xy) / cacheGrid.z;
    ivec2 page = clamp(ivec2(floor(cell)), ivec2(0), ivec2(cacheWindow.xy) - 1);
    int index = page.y * int(cacheWindow.x) + page.x;
    int entry = cachePages[index >> 2][index & 3];
    float size = cacheGrid.z;
    float interior = cacheGrid.w - 2.0 * cacheWindow.z;
    vec3 uv = vec3((cacheWindow.z + fract(at / size) * interior) / cacheGrid.w, float(entry));
    // Explicit gradients: a quad that straddles two pages keeps its footprint.
    float scale = interior / (size * cacheGrid.w);
    vec2 dx = atDx * scale, dy = atDy * scale;
    vec4 moments = textureGrad(u_cacheMoments, uv, dx, dy);
    // A pixel on a sliver narrower than a texel may find no texel the fill covered around it: the page's coarsest mip,
    // whose texels take in its neighbours, stands in there.
    bool covered = moments.a >= 1.0 / 255.0;
    if (!covered) moments = textureLod(u_cacheMoments, uv, 2.0);
    float coverageScale = 1.0 / max(moments.a, 1e-4);
    meanNormal = vec3(moments.xy * 2.0 - moments.a, moments.z) * coverageScale;
    detail = (covered ? textureGrad(u_cacheDetail, uv, dx, dy) : textureLod(u_cacheDetail, uv, 2.0)) * coverageScale;
    return (covered ? textureGrad(u_cacheColour, uv, dx, dy) : textureLod(u_cacheColour, uv, 2.0)) * coverageScale;
}

// The GGX alpha^2 of texels whose unit normals average to length l: their own mean alpha^2 plus their spread, which a
// von Mises-Fisher lobe of concentration kappa = l (3 - l^2) / (1 - l^2) adds as 1 / (2 kappa) (Unity's
// CommonMaterial.hlsl, TextureNormalVariance: alpha^2 += 2 variance, variance = 1 / (4 kappa)). Normals that cancel out
// (l near 0) read fully rough.
float filteredAlpha2(float alpha2, float l) {
    float l2 = l * l;
    float spread = l2 < 1.0 ? (1.0 - l2) / max(2.0 * l * (3.0 - l2), 1e-6) : 0.0;
    return clamp(alpha2 + spread, 0.0, 1.0);
}
#endif
#ifdef wallChartFlag
// A charted wall (GpuMaterialCache.Charts): its point in its chart (world units in the cache's wall region), where the
// fill draws it flat and the cached-lit program reads it.
in vec2 v_wallChart;

// The frame a wall's cached normal is kept in, the same in the fill and the read: across the wall, up it and out of it,
// from its smooth normal (one facing straight up takes X across).
mat3 wallFrame(vec3 face) {
    vec3 across = cross(vec3(0.0, 0.0, 1.0), face);
    across = dot(across, across) > 1e-6 ? normalize(across) : vec3(1.0, 0.0, 0.0);
    return mat3(across, cross(face, across), face);
}
#endif

// terrain-ground-color-functions

// terrain-projection-functions

// ---- Surface families --------------------------------------------------------------------------------------------

bool family(float f) { return abs(sculptFamily - f) < .5; }

// Grey lunar/volcanic sand reuses the shared sand-ground relief. Derive the colour from the supporting covers,
// so a theme boundary interpolates in world space instead of changing abruptly at a hex edge.
float greySand() {
#ifdef terrainBlendFlag
    vec4 weights = max(v_coverWeights, vec4(0.0));
    vec4 grey = (1.0 - step(vec4(.5), abs(coverFamilies - LUNAR_FAMILY)))
          + (1.0 - step(vec4(.5), abs(coverFamilies - VOLCANO_FAMILY)));
    vec4 substrate = step(vec4(.5), abs(coverFamilies - 2.0));
    return dot(weights, grey) / max(dot(weights, substrate), .0001);
#else
    return family(LUNAR_FAMILY) || family(VOLCANO_FAMILY) ? 1.0 : 0.0;
#endif
}

// Meadows and snowfields lie on a mantle (earth, snow) that buries low steps and caps high rock cliffs.
bool mantled() { return family(0.0) || family(5.0) || family(TROPICAL_FAMILY); }

// A meadow worn through to its soil: the bare earth shows between the tussocks first.
vec3 worn(vec3 turf, float height, float amount) {
    vec3 soil = toLinear(vec3(.31, .25, .18)) * (.75 + .5 * height);
    return mix(turf, soil, clamp(amount * 1.6 - height * .9, 0.0, 1.0));
}

float hash(vec3 p); // terrain-concrete.glsl

// Smooth value noise around a crater: turn is the angle in turns (0 to 1), n the bumps per turn; seamless at the join.
float ringNoise(vec3 id, float turn, float n) {
    float a = turn * n, i = floor(a), f = a - i;
    return mix(hash(id + vec3(mod(i, n), 0.0, 9.3)), hash(id + vec3(mod(i + 1.0, n), 0.0, 9.3)), f * f * (3.0 - 2.0 * f));
}

// Impact craters on lunar ground, placed in world cells so they never follow the material's 12 m repeat: three scales
// (cells of 26, 9 and 3.5 m), each cell holding at most one crater, kept by its hash. Each is a bowl with a raised rim,
// irregular and slightly polygonal as impacts into uneven ground leave it, in units of its radius, so every size has
// the same shape; most are old and shallow, the younger ones throw a bright ejecta apron streaked radially, and a few
// fresh ones are deep, with a bright halo and longer streaks. A high sun barely shades their walls, so as on Moon photos
// their tone carries them too: a pale rim and a duller bowl. Shading only (normal, cavity, albedo): support, movement and
// line of sight keep the actual ground. A crater a pixel (of pixel metres) covers much of fades out, so small ones never turn into grain.
// A crater forms only where its bowl and apron fit on level ground: clear is the pixel's distance in metres to the nearest
// drop or rise and its gradient over the ground, from which each crater estimates that distance at its own centre, so
// it keeps or drops whole and a step never cuts one.
void lunarCraters(vec3 world, float pixel, float clear, vec2 clearGradient, float amount, inout vec3 albedo,
      inout vec3 normal, inout float cavity) {
    vec2 slope = vec2(0.0);
    float bowl = 0.0, ring = 0.0, fresh = 0.0, ejecta = 0.0;
    for (int scale = 0; scale < 3; scale++) {
        float cell = scale == 0 ? 26.0 : scale == 1 ? 9.0 : 3.5;
        vec2 radius = scale == 0 ? vec2(3.0, 8.0) : scale == 1 ? vec2(1.0, 3.0) : vec2(.4, 1.1);
        float chance = scale == 0 ? .525 : scale == 1 ? .24 : .12;
        vec2 cellOf = floor(world.xy / cell);
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 id = vec3(cellOf + vec2(x, y), float(scale) * 17.0 + 3.1);
                if (hash(id) > chance) continue;
                vec2 centre = (cellOf + vec2(x, y) + .2 + .6 * vec2(hash(id + 1.3), hash(id + 2.7))) * cell;
                float pick = hash(id + 4.1);
                float r = mix(radius.x, radius.y, pick * pick);
                vec2 d = world.xy - centre;
                float s = length(d) / r;
                float seen = (1.0 - smoothstep(.12, .4, pixel / r))
                      * smoothstep(1.8 * r, 2.8 * r, clear - dot(clearGradient, d));
                if (s > 2.9 || seen <= 0.0) continue;
                // Most craters are old and worn shallow; a few are fresh.
                float age = hash(id + 6.7);
                float isFresh = step(.86, age);
                // A near-round outline: broad smooth lumps, a faint polygonal lean and a ragged edge.
                float angle = atan(d.y, d.x), turn = angle / 6.2831853 + .5;
                s *= 1.0 + .04 * sin(2.0 * angle + pick * 71.0) + .035 * sin(3.0 * angle + pick * 53.0)
                      + .03 * sin(5.0 * angle + age * 37.0) + .02 * sin(6.0 * angle + age * 17.0)
                      + .05 * (ringNoise(id + 5.0, turn, 31.0) - .5);
                if (s > 2.5) continue;
                float depth = mix(.12, .3, age) + .05 * isFresh;
                // Bowl h = depth (s^2 - 1) + rim inside, rim exp(-3 (s - 1)) outside: the slope dh/ds, along d, eased
                // through the crest so the rim rounds over instead of ending in a cut-out edge. The rim stands higher
                // along some arcs than others, worn down in places.
                float rim = .25 * depth * mix(.35, 1.5, ringNoise(id + 41.0, turn, 7.0));
                float dh = mix(2.0 * depth * min(s, 1.0), -3.0 * rim * exp(-3.0 * max(s - 1.0, 0.0)),
                      smoothstep(.86, 1.1, s));
                vec2 along = d / max(length(d), 1e-4);
                slope += dh * along * seen;
                bowl = max(bowl, (1.0 - smoothstep(.55, 1.0, s)) * depth / .35 * seen);
                ring = max(ring, exp(-(s - 1.0) * (s - 1.0) / .02) * rim / (.25 * .35) * .7 * seen);
                if (age > .72) {
                    // The young ones' radial ejecta streaks, each reaching its own distance, clear of the rim;
                    // narrower than about two pixels they average out to a faint apron instead of shimmering.
                    float streaks = ringNoise(id + 11.0, turn, 72.0) * ringNoise(id + 23.0, turn, 29.0);
                    float lane = 6.2831853 * s * r / 72.0;
                    streaks = mix(.15, smoothstep(.15, .55, streaks), smoothstep(1.0, 3.0, lane / max(pixel, 1e-4)));
                    float reach = mix(1.8, 2.5, ringNoise(id + 31.0, turn, 17.0));
                    float apron = smoothstep(1.1, 1.3, s) * (1.0 - smoothstep(reach - .8, reach, s));
                    ejecta = max(ejecta, streaks * apron * smoothstep(.72, .95, age) * seen);
                }
                if (isFresh > 0.0) fresh = max(fresh, (1.0 - smoothstep(.9, 1.15, s)) * seen);
            }
        }
    }
    normal = normalize(normal - vec3(slope, 0.0) * 1.6 * amount);
    cavity *= 1.0 - .25 * bowl * amount;
    albedo *= (1.0 - .14 * bowl * amount) * (1.0 + .16 * ring * amount) * (1.0 + .22 * fresh * amount)
          * (1.0 + .5 * ejecta * amount);
}

// How strongly debris shows on open ground: at rims, at cliff feet, on slopes and in scattered patches.
vec4 debrisWeights() {
    if (family(0.0)) return vec4(.55, 1.0, .45, 0.0);  // grass: rocky edges and scree, turf elsewhere
    if (family(TROPICAL_FAMILY)) return vec4(.35, .7, .4, .05);
    if (family(1.0)) return vec4(.6, .9, .6, .45);     // dirt: gravel
    if (family(2.0)) return vec4(.85, .9, .5, .3);     // sand: desert pavement
    if (family(DESERT_FAMILY) || family(MARS_FAMILY)) return vec4(.85, .9, .5, .3);
    if (family(3.0) || family(LUNAR_FAMILY) || family(VOLCANO_FAMILY)) return vec4(.5, .9, .6, .55);
    if (family(FUNGUS_FAMILY)) return vec4(.8, .4, .6, .2); // cyan crust in sheltered rock pockets
    if (family(4.0)) return vec4(0.0);                  // concrete: clean slab
    return vec4(.75, .9, .7, .12);                     // snow: wind-scoured rock
}

// Sunlit ground around a family reflects its own colour into shaded walls and undersides.
vec3 groundBounceFor(float f) {
    if (abs(f) < .5) return vec3(.15, .145, .06);
    if (abs(f - 1.0) < .5) return vec3(.22, .15, .10);
    if (abs(f - 2.0) < .5) return mix(vec3(.42, .28, .16), vec3(.23, .24, .26), greySand());
    if (abs(f - DESERT_FAMILY) < .5) return vec3(.35, .23, .13);
    if (abs(f - MARS_FAMILY) < .5) return vec3(.34, .14, .07);
    if (abs(f - VOLCANO_FAMILY) < .5) return vec3(.14, .16, .19);
    if (abs(f - TROPICAL_FAMILY) < .5) return vec3(.13, .15, .065);
    if (abs(f - 3.0) < .5 || abs(f - LUNAR_FAMILY) < .5) return vec3(.22, .21, .19);
    if (abs(f - FUNGUS_FAMILY) < .5) return vec3(.23, .20, .28);
    if (abs(f - 4.0) < .5) return vec3(.30, .29, .27);
    if (f > VOLCANIC_CRUST_FAMILY - .5) return vec3(.04, .025, .018);
    return vec3(.75, .78, .82);
}

vec3 groundBounce() { return groundBounceFor(sculptFamily); }

vec3 groundTone(vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    return groundToneFor(sculptFamily, albedo, world, broad, fine, region, rim, foot);
}

// sculpt-material-functions
void main() {
    vec2 surfaceUV = v_diffuseUV;
    vec3 face = normalize(v_normal);
    float occlusion = v_color.r;
    float level = floor(v_color.g * 255.0 + .5) - 64.0;
    float kind = v_color.b;
    bool ground = kind < .125, plant = kind >= .125 && kind < .375, cliff = kind >= .375 && kind < .625;
    bool leaf = plant && kind > .325; // Explicit authored tissue; legacy shrubs and wood remain opaque.
    bool panel = cliff && kind < .475;
    float projectionAngle = (v_color.a - .5) * 6.2831853;
    vec3 projection = panel ? vec3(cos(projectionAngle), sin(projectionAngle), 0.0) : face;
    terrainWallProjection = panel ? projection : vec3(0.0);
    float hardness = panel ? .5 : v_color.a;
    bool abyss = cliff && kind > .55;
    bool mudShell = cliff && !panel && !abyss && kind > 139.0 / 255.0;
    if (mudShell) occlusion = 1.0;
    bool pit = kind >= .625 && kind < .875;
    bool shore = ground && v_color.a < .25;
    bool zeroDepth = shore && surfaceUV.y < 0.0;
    if (zeroDepth) surfaceUV.y = -1.0 - surfaceUV.y;
    // Rock-kind bytes 224..254 carry the fractional water level; 255 remains ordinary dry rock.
    bool wetRock = kind >= .875 && kind < .999;
    bool waterCovered = shore || wetRock;
    // Without gravity nothing lies loose: the dry rock kit stands as bedrock outcrops (BoardScene.Tile.lunar), exposed
    // rock of the ground it breaks through, in that ground's natural material like a rock cliff. Its bedding planes
    // lie as open to the sky as the plain they continue; only its scarps keep the kit's occlusion toward the root.
    bool bedrock = kind >= .999 && u_gravity <= 0.0;
    if (bedrock) occlusion = mix(occlusion, 1.0, smoothstep(.55, .85, face.z));
    // Without gravity the water is gone: its bed keeps its stones, sediment and wet look, but neither the water's
    // colour nor its light, and draws its own grid.
    bool watered = waterCovered && u_gravity > 0.0;
    // Wet ground uses the spare ground-kind range for the fractional surface level of a descending stream.
    if (shore) level = v_color.g * 255.0 - 64.0 + kind * 8.0;
    if (wetRock) level = v_color.g * 255.0 - 64.0 + (kind * 255.0 - 224.0) / 30.0;
    // A water hex's ground packs its water's palette with the nearest step's height, which dry ground carries alone.
    float tintByte = v_color.a * 255.0;
    float palette = floor(tintByte / 16.0 + .03);
    float steps = shore ? (tintByte - 16.0 * palette) / 2.0 : (v_color.a - .3) / .1;
    vec3 world = v_cloudPosition / u_metre;
    vec3 worldDx = dFdx(world), worldDy = dFdy(world);
#if defined(wallChartFlag) && defined(materialCachedFlag)
    vec2 chart = v_wallChart / u_metre, chartDx = dFdx(chart), chartDy = dFdy(chart);
#endif
#ifdef lightingFlag
    vec3 geometric = visibleFace(worldDx, worldDy, -viewDirection());
    terrainView = -viewDirection();
#endif
    // A scar patch's draw shades only where its image covers the receiver (scar-material.glsl).
    if (!surfaceScarCovers()) { fragColor = vec4(0.0); return; }
    pixelMetres = max(max(length(worldDx), length(worldDy)), 1e-4);
#ifdef materialFillFlag
    // The close view's material at every texel. The maps are still read at the texel, and the pyramid averages the rest.
    // No parallax: it depends on the view.
    pixelMetres = u_cacheClosePixel;
#endif
    // Depth below this water hex's level, in metres, and height above its water, which lies u_waterLine lower.
    float depth = waterCovered ? (level * u_levelHeight - v_cloudPosition.z) / u_metre : -1.0;
    float above = waterCovered ? u_waterLine / u_metre - depth : 99.0;
    vec2 p = vec2(world.x, -world.y);
    // Capture numerical-map gradients before varying material/coverage branches.
    mat2 groundGradient = mat2(dFdx(p), dFdy(p));
    // Ground's distance to the nearest drop or rise (BoardRelief.Shade: rim and foot, metres) and its gradient over the
    // ground, which keep the lunar craters clear of steps.
    float stepClear = min(surfaceUV.x, surfaceUV.y);
    mat2 groundFrame = mat2(worldDx.x, worldDy.x, worldDx.y, worldDy.y);
    vec2 stepGradient = abs(determinant(groundFrame)) > 1e-12
          ? inverse(groundFrame) * vec2(dFdx(stepClear), dFdy(stepClear)) : vec2(0.0);
    mat2 wallXGradient = mat2(vec2(worldDx.y * sign(projection.x), -worldDx.z),
          vec2(worldDy.y * sign(projection.x), -worldDy.z));
    mat2 wallYGradient = mat2(vec2(-worldDx.x * sign(projection.y), -worldDx.z),
          vec2(-worldDy.x * sign(projection.y), -worldDy.z));
    // Smooth value fields from the shared 64-texel noise: broad has ~11 m cells, fine ~2.5 m, and region ~40 m.
    vec3 fields = groundFields(u_rainNoise, world);
    float broad = fields.x, fine = fields.y, region = fields.z;
    // Keep the walking surface fully muddy. Deposits continue over its caprock edge and drain down the shell,
    // with the existing world fields varying their reach. The shell carries drop from its local TOP edge, so
    // descending decks keep the same fade. The material's own height breaks the margin without extra reads.
    float deckMud = ground && !shore && kind > .05 ? 1.0
          : mudShell ? 1.0 - smoothstep(.35, mix(1.3, 2.9, .35 * broad + .65 * fine), v_color.r * 8.0) : 0.0;
    MaterialProjection rockProjection = materialProjection(world, face);
#if defined(biomeMaskFlag) && !defined(materialCachedFlag) && !defined(materialFillFlag) && !defined(volcanicFlag)
    if ((ground || cliff || bedrock) && !family(4.0) && !(shore && above <= -1.1)) {
        int wetBiome = deckMud == 1.0 ? 4
              : (!shore || above >= -.15 ? opaqueWetBiome(world, face) : 0);
        bool sandPossible = family(2.0);
#ifdef terrainBlendFlag
        sandPossible = sandPossible || any(lessThan(abs(coverFamilies - 2.0), vec4(.5)));
#endif
        terrainCovered = wetBiome == 4 || (!sandPossible && (wetBiome == 2 || wetBiome == 3));
    }
#endif
    vec3 albedo = toLinear(vec3(.52));
    vec3 normal = face;
    float cavity = 1.0;
    float relief = 1.0;
    float materialHeight = .5;
    // Each material path sets its finish; covers, wear, damage and scorch change it after.
    Finish finish = Finish(.9, 0.0, 0.0, vec3(0.0));
    float volcanic = 0.0;
#ifdef terrainBlendFlag
    vec4 materialCoverage = vec4(1.0, 0.0, 0.0, 0.0);
#endif
    float caustic = 0.0;
    float grass = family(0.0) ? 1.0 : 0.0;
    // The lunar share of the final cover: its craters and its moon-dust light (Surface.regolith).
    float lunar = 0.0;
    float sand = family(2.0) ? 1.0 : 0.0;
    vec3 bounce = groundBounce();
    float response = u_groundResponse;
    float shoreAbsorption = 0.0;
    float rainCover = step(0.0, response);
    bool natural = (ground || cliff || bedrock) && !family(4.0);
    // Natural surfaces take the small cliff material: walls, steep dry/wet banks and bedrock its side
    // projections (blendCliff when blended), tops its top projection (blendCovers when blended). Volcanic programs
    // keep their natural material on every face.
#ifdef volcanicFlag
    bool rocky = false;
#else
    bool rocky = natural && (cliff || bedrock || (ground && face.z < .6));
#endif
    // Reuse the material's world fields for a broken soil/gravel margin. Keep it below the dry rim so the
    // neighbouring land agrees at the seam; no new noise reads or shoreline mesh are needed.
    riverBank = shore && natural && palette < .5;
    float bankMargin = .995 * u_waterLine / u_metre;
    // Use the whole exposed bank for the fade. Nested height smoothsteps compressed it into a sharp strip.
    float bankProgress = clamp((bankMargin - above) / max(bankMargin - .01, .001), 0.0, 1.0);
    bankProgress += (broad * .35 + fine * .65 - .5) * .3 * bankProgress * (1.0 - bankProgress);
    float nearWaterSediment = shore ? 1.0 - smoothstep(-.25, .05, above) : 0.0;
    // Depth-zero arid banks use the same halfway profile for clear and Mars water alike.
    shallowSediment = zeroDepth ? mix(nearWaterSediment, bankProgress, .5) : -1.0;
    // Dry cover is gone just before the waterline; the endpoints still match the adjoining ground and bed.
    float sediment = riverBank ? bankProgress : nearWaterSediment;
    // Levels of water over a submerged bed; none elsewhere.
    float submerged = 0.0;
    vec3 transmission = vec3(1.0);
    float biomePool = 0.0, biomeDamp = 0.0;
    // Cliffs grade continuously with height.
    if (cliff) level = v_cloudPosition.z / u_levelHeight;
#ifdef materialCachedFlag
    // The canonical material the fill wrote for this top (cachedMaterial); the layers after it stay live.
    vec3 meanNormal;
    vec4 cachedDetail;
#ifdef wallChartFlag
    vec4 cachedColour = cachedMaterial(chart, chartDx, chartDy, meanNormal, cachedDetail);
    meanNormal = wallFrame(face) * meanNormal;
#else
    vec4 cachedColour = cachedMaterial(world.xy, worldDx.xy, worldDy.xy, meanNormal, cachedDetail);
#endif
    albedo = cachedColour.rgb;
    materialHeight = cachedColour.a;
    float meanLength = length(meanNormal);
    normal = meanLength > 1e-4 ? meanNormal / meanLength : vec3(0.0, 0.0, 1.0);
    cavity = cachedDetail.x;
    // The texels' sun is their relief times their normal's: averaged, it is the mean relief times the mean normal,
    // whose length the normalised normal leaves out (sunOnRelief takes it back).
    relief = cachedDetail.y * meanLength;
    finish = Finish(sqrt(sqrt(filteredAlpha2(cachedDetail.z, meanLength))), 0.0, 0.0, vec3(0.0));
    // A natural top matches its slope at the same height, as the evaluator sets it.
    if (natural && !shore) level = v_cloudPosition.z / u_levelHeight;
#ifdef terrainBlendFlag
    // The live layers take the covers' plain proportions (blendCovers' starting weights) where the fill resolved their
    // height-blended patches: bounce light, rain response, grass and sand follow the mixture, not each patch.
    vec4 proportions = max(v_coverWeights, vec4(0.0));
    proportions *= step(vec4(.02), proportions);
    materialCoverage = proportions / max(dot(proportions, vec4(1.0)), .00001);
    coverEffects(materialCoverage, sediment, grass, sand, bounce, response, rainCover);
#endif
#else
    vec4 detail = vec4(0.0, 0.0, 1.0, 1.0);
    if (natural) {
        float foot = shore || cliff || bedrock ? surfaceUV.x : surfaceUV.y;
        float rim = shore || cliff || bedrock ? surfaceUV.y : surfaceUV.x;
        float rock = ground ? rockiness(steps) : bedrock ? 1.0 : v_color.g;
        float bed = ground ? .5 : hardness;
#ifdef terrainBlendFlag
        // Blended tops take blendCovers below.
        if (rocky) {
            TerrainMaterial material = blendCliff(rockProjection, world, face, foot, rim, rock, bed, broad, fine,
                  region, sediment, materialCoverage);
            coverEffects(materialCoverage, sediment, grass, sand, bounce, response, rainCover);
#else
        {
#ifdef volcanicFlag
            TerrainMaterial material = crustOrBankMaterial(world, face);
#else
            // One material for tops and walls: tops read the top projection, walls, banks and bedrock the sides.
            TerrainMaterial material = cliffMaterial(rockProjection, world, face, foot, rim, rock, bed, broad, fine,
                  region, sediment, vec4(sculptFamily), 0, u_sculptTiles, u_sculptLayers, rocky, false);
#endif
#endif
            albedo = material.color;
            normal = material.normal;
            cavity = material.cavity;
            relief = material.relief;
            materialHeight = material.height;
            finish = material.finish;
            volcanic = material.volcanic;
        }
        // Match a top to its slope at the same height; tactical level grading remains continuous.
        if (!shore) level = v_cloudPosition.z / u_levelHeight;
    } else if (ground) {
        // A water hex's steep banks take their maps from the side, as walls do, so nothing stretches downhill.
        bool steep = shore && face.z < .6;
        vec2 q = !steep ? p : abs(face.x) > abs(face.y) ? vec2(world.y * sign(face.x), -world.z)
              : vec2(-world.x * sign(face.y), -world.z);
        // Pavement has no directional grain to preserve. Translate both aligned maps through a continuous
        // world field instead of repeating the same concrete cloud at two fixed scales (two reads either way).
        float concreteVariation = broad * .45 + fine * .55;
        mat2 qGradient = !steep ? groundGradient
              : abs(face.x) > abs(face.y)
              ? mat2(vec2(worldDx.y * sign(face.x), -worldDx.z), vec2(worldDy.y * sign(face.x), -worldDy.z))
              : mat2(vec2(-worldDx.x * sign(face.y), -worldDx.z), vec2(-worldDy.x * sign(face.y), -worldDy.z));
        mat2 concreteGradient = qGradient / u_sculptTiles.x;
        // The pavement and its rubble take the terrain's parallax (terrain-materials.glsl) on their one projection.
        vec2 qView = !steep ? vec2(terrainView.x, -terrainView.y) : abs(face.x) > abs(face.y)
              ? vec2(terrainView.y * sign(face.x), -terrainView.z) : vec2(-terrainView.x * sign(face.y), -terrainView.z);
        vec2 topReach = planeReach(u_sculptLayers.x * .5, u_sculptTiles.x, qView, qGradient);
        vec2 rubbleReach = planeReach(u_sculptLayers.y * .5, u_sculptTiles.y, qView, qGradient);
        vec2 qTop = q, qRubble = q;
        if (dot(topReach, topReach) > 0.0) {
            qTop -= topReach * (1.0 - (family(4.0)
                  ? translatedHeight(u_sculptLayers.x * .5, q / u_sculptTiles.x, concreteGradient, concreteVariation)
                  : planarHeight(u_sculptLayers.x, q, qGradient, u_sculptTiles.x, broad)));
        }
        if (dot(rubbleReach, rubbleReach) > 0.0) {
            qRubble -= rubbleReach * (1.0 - planarHeight(u_sculptLayers.y, q, qGradient, u_sculptTiles.y, fine));
        }
        vec4 top = family(4.0)
              ? translatedTexel(u_sculptLayers.x, qTop / u_sculptTiles.x, concreteGradient, concreteVariation)
              : planar(u_sculptLayers.x, qTop, u_sculptTiles.x, broad);
        vec4 rubble = planar(u_sculptLayers.y, qRubble, u_sculptTiles.y, fine);
        vec4 weights = debrisWeights();
        float rim = 1.0 - smoothstep(.3, 2.2 + 1.5 * fine, surfaceUV.x);
        float foot = 1.0 - smoothstep(.4, 3.0 + 2.0 * broad, surfaceUV.y);
        float slope = 1.0 - smoothstep(.8, .96, face.z);
        float patches = smoothstep(.56, .78, fine * .7 + broad * .3);
        // Mantled ground shows scree only beside rock cliffs; beside the earth banks of lower steps a meadow wears
        // through to its soil, and snow stays whole. The vertex tint carries the nearest step's height.
        float rock = mantled() ? rockiness(steps) : 1.0;
        float edges = max(rim * weights.x, foot * weights.y);
        float want = clamp(max(edges * rock, slope * weights.z) + patches * weights.w, 0.0, 1.0);
        // A water hex's bed is stony from just above the waterline down, whatever grows on the land around; its
        // dry bank stays the land's own ground.
        if (shore) want = max(want, sediment);
        float w = heightBlend(top.a, rubble.a, want);
        albedo = mix(top.rgb, rubble.rgb, w);
        materialHeight = mix(top.a, rubble.a, w);
        Finish topFinish = family(4.0)
              ? translatedFinish(u_sculptLayers.x, qTop / u_sculptTiles.x, concreteGradient, concreteVariation)
              : planarFinish(u_sculptLayers.x, qTop, qGradient, u_sculptTiles.x, broad);
        finish = mixFinish(topFinish, planarFinish(u_sculptLayers.y, qRubble, qGradient, u_sculptTiles.y, fine), w);
        // In patches only: most of a bank's lip keeps its turf.
        if (family(0.0)) {
            float wear = max(rim * .55, foot * .75) * (1.0 - rock) * smoothstep(.35, .7, fine);
            albedo = mix(albedo, worn(albedo, top.a, wear), 1.0 - w);
        }
        if (u_normalMaps > .5 && !steep) {
            vec4 topNormal = family(4.0)
                  ? translatedTexel(u_terrainData, u_sculptLayers.x * .5, qTop / u_sculptTiles.x, concreteGradient, concreteVariation)
                  : planarNormal(u_sculptLayers.x + 1.0, qTop, u_sculptTiles.x, broad);
            if (family(4.0)) topNormal.rgb = topNormal.rgb * 2.0 - 1.0;
            detail = mix(topNormal,
                  planarNormal(u_sculptLayers.y + 1.0, qRubble, u_sculptTiles.y, fine), w);
        }
        albedo = groundTone(albedo, world, broad, fine, region, rim, foot);
    } else if (plant) {
        // The bush GLB supplies distinct woody stems and leaves; UV/alpha carry its linear RGB.
        // Live shader reload can still encounter .25-kind meshes from the earlier height-based format.
        float leaf = texture(u_rainNoise, world.xy / 7.0 + world.z * .13).r;
        vec3 pigment = kind > .275 ? vec3(surfaceUV, v_color.a)
              : family(0.0) ? toLinear(vec3(.27, .35, .12)) : toLinear(vec3(.40, .42, .26));
        albedo = pigment * mix(.92, 1.06, leaf);
    } else if (pit) {
        // A tree pit in the pavement: authored bark mulch inside a concrete kerb.
        if (v_color.a > .5) {
            albedo = planar(u_sculptLayers.x, p, u_sculptTiles.x, broad).rgb;
            finish = planarFinish(u_sculptLayers.x, p, groundGradient, u_sculptTiles.x, broad);
        } else {
            vec4 mulch = planar(u_treePitMaterial.x, p, u_treePitMaterial.y, fine);
            albedo = mulch.rgb;
            materialHeight = mulch.a;
            finish = planarFinish(u_treePitMaterial.x, p, groundGradient, u_treePitMaterial.y, fine);
            if (u_normalMaps > .5) {
                vec4 detail = planarNormal(u_treePitMaterial.x + 1.0, p, u_treePitMaterial.y, fine);
                normal = upNormal(detail.rgb, face);
                cavity = detail.a;
            }
        }
    } else if (cliff && family(4.0)) {
        concreteSlab(world, worldDx, worldDy, face, projection, surfaceUV.x, surfaceUV.y,
              albedo, normal, occlusion, cavity, finish);
    } else {
        // Rocks: the two vertical projections, V running down the face, never mirrored from outside.
        // Where a rounded corner turns between them, the projection whose relief stands higher shows through.
        vec2 uvx, uvy;
        float side;
        vec4 wall = wallSample(world, projection, uvx, uvy, side);
        // Its two wall projections take the terrain's parallax (terrain-materials.glsl), from the wall's height.
        vec2 reachX = planeReach(u_sculptLayers.z * .5, u_sculptTiles.z,
              vec2(terrainView.y * sign(projection.x), -terrainView.z), wallXGradient);
        vec2 reachY = planeReach(u_sculptLayers.z * .5, u_sculptTiles.z,
              vec2(-terrainView.x * sign(projection.y), -terrainView.z), wallYGradient);
        if (dot(reachX, reachX) + dot(reachY, reachY) > 0.0) {
            uvx -= reachX / u_sculptTiles.z * (1.0 - wall.a);
            uvy -= reachY / u_sculptTiles.z * (1.0 - wall.a);
            wall = mix(materialTexel(u_sculptLayers.z, uvy, wallYGradient / u_sculptTiles.z),
                  materialTexel(u_sculptLayers.z, uvx, wallXGradient / u_sculptTiles.z), side);
        }
        // A boulder's crown needs a horizontal stone projection: the two wall projections collapse on a
        // flat top, otherwise leaving a single colour or stretched stripes under the moss/snow treatment.
        float crown = smoothstep(.45, .85, face.z);
        if (crown > 0.0) wall = mix(wall, planar(u_sculptLayers.z, p, u_sculptTiles.z, fine), crown);
        albedo = wall.rgb;
        if (u_normalMaps > .5) {
            normal = wallNormal(u_sculptLayers.z + 1.0, uvx, uvy, face, side);
            cavity = mix(normalTexel(u_sculptLayers.z + 1.0, uvy).a, normalTexel(u_sculptLayers.z + 1.0, uvx).a, side) * .5 + .5;
            if (crown > 0.0) {
                vec4 top = planarNormal(u_sculptLayers.z + 1.0, p, u_sculptTiles.z, fine);
                normal = normalize(mix(normal, upNormal(top.rgb, face), crown));
                cavity = mix(cavity, top.a * .5 + .5, crown);
            }
        }
        finish = wallFinish(u_sculptLayers.z, uvx, uvy,
              wallXGradient / u_sculptTiles.z, wallYGradient / u_sculptTiles.z, side);
        if (crown > 0.0) {
            finish = mixFinish(finish, planarFinish(u_sculptLayers.z, p, groundGradient, u_sculptTiles.z, fine), crown);
        }
        float h = surfaceUV.x;
        // Broad ledges collect the ground cover (sand, snow, moss on alpine rock); on the rock kit only snow and
        // sand dust lie.
        float ledge = smoothstep(.8, .95, face.z) * smoothstep(.3, .6, fine) * (family(0.0) ? .7 : 1.0);
        // Snow caps the upper facets of a boulder, leaving its stone sides legible against the snowfield.
        if (family(5.0)) {
            ledge = smoothstep(.72, .92, face.z);
        }
        ledge *= family(5.0) ? smoothstep(.2, .9, h) : family(2.0) ? .45 : 0.0;
        if (ledge > 0.0 && !family(4.0)) {
            vec4 top = planar(u_sculptLayers.x, p, u_sculptTiles.x, broad);
            float w = heightBlend(wall.a, top.a, ledge);
            albedo = mix(albedo, groundTone(top.rgb, world, broad, fine, region, 0.0, 0.0), w);
            finish = mixFinish(finish, planarFinish(u_sculptLayers.x, p, groundGradient, u_sculptTiles.x, broad), w);
            if (u_normalMaps > .5) {
                normal = normalize(mix(normal, upNormal(planarNormal(u_sculptLayers.x + 1.0, p, u_sculptTiles.x, broad).rgb, face), w));
            }
        }
        // Stable per-block pigment variation, independent of water optics and root occlusion.
        albedo *= mix(.78, 1.0, wetRock ? surfaceUV.y : v_color.a);
        if (family(0.0)) {
            float moss = smoothstep(.7, .94, face.z) * smoothstep(.6, .8, fine);
            albedo *= mix(vec3(1.0), toLinear(vec3(.9, .97, .82)), moss * .22);
        }
    }
    if (ground && !natural && u_normalMaps > .5) {
        normal = upNormal(detail.rgb, face);
        cavity = detail.a * .6 + .4;
    }
    if (shore && !natural && family(4.0) && face.z < .6) {
        concreteSlab(world, worldDx, worldDy, face, projection, surfaceUV.x, surfaceUV.y,
              albedo, normal, occlusion, cavity, finish);
    }
#ifdef terrainBlendFlag
    if ((ground || cliff) && !rocky) {
        float foot = shore || cliff ? surfaceUV.x : surfaceUV.y;
        float rim = shore || cliff ? surfaceUV.y : surfaceUV.x;
        float rock = ground ? rockiness(steps) : v_color.g;
        // A bank blend must use sediment below the waterline, never repaint the bed with neighbouring turf.
        blendCovers(world, face, foot, rim, rock, ground ? .5 : hardness, sediment,
              broad, fine, region, albedo, normal, cavity, relief, materialHeight, grass, sand, bounce, response,
              rainCover, finish, volcanic, materialCoverage);
    }
#endif
#endif
    if (ground && !shore && family(4.0)) {
        // BoardRelief.groundOcclusion, per pixel (cached pavement too: the cache holds no occlusion).
        occlusion = 1.0 - .3 * exp(-surfaceUV.y / 1.6);
    }
    lunar = family(LUNAR_FAMILY) ? 1.0 : 0.0;
#ifdef terrainBlendFlag
    if (ground || cliff) lunar = dot(materialCoverage, 1.0 - step(vec4(.5), abs(coverFamilies - LUNAR_FAMILY)));
#endif
#ifndef materialCachedFlag
    // Lunar ground keeps its impact craters, on level faces of its actual share of the cover.
    if (ground && !shore && face.z > .85 && lunar > 0.0) {
        lunarCraters(world, pixelMetres, stepClear, stepGradient, lunar * smoothstep(.85, .97, face.z), albedo,
              normal, cavity);
    }
#endif
    // A field's share of the ground: the fill keeps it for the cached top's live layers (cachedField).
    float field = 0.0;
    // biomeMaskFlag: the board has biomes or liquids of two palettes (GpuBiomeSurface); without them this stage
    // finds no cover and leaves everything as it is.
#ifdef biomeMaskFlag
    if (ground || cliff || bedrock) {
#ifdef materialCachedFlag
        // A cached top's hex has no biome but fields (GpuMaterialCache.uncovered), whose ground the fill holds.
        cachedField(world, face, worldDx, cliff || bedrock ? surfaceUV.x : 0.0, cachedDetail.w, albedo, grass, bounce);
#else
        // Ground carries its distance to the nearest drop and rise; walls their depth below the rim and height above
        // the foot.
        tundraLandform = ground ? surfaceUV.xy : surfaceUV.yx;
        biomeSurface(world, face, shore, above, cliff || bedrock ? surfaceUV.x : 0.0,
              cliff || bedrock ? surfaceUV.y : surfaceUV.x, materialHeight, sand, deckMud, region,
              albedo, normal, cavity, finish, grass, bounce, biomePool, biomeDamp, field);
#endif
    }
#endif
#ifndef materialFillFlag
    if (ground && grass > 0.0 && !shore && u_wind.z > 0.0) {
        // Gusts roll across a meadow: the grass leans with them and catches the light differently.
        vec2 gust = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(1.0, 0.0);
        float sway = sin(dot(world.xy, gust) * .45 - u_rainTime * 1.9 + fine * 6.0)
              * sin(dot(world.xy, vec2(-gust.y, gust.x)) * .21 - u_rainTime * .7);
        normal = normalize(normal + vec3(gust * sway * .12 * u_wind.z * grass, 0.0));
    }
#endif
    if (waterCovered) {
        // Damp sediment stops short of the patchy dry bank, following the same already-sampled fields.
        float dampMargin = shore && natural ? bankMargin * .65 : .12;
        shoreAbsorption = .65 * (1.0 - smoothstep(0.0, dampMargin, above))
              * (1.0 - smoothstep(0.0, .20, biomeDamp));
    }
    if (watered && above < 0.0) {
        // Beneath the waterline the bed keeps the hue its column of water passes and catches caustics
        // (water-optics.glsl), while the surface above removes the brightness the column absorbs.
        submerged = max(0.0, depth * u_metre - u_waterLine) / u_levelHeight;
        // Fine wetland sediment keeps its brown/olive tint in the thin shore wash; deeper bed optics are unchanged.
        vec4 mixture = liquidCoverage(world, level);
        if (dot(mixture, vec4(1.0)) < .5) mixture = waterPalette(palette);
        // Water-owned triangles include exposed bars. Apply suspended sediment only below the actual
        // waterline, or the dry marsh changes colour at the straight boundary of the bed mesh.
        vec3 bedTint = mix(waterBedTint(mixture, submerged), toLinear(vec3(.92, .88, .73)), clamp(biomeDamp * 2.0, 0.0, 1.0));
        transmission = mix(vec3(1.0), bedTint, smoothstep(0.0, .12, -above));
        if (u_rainDetail > 0.0 && u_waterEffects > 0.0) {
            caustic = waterBedCaustics(v_cloudPosition.xy * u_rainScale, submerged) * u_rainDetail * u_waterEffects;
        }
    }
    // 1 above the water, 0 on a submerged bed: the water surface draws the grid and takes the rain for it.
    float exposed = watered ? 1.0 - smoothstep(0.0, 1.0, depth * u_metre - u_waterLine) : 1.0;
    // Weathering follows the ground onto the level toe and lip of its banks, fading as they steepen, so a bank meets
    // its foot and rim in the same worn tone.
    float weathered = ground ? 1.0 : cliff && natural && !panel ? smoothstep(.75, .97, face.z) : 0.0;
    if (weathered > 0.0 && volcanic < .01) {
        // Follow the final material weights, including sand windows and authored mixtures. Reuse the blended
        // relief once; no extra maps or stamped motifs. Wetland pools suppress dry surface abrasion.
#ifndef materialCachedFlag
        vec4 wear = terrainWear(fields, materialHeight, cavity, face, normal);
#ifdef terrainBlendFlag
        vec3 pigment = materialWeathering(coverFamilies.x, wear, fields, cavity) * materialCoverage.x
              + materialWeathering(coverFamilies.y, wear, fields, cavity) * materialCoverage.y
              + materialWeathering(coverFamilies.z, wear, fields, cavity) * materialCoverage.z
              + materialWeathering(coverFamilies.w, wear, fields, cavity) * materialCoverage.w;
#else
        vec3 pigment = materialWeathering(sculptFamily, wear, fields, cavity);
#endif
        // Snow's authored RGB already contains its cool granular crust. Do not add a second microscopic
        // color treatment. Keep the original wear response for roughness.
        vec3 colorPigment = pigment;
#ifdef terrainBlendFlag
        float snowCoverage = dot(materialCoverage, 1.0 - step(vec4(.5), abs(coverFamilies - 5.0)));
        if (snowCoverage > 0.0) colorPigment -= materialWeathering(5.0, wear, fields, cavity) * snowCoverage;
#else
        if (family(5.0)) colorPigment = vec3(0.0);
#endif
        float wearStrength = exposed * (1.0 - biomePool) * (1.0 - biomeDamp * .65) * weathered;
        pigment *= wearStrength;
        albedo *= authoredPigmentGain(vec3(1.0) + colorPigment * wearStrength);
        finish.roughness = mix(finish.roughness, .98, max(max(abs(pigment.r), abs(pigment.g)), abs(pigment.b)));
#endif
#ifndef materialFillFlag
        if (ground) groundDamage(world, face, grass, sand, exposed, albedo, normal, cavity, finish);
#endif
    }
#ifndef materialCachedFlag
    // The close view shades its own pixel's normals by their mean direction, the reference every zoom averages: the
    // relief counts from there, over the octave in which the reads start to average close pixels. Finer, their
    // filtering alone would shorten them. The fill, at the close pixel, stores the close view's: none; the cache's
    // mean normal length carries the averaged relief.
    relief = mix(1.0, relief, clamp(log2(pixelMetres / CLOSE_PIXEL), 0.0, 1.0));
#endif
#ifdef materialFillFlag
    // The fill stores the canonical material, premultiplied by full coverage, and stops before the live layers.
    vec3 unitNormal = normalize(normal);
#ifdef wallChartFlag
    // A wall keeps its normal in its own frame, which its chart's texels share (wallFrame).
    unitNormal = transpose(wallFrame(face)) * unitNormal;
#endif
    float alpha = finish.roughness * finish.roughness;
    fragColor = vec4(albedo, materialHeight);
    fillMoments = vec4(unitNormal.xy * .5 + .5, max(unitNormal.z, 0.0), 1.0);
    fillDetail = vec4(cavity, relief, alpha * alpha, field);
    return;
#endif
#ifdef plainSculptFinishFlag
    // The asset metadata proves these authored channels are zero. Let the compiler remove their reads/blends and
    // unused lighting paths. Keep this before live effects: nuclear scorch below can still emit light.
    finish.metallic = 0.0;
    finish.scatter = 0.0;
    finish.emission = vec3(0.0);
#endif
    if (!ground && u_surfaceScar > .5) groundDamage(world, face, 0.0, sand, exposed, albedo, normal, cavity, finish);
    // Scorch replaces material before rain, grid and elevation grade. Severity zero takes no material samples.
    if (u_groundScorch.x > 0.0 && volcanic < 1.0) {
        vec2 scorch = groundScorches(v_cloudPosition.xy) * exposed * (1.0 - volcanic);
        float severity = max(scorch.x, scorch.y);
        if (severity > 0.0) {
            severity += .025 * (2.0 * fine - 1.0) * 4.0 * severity * (1.0 - severity);
            float top = leaf ? 0.0 : smoothstep(.35, .65, face.z);
            vec3 sites = SCORCH_SITES;
            float height = .5, referenceRoughness = .28, ao = 1.0;
            vec3 detail = vec3(0.0, 0.0, 1.0);
            if (top > 0.0) {
                PackedTopCoordinates uv = packedTopCoordinates(rockProjection, u_scorchedMaterial.y, region);
                vec4 reference = packedTopColor(u_scorchedMaterial.x, uv);
                vec2 surface = packedTopSurface(u_scorchedMaterial.x, uv, detail);
                sites = mix(sites, scorchSites(reference.rgb, surface.x), top);
                height = mix(height, reference.a, top);
                referenceRoughness = sqrt(mix(referenceRoughness * referenceRoughness, surface.x * surface.x, top));
                ao = mix(ao, surface.y, top);
            }
            Scorched deposit = scorched(severity, sites, referenceRoughness, height);
            albedo = mix(albedo, deposit.albedo, deposit.coverage);
            float roughness = sqrt(mix(finish.roughness * finish.roughness, deposit.roughness * deposit.roughness,
                  deposit.coverage));
            // The deposit buries the material: none of its metallic, scattering or glow shows through.
            coverFinish(finish, deposit.coverage);
            finish.roughness = roughness;
            cavity = mix(cavity, ao, deposit.coverage);
            if (u_normalMaps > .5 && top > 0.0) {
                detail = normalize(vec3(detail.xy * deposit.detail, detail.z));
                normal = normalize(mix(normal, upNormal(detail, face),
                      deposit.coverage * smoothstep(.12, .75, severity) * top));
            }
            // Only a nuclear blast heats the sparse fused fissures. Painted glass is always cold.
            float heat = u_groundScorch.y;
            finish.emission += deposit.coverage * smoothstep(.78, .97, scorch.x) * top
                  * (1.0 - smoothstep(.04, .12, height)) * heat * heat * 1.1 * vec3(1.0, .12, .01);
        }
    }
    // Rain darkens exposed ground and rock, and gathers in puddles on level ground.
    float wet = u_wetness * rainCover * exposed;
    // Moisture darkens porous peat, but does not turn its entire surface into standing water. Rain
    // coating and transient puddles both respect that absorption; permanent pools remain fully wet.
    float saturated = smoothstep(0.0, .28, biomeDamp);
    float rainRetention = mix(1.0, .15, saturated);
    float rainFilm = wet * max(0.0, response);
    float film = max(rainFilm * rainRetention, biomePool);
    if (ground && wet * u_rainDetail > 0.0 && face.z > .97) {
        vec2 position = v_cloudPosition.xy * u_rainScale;
        float rainPool = rainPuddle(position, wet, max(0.0, response))
              * smoothstep(.97, .999, face.z) * u_rainDetail;
        // Ripples only where a puddle shows them; elsewhere the weight is zero and the normal stays exactly.
        vec3 rippled = rainPool > 0.0 ? normalize(vec3(rainRipples(position), 1.0)) : normal;
        normal = normalize(mix(normal, rippled, rainPool * rainRetention));
        film = max(mix(rainFilm, 1.0, rainPool) * rainRetention, biomePool);
    }
    // Biome peat/mud already contains its wet pigment. Rain only darkens the remaining unsaturated share;
    // the existing film/pool response above still receives the full rain amount.
    albedo = wetAlbedo(albedo, max(groundAbsorption(wet * (1.0 - saturated), response), shoreAbsorption));
    if (ground) albedo *= mix(1.0, terrainGrid(v_cloudPosition.xy * u_rainScale), exposed * (1.0 - volcanic));
    albedo *= transmission;
    albedo = materialReflectance(albedo);
    // Grade the completed bounded pigment: later weathering or a reflectance bound must not flatten the lift.
    // Molten material keeps its own presentation; cliffs use the continuous height set above.
    if (level != 0.0 && volcanic < 1.0) {
        float fungusGrade = family(FUNGUS_FAMILY) ? 1.0 : 0.0;
        float snowGrade = family(5.0) ? 1.0 : 0.0;
#ifdef terrainBlendFlag
        // The final material coverage includes height competition and sediment transfer at mixed contacts.
        fungusGrade = dot(materialCoverage, 1.0 - step(vec4(.5), abs(coverFamilies - FUNGUS_FAMILY)));
        snowGrade = dot(materialCoverage, 1.0 - step(vec4(.5), abs(coverFamilies - 5.0)));
#endif
        // The snow itself, not the rock or bare ground of a snowfield, grades as snow (snowLevelGrade).
        snowGrade *= smoothstep(.45, .7, dot(albedo, vec3(.2126, .7152, .0722)));
        vec3 graded = levelGrade(albedo, level, fungusGrade, 1.0 - smoothstep(0.0, .25, submerged));
        albedo = mix(mix(graded, snowLevelGrade(albedo, level), snowGrade), albedo, volcanic);
    }
#ifdef lightingFlag
    vec3 view = -viewDirection();
    normal = shadingNormal(normal, geometric, view);
    // Once per pixel, from the final normal: mapped relief, ripples and flat pools alike (light-model.glsl).
    surfaceVariance = normalVariance(dFdx(normal), dFdy(normal));
    // Thin tissue receives the shadow from its sun-facing side; other casters still occlude it.
    // Keep the single receiver-plane lookup outside the per-pixel tissue/volcanic branches.
    vec3 light = sunDirection();
    vec3 receiver = leaf && dot(face, light) < 0.0 ? -face : face;
    // Natural cliffs approximate a curved surface with shared vertex normals. Use that unmapped surface for
    // incident sidedness: a triangle cutoff exposes their tessellation at the light horizon. Planar panels and
    // other geometry retain triangle support; normal-map detail cannot move this incident hemisphere. Their mapped
    // detail fades into that horizon (terminatorShadow), never steps to dark at it; a scattering material keeps the
    // light it wraps past the horizon (shadeSurface), which this visibility would scale too.
    bool smoothSupport = cliff && !panel;
    // The share of the sun that relief finer than the pixel turns away comes off here, so zoom keeps the tone.
    // The shadow is looked up on the visible relief, about RELIEF_METRES deep, not the smooth mesh, so raised grains
    // catch the light at a shadow's edge. Hollows stay on the mesh: pushed into it they would shade themselves under
    // any sun; shadeSurface shades them by the cavity (micro-shadowing).
    float lift = max(materialHeight - .5, 0.0) * RELIEF_METRES * u_metre;
    // The grass, clods and stones along a natural edge fray its shadow, up to a quarter metre either way, in tufts of
    // 0.1 to 0.3 m. The noise's mipmaps take the fray out with distance, where it would only shimmer.
    vec2 tufts = world.xy + world.z * vec2(.6, -.4);
    vec2 fray = (texture(u_rainNoise, tufts / 19.2 + .31).gb - .5 + .5 * (texture(u_rainNoise, tufts / 6.4 + .67).rg - .5))
          * (2.0 * FRAY_METRES / 1.5);
    // shadeSurface has no directional lobe on this hemisphere unless it transmits or scatters light.
    bool receivesSun = leaf || volcanic > 0.0 || finish.scatter > 0.0
          || dot(smoothSupport ? face : geometric, light) > 0.0;
    Surface surface = opaqueSurface(albedo, normal, smoothSupport ? face : geometric, finish.roughness, film,
          sculptShadow(receiver, light, u_metre, lift, fray, receivesSun)
                * sunOnRelief(relief, dot(face, light))
                * (smoothSupport && finish.scatter <= 0.0 ? terminatorShadow(face, normal, light) : 1.0));
    // Bounded like the albedo and roughness: a degenerate projection's sample must not reach the light model.
    metallicShare(surface, clamp(finish.metallic, 0.0, 1.0));
    surface.scatter = clamp(finish.scatter, 0.0, 1.0);
    surface.occlusion = surface.specularOcclusion = occlusion;
    surface.cavity = cavity;
    // Match the leaves in terrain-foliage: they pass .35 of their colour from the other side, never a wrap lobe.
    if (leaf) surface.back = thinTransmission(albedo, .35);
    surface.heat = volcanic;
    surface.bounce = bounce;
    surface.emission = finish.emission;
    surface.regolith = lunar * (1.0 - volcanic);
    vec3 result = shadeSurface(surface, caustic, submerged);
#else
    vec3 result = albedo + finish.emission;
#endif
    // Fade through the final level above the black cap. All walls around one pit share this height reference,
    // even where their rims stand at different levels, so the fade cannot break into wedges at their corners.
    if (abyss) {
        float light = smoothstep(0.0, .9 * u_levelHeight / u_metre, surfaceUV.x);
        result *= light * light;
    }
    // The fill returned above: diagnostics must never change stored material or its later cached lighting.
    // Rocks and plants also use this shader, but are outside the terrain material cache.
    if (ground || cliff || abyss) result = mix(result, u_materialCacheDebug.rgb, u_materialCacheDebug.a);
    fragColor = vec4(result, 1.0);
}
