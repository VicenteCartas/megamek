#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform vec4 u_centerRadius;
// Normalized attack age, fire amount, optical density and stable event/packet seed.
uniform vec4 u_effect;
// LIGHT_MODEL
uniform vec3 u_light;
uniform vec3 u_sun;
// Unit travel direction in XY and the board's normalized strength in Z.
uniform vec3 u_wind;
uniform float u_stretch;
// Unit direction of a thrown spike in XYZ and how far the volume is drawn out along it in W (0 keeps it round).
uniform vec4 u_axis;
// Palette beyond the plain fireball: soot darkens the smoke (below zero it pales toward dust that scatters the light),
// ember keeps fire glowing inside it for longer and dust browns it like thrown earth. All zero is the plain fireball.
uniform vec4 u_look;
uniform vec4 u_viewport;
uniform mat4 u_inverseProjView;
uniform sampler2D u_depth;

float hash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float turbulence(vec3 p) {
    return noise(p) * 0.57 + noise(p * 2.03 + 13.7) * 0.29 + noise(p * 4.11 + 31.3) * 0.14;
}

// Density and local fuel share the same advected field, including for the short light rays.
vec2 medium(vec3 p) {
    float age = u_effect.x;
    float radius = length(p);
    if (radius >= 1.0) return vec2(0.0);
    // A violent (fuel-air) blast churns in small cells at first that grow into large billows as it ages.
    float frequency = 3.2 * mix(1.0, mix(1.9, 0.7, age), u_look.w);
    vec3 flow = p * frequency + vec3(u_effect.w, u_effect.w * 0.7, -age * 2.2);
    // Upward advection and radial roll break up the silhouette without crawling over the proxy's triangles.
    flow.xy += vec2(sin(p.z * 3.0 + age * 4.0), cos(p.z * 3.0 - age * 3.0)) * 0.45;
    flow.z += sin(length(p.xy) * 5.0 - age * 4.0) * 0.4;
    flow.xy -= u_wind.xy * u_wind.z * age * 1.5;
    float billow = noise(flow);
    float detail = turbulence(flow * 2.1 + billow * 1.7);
    vec3 shape = p;
    // A thrown spike narrows toward its leading tip, so a crown of them reads as jagged rather than as round lobes.
    float along = dot(p, u_axis.xyz);
    // Only a stretch tapers; a squashed (flattened) volume keeps its round edge.
    float taper = 1.6 * clamp(u_axis.w, 0.0, 1.0) * max(along, 0.0);
    shape += (p - u_axis.xyz * along) * taper;
    shape.xy *= 1.0 + age * (0.18 - p.z * 0.32);
    float edge = length(shape) + (0.5 - billow) * 0.5 + (0.5 - detail) * 0.18;
    float body = (1.0 - smoothstep(0.62, 0.88, edge)) * (1.0 - smoothstep(0.9, 1.0, radius));
    float density = body * mix(0.35, 1.25, smoothstep(0.25, 0.75, detail));
    float fuel = clamp(0.95 - radius * 0.65 + (detail - 0.5) * 1.8 + (billow - 0.5) * 0.5, 0.0, 1.0);
    return vec2(density, fuel);
}

// Inverse of the proxy's downwind stretch: ray, opaque depth and lighting stay in the same volume space.
vec3 volumeVector(vec3 v) {
    v.xy -= u_wind.xy * dot(v.xy, u_wind.xy) * (u_stretch / (1.0 + u_stretch));
    v -= u_axis.xyz * dot(v, u_axis.xyz) * (u_axis.w / (1.0 + u_axis.w));
    return v;
}

void main() {
    vec2 uv = (gl_FragCoord.xy - u_viewport.xy) / u_viewport.zw;
    float depth = texture(u_depth, uv).r;
    vec4 nearPoint = u_inverseProjView * vec4(uv * 2.0 - 1.0, -1.0, 1.0);
    vec4 surface = u_inverseProjView * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 nearWorld = nearPoint.xyz / nearPoint.w;
    vec3 worldRay = surface.xyz / surface.w - nearWorld;
    vec3 ray = volumeVector(worldRay);
    float sceneDistance = length(ray) / u_centerRadius.w;
    if (sceneDistance <= 0.00001) discard;
    float opticalScale = length(worldRay) / length(ray);
    // Reconstruct both camera types from the actual viewport, starting at the near clipping plane.
    vec3 direction = normalize(ray);
    vec3 origin = volumeVector(nearWorld - u_centerRadius.xyz) / u_centerRadius.w;
    float b = dot(origin, direction);
    float discriminant = b * b - dot(origin, origin) + 1.0;
    if (discriminant <= 0.0) discard;
    float root = sqrt(discriminant);
    float nearT = max(0.0, -b - root), farT = min(-b + root, sceneDistance);
    if (farT <= nearT) discard;

    const int STEPS = 32;
    float stepSize = (farT - nearT) / float(STEPS);
    float jitter = mix(0.25, 0.75, hash(vec3(gl_FragCoord.xy, u_effect.w)));
    float ember = u_look.y * 0.28;
    float cooling = u_effect.y * (1.0 - smoothstep(0.12 + ember, 0.68 + ember, u_effect.x));
    float soot = max(u_look.x, 0.0), pale = max(-u_look.x, 0.0);
    vec3 litSmoke = mix(mix(mix(toLinear(vec3(0.44, 0.43, 0.41)), toLinear(vec3(0.17, 0.16, 0.155)), soot),
                            toLinear(vec3(0.78, 0.76, 0.72)), pale), toLinear(vec3(0.4, 0.32, 0.23)), u_look.z);
    // Dust lights its own shade: light scattered inside the cloud keeps it pale where smoke goes dark.
    vec3 shadedSmoke = mix(mix(mix(toLinear(vec3(0.065, 0.07, 0.08)), toLinear(vec3(0.025, 0.024, 0.025)), soot),
                               toLinear(vec3(0.42, 0.4, 0.37)), pale), toLinear(vec3(0.08, 0.06, 0.045)), u_look.z);
    vec3 sun = normalize(volumeVector(u_sun));
    float transmission = 1.0;
    vec3 radiance = vec3(0.0);
    for (int i = 0; i < STEPS; i++) {
        vec3 p = origin + direction * (nearT + (float(i) + jitter) * stepSize);
        vec2 mediumSample = medium(p);
        // Dissipation reduces optical density, exposing gaps between billows before they vanish.
        float absorption = 1.0 - exp(-mediumSample.x * stepSize * opticalScale * 6.0 * u_effect.z);
        if (absorption > 0.0001) {
            // Two short light samples provide local self-shadowing without a second long ray march.
            float shadow = exp(-(medium(p + sun * 0.18).x + medium(p + sun * 0.4).x) * 1.7 * u_effect.z);
            vec3 smoke = mix(shadedSmoke, litSmoke, shadow) * u_light;
            float heat = cooling * mediumSample.y;
            // Decode palette stops, then apply linear emission strength independently of their colour.
            vec3 fire = mix(toLinear(vec3(0.7, 0.018, 0.001)),
                  1.55 * toLinear(vec3(1.0, 0.38 / 1.55, 0.012 / 1.55)), smoothstep(0.08, 0.5, heat));
            fire = mix(fire, 2.1 * toLinear(vec3(1.0, 1.65 / 2.1, 0.7 / 2.1)), smoothstep(0.5, 0.95, heat));
            // Burning metal in a violent blast: small blinding pockets flicker through its fire.
            float pocket = smoothstep(0.78, 0.86, noise(p * 13.0 + vec3(u_effect.w * 3.1, 0.0, -u_effect.x * 9.0)));
            fire += 2.6 * toLinear(vec3(1.0, 2.3 / 2.6, 1.7 / 2.6)) * pocket * u_look.w * smoothstep(0.2, 0.6, heat);
            smoke += toLinear(vec3(0.22, 0.045, 0.006)) * cooling * mediumSample.y;
            vec3 source = mix(smoke, fire, smoothstep(0.08, 0.5, heat));
            radiance += transmission * absorption * source;
            transmission *= 1.0 - absorption;
        }
        if (transmission < 0.02) break;
    }
    // Premultiplied output preserves both dark smoke absorption and emissive fire.
    fragColor = vec4(radiance, 1.0 - transmission);
}
