// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One optical model for frozen lake slabs, land ice and detected black ice on pavement/roads/bridge decks. Every mode
// samples the same world-anchored floes, so a lake slab and the land ice beside it read as one sheet over other ground.
// After terrain-hexes.glsl: land ice meets dry ground along a jagged, shattered border that straddles their shared
// hex edge; the dry side draws its part of it.
#ifdef iceFlag
uniform sampler2D u_iceColor;
// Layer 0 normal; layer 1 R surface height, G roughness, B cracks and bubbles, A cloudy ice.
uniform sampler2DArray u_iceMaps;
// 1 land ice, 2 detected thin black ice, 3 floating lake slab; 4 dry ground beside land ice, 5 paved ground beside
// land or black ice, which draw their side of the shared border; 6 solid frozen waterfall curtains and icicles.
#ifdef ICE_MODE
// Whole draw-uniform paths disappear at compilation: slabs need no land borders/base shading, while black ice
// needs no floe/snow material. GpuTerrain keys shader reuse by this immutable material mode.
const float u_iceMode = ICE_MODE;
#else
uniform float u_iceMode;
#endif
uniform float u_iceMetre;
// GpuAssets.coverage of the sheet's maps: a wider texture repeats less often at the same texel density.
uniform float u_iceCoverage;
uniform float u_iceLevel;      // world units per level
uniform float u_iceWaterline;  // world units from a water hex's level down to its surface
uniform float u_iceNormals;
uniform vec2 u_iceBoard;       // the per-hex texture's size where any hex shows ice, else zero

// Linear albedos. Clear ice returns almost no light of its own over deep water; wind-packed snow and the grey
// refrozen slush in the joints between floes scatter it.
const vec3 ICE_DEEP = vec3(.012, .034, .048);
const vec3 ICE_BARE = vec3(.40, .50, .56);
const vec3 ICE_SNOW = vec3(.84, .88, .91);
const vec3 ICE_JOINT = vec3(.46, .54, .59);

// The normal map is baked for the unturned 24 m field. A transformed sample needs the inverse basis change and
// the matching gradient scale, including the anisotropic wind stretch. Offsets only change the sampled position.
vec3 iceMappedNormal(vec2 uv, mat2 transform, vec2 dx, vec2 dy) {
    vec3 normal = (textureGrad(u_iceMaps, vec3(uv, 0.0), dx, dy).xyz * 255.0 - 128.0) / 127.0;
    return normalize(vec3(transpose(transform) * normal.xy, normal.z));
}

// The shared 64² noise with smoothed cells, texels the given spacing in metres apart. The base level avoids mip
// selection jumps at the cell borders; the fields are broad enough not to alias.
vec3 iceNoise(vec2 metres, float spacing, vec2 offset) {
    vec2 texel = metres / spacing + offset, cell = floor(texel), f = fract(texel);
    return textureLod(u_rainNoise, (cell + f * f * (3.0 - 2.0 * f) + .5) / 64.0, 0.0).rgb;
}

// Floes on a jittered lattice, in cell units: x is the distance between the nearest two sites (twice the distance to
// their shared joint), y a stable value per floe, zw the offset from the point to its floe's site.
vec4 iceFloes(vec2 p) {
    vec2 cell = floor(p), f = fract(p), toSite = vec2(0.0);
    float first = 8.0, second = 8.0;
    ivec2 nearest = ivec2(0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 key = ivec2(cell) + ivec2(x, y);
            vec2 site = vec2(x, y) + .1 + .8 * vec2(biomeHash(key), biomeHash(key + ivec2(71, 13))) - f;
            float d = dot(site, site);
            if (d < first) { second = first; first = d; nearest = key; toSite = site; } else if (d < second) { second = d; }
        }
    }
    return vec4(sqrt(second) - sqrt(first), biomeHash(nearest + ivec2(29, 53)), toSite);
}

// The border of the iced area, from the per-hex texture, in metres: x is signed to the border shared with dry
// neighbours at this level, which draw their side of it (positive in the ice); y is to ground that cannot (water,
// another level); z is 1 where the nearest ice is thin black ice. Beneath the surface of the water hex a point lies
// in, ice has no footing: x is then far outside. A dry point measures to the ice it borders (fringe), which is
// frozen land, or black ice as well for paved ground (glazed); a frozen lake ends at its own shore.
vec3 iceBorder(vec2 world, float z, bool fringe, bool glazed) {
    if (u_iceBoard.x < 1.0) return vec3(1e4, 1e4, 0.0);
    const float width = 30.0, height = 30.0 * BOARD_HEX.y / BOARD_HEX.x;
    int column = int(floor(world.x / (width * .75)));
    float ownDistance = 1e4;
    ivec2 owner = ivec2(-1);
    // Find the support geometrically, then stream the neighbours instead of retaining four nine-element arrays.
    for (int i = 0; i < 9; i++) {
        int x = column + i / 3 - 1;
        int y = int(floor(-world.y / height - mod(float(x), 2.0) * .5)) + i % 3 - 1;
        if (x < 0 || y < 0 || float(x) >= u_iceBoard.x || float(y) >= u_iceBoard.y) continue;
        float distance = biomeHexDistance(world - boardHexCenter(x, y));
        if (distance < ownDistance) { ownDistance = distance; owner = ivec2(x, y); }
    }
    vec4 support = owner.x >= 0 ? texelFetch(u_biomeHexes, owner, 0) : vec4(0.0);
    float own = owner.x >= 0 ? support.a * 255.0 - 64.0 : 1e4;
    bool ownWet = support.g > .002 || int(support.b * 255.0 + .5) == 2;
    if (ownWet && z < own * u_iceLevel - u_iceWaterline) return vec3(-1e4, -1e4, 0.0);
    float ice = 1e4, soft = 1e4, hard = 1e4, glaze = 0.0;
    for (int i = 0; i < 9; i++) {
        int x = column + i / 3 - 1;
        int y = int(floor(-world.y / height - mod(float(x), 2.0) * .5)) + i % 3 - 1;
        if (x < 0 || y < 0 || float(x) >= u_iceBoard.x || float(y) >= u_iceBoard.y) continue;
        vec4 tile = texelFetch(u_biomeHexes, ivec2(x, y), 0);
        int kind = int(tile.b * 255.0 + .5);
        float level = tile.a * 255.0 - 64.0;
        float distance = biomeHexDistance(world - boardHexCenter(x, y));
        bool wet = tile.g > .002 || kind == 2;
        bool iced = fringe ? (kind == 1 || glazed && kind == 3) && level == own : kind != 0;
        if (iced) {
            if (distance < ice) { ice = distance; glaze = kind == 3 ? 1.0 : 0.0; }
        } else if (fringe || !wet && level == own) {
            soft = min(soft, distance);
        } else {
            hard = min(hard, distance);
        }
    }
    return vec3(.5 * (soft - ice), .5 * (hard - ice), glaze);
}

// Shared by the full sheet and open leads. Keeping one optical response lets each material skip fields it does not
// use without changing the light, reflection, shadows or the reflection/transmission energy split.
void iceOptics(vec3 normal, vec3 geometric, float roughness, vec3 f0, out vec3 lit, out vec3 reflection, out vec3 sheen,
      out float fresnel) {
    vec3 ambient = vec3(1.0), direct = vec3(0.0);
    sheen = vec3(0.0);
    vec3 view = -viewDirection();
    normal = shadingNormal(normal, geometric, view);
    roughness = antialiasedRoughness(roughness, surfaceVariance);
#ifdef lightingFlag
    incidentLighting(normal, ambient, direct);
#if numDirectionalLights > 0
    sheen = u_dirLights[0].color * surfaceVisibility() * float(dot(geometric, -u_dirLights[0].direction) > 0.0)
          * surfaceReflectance(normal, -u_dirLights[0].direction, view, roughness, f0);
#endif
#ifdef cloudShadowFlag
    float cloudLight = cloudTransmission(v_cloudPosition);
    direct *= cloudLight;
    sheen *= cloudLight;
#endif
#endif
    lit = ambient + direct;
#ifdef lightingFlag
    reflection = skyReflection(normal, view, roughness, f0, GROUND_ALBEDO, environmentGround(GROUND_ALBEDO), ambient);
#else
    reflection = environmentSpecular(normal, view, roughness, f0, GROUND_ALBEDO * lit, ambient);
#endif
    fresnel = specularAlbedo(max(0.0, dot(view, normal)), roughness, f0).r;
}

// A cascade grows in vertical rivulets, unlike a lake's horizontal floes. Geometry supplies its rounded columns and
// hanging icicles; these stationary fields add trapped bubbles, frosted ribs and polished blue ice between them.
// Reuse the lake's maps and optical response, including the scene's actual sun, shadows and environment reflection.
vec3 iceCascade(vec3 worldDx, vec3 worldDy) {
    vec3 geometric = visibleFace(worldDx, worldDy, -viewDirection());
    vec3 face = normalize(v_normal);
    vec3 world = v_cloudPosition / u_iceMetre;
    // Authored once with the retained geometry: crest arc length and actual downward path length.
    // Capture UV gradients before the detail branch; no derivative depends on a coverage discard.
    vec2 uv = v_diffuseUV / u_iceCoverage, uvDx = dFdx(v_diffuseUV) / u_iceCoverage, uvDy = dFdy(v_diffuseUV) / u_iceCoverage;
    // Deliberately retain the broad, world-anchored frost field across the separate closed-surface charts.
    vec2 along = vec2(.79, .61);
    vec4 properties = textureGrad(u_iceMaps, vec3(uv, 1.0), uvDx, uvDy);
    vec3 veins = iceNoise(vec2(dot(world.xy, along), world.z * .09), .65, vec2(18.7, 6.3));
    // The fade follows the texel footprint, which a wider texture keeps at the same density.
    float detail = 1.0 - smoothstep(.008, .08, max(length(uvDx), length(uvDy)) * u_iceCoverage);
    float bubbles = properties.b * detail;
    float rime = smoothstep(.27, .55, veins.x * .70 + properties.a * .30);
    // Snow settles on upward shelves; the vertical faces retain glazed ribs instead of lake-shaped snow patches.
    float frost = clamp(rime * .78 + bubbles * .3 + smoothstep(.25, .8, face.z) * .75, 0.0, 1.0);
    vec3 normal = face;
    if (u_iceNormals * detail > .001) {
        float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
        if (abs(determinant) > 1e-10) {
            vec3 tangent = (worldDx * uvDy.y - worldDy * uvDx.y) / determinant;
            vec3 bitangent = (worldDy * uvDx.x - worldDx * uvDy.x) / determinant;
            tangent -= face * dot(face, tangent);
            if (dot(tangent, tangent) > 1e-10) {
                tangent = normalize(tangent);
                vec3 perpendicular = cross(face, tangent);
                bitangent = dot(perpendicular, bitangent) < 0.0 ? -perpendicular : perpendicular;
                vec3 mapped = iceMappedNormal(uv, mat2(1.0), uvDx, uvDy);
                normal = normalize(face + (tangent * mapped.x + bitangent * mapped.y)
                      * detail * u_iceNormals * mix(.32, .65, frost));
            }
        }
    }
    float roughness = mix(.075 + .14 * properties.g, .62, frost);
    roughness = mix(roughness, max(roughness, .24), 1.0 - detail);
    vec3 lit, reflection, sheen;
    float fresnel;
    iceOptics(normal, geometric, roughness, vec3(.018), lit, reflection, sheen, fresnel);
    vec3 blue = mix(vec3(.20, .43, .53), vec3(.43, .65, .70), veins.y);
    vec3 scattering = materialReflectance(mix(blue, ICE_SNOW, frost));
    float bodyAlpha = mix(.82, .99, frost);
    float alpha = fresnel + (1.0 - fresnel) * bodyAlpha;
    fragColor.a = alpha;
    return scattering * lit * bodyAlpha * (1.0 - fresnel) + reflection + sheen;
}

// Floating modes return premultiplied linear radiance; opaque land/glaze compose their support here.
vec3 iceFinish(vec3 underlying, vec3 worldDx, vec3 worldDy) {
    vec3 geometric = visibleFace(worldDx, worldDy, -viewDirection());
    vec3 face = normalize(v_normal);
    float slab = step(2.5, u_iceMode) * (1.0 - step(3.5, u_iceMode));
    bool fringe = u_iceMode > 3.5;
    float thin = step(1.5, u_iceMode) * (1.0 - step(2.5, u_iceMode));
    bool lead = slab > .5 && v_color.r < .5;
    vec2 world = v_cloudPosition.xy / u_iceMetre;
    vec2 metres = vec2(world.x, -world.y), uv = metres / (24.0 * u_iceCoverage);
    // The caller captured derivatives before the supporting surface's road/scar coverage discard.
    // These affine transforms retain the same map footprint through sheet/lead/border early-outs.
    vec2 metresDx = vec2(worldDx.x, -worldDx.y) / u_iceMetre;
    vec2 metresDy = vec2(worldDy.x, -worldDy.y) / u_iceMetre;
    vec2 uvDx = metresDx / (24.0 * u_iceCoverage), uvDy = metresDy / (24.0 * u_iceCoverage);
    float pixel = max(length(abs(metresDx) + abs(metresDy)), 1e-4);
    // Snow and ice cover ground up to steep banks; a slab's own cut edges are ice as well.
    float coverage = max(slab, smoothstep(.2, .45, face.z));
    if (coverage <= 0.0) { return underlying; }
    // How far inside the ice a point on land lies, the ice's thickness there, and its broken edge.
    float inside = 1e4, thickness = 1.0, rim = 0.0, shattered = 0.0;
    vec2 tilt = vec2(0.0);
    if (slab < .5) {
        vec3 border = iceBorder(world, v_cloudPosition.z, fringe, u_iceMode > 4.5);
        if (fringe) { thin = border.z; }
        // A site's own cell puts it within sqrt(2) * .9 of the query. The nearest site cannot be farther:
        // abs(jag) <= 3.2 + .7 * sqrt(2) * .9 + .8 < 5 metres. Beyond the wet rim there is no ice contribution.
        const float maximumJag = 5.0;
        if (min(border.x + maximumJag, border.y - .4) <= -1.2 - pixel) return underlying;
        // Beyond the thickness and pixel-wide rim: full coverage, no cracks, rim or shard lean even at far zoom.
        if (min(border.x - maximumJag, border.y - maximumJag - .4) >= max(5.0, .12 + pixel)) {
            inside = 5.0;
        } else {
            // The border runs jaggedly either side of the hex edge: each shard of broken ice has its own offset and lean,
            // with finer splinters along it. Where the far side cannot draw its part, the ice breaks off short instead.
            vec4 shard = iceFloes(world / 3.2 + vec2(17.3, 4.1));
            vec4 splinter = iceFloes(world / 1.1 + vec2(5.9, 23.3));
            float lean = biomeHash(ivec2(floor(shard.y * 7919.0), 3)) * 6.2832;
            float jag = 3.2 * (2.0 * shard.y - 1.0) + .7 * dot(shard.zw, vec2(cos(lean), sin(lean)))
                  + .8 * (2.0 * splinter.y - 1.0);
            inside = min(border.x - jag, border.y - abs(jag) - .4);
            // Cracks open between the shards toward the broken edge and close a few metres in.
            float near = 1.0 - smoothstep(.5, 4.0, inside);
            float crack = .2 * near;
            shattered = (1.0 - smoothstep(crack, crack + pixel, shard.x * 1.6)) * step(.02, near);
            coverage *= smoothstep(-pixel, pixel, inside) * (1.0 - shattered);
            if (coverage <= 0.0) {
                // Meltwater darkens the ground just off the ice.
                float wet = (1.0 - smoothstep(0.0, 1.2 + pixel, -inside)) * (1.0 - thin);
                return underlying * (1.0 - .22 * wet);
            }
            thickness = smoothstep(0.0, 5.0, inside);
            // Fracture faces at the edge and along the open cracks catch the light.
            rim = max(1.0 - smoothstep(0.0, .12 + pixel, inside),
                  near * (1.0 - smoothstep(crack, crack + .1 + pixel, shard.x * 1.6)));
            tilt = (vec2(shard.y, biomeHash(ivec2(floor(shard.y * 6151.0), 11))) - .5) * .16 * (1.0 - thickness);
        }
    }
    vec2 footprint = max(abs(uvDx), abs(uvDy));
    // Shading LOD follows projected texel footprint; mipmaps filter every map with the same derivatives.
    float detail = 1.0 - smoothstep(.006, .07, max(footprint.x, footprint.y) * u_iceCoverage);
    // Broad world fields, and which of two differently rotated samples of the maps shows, so the 24 m repeat does not
    // read across a lake.
    vec3 broad = iceNoise(metres, 12.0, vec2(9.3, 41.7)) * .65 + iceNoise(metres, 4.0, vec2(27.1, 3.9)) * .35;
    mat2 iceTurn = mat2(.81, -.59, .59, .81) * 1.37;
    vec2 turned = iceTurn * uv + vec2(.41, .17);
    float swap = smoothstep(.40, .60, broad.x);
    vec4 properties = mix(textureGrad(u_iceMaps, vec3(uv, 1.0), uvDx, uvDy),
          textureGrad(u_iceMaps, vec3(turned, 1.0), iceTurn * uvDx, iceTurn * uvDy), swap);
    if (lead) {
        // Leads use only cloudy slush and the unperturbed face. No floes, snow, normal or colour-map fields enter
        // this result, so avoid computing the sheet beneath an early return that would discard all of them.
        vec3 lit, reflection, sheen;
        float fresnel;
        iceOptics(face, geometric, MIN_ROUGHNESS, vec3(.02037), lit, reflection, sheen, fresnel);
        float slush = smoothstep(.5, .85, properties.a) * .55;
        vec3 water = mix(ICE_DEEP * lit, ICE_BARE * lit * .8, slush);
        float alpha = mix(.86, .95, slush);
        fragColor.a = alpha;
        return water * alpha * (1.0 - fresnel) + reflection + sheen * (1.0 - slush);
    }
    float cloudy = clamp(properties.a * mix(.3, .9, broad.y), 0.0, 1.0);
    if (slab > .5 && face.z < .5) {
        // A cut face exposes the sheet's ice, not its snow cover. The authored data map describes polished clear
        // ice and rougher cloudy ice; none of the top surface's snow, floe joints or colour-map fields enter here.
        float roughness = clamp(properties.g, MIN_ROUGHNESS, 1.0);
        roughness = mix(roughness, max(roughness, .22), 1.0 - detail);
        vec3 lit, reflection, sheen;
        float fresnel;
        iceOptics(face, geometric, roughness, vec3(.018), lit, reflection, sheen, fresnel);
        vec3 edge = mix(vec3(.42, .70, .76), ICE_SNOW, .25 + .4 * cloudy) * lit;
        fragColor.a = .94;
        // Preserve the cut face's existing .94 coverage-weighted radiance.
        return fragColor.a * (edge * (1.0 - fresnel) + reflection + sheen);
    }
    float margin = slab * (1.0 - v_color.a);
    float snow = 0.0, depth = 0.0, drift = 0.0, joint = 0.0;
    mat2 windStretch = mat2(.28, 0.0, 0.0, 1.0) * mat2(.94, .34, -.34, .94);
    vec2 wind = windStretch * metres;
    // Thin black ice has exactly zero snow and joints. Its border still uses the same shards and polished map.
    if (thin < .5) {
        // Floes of two sizes. Most carry wind-packed snow; some are blown bare to grey ice. Refrozen slush fills the joints
        // of the large ones, and of the small ones in places; many joints have healed. Toward open water they open.
        // A gentle warp bends the joints the way pressure cracks run, instead of straight lattice seams.
        vec2 bent = world + (iceNoise(metres, 7.0, vec2(51.3, 12.7)).xy - .5) * 3.5;
        vec4 plate = iceFloes(bent / 17.0);
        vec4 floe = iceFloes(bent / 6.5 + vec2(3.7, 8.1));
        vec3 along = iceNoise(metres, 2.5, vec2(7.7, 30.1));
        float healed = smoothstep(.25, .65, along.x);
        float fade = (1.0 - smoothstep(.2, .8, pixel)) * (1.0 - thin);
        // Drifted snow: shallow wind streaks and the ice's relief showing through where it is thin.
        drift = iceNoise(wind, 1.6, vec2(11.3, 5.5)).y;
        // Snow lies on most floes, blown into soft-edged patches that cross their joints; thin ice at a broken edge
        // carries less.
        snow = (1.0 - thin) * (1.0 - .6 * margin) * mix(.3, 1.0, thickness) * smoothstep(.22, .48, plate.y * .5
              + floe.y * .2 + (broad.z - .5) * .55 + (properties.a - .5) * .35 + (drift - .5) * .35 + .15);
        depth = clamp(mix(.55, 1.0, smoothstep(.3, .7, broad.y)) + (drift - .5) * .35, .4, 1.0);
        // Joints vary in width along their run; snow partly buries them.
        float width = .09 * mix(.35, 1.7, along.z);
        joint = fade * max((1.0 - smoothstep(width, width + pixel + .55 * margin, plate.x * 8.5))
              * mix(.3, 1.0, healed),
              (1.0 - smoothstep(.05, .05 + pixel, floe.x * 3.25)) * .55 * smoothstep(.5, .8, broad.y) * (1.0 - healed));
        joint *= 1.0 - .7 * snow * smoothstep(.45, .8, drift);
        joint = max(joint, fade * margin * (1.0 - smoothstep(.05, .05 + pixel + .3, floe.x * 3.25)));
    }
    float cracks = properties.b * detail * (1.0 - snow);
    vec3 normal = face;
    if (detail * u_iceNormals > .001 && face.z > .5) {
        // Polished bare ice undulates gently. Snow has its own broader, softer dunes: the same map, stretched
        // along the wind. Shards at a broken edge lean each their own way.
        vec3 polished = vec3(0.0), dunes = vec3(0.0);
        if (snow < 1.0) {
            polished = mix(iceMappedNormal(uv, mat2(1.0), uvDx, uvDy),
                  iceMappedNormal(turned, iceTurn, iceTurn * uvDx, iceTurn * uvDy), swap);
        }
        if (snow > 0.0) {
            mat2 stretch = windStretch * (24.0 / 9.0);
            dunes = iceMappedNormal(wind / (9.0 * u_iceCoverage), stretch, stretch * uvDx, stretch * uvDy);
        }
        vec3 micro = mix(polished, dunes, snow);
        vec3 tangent = normalize(vec3(face.z, 0.0, -face.x));
        micro.xy = micro.xy * detail * mix(.45, .8, snow) + tilt;
        normal = normalize(tangent * micro.x - cross(face, tangent) * micro.y + face * micro.z);
    }
    float roughness = mix(mix(.06, .22, cloudy), .8, snow * depth);
    roughness = mix(roughness, max(roughness, .22), 1.0 - detail);
    vec3 lit, reflection, sheen;
    float fresnel;
    iceOptics(normal, geometric, roughness, vec3(.018), lit, reflection, sheen, fresnel);
    vec3 tint = mix(textureGrad(u_iceColor, uv, uvDx, uvDy).rgb,
          textureGrad(u_iceColor, turned, iceTurn * uvDx, iceTurn * uvDy).rgb, swap);
    tint /= max(dot(tint, vec3(.333)), .05);
    vec3 bare = materialReflectance(ICE_BARE * mix(vec3(1.0), tint, .35) * (1.0 + cloudy * .35)
          + vec3(.55) * cracks);
    // Snow grain from the drift streaks and the ice's height field.
    vec3 snowColor = materialReflectance(mix(ICE_BARE, ICE_SNOW, depth)
          * (.92 + .12 * drift + .12 * (properties.r - .5)));
    if (slab > .5) {
        // Over the already-drawn bed and water: bare ice scatters part of the light and darkens what it transmits;
        // snow and slush replace it; open leads show the water; reflection takes over at grazing angles.
        float bareAlpha = min(1.0, .5 + .25 * cloudy + .3 * cracks);
        vec3 body = mix(mix(ICE_DEEP, bare, .8) * lit * bareAlpha, snowColor * lit, snow);
        float bodyAlpha = mix(bareAlpha, 1.0, snow);
        float jointAlpha = mix(.85, .1, margin);
        body = mix(body, mix(ICE_DEEP, ICE_JOINT, 1.0 - margin) * lit * jointAlpha, joint);
        bodyAlpha = mix(bodyAlpha, jointAlpha, joint);
        float alpha = fresnel + (1.0 - fresnel) * bodyAlpha;
        fragColor.a = alpha;
        return body * (1.0 - fresnel) + reflection + sheen;
    }
    // Land ice and glaze over their opaque support: the ground shows darker and cooler beneath bare ice, and more
    // plainly where the ice thins toward a broken edge, whose fracture faces catch the light.
    vec3 beneath = underlying * mix(vec3(.55, .63, .70), vec3(.80, .85, .88), thin);
    float glazing = mix(.45 + .25 * cloudy, .08 + .3 * cracks, thin) * mix(.35, 1.0, thickness);
    vec3 glaze = mix(beneath, bare * lit, glazing);
    vec3 body = mix(mix(glaze, snowColor * lit, snow), ICE_JOINT * lit, joint);
    body = mix(body, ICE_SNOW * lit, rim * .35 * (1.0 - thin * .6));
    vec3 iced = body * (1.0 - fresnel) + reflection + sheen;
    return mix(underlying, iced, coverage);
}
#endif

void main() {
#ifdef iceFlag
    // Capture before iceBaseSurface can discard a road-mask fragment or end a scar patch's.
    vec3 worldDx = dFdx(v_cloudPosition), worldDy = dFdy(v_cloudPosition);
    vec3 iceFace = normalize(v_normal);
    float iceVariance = normalVariance(dFdx(iceFace), dFdy(iceFace));
    surfaceVariance = iceVariance;
    if (u_iceMode > 5.5) {
        fragColor.rgb = iceCascade(worldDx, worldDy);
        return;
    }
    // Floating slabs replace both RGB and alpha in every optical path. Their ordinary ground material has no
    // discard or depth side effects: computing its maps, rain response and lighting would only discard the result.
    // Land and black ice still need their actual underlying surface for transmission and the broken border.
    if (u_iceMode > 2.5 && u_iceMode < 3.5) fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    else iceBaseSurface();
#ifdef SURFACE_SCARS
    // Where a scar patch's image leaves its receiver bare (scar-material.glsl), no coat covers the empty texel either.
    if (u_surfaceScar > .5 && surfaceScarMask.r <= 0.0) return;
#endif
    // The coat's own normal, not the variance of the support's maps.
    surfaceVariance = iceVariance;
    fragColor.rgb = iceFinish(fragColor.rgb, worldDx, worldDy);
#ifdef GROUND_DAMAGE_TILE
    // The same deposit coverage as the ground below; ice keeps its existing optical layer.
    fragColor.rgb *= 1.0 - .9 * scorchCoverage(groundScorchAt(v_cloudPosition.xy));
#endif
#else
    iceBaseSurface();
#endif
}
