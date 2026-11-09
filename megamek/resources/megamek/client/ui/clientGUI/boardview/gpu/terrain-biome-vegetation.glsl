// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 14) in vec4 a_coverRoot;
uniform float u_biomeLod;
uniform float u_coverPixels;
uniform float u_levelHeight;
uniform float u_metre;
out vec2 v_coverData;
out vec2 v_coverRoot;
out vec2 v_coverFade;

// False when the plant cannot contribute to this template: the caller skips the rest of the vertex program.
bool biomePlant(vec3 point, vec3 sourceNormal, vec4 pigment, out vec3 position, out vec3 normal, out vec4 color) {
    vec3 root = a_coverRoot.xyz;
    vec4 clip = u_projViewTrans * vec4(root, 1.0);
    float pixels = u_coverPixels / max(.001, abs(clip.w));
    // Projected hex widths at which this template's dissolve can keep any fragment.
    vec2 band = u_biomeLod < .5 ? vec2(@START0@, 1e9) : u_biomeLod < 1.5 ? vec2(@START1@, @FULL0@) : vec2(@START2@, @FULL1@);
    // Per-chunk and board-wide batches keep reeds outside this template's detail band or the view.
    if ((clip.w > 0.0 && pixels <= band.x) || pixels >= band.y) { return false; }
    // Beyond one clip plane by more than a clump's reach, nothing of it can enter the view: a clip coordinate changes
    // by at most its matrix row's length per unit moved.
    float reach = 3.0 * u_metre + u_levelHeight;
    float perspective = length(vec3(u_projViewTrans[0].w, u_projViewTrans[1].w, u_projViewTrans[2].w));
    vec2 margin = reach * (perspective + vec2(length(vec3(u_projViewTrans[0].x, u_projViewTrans[1].x, u_projViewTrans[2].x)),
          length(vec3(u_projViewTrans[0].y, u_projViewTrans[1].y, u_projViewTrans[2].y))));
    vec4 outside = vec4(clip.xy, -clip.xy) - clip.w - margin.xyxy;
    if (max(max(outside.x, outside.y), max(outside.z, outside.w)) > 0.0) { return false; }
    vec3 coverage = smoothstep(vec3(@START0@, @START1@, @START2@), vec3(@FULL0@, @FULL1@, @FULL2@), vec3(pixels));
    v_coverFade = u_biomeLod < .5 ? vec2(0.0, coverage.x) : u_biomeLod < 1.5 ? coverage.xy : coverage.yz;
    v_coverRoot = root.xy / u_worldMetre;
    float seed = a_coverRoot.w, angle = seed * 97.71;
    mat2 turn = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    float height = u_metre * mix(.90, 1.65, fract(seed * 19.37));
    float top = 1.1;
    // Vary the clump's proportions, with every root fixed to its supporting surface.
    if (fract(seed * 71.13) < .55) {
        point *= vec3(.8, .8, .60);
        sourceNormal /= vec3(.8, .8, .60);
        top *= .60;
    }
    vec2 wind = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(.8, .6);
    // Flexible marsh leaves form an arch, with no displacement at the grounded base. Strength scales both lean and
    // angular excursion.
    float flex = u_wind.z * (1.0 + .28 * vegetationGust(root.xy));
    // A blast's wind arches them away from ground zero as its front passes (terrain-shocks.glsl).
    vec2 arch = wind * flex + shockWind(root.xy) * .45;
    flex = min(length(arch), 1.4);
    if (flex > 1e-4) { wind = arch / length(arch); }
    float angleAtHeight = flex * point.z / top;
    float sine = sin(angleAtHeight), cosine = cos(angleAtHeight);
    vec2 bend = wind * sine * point.z;
    float lifted = cosine * point.z;
    // Bend the centreline without stretching its tip away from the root; retain the clump's cross-section.
    position = root + vec3(turn * point.xy + bend, lifted) * height;
    vec2 leaf = turn * sourceNormal.xy;
    vec2 tangentXY = wind * (sine + angleAtHeight * cosine);
    float tangentZ = cosine - angleAtHeight * sine;
    normal = vec3(leaf * tangentZ, sourceNormal.z - dot(tangentXY, leaf));
    if (u_biomeLod > 1.5) {
        // At a few pixels a clump needs one quad. Face the camera so rotating or looking straight down cannot turn it
        // edge-on. Keep the base horizontal and lift the top out of its supporting terrain.
        vec3 right = normalize(vec3(u_projViewTrans[0].x, u_projViewTrans[1].x, 0.0));
        vec3 up = normalize(vec3(u_projViewTrans[0].y, u_projViewTrans[1].y, u_projViewTrans[2].y));
        up = normalize(vec3(up.xy, max(up.z, .35)));
        position = root + (right * point.x + up * lifted + vec3(bend, 0.0)) * height;
        normal = cross(right, up * tangentZ + vec3(tangentXY, 0.0));
    }
    normal = dot(normal, normal) > .00000001 ? normalize(normal) : vec3(0.0, 0.0, 1.0);
    // Reed cutouts already contain their colour and vertical shading.
    color = vec4(pigment.rgb, 1.0);
    v_coverData = vec2(height, clamp(point.z, 0.0, 1.0));
    return true;
}
