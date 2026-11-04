// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Imported glTF surfaces fill a Surface from their material maps and are lit by shadeSurface, as the terrain is.
#ifdef lightingFlag
#ifdef modelSurfaceFlag
uniform vec4 u_modelSurface; // roughness, metallic and scattering factors; finish map present
uniform sampler2D u_modelSurfaceMap; // R scattering, G roughness, B metallic
uniform vec3 u_modelEmission, u_modelGlow; // the emission map's linear tint; constant emission
uniform float u_modelPaintMask; // 1: the albedo's alpha is a paint mask, where alone the vertex colour tints (model.frag)
uniform float u_modelPremultiplied; // 1: Godot's premultiplied alpha, only the diffuse share fades (model.frag)
uniform float u_modelRuby; // inverse absorption reference distance in world units; zero for other materials
uniform mat4 u_rubyVolume; // one closed crystal's world-to-normalized bounding volume
in vec2 v_modelUV;
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
    // main() calls this before any discard, and every branch above has joined again.
    surfaceVariance += normalVariance(dFdx(normal), dFdy(normal));
#ifdef normalFlag
    return gl_FrontFacing ? normal : -normal;
#else
    return normal;
#endif
}

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
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
    scatter = clamp(scatter, 0.0, 1.0);
    // Finish any scar normal before repairing the shared reflection frame.
    normal = shadingNormal(normal, geometric, view);
    Surface surface = opaqueSurface(albedo, normal, geometric, roughness, 0.0, surfaceVisibility());
    metallicShare(surface, metallic);
    surface.scatter = scatter;
#ifdef modelSurfaceFlag
    if (u_modelRuby > 0.0) {
        // Cloudy corundum has a solid visible boundary, with wavelength-dependent transport inside it.
        // An oriented-volume chord approximates thickness; it never samples the scenery through the body.
        vec3 position = clamp((u_rubyVolume * vec4(v_cloudPosition, 1.0)).xyz, vec3(-.999), vec3(.999));
        vec3 direction = mat3(u_rubyVolume) * (dot(geometric, sunDirection()) < 0.0 ? sunDirection() : -view);
        vec3 reach = (mix(vec3(-1.0), vec3(1.0), step(vec3(0.0), direction)) - position)
              / (sign(direction + vec3(1e-12)) * max(abs(direction), vec3(1e-6)));
        float thickness = max(.2, min(reach.x, min(reach.y, reach.z)) * u_modelRuby);
        vec3 transmitted = pow(clamp(albedo, vec3(.001), vec3(.95)), vec3(thickness));
        surface.dielectric = .0758;
        surface.front = vec3(.25);
        surface.back = min(vec3(1.0) - albedo * surface.front, transmitted * .7) / max(albedo, vec3(.001));
    }
#endif
#if defined(modelSurfaceFlag) && defined(ambientTextureFlag)
    surface.occlusion = surface.specularOcclusion = texture(u_ambientTexture, v_modelUV + modelShift).r;
#endif
    surface.emission = emission;
    return shadeSurface(surface);
}
#endif
