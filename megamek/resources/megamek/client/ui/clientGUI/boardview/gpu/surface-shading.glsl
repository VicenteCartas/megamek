// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One surface description and its one lighting entry, after surface-lighting.glsl and water-optics.glsl. A material
// fills a Surface; shadeSurface lights it once with the board's light model (light-model.glsl): the sun or moon with
// the shadow its caller looked up, the sky and its reflection (environment-lighting.glsl), and the local lights
// (lava-lighting.glsl). Specular antialiasing widens the lobe once, for every term. Emission follows the light, never
// scaled by it. Ice, lava and water layers keep their own evaluations; this lights the surface beneath them.
#ifdef lightingFlag
// The scene's one directional light, the sun or the moon. Without one its colour is zero, so every term it lights is
// zero in the same program.
vec3 sunColor() {
#if numDirectionalLights > 0
    return u_dirLights[0].color;
#else
    return vec3(0.0);
#endif
}

// Toward that light.
vec3 sunDirection() {
#if numDirectionalLights > 0
    return -u_dirLights[0].direction;
#else
    return vec3(0.0, 0.0, 1.0);
#endif
}

struct Surface {
    vec3 albedo;             // bounded linear diffuse reflectance (materialReflectance), graded
    vec3 normal;             // shading normal: mapped detail, after shadingNormal
    vec3 geometric;          // never mapped: its side decides whether direct light reaches the front
    float roughness;         // perceptual, dry; negative selects the matte fallback
    float film;              // water coat, 0 dry to 1 standing water: smooths the lobe, sets f0 and the diffuse budget
    vec3 f0;                 // reflectance of the metallic share at normal incidence: the metal's colour
    float metallic;          // share of the surface that is metal; the rest is a dielectric (f0 .04, or water's)
    float occlusion;         // geometric occlusion of the diffuse sky; the lower of it and screen-space AO counts
    float detail;            // a texture's finer occlusion of the diffuse sky, multiplied after that
    float specularOcclusion; // occlusion of the reflected environment
    float cavity;            // micro-occlusion: of the sky, local lights and reflections, and the sun's micro-shadow
    float visibility;        // of the sun: the shadow map and any self-shadowing, looked up by the caller
    vec3 front;              // diffuse share of light on the visible side; 1 for opaque surfaces
    vec3 back;               // share passed from the far side, relative to albedo (thinTransmission); 0 when opaque
    float heat;              // lava's matte hot share, lit like the crust programs; 0 elsewhere
    vec3 bounce;             // albedo of the sunlit ground below, which the sky reflects into undersides
    vec3 emission;           // radiance the light does not scale
    float scatter;           // subsurface scattering (Godot's subsurf_scatter), 0 to 1: light wraps past the terminator
    float regolith;          // share lit as moon dust (regolithResponse), 0 to 1; 0 elsewhere
    float dielectric;        // normal-incidence dielectric reflection; .04 ordinarily, .0758 for ruby (IOR 1.76)
};

// An opaque dielectric over GROUND_ALBEDO, without occlusion, transmission, heat, emission or scattering; callers set
// what they have.
Surface opaqueSurface(vec3 albedo, vec3 normal, vec3 geometric, float roughness, float film, float visibility) {
    return Surface(albedo, normal, geometric, roughness, film, vec3(.04), 0.0, 1.0, 1.0, 1.0, 1.0, visibility,
          vec3(1.0), vec3(0.0), 0.0, GROUND_ALBEDO, vec3(0.0), 0.0, 0.0, .04);
}

// Moon dust scatters the sun as lunar regolith does, not as a Lambertian surface: it takes cos i / (cos i + cos e)
// rather than cos i, so a face turned from the sun darkens far less (the Moon's flat, even brightness), and it
// brightens where the viewer looks along the sunlight (the opposition surge). Relative to the diffuse response, and
// normalized on level ground, so the board keeps its exposure and only the slopes' shading and the surge change.
float regolithResponse(vec3 normal, vec3 light, vec3 view) {
    float nl = max(0.0, dot(normal, light)), nv = max(0.0, dot(normal, view));
    float level = max(0.0, light.z) + max(0.0, view.z);
    float shape = clamp(level / max(nl + nv, .05), .5, 2.0);
    float phase = acos(clamp(dot(light, view), -1.0, 1.0));
    return shape * (1.0 + .3 * exp(-phase / .15));
}

// Godot's metallic workflow, on a surface whose albedo is the material's colour: the metallic share reflects that colour
// and diffuses none.
void metallicShare(inout Surface s, float metallic) {
    s.f0 = s.albedo;
    s.albedo *= 1.0 - metallic;
    s.metallic = metallic;
}

// Thin tissue, per channel: of the colour R it reflects from the front it passes T = min(share * R, 1 - R) from the
// far side, so R + T never exceeds one. Surface.back takes it relative to R (T / R).
vec3 thinTransmission(vec3 albedo, float share) {
    return min(vec3(share), (1.0 - albedo) / max(albedo, vec3(1e-4)));
}

// Chiang, Li and Burley, "Taming the Shadow Terminator" (2019): a shading normal that leans further towards the sun than
// the smooth surface it details is shadowed by that surface's bumps, down to nothing at the surface's horizon, so the
// light fades into the terminator instead of stepping from lit to unlit there. For a smooth support only: across a
// faceted one it would differ from triangle to triangle.
float terminatorShadow(vec3 support, vec3 normal, vec3 light) {
    float sl = dot(support, light), nl = dot(normal, light), sn = dot(support, normal);
    if (sl <= 0.0) return 0.0;
    if (nl <= 0.0 || sn <= 0.0) return 1.0;
    float g = min(1.0, sl / max(nl * sn, 1e-6));
    return g * (1.0 + g * (1.0 - g));
}

// Reflected and emitted radiance. caustic and submerged describe water above the surface: the light its waves focus
// onto it and how many levels of water lie over it (water-optics.glsl).
vec3 shadeSurface(Surface s, float caustic, float submerged) {
#if defined(GROUND_DAMAGE_TILE) && !defined(SCORCH_IN_MATERIAL)
    // Scorch (terrain-damage-mask.glsl), a nuclear blast's or a hex's authored one, on every terrain surface lit here;
    // models never read it.
    vec2 scorches = groundScorches(v_cloudPosition.xy) * (1.0 - submerged);
    float scorch = max(scorches.x, scorches.y);
    if (scorch > 0.0) {
        Scorched deposit = scorched(scorch, SCORCH_SITES, .28, .5);
        s.albedo = mix(s.albedo, deposit.albedo, deposit.coverage);
        s.roughness = sqrt(mix(s.roughness * s.roughness, deposit.roughness * deposit.roughness, deposit.coverage));
        // The deposit buries the material: none of its metal, scattering or glow shows through.
        s.metallic *= 1.0 - deposit.coverage;
        s.scatter *= 1.0 - deposit.coverage;
        s.emission *= 1.0 - deposit.coverage;
    }
#endif
    vec3 view = -viewDirection();
    vec3 normal = s.normal;
    // The water coat, the metallic share and specular antialiasing (surfaceVariance) set every lobe's roughness and
    // f0, once.
    float roughness = dielectricRoughness(s.film, s.roughness);
    vec3 f0 = mix(vec3(mix(s.dielectric, .02037, clamp(s.film, 0.0, 1.0))), s.f0, s.metallic);
    vec3 diffuseBudget = 1.0 - specularAlbedo(max(0.0, dot(normal, view)), roughness, f0);
    vec3 multiscatter = specularMultiscatter(max(0.0, dot(normal, view)), roughness, f0);
    // The sky's ambient occlusion, with the screen-space occlusion of the opaque scene (ambient-occlusion.glsl): once,
    // for every surface, on the sky alone. Local lights and the sun keep their own visibility.
    vec2 screen = screenOcclusion();
    // Of two estimates of the same occlusion, the stronger, as Unity HDRP combines material and screen-space occlusion;
    // then the texture's finer detail, which the screen-space estimate does not resolve.
    float occlusion = min(s.occlusion, screen.x) * s.detail;
    vec3 localSpecular;
    vec3 localLight = lavaIrradiance(v_cloudPosition, normal, view, roughness, f0, localSpecular);
    // Sky from above; from below, light reflected by the sunlit ground: warm in the desert, white on snow.
    vec3 sky = skyLight(normal, s.bounce) * s.front;
    bool tissue = s.back != vec3(0.0);
#ifdef normalQualityFlag
    // Thin tissue's far side faces the ground.
    if (tissue) sky += environmentGround(s.bounce) * s.back;
#else
    if (tissue) sky += skyLight(-normal, s.bounce) * s.back;
#endif
    vec3 ambient = (sky * occlusion * s.cavity + localLight * s.cavity) * diffuseBudget;
    vec3 light = sunDirection();
    // Contact shadows (ambient-occlusion.glsl) add the small occluders the shadow map is too coarse for.
    vec3 sun = sunColor() * crystalTransmission(v_cloudPosition) * s.visibility * screen.y;
    bool lit = dot(s.geometric, light) > 0.0;
    vec3 incidence = lit ? diffuseReflectance(normal, light, view, roughness, f0) : vec3(0.0);
    if (s.scatter > 0.0) {
        // Light entering a translucent body (snow, wax, fungal flesh) leaves it further on: its matte share wraps past
        // the terminator (normalized wrap lighting), softening the shading as much as the material scatters.
        float wrapped = max(0.0, (dot(normal, light) + s.scatter) / (1.0 + s.scatter));
        incidence = mix(incidence, diffuseBudget * wrapped, s.scatter);
    }
    vec3 incident = incidence * s.front;
    if (s.regolith > 0.0) incident *= mix(1.0, regolithResponse(normal, light, view), s.regolith);
    if (tissue) {
        vec3 through = dot(s.geometric, light) < 0.0
              ? diffuseReflectance(-normal, light, -view, roughness, f0) : vec3(0.0);
        incident += through * s.back;
    }
    // Micro-shadowing (Naughty Dog's, as Unity HDRP's): the cavity hides the sun from its hollows, most where it grazes
    // either face; a cavity under .7 keeps some shade even overhead. The sun's only cavity term, on both its lobes.
    float hollows = clamp(abs(dot(normal, light)) + 2.0 * s.cavity * s.cavity - 1.0, 0.0, 1.0);
    vec3 direct = sun * incident * hollows * (1.0 + caustic);
    vec3 sheen = vec3(0.0);
    if (s.heat < 1.0 && lit && any(greaterThan(incidence, vec3(0.0)))) {
        sheen = sun * surfaceReflectance(normal, light, view, roughness, f0) * multiscatter * hollows;
    }
    if (s.heat > 0.0) {
        // The material response and spill light are identical on both sides of the blend. Only the sky bounce,
        // occlusion and shadow filter differ; reuse the expensive terms instead of shading twice.
        vec3 hotAmbient = (skyLight(normal, GROUND_ALBEDO) + localLight) * diffuseBudget;
        vec3 hotDirect = sunColor() * crystalTransmission(v_cloudPosition) * incident * surfaceVisibility();
        ambient = mix(ambient, hotAmbient * s.cavity, s.heat);
        direct = mix(direct, hotDirect, s.heat);
        sheen *= 1.0 - s.heat;
    }
    vec3 albedo = s.albedo;
    // Cloud shadows attenuate direct light and sheen here (inserted by GpuCloudShadow).
    albedo *= ambient + direct;
    albedo += sheen;
    // The hot share is matte, matching the dedicated crust and lava programs.
    albedo += localSpecular * multiscatter * s.cavity * (1.0 - s.heat);
#ifdef normalQualityFlag
    vec3 reflectedSky = s.film > .01 || s.metallic > .01 ? skyReflection(normal, view, roughness, f0, s.bounce,
          environmentGround(s.bounce), surfaceAmbient(vec3(0.0, 0.0, 1.0)))
          : faceSky * specularAlbedo(max(0.0, dot(normal, view)), roughness, f0);
    albedo += reflectedSky * multiscatter * s.specularOcclusion * s.cavity * (1.0 - s.heat);
#else
    albedo += skyReflection(normal, view, roughness, f0, s.bounce, environmentGround(s.bounce),
          surfaceAmbient(vec3(0.0, 0.0, 1.0))) * multiscatter * s.specularOcclusion * s.cavity * (1.0 - s.heat);
#endif
    if (submerged > 0.0) {
        vec3 scattered = surfaceAmbient(vec3(0.0, 0.0, 1.0)) * occlusion + sunOnGround() * (1.0 + caustic);
        albedo = submergedLight(albedo, s.albedo, scattered, submerged);
    }
    return albedo + s.emission;
}

vec3 shadeSurface(Surface s) { return shadeSurface(s, 0.0, 0.0); }
#endif
