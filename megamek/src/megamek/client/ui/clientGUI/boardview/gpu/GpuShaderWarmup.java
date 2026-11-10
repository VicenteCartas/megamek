/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.logging.MMLogger;
import org.lwjgl.system.Platform;

/**
 * Links a board's costliest programs, its terrain's, before any board opens. While the window shows a menu (the start
 * screen, the lobby), a process of its own ({@link #main}) prepares boards' terrain as a board's loading stage does and
 * leaves the binaries in the program cache, where a game's loading stage, the lobby's map preview and the editor take
 * them in a few milliseconds each. First combat's effects, which every game links (GpuBattleView#prepareEffects), in
 * a second or so; then the boards likely next ({@link #prepare}): the last one shown, the lobby's, a
 * scenario's. Then one sample board per theme, which holds every component, preset and look of the editor's blueprint,
 * for every lighting configuration a board can have ({@link Lighting}). All with the saved Display settings. Nothing
 * links in the game's process, so no frame waits for it.
 * <p>
 * The process stops while a board shows, which wants the cores, and goes on when a menu shows again. A file beside the
 * cache lists the finished steps for one fingerprint of what the programs are made of: driver, shading language, Display
 * settings, the board's system properties, shader files, code and blueprint. With every step done for the same
 * fingerprint nothing starts; a new fingerprint starts over.
 * <p>
 * Each board's family programs (GpuFamilyPrograms), the blends' above all, are prepared with its generic ones, all of
 * them: a game builds only a few each load, or links them during play. Not prepared: programs a board finds only by
 * drawing (units, shadows, the atmosphere's), which link in its loading stage as before. Without program binaries
 * (macOS may offer none) nothing is prepared.
 */
final class GpuShaderWarmup {
    private static final MMLogger LOGGER = MMLogger.create(GpuShaderWarmup.class);
    /** {@code -Dmegamek.gpu.shaderWarmup=false} prepares nothing ahead of a board; the GPU tests turn it off. */
    static final String PROPERTY = "megamek.gpu.shaderWarmup";
    /**
     * {@code -Dmegamek.gpu.shaderWarmupSteps=game/grass,editor/snow} prepares only these sample boards, {@code none} none,
     * for tests and timing; boards asked for ({@link #prepare}) are always prepared.
     */
    static final String STEPS_PROPERTY = "megamek.gpu.shaderWarmupSteps";
    /** Part of the fingerprint: raise it when the steps or what they prepare change, so a finished warm-up runs again. */
    private static final int PLAN = 2;
    private static final String READY = "READY", MISMATCH = "MISMATCH", STEP = "STEP ", DONE = "DONE", FAILED = "FAILED ";
    /** Process ends before its last step that this run accepts; then it prepares nothing more. */
    private static final int ATTEMPTS = 3;
    /** The view the Display settings size the shadow scene for; nothing draws. */
    private static final int WIDTH = 1920, HEIGHT = 1080;
    /** Boards asked for that are kept ({@link #prepare}), and the largest prepared: a larger one has its loading stage. */
    private static final int BOARDS = 4, BOARD_HEXES = 64 * 64;
    /**
     * The share of the program cache the sample boards fill at most, most useful first: the boards played keep the rest
     * (GpuProgramCache prunes the least recently used beyond its capacity). Boards asked for are always prepared.
     */
    private static final long SAMPLE_BYTES = GpuProgramCache.CAPACITY / 2;
    /** How long after a board last drew inside a menu (the lobby's map preview) the warm-up waits to go on. */
    private static final long HOLD_NANOS = 2_000_000_000L;
    /**
     * The process's threads for chunk builds, and the driver's for compiles (GpuGlsl.Preparation): the game keeps the
     * other cores, and every compile the driver runs at once holds its own memory. The process sees twice as many
     * processors, so that GpuTerrain's pool, which takes half of them, keeps to these.
     */
    static final int THREADS = 2;
    /**
     * {@code -Dmegamek.gpu.shaderWarmupFreeMiB}: the memory the warm-up leaves the system, 1024 MiB by default; a negative
     * value turns the check off (the tests). Its process starts only while that much is available beyond its own size
     * ({@link #PROCESS_BYTES}), and ends within a second once less is, its memory back at once, so that the game and
     * other programs don't page for it; it tries again {@link #SHORTAGE_NANOS} later.
     */
    static final String FREE_PROPERTY = "megamek.gpu.shaderWarmupFreeMiB";
    /**
     * About the most its process held: 4.2 GB on Iris Xe, of which up to 1.3 GB was the system memory its textures took
     * (2.9 GB at the median), and 2.9 GB on an RTX 4070 (1.8 GB). Mostly the driver's: the Java heap took 0.6 GB.
     */
    private static final long PROCESS_BYTES = 4_400L << 20;
    /** How often the window checks the system's memory while the process runs, and how long a shortage holds it. */
    private static final long MEMORY_CHECK_NANOS = 1_000_000_000L, SHORTAGE_NANOS = 10_000_000_000L;
    private static final Path MEMINFO = Path.of("/proc/meminfo"), NICE = Path.of("/usr/bin/nice");

    /**
     * A lighting configuration's programs: a game's board has neither the biome stage nor crystal transmission, or one
     * of them by its surfaces; the editor keeps both and prepares every part of a board (GpuTerrain#setEditable). Most
     * common first; a board asked for by name has the one its own surfaces choose.
     */
    private enum Lighting {
        GAME(false, false, false), EDITOR(true, null, null), BIOMES(false, true, false), CRYSTALS(false, false, true),
        BOARD(false, null, null);

        /** The sample boards' configurations. */
        static final List<Lighting> SAMPLES = List.of(GAME, EDITOR, BIOMES, CRYSTALS);
        final boolean editable;
        final Boolean biomes, crystals;

        Lighting(boolean editable, Boolean biomes, Boolean crystals) {
            this.editable = editable;
            this.biomes = biomes;
            this.crystals = crystals;
        }
    }

    /** A theme's sample board, or a board asked for by name with the air it has; or combat's effects. */
    private record Step(Lighting lighting, String theme, Path board, boolean vacuum, boolean weightless) {
        /** Combat's effects, of no board or lighting: {@link Effects}. */
        static final Step EFFECTS = new Step(null, "effects");

        Step(Lighting lighting, String theme) { this(lighting, theme, null, false, false); }

        /** A board asked for, its kind and air in its name ({@link #prepare}); null for another file. */
        static Step of(Path board) {
            String[] parts = name(board).split("-");
            if (parts.length != 4) { return null; }
            return new Step(parts[0].equals("editor") ? Lighting.EDITOR : Lighting.BOARD, null, board,
                  parts[1].equals("vacuum"), parts[2].equals("zero"));
        }

        String id() {
            return board != null ? "board/" + name(board)
                  : lighting == null ? theme : lighting.name().toLowerCase(Locale.ROOT) + "/" + theme;
        }

        private static String name(Path board) {
            String file = board.getFileName().toString();
            return file.substring(0, file.length() - ".board2".length());
        }
    }

    /** A game's combat effects, made for their programs alone, and the preparation that links those off the frame. */
    private record Effects(GpuAttackEffects attack, GpuDesignators designators, GpuTerrainEffects fire,
          GpuWaterImpacts splashes, GpuJumpJets jets, GpuGlsl.Preparation links) implements Disposable {
        Effects() {
            this(new GpuAttackEffects(), new GpuDesignators(), new GpuTerrainEffects(), new GpuWaterImpacts(),
                  new GpuJumpJets(), new GpuGlsl.Preparation());
        }

        /** Whether every program is linked; false while a link runs. */
        boolean linked() {
            return links.run(() -> GpuBattleView.prepareEffects(attack, designators, fire, splashes, jets));
        }

        @Override
        public void dispose() {
            attack.dispose();
            designators.dispose();
            fire.dispose();
            splashes.dispose();
            jets.dispose();
            links.dispose();
        }
    }

    private static final Object LOCK = new Object();
    // Guarded by LOCK: the game's side.
    private static GpuProgramCache cache;
    private static String fingerprint, settings;
    private static int glsl, major, minor, failures;
    /** Whether a board shows, or no scene yet: GpuHost tells it as each scene starts. */
    private static boolean boardShowing = true;
    private static boolean finished, starting;
    /** Until when a board drawn inside a menu, or a shortage of memory, holds the warm-up ({@link #frame}); 0 for none. */
    private static long heldUntil;
    /** When the window last checked the system's memory for the running process; whether the log says it waits. */
    private static long memoryChecked;
    private static boolean toldShort;
    /** Boards asked for so far ({@link #prepare}): what a check of the files began before can't have seen. */
    private static int asked;
    private static Process process;

    private GpuShaderWarmup() { }

    /** On the window's thread once its context exists (GpuHost): prepares what this fingerprint hasn't finished. */
    static void open() {
        GpuProgramCache programs = GpuGlsl.programCache;
        if (programs == null || !Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) { return; }
        int[] context = GpuGlsl.contextVersion();
        synchronized (LOCK) {
            // Another window of this run, on the same card: the warm-up it has goes on.
            if (cache != null && cache.directory().equals(programs.directory()) && cache.driver().equals(programs.driver())) {
                return;
            }
            stop();
            cache = programs;
            glsl = GpuGlsl.glsl();
            major = context[0];
            minor = context[1];
            fingerprint = null;
            finished = false;
            failures = 0;
        }
        refresh(programs);
    }

    /**
     * Each frame a board draws inside a menu (the lobby's map preview): that board's programs come first, so the
     * warm-up's process ends at once, and nothing starts it again until no board has drawn for a moment ({@link #frame}).
     * Any thread.
     */
    static void boardDrawn() {
        synchronized (LOCK) {
            heldUntil = System.nanoTime() + HOLD_NANOS;
            stop();
        }
    }

    /**
     * The window's thread, each frame (GpuHost), minimized too: the process ends when the system runs short of memory,
     * and a hold that has ended lets the warm-up go on, once a menu shows.
     */
    static void frame() {
        GpuProgramCache programs = null;
        boolean stopped = false;
        synchronized (LOCK) {
            long now = System.nanoTime();
            if (process != null && now - memoryChecked >= MEMORY_CHECK_NANOS) {
                memoryChecked = now;
                if (shortOfMemory(availableMemory(), 0)) {
                    heldUntil = now + SHORTAGE_NANOS;
                    stop();
                    stopped = true;
                }
            }
            if (heldUntil != 0 && now >= heldUntil) {
                if (boardShowing) {
                    // The next menu goes on (showing): nothing is read while a board loads or plays.
                    heldUntil = 0;
                } else if (shortOfMemory(availableMemory(), PROCESS_BYTES)) {
                    // Still short: nothing is read for a process that couldn't start.
                    heldUntil = now + SHORTAGE_NANOS;
                } else {
                    heldUntil = 0;
                    programs = cache;
                }
            }
        }
        if (stopped) { LOGGER.info("3D board: the shader warm-up waits for free memory"); }
        if (programs != null) { refresh(programs); }
    }

    /**
     * Whether {@code available} memory (-1 when unknown) is less than {@code bytes} beyond the memory the warm-up leaves
     * the system ({@link #FREE_PROPERTY}); never with the check off.
     */
    private static boolean shortOfMemory(long available, long bytes) {
        long reserve = Long.getLong(FREE_PROPERTY, 1024) << 20;
        return reserve >= 0 && available < reserve + bytes;
    }

    /**
     * The physical memory the system can hand out without paging: Windows' available memory (Java's figure), Linux's
     * MemAvailable (Java counts only the free pages there, not the file cache the system can drop). -1 elsewhere, and
     * where it can't be read, so the warm-up waits: on macOS too Java counts only the free pages.
     */
    private static long availableMemory() {
        if (Platform.get() == Platform.LINUX) {
            try (var lines = Files.lines(MEMINFO)) {
                return lines.filter(line -> line.startsWith("MemAvailable:")).findFirst()
                      .map(line -> Long.parseLong(line.replaceAll("\\D", "")) << 10).orElse(-1L);
            } catch (IOException | RuntimeException unreadable) {
                return -1;
            }
        }
        if (Platform.get() == Platform.WINDOWS) {
            try {
                if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean system) {
                    return system.getFreeMemorySize();
                }
            } catch (LinkageError absent) {
                // A Java runtime without the jdk.management module.
            }
        }
        return -1;
    }

    /**
     * As a board view is asked for (GpuHost.show, any thread) or a scene starts (the window's thread): a board view
     * stops the warm-up at once, since that board's links come first and its frames want the cores; any other scene lets
     * it go on, for what the programs are made of now: a board's Display settings, or an edited shader file, may have
     * changed them.
     */
    static void showing(boolean boardScene) {
        GpuProgramCache programs;
        synchronized (LOCK) {
            boardShowing = boardScene;
            if (boardScene) {
                stop();
                return;
            }
            programs = cache;
        }
        if (programs != null) { refresh(programs); }
    }

    /**
     * Reads the fingerprint on a thread of its own, the settings on the Swing thread, then starts the process when this
     * fingerprint has steps left; a process still preparing another fingerprint stops.
     */
    private static void refresh(GpuProgramCache programs) {
        Thread thread = new Thread(() -> {
            String chosen = null, print = null;
            try {
                String[] captured = new String[1];
                SwingUtilities.invokeAndWait(() -> {
                    GUIPreferences preferences = GUIPreferences.getInstance();
                    captured[0] = settings(BoardDisplaySettings.capture(preferences), preferences.getBoardGraphics());
                });
                chosen = captured[0];
                print = fingerprint(programs, GpuGlsl.glsl(), chosen);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException | IOException | URISyntaxException | RuntimeException failure) {
                LOGGER.info("3D board: no shader warm-up: {}", failure.toString());
            }
            int seen;
            synchronized (LOCK) { seen = asked; }
            boolean done = print == null || done(programs, print);
            synchronized (LOCK) {
                if (cache != programs) { return; }
                if (!Objects.equals(print, fingerprint)) {
                    stop();
                    failures = 0;
                }
                settings = chosen;
                fingerprint = print;
                // A board asked for meanwhile isn't in what was read.
                finished = done && asked == seen;
            }
            resume();
        }, "shader-warmup-fingerprint");
        // At the game's priority: below it, a holder of the lock the window's thread takes every frame could wait out a
        // busy machine. The work below normal is the process's.
        thread.setDaemon(true);
        thread.start();
    }

    /** The window has closed (GpuHost): the process ends, and nothing starts until a window opens the warm-up again. */
    static void close() {
        synchronized (LOCK) {
            stop();
            cache = null;
            fingerprint = null;
            finished = false;
            boardShowing = true;
            heldUntil = 0;
        }
    }

    /**
     * A board the next game or the editor will likely show: the last one shown, the lobby's or a scenario's. While a menu
     * shows, its terrain is prepared before the sample boards, with the lighting its own surfaces choose; the newest
     * {@link #BOARDS} are kept beside the cache for later launches. Any thread; a space map or a board of more than
     * {@link #BOARD_HEXES} hexes is left to its loading stage.
     */
    static void prepare(Board board, boolean editor, PlanetaryConditions conditions) {
        GpuProgramCache programs;
        synchronized (LOCK) { programs = cache; }
        if (programs == null || board == null || board.isSpace() || board.getWidth() < 1 || board.getHeight() < 1
              || (long) board.getWidth() * board.getHeight() > BOARD_HEXES) {
            return;
        }
        try {
            var text = new ByteArrayOutputStream();
            BoardFile.write(board, text);
            boolean vacuum = conditions != null && conditions.getAtmosphere().isVacuum();
            boolean weightless = conditions != null && conditions.getGravity() == 0;
            String name = (editor ? "editor" : "game") + (vacuum ? "-vacuum" : "-air") + (weightless ? "-zero" : "-g")
                  + "-" + GpuProgramCache.key(text.toString(StandardCharsets.UTF_8), "", "").substring(0, 16);
            Path folder = boards(programs);
            Files.createDirectories(folder);
            Path file = folder.resolve(name + ".board2");
            if (Files.exists(file)) {
                Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
            } else {
                Path partial = Files.createTempFile(folder, name, ".tmp");
                Files.write(partial, text.toByteArray());
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            List<Path> kept = requests(programs);
            for (int i = BOARDS; i < kept.size(); i++) { Files.deleteIfExists(kept.get(i)); }
            String print;
            synchronized (LOCK) { print = fingerprint; }
            // Already prepared for what the programs are made of now: nothing to start.
            List<String> lines = print == null ? List.of() : progressLines(progress(programs));
            if (!lines.isEmpty() && lines.getFirst().equals(print) && ids(lines).contains(Step.of(file).id())) { return; }
        } catch (IOException | RuntimeException failure) {
            LOGGER.debug(failure, "3D board: the shader warm-up keeps no copy of this board");
            return;
        }
        synchronized (LOCK) {
            if (cache != programs) { return; }
            asked++;
            finished = false;
        }
        // A process running takes it up before its next step.
        resume();
    }

    /** The folder of boards asked for, beside the cache. */
    private static Path boards(GpuProgramCache programs) {
        return programs.directory().resolve("warmup-boards");
    }

    /** The boards asked for, newest first. */
    private static List<Path> requests(GpuProgramCache programs) throws IOException {
        Path folder = boards(programs);
        if (!Files.isDirectory(folder)) { return List.of(); }
        try (var files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".board2"))
                  .sorted(Comparator.comparing((Path file) -> file.toFile().lastModified()).reversed()).toList();
        }
    }

    /** Whether the warm-up's process runs: what it links lands in the cache. */
    static boolean busy() {
        synchronized (LOCK) { return process != null; }
    }

    /** Whether every step is done for this run's fingerprint, or there is nothing to prepare. */
    static boolean finished() {
        synchronized (LOCK) { return finished; }
    }

    /**
     * Starts the process when a menu shows, the fingerprint is known and steps are left; off the lock, which the window's
     * thread asks every frame ({@link #busy}).
     */
    private static void resume() {
        // Read before the lock, which the window's thread takes every frame.
        long available = availableMemory();
        GpuProgramCache programs = null;
        List<String> arguments = null;
        String driver = null, chosen = null, print = null;
        boolean waiting = false, tell = false;
        synchronized (LOCK) {
            if (boardShowing || heldUntil != 0 || finished || starting || process != null || fingerprint == null
                  || failures >= ATTEMPTS) {
                return;
            }
            if (shortOfMemory(available, PROCESS_BYTES)) {
                // Asked again after the hold (frame); said once a run.
                heldUntil = System.nanoTime() + SHORTAGE_NANOS;
                waiting = true;
                tell = !toldShort;
                toldShort = true;
            } else {
                starting = true;
                programs = cache;
                arguments = List.of(cache.directory().toString(), Integer.toString(major), Integer.toString(minor),
                      Integer.toString(glsl), fingerprint);
                driver = cache.driver();
                chosen = settings;
                print = fingerprint;
            }
        }
        if (waiting) {
            if (tell && available < 0) {
                LOGGER.info("3D board: no shader warm-up: this system doesn't report its available memory");
            } else if (tell) {
                LOGGER.info("3D board: the shader warm-up waits until the system has {} MB of memory available",
                      (Long.getLong(FREE_PROPERTY, 1024) << 20) + PROCESS_BYTES >> 20);
            }
            return;
        }
        Process started = null;
        try {
            started = launch(arguments, driver, chosen);
        } catch (IOException | RuntimeException failure) {
            LOGGER.info("3D board: the shader warm-up did not start: {}", failure.toString());
        }
        boolean unwanted;
        synchronized (LOCK) {
            starting = false;
            if (started == null) {
                failures = ATTEMPTS;
                return;
            }
            // A board opened, or what the programs are made of changed, while it started.
            unwanted = boardShowing || heldUntil != 0 || cache != programs || !Objects.equals(print, fingerprint);
            if (!unwanted) { process = started; }
        }
        if (unwanted) {
            started.destroyForcibly();
            // A change's own refresh found this one starting and left it: what the programs are made of now goes on.
            resume();
            return;
        }
        Process running = started;
        GpuProgramCache reading = programs;
        Thread reader = new Thread(() -> answers(running, reading), "shader-warmup");
        reader.setDaemon(true);
        reader.start();
    }

    /** Ends the process; a link it ran is lost, its finished steps are kept. Under LOCK. */
    private static void stop() {
        Process running = process;
        process = null;
        if (running != null) { running.destroyForcibly(); }
    }

    /** The process for {@code arguments}, told the game's driver string and settings. */
    private static Process launch(List<String> arguments, String driver, String chosen) throws IOException {
        List<String> options = new ArrayList<>(List.of("-Xmx1536m", "-XX:+UseSerialGC",
              // Its chunk builds leave the game most of the cores (THREADS).
              "-XX:ActiveProcessorCount=" + 2 * THREADS,
              "-Djava.util.concurrent.ForkJoinPool.common.parallelism=" + THREADS));
        // The game's module openings, which libGDX and the board's code need here as much.
        List<String> game = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int i = 0; i < game.size(); i++) {
            String option = game.get(i);
            if (option.startsWith("--add-opens") || option.startsWith("--add-exports")
                  || option.startsWith("--enable-native-access")) {
                options.add(option);
                if (!option.contains("=") && i + 1 < game.size()) { options.add(game.get(++i)); }
            }
        }
        // The game's switches, and no link helper: this process is one.
        new TreeMap<>(System.getProperties()).forEach((key, value) -> {
            if (key.toString().startsWith("megamek.")) { options.add("-D" + key + "=" + value); }
        });
        options.add("-D" + GpuLinkHelper.PROPERTY + "=false");
        List<String> command = GpuLinkHelper.command(options, GpuShaderWarmup.class, arguments);
        if (command == null) { throw new IOException("no Java to start"); }
        // Below the game's priority on Linux and macOS too; Windows lowers it in the process (main).
        if (Platform.get() != Platform.WINDOWS && Files.isExecutable(NICE)) {
            command.addAll(0, List.of(NICE.toString(), "-n", "10"));
        }
        Process started = GpuLinkHelper.start(command);
        // Its input stays open: its end tells the process the game left.
        var output = new DataOutputStream(started.getOutputStream());
        try {
            GpuLinkHelper.write(output, driver);
            GpuLinkHelper.write(output, chosen);
            output.flush();
        } catch (IOException failure) {
            started.destroyForcibly();
            throw failure;
        }
        return started;
    }

    /**
     * Follows the process's answers until it ends; one that ended by itself before its last step counts as failed. Then
     * the files say what is left: a board asked for while it finished, say.
     */
    private static void answers(Process started, GpuProgramCache programs) {
        boolean completed = false;
        try (BufferedReader input = started.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.startsWith(STEP)) {
                    // Step, programs stored, bytes, seconds. The bytes count towards the game's pruning, as the link
                    // helper's do.
                    String[] parts = line.split(" ");
                    long bytes = Long.parseLong(parts[3]);
                    programs.added(bytes);
                    LOGGER.info("3D board: shader warm-up prepared {}: {} programs, {} kB in {} s", parts[1], parts[2],
                          bytes >> 10, parts[4]);
                } else if (line.equals(DONE)) {
                    completed = true;
                } else if (line.equals(MISMATCH)) {
                    LOGGER.info("3D board: no shader warm-up, its process draws on another driver");
                    synchronized (LOCK) { failures = ATTEMPTS; }
                } else if (line.startsWith(FAILED)) {
                    LOGGER.info("3D board: the shader warm-up failed: {}", new String(Base64.getDecoder()
                          .decode(line.substring(FAILED.length())), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException ended) {
            // As below.
        }
        String print;
        int seen;
        synchronized (LOCK) {
            if (process != started) { return; }
            process = null;
            if (!completed) { failures++; }
            print = fingerprint;
            seen = asked;
        }
        boolean done = print == null || done(programs, print);
        synchronized (LOCK) {
            if (cache == programs && Objects.equals(print, fingerprint)) { finished = done && asked == seen; }
        }
        resume();
    }

    /** The file of finished steps beside the cache, one per driver: a computer with two cards keeps both. */
    private static Path progress(GpuProgramCache programs) {
        return programs.directory().resolve("warmup-" + GpuProgramCache.key(programs.driver(), "", "").substring(0, 16)
              + ".txt");
    }

    /**
     * The progress file's lines; none once it has gone unwritten as long as the cache keeps an unused entry
     * (GpuProgramCache.UNUSED), since pruning may have deleted what it lists: all is prepared again, and what the cache
     * still holds loads at once.
     */
    private static List<String> progressLines(Path progress) throws IOException {
        if (!Files.isRegularFile(progress)
              || Files.getLastModifiedTime(progress).toInstant().isBefore(Instant.now().minus(GpuProgramCache.UNUSED))) {
            return List.of();
        }
        return Files.readAllLines(progress);
    }

    /** Whether the file lists the sample boards and every board asked for as done for {@code print}. */
    private static boolean done(GpuProgramCache programs, String print) {
        try {
            List<String> lines = progressLines(progress(programs));
            Set<String> ids = ids(lines);
            if (lines.isEmpty() || !lines.getFirst().equals(print) || !ids.contains(DONE)) { return false; }
            for (Path board : requests(programs)) {
                Step step = Step.of(board);
                if (step != null && !ids.contains(step.id())) { return false; }
            }
            return true;
        } catch (IOException | RuntimeException absent) {
            return false;
        }
    }

    /** The steps a progress file lists (each "id bytes"), and DONE once the sample boards are. */
    private static Set<String> ids(List<String> lines) {
        Set<String> ids = new HashSet<>();
        for (String line : lines) { ids.add(line.split(" ", 2)[0]); }
        return ids;
    }

    /** The Display settings and the graphics as one line, which the process reads back ({@link #display}). */
    private static String settings(BoardDisplaySettings display, BoardGraphics graphics) {
        return String.join(",", graphics.name(), display.quality().name(), Float.toString(display.resolution()),
              Boolean.toString(display.antiAliasing()), Boolean.toString(display.highShadows()),
              Boolean.toString(display.specularAntiAliasing()), Boolean.toString(display.normalMaps()),
              Boolean.toString(display.terrainParallax()), Boolean.toString(display.tessellation()),
              Boolean.toString(display.ambientOcclusion()), Boolean.toString(display.depthCues()),
              Boolean.toString(display.vignette()));
    }

    private static BoardDisplaySettings display(String[] fields) {
        return new BoardDisplaySettings(BoardQuality.valueOf(fields[1]), Float.parseFloat(fields[2]),
              Boolean.parseBoolean(fields[3]), Boolean.parseBoolean(fields[4]), Boolean.parseBoolean(fields[5]),
              Boolean.parseBoolean(fields[6]), Boolean.parseBoolean(fields[7]), Boolean.parseBoolean(fields[8]),
              Boolean.parseBoolean(fields[9]), Boolean.parseBoolean(fields[10]), Boolean.parseBoolean(fields[11]));
    }

    /** What the prepared programs are made of: anything that changes one of them changes this. */
    private static String fingerprint(GpuProgramCache programs, int glsl, String settings)
          throws IOException, URISyntaxException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("Every Java platform provides SHA-256", missing);
        }
        List<String> parts = new ArrayList<>(List.of("plan " + PLAN, programs.driver(), "glsl " + glsl, settings));
        // Not the memory the warm-up leaves free, which only changes when it runs.
        new TreeMap<>(System.getProperties()).forEach((key, value) -> {
            if (key.toString().startsWith("megamek.gpu.") && !key.equals(FREE_PROPERTY)) { parts.add(key + "=" + value); }
        });
        // Read here, not through the files the board keeps (GpuShaderSource.readDisk): an edit since shows.
        for (String name : GpuShaderSource.files()) {
            parts.add(name);
            parts.add(GpuShaderSource.file(name).text());
        }
        parts.add(codeStamp());
        parts.add(Files.readString(Configuration.dataDir().toPath().resolve("board-editor/blueprint.json")));
        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The code's identity: its jar's size and time, or in a checkout the number and newest time of the board view's
     * compiled classes, whose Java assembles the programs' sources.
     */
    private static String codeStamp() throws IOException, URISyntaxException {
        Path code = Path.of(GpuShaderWarmup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(code)) {
            return code + " " + Files.size(code) + " " + Files.getLastModifiedTime(code).toMillis();
        }
        String gpu = GpuShaderWarmup.class.getPackageName();
        Path classes = code.resolve(gpu.substring(0, gpu.lastIndexOf('.')).replace('.', '/'));
        long[] stamp = new long[2];
        try (var files = Files.walk(classes)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                stamp[0]++;
                stamp[1] = Math.max(stamp[1], file.toFile().lastModified());
            });
        }
        return code + " " + stamp[0] + " " + stamp[1];
    }

    /** Every theme the editor offers, grass first: the tilesets' and the blueprint's special ones. */
    private static List<String> themes() {
        var themes = new LinkedHashSet<String>();
        themes.add("grass");
        themes.addAll(BoardArtwork.themes());
        themes.addAll(BoardEditorBlueprint.get().specialThemes());
        // No theme is grass.
        themes.removeIf(String::isBlank);
        return List.copyOf(themes);
    }

    /**
     * {@code theme}'s sample board: each component's sample, preset and look side by side, as the editor's cards show
     * them one at a time (GpuEditorTerrainPreviews). A design with a model of its own is no terrain and stays out.
     */
    static BoardScene board(String theme) {
        BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
        List<Hex> hexes = new ArrayList<>();
        for (var component : blueprint.components()) {
            hexes.add(GpuEditorTerrainPreviews.hex(blueprint, component.id(), "", theme));
            for (var preset : component.presets()) {
                hexes.add(GpuEditorTerrainPreviews.hex(blueprint, component.id(), "preset:" + preset.id(), theme));
            }
            for (var variant : blueprint.variants(component.id())) {
                if (variant.asset().isEmpty()) {
                    hexes.add(GpuEditorTerrainPreviews.hex(blueprint, component.id(), variant.id(), theme));
                }
            }
        }
        int width = (int) Math.ceil(Math.sqrt(hexes.size()));
        Board sample = Board.createEmptyBoard(width, (hexes.size() + width - 1) / width);
        // As the editor's samples: road level 2 is the native Alley finish.
        sample.setNativeFormat(true);
        for (int i = 0; i < hexes.size(); i++) { sample.setHex(new Coords(i % width, i / width), hexes.get(i)); }
        return GpuEditorTerrainPreviews.scene(sample);
    }

    /**
     * The warm-up's process. Arguments: the program cache's directory, the context version, the shading language and the
     * fingerprint. Input: the game's driver string and settings ({@link #settings}), then nothing: its end means the game
     * left. Output: {@code READY}, a {@code STEP} line with the bytes stored for each finished step, then {@code DONE};
     * or {@code FAILED} and a reason.
     */
    public static void main(String[] args) throws IOException {
        // Anything else on standard output is ignored by the game, which reads only these lines.
        PrintStream answers = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        GpuLinkHelper.lowerPriority();
        var input = new DataInputStream(System.in);
        String driver = GpuLinkHelper.read(input), chosen = GpuLinkHelper.read(input);
        Thread parent = new Thread(() -> {
            try {
                while (input.read() >= 0) {
                    // Nothing else comes.
                }
            } catch (IOException gone) {
                // As at its end.
            }
            // Halting skips a driver's thread teardown, which has hung at exit (GpuGlsl's shared compiler).
            Runtime.getRuntime().halt(0);
        }, "shader-warmup-game");
        parent.setDaemon(true);
        parent.start();
        String[] fields = chosen.split(",");
        var worker = new Worker(Path.of(args[0]), Integer.parseInt(args[3]), args[4], driver, display(fields),
              BoardGraphics.valueOf(fields[0]), answers);
        // Every family program, blends first among the most drawn: a game builds a few a load, here no frame waits.
        GpuFamilyPrograms.fresh = Integer.MAX_VALUE;
        GpuFamilyPrograms.capacity = 1 << 20;
        Lwjgl3ApplicationConfiguration.useGlfwAsync();
        var configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("MegaMek shader warm-up");
        configuration.setInitialVisible(false);
        configuration.setWindowedMode(64, 64);
        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, Integer.parseInt(args[1]),
              Integer.parseInt(args[2]));
        configuration.disableAudio(true);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.setIdleFPS(60);
        new Lwjgl3Application(worker, configuration);
    }

    /** The process's steps, one a frame until its terrain is ready. Render thread only. */
    private static final class Worker extends ApplicationAdapter {
        private final Path directory;
        private final int glsl;
        private final String fingerprint, driver;
        private final BoardDisplaySettings display;
        private final BoardGraphics graphics;
        private final PrintStream answers;
        private final BoardCamera camera = new BoardCamera();
        /** The steps this fingerprint finished, as its file lists them; the sample boards; the steps a test chose. */
        private final Set<String> done = new HashSet<>();
        private final List<Step> samples = new ArrayList<>();
        private Set<String> chosen;
        /** The bytes the sample boards stored for this fingerprint. */
        private long sampled;
        private Path progress;
        private Step current;
        /** Bytes and programs stored when the current step began, and when it began. */
        private long stored, started;
        private int programs;
        private Lighting lighting;
        private GpuTerrain terrain;
        private GpuAtmosphere atmosphere;
        private GpuAmbientOcclusion occlusion;
        /** While {@link Step#EFFECTS} links. */
        private Effects effects;

        Worker(Path directory, int glsl, String fingerprint, String driver, BoardDisplaySettings display,
              BoardGraphics graphics, PrintStream answers) {
            this.directory = directory;
            this.glsl = glsl;
            this.fingerprint = fingerprint;
            this.driver = driver;
            this.display = display;
            this.graphics = graphics;
            this.answers = answers;
        }

        @Override
        public void create() {
            try {
                GpuGlsl.detectWarmup(directory, glsl);
                GpuProgramCache programs = GpuGlsl.programCache;
                if (programs == null || !programs.driver().equals(driver)) {
                    // Its binaries wouldn't serve the game: no other try this run (GpuLinkHelper's MISMATCH).
                    answers.println(MISMATCH);
                    Runtime.getRuntime().halt(1);
                }
                progress = progress(programs);
                // A new fingerprint, or an expired file, starts the file over.
                List<String> lines = progressLines(progress);
                if (!lines.isEmpty() && lines.getFirst().equals(fingerprint)) {
                    done.addAll(ids(lines));
                    for (String line : lines.subList(1, lines.size())) {
                        String[] parts = line.split(" ");
                        if (parts.length == 2 && !parts[0].startsWith("board/")) { sampled += Long.parseLong(parts[1]); }
                    }
                }
                if (done.isEmpty()) { Files.writeString(progress, fingerprint + "\n"); }
                String only = System.getProperty(STEPS_PROPERTY, "");
                chosen = only.isBlank() ? null : Set.of(only.split(","));
                for (Lighting each : Lighting.SAMPLES) {
                    for (String theme : themes()) { samples.add(new Step(each, theme)); }
                }
                answers.println(READY);
            } catch (IOException | RuntimeException | LinkageError failure) {
                fail(failure.toString());
            }
        }

        /**
         * The next step not done: combat's effects, a board asked for, newest first, else the next sample board; null
         * when none is left.
         */
        private Step next() throws IOException {
            if (wanted(Step.EFFECTS)) { return Step.EFFECTS; }
            for (Path board : requests(GpuGlsl.programCache)) {
                Step step = Step.of(board);
                if (step != null && wanted(step)) { return step; }
            }
            for (Step step : samples) {
                if (sampled < SAMPLE_BYTES && wanted(step)) { return step; }
            }
            return null;
        }

        private boolean wanted(Step step) {
            return !done.contains(step.id()) && (step.board() != null || chosen == null || chosen.contains(step.id()));
        }

        /** Records {@code step} done with the bytes it stored, which count towards {@link #SAMPLE_BYTES} for a sample board. */
        private void finish(Step step, long bytes) throws IOException {
            Files.writeString(progress, step.id() + " " + bytes + "\n", StandardOpenOption.APPEND);
            done.add(step.id());
            if (step.board() == null) { sampled += bytes; }
        }

        @Override
        public void render() {
            try {
                if (current == null) {
                    Step step = next();
                    if (step == null) {
                        if (!done.contains(DONE)) { Files.writeString(progress, DONE + "\n", StandardOpenOption.APPEND); }
                        answers.println(DONE);
                        Runtime.getRuntime().halt(0);
                    }
                    begin(step);
                    return;
                }
                boolean ready;
                if (current == Step.EFFECTS) {
                    ready = effects.linked();
                } else {
                    terrain.refine(null, true);
                    ready = !terrain.busy() && !terrain.familiesPending();
                }
                if (ready) {
                    if (effects != null) { effects.dispose(); effects = null; }
                    long written = GpuGlsl.programCache.unpruned();
                    finish(current, written - stored);
                    int count = GpuGlsl.programCache.stored.get();
                    answers.println(STEP + current.id() + " " + (count - programs) + " " + (written - stored) + " "
                          + (System.nanoTime() - started) / 1_000_000_000L);
                    stored = written;
                    programs = count;
                    current = null;
                }
            } catch (IOException | RuntimeException | LinkageError failure) {
                if (current == null) {
                    fail(failure.toString());
                    return;
                }
                // A board that can't be prepared has its loading stage, as before, and the other steps go on: one theme's
                // failure leaves the blends of the others to prepare. It counts as done, so it isn't tried again.
                answers.println(FAILED + Base64.getEncoder().encodeToString((current.id() + ": " + failure)
                      .getBytes(StandardCharsets.UTF_8)));
                try {
                    // What it stored before it failed is its own, not the next step's.
                    long written = GpuGlsl.programCache.unpruned();
                    finish(current, written - stored);
                    stored = written;
                    programs = GpuGlsl.programCache.stored.get();
                    if (current.board() != null) { Files.deleteIfExists(current.board()); }
                } catch (IOException ignored) {
                    // Tried again at the next start.
                }
                release();
                current = null;
            }
        }

        /**
         * Asks for the board's terrain as a board view configures its own each frame (GpuBattleView, and the lobby's
         * preview), its lighting configuration's terrain made once for all the boards of that configuration.
         */
        private void begin(Step step) throws IOException {
            current = step;
            started = System.nanoTime();
            if (step == Step.EFFECTS) {
                effects = new Effects();
                return;
            }
            if (step.lighting() != lighting) {
                release();
                lighting = step.lighting();
                terrain = new GpuTerrain();
                terrain.setEditable(lighting.editable);
                // A game shows combat scars, the editor none (GpuBattleView: the source's gameplay).
                terrain.setCombatScars(!lighting.editable);
                terrain.setLightingStages(lighting.biomes, lighting.crystals);
                // GpuBoardTuning's default.
                terrain.setMaterialCache(GpuMaterialCache.ENABLED && GpuMaterialCache.SPLIT);
                atmosphere = new GpuAtmosphere(BoardAtmosphere.DEFAULTS, GpuAtmosphere.Options.DEFAULTS);
                occlusion = new GpuAmbientOcclusion();
            } else {
                // A new board, as the lobby's preview switches to one: of the same size and id it would be an edit,
                // which starts no family programs, and the blends would never link.
                terrain.boardChanged();
            }
            BoardScene scene = step.board() == null ? board(step.theme())
                  : GpuEditorTerrainPreviews.scene(BoardFile.read(step.board()));
            camera.resize(WIDTH, HEIGHT);
            camera.fit(scene);
            atmosphere.configure(BoardAtmosphere.DEFAULTS);
            display.apply(terrain, atmosphere, occlusion, WIDTH, HEIGHT, false);
            atmosphere.updateLight(camera.camera);
            terrain.setAtmosphere(atmosphere.lighting());
            atmosphere.configureClouds(terrain, scene);
            // A vacuum's lunar ground, as its game's conditions make it (GpuTerrain#update).
            terrain.setGravity(step.weightless() ? 0 : BoardAtmosphere.DEFAULTS.gravity());
            terrain.setVacuum(step.vacuum());
            terrain.setColumns(graphics == BoardGraphics.CLASSIC);
            terrain.update(scene, camera.camera);
        }

        private void release() {
            if (effects != null) { effects.dispose(); effects = null; }
            if (terrain != null) { terrain.dispose(); terrain = null; }
            if (atmosphere != null) { atmosphere.dispose(); atmosphere = null; }
            if (occlusion != null) { occlusion.dispose(); occlusion = null; }
            lighting = null;
        }

        /** The process can't go on (it couldn't start, or its progress can't be kept): the game counts a failure. */
        private void fail(String reason) {
            answers.println(FAILED + Base64.getEncoder().encodeToString(reason.getBytes(StandardCharsets.UTF_8)));
            Runtime.getRuntime().halt(1);
        }
    }
}
