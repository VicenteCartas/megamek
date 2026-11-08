// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Cutouts that follow their albedo's alpha (GpuModelMaterial#fade): the texels solid here draw first and hide what lies
// behind them; the faded ones then blend over the finished scene (GpuCutoutFade), in any order.
#if defined(blendedFlag) && defined(alphaTestFlag) && defined(modelSurfaceFlag) && defined(diffuseTextureFlag)
#define cutoutFadeFlag
uniform float u_modelFade;        // 0 a plain cutout, 1 its solid texels, 2 its faded texels
uniform vec2 u_modelFadeDepth;    // the nearest depth in view, and the weight lost per unit of depth beyond it
uniform float u_modelFadeSide;    // with glass in view, the faded texels this pass draws: -1 behind it, 1 in front; else 0
uniform sampler2D u_modelFadeGlass;
layout(location = 1) out float fadeOpticalDepth;

// Up close only what the texture holds as solid hides what's behind. Minified, filtering leaves few texels fully opaque,
// so the file's own cutoff decides, or crowns would lose all occlusion.
float cutoutFadeCut(float cutoff, vec2 uv) {
    if (u_modelFade > 1.5 && u_modelFadeSide != 0.0) {
        bool behind = gl_FragCoord.z > texelFetch(u_modelFadeGlass, ivec2(gl_FragCoord.xy), 0).r;
        return behind == (u_modelFadeSide > 0.0) ? 2.0 : cutoff;
    }
    if (u_modelFade < .5 || u_modelFade > 1.5) { return cutoff; }
    vec2 texels = vec2(textureSize(u_diffuseTexture, 0));
    vec2 acrossX = dFdx(uv) * texels, acrossY = dFdy(uv) * texels;
    float mip = .5 * log2(max(max(dot(acrossX, acrossX), dot(acrossY, acrossY)), 1e-6));
    return mix(.98, cutoff, smoothstep(1.0, 4.0, mip));
}

// A faded texel's share of the pixel's sums. What lets the background through multiplies, so its logarithm adds: each layer
// makes the pixel more opaque, whatever the order. Colours average by alpha, nearer layers counting more.
void cutoutFadeWrite(inout vec4 colour) {
    if (u_modelFade < 1.5) { return; }
    float alpha = min(colour.a, .999);
    float behind = clamp((gl_FragCoord.z - u_modelFadeDepth.x) * u_modelFadeDepth.y, 0.0, 20.0);
    float weight = alpha * exp(8.0 - behind);
    fadeOpticalDepth = -log(1.0 - alpha);
    colour = vec4(colour.rgb * weight, weight);
}
#endif
