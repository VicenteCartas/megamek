// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Split-sum integration of the actual axisymmetric atmosphere gradient, excluding the directional sun/moon.
// RG are normalized GGX convolution weights for upper hemisphere and sqrt(height); BA integrate Smith GGX.
// The separate final row is cosine-weighted BRDF albedo. GpuEnvironmentLighting generates this one table.
#ifdef environmentLightingFlag
uniform vec3 u_environmentSky, u_environmentHorizon;
uniform vec2 u_environmentImage; // 0 analytic, 1 imported, 2 generated sky; radiance gain
uniform sampler2D u_environmentLut;

vec4 environmentTable(vec2 pixel) {
    return textureLod(u_environmentLut, (pixel + .5) / vec2(textureSize(u_environmentLut, 0)), 0.0);
}

vec3 imageEnvironment(vec3 direction, float roughness) {
    float longitude = dot(direction.xy, direction.xy) > 1e-12 ? atan(direction.y, direction.x) : 0.0;
    vec2 uv = vec2(longitude / (2.0 * PI) + .5,
          acos(clamp(direction.z, -1.0, 1.0)) / PI);
    float level = clamp(roughness, 0.0, 1.0) * 5.0, low = floor(level), high = min(5.0, low + 1.0);
    vec2 a = vec2(mod(low, 3.0) * 130.0, 130.0 + floor(low / 3.0) * 66.0);
    vec2 b = vec2(mod(high, 3.0) * 130.0, 130.0 + floor(high / 3.0) * 66.0);
    // Pixel coordinates include the half-pixel interpolation across each tile's seam and pole gutters.
    return mix(environmentTable(a + .5 + uv * vec2(128.0, 64.0)).rgb,
          environmentTable(b + .5 + uv * vec2(128.0, 64.0)).rgb, fract(level)) * u_environmentImage.y;
}
#endif

#ifdef environmentLightingFlag
uniform float u_shadowStrength;
#endif
float shadowVisibility(float visibility) {
#ifdef environmentLightingFlag
    return mix(1.0, visibility, u_shadowStrength);
#else
    return visibility;
#endif
}

vec2 integratedBrdf(float nv, float roughness) {
#ifdef normalQualityFlag
    // Karis 2014, the split sum's analytic fit. Normal quality's light reads no table: only without any does it pay.
    vec4 r = clamp(roughness, MIN_ROUGHNESS, 1.0) * vec4(-1.0, -.0275, -.572, .022) + vec4(1.0, .0425, 1.04, -.04);
    float a = min(r.x * r.x, pow(2.0, -9.28 * clamp(nv, 0.0, 1.0))) * r.x + r.y;
    return vec2(-1.04, 1.04) * a + r.zw;
#elif defined(environmentLightingFlag)
    return environmentTable(vec2(clamp(nv, 0.0, 1.0), clamp(roughness, MIN_ROUGHNESS, 1.0)) * 127.0).ba;
#else
    // Legacy/test environments without this attribute use a bounded mirror Fresnel budget and no LUT sampler.
    float grazing = pow(1.0 - clamp(nv, 0.0, 1.0), 5.0);
    return vec2(1.0 - grazing, grazing);
#endif
}

vec3 specularAlbedo(float nv, float roughness, vec3 f0) {
    vec2 brdf = integratedBrdf(nv, roughness);
    return clamp(f0 * brdf.x + brdf.y, 0.0, 1.0);
}

// Single-scattering GGX loses the light that bounces more than once between microfacets: 1 - E of white light, most
// at rough grazing views. That light leaves as more of the same lobe, coloured by f0 (Fdez-Aguera 2019), so rough
// metals, which have no diffuse layer to take it, no longer darken.
vec3 specularMultiscatter(float nv, float roughness, vec3 f0) {
    vec2 brdf = integratedBrdf(nv, roughness);
    float white = max(1e-4, brdf.x + brdf.y);
    return 1.0 + f0 * ((1.0 - white) / white);
}

#ifdef environmentLightingFlag
// The finite-source approximation redistributes the material lobe. Match its directional integral to the original
// GGX material so the shared reciprocal diffuse budget remains valid, including at nearly tangent views.
vec3 directionalNormalization(float nv, float roughness, vec3 f0) {
    vec2 filtered = environmentTable(vec2(128.0 + sqrt(sqrt(clamp(nv, 0.0, 1.0))) * 127.0,
          clamp(roughness, MIN_ROUGHNESS, 1.0) * 127.0)).rg;
    return specularAlbedo(nv, roughness, f0) / max(vec3(1e-7), f0 * filtered.x + filtered.y);
}
#endif

vec3 averageSpecularAlbedo(float roughness, vec3 f0) {
#if defined(environmentLightingFlag) && !defined(normalQualityFlag)
    vec2 brdf = environmentTable(vec2(clamp(roughness, MIN_ROUGHNESS, 1.0) * 127.0, 128.0)).rg;
    return clamp(f0 * brdf.x + brdf.y, 0.0, 1.0);
#else
    return f0 + (1.0 - f0) / 21.0;
#endif
}

// A reciprocal matte layer spends exactly the energy left by GGX. For white reflectance its hemispherical
// integral plus the specular lobe is one; no extra grazing-angle light is created by adding Lambert to Fresnel.
vec3 diffuseReflectance(vec3 normal, vec3 light, vec3 view, float roughness, vec3 f0) {
    float nl = max(0.0, dot(normal, light)), nv = max(0.0, dot(normal, view));
    if (nl <= 0.0 || nv <= 0.0) return vec3(0.0);
    vec3 outgoing = 1.0 - specularAlbedo(nv, roughness, f0);
    vec3 incoming = 1.0 - specularAlbedo(nl, roughness, f0);
    return outgoing * incoming / max(vec3(.0001), 1.0 - averageSpecularAlbedo(roughness, f0)) * nl;
}

#ifdef environmentLightingFlag
vec2 environmentWeights(vec3 direction, float roughness) {
    return environmentTable(vec2(clamp(direction.z * .5 + .5, 0.0, 1.0), clamp(roughness, 0.0, 1.0)) * 127.0).rg;
}
#endif

// Radiance of a GGX-filtered analytic sky/ground environment. N=V=R is the usual split-sum approximation.
vec3 environmentRadiance(vec3 direction, float roughness, vec3 ground, vec3 fallbackSky) {
#if defined(environmentLightingFlag) && !defined(normalQualityFlag)
    if (u_environmentImage.x > .5) {
        vec3 image = imageEnvironment(direction, roughness);
        // Generated skies contain no lower hemisphere: retain the receiving surface's existing ground bounce.
        if (u_environmentImage.x > 1.5) image += ground * (1.0 - environmentWeights(direction, roughness).x);
        return image;
    }
    vec2 basis = environmentWeights(direction, roughness);
    return u_environmentHorizon * basis.x + (u_environmentSky - u_environmentHorizon) * basis.y
          + ground * (1.0 - basis.x);
#else
#ifdef normalQualityFlag
    fallbackSky *= .9; // Level with the prefiltered sky's mean over a board's lit ground.
#endif
    return mix(ground, fallbackSky, clamp(direction.z * .5 + .5, 0.0, 1.0));
#endif
}

vec3 environmentSpecular(vec3 normal, vec3 view, float roughness, vec3 f0, vec3 ground, vec3 fallbackSky) {
    if (dot(normal, view) <= 0.0) return vec3(0.0);
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    return environmentRadiance(reflect(-view, normal), roughness, ground, fallbackSky)
          * specularAlbedo(max(0.0, dot(normal, view)), roughness, f0);
}
