// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Surface-only treatment: reeds/crops stand on this same terrain; these small wet depressions do not change rules.
// Earth shares the already-bound terrain array; projection and metre scale are unchanged.
uniform float u_biomeSoilLayer;
uniform float u_biomeSoilTile;
// One shared material for ground mud and natural bridge mud: colour/height + packed normal XY/roughness/AO.
uniform vec2 u_mudMaterial;

// Explicit projection gradients keep texture mip selection valid at wetland/field edges and between slope modes.
vec4 biomeTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
    return textureGrad(maps, vec3(uv, layer), gradient[0], gradient[1]);
}
vec4 biomeTexel(float layer, vec2 uv, mat2 gradient) {
    return biomeTexel(u_terrainLayers, layer * .5, uv, gradient);
}
vec4 biomePlanar(sampler2DArray maps, float map, MaterialProjection p, float mixer, bool normalMap) {
    float tile = u_biomeSoilTile;
    vec4 near = biomeTexel(maps, map, p.top / tile, p.topGradient / tile);
    // Relief: the first repeat only.
    if (normalMap) return vec4(near.rgb * 2.0 - 1.0, near.a);
    vec4 far = mixer > 0.0 ? biomeTexel(maps, map, TURN * p.top / (tile * 2.37) + .31, TURN * p.topGradient / (tile * 2.37))
          : near;
    return mix(near, far, mixer);
}
vec4 biomePlanar(float map, MaterialProjection p, float mixer, bool normalMap) {
    if (normalMap) return biomePlanar(u_terrainData, (map - 1.0) * .5, p, mixer, true);
    return biomePlanar(u_terrainLayers, map * .5, p, mixer, false);
}
vec4 biomeSoil(MaterialProjection p, float mixer) {
    if (p.lying >= 1.0) return biomePlanar(u_biomeSoilLayer, p, mixer, false);
    float tile = u_biomeSoilTile;
    vec4 steep = mix(biomeTexel(u_biomeSoilLayer, p.y / tile, p.yGradient / tile),
          biomeTexel(u_biomeSoilLayer, p.x / tile, p.xGradient / tile), p.side);
    return p.lying <= 0.0 ? steep : mix(steep, biomePlanar(u_biomeSoilLayer, p, mixer, false), p.lying);
}
vec3 biomeSoilNormal(MaterialProjection p, vec3 face, float mixer) {
    if (p.lying >= 1.0) return upNormal(biomePlanar(u_biomeSoilLayer + 1.0, p, mixer, true).rgb, face);
    float tile = u_biomeSoilTile;
    vec3 steep = wallNormal(biomeTexel(u_terrainData, u_biomeSoilLayer * .5, p.x / tile, p.xGradient / tile),
          biomeTexel(u_terrainData, u_biomeSoilLayer * .5, p.y / tile, p.yGradient / tile), face, p.side);
    if (p.lying <= 0.0) return normalize(steep);
    return normalize(mix(steep, upNormal(biomePlanar(u_biomeSoilLayer + 1.0, p, mixer, true).rgb, face), p.lying));
}

// BoardFields in GLSL: the field code of the parcel at p (metres), and the metres from p to that parcel's nearest edge.
float fieldStripEdge(int strip) { return 90.0 * (float(strip) + .5 * (biomeHash(ivec2(strip * 7 + 3, 41)) - .5)); }
float fieldCutEdge(int strip, int cut, float spacing) {
    return spacing * (float(cut) + .5 * (biomeHash(ivec2(strip * 5 + 2, cut * 3 - 7)) - .5));
}
int fieldParcel(vec2 p, out float edge) {
    int strip = int(floor(p.x / 90.0));
    if (p.x < fieldStripEdge(strip)) strip--;
    else if (p.x >= fieldStripEdge(strip + 1)) strip++;
    float normalX = biomeHash(ivec2(strip * 5 - 2, 17)) < .5 ? -.5 : .5;
    float spacing = 75.0 * (.75 + .5 * biomeHash(ivec2(strip * 3 - 1, 9)));
    float c = p.x * normalX + p.y * .8660254;
    int cut = int(floor(c / spacing));
    if (c < fieldCutEdge(strip, cut, spacing)) cut--;
    else if (c >= fieldCutEdge(strip, cut + 1, spacing)) cut++;
    edge = min(min(p.x - fieldStripEdge(strip), fieldStripEdge(strip + 1) - p.x),
          min(c - fieldCutEdge(strip, cut, spacing), fieldCutEdge(strip, cut + 1, spacing) - c));
    float pick = biomeHash(ivec2(strip * 13 + 1, cut * 7 - 3));
    int crop = pick < .5 ? 0 : 1;
    return crop | (biomeHash(ivec2(strip * 3 + 11, cut * 5 + 1)) < .5 ? 0 : normalX > 0.0 ? 1 : 2) << 1;
}

// The biome cover at a face (biomeCoverage) as the stage lays it, gated by slope and water: cover where crops and pools
// stand, fringe the broader deposits round them.
void biomeCover(vec3 world, vec3 face, bool shore, float above, float foot, float footprint, out vec4 cover,
      out vec4 fringe, out float tundra) {
    biomeCoverage(world, 14.0, footprint, cover, fringe, tundra);
    cover.yzw *= min(3.0, 1.0 / max(dot(cover.yzw, vec3(1.0)), .0001));
    // Broad reach on level ground connects shore patches. On a draining face retain the graded coverage;
    // amplifying it to a solid mask makes isolated peat fingers look pasted onto the neighbouring turf.
    float spread = mix(1.4, 3.0, smoothstep(.70, .97, face.z));
    fringe.yzw *= min(spread, 1.0 / max(dot(fringe.yzw, vec3(1.0)), .0001));
    float growing = smoothstep(.55, .92, face.z);
    // At the toe, deposited earth/peat meets the neighbouring floor even on a steep face.
    float bank = max(smoothstep(.08, .80, face.z), 1.0 - smoothstep(.1, 1.1, foot));
    cover *= growing;
    fringe *= bank;
    // Cultivation belongs on the plateau. The existing grass/soil slope material remains visible as the bank turns.
    cover.x *= smoothstep(.85, .98, face.z);
    fringe.x *= smoothstep(.65, .97, face.z);
    // Only wet sediment reaches into shallow water. Crops stay on dry land, and deeper beds retain their material.
    if (shore) {
        cover.x = 0.0;
        cover *= smoothstep(-1.1, -.15, above);
        fringe.x = 0.0;
        fringe *= smoothstep(-1.1, -.15, above);
    }
    // The bank's broad sediment reach must not flood neighbouring cultivated rows.
    fringe = max(fringe, cover);
    cover.yzw *= 1.0 - cover.x;
    fringe.yzw *= (1.0 - fringe.x) * min(1.0, 1.0 / max(dot(fringe.yzw, vec3(1.0)), .0001));
}

// A field's bounce light: its earth's.
const vec3 FIELD_BOUNCE = vec3(.17, .15, .065);

// How far the view has thinned a field's plants out (cropPlants): 0 where they stand whole. The hex's projected size is
// the plants' own, across the screen, where a view tilting away does not foreshorten the ground.
float cropThinned(vec3 worldDx) { return 1.0 - cropPlants(CROP_HEX_METRES / max(length(worldDx.xy), 1e-4)); }

// How much of a field's ground the stand's own colour from above takes where its plants are thinned out. Standing crops
// keep clear of the headland by their own reach, and of the broad margin, which is weathered cultivated earth.
float fieldCanopy(float thinned, float headland, vec4 cover, vec4 fringe) {
    float soft = max(.4, pixelMetres);
    return thinned * smoothstep(1.6 - soft, 2.8 + soft, headland) * cover.x / max(fringe.x, .0001) * .95;
}

// The stand's colour from above (terrain-crops.glsl), as the view tilts. Where it shows, a pixel spans .75 m or more
// (CROP_FADE): no finer grain.
vec3 fieldCrop(bool maize, float broad) {
    float incline = viewDirection().z;
    return cropCanopy(maize, broad, sqrt(max(0.0, 1.0 - incline * incline)));
}

// A cached top's field (GpuMaterialCache): the fill holds its tilled ground and the field's share of it (`amount`).
// Grass and bounce light follow that share, and where the view thins the plants out the stand's colour takes it, as
// biomeSurface lays them.
void cachedField(vec3 world, vec3 face, vec3 worldDx, float foot, float amount, inout vec3 color, inout float grass,
      inout vec3 bounce) {
    if (amount < .0001) return;
    grass *= 1.0 - amount;
    bounce = mix(bounce, FIELD_BOUNCE, amount);
    float thinned = cropThinned(worldDx);
    if (thinned <= 0.0) return;
    float headland;
    int code = fieldParcel(world.xy, headland);
    vec4 cover, fringe;
    float tundra;
    biomeCover(world, face, false, 99.0, foot, 0.0, cover, fringe, tundra);
    float canopy = fieldCanopy(thinned, headland, cover, fringe);
    if (canopy <= 0.0) return;
    color = mix(color, fieldCrop((code & 1) == 1, biomeNoise(world.xy / 4.7 + 13.0)), canopy * amount);
}

// `field`: the field's share of the ground (its tilled earth's), which the cache fill keeps for cachedField.
void biomeSurface(vec3 world, vec3 face, bool shore, float above, float foot, float rim, float materialHeight, float sand,
      float deckMud, float region, inout vec3 color, inout vec3 normal, inout float cavity, inout Finish finish,
      inout float grass, inout vec3 bounce, out float pool, out float damp, out float field) {
    pool = 0.0; damp = 0.0; field = 0.0;
    // Capture every quad derivative before any spatial return/branch. Projection already contains world XYZ.
    MaterialProjection projection = materialProjection(world, face);
    vec3 worldDx = vec3(projection.topGradient[0] * vec2(1.0, -1.0), -projection.xGradient[0].y);
    vec3 worldDy = vec3(projection.topGradient[1] * vec2(1.0, -1.0), -projection.xGradient[1].y);
    vec2 rippleDx = worldDx.xy * .20, rippleDy = worldDy.xy * .20;
    if (shore && above <= -1.1) return;
    // The sculpted waterline can recede well inside the water hex. Reach it with sediment, not a hex-edge stripe.
    // Both sides of the bank use the same field. A different width/normal gate on water-owned triangles
    // leaves straight wedges wherever the sculpted shoreline crosses the original hex boundary.
    vec4 cover, fringe;
    float tundra;
    biomeCover(world, face, shore, above, foot, length(abs(worldDx.xy) + abs(worldDy.xy)), cover, fringe, tundra);
    if (!shore) { tundraSurface(world, face, tundra, color, normal, cavity, finish, grass, bounce); }
    if (family(4.0)) return;
    // Deck mud belongs at the bridge's height, including material sliding over its rock shell. Apply it after
    // ground-mask spreading and slope gates: those would amplify the fade or remove deposits on vertical sides.
    cover.w = max(cover.w, deckMud);
    fringe.w = max(fringe.w, deckMud);
    float strength = max(max(fringe.x, fringe.y), max(fringe.z, fringe.w));
    if (strength < .0001) return;
    float broad = biomeNoise(world.xy / 4.7 + 13.0);
    float fine = biomeNoise(world.xy * 12.0);
    float grain = mix(fine, .5, smoothstep(.03, .15, pixelMetres));
    if (fringe.x > 0.0) {
        // Each parcel has its crop, wheat or maize, and its row direction (BoardFields). Parcels meet at grass
        // headlands, their width kept to a pixel at least.
        float headland;
        int code = fieldParcel(world.xy, headland);
        float soft = max(.4, pixelMetres);
        float tilled = smoothstep(1.2 - soft, 1.2 + soft, headland);
        float angle = float(code >> 1) * 1.0471976;
        vec2 direction = vec2(cos(angle), sin(angle));
        float row = dot(world.xy, direction) / 1.15;
        float rowWidth = (abs(dot(worldDx.xy, direction)) + abs(dot(worldDy.xy, direction))) / 1.15;
        float visible = 1.0 - smoothstep(.25, .8, rowWidth);
        float ridge = .5 + .5 * cos(row * 6.2831853);
        // Between the plants the ground is tilled earth. As they fade (cropPlants), it takes the stand's own colour from
        // above (fieldCanopy), under the billboards too, which fade later; never in the cache's fill, which holds the
        // ground as the close view shows it.
        vec4 soil = biomePlanar(u_biomeSoilLayer, projection, broad, false);
        vec3 earth = cropEarth(soil.a) * mix(.78, 1.20, grain);
        float amount = materialWeights(vec4(1.0 - fringe.x, fringe.x, 0.0, 0.0),
              vec4(materialHeight, soil.a, 0.0, 0.0)).y;
        amount *= tilled;
        field = amount;
#ifndef materialFillFlag
        float canopy = fieldCanopy(cropThinned(worldDx), headland, cover, fringe);
        if (canopy > 0.0) earth = mix(earth, fieldCrop((code & 1) == 1, broad), canopy);
#endif
        color = mix(color, earth, amount);
        finish = mixFinish(finish, planarFinish(u_biomeSoilLayer, projection.top, projection.topGradient, u_biomeSoilTile,
              broad), amount);
        if (u_normalMaps > .5) {
            vec3 soilNormal = upNormal(biomePlanar(u_biomeSoilLayer + 1.0, projection, broad, true).rgb, face);
            normal = normalize(mix(normal, soilNormal, amount));
        }
        normal = normalize(normal + vec3(direction * sin(row * 6.2831853) * .3 * visible * cover.x * tilled, 0.0));
        cavity *= 1.0 - cover.x * tilled * (1.0 - ridge) * .30 * visible;
        grass *= 1.0 - amount;
        bounce = mix(bounce, FIELD_BOUNCE, amount);
    }
    float wetland = fringe.y + fringe.z;
    if (wetland > 0.0) {
        vec3 wetness = biomeWetnessGradient(world.xy);
        float wet = wetness.x;
        float quick = fringe.z / wetland;
        float bare = fringe.z / wetland;
        float aa = max(.003, abs(dot(wetness.yz, worldDx.xy)) + abs(dot(wetness.yz, worldDy.xy)));
        float emerged = shore ? smoothstep(-.10, -.01, above) : 1.0;
        float supported = clamp((cover.y + cover.z) / wetland, 0.0, 1.0);
        // Moss can anchor to saturated banks between marsh levels; reflecting pools still need level support.
        float anchored = max(supported, smoothstep(.15, .80, face.z) * .9);
        float hummock = smoothstep(.50, .67, wet) * (1.0 - bare) * emerged * anchored;
        float water = (1.0 - smoothstep(.39 - aa, .43 + aa, wet)) * (1.0 - bare * .45 - quick * .2);
        water *= supported * smoothstep(.94, .995, face.z);
        // A draining lip breaks into peat shelves before the drop. Leave uphill/shore waterlines level.
        if (!shore) {
            // Low-step rim distances refer to the original seam through the bank, not its receded plateau lip.
            float retreat = mix(2.5, 7.0, biomeNoise(world.xy / 2.0 + 19.0));
            water *= smoothstep(retreat * .2, retreat, rim);
        }
        // Reuse actual soil relief rather than replacing it with smooth coloured noise.
        vec4 soil = biomeSoil(projection, broad);
        // Use the same texture-height competition as grass/soil contacts, preserving the native slope's detail.
        float deposits = materialWeights(vec4(1.0 - wetland, wetland, 0.0, 0.0),
              vec4(materialHeight, soil.a, 0.0, 0.0)).y;
        // A bank drains into a damp apron before peat replaces its turf. Keep texture-height detail within
        // that gradual saturation change, rather than cutting a dark, solid tongue into dry grass.
        float draining = 1.0 - smoothstep(.82, .98, face.z);
        float saturation = smoothstep(0.0, .75, wetland) * draining;
        wetland = mix(deposits, wetland, draining * .55);
        float crumbs = mix(.60, 1.38, soil.a) * mix(.80, 1.20, grain);
        vec3 peat = mix(toLinear(vec3(.20, .16, .105)), toLinear(vec3(.32, .275, .185)), broad) * crumbs;
        vec3 silt = mix(toLinear(vec3(.29, .25, .175)), toLinear(vec3(.40, .345, .245)), broad) * crumbs;
        vec3 moss = mix(toLinear(vec3(.20, .225, .085)), toLinear(vec3(.33, .315, .16)), broad) * crumbs;
        vec3 mud = mix(peat, silt, quick);
        // SAND can coexist with swamp/mud. Keep damp mineral grains and ripples on its exposed margins.
        mud = mix(mud, color * .68, sand);
        float depth = 1.0 - smoothstep(.22, .455, wet);
        vec3 shallow = mix(mud * .82, toLinear(vec3(.13, .16, .13)), depth * .83);
        float algae = smoothstep(.52, .66, broad) * smoothstep(.39, .44, wet) * (1.0 - bare);
        // Small duckweed rafts follow the sheltered margins, leaving the centres visibly open.
        algae *= smoothstep(.48, .64, grain) * .72;
        shallow = mix(shallow, moss, algae);
        vec3 marsh = mix(mix(mud, moss, hummock), shallow, water);
        color *= 1.0 - saturation * (1.0 - wetland) * .25;
        color = mix(color, marsh, wetland);
        pool = water * (1.0 - algae) * wetland * emerged;
        damp = max(saturation * .60, wetland * mix(.85, .28, hummock));
        // Saturated peat is porous and fibrous, not a continuous glossy water coating. Moss and duckweed
        // retain their rough surface; only the exposed pool mask below receives the water BRDF.
        float peatRoughness = mix(.80, .72, quick) + (grain - .5) * .06;
        finish.roughness = mix(finish.roughness, mix(peatRoughness, .93, max(hummock, algae * water)), wetland);
        coverFinish(finish, wetland);
        // Low hummocks alter the shading normal without moving the shared picking/support surface.
        float bumpT = clamp((wet - .45) / (.64 - .45), 0.0, 1.0);
        vec2 bumpGradient = wetness.yz * (.23 * 6.0 * bumpT * (1.0 - bumpT) / (.64 - .45));
        float area = dot(worldDx, cross(worldDy, face));
        vec3 gradient = (cross(worldDy, face) * dot(bumpGradient, worldDx.xy)
              + cross(face, worldDx) * dot(bumpGradient, worldDy.xy)) / max(abs(area), .00001) * sign(area);
        if (u_normalMaps > .5) {
            vec3 soilNormal = biomeSoilNormal(projection, face, broad);
            normal = normalize(mix(normal, soilNormal, wetland * (1.0 - sand)));
        }
        normal = normalize(normal - gradient * wetland * (1.0 - bare));
        // The water's eased wind and integrated drift (GpuOcean): a change of wind never makes the ripples jump. The
        // swell's crests keep one world direction, as turning them would sweep them across the board.
        if (pool > 0.0) {
            vec2 ripple = vec2(0.0);
            float rippleDetail = 1.0 - smoothstep(.15, .55, pixelMetres);
            if (rippleDetail > 0.0) {
                vec2 drift = u_waterDrift * .015;
                float windStrength = u_waterWind.z * mix(.35, 1.0, bare);
                vec2 axis = vec2(.8, .6);
                ripple = (textureGrad(u_waterDetail, world.xy * .20 - drift, rippleDx, rippleDy).rg - .5)
                      * (.012 + windStrength * .035)
                      + axis * sin(dot(world.xy, axis) * 5.0 - u_rainTime * 1.6) * windStrength * .012
                      + rainRipples(world.xy * .03) * .035;
                ripple *= rippleDetail;
            }
            // Distant pools still flatten the hummock normal even when their ripples are subpixel.
            normal = normalize(mix(normal, normalize(vec3(-ripple, 1.0)), pool));
        }
        cavity = mix(mix(cavity, .78 + .22 * soil.a, wetland), 1.0, pool);
        grass *= 1.0 - wetland;
        bounce = mix(bounce, vec3(.09, .10, .045), wetland);
    }
    if (fringe.w > 0.0) {
        // World UVs do not restart at the bank, deck or rim. Height, pools, pigment and relief all come from the PTEX.
        PackedTopCoordinates uv = packedTopCoordinates(projection, u_mudMaterial.y, region);
        vec4 mud = packedTopColor(u_mudMaterial.x, uv);
        float amount = materialWeights(vec4(1.0 - fringe.w, fringe.w, 0.0, 0.0),
              vec4(materialHeight, mud.a, 0.0, 0.0)).y;
        if (amount > 0.0) {
            vec3 detail;
            vec2 surface = packedTopSurface(u_mudMaterial.x, uv, detail);
            color = mix(color, mud.rgb, amount);
            float roughness = sqrt(mix(finish.roughness * finish.roughness, surface.x * surface.x, amount));
            finish = mixFinish(finish, packedTopFinish(u_mudMaterial.x, uv), amount);
            finish.roughness = roughness;
            cavity = mix(cavity, surface.y, amount);
            if (u_normalMaps > .5) {
                normal = normalize(mix(normal, upNormal(detail, face), amount));
            }
            // Only the authored shallow basins have a water film; the same wet clods remain rough on both surfaces.
            float puddle = (1.0 - smoothstep(.14, .20, mud.a)) * smoothstep(.94, .995, face.z);
            pool = mix(pool, puddle, amount);
            damp = max(damp, amount);
            grass *= 1.0 - amount;
            bounce = mix(bounce, mud.rgb, amount);
        }
    }
}
