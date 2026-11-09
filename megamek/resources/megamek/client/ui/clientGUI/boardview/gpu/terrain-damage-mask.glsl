// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by terrain and grass. Directory texels name sparse, fixed-ground-size image layers.
// SHOCKS
uniform sampler2DArray u_groundDamage;
uniform vec2 u_groundDamageSize;
uniform vec3 u_groundDamageGrid;
// x: 1 while any scorch shows (a nuclear blast's or a hex's authored one); y: Ground Zero's heat, cooling after a blast
uniform vec2 u_groundScorch;

// The directory texel of an 8 m tile: R and G name its image layer, B holds a nuclear blast's scorch, A the hex's
// authored scorch (GpuGroundDamage.author).
vec4 groundDamageEntry(ivec2 cell) {
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(u_groundDamageGrid.yz)))) return vec4(0.0);
    int size = int(GROUND_DAMAGE_TILE);
    int address = cell.y * int(u_groundDamageGrid.y) + cell.x;
    return texelFetch(u_groundDamage, ivec3(address % size, (address / size) % size, address / (size * size)), 0);
}

// The scorch here, 0 to 1: x a nuclear blast's, y the hexes' authored one; bilinear between the tiles' centres, so its
// 8 m tiles never show. A blast's scorch shows where its front has passed (terrain-shocks.glsl).
vec2 groundScorches(vec2 world) {
    if (u_groundScorch.x <= 0.0) return vec2(0.0);
    vec2 position = vec2(world.x, -world.y) / u_groundDamageGrid.x - .5;
    ivec2 cell = ivec2(floor(position));
    vec2 f = position - vec2(cell);
    vec2 near = mix(groundDamageEntry(cell).ba, groundDamageEntry(cell + ivec2(1, 0)).ba, f.x);
    vec2 far = mix(groundDamageEntry(cell + ivec2(0, 1)).ba, groundDamageEntry(cell + ivec2(1, 1)).ba, f.x);
    return mix(near, far, f.y) * vec2(shockReached(world), 1.0);
}

// The scorch that shows here: the stronger of a nuclear blast's and the authored one.
float groundScorchAt(vec2 world) {
    vec2 scorches = groundScorches(world);
    return max(scorches.x, scorches.y);
}

// Any authored or nuclear scorch clears grass, including the material's fading edge.
float scorchedGrass(vec2 world) { return groundScorchAt(world) > 0.0 ? 1.0 : 0.0; }

// One deposit evaluation for every land surface. Sculpt tops supply the authored reference maps; steep faces and
// plants use their average sites, without a stretched map or extra samples. All palette values are linear reflectance.
const vec3 SCORCH_SITES = vec3(.35, .22, .5); // cinder, ash, fused mineral
struct Scorched { vec3 albedo; float roughness; float coverage; float detail; };

float scorchCoverage(float severity) { return smoothstep(0.0, .55, severity); }

vec3 scorchSites(vec3 reference, float roughness) {
    float luminance = dot(reference, vec3(.2126, .7152, .0722));
    return vec3(1.0 - smoothstep(.03, .10, luminance), smoothstep(.10, .26, luminance),
          1.0 - smoothstep(.35, .65, roughness));
}

Scorched scorched(float severity, vec3 sites, float roughness, float height) {
    float fused = smoothstep(.78, .97, severity);
    float cinder = smoothstep(.12, .45, severity) * sites.x;
    float ash = smoothstep(.35, .68, severity) * sites.y * (1.0 - .65 * fused);
    float glass = fused * sites.z;
    vec4 weights = vec4((1.0 - glass) * (1.0 - ash) * (1.0 - cinder),
          (1.0 - glass) * (1.0 - ash) * cinder, (1.0 - glass) * ash, glass);
    vec3 pigment = weights.x * vec3(.060, .043, .026) + weights.y * vec3(.016, .018, .017)
          + weights.z * vec3(.230, .220, .200) + weights.w * vec3(.028, .037, .030);
    return Scorched(pigment * mix(.8, 1.2, height),
          dot(weights, vec4(.90, .86, .97, clamp(roughness, .18, .38))), scorchCoverage(severity),
          dot(weights, vec4(.5, .9, .35, .3)));
}

vec4 groundDamageAt(vec2 world) {
    if (u_groundDamageSize.x <= 0.0) return vec4(0.0);
    vec2 position = vec2(world.x, -world.y) / u_groundDamageGrid.x;
#ifdef GROUND_DAMAGE_FRAGMENT
    // Different fragments can select different layers; never differentiate the wrapped tile coordinates.
    vec2 dx = dFdx(position) * GROUND_DAMAGE_INNER / GROUND_DAMAGE_TILE;
    vec2 dy = dFdy(position) * GROUND_DAMAGE_INNER / GROUND_DAMAGE_TILE;
#endif
    ivec2 bytes = ivec2(groundDamageEntry(ivec2(floor(position))).rg * 255.0 + .5);
    int layer = bytes.x + bytes.y * 256;
    if (layer == 0) return vec4(0.0);
    vec2 uv = (fract(position) * GROUND_DAMAGE_INNER + GROUND_DAMAGE_BORDER) / GROUND_DAMAGE_TILE;
#ifdef GROUND_DAMAGE_FRAGMENT
    return textureGrad(u_groundDamage, vec3(uv, float(layer)), dx, dy);
#else
    return textureLod(u_groundDamage, vec3(uv, float(layer)), 0.0);
#endif
}

float groundScarCover(vec4 damage) {
    // Opaque, dark cores clear the turf; lighter debris and low-strength singeing retain some blades.
    return smoothstep(.04, .95, damage.r - damage.g * .45);
}
