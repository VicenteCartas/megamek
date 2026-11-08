/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.utils.Array;
import org.lwjgl.opengl.ARBPipelineStatisticsQuery;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;

/** Optional inspection of actual terrain submissions; never installed during normal benchmark frames. */
final class GpuDrawCallAudit implements RenderableSorter, AutoCloseable {
    /** One opaque pass, outside timing samples. Driver counts may include helper work and are not an overdraw ratio. */
    static final class PipelineStatistics implements AutoCloseable {
        private record Counter(int target, String name) { }
        private static final List<Counter> COUNTERS = List.of(
              new Counter(ARBPipelineStatisticsQuery.GL_VERTICES_SUBMITTED_ARB, "submitted vertices (including instances)"),
              new Counter(ARBPipelineStatisticsQuery.GL_PRIMITIVES_SUBMITTED_ARB, "submitted primitives or patches"),
              new Counter(ARBPipelineStatisticsQuery.GL_VERTEX_SHADER_INVOCATIONS_ARB, "vertex shader invocations"),
              new Counter(ARBPipelineStatisticsQuery.GL_TESS_CONTROL_SHADER_PATCHES_ARB, "tessellation control patches"),
              new Counter(ARBPipelineStatisticsQuery.GL_TESS_EVALUATION_SHADER_INVOCATIONS_ARB, "tessellation evaluation invocations"),
              new Counter(ARBPipelineStatisticsQuery.GL_CLIPPING_INPUT_PRIMITIVES_ARB, "primitives entering clipping"),
              new Counter(ARBPipelineStatisticsQuery.GL_CLIPPING_OUTPUT_PRIMITIVES_ARB, "primitives leaving clipping"),
              new Counter(ARBPipelineStatisticsQuery.GL_FRAGMENT_SHADER_INVOCATIONS_ARB, "fragment shader invocations"),
              new Counter(GL15.GL_SAMPLES_PASSED, "samples passing depth and stencil"));
        private final int[] queries = new int[COUNTERS.size()];
        private boolean active, started;

        PipelineStatistics() {
            if (!GL.getCapabilities().GL_ARB_pipeline_statistics_query) {
                throw new IllegalStateException("Pipeline diagnostics require GL_ARB_pipeline_statistics_query");
            }
            for (int i = 0; i < queries.length; i++) {
                int target = COUNTERS.get(i).target();
                if ((target == ARBPipelineStatisticsQuery.GL_TESS_CONTROL_SHADER_PATCHES_ARB
                      || target == ARBPipelineStatisticsQuery.GL_TESS_EVALUATION_SHADER_INVOCATIONS_ARB)
                      && !GL.getCapabilities().OpenGL40 && !GL.getCapabilities().GL_ARB_tessellation_shader) {
                    continue;
                }
                if (GL15.glGetQueryi(target, GL15.GL_QUERY_COUNTER_BITS) > 0) {
                    queries[i] = GL15.glGenQueries();
                }
            }
        }

        void stage(String name) {
            if (active && !"opaque terrain".equals(name)) { end(); }
            if (!active && "opaque terrain".equals(name)) {
                if (started) { throw new IllegalStateException("Expected one opaque pass per diagnostic frame"); }
                started = active = true;
                for (int i = 0; i < queries.length; i++) {
                    if (queries[i] != 0) { GL15.glBeginQuery(COUNTERS.get(i).target(), queries[i]); }
                }
            }
        }

        private void end() {
            if (!active) { return; }
            for (int i = 0; i < queries.length; i++) {
                if (queries[i] != 0) { GL15.glEndQuery(COUNTERS.get(i).target()); }
            }
            active = false;
        }

        String report(String scenario) {
            end();
            if (!started) { throw new IllegalStateException("No opaque pass reached the pipeline diagnostic"); }
            var report = new StringBuilder("PIPELINE ").append(scenario).append(" opaque terrain\n");
            report.append("counter,value\n");
            for (int i = 0; i < queries.length; i++) {
                report.append(COUNTERS.get(i).name()).append(',');
                if (queries[i] == 0) { report.append("unsupported"); }
                else { report.append(ARBTimerQuery.glGetQueryObjectui64(queries[i], GL15.GL_QUERY_RESULT)); }
                report.append('\n');
            }
            return report.append("Driver-dependent counts may include helper work. Passing samples accumulate across draws "
                        + "and MSAA samples; they are not unique pixels.\n")
                  .toString();
        }

        @Override
        public void close() {
            end();
            for (int query : queries) { if (query != 0) { GL15.glDeleteQueries(query); } }
        }
    }

    private record Origin(String category, int chunk) { }
    private record Row(String pass, String category, String family, int shader, long materialMask,
          long environmentMask, long vertexMask, boolean blended) { }
    private record ChunkMaterial(int chunk, Attributes attributes) { }

    private static final class Counts {
        int draws, knownChunkDraws;
        long submittedIndices;
        final Set<Mesh> meshes = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Attributes, Integer> materials = new HashMap<>();
        final Set<ChunkMaterial> chunkMaterials = new HashSet<>();
        final Set<Integer> chunks = new HashSet<>();
        final Set<List<String>> textureSets = new HashSet<>();
        final Set<Object> waterFields = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> ids = new LinkedHashSet<>();
    }

    private final GpuTerrain terrain;
    private final ModelBatch batch;
    private final Field sorterField;
    private final RenderableSorter original;
    private final Map<Mesh, Origin> origins = new IdentityHashMap<>();
    private final Map<Shader, Integer> shaders = new IdentityHashMap<>();
    private final Map<Row, Counts> rows = new LinkedHashMap<>();
    private final Map<String, Integer> passes = new HashMap<>();
    private final Map<String, Integer> categories = new LinkedHashMap<>();
    private final Map<Long, String> masks = new LinkedHashMap<>();
    private String stage = "unlabelled";
    private boolean indexed;
    private long terrainPageBytes;
    private final FamilyTiming timing;

    GpuDrawCallAudit(GpuBattleView view) { this(view, false); }

    /** Timed mode is a separate diagnostic, with the same original sorter and submitted batches. */
    GpuDrawCallAudit(GpuBattleView view, boolean timed) {
        timing = timed ? new FamilyTiming() : null;
        terrain = (GpuTerrain) field(view, "terrain");
        batch = (ModelBatch) field(terrain, "batch");
        original = batch.getRenderableSorter();
        try {
            sorterField = ModelBatch.class.getDeclaredField("sorter");
            sorterField.setAccessible(true);
            sorterField.set(batch, this);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    void stage(String stage) { this.stage = stage; }

    @Override
    public void sort(Camera camera, Array<Renderable> renderables) {
        if (timing != null) { timing.restore(renderables); }
        original.sort(camera, renderables);
        // Capture installed ownership after this frame's scene update, without asking providers to submit again.
        if (!indexed) { indexOrigins(); indexed = true; }
        if (timing != null) { timing.wrap(renderables); return; }
        String pass = stage + "/" + passes.merge(stage, 1, Integer::sum);
        for (Renderable combined : renderables) {
            for (Renderable part : GpuTerrainBatch.ranges(combined)) {
                if (part.meshPart.size == 0) { continue; }
                Origin origin = origin(part);
                String family = family(part);
                BlendingAttribute blend = part.material.get(BlendingAttribute.class, BlendingAttribute.Type);
                Row row = new Row(pass, origin.category(), family,
                      shaders.computeIfAbsent(part.shader, ignored -> shaders.size() + 1), part.material.getMask(),
                      part.environment == null ? 0 : part.environment.getMask(),
                      part.meshPart.mesh.getVertexAttributes().getMaskWithSizePacked(), blend != null && blend.blended);
                Counts counts = rows.computeIfAbsent(row, ignored -> new Counts());
                counts.draws++;
                counts.submittedIndices += part.meshPart.size;
                counts.meshes.add(part.meshPart.mesh);
                // Attributes equality ignores Material.id, which does not affect shader inputs.
                Attributes attributes = new Attributes();
                attributes.set(part.material);
                counts.materials.merge(attributes, 1, Integer::sum);
                if (origin.chunk() >= 0) {
                    counts.knownChunkDraws++;
                    counts.chunks.add(origin.chunk());
                    counts.chunkMaterials.add(new ChunkMaterial(origin.chunk(), attributes));
                }
                if (counts.ids.size() < 3) { counts.ids.add(part.material.id); }
                List<String> textures = new ArrayList<>();
                List<String> aliases = new ArrayList<>();
                for (Attribute attribute : part.material) {
                    aliases.add(Attribute.getAttributeAlias(attribute.type));
                    if (attribute instanceof TextureAttribute texture) {
                        textures.add(Attribute.getAttributeAlias(attribute.type) + "="
                              + texture.textureDescription.texture.getTextureObjectHandle());
                    } else if (attribute instanceof GpuWaterShader) {
                        Object field = field(attribute, "field");
                        if (field != null) {
                            counts.waterFields.add(field);
                            textures.add("waterField=" + ((Texture) field(field, "texture")).getTextureObjectHandle());
                        }
                    }
                }
                counts.textureSets.add(List.copyOf(textures));
                masks.putIfAbsent(row.materialMask(), String.join("+", aliases));
                categories.merge(stage + "/" + origin.category(), 1, Integer::sum);
            }
        }
    }

    void beginTimingFrame() { timing.beginFrame(); }

    /** Complete/read queries only after the actual full frame, outside every ordinary CPU/GPU sample. */
    void endTimingFrame(boolean measured) { timing.endFrame(measured); }

    String timingReport(String scenario) { return timing.report(scenario); }

    private record TimeRow(String stage, String source, String family, String shaders) { }
    private record TimeSpan(TimeRow row, int slot, int draws) { }

    private static final class TimeCounts {
        long nanos;
        int spans, draws;
    }

    /** Query pairs are reused between complete diagnostic frames, never nested with a draw's internal work. */
    private final class FamilyTiming {
        private final Map<Shader, Timed> wrappers = new IdentityHashMap<>();
        private final Set<Renderable> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<int[]> queries = new ArrayList<>();
        private final List<TimeSpan> pending = new ArrayList<>();
        private final List<Map<TimeRow, TimeCounts>> frames = new ArrayList<>();
        private final List<Double> totals = new ArrayList<>();
        private final Map<TimeRow, Set<String>> examples = new LinkedHashMap<>();
        private final int[] frameQueries = new int[2];
        private final long timestampMask;
        private boolean active;

        FamilyTiming() {
            var capabilities = GL.getCapabilities();
            int bits = capabilities.OpenGL33 || capabilities.GL_ARB_timer_query
                  ? GL15.glGetQueryi(ARBTimerQuery.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) : 0;
            if (bits == 0) { throw new IllegalStateException("Family attribution requires GPU timestamps"); }
            timestampMask = bits == 64 ? -1L : (1L << bits) - 1;
            for (int i = 0; i < 2; i++) { frameQueries[i] = GL15.glGenQueries(); }
        }

        void restore(Iterable<Renderable> parts) {
            for (Renderable part : parts) {
                if (part.shader instanceof Timed timed && timed.owner == this) { part.shader = timed.inner; }
            }
        }

        void wrap(Array<Renderable> parts) {
            if (!active) { throw new IllegalStateException("Draw outside family diagnostic frame"); }
            for (Renderable part : parts) {
                touched.add(part);
                part.shader = wrappers.computeIfAbsent(part.shader, inner -> new Timed(inner, this));
            }
        }

        void beginFrame() {
            if (active || !pending.isEmpty()) { throw new IllegalStateException("Unfinished family diagnostic frame"); }
            active = true;
            ARBTimerQuery.glQueryCounter(frameQueries[0], ARBTimerQuery.GL_TIMESTAMP);
        }

        void draw(Shader inner, Renderable combined) {
            // Restore before delegation: Group.ranges and the next frame's depth twins see the original shader.
            combined.shader = inner;
            Set<String> sources = new TreeSet<>(), families = new TreeSet<>(), programs = new TreeSet<>();
            Set<String> ids = new LinkedHashSet<>();
            int draws = 0;
            for (Renderable part : GpuTerrainBatch.ranges(combined)) {
                if (part.meshPart.size == 0) { continue; }
                draws++;
                sources.add(origin(part).category());
                families.add(timingFamily(part));
                Shader shader = GpuShaderProvider.unwrap(part.shader);
                programs.add(Integer.toString(shaders.computeIfAbsent(shader, ignored -> shaders.size() + 1)));
                if (ids.size() < 3) { ids.add(String.valueOf(part.material.id).replace(',', ';')); }
            }
            TimeRow row = new TimeRow(stage, label(sources), label(families), String.join("|", programs));
            Set<String> retained = examples.computeIfAbsent(row, ignored -> new LinkedHashSet<>());
            for (String id : ids) { if (retained.size() < 3) { retained.add(id); } }
            int slot = pending.size();
            if (slot == queries.size()) { queries.add(new int[] { GL15.glGenQueries(), GL15.glGenQueries() }); }
            int[] pair = queries.get(slot);
            ARBTimerQuery.glQueryCounter(pair[0], ARBTimerQuery.GL_TIMESTAMP);
            try { inner.render(combined); }
            finally { ARBTimerQuery.glQueryCounter(pair[1], ARBTimerQuery.GL_TIMESTAMP); }
            pending.add(new TimeSpan(row, slot, draws));
        }

        void endFrame(boolean measured) {
            ARBTimerQuery.glQueryCounter(frameQueries[1], ARBTimerQuery.GL_TIMESTAMP);
            active = false;
            restore(touched);
            touched.clear();
            Gdx.gl.glFinish();
            Map<TimeRow, TimeCounts> result = new LinkedHashMap<>();
            for (TimeSpan span : pending) {
                long nanos = elapsed(queries.get(span.slot()));
                TimeCounts counts = result.computeIfAbsent(span.row(), ignored -> new TimeCounts());
                counts.nanos += nanos;
                counts.spans++;
                counts.draws += span.draws();
            }
            long total = elapsed(frameQueries);
            pending.clear();
            if (measured) { frames.add(result); totals.add(total / 1e6); }
        }

        private long elapsed(int[] pair) {
            long before = ARBTimerQuery.glGetQueryObjectui64(pair[0], GL15.GL_QUERY_RESULT);
            long after = ARBTimerQuery.glGetQueryObjectui64(pair[1], GL15.GL_QUERY_RESULT);
            return (after - before) & timestampMask;
        }

        String report(String scenario) {
            if (frames.isEmpty()) { throw new IllegalStateException("No measured family diagnostic frames"); }
            var report = new StringBuilder(String.format(java.util.Locale.ROOT,
                  "FAMILY GPU %s: frames=%d diagnosticWholeFrameMedianMs=%.4f diagnosticWholeFrameP95Ms=%.4f%n",
                  scenario, frames.size(), percentile(totals, .5), percentile(totals, .95)));
            report.append("stage,source,family,shaders,frames,renderSpansPerFrame,drawsPerFrame,gpuMedianMs,gpuP95Ms,materialExamples\n");
            Set<TimeRow> rows = new LinkedHashSet<>();
            for (var frame : frames) { rows.addAll(frame.keySet()); }
            for (TimeRow row : rows) {
                List<Double> times = new ArrayList<>();
                int spans = 0, draws = 0;
                for (var frame : frames) {
                    TimeCounts counts = frame.get(row);
                    times.add(counts == null ? 0 : counts.nanos / 1e6);
                    if (counts != null) { spans += counts.spans; draws += counts.draws; }
                }
                report.append(String.format(java.util.Locale.ROOT, "%s,%s,%s,%s,%d,%.2f,%.2f,%.4f,%.4f,%s%n",
                      row.stage(), row.source(), row.family(), row.shaders(), frames.size(),
                      spans / (double) frames.size(), draws / (double) frames.size(),
                      percentile(times, .5), percentile(times, .95), String.join("|", examples.get(row))));
            }
            report.append("FAMILY GPU limits: separate query-instrumented diagnostic, not ordinary frame performance. ")
                  .append("Each interval includes inner.render (material binding and submitted geometry), excludes Shader.begin/end; ")
                  .append("query boundaries can perturb GPU scheduling. Compare whole diagnostic time with the ordinary total. ")
                  .append("Main terrain ModelBatch only: excludes separate terrain depth twins, shadows, units, particle batches and UI. ")
                  .append("Actual batching, order, depth and blending are retained; mixed groups are never split. ")
                  .append("Family names describe the submitted material and its cover palette, not isolated texture-sample costs. ")
                  .append("Animation advances normally; camera stays at the reported steady view. Per-row percentiles cannot be summed.\n");
            return report.toString();
        }

        void dispose() {
            restore(touched);
            touched.clear();
            for (int[] pair : queries) { for (int query : pair) { GL15.glDeleteQueries(query); } }
            for (int query : frameQueries) { GL15.glDeleteQueries(query); }
        }
    }

    private static String label(Set<String> values) {
        return values.isEmpty() ? "empty" : values.size() == 1 ? values.iterator().next()
              : "mixed[" + String.join("|", values) + "]";
    }

    private static double percentile(List<Double> values, double fraction) {
        List<Double> ordered = values.stream().sorted().toList();
        return ordered.get(Math.min(ordered.size() - 1, (int) (ordered.size() * fraction)));
    }

    /** The actual material role, including blended palettes; no claim to separate costs inside one shader draw. */
    private static String timingFamily(Renderable part) {
        String family = family(part);
        if (!family.equals("-")) {
            var blend = part.material.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
            if (blend != null) {
                Set<String> covers = new TreeSet<>();
                for (float value : blend.families) { covers.add(surfaceFamily((int) value)); }
                family += "+cover[" + String.join("|", covers) + "]";
            }
            if (part.material.has(GpuMagmaShader.TYPE)) { family += "+magma-" + field(part.material.get(GpuMagmaShader.TYPE), "mode"); }
            return family;
        }
        if (part.material.has(GpuMagmaShader.TYPE)) { return "magma-" + field(part.material.get(GpuMagmaShader.TYPE), "mode"); }
        for (String marker : List.of("boardImpostor", "boardFoliage", "boardBiomeVegetation", "boardVegetationWind",
              "boardPit", "boardGround", "boardBridgeDeck", "boardModelSurface", "boardBuildingCutaway")) {
            long type = Attribute.getAttributeType(marker);
            if (type != 0 && part.material.has(type)) { return marker; }
        }
        return "model/other";
    }

    private static String surfaceFamily(int family) {
        if (family == BoardSurfaceBlend.CRUST) { return "CRUST"; }
        if (family == BoardSurfaceBlend.BANK) { return "VOLCANIC_BANK"; }
        return family >= 0 && family < BoardScene.Surface.values().length
              ? BoardScene.Surface.values()[family].name() : "bed-" + family;
    }

    /** One wrapper per original shader, so ModelBatch retains the original begin/end grouping. */
    private static final class Timed implements Shader {
        private final Shader inner;
        private final GpuDrawCallAudit.FamilyTiming owner;
        Timed(Shader inner, GpuDrawCallAudit.FamilyTiming owner) { this.inner = inner; this.owner = owner; }
        @Override public void init() { }
        @Override public boolean canRender(Renderable part) { return inner.canRender(part); }
        @Override public int compareTo(Shader other) { return inner.compareTo(other instanceof Timed timed ? timed.inner : other); }
        @Override public void begin(Camera camera, RenderContext context) { inner.begin(camera, context); }
        @Override public void render(Renderable part) { owner.draw(inner, part); }
        @Override public void end() { inner.end(); }
        @Override public void dispose() { /* The terrain's provider owns the borrowed shader. */ }
    }

    /**
     * The audited draws by source ("instanced-trees" for trees, placed objects and building modules, "grass", "scatter",
     * "water", ...) and by family ("water-..." for every water surface), summed over the passes.
     */
    Map<String, Integer> totals() {
        Map<String, Integer> result = new HashMap<>();
        rows.forEach((row, counts) -> {
            result.merge(row.category(), counts.draws, Integer::sum);
            result.merge(row.family(), counts.draws, Integer::sum);
        });
        return result;
    }

    /** The audited draws from {@code category} whose material has an attribute of {@code type}, over the passes. */
    int draws(String category, long type) {
        return rows.entrySet().stream()
              .filter(entry -> entry.getKey().category().equals(category) && (entry.getKey().materialMask() & type) != 0)
              .mapToInt(entry -> entry.getValue().draws).sum();
    }

    void report(String scenario) {
        System.out.printf("DRAW AUDIT %s: terrain colour ModelBatch only; stage/category submitted draws=%s%n",
              scenario, categories);
        System.out.printf("DRAW AUDIT terrainPageGeometry=%.3f MiB (vertex/index capacity; CPU and GPU each store a copy)%n",
              terrainPageBytes / 1048576.0);
        System.out.println("pass,source,family,shader,materialMask,environmentMask,vertexMask,blended,draws,indices,"
              + "meshes,usedVertices,vertexCapacity,maxMeshVertices,meshesAt48k,meshesAt65532,materialValues,"
              + "repeatedMaterialDraws,knownChunks,knownChunkDraws,chunkMaterialBuckets,sameChunkMaterialExtraDraws,"
              + "textureSets,waterFields,largestMeshUsedCapacity,materialIdExamples");
        var ordered = rows.entrySet().stream()
              .sorted(Comparator.<Map.Entry<Row, Counts>>comparingInt(entry -> entry.getValue().draws).reversed()).toList();
        for (var entry : ordered.stream().limit(24).toList()) {
            Row row = entry.getKey();
            Counts counts = entry.getValue();
            long used = 0, capacity = 0;
            int maximum = 0, near48k = 0, nearCeiling = 0;
            for (Mesh mesh : counts.meshes) {
                int vertices = mesh.getNumVertices();
                used += vertices;
                capacity += mesh.getMaxVertices();
                maximum = Math.max(maximum, vertices);
                if (vertices >= 48000) { near48k++; }
                if (vertices >= 65532) { nearCeiling++; }
            }
            String largest = String.join("|", counts.meshes.stream()
                  .sorted(Comparator.comparingInt(Mesh::getNumVertices).reversed()).limit(3)
                  .map(mesh -> mesh.getNumVertices() + "/" + mesh.getMaxVertices()).toList());
            System.out.printf(java.util.Locale.ROOT,
                  "%s,%s,%s,%d,%x,%x,%x,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s%n",
                  row.pass(), row.category(), row.family(), row.shader(), row.materialMask(), row.environmentMask(),
                  row.vertexMask(), row.blended(), counts.draws, counts.submittedIndices, counts.meshes.size(),
                  used, capacity, maximum, near48k, nearCeiling, counts.materials.size(),
                  counts.draws - counts.materials.size(), counts.chunks.size(), counts.knownChunkDraws,
                  counts.chunkMaterials.size(), counts.knownChunkDraws - counts.chunkMaterials.size(),
                  counts.textureSets.size(), counts.waterFields.size(), largest, String.join("|", counts.ids));
        }
        int omitted = ordered.stream().skip(24).mapToInt(entry -> entry.getValue().draws).sum();
        System.out.printf("DRAW AUDIT omittedRows=%d omittedDraws=%d%n", Math.max(0, rows.size() - 24), omitted);
        masks.entrySet().stream().limit(24).forEach(entry ->
              System.out.printf("DRAW AUDIT materialMask=%x attributes=%s%n", entry.getKey(), entry.getValue()));
        System.out.println("DRAW AUDIT limits: counts describe submissions, not GPU work or guaranteed mergeable draws. "
              + "Indices exclude instance multiplication. Vertices/capacity count each mesh once per row; parts can share meshes. "
              + "48k is Layer's pre-shape split threshold, 65536 its unsigned-short address space. "
              + "Chunk/material buckets exclude shared or instanced meshes with ambiguous ownership. "
              + "Material equality retains texture/UV/uniform values; different water fields are per-chunk inputs. "
              + "Blended order, pass boundaries, transforms and vertex limits still constrain batching.");
    }

    private Origin origin(Renderable part) {
        if (GpuTreeInstances.instanced(part)) { return new Origin("instanced-trees", -1); }
        if (part.material.has(GpuGroundCover.Wind.TYPE)) { return new Origin("grass", -1); }
        return origins.getOrDefault(part.meshPart.mesh, new Origin("other", -1));
    }

    private void indexOrigins() {
        // Frozen baseline classes predate this optional aggregate. Read its existing ranges without re-submitting
        // through their provider, which would clear shader/environment on the batch's queued objects.
        Object props = field(terrain, "propBatch", true);
        if (props != null) {
            for (Object page : ((Map<?, ?>) field(props, "pages")).values()) {
                for (Object value : (Array<?>) field(page, "cached")) {
                    addOrigin(((Renderable) value).meshPart.mesh, "props-batched", -1);
                }
            }
        }
        Object terrainPages = field(terrain, "terrainPages", true);
        if (terrainPages != null) {
            for (Object page : ((Map<?, ?>) field(terrainPages, "pages")).values()) {
                for (Object value : (Array<?>) field(page, "cached")) {
                    Mesh mesh = ((Renderable) value).meshPart.mesh;
                    addOrigin(mesh, "terrain-paged", -1);
                }
            }
            terrainPageBytes = pageBytes(terrainPages);
        }
        List<?> chunks = (List<?>) field(terrain, "chunks");
        for (int index = 0; index < chunks.size(); index++) {
            Object chunk = chunks.get(index);
            for (String category : List.of("opaque", "scatter", "overlays", "water")) {
                for (Object value : (List<?>) field(chunk, category)) {
                    for (Mesh mesh : ((ModelInstance) value).model.meshes) { addOrigin(mesh, category, index); }
                }
            }
            for (String cache : List.of("propRenderables", "shadowPropRenderables")) {
                for (Object value : (Array<?>) field(chunk, cache)) {
                    addOrigin(((Renderable) value).meshPart.mesh, "props", index);
                }
            }
            for (Object prop : (Set<?>) field(chunk, "faded")) {
                for (Mesh mesh : ((ModelInstance) field(prop, "instance")).model.meshes) {
                    addOrigin(mesh, "faded-props", index);
                }
            }
        }
    }

    private void addOrigin(Mesh mesh, String category, int chunk) {
        Origin previous = origins.get(mesh);
        origins.put(mesh, previous == null ? new Origin(category, chunk)
              : new Origin(previous.category().equals(category) ? category : "shared", previous.chunk() == chunk ? chunk : -1));
    }

    private static String family(Renderable part) {
        for (Attribute attribute : part.material) {
            String alias = Attribute.getAttributeAlias(attribute.type);
            if ("boardSculpt".equals(alias) && attribute instanceof FloatAttribute value) {
                int family = (int) value.value;
                return surfaceFamily(family);
            }
            if (attribute instanceof GpuWaterShader water) {
                return "water-" + field(water, "palette") + "-" + water.mode.name().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "-";
    }

    static long pageBytes(Object cache) {
        Set<Mesh> meshes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object page : ((Map<?, ?>) field(cache, "pages")).values()) {
            for (Object value : (Array<?>) field(page, "cached")) { meshes.add(((Renderable) value).meshPart.mesh); }
        }
        return meshes.stream().mapToLong(mesh -> (long) mesh.getMaxVertices() * mesh.getVertexSize()
              + (long) mesh.getMaxIndices() * Short.BYTES).sum();
    }

    private static Object field(Object object, String name) {
        return field(object, name, false);
    }

    private static Object field(Object object, String name, boolean optional) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // The benchmark uses an anonymous GpuBattleView subclass.
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        if (optional) { return null; }
        throw new IllegalStateException("Missing audit field " + name + " on " + object.getClass());
    }

    @Override
    public void close() {
        try { sorterField.set(batch, original); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        finally { if (timing != null) { timing.dispose(); } }
    }
}
