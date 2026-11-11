/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Configuration;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardFlag;
import megamek.logging.MMLogger;

/** A page-sized, disposable preview queue: CPU model decoding off-thread; one upload/render per UI frame. */
final class GpuEditorModelPreviews implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuEditorModelPreviews.class);
    /** A model; for a route asset its layout of {@code sides} and {@code tracks}, from above when {@code top}. */
    private record Request(String asset, int sides, int tracks, boolean top, Image target) { }
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "board-editor-previews"); thread.setDaemon(true); return thread;
    });
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Map<Image, FrameBuffer> images = new HashMap<>();
    private Future<ModelData> pending;
    private Request current;
    /** The current request's model and its textures while a program it draws with still links (GpuShaderProvider). */
    private Model model;
    private final Map<String, Texture> textures = new HashMap<>();
    private ModelBatch batch;
    private GpuCutoutFade cutoutFade;
    private GpuSceneDisplay display;
    private final GpuEnvironmentLighting environmentLighting = new GpuEnvironmentLighting();

    private Environment lighting() {
        var lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        Environment light = new Environment();
        environmentLighting.set(lighting);
        light.set(environmentLighting);
        GpuEnvironmentLighting.applyAntialiasing(light);
        light.set(ColorAttribute.createAmbientLight(lighting.ambient()));
        light.add(new DirectionalLight().set(lighting.direct(), lighting.direction()));
        return light;
    }

    /** Its display program links with the editor's first frames, for framebuffer objects. */
    GpuEditorModelPreviews() { GpuGlsl.offscreen(() -> display = new GpuSceneDisplay()); }

    /** A preview of the model, as a palette entry places it; a route's shows a N-S straight. */
    void add(String asset, Image target) {
        queue.add(new Request(asset, megamek.common.board.RailRoute.side(0) | megamek.common.board.RailRoute.side(3), 0, false, target));
    }

    /** A route of {@code kind} on its flat carrier with these sides and track pieces, seen from above (the junction card). */
    void add(megamek.common.board.RailRoute.Kind kind, int sides, int tracks, Image target) {
        queue.add(new Request(kind.asset, sides, tracks, true, target));
    }

    /** The previews still to draw. */
    int pending() { return queue.size() + (current == null ? 0 : 1); }

    /** Borrowed: the preview drawn for {@code target}; null while there is none (GpuEditorThumbnail). */
    FrameBuffer drawn(Image target) { return images.get(target); }

    void update() {
        queue.removeIf(request -> request.target().getStage() == null);
        images.entrySet().removeIf(entry -> {
            if (entry.getKey().getStage() != null) { return false; }
            entry.getValue().dispose(); return true;
        });
        if (current != null && current.target().getStage() == null) {
            if (pending != null) { pending.cancel(true); }
            pending = null; current = null; release();
        }
        if (pending != null && pending.isDone() || model != null) {
            try {
                if (model == null) {
                    model = ModelTextures.create(pending.get(), textures,
                          (key, color) -> textures.computeIfAbsent((color ? "color:" : "data:") + key, ignored -> color
                                ? GpuColorTexture.create(new FileHandle(key), true)
                                : GpuColorTexture.createData(new FileHandle(key), true)));
                    pending = null;
                }
                // A program it draws with still links: the same model draws again next frame.
                if (render(model, current.top(), current.target())) { current = null; release(); }
            } catch (Exception failure) {
                LOGGER.warn(failure, "No editor preview of {}", current.asset());
                current.target().setUserObject(megamek.client.ui.gdx.UiKit.text("BoardEditor.noPreview"));
                pending = null; current = null; release();
            }
        }
        if (pending == null && current == null && !queue.isEmpty()) {
            current = queue.removeFirst(); String asset = current.asset();
            int sides = current.sides(), tracks = current.tracks();
            pending = worker.submit(() -> {
                File root = new File(Configuration.dataDir(), "models/board");
                // A route is generated, not a file: the builder lays its pieces on a flat carrier.
                var route = megamek.common.board.RailRoute.Kind.of(asset);
                if (BoardFlag.ASSET.equals(asset)) { return GpuFlag.data(); }
                if (BoardBillboard.ASSET.equals(asset)) { return BoardBillboardMesh.preview(); }
                if (route != null) { return BoardRail.model(BoardRail.flat(route, sides, tracks)); }
                if (asset.startsWith("buildings/")) {
                    if (megamek.common.board.BuildingVolumes.of(asset).present()) { return GpuAuthoredBuilding.preview(asset); }
                    File custom = megamek.common.board.TilesetScenery.customBuildingFile(asset);
                    // A design stands alone, two levels high: one of each module, its closed sides' pieces.
                    if (custom.isFile()) { return ConnectedKit.model(GpuBuilding.kit(asset), 0, 2, new int[6], 0, 0, asset); }
                }
                return RigidGlb.loadLods(RigidGlb.source(root, asset), asset.startsWith("buildings/") ? root.getParentFile().toPath() : root.toPath()).getFirst();
            });
        }
    }

    /** Draws the model's preview into the target; false while a program it draws with still links (GpuShaderProvider). */
    private boolean render(Model model, boolean top, Image target) {
        FrameBuffer image = null;
        FrameBuffer scene = null;
        try {
            ModelInstance instance = new ModelInstance(model);
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox());
            Vector3 center = bounds.getCenter(new Vector3());
            float radius = Math.max(1, bounds.getDimensions(new Vector3()).len() / 2);
            PerspectiveCamera camera = new PerspectiveCamera(35, 128, 96);
            // A junction is read from above, north up; a model from the palette's three-quarter view.
            camera.up.set(top ? Vector3.Y : Vector3.Z);
            camera.position.set(center).add((top ? new Vector3(0, -.25f, 1) : new Vector3(1, -1.3f, 1)).nor().scl(radius * (top ? 3 : 3.7f)));
            camera.lookAt(center); camera.near = radius / 100; camera.far = radius * 10; camera.update();
            var lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
            float exposure = lighting.adaptation();
            float backdropScale = GpuSceneRadiance.SCALE / exposure;
            Environment light = lighting();
            if (batch == null) {
                batch = new ModelBatch(GpuShaderManager.provider("Editor model previews", GpuUnitShader::provider));
                cutoutFade = new GpuCutoutFade();
            }
            scene = GpuHeatGlow.buffer(128, 96, true);
            image = GpuAtmosphere.buffer(128, 96, true);
            int skipped = GpuShaderProvider.unlinkedDraws();
            scene.begin();
            try {
                Gdx.gl.glDepthMask(true);
                ScreenUtils.clear(GpuColor.linear(.045f) * backdropScale,
                      GpuColor.linear(.06f) * backdropScale,
                      GpuColor.linear(.065f) * backdropScale, 1, true);
                batch.begin(camera);
                try { batch.render(instance, light); } finally { batch.end(); }
                cutoutFade.render(batch, camera, light, List.of(instance), bounds);
            } finally { scene.end(); }
            if (GpuShaderProvider.unlinkedDraws() != skipped) { return false; }
            try { display.render(scene, image, exposure); } finally { image.end(); }
            TextureRegion region = new TextureRegion(image.getColorBufferTexture()); region.flip(false, true);
            target.setDrawable(new TextureRegionDrawable(region)); target.setUserObject("Ready"); images.put(target, image); image = null;
            return true;
        } finally {
            if (scene != null) { scene.dispose(); }
            if (image != null) { image.dispose(); }
        }
    }

    /** Releases the current request's model and textures. */
    private void release() {
        if (model != null) { model.dispose(); model = null; }
        textures.values().forEach(Texture::dispose);
        textures.clear();
    }

    void clear() {
        queue.clear();
        if (pending != null) { pending.cancel(true); pending = null; }
        current = null; release();
        images.values().forEach(FrameBuffer::dispose); images.clear();
    }
    @Override public void dispose() {
        clear(); worker.shutdownNow();
        if (batch != null) { batch.dispose(); cutoutFade.dispose(); }
        if (display != null) { display.dispose(); }
        environmentLighting.dispose();
    }
}
