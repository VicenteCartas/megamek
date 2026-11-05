#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Trees: their models' own colours and detail maps, lit like the sculpted terrain they stand on (shadeSurface,
// surface-shading.glsl): linear light, sky and ground bounce and soft shadows; the composite rolls off their
// highlights. A canopy scatters light through its leaves, so the sun wraps around it instead of stopping at a hard
// terminator.
in vec3 v_normal;
// The alpha test's inputs are invariant where they are computed, in the vertex stage (foliage-coverage.glsl), so a
// program and its depth twin (GpuTerrain.depthPrepass) keep the same texels. Here they are declared to match it:
// GLSL 4.20 (section 4.8.1) dropped 4.10's rule that the input be invariant too, and NVIDIA rejects it at 4.60.
#if __VERSION__ < 420
#define coverageIn invariant in
#else
#define coverageIn in
#endif
#ifdef colorFlag
coverageIn vec4 v_color;
#endif
#ifdef diffuseTextureFlag
coverageIn vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
uniform float u_normalMaps;
#endif
#ifdef ambientTextureFlag
// Leaf surface texture: red cavity occlusion, green roughness, blue thin-tissue transmission.
// Stored in glTF occlusionTexture (whose standard red channel remains valid).
uniform sampler2D u_ambientTexture;
#endif
#ifdef modelSurfaceFlag
// The plant material's finish (GpuModelMaterial, from its .tres): roughness, metallic and scattering over its map, and
// its emission map's tint and constant glow.
uniform vec4 u_modelSurface;
uniform sampler2D u_modelSurfaceMap; // R scattering, G roughness, B metallic
uniform vec3 u_modelEmission, u_modelGlow;
uniform vec3 u_modelLight; // GpuModelLight: enabled, emission multiplier (night lights fade by day), unused here
uniform float u_modelBoard; // 1 for a board material's .tres, 0 for the GLB's own material
#endif
#ifdef emissiveTextureFlag
uniform sampler2D u_emissiveTexture;
#endif
#ifdef emissiveColorFlag
uniform vec4 u_emissiveColor;
#endif
#ifdef blendedFlag
coverageIn float v_opacity;
#ifdef alphaTestFlag
coverageIn float v_alphaTest;
#endif
#endif
uniform float u_foliage; // 0 solid, 1 canopy, 2 snow, 3 fungal tissue
uniform float u_metre; // world units per metre
uniform float u_foliageProxy; // Exact authored branch-card roles, not all thin tissue.
// How far a crown's leaf normals spread about its envelope normal, as the GGX alpha^2 it adds (twice the projected
// normal variance, as antialiasedRoughness adds it). Leaves facing every way over the outward hemisphere are the
// cosine-weighted facet distribution, which is GGX at alpha^2 = 1 (projected variance 1/2).
const float CROWN_NORMAL_VARIANCE = 1.0;
#if defined(impostorFlag) && defined(normalTextureFlag) && defined(ambientTextureFlag)
// External pilot: these existing bindings hold represented geometry and resolved material data.
#define impostorMaterialFlag
in float v_impostorAspect;
#endif

void main() {
    vec3 baseNormal = normalize(v_normal);
    vec3 face = baseNormal;
    vec3 crownNormal = gl_FrontFacing ? face : -face;
    // Hoist derivative work before any alpha discard or mapped/proxy branch.
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
#ifdef normalTextureFlag
    vec2 du = dFdx(v_diffuseUV), dv = dFdy(v_diffuseUV);
#endif
    surfaceVariance = normalVariance(dFdx(baseNormal), dFdy(baseNormal));
    vec4 diffuse = vec4(1.0);
#ifdef diffuseTextureFlag
    diffuse = texture(u_diffuseTexture, v_diffuseUV);
#endif
    // How much of the surface is leaves, which scatter light around, rather than bark, cactus or snow.
    float leaves = abs(u_foliage - 1.0) < .5 ? 1.0 : 0.0;
#ifdef impostorFlag
    // An impostor card's colour says how its plant takes the sun (prepare_tree_lods.py): red, the share its own leaves
    // let through; green, how much of what the card shows is bark, cactus or snow. The card's shadow lookup skips the
    // crown it stands in (surface-lighting.glsl), so the shadow map adds other casters alone.
#ifndef impostorMaterialFlag
    float sunlit = v_color.r;
    leaves = 1.0 - v_color.g;
#endif
#elif defined(colorFlag)
    diffuse *= v_color;
#endif
#ifdef diffuseColorFlag
    diffuse *= u_diffuseColor;
#endif
#ifdef blendedFlag
    float opacity = diffuse.a * v_opacity;
#else
    float opacity = 1.0;
#endif
#ifdef impostorMaterialFlag
    // Numeric AO / dry roughness / effective transmission / snow fraction; same cutout footprint.
    vec4 cardSurface = texture(u_ambientTexture, v_diffuseUV);
#endif
#ifdef lightingFlag
    vec3 surface = vec3(1.0, .58, .35);
#ifdef impostorMaterialFlag
    surface = cardSurface.rgb;
#elif defined(ambientTextureFlag)
    surface = texture(u_ambientTexture, v_diffuseUV).rgb;
#endif
    vec3 light = sunDirection();
    // Use the crown's smooth normal for stable shadow offsets; the detail normal is only leaf relief.
    float visibility = sculptShadow(crownNormal, light, u_metre, 0.0, vec2(0.0));
#if defined(impostorFlag) && !defined(impostorMaterialFlag)
    // The card's baked crown self-shadow follows the shadow strength, as the shadow map does.
    visibility *= shadowVisibility(sunlit);
#endif
#endif
    // Alpha-tested texels own no color or depth. Keep texture/receiver derivatives above this cutoff;
    // normal detail uses the captured gradients, and single-level shadow/cloud reads use explicit LOD.
#if defined(blendedFlag) && defined(alphaTestFlag)
    if (opacity <= v_alphaTest) discard;
#endif
#ifdef depthPrepassFlag
    // The depth twin keeps exactly these texels and writes their depth alone.
    return;
#endif
#ifdef normalTextureFlag
    // Derive the frame from the authored UVs, so mapped ribs follow bent stems and rotated instances.
    // Mip filtering removes subpixel normal detail without adding a mesh level or draw pass.
    float determinant = du.x * dv.y - du.y * dv.x;
#ifdef impostorMaterialFlag
    // Source geometry is always present; u_normalMaps controls microscopic detail, which this pilot omits.
    vec4 cardNormal = textureGrad(u_normalTexture, v_diffuseUV, du, dv);
    leaves = cardNormal.a;
    vec3 cardScale = abs(baseNormal.z) > .5 ? vec3(1.0, 1.0, v_impostorAspect)
          : vec3(1.0, v_impostorAspect, 1.0);
    mat3 cardFrame = mat3(1.0);
    bool cardFrameValid = false;
    bool normalEnabled = true;
#else
    bool normalEnabled = u_normalMaps > .5;
#endif
    if (normalEnabled && abs(determinant) > 1e-10) {
        vec3 tangent = (dx * dv.y - dy * du.y) / determinant;
        vec3 bitangent = (dy * du.x - dx * dv.x) / determinant;
        tangent -= face * dot(face, tangent);
        if (dot(tangent, tangent) > 1e-10) {
            tangent = normalize(tangent);
            vec3 perpendicular = cross(face, tangent);
            bitangent = dot(perpendicular, bitangent) < 0.0 ? -perpendicular : perpendicular;
#ifdef impostorMaterialFlag
            // Filtered source normals shorten before any instance transform; aspect is not roughness.
            vec3 detail = (cardNormal.rgb * 255.0 - 128.0) / 127.0;
            // Inverse transpose for actual independent horizontal/vertical instance scales.
            detail *= cardScale;
            cardFrame = mat3(tangent, bitangent, face);
            // Opposing shape normals can cancel in a filtered footprint. Keep the finite carrier fallback.
            if (dot(detail, detail) > 1e-10) {
                face = normalize(tangent * detail.x + bitangent * detail.y + face * detail.z);
                baseNormal = face;
                cardFrameValid = true;
            }
#else
            vec3 detail = (textureGrad(u_normalTexture, v_diffuseUV, du, dv).rgb * 255.0 - 128.0) / 127.0;
            face = normalize(tangent * detail.x + bitangent * detail.y + face * detail.z);
#endif
        }
    }
#endif
#ifdef impostorFlag
    bool proxy = true;
#else
    bool proxy = u_foliageProxy > .5;
#endif
    if (!proxy && !gl_FrontFacing) face = -face;
#ifdef lightingFlag
    vec3 geometric = visibleFace(dx, dy, -viewDirection());
    if (proxy) {
        // Botanical base and mapped detail share the visible virtual side; the card only carries coverage.
        geometric = baseNormal;
        if (dot(geometric, -viewDirection()) < 0.0) { geometric = -geometric; face = -face; }
    }
#endif
    vec3 albedo = diffuse.rgb;
    // Snow pigment is authored in the texture and model; keep its material response below.
    float snow = abs(u_foliage - 2.0) < .5 ? 1.0 : 0.0;
#ifdef impostorMaterialFlag
    snow = cardSurface.a;
#endif
    albedo = wetAlbedo(albedo, u_wetness * (1.0 - snow) * .2);
    albedo = materialReflectance(albedo);
    // Godot's emission: the map times its tint, plus the constant glow.
    vec3 emission = vec3(0.0);
#if defined(emissiveTextureFlag) && defined(diffuseTextureFlag) && defined(emissiveColorFlag)
    emission = texture(u_emissiveTexture, v_diffuseUV).rgb * u_emissiveColor.rgb;
#elif defined(emissiveTextureFlag) && defined(diffuseTextureFlag)
    emission = texture(u_emissiveTexture, v_diffuseUV).rgb;
#elif defined(emissiveColorFlag)
    emission = u_emissiveColor.rgb;
#endif
#ifdef modelSurfaceFlag
    emission = emission * u_modelEmission + u_modelGlow;
    if (u_modelLight.x > 0.0) { emission *= u_modelLight.y; }
#endif
    // Fungal tissue breathes: its glow swells and fades slowly across a colony.
    if (u_foliage > 2.5) emission *= .94 + .06 * sin(u_rainTime * .65 + dot(v_cloudPosition.xy, vec2(.073, .051)));
#ifdef lightingFlag
    // A plant material of the GLB's own and an impostor card (its plant's baked bark and leaves) take their role's
    // response. Leaves pass their scattering through as thin tissue (below); solid bodies wrap it past the terminator.
    float roughness = mix(mix(.90, .95, snow), surface.g, leaves), metallic = 0.0, scatter = surface.b, wrap = 0.0;
#ifdef impostorMaterialFlag
    roughness = surface.g;
#endif
#if defined(modelSurfaceFlag) && !defined(impostorFlag)
    // A board material's finish, as its .tres gives it.
    if (u_modelBoard > .5) {
        vec3 finish = vec3(1.0);
#ifdef diffuseTextureFlag
        if (u_modelSurface.w > .5) finish = texture(u_modelSurfaceMap, v_diffuseUV).rgb;
#endif
        scatter = u_modelSurface.z * finish.r;
        roughness = u_modelSurface.x * finish.g;
        metallic = u_modelSurface.y * finish.b;
        wrap = (1.0 - leaves) * clamp(scatter, 0.0, 1.0);
    }
#endif
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
    scatter = clamp(scatter, 0.0, 1.0);
#ifndef impostorMaterialFlag
    // A branch card or impostor carries its crown's envelope normal, about which the leaves it stands for face every
    // way: widen the authored lobe by that spread, as specular antialiasing widens it by a pixel's normal variance.
    // Real leaf geometry and the pilot's baked source normals keep their authored roughness.
    if (proxy) roughness = sqrt(sqrt(min(pow(roughness, 4.0) + CROWN_NORMAL_VARIANCE, 1.0)));
#endif
    float film = (1.0 - snow) * u_wetness * .15;
    vec3 view = -viewDirection();
    // Both real surfaces and proxies use their own visible material frame for every repaired lobe.
    face = shadingNormal(face, geometric, view);
#ifdef impostorMaterialFlag
    // Diagnostic only: the recorded affine fit is not an accepted self-shadow solution (especially pine).
    float selfVisibility = v_color.a;
    if (cardFrameValid) {
        vec3 localLight = normalize((transpose(cardFrame) * light) * cardScale);
        selfVisibility = clamp(v_color.a + dot(v_color.rgb * 2.0 - 1.0, localLight), 0.0, 1.0);
    }
    visibility *= selfVisibility;
#endif
    // Bark and snow scatter a broad reflection, as does a crown's card (widened above); real leaves keep their
    // authored lobe. Rain smooths bark and leaves; separately marked snow keeps its dry response like the ground.
    Surface plant = opaqueSurface(albedo, face, geometric, roughness, film, visibility);
    metallicShare(plant, metallic);
    plant.scatter = wrap;
#if defined(impostorFlag) && !defined(impostorMaterialFlag)
    // A card's leaves face every way about its crown: each side takes the light that reaches it.
    plant.geometric = face;
#endif
#if defined(normalTextureFlag) && !defined(ambientTextureFlag)
    // Opaque cactus skin has a broad waxy highlight under the coarse shadow filter, using the terrain's existing
    // dielectric light model.
    plant.visibility = surfaceVisibility();
#else
#ifdef impostorMaterialFlag
    float transmitted = scatter;
#else
    float transmitted = leaves * scatter;
#endif
    // Actual thin tissue reflects on the visible side and transmits from the opposite side (thinTransmission).
    // Canopy occlusion attenuates the incoming sky alone; the leaf cavity map (detail) also its reflection.
    plant.back = thinTransmission(albedo, transmitted);
    plant.occlusion = mix(.85, mix(.7, 1.0, face.z * .5 + .5), leaves);
    plant.detail = surface.r;
    plant.specularOcclusion = surface.r;
#endif
    plant.emission = emission;
    albedo = shadeSurface(plant);
#else
    albedo += emission;
#endif
    fragColor = vec4(albedo, opacity);
}
