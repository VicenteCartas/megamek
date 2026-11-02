// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Sandstorm density, wind-driven grains and dust shading, composed through the shared ground layer.
uniform vec4 u_sand; // Strength, height, sand baseline, inverse terrain-level height.
uniform vec4 u_sandWind; // Unit direction, integrated fine-grain travel, inverse hex width.
uniform vec2 u_sandOffset;
uniform vec3 u_sandLight; // The ground light times the dust albedo (BoardAtmosphere.SAND_DUST).
uniform float u_sandMaxOpacity;

vec4 sandLayer(float depth) {
    if (u_sand.x <= 0.0 || outsideGroundLayer()) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 origin = world(0.0), surface = world(depth);
    vec3 direction = normalize(world(1.0) - origin);
    // The plinth follows the actual terrain minimum, even when sand starts above negative levels.
    if (groundBaseSide(surface, depth, u_boundsMin.z)) return vec4(0.0, 0.0, 0.0, 1.0);
    vec2 segment = groundSegment(origin, surface, direction);
    // Sublevels below a raised baseline are sheltered. Clip the air column above that plane;
    // negating world Z lets the shared belowHeight helper clip its opposite half-space.
    if (u_sand.z > u_boundsMin.z) segment = belowHeight(segment, -origin, -direction, -u_sand.z);
    if (segment.y <= segment.x) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 first = origin + direction * segment.x;
    vec3 last = origin + direction * segment.y;
    float z0 = max(0.0, first.z - u_sand.z), z1 = max(0.0, last.z - u_sand.z);
    float nearDensity = exp(-z0 / u_sand.y), farDensity = exp(-z1 / u_sand.y);
    float difference = farDensity - nearDensity;
    float integral = heightIntegral(first.z - u_sand.z, last.z - u_sand.z, segment.y - segment.x, u_sand.y);
    // Sample the density-weighted centre of the actual air column, rather than projecting noise onto the ground.
    float meanHeight = abs(difference) > 0.00001
          ? ((z1 + u_sand.y) * farDensity - (z0 + u_sand.y) * nearDensity) / difference : (z0 + z1) * 0.5;
    float distance = abs(direction.z) < 0.000001 ? (segment.x + segment.y) * 0.5
          : clamp((u_sand.z + clamp(meanHeight, min(z0, z1), max(z0, z1)) - origin.z) / direction.z,
                segment.x, segment.y);
    vec3 position = origin + direction * distance;
    float altitude = max(0.0, position.z - u_sand.z);
    vec3 field = vec3(position.xy * (u_sandWind.w * 0.5) - u_sandOffset, altitude / u_sand.y);
    float gust = smoothstep(0.2, 0.8, groundNoise(field));
    vec2 crosswind = vec2(-u_sandWind.y, u_sandWind.x);
    vec3 grains = vec3(dot(position.xy, u_sandWind.xy) * u_sandWind.w * 24.0 - u_sandWind.z,
          dot(position.xy, crosswind) * u_sandWind.w * 72.0, altitude * u_sandWind.w * 32.0);
    // Filter with the actual 3D footprint. Mips of the packed Z-slice atlas would create seams.
    float footprint = max(length(dFdx(grains)), length(dFdy(grains)));
    float grain = mix(groundNoise(grains), 0.5, smoothstep(0.5, 1.5, footprint));
    float density = (0.25 + 0.75 * gust) * (0.55 + 0.7 * grain);
    density *= 1.0 - smoothstep(u_sand.y * 1.5, u_sand.y * 3.0, altitude);
    density *= groundEdge(position, 1.0 / u_sandWind.w);
    float opacity = min(u_sandMaxOpacity, 1.0 - exp(-u_sand.x * integral * u_sand.w * density * 0.12));
    // Darker, redder and lighter, paler grains around the mean albedo.
    vec3 dust = mix(vec3(0.807, 0.763, 0.706), vec3(1.193, 1.237, 1.294), grain) * u_sandLight;
    return vec4(dust * opacity, 1.0 - opacity);
}
