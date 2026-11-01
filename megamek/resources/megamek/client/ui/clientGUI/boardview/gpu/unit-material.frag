// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted before libGDX's main; linear texture samples blend with linear diffuse before lighting and emission.
uniform sampler2D u_markerTexture;
uniform float u_markerEnabled;
in vec3 v_paintPosition;
#ifdef modelSurfaceFlag
uniform sampler2D u_paintPlate;
uniform float u_paintPlateEnabled;
#endif
uniform sampler2D u_damageTexture;
uniform float u_damageEnabled;
uniform float u_damageOpacity;
// How much of the unit shows (GpuUnitShader.Shown): 1 but while it fades out.
uniform float u_unitOpacity;
in vec2 v_damageUV;
in float v_damageMask;

// Smooth, non-periodic coordinate warping breaks the mirrored grid with one texture lookup.
vec2 damageHash(vec2 cell) {
    vec2 p = fract(cell * vec2(0.3183099, 0.3678794));
    p += dot(p, p.yx + vec2(17.17, 37.73));
    return fract(vec2(p.x * p.y, (p.x + p.y) * (p.y + 0.19)));
}

vec2 damageUV(vec2 uv) {
    vec2 cell = floor(uv * 0.5);
    vec2 f = fract(uv * 0.5);
    f = f * f * (3.0 - 2.0 * f);
    vec2 warp = mix(mix(damageHash(cell), damageHash(cell + vec2(1, 0)), f.x),
                    mix(damageHash(cell + vec2(0, 1)), damageHash(cell + vec2(1, 1)), f.x), f.y);
    return uv + (warp - 0.5) * 1.5;
}

vec3 unitOverlays(vec3 diffuse) {
#ifdef modelSurfaceFlag
    if (u_paintPlateEnabled > 0.5) {
        // Paint on a plate (GpuUnitCamouflage): the camouflage, shaded by the plate's light and dark against its mean
        // (its last mip level), covers the plate only partly, so its steel shows through the paint.
        const vec3 luma = vec3(0.2126, 0.7152, 0.0722);
        const float paintCover = 0.6;
        vec3 plate = texture(u_paintPlate, v_modelUV + modelShift).rgb;
        float tone = dot(plate, luma) / max(dot(textureLod(u_paintPlate, vec2(0.5), 16.0).rgb, luma), 1e-4);
        diffuse = mix(plate, diffuse * tone, paintCover);
    }
#endif
    if (u_markerEnabled > 0.5) {
        vec4 marker = texture(u_markerTexture, v_paintPosition.xy);
        diffuse = mix(diffuse, marker.rgb, marker.a);
    }
    if (u_damageEnabled > 0.5 && (u_damageEnabled > 1.5 || v_damageMask > 0.5)) {
        vec4 damage = texture(u_damageTexture, damageUV(v_damageUV));
        diffuse = mix(diffuse, damage.rgb, damage.a * u_damageOpacity);
    }
    return diffuse;
}
