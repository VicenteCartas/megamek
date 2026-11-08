// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The pool and a waterfall's rounded crest use exactly the same surface calculation.
void waterHabitat(out vec4 habitat, out vec4 mixture) {
    habitat = vec4(0.0);
    mixture = waterPalette(u_waterMaterial.x);
    // A heaving surface keeps the liquid of its rest level, not of the height a crest reaches.
    vec3 at = v_cloudPosition;
#ifdef waterSurfaceFlag
    at = v_waterRest;
#endif
    vec4 fringe, connected;
    terrainCoverage(at / u_metre, 14.0, (at.z + u_waterLine) / u_levelHeight, habitat, fringe, connected);
    if (dot(connected, vec4(1.0)) > .5) mixture = connected;
}

// The local lights complete illumination here, in the walk that also gives their reflection: a fall's sheet (sheet,
// sheetRoughness) receives its own in sheetGlint.
vec4 waterPool(bool falling, vec3 geometric, vec4 habitat, vec4 mixture, vec3 tint, vec3 authored,
      inout WaterLighting illumination, vec3 sheet, float sheetRoughness, out vec3 sheetGlint) {
    bool procedural = u_waterMaterial.y > 0.5;
    vec2 rest = v_cloudPosition.xy;
#ifdef waterSurfaceFlag
    rest = v_waterRest.xy;
#endif
    vec2 position = rest * u_rainScale;
    float effects = u_waterEffects;
    float detail = u_rainDetail * effects;
    float churn = mix(0.55, 1.0, u_rainDetail) * effects;
    vec3 scatter = waterScatter(mixture), froth = waterFroth(mixture, tint);
    vec3 view = -viewDirection(), color;
    float alpha;
    // The pool's own surface. A fall starts with exactly this look where it leaves its pool, from the same field,
    // ripples, foam and agitation at the same place, so the two join without a seam; only its curving lip then
    // turns into falling water.
    vec3 surfaceNormal = normalize(v_normal);
    vec4 field = texture(u_waterField, rest * u_waterFieldMap.xy + u_waterFieldMap.zw);
    float shore = (field.r * 2.0 - 1.0) * SHORE_RANGE;
    float depth = field.g * WATER_DEPTH_RANGE;
    float wetShore = falling ? 0.0 : clamp(dot(habitat.yzw, vec3(3.0)), 0.0, 1.0)
          * (1.0 - smoothstep(.12, .65, depth)) * mix(.45, 1.0, mixture.x);
    vec2 current = waterSurfaceCurrent(field) * effects;
    float agitation = (falling ? v_color.a : v_color.r) * effects;

    // Broad noise, slow enough never to shear an advected pattern: G staggers the flow's restarts, B gathers
    // rapids foam into clusters a hex or two across, R varies the deep water's tone and the surf along a bank.
    vec4 broad = texture(u_surfaceNoise, rainNoiseAt(position * 0.008 + 0.29));
    // Gusts roll downwind in patches, so the wind never roughens a lake evenly: cat's paws on the chop. The drift
    // is integrated as the wind turns, so the patches never jump.
    float gust = texture(u_surfaceNoise, rainNoiseAt(position * 0.035 - u_waterDrift * 0.006)).r;

    // Foam grain and, in rapids, fine chop. A current advects them in two phases, each restarting only while
    // its weight is zero, however weak the current, so no restart ever shows; in still water both phases read
    // the same texels and nothing pulses. Samples stay outside non-uniform branches: mip selection stays defined.
    float flowing = waterFlowShare(current);
    float level = smoothstep(.975, .995, surfaceNormal.z);
    // The current already includes gradual shallow drag. Fade its detail out at the actual waterline without
    // selecting a separate surface style for depth-zero hexes.
    float running = flowing * level * smoothstep(0.0, 0.04, depth) * (1.0 - wetShore);
    LiquidFlow flow = liquidFlow(u_rainTime, FLOW_CYCLE, broad.g);
    float phase = flow.phase.x;
    float weightA = flow.weight.x, weightB = flow.weight.y;
    // Uncorrelated patterns blend with preserved variance, so flowing water never calms mid-cycle.
    float preserve = mix(1.0, liquidFlowVariance(flow), flowing);
    vec2 advectedA = liquidFlowUv(position, current, flow, 0, vec2(0.0), 0.0);
    vec2 advectedB = liquidFlowUv(position, current, flow, 1, vec2(0.0), 0.0) + 0.37 * flowing;
    // Fixed in the world: the simulated cascades carry the wind's direction, and a detail map turning with the wind
    // would sweep across the whole board whenever it changed.
    mat2 crossing = CROSSING;
    // Fine facets gently oscillate instead of transporting the lake along an arbitrary fixed bearing. River
    // advection still comes from its current; the oscillation has bounded displacement and one shared clock.
    vec2 driftB = vec2(sin(u_rainTime * .47), cos(u_rainTime * .31)) * .025;
    vec4 small = ((texture(u_surfaceNoise, vec3(crossing * advectedA * 3.8 + driftB, WATER_DETAIL)) - 0.5) * weightA
          + (texture(u_surfaceNoise, vec3(crossing * advectedB * 3.8 + driftB, WATER_DETAIL)) - 0.5) * weightB) * preserve;
    small.rg = small.rg * crossing;
    // Rapids churn in larger patches than ordinary current ripples, carried by the current alone. Still, agitated
    // water (a lake's pouring lip) keeps a slow churn in the same restarting phases, whose patterns never overlap.
    // Keep one clock and blend by agitation: multiplying time by local agitation would shear the texture at joins.
    vec2 stir = current + vec2(0.06, -0.06) * (1.0 - flowing);
    vec2 stirA = liquidFlowUv(position, stir, flow, 0, vec2(0.0), 0.0);
    vec2 stirB = liquidFlowUv(position, stir, flow, 1, vec2(0.0), 0.0) + 0.37;
    vec2 rapidA = crossing * stirA * 1.8, rapidB = crossing * stirB * 1.8;
    // Capture the complete advected footprint before the spatial skip, including current and phase variation.
    vec2 rapidDxA = dFdx(rapidA), rapidDyA = dFdy(rapidA);
    vec2 rapidDxB = dFdx(rapidB), rapidDyB = dFdy(rapidB);
    vec4 rapidDetail = vec4(0.0);
    if (agitation > 0.0) {
        rapidDetail = ((textureGrad(u_surfaceNoise, vec3(rapidA, WATER_DETAIL), rapidDxA, rapidDyA) - 0.5) * weightA
              + (textureGrad(u_surfaceNoise, vec3(rapidB, WATER_DETAIL), rapidDxB, rapidDyB) - 0.5) * weightB)
              * liquidFlowVariance(flow);
    }
    rapidDetail.rg = rapidDetail.rg * crossing;

    // Wind waves: three cascades of one simulated sea at the undisplaced position. The same local exposure shapes
    // both geometry and shading; sheltered water must not regain an open sea's swell through a shading floor.
    vec4 fetchMetres = waterFetch(rest);
    vec3 energy = waterWaveEnergy(rest, field, fetchMetres);
    // Open water keeps this floor. Running water is too narrow for the wind to raise a sea on: a strong wind raises
    // only short steep chop there, the ripple band, carried along with its current below. A descent keeps its own
    // current-driven surface: its mesh normal, exactly up on level water, tilts from the lip.
    float openWater = smoothstep(0.0, 4.0, dot(fetchMetres, vec4(1.0)));
    vec3 minimum = mix(vec3(0.0, .08, .2) * openWater, vec3(0.0, 0.0, level), flowing);
    vec3 lit = max(energy, minimum * (smoothstep(0.0, .01, shore) * waterWindShare(field))) * effects;
    lit.yz *= mix(.75, 1.25, gust);
    vec4 swell = vec4(0.0), chop = vec4(0.0), ripple = vec4(0.0);
    // The fine slope shapes reflections and glitter; the broad one, swell and some chop, shapes how the waves
    // are lit, so the big crests read from a tactical height instead of drowning in ripples.
    vec2 slope, broadSlope;
    float noise, height = 0.0, whitecaps = 0.0, fineUV = 1.0;
    if (u_waterOceanScale.x > 0.0) {
        swell = texture(u_waterOcean0, rest * u_waterOceanScale.x);
        // Wind waves on a current ride along with it: in running water they are read where the current has carried
        // them, in the same two restarting phases as the detail above, so they travel with the flow plus the wind.
        vec2 chopDx = dFdx(rest * u_waterOceanScale.y), chopDy = dFdy(rest * u_waterOceanScale.y);
        vec2 rippleDx = dFdx(rest * u_waterOceanScale.z), rippleDy = dFdy(rest * u_waterOceanScale.z);
        if (flowing > 0.0) {
            vec2 carriedA = advectedA / u_rainScale, carriedB = advectedB / u_rainScale;
            chop = textureGrad(u_waterOcean1, carriedA * u_waterOceanScale.y, chopDx, chopDy) * weightA
                  + textureGrad(u_waterOcean1, carriedB * u_waterOceanScale.y, chopDx, chopDy) * weightB;
            ripple = textureGrad(u_waterOcean2, carriedA * u_waterOceanScale.z, rippleDx, rippleDy) * weightA
                  + textureGrad(u_waterOcean2, carriedB * u_waterOceanScale.z, rippleDx, rippleDy) * weightB;
            chop.xyz *= preserve;
            // A river's short chop keeps its real height: the storm's art-directed gain is for a sea's crests.
            ripple.xyz *= preserve / mix(1.0, u_waterStorm, flowing);
        } else {
            chop = textureGrad(u_waterOcean1, rest * u_waterOceanScale.y, chopDx, chopDy);
            ripple = textureGrad(u_waterOcean2, rest * u_waterOceanScale.z, rippleDx, rippleDy);
        }
        // A river's short chop is all the wave it has: its faces take the light as the sea's crests do.
        broadSlope = swell.xy * lit.x + chop.xy * (lit.y * .35) + ripple.xy * (lit.z * flowing * 1.5);
        slope = swell.xy * lit.x + chop.xy * lit.y + ripple.xy * (lit.z * .7);
        // Metres above the mean surface, from the crests the geometry and the eye both see.
        height = swell.z * energy.x + chop.z * energy.y;
        // A river's short chop is steep enough in a gale to break at its highest crests: small flecks, never a sea's
        // whitecaps. In a breeze its crests rarely reach that height.
        float flecks = smoothstep(.06, .12, ripple.z) * lit.z * flowing;
        // GpuOcean steers the open sea's whitecaps by this combination and lace (ocean-water-finish.frag).
        whitecaps = max(max(swell.w * energy.x, chop.w * energy.y * .8), flecks);
        fineUV = u_waterOceanScale.z;
        noise = small.b;
    } else {
        vec4 large = texture(u_surfaceNoise, vec3(crossing * position * 1.65 + vec2(u_rainTime * 0.018, 0.0), WATER_DETAIL)) - 0.5;
        slope = large.rg * crossing * 0.5 * lit.x * smoothstep(0.0, .25, u_waterWind.z);
        broadSlope = slope;
        noise = large.b * 0.6 + small.b * 0.4;
    }
    float marshShare = habitat.y / max(dot(habitat.yzw, vec3(1.0)), .0001);
    float calmed = 1.0 - wetShore * mix(.75, .25, marshShare);
    slope *= calmed;
    broadSlope *= calmed;
    // Slopes are the surface's gradient: the normal leans away from the way the water rises.
    // Ordinary rivers carry the same fine facets as lakes, not a separate layer of enormous reflective faces.
    // Their speed and strength ease into still water and over shallow shelves; coarse churn requires agitation.
    slope += (small.rg * (0.004 + 0.046 * u_waterWind.z + 0.024 * running) * detail
          + rapidDetail.rg * (0.4 * agitation)) * effects;
    vec3 ripples = effects > 0.0 ? rainRippleField(position) * effects : vec3(0.0);
    slope += ripples.xy;

    // Surf: the swell feels the bottom near a bank and rolls in as bands whose crests march shoreward, broken up
    // along the bank. The bank's direction comes from the field itself, so the surf turns with every bay.
    vec2 seaward = vec2(0.0);
    float fetch = 0.0;
    // Encoded bank distance cannot exceed SHORE_RANGE. Beyond this bound even the most open sample gives zero
    // fetch, as does a full current. The field has one level, so explicit LOD keeps this spatial branch defined.
    if (flowing < 1.0 && SHORE_RANGE - shore > 0.12) {
        vec2 fieldUV = rest * u_waterFieldMap.xy + u_waterFieldMap.zw;
        seaward = vec2(textureLod(u_waterField, fieldUV + vec2(u_waterFieldMap.x * 3.0, 0.0), 0.0).r,
              textureLod(u_waterField, fieldUV + vec2(0.0, u_waterFieldMap.y * 3.0), 0.0).r) - field.r;
        seaward /= max(length(seaward), 1e-5);
        // Surf needs open water to build over; a narrow channel or current's bank stays without it.
        float beyond = (textureLod(u_waterField,
              fieldUV + seaward * u_waterFieldMap.xy * (0.35 / u_rainScale), 0.0).r * 2.0 - 1.0) * SHORE_RANGE;
        fetch = smoothstep(0.12, 0.25, beyond - shore) * (1.0 - flowing);
    }
    float nearBank = (1.0 - smoothstep(0.05, 0.28, shore)) * smoothstep(0.0, 0.01, shore) * fetch;
    float march = shore * 65.0 + u_rainTime * 1.6 + broad.r * 11.0 + gust * 5.0;
    // Residual ocean swell still washes towards shore in calm air: a small, roughly eight-second wave. Available
    // open water limits it in lakes and channels. Its phase stays on the shared clock as wind changes; mixing
    // two fixed wave trains avoids the jumps and spatial shearing caused by multiplying time by local strength.
    float openReach = max(max(fetchMetres.x, fetchMetres.y), max(fetchMetres.z, fetchMetres.w));
    float swellWash = .08 * smoothstep(250.0, WATER_FETCH, openReach);
    float swellMarch = shore * 22.0 + u_rainTime * .75 + broad.r * 3.0;
    // Wind waves wash most strongly onto exposed shores. The gentle residual wash does not paint a foam rim;
    // breaking crests, rapids, falls and unit contacts supply their own foam below.
    vec2 waveDirection = u_waterWind.xy / max(length(u_waterWind.xy), .001);
    float arriving = mix(.15, 1.0, max(dot(waveDirection, -seaward), 0.0));
    float surfEnergy = u_waterWind.z * fetch * arriving;
    float wash = cos(swellMarch) * swellWash * fetch + cos(march) * surfEnergy;
    slope += seaward * (wash * 0.2 * nearBank * effects * (1.0 - wetShore * .9));

    float wading = waterUnitWakes(v_cloudPosition.xy, noise, effects, slope);
    WaterImpact landing = waterFallImpacts(position, shore, noise, phase, vec2(weightA, weightB), effects, slope);
    float boil = landing.boil, bubbles = landing.bubbles, impact = landing.foam;
    vec3 normal = normalize(surfaceNormal + vec3(-slope, 0.0));
    vec3 broadNormal = normalize(surfaceNormal + vec3(-broadSlope, 0.0));

    // Banks: a crisp line where the water touches, never thinner than a pixel so it holds still from afar; the
    // surf washing up and back over a band that pulses with each wave, and the foam it leaves trailing in drifts.
    float pixel = fwidth(shore);
    float contact = smoothstep(0.0, 0.003 + pixel, shore)
          * (1.0 - smoothstep(0.006 + pixel, 0.016 + 2.0 * pixel, shore + noise * 0.012));
    // Foam covers the water densely at the bank and breaks into drifts further out; the band pulses with each
    // wave, varies along the bank and swells again where a roller breaks. Beside running water it holds steady and
    // only its grain drifts downstream: no wind-drifted pattern may travel along a river's bank.
    float bankGust = mix(gust, 0.5, flowing);
    float surge = mix(0.5 + 0.5 * sin(u_rainTime * 1.1 + broad.r * 13.0 + gust * 6.0), 0.5, flowing);
    float width = mix(0.045, 0.11, bankGust) * (0.6 + 0.4 * surge) * (0.7 + 0.6 * broad.b) * mix(0.45, 1.0, fetch);
    float grainy = noise + 0.5;
    float coverage = exp(-shore / width) + 0.4 * smoothstep(0.55, 0.95, sin(march)) * nearBank * surfEnergy;
    float surf = smoothstep(1.0 - coverage, 1.2 - coverage, grainy) * smoothstep(0.0, 0.003, shore);
    // Whitecaps where the simulated crests fold over; the simulation drags their foam out into downwind streaks.
    // Dense where the crest folds, fraying into lace as the foam thins. Seen from afar the lace averages out, so the
    // threshold widens and thin foam fades instead of hardening into solid patches.
    float lace = texture(u_surfaceNoise, vec3(crossing * position * 2.3 + 0.53, WATER_DETAIL)).b;
    float laceEdge = mix(.8, .12, u_rainDetail);
    float whitecap = smoothstep(1.0 - .65 * whitecaps, 1.0 - .65 * whitecaps + laceEdge, mix(grainy, lace, .6))
          * smoothstep(0.0, .35, whitecaps) * mix(.55, 1.0, u_rainDetail);
    // Strong rapids increase motion and the number of broken foam patches, while leaving water between them.
    // The fine grain frays their edges; the caustic network must not turn the entire reach into a white web.
    float threshold = 0.70 - 0.16 * agitation + 0.16 * (0.5 - broad.b);
    float rapids = smoothstep(threshold, threshold + 0.18, rapidDetail.b * 0.8 + small.b * 0.2 + 0.5)
          * smoothstep(0.05, 0.3, agitation);
    // The foam round a unit breaks up like any other: dense where the water piles up, in drifts further out.
    float stirred = smoothstep(1.0 - 1.25 * wading, 1.15 - 1.25 * wading, grainy);
    float foam = clamp(max(max(max(contact * .6, surf * .9) * surfEnergy, max(whitecap, stirred)),
          max(rapids, impact)) * churn, 0.0, 1.0);
    // A thin wash over sand is mostly clear, with only a trace of foam. Use the actual water column so the
    // effect fades around emerging bars and rocks instead of painting the old hex-shaped shoreline white.
    float foamWater = falling ? 1.0 : mix(0.035, 1.0, smoothstep(0.06, 0.45, depth))
          * smoothstep(0.0, 0.006, shore);
    foam *= foamWater * (1.0 - wetShore * .94);

    vec3 kept = waterTransmission(mixture, depth);
    // One optical normal owns the Fresnel budget, direct glint and environment reflection.
    normal = shadingNormal(normal, geometric, view);
    float facing = clamp(dot(normal, view), 0.0, 1.0);
    // Pixel footprint broadens unresolved wave facets. The same roughness filters sky and directional reflection.
    // The footprint filter is always on; specular anti-aliasing adds the mesh's own variance. The larger of the two
    // widenings applies, so the water never widens twice and keeps its look with the setting off.
    vec2 texels = fwidth(u_waterOceanScale.x > 0.0 ? rest * fineUV : position * 2.0) * OCEAN_SIZE;
    float calm = clamp(0.16 + 0.04 * u_waterWind.z, 0.16, 0.45);
    float footprint = clamp(calm + 0.06 * log2(1.0 + max(texels.x, texels.y)), 0.16, 0.45);
    float roughness = max(footprint, antialiasedRoughness(calm, surfaceVariance));
    roughness = mix(roughness, .45, falling ? 0.0 : min(length(surfaceNormal.xy) * 20.0, 1.0));
    // Fires, lava, fungi and weapon fire light the water and glint on the same facets.
    vec3 localGlint = waterLocalLighting(illumination, normal, view, roughness, falling, sheet, sheetRoughness,
          sheetGlint);
    float fresnel = specularAlbedo(facing, roughness, vec3(.02037)).r;
    vec3 reflection = waterEnvironmentReflection(normal, view, roughness, illumination);
    vec3 glint = vec3(0.0);
    vec3 glow = vec3(0.0);
    vec3 facets = illumination.light;
    vec2 key = vec2(-0.6, 0.8);
    // A crest's height over the local sea, relative to what this wind raises: 0 in a trough, 1 on a high crest. Where
    // the swell gathers the surface tightest, the sharp top of the crest, the water is thinnest and glows most.
    float crest = smoothstep(-.1, 1.0, height / (.25 + 1.6 * u_waterWind.z));
#ifdef waterSurfaceFlag
    crest = max(crest, smoothstep(.15, .55, v_waterCrest));
#endif
#if defined(lightingFlag) && numDirectionalLights > 0
    vec3 toSun = -u_dirLights[0].direction;
    key = normalize(toSun.xy + vec2(0.0, 0.001));
    // Wave faces turned toward the sun catch more of it: the waves read even where nothing reflects.
    facets += illumination.sunlight * (max(dot(broadNormal, toSun), 0.0) - toSun.z) * 0.8;
    // The sun's glitter, from facets as rough as the waves a pixel averages: close up each wave flashes, far off
    // their spread becomes a broad path of glitter instead of aliasing into lines.
    // Water running down a slope is broken: a descent spreads the sun's glitter instead of drawing it as a line along
    // its curve. The mesh normal is exactly up on level water, which keeps its sharp glint.
    glint = illumination.sunlight * float(dot(geometric, toSun) > 0.0)
          * waterDirectReflection(normal, toSun, view, roughness);
    // Thin crests let the sun through, brightest when the viewer looks toward it: they glow turquoise like the
    // shallows. Light scattered inside the water lifts every crest a little even with the sun behind the viewer.
    float behind = max(dot(normalize(viewDirection().xy + 1e-5), normalize(toSun.xy + 1e-5)), 0.0);
    glow = waterShallows(mixture) * (illumination.sunlight * (0.15 + 0.85 * behind * behind) * 0.6
          + illumination.light * 0.25) * crest * effects;
#endif
    glint += localGlint;
    // The brighter side of the sky lights the faces tilted toward it, even overcast or seen from straight above.
    facets *= clamp(1.0 - dot(broadSlope, key) * 1.1, 0.6, 1.4);
    vec3 body;
    float bodyAlpha;
    if (procedural) {
        // The thin column of depth-zero water needs visible turquoise as well as absorption; ramp it within the
        // shallows, not across whole game levels. The waterline still fades to clear. Cap opacity and let the bed
        // take the rest of the absorption, so submerged units stay readable.
        float column = max(1.0 - max(max(kept.r, kept.g), kept.b),
              0.3 * smoothstep(0.0, 0.1, depth));
        // Deep water varies a little in tone over a few hexes, as depth and silt do, and troughs run darker than
        // the crests beside them.
        vec3 deep = scatter * mix(0.86, 1.12, broad.r) * mix(0.8, 1.15, crest);
        body = mix(waterShallows(mixture), deep, smoothstep(0.1, 1.6, depth)) * facets * column;
        bodyAlpha = min(column, WATER_MAX_OPACITY);
        // Only a trace of fine suspended variation: broad bright/dark patches would look like giant river waves.
        float clarity = 1.0 + .08 * small.b * running;
        body *= clarity;
        bodyAlpha = min(bodyAlpha * clarity, WATER_MAX_OPACITY);
    } else {
        bodyAlpha = 1.0;
        body = materialReflectance(authored * tint) * facets;
    }
    // Suspended peat/silt joins shallow marsh pools and bare mud to open water over several metres.
    vec3 wetColor = mix(toLinear(vec3(.105, .14, .072)), toLinear(vec3(.24, .195, .105)),
          clamp((habitat.z + habitat.w) / max(dot(habitat.yzw, vec3(1.0)), .0001), 0.0, 1.0));
    body = mix(body, wetColor * facets * bodyAlpha, wetShore);
    body += glow * max(bodyAlpha, 0.35) * (1.0 - wetShore);
    // Raindrop rings: each crest and trough catches the light differently.
    body *= 1.0 + clamp(ripples.z, -1.0, 1.0) * 0.35;
    // Churning water carries air: paler and more opaque long before it breaks into foam; spray thrown up by a
    // fall lights the water around it.
    float aerated = max(agitation * mix(0.10, 0.35, rapids), boil * boil * 0.5) * churn * foamWater;
    body = mix(body, froth * mix(facets, illumination.whiteLight, 0.5) * 0.85, aerated);
    bodyAlpha = mix(bodyAlpha, 0.85, aerated);
    // The column thins to nothing at the bank, so water meets its shore without a drawn edge.
    float edge = smoothstep(0.0, 0.02, shore);
    color = (body * (1.0 - fresnel) + reflection + glint * (1.0 - foam)) * edge;
    alpha = (bodyAlpha * (1.0 - fresnel) + fresnel) * edge;
    // Foam is never flat white: thicker and thinner froth, and bubbles churning below a fall, whose freshly
    // aerated water glows brightest.
    float grain = mix(noise + 0.5, bubbles, boil);
    vec3 foamReflectance = materialReflectance(froth * ((0.78 + 0.34 * grain) * (1.0 + 0.25 * boil)));
    color = mix(color, foamReflectance * illumination.whiteLight, foam);
    alpha = mix(alpha, 1.0, foam);
    float grid = terrainGrid(position);
    color *= grid;
    alpha = 1.0 - (1.0 - alpha) * grid;
    return vec4(color, alpha);
}
