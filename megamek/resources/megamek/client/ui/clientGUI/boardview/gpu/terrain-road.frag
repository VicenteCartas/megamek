#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Roads, loose verges, bridge decks and tunnel floors.
// DefaultShader owns the uniforms, material binding and directional shadow map.

in vec2 v_diffuseUV;
in vec3 v_normal;
in vec4 v_color;
uniform sampler2D u_diffuseTexture;
#if defined(normalTextureFlag) || defined(roadCoatFlag) || defined(roadMapsFlag)
uniform float u_normalMaps;
#endif
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
#endif
uniform float u_groundResponse;
#ifdef roadMaskFlag
uniform sampler2D u_roadMask;
flat in vec4 v_roadMaskRegion;
in vec2 v_roadMaskUV;
#endif
// A road material's repeat at the width it is catalogued for (BoardRelief.detailMetres(1)).
uniform float u_worldMetre;
#ifdef bridgeDeckFlag
uniform float u_bridgeDeckCoverage; // GpuAssets.roadCoverage of the deck's road texture
#endif
#ifdef roadMapsFlag
uniform sampler2D u_roadSurface;
uniform float u_roadTransition;
uniform float u_roadFinish; // the material's place in GpuRoads.MATERIALS, -1 for none
#endif
#if defined(roadMapsFlag) || defined(roadCoatFlag)
// Per road material, its heightmap parallax as Godot draws it: the depth along U and V in UV units (heightmap_scale /
// 100, negative where Godot flips the tangent or binormal); the deep parallax's minimum and maximum layers, 0 for one
// step.
uniform vec4 u_roadRelief[FINISH_COUNT - ROAD_FINISH];
#endif
#if defined(roadMapsFlag) || defined(roadCoatFlag)
uniform vec4 u_roadProfile;
#endif
#ifdef roadCoatFlag
// Every coat of a chunk in one draw. Road maps: colour, normal and surface per material; sculpt maps: colour and
// normal per material. Each coat's own choice, finish and wet response come from its vertices (GpuRoads#coat).
uniform sampler2DArray u_roadColors, u_roadData;
uniform sampler2DArray u_sculptColors, u_sculptData;
// The finish layers follow the sculpt normal/AO layers (finish-arrays.glsl).
#define FINISH_LAYERS u_sculptData
flat in vec4 v_roadCoat;
#endif

// ground-surface-functions
// Those shared functions are inserted just before main, after the helpers below.
vec3 groundNormal(vec2 uv, float strength);
#ifdef roadCoatFlag
vec3 mappedNormal(vec4 texel, float strength);
#endif
vec3 toLinear(vec3 c);
// Paved wear's noise reads the shared rain noise, which those functions declare: it follows main.
vec4 wearNoise(vec2 m, float size, float offset, float pixel);

// A surface's maps and settings: from its material, or for a merged coat from its vertices.
struct RoadMaps {
    int maps;           // a merged coat's road material, or 3 plus its sculpt material
    float transition;   // positive: material joins; negative: loose, wheels, landings (GpuRoads#attribute)
    float response;     // wet response; negative excludes the surface from liquid water
    bool mapped;        // has a surface map: grain, roughness and occlusion
    bool soil;          // compact soil, which holds mud and shallow water
};

RoadMaps roadMaps() {
#ifdef roadCoatFlag
    vec4 coat = floor(v_roadCoat * 255.0 + .5);
    int maps = int(coat.r);
    return RoadMaps(maps, coat.g - 4.0, coat.b / 255.0, maps < 3, maps == 1);
#else
    RoadMaps m = RoadMaps(-1, 0.0, u_groundResponse, false, false);
#ifdef roadMapsFlag
    m.transition = u_roadTransition;
    m.mapped = true;
#endif
#ifdef roadSoilFlag
    m.soil = true;
#endif
    return m;
#endif
}

vec4 roadAlbedo(RoadMaps m, vec2 uv) {
#ifdef roadCoatFlag
    return m.mapped ? texture(u_roadColors, vec3(uv, float(m.maps)))
          : texture(u_sculptColors, vec3(uv, float(m.maps - 3)));
#else
    return texture(u_diffuseTexture, uv);
#endif
}

// Only a surface with maps has one; see RoadMaps.mapped.
vec4 roadSurface(RoadMaps m, vec2 uv) {
#ifdef roadCoatFlag
    return texture(u_roadData, vec3(uv, float(m.maps * 2 + 1)));
#elif defined(roadMapsFlag)
    return texture(u_roadSurface, uv);
#else
    return vec4(.5);
#endif
}

vec3 roadNormal(RoadMaps m, vec2 uv, float strength) {
#ifdef roadCoatFlag
    return mappedNormal(m.mapped ? texture(u_roadData, vec3(uv, float(m.maps * 2)))
          : texture(u_sculptData, vec3(uv, float(m.maps - 3))), strength);
#else
    return groundNormal(uv, strength);
#endif
}

#if defined(roadMapsFlag) || defined(roadCoatFlag)
// The material's place in the board's finish arrays (finish-arrays.glsl); -1 for a road material they lack.
int roadFinish(RoadMaps m) {
#ifdef roadCoatFlag
    return m.mapped ? ROAD_FINISH + m.maps : m.maps - 3;
#else
    return u_roadFinish < -.5 ? -1 : ROAD_FINISH + int(u_roadFinish + .5);
#endif
}

// Godot's heightmap parallax over a mapped road material's relief (its surface map's R, white high): the view, in the
// UV frame, moves its colour, normal, surface and finish reads. Deep parallax marches from the maximum layers at grazing
// views to the minimum head-on, then interpolates the crossing.
// A mapped material's height (its surface map's R) with explicit gradients: the march below diverges per pixel.
float roadHeight(RoadMaps m, vec2 uv, mat2 gradient) {
#ifdef roadCoatFlag
    return textureGrad(u_roadData, vec3(uv, float(m.maps * 2 + 1)), gradient[0], gradient[1]).r;
#else
    return textureGrad(u_roadSurface, uv, gradient[0], gradient[1]).r;
#endif
}

// dx and dy are the surface position's derivatives; view points from it to the eye.
vec2 roadParallax(RoadMaps m, vec2 uv, mat2 gradient, vec3 face, vec3 dx, vec3 dy, vec3 view) {
    int material = roadFinish(m) - ROAD_FINISH;
    float determinant = gradient[0].x * gradient[1].y - gradient[0].y * gradient[1].x;
    // The Normal maps switch takes the parallax with the normal maps.
    if (u_normalMaps <= .5 || !m.mapped || material < 0 || abs(determinant) < 1e-12) return vec2(0.0);
    vec4 relief = u_roadRelief[material];
    vec3 tangent = (dx * gradient[1].y - dy * gradient[0].y) / determinant;
    vec3 bitangent = (dy * gradient[0].x - dx * gradient[1].x) / determinant;
    vec3 v = normalize(vec3(dot(view, normalize(tangent)), dot(view, normalize(bitangent)), dot(view, face)));
    vec2 reach = v.xy * relief.xy;
    // A relief shallower than half a pixel moves nothing visible: skip its reads.
    if (dot(reach, reach) < .25 * max(dot(gradient[0], gradient[0]), dot(gradient[1], gradient[1]))) return vec2(0.0);
    float depth = 1.0 - roadHeight(m, uv, gradient);
    if (relief.w < 1.0) return -reach * depth;
    float layers = mix(relief.w, relief.z, abs(v.z));
    vec2 delta = reach / layers;
    vec2 shift = vec2(0.0);
    float current = 0.0;
    for (int layer = 0; layer < 128 && current < depth; layer++) {
        shift -= delta;
        depth = 1.0 - roadHeight(m, uv + shift, gradient);
        current += 1.0 / layers;
    }
    float after = depth - current;
    float before = 1.0 - roadHeight(m, uv + shift + delta, gradient) - current + 1.0 / layers;
    return after - before < -1e-6 ? mix(shift, shift + delta, after / (after - before)) : shift;
}
#endif

vec4 roadProfile() {
#if defined(roadMapsFlag) || defined(roadCoatFlag)
    return u_roadProfile;
#else
    return vec4(1.0);
#endif
}

// ---- Paved wear -----------------------------------------------------------------------------------------------------
// A paved carriageway ages, carries traffic and breaks down; its core coat shows it, under the paint (GpuRoads#patches).
// That coat's mask carries the traffic's heading (RG; its length falls to a half across a junction's hub,
// BoardRoad#wearHub) and where across its lane a point lies (B, BoardRoad#wearLane: 0 the lane's centre, 1 its edge, the
// wheel paths at WHEEL_PATH). World-anchored fields place every feature and the heading only orients them,
// symmetrically, so neighbouring hexes agree whichever way their paths run. Sizes are metres.
uniform float u_metre;       // world units per metre

const float WHEEL_PATH = .4;  // BoardRoad.WHEEL_OFFSET over half of Kind.PAVED.halfWidth
const float LANE_METRES = 2.7; // half of Kind.PAVED.halfWidth, in metres: B's full range
const float HUB_WHEEL = .6;    // turning traffic crosses a junction's hub everywhere: it wears evenly

bool pavedWear(RoadMaps m) {
#if (defined(roadMapsFlag) || defined(roadCoatFlag)) && defined(roadMaskFlag)
    return m.mapped && abs(m.transition + 2.0) < .5 && roadFinish(m) == ROAD_FINISH;
#else
    return false;
#endif
}

vec4 wearHash(vec2 cell) {
    vec4 p = fract(cell.xyxy * vec4(.1031, .1030, .0973, .1099));
    p += dot(p, p.wzxy + 33.33);
    return fract((p.xxyz + p.yzzw) * p.zywx);
}

// A line of half width w at distance d, averaged over a pixel: a line thinner than the pixel fades rather than aliases.
float wearLine(float d, float w, float pixel) {
    float drawn = max(w, pixel * .5);
    return clamp((drawn - abs(d)) / pixel + .5, 0.0, 1.0) * (w / drawn);
}

// The distance to the nearest border of a grid of jittered cells: half the gap between the two nearest points.
float crackBorder(vec2 p) {
    vec2 g = floor(p), f = p - g;
    float d1 = 8.0, d2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 r = vec2(x, y) + .15 + .7 * wearHash(g + vec2(x, y)).xy - f;
            float d = dot(r, r);
            if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
        }
    }
    return (sqrt(d2) - sqrt(d1)) * .5;
}

// What a pit exposes: the gravel road's aggregate where the merged coats carry its maps, else darkened asphalt.
// Explicit gradients (the road's UV over the pixel): asphaltWear runs only where the paint shows.
vec3 pitAggregate(vec2 uv, mat2 gradient, vec3 asphalt) {
#ifdef roadCoatFlag
    return textureGrad(u_roadColors, vec3(uv * 1.7, 2.0), gradient[0] * 1.7, gradient[1] * 1.7).rgb * .62;
#else
    return asphalt * vec3(1.5, 1.35, 1.15);
#endif
}

// The asphalt's wear at m (metres, Y flipped as the road UVs) over a pixel of `pixel` metres: its albedo and surface
// (R height, G roughness, B cavity), and the slope its damage gives the normal (in m's frame). Lane-bound and oriented
// marks give way to even wear across a junction's hub. Wear never makes the surface glossier: it would glint in the sun.
// Returns how much water it holds: ruts, cracks and potholes fill first.
float asphaltWear(vec2 m, float pixel, vec2 heading, float lane, float hub, vec2 uv, mat2 gradient, inout vec3 albedo,
      inout vec4 surface, out vec2 slope) {
    slope = vec2(0.0);
    vec2 across = vec2(-heading.y, heading.x);
    float along = dot(m, heading);
    float grain = surface.r;
    // Broad state: how old a stretch is (its binder oxidises from black to grey) and how far it has broken down.
    float age = wearNoise(m, 173.0, .61, pixel).g;
    float decay = smoothstep(.36, .76, wearNoise(m, 61.0, .29, pixel).r * .6 + age * .4);
    float oxidised = smoothstep(.2, .8, age);
    float fine = wearNoise(m, 2.3, .43, pixel).b;
    float luma = dot(albedo, vec3(.2126, .7152, .0722));
    albedo = mix(albedo, vec3(luma), .55 * oxidised) * mix(.8, 1.3, oxidised);
    float wheel = mix(1.0 - smoothstep(.07, .2, abs(lane - WHEEL_PATH)), HUB_WHEEL, hub);
    float marks = 1.0 - hub;
    // Old binder loosens its aggregate between the wheel paths: open texture.
    float ravel = smoothstep(.55, .85, age * .6 + fine * .4) * (1.0 - wheel);
    albedo *= 1.0 + ravel * (grain - .5) * .6;
    surface.g = min(.98, surface.g + .08 * ravel);
    // Traffic polishes the wheel paths dark and smooth, streaked along with rubber; the paths sink into ruts.
    vec2 streak = vec2(along / 7.0, dot(m, across) * 1.3);
    float rubbed = .5 * (wearNoise(streak, 1.0, 0.0, pixel * 1.3).r + wearNoise(-streak, 1.0, 0.0, pixel * 1.3).r);
    rubbed = mix(rubbed, wearNoise(m, 1.3, .21, pixel).r, hub);
    float polish = wheel * mix(.45, 1.0, rubbed);
    albedo *= mix(1.0, .62, polish * mix(.55, 1.0, oxidised));
    float hollow = wheel * decay * .6;
    // Binder squeezed up in hot wheel paths: black.
    float bleed = wheel * smoothstep(.74, .9, wearNoise(m, 9.0, .17, pixel).g);
    albedo = mix(albedo, albedo * .42, bleed);
    // Engines drip down the middle of each lane.
    vec2 dripUV = vec2(along / 2.2, dot(m, across) * 3.0);
    float drops = .5 * (wearNoise(dripUV + .3, 1.0, 0.0, pixel * 3.0).g + wearNoise(.3 - dripUV, 1.0, 0.0, pixel * 3.0).g);
    float drip = (1.0 - smoothstep(.06, .17, lane)) * smoothstep(.45, .78, drops) * marks;
    albedo *= mix(1.0, .55, drip);
    // Grit and dust gather where no wheel sweeps: along the kerbs and the middle.
    float grit = smoothstep(.82, .99, lane) * smoothstep(.3, .7, fine) * .55 * marks;
    albedo = mix(albedo, toLinear(vec3(.44, .41, .36)) * (.8 + .4 * grain), grit);
    surface.g = mix(surface.g, .95, grit);
    float crack = 0.0, sealant = 0.0, fresh = 0.0, seam = 0.0, cut = 0.0, pit = 0.0, skid = 0.0, stain = 0.0;
    if (pixel < .35) {
        // The longitudinal joint between the lanes opens in stretches (the lane edge also runs along the kerb).
        float joint = wearLine((1.0 - lane) * LANE_METRES, .008 + .01 * decay, pixel);
        crack = max(crack, joint * smoothstep(.45, .7, wearNoise(m, 5.0, .53, pixel).b)
              * smoothstep(.15, .5, decay + oxidised * .3) * marks);
    }
    // Features of 8 m cells, each in its own frame along the traffic: thermal cracks across the road, sealed or open;
    // repairs, utility trenches, potholes, skid marks and oil stains.
    vec2 g = floor(m / 8.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 cell = g + vec2(x, y);
            vec4 h = wearHash(cell), k = wearHash(cell + 71.3);
            vec2 r = m - (cell + .2 + .6 * h.xy) * 8.0;
            float u = dot(r, heading), v = dot(r, across);
            float kind = h.z;
            if (kind < .35 + .3 * oxidised) {
                if (abs(v) > 7.0 || pixel > .35) continue;
                float d = u + (wearNoise(m, 1.9, h.w, pixel).r - .5) * .4;
                float line = wearLine(d, k.x < .45 ? .045 : .008 + .012 * decay, pixel)
                      * smoothstep(7.0, 5.5, abs(v) + k.y * 3.0) * marks;
                if (k.x < .45) sealant = max(sealant, line); else crack = max(crack, line);
            } else if (kind < .7) {
                continue;
            } else if (kind < .78) {
                // A repair (a rectangle of newer asphalt) or a trench across the road, refilled with asphalt or
                // concrete; their saw cuts are sealed.
                vec2 extent = kind < .75 ? vec2(.7 + 2.2 * k.y, .6 + 1.6 * k.z) : vec2(.3 + .25 * k.y, 7.0);
                float inside = min(extent.x - abs(u), extent.y - abs(v));
                if (inside < -.05) continue;
                float area = clamp(inside / pixel + .5, 0.0, 1.0);
                if (kind > .75 && k.w < .5) cut = max(cut, area); else fresh = max(fresh, area);
                seam = max(seam, wearLine(inside, .025, pixel));
            } else if (kind < .78 + .1 * decay) {
                float radius = .25 + .55 * k.y;
                float d = length(r) + (wearNoise(m, .6, h.w, pixel).r - .5) * .3 * radius;
                float inside = smoothstep(radius, radius - .06, d);
                pit = max(pit, inside);
                // The pit's wall faces inwards.
                slope -= r / max(length(r), 1e-4) * (inside - smoothstep(radius - .06, radius - .2, d)) * .9;
            } else if (kind < .92 && kind > .87) {
                // One vehicle's braking: its two tyres' streaks, broken and fading at both ends.
                float reach = 2.0 + 4.0 * k.y;
                float streak = (1.0 - smoothstep(.07, .13, abs(abs(v) - .9))) * (1.0 - smoothstep(reach * .5, reach, abs(u)));
                skid = max(skid, streak * mix(.4, 1.0, wearNoise(m, .7, h.w, pixel).r) * .8 * marks);
            } else if (kind > .95) {
                float radius = .5 + 1.1 * k.y;
                float d = length(r) + (wearNoise(m, .9, h.w, pixel).g - .5) * .5 * radius;
                stain = max(stain, smoothstep(radius, radius * .6, d) * mix(.5, 1.0, k.z));
            }
        }
    }
    // Weathered stretches crack into blocks of a few metres, sealed into black bands where the road is maintained; the
    // decayed wheel paths into small fatigue cells.
    float blocks = smoothstep(.5, .8, age * .5 + decay * .5);
    if (blocks > 0.0 && pixel < .3) {
        vec2 q = m / 2.6 + (vec2(wearNoise(m, 3.1, .11, pixel).r, wearNoise(m, 3.1, .63, pixel).g) - .5) * .45;
        float border = crackBorder(q) * 2.6, present = blocks * smoothstep(.35, .6, wearNoise(m, 7.0, .71, pixel).b);
        float sealed = smoothstep(.45, .6, wearNoise(m, 40.0, .37, pixel).g);
        crack = max(crack, wearLine(border, .007 + .008 * decay, pixel) * present * (1.0 - sealed));
        sealant = max(sealant, wearLine(border, .03, pixel) * present * sealed);
    }
    float fatigue = wheel * smoothstep(.5, .85, decay + (fine - .5) * .4);
    if (fatigue > 0.0 && pixel < .06) {
        vec2 q = m / .32 + (vec2(wearNoise(m, .5, .37, pixel).r, wearNoise(m, .5, .81, pixel).g) - .5) * .5;
        crack = max(crack, wearLine(crackBorder(q) * .32, .004, pixel) * fatigue);
    }
    albedo *= mix(1.0, .9, fatigue);
    // Repairs and trenches: newer binder, smoother and darker; some trenches are filled with concrete.
    vec3 newer = mix(albedo, vec3(dot(albedo, vec3(.2126, .7152, .0722))), .3) * .6;
    albedo = mix(albedo, newer, fresh);
    albedo = mix(albedo, toLinear(vec3(.47, .46, .43)) * (.8 + .4 * grain), cut * .8);
    // Open cracks are dark slits; sealed ones, and the saw cuts round repairs, black bands.
    sealant = max(sealant, seam);
    albedo = mix(albedo, albedo * .15, crack);
    albedo = mix(albedo, toLinear(vec3(.055)), sealant);
    albedo = mix(albedo, albedo * .35, skid);
    albedo = mix(albedo, albedo * .5, stain);
    // A pothole: broken through to the base course, its aggregate shadowed deeper in.
    albedo = mix(albedo, pitAggregate(uv, gradient, albedo) * mix(.55, 1.0, grain), pit);
    surface.g = mix(surface.g, .92, pit);
    surface.b = mix(surface.b, surface.b * .55, pit);
    hollow = max(hollow, max(pit, crack * .8));
    surface.r = mix(surface.r, 0.0, hollow);
    return hollow;
}

// Road paint wears with its road, most on weathered stretches (asphaltWear's age): how much has flaked off (x), and
// how dull and grimy the rest is (y).
vec2 paintWear(vec2 m, float pixel) {
    float worn = smoothstep(.15, .85, wearNoise(m, 173.0, .61, pixel).g);
    float flakes = wearNoise(m, .9, .13, pixel).r * .6 + wearNoise(m, .23, .57, pixel).g * .4;
    return vec2(worn * smoothstep(.78 - .32 * worn, .92 - .3 * worn, flakes), worn);
}

void main() {
    vec4 roadColor = v_color;
    // Off its mask a fragment is discarded at the end: it keeps its neighbours' lanes through the material's filtering
    // and fwidth, but skips the asphalt's wear and the lighting (whose derivatives are taken below, before them).
    bool roadHidden = false;
#ifdef roadMaskFlag
    vec2 maskUV = v_roadMaskUV;
    roadColor = texture(u_roadMask, v_roadMaskRegion.xy + maskUV * v_roadMaskRegion.zw);
    roadHidden = any(lessThan(maskUV, vec2(0.0))) || any(greaterThan(maskUV, vec2(1.0))) || roadColor.a < .002;
#if __VERSION__ >= 450
    // A quad wholly off its mask draws nothing: it stops before the material. Fine derivatives make it the quad's
    // decision (one shown lane keeps all four), so no lane below loses a neighbour its derivatives read.
    float shown = roadHidden ? 0.0 : 1.0;
    float row = shown + abs(dFdxFine(shown));
    if (row + abs(dFdyFine(row)) == 0.0) discard;
#endif
#endif
    vec3 geometric = visibleFace(dFdx(v_cloudPosition), dFdy(v_cloudPosition), -viewDirection());
    // Before the mask's discard, like the face above.
    vec3 face = normalize(v_normal);
    surfaceVariance = normalVariance(dFdx(face), dFdy(face));
    vec2 uv = v_diffuseUV;
#ifdef bridgeDeckFlag
    // A rotated bridge shares the adjacent road's scale, orientation and texture phase, widened with its texture.
    uv = vec2(v_cloudPosition.x, -v_cloudPosition.y) / (u_worldMetre * u_bridgeDeckCoverage);
#endif
    mat2 roadGradient = mat2(dFdx(uv), dFdy(uv));
    // Use, mud and loose clumps are anchored to the ground: they keep their size in metres whatever ground a material's
    // texture covers. The material's own maps, magnified or not, take uv, so its grain keeps its size too.
    vec2 groundUV = vec2(v_cloudPosition.x, -v_cloudPosition.y) / u_worldMetre;
    RoadMaps m = roadMaps();
    // Paved wear's ground in metres and the pixel it covers, taken before the varying branches.
    vec2 wearMetres = vec2(v_cloudPosition.x, -v_cloudPosition.y) / u_metre;
    float wearPixel = max(length(dFdx(wearMetres)), length(dFdy(wearMetres)));
    bool paved = pavedWear(m);
    // The material's own reads take its parallax; world-anchored patterns and its other grain scales do not.
    vec2 materialUV = uv;
#if defined(roadMapsFlag) || defined(roadCoatFlag)
    materialUV += roadParallax(m, uv, roadGradient, face, dFdx(v_cloudPosition), dFdy(v_cloudPosition), -viewDirection());
#endif
    vec3 tint = roadColor.rgb;
    if (m.mapped && (m.transition > .5 || m.transition < -1.5)) { tint = vec3(1.0); }
#ifdef roadMaskFlag
    // Ordinary masks repeat one authored tint at every texel, including transparent borders, so filtering cannot
    // change it. Their shared atlas also holds join coordinates and wheel directions, which must remain data.
    else { tint = toLinear(tint); }
#endif
    vec3 albedo = roadAlbedo(m, materialUV).rgb * tint;
    vec4 properties = m.mapped ? roadSurface(m, materialUV) : vec4(.5);
    float roadCoverage = 1.0;
    bool wheelWear = m.mapped && m.transition < -1.5 && m.transition > -2.5 && !paved;
    // Reuse the shared, stable noise field. These masks describe use and loose material, not road width.
    float wearPatches = 0.0, loosePatches = 0.0, compaction = 0.0;
    vec2 wearSlope = vec2(0.0);
    float wearWater = 0.0;
    if (paved) {
        vec2 heading = (roadColor.rg * 255.0 - 128.0) / 127.0;
        float hub = smoothstep(.04, .46, 1.0 - length(heading));
        wearWater = roadHidden ? 0.0 : asphaltWear(wearMetres, wearPixel, normalize(heading), roadColor.b, hub,
              materialUV, roadGradient, albedo, properties, wearSlope);
    } else if (m.mapped && abs(m.transition) > .5) {
        wearPatches = texture(u_rainNoise, groundUV / 18.0).g;
        loosePatches = texture(u_rainNoise, groundUV / 5.0 + .37).b;
        albedo *= mix(.95, 1.05, wearPatches);
        if (wheelWear) {
            vec2 heading = normalize((roadColor.rg * 255.0 - 128.0) / 127.0);
            vec2 scuff = vec2(dot(groundUV, heading), dot(groundUV, vec2(-heading.y, heading.x)))
                  * vec2(1.0 / 36.0, 1.0 / 3.0);
            // Averaging both directions gives neighbouring hexes identical wear even when their paths run
            // opposite ways. The pair's geometry fixes axle spacing; this only varies compaction within it.
            loosePatches = .5 * (texture(u_rainNoise, scuff).b + texture(u_rainNoise, -scuff).b);
            vec2 broken = scuff * vec2(3.5, 2.2);
            float clods = .5 * (texture(u_rainNoise, broken).g + texture(u_rainNoise, -broken).g);
            compaction = smoothstep(.28, .68, loosePatches) * smoothstep(.18, .6, wearPatches);
            // Repeated vehicle passes leave intermittent compaction, sometimes dusty, sometimes darker.
            // Broad feathered swaths replace the old continuously dark, narrow "rails".
            albedo *= mix(.77, 1.32, loosePatches) * mix(.88, 1.12, clods);
            properties.g = mix(properties.g, .76, compaction);
            wearPatches *= mix(.4, 1.6, clods);
        }
    }
    if (m.mapped && m.transition > .5) {
        vec4 profile = roadProfile();
        float along = (roadColor.r - .5) * 2.0 * profile.x;
        float across = abs((roadColor.g - .5) * 2.0 * profile.x);
        float rut = 1.0 - smoothstep(profile.z * .5, profile.z, abs(across - profile.y));
        float grain = properties.r;
        float broad = roadSurface(m, uv * .21).r;
        // A full-width construction edge, chipped only at a small scale.
        float seam = along + (broad - .5) * .8 + (grain - .5) * .6;
        float body = smoothstep(-.7, .5, seam);
        float carry = 1.0 - smoothstep(0.0, profile.x, -along);
        float dust = (.10 + .15 * broad) * carry * carry;
        if (m.transition > 1.5) {
            // Soil dragged by tyres continues onto asphalt at the same axle spacing; no central blotch.
            float tracked = rut * (.4 + .6 * grain) * pow(carry, 1.3);
            roadCoverage = max(body, dust + .8 * tracked);
            albedo *= mix(1.0, profile.w, rut * body);
        } else {
            // Crushed aggregate leaves discrete grains as well as a little mineral dust.
            float stones = smoothstep(.78 - .3 * carry, .90 - .3 * carry, grain) * carry;
            roadCoverage = max(body, dust + stones);
        }
        roadCoverage = clamp(roadCoverage, 0.0, 1.0);
    }
    float normalStrength = 1.0;
    if (wheelWear) { normalStrength = mix(1.2, .35, compaction); }
    vec3 normal = roadNormal(m, materialUV, normalStrength);
    // Potholes' walls (asphaltWear's slope, in the road UVs' frame).
    if (paved) normal = normalize(normal + vec3(wearSlope.x, -wearSlope.y, 0.0));
    // Only exposed, upward-facing ground receives liquid water. No accumulation or terrain-rule changes.
    float wet = u_wetness * step(0.0, m.response) * smoothstep(0.2, 0.8, v_normal.z);
    float response = max(0.0, m.response);
    float absorption = groundAbsorption(wet, response);
    float soilBasins = 0.0, soilMud = 0.0;
    if (m.soil) {
        // Compact soil holds patchy mud and shallow water; the basins stay put as rainfall changes.
        soilBasins = texture(u_rainNoise, groundUV / 22.0).r * .7
              + texture(u_rainNoise, groundUV / 7.0 + .37).g * .3;
        float mud = wet * smoothstep(.24, .68, soilBasins);
        absorption = max(absorption, .7 * mud);
        properties.g = mix(properties.g, .55, mud);
        soilMud = mud;
    }
    float puddle = groundPuddle(wet, response);
    if (wet * u_rainDetail > 0.0 && m.mapped) {
        // Microscopic depressions wet first, without moving the road or its supporting terrain.
        puddle *= mix(1.0, 1.0 - smoothstep(.18, .78, properties.r), .35);
        if (m.soil) {
            float basinWater = smoothstep(mix(.86, .51, wet), mix(.92, .59, wet), soilBasins);
            puddle = max(puddle, basinWater * wet * smoothstep(.98, .999, v_normal.z) * u_rainDetail);
        }
        // Ruts, cracks and potholes hold water first.
        if (paved) puddle = max(puddle, smoothstep(.3, .9, wearWater) * wet * u_rainDetail);
    }
    albedo = wetAlbedo(albedo, absorption);
    groundFilm(normal, puddle);
    // The material's finish; a mapped material's roughness is its surface map's, with the wear and mud above.
    Finish finish = Finish(m.mapped ? properties.g : -1.0, 0.0, 0.0, vec3(0.0));
#if defined(roadMapsFlag) || defined(roadCoatFlag)
    int material = roadFinish(m);
    if (material >= 0) {
        Finish authored = finishTexel(material, materialUV, roadGradient);
        finish = Finish(m.mapped ? properties.g : authored.roughness, authored.metallic, authored.scatter, authored.emission);
        // Mud buries the soil's own finish.
        coverFinish(finish, soilMud);
    }
#endif
    if (!roadHidden) {
        albedo = groundLighting(albedo, normal, geometric, wet, response, puddle, finish,
              m.mapped ? mix(1.0, properties.b, .7) : 1.0);
    }
#ifdef roadFlag
    // The road's outer strip carries coverage. World-anchored grain breaks up its dusty verge.
    float grain = m.mapped ? properties.r : roadAlbedo(m, uv * 2.3).r;
    float alpha = clamp(roadColor.a + (grain - .5) * min(roadColor.a, 1.0 - roadColor.a) * .7, 0.0, 1.0);
    if (m.mapped && m.transition < -2.5) {
        // Grounded bridge landings: individual asphalt chips/aggregate expose soil and grass. The broad
        // envelope is in the mask; material grain supplies the small fractured edge at every orientation.
        float chips = roadSurface(m, uv * .63 + .19).r;
        float fragments = clamp((.56 * grain + .28 * chips + .16 * loosePatches - .2) / .6, 0.0, 1.0);
        float filterWidth = max(.025, fwidth(fragments));
        float broken = smoothstep(fragments - filterWidth, fragments + filterWidth, roadColor.a);
        broken *= smoothstep(0.0, .025, roadColor.a);
        float dust = m.transition < -3.5 ? .16 : .025;
        alpha = mix(broken, roadColor.a, dust);
    } else if (wheelWear) {
        alpha *= smoothstep(.16, .62, wearPatches) * mix(.35, 1.0, loosePatches);
    } else if (m.transition > 2.5) {
        vec2 paint = paintWear(wearMetres, wearPixel);
        alpha *= 1.0 - paint.x;
        albedo *= 1.0 - .22 * paint.y;
    } else if (m.mapped && abs(m.transition) > .5 && !paved) {
        // The wide loose margin has no opaque shoulder underneath it. Clumps give way to isolated grains
        // and exposed ground; the compacted core remains continuous and at the authored road width.
        float clumps = texture(u_rainNoise, groundUV / 32.0 + .19).r;
        float threshold = .05 + .8 * clumps + .12 * loosePatches;
        float broken = smoothstep(threshold - .12, threshold + .12, roadColor.a + (grain - .5) * .32);
        alpha = mix(roadColor.a, broken, 4.0 * roadColor.a * (1.0 - roadColor.a));
        alpha *= roadCoverage;
    }
    fragColor = vec4(albedo, alpha);
#else
    fragColor = vec4(albedo, 1.0);
#endif
#ifdef roadMaskFlag
    if (roadHidden) discard;
#endif
}

// The shared noise over cells of `size` metres, filtered for the pixel: explicitly, as its reads sit in varying branches.
vec4 wearNoise(vec2 m, float size, float offset, float pixel) {
    return textureLod(u_rainNoise, m / size + offset, log2(max(pixel * 64.0 / size, 1.0)));
}
