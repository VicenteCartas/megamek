// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Finite emitters: lava, burning hexes and fungi of the installed board, and each frame's fireballs and weapon fire.
// They approximate area illumination, not obstacle shadows. GpuLavaLighting bins them on the board plane; a pixel
// evaluates only the emitters listed for its grid cell. Beams and flamer streams are segments, the rest points.
// LAVA_LIGHTS
uniform vec3 u_lavaGrid;        // world XY of the grid's corner, cells per world unit
uniform ivec2 u_lavaCells;      // grid columns and rows; zero without emitters

// An emitter's strength at a receiver distanceSquared from its centre or axis, in units of its size: it ends at two
// sizes, and falls off over the squared distance to its emitting surface.
float lavaFalloff(float power, float distanceSquared, float squared) {
    float reach = max(0.0, 1.0 - distanceSquared * .25);
    return power * reach * reach / (1.0 + squared * 9.0);
}

// The squared distance from a receiver distanceSquared from a segment's axis to the surface of its tube.
float lavaTube(float distanceSquared, float tube) {
    float gap = max(0.0, sqrt(distanceSquared) - tube);
    return gap * gap;
}

// The local lights' tone response: it keeps the existing lava response, while allowing warm fungal gills and cyan
// mycelium to mix naturally.
float lavaResponse(float energy) { return (1.0 - exp(-energy)) / max(energy, .00001); }

// One emitter of a cell's list as the pixel sees it, in units of the emitter's size: the way to its nearest point (a
// point's broad emitting disk, a segment's axis), the squared distance it falls off over, its strength and direction
// there. A segment also keeps its whole power, the way to its start, its run to the far end and its tube radius.
struct LavaSource {
    vec3 color;
    bool segment;
    float power;
    vec3 start;
    vec3 along;
    float tube;
    vec3 toLight;
    float squared;
    float strength;
    vec3 direction;
};

// The emitter's GGX highlight on a facet. Its angular size widens the lobe (Karis 2013), so a broad flame never
// glints as a point. A segment glints from its point nearest the ray mirrored about the facet, with its whole power:
// its tube widens the lobe across it, and its length spreads one point's highlight along it, normalized as Karis's
// tube lights are, down to the facet's own lobe once the segment is long or near.
vec3 lavaGlint(LavaSource source, vec3 facet, float roughness, vec3 view, vec3 f0) {
    if (!source.segment) {
        float alpha = min(1.0, roughness * roughness + .21 * inversesqrt(max(source.squared, .0001)));
        return source.color * source.strength * pointReflectance(facet, source.direction, view, sqrt(alpha), f0);
    }
    vec3 ray = reflect(-view, facet);
    float crossing = dot(ray, source.along), length2 = dot(source.along, source.along);
    float t = (dot(ray, source.start) * crossing - dot(source.start, source.along))
          / max(length2 - crossing * crossing, 1e-6 * length2 + 1e-12);
    vec3 toLight = source.start + source.along * clamp(t, 0.0, 1.0);
    float distanceSquared = dot(toLight, toLight), squared = lavaTube(distanceSquared, source.tube);
    float nearness = inversesqrt(max(squared, .0001));
    float alpha = min(1.0, roughness * roughness + .5 * source.tube * nearness);
    float strength = lavaFalloff(source.power, distanceSquared, squared) * alpha
          / clamp(alpha + .25 * sqrt(length2) * nearness, 1e-4, 1.0);
    return source.color * strength
          * pointReflectance(facet, toLight * inversesqrt(max(distanceSquared, .00001)), view, sqrt(alpha), f0);
}

// One walk over the cell's emitters for the pixel at position. Returns their diffuse irradiance on a surface along
// normal; specular receives their highlight on a glossy facet, compressed by the light the facet faces as the diffuse
// light is. With facets 0 the facet is normal. Water scatters by its level surface and mirrors the lights in its waves
// in the same walk: facets 1 takes the highlight on facet, and facets 2 adds specular2 on facet2 (a fall's sheet beside
// its pool's lip).
vec3 lavaLighting(vec3 position, vec3 normal, vec3 view, vec3 f0, int facets, vec3 facet, float roughness,
      out vec3 specular, vec3 facet2, float roughness2, out vec3 specular2) {
    specular = vec3(0.0);
    specular2 = vec3(0.0);
    ivec2 cell = ivec2(floor((position.xy - u_lavaGrid.xy) * u_lavaGrid.z));
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, u_lavaCells))) return vec3(0.0);
    vec2 list = texelFetch(u_lavaLights, lavaTexel(cell.y * u_lavaCells.x + cell.x), 0).xy;
    int first = int(list.x), end = int(list.y);
    if (first == end) return vec3(0.0);
    if (facets == 0) facet = normal;
    // Rough dry dielectrics show no highlight worth a second lobe per light.
    bool metal = max(f0.r, max(f0.g, f0.b)) > .1;
    bool glossy = roughness < .75 || metal, glossy2 = facets == 2 && (roughness2 < .75 || metal);
    float energy = 0.0, energy1 = 0.0, energy2 = 0.0;
    vec3 illumination = vec3(0.0);
    for (int at = first; at < end; at += 2) {
        ivec2 texel = lavaTexel(at);
        vec4 light = texelFetch(u_lavaLights, texel, 0);
        vec4 color = texelFetch(u_lavaLights, texel + ivec2(1, 0), 0);
        float width = max(color.w, .001);
        LavaSource source = LavaSource(color.rgb, light.w < 0.0, light.w, vec3(0.0), vec3(0.0), 0.0,
              (light.xyz - position) / width, 0.0, 0.0, vec3(0.0));
        float distanceSquared, power = light.w;
        if (source.segment) {
            vec4 far = texelFetch(u_lavaLights, lavaTexel(at + 2), 0);
            at += 2;
            source.power = -light.w;
            source.start = source.toLight;
            source.along = (far.xyz - light.xyz) / width;
            source.tube = far.w;
            float length2 = dot(source.along, source.along);
            source.toLight += source.along * clamp(-dot(source.start, source.along) / max(length2, 1e-12), 0.0, 1.0);
            distanceSquared = dot(source.toLight, source.toLight);
            if (distanceSquared > 4.0) continue;
            source.squared = lavaTube(distanceSquared, source.tube);
            source.direction = source.toLight * inversesqrt(max(distanceSquared, .00001));
            // Lit from its nearest point, a segment shares its light along its length. Across a line the falloff
            // gathers .45-.75 of what it gathers over the plane round a point, per size, more round a thicker tube;
            // with .7 a flamer stream lights in all about as one point of its power, a thin laser beam two thirds.
            power = source.power / (1.0 + .7 * sqrt(length2));
        } else {
            distanceSquared = dot(source.toLight, source.toLight);
            if (distanceSquared > 4.0) continue;
            // Closest point on a broad emitting disk; the small source lift gives low banks a warm grazing wash.
            float horizontal = length(source.toLight.xy);
            source.toLight.xy *= max(0.0, 1.0 - .42 / max(horizontal, .001));
            source.squared = dot(source.toLight, source.toLight);
            source.direction = source.toLight * inversesqrt(max(source.squared, .00001));
        }
        source.strength = lavaFalloff(power, distanceSquared, source.squared);
        float facing = max(0.0, dot(normal, source.direction) * .92 + .08);
        float contribution = source.strength * facing;
        energy += contribution;
        illumination += color.rgb * contribution;
        if (facets > 0) energy1 += source.strength * max(0.0, dot(facet, source.direction) * .92 + .08);
        if (facets > 1) energy2 += source.strength * max(0.0, dot(facet2, source.direction) * .92 + .08);
        if (glossy) specular += lavaGlint(source, facet, roughness, view, f0);
        if (glossy2) specular2 += lavaGlint(source, facet2, roughness2, view, f0);
    }
    specular *= lavaResponse(facets == 0 ? energy : energy1);
    specular2 *= lavaResponse(energy2);
    return illumination * lavaResponse(energy);
}

vec3 lavaIrradiance(vec3 position, vec3 normal, vec3 view, float roughness, vec3 f0, out vec3 specular) {
    vec3 none;
    return lavaLighting(position, normal, view, f0, 0, normal, roughness, specular, normal, 1.0, none);
}

vec3 lavaIrradiance(vec3 position, vec3 normal) {
    vec3 specular;
    return lavaIrradiance(position, normal, normal, 1.0, vec3(0.0), specular);
}
