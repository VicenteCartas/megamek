#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Open water; premultiplied reflection and in-scattered light over the visible bed.
#define waterSurfaceFlag
in vec3 v_waterRest;
in float v_waterCrest;
// water-uniforms
// water-lighting-functions
// biome-water-functions
// water-pool-functions

void main() {
    // Capture before coverage rejection, shared by geometric facing and the reflected cloud-plane footprint.
    waterPositionDx = dFdx(v_cloudPosition);
    waterPositionDy = dFdy(v_cloudPosition);
    vec3 geometric = visibleFace(waterPositionDx, waterPositionDy, -viewDirection());
    vec3 face = normalize(v_normal);
    surfaceVariance = normalVariance(dFdx(face), dFdy(face));
    if (waterHidden()) discard;
    vec4 habitat, mixture;
    waterHabitat(habitat, mixture);
    vec3 tint = waterTint(mixture);
    bool procedural = u_waterMaterial.y > 0.5;
#ifdef authoredWaterFlag
    vec3 authored = procedural ? vec3(0.0) : texture(u_diffuseTexture, v_diffuseUV).rgb;
#else
    vec3 authored = vec3(0.0);
#endif
    WaterLighting illumination = waterLighting(false);
    vec3 noSheet;
    waterOutput(waterPool(false, geometric, habitat, mixture, tint, authored, illumination, face, 1.0, noSheet));
}
