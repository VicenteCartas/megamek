/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * The board's sun shadow map and its caches: when the map is redrawn, and what it reuses (the terrain's map under
 * moving units, the board-wide fit's map, an unchanged projection). GpuTerrain casts into it ({@link Casters}).
 */
final class GpuTerrainShadows implements Disposable {
    /** What casts into the map, from the light's camera: the terrain's own depth pass and the units over it. */
    interface Casters {
        /** The terrain, its trees and objects, and these units. */
        void terrain(Camera light, List<ModelInstance> units);

        /** These units over the terrain already in the map. */
        void units(Camera light, List<ModelInstance> units);

        /** A unit's bounds in the world, which hold all it casts. */
        BoundingBox bounds(ModelInstance unit);

        /** Revision of a deforming caster whose transform and node pose can remain unchanged. */
        default int revision(ModelInstance unit) { return 0; }
    }

    /**
     * The map's texels across follow the scene's pixels (resolution): at least its 1080p size, at most its 4K size, or
     * twice as many with High shadows. {@code -Dmegamek.gpu.shadowTexels} sets one size for every scene.
     */
    static final int MIN_RESOLUTION = 2304, MAX_RESOLUTION = 4608, MAX_HIGH_RESOLUTION = 8192;
    private static final Integer FORCED_RESOLUTION = Integer.getInteger("megamek.gpu.shadowTexels");
    /** Shadow texels a scene pixel takes along the scene's longer side: 2304 for a 1920-pixel scene. */
    private static final float TEXELS_PER_PIXEL = 1.2f;
    /** Frames a changed size must hold before the map follows it (a window being resized, a quality switch). */
    private static final int SETTLE_FRAMES = 30;
    /** Texels on a side of the tiles in which moved units' shadows are restored from the cached terrain map. */
    private static final int SHADOW_TILE = 64;
    /** Copies per restore before one copy of the whole map is the cheaper (an estimate, not measured). */
    private static final int SHADOW_RUNS = 32;
    private static final float SHADOW_GUARD_SCALE = 1.1f;
    /**
     * A refit keeps the map's texel while the view's fit still has this much room in it and needs a texel at most
     * SHADOW_MAX_SCALE finer (the map moves by whole texels only), and a map is kept while its texel is so fine: the
     * texel grid stays put under the casters through pans and orbits and changes only past this band. Fresh maps take
     * the ladder's texel for the guarded fit, 1.1 to 1.124 times the fit's.
     */
    private static final float SHADOW_KEEP_SCALE = 1.02f;
    private static final float SHADOW_MAX_SCALE = 1.2f;
    /** Texel sizes lie on a fixed ladder, this many steps an octave. */
    private static final int SHADOW_LADDER = 32;

    private final Casters casters;
    private final List<Model> shadowModels = new ArrayList<>();
    private final List<Matrix4> shadowTransforms = new ArrayList<>();
    private final com.badlogic.gdx.utils.IntArray shadowPoses = new com.badlogic.gdx.utils.IntArray();
    /** Each drawn unit's footprint in the light's map ({@link #shadowTexels}), as shadowModels. */
    private final List<int[]> shadowRects = new ArrayList<>();
    private final Matrix4 shadowView = new Matrix4();
    private boolean shadowProjectionValid;
    /** The retained map is clear while its directional source is zero; cleared once per such interval. */
    private boolean shadowEmpty;
    private final GpuShadowLight.SlabCamera shadowFit = new GpuShadowLight.SlabCamera();
    private boolean shadowViewPresent;
    private GpuShadowLight shadow;
    /** Allocated only for gameplay casters. Reuses the unchanged terrain depth when units move. */
    private FrameBuffer staticShadow;
    private boolean staticShadowValid;
    /** The cache whose terrain the light's map holds under its units; null after a render no cache holds. */
    private FrameBuffer shadowBase;
    // The fit that covers the whole board (views crossing the horizon or below the highest terrain) is the same
    // every time a tilt returns to it and the costliest to draw: its terrain shadow is kept and copied back.
    private FrameBuffer boardShadow;
    private boolean boardShadowValid;
    private final GpuShadowLight.SlabCamera boardShadowFit = new GpuShadowLight.SlabCamera();
    private boolean shadowBoardWide;
    private boolean shadowDirty;
    private long revision;

    long revision() { return revision; }
    // Frames since a camera refinement last replaced a chunk the cached shadow has not redrawn; -1 when none.
    private int refinedShadowFrames = -1;
    // The battle view's scene (setScene): its longer side in pixels, 0 for none, and High shadows (Display tab); the map's
    // texels across, and a different size asked for with the frames it has held.
    private float scenePixels;
    private boolean high;
    private int resolution = MIN_RESOLUTION, wanted, wantedFrames;
    /** The context's largest texture side, read once. */
    private int maxTexture;

    GpuTerrainShadows(Casters casters) { this.casters = casters; }

    /** Redraws the map at the next pass, its terrain too (GpuTerrain#refreshShadows). */
    void invalidate() { staticShadowValid = false; shadowDirty = true; }

    /** A refined chunk casts its terrain at finer detail: the map serves until refinement settles, then redraws once. */
    void refined() { refinedShadowFrames = 0; }

    /** The map's texels across. */
    int resolution() { return resolution; }

    /**
     * The battle view's scene: {@code pixels} along its longer side as the render resolution draws it, and High shadows
     * (twice the texels a pixel takes). The map follows once they hold (followScene). Prints and previews never set it
     * and keep {@link #MIN_RESOLUTION}, so a window resized during a tiled print cannot change its tiles' shadows.
     */
    void setScene(float pixels, boolean high) {
        scenePixels = pixels;
        this.high = high;
    }

    /**
     * Texels across the map for a scene of {@code pixels} along its longer side: {@link #TEXELS_PER_PIXEL} each, twice
     * that when {@code high}, in whole tiles, from {@link #MIN_RESOLUTION} to {@link #MAX_RESOLUTION} (or
     * {@link #MAX_HIGH_RESOLUTION}) and the largest texture the context allows. The map is fitted to the view, so a
     * texel then covers about as many pixels at every scene size.
     */
    static int resolution(float pixels, boolean high, int maxTexture) {
        int largest = maxTexture / SHADOW_TILE * SHADOW_TILE;
        if (FORCED_RESOLUTION != null) { return tiled(Math.clamp(FORCED_RESOLUTION, SHADOW_TILE, largest)); }
        int most = Math.min(high ? MAX_HIGH_RESOLUTION : MAX_RESOLUTION, largest);
        return Math.max(Math.min(MIN_RESOLUTION, most), Math.min(tiled(pixels * TEXELS_PER_PIXEL * (high ? 2 : 1)), most));
    }

    /** Texels up to whole restore tiles. */
    private static int tiled(float texels) { return (int) Math.ceil(texels / SHADOW_TILE) * SHADOW_TILE; }

    /** The texels across the scene asks for (setScene). */
    private int sceneResolution() {
        if (maxTexture == 0) {
            var values = BufferUtils.newIntBuffer(16);
            Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, values);
            maxTexture = values.get(0);
        }
        return resolution(scenePixels, high, maxTexture);
    }

    /** The light, made on first use, as the environment's shadow map. */
    GpuShadowLight bind(Environment environment) {
        if (shadow == null) {
            resolution = sceneResolution();
            shadow = new GpuShadowLight(resolution, resolution, 1, 1, 1, 2);
            shadowFit.texels = boardShadowFit.texels = resolution;
            shadowProjectionValid = false;
        }
        if (environment.shadowMap != shadow) {
            environment.add(shadow);
            environment.shadowMap = shadow;
        }
        return shadow;
    }

    /** No light at all: the light and its map go. */
    void release(Environment environment) {
        if (shadow != null) {
            environment.remove(shadow);
            shadow.dispose();
            shadow = null;
            shadowEmpty = false;
            // The caches go with the map: the next one may take another size.
            disposeCaches();
        }
        environment.shadowMap = null;
    }

    /**
     * Redraws the map for this view and these units where they changed, from what it may reuse. {@code casterBounds}:
     * what casts, the board's terrain and objects; {@code bound}: the map is the environment's; {@code directLight}:
     * the sun or moon lights the board at all.
     */
    void render(Camera view, List<ModelInstance> units, BoundingBox casterBounds, boolean bound, boolean directLight) {
        if (shadow == null || !bound) {
            return;
        }
        followScene();
        // Refinement has settled: the refined chunks cast their own shadow (replaceDetail).
        if (refinedShadowFrames >= 0 && ++refinedShadowFrames > GpuMeshPage.SETTLE_FRAMES) {
            refinedShadowFrames = -1;
            shadowDirty = true;
        }
        if (!directLight) {
            if (!shadowEmpty) {
                revision++;
                shadow.begin();
                shadow.end();
                shadowBase = null;
                shadowEmpty = true;
                shadowProjectionValid = false;
                staticShadowValid = false;
                boardShadowValid = false;
                shadowDirty = true;
            }
            return;
        }
        shadowEmpty = false;
        // A unit changed since the map last drew it: new, moved or posed anew.
        int[] poses = new int[units.size()];
        boolean[] moved = new boolean[units.size()];
        boolean changed = shadowDirty || !shadowProjectionValid || units.size() != shadowModels.size();
        for (int index = 0; index < units.size(); index++) {
            ModelInstance unit = units.get(index);
            poses[index] = 31 * shadowPose(unit) + casters.revision(unit);
            moved[index] = index >= shadowModels.size() || unit.model != shadowModels.get(index)
                  || !Arrays.equals(unit.transform.val, shadowTransforms.get(index).val) || poses[index] != shadowPoses.get(index);
            changed |= moved[index];
        }
        boolean viewChanged = (view != null) != shadowViewPresent
              || (view != null && !Arrays.equals(view.combined.val, shadowView.val));
        if (!changed && !viewChanged) {
            return;
        }
        BoundingBox bounds = new BoundingBox(casterBounds);
        for (ModelInstance unit : units) { bounds.ext(casters.bounds(unit)); }
        BoundingBox receivers = view == null ? bounds : BoardCamera.viewportBounds(view, bounds);
        // A view that crosses the horizon or sits below the highest terrain needs most of the board, and a tilt
        // there changes the required fit every frame; each refit redrew every chunk and tree (200-430 ms on an
        // Intel Iris Xe on MesaCity 1). Views needing most of the board share the board-wide fit, whose map is
        // cached, and keep it until they need well under half of it, so a tilt cannot alternate between fits.
        float share = receivers.getWidth() * receivers.getHeight() / Math.max(1e-6f, bounds.getWidth() * bounds.getHeight());
        boolean boardWide = share > (shadowBoardWide ? .4f : .7f);
        shadowBoardWide = boardWide;
        fitShadowToReceivers(boardWide ? bounds : receivers, shadowFit, bounds, shadow.direction);
        shadowViewPresent = view != null;
        if (view != null) { shadowView.set(view.combined); }
        Camera lightCamera = shadow.getCamera();
        boolean projectionChanged = !shadowProjectionValid || !canReuseShadowCamera(lightCamera, shadowFit);
        if (!changed && !projectionChanged) { return; }
        shadowProjectionValid = false;
        if (projectionChanged) {
            // Under the same light the texel grid stays where it is in the world (keepsTexel, snapShadowCamera):
            // casters rasterize alike at every refit, so a pan or an orbit does not move their shadows' edges. When
            // either axis must change, both take the ladder's texel at once: one change, not two in a row. The
            // board-wide fit is always the same, the one its cache holds.
            boolean keep = !boardWide && lightCamera.direction.equals(shadowFit.direction)
                  && lightCamera.up.equals(shadowFit.up) && keepsTexel(lightCamera.viewportWidth, shadowFit.viewportWidth)
                  && keepsTexel(lightCamera.viewportHeight, shadowFit.viewportHeight);
            if (!keep) {
                lightCamera.viewportWidth = ladderExtent(shadowFit.viewportWidth);
                lightCamera.viewportHeight = ladderExtent(shadowFit.viewportHeight);
            }
            lightCamera.position.set(shadowFit.position);
            lightCamera.direction.set(shadowFit.direction);
            lightCamera.up.set(shadowFit.up);
            lightCamera.near = shadowFit.near;
            lightCamera.far = shadowFit.far;
            snapShadowCamera((GpuShadowLight.SlabCamera) lightCamera);
        }
        // The tiles the changed units' shadows covered and now cover: within an unchanged projection, only these differ
        // from the cached terrain map under the units (restoreTiles).
        int[][] rects = new int[units.size()][];
        for (int index = 0; index < units.size(); index++) { rects[index] = shadowTexels(lightCamera, casters.bounds(units.get(index))); }
        boolean[] stale = new boolean[tiles() * tiles()];
        for (int index = 0; index < Math.max(units.size(), shadowRects.size()); index++) {
            if (index < units.size() && !moved[index]) { continue; }
            if (index < shadowRects.size()) { markTiles(stale, shadowRects.get(index)); }
            if (index < units.size()) { markTiles(stale, rects[index]); }
        }
        shadowModels.clear();
        shadowPoses.clear();
        shadowRects.clear();
        while (shadowTransforms.size() > units.size()) { shadowTransforms.removeLast(); }
        for (int index = 0; index < units.size(); index++) {
            ModelInstance unit = units.get(index);
            shadowModels.add(unit.model);
            if (index == shadowTransforms.size()) { shadowTransforms.add(new Matrix4()); }
            shadowTransforms.get(index).set(unit.transform);
            shadowPoses.add(poses[index]);
            shadowRects.add(rects[index]);
        }
        // Screen passes disable depth writes; both a fresh map and units over cached depth must write again.
        Gdx.gl.glDepthMask(true);
        int buffers = GL20.GL_DEPTH_BUFFER_BIT;
        if (shadowDirty) { boardShadowValid = false; }
        boolean boardShadowCurrent = boardWide && boardShadowValid && sameFit(boardShadowFit, lightCamera);
        if (shadowDirty || projectionChanged || units.isEmpty() && !staticShadowValid) {
            // Coverage and terrain/light changes pay only the full pass. Populate the cache lazily when
            // units move in a stable view, rather than copying an invalidated cache on every camera frame.
            // Preview/editor maps retain just the original framebuffer, with no copy or extra allocation.
            staticShadowValid = false;
            if (boardShadowCurrent) {
                // A tilt that crosses the horizon or drops below the highest terrain returns to the board-wide fit
                // every time; drawing every chunk and tree again took 220-530 ms on an Intel Iris Xe, a copy a few.
                boardShadow.transfer(shadow.getFrameBuffer(), buffers);
                shadowBase = boardShadow;
                renderUnitShadows(lightCamera, units);
            } else {
                shadow.begin();
                try { casters.terrain(lightCamera, boardWide ? List.of() : units); }
                finally { shadow.end(); }
                shadowBase = null;
                if (boardWide) {
                    if (boardShadow == null) {
                        boardShadow = GpuShadowLight.depthBuffer(resolution, resolution);
                    }
                    shadow.getFrameBuffer().transfer(boardShadow, buffers);
                    remember(boardShadowFit, lightCamera);
                    boardShadowValid = true;
                    shadowBase = boardShadow;
                    renderUnitShadows(lightCamera, units);
                }
            }
        } else {
            if (boardShadowCurrent) {
                restoreTiles(boardShadow, stale);
            } else {
                if (staticShadow == null) {
                    staticShadow = GpuShadowLight.depthBuffer(resolution, resolution);
                }
                if (!staticShadowValid) {
                    shadow.begin();
                    try { casters.terrain(lightCamera, List.of()); }
                    finally { shadow.end(); }
                    shadow.getFrameBuffer().transfer(staticShadow, buffers);
                    staticShadowValid = true;
                    shadowBase = staticShadow;
                } else {
                    // The one depth attachment is both the sampled shadow and the depth test: moved units erase
                    // their old shadows and remain occluded by the cached terrain.
                    restoreTiles(staticShadow, stale);
                }
            }
            renderUnitShadows(lightCamera, units);
        }
        shadowProjectionValid = true;
        shadowDirty = false;
        revision++;
    }

    /**
     * A size held for {@link #SETTLE_FRAMES} frames resizes the map, whose caches go with its depth; this render then
     * draws it whole.
     */
    private void followScene() {
        int next = sceneResolution();
        if (next == resolution) { wantedFrames = 0; return; }
        wantedFrames = next == wanted ? wantedFrames + 1 : 0;
        wanted = next;
        if (wantedFrames < SETTLE_FRAMES) { return; }
        resolution = next;
        wantedFrames = 0;
        shadow.resize(resolution);
        shadowFit.texels = boardShadowFit.texels = resolution;
        disposeCaches();
        // Its depth is undefined until drawn or cleared: a lightless board clears it again.
        shadowEmpty = false;
        shadowProjectionValid = false;
        shadowDirty = true;
    }

    /** The cached terrain maps, sized as the map they were drawn for. */
    private void disposeCaches() {
        if (staticShadow != null) { staticShadow.dispose(); staticShadow = null; }
        if (boardShadow != null) { boardShadow.dispose(); boardShadow = null; }
        shadowBase = null;
        staticShadowValid = boardShadowValid = false;
    }

    /** A unit's footprint in the light's map, in texels from x0, y0 to x1, y1, a texel wider each side. */
    private int[] shadowTexels(Camera lightCamera, BoundingBox bounds) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = x1;
        Vector3 corner = new Vector3();
        for (int i = 0; i < 8; i++) {
            corner.set((i & 1) == 0 ? bounds.min.x : bounds.max.x, (i & 2) == 0 ? bounds.min.y : bounds.max.y,
                  (i & 4) == 0 ? bounds.min.z : bounds.max.z).prj(lightCamera.combined);
            x0 = Math.min(x0, corner.x);
            y0 = Math.min(y0, corner.y);
            x1 = Math.max(x1, corner.x);
            y1 = Math.max(y1, corner.y);
        }
        float half = resolution / 2f;
        return new int[] { Math.clamp((int) Math.floor((x0 + 1) * half) - 1, 0, resolution),
              Math.clamp((int) Math.floor((y0 + 1) * half) - 1, 0, resolution),
              Math.clamp((int) Math.ceil((x1 + 1) * half) + 1, 0, resolution),
              Math.clamp((int) Math.ceil((y1 + 1) * half) + 1, 0, resolution) };
    }

    /** The map's restore tiles across. */
    private int tiles() { return resolution / SHADOW_TILE; }

    private void markTiles(boolean[] tiles, int[] texels) {
        int across = tiles();
        int top = Math.min(across, (texels[3] + SHADOW_TILE - 1) / SHADOW_TILE);
        int right = Math.min(across, (texels[2] + SHADOW_TILE - 1) / SHADOW_TILE);
        for (int y = texels[1] / SHADOW_TILE; y < top; y++) {
            for (int x = texels[0] / SHADOW_TILE; x < right; x++) { tiles[y * across + x] = true; }
        }
    }

    /**
     * The cached terrain map back over the stale tiles, a run of them in a row per copy, instead of the whole map (2304
     * by 2304 depth texels or more) whenever a unit moves. The whole map in one copy where the map holds another cache's terrain
     * (the two may hold different levels of detail), past half the map, or past SHADOW_RUNS copies.
     */
    private void restoreTiles(FrameBuffer cache, boolean[] stale) {
        var runs = new com.badlogic.gdx.utils.IntArray();
        int marked = 0;
        int across = tiles();
        for (int y = 0; y < across; y++) {
            for (int x = 0; x < across; x++) {
                if (!stale[y * across + x]) { continue; }
                int end = x;
                while (end + 1 < across && stale[y * across + end + 1]) { end++; }
                runs.addAll(x * SHADOW_TILE, y * SHADOW_TILE, (end + 1) * SHADOW_TILE, (y + 1) * SHADOW_TILE);
                marked += end + 1 - x;
                x = end;
            }
        }
        if (cache != shadowBase || marked * 2 > stale.length || runs.size / 4 > SHADOW_RUNS) {
            cache.transfer(shadow.getFrameBuffer(), GL20.GL_DEPTH_BUFFER_BIT);
        } else if (!runs.isEmpty()) {
            GpuShadowLight.copyDepth(cache, shadow.getFrameBuffer(), runs);
        }
        shadowBase = cache;
    }

    /** The units' shadows over a terrain shadow already in the light's framebuffer. */
    private void renderUnitShadows(Camera lightCamera, List<ModelInstance> units) {
        if (units.isEmpty()) { return; }
        shadow.getFrameBuffer().begin();
        try {
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(1, 1, resolution - 2, resolution - 2);
            GpuShadowLight.depthClamp(true);
            casters.units(lightCamera, units);
        } finally {
            GpuShadowLight.depthClamp(false);
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            shadow.getFrameBuffer().end();
        }
    }

    private static boolean sameFit(Camera a, Camera b) {
        return a.position.equals(b.position) && a.direction.equals(b.direction) && a.up.equals(b.up)
              && a.viewportWidth == b.viewportWidth && a.viewportHeight == b.viewportHeight && a.near == b.near && a.far == b.far;
    }

    private static void remember(Camera target, Camera source) {
        target.position.set(source.position);
        target.direction.set(source.direction);
        target.up.set(source.up);
        target.viewportWidth = source.viewportWidth;
        target.viewportHeight = source.viewportHeight;
        target.near = source.near;
        target.far = source.far;
    }

    private static int poseHash(Iterable<Node> nodes) {
        int result = 1;
        for (Node node : nodes) {
            result = 31 * result + Arrays.hashCode(node.globalTransform.val);
            for (var part : node.parts) {
                result = 31 * result + (part.enabled ? 1 : 0);
            }
            result = 31 * result + poseHash(node.getChildren());
        }
        return result;
    }

    private static int shadowPose(ModelInstance instance) {
        return 31 * poseHash(instance.nodes) + (instance instanceof GpuUnitInstance unit ? unit.detailRevision() : 0);
    }

    /** Focus texels on visible receivers, retaining the full light depth for offscreen shadow casters. */
    static void fitShadowCamera(Camera view, GpuShadowLight.SlabCamera target, BoundingBox bounds, Vector3 direction) {
        fitShadowToReceivers(view == null ? bounds : BoardCamera.viewportBounds(view, bounds), target, bounds, direction);
    }

    /** Focus texels on these receivers, retaining the full light depth for offscreen shadow casters. */
    static void fitShadowToReceivers(BoundingBox receivers, GpuShadowLight.SlabCamera target, BoundingBox bounds,
          Vector3 direction) {
        target.direction.set(direction).nor();
        Vector3 right = new Vector3(target.direction)
              .crs(Math.abs(target.direction.z) > 0.99f ? Vector3.Y : Vector3.Z).nor();
        target.up.set(right).crs(target.direction).nor();
        BoundingBox lightSpace = new BoundingBox().inf();
        float near = Float.POSITIVE_INFINITY;
        float far = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Vector3 point = new Vector3((corner & 1) == 0 ? receivers.min.x : receivers.max.x,
                  (corner & 2) == 0 ? receivers.min.y : receivers.max.y,
                  (corner & 4) == 0 ? receivers.min.z : receivers.max.z);
            lightSpace.ext(point.dot(right), point.dot(target.up), 0);
            point.set((corner & 1) == 0 ? bounds.min.x : bounds.max.x,
                  (corner & 2) == 0 ? bounds.min.y : bounds.max.y,
                  (corner & 4) == 0 ? bounds.min.z : bounds.max.z);
            float depth = point.dot(target.direction);
            near = Math.min(near, depth);
            far = Math.max(far, depth);
        }
        // Leave a filtering border, and align the light grid to texels to avoid crawling edges while panning.
        float border = 1 + 4f / target.texels;
        target.viewportWidth = Math.max(1, lightSpace.getWidth()) * border;
        target.viewportHeight = Math.max(1, lightSpace.getHeight()) * border;
        Vector3 center = lightSpace.getCenter(new Vector3());
        target.position.set(right).scl(center.x).mulAdd(target.up, center.y);
        if (GpuShadowLight.slab(target.direction)) {
            // Depth runs down all casters' heights, onscreen or not, from a world unit above the highest to one below
            // the lowest (GpuShadowLight.SlabCamera).
            target.near = bounds.max.z + 1;
            target.far = bounds.min.z - 1;
        } else {
            // Lower than the world sun ever is, along the light over all casters, onscreen or not.
            target.position.mulAdd(target.direction, near - 1);
            target.near = 1;
            target.far = far - near + 2;
        }
        snapShadowCamera(target);
    }

    private static void snapShadowCamera(GpuShadowLight.SlabCamera target) {
        Vector3 right = new Vector3(target.direction).crs(target.up).nor();
        float x = target.position.dot(right), y = target.position.dot(target.up);
        float texelX = target.viewportWidth / target.texels;
        float texelY = target.viewportHeight / target.texels;
        target.position.mulAdd(right, Math.round(x / texelX) * texelX - x)
              .mulAdd(target.up, Math.round(y / texelY) * texelY - y);
        target.update();
    }

    /** Keep the existing projection only while it covers all receivers/caster depths at the required detail. */
    static boolean canReuseShadowCamera(Camera cached, Camera required) {
        if (!cached.direction.equals(required.direction) || !cached.up.equals(required.up)
              || cached.viewportWidth > required.viewportWidth * SHADOW_MAX_SCALE
              || cached.viewportHeight > required.viewportHeight * SHADOW_MAX_SCALE) { return false; }
        Vector3 point = new Vector3();
        for (Vector3 corner : required.frustum.planePoints) {
            point.set(corner).prj(cached.combined);
            // The required fit already includes the PCF border. Allow only floating-point depth roundoff.
            if (Math.abs(point.x) > 1 || Math.abs(point.y) > 1 || Math.abs(point.z) > 1.00001f) { return false; }
        }
        return true;
    }

    /** Whether the light's extent across one axis still serves a fit needing {@code required} there. */
    private static boolean keepsTexel(float current, float required) {
        return current >= required * SHADOW_KEEP_SCALE && current <= required * SHADOW_MAX_SCALE;
    }

    /** The light's extent across one axis for a fit needing {@code required}: the guarded fit's, its texel on the ladder. */
    private float ladderExtent(float required) {
        double steps = Math.ceil(Math.log(required * SHADOW_GUARD_SCALE / resolution) / Math.log(2) * SHADOW_LADDER);
        return (float) Math.pow(2, steps / SHADOW_LADDER) * resolution;
    }

    @Override
    public void dispose() {
        if (staticShadow != null) { staticShadow.dispose(); }
        if (boardShadow != null) { boardShadow.dispose(); }
        if (shadow != null) {
            shadow.dispose();
        }
    }
}
