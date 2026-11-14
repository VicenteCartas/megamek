// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Injected into the lit vertex shader. Nothing is stored for a blade: each places itself on the ground map from its
// instance's index (groundCover and the functions before it). Blade shape and wind live here too.
// Older running clients can reload this shader before their Java-side constants are rebuilt.
#ifndef GRASS_MAX_HEIGHT
#define GRASS_MAX_HEIGHT .07
#endif
#ifndef GRASS_TUFT_BLADES
#define GRASS_TILE_METRES 8.0
#define GRASS_TILE_BLADES 448.0
#define GRASS_TUFTS 2
#define GRASS_TUFT_BLADES 112.0
#define CROP_CELLS 5
#define CROP_HEADLAND 1.2
#define CROP_ROW_METRES 1.15
#define CROP_SPACING 1.25
#define CROP_ROW_HALF_WIDTH 1.44
#define CROP_STALK_REACH .3
#endif
uniform float u_coverPixels;
// The shares its quality draws of a meadow's blades and of a field's plants (BoardQuality).
uniform vec2 u_coverShare;
uniform float u_coverHexWidth;
// The Grass height option: a multiple of every blade's height but a field's plants'.
uniform float u_grassHeight;
uniform float u_levelHeight;
uniform float u_metre;
// 1 for a field's plant, .25 for grass lit by its ground (a meadow's, tundra's sward and tussocks);
// and how far up its sward a point stands.
out vec2 v_coverData;
out vec2 v_coverRoot;
// The ground map (GpuGroundMap): how much grows on the ground under the view (red the meadow, green earth tundra's
// sward, blue the ground its tussocks stand on, alpha a field's crops), and that ground's height as its depth.
uniform sampler2D u_groundCover;
uniform sampler2D u_groundHeight;
// The map's lower left corner in the world, one over its width, and its texel, in world units.
uniform vec4 u_groundMap;
// The height of the map's depth 0 and the range down to its depth 1.
uniform vec2 u_groundLevels;
// The run of tiles being drawn (GpuGroundCover): its first tile's column, its row, and the instances each tile draws.
uniform vec3 u_coverRun;

float grassRandom(uint seed) {
    seed ^= seed >> 16;
    seed *= 0x7feb352du;
    seed ^= seed >> 15;
    seed *= 0x846ca68bu;
    seed ^= seed >> 16;
    return float(seed & 0x00ffffffu) / 16777216.0;
}

// One blade's place and what it grows as, from its tile and its index in it.
struct CoverRoot {
    vec3 position;
    // Its rank among its hex's worth of candidates: its density takes those below it.
    float rank;
    // Its ground's slope, uphill; how far a blade on a lip leans out over it, 0 to 1, and which way.
    vec2 slope;
    float lean;
    vec2 heading;
    // Earth tundra's sward rather than a meadow's blade.
    bool sward;
    // A tussock's blade: its tuft's size and its own distance from the tuft's centre, 0 to 1, heading out by `heading`.
    float tuft, reach;
};

float groundHeight(vec2 uv) { return u_groundLevels.x - textureLod(u_groundHeight, uv, 0.0).r * u_groundLevels.y; }

// The ground at a world place: the map's shares there, its height and its slope, uphill. False off the map, and on a
// step between two texels: a cliff's face or an object's side, where the map's height is neither's ground.
bool groundAt(vec2 world, out vec4 shares, out vec3 position, out vec2 slope) {
    vec2 uv = (world - u_groundMap.xy) * u_groundMap.z;
    shares = textureLod(u_groundCover, uv, 0.0);
    float texel = u_groundMap.w * u_groundMap.z, z = groundHeight(uv);
    slope = (vec2(groundHeight(uv + vec2(texel, 0.0)), groundHeight(uv + vec2(0.0, texel))) - z) / u_groundMap.w;
    position = vec3(world, z);
    return dot(slope, slope) <= 2.25 && all(greaterThan(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0)));
}

// Smooth noise of a world place, 0 to 1, from the blades' own hash: the tussocks' loose clusters.
float coverNoise(vec2 p) {
    ivec2 cell = ivec2(floor(p));
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    uint at = uint(cell.x) * 0x9E3779B9u ^ uint(cell.y) * 0x85EBCA6Bu;
    return mix(mix(grassRandom(at), grassRandom(at + 0x9E3779B9u), f.x),
          mix(grassRandom(at + 0x85EBCA6Bu), grassRandom(at + 0x9E3779B9u + 0x85EBCA6Bu), f.x), f.y);
}

// A blade places itself. Tiles are squares fixed in the world; an instance's index says which tile of the run being
// drawn and which of the tile's candidates it is, so neither a zoom nor a pan moves a blade and nothing is stored.
// It grows where the ground map's share for its kind allows, on the map's height. False where none grows.
bool groundCover(int part, out CoverRoot root) {
    int drawn = int(u_coverRun.z), tile = gl_InstanceID / drawn, index = gl_InstanceID - tile * drawn;
    ivec2 cell = ivec2(int(u_coverRun.x) + tile, int(u_coverRun.y));
    float side = GRASS_TILE_METRES * u_metre;
    uint start = uint(cell.x) * 0x9E3779B9u ^ uint(cell.y) * 0x85EBCA6Bu;
    root.slope = vec2(0.0); root.lean = 0.0; root.heading = vec2(1.0, 0.0); root.sward = false;
    root.tuft = 0.0; root.reach = 0.0;
    vec4 shares;
    if (part == 10) {
        // A tussock: a tile holds a few tufts, each anywhere in its own strip of it, carried some metres by a slow
        // drift that gathers tufts into loose clusters and leaves open ground between them. A tile's instances are a
        // blade of each tuft in turn, so a prefix thins every tuft alike.
        int blade = index / GRASS_TUFTS, strip = index % GRASS_TUFTS;
        uint tuft = start + uint(strip) * 0x27D4EB2Fu + 0x51u;
        if (grassRandom(tuft) > .9) { return false; }
        vec2 home = (vec2(cell) + vec2((float(strip) + grassRandom(tuft + 1u)) / float(GRASS_TUFTS),
              grassRandom(tuft + 2u))) * side;
        vec2 drift = vec2(coverNoise(home / u_metre / 12.0 + vec2(31.0, -17.0)),
              coverNoise(home / u_metre / 12.0 + vec2(-53.0, 71.0))) - .5;
        vec2 centre = home + drift * 10.0 * u_metre;
        vec2 uv = (centre - u_groundMap.xy) * u_groundMap.z;
        if (textureLod(u_groundCover, uv, 0.0).b <= .5) { return false; }
        uint own = tuft ^ (uint(blade) * 0x165667B1u + 0x9E3779B9u);
        root.tuft = grassRandom(tuft + 3u);
        root.reach = grassRandom(own);
        float turn = grassRandom(own + 1u) * 6.2831853;
        root.heading = vec2(cos(turn), sin(turn));
        vec2 world = centre + root.heading * root.reach * (.45 + .45 * root.tuft) * u_metre;
        root.position = vec3(world, groundHeight((world - u_groundMap.xy) * u_groundMap.z) - .001 * u_coverHexWidth);
        root.rank = float(blade) * float(GRASS_ROOTS_PER_HEX) / GRASS_TUFT_BLADES;
        return true;
    }
    if (part >= 2 && part <= 8) {
        // Maize stands on its parcel's planted rows. A cell is too small for more than two plants, and those stand
        // side by side in the planted row nearest its centre: its two candidates are that row's plants nearest the
        // centre, each if it lies in the cell. A tile's cells come in an order of its own whose every prefix is spread
        // over it (7 shares no factor with their count): a plant's rank is its place in that order.
        int cells = CROP_CELLS * CROP_CELLS, order = (index / 2 * 7 + int(start % uint(cells))) % cells;
        ivec2 at = ivec2(order % CROP_CELLS, order / CROP_CELLS);
        float size = side / u_metre / float(CROP_CELLS);
        vec2 low = (vec2(cell) + vec2(at) / float(CROP_CELLS)) * side / u_metre, centre = low + .5 * size;
        float edge;
        int code = fieldParcel(centre, edge);
        if ((code & 1) == 0) { return false; }
        float angle = float(code >> 1) * 1.0471976;
        vec2 across = vec2(cos(angle), sin(angle));
        float u = dot(centre, across), v = centre.y * across.x - centre.x * across.y;
        // The planted rows are the ground's alternate furrows; the plants along them stagger by half a step.
        int pair = int(floor(u / (2.0 * CROP_ROW_METRES) + .5)), ix = 2 * pair;
        float stagger = float(pair & 1) * CROP_SPACING * .5;
        float along = (v - stagger) / CROP_SPACING;
        int iy = int(floor(along + .5));
        // The second candidate: the row's next plant on the centre's side of the nearest.
        if ((index & 1) != 0) { iy += along >= float(iy) ? 1 : -1; }
        vec2 rows = vec2(float(ix) * CROP_ROW_METRES, float(iy) * CROP_SPACING + stagger);
        vec2 plant = rows.x * across + rows.y * vec2(-across.y, across.x);
        if (any(lessThan(plant, low)) || any(greaterThanEqual(plant, low + size))) { return false; }
        rows += (vec2(biomeHash(ivec2(ix * 7 + 1, iy * 3 - 5)), biomeHash(ivec2(ix * 5 - 3, iy * 7 + 2))) - .5) * vec2(.2, .3);
        plant = rows.x * across + rows.y * vec2(-across.y, across.x);
        // Each parcel plants only itself, clear of its headland by its leaves' reach: a plant is no farther from its
        // cell's centre than the cell's half diagonal and its own offset, so only a centre near an edge asks again.
        float clear = CROP_HEADLAND + CROP_ROW_HALF_WIDTH;
        if (edge < clear + .75 * size + .2 && (fieldParcel(plant, edge) != code || edge < clear)) { return false; }
        if (!groundAt(plant * u_metre, shares, root.position, root.slope) || shares.a < .6) { return false; }
        root.position.z -= .018 * u_metre;
        root.rank = float(index) * float(GRASS_ROOTS_PER_HEX) / float(2 * cells);
        return true;
    }
    // Grass and wheat: a tile's candidates follow a low-discrepancy sequence from the tile's own start, each a little
    // off it, so every prefix by rank is evenly spread.
    float rank = float(index);
    uint blade = start ^ (uint(index) * 0x27D4EB2Fu + (part == 1 ? 0x2545F491u : 0x165667B1u));
    vec2 place = fract(vec2(grassRandom(start + uint(part)), grassRandom(start + 1u + uint(part)))
          + rank * vec2(.7548776662, .5698402910))
          + (vec2(grassRandom(blade), grassRandom(blade + 7u)) - .5) * (.6 / sqrt(GRASS_TILE_BLADES));
    vec2 world = (vec2(cell) + place) * side;
    float chance = grassRandom(blade + 13u);
    root.rank = rank * float(GRASS_ROOTS_PER_HEX) / GRASS_TILE_BLADES;
    if (part == 1) {
        // A wheat stalk stands anywhere in its parcel, clear of the headland by its ear's reach.
        vec2 uv = (world - u_groundMap.xy) * u_groundMap.z;
        if (textureLod(u_groundCover, uv, 0.0).a < .6) { return false; }
        float edge;
        if ((fieldParcel(world / u_metre, edge) & 1) != 0 || edge < CROP_HEADLAND + CROP_STALK_REACH) { return false; }
        if (!groundAt(world, shares, root.position, root.slope)) { return false; }
        root.position.z -= .018 * u_metre;
        return true;
    }
    // A meadow's blade grows by the near end of its chance, the sward by the far end: the candidates the meadow leaves.
    vec2 uv = (world - u_groundMap.xy) * u_groundMap.z;
    vec2 grows = textureLod(u_groundCover, uv, 0.0).rg;
    bool meadow = grows.r > chance;
    root.sward = !meadow && grows.g > 1.0 - chance;
    if (!meadow && !root.sward || !groundAt(world, shares, root.position, root.slope)) { return false; }
    root.position.z -= .001 * u_coverHexWidth;
    if (meadow) {
        // A lip: the ground a metre and a half on falls away by more than the slope here. The blade leans out that way
        // as far as the ground falls: standing behind the lip, arching over it, hanging down a face.
        float ahead = 1.5 * u_metre, reach = ahead * u_groundMap.z, z = root.position.z;
        vec4 around = vec4(groundHeight(uv - vec2(reach, 0.0)), groundHeight(uv + vec2(reach, 0.0)),
              groundHeight(uv - vec2(0.0, reach)), groundHeight(uv + vec2(0.0, reach)));
        vec2 drop = z - vec2(min(around.x, around.y), min(around.z, around.w)), way = around.xz - around.yw;
        float fall = max(drop.x, drop.y) - 1.5 * length(root.slope) * ahead;
        if (fall > 0.0 && dot(way, way) > 0.0) {
            root.lean = clamp(fall / (.45 * ahead), 0.0, 1.0);
            root.heading = normalize(way);
        }
    }
    return true;
}

// False when this blade is beyond the density its projected size allows, or grows nowhere: the caller skips the rest of
// the program. A run draws the candidates its nearest tile needs; farther tiles need fewer of them.
bool grassBlade(vec3 samplePoint, out vec3 position, out vec3 normal, out vec4 color) {
    // A template's vertices carry their part in z (GpuGroundCover): 0 grass, a meadow's blade or earth tundra's sward;
    // 1 a wheat stalk of a field's stand, upright and stiff, its ear between WHEAT_EAR and the awns closing it; 2 a
    // maize plant's stalk and tassel and 3 to 8 its leaves; 10 a tundra tussock's blade.
    int part = int(samplePoint.z + .5);
    CoverRoot place;
    if (!groundCover(part, place)) { return false; }
    vec3 root = place.position;
    bool tussock = part == 10, wheat = part == 1, crop = part >= 1 && part <= 8, grass = part == 0, sward = place.sward;
    // A blade on a lip hangs out over it.
    bool rim = grass && place.lean > 0.0;
    float pixels = u_coverPixels / max(.001, abs((u_projViewTrans * vec4(root, 1.0)).w));
    // A field's plants thin out as they shrink, each survivor widening by as much, until they fade into the ground's
    // crop colour (terrain-crops.glsl); so does a tussock once it has stood up. Grass only thins out, over the
    // ground's own grass.
    float kept = crop ? cropShare(!wheat, pixels) : 1.0;
    float share = crop ? u_coverShare.y * kept : tussock ? clamp(pixels / GRASS_TUFT_KEEP, .001, 1.0) : u_coverShare.x;
    // Survivors widen by a far plant's or a tussock's whole thinning, and by the root of what a level (or a meadow) leaves out.
    float widening = crop ? kept * sqrt(u_coverShare.y) : tussock ? share : sqrt(share);
    float density = (crop ? share * cropPlants(pixels) : tussock ? share * smoothstep(GRASS_TUFT_START, GRASS_TUFT_STAND, pixels)
          : share * smoothstep(GRASS_START_PIXELS, GRASS_FULL_PIXELS, pixels)) * float(GRASS_ROOTS_PER_HEX);
    if (density <= place.rank) { return false; }
    // The ranks between two candidates: the last one standing grows in over them.
    float apart = tussock ? float(GRASS_ROOTS_PER_HEX) / GRASS_TUFT_BLADES : float(GRASS_ROOTS_PER_HEX) / GRASS_TILE_BLADES;
    uint seed = floatBitsToUint(root.x) ^ (floatBitsToUint(root.y) * 1664525u) ^ uint(place.rank);
    // Reject the whole rooted blade, not fragments of a swaying tip.
    float scar = max(groundScarCover(groundDamageAt(root.xy)), scorchedGrass(root.xy));
    if (scar > grassRandom(seed + 83u)) { return false; }
    vec2 slope = place.slope;
    // A lip's blades fan out within some 40 degrees of their heading down the bank; a tussock's head out from its centre.
    float angle = rim ? atan(place.heading.y, place.heading.x) + (grassRandom(seed) - .5) * 1.38
          : tussock ? atan(place.heading.y, place.heading.x) + (grassRandom(seed) - .5) * .63 : grassRandom(seed) * 6.2831853;
    float variation = grassRandom(seed + 23u);
    vec2 direction = vec2(cos(angle), sin(angle));
    vec3 side = vec3(-direction.y, direction.x, 0.0);
    if (crop && !wheat) {
        // Maize, as tall as the stand its slab shows further away (GpuBiomeVegetation): a stalk swaying a little with
        // its tassel above .9, and six leaves alternating up it, arching out from their nodes, widest midway and
        // drooping at their tips, which flutter in the wind. A plant this size grows in over its last 512 ranks,
        // widening rather than appearing.
        float plant = u_levelHeight * CROP_HEIGHT * mix(.92, 1.06, variation) * (1.0 - scar * .5);
        float growth = clamp((density - place.rank) / 512.0, 0.0, 1.0) / widening;
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
        height = u_coverHexWidth * GRASS_MAX_HEIGHT * mix(.95, 1.5, place.tuft) * mix(.75, 1.0, variation);
        width = max(u_coverHexWidth * mix(.0022, .0036, grassRandom(seed + 37u)), .75 * u_coverHexWidth / pixels)
              * (.4 + .6 * smoothstep(0.0, .25, t)) * (1.0 - t);
    }
    if (!wheat) { height *= u_grassHeight; }
    height *= 1.0 - scar * .5;
    // Fractional growth of the last blade keeps density transitions continuous, without shading invisible blades.
    float growth = clamp((density - place.rank) / apart, 0.0, 1.0) / widening;
    float gust = vegetationGust(root.xy);
    vec3 tip, control;
    if (tussock) {
        // Blades at the tuft's centre stand; towards its rim they lean out and arch over, their tips drooping to the ground.
        float lean = place.reach, droop = mix(.3, 1.0, grassRandom(seed + 51u));
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
        if (rim) {
            // Behind the lip a blade stands, leaning out; past it nothing holds a blade up: it arches out over the edge
            // and its free end hangs under its own weight, as far as the ground falls away below it, swinging with the gusts.
            float hang = mix(.2, .8, grassRandom(seed + 51u));
            vec3 over = height * (vec3(direction * (.85 - .3 * hang), -.75 * hang) + vec3(u_wind.xy * u_wind.z * (.15 + .1 * gust), 0.0));
            tip = mix(tip, over, place.lean);
            control = mix(control, height * vec3(direction * .3, mix(.35, .6, grassRandom(seed + 59u))), place.lean);
        }
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
    normal = tussock ? normalize(vec3(direction * (.25 + .55 * place.reach), 1.0))
          : grass ? normalize(vec3(-slope + .25 * vec2(cos(angle), sin(angle)), 1.0)) : normalize(cross(side, tangent));
    float tone = grassRandom(seed + 71u);
    color = vec4(wheat ? (t > WHEAT_EAR.x ? cropHead(false, tone) : cropStalk(false, tone))
          // The first blades to stand, far apart, keep to their thatch's colour: sunlit tips come with the full sward.
          : sward ? tundraSwardColor(stands, mix(.55, .4 + .6 * smoothstep(0.0, .6, t),
                smoothstep(GRASS_START_PIXELS, GRASS_FULL_PIXELS, pixels)), tone)
          // A tuft is dark at its heart and pale at its tips.
          : tussock ? tundraSwardColor(stands, .2 + .8 * smoothstep(0.0, .7, t), tone) * mix(.6, 1.0, smoothstep(0.0, .35, t))
          // A lip's blades hang half under the sward above them, in the meadow's darker tones.
          : mix(toLinear(vec3(.24, .30, .10)), toLinear(vec3(.44, .48, .21)), tone * mix(1.0, .6, place.lean)), 1.0);
    color.rgb = mix(color.rgb, toLinear(vec3(.18, .14, .08)), scar * .75);
    v_coverData = vec2(wheat ? 1.0 : grass || tussock ? .25 : 0.0, t);
    v_coverRoot = root.xy / u_worldMetre;
    return true;
}
