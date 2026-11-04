#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Imported models: units, props, buildings and their liquids. libGDX's vertex stage and material attributes supply the
// inputs; lit draws take the board's one light model (model-surface.glsl, shadeSurface), unlit draws their material
// colour. Scene radiance stays linear; GpuSceneRadiance stores it.
#ifdef normalFlag
in vec3 v_normal;
#endif
#ifdef colorFlag
in vec4 v_color;
#endif
#ifdef blendedFlag
in float v_opacity;
#ifdef alphaTestFlag
in float v_alphaTest;
#endif
#endif
#ifdef diffuseTextureFlag
in vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
#ifdef emissiveTextureFlag
in vec2 v_emissiveUV;
uniform sampler2D u_emissiveTexture;
#endif
#ifdef emissiveColorFlag
uniform vec4 u_emissiveColor;
#endif
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
#endif
// Enabled, emission multiplier, diffuse multiplier. Zero leaves authored materials unchanged, including in renderers
// that do not register this control. Light timing and intensity belong to the material's draw setter.
uniform vec3 u_modelLight;
// The heightmap parallax's shift of every map read (model-surface.glsl), zero without one.
vec2 modelShift = vec2(0.0);

void main() {
#ifdef lightingFlag
    // The shading frame takes its derivatives before any scar or alpha discard.
    vec3 modelView = -viewDirection();
    vec3 modelFace;
    vec3 modelShading = modelNormal(modelView, modelFace);
#endif
    // surface-frame-ready
    vec4 diffuse = vec4(1.0);
    float paintShare = 1.0;
#ifdef diffuseTextureFlag
    diffuse = texture(u_diffuseTexture, v_diffuseUV + modelShift);
#ifdef modelSurfaceFlag
    if (u_modelPaintMask > .5) { paintShare = diffuse.a; diffuse.a = 1.0; }
#endif
#endif
#ifdef colorFlag
    diffuse *= vec4(mix(vec3(1.0), v_color.rgb, paintShare), v_color.a);
#endif
#ifdef diffuseColorFlag
    diffuse *= u_diffuseColor;
#endif
#ifdef modelSurfaceFlag
    diffuse.rgb *= mix(vec3(1.0), u_buildingPaint, paintShare);
#endif
    // unit-overlays
#if defined(emissiveTextureFlag) && defined(emissiveColorFlag)
    vec4 emissive = texture(u_emissiveTexture, v_emissiveUV + modelShift) * u_emissiveColor;
#elif defined(emissiveTextureFlag)
    vec4 emissive = texture(u_emissiveTexture, v_emissiveUV + modelShift);
#elif defined(emissiveColorFlag)
    vec4 emissive = u_emissiveColor;
#else
    vec4 emissive = vec4(0.0);
#endif
#if defined(blendedFlag) && defined(modelSurfaceFlag)
    // Godot's premultiplied alpha (a .tres's blend_mode 4): the albedo fades with the opacity before lighting, so the
    // diffuse light does and the specular reflection, which the dielectric's own reflectance sets, does not.
    if (u_modelPremultiplied > 0.5) { diffuse.rgb *= diffuse.a * v_opacity; }
#endif
    if (u_modelLight.x > 0.0) {
        diffuse.rgb *= u_modelLight.z;
    }
#ifdef lightingFlag
    fragColor.rgb = modelSurface(diffuse.rgb, emissive.rgb, modelShading, modelFace, modelView);
#else
    if (u_modelLight.x > 0.0) { emissive.rgb *= u_modelLight.y; }
    fragColor.rgb = diffuse.rgb + emissive.rgb;
#endif
#ifdef blendedFlag
    fragColor.a = diffuse.a * v_opacity;
#ifdef alphaTestFlag
    if (fragColor.a <= v_alphaTest) discard;
#endif
#else
    fragColor.a = 1.0;
#endif
    // Tactical draws after the scene composite may borrow a scene color texture or an imported material. Their samples
    // and factors are linear too, but their destination is already display encoded.
#if defined(displayOutputFlag) || (!defined(lightingFlag) && !defined(sceneUnlitFlag) && (defined(linearTextureFlag) || defined(linearColorsFlag)))
    fragColor.rgb = toDisplay(fragColor.rgb);
#endif
}
