/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelCache;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native pixels, culling and ownership for persistent prop pages across visibility and source changes. */
@Tag("on-demand")
class GpuPropBatchSmokeTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void propsBatchWithoutChangingPixelsAndRebuildOnlyForReplacedSources(boolean textured) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check(textured); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Native prop batch prototype", failure.get()); }
    }

    private record Source(int page, ModelCache owner, Array<Renderable> parts) implements RenderableProvider {
        @Override
        public void getRenderables(Array<Renderable> destination, Pool<Renderable> pool) {
            for (Renderable part : parts) { part.shader = null; part.environment = null; }
            destination.addAll(parts);
        }
    }

    private static void check(boolean textured) throws Exception {
        sparseIndexedRangesKeepUnsignedVertices();
        levelTopsKeepTheirOwnDraw();
        Pixmap image = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        image.setColor(Color.CORAL);
        image.fill();
        image.setColor(Color.NAVY);
        image.fillRectangle(0, 0, 2, 2);
        Texture texture = new Texture(image);
        image.dispose();
        texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        // Scene props and scatter carry this marker; batching must preserve it, not reject the material.
        Material material = new Material(ColorAttribute.createDiffuse(Color.WHITE), new GpuLinearColors());
        material.set(new GpuModelMaterial(.6f, 0, null));
        if (textured) { material.set(TextureAttribute.createDiffuse(texture)); }
        Model small = model(20, material);
        Model large = model(25, material);
        Model fallback = model(20, new Material(ColorAttribute.createDiffuse(Color.WHITE),
              IntAttribute.createCullFace(GL20.GL_NONE)));
        List<Source> owned = new ArrayList<>(), all = new ArrayList<>();
        GpuPropBatch aggregate = new GpuPropBatch();
        Array<Renderable> submitted = new Array<>();
        GpuOpaqueSorter sorter = new GpuOpaqueSorter();
        ModelBatch batch = new ModelBatch(new DefaultShaderProvider(), (camera, values) -> {
            submitted.clear();
            submitted.addAll(values);
            sorter.sort(camera, values);
        });
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        boolean aggregateDisposed = false;
        try {
            // 375 chunk-owned meshes, 283,500 vertices: close to the audited 375 draws / 282,960 vertices.
            for (int i = 0; i < 375; i++) {
                Source source = source(i % 5 == 0 ? large : small, i, 0);
                all.add(source);
                owned.add(source);
            }
            Source replacement = source(small, 137, .35f), unbatched = source(fallback, 375, 0);
            owned.add(replacement);
            owned.add(unbatched);
            Source transformed = source(small, 376, 0);
            for (Renderable part : transformed.parts()) { part.worldTransform.translate(0, 0, 2); }
            owned.add(transformed);
            Set<Mesh> borrowed = managedMeshes();
            Environment environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(.3f, .3f, .3f, 1));
            environment.add(new DirectionalLight().set(.8f, .7f, .6f, -.6f, -.4f, -1));
            BoardCamera view = new BoardCamera();
            view.resize(1200, 900);
            view.camera.zoom = .12f;
            view.center(new Vector3(48, -48, 0));
            // Pages build incrementally; steady-state comparisons begin once all 49 pages are available.
            for (int i = 0; i < 50; i++) { draw(batch, view.camera, environment, all, aggregate); }
            int maximumChanged = 0, maximumDelta = 0;
            for (boolean perspective : new boolean[] { false, true }) {
                view.setPerspective(perspective);
                for (float tilt : new float[] { 0, 55, 75 }) {
                    view.setIsometric(false);
                    view.orbit(20, tilt);
                    assertEquals(tilt, view.tilt());
                    for (int warm = 0; warm < 8; warm++) {
                        draw(batch, view.camera, environment, all, null);
                        draw(batch, view.camera, environment, all, aggregate);
                    }
                    byte[] reference = pixels(batch, view.camera, environment, all, null);
                    assertEquals(new Difference(0, 0), difference(reference,
                          pixels(batch, view.camera, environment, all, null)), "Repeated reference must be deterministic");
                    byte[] combined = pixels(batch, view.camera, environment, all, aggregate);
                    Difference difference = difference(reference, combined);
                    System.out.printf("Prop batch pixels perspective=%s tilt=%.0f: %s%n", perspective, tilt, difference);
                    maximumChanged = Math.max(maximumChanged, difference.changedPixels());
                    maximumDelta = Math.max(maximumDelta, difference.maximumChannelDelta());
                }
            }
            assertEquals(48, aggregate.rebuilds(), "Camera motion reuses pages; the single-source page needs no copy");
            List<Source> onePage = all.stream().filter(source -> source.page() == 0).toList();
            draw(batch, view.camera, environment, onePage, null);
            Map<Vertex, Integer> originalVertices = vertices(submitted);
            Material originalMaterial = submitted.first().material;
            draw(batch, view.camera, environment, all, onePage, aggregate);
            assertEquals(originalVertices, vertices(submitted), "Every indexed position, normal and color must stay bit-exact");
            assertEquals(1, submitted.size, "One compatible page has one draw");
            assertTrue(originalMaterial.same(submitted.first().material, true), "Copied material values must stay exact");

            GL20 rawGl20 = Gdx.gl20;
            profiler.enable();
            profiler.reset();
            draw(batch, view.camera, environment, all, null);
            int originalDraws = profiler.getDrawCalls();
            profiler.reset();
            draw(batch, view.camera, environment, all, aggregate);
            int combinedDraws = profiler.getDrawCalls();
            GpuStageTimings.stopCounting(profiler, rawGl20);
            System.out.printf("Prop batch draws: original=%d combined=%d%n", originalDraws, combinedDraws);
            assertEquals(375, originalDraws);
            assertEquals(49, combinedDraws);
            measure(batch, view.camera, environment, all, aggregate);
            GpuPropBatch crowded = new GpuPropBatch();
            try {
                // Dense authored geometry can exceed the unsigned-short index limit even inside one spatial page.
                List<Source> samePage = all.stream().map(source -> new Source(0, source.owner(), source.parts())).toList();
                byte[] reference = pixels(batch, view.camera, environment, samePage, null);
                // It merges once settled; meanwhile the sources draw as they are.
                for (int frame = 0; frame < GpuMeshPage.SETTLE_FRAMES; frame++) {
                    draw(batch, view.camera, environment, samePage, crowded);
                }
                assertEquals(new Difference(0, 0), difference(reference,
                      pixels(batch, view.camera, environment, samePage, crowded)), "Page splitting must preserve exact pixels");
                assertEquals(5, submitted.size, "283,500 vertices must split across five indexable meshes");
                int indices = 0;
                for (Renderable value : submitted) {
                    assertTrue(value.meshPart.mesh.getNumVertices() <= 65536, "Every mesh must retain 16-bit index safety");
                    indices += value.meshPart.size;
                }
                assertEquals(283500, indices, "Page splitting must preserve every triangle");
                List<Source> partial = samePage.subList(90, 260);
                byte[] visiblePixels = pixels(batch, view.camera, environment, partial, null);
                Map<Vertex, Integer> visibleVertices = vertices(submitted);
                draw(batch, view.camera, environment, samePage, partial, crowded);
                assertEquals(visibleVertices, vertices(submitted), "Partial draws across mesh splits retain only visible indices");
                assertEquals(new Difference(0, 0), difference(visiblePixels, capture(true)));
                assertEquals(1, crowded.rebuilds(), "Culling a multi-mesh page must not rebuild it");
            } finally {
                crowded.dispose();
            }

            List<Source> subset = all.subList(90, 260);
            // Rows 6..17 cross two partial page rows. Visibility alone must never copy or upload geometry.
            measureChange("pan subset", batch, view.camera, environment, all, subset, aggregate, 48);
            List<Source> edited = new ArrayList<>(subset);
            edited.set(137 - 90, replacement);
            List<Source> replaced = new ArrayList<>(all);
            replaced.set(137, replacement);
            measureChange("edited chunk", batch, view.camera, environment, replaced, edited, aggregate, 49);
            List<Source> mixed = new ArrayList<>(edited);
            mixed.add(unbatched);
            mixed.add(transformed);
            replaced.add(unbatched);
            replaced.add(transformed);
            measureChange("unchanged fallback", batch, view.camera, environment, replaced, mixed, aggregate, 49);
            measureChange("no visible props", batch, view.camera, environment, replaced, List.of(), aggregate, 49);
            measureChange("return to overview", batch, view.camera, environment, all, all, aggregate, 50);

            byte[] beforeDispose = pixels(batch, view.camera, environment, all, null);
            aggregate.dispose();
            aggregateDisposed = true;
            assertEquals(borrowed, managedMeshes(), "All aggregate buffers must be released; borrowed meshes must survive");
            assertEquals(new Difference(0, 0), difference(beforeDispose,
                  pixels(batch, view.camera, environment, all, null)), "Source caches still render after aggregate disposal");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            assertEquals(new Difference(0, 0), new Difference(maximumChanged, maximumDelta), "Aggregate pixels must remain exact");
        } finally {
            texture.dispose();
            if (profiler.isEnabled()) { profiler.disable(); }
            if (!aggregateDisposed) { aggregate.dispose(); }
            batch.dispose();
            owned.forEach(source -> source.owner().dispose());
            fallback.dispose();
            large.dispose();
            small.dispose();
        }
    }

    private static void measureChange(String name, ModelBatch batch, Camera camera, Environment environment,
          List<Source> sources, List<Source> visible, GpuPropBatch aggregate, long rebuilds) {
        byte[] reference = pixels(batch, camera, environment, visible, null);
        assertEquals(new Difference(0, 0), difference(reference,
              pixels(batch, camera, environment, visible, null)), "Repeated reference must be deterministic");
        Gdx.gl.glFinish();
        long start = System.nanoTime();
        draw(batch, camera, environment, sources, visible, aggregate);
        double elapsed = (System.nanoTime() - start) / 1e6;
        Gdx.gl.glFinish();
        Difference difference = difference(reference, capture(!visible.isEmpty()));
        System.out.printf("Prop batch %s: first submission %.3f ms; %s%n", name, elapsed, difference);
        // The outdated page and the replaced sources must draw exactly the new geometry at once.
        assertEquals(new Difference(0, 0), difference, name);
        for (int frame = 0; frame < GpuMeshPage.SETTLE_FRAMES; frame++) {
            draw(batch, camera, environment, sources, visible, aggregate);
        }
        assertEquals(rebuilds, aggregate.rebuilds(), name);
        assertEquals(new Difference(0, 0), difference(reference, capture(!visible.isEmpty())), "Merged again: " + name);
        draw(batch, camera, environment, sources, visible, aggregate);
        assertEquals(rebuilds, aggregate.rebuilds(), "Settled frame must reuse " + name);
    }

    private static void measure(ModelBatch batch, Camera camera, Environment environment, List<Source> sources,
          GpuPropBatch aggregate) {
        double[] original = new double[120], combined = new double[120];
        for (int sample = -24; sample < original.length; sample++) {
            for (int turn = 0; turn < 2; turn++) {
                boolean cached = ((sample + turn) & 1) == 0;
                Gdx.gl.glFinish();
                long start = System.nanoTime();
                draw(batch, camera, environment, sources, cached ? aggregate : null);
                double elapsed = (System.nanoTime() - start) / 1e6;
                if (sample >= 0) { (cached ? combined : original)[sample] = elapsed; }
            }
        }
        Arrays.sort(original);
        Arrays.sort(combined);
        System.out.printf("Prop batch warm/interleaved CPU submission median/p95: original %.3f/%.3f ms; combined %.3f/%.3f ms%n",
              original[60], original[114], combined[60], combined[114]);
    }

    private static void draw(ModelBatch batch, Camera camera, Environment environment, List<Source> sources,
          GpuPropBatch aggregate) {
        draw(batch, camera, environment, sources, sources, aggregate);
    }

    private static void draw(ModelBatch batch, Camera camera, Environment environment, List<Source> sources,
          List<Source> visible, GpuPropBatch aggregate) {
        ScreenUtils.clear(.04f, .06f, .08f, 1, true);
        batch.begin(camera);
        if (aggregate == null) {
            for (Source source : visible) { batch.render(source, environment); }
        } else {
            aggregate.begin();
            Set<Source> shown = new HashSet<>(visible);
            for (Source source : sources) { aggregate.add(source, source.page(), shown.contains(source)); }
            // No wall clock: a settled page merges within the frame.
            aggregate.render(batch, environment, Long.MAX_VALUE);
        }
        batch.end();
    }

    private static byte[] pixels(ModelBatch batch, Camera camera, Environment environment, List<Source> sources,
          GpuPropBatch aggregate) {
        draw(batch, camera, environment, sources, aggregate);
        return capture(!sources.isEmpty());
    }

    private static byte[] capture(boolean visibleGeometry) {
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), false);
        if (visibleGeometry) {
            int visible = 0;
            for (int i = 0; i < pixels.length; i += 4) {
                // Allow the implementation's rounding of the .04/.06/.08 clear color.
                if (Math.abs(Byte.toUnsignedInt(pixels[i]) - 10) > 2
                      || Math.abs(Byte.toUnsignedInt(pixels[i + 1]) - 15) > 2
                      || Math.abs(Byte.toUnsignedInt(pixels[i + 2]) - 20) > 2) { visible++; }
            }
            assertTrue(visible > 500, "Pixel comparison must contain substantial visible geometry: " + visible);
        }
        return pixels;
    }

    private record Difference(int changedPixels, int maximumChannelDelta) { }

    private record Vertex(int x, int y, int z, int nx, int ny, int nz, int color) { }

    private static void sparseIndexedRangesKeepUnsignedVertices() {
        Mesh mesh = new Mesh(true, 65536, 12, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked());
        Array<Renderable> source = new Array<>(), copied = new Array<>();
        try {
            float[] points = new float[65536 * 7];
            for (int i = 0; i < 65536; i++) {
                points[i * 7] = i;
                points[i * 7 + 1] = i % 17;
                points[i * 7 + 2] = i % 11;
                points[i * 7 + 5] = 1;
                points[i * 7 + 6] = Color.WHITE.toFloatBits();
            }
            mesh.setVertices(points);
            mesh.setIndices(new short[] { 0, 1, 2, (short) 40001, (short) 40002, (short) 40003,
                  (short) 65533, (short) 65534, (short) 65535, (short) 40003, (short) 40002, (short) 40001 });
            for (int[] range : new int[][] { { 3, 6 }, { 9, 3 }, { 0, 3 } }) {
                Renderable part = new Renderable();
                part.material = new Material(ColorAttribute.createDiffuse(Color.WHITE));
                part.meshPart.set("range", mesh, range[0], range[1], GL20.GL_TRIANGLES);
                source.add(part);
            }
            var merge = new GpuPropBatch.Merge(source);
            assertTrue(merge.step(Long.MAX_VALUE));
            copied.addAll(merge.merged);
            assertEquals(vertices(source), vertices(copied), "Sparse and reversed ranges keep every unsigned index and vertex");
            assertEquals(12, copied.first().meshPart.size);
            assertEquals(12, copied.first().meshPart.mesh.getNumVertices(), "Unused vertices must not enter the copied mesh");
        } finally {
            GpuPropBatch.disposeMeshes(copied);
            mesh.dispose();
        }
    }

    /** A level top's material differs from its twin only by id, and its program has no walls: a merge keeps them apart. */
    private static void levelTopsKeepTheirOwnDraw() {
        Mesh mesh = new Mesh(true, 6, 6, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked());
        Array<Renderable> source = new Array<>(), copied = new Array<>();
        try {
            mesh.setVertices(new float[6 * 7]);
            mesh.setIndices(new short[] { 0, 1, 2, 3, 4, 5 });
            Material walls = new Material(ColorAttribute.createDiffuse(Color.WHITE));
            for (Material material : List.of(walls, GpuFamilyPrograms.flat(walls))) {
                Renderable part = new Renderable();
                part.material = material;
                part.meshPart.set("range", mesh, source.size * 3, 3, GL20.GL_TRIANGLES);
                source.add(part);
            }
            var merge = new GpuPropBatch.Merge(source);
            assertTrue(merge.step(Long.MAX_VALUE));
            copied.addAll(merge.merged);
            assertEquals(2, copied.size, "Level tops must not join their twin's draw");
            assertTrue(GpuFamilyPrograms.isFlat(copied.get(1).material));
        } finally {
            GpuPropBatch.disposeMeshes(copied);
            mesh.dispose();
        }
    }

    /** Compare every referenced vertex, including multiplicity, independently of mesh/part packing and ordering. */
    private static Map<Vertex, Integer> vertices(Array<Renderable> renderables) {
        Map<Vertex, Integer> result = new HashMap<>();
        for (Renderable value : renderables) {
            Mesh mesh = value.meshPart.mesh;
            VertexAttributes attributes = mesh.getVertexAttributes();
            int stride = attributes.vertexSize / Float.BYTES;
            int position = attributes.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
            int normal = attributes.findByUsage(VertexAttributes.Usage.Normal).offset / Float.BYTES;
            int color = attributes.findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
            float[] data = new float[mesh.getNumVertices() * stride];
            short[] indices = new short[value.meshPart.size];
            mesh.getVertices(data);
            mesh.getIndices(value.meshPart.offset, value.meshPart.size, indices, 0);
            for (short index : indices) {
                int base = Short.toUnsignedInt(index) * stride;
                Vertex vertex = new Vertex(Float.floatToRawIntBits(data[base + position]),
                      Float.floatToRawIntBits(data[base + position + 1]), Float.floatToRawIntBits(data[base + position + 2]),
                      Float.floatToRawIntBits(data[base + normal]), Float.floatToRawIntBits(data[base + normal + 1]),
                      Float.floatToRawIntBits(data[base + normal + 2]), Float.floatToRawIntBits(data[base + color]));
                result.merge(vertex, 1, Integer::sum);
            }
        }
        return result;
    }

    private static Difference difference(byte[] a, byte[] b) {
        assertEquals(a.length, b.length);
        int changed = 0, maximum = 0;
        for (int i = 0; i < a.length; i += 4) {
            int delta = 0;
            for (int channel = 0; channel < 4; channel++) {
                delta = Math.max(delta, Math.abs(Byte.toUnsignedInt(a[i + channel]) - Byte.toUnsignedInt(b[i + channel])));
            }
            if (delta != 0) { changed++; maximum = Math.max(maximum, delta); }
        }
        return new Difference(changed, maximum);
    }

    private static Source source(Model model, int index, float edit) {
        // Spread 375 occupied chunks across the 25 by 25 chunk grid of a 200 by 200 board.
        int row = index / 15, column = (index % 15 * 5 / 3 + row % 5) % 25;
        ModelInstance instance = new ModelInstance(model);
        if (edit != 0) {
            TextureAttribute texture = instance.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse);
            if (texture != null) { texture.scaleV = .7f; texture.offsetU = .2f; }
        }
        instance.transform.setToTranslation(column * 4, -row * 4, edit)
              .rotate(Vector3.Z, index * 17).rotate(Vector3.X, index % 11 * 3)
              .scale(1 + index % 3 * .2f, .7f + index % 5 * .1f, 1.2f + edit);
        ModelCache cache = new ModelCache(new ModelCache.Sorter(), new ModelCache.TightMeshPool());
        cache.begin();
        cache.add(instance);
        cache.end();
        Array<Renderable> parts = new Array<>();
        cache.getRenderables(parts, null);
        int page = row / GpuPropBatch.CHUNKS_PER_PAGE * 7 + column / GpuPropBatch.CHUNKS_PER_PAGE;
        return new Source(page, cache, parts);
    }

    private static Model model(int sectors, Material material) {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder mesh = builder.part("prop", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
              | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates, material);
        for (int row = 0; row < 6; row++) {
            for (int column = 0; column < sectors; column++) {
                var a = vertex(row, column, sectors);
                var b = vertex(row, column + 1, sectors);
                var c = vertex(row + 1, column + 1, sectors);
                var d = vertex(row + 1, column, sectors);
                mesh.triangle(a, d, c);
                mesh.triangle(c, b, a);
            }
        }
        return builder.end();
    }

    private static MeshPartBuilder.VertexInfo vertex(int row, int column, int sectors) {
        double latitude = Math.PI * row / 6, longitude = 2 * Math.PI * column / sectors;
        Vector3 normal = new Vector3((float) (Math.sin(latitude) * Math.cos(longitude)),
              (float) (Math.sin(latitude) * Math.sin(longitude)), (float) Math.cos(latitude)).nor();
        return new MeshPartBuilder.VertexInfo().setPos(normal).setNor(normal)
              .setCol(.35f + row * .07f, .45f + column % 3 * .1f, .3f, 1).setUV(column / (float) sectors, row / 6f);
    }

    @SuppressWarnings("unchecked")
    private static Set<Mesh> managedMeshes() throws Exception {
        Field field = Mesh.class.getDeclaredField("meshes");
        field.setAccessible(true);
        Array<Mesh> meshes = ((Map<com.badlogic.gdx.Application, Array<Mesh>>) field.get(null)).get(Gdx.app);
        Set<Mesh> result = new HashSet<>();
        if (meshes != null) { for (Mesh mesh : meshes) { result.add(mesh); } }
        assertTrue(!result.isEmpty(), "The native fixture must own real mesh resources");
        return result;
    }
}
