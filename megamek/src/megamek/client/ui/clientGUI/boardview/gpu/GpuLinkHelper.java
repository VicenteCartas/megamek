/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.WinBase;

/**
 * Links programs in a process of its own and leaves their binaries in the program cache. Without the driver's parallel
 * compiler, a link on any context of the game's process holds the render thread for the whole link (Intel: 2-7 s for a
 * terrain program, every GL call that touches a program, texture or buffer waits). A link in another process holds
 * nothing, and its binary loads in 2-4 ms ({@link GpuGlsl.Preparation}).
 * <p>
 * One helper, started on the first job and leaving after a minute with none; it also leaves when the game does, which
 * closes its input. It uses the game's Java and class path, no libGDX, the same context version, and the same card: a
 * helper on another driver says so and is not used. It never prunes the cache. A helper that won't start or dies again
 * and again ends this: the game's own paths take over behind the loading stage, as on a driver without binary formats,
 * where none starts at all; beside the frames a program a generic one covers then simply stays generic.
 */
final class GpuLinkHelper {
    private static final MMLogger LOGGER = MMLogger.create(GpuLinkHelper.class);
    /** {@code -Dmegamek.gpu.linkHelper=false} keeps links in the game's process; {@code true} also uses it beside the parallel compiler. */
    static final String PROPERTY = "megamek.gpu.linkHelper";
    private static final long START_NANOS = 60_000_000_000L, JOB_NANOS = 300_000_000_000L, IDLE_NANOS = 60_000_000_000L;
    /** Helpers that may die before this ends, and binaries of one program the driver may reject before the helper leaves it be. */
    private static final int DEATHS = 2, REJECTIONS = 3;
    private static final String READY = "READY", MISMATCH = "MISMATCH", DONE = "DONE ", FAILED = "FAILED ", UNSAVED = "UNSAVED ";
    private static final int[] STAGES = { GL20C.GL_VERTEX_SHADER, GL20C.GL_FRAGMENT_SHADER, GL40C.GL_TESS_CONTROL_SHADER,
          GL40C.GL_TESS_EVALUATION_SHADER };
    private static final String[] STAGE_NAMES = { "vertex", "fragment", "tessellation control", "tessellation evaluation" };

    private record Job(String key, String name, List<String> stages, boolean optional) { }

    private final GpuProgramCache cache;
    private final Path directory;
    private final String driver;
    private final int major, minor;
    /** Guards the queues; required programs (behind loading, an editor's) go before optional ones (family programs). */
    private final Object lock = new Object();
    private final ArrayDeque<Job> required = new ArrayDeque<>(), optional = new ArrayDeque<>();
    /** Queued or linking. */
    private final Set<String> waiting = ConcurrentHashMap.newKeySet();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final Map<String, Integer> rejections = new ConcurrentHashMap<>();
    /** Programs it can't give the game, whose binary doesn't reach the cache or doesn't load: not worth asking again. */
    private final Set<String> refused = ConcurrentHashMap.newKeySet();
    private volatile boolean usable = true;
    private volatile Process process;
    private Thread thread;

    private GpuLinkHelper(GpuProgramCache cache, int major, int minor) {
        this.cache = cache;
        directory = cache.directory();
        driver = cache.driver();
        this.major = major;
        this.minor = minor;
    }

    /** The helper for this context, or null: no cache to leave binaries in, the driver links in parallel, or the property. */
    static GpuLinkHelper open(GpuProgramCache cache, int major, int minor, boolean parallelCompiler) {
        String wanted = System.getProperty(PROPERTY, "");
        boolean use = wanted.isEmpty() ? !parallelCompiler : Boolean.parseBoolean(wanted);
        return use && cache != null && launcher() != null ? new GpuLinkHelper(cache, major, minor) : null;
    }

    /** Whether its binaries reach this cache: tests replace the cache. */
    boolean serves(GpuProgramCache cache) {
        return cache != null && directory.equals(cache.directory()) && driver.equals(cache.driver());
    }

    boolean usable() { return usable; }

    /** Queued or linking; false again once its binary is in the cache, or it failed, or the helper ended. */
    boolean waiting(String key) { return waiting.contains(key); }

    /** Whether any program is queued or linking. */
    boolean busy() { return !waiting.isEmpty(); }

    /** Why this program doesn't compile, as the driver says it; null while it hasn't failed. */
    String failure(String key) { return failures.get(key); }

    /** The driver rejected this program's binary: one it keeps rejecting is no use linking again. */
    void rejected(String key) {
        if (rejections.merge(key, 1, Integer::sum) >= REJECTIONS) { refused.add(key); }
    }

    /** Whether this program's binary can't reach the game this way: it links there if it must, or stays without. */
    boolean refused(String key) { return refused.contains(key); }

    /** Asks for a program whose binary isn't in the cache, once while it waits. */
    void submit(String key, GpuShaderManager.Sources source, boolean optionalProgram) {
        if (!usable || !waiting.add(key)) { return; }
        List<String> stages = new ArrayList<>(List.of(GpuTessellation.stages(source.vertex())));
        stages.add(1, source.fragment());
        synchronized (lock) {
            (optionalProgram ? optional : required).add(new Job(key, source.name(), stages, optionalProgram));
            if (thread == null) {
                thread = new Thread(this::run, "shader-link-helper");
                thread.setDaemon(true);
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** Forgets queued optional programs: a lighting change wants others. One already linking still fills the cache. */
    void clearOptional() {
        synchronized (lock) {
            optional.forEach(job -> waiting.remove(job.key()));
            optional.clear();
        }
    }

    /** The helper's process, for tests; null while none runs. */
    Process process() { return process; }

    /** Runs on its own thread, so that nothing it writes, reads or waits for reaches the render thread. */
    private void run() {
        int deaths = 0;
        DataOutputStream output = null;
        BufferedReader input = null;
        while (usable) {
            Job job = null;
            try {
                job = next(output != null);
                if (job == null) {
                    if (output != null) { output.close(); }
                    output = null;
                    input = null;
                    process = null;
                    continue;
                }
                if (output == null) {
                    Process started = start();
                    process = started;
                    output = new DataOutputStream(started.getOutputStream());
                    input = started.inputReader(StandardCharsets.UTF_8);
                    write(output, driver);
                    output.flush();
                    String first = reply(started, input, START_NANOS);
                    if (!READY.equals(first)) {
                        end(MISMATCH.equals(first) ? "it draws on another driver" : "it did not start");
                        started.destroyForcibly();
                        return;
                    }
                }
                write(output, job.key());
                write(output, job.name());
                output.writeInt(job.stages().size());
                for (String stage : job.stages()) { write(output, stage); }
                output.flush();
                String answer = reply(process, input, JOB_NANOS);
                if (answer == null) { throw new IOException("no answer"); }
                String[] parts = answer.split(" ", 3);
                if (answer.startsWith(FAILED)) {
                    failures.put(parts[1], new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8));
                } else if (answer.startsWith(UNSAVED)) {
                    refused.add(parts[1]);
                } else {
                    cache.added(Long.parseLong(parts[2]));
                }
                waiting.remove(job.key());
            } catch (IOException | RuntimeException died) {
                LOGGER.info("3D board: the shader link helper stopped: {}", died.toString());
                Process gone = process;
                if (gone != null) { gone.destroyForcibly(); }
                process = null;
                output = null;
                input = null;
                if (++deaths > DEATHS) {
                    end("it stopped " + deaths + " times");
                } else if (job != null) {
                    synchronized (lock) { (job.optional() ? optional : required).addFirst(job); }
                }
            }
        }
        Process last = process;
        if (last != null) { last.destroyForcibly(); }
        process = null;
    }

    /** The next job, required first; null after a minute with none where a helper runs, and once this has ended. */
    private Job next(boolean running) {
        long idleSince = System.nanoTime();
        synchronized (lock) {
            while (usable && required.isEmpty() && optional.isEmpty()) {
                long left = IDLE_NANOS - (System.nanoTime() - idleSince);
                if (running && left <= 0) { return null; }
                try {
                    if (running) { lock.wait(left / 1_000_000 + 1); } else { lock.wait(); }
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }
            return required.isEmpty() ? optional.poll() : required.poll();
        }
    }

    /** The helper's next protocol line within the time, polling so that a hung driver or a dead helper can't hold this thread; null then. */
    private static String reply(Process helper, BufferedReader input, long nanos) throws IOException {
        long deadline = System.nanoTime() + nanos;
        while (true) {
            // Whether it's alive comes first: a line it wrote before it left is still read.
            boolean over = !helper.isAlive() || System.nanoTime() > deadline;
            if (input.ready()) {
                String line = input.readLine();
                if (line == null) { return null; }
                if (line.equals(READY) || line.equals(MISMATCH) || line.startsWith(DONE) || line.startsWith(FAILED) || line.startsWith(UNSAVED)) { return line; }
            } else if (over) {
                return null;
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    /** From here on the helper is out of the way; those waiting for it find it ended and go their own way. */
    private void end(String why) {
        LOGGER.info("3D board: no shader link helper, links stay in this process: {}", why);
        usable = false;
        synchronized (lock) {
            required.clear();
            optional.clear();
            lock.notifyAll();
        }
        waiting.clear();
    }

    private Process start() throws IOException {
        return start(command(List.of("-Xmx128m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Djava.awt.headless=true"),
              GpuLinkHelper.class, List.of(directory.toString(), Integer.toString(major), Integer.toString(minor))));
    }

    /**
     * The command that runs {@code main} with the game's Java and class path after {@code options}, its log4j off: its
     * logger must not write to the game's log files, which rotate, nor to its answers. Null when there's no Java to start.
     */
    static List<String> command(List<String> options, Class<?> main, List<String> arguments) {
        Path java = launcher();
        if (java == null) { return null; }
        var command = new ArrayList<>(List.of(java.toString()));
        command.addAll(options);
        command.addAll(List.of("-Dlog4j2.configurationFile=none", "-Dlog4j2.level=OFF", "-Dlog4j2.statusLoggerLevel=OFF"));
        String libraries = System.getProperty("org.lwjgl.librarypath");
        if (libraries != null) { command.add("-Dorg.lwjgl.librarypath=" + libraries); }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
        command.addAll(arguments);
        return command;
    }

    /** Starts {@code command} on the game's graphics card, its error output discarded. */
    static Process start(List<String> command) throws IOException {
        var builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        // Optimus reads the card from the process's environment, which Java's copy doesn't hold (GpuGraphicsCard.apply).
        String card = GpuGraphicsCard.override();
        if (card != null) { builder.environment().put("SHIM_MCCOMPAT", card); }
        return builder.start();
    }

    /** The game's Java, windowless on Windows; null when there's none to start. */
    private static Path launcher() {
        String home = System.getProperty("java.home");
        if (home == null || System.getProperty("java.class.path", "").isEmpty()) { return null; }
        Path bin = Path.of(home, "bin");
        Path windowless = bin.resolve("javaw.exe"), plain = bin.resolve(Platform.get() == Platform.WINDOWS ? "java.exe" : "java");
        return Files.isExecutable(windowless) ? windowless : Files.isExecutable(plain) ? plain : null;
    }

    static void write(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String read(DataInputStream input) throws IOException {
        byte[] bytes = new byte[input.readInt()];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The helper process. Arguments: the cache's directory and the context version. Input: the parent's driver string,
     * then jobs, as {@link #run} writes them. Output: {@code READY} or {@code MISMATCH}, then an answer to each job.
     */
    public static void main(String[] args) throws IOException {
        // Anything on standard output but an answer is ignored by the parent, and nothing here is read as one.
        PrintStream answers = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var input = new DataInputStream(System.in);
        lowerPriority();
        String expected = read(input);
        if (!GLFW.glfwInit()) { answers.println(MISMATCH); Runtime.getRuntime().halt(1); }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, Integer.parseInt(args[1]));
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, Integer.parseInt(args[2]));
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(1, 1, "MegaMek shader link helper", 0, 0);
        if (window == 0) { answers.println(MISMATCH); Runtime.getRuntime().halt(1); }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        GpuProgramCache cache = GpuProgramCache.open(Path.of(args[0]), false);
        if (cache == null || !cache.driver().equals(expected)) { answers.println(MISMATCH); Runtime.getRuntime().halt(1); }
        answers.println(READY);
        try {
            while (true) {
                String key = read(input), name = read(input);
                String[] stages = new String[input.readInt()];
                for (int i = 0; i < stages.length; i++) { stages[i] = read(input); }
                try {
                    int bytes = link(cache, key, stages);
                    answers.println(bytes > 0 ? DONE + key + " " + bytes : UNSAVED + key);
                } catch (IllegalStateException failed) {
                    answers.println(FAILED + key + " " + Base64.getEncoder().encodeToString((name + " " + failed.getMessage()).getBytes(StandardCharsets.UTF_8)));
                }
            }
        } catch (EOFException gameLeft) {
            // Halting skips a driver's thread teardown, which has hung at exit (GpuGlsl.SharedCompiler).
            Runtime.getRuntime().halt(0);
        }
    }

    /** The bytes its binary takes in the cache; 0 when it isn't there. */
    private static int link(GpuProgramCache cache, String key, String[] sources) {
        int program = GL20C.glCreateProgram();
        int[] shaders = new int[sources.length];
        try {
            for (int i = 0; i < sources.length; i++) {
                shaders[i] = GL20C.glCreateShader(STAGES[i]);
                GL20C.glShaderSource(shaders[i], sources[i]);
                GL20C.glCompileShader(shaders[i]);
                GL20C.glAttachShader(program, shaders[i]);
            }
            for (int i = 0; i < sources.length; i++) {
                if (GL20C.glGetShaderi(shaders[i], GL20C.GL_COMPILE_STATUS) == 0) {
                    throw new IllegalStateException(STAGE_NAMES[i] + ": " + GL20C.glGetShaderInfoLog(shaders[i]));
                }
            }
            GpuProgramCache.retrievable(program);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("link: " + GL20C.glGetProgramInfoLog(program));
            }
            return cache.store(program, key);
        } finally {
            for (int shader : shaders) { if (shader != 0) { GL20C.glDeleteShader(shader); } }
            GL20C.glDeleteProgram(program);
            GL11.glFlush();
        }
    }

    /**
     * Below normal priority on Windows, so that a frame goes first whenever both want a core. Not Windows' background
     * mode, which sets the class back to normal while starving the process's disk reads and memory: the shader
     * warm-up's driver threads then spun on 13 cores for minutes.
     */
    static void lowerPriority() {
        if (Platform.get() != Platform.WINDOWS) { return; }
        long function = WinBase.GetProcAddress(WinBase.GetModuleHandle("kernel32.dll"), "SetPriorityClass");
        // The current process's pseudo handle, and BELOW_NORMAL_PRIORITY_CLASS.
        if (function != 0) { JNI.callPPI(-1L, 0x4000L, function); }
    }
}
