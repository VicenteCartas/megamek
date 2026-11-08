#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Camera-facing puffs, droplets and mist launched by the dedicated spray vertex path.
// water-uniforms
// water-lighting-functions

void main() {
    vec4 mixture = waterPalette(u_waterMaterial.x);
    vec3 froth = waterFroth(mixture, waterTint(mixture));
    WaterLighting illumination = waterLighting();
    float effects = u_waterEffects, detail = u_rainDetail * effects;
    vec3 color;
    float alpha;
    // Spray where a fall lands (GpuWaterfall): dense white puffs bursting up, bright droplets flung out in arcs
    // and mist billowing away, each particle a camera-facing quad that the vertex shader launched. Its colour
    // carries age, size seed, whether it is in the air and its kind.
    float age = v_color.r;
    float mist = step(0.75, v_color.a), droplet = step(0.25, v_color.a) - mist;
    vec2 corner = v_diffuseUV * 2.0 - 1.0;
    float ragged = texture(u_surfaceNoise, vec3(v_diffuseUV * mix(0.45, 0.3, mist) + v_color.g * 7.31 + age * 0.15, WATER_DETAIL)).b;
    float grain = texture(u_surfaceNoise, vec3(v_diffuseUV * 1.3 + v_color.g * 3.7 - age * 0.3, WATER_DETAIL)).b;
    // Puffs and mist are ragged and soft; a droplet is a small, crisp streak along its flight.
    float soft = 1.0 - smoothstep(mix(0.15, 0.0, mist), 1.0, length(corner) + (ragged - 0.5) * 0.9);
    float bead = (1.0 - smoothstep(0.1, 1.0, length(corner))) * (0.45 + 0.55 * smoothstep(-1.0, 0.6, corner.y));
    float shape = mix(soft, bead, droplet);
    // Puffs burst out dense and thin as they scatter; droplets stay bright until they drop back; mist swells and
    // fades away.
    float fade = smoothstep(0.0, 0.06, age) * (1.0 - smoothstep(mix(mix(0.55, 0.8, droplet), 0.3, mist), 1.0, age))
          * v_color.b;
    float density = mix(mix(0.95 * mix(0.65, 1.0, grain), 0.9, droplet), 0.3, mist);
    alpha = shape * fade * density * mix(0.6, 1.0, detail) * effects;
    // Spray scatters sunlight forward: it glows when seen against the sun.
    vec3 mistLight = illumination.whiteLight * 1.15;
#if defined(lightingFlag) && numDirectionalLights > 0
    float against = max(dot(normalize(viewDirection()), -u_dirLights[0].direction), 0.0);
    mistLight += illumination.sunlight * (against * against * 0.5);
#endif
    color = froth * mistLight * alpha * mix(mix(1.1, 1.25, droplet), 1.0, mist);
    waterOutput(vec4(color, alpha));
}
