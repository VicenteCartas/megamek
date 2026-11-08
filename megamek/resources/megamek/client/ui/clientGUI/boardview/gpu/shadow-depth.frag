#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shadow-map depth only; camera depth must retain the actual surface position.

#if defined(diffuseTextureFlag) && defined(blendedFlag)
in vec2 v_texCoords0;
uniform sampler2D u_diffuseTexture;
uniform float u_alphaTest;
#endif

#ifdef PackedDepthFlag
in float v_depth;
// SHADOW_MAP_PASS: GpuTerrain's shadow pass defines SHADOW_PASS.
#ifdef SHADOW_PASS
// The map's texels across (GpuShadowLight.register).
uniform float u_shadowTexels;
#endif
#endif
#if defined(PackedDepthFlag) && defined(SHADOW_PASS) || defined(impostorFlag)
// The pass's view-projection (libGDX's camera uniform).
uniform mat4 u_projViewTrans;
#endif
#ifdef impostorFlag
// IMPOSTOR_SAMPLE
// Its normal atlas, whose alpha holds the depth of each texel's surface along its frame's view (prepare_tree_lods.py).
uniform sampler2D u_normalTexture;
#endif

void main() {
#ifdef impostorFlag
    // A tree impostor shows the frame nearest the light's view (impostor-sample.glsl), and its texel's surface stands off
    // the quad by its depth along that view (terrain-foliage.frag places it alike): the map stores that surface, so the
    // plant shadows itself and what stands near it as its mesh does. The sun's map is orthographic: depth is linear.
    vec2 gradX, gradY;
    vec3 frameView;
    vec2 uv = impostorUV(gradX, gradY, frameView);
    float coverage = textureGrad(u_diffuseTexture, uv, gradX, gradY).a;
    vec3 offset = impostorOffset(frameView, textureGrad(u_normalTexture, uv, gradX, gradY).a);
    float offsetDepth = .5 * dot(vec3(u_projViewTrans[0][2], u_projViewTrans[1][2], u_projViewTrans[2][2]), offset);
#else
    float offsetDepth = 0.0;
#endif
#ifdef PackedDepthFlag
    // Measured in shadow texels, this follows the actual face slope along the light, including vertical cliffs.
    // Half-texel PCF offsets plus bilinear comparisons reach at most 1.5 texels in each axis.
    // Evaluate derivatives before alpha discard so cutout edges retain valid neighbor values.
    // A depth that measures height across the casters' slab (GpuShadowLight.SlabCamera) also changes from texel to
    // texel at one distance from the sun (drift, per texel; surface-lighting.glsl's shadowDrift per map width), which
    // the receivers follow. Along each axis the bias covers the farthest texel a comparison reads, 1.5 texels out: 1.5
    // texels of the slope without the drift (bent), the bias depth along the light gives (a face grazed by the light),
    // or, where the drift adds to that slope (a face turned to the sun), the slope with it across a bilinear
    // comparison's texels, which share one reference, plus half a texel of bent. Depth along the view does not drift.
    vec2 drift = vec2(0.0);
#ifdef SHADOW_PASS
    if (u_projViewTrans[0][3] == 0.0 && u_projViewTrans[1][3] == 0.0 && u_projViewTrans[2][3] == 0.0) {
        vec3 across = vec3(u_projViewTrans[0][0], u_projViewTrans[1][0], u_projViewTrans[2][0]);
        vec3 up = vec3(u_projViewTrans[0][1], u_projViewTrans[1][1], u_projViewTrans[2][1]);
        vec3 row = vec3(u_projViewTrans[0][2], u_projViewTrans[1][2], u_projViewTrans[2][2]);
        drift = vec2(dot(row, across) / dot(across, across), dot(row, up) / dot(up, up)) / u_shadowTexels;
    }
#endif
    vec2 change = vec2(dFdx(v_depth), dFdy(v_depth));
    vec2 bent = abs(change - drift);
    vec2 axes = max(abs(change) + .5 * bent, 1.5 * bent);
    float bias = axes.x + axes.y;
    // Plus one step of the 16-bit map, which rounds what it stores: a face square to the light keeps its own depth. An
    // impostor's bias follows its quad, whose depth is smooth, not its texels' surfaces.
    float depth = clamp(v_depth + offsetDepth + bias + 1.0 / 65535.0, 0.0, 1.0);
#endif
#ifdef impostorFlag
    if (coverage < u_alphaTest) discard;
#elif defined(diffuseTextureFlag) && defined(blendedFlag)
    if (texture(u_diffuseTexture, v_texCoords0).a < u_alphaTest) discard;
#endif
#ifdef PackedDepthFlag
    // PackedDepthFlag is libGDX's vertex-depth selector; the framebuffer stores only D16 now.
    gl_FragDepth = depth;
#endif
}
