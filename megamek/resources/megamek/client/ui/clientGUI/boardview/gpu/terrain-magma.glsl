// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared volcanic material evaluation for liquid sheets and terrain-boundary palettes.
// FULL_SIZE_MAPS
uniform vec2 u_magmaLayers; // this set's colour layer, and the first of its normal, surface and heat (GpuMagmaShader)
const float MAGMA_NORMAL = 1.0;
const float MAGMA_SURFACE = 2.0; // height, roughness, AO, relief UV / .1
const float MAGMA_HEAT = 3.0; // molten: linear emission RGB, original heat A; crust: heat R, crack glow A
#ifdef magmaEmissionMapFlag
uniform float u_magmaEmissionScale;
#endif
// GpuAssets.coverage: a wider texture covers more ground per repeat at the same texel density. Only the texture's world
// scale widens; slopes and relief stay physical.
uniform float u_magmaCoverage;
uniform sampler2D u_magmaField; // the lava's liquid field: R signed bank distance, BA world XY current
uniform vec4 u_magmaFieldMap;   // world XY to field UV; zero without a field
// Hex widths either side of a lava bank over which the melt chills into a skin and the cooled bank still glows.
const float MAGMA_MARGIN = .08;
struct Volcanic {
    vec3 albedo; // linear reflectance; the colour sampler decodes before filtering
    vec3 normal;
    vec4 surface;
    vec2 heat;
    vec3 emission;
};

vec2 magmaRelief(vec2 uv, vec2 dx, vec2 dy, vec4 surface, vec2 parallax);
#ifndef magmaEmissionMapFlag
vec3 magmaEmission(vec2 heat, float strength);
#endif
float magmaRepeatMetres();
// The metres one repeat of the actual texture covers.
float magmaTileMetres() { return magmaRepeatMetres() * u_magmaCoverage; }

vec4 magmaTexel(float map, vec2 uv, vec2 dx, vec2 dy) { return textureGrad(u_fullSizeData, vec3(uv, u_magmaLayers.y + map - 1.0), dx, dy); }

vec4 magmaField(vec2 world) {
    return texture(u_magmaField, world * u_magmaFieldMap.xy + u_magmaFieldMap.zw);
}

// Signed distance to the lava's nearest bank in hex widths, positive over the melt, clamped to +-0.4.
float magmaShore(vec4 field) { return (field.r * 2.0 - 1.0) * .4; }

// A cooled bank has lost most of its heat, but its fissures still glow where it meets the melt.
float magmaBankHeat(float shore) {
    return mix(.10, 1.0, 1.0 - smoothstep(0.0, MAGMA_MARGIN, -shore));
}

float magmaBankHeat(vec2 position) {
    return magmaBankHeat(u_magmaFieldMap.x > 0.0 ? magmaShore(magmaField(position)) : -.4);
}

Volcanic magmaSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, bool relief) {
    vec4 surface = magmaTexel(MAGMA_SURFACE, uv, dx, dy);
    vec3 normal = vec3(0.0, 0.0, 1.0);
    if (u_normalMaps > .5) {
        // Relief and normals share the authored height. The surface's roughness mips carry the normals' spread
        // (GpuAssets.mipmaps). A relief that moves at most half a pixel moves nothing visible: it costs no reads.
        vec2 parallax = eye.xy / max(abs(eye.z), .35) * surface.a * .1 * (12.0 / magmaTileMetres());
        if (relief && dot(parallax, parallax) >= max(dot(dx, dx), dot(dy, dy))) {
            uv = magmaRelief(uv, dx, dy, surface, parallax);
            surface = magmaTexel(MAGMA_SURFACE, uv, dx, dy);
        }
        normal = (magmaTexel(MAGMA_NORMAL, uv, dx, dy).rgb * 255.0 - 128.0) / 127.0;
    }
    vec4 heat = magmaTexel(MAGMA_HEAT, uv, dx, dy);
#ifdef magmaEmissionMapFlag
    // PTEX owns the emitted spectrum and its coverage; one raw filtered sample supplies canonical radiance.
    vec2 coverage = vec2(heat.a, 0.0);
    vec3 emission = heat.rgb * u_magmaEmissionScale;
#else
    vec2 coverage = heat.ra;
    vec3 emission = vec3(0.0);
#endif
    return Volcanic(textureGrad(u_fullSizeColor, vec3(uv, u_magmaLayers.x), dx, dy).rgb, normal, surface, coverage, emission);
}

Volcanic magmaMix(Volcanic a, Volcanic b, float weight) {
    return Volcanic(mix(a.albedo, b.albedo, weight), mix(a.normal, b.normal, weight),
          mix(a.surface, b.surface, weight), mix(a.heat, b.heat, weight), mix(a.emission, b.emission, weight));
}

// MAGMA_CONDITION

vec3 magmaHash(vec2 cell) {
    vec3 p = fract(vec3(cell, cell.x + cell.y) * vec3(.1031, .1030, .0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.xxy + p.yzz) * p.zyx);
}

Volcanic magmaPatch(vec2 uv, vec2 dx, vec2 dy, vec2 cell, vec3 eye, vec2 downhill) {
    vec3 random = magmaHash(cell + vec2(17.0, 83.0));
    float angle = random.x * 6.2831853;
    mat2 rotation = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    float scale = mix(.8, 1.2, random.z);
    uv = rotation * uv * scale + random.yz;
    dx = rotation * dx * scale;
    dy = rotation * dy * scale;
    eye.xy = rotation * eye.xy * scale;
    Volcanic material = magmaPhaseSample(uv, dx, dy, eye, rotation * downhill * scale, random);
    // Rotate the height gradient back with the texture. Rotating colour alone mislights the fissures.
    material.normal.xy = transpose(rotation) * material.normal.xy * (scale * 12.0 / magmaRepeatMetres())
          / max(material.normal.z, .15);
    material.normal.z = 0.0;
    return material;
}

Volcanic magmaProjection(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill) {
    // One coherent textured surface, with the existing normal, relief, heat and two-phase flow.
    return magmaPatch(uv, dx, dy, vec2(0.0), eye, downhill);
}
// Capture the warped finite derivatives before any per-pixel palette coverage decision.
struct MagmaProjection {
    vec3 position, dx, dy;
    mat3 domain;
};

MagmaProjection magmaProjectionFor(vec3 world) {
    vec3 position = world / magmaTileMetres();
    mat3 domain = magmaDomain(position);
    return MagmaProjection(position, dFdx(position), dFdy(position), domain);
}

Volcanic magmaSurface(MagmaProjection projection, vec3 face, vec3 eye, vec3 uphill, float bank, vec4 waves) {
    vec3 position = projection.position, dx = projection.dx, dy = projection.dy;
    // Every projection shares one continuous domain. Transform directions and height gradients
    // with its Jacobian too, so texture bending cannot detach the relief or current from the colour.
    mat3 domain = projection.domain;
    vec3 textureEye = domain * eye, textureUphill = domain * uphill;
    // Smooth triplanar projection covers every slope orientation, including curved lava lips and vertical cuts.
    // Prepared derivatives retain stable mip levels through projection and palette coverage branches.
    vec3 weights = pow(abs(face), vec3(4.0));
    weights /= dot(weights, vec3(1.0));
    vec3 pigment = vec3(0.0), gradient = vec3(0.0);
    vec4 surface = vec4(0.0);
    vec2 heat = vec2(0.0);
    vec3 emission = vec3(0.0);
    for (int axis = 0; axis < 3; axis++) {
        if (weights[axis] < .001) continue;
        vec3 tangent = axis == 0 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 bitangent = axis == 2 ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 0.0, -1.0);
        vec2 uv = vec2(dot(position, tangent), dot(position, bitangent));
        vec2 du = vec2(dot(dx, tangent), dot(dx, bitangent));
        vec2 dv = vec2(dot(dy, tangent), dot(dy, bitangent));
        vec3 localEye = vec3(dot(textureEye, tangent), dot(textureEye, bitangent), dot(eye, face));
        vec2 downhill = vec2(dot(textureUphill, tangent), dot(textureUphill, bitangent));
        Volcanic material = magmaProjection(uv, du, dv, localEye, downhill);
        pigment += material.albedo * weights[axis];
        surface += material.surface * weights[axis];
        heat += material.heat * weights[axis];
        emission += material.emission * weights[axis];
        gradient += (tangent * material.normal.x + bitangent * material.normal.y) * weights[axis];
    }
    // Chain rule: bring the warped texture's height gradient back into world coordinates.
    gradient = transpose(domain) * gradient;
    gradient -= face * dot(face, gradient);
    vec3 swell = vec3(-waves.xy, 0.0) * .30 * bank;
    gradient += swell - face * dot(swell, face);
    vec3 normal = normalize(face + gradient * u_normalMaps);
    return Volcanic(pigment, normal, surface, heat, emission);
}

// Dedicated magma draws keep the same entry point; palette callers can share its prepared projection.
Volcanic magmaSurface(vec3 world, vec3 face, vec3 eye, vec3 uphill, float bank, vec4 waves) {
    return magmaSurface(magmaProjectionFor(world), face, eye, uphill, bank, waves);
}

#ifndef magmaEmissionMapFlag
vec3 magmaEmission(vec2 heat, float strength) {
    // Emission is added AFTER sunlight, occlusion and cloud/geometry shadowing. Night never extinguishes heat.
    // Each material variant supplies its fixed source radiance; shared display exposure remains downstream.
    vec3 hot = magmaHeatColor(heat.x);
    vec3 emission = hot * heat.x * strength;
    vec3 crackGlow = MAGMA_CRACK_RADIANCE * heat.y * strength * (1.0 - heat.x);
    return emission + crackGlow;
}
#endif
