/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import megamek.common.Configuration;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBParallelShaderCompile;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRParallelShaderCompile;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The board's shading language. The board asks for the newest core context the driver creates, from 4.6 down to 3.3,
 * the least it needs: some drivers (Intel's on Windows) return exactly the version asked for, so asking for 3.3 would
 * keep them there. Current Windows and Linux drivers give 4.6, every Mac 4.1. Once the context exists,
 * {@link #detect()} reads its version and every shader compiled afterwards is GLSL of it, up to 4.60. Board shaders
 * use GLSL 3.30 core syntax. Only libGDX's built-in shaders need the legacy spelling adapter; custom programs compile
 * and export complete sources without it. Program ownership and libGDX prefix overrides belong to the render
 * thread; startup compilation may use the driver's parallel compiler or a private shared context. A program linked
 * before, on the same driver, loads from {@link GpuProgramCache} instead of compiling.
 */
final class GpuGlsl {
    private static final MMLogger LOGGER = MMLogger.create(GpuGlsl.class);
    /** The lowest and highest shading language versions the board compiles for. */
    static final int MINIMUM = 330;
    static final int MAXIMUM = 460;
    /** A system property that caps the version, for troubleshooting a driver: {@code -Dmegamek.gpu.glsl=330}. */
    static final String CAP_PROPERTY = "megamek.gpu.glsl";
    /** Optional directory for complete, editable shader pairs, including material defines and shared functions. */
    static final String EXPORT_PROPERTY = "megamek.gpu.shaderExport";
    /** Opt-in wall-clock diagnostics for queued links and render-thread adoption, not GPU draw timings. */
    static final String TIMING_PROPERTY = "megamek.gpu.shaderTiming";
    private static final Pattern VERSION = Pattern.compile("(?m)^[\\t ]*#[\\t ]*version\\b[^\\r\\n]*(?:\\r?\\n|$)");
    private static final String VERTEX_DEFINES = """
          #define attribute in
          #define varying out
          #define texture2D texture
          #define texture2DLod textureLod
          #define textureCube texture
          """;
    private static final String FRAGMENT_DEFINES = """
          #define varying in
          #define texture2D texture
          #define texture2DLod textureLod
          #define textureCube texture
          out vec4 fragColor;
          #define gl_FragColor fragColor
          """;
    /** Context versions tried in turn, newest first. */
    private static final int[][] CONTEXTS = { { 4, 6 }, { 4, 5 }, { 4, 3 }, { 4, 1 }, { 4, 0 }, { 3, 3 } };
    private static int version = MINIMUM;
    private static String context = "OpenGL 3.3";
    /** The graphics card of the board's context, as its driver names it; empty until the context exists. */
    private static String renderer = "";
    /** The newest context this computer creates, found once per run. */
    private static int[] newest;
    private static final ThreadLocal<Preparation> PREPARING = new ThreadLocal<>();
    /** The render thread's program binaries, opened by {@link #detect()}; null caches nothing. Tests replace it. */
    static GpuProgramCache programCache;
    /** Whether Intel's own shader cache is full ({@link GpuProgramCache#INTEL_CACHE}): read once the context exists. */
    private static boolean intelCacheFull;
    /** Whether a board view shows its board rather than its loading stage (GpuBattleView). */
    private static volatile boolean presenting;
    /** Programs compiled from source while a board showed that held the render thread: none should. */
    private static final AtomicInteger AFTER_LOADING = new AtomicInteger();
    /** Programs compiled from source while a board showed that linked off the render thread ({@link #deferral}). */
    private static final AtomicInteger DEFERRED = new AtomicInteger();
    /** Render-thread time a presented frame may spend starting deferred links (binary loads, link submissions). */
    private static final long DEFERRAL_BUDGET = 6_000_000;
    private static long deferralSpent;
    /** See {@link #linkOffscreen}; render thread only. */
    private static Preparation offscreenLinks;
    private static final ThreadLocal<Boolean> OFFSCREEN = ThreadLocal.withInitial(() -> false);
    private static final IntBuffer FRAMEBUFFER = BufferUtils.newIntBuffer(16);

    private GpuGlsl() { }

    /**
     * A board view shows its board ({@code true}, once per presented frame) or its loading stage. A program compiled
     * from source while the board shows is counted: {@link #compilesAfterLoading()} when it held the render thread,
     * logged "after-loading" ("after-loading-offscreen" inside {@link #offscreen}) with {@link #TIMING_PROPERTY}; else
     * {@link #deferredAfterLoading()}, logged "after-loading-deferred". One flag serves every view on the render thread.
     */
    static void presenting(boolean shown) {
        presenting = shown;
        deferralSpent = 0;
    }

    static int compilesAfterLoading() { return AFTER_LOADING.get(); }

    static int deferredAfterLoading() { return DEFERRED.get(); }

    /**
     * Intel compiles a program again at its first draw to the other kind of framebuffer than its linking context last
     * drew to, and its binaries keep the first kind. Without the parallel compiler, a program requested outside a
     * preparation for a framebuffer object (one is bound, or {@link #offscreen} runs) therefore links on this
     * preparation's worker, which never draws, and the render thread waits; a program for the window links on the
     * render thread. Null leaves every such link on the render thread, as tests without a board view do. Once the board
     * shows, the same preparation links material programs that are still missing ({@link #deferral}).
     */
    static void linkOffscreen(Preparation preparation) { offscreenLinks = preparation; }

    /**
     * While a board shows: the preparation that links a material program a draw finds missing, off the render thread,
     * while its draws wait (GpuShaderProvider); null behind the loading stage, inside another preparation or without a
     * board view.
     */
    static Preparation deferral() { return presenting && PREPARING.get() == null ? offscreenLinks : null; }

    /** Adds render-thread time spent starting or checking a deferred link to this frame's budget. */
    static void spent(long nanos) { deferralSpent += nanos; }

    /**
     * Whether this presented frame spent its budget for links (GpuShaderProvider's, a terrain's warm-up): programs not
     * yet requested wait for the next frame.
     */
    static boolean overBudget() { return presenting && deferralSpent >= DEFERRAL_BUDGET; }

    /** Runs code that creates programs drawing into framebuffer objects only, such as effects warmed up in advance. */
    static void offscreen(Runnable creation) {
        boolean previous = OFFSCREEN.get();
        OFFSCREEN.set(true);
        try { creation.run(); }
        finally { OFFSCREEN.set(previous); }
    }

    private static boolean offscreen() {
        if (OFFSCREEN.get()) { return true; }
        FRAMEBUFFER.clear();
        Gdx.gl.glGetIntegerv(GL30.GL_DRAW_FRAMEBUFFER_BINDING, FRAMEBUFFER);
        return FRAMEBUFFER.get(0) != 0;
    }

    static ShaderProgram compile(String name, String prefix, String vertex, String fragment) {
        return compilePrepared(name, source(prefix, vertex, version), source(prefix, fragment, version));
    }

    /**
     * Resource stages declare their editor baseline; the runtime supplies the selected version and material flags. A
     * vertex source that carries tessellation stages (GpuTessellation) gives each of them the same header.
     */
    static String source(String prefix, String source, int glsl) {
        String[] stages = GpuTessellation.stages(source);
        String header = "#version " + glsl + " core\n" + prefix;
        if (stages.length == 1) { return header + VERSION.matcher(source).replaceFirst(""); }
        return header + VERSION.matcher(stages[0]).replaceFirst("") + "\n" + GpuTessellation.CONTROL + "\n" + header
              + stages[1] + "\n" + GpuTessellation.EVALUATION + "\n" + header + VERSION.matcher(stages[2]).replaceFirst("");
    }

    /** The shading language every board shader compiles as, from {@link #MINIMUM} to {@link #MAXIMUM}. */
    static int glsl() { return version; }

    static ShaderProgram compile(String name, String vertex, String fragment) {
        return compile(name, "", vertex, fragment);
    }

    /**
     * The source without its comments, as the GLSL preprocessor removes them, keeping every line break so that compiler
     * messages keep their line numbers. A block comment within a line becomes a space.
     */
    static String withoutComments(String source) {
        // Whole runs between comments are copied at once: a load strips every program's sources while the JIT is cold.
        int length = source.length(), from = 0, slash = source.indexOf('/');
        StringBuilder result = new StringBuilder(length);
        while (slash >= 0 && slash + 1 < length) {
            char next = source.charAt(slash + 1);
            if (next == '/') {
                result.append(source, from, slash);
                from = slash + 2;
                while (from < length && source.charAt(from) != '\n' && source.charAt(from) != '\r') { from++; }
            } else if (next == '*') {
                result.append(source, from, slash);
                int end = source.indexOf("*/", slash + 2);
                if (end < 0) { end = length - 2; }
                int lines = result.length();
                for (int j = slash + 2; j < end; j++) {
                    char inside = source.charAt(j);
                    if (inside == '\n' || inside == '\r') { result.append(inside); }
                }
                if (result.length() == lines) { result.append(' '); }
                from = end + 2;
            } else {
                slash = source.indexOf('/', slash + 1);
                continue;
            }
            slash = from < length ? source.indexOf('/', from) : -1;
        }
        if (from < length) { result.append(source, from, length); }
        return result.toString();
    }

    /** Own every handle through failure, then transfer the successful link to libGDX without recompiling it. */
    private static ShaderProgram compilePrepared(String name, String vertex, String fragment) {
        // A comment-only edit of a shared file then changes neither the cache key nor the program.
        vertex = withoutComments(vertex);
        fragment = withoutComments(fragment);
        GpuShaderManager.compiling(name, vertex, fragment);
        var source = new GpuShaderManager.Sources(name, vertex, fragment);
        Preparation preparation = PREPARING.get();
        Linked linked = preparation != null ? preparation.take(source)
              : offscreenLinks != null && offscreenLinks.worker != null && offscreen()
                    ? offscreenLinks.submit(source).await(source) : new Linked(source);
        try { return linked.adopt(source); }
        finally { linked.dispose(); }
    }

    /** A provider construction can yield here before allocating its material shader or publishing any program. */
    static final class Pending extends RuntimeException {
        private Pending() { super(null, null, false, false); }
    }

    /**
     * Render-thread-owned startup links, polled once per loading frame. No link-status query, uniform lookup or
     * libGDX construction may force a pending driver job to finish. Without the extension, a worker owns a hidden
     * shared GL context and performs the blocking driver work there. Adoption always belongs to the render thread.
     */
    static final class Preparation implements Disposable {
        private final Map<GpuShaderManager.Sources, Linked> pending = new HashMap<>();
        private record Job(Linked linked, CompletableFuture<Void> done) {
            /** The finished link; a failed one is released and rethrown under the program's name. */
            Linked await(GpuShaderManager.Sources source) {
                try {
                    done.join();
                    return linked;
                } catch (RuntimeException failure) {
                    linked.dispose();
                    if (failure instanceof CompletionException) {
                        throw new GdxRuntimeException(source.name(), failure.getCause());
                    }
                    throw failure;
                }
            }
        }
        private final Map<GpuShaderManager.Sources, Job> working = new HashMap<>();
        private final SharedCompiler worker;
        /**
         * Native tests set this to put every preparation on the shared-context path: its links finish on another thread,
         * so one stays pending for at least a step even where the driver's own program cache finishes a parallel link at
         * once.
         */
        static boolean sharedContext;

        Preparation() { this(sharedContext); }

        /** Force the shared-context path in native tests even on a driver with parallel-compilation support. */
        Preparation(boolean sharedContext) {
            boolean khr = Gdx.graphics.supportsExtension("GL_KHR_parallel_shader_compile");
            boolean arb = Gdx.graphics.supportsExtension("GL_ARB_parallel_shader_compile");
            worker = sharedContext || !(khr || arb) ? new SharedCompiler() : null;
            if (worker == null) {
                if (khr) { KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(-1); }
                else { ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(-1); }
            }
        }

        boolean run(Runnable action) {
            Preparation previous = PREPARING.get();
            PREPARING.set(this);
            try { action.run(); return true; }
            catch (Pending unfinished) { return false; }
            finally {
                if (previous == null) { PREPARING.remove(); }
                else { PREPARING.set(previous); }
            }
        }

        /** Whether the driver links in parallel off this thread; else one shared-context worker links one at a time. */
        boolean parallel() { return worker == null; }

        private Job submit(GpuShaderManager.Sources source) {
            Linked linked = new Linked(source, true);
            Gdx.gl20.glFlush(); // Publish the newly allocated shared objects before the worker uses them.
            linked.timing("queued-shared");
            return new Job(linked, worker.compile(linked, source));
        }

        /** Whether a link it started is still running: running its action again would only find it unfinished. */
        boolean linking() {
            for (Job job : working.values()) { if (!job.done().isDone()) { return true; } }
            for (Linked linked : pending.values()) { if (!linked.complete()) { return true; } }
            return false;
        }

        private Linked take(GpuShaderManager.Sources source) {
            if (worker != null) {
                Job job = working.computeIfAbsent(source, this::submit);
                if (!job.done().isDone()) { throw new Pending(); }
                working.remove(source);
                return job.await(source);
            }
            Linked linked = pending.computeIfAbsent(source, Linked::new);
            if (!linked.complete()) { throw new Pending(); }
            linked.timing("parallel-ready");
            pending.remove(source);
            return linked;
        }

        @Override
        public void dispose() {
            // Teardown must release the worker's context before GLFW destroys either shared window. A native
            // compiler call cannot be interrupted safely; closing the renderer may wait for that call to return.
            if (worker != null) {
                working.values().forEach(job -> job.done().cancel(false));
                worker.dispose();
            }
            working.values().forEach(job -> job.linked().dispose());
            working.clear();
            pending.values().forEach(Linked::dispose);
            pending.clear();
        }
    }

    /**
     * One private context per compiler, all on one thread that never ends; never use or replace Gdx globals there. On
     * Windows an exiting thread that had a context current enters NVIDIA's driver under the loader lock, while
     * glfwDestroyWindow holds that driver's lock and waits for driver threads that need the loader lock: a compiler
     * thread ending at teardown hung the process (RTX 4070, driver 617.42).
     */
    private static final class SharedCompiler implements Disposable {
        private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "terrain-shader-compiler");
            thread.setDaemon(true);
            return thread;
        });
        private static final GL20 CALLS = compilerCalls();
        private final long window;
        /** Its context's functions, created on first use; only the compiler thread reads or writes them. */
        private GLCapabilities capabilities;

        SharedCompiler() {
            long owner = GLFW.glfwGetCurrentContext();
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GL11.glGetInteger(GL30.GL_MAJOR_VERSION));
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GL11.glGetInteger(GL30.GL_MINOR_VERSION));
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            if (SharedLibraryLoader.os == Os.MacOsX) { GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE); }
            try { window = GLFW.glfwCreateWindow(1, 1, "Terrain shader compiler", 0, owner); }
            finally { GLFW.glfwDefaultWindowHints(); }
            if (window == 0) { throw new GdxRuntimeException("Cannot create a shared terrain shader compiler context"); }
        }

        CompletableFuture<Void> compile(Linked linked, GpuShaderManager.Sources source) {
            return CompletableFuture.runAsync(() -> {
                GL20 gl = current();
                try {
                    linked.link(source, gl);
                    linked.validate(source, gl); // Intentionally blocking only this worker.
                } finally {
                    // Failed links also need a completed producer stream before the render thread deletes them.
                    linked.timing("producer-finish-start");
                    gl.glFinish();
                    linked.timing("producer-finished");
                }
            }, THREAD);
        }

        /** Makes this compiler's context current on the compiler thread, which another compiler may have used last. */
        private GL20 current() {
            if (GLFW.glfwGetCurrentContext() != window) {
                GLFW.glfwMakeContextCurrent(window);
                if (capabilities == null) { capabilities = GL.createCapabilities(); }
                else { GL.setCapabilities(capabilities); }
            }
            return CALLS;
        }

        @Override
        public void dispose() {
            // Release on the compiler thread first: a failed release keeps a window whose context may still be current.
            CompletableFuture.runAsync(() -> {
                if (GLFW.glfwGetCurrentContext() != window) { return; }
                GLFW.glfwMakeContextCurrent(0);
                if (GLFW.glfwGetCurrentContext() != 0) {
                    throw new GdxRuntimeException("Cannot release terrain shader compiler context: " + glfwError());
                }
                GL.setCapabilities(null);
            }, THREAD).join();
            GLFW.glfwDestroyWindow(window);
        }

        /** libGDX's backend adapter is package-private and owns scratch buffers used by the render thread. */
        private static GL20 compilerCalls() {
            return (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                  (proxy, method, args) -> {
                      switch (method.getName()) {
                          case "glShaderSource": GL20C.glShaderSource((int) args[0], (String) args[1]); break;
                          case "glCompileShader": GL20C.glCompileShader((int) args[0]); break;
                          case "glAttachShader": GL20C.glAttachShader((int) args[0], (int) args[1]); break;
                          case "glDetachShader": GL20C.glDetachShader((int) args[0], (int) args[1]); break;
                          case "glLinkProgram": GL20C.glLinkProgram((int) args[0]); break;
                          case "glGetShaderiv": GL20C.glGetShaderiv((int) args[0], (int) args[1], (IntBuffer) args[2]); break;
                          case "glGetProgramiv": GL20C.glGetProgramiv((int) args[0], (int) args[1], (IntBuffer) args[2]); break;
                          case "glGetShaderInfoLog": return GL20C.glGetShaderInfoLog((int) args[0]);
                          case "glGetProgramInfoLog": return GL20C.glGetProgramInfoLog((int) args[0]);
                          case "glFinish": GL11.glFinish(); break;
                          default: throw new UnsupportedOperationException("Compiler GL call: " + method.getName());
                      }
                      return null;
                  });
        }
    }

    private static final class Linked implements Disposable {
        private int vertex, fragment, program;
        /** The tessellation stages, when the vertex source carries them (GpuTessellation); else 0. */
        private int control, evaluation;
        private final java.nio.IntBuffer status = BufferUtils.newIntBuffer(1);
        private final String timingId;
        private final long requestedAt;
        private long previousTiming;
        private final GpuProgramCache cache;
        private final String cacheKey;
        /** Loaded from the cache: its stages were never compiled. Cached: loaded or stored, nothing left to keep. */
        private boolean binary, cached;
        /**
         * Requested while a board showed (presenting): by the board's own frames, or by an offscreen renderer; and
         * whether the render thread waits for it, outside any preparation.
         */
        private final boolean afterLoading = presenting, offscreenRequest = OFFSCREEN.get(), stalls = PREPARING.get() == null;

        Linked(GpuShaderManager.Sources source) { this(source, false); }

        Linked(GpuShaderManager.Sources source, boolean deferred) {
            timingId = Boolean.getBoolean(TIMING_PROPERTY) ? source.name() + " "
                  + sourceId(source.vertex(), source.fragment())
                  + " (vertex=" + source.vertex().length() + ", fragment=" + source.fragment().length() + ")" : null;
            requestedAt = timingId == null ? 0 : System.nanoTime();
            previousTiming = requestedAt;
            timing(deferred ? "requested-shared" : PREPARING.get() == null ? "requested-synchronous" : "requested-parallel");
            export(source.name(), source.vertex(), source.fragment());
            cache = programCache;
            cacheKey = cache == null ? null : cache.key(source.vertex(), source.fragment());
            GL20 gl = Gdx.gl20;
            try {
                vertex = gl.glCreateShader(GL20.GL_VERTEX_SHADER);
                fragment = gl.glCreateShader(GL20.GL_FRAGMENT_SHADER);
                program = gl.glCreateProgram();
                if (GpuTessellation.stages(source.vertex()).length == 3) {
                    control = gl.glCreateShader(GL40.GL_TESS_CONTROL_SHADER);
                    evaluation = gl.glCreateShader(GL40.GL_TESS_EVALUATION_SHADER);
                    if (control == 0 || evaluation == 0) {
                        throw new GdxRuntimeException(source.name() + ": cannot create tessellation stages");
                    }
                }
                if (vertex == 0 || fragment == 0 || program == 0) {
                    throw new GdxRuntimeException(source.name() + ": cannot create shader program");
                }
                timing("allocated");
                if (!deferred) { link(source, gl); }
            } catch (RuntimeException failure) { dispose(gl); throw failure; }
        }

        /** A rejected binary leaves this program unlinked; the source link below then replaces that state. */
        void link(GpuShaderManager.Sources source, GL20 gl) {
            if (cache != null && cache.load(program, cacheKey)) {
                binary = cached = true;
                timing("binary-loaded");
                return;
            }
            if (afterLoading && !stalls) {
                DEFERRED.incrementAndGet();
                timing("after-loading-deferred");
            } else if (afterLoading) {
                AFTER_LOADING.incrementAndGet();
                timing(offscreenRequest ? "after-loading-offscreen" : "after-loading");
            }
            timing("source-upload-start");
            String[] stages = GpuTessellation.stages(source.vertex());
            gl.glShaderSource(vertex, stages[0]);
            gl.glShaderSource(fragment, source.fragment());
            if (control != 0) {
                gl.glShaderSource(control, stages[1]);
                gl.glShaderSource(evaluation, stages[2]);
            }
            timing("source-uploaded");
            gl.glCompileShader(vertex);
            timing("vertex-submitted");
            gl.glCompileShader(fragment);
            timing("fragment-submitted");
            gl.glAttachShader(program, vertex);
            gl.glAttachShader(program, fragment);
            if (control != 0) {
                gl.glCompileShader(control);
                gl.glCompileShader(evaluation);
                gl.glAttachShader(program, control);
                gl.glAttachShader(program, evaluation);
            }
            if (cache != null) { GpuProgramCache.retrievable(program); }
            gl.glLinkProgram(program);
            timing("link-submitted");
        }


        boolean complete() {
            Gdx.gl20.glGetProgramiv(program, ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB, status);
            return status.get(0) != 0;
        }

        void validate(GpuShaderManager.Sources source, GL20 gl) {
            timing("validation-start");
            // Resolve the link even when a stage failed before disposing its owned handles.
            gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, status);
            boolean linked = status.get(0) != 0;
            int[] shaders = binary ? new int[0] : control == 0 ? new int[] { vertex, fragment }
                  : new int[] { vertex, control, evaluation, fragment };
            for (int shader : shaders) {
                gl.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, status);
                if (status.get(0) == 0) {
                    String error = source.name() + (shader == vertex ? " vertex: " : shader == control
                          ? " tessellation control: " : shader == evaluation ? " tessellation evaluation: " : " fragment: ")
                          + gl.glGetShaderInfoLog(shader);
                    clearFailedLink(gl);
                    throw new GdxRuntimeException(error);
                }
            }
            if (!linked) {
                String error = source.name() + " link: " + gl.glGetProgramInfoLog(program);
                clearFailedLink(gl);
                throw new GdxRuntimeException(error);
            }
            // The first successful check keeps the program: a worker's own, otherwise the render thread's adoption.
            if (cache != null && !cached) {
                cached = true;
                cache.store(program, cacheKey);
                timing("binary-stored");
            }
            timing("validated");
        }

        private void timing(String stage) {
            if (timingId == null) { return; }
            long now = System.nanoTime();
            LOGGER.info("GPU_SHADER {} stage={} delta={}ms age={}ms thread={}", timingId, stage,
                  String.format(Locale.ROOT, "%.3f", (now - previousTiming) / 1e6),
                  String.format(Locale.ROOT, "%.3f", (now - requestedAt) / 1e6), Thread.currentThread().getName());
            previousTiming = now;
        }

        /**
         * Intel 32.0.101.7088 retains failed-link state when another context later reuses the deleted program's
         * name. Resolve a tiny valid link before deletion, only on that vendor's error path. The original diagnostic
         * is retained, and a successful link does no extra compilation.
         */
        private void clearFailedLink(GL20 gl) {
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            if (vendor == null || !vendor.toLowerCase(Locale.ROOT).contains("intel")) { return; }
            if (control != 0) {
                gl.glDetachShader(program, control);
                gl.glDetachShader(program, evaluation);
            }
            gl.glShaderSource(vertex, "#version 330 core\nvoid main(){gl_Position=vec4(0.0);}");
            gl.glShaderSource(fragment, "#version 330 core\nout vec4 color; void main(){color=vec4(0.0);}");
            gl.glCompileShader(vertex);
            gl.glCompileShader(fragment);
            gl.glLinkProgram(program);
            gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, status);
        }

        ShaderProgram adopt(GpuShaderManager.Sources source) {
            timing("adoption-start");
            var gl = Gdx.gl20;
            validate(source, gl);
            String vertexPrefix = ShaderProgram.prependVertexCode, fragmentPrefix = ShaderProgram.prependFragmentCode;
            try {
                // ShaderProgram has no constructor for linked handles. Adapt only its construction calls; all
                // reflection/introspection uses the real GL implementation. No private libGDX fields are modified.
                Gdx.gl20 = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                      (proxy, method, args) -> {
                          return switch (method.getName()) {
                              case "glCreateShader" -> (int) args[0] == GL20.GL_VERTEX_SHADER ? vertex : fragment;
                              case "glCreateProgram" -> program;
                              case "glShaderSource", "glCompileShader", "glAttachShader", "glLinkProgram" -> null;
                              // A loaded binary's stage objects were never compiled; libGDX only asks whether they were.
                              case "glGetShaderiv" -> {
                                  if (binary && (int) args[1] == GL20.GL_COMPILE_STATUS) {
                                      ((IntBuffer) args[2]).put(0, GL20.GL_TRUE);
                                      yield null;
                                  }
                                  yield invoke(gl, method, args);
                              }
                              default -> invoke(gl, method, args);
                          };
                      });
                ShaderProgram.prependVertexCode = "";
                ShaderProgram.prependFragmentCode = "";
                ShaderProgram result = new GpuShaderUniforms(source.name(), source.vertex(), source.fragment());
                if (!result.isCompiled()) { throw new GdxRuntimeException(source.name() + ": " + result.getLog()); }
                vertex = fragment = program = 0; // The libGDX owner now disposes these exact handles.
                timing("adopted");
                return result;
            } finally {
                Gdx.gl20 = gl;
                ShaderProgram.prependVertexCode = vertexPrefix;
                ShaderProgram.prependFragmentCode = fragmentPrefix;
            }
        }

        @Override
        public void dispose() { dispose(Gdx.gl20); }

        /** After an adoption libGDX owns the vertex and fragment stages; the tessellation stages are still this one's. */
        private void dispose(GL20 gl) {
            if (vertex != 0) { gl.glDeleteShader(vertex); }
            if (fragment != 0) { gl.glDeleteShader(fragment); }
            if (control != 0) { gl.glDeleteShader(control); }
            if (evaluation != 0) { gl.glDeleteShader(evaluation); }
            if (program != 0) { gl.glDeleteProgram(program); }
            vertex = fragment = control = evaluation = program = 0;
        }
    }

    /** A proxied call passed on to the real GL implementation, failing with its own exception. */
    private static Object invoke(GL20 gl, Method method, Object[] args) throws Throwable {
        try { return method.invoke(gl, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static String sourceId(String vertex, String fragment) {
        return UUID.nameUUIDFromBytes((vertex + "\0" + fragment).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void export(String name, String vertex, String fragment) {
        String directory = System.getProperty(EXPORT_PROPERTY);
        if (directory == null || directory.isBlank()) { return; }
        String id = sourceId(vertex, fragment);
        String file = name.replaceAll("[^a-zA-Z0-9._-]", "-") + "-" + id;
        try {
            new FileHandle(new File(directory, file + ".vert")).writeString(vertex, false, "UTF-8");
            new FileHandle(new File(directory, file + ".frag")).writeString(fragment, false, "UTF-8");
        } catch (GdxRuntimeException failure) {
            LOGGER.warn("Could not export shader {} to {}", name, directory, failure);
        }
    }

    /**
     * Requests the newest core context this computer creates and compiles every shader as GLSL 3.30 until
     * {@link #detect()} runs.
     */
    static void configure(Lwjgl3ApplicationConfiguration configuration) {
        if (newest == null) { newest = newestContext(); }
        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, newest[0], newest[1]);
        use(MINIMUM);
    }

    /**
     * Tries a hidden window for each supported version. A failed probe reports its native error instead of pretending
     * that 3.3 is available. libGDX's own window then finds GLFW initialized; its remaining hints concern unused input
     * and ANGLE settings.
     */
    private static int[] newestContext() {
        Lwjgl3NativesLoader.load();
        // These windows are the run's first OpenGL contexts, which fix the graphics card it draws with.
        GpuGraphicsCard.apply();
        if (!GLFW.glfwInit()) { throw new GdxRuntimeException("Could not initialize GLFW. " + glfwError()); }
        boolean available = false;
        try {
            String error = "";
            for (int[] candidate : CONTEXTS) {
                GLFW.glfwDefaultWindowHints();
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, candidate[0]);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, candidate[1]);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                if (SharedLibraryLoader.os == Os.MacOsX) {
                    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
                }
                long window = GLFW.glfwCreateWindow(1, 1, "", 0, 0);
                if (window != 0) {
                    GLFW.glfwDestroyWindow(window);
                    available = true;
                    return candidate;
                }
                error = glfwError();
            }
            throw new GdxRuntimeException("Could not create an OpenGL 3.3 or newer context. " + error);
        } finally {
            GLFW.glfwDefaultWindowHints();
            if (!available) { GLFW.glfwTerminate(); }
        }
    }

    /** GLFW errors belong to the calling thread; capture them before handing a failure to the EDT. */
    static String glfwError() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var description = stack.callocPointer(1);
            int error = GLFW.glfwGetError(description);
            if (error == GLFW.GLFW_NO_ERROR) { return ""; }
            String detail = description.get(0) == 0 ? "No description" : MemoryUtil.memUTF8(description.get(0));
            return String.format(Locale.ROOT, "GLFW 0x%08X: %s", error, detail);
        }
    }

    /**
     * Compiles every later shader for the version of the current context. Runs on the render thread once the window's
     * context exists and before the first shader is compiled.
     */
    static void detect() {
        int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION), minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
        int chosen = select(major, minor, Integer.getInteger(CAP_PROPERTY, MAXIMUM));
        use(chosen);
        context = String.format(Locale.ROOT, "OpenGL %d.%d", major, minor);
        renderer = GL11.glGetString(GL11.GL_RENDERER);
        LOGGER.info("3D board: {} on {}, shaders compiled as GLSL {}", GL11.glGetString(GL11.GL_VERSION), renderer,
              chosen);
        // Machine-specific state belongs with MegaMek's other written settings.
        programCache = GpuProgramCache.open(
              GpuProgramCache.directory(new File(Configuration.configDir(), "gpu-program-cache").toPath()));
        String vendor = GL11.glGetString(GL11.GL_VENDOR);
        boolean intel = vendor != null && vendor.toLowerCase(Locale.ROOT).contains("intel");
        // Before any loading stage: one small folder's listing, about a millisecond.
        intelCacheFull = SharedLibraryLoader.os == Os.Windows && intel
              && GpuProgramCache.full(GpuProgramCache.INTEL_CACHE, GpuProgramCache.INTEL_CACHE_CAP);
    }

    /** Whether Intel's own shader cache has filled up; false on other drivers and systems. */
    static boolean intelCacheFull() { return intelCacheFull; }

    /** The GLSL version for an OpenGL context version: the same version, capped, never below {@link #MINIMUM}. */
    static int select(int major, int minor, int cap) {
        return Math.clamp(Math.min(major * 100 + minor * 10, cap), MINIMUM, MAXIMUM);
    }

    /** The graphics card the board draws with, as its driver names it; empty before the board's context exists. */
    static String renderer() {
        return renderer == null ? "" : renderer;
    }

    /** The context and shading language in use, as the tuning panel shows them. */
    static String description() {
        return String.format(Locale.ROOT, "%s, GLSL %d.%02d", context, version / 100, version % 100);
    }

    private static void use(int glsl) {
        version = glsl;
        ShaderProgram.prependVertexCode = "#version " + glsl + " core\n" + VERTEX_DEFINES;
        ShaderProgram.prependFragmentCode = "#version " + glsl + " core\n" + FRAGMENT_DEFINES;
    }
}
