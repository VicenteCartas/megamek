/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/** Cosmetic spores derived from the installed mushroom props; no independent placement or simulation state. */
final class GpuFungus implements Disposable {
    private static final int PUFFS = 8;
    private static final int CAPACITY = 4096;
    private final GpuEffectBatch mist = new GpuEffectBatch(CAPACITY, "fungus-spores");
    private final List<Emitter> visible = new ArrayList<>();
    private final Vector3 point = new Vector3();

    /** Owned by the terrain chunk, replaced with its props. Radius and phase survive camera/LOD changes. */
    record Emitter(Vector3 origin, float radius, float rise, float phase, boolean cloud) { }

    static List<Emitter> emitters(String asset, Matrix4 transform) {
        var vents = BoardFungus.vents(asset);
        if (vents.isEmpty()) { return List.of(); }
        // Basis lengths retain nonuniform authored scale after rotation; matrix row lengths mix the axes.
        float spread = Math.min(Vector3.X.cpy().rot(transform).len(), Vector3.Y.cpy().rot(transform).len());
        float height = Vector3.Z.cpy().rot(transform).len();
        return vents.stream().map(vent -> source(vent.origin().cpy().mul(transform),
              Math.max(.25f * BoardGeometry.hexScale(), vent.radius() * spread), vent.radius() * height * 6, false)).toList();
    }

    /** One low cloud per occupied hex, derived from its installed large mushrooms, regardless of woods density. */
    static Emitter cloud(Coords coords, BoundingBox colony) {
        Vector3 origin = BoardGeometry.center(coords, 0);
        origin.z = colony.min.z + colony.getDepth() * .25f;
        float radius = BoardGeometry.height() * .34f;
        return source(origin, radius, radius * .18f, true);
    }

    private static Emitter source(Vector3 origin, float radius, float rise, boolean cloud) {
        float phase = MathUtils.sin(origin.x * .73f + origin.y * 1.21f + origin.z * .37f) * 437.58f;
        return new Emitter(origin, radius, rise, phase - (float) Math.floor(phase), cloud);
    }

    /** All fungal forms spill light, including the tiny scatter that never releases mist. */
    static GpuLavaLighting.Source light(int boardHeight, Coords coords, String asset, BoundingBox bounds) {
        if (!BoardFungus.asset(asset)) { return null; }
        Vector3 origin = bounds.getCenter(new Vector3());
        boolean hanging = asset.equals("fungus/cliff-mycelium");
        if (!hanging) { origin.z = bounds.min.z + bounds.getDepth() * .65f; }
        float size = Math.max(bounds.getWidth(), bounds.getHeight()) * 1.1f;
        Vector3 color = hanging ? new Vector3(.12f, .8f, .9f) : new Vector3(1.1f, .3f, .18f);
        return new GpuLavaLighting.Source(coords.getX() * boardHeight + coords.getY(), origin,
              BoardFungus.SCATTER.contains(asset) ? .22f : 1.25f, color, size);
    }

    void begin() { visible.clear(); }

    void add(List<Emitter> sources, Camera camera) {
        for (Emitter source : sources) {
            float reach = source.radius() * 2 + (source.cloud() ? 0 : source.rise());
            if (camera.frustum.sphereInFrustum(source.origin(), reach)
                  && source.radius() * BoardCamera.pixelsPerUnit(camera, source.origin()) > 1.5f) {
                visible.add(source);
            }
        }
    }

    void render(Camera camera, float seconds, Vector3 wind) {
        mist.begin();
        // Alpha-blended mist shares the scene depth. Sorting the small overlapping plumes prevents dark seams.
        visible.sort(Comparator.comparingDouble((Emitter e) -> e.origin().dot(camera.direction)).reversed());
        // At the batch limit retain the nearest colonies, then draw those in back-to-front order.
        for (int index = Math.max(0, visible.size() - CAPACITY / PUFFS); index < visible.size(); index++) {
            Emitter source = visible.get(index);
            float pixels = source.radius() * BoardCamera.pixelsPerUnit(camera, source.origin());
            float detail = MathUtils.clamp((pixels - 1.5f) / 5, 0, 1);
            for (int puff = 0; puff < PUFFS; puff++) {
                float seed = source.phase() * 19 + puff * 2.399963f;
                float age = seconds / (7 + source.phase() * 3) + source.phase() + puff / (float) PUFFS;
                age -= (float) Math.floor(age);
                float radius = source.radius();
                // Broad cloudlets mill around the hex; cup wisps expand and fade above their opening.
                float drift = source.cloud() ? radius * .6f : age * radius * .18f;
                point.set(source.origin()).add(
                      MathUtils.cos(seed + age * .7f) * drift + wind.x * wind.z * drift * age * .3f,
                      MathUtils.sin(seed + age * .7f) * drift + wind.y * wind.z * drift * age * .3f,
                      age * source.rise());
                float fade = MathUtils.sin(age * MathUtils.PI);
                float size = source.cloud() ? .85f + age * .4f : .35f + age * .9f;
                float alpha = source.cloud() ? .16f * fade * fade : .22f * fade;
                mist.billboard(camera, point, radius * size,
                      1 + (int) (seed * 7) + age, alpha * detail);
            }
        }
        mist.render(camera, mist.size());
    }

    int particles() { return mist.size(); }

    @Override
    public void dispose() { mist.dispose(); visible.clear(); }

    /** Links its program now (GpuTerrain.prepareEffects), not when spores first come into view. */
    void prepare() { mist.prepare(); }
}
