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
// 1 for a mirror image drawn as it is, whose reversed winding turns its visible faces into back faces (GpuTerrain);
// an instanced one's batch flips the front face instead.
uniform float u_mirrored;
// How far a crown's leaf normals spread about its envelope normal, as the GGX alpha^2 it adds (twice the projected
// normal variance, as antialiasedRoughness adds it). Leaves facing every way over the outward hemisphere are the
// cosine-weighted facet distribution, which is GGX at alpha^2 = 1 (projected variance 1/2).
const float CROWN_NORMAL_VARIANCE = 1.0;
#ifdef treeDissolveFlag
// A tree changing detail level draws at both for TREE_DISSOLVE seconds of u_treeClock from when it began
// (v_treeDissolve, tree-instances.glsl): the level it takes keeps the pixels of one screen pattern under its progress,
// the one it leaves the others, so each pixel shows one of them and its depth twin keeps the same.
flat in float v_treeDissolve;
uniform float u_treeClock;
#endif
#ifdef impostorFlag
// IMPOSTOR_SAMPLE
#endif

// CUTOUT_FADE

void main() {
    vec3 baseNormal = normalize(v_normal);
    vec3 face = baseNormal;
    bool frontFacing = gl_FrontFacing != (u_mirrored > .5);
    vec3 crownNormal = frontFacing ? face : -face;
    // Hoist derivative work before any alpha discard or mapped/proxy branch.
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
#ifdef impostorFlag
    // An impostor's texels come from the frame each pixel shows: its own coordinate and gradients (impostor-sample.glsl).
    vec2 du, dv;
    vec3 frameView;
    vec2 uv = impostorUV(du, dv, frameView);
#else
    vec2 uv = v_diffuseUV;
#ifdef normalTextureFlag
    vec2 du = dFdx(v_diffuseUV), dv = dFdy(v_diffuseUV);
#endif
#endif
    surfaceVariance = normalVariance(dFdx(baseNormal), dFdy(baseNormal));
    vec4 diffuse = vec4(1.0);
#if defined(diffuseTextureFlag) && defined(impostorFlag)
    diffuse = textureGrad(u_diffuseTexture, uv, du, dv);
#elif defined(diffuseTextureFlag)
    diffuse = texture(u_diffuseTexture, v_diffuseUV);
#endif
    // How much of the surface is leaves, which scatter light around, rather than bark, cactus or snow.
    float leaves = abs(u_foliage - 1.0) < .5 ? 1.0 : 0.0;
#ifdef impostorFlag
    // An impostor's colour describes the surface its texels show (prepare_tree_lods.py): red, their mean occlusion;
    // green, the share of them that is bark, cactus or snow; blue and alpha, the mean roughness and scatter of the rest,
    // its leaves.
    leaves = 1.0 - v_color.g;
#ifdef normalTextureFlag
    // Its normal atlas: RGB, the normal of the surface the texel shows, in the plant's unscaled axes; alpha, that
    // surface's depth along the frame's view, in sphere diameters about .5. The normal stands as the tree does
    // (impostorNormal); the shadow is looked up at that surface (surface-lighting.glsl).
    vec4 shape = textureGrad(u_normalTexture, uv, du, dv);
    vec3 frameNormal = impostorNormal((shape.rgb * 255.0 - 128.0) / 127.0);
    // The quad's point lies off the frame's plane by its own offset along the frame's view, unless the frame is the view.
    impostorSurface = impostorPlaced(frameView * ((shape.a * 2.0 - 1.0) * v_impostorFrame.z - dot(v_impostorPoint, frameView)));
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
#ifdef lightingFlag
    vec3 surface = vec3(1.0, .58, .35);
#if defined(impostorFlag)
    surface = v_color.rba;
#elif defined(ambientTextureFlag)
    surface = texture(u_ambientTexture, v_diffuseUV).rgb;
#endif
    vec3 light = sunDirection();
    // Use the crown's smooth normal for stable shadow offsets; the detail normal is only leaf relief.
    float visibility = sculptShadow(crownNormal, light, u_metre, 0.0);
#endif
    // Alpha-tested texels own no color or depth. Keep texture/receiver derivatives above this cutoff;
    // normal detail uses the captured gradients, and single-level shadow/cloud reads use explicit LOD.
#ifdef cutoutFadeFlag
    if (opacity <= cutoutFadeCut(v_alphaTest, uv)) discard;
#elif defined(blendedFlag) && defined(alphaTestFlag)
    if (opacity <= v_alphaTest) discard;
#endif
#ifdef treeDissolveFlag
    if (v_treeDissolve != 0.0) {
        float progress = clamp((u_treeClock - abs(v_treeDissolve)) * (1.0 / TREE_DISSOLVE), 0.0, 1.0);
        // Interleaved gradient noise: an even spread of thresholds at every scale.
        float pattern = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(.06711056, .00583715))));
        if (v_treeDissolve > 0.0 ? pattern >= progress : pattern < progress) discard;
    }
#endif
#ifdef depthPrepassFlag
    // The depth twin keeps exactly these texels and writes their depth alone.
    return;
#endif
#ifdef normalTextureFlag
    // Derive the frame from the authored UVs, so mapped ribs follow bent stems and rotated instances.
    // Mip filtering removes subpixel normal detail without adding a mesh level or draw pass.
    float determinant = du.x * dv.y - du.y * dv.x;
#if defined(impostorFlag)
    // An impostor's normals are its plant's shape, not optional detail: decoded above. A texel's normal facing away
    // turns toward the viewer below, as a leaf card's does (proxy): the far mesh's leaves seen from behind.
    face = frameNormal;
    baseNormal = frameNormal;
    bool normalEnabled = false;
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
            vec3 detail = (textureGrad(u_normalTexture, v_diffuseUV, du, dv).rgb * 255.0 - 128.0) / 127.0;
            face = normalize(tangent * detail.x + bitangent * detail.y + face * detail.z);
        }
    }
#endif
#ifdef impostorFlag
    bool proxy = true;
#else
    bool proxy = u_foliageProxy > .5;
#endif
    if (!proxy && !frontFacing) face = -face;
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
    // A plant material of the GLB's own and an impostor (its plant's baked bark and leaves) take their role's
    // response. Leaves pass their scattering through as thin tissue (below); solid bodies wrap it past the terminator.
    float roughness = mix(mix(.90, .95, snow), surface.g, leaves), metallic = 0.0, scatter = surface.b, wrap = 0.0;
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
    // A branch card or impostor carries its crown's envelope normal, about which the leaves it stands for face every
    // way: widen the authored lobe by that spread, as specular antialiasing widens it by a pixel's normal variance.
    // Real leaf geometry keeps its authored roughness.
    if (proxy) roughness = sqrt(sqrt(min(pow(roughness, 4.0) + CROWN_NORMAL_VARIANCE, 1.0)));
    float film = (1.0 - snow) * u_wetness * .15;
    vec3 view = -viewDirection();
    // Both real surfaces and proxies use their own visible material frame for every repaired lobe.
    face = shadingNormal(face, geometric, view);
    // Bark and snow scatter a broad reflection, as does a crown's card (widened above); real leaves keep their
    // authored lobe. Rain smooths bark and leaves; separately marked snow keeps its dry response like the ground.
    Surface plant = opaqueSurface(albedo, face, geometric, roughness, film, visibility);
    metallicShare(plant, metallic);
    plant.scatter = wrap;
#ifdef impostorFlag
    // An impostor's leaves face every way about its crown: each side takes the light that reaches it.
    plant.geometric = face;
#endif
#if defined(normalTextureFlag) && !defined(ambientTextureFlag) && !defined(impostorFlag)
    // Opaque cactus skin has a broad waxy highlight under the coarse shadow filter, using the terrain's existing
    // dielectric light model.
    plant.visibility = surfaceVisibility();
#else
    float transmitted = leaves * scatter;
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
#ifdef cutoutFadeFlag
    cutoutFadeWrite(fragColor);
#endif
}
