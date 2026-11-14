/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.ShortArray;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL43C;
import megamek.logging.MMLogger;

/**
 * Every instanced draw of the board: building parts, placed objects, trees, fades, outlines and falling pieces, drawn
 * GPU-driven. The parts' geometry lives once in a pool per vertex layout; every placement lives in one board-wide
 * instance buffer, written when a snapshot of parts is first drawn or changes ({@link Instances}), never per frame.
 * When a pass's visible snapshots change, or the instances it hides by key ({@link #hide}), its instances are gathered
 * part by part into the pass's own buffer and one command per part is written ({@link #renderables}); a
 * pass drawn for a camera keeps only the instances it can see ({@link #cull}); a still view does neither. A pass draws
 * one renderable per material, layout and mirror (a group): one program and one {@code glMultiDrawElementsIndirect}.
 * (A command per part and snapshot instead cost the Iris Xe more GPU time than the same draws one by one: 31,000
 * commands for the city.)
 * <p>
 * On OpenGL 4.3 the gathering and culling are compute passes; on 4.1 (macOS) the runs are copied buffer to buffer and
 * each command is drawn alone with its instance attributes pointed at its first instance ({@link #full}). Instances keep
 * the format of {@link GpuTreeInstances} (tree-instances.glsl), read through divisor attributes; each also names its
 * run's piece record ({@link Pieces}), which the vertex stage reads from a buffer texture. Render thread.
 */
final class GpuIndirectInstances implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuIndirectInstances.class);
    /** Whether this process has logged the way its board draws. */
    private static boolean logged;
    /** {@code gl41} draws the OpenGL 4.1 way on any context ({@link #full}), as macOS does: its verification. */
    private static final boolean GL41 = "gl41".equals(System.getProperty("megamek.gpu.indirectDraws"));
    /**
     * Floats per instance: GpuTreeInstances' eight, four slot colours, which only colour-slotted layouts read, and a
     * falling piece's record ({@link #RECORD_LOCATION}: its index among the records, 0 for none) and three to spare.
     */
    static final int STRIDE = GpuTreeInstances.STRIDE + 8;
    private static final int RECORD = GpuTreeInstances.STRIDE + 4;
    /** The piece record's attribute (tree-instances.glsl a_instance3): a fixed location, not in any part's layout. */
    static final int RECORD_LOCATION = 12;
    /** Floats of a piece record ({@link Pieces#record}): three RGBA texels of the records' buffer texture. */
    static final int RECORD_FLOATS = 12;
    /** A record's float that holds its run's first vertex in its pool (gl_VertexID counts from there). */
    private static final int RECORD_BASE = 9;
    /** The unit the records' buffer texture is bound on: past GpuPoses#UNIT, which nothing else binds either. */
    static final int RECORDS_UNIT = GpuPoses.UNIT + 1;
    private static final int COMMAND = 5;
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final float[] NO_COLOURS = new float[4];
    /** A list's parts, groups and geometries are sorted in one long key: 21 bits each (#write). */
    private static final int KEY_BITS = 21;

    /**
     * The passes; each keeps its own commands, as each sees its own lists: standing props, trees and the falling pieces
     * in passes of their own, so that a tree that changes level or a fall that comes or goes does not regroup the
     * standing buildings; the cutouts' faded texels (FADE, the felled trees' in FALL_FADE) and the faded buildings'
     * outline depth (OUTLINE).
     */
    enum Pass {
        COLOUR, DEPTH, SHADOW, FALL_COLOUR, FALL_DEPTH, FALL_SHADOW, TREE_COLOUR, TREE_DEPTH, TREE_SHADOW, FADE, FALL_FADE,
        OUTLINE;

        /**
         * Whether its instances stand where they are placed, so that it draws only what its view's camera sees: not the
         * falls', whose pieces and trees move in the vertex stage away from where they stood.
         */
        boolean standing() {
            return this != FALL_COLOUR && this != FALL_DEPTH && this != FALL_SHADOW && this != FALL_FADE;
        }
    }

    /**
     * What a list of falling pieces needs from its owner (GpuDestruction): each run's record, taken from the run's first
     * piece, and the record's release with its list.
     */
    interface Pieces {
        /**
         * {@link #RECORD_FLOATS} floats: the piece's box centre and what moves it (GpuInstancedMesh#piece), its half size
         * and the fragments it broke into (0: whole), then its fragment table's first texel among the pose texture's
         * table rows; the tenth float is left for the run's first vertex.
         */
        float[] record(Renderable sample);

        /** The list that held {@code record}, made by {@link #record} for {@code sample}, goes. */
        void released(Renderable sample, float[] record);
    }

    /**
     * Whether the context has OpenGL 4.3's compute passes, storage buffers and multi-draw indirect (Windows, Linux),
     * else the 4.1 way (macOS, or {@link #GL41}): the same pools, lists and passes, a pass's runs copied buffer to buffer
     * and each command drawn alone, without culling.
     */
    private static boolean fullContext() {
        return !GL41 && GL.getCapabilities().OpenGL43;
    }

    /** Multi-draws issued and the commands they drew, since start (benchmarks). */
    static long multiDraws, commandsDrawn;
    /**
     * Indices the drawn commands name, times the instances uploaded for them (before the culling pass): the geometry the
     * path was given to draw, as a profiler counts a draw call's (tests).
     */
    static long indicesDrawn;
    /** Lists written so far: an unchanged view writes none (the terrain's statistics, its tests). */
    private long writes;

    /** One vertex layout's parts: their vertices, indices and the vertex array that draws them with the instances. */
    private final class Pool {
        final VertexAttributes layout, instanceLayout;
        final GpuBufferStore vertices, indices = new GpuBufferStore(Short.BYTES);
        final View[] views = new View[Pass.values().length * 2];
        private final Binding[] bindings = new Binding[Pass.values().length];

        Pool(VertexAttributes layout) {
            this.layout = layout;
            instanceLayout = GpuTreeInstances.instanceAttributes(layout);
            vertices = new GpuBufferStore(layout.vertexSize);
        }

        View view(Pass pass, boolean mirrored) {
            int index = pass.ordinal() * 2 + (mirrored ? 1 : 0);
            if (views[index] == null) { views[index] = new View(this, pass, mirrored); }
            return views[index];
        }

        /** A pass's vertex array: the pool's vertices and indices, and the pass's instances. */
        private final class Binding {
            int vao;
            int[] locations = new int[0], instanceLocations = new int[0];
            /** How many of the instance attributes it points ({@link #pointInstances}). */
            int instanced;
            final IntArray enabled = new IntArray();
            int vertexGeneration = -1, indexGeneration = -1, instanceGeneration = -1;
        }

        /** Binds the pass's vertex array, pointing it at the shader's locations where they or the buffers changed. */
        void bind(Pass pass, int[] shaderLocations, int[] shaderInstanceLocations) {
            GL30 gl = Gdx.gl30;
            Binding binding = bindings[pass.ordinal()];
            if (binding == null) {
                bindings[pass.ordinal()] = binding = new Binding();
                int[] names = new int[1];
                gl.glGenVertexArrays(1, names, 0);
                binding.vao = names[0];
            }
            gl.glBindVertexArray(binding.vao);
            Drawn drawn = passes[pass.ordinal()];
            int count = layout.size(), instanced = instanceLayout.size();
            if (binding.vertexGeneration == vertices.generation && binding.indexGeneration == indices.generation
                  && binding.instanceGeneration == drawn.outputGeneration
                  && same(binding.locations, shaderLocations, count)
                  && same(binding.instanceLocations, shaderInstanceLocations, instanced)) { return; }
            for (int i = 0; i < binding.enabled.size; i++) { gl.glDisableVertexAttribArray(binding.enabled.get(i)); }
            binding.enabled.clear();
            binding.locations = Arrays.copyOf(shaderLocations, count);
            binding.instanceLocations = Arrays.copyOf(shaderInstanceLocations, instanced);
            gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, vertices.handle);
            for (int i = 0; i < count; i++) {
                if (binding.locations[i] >= 0) {
                    VertexAttribute attribute = layout.get(i);
                    point(binding, binding.locations[i], attribute.numComponents, attribute.type, attribute.normalized,
                          layout.vertexSize, 0, attribute.offset);
                }
            }
            gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, drawn.output);
            for (int i = 0; i < instanced; i++) {
                if (binding.instanceLocations[i] >= 0) {
                    VertexAttribute attribute = instanceLayout.get(i);
                    point(binding, binding.instanceLocations[i], attribute.numComponents, attribute.type,
                          attribute.normalized, STRIDE * Float.BYTES, 1, attribute.offset);
                }
            }
            // Not among the parts' instance attributes, which choose their programs: its location is fixed instead.
            point(binding, RECORD_LOCATION, 4, GL20.GL_FLOAT, false, STRIDE * Float.BYTES, 1, RECORD * Float.BYTES);
            binding.instanced = instanced;
            gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
            gl.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, indices.handle);
            binding.vertexGeneration = vertices.generation;
            binding.indexGeneration = indices.generation;
            binding.instanceGeneration = drawn.outputGeneration;
        }

        private void point(Binding binding, int location, int components, int type, boolean normalized, int stride,
              int divisor, int offset) {
            GL30 gl = Gdx.gl30;
            gl.glEnableVertexAttribArray(location);
            gl.glVertexAttribPointer(location, components, type, normalized, stride, offset);
            gl.glVertexAttribDivisor(location, divisor);
            binding.enabled.add(location);
        }

        /**
         * The 4.1 way's draws (#drawAlone): the pass's instance attributes, already enabled and divided, pointed at
         * {@code offset} bytes into the bound array buffer.
         */
        void pointInstances(Binding binding, long offset) {
            for (int i = 0; i < binding.instanced; i++) {
                if (binding.instanceLocations[i] < 0) { continue; }
                VertexAttribute attribute = instanceLayout.get(i);
                GL20C.glVertexAttribPointer(binding.instanceLocations[i], attribute.numComponents, attribute.type,
                      attribute.normalized, STRIDE * Float.BYTES, offset + attribute.offset);
            }
            GL20C.glVertexAttribPointer(RECORD_LOCATION, 4, GL20.GL_FLOAT, false, STRIDE * Float.BYTES,
                  offset + RECORD * Float.BYTES);
        }

        private static boolean same(int[] bound, int[] asked, int count) {
            if (bound.length != count) { return false; }
            for (int i = 0; i < count; i++) {
                if (bound[i] != asked[i]) { return false; }
            }
            return true;
        }

        void dispose() {
            for (View view : views) { if (view != null) { view.dispose(); } }
            for (Binding binding : bindings) {
                if (binding != null) { Gdx.gl30.glDeleteVertexArrays(1, new int[] { binding.vao }, 0); }
            }
            Arrays.fill(bindings, null);
            vertices.dispose();
            indices.dispose();
        }
    }

    /**
     * What a renderable of a pool draws in a pass: the commands named by its mesh part's offset (the first) and size
     * (how many). Its layout and instance layout are the parts' own, so the shader providers choose the parts' instanced
     * programs (GpuTerrain#prepareTerrainMaterial). The bridge through libGDX that ModelBatch and
     * its shader providers still need, for the programs and their global inputs (lights, shadows, clouds).
     */
    private final class View extends Mesh {
        final Pool pool;
        final Pass pass;
        final boolean mirrored;

        View(Pool pool, Pass pass, boolean mirrored) {
            super(true, 0, 0, pool.layout);
            VertexAttribute[] placements = new VertexAttribute[pool.instanceLayout.size()];
            for (int i = 0; i < placements.length; i++) { placements[i] = pool.instanceLayout.get(i).copy(); }
            enableInstancedRendering(true, 1, placements);
            this.pool = pool;
            this.pass = pass;
            this.mirrored = mirrored;
        }

        @Override
        public void bind(ShaderProgram shader) {
            int[] at = new int[pool.layout.size()], instanced = new int[pool.instanceLayout.size()];
            for (int i = 0; i < at.length; i++) { at[i] = shader.getAttributeLocation(pool.layout.get(i).alias); }
            for (int i = 0; i < instanced.length; i++) {
                instanced[i] = shader.getAttributeLocation(pool.instanceLayout.get(i).alias);
            }
            pool.bind(pass, at, instanced);
        }

        @Override
        public void bind(ShaderProgram shader, int[] locations, int[] instanceLocations) {
            pool.bind(pass, locations, instanceLocations);
        }

        @Override
        public void unbind(ShaderProgram shader) { Gdx.gl30.glBindVertexArray(0); }

        @Override
        public void unbind(ShaderProgram shader, int[] locations, int[] instanceLocations) {
            Gdx.gl30.glBindVertexArray(0);
        }

        @Override
        public void render(ShaderProgram shader, int primitiveType, int offset, int count, boolean autoBind) {
            if (count <= 0) { return; }
            if (autoBind) { bind(shader); }
            Drawn drawn = passes[pass.ordinal()];
            if (full) { draw(drawn, offset, count, mirrored); } else { drawAlone(drawn, pool, pass, offset, count, mirrored); }
            if (autoBind) { unbind(shader); }
        }
    }

    /** A part's place in its pool. */
    private record Range(Mesh mesh, int offset, int size) { }

    private static final class Geometry {
        final Range key;
        final Pool pool;
        final int id;
        /** vertex, vertices, index, indices: its place in its pool; bound: its bounding sphere's among {@link #bounds}. */
        int vertex, vertices, index, indices, bound, users;

        Geometry(Range key, Pool pool, int id) {
            this.key = key;
            this.pool = pool;
            this.id = id;
        }
    }

    /** One multi-draw a pass: its parts' material (a copy), pool and mirror, and whether they dissolve (#list). */
    private record Key(Material material, VertexAttributes layout, boolean mirrored, boolean dissolves) { }

    private final class Group {
        final Key key;
        final Pool pool;
        final int id;
        final Material material;
        /** What it draws, the same in every run and for its dissolve's twin ({@link #content}). */
        final int content;
        final Renderable[] renderables = new Renderable[Pass.values().length];
        /** Each pass's parts of this group, in the order of their commands. */
        final List<List<Part>> parts = new ArrayList<>();
        int users;

        Group(Key key, Pool pool, int id) {
            this.key = key;
            this.pool = pool;
            this.id = id;
            content = content(key);
            Material instanced = GpuTreeInstances.instancedMaterial(key.material());
            material = key.dissolves() ? GpuTreeInstances.dissolving(instanced, true) : instanced;
            for (Pass pass : Pass.values()) {
                Renderable renderable = new Renderable();
                renderable.material = material;
                renderable.meshPart.mesh = pool.view(pass, key.mirrored());
                renderable.meshPart.primitiveType = GL20.GL_TRIANGLES;
                renderables[pass.ordinal()] = renderable;
                parts.add(new ArrayList<>());
            }
        }
    }

    /**
     * A snapshot's instances: written once into a range of the instance buffer, sorted by group and part, so that each
     * run of one part is one command. Released when its owner replaces the snapshot or goes.
     */
    final class Instances {
        private final Object source;
        private final int revision;
        private final int first, count;
        private final Group[] groups;
        private final Geometry[] geometries;
        private final int[] starts, sizes;
        /** Each instance's key in the list's order ({@link #hide}), and the keys it holds; null for a list of none. */
        private final int[] keys, held;
        /** Each instance's part's index among the parts written, which a pass's selection names ({@link #add}). */
        private final int[] indices;
        /** A pieces list's records, one a run from {@code record} on, as {@link #pieces} made them; else null. */
        private final float[][] records;
        private final Renderable[] samples;
        private final Pieces pieces;
        private final int record;
        private boolean released;

        private Instances(Object source, int revision, int first, int count, Group[] groups, Geometry[] geometries,
              int[] starts, int[] sizes, int[] keys, int[] held, int[] indices, Pieces pieces, int record,
              Renderable[] samples, float[][] records) {
            this.source = source;
            this.revision = revision;
            this.indices = indices;
            this.first = first;
            this.count = count;
            this.groups = groups;
            this.geometries = geometries;
            this.starts = starts;
            this.sizes = sizes;
            this.keys = keys;
            this.held = held;
            this.pieces = pieces;
            this.record = record;
            this.samples = samples;
            this.records = records;
        }

        /** How many instances it holds. */
        int count() { return count; }

        /**
         * Gives its range, its records and its parts back; a part no list holds stays placed in its pool until
         * {@link #retain} frees it. Idempotent.
         */
        void release() {
            if (released) { return; }
            released = true;
            if (disposed) { return; }
            instances.give(first, count);
            if (records != null) {
                GpuIndirectInstances.this.records.give(record, records.length);
                for (int run = 0; run < records.length; run++) { pieces.released(samples[run], records[run]); }
            }
            for (int run = 0; run < groups.length; run++) {
                Group group = groups[run];
                if (--group.users == 0) { groupsByKey.remove(group.key); }
                geometries[run].users--;
            }
        }
    }

    /**
     * Frees the parts placed in the pools that no list holds and whose meshes are not {@code live}. A part a list let go
     * (a level the view left, a fall that sank) stays placed until then, so that coming back to it places nothing again:
     * a zoom across tree or building levels and back copied their geometry into the pools each time.
     */
    void retain(java.util.Set<Mesh> live) {
        geometriesByRange.values().removeIf(geometry -> {
            if (geometry.users > 0 || live.contains(geometry.key.mesh())) { return false; }
            geometry.pool.vertices.give(geometry.vertex, geometry.vertices);
            geometry.pool.indices.give(geometry.index, geometry.indices);
            bounds.give(geometry.bound, 1);
            return true;
        });
    }

    /** Null-safe {@link Instances#release}, for an owner that goes. */
    static void release(Instances list) {
        if (list != null) { list.release(); }
    }

    /** A pass's part: its group and geometry. */
    private record PartKey(int group, int geometry) { }

    /** One part of a pass: one command, over its runs' instances copied together. */
    private static final class Part {
        final Geometry geometry;
        /** (first instance in the board's buffer, count) a run. */
        final IntArray runs = new IntArray();
        int total;

        Part(Geometry geometry) { this.geometry = geometry; }
    }

    /**
     * A pass's snapshots this frame and what it last drew them with: its commands, and its instances, copied from the
     * board's buffer part by part (output); the hidden keys' revision it drew, where its lists hold keys. Each group's
     * commands go up in a buffer of their own, exactly their size ({@code buffers}, by the group's place among
     * {@link #groups}; {@code firsts}: its first command in {@code commands}): the Iris Xe's driver rejects a multi-draw
     * (GL_INVALID_OPERATION) from a buffer that holds further commands after the ones it draws, in some passes every one.
     */
    private static final class Drawn {
        final List<Instances> lists = new ArrayList<>(), drawn = new ArrayList<>();
        /** Each list's selection of its parts this frame ({@link #add}), null for all; and those drawn. */
        final List<BitSet> shown = new ArrayList<>(), drawnShown = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
        final Map<PartKey, Part> parts = new HashMap<>();
        final Array<Renderable> renderables = new Array<>();
        final IntArray buffers = new IntArray(), firsts = new IntArray();
        int output, outputCapacity, outputGeneration, jobs;
        /**
         * A culled pass's commands as the culling pass reads them ({@link #cull}): a command's first job and jobs, its
         * geometry's bounding sphere and its first instance; and the view's side planes it was culled for, null for
         * none since it was last built.
         */
        int partBuffer, commandCount;
        float[] culledFor;
        /** Whether it was built for the culling pass (its jobs and parts uploaded, its instances left to it). */
        boolean builtCulled;
        /** The keys this pass hides ({@link #hide}) and their revision; the revision its jobs were built for. */
        BitSet hidden = new BitSet();
        int hiddenRevision, builtRevision;
        boolean keyed;
        int[] commands = new int[0];
    }

    private final Map<VertexAttributes, Pool> pools = new HashMap<>();
    private final Map<Range, Geometry> geometriesByRange = new HashMap<>();
    private final Map<Key, Group> groupsByKey = new HashMap<>();
    private final GpuBufferStore instances = new GpuBufferStore(STRIDE * Float.BYTES);
    /** The falling pieces' records ({@link Pieces#record}); its first is no record (an instance's 0 names none). */
    private final GpuBufferStore records = new GpuBufferStore(RECORD_FLOATS * Float.BYTES);
    /** Each geometry's bounding sphere in its model's frame, centre and radius ({@link #cull}). */
    private final GpuBufferStore bounds = new GpuBufferStore(4 * Float.BYTES);
    private final int recordsTexture;
    private int recordsGeneration = -1;
    private final Drawn[] passes = new Drawn[Pass.values().length];
    private int nextGeometry, nextGroup, piecesPlaced;
    /** Whether the list being written holds pieces: its new parts count among {@link #piecesPlaced}. */
    private boolean placingPieces;
    private boolean disposed;
    private FloatBuffer floats = BufferUtils.newFloatBuffer(1024);
    private ShortBuffer shorts = BufferUtils.newShortBuffer(1024);
    private IntBuffer ints = BufferUtils.newIntBuffer(1024);

    /** How much larger a swaying instance's culling sphere is ({@link #CULL}). */
    private static final float SWAY_ROOM = 1.5f;
    /** Copies runs of instances from the board's buffer into a pass's: a work group a run (src, dst, count). */
    private static final String COMPACT = """
          #version 430
          layout(local_size_x = 64) in;
          layout(std430, binding = 0) readonly buffer Source { vec4 source[]; };
          layout(std430, binding = 1) writeonly buffer Target { vec4 target[]; };
          layout(std430, binding = 2) readonly buffer Jobs { ivec4 jobs[]; };
          uniform int u_jobs;
          void main() {
              int job = int(gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x);
              if (job >= u_jobs) { return; }
              ivec4 run = jobs[job];
              for (int i = int(gl_LocalInvocationID.x); i < run.z * VEC4S; i += 64) {
                  target[run.y * VEC4S + i] = source[run.x * VEC4S + i];
              }
          }
          """.replace("VEC4S", Integer.toString(STRIDE / 4));
    /**
     * The culling pass ({@link #cull}): a work group a command of one group, which keeps the instances of its part's runs
     * whose bounding spheres reach inside the view's side planes, in their order (a prefix sum a 64-instance step), and
     * writes their count into the group's command. The near and far planes cull nothing: the shadow map clamps depth.
     * An instance with a piece record (a standing tree) may sway about its foot in a blast (tree-instances.glsl, about
     * 0.3 rad a blast, more where blasts overlap): its sphere takes {@link #SWAY_ROOM} times its radius.
     */
    private static final String CULL = """
          #version 430
          layout(local_size_x = 64) in;
          layout(std430, binding = 0) readonly buffer Source { vec4 source[]; };
          layout(std430, binding = 1) writeonly buffer Target { vec4 target[]; };
          layout(std430, binding = 2) readonly buffer Jobs { ivec4 jobs[]; };
          layout(std430, binding = 3) buffer Commands { uint commands[]; };
          layout(std430, binding = 5) readonly buffer Parts { ivec4 parts[]; };
          layout(std430, binding = 6) readonly buffer Bounds { vec4 bounds[]; };
          uniform vec4 u_planes[4];
          uniform int u_firstPart, u_parts;
          shared uint kept[64];
          shared uint total;
          INSTANCE_PLACE
          void main() {
              uint command = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
              if (command >= uint(u_parts)) { return; }
              ivec4 part = parts[u_firstPart + int(command)];
              vec4 sphere = bounds[part.z];
              uint local = gl_LocalInvocationID.x;
              if (local == 0u) { total = 0u; }
              barrier();
              for (int job = part.x; job < part.x + part.y; job++) {
                  ivec4 run = jobs[job];
                  for (int start = 0; start < run.z; start += 64) {
                      int i = start + int(local);
                      int at = (run.x + i) * VEC4S;
                      bool shown = false;
                      if (i < run.z) {
                          vec4 place = source[at], turn = source[at + 1];
                          vec3 centre = placePoint(place, turn, sphere.xyz);
                          float radius = sphere.w * max(abs(place.w), turn.z) * (source[at + 3].x > 0.5 ? SWAY_ROOM : 1.0);
                          shown = true;
                          for (int p = 0; p < 4; p++) { shown = shown && dot(u_planes[p].xyz, centre) + u_planes[p].w >= -radius; }
                      }
                      kept[local] = shown ? 1u : 0u;
                      barrier();
                      for (uint step = 1u; step < 64u; step <<= 1) {
                          uint before = local >= step ? kept[local - step] : 0u;
                          barrier();
                          kept[local] += before;
                          barrier();
                      }
                      if (shown) {
                          int to = (part.w + int(total + kept[local]) - 1) * VEC4S;
                          for (int k = 0; k < VEC4S; k++) { target[to + k] = source[at + k]; }
                      }
                      barrier();
                      if (local == 63u) { total += kept[63]; }
                      barrier();
                  }
              }
              if (local == 0u) { commands[command * 5u + 1u] = total; }
          }
          """.replace("VEC4S", Integer.toString(STRIDE / 4)).replace("SWAY_ROOM", Float.toString(SWAY_ROOM));
    /** {@code false} draws every instance of the visible snapshots (a comparison); the culling pass runs otherwise. */
    private static final boolean CULLING = !"false".equals(System.getProperty("megamek.gpu.indirectCulling"));
    private static final int MAX_GROUPS = 65535;
    private final int compact, compactJobs, cull, cullPlanes, cullFirstPart, cullParts;
    /** The compute passes and multi-draws ({@link #fullContext}); false: the 4.1 way. */
    private final boolean full;

    private GpuIndirectInstances(int compact, int cull) {
        Arrays.setAll(passes, pass -> new Drawn());
        this.compact = compact;
        this.cull = cull;
        full = compact != 0;
        // Which way this card draws, once a process: a field report names it.
        if (!logged) {
            logged = true;
            LOGGER.info("3D board instanced draws: {}", full ? "culled and multi-drawn on the GPU" : "the OpenGL 4.1 way");
        }
        compactJobs = full ? GL20C.glGetUniformLocation(compact, "u_jobs") : -1;
        cullPlanes = full ? GL20C.glGetUniformLocation(cull, "u_planes") : -1;
        cullFirstPart = full ? GL20C.glGetUniformLocation(cull, "u_firstPart") : -1;
        cullParts = full ? GL20C.glGetUniformLocation(cull, "u_parts") : -1;
        // Record 0 stands for none, and the buffer texture always has a buffer: its programs never find it incomplete.
        records.take(1);
        recordsTexture = GL11C.glGenTextures();
        bindRecords();
    }

    /**
     * The path for this context, its compute programs linked now (at load, as every board program is, through the
     * program cache): the 4.1 way where the context lacks OpenGL 4.3 or a program does not link.
     */
    static GpuIndirectInstances create() {
        if (!fullContext()) { return new GpuIndirectInstances(0, 0); }
        int program = GpuGlProgram.compute("GpuIndirectInstances compaction", COMPACT);
        // Placed as the instanced vertex stages place them (instance-place.glsl).
        int cull = program == 0 ? 0 : GpuGlProgram.compute("GpuIndirectInstances culling",
              CULL.replace("INSTANCE_PLACE", GpuShaderSource.read("instance-place.glsl")));
        if (program == 0 || cull == 0) {
            // A driver that links neither draws the 4.1 way.
            if (program != 0) { GL20C.glDeleteProgram(program); }
            if (cull != 0) { GL20C.glDeleteProgram(cull); }
            return new GpuIndirectInstances(0, 0);
        }
        return new GpuIndirectInstances(program, cull);
    }

    /**
     * The snapshot of these parts, reusing {@code current} while it holds the same source and revision; else the parts
     * are written anew and {@code current} is released after. Each part may carry a key ({@link #hide}, at least 0;
     * {@code keys} null for none). Every part's transform must be {@link GpuTreeInstances#instanceable}. Which of them a
     * pass draws is chosen as it adds the list ({@link #add}). With {@code dissolves} (null for none) a part draws in a
     * dissolve's group, whose programs dissolve between levels (GpuTreeInstances#dissolving), with when its level began
     * to dissolve (GpuTerrain.Prop#dissolve), 0 for none; NaN keeps it in its group at rest.
     */
    Instances list(Instances current, Object source, int revision, Array<Renderable> parts, IntArray keys,
          FloatArray dissolves) {
        if (holds(current, source, revision)) { return current; }
        // Written before the one it replaces goes: their shared parts stay where they are in the pools.
        Instances written = write(source, revision, new Parts(parts, null, dissolves), keys, null);
        release(current);
        return written;
    }

    /** Whether {@code list} holds this source at this revision: {@link #list} keeps it as it is. */
    static boolean holds(Instances list, Object source, int revision) {
        return list != null && !list.released && list.source == source && list.revision == revision;
    }

    /**
     * Falling pieces as a list: each part's instance names what moves it ({@code owners}, tree-instances.glsl
     * a_instance1.w) and its run's record ({@code pieces}). Written anew; {@code current} is released after.
     */
    Instances pieces(Instances current, Object source, Array<Renderable> parts, IntArray owners, Pieces pieces) {
        Instances written = write(source, 0, new Parts(parts, owners, null), null, pieces);
        release(current);
        return written;
    }

    /**
     * A stand's trees as a list (GpuTreeInstances.Stand): each run's trees ({@link GpuTreeInstances#STRIDE} floats
     * each, as the stand keeps them: the eighth a tree's dissolve, or a felled tree's slot) under each part of its
     * model ({@code parts}, by run), each run's record naming what moves its trees ({@code pieces}: a standing stand's
     * sway in a blast, a felled one's fall). Reuses {@code current} while it holds the same source and revision.
     */
    Instances trees(Instances current, Object source, int revision, List<Renderable[]> parts, List<FloatArray> runs,
          Pieces pieces) {
        if (holds(current, source, revision)) { return current; }
        Instances written = write(source, revision, new Trees(parts, runs), null, pieces);
        release(current);
        return written;
    }

    /** A stand's runs (#trees): entry by entry, a run's parts in turn, each over all of the run's trees. */
    private static final class Trees implements Placed {
        private final List<Renderable[]> parts;
        private final List<FloatArray> runs;
        private final int[] run, part, tree;

        Trees(List<Renderable[]> parts, List<FloatArray> runs) {
            this.parts = parts;
            this.runs = runs;
            int size = 0;
            for (int r = 0; r < runs.size(); r++) { size += parts.get(r).length * (runs.get(r).size / GpuTreeInstances.STRIDE); }
            run = new int[size];
            part = new int[size];
            tree = new int[size];
            int entry = 0;
            for (int r = 0; r < runs.size(); r++) {
                int trees = runs.get(r).size / GpuTreeInstances.STRIDE;
                for (int p = 0; p < parts.get(r).length; p++) {
                    for (int t = 0; t < trees; t++, entry++) {
                        run[entry] = r;
                        part[entry] = p;
                        tree[entry] = t;
                    }
                }
            }
        }

        @Override public int size() { return run.length; }

        @Override public Renderable part(int entry) { return parts.get(run[entry])[part[entry]]; }

        @Override
        public boolean mirrored(int entry) {
            return runs.get(run[entry]).items[tree[entry] * GpuTreeInstances.STRIDE + 3] < 0;
        }

        @Override
        public void place(int entry, FloatArray data) {
            data.addAll(runs.get(run[entry]).items, tree[entry] * GpuTreeInstances.STRIDE, GpuTreeInstances.STRIDE);
        }
    }

    /** Lists written so far: an unchanged view writes none. */
    long writes() { return writes; }

    /** Bytes of the parts' pools and of the board's instance buffer. */
    long bytes() {
        long total = (long) instances.capacity * instances.bytes;
        for (Pool pool : pools.values()) {
            total += (long) pool.vertices.capacity * pool.vertices.bytes + (long) pool.indices.capacity * pool.indices.bytes;
        }
        return total;
    }

    /** Whether the part is in its pool, so that a list of it places no geometry. */
    boolean placed(Renderable part) {
        return geometriesByRange.containsKey(new Range(part.meshPart.mesh, part.meshPart.offset, part.meshPart.size));
    }

    /** Parts placed in the pools by pieces lists so far, each a compacted copy of its mesh range (a take places none). */
    int piecesPlaced() { return piecesPlaced; }

    /**
     * The instances of these keys are not drawn in this pass from now on, until {@code revision} moves on with another
     * set: its jobs leave them out when they are next built. The set is the caller's; only its revision says that it
     * changed.
     */
    void hide(Pass pass, BitSet keys, int revision) {
        Drawn drawn = passes[pass.ordinal()];
        drawn.hidden = keys;
        drawn.hiddenRevision = revision;
    }

    /**
     * What a list is written from: entries of one model part each, each with where it stands. The part gives its
     * material and mesh range; {@link #place} writes its instance's first {@link GpuTreeInstances#STRIDE} floats.
     */
    private interface Placed {
        int size();

        Renderable part(int entry);

        boolean mirrored(int entry);

        /** Whether the entry draws in a dissolve's group (#list). */
        default boolean dissolves(int entry) { return false; }

        void place(int entry, FloatArray data);
    }

    /**
     * A snapshot's renderables, placed by their transforms; a falling piece's naming what moves it ({@code owners}), a
     * part dissolving between levels when it began ({@code dissolves}, tree-instances.glsl a_instance1.w).
     */
    private record Parts(Array<Renderable> parts, IntArray owners, FloatArray dissolves) implements Placed {
        @Override public int size() { return parts.size; }

        @Override public Renderable part(int entry) { return parts.get(entry); }

        @Override public boolean mirrored(int entry) { return GpuTreeInstances.mirrored(parts.get(entry).worldTransform); }

        @Override public boolean dissolves(int entry) { return dissolves != null && !Float.isNaN(dissolves.get(entry)); }

        @Override
        public void place(int entry, FloatArray data) {
            GpuTreeInstances.append(data, parts.get(entry).worldTransform, owners == null ? 0 : owners.get(entry));
            if (dissolves(entry)) { data.items[data.size - 1] = dissolves.get(entry); }
        }
    }

    private Instances write(Object source, int revision, Placed parts, IntArray keys, Pieces pieces) {
        writes++;
        int count = parts.size();
        if (count >= 1 << KEY_BITS) { throw new IllegalArgumentException("Too many parts in one list: " + count); }
        Group[] partGroups = new Group[count];
        Geometry[] partGeometries = new Geometry[count];
        // Groups, and parts of one geometry, in the order of their ids, each instance in its own order among equals: one
        // primitive sort. Ranks, not ids, go into the key, so that it stays within 21 bits a field however long the board
        // runs.
        Map<Group, Integer> groupRanks = new IdentityHashMap<>();
        Map<Integer, Integer> geometryRanks = new HashMap<>();
        List<Group> distinctGroups = new ArrayList<>();
        // Falling pieces count their parts placed (a fall's take places none); trees' records place none of theirs.
        placingPieces = pieces != null && parts instanceof Parts;
        // A list's parts share few materials and meshes: each pair's group is found once a write (a strike's falls
        // write 91,670 parts, whose keys hash every attribute of a material and of a vertex layout).
        Map<Material, Map<Mesh, Group[]>> found = new IdentityHashMap<>();
        for (int i = 0; i < count; i++) {
            Renderable part = parts.part(i);
            boolean mirrored = parts.mirrored(i), dissolves = parts.dissolves(i);
            // By mirror, and whether it dissolves.
            int variant = (mirrored ? 1 : 0) + (dissolves ? 2 : 0);
            Group[] variants = found.computeIfAbsent(part.material, material -> new IdentityHashMap<>())
                  .computeIfAbsent(part.meshPart.mesh, mesh -> new Group[4]);
            if (variants[variant] == null) { variants[variant] = group(part, mirrored, dissolves); }
            partGroups[i] = variants[variant];
            partGeometries[i] = geometry(part.meshPart, partGroups[i].pool);
            if (groupRanks.putIfAbsent(partGroups[i], -1) == null) { distinctGroups.add(partGroups[i]); }
            geometryRanks.putIfAbsent(partGeometries[i].id, -1);
        }
        placingPieces = false;
        distinctGroups.sort((a, b) -> Integer.compare(a.id, b.id));
        for (int rank = 0; rank < distinctGroups.size(); rank++) { groupRanks.put(distinctGroups.get(rank), rank); }
        int[] ids = geometryRanks.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        for (int rank = 0; rank < ids.length; rank++) { geometryRanks.put(ids[rank], rank); }
        long[] sorted = new long[count];
        for (int i = 0, n = 0; i < count; i++) {
            sorted[n++] = (long) groupRanks.get(partGroups[i]) << 2 * KEY_BITS
                  | (long) geometryRanks.get(partGeometries[i].id) << KEY_BITS | i;
        }
        Arrays.sort(sorted);
        int[] order = new int[count];
        int runs = 0;
        for (int n = 0; n < count; n++) {
            order[n] = (int) (sorted[n] & (1 << KEY_BITS) - 1);
            if (n == 0 || sorted[n] >>> KEY_BITS != sorted[n - 1] >>> KEY_BITS) { runs++; }
        }
        // A pieces list's runs take their records in one range, which their instances name as they are written.
        int record = pieces == null || runs == 0 ? 0 : records.take(runs);
        float[][] runRecords = pieces == null ? null : new float[runs][];
        Renderable[] samples = pieces == null ? null : new Renderable[runs];
        FloatArray recordData = pieces == null ? null : new FloatArray(runs * RECORD_FLOATS);
        int first = count == 0 ? 0 : instances.take(count);
        FloatArray data = new FloatArray(count * STRIDE);
        Group[] runGroups = new Group[runs];
        Geometry[] runGeometries = new Geometry[runs];
        int[] starts = new int[runs], sizes = new int[runs];
        int[] instanceKeys = keys == null ? null : new int[count], indices = new int[count];
        BitSet heldKeys = keys == null ? null : new BitSet();
        for (int n = 0, run = -1; n < count; n++) {
            int i = order[n];
            Renderable part = parts.part(i);
            if (n == 0 || sorted[n] >>> KEY_BITS != sorted[n - 1] >>> KEY_BITS) {
                run++;
                runGroups[run] = partGroups[i];
                runGeometries[run] = partGeometries[i];
                partGroups[i].users++;
                partGeometries[i].users++;
                starts[run] = n;
                if (pieces != null) {
                    float[] own = pieces.record(part);
                    own[RECORD_BASE] = partGeometries[i].vertex;
                    runRecords[run] = own;
                    samples[run] = part;
                    recordData.addAll(own, 0, RECORD_FLOATS);
                }
            }
            sizes[run]++;
            indices[n] = i;
            parts.place(i, data);
            data.addAll(partGroups[i].pool.instanceLayout.size() > 2 ? RigidGlb.slotColours(part.userData) : NO_COLOURS);
            data.add(pieces == null ? 0 : record + run, 0, 0, 0);
            if (keys != null) {
                instanceKeys[n] = keys.get(i);
                heldKeys.set(keys.get(i));
            }
        }
        if (count > 0) { instances.write(first, floats(data)); }
        if (recordData != null && recordData.size > 0) {
            records.write(record, floats(recordData));
            bindRecords();
        }
        return new Instances(source, revision, first, count, runGroups, runGeometries, starts, sizes, instanceKeys,
              heldKeys == null ? null : heldKeys.stream().toArray(), indices, pieces, record, samples, runRecords);
    }

    /**
     * The order a pass draws its groups in (#build): by what they draw ({@link Group#content}), then whether they
     * dissolve. Not by id: a group goes with its last list and comes back with a new one, and a level's parts move into
     * a dissolve's groups (#list) and back; this order keeps their coplanar faces' tie winners through both, and is the
     * same in every run but for groups their content cannot tell apart.
     */
    private static int drawOrder(Group a, Group b) {
        int order = Integer.compare(a.content, b.content);
        if (order == 0) { order = Boolean.compare(a.key.dissolves(), b.key.dissolves()); }
        return order != 0 ? order : Integer.compare(a.id, b.id);
    }

    /**
     * A key of what a group draws that is the same in every run: its material's id (an authored one: libGDX numbers the
     * others as the process makes materials) and its attributes' values, a texture by its size, format, filters and wraps
     * (not its GL handle, which differs between runs), other attributes by their names alone; its vertex layout and
     * mirror. Summed attribute by attribute: their order follows the types' numbers, which follow the order the attribute
     * classes load in. Groups the key cannot tell apart go by their ids.
     */
    private static int content(Key key) {
        String id = key.material().id;
        int content = id.matches("mtl\\d+") ? 0 : id.hashCode();
        for (Attribute attribute : key.material()) { content += attribute(attribute); }
        // By index: a layout's iterator is shared, and another thread may be walking the same mesh's layout.
        VertexAttributes layout = key.layout();
        for (int i = 0; i < layout.size(); i++) {
            VertexAttribute attribute = layout.get(i);
            content = 31 * content + attribute.alias.hashCode() + 7 * attribute.usage + attribute.numComponents;
        }
        return 31 * content + (key.mirrored() ? 1 : 0);
    }

    /** One attribute's share of {@link #content}. */
    private static int attribute(Attribute attribute) {
        int name = Attribute.getAttributeAlias(attribute.type).hashCode();
        int share = switch (attribute) {
            // Enums by their ordinals: an enum's hash is its identity's.
            case TextureAttribute texture -> {
                var description = texture.textureDescription;
                yield java.util.Objects.hash(description.texture.getWidth(), description.texture.getHeight(),
                      ordinal(description.texture.getTextureData().getFormat()), ordinal(description.minFilter),
                      ordinal(description.magFilter), ordinal(description.uWrap), ordinal(description.vWrap),
                      texture.offsetU, texture.offsetV, texture.scaleU, texture.scaleV, texture.uvIndex);
            }
            case ColorAttribute color -> color.color.toIntBits();
            case FloatAttribute number -> Float.floatToIntBits(number.value);
            case IntAttribute number -> number.value;
            case BlendingAttribute blending -> java.util.Objects.hash(blending.blended, blending.sourceFunction,
                  blending.destFunction, blending.opacity);
            case DepthTestAttribute depth -> java.util.Objects.hash(depth.depthFunc, depth.depthRangeNear,
                  depth.depthRangeFar, depth.depthMask);
            default -> 0;
        };
        return name * 31 + share;
    }

    /** An enum's ordinal, -1 for none. */
    private static int ordinal(Enum<?> value) { return value == null ? -1 : value.ordinal(); }

    private Group group(Renderable part, boolean mirrored, boolean dissolves) {
        VertexAttributes layout = part.meshPart.mesh.getVertexAttributes();
        // The key keeps a copy: the part's material may change after its snapshot.
        Key key = new Key(part.material, layout, mirrored, dissolves);
        Group group = groupsByKey.get(key);
        if (group == null) {
            Key kept = new Key(new Material(part.material), layout, mirrored, dissolves);
            group = new Group(kept, pools.computeIfAbsent(layout, Pool::new), nextGroup++);
            groupsByKey.put(kept, group);
        }
        return group;
    }

    /** The part's place in its pool, compacted and written at its first use (GpuTreeInstances#compact). */
    private Geometry geometry(MeshPart part, Pool pool) {
        Range key = new Range(part.mesh, part.offset, part.size);
        Geometry geometry = geometriesByRange.get(key);
        if (geometry != null) { return geometry; }
        FloatArray vertices = new FloatArray();
        ShortArray indices = new ShortArray();
        GpuTreeInstances.compact(part, vertices, indices);
        geometry = new Geometry(key, pool, nextGeometry++);
        geometry.vertices = vertices.size / (pool.layout.vertexSize / Float.BYTES);
        geometry.indices = indices.size;
        geometry.vertex = pool.vertices.take(geometry.vertices);
        geometry.index = pool.indices.take(geometry.indices);
        pool.vertices.write(geometry.vertex, floats(vertices));
        if (shorts.capacity() < indices.size) { shorts = BufferUtils.newShortBuffer(indices.size * 2); }
        shorts.clear();
        shorts.put(indices.items, 0, indices.size).flip();
        pool.indices.write(geometry.index, shorts);
        geometry.bound = bounds.take(1);
        bounds.write(geometry.bound, floats(sphere(vertices, pool.layout)));
        geometriesByRange.put(key, geometry);
        if (placingPieces) { piecesPlaced++; }
        return geometry;
    }

    /** The sphere around these vertices' box, centre and radius, in their model's frame. */
    private static FloatArray sphere(FloatArray vertices, VertexAttributes layout) {
        int stride = layout.vertexSize / Float.BYTES;
        int at = layout.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
        float[] min = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (int v = at; v + 2 < vertices.size; v += stride) {
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], vertices.items[v + axis]);
                max[axis] = Math.max(max[axis], vertices.items[v + axis]);
            }
        }
        FloatArray result = new FloatArray(4);
        if (min[0] > max[0]) {
            result.add(0, 0, 0, 0);
            return result;
        }
        float x = max[0] - min[0], y = max[1] - min[1], z = max[2] - min[2];
        // A hair wider than the box's half diagonal: a sphere that grazes the view keeps its instance.
        result.add((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2,
              (float) Math.sqrt(x * x + y * y + z * z) / 2 * 1.01f + 1e-3f);
        return result;
    }

    private FloatBuffer floats(FloatArray data) {
        if (floats.capacity() < data.size) { floats = BufferUtils.newFloatBuffer(data.size * 2); }
        floats.clear();
        floats.put(data.items, 0, data.size).flip();
        return floats;
    }

    /**
     * The records' buffer texture on its unit, pointed at the records' buffer where it was replaced: before every pass,
     * as another terrain in the same context (a print, the editor's previews) binds its own there.
     */
    private void bindRecords() {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + RECORDS_UNIT);
        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, recordsTexture);
        if (recordsGeneration != records.generation) {
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32F, records.handle);
            recordsGeneration = records.generation;
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    /** Starts a pass's frame: the snapshots it draws follow ({@link #add}). */
    void begin(Pass pass) {
        passes[pass.ordinal()].lists.clear();
        passes[pass.ordinal()].shown.clear();
    }

    /** A snapshot this pass draws, all of it but what it hides by key, in the owners' order. */
    void add(Pass pass, Instances list) { add(pass, list, null); }

    /**
     * A snapshot this pass draws, those of its parts {@code shown} names by their index among the parts written (null
     * for all): a selection that differs from the last frame's regroups the pass, the list stays as written. The set is
     * the pass's from now on, until its next frame.
     */
    void add(Pass pass, Instances list, BitSet shown) {
        if (list.count == 0) { return; }
        passes[pass.ordinal()].lists.add(list);
        passes[pass.ordinal()].shown.add(shown);
    }

    /**
     * The pass's renderables, one a group with commands: the same as last frame's unless its snapshots changed, or the
     * hidden keys where its snapshots hold keys, when their commands are written and uploaded again. A pass drawn for
     * a {@code camera} (null: one whose instances move in the vertex stage, the falls) draws only the instances whose
     * bounding spheres reach inside its side planes ({@link #cull}), culled again whenever those planes moved.
     */
    Array<Renderable> renderables(Pass pass, Camera camera) {
        bindRecords();
        Drawn drawn = passes[pass.ordinal()];
        boolean culled = CULLING && full && camera != null;
        if (!drawn.lists.equals(drawn.drawn) || !drawn.shown.equals(drawn.drawnShown)
              || drawn.keyed && drawn.builtRevision != drawn.hiddenRevision || culled != drawn.builtCulled) {
            build(drawn, pass, culled);
        }
        if (culled) { cull(drawn, camera); }
        return drawn.renderables;
    }

    /** The pass's commands and its instances' runs, uploaded: copied by the compaction pass, or left to {@link #cull}. */
    private void build(Drawn drawn, Pass pass, boolean culled) {
        drawn.builtCulled = culled;
        drawn.culledFor = null;
        drawn.drawn.clear();
        drawn.drawn.addAll(drawn.lists);
        drawn.drawnShown.clear();
        drawn.drawnShown.addAll(drawn.shown);
        for (Group group : drawn.groups) { group.parts.get(pass.ordinal()).clear(); }
        drawn.groups.clear();
        drawn.parts.clear();
        drawn.keyed = false;
        drawn.builtRevision = drawn.hiddenRevision;
        BitSet hidden = drawn.hidden;
        int runs = 0;
        for (int index = 0; index < drawn.lists.size(); index++) {
            Instances list = drawn.lists.get(index);
            BitSet selected = drawn.shown.get(index);
            drawn.keyed |= list.keys != null;
            boolean keyed = list.keys != null && hides(hidden, list.held), masked = keyed || selected != null;
            for (int run = 0; run < list.groups.length; run++) {
                Part part = null;
                int from = list.starts[run], end = from + list.sizes[run];
                // The run's shown instances, in runs of their own where hidden ones lie between them.
                while (from < end) {
                    while (masked && from < end && !shows(list, from, keyed, hidden, selected)) { from++; }
                    if (from == end) { break; }
                    int to = masked ? from + 1 : end;
                    while (to < end && shows(list, to, keyed, hidden, selected)) { to++; }
                    if (part == null) {
                        part = part(drawn, pass, list.groups[run], list.geometries[run]);
                    }
                    part.runs.add(list.first + from, to - from);
                    part.total += to - from;
                    runs++;
                    from = to;
                }
            }
        }
        // Draw order decides which of two coplanar faces shows: the groups go in an order of what they draw, not of when
        // they were made (#drawOrder).
        drawn.groups.sort(GpuIndirectInstances::drawOrder);
        int commands = drawn.parts.size();
        if (drawn.commands.length < commands * COMMAND) { drawn.commands = new int[commands * COMMAND * 2]; }
        int[] jobs = new int[runs * 4], partInfo = new int[commands * 4];
        drawn.renderables.clear();
        int at = 0, job = 0, placed = 0, command = 0;
        drawn.firsts.clear();
        for (Group group : drawn.groups) {
            Renderable renderable = group.renderables[pass.ordinal()];
            List<Part> parts = group.parts.get(pass.ordinal());
            // Its mesh part names its place among the pass's groups, whose commands it draws (#draw).
            renderable.meshPart.offset = drawn.firsts.size;
            renderable.meshPart.size = parts.size();
            drawn.firsts.add(at / COMMAND);
            for (Part part : parts) {
                Geometry geometry = part.geometry;
                drawn.commands[at++] = geometry.indices;
                drawn.commands[at++] = part.total;
                drawn.commands[at++] = geometry.index;
                drawn.commands[at++] = geometry.vertex;
                drawn.commands[at++] = placed;
                // What the culling pass reads of it: its runs among the jobs, its bounds and its first instance.
                partInfo[command++] = job / 4;
                partInfo[command++] = part.runs.size / 2;
                partInfo[command++] = geometry.bound;
                partInfo[command++] = placed;
                for (int r = 0; r < part.runs.size; r += 2) {
                    jobs[job++] = part.runs.get(r);
                    jobs[job++] = placed;
                    jobs[job++] = part.runs.get(r + 1);
                    job++;
                    placed += part.runs.get(r + 1);
                }
            }
            drawn.renderables.add(renderable);
        }
        // The 4.1 way draws each command from the array itself (#drawAlone).
        for (int group = 0; full && group < drawn.firsts.size; group++) {
            if (group == drawn.buffers.size) { drawn.buffers.add(0); }
            int first = drawn.firsts.get(group) * COMMAND;
            int end = group + 1 < drawn.firsts.size ? drawn.firsts.get(group + 1) * COMMAND : at;
            drawn.buffers.set(group, commands(drawn.buffers.get(group), drawn.commands, first, end - first));
        }
        drawn.commandCount = commands;
        reserve(drawn, placed);
        if (placed == 0) { return; }
        if (full) {
            if (drawn.jobs == 0) { drawn.jobs = Gdx.gl.glGenBuffer(); }
            upload(drawn.jobs, jobs, runs * 4, GL20.GL_STREAM_DRAW);
        }
        if (culled) {
            if (drawn.partBuffer == 0) { drawn.partBuffer = Gdx.gl.glGenBuffer(); }
            upload(drawn.partBuffer, partInfo, commands * 4, GL20.GL_STREAM_DRAW);
        } else if (full) {
            compact(drawn, runs);
        } else {
            // The 4.1 way: a copy a run, buffer to buffer.
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, instances.handle);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, drawn.output);
            long instance = (long) STRIDE * Float.BYTES;
            for (int r = 0; r < runs; r++) {
                GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, jobs[r * 4] * instance,
                      jobs[r * 4 + 1] * instance, jobs[r * 4 + 2] * instance);
            }
        }
    }

    /**
     * Keeps the instances of the pass's commands whose bounding spheres (their geometry's under their placement, as
     * instance-place.glsl places a vertex) reach inside the camera's side planes, a command's in their order, and writes
     * each command's count into its group's buffer: a work group a command, a dispatch a group, as each group draws
     * from a buffer of its own (#commands). Nothing where the planes are those it last culled for.
     */
    private void cull(Drawn drawn, Camera camera) {
        float[] planes = new float[16];
        for (int side = 0; side < 4; side++) {
            // libGDX's frustum: near, far, then left, right, top and bottom.
            Plane plane = camera.frustum.planes[side + 2];
            planes[side * 4] = plane.normal.x;
            planes[side * 4 + 1] = plane.normal.y;
            planes[side * 4 + 2] = plane.normal.z;
            planes[side * 4 + 3] = plane.d;
        }
        if (Arrays.equals(planes, drawn.culledFor)) { return; }
        drawn.culledFor = planes;
        if (drawn.firsts.size == 0) { return; }
        GL20C.glUseProgram(cull);
        GL20C.glUniform4fv(cullPlanes, planes);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, instances.handle);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, drawn.output);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, drawn.jobs);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, drawn.partBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, bounds.handle);
        int groups = drawn.firsts.size;
        for (int group = 0; group < groups; group++) {
            int first = drawn.firsts.get(group);
            int count = (group + 1 < groups ? drawn.firsts.get(group + 1) : drawn.commandCount) - first;
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, drawn.buffers.get(group));
            GL20C.glUniform1i(cullFirstPart, first);
            GL20C.glUniform1i(cullParts, count);
            int width = Math.min(count, MAX_GROUPS);
            GL43C.glDispatchCompute(width, (count + width - 1) / width, 1);
        }
        GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
        for (int binding : new int[] { 0, 1, 2, 3, 5, 6 }) { GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0); }
        GL20C.glUseProgram(0);
    }

    /** Whether the list's instance at {@code at} draws: not hidden by its key, and among the pass's selection. */
    private static boolean shows(Instances list, int at, boolean keyed, BitSet hidden, BitSet selected) {
        return !(keyed && hidden.get(list.keys[at])) && (selected == null || selected.get(list.indices[at]));
    }

    /** Whether one of these keys is hidden. */
    private static boolean hides(BitSet hidden, int[] keys) {
        for (int key : keys) {
            if (hidden.get(key)) { return true; }
        }
        return false;
    }

    /** The pass's part of this group and geometry, made and placed in its group's commands at its first run. */
    private Part part(Drawn drawn, Pass pass, Group group, Geometry geometry) {
        PartKey key = new PartKey(group.id, geometry.id);
        Part part = drawn.parts.get(key);
        if (part == null) {
            drawn.parts.put(key, part = new Part(geometry));
            List<Part> parts = group.parts.get(pass.ordinal());
            if (parts.isEmpty()) { drawn.groups.add(group); }
            parts.add(part);
        }
        return part;
    }

    /**
     * A group's commands in a new buffer, the one it replaces deleted: the Iris Xe's driver rejects multi-draws
     * (GL_INVALID_OPERATION) from a command buffer whose data was given again, and from one declared GL_STREAM_DRAW;
     * a new static buffer made as the draw-indirect buffer draws. NVIDIA draws from all of them.
     */
    private int commands(int buffer, int[] values, int from, int count) {
        // Made before the old one goes, so that it is never given the old one's name again.
        int made = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, made);
        GL15C.glBufferData(GL_DRAW_INDIRECT_BUFFER, ints(values, from, count), GL15C.GL_STATIC_DRAW);
        if (buffer != 0) { GL15C.glDeleteBuffers(buffer); }
        return made;
    }

    private IntBuffer ints(int[] values, int from, int count) {
        if (ints.capacity() < count) { ints = BufferUtils.newIntBuffer(count * 2); }
        ints.clear();
        ints.put(values, from, count).flip();
        return ints;
    }

    private void upload(int buffer, int[] values, int count, int usage) {
        ints(values, 0, count);
        GL15C.glBindBuffer(GL30.GL_COPY_WRITE_BUFFER, buffer);
        Gdx.gl.glBufferData(GL30.GL_COPY_WRITE_BUFFER, Math.max(count, 1) * Integer.BYTES, ints, usage);
    }

    /** The pass's instance buffer, grown (and its vertex arrays pointed again) when it holds fewer than {@code count}. */
    private void reserve(Drawn drawn, int instanceCount) {
        if (drawn.outputCapacity < instanceCount) {
            if (drawn.output != 0) { Gdx.gl.glDeleteBuffer(drawn.output); }
            drawn.output = Gdx.gl.glGenBuffer();
            drawn.outputCapacity = Math.max(instanceCount, drawn.outputCapacity * 2);
            GL15C.glBindBuffer(GL30.GL_COPY_WRITE_BUFFER, drawn.output);
            GL15C.glBufferData(GL30.GL_COPY_WRITE_BUFFER, (long) drawn.outputCapacity * STRIDE * Float.BYTES,
                  GL20.GL_STATIC_DRAW);
            drawn.outputGeneration++;
        }
    }

    /** Copies the pass's runs (its jobs, uploaded) into its instance buffer. */
    private void compact(Drawn drawn, int runs) {
        GL20C.glUseProgram(compact);
        GL20C.glUniform1i(compactJobs, runs);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, instances.handle);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, drawn.output);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, drawn.jobs);
        int width = Math.min(runs, MAX_GROUPS);
        GL43C.glDispatchCompute(width, (runs + width - 1) / width, 1);
        GL43C.glMemoryBarrier(GL43C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
        for (int binding = 0; binding < 3; binding++) { GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0); }
        GL20C.glUseProgram(0);
    }

    /** The commands of the pass's group at {@code group} (its place among Drawn#groups): {@code count} of them. */
    private void draw(Drawn drawn, int group, int count, boolean mirrored) {
        if (mirrored) { Gdx.gl.glFrontFace(GL20.GL_CW); }
        try {
            GL15C.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, drawn.buffers.get(group));
            GL43C.glMultiDrawElementsIndirect(GL20.GL_TRIANGLES, GL20.GL_UNSIGNED_SHORT, 0, count, 0);
        } finally {
            if (mirrored) { Gdx.gl.glFrontFace(GL20.GL_CCW); }
        }
        multiDraws++;
        commandsDrawn += count;
        countIndices(drawn, group, count);
    }

    private static void countIndices(Drawn drawn, int group, int count) {
        int first = drawn.firsts.get(group);
        for (int c = first; c < first + count; c++) {
            indicesDrawn += (long) drawn.commands[c * COMMAND] * drawn.commands[c * COMMAND + 1];
        }
    }

    /**
     * The 4.1 way: the group's commands one at a time, each with the pass's instance attributes pointed at its first
     * instance, as there is no base instance before OpenGL 4.2; pointed back at the buffer's start after.
     */
    private void drawAlone(Drawn drawn, Pool pool, Pass pass, int group, int count, boolean mirrored) {
        Pool.Binding binding = pool.bindings[pass.ordinal()];
        if (mirrored) { Gdx.gl.glFrontFace(GL20.GL_CW); }
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, drawn.output);
        try {
            int first = drawn.firsts.get(group);
            for (int c = first; c < first + count; c++) {
                int at = c * COMMAND;
                pool.pointInstances(binding, (long) drawn.commands[at + 4] * STRIDE * Float.BYTES);
                GL32C.glDrawElementsInstancedBaseVertex(GL20.GL_TRIANGLES, drawn.commands[at], GL20.GL_UNSIGNED_SHORT,
                      (long) drawn.commands[at + 2] * Short.BYTES, drawn.commands[at + 1], drawn.commands[at + 3]);
            }
            pool.pointInstances(binding, 0);
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            if (mirrored) { Gdx.gl.glFrontFace(GL20.GL_CCW); }
        }
        commandsDrawn += count;
        countIndices(drawn, group, count);
    }

    @Override
    public void dispose() {
        disposed = true;
        pools.values().forEach(Pool::dispose);
        pools.clear();
        instances.dispose();
        records.dispose();
        bounds.dispose();
        GL11C.glDeleteTextures(recordsTexture);
        for (Drawn drawn : passes) {
            for (int buffer : new int[] { drawn.output, drawn.jobs, drawn.partBuffer }) {
                if (buffer != 0) { Gdx.gl.glDeleteBuffer(buffer); }
            }
            for (int i = 0; i < drawn.buffers.size; i++) { Gdx.gl.glDeleteBuffer(drawn.buffers.get(i)); }
            drawn.buffers.clear();
            drawn.output = drawn.jobs = drawn.partBuffer = 0;
        }
        GL20C.glDeleteProgram(compact);
        GL20C.glDeleteProgram(cull);
        geometriesByRange.clear();
        groupsByKey.clear();
    }
}
