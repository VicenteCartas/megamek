/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.logging.MMLogger;

/** Small, private sample boards, rendered progressively with the same geometry, artwork and lighting as the board. */
final class GpuEditorTerrainPreviews implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuEditorTerrainPreviews.class);
    private static final int WIDTH = 256, HEIGHT = 160, CACHE_SIZE = 48;
    /** The cliff behind two adjacent level-zero hexes; each of these three hexes touches both of the others. */
    private static final List<Coords> THEME_HEXES = List.of(new Coords(0, 0), new Coords(0, 1), new Coords(1, 0));
    private record Sample(String theme, String component, String variant, BoardEditorSession.Brush stamp) { }
    private record Cached(FrameBuffer buffer, List<Image> targets) { }
    private final Map<Sample, List<Image>> queue = new LinkedHashMap<>();
    private final Map<Sample, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "board-editor-terrain-previews"); thread.setDaemon(true); return thread;
    });
    /** Only the preview worker touches these disposable sample boards and their capture. */
    private Future<BoardScene> pending;
    private Sample current;
    private BoardScene scene;
    private GpuTerrain terrain;
    private GpuAtmosphere atmosphere;
    private final BoardCamera camera = new BoardCamera();
    private GpuTerrain lender;

    void add(String theme, String component, String variant, Image target) {
        add(new Sample(theme.isEmpty() ? "grass" : theme, component, variant, null), target);
    }

    /** The EDT's immutable brush snapshot, including its objects; never the currently inspected, mutable hex. */
    void add(BoardEditorSession.Brush stamp, Image target) {
        add(new Sample(stamp.theme(), "ground", "", stamp), target);
    }

    /** A category's tile: the Theme category shows its theme's sample, the others their icon hex as a hex stamp. */
    void add(BoardEditorBlueprint.Category category, Image target) {
        if (category.themes() != null) { add(category.icon().getTheme(), "", "", target); }
        else { add(BoardEditorSession.hexStamp("icon/" + category.id(), category.icon()), target); }
    }

    private void add(Sample sample, Image target) {
        remove(target);
        Cached ready = cache.get(sample);
        if (ready != null) { assign(target, ready.buffer()); ready.targets().add(target); }
        else { queue.computeIfAbsent(sample, ignored -> new ArrayList<>()).add(target); }
    }

    /** Forgets the sample {@code target} shows or waits for: it shows another picture now. */
    void remove(Image target) {
        cache.values().forEach(value -> value.targets().remove(target));
        queue.values().forEach(targets -> targets.remove(target));
    }

    /** The samples still to draw. */
    int pending() { return queue.size(); }

    /** Borrowed: the sample drawn for {@code target}; null while there is none (GpuEditorThumbnail). */
    FrameBuffer drawn(Image target) {
        return cache.values().stream().filter(value -> value.targets().contains(target)).map(Cached::buffer).findFirst().orElse(null);
    }

    /**
     * No waiting on the UI thread, and at most one completed image per frame. The samples draw with {@code board}'s
     * decoded models and textures, so a first sample never decodes them again on the render thread; without a board
     * renderer they wait, and a new one (an asset reload) replaces the sample renderer.
     */
    void update(GpuTerrain board) {
        // The samples draw into their own framebuffers only (GpuGlsl.offscreen).
        GpuGlsl.offscreen(() -> updateSamples(board));
    }

    private void updateSamples(GpuTerrain board) {
        use(board);
        if (lender == null) { return; }
        cache.values().forEach(value -> value.targets().removeIf(image -> image.getStage() == null));
        queue.values().forEach(targets -> targets.removeIf(image -> image.getStage() == null));
        queue.entrySet().removeIf(entry -> entry.getValue().isEmpty() && !entry.getKey().equals(current));
        try {
            if (pending != null && pending.isDone()) {
                scene = pending.get(); pending = null;
                show();
            } else if (scene != null && terrain == null) { show(); }
            if (scene != null) {
                terrain.refine(camera.camera);
                if (!terrain.busy() && terrain.ready(scene)) {
                    List<Image> targets = queue.get(current);
                    // A draw whose program still links skipped this image (GpuShaderProvider): drawn again next frame.
                    int skipped = GpuShaderProvider.unlinkedDraws();
                    FrameBuffer buffer = targets == null || targets.isEmpty() ? null : render();
                    if (buffer != null && GpuShaderProvider.unlinkedDraws() != skipped) {
                        buffer.dispose();
                        return;
                    }
                    if (buffer != null) {
                        targets.forEach(target -> assign(target, buffer));
                        cache.put(current, new Cached(buffer, targets));
                        trim();
                    }
                    queue.remove(current);
                    scene = null; current = null;
                }
            }
            if (current == null && !queue.isEmpty()) {
                current = queue.keySet().stream().filter(sample -> sample.stamp() != null).findFirst()
                      .orElseGet(() -> queue.keySet().stream().filter(sample -> sample.component().isEmpty()).findFirst()
                            .orElseGet(() -> queue.keySet().iterator().next()));
                Sample sample = current;
                pending = worker.submit(() -> capture(sample));
            }
        } catch (Exception failure) {
            LOGGER.warn(failure, "Editor sample preview failed: {}", current);
            List<Image> targets = queue.remove(current);
            if (targets != null) {
                String unavailable = megamek.client.ui.gdx.UiKit.text("BoardEditor.previewUnavailable");
                targets.forEach(target -> target.setUserObject(unavailable));
            }
            current = null; scene = null; pending = null;
        }
    }

    private void use(GpuTerrain board) {
        if (board != lender) {
            disposeRenderer();
            lender = board;
        }
    }

    /** Starts building {@link #scene}, the current sample, in the sample renderer. */
    private void show() {
        createRenderer();
        camera.setIsometric(true);
        if (current.component().isEmpty()) {
            camera.orbit(-15, 0);
            camera.fit(scene, THEME_HEXES.stream().map(scene::tile).toList());
        } else { camera.fit(scene); }
        configure(scene);
        terrain.update(scene, camera.camera);
    }

    private void createRenderer() {
        if (terrain == null) {
            terrain = new GpuTerrain(lender); atmosphere = new GpuAtmosphere();
            atmosphere.configure(BoardAtmosphere.DEFAULTS);
            camera.resize(WIDTH, HEIGHT);
        }
    }

    private void configure(BoardScene board) {
        atmosphere.updateLight(camera.camera); terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, board);
        // The board's lighting configuration, so the samples draw with its programs. Outside the board's opaque pass
        // its occlusion reads visibility 1 (GpuAmbientOcclusion.texture).
        GpuEnvironmentLighting.required(terrain.environment()).ambientOcclusion =
              GpuEnvironmentLighting.ambientOcclusion(lender.environment());
    }

    /** The sample renderer goes before the board renderer whose assets it borrows. */
    private void disposeRenderer() {
        if (terrain != null) { terrain.dispose(); terrain = null; }
        if (atmosphere != null) { atmosphere.dispose(); atmosphere = null; }
    }

    private FrameBuffer render() {
        FrameBuffer buffer = GpuAtmosphere.buffer(WIDTH, HEIGHT, true);
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.settings())) {
            terrain.animate(0, List.of());
            terrain.renderShadows(camera.camera, List.of());
            atmosphere.begin(WIDTH, HEIGHT, 0);
            if (current.component().isEmpty()) {
                for (Coords coords : THEME_HEXES) { terrain.renderEditorSection(camera.camera, coords, ""); }
            } else { terrain.render(camera.camera); terrain.renderTransparent(camera.camera); }
            atmosphere.end(camera.camera, terrain, scene, 0, null, false, buffer);
            buffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            return buffer;
        } catch (RuntimeException failure) { buffer.dispose(); throw failure; }
    }

    private static BoardScene capture(Sample sample) {
        BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
        boolean theme = sample.component().isEmpty();
        Board board = Board.createEmptyBoard(theme ? 2 : 1, theme ? 2 : 1);
        // Samples show editor choices, so road level 2 is the native Alley finish, as in the edited board.
        board.setNativeFormat(true);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                if (sample.stamp() != null) {
                    // Reconstruct only the worker's private preview. Keep captured exits, including derived ones,
                    // rather than recalculating them against the preview's missing neighbours.
                    var stamp = sample.stamp();
                    Hex hex = board.getHex(x, y);
                    hex.setLevel(stamp.elevation()); hex.setTheme(stamp.theme());
                    stamp.properties().forEach(property -> hex.addTerrain(new Terrain(Terrains.getType(property.terrain()),
                          property.value(), property.explicit(), property.exits())));
                    hex.setAppearance(stamp.appearance()); hex.setDecorations(stamp.objects());
                    continue;
                }
                String componentId = theme ? x == 0 ? y == 0 ? "cliff" : "vegetation" : "ground" : sample.component();
                Hex hex = hex(blueprint, componentId, sample.variant(), sample.theme());
                if (theme) {
                    // The rectangular sample's unused corner is below the three rendered hexes. Cliff edges close
                    // their exposed skirts; only the three touching hexes are framed and rendered, using native meshes.
                    if (x == 1 && y == 1) { hex.setLevel(-1); }
                    else { hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, 63)); }
                }
                board.setHex(new Coords(x, y), hex);
            }
        }
        return scene(board);
    }

    /** A sample board as the 3D board draws it, without units or a game. */
    static BoardScene scene(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board), List.of(), List.of(), -1,
              "", List.of());
    }

    /** A component's sample hex: its preview, a preset ("preset:id") or a look (a variant id) in a theme. */
    static Hex hex(BoardEditorBlueprint blueprint, String componentId, String variantId, String theme) {
        var component = blueprint.component(componentId);
        Hex hex = new Hex(component.previewLevel(), component.preview(), theme);
        if (variantId.startsWith("preset:")) {
            var preset = component.presets().stream().filter(p -> p.id().equals(variantId.substring(7))).findFirst().orElseThrow();
            hex = new Hex(component.previewLevel(), preset.terrain(), theme);
            for (int type : new int[] { Terrains.ROAD, Terrains.BRIDGE }) {
                Terrain terrain = hex.getTerrain(type);
                if (terrain != null && !terrain.hasExitsSpecified()) { hex.addTerrain(new Terrain(type, terrain.getLevel(), true, 9)); }
            }
        } else if (!variantId.isEmpty()) {
            var variant = blueprint.variant(variantId);
            // A skin shows the cover its card paints (BoardEditorSession's brush).
            if (variant.skin() != null) { hex = new Hex(component.previewLevel(), variant.skin().terrain(), theme); }
            hex.setAppearance(Map.of(variant.owner(), variant.material().isEmpty()
                  ? new HexAppearance(variant.id(), null, null, variant.blend() ? .5 : null)
                  : new HexAppearance(null, null, variant.material(), null)));
        }
        return hex;
    }

    private static void assign(Image target, FrameBuffer buffer) {
        TextureRegion region = new TextureRegion(buffer.getColorBufferTexture()); region.flip(false, true);
        target.setDrawable(new TextureRegionDrawable(region)); target.setUserObject("Ready");
    }

    private void trim() {
        var entries = cache.entrySet().iterator();
        while (cache.size() > CACHE_SIZE && entries.hasNext()) {
            var entry = entries.next();
            // Never dispose a texture still displayed by a card or the selected theme's face.
            if (entry.getValue().targets().isEmpty()) { entry.getValue().buffer().dispose(); entries.remove(); }
        }
    }

    @Override public void dispose() {
        queue.clear();
        if (pending != null) { pending.cancel(true); }
        worker.shutdown();
        cache.values().forEach(value -> value.buffer().dispose()); cache.clear();
        disposeRenderer();
    }
}
