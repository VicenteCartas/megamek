#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Authored ground artwork. DefaultShader owns the uniforms, material binding and directional shadow map.

in vec2 v_diffuseUV;
in vec3 v_normal;
in vec4 v_color;
uniform sampler2D u_diffuseTexture;
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
uniform float u_normalMaps;
#endif
#ifdef ambientTextureFlag
uniform sampler2D u_ambientTexture;
#endif
#ifdef modelSurfaceFlag
// The GLB material's finish (GpuModelMaterial): a board material's .tres, or the GLB's own glTF material.
uniform vec4 u_modelSurface;
uniform sampler2D u_modelSurfaceMap; // R scattering, G roughness, B metallic
uniform vec3 u_modelEmission, u_modelGlow;
uniform vec3 u_modelLight; // GpuModelLight: enabled, emission multiplier (night lights fade by day), unused here
uniform float u_modelBoard;
#endif
#ifdef emissiveTextureFlag
uniform sampler2D u_emissiveTexture;
#endif
#ifdef emissiveColorFlag
uniform vec4 u_emissiveColor;
#endif
uniform float u_groundResponse;
// ground-surface-functions

void main() {
    vec3 geometric = visibleFace(dFdx(v_cloudPosition), dFdy(v_cloudPosition), -viewDirection());
    vec3 face = normalize(v_normal);
    surfaceVariance = normalVariance(dFdx(face), dFdy(face));
    vec3 albedo = texture(u_diffuseTexture, v_diffuseUV).rgb * v_color.rgb;
    vec3 normal = groundNormal(v_diffuseUV, 1.0);
    float wet = u_wetness * step(0.0, u_groundResponse) * smoothstep(0.2, 0.8, v_normal.z);
    float response = max(0.0, u_groundResponse);
    albedo = wetAlbedo(albedo, groundAbsorption(wet, response));
    float puddle = groundPuddle(wet, response);
    groundFilm(normal, puddle);
    // A board material's finish; the GLB's own material keeps the ground's matte response, as it is ground.
    Finish finish = Finish(-1.0, 0.0, 0.0, vec3(0.0));
#if defined(emissiveTextureFlag) && defined(emissiveColorFlag)
    finish.emission = texture(u_emissiveTexture, v_diffuseUV).rgb * u_emissiveColor.rgb;
#elif defined(emissiveTextureFlag)
    finish.emission = texture(u_emissiveTexture, v_diffuseUV).rgb;
#elif defined(emissiveColorFlag)
    finish.emission = u_emissiveColor.rgb;
#endif
#ifdef modelSurfaceFlag
    if (u_modelBoard > .5) {
        vec3 map = u_modelSurface.w > .5 ? texture(u_modelSurfaceMap, v_diffuseUV).rgb : vec3(1.0);
        finish = Finish(u_modelSurface.x * map.g, u_modelSurface.y * map.b, u_modelSurface.z * map.r, finish.emission);
    }
    finish.emission = finish.emission * u_modelEmission + u_modelGlow;
    if (u_modelLight.x > 0.0) { finish.emission *= u_modelLight.y; }
#endif
    float occlusion = 1.0;
#ifdef ambientTextureFlag
    occlusion = texture(u_ambientTexture, v_diffuseUV).r;
#endif
    fragColor = vec4(groundLighting(albedo, normal, geometric, wet, response, puddle, finish, occlusion), 1.0);
}
