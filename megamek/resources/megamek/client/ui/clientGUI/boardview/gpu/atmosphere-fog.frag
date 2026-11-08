#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
precision highp float;
#endif
in vec2 v_uv;
uniform sampler2D u_depth;
uniform sampler2D u_waterDepth; // GpuWaterDepth: the nearest wave, where fog stops over water; else the scene depth
uniform vec3 u_fog;
uniform float u_haze;
uniform float u_noiseScale;
uniform vec3 u_fogVariation; // Height delta, density thinning, minimum local height.
uniform vec2 u_fogOffset;
uniform vec3 u_fogColor;
uniform float u_maxOpacity;
uniform float u_rays;
uniform vec3 u_sunColor;
uniform vec3 u_sunDirection;
uniform sampler2DShadow u_geometryShadow;
uniform mat4 u_geometryProjection;
uniform float u_hasGeometryShadow;
uniform float u_shadowStrength;
#define cloudShadowFlag
#define cloudVolumeFlag
// CLOUD_SHADOW
// SCATTERING_PHASE
// GROUND_LAYER

// Air this many world units nearer the sun than the surface under it reads lit (sunlight).
const float GEOMETRY_SHADOW_BIAS = 1.0;

float sunlight(vec3 position) {
    float light = cloudTransmission(position);
    if (u_hasGeometryShadow > 0.5 && u_shadowStrength > 0.0) {
        vec4 projected = u_geometryProjection * vec4(position, 1.0);
        vec3 uv = projected.xyz / projected.w * 0.5 + 0.5;
        if (uv.x > 0.0 && uv.y > 0.0 && uv.x < 1.0 && uv.y < 1.0 && uv.z > 0.0 && uv.z < 1.0) {
            // Map depth per world unit toward the sun: the depth row along the light, whatever the map's depth
            // measures (GpuShadowLight.SlabCamera: height across the casters' slab).
            float depthPerUnit = .5 * abs(dot(vec3(u_geometryProjection[0][2], u_geometryProjection[1][2],
                  u_geometryProjection[2][2]), u_sunDirection));
            // The finest level: the map's coarser ones serve only wide penumbrae (GpuShadowLight#levels).
            light *= mix(1.0, textureLod(u_geometryShadow, vec3(uv.xy, uv.z - GEOMETRY_SHADOW_BIAS * depthPerUnit), 0.0),
                  u_shadowStrength);
        }
    }
    return light;
}

void main() {
    if (outsideGroundLayer()) {
        fragColor = vec4(0, 0, 0, 1);
        return;
    }
    vec3 origin = world(0.0);
    vec3 direction = normalize(world(1.0) - origin);
    // Fog fills the air down to the water surface, not the column beneath it to the bed.
    float surface = min(texture(u_depth, v_uv).r, texture(u_waterDepth, v_uv).r);
    // Fog occupies air above the board, including against sky, while leaving the solid plinth untouched.
    vec3 endpoint = world(surface);
    if (groundBaseSide(endpoint, surface, u_fog.z)) {
        fragColor = vec4(0, 0, 0, 1);
        return;
    }
    vec2 segment = groundSegment(origin, endpoint, direction);
    float start = segment.x, end = segment.y;
    if (end <= start || u_fog.x + u_haze + u_rays <= 0.0) {
        fragColor = vec4(0, 0, 0, 1);
        return;
    }
    // Keep fog/haze sampling independent of the taller cloud-shaft volume.
    float top = max((u_fog.y + u_fogVariation.x) * 3.0, u_haze > 0.0 ? u_fog.y * 6.0 : 0.0);
    vec2 fogSegment = belowHeight(segment, origin, direction, u_fog.z + top);
    float stepLength = max(0.0, fogSegment.y - fogSegment.x) * 0.25;
    float opticalDepth = 0.0;
    for (int i = 0; i < 4; i++) {
        float from = fogSegment.x + float(i) * stepLength;
        vec3 first = origin + direction * from;
        vec3 last = first + direction * stepLength;
        vec3 position = (first + last) * 0.5;
        float height = u_fog.y, density = 1.0;
        if (u_fog.x > 0.0 && u_fogVariation.x + u_fogVariation.y > 0.0) {
            vec3 point = vec3(position.xy * u_noiseScale - u_fogOffset,
                  (position.z - u_fog.z) / u_fog.y);
            float bank = smoothstep(0.22, 0.78, groundNoise(point) * 0.7 + groundNoise(point * 2.0) * 0.3);
            height = max(u_fogVariation.z, height + (bank * 2.0 - 1.0) * u_fogVariation.x);
            density = mix(1.0, bank * bank * bank * bank, u_fogVariation.y);
        }
        density *= 1.0 - smoothstep(height * 1.5, height * 3.0, position.z - u_fog.z);
        float integral = heightIntegral(first.z - u_fog.z, last.z - u_fog.z, stepLength, height);
        vec2 hazeSegment = belowHeight(vec2(from, from + stepLength), origin, direction, u_fog.z + u_fog.y * 6.0);
        float edge = groundEdge(position, 0.5 / u_noiseScale);
        opticalDepth += (u_fog.x * integral * density + u_haze * max(0.0, hazeSegment.y - hazeSegment.x)) * edge;
    }
    float opacity = min(u_maxOpacity, 1.0 - exp(-opticalDepth));
    // Linear light the fog scatters, already exposed (BoardAtmosphere.Lighting.fog).
    vec3 illumination = u_fogColor;
    // Fog and haze share one opacity ceiling, even when both controls are at their maximum.
    vec3 scattered = illumination * opacity;
    // At the opacity cap the shaft contribution is exactly zero: skip all twelve shadow samples.
    if (u_rays > 0.0 && opacity < u_maxOpacity && surface < 1.0) {
        float lit = 0.0;
        float transmission = 1.0;
        float stepLength = (end - start) / 12.0;
        for (int i = 0; i < 12; i++) {
            vec3 position = origin + direction * (start + (float(i) + 0.5) * stepLength);
            float density = u_rays * (0.35 + 0.65 * exp(-max(0.0, position.z - u_fog.z) / (u_fog.y * 4.0)));
            density *= groundEdge(position, 0.5 / u_noiseScale);
            float segment = 1.0 - exp(-density * stepLength);
            lit += transmission * segment * sunlight(position);
            transmission *= 1.0 - segment;
        }
        float shaftOpacity = min(u_maxOpacity - opacity, (1.0 - opacity) * (1.0 - transmission));
        float phase = scatteringPhase(dot(direction, u_sunDirection), 0.5);
        vec3 shaftLight = illumination * 0.05 + u_sunColor * (lit / max(0.00001, 1.0 - transmission)) * phase * 2.0;
        scattered += shaftLight * shaftOpacity;
        opacity += shaftOpacity;
    }
    fragColor = vec4(scattered, 1.0 - opacity);
}
