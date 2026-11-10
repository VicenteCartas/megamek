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
// A dust cloud's surge front, surge thickness, column radius and column height, in radii; zero for a round volume.
uniform vec4 u_cloud;
// How many times finer than a tenth of its radius a dust cloud's billows are: those of a wide cloud are no larger.
uniform float u_grain;
uniform vec4 u_viewport;
uniform mat4 u_inverseProjView;
uniform sampler2D u_depth;
uniform sampler2D u_noise;

// The periodic, two-channel 3D lookup the board's clouds and hex fires use: one filtered fetch per octave.
float noise(vec3 p) {
    vec3 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec2 uv = cell.xy + vec2(37.0, 17.0) * cell.z + f.xy;
    vec2 pair = textureLod(u_noise, (uv + 0.5) / 256.0, 0.0).rg;
    return mix(pair.x, pair.y, f.z);
}

float turbulence(vec3 p) {
    return noise(p) * 0.57 + noise(p * 2.03 + 13.7) * 0.29 + noise(p * 4.11 + 31.3) * 0.14;
}

// A structure's dust, standing on the ground in a flattened volume: a surge along the ground, thickest at its rolling
// front, whose reach differs by bearing, joined to a dome over the wreck. Out of both stand bulbs as tall as they are
// wide, with creases between them, and smaller ones on those: they are sampled in the volume's radii, not its squashed
// space, so they are round, grow as the cloud spreads and climb as it ages. Returns the density, how open to the sky
// the place is (out of a crease, up the cloud), and how far around it, in radii, is certainly empty. Without fine,
// the bulbs alone, of a round front: the short light rays' density.
vec3 cloud(vec3 p, bool fine) {
    vec3 at = vec3(p.xy, p.z * (1.0 + u_axis.w));
    float reach = length(at.xy);
    // How far outside the dome and the surge, in radii.
    float outside = (length(vec3(at.xy / u_cloud.z, at.z / u_cloud.w)) - 1.0) * min(u_cloud.z, u_cloud.w);
    if (u_cloud.x > 0.0 && reach < u_cloud.x * 1.22 + 0.15 && abs(at.z) < u_cloud.y + 0.15) {
        float front = u_cloud.x * (fine
              ? 0.72 + 0.5 * noise(vec3(at.xy / max(reach, 0.001) * 1.3 + u_effect.w, u_effect.w)) : 0.97);
        float thickness = u_cloud.y * mix(0.5, 1.0, smoothstep(0.3, 0.8, reach / front));
        outside = min(outside, length(vec2(max(reach - front + thickness, 0.0), at.z)) - thickness);
    }
    // Further out than the bulbs stand out of the shape: empty, without their fetches.
    outside *= u_grain;
    if (outside >= 0.149) return vec3(0.0, 0.0, (outside - 0.149) / u_grain);
    vec3 drift = vec3(u_effect.w, u_effect.w * 0.7, -u_effect.x);
    vec3 flow = at * u_grain;
    // Round tops, sharp creases.
    float bulb = 1.0 - abs(2.0 * noise(flow * 3.5 + drift) - 1.0);
    bulb = 1.0 - bulb * bulb;
    outside += 0.03 - bulb * 0.13;
    if (outside >= 0.049) return vec3(0.0);
    float small = abs(2.0 * noise(flow * 8.0 + drift * 2.3) - 1.0);
    outside -= (small - 0.4) * 0.06;
    if (!fine) return vec3(1.0 - smoothstep(-0.05, 0.0, outside), 0.0, 0.0);
    float tiny = abs(2.0 * noise(flow * 19.0 + drift * 4.0 + bulb * 1.7) - 1.0);
    // Crisp while it is young, soft as it thins.
    float body = 1.0 - smoothstep(-mix(0.008, 0.08, u_effect.x), 0.0, outside - (tiny - 0.4) * 0.025);
    float open = mix(0.78, 1.0, bulb) * mix(0.86, 1.0, small) * mix(0.92, 1.0, tiny)
          * mix(0.88, 1.0, clamp(at.z / max(u_cloud.y, u_cloud.w), 0.0, 1.0));
    return vec3(body, open, 0.0);
}

// Density and local fuel share the same advected field. Without fine, the billows alone: the short light
// rays' density, one fetch a sample.
vec2 medium(vec3 p, bool fine) {
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
    vec3 shape = p;
    // A thrown spike narrows toward its leading tip, so a crown of them reads as jagged rather than as round lobes.
    float along = dot(p, u_axis.xyz);
    // Only a stretch tapers; a squashed (flattened) volume keeps its round edge.
    float taper = 1.6 * clamp(u_axis.w, 0.0, 1.0) * max(along, 0.0);
    shape += (p - u_axis.xyz * along) * taper;
    shape.xy *= 1.0 + age * (0.18 - p.z * 0.32);
    float outline = length(shape) + (0.5 - billow) * 0.5;
    // Outside whatever the detail could still add: empty, for three fetches less.
    if (outline >= 0.97) return vec2(0.0);
    float detail = fine ? turbulence(flow * 2.1 + billow * 1.7) : 0.5;
    float edge = outline + (0.5 - detail) * 0.18;
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

    float jitter = mix(0.25, 0.75, fract(52.9829189 * fract(dot(gl_FragCoord.xy + u_effect.w, vec2(0.06711056, 0.00583715)))));
    float ember = u_look.y * 0.28;
    float cooling = u_effect.y * (1.0 - smoothstep(0.12 + ember, 0.68 + ember, u_effect.x));
    float soot = max(u_look.x, 0.0), pale = max(-u_look.x, 0.0);
    vec3 litSmoke = mix(mix(mix(toLinear(vec3(0.44, 0.43, 0.41)), toLinear(vec3(0.17, 0.16, 0.155)), soot),
                            1.5 * toLinear(vec3(0.97, 0.93, 0.86)), pale), toLinear(vec3(0.4, 0.32, 0.23)), u_look.z);
    // Dust lights its own shade: light scattered inside the cloud keeps it pale where smoke goes dark. In the sun it
    // is brighter than the ground it rose from.
    vec3 shadedSmoke = mix(mix(mix(toLinear(vec3(0.065, 0.07, 0.08)), toLinear(vec3(0.025, 0.024, 0.025)), soot),
                               toLinear(vec3(0.76, 0.76, 0.8)), pale), toLinear(vec3(0.08, 0.06, 0.045)), u_look.z);
    float transmission = 1.0;
    vec3 radiance = vec3(0.0);
    if (u_cloud.z > 0.0) {
        // A dust cloud: strides through the air around it, fine steps where the ray meets it, so that its billows
        // keep their edges, and longer ones as it goes dark inside. Its light rays keep the sun's own bearing through
        // the flattened volume and are as short as its bulbs are deep.
        float fine = 0.01 / u_grain;
        vec3 sun = volumeVector(u_sun) * 0.6 / u_grain;
        // Radii covered per unit of the ray.
        float pace = length(vec3(direction.xy, direction.z * (1.0 + u_axis.w)));
        float t = nearT + jitter * fine / pace;
        for (int i = 0; i < 48; i++) {
            if (t >= farT || transmission < 0.02) break;
            vec3 p = origin + direction * t;
            vec3 dust = cloud(p, true);
            float stride = max(dust.z, fine * (dust.x > 0.0 ? 1.0 + 4.0 * (1.0 - transmission) : 2.0));
            if (dust.x > 0.0) {
                // No further than the ground or the piece of wreck the ray ends on.
                float span = min(stride, (farT - t) * pace);
                float absorption = 1.0 - exp(-dust.x * span * u_grain * 40.0 * u_effect.z);
                float shadow = exp(-(cloud(p + sun * 0.18, false).x + cloud(p + sun * 0.4, false).x) * 2.0 * u_effect.z);
                // Less of the sky's light reaches into its creases and under its bulk.
                radiance += transmission * absorption * mix(shadedSmoke, litSmoke, shadow) * u_light * dust.y;
                transmission *= 1.0 - absorption;
            }
            t += stride / pace;
        }
        fragColor = vec4(radiance, 1.0 - transmission);
        return;
    }
    // A step of about a tenth of the radius: a ray through the middle takes all of them, one through the rim few.
    const int STEPS = 20;
    int steps = clamp(int(ceil((farT - nearT) * 10.0)), 4, STEPS);
    float stepSize = (farT - nearT) / float(steps);
    vec3 sun = normalize(volumeVector(u_sun));
    for (int i = 0; i < STEPS; i++) {
        if (i >= steps) break;
        vec3 p = origin + direction * (nearT + (float(i) + jitter) * stepSize);
        vec2 mediumSample = medium(p, true);
        // Dissipation reduces optical density, exposing gaps between billows before they vanish.
        float absorption = 1.0 - exp(-mediumSample.x * stepSize * opticalScale * 6.0 * u_effect.z);
        if (absorption > 0.0001) {
            // Two short light samples provide local self-shadowing without a second long ray march.
            float shadow = exp(-(medium(p + sun * 0.18, false).x + medium(p + sun * 0.4, false).x) * mix(1.7, 1.0, pale) * u_effect.z);
            vec3 smoke = mix(shadedSmoke, litSmoke, shadow) * u_light;
            float heat = cooling * mediumSample.y;
            // Decode palette stops, then apply linear emission strength independently of their colour.
            vec3 fire = mix(toLinear(vec3(0.7, 0.018, 0.001)),
                  1.55 * toLinear(vec3(1.0, 0.38 / 1.55, 0.012 / 1.55)), smoothstep(0.08, 0.5, heat));
            fire = mix(fire, 2.1 * toLinear(vec3(1.0, 1.65 / 2.1, 0.7 / 2.1)), smoothstep(0.5, 0.95, heat));
            // Burning metal in a violent blast: small blinding pockets flicker through its fire. No other blast pays
            // for their noise.
            if (u_look.w > 0.0) {
                float pocket = smoothstep(0.78, 0.86, noise(p * 13.0 + vec3(u_effect.w * 3.1, 0.0, -u_effect.x * 9.0)));
                fire += 2.6 * toLinear(vec3(1.0, 2.3 / 2.6, 1.7 / 2.6)) * pocket * u_look.w * smoothstep(0.2, 0.6, heat);
            }
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
