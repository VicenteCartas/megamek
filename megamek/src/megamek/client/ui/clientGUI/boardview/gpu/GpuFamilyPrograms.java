/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import megamek.logging.MMLogger;

/**
 * Terrain programs with a draw's surface families as compile-time constants (SCULPT_FAMILY and COVER_FAMILIES in
 * terrain-sculpt.frag), so the compiler keeps only those families' paths. The generic program of the same flags reads
 * the same values from uniforms and draws the same pixels; it stays every draw's fallback. Family programs build only
 * while the terrain loads, behind the loading stage and after the generic programs: the most-drawn variants, several
 * at once with the driver's parallel compiler, else one at a time (GpuTerrain decides when). When one is ready, every
 * live material of its variant
 * gains the {@link Constants} marker, and the shader provider, through the shader manager's handles, resolves those
 * draws to it; a chunk installed later is marked as it is installed. Variants that appear after the load keep the
 * generic program until the next load. With the link helper (GpuLinkHelper) a fresh link holds no frame, so the loading
 * stage waits only for what the program cache holds, every variant asks once behind it, and the links go on beside the
 * frames, each adopted when it is ready, one a frame: a generic program draws the same pixels meanwhile. A terrain builds at most {@link #CAPACITY} family programs for each lighting
 * configuration and twice that in all; once it holds that many, a configuration without programs stays generic. The
 * provider keeps every program it built, and returning to a configuration finds its programs there.
 * <p>
 * A chunk's level tops ({@link #flat}) draw apart from the rest of their material, in a family program of their own
 * without walls, rocks, plants or pits (SCULPT_TOPS): the most-drawn programs, and much the cheapest. A generic program
 * draws them like any other draw of their material.
 */
final class GpuFamilyPrograms {
    private static final MMLogger LOGGER = MMLogger.create(GpuFamilyPrograms.class);
    /** {@code -Dmegamek.gpu.familyPrograms=false} draws every terrain surface with its generic program. */
    static final String PROPERTY = "megamek.gpu.familyPrograms";
    /**
     * Family programs a terrain builds for each lighting configuration, and twice that in all; libGDX frees them with
     * the terrain. Day/night and cloud cover keep stable bindings and therefore share programs. Actual flag changes
     * such as specular anti-aliasing still select configurations: the total gives two of them all their programs, and
     * a third configuration draws with the generic programs, as before family programs.
     */
    static final int CAPACITY = 32;
    /** Links in flight with the driver's parallel compiler: NVIDIA's runs six at once (4.4 times one at a time). */
    static final int IN_FLIGHT = 6;
    /** The capacity in force; native checks lower it to reach it on a small board. */
    static int capacity = CAPACITY;
    /**
     * Family programs one load (a board, graphics or lighting change behind the loading stage) compiles from source,
     * the most-drawn first; programs the program cache holds load beside them at no such cost. The other variants keep
     * the generic program until a later load, which takes these from the cache and compiles the next ones; without a
     * cache (a driver with no binary formats) every load compiles the first ones. Uncached, one links on Iris Xe in
     * 1.1 to 4 s at Medium, 1.6 to 5.6 s at High. The link helper's links aren't counted: they hold nothing.
     */
    static final int FRESH = 4;
    /** The share in force: the shader warm-up's process (GpuShaderWarmup), whose links no frame waits for, lifts it. */
    static int fresh = FRESH;

    /**
     * Marks a material drawn by its family program. It is part of the material's mask, so the comparisons that already
     * separate materials (DefaultShader.canRender, merged pages, the terrain batch's material ids) keep generic and
     * family draws apart. It spends one of libGDX's 64 attribute types; a flag inside Sculpt would need its own copy,
     * equality and scar handling instead.
     */
    static final class Constants extends Attribute {
        static final long TYPE = register("terrainFamilyConstants");

        Constants() { super(TYPE); }

        @Override
        public Attribute copy() { return new Constants(); }

        @Override
        public int compareTo(Attribute other) { return Long.compare(type, other.type); }
    }

    /** The families, proven base aliases, vertex layout and generic variant a program is for. */
    private record Key(List<Float> families, int aliases, boolean tops, VertexAttributes attributes, Object variant) { }

    /** A material installed by a chunk and the indices it draws; the chunk owns it, so it is held weakly. */
    private record Use(WeakReference<Material> material, int indices) { }

    private static final class Variant {
        final Key key;
        final float[] families;
        final List<Use> uses = new ArrayList<>();
        /** The warm-up renderable while its program compiles, and the lighting configuration it compiles for. */
        Renderable sample;
        List<Object> sampleConfiguration;
        /** Deferred: no cached program, and this configuration compiled its {@link #FRESH} share already. */
        boolean ready, failed, deferred;
        /** Its program's cache key, while the link helper links it. */
        String linkKey;

        Variant(Key key, float[] families) {
            this.key = key;
            this.families = families;
        }

        /** A material still installed, or null once every chunk that used the variant is gone. */
        Material live() {
            uses.removeIf(use -> use.material().get() == null);
            for (Use use : uses) {
                Material material = use.material().get();
                if (material != null) { return material; }
            }
            return null;
        }

        int weight() {
            int weight = 0;
            for (Use use : uses) { if (use.material().get() != null) { weight += use.indices(); } }
            return weight;
        }

        /** Sets or clears the marker on every live material; returns whether any material changed program. */
        boolean mark(boolean constants) {
            boolean changed = false;
            for (Use use : uses) {
                Material material = use.material().get();
                if (material != null && material.has(Constants.TYPE) != constants) {
                    if (constants) { material.set(new Constants()); } else { material.remove(Constants.TYPE); }
                    changed = true;
                }
            }
            return changed;
        }

        void release() {
            if (sample != null) { sample.meshPart.mesh.dispose(); }
            sample = null;
            sampleConfiguration = null;
        }
    }

    private final boolean enabled;
    private final long sculptType;
    private final Function<Material, Object> variantOf;
    private final Map<Key, Variant> variants = new LinkedHashMap<>();
    /** Every program built, by variant and lighting configuration: what the provider holds. */
    private final Set<List<Object>> built = new HashSet<>();
    /** Variants whose links run, oldest first. */
    private final List<Variant> compiling = new ArrayList<>();
    /** A request or a reset since the last search for the next program. */
    private boolean waiting;
    /** Links this configuration started from source. */
    private int freshLinks;
    /** The preparation of the last step, and whether its links run in the link helper. */
    private GpuGlsl.Preparation links;
    private boolean background;
    private long environmentMask = -1;
    private int environmentFlags;
    private boolean shadows;

    /**
     * {@code variantOf} returns what selects a material's generic program besides its families, without the marker.
     * Renderers that borrow another terrain's assets (previews, prints) pass {@code wanted} false: generic only.
     */
    GpuFamilyPrograms(boolean wanted, long sculptType, Function<Material, Object> variantOf) {
        enabled = wanted && Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
        this.sculptType = sculptType;
        this.variantOf = variantOf;
    }

    /**
     * A chunk installed this material, drawing {@code indices} indices. When the material's family program is ready,
     * it is marked now, so the chunk's first frame already uses it.
     */
    void request(Material material, int indices, VertexAttributes attributes) {
        if (!enabled || indices <= 0) { return; }
        float[] families = families(material, sculptType);
        if (families == null) { return; }
        List<Float> boxed = new ArrayList<>(families.length);
        for (float family : families) { boxed.add(family); }
        Variant variant = variants.computeIfAbsent(new Key(boxed, baseAliases(material), isFlat(material), attributes, variantOf.apply(material)),
              key -> new Variant(key, families));
        variant.uses.removeIf(use -> use.material().get() == null);
        variant.uses.add(new Use(new WeakReference<>(material), indices));
        if (variant.ready) { material.set(new Constants()); }
        else { waiting |= !variant.failed; }
    }

    /**
     * Before the terrain draws or prepares: a changed lighting configuration returns every material to its generic
     * program; the terrain then prepares the generic and family programs of the new configuration, and takes back the
     * ones the provider kept from an earlier visit. Returns whether the configuration changed.
     */
    boolean configure(Environment environment) {
        if (!enabled || environment.getMask() == environmentMask
              && GpuEnvironmentLighting.flags(environment) == environmentFlags
              && (environment.shadowMap != null) == shadows) {
            return false;
        }
        environmentMask = environment.getMask();
        environmentFlags = GpuEnvironmentLighting.flags(environment);
        shadows = environment.shadowMap != null;
        for (Variant variant : variants.values()) {
            variant.mark(false);
            variant.ready = variant.failed = variant.deferred = false;
        }
        if (background) {
            links.dropOptional();
            forgetLinks();
        }
        // A link still running finishes into the provider for its own configuration; nothing marks it (prepare).
        waiting = true;
        freshLinks = 0;
        return true;
    }

    /**
     * One step while the terrain loads: adopts a finished program, and starts the most-drawn variants without one.
     * The shared-context compiler links one program at a time and, on Intel, holds the render thread meanwhile, so it
     * starts one only while no link runs; the driver's parallel compiler keeps {@link #IN_FLIGHT} running. Returns
     * whether any material changed program.
     */
    boolean prepare(GpuGlsl.Preparation preparation, ShaderProvider provider, Environment environment) {
        if (!enabled || preparation == null) { return false; }
        links = preparation;
        boolean elsewhere = preparation.background();
        if (background && !elsewhere) {
            // The helper ended: nothing links in this process beside the frames. Until the next load, these stay generic.
            forgetLinks();
            background = false;
            return false;
        }
        background = elsewhere;
        if (elsewhere) { return prepareElsewhere(preparation, provider, environment); }
        int limit = preparation.parallel() ? IN_FLIGHT : 1;
        if (limit == 1 && preparation.linking()) { return false; }
        boolean changed = false;
        // One running link is checked per step, since a check assembles its program's sources again.
        if (!compiling.isEmpty()) {
            Variant variant = compiling.removeFirst();
            Boolean marked = finish(variant, preparation, provider);
            if (marked == null) { compiling.addLast(variant); } else { changed = marked; }
        }
        while (compiling.size() < limit && waiting) {
            Variant variant = next(environment);
            waiting = variant != null;
            if (variant == null) { break; }
            Boolean marked = finish(variant, preparation, provider);
            if (marked == null) { compiling.addLast(variant); } else { changed |= marked; }
        }
        return changed;
    }

    /**
     * With the link helper. Behind the loading stage every variant asks once, which assembles its sources, and those the
     * cache holds are adopted at once. Then a finished link is adopted when its binary is there: while the board shows,
     * one a frame within the frame's budget for links, and asking only then, since asking assembles the sources again.
     */
    private boolean prepareElsewhere(GpuGlsl.Preparation preparation, ShaderProvider provider, Environment environment) {
        boolean changed = false, showing = GpuGlsl.presenting();
        while (!showing && waiting) {
            Variant variant = next(environment);
            waiting = variant != null;
            if (variant == null) { break; }
            Boolean marked = finish(variant, preparation, provider);
            if (marked == null) { compiling.add(variant); } else { changed |= marked; }
        }
        for (var unfinished = compiling.iterator(); unfinished.hasNext(); ) {
            Variant variant = unfinished.next();
            if (!preparation.over(variant.linkKey)) { continue; }
            if (showing && GpuGlsl.overBudget()) { break; }
            long start = System.nanoTime();
            Boolean marked = finish(variant, preparation, provider);
            GpuGlsl.spent(System.nanoTime() - start);
            if (marked == null) { continue; }
            unfinished.remove();
            changed |= marked;
            if (showing) { break; }
        }
        return changed;
    }

    /** The helper's links are no one's any more: their variants may ask again, and their binaries still fill the cache. */
    private void forgetLinks() {
        for (Variant variant : compiling) {
            List<Object> program = new ArrayList<>(List.of(variant.key));
            program.addAll(variant.sampleConfiguration);
            built.remove(program);
            variant.release();
        }
        compiling.clear();
    }

    /**
     * Requests the variant's program: null while its link runs; otherwise the variant is released, and the result is
     * whether any material changed program.
     */
    private Boolean finish(Variant variant, GpuGlsl.Preparation preparation, ShaderProvider provider) {
        boolean current = variant.sampleConfiguration.equals(configuration()), changed = false;
        int started = preparation.freshLinks();
        try {
            Runnable ask = () -> variant.sample.shader = provider.getShader(variant.sample);
            if (!(background ? preparation.runOptional(ask) : preparation.run(ask, freshLinks < fresh))) {
                variant.linkKey = preparation.lastKey();
                return null;
            }
            // A program of an earlier configuration stays in the provider for a return to it; it marks nothing.
            if (current) {
                variant.ready = true;
                changed = variant.mark(true);
            }
        } catch (GpuGlsl.Uncached refused) {
            // Nothing started: the provider holds no program of the variant for the configuration it was chosen in.
            variant.deferred = current;
            List<Object> program = new ArrayList<>(List.of(variant.key));
            program.addAll(variant.sampleConfiguration);
            built.remove(program);
        } catch (RuntimeException failure) {
            // Its draws keep the generic program, and a broken variant is not tried again in this configuration.
            variant.failed = current;
            LOGGER.warn(failure, "Terrain family program {} failed; its draws keep the generic program",
                  Arrays.toString(variant.families));
        } finally {
            freshLinks += preparation.freshLinks() - started;
        }
        variant.release();
        return changed;
    }

    private List<Object> configuration() { return List.of(environmentMask, environmentFlags, shadows); }

    /**
     * The most-drawn variant without a program that the capacity allows (a program the provider already holds costs
     * nothing), with its warm-up sample; null when none is left.
     */
    private Variant next(Environment environment) {
        // Variants whose chunks all went before their turn were stale edits.
        variants.values().removeIf(variant -> !variant.ready && !variant.failed && variant.live() == null);
        List<Variant> candidates = new ArrayList<>(variants.values().stream()
              .filter(variant -> !variant.ready && !variant.failed && !variant.deferred && !compiling.contains(variant)
                    && variant.weight() > 0)
              .toList());
        // Equal weights fall back to the variant's description: the choice must not follow chunk install order.
        candidates.sort(Comparator.comparingInt(Variant::weight).reversed()
              .thenComparing(variant -> variant.key.toString()));
        for (Variant variant : candidates) {
            List<Object> program = List.of(variant.key, environmentMask, environmentFlags, shadows);
            Material live = variant.live();
            if (live == null || !built.contains(program) && room() <= 0) { continue; }
            built.add(program);
            variant.sample = new Renderable();
            variant.sample.material = new Material(live);
            variant.sample.material.set(new Constants());
            // The configuration it compiles for, even if the terrain's changes before the link finishes.
            variant.sample.environment = new Environment();
            variant.sample.environment.set(environment);
            GpuEnvironmentLighting.freeze(variant.sample.environment);
            variant.sample.environment.shadowMap = environment.shadowMap;
            variant.sample.meshPart.set("terrain-family-warmup", new Mesh(true, 0, 0, variant.key.attributes()), 0, 0,
                  GL20.GL_TRIANGLES);
            variant.sampleConfiguration = configuration();
            return variant;
        }
        return null;
    }

    /**
     * Programs still to be built for the installed variants in this configuration, plus the running link, even one
     * for an earlier configuration: the loading stage waits until the provider has adopted it.
     */
    int pending() {
        int free = 0, others = 0;
        for (Variant variant : variants.values()) {
            if (variant.ready || variant.failed || variant.deferred || compiling.contains(variant)
                  || variant.live() == null) { continue; }
            if (built.contains(List.of(variant.key, environmentMask, environmentFlags, shadows))) { free++; }
            else { others++; }
        }
        // The loading stage doesn't wait for the helper's links, only to adopt the finished ones.
        long linking = background ? compiling.stream().filter(variant -> links.over(variant.linkKey)).count() : compiling.size();
        return (int) linking + free + Math.min(others, Math.max(0, room()));
    }

    /** Whether links in the helper go on: the terrain keeps taking steps while the board shows. */
    boolean adopting() { return background && !compiling.isEmpty(); }

    /** Programs this configuration may still build: its own capacity, within twice the capacity over all of them. */
    private int room() {
        long taken = built.stream().filter(program -> program.subList(1, 4).equals(configuration())).count();
        return (int) Math.min(capacity - taken, 2L * capacity - built.size());
    }

    /** The families of the programs ready in this configuration. */
    List<float[]> ready() {
        return variants.values().stream().filter(variant -> variant.ready).map(variant -> variant.families.clone())
              .toList();
    }

    /** Family programs built over all configurations: the ones the shader provider holds. */
    int programs() { return built.size(); }

    /** Whether a family link is running or waits for adoption. */
    boolean building() { return !compiling.isEmpty(); }

    /** Distinct variants installed chunks asked for, built or not. */
    int requested() { return variants.size(); }

    void dispose() {
        variants.values().forEach(Variant::release);
        compiling.forEach(Variant::release);
        variants.clear();
        compiling.clear();
    }

    /**
     * A sculpt draw's families: its sculpt family, then a blend's four slots. Null for a draw without a family program.
     * Crust and bank draws and volcanic palettes with at most one ordinary family already compile one family's path
     * (VOLCANIC_ORDINARY_FAMILY): constants would remove a few percent and take a compile slot. Scarred surfaces keep
     * their generic program.
     */
    static float[] families(Material material, long sculptType) {
        var sculpt = (FloatAttribute) material.get(sculptType);
        if (sculpt == null || material.has(GpuSurfaceScars.Marking.TYPE)) { return null; }
        var blend = (GpuSurfaceBlend) material.get(GpuSurfaceBlend.TYPE);
        if (blend == null) { return sculpt.value >= BoardSurfaceBlend.CRUST ? null : new float[] { sculpt.value }; }
        if (GpuSurfaceBlend.ordinaryFamily(material) != -1) { return null; }
        float[] slots = blend.families;
        return new float[] { sculpt.value, slots[0], slots[1], slots[2], slots[3] };
    }

    /** A marker in the id, as the material cache's and tessellation's, whose suffixes follow it (isTop, curved). */
    private static final String FLAT = "#flat";
    /**
     * The height every vertex normal of a level top reaches. Between such unit normals a pixel's normal stays this
     * high, so terrain-sculpt.frag's wall test (rocky) is false throughout the triangle.
     */
    static final float FLAT_Z = .601f;

    /** Whether a draw in this material divides off its level tops: concrete and crust have no natural ground to lose. */
    static boolean divides(Material material, long sculptType) {
        float[] families = families(material, sculptType);
        return families != null && families[0] != BoardScene.Surface.CONCRETE.ordinal()
              && !material.has(GpuMagmaShader.TYPE);
    }

    /** The material a chunk's level tops draw in: the same attributes, so the same generic program, under its own id. */
    static Material flat(Material material) {
        Material copy = new Material(material);
        copy.id = material.id + FLAT;
        return copy;
    }

    static boolean isFlat(Material material) { return material.id.contains(FLAT); }

    /** The material without the level tops' marker: what those tops' ground draws as where it curves. */
    static Material unflat(Material material) {
        Material copy = new Material(material);
        copy.id = material.id.replace(FLAT, "");
        return copy;
    }

    /** Whether this terrain builds family programs at all: previews and prints don't. */
    boolean enabled() { return enabled; }

    /** Reuse proven from this material's owned uniform values, never family identity alone. */
    static int baseAliases(Material material) {
        var blend = material.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
        return blend == null ? 0 : blend.baseAliases();
    }

    /** The families a program for this material is compiled with: a marked material's, none for a generic program. */
    static float[] constants(Material material, long sculptType) {
        return material.has(Constants.TYPE) ? families(material, sculptType) : null;
    }

    /**
     * Whether a program compiled with these families (null: a generic program) may draw the material: a generic program
     * no marked material, a family program only marked materials with its families, aliases and level tops. The shader
     * provider's scan rejects other programs first.
     */
    static boolean draws(float[] families, int aliases, boolean tops, Material material, long sculptType) {
        boolean marked = material.has(Constants.TYPE);
        if (families == null || !marked) { return families == null && !marked; }
        var sculpt = (FloatAttribute) material.get(sculptType);
        var blend = (GpuSurfaceBlend) material.get(GpuSurfaceBlend.TYPE);
        if (sculpt == null || sculpt.value != families[0] || (blend == null) != (families.length == 1)) {
            return false;
        }
        for (int i = 1; i < families.length; i++) {
            if (blend.families[i - 1] != families[i]) { return false; }
        }
        return aliases == baseAliases(material) && tops == isFlat(material);
    }

    /** The defines that make a family program's families constant; none for a generic program. */
    static String defines(Material material, long sculptType) {
        float[] families = constants(material, sculptType);
        if (families == null) { return ""; }
        String sculpt = "#define SCULPT_FAMILY " + families[0] + "\n" + (isFlat(material) ? "#define SCULPT_TOPS\n" : "");
        int aliases = baseAliases(material);
        String reuse = ((aliases & 1) != 0 ? "#define COVER_B_IS_BASE\n" : "")
              + ((aliases & 2) != 0 ? "#define COVER_C_IS_BASE\n" : "");
        return families.length == 1 ? sculpt : sculpt + "#define COVER_FAMILIES vec4(" + families[1] + ", "
              + families[2] + ", " + families[3] + ", " + families[4] + ")\n" + reuse;
    }

    /** The program name's suffix, as the shader editor and the compile log show it. */
    static String name(Material material, long sculptType) {
        float[] families = constants(material, sculptType);
        return families == null ? "" : " families " + Arrays.toString(families) + " aliases " + baseAliases(material) + (isFlat(material) ? " tops" : "");
    }
}
