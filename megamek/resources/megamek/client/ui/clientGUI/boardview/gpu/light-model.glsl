// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One light model for every lit surface. Material reflectance and BoardAtmosphere light arrive linear;
// light is already adapted (clear noon gives white level ground 1). Only display output encodes scene radiance.
// COLOR_SPACE

// Reflectance of the ground below surfaces that do not know their terrain family.
const vec3 GROUND_ALBEDO = vec3(0.2);

// Light on a face whose normal has this up component: the sky from above, the lit ground's reflection from below.
vec3 hemisphere(vec3 sky, vec3 sunOnGround, vec3 groundAlbedo, float up) {
    return mix(groundAlbedo * (sky + sunOnGround), sky, up * 0.5 + 0.5);
}

const float PI = 3.141592653589793;
const float MIN_ROUGHNESS = .045;

// Material styling can brighten an authored pigment. Keep its RGB ratios while bounding reflectance;
// this applies before lighting, never to HDR radiance or emission.
vec3 materialReflectance(vec3 color) {
    color = max(color, vec3(0.0));
    return color / max(1.0, max(color.r, max(color.g, color.b)));
}

// The caller supplies unit vectors and a visible, oriented material face.
// For real opaque geometry, face is the actual triangle normal, obtained before divergent work. A billboard's
// triangle is not a substitute for the virtual plant surface represented by its pixels.
vec3 shadingNormal(vec3 normal, vec3 face, vec3 view) {
    float nv = dot(normal, view), ng = dot(normal, face), gv = dot(face, view);
    if (gv <= 0.0 || (nv > 0.0 && 2.0 * nv * ng >= gv)) return normal;
    // Rotate only when the mirror ray enters the material's opposite side. The closest boundary in the
    // normal/face plane satisfies Ix*sin(2*theta) + Iz*cos(2*theta) = 0. Its half-angle form needs no iteration.
    vec3 across = cross(face, cross(normal, face));
    float length2 = dot(across, across);
    // An exactly inverted input has no preferred tangent axis. Authored front-side normals should not reach it.
    if (length2 == 0.0) return face;
    across *= inversesqrt(length2);
    float ix = dot(across, view);
    float projectedView = sqrt(ix * ix + gv * gv);
    float large = sqrt(.5 * (1.0 + abs(ix) / projectedView));
    // Derive the small component from sin(2*theta), not 1-abs(Ix)/length: the latter loses it near the horizon.
    float small = gv / (2.0 * projectedView * large);
    return ix >= 0.0 ? large * across + small * face : small * across + large * face;
}

// Cross the world-position derivatives before any coverage discard or varying material branch. Raster orientation
// supplies the visible side even for mirrored/two-sided instances; it does not follow the mapped lighting normal.
vec3 visibleFace(vec3 dx, vec3 dy, vec3 view) {
    vec3 face = normalize(cross(dx, dy));
    return dot(face, view) < 0.0 ? -face : face;
}

// Geometric specular antialiasing (Kaplanyan et al. 2016; Tokuyoshi and Kaplanyan 2019). A highlight narrower than
// the normal's spread over a pixel sparkles; widening GGX alpha^2 by that spread keeps it stable. The setting compiles
// it in (specularAntialiasingFlag, GpuEnvironmentLighting.Antialiasing). Without it the helpers below are constants,
// so the compiler drops every variance computation.
// The program's normal variance, once per pixel from the screen derivatives of its final shading normal (mapped
// detail included where derivatives allow). dielectricRoughness and the other lit surfaces read it after shadingNormal.
float surfaceVariance = 0.0;
#ifdef specularAntialiasingFlag
const float SPECULAR_ANTIALIASING_KAPPA = .18;

// The variance of a normal across this pixel, from its screen derivatives and the pixel filter's variance 1/(2 pi)
// (Tokuyoshi and Kaplanyan 2019). Take dFdx/dFdy in uniform control flow before any discard, as for visibleFace:
// derivatives are undefined across a discarded neighbour.
float normalVariance(vec3 dx, vec3 dy) {
    return 0.15915494 * (dot(dx, dx) + dot(dy, dy));
}

// Perceptual roughness whose GGX alpha^2 (alpha = roughness^2) widens by the variance, bounded by KAPPA.
float antialiasedRoughness(float roughness, float variance) {
    float alpha = roughness * roughness;
    return sqrt(sqrt(clamp(alpha * alpha + min(2.0 * variance, SPECULAR_ANTIALIASING_KAPPA), 0.0, 1.0)));
}
#else
float normalVariance(vec3 dx, vec3 dy) { return 0.0; }
float antialiasedRoughness(float roughness, float variance) { return roughness; }
#endif

// Correlated Smith visibility for GGX, with alpha = perceptual roughness squared.
float ggxVisibility(float nv, float nl, float a2) {
    return .5 / max(1e-7, nl * sqrt(nv * nv * (1.0 - a2) + a2)
          + nv * sqrt(nl * nl * (1.0 - a2) + a2));
}

// GGX direct reflection including NoL. Directional light colors are E/pi in the board's pre-exposed units,
// so the physical BRDF is multiplied by pi here; Lambert's matching pi cancels its 1/pi.
vec3 pointReflectance(vec3 normal, vec3 light, vec3 view, float roughness, vec3 f0) {
    float incidence = max(0.0, dot(normal, light));
    if (incidence <= 0.0 || dot(normal, view) <= 0.0) return vec3(0.0);
    // Both directions are in the positive hemisphere, so their sum is nonzero even near opposite horizons.
    // An epsilon floor here would shorten H and invalidate the unit-vector identity used by GGX below.
    vec3 halfVector = normalize(light + view);
    float nv = dot(normal, view), nh = max(0.0, dot(normal, halfVector));
    float r = clamp(roughness, MIN_ROUGHNESS, 1.0);
    float a2 = r * r * r * r;
    // |N x H|^2 = 1 - NoH^2 for unit vectors, without subtracting nearly equal values at a sharp highlight.
    // The cancellation form can give swapped light/view calls different lobes after float reassociation.
    vec3 across = cross(normal, halfVector);
    float denominator = dot(across, across) + a2 * nh * nh;
#ifdef normalQualityFlag
    // Karis 2015, mobile GGX: visibility and Fresnel folded into one term of LoH.
    float lh = max(dot(light, halfVector), .1);
    return f0 * a2 * incidence / max(4.0 * denominator * denominator * lh * lh * (r + .5), 1e-6);
#endif
    float distribution = a2 / max(PI * denominator * denominator, 1e-12);
    float visibility = ggxVisibility(nv, incidence, a2);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - clamp(dot(view, halfVector), 0.0, 1.0), 5.0);
    return PI * distribution * visibility * fresnel * incidence;
}

#ifdef environmentLightingFlag
uniform float u_directionalRadiusSine;
vec3 directionalNormalization(float nv, float roughness, vec3 f0);
#endif

// A finite sun/moon spreads a sharp highlight instead of sampling a delta light at its unbounded point peak.
// Approximate the cap convolution with a normalized anisotropic GGX lobe: at mirror alignment the cap projects
// to half-vector axes sin(radius)/2 and sin(radius)/(2*NoV). The bounded angular projection remains valid as the
// tangent-plane approximation approaches the horizon. This is a single-lobe approximation, not disk quadrature.
// Matching Smith visibility and preintegrated energy normalization keep the material's diffuse budget intact.
vec3 surfaceReflectance(vec3 normal, vec3 light, vec3 view, float roughness, vec3 f0) {
#ifdef normalQualityFlag
    return pointReflectance(normal, light, view, roughness, f0);
#elif defined(environmentLightingFlag)
    float nv = dot(normal, view), nl = dot(normal, light);
    if (nv <= 0.0 || nl <= 0.0) return vec3(0.0);
    if (u_directionalRadiusSine <= 0.0) return pointReflectance(normal, light, view, roughness, f0);
    float r = clamp(roughness, MIN_ROUGHNESS, 1.0), a2 = r * r * r * r;
    float radius2 = .25 * u_directionalRadiusSine * u_directionalRadiusSine;
    vec2 axes2 = a2 + vec2(radius2, radius2 / (nv * nv + radius2));
    vec3 tangent = view - normal * nv;
    float tangentLength2 = dot(tangent, tangent);
    // At the normal, both axes coincide; any tangent frame has the same response.
    tangent = tangentLength2 > 1e-10 ? tangent * inversesqrt(tangentLength2)
          : normalize(cross(normal, abs(normal.z) < .9 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0)));
    vec3 bitangent = cross(normal, tangent), halfVector = normalize(light + view);
    vec2 halfPlane = vec2(dot(halfVector, tangent), dot(halfVector, bitangent));
    vec2 lightPlane = vec2(dot(light, tangent), dot(light, bitangent));
    float nh = max(0.0, dot(normal, halfVector));
    float denominator = dot(halfPlane * halfPlane, 1.0 / axes2) + nh * nh;
    float distribution = 1.0 / (PI * sqrt(axes2.x * axes2.y) * denominator * denominator);
    float visibility = .5 / max(1e-12, nl * sqrt(nv * nv + axes2.x * tangentLength2)
          + nv * sqrt(nl * nl + dot(axes2, lightPlane * lightPlane)));
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - clamp(dot(view, halfVector), 0.0, 1.0), 5.0);
    return PI * distribution * visibility * fresnel * nl * directionalNormalization(nv, r, f0);
#else
    return pointReflectance(normal, light, view, roughness, f0);
#endif
}
