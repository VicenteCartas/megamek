// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Every custom lit surface shares per-fragment lighting and geometry-shadow sampling, after light-model.glsl.
#ifdef lightingFlag
#ifdef crystalTransmissionFlag
uniform sampler2D u_crystalTransmission;
uniform mat4 u_crystalProjection;
uniform float u_crystalEnabled;
#endif

// Only directional light transmitted to receivers is tinted; ambient sky and self-emission are unchanged.
vec3 crystalTransmission(vec3 position) {
#ifdef crystalTransmissionFlag
    if (u_crystalEnabled < .5) return vec3(1.0);
    vec4 projected = u_crystalProjection * vec4(position, 1.0);
    vec2 uv = projected.xy / projected.w * .5 + .5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec3(1.0);
    vec2 step = .75 / vec2(textureSize(u_crystalTransmission, 0));
    vec3 transmission = (texture(u_crystalTransmission, uv + step).rgb
          + texture(u_crystalTransmission, uv - step).rgb
          + texture(u_crystalTransmission, uv + vec2(step.x, -step.y)).rgb
          + texture(u_crystalTransmission, uv + vec2(-step.x, step.y)).rgb) * .25;
    return mix(vec3(1.0), transmission, u_shadowStrength);
#else
    return vec3(1.0);
#endif
}
uniform vec3 u_ambientCubemap[6];
#if numDirectionalLights > 0
struct DirectionalLight {
    vec3 color;
    vec3 direction;
};
uniform DirectionalLight u_dirLights[numDirectionalLights];
#endif
#ifdef shadowMapFlag
uniform sampler2DShadow u_shadowTexture;
uniform float u_shadowPCFOffset;
uniform mat4 u_shadowMapProjViewTrans;
in vec3 v_shadowMapUv;

// The receiver's plane moves a comparison by the map's drift (shadowDrift) in full and by the rest of its slope at most
// shadowPlaneLimit, in map depth; sculptShadow and surfaceVisibility set both.
vec2 shadowPlaneDrift;
float shadowPlaneLimit;

// The receiver's plane (slope: its depth per unit across the map) offset away across the map.
float shadowPlane(vec2 offset, vec2 slope) {
    return dot(offset, shadowPlaneDrift)
          + clamp(dot(offset, slope - shadowPlaneDrift), -shadowPlaneLimit, shadowPlaneLimit);
}

// One hardware comparison at at (shadow-map coordinates), against the receiver's plane there and depth further along
// it. The map has no mip levels: an explicit level lets taps sit in branches.
float shadowTap(vec3 uv, vec2 slope, vec2 at, float depth) {
    return textureLod(u_shadowTexture, vec3(at, uv.z + depth + shadowPlane(at - uv.xy, slope)), 0.0);
}

// Two adjacent texels' tent weights, first and first + 1 (texel centres at whole numbers of p), as one bilinear
// comparison: its position in texels and their summed weight.
vec2 shadowPair(float p, float halfWidth, float first) {
    vec2 w = max(halfWidth - abs(vec2(first, first + 1.0) - p), 0.0);
    float sum = w.x + w.y;
    return vec2(first + .5 + w.y / max(sum, 1e-8), sum);
}

// Texels across the square shadow map: libGDX's u_shadowPCFOffset is half of one.
float shadowMapSize() { return .5 / u_shadowPCFOffset; }

// The map's depth per unit across it at one distance from the sun: none for depth along the light; for height across
// the casters' slab (GpuShadowLight.SlabCamera) the drift shadow-depth.frag leaves out of the casters' slope bias.
vec2 shadowDrift() {
    vec3 across = vec3(u_shadowMapProjViewTrans[0][0], u_shadowMapProjViewTrans[1][0], u_shadowMapProjViewTrans[2][0]);
    vec3 up = vec3(u_shadowMapProjViewTrans[0][1], u_shadowMapProjViewTrans[1][1], u_shadowMapProjViewTrans[2][1]);
    vec3 row = vec3(u_shadowMapProjViewTrans[0][2], u_shadowMapProjViewTrans[1][2], u_shadowMapProjViewTrans[2][2]);
    return vec2(dot(row, across) / dot(across, across), dot(row, up) / dot(up, up));
}

// A pair's comparison at height y (shadowTent), weighted; none where a narrow tent gives it no weight.
float shadowWeighted(vec3 uv, vec2 slope, float size, vec2 x, float y) {
    return x.y > 0.0 ? x.y * shadowTap(uv, slope, vec2(x.x, y) / size, 0.0) : 0.0;
}

// One row of a tent (shadowTent): the pairs x0..x2 at the pair y, in texels.
float shadowRow(vec3 uv, vec2 slope, float size, vec2 x0, vec2 x1, vec2 x2, vec2 y) {
    return y.y > 0.0 ? y.y * (shadowWeighted(uv, slope, size, x0, y.x) + shadowWeighted(uv, slope, size, x1, y.x)
          + shadowWeighted(uv, slope, size, x2, y.x)) : 0.0;
}

// A separable tent filter, halfWidth texels to each side (1 to 3; up to 2 without wide), as 3 by 3 (2 by 2) bilinear
// comparisons whose weights make it exact: smooth in position and width, with no rotation pattern. Written out, as
// Intel's compiler does not unroll such loops.
float shadowTent(vec3 uv, vec2 slope, float halfWidth, bool wide) {
    float size = shadowMapSize();
    vec2 p = uv.xy * size - .5, first = floor(p) - (wide ? 2.0 : 1.0);
    vec2 x0 = shadowPair(p.x, halfWidth, first.x), x1 = shadowPair(p.x, halfWidth, first.x + 2.0);
    vec2 y0 = shadowPair(p.y, halfWidth, first.y), y1 = shadowPair(p.y, halfWidth, first.y + 2.0);
    vec2 x2 = wide ? shadowPair(p.x, halfWidth, first.x + 4.0) : vec2(0.0);
    vec2 y2 = wide ? shadowPair(p.y, halfWidth, first.y + 4.0) : vec2(0.0);
    float lit = shadowRow(uv, slope, size, x0, x1, x2, y0) + shadowRow(uv, slope, size, x0, x1, x2, y1)
          + shadowRow(uv, slope, size, x0, x1, x2, y2);
    return lit / ((x0.y + x1.y + x2.y) * (y0.y + y1.y + y2.y));
}

// Comparisons at middle and spacing away from it along each axis: the centre and edges of a block grid (sculptShadow).
float shadowCross(vec3 uv, vec2 slope, vec2 middle, float spacing, float depth) {
    vec2 x = vec2(spacing, 0.0), y = vec2(0.0, spacing);
    return shadowTap(uv, slope, middle, depth) + shadowTap(uv, slope, middle + x, depth)
          + shadowTap(uv, slope, middle - x, depth) + shadowTap(uv, slope, middle + y, depth)
          + shadowTap(uv, slope, middle - y, depth);
}

// Comparisons spacing away from middle along both axes: the grid's corners.
float shadowCorners(vec3 uv, vec2 slope, vec2 middle, float spacing, float depth) {
    vec2 rising = vec2(spacing), falling = vec2(spacing, -spacing);
    return shadowTap(uv, slope, middle + rising, depth) + shadowTap(uv, slope, middle - rising, depth)
          + shadowTap(uv, slope, middle + falling, depth) + shadowTap(uv, slope, middle - falling, depth);
}
#endif

vec3 surfaceAmbient(vec3 normal) {
    vec3 squared = normal * normal;
    vec3 positive = step(vec3(0.0), normal);
    return squared.x * mix(u_ambientCubemap[0], u_ambientCubemap[1], positive.x)
          + squared.y * mix(u_ambientCubemap[2], u_ambientCubemap[3], positive.y)
          + squared.z * mix(u_ambientCubemap[4], u_ambientCubemap[5], positive.z);
}

// Direct light on level ground, which the ground reflects into walls and undersides.
vec3 sunOnGround() {
#if numDirectionalLights > 0
    return u_dirLights[0].color * max(0.0, -u_dirLights[0].direction.z);
#else
    return vec3(0.0);
#endif
}

// Sky light on a face along normal: the sky from above, the sunlit ground of this albedo from below. A cloud's shadow
// over the face shades that ground too.
vec3 environmentGround(vec3 groundAlbedo) {
    vec3 ground = sunOnGround();
#ifdef cloudShadowFlag
    ground *= cloudTransmission(v_cloudPosition);
#endif
    return groundAlbedo * (surfaceAmbient(vec3(0.0, 0.0, 1.0)) + ground);
}

// The board's relief and buildings hide part of the sky in canyons, streets and at the feet of walls
// (sky-visibility.glsl). What hides it is not black: seen from shade, the faces hiding the sky mostly turn away from the
// sun, so the hidden share takes ground lit by the sky alone (sunlit ground, about as bright as the sky, erased the
// occlusion). Along direction the sky is the share (1 + direction.z) / 2; the ground below keeps its own bounce. Never
// above open, the light there unoccluded: a dim lighting image or snow's bounce must not turn occlusion into a lift.
vec3 hiddenSky(vec3 direction, vec3 ground, vec3 groundAlbedo, vec3 open) {
    float upper = .5 + .5 * direction.z;
    return min((1.0 - upper) * ground + upper * groundAlbedo * surfaceAmbient(vec3(0.0, 0.0, 1.0)), open);
}

#ifdef normalQualityFlag
vec3 faceSky = vec3(0.0); // The sky on this face (skyLight), which rough surfaces reflect in its stead (shadeSurface).
#endif

vec3 skyLight(vec3 normal, vec3 groundAlbedo) {
    // At roughness one the normalized GGX prefilter is the cosine convolution: irradiance divided by pi.
    vec3 ground = environmentGround(groundAlbedo);
    vec3 open = environmentRadiance(normal, 1.0, ground, surfaceAmbient(vec3(0.0, 0.0, 1.0)));
#ifdef normalQualityFlag
    faceSky = open;
#endif
    return mix(hiddenSky(normal, ground, groundAlbedo, open), open, skyVisibility(v_cloudPosition, normal));
}

// The environment's reflection, as environmentSpecular: the relief hides the reflected sky as it hides the sky, seen
// along the reflection, and the hidden share takes the light of what hides it.
vec3 skyReflection(vec3 normal, vec3 view, float roughness, vec3 f0, vec3 groundAlbedo, vec3 ground,
      vec3 fallbackSky) {
    if (dot(normal, view) <= 0.0) return vec3(0.0);
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    vec3 reflected = reflect(-view, normal);
    vec3 radiance = environmentRadiance(reflected, roughness, ground, fallbackSky);
    float visible = skyVisibility(v_cloudPosition, reflected);
    if (visible < 1.0) radiance = mix(hiddenSky(reflected, ground, groundAlbedo, radiance), radiance, visible);
    return radiance * specularAlbedo(dot(normal, view), roughness, f0);
}

// Models and the other surfaces: the terrain's tent (sculptShadow) at a fixed width, in four comparisons. Without
// the receiver's plane, it keeps the texels a four-tap box read, so a surface lit at an angle does not shadow itself:
// the casters' slope bias covers the box. The comparisons follow only the map's drift, which that bias leaves out.
float surfaceVisibility() {
#ifdef shadowMapFlag
    shadowPlaneDrift = shadowDrift();
    shadowPlaneLimit = 0.0;
    return shadowVisibility(shadowTent(v_shadowMapUv, vec2(0.0), 1.5, false));
#else
    return 1.0;
#endif
}

#ifdef impostorFlag
// The crown radius of an impostor card's tree, in world units (tree-instances.glsl).
in float v_impostorLift;
#endif

// The sculpted terrain and its trees widen the tent with the distance to what casts the shadow (contact hardening).
// The sun's disc, about half a degree across: a penumbra is this share of the distance from what casts it.
const float SUN_DIAMETER = .0093;
// Beyond the map's drift, the receiver's plane moves a comparison at most this far along the light, in metres
// (shadowPlaneLimit).
const float RECEIVER_PLANE_METRES = 1.0;

// metre: world units per metre. lift: how far the visible surface stands out along normal from the mesh, its relief
// (terrain parallax): raised grains catch the light at a shadow's edge and hollows fall into it first. fray: a shift
// of the lookup across the light, in metres: the grass, clods and stones that fray a natural shadow's edge.
float sculptShadow(vec3 normal, vec3 light, float metre, float lift, vec2 fray, bool receivesSun) {
#ifdef shadowMapFlag
    // Normal offset: the lookup moves out along the surface normal by half a shadow texel, and by up to two where the
    // light skims the surface, so a flat face lit at a grazing angle never shadows itself.
    vec3 row = vec3(u_shadowMapProjViewTrans[0][0], u_shadowMapProjViewTrans[1][0], u_shadowMapProjViewTrans[2][0]);
    float texel = 4.0 * u_shadowPCFOffset / max(length(row), 1e-6);
    float skim = 1.0 - abs(dot(normal, light));
    vec3 receiver = v_cloudPosition + normal * (texel * (.5 + 1.5 * skim) + lift);
#ifdef impostorFlag
    // An impostor card stands in the middle of its crown, whose leaves face the light about a crown radius nearer:
    // looked up there, the card's own cards and crown cannot shade it, while other trees and buildings still do.
    receiver += light * v_impostorLift;
#endif
    vec4 lifted = u_shadowMapProjViewTrans * vec4(receiver, 1.0);
    vec3 shadowUv = lifted.xyz / lifted.w * .5 + .5;
    shadowUv.z = min(shadowUv.z, .998);
    // Receiver-plane depth: each tap compares against this surface's own depth at the tap, not at the pixel centre,
    // so walls and grazing ground do not shadow themselves across the filter.
    vec3 du = dFdx(shadowUv), dv = dFdy(shadowUv);
    float det = du.x * dv.y - du.y * dv.x;
    vec2 slope = abs(det) > 1e-14 ? vec2(dv.y * du.z - du.y * dv.z, du.x * dv.z - dv.x * du.z) / det : vec2(0.0);
    // Map depth per world unit toward the sun: the depth row along the light, whatever the map's depth measures
    // (GpuShadowLight.SlabCamera: height across the casters' slab).
    float depthPerUnit = .5 * abs(dot(vec3(u_shadowMapProjViewTrans[0][2], u_shadowMapProjViewTrans[1][2],
          u_shadowMapProjViewTrans[2][2]), light));
    shadowPlaneLimit = RECEIVER_PLANE_METRES * metre * depthPerUnit;
    shadowPlaneDrift = shadowDrift();
    // Derivatives above remain defined across the light horizon; only unused comparisons are skipped.
    if (!receivesSun) return 1.0;
    // After the receiver plane: the fray moves where the edge falls, along the surface as the taps do, never into it.
    vec2 frayUv = fray * metre * .5 * length(row);
    shadowUv += vec3(frayUv, shadowPlane(frayUv, slope));
    // The widest tent (shadowTent) reaches six by six texels: nine blocks of two by two, each one comparison at its
    // middle. Against the highest depth any tent compares in a block (half a texel of the receiver's plane above: its
    // drift in full, the plane's clamp bounding the rest), the centre and its four edges lit: lit, the corners, a fifth
    // of the widest tent's weight, unseen. Against the lowest, all nine shadowed: every texel any tent reads is
    // shadowed, the umbra, exactly.
#ifdef normalQualityFlag
    // One bilinear comparison, without looking for what casts the shadow.
    return shadowVisibility(textureLod(u_shadowTexture, shadowUv, 0.0));
#else
    float size = shadowMapSize(), spacing = 2.0 / size;
    vec2 drift = abs(shadowPlaneDrift), bent = abs(slope - shadowPlaneDrift);
    float bound = .5 * (drift.x + drift.y) / size + min(.5 * (bent.x + bent.y) / size, 2.0 * shadowPlaneLimit);
    vec2 middle = (floor(shadowUv.xy * size - .5) + 1.0) / size;
    float lit = shadowCross(shadowUv, slope, middle, spacing, bound);
    if (lit >= 4.9999) return 1.0;
    if (lit <= 1e-4 && shadowCross(shadowUv, slope, middle, spacing, -bound)
          + shadowCorners(shadowUv, slope, middle, spacing, -bound) <= 1e-4) return shadowVisibility(0.0);
    // Contact hardening: a bank's rim casts a crisp edge and a tower a soft one. What casts the shadow is found by
    // comparisons alone, without a second sampler: those five blocks at the receiver's own depth, and again 3 m and
    // 15 m nearer the sun; a blocker still found there stands farther away. None at its own depth: the crispest.
    float blocked = 5.0 - shadowCross(shadowUv, slope, middle, spacing, 0.0);
    // Half the penumbra in texels, as the tent's half width: from one (the map resolves no finer) to the widest.
    float halfWidth = 1.0;
    if (blocked > 1e-4) {
        float beyondNear = 5.0 - shadowCross(shadowUv, slope, middle, spacing, -3.0 * metre * depthPerUnit);
        float beyondFar = 5.0 - shadowCross(shadowUv, slope, middle, spacing, -15.0 * metre * depthPerUnit);
        float caster = (1.5 + 5.5 * beyondNear / blocked + 23.0 * beyondFar / blocked) * metre;
        halfWidth = clamp(.5 * SUN_DIAMETER * caster / texel + .5, 1.0, 3.0);
    }
    return shadowVisibility(shadowTent(shadowUv, slope, halfWidth, true));
#endif
#else
    return 1.0;
#endif
}

float sculptShadow(vec3 normal, vec3 light, float metre, float lift, vec2 fray) {
    return sculptShadow(normal, light, metre, lift, fray, true);
}

// Shared dielectric response: callers can reuse their shadow sample for mapped leaves as well as opaque surfaces.
// The water film's smooth coat widens too: every lobe, budget and environment call takes the antialiased roughness.
float dielectricRoughness(float film, float roughness) {
    return antialiasedRoughness(mix(clamp(roughness < 0.0 ? .85 : roughness, MIN_ROUGHNESS, 1.0), .06,
          clamp(film, 0.0, 1.0)), surfaceVariance);
}

vec3 dielectricF0(float film) { return vec3(mix(.04, .02037, clamp(film, 0.0, 1.0))); }

float dielectricSheen(vec3 normal, vec3 light, vec3 view, float film, float roughness) {
    return surfaceReflectance(normal, light, view, dielectricRoughness(film, roughness), dielectricF0(film)).r;
}

float dielectricDiffuse(vec3 normal, vec3 light, vec3 view, float film, float roughness) {
    return diffuseReflectance(normal, light, view, dielectricRoughness(film, roughness), dielectricF0(film)).r;
}

float dielectricBudget(vec3 normal, vec3 view, float film, float roughness) {
    return 1.0 - specularAlbedo(max(0.0, dot(normal, view)), dielectricRoughness(film, roughness), dielectricF0(film)).r;
}

vec3 surfaceEnvironment(vec3 normal, float film, float roughness, vec3 groundAlbedo) {
    return skyReflection(normal, -viewDirection(), dielectricRoughness(film, roughness), dielectricF0(film),
          groundAlbedo, environmentGround(groundAlbedo), surfaceAmbient(vec3(0.0, 0.0, 1.0)));
}

// Irradiance for scattering volumes and optical coats, before their own reflection/transmission split. Without local,
// it leaves out the local lights, for water that walks them once with their reflection (lavaLighting).
void incidentLighting(vec3 normal, bool local, out vec3 ambient, out vec3 direct) {
    ambient = skyLight(normal, GROUND_ALBEDO);
    if (local) ambient += lavaIrradiance(v_cloudPosition, normal);
    direct = vec3(0.0);
#if numDirectionalLights > 0
    for (int i = 0; i < numDirectionalLights; i++) {
        direct += u_dirLights[i].color * crystalTransmission(v_cloudPosition) * max(0.0, dot(normal, -u_dirLights[i].direction));
    }
#endif
    direct *= surfaceVisibility();
}

void incidentLighting(vec3 normal, out vec3 ambient, out vec3 direct) {
    incidentLighting(normal, true, ambient, direct);
}

void surfaceLighting(vec3 normal, vec3 face, float film, float roughness,
      out vec3 ambient, out vec3 direct, out vec3 sheen) {
    vec3 view = -viewDirection();
    ambient = (skyLight(normal, GROUND_ALBEDO) + lavaIrradiance(v_cloudPosition, normal))
          * dielectricBudget(normal, view, film, roughness);
    direct = vec3(0.0);
    sheen = vec3(0.0);
#if numDirectionalLights > 0
    for (int i = 0; i < numDirectionalLights; i++) {
        vec3 light = -u_dirLights[i].direction;
        if (dot(face, light) > 0.0) {
            vec3 transmitted = u_dirLights[i].color * crystalTransmission(v_cloudPosition);
            direct += transmitted * dielectricDiffuse(normal, light, view, film, roughness);
            sheen += transmitted * dielectricSheen(normal, light, view, film, roughness);
        }
    }
#endif
#ifdef shadowMapFlag
    float visibility = surfaceVisibility();
    direct *= visibility;
    sheen *= visibility;
#endif
}

// A proxy canopy's card is not an opaque incident-light boundary. Its signed botanical cosine owns the lobes.
void surfaceLighting(vec3 normal, float film, float roughness, out vec3 ambient, out vec3 direct, out vec3 sheen) {
    surfaceLighting(normal, normal, film, roughness, ambient, direct, sheen);
}

// Materials without a roughness map use a matte dielectric, with the same water-film boundary.
void surfaceLighting(vec3 normal, float film, out vec3 ambient, out vec3 direct, out vec3 sheen) {
    surfaceLighting(normal, film, -1.0, ambient, direct, sheen);
}
#endif
