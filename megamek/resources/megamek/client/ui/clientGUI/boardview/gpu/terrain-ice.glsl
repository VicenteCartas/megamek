// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One optical model for frozen lake slabs, land ice and detected black ice on pavement/roads/bridge decks. Every mode
// samples the same world-anchored floes, so a lake slab and the land ice beside it read as one sheet over other ground.
// After terrain-hexes.glsl: land ice meets dry ground along a jagged, shattered border that straddles their shared
// hex edge; the dry side draws its part of it.
#ifdef iceFlag
uniform sampler2D u_iceColor;
// Layer 0 normal; layer 1 R surface height, G roughness, B cracks and bubbles, A cloudy ice; layer 2 the floes baked for
// ICE_FLOE_REPEAT metres: R joints, G where snow settles first; for a broken border, B the cracks between its shards and A each
// shard's value, flat up to its edge.
uniform sampler2DArray u_iceMaps;
const float ICE_FLOE_REPEAT = 102.0;
const float ICE_SNOW_LINE = .435;
// Turned so that neither the wind's streaks nor the repeat run along the board's axes.
const mat2 ICE_FLOE_TURN = mat2(.94, -.34, .34, .94);
// The ground that shows through land ice's bare interior, where its own shader does not run.
const vec3 ICE_GROUND = vec3(.085, .10, .105);
// 1 land ice, 2 detected thin black ice, 3 floating lake slab; 4 dry ground beside land ice, 5 paved ground beside
// land or black ice, which draw their side of the shared border; 6 solid frozen waterfall curtains and icicles.
#ifdef ICE_MODE
// Floating slabs and waterfalls: whole draw-uniform paths disappear at compilation, as they need no land borders or base
// shading. The modes on the ground share programs (GpuIceShader.variant), which hold no slab; land ice's holds no black ice.
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

// The border of the iced area, from the per-hex texture, in metres: x is signed to the border shared with dry
// neighbours at this level, which draw their side of it (positive in the ice); y is to ground that cannot (water,
// another level); z is 1 where the nearest ice is thin black ice. Beneath the surface of the water hex a point lies
// in, ice has no footing: x is then far outside. A dry point measures to the ice it borders (fringe), which is
// frozen land, or black ice as well for paved ground (glazed); a frozen lake ends at its own shore.
vec3 iceBorder(vec2 world, float z, bool fringe, bool glazed) {
    if (u_iceBoard.x < 1.0) return vec3(1e4, 1e4, 0.0);
    const float width = 30.0, height = 30.0 * BOARD_HEX.y / BOARD_HEX.x;
    int column = int(floor(world.x / (width * .75)));
    // B bit 7 of a cell's texel: the CPU proved all it can reach frozen land at one level (GpuBiomeSurface): no border near.
    ivec2 iceCell = ivec2(column, int(floor(-world.y / height - mod(float(column), 2.0) * .5)));
    if (all(greaterThanEqual(iceCell, ivec2(0))) && all(lessThan(iceCell, ivec2(u_iceBoard)))
          && (int(texelFetch(u_biomeHexes, iceCell, 0).b * 255.0 + .5) & 128) != 0) {
        return vec3(1e4, 1e4, 0.0);
    }
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
    bool ownWet = support.g > .002 || (int(support.b * 255.0 + .5) & 3) == 2;
    if (ownWet && z < own * u_iceLevel - u_iceWaterline) return vec3(-1e4, -1e4, 0.0);
    float ice = 1e4, soft = 1e4, hard = 1e4, glaze = 0.0;
    for (int i = 0; i < 9; i++) {
        int x = column + i / 3 - 1;
        int y = int(floor(-world.y / height - mod(float(x), 2.0) * .5)) + i % 3 - 1;
        if (x < 0 || y < 0 || float(x) >= u_iceBoard.x || float(y) >= u_iceBoard.y) continue;
        vec4 tile = texelFetch(u_biomeHexes, ivec2(x, y), 0);
        int kind = int(tile.b * 255.0 + .5) & 3;
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
#ifdef normalQualityFlag
    ambient = skyLight(normal, GROUND_ALBEDO);
#else
    ambient = skyLight(normal, GROUND_ALBEDO) + lavaIrradiance(v_cloudPosition, normal);
#endif
#if numDirectionalLights > 0
#if defined(normalQualityFlag) && defined(shadowMapFlag)
    // One bilinear comparison, as Normal's ground.
    float visibility = shadowVisibility(textureLod(u_shadowTexture, v_shadowMapUv, 0.0));
#else
    float visibility = surfaceVisibility();
#endif
    vec3 sun = u_dirLights[0].color * visibility;
    direct = sun * max(0.0, dot(normal, -u_dirLights[0].direction));
    sheen = sun * float(dot(geometric, -u_dirLights[0].direction) > 0.0)
          * surfaceReflectance(normal, -u_dirLights[0].direction, view, roughness, f0);
#endif
#ifdef cloudShadowFlag
    float cloudLight = cloudTransmission(v_cloudPosition);
    direct *= cloudLight;
    sheen *= cloudLight;
#endif
#endif
    lit = ambient + direct;
    vec3 albedo = specularAlbedo(max(0.0, dot(view, normal)), roughness, f0);
    fresnel = albedo.r;
#if defined(lightingFlag) && defined(normalQualityFlag)
    // Snow reflects its face's sky; polished ice the sky along its reflection, whatever may hide it.
    reflection = roughness > .5 ? faceSky * albedo : environmentRadiance(reflect(-view, normal), clamp(roughness, MIN_ROUGHNESS, 1.0),
          environmentGround(GROUND_ALBEDO), ambient) * albedo;
#elif defined(lightingFlag)
    reflection = skyReflection(normal, view, roughness, f0, GROUND_ALBEDO, environmentGround(GROUND_ALBEDO), ambient);
#else
    reflection = environmentSpecular(normal, view, roughness, f0, GROUND_ALBEDO * lit, ambient);
#endif
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

// Floating modes return premultiplied linear radiance; opaque land/glaze compose their support here. Land ice has measured its
// border already when measured says so; interior means no ground ran beneath this point.
vec3 iceFinish(vec3 underlying, vec3 worldDx, vec3 worldDy, vec3 border, bool measured, bool interior) {
    vec3 geometric = visibleFace(worldDx, worldDy, -viewDirection());
    vec3 face = normalize(v_normal);
#ifdef ICE_MODE
    float slab = step(2.5, u_iceMode) * (1.0 - step(3.5, u_iceMode));
#else
    const float slab = 0.0;
#endif
    bool fringe = u_iceMode > 3.5;
#ifdef ICE_LAND
    float thin = 0.0;
    const bool glazed = false;
#else
    float thin = step(1.5, u_iceMode) * (1.0 - step(2.5, u_iceMode));
    bool glazed = u_iceMode > 4.5;
#endif
    bool lead = slab > .5 && v_color.r < .5;
    vec2 world = v_cloudPosition.xy / u_iceMetre;
    vec2 metres = vec2(world.x, -world.y), uv = metres / (24.0 * u_iceCoverage);
    // The caller captured derivatives before the supporting surface's road/scar coverage discard.
    vec2 metresDx = vec2(worldDx.x, -worldDx.y) / u_iceMetre;
    vec2 metresDy = vec2(worldDy.x, -worldDy.y) / u_iceMetre;
    vec2 uvDx = metresDx / (24.0 * u_iceCoverage), uvDy = metresDy / (24.0 * u_iceCoverage);
    float pixel = max(length(abs(metresDx) + abs(metresDy)), 1e-4);
    // Snow and ice cover ground up to steep banks; a slab's own cut edges are ice as well.
    float coverage = max(slab, smoothstep(.2, .45, face.z));
    if (coverage <= 0.0) { return underlying; }
    vec4 floes = textureGrad(u_iceMaps, vec3(ICE_FLOE_TURN * metres / ICE_FLOE_REPEAT, 2.0),
          ICE_FLOE_TURN * metresDx / ICE_FLOE_REPEAT, ICE_FLOE_TURN * metresDy / ICE_FLOE_REPEAT);
    // How far inside the ice a point on land lies, the ice's thickness there, and its broken edge.
    float inside = 1e4, thickness = 1.0, rim = 0.0, shattered = 0.0;
    vec2 tilt = vec2(0.0);
    if (slab < .5) {
        if (!measured) { border = iceBorder(world, v_cloudPosition.z, fringe, glazed); }
        if (fringe) { thin = border.z; }
        const float maximumJag = 5.0;
        if (min(border.x + maximumJag, border.y - .4) <= -1.2 - pixel) return underlying;
        if (min(border.x - maximumJag, border.y - maximumJag - .4) >= max(5.0, .12 + pixel)) {
            inside = 5.0;
        } else {
            // The border runs jaggedly either side of the hex edge, along the map's shards. Where the far side cannot draw
            // its part, the ice breaks off short instead.
            // Cracks open between the shards toward the broken edge and close a few metres in; along them the ice retreats.
            float near = 1.0 - smoothstep(1.0, 5.0, min(border.x, border.y));
            float jag = 3.6 * (2.0 * floes.a - 1.0);
            inside = min(border.x - jag, border.y - abs(jag) - .4);
            shattered = near * smoothstep(.35, .65, floes.b);
            coverage *= smoothstep(-pixel, pixel, inside) * (1.0 - shattered);
            if (coverage <= 0.0) {
                // Meltwater darkens the ground just off the ice.
                float wet = (1.0 - smoothstep(0.0, 1.2 + pixel, -inside)) * (1.0 - thin);
                return underlying * (1.0 - .22 * wet);
            }
            thickness = smoothstep(0.0, 5.0, inside);
            // Fracture faces at the edge catch the light; each shard leans its own way.
            rim = max(1.0 - smoothstep(0.0, .12 + pixel, inside), near * smoothstep(.1, .35, floes.b) * .6);
            tilt = vec2(floes.a - .5, fract(floes.a * 7.0) - .5) * .16 * (1.0 - thickness);
        }
    }
    vec2 footprint = max(abs(uvDx), abs(uvDy));
    // Shading LOD follows projected texel footprint; mipmaps filter every map with the same derivatives.
    float detail = 1.0 - smoothstep(.006, .07, max(footprint.x, footprint.y) * u_iceCoverage);
    vec4 properties = textureGrad(u_iceMaps, vec3(uv, 1.0), uvDx, uvDy);
    if (lead) {
        // Leads use only cloudy slush and the unperturbed face.
        vec3 lit, reflection, sheen;
        float fresnel;
        iceOptics(face, geometric, MIN_ROUGHNESS, vec3(.02037), lit, reflection, sheen, fresnel);
        float slush = smoothstep(.5, .85, properties.a) * .55;
        vec3 water = mix(ICE_DEEP * lit, ICE_BARE * lit * .8, slush);
        float alpha = mix(.86, .95, slush);
        fragColor.a = alpha;
        return water * alpha * (1.0 - fresnel) + reflection + sheen * (1.0 - slush);
    }
    float cloudy = clamp(properties.a * mix(.3, .9, floes.g), 0.0, 1.0);
    if (slab > .5 && face.z < .5) {
        // A cut face exposes the sheet's ice, not its snow cover.
        float roughness = clamp(properties.g, MIN_ROUGHNESS, 1.0);
        roughness = mix(roughness, max(roughness, .22), 1.0 - detail);
        vec3 lit, reflection, sheen;
        float fresnel;
        iceOptics(face, geometric, roughness, vec3(.018), lit, reflection, sheen, fresnel);
        vec3 edge = mix(vec3(.42, .70, .76), ICE_SNOW, .25 + .4 * cloudy) * lit;
        fragColor.a = .94;
        return fragColor.a * (edge * (1.0 - fresnel) + reflection + sheen);
    }
    float margin = slab * (1.0 - v_color.a);
    float snow = 0.0, depth = 0.0, joint = 0.0;
    // Thin black ice has exactly zero snow and joints.
    if (thin < .5) {
        // Thin ice at a broken edge carries less snow.
        // A broad field moves the snow line, so that no two repeats of the map look alike.
        vec3 broad = iceNoise(metres, 32.0, vec2(9.3, 41.7));
        float line = ICE_SNOW_LINE + (broad.x - .5) * .16;
        snow = (1.0 - .6 * margin) * mix(.3, 1.0, thickness) * smoothstep(line - .065, line + .065, floes.g);
        depth = clamp(.74 + (floes.g - ICE_SNOW_LINE) * 1.3 + (broad.y - .5) * .25, .4, 1.0);
        // Snow partly buries the joints; toward open water they open.
        joint = floes.r * (1.0 - .45 * snow * smoothstep(.6, .9, depth));
        joint = max(joint, margin * floes.r);
    }
    float cracks = properties.b * detail * (1.0 - snow);
    vec3 normal = face;
    if (detail * u_iceNormals > .001 && face.z > .5) {
        // Polished bare ice undulates gently, snow more softly; shards at a broken edge lean each their own way.
        vec3 micro = iceMappedNormal(uv, mat2(1.0), uvDx, uvDy);
        vec3 tangent = normalize(vec3(face.z, 0.0, -face.x));
        micro.xy = micro.xy * detail * mix(.45, .8, snow) + tilt;
        normal = normalize(tangent * micro.x - cross(face, tangent) * micro.y + face * micro.z);
    }
    float roughness = mix(mix(.06, .22, cloudy), .8, snow * depth);
    roughness = mix(roughness, max(roughness, .22), 1.0 - detail);
    vec3 lit, reflection, sheen;
    float fresnel;
    iceOptics(normal, geometric, roughness, vec3(.018), lit, reflection, sheen, fresnel);
    vec3 tint = textureGrad(u_iceColor, uv, uvDx, uvDy).rgb;
    tint /= max(dot(tint, vec3(.333)), .05);
    vec3 bare = materialReflectance(ICE_BARE * mix(vec3(1.0), tint, .35) * (1.0 + cloudy * .35)
          + vec3(.55) * cracks);
    // Snow grain from the depth's streaks and the ice's height field.
    vec3 snowColor = materialReflectance(mix(ICE_BARE, ICE_SNOW, depth)
          * (.92 + .24 * (depth - .5) + .12 * (properties.r - .5)));
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
    // Land ice and glaze over their support: the ground shows darker and cooler beneath bare ice, and more plainly where the
    // ice thins toward a broken edge. Deep inside it no ground ran: a flat tone stands for it.
    vec3 ground = interior ? ICE_GROUND * lit : underlying;
    vec3 beneath = ground * mix(vec3(.55, .63, .70), vec3(.80, .85, .88), thin);
    float glazing = mix(.45 + .25 * cloudy, .08 + .3 * cracks, thin) * mix(.35, 1.0, thickness);
    vec3 glaze = mix(beneath, bare * lit, glazing);
    vec3 body = mix(mix(glaze, snowColor * lit, snow), ICE_JOINT * lit, joint);
    body = mix(body, ICE_SNOW * lit, rim * .35 * (1.0 - thin * .6));
    vec3 iced = body * (1.0 - fresnel) + reflection + sheen;
    return mix(ground, iced, coverage);
}
#endif

void main() {
#ifdef iceFlag
    // Capture before iceBaseSurface can discard a road-mask fragment or end a scar patch's.
    vec3 worldDx = dFdx(v_cloudPosition), worldDy = dFdy(v_cloudPosition);
    vec3 iceFace = normalize(v_normal);
    float iceVariance = normalVariance(dFdx(iceFace), dFdy(iceFace));
    surfaceVariance = iceVariance;
#ifdef ICE_MODE
    if (u_iceMode > 5.5) {
        fragColor.rgb = iceCascade(worldDx, worldDy);
        return;
    }
#endif
    // Floating slabs replace both RGB and alpha in every optical path. Their ordinary ground material has no
    // discard or depth side effects: computing its maps, rain response and lighting would only discard the result.
    // Land and black ice still need their actual underlying surface for transmission and the broken border.
    vec3 iceBorderHere = vec3(0.0);
    bool iceMeasured = false, iceInterior = false;
#ifdef ICE_MODE
    if (u_iceMode > 2.5 && u_iceMode < 3.5) fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    else
#endif
    {
#if !defined(roadCoatFlag) && !defined(SURFACE_SCARS)
        // Well inside land ice on a flat top, nothing of the ground below shows but a tone: its whole shader is spared.
        if (u_iceMode < 1.5 && iceFace.z > .45) {
            iceBorderHere = iceBorder(v_cloudPosition.xy / u_iceMetre, v_cloudPosition.z, false, false);
            iceMeasured = true;
            iceInterior = min(iceBorderHere.x, iceBorderHere.y - .4) > 10.5;
        }
#endif
        if (iceInterior) fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        else iceBaseSurface();
    }
#ifdef SURFACE_SCARS
    // Where a scar patch's image leaves its receiver bare (scar-material.glsl), no coat covers the empty texel either.
    if (u_surfaceScar > .5 && surfaceScarMask.r <= 0.0) return;
#endif
    // The coat's own normal, not the variance of the support's maps.
    surfaceVariance = iceVariance;
    fragColor.rgb = iceFinish(fragColor.rgb, worldDx, worldDy, iceBorderHere, iceMeasured, iceInterior);
#ifdef GROUND_DAMAGE_TILE
    // The same deposit coverage as the ground below; ice keeps its existing optical layer.
    fragColor.rgb *= 1.0 - .9 * scorchCoverage(groundScorchAt(v_cloudPosition.xy));
#endif
#else
    iceBaseSurface();
#endif
}
