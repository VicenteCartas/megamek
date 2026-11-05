// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Imported glTF surfaces fill a Surface from their material maps and are lit by shadeSurface, as the terrain is.
#ifdef modelSurfaceFlag
uniform float u_modelPaintMask; // Albedo alpha selects paint rather than opacity, in lit and unlit views.
uniform vec3 u_buildingPaint; // Placement colour on explicitly paintable building surfaces; white elsewhere.
in vec2 v_modelUV;
uniform float u_modelPremultiplied; // Premultiplied alpha is also used by unlit material previews.
#endif
#ifdef lightingFlag
#ifdef modelSurfaceFlag
uniform vec4 u_modelSurface; // roughness, metallic and scattering factors; finish map present
uniform sampler2D u_modelSurfaceMap; // R scattering, G roughness, B metallic
uniform vec3 u_modelEmission, u_modelGlow; // the emission map's linear tint; constant emission
#ifdef modelRubyFlag
uniform float u_modelRuby; // inverse absorption reference distance in world units
uniform mat4 u_rubyVolume; // world to the authored closed crystal
uniform vec4 u_rubyPlanes[32]; // outward facet normal and plane distance
uniform int u_rubyFacets; // the used entries of u_rubyPlanes
#endif
#endif
#ifdef modelReliefFlag
uniform sampler2D u_modelHeight; // white high
uniform vec4 u_modelRelief; // depth along U and V in UV units; deep parallax's minimum and maximum layers, 0 for one step
#endif
#if defined(modelSurfaceFlag) && (defined(normalTextureFlag) || defined(modelReliefFlag))
uniform float u_normalMaps; // the board's normal maps and parallax, as one switch
#endif
#if defined(modelSurfaceFlag) && defined(ambientTextureFlag)
uniform sampler2D u_ambientTexture;
#endif

// The interpolated vertex normal; a model without normals shades its visible faces.
vec3 modelVertexNormal(vec3 geometric) {
#ifdef normalFlag
    return normalize(v_normal);
#else
    return geometric;
#endif
}

#ifdef modelReliefFlag
// Godot's heightmap parallax (StandardMaterial3D): the view, in the UV frame, moves every map's read across the relief.
// Deep parallax marches from the maximum layers at grazing views to the minimum head-on, then interpolates the crossing.
vec2 modelParallax(vec3 view, vec3 tangent, vec3 bitangent, vec3 normal, vec2 ux, vec2 uy) {
    vec3 v = normalize(vec3(dot(view, tangent), dot(view, bitangent), dot(view, normal)));
    vec2 reach = v.xy * u_modelRelief.xy;
    // A relief shallower than half a pixel moves nothing visible: skip its reads.
    if (dot(reach, reach) < .25 * max(dot(ux, ux), dot(uy, uy))) return vec2(0.0);
    float depth = 1.0 - textureGrad(u_modelHeight, v_modelUV, ux, uy).r;
    if (u_modelRelief.w < 1.0) return -reach * depth;
    float layers = mix(u_modelRelief.w, u_modelRelief.z, abs(v.z));
    vec2 delta = reach / layers;
    vec2 shift = vec2(0.0);
    float current = 0.0;
    for (int layer = 0; layer < 128 && current < depth; layer++) {
        shift -= delta;
        depth = 1.0 - textureGrad(u_modelHeight, v_modelUV + shift, ux, uy).r;
        current += 1.0 / layers;
    }
    float after = depth - current;
    float before = 1.0 - textureGrad(u_modelHeight, v_modelUV + shift + delta, ux, uy).r - current + 1.0 / layers;
    return after - before < -1e-6 ? mix(shift, shift + delta, after / (after - before)) : shift;
}
#endif

vec3 modelNormal(vec3 view, out vec3 geometric) {
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
    geometric = visibleFace(dx, dy, view);
    vec3 normal = modelVertexNormal(geometric);
    // Reflections reverse triangle winding without reversing the inverse-transpose normal. Orient the authored
    // frame to the visible geometric face, so mirrored and two-sided props agree independently of glFrontFace.
    float visibleSide = dot(normal, geometric) < 0.0 ? -1.0 : 1.0;
#if defined(modelSurfaceFlag) && (defined(normalTextureFlag) || defined(modelReliefFlag))
    // Derive the glTF tangent frame from the original mesh UVs, independent of projected unit camouflage.
    // Degenerate UVs keep the geometric normal; mirrored UVs preserve their handedness.
    vec2 ux = dFdx(v_modelUV), uy = dFdy(v_modelUV);
    float determinant = ux.x * uy.y - ux.y * uy.x;
    if (abs(determinant) > 1e-10) {
        vec3 tangent = (dx * uy.y - dy * ux.y) / determinant;
        tangent -= normal * dot(normal, tangent);
        if (dot(tangent, tangent) > 1e-10) {
            tangent = normalize(tangent);
            vec3 bitangent = cross(normal, tangent);
            vec3 uvBitangent = (dy * ux.x - dx * uy.x) / determinant;
            bitangent *= dot(bitangent, uvBitangent) < 0.0 ? -1.0 : 1.0;
#ifdef modelReliefFlag
            if (u_normalMaps > .5) modelShift = modelParallax(view, tangent, bitangent, normal, ux, uy);
#endif
#ifdef normalTextureFlag
            if (u_normalMaps > .5) {
                vec3 mapped = textureGrad(u_normalTexture, v_modelUV + modelShift, ux, uy).xyz * 2.0 - 1.0;
                normal = normalize(mat3(tangent, bitangent, normal) * mapped);
            }
#endif
        }
    }
#endif
    normal *= visibleSide;
    // main() calls this before any discard, and every branch above has joined again.
    surfaceVariance += normalVariance(dFdx(normal), dFdy(normal));
    return normal;
}

#ifdef modelRubyFlag
// The environment deliberately omits the sun. Its refracted image is the finite solar disk convolved with a
// normalized angular lobe: roughness broadens and dims the highlight without changing its integrated energy.
vec3 rubySun(vec3 position, vec3 direction, float roughness) {
    float optical = max(.12, roughness);
    float variance = 2.0 * optical * optical * optical * optical + SUN_DIAMETER * SUN_DIAMETER / 16.0;
    float concentration = 1.0 / variance;
    // The spherical Gaussian's normalization is k/(2*pi*(1-exp(-2*k))); exp(-2*k) is negligible here.
    // sunColor is irradiance/pi, giving .5*k after the radiance conversion.
    float lobe = .5 * concentration * exp(concentration * (dot(direction, sunDirection()) - 1.0));
    vec3 light = sunColor() * lobe;
#ifdef shadowMapFlag
    vec4 projected = u_shadowMapProjViewTrans * vec4(position, 1.0);
    vec3 uv = projected.xyz / projected.w * .5 + .5;
    uv.z = min(uv.z, .998);
    light *= shadowVisibility(shadowTent(uv, vec2(0.0), 1.5, false));
#endif
#ifdef cloudShadowFlag
    light *= cloudTransmission(position);
#endif
    return light;
}

// Internal refraction through a closed convex crystal. The escaped ray sees the filtered sky/ground environment:
// inclusions make it translucent, not a clear alpha window onto the scene. Two internal reflections are retained.
vec3 rubyTransport(vec3 pigment, vec3 normal, vec3 view, float roughness) {
    vec3 position = (u_rubyVolume * vec4(v_cloudPosition, 1.0)).xyz;
    vec3 direction = refract(-view, normal, 1.0 / 1.76);
    float energy = 1.0 - specularAlbedo(max(0.0, dot(normal, view)), roughness, vec3(.0758)).r;
    float path = 0.0;
    vec3 radiance = vec3(0.0), worldExit = v_cloudPosition;
    for (int bounce = 0; bounce < 3; bounce++) {
        vec3 ray = mat3(u_rubyVolume) * direction;
        float nearest = 1e20;
        vec3 exitNormal = vec3(0.0);
        for (int face = 0; face < u_rubyFacets; face++) {
            float along = dot(u_rubyPlanes[face].xyz, ray);
            if (along <= 1e-6) continue;
            float distance = (u_rubyPlanes[face].w - dot(u_rubyPlanes[face].xyz, position)) / along;
            if (distance > 1e-4 && distance < nearest) { nearest = distance; exitNormal = u_rubyPlanes[face].xyz; }
        }
        if (nearest > 1e19) break;
        path += nearest;
        worldExit += direction * nearest;
        vec3 worldNormal = normalize(transpose(mat3(u_rubyVolume)) * exitNormal);
        vec3 escaped = refract(direction, -worldNormal, 1.76);
        float fresnel = 1.0;
        if (dot(escaped, escaped) > 1e-6) {
            escaped = normalize(escaped);
            // Exact interior Fresnel tends continuously to one at the critical angle. Retain the reflected share
            // at every interface, including below TIR, rather than switching between two unrelated exit rays.
            float ci = max(0.0, dot(direction, worldNormal)), ct = max(0.0, dot(escaped, worldNormal));
            vec2 ratios = vec2(1.76 * ci - ct, ci - 1.76 * ct) / max(vec2(1.76 * ci + ct, ci + 1.76 * ct), vec2(1e-6));
            fresnel = .5 * dot(ratios, ratios);
            vec3 ground = environmentGround(GROUND_ALBEDO);
            vec3 light = environmentRadiance(escaped, max(.12, roughness), ground, surfaceAmbient(vec3(0.0, 0.0, 1.0)));
            light = mix(hiddenSky(escaped, ground, GROUND_ALBEDO, light), light, skyVisibility(worldExit, escaped));
            light += rubySun(worldExit, escaped, roughness);
            vec3 absorption = pow(clamp(pigment, vec3(.001), vec3(.95)), vec3(max(.12, path * u_modelRuby)));
            radiance += light * absorption * energy * (1.0 - fresnel);
        }
        energy *= fresnel;
        position += ray * nearest - exitNormal * .001;
        direction = reflect(direction, worldNormal);
    }
    return radiance;
}
#endif

vec3 modelSurface(vec3 albedo, vec3 emission, vec3 normal, vec3 geometric, vec3 view) {
    albedo = materialReflectance(albedo);
    float roughness = .85, metallic = 0.0, scatter = 0.0;
#ifdef modelSurfaceFlag
    roughness = u_modelSurface.x; metallic = u_modelSurface.y; scatter = u_modelSurface.z;
    if (u_modelSurface.w > .5) {
        vec3 channels = texture(u_modelSurfaceMap, v_modelUV + modelShift).rgb;
        scatter *= channels.r;
        roughness *= channels.g;
        metallic *= channels.b;
    }
    // Godot's emission: the map times its tint, plus the constant glow.
    emission = emission * u_modelEmission + u_modelGlow;
#endif
    // Apply light controls after combining glTF emission and the shared material's glow.
    if (u_modelLight.x > 0.0) { emission *= u_modelLight.y; }
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
    scatter = clamp(scatter, 0.0, 1.0);
    // Finish any scar normal before repairing the shared reflection frame.
    normal = shadingNormal(normal, geometric, view);
    Surface surface = opaqueSurface(albedo, normal, geometric, roughness, 0.0, surfaceVisibility());
    metallicShare(surface, metallic);
    surface.scatter = scatter;
#ifdef modelRubyFlag
    surface.dielectric = .0758;
    // Natural cloudy ruby partitions non-reflected light between inclusions (60%) and clearer paths (40%).
    surface.front = vec3(.45);
    surface.back = thinTransmission(albedo, .15);
#endif
#if defined(modelSurfaceFlag) && defined(ambientTextureFlag)
    surface.occlusion = surface.specularOcclusion = texture(u_ambientTexture, v_modelUV + modelShift).r;
#endif
    surface.emission = emission;
    vec3 lit = shadeSurface(surface);
#ifdef modelRubyFlag
    lit += .4 * rubyTransport(albedo, geometric, view, roughness);
#endif
    return lit;
}
#endif
