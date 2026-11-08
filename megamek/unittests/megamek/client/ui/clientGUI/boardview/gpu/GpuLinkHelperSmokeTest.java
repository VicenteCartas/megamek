/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Programs linked in another process: the binary comes back through the program cache, the game's process links nothing,
 * a program that doesn't compile says why, and neither a damaged binary nor a helper that dies loses a program.
 */
@Tag("on-demand")
class GpuLinkHelperSmokeTest {
    private static final String VERTEX = "#version 330 core\nin vec3 a_position;\nvoid main() { gl_Position = vec4(a_position, 1.0); }\n";
    private static final long LIMIT = TimeUnit.MINUTES.toNanos(3);
    @TempDir Path directory;

    private static String fragment(float red) {
        return "#version 330 core\nout vec4 color;\nvoid main() { color = vec4(" + red + ", 0.0, 0.0, 1.0); }\n";
    }

    @Test
    void linksComeBackThroughTheCacheAndNothingIsLost() throws Exception {
        AtomicBoolean unsupported = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuProgramCache previous = GpuGlsl.programCache;
                GpuLinkHelper previousHelper = GpuGlsl.linkHelper;
                try {
                    var cache = GpuProgramCache.open(directory);
                    unsupported.set(cache == null);
                    if (cache == null) { return; }
                    GpuGlsl.programCache = cache;
                    var helper = GpuLinkHelper.open(cache, version(GL30.GL_MAJOR_VERSION), version(GL30.GL_MINOR_VERSION), false);
                    assertTrue(helper != null, "A driver with binary formats can use a helper");
                    GpuGlsl.linkHelper = helper;
                    var links = new GpuGlsl.Preparation(false);
                    assertTrue(links.background() && links.parallel());

                    // Linked over there, loaded here.
                    long start = System.nanoTime();
                    link(links, fragment(.1f));
                    System.out.printf("First program through a starting helper: %d ms%n", (System.nanoTime() - start) / 1_000_000);
                    assertEquals(0, cache.stored.get(), "The binary is the helper's");
                    assertEquals(1, cache.loaded.get());
                    start = System.nanoTime();
                    link(links, fragment(.2f));
                    System.out.printf("A program through a running helper: %d ms%n", (System.nanoTime() - start) / 1_000_000);
                    assertEquals(2, cache.loaded.get());

                    // A program that doesn't compile says so with the driver's message, and fails without a link here.
                    Runnable broken = () -> GpuGlsl.compile("broken program", VERTEX, "#version 330 core\nthis does not compile\n");
                    String reported = awaitFailure(links, broken);
                    assertTrue(reported.contains("broken program") && reported.contains("fragment"), reported);
                    assertEquals(0, cache.stored.get());

                    // A damaged binary is deleted, and the helper links it again.
                    String key = cache.key(GpuGlsl.source("", VERTEX, GpuGlsl.glsl()), GpuGlsl.source("", fragment(.2f), GpuGlsl.glsl()));
                    Path entry = directory.resolve(key + ".bin");
                    byte[] bytes = Files.readAllBytes(entry);
                    bytes[bytes.length - 1] ^= 1;
                    Files.write(entry, bytes);
                    link(links, fragment(.2f));
                    assertEquals(1, cache.discarded.get());
                    assertTrue(Files.isRegularFile(entry), "Linked again");

                    // A binary the driver keeps rejecting links here in the end, rather than over there for ever.
                    int storedHere = cache.stored.get();
                    for (int rejected = cache.discarded.get(); cache.stored.get() == storedHere; rejected = cache.discarded.get()) {
                        bytes = Files.readAllBytes(entry);
                        bytes[bytes.length - 1] ^= 1;
                        Files.write(entry, bytes);
                        link(links, fragment(.2f));
                        assertEquals(rejected + 1, cache.discarded.get());
                        assertTrue(cache.discarded.get() < 10, "A rejected program stops linking over there");
                    }

                    // A cache the helper can't write to: this program links here too, and an optional one stays without.
                    var moved = directory.resolveSibling(directory.getFileName() + "-moved");
                    Files.move(directory, moved);
                    try {
                        storedHere = cache.stored.get();
                        link(links, fragment(.25f));
                        assertEquals(storedHere, cache.stored.get(), "Nor can this process keep it");
                        assertThrows(GpuGlsl.Uncached.class, () -> awaitOptional(links, fragment(.26f)));
                    } finally {
                        Files.move(moved, directory);
                    }

                    // The game's end closes its input: the helper leaves. The next job starts another.
                    Process running = helper.process();
                    running.getOutputStream().close();
                    assertTrue(running.waitFor(20, TimeUnit.SECONDS), "A helper leaves when its input closes");
                    int keptBefore = cache.stored.get();
                    link(links, fragment(.3f));
                    assertEquals(keptBefore, cache.stored.get(), "Its binary is the new helper's");

                    // A helper dying under a job loses nothing. Dying too often ends it, and the game's own paths link.
                    int program = 40;
                    while (helper.usable()) {
                        float red = program++ * .01f;
                        Runnable ask = () -> GpuGlsl.compile("killed", VERTEX, fragment(red));
                        assertFalse(links.run(ask), "Not linked yet");
                        await(() -> helper.process() != null || !helper.usable());
                        Process victim = helper.process();
                        if (victim != null) { victim.destroyForcibly(); }
                        await(() -> helper.process() != victim || !helper.usable());
                    }
                    assertFalse(links.background(), "An ended helper is out of the way");
                    assertThrows(GpuGlsl.Uncached.class, () -> links.runOptional(() -> GpuGlsl.compile("optional", VERTEX, fragment(.8f))),
                          "Beside the frames nothing optional links in this process");
                    assertEquals(links.parallel(), Gdx.graphics.supportsExtension("GL_KHR_parallel_shader_compile")
                          || Gdx.graphics.supportsExtension("GL_ARB_parallel_shader_compile"));
                    int stored = cache.stored.get();
                    link(links, fragment(.9f));
                    assertEquals(stored + 1, cache.stored.get(), "With no helper the game's process links and keeps it");
                    links.dispose();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuGlsl.programCache = previous;
                    GpuGlsl.linkHelper = previousHelper;
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Link helper", failure.get()); }
        assumeFalse(unsupported.get(), "The driver offers no program binary format");
    }

    private static int version(int name) {
        var value = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(name, value);
        return value.get(0);
    }

    private static void link(GpuGlsl.Preparation links, String fragment) {
        ShaderProgram[] program = new ShaderProgram[1];
        Runnable ask = () -> program[0] = GpuGlsl.compile("helper test", VERTEX, fragment);
        long deadline = System.nanoTime() + LIMIT;
        while (!links.run(ask)) {
            assertTrue(System.nanoTime() < deadline, "The program must link");
            pause();
        }
        assertTrue(program[0].isCompiled());
        program[0].dispose();
    }

    private static void awaitOptional(GpuGlsl.Preparation links, String fragment) {
        long deadline = System.nanoTime() + LIMIT;
        while (!links.runOptional(() -> GpuGlsl.compile("optional", VERTEX, fragment))) {
            assertTrue(System.nanoTime() < deadline, "It must come to an end");
            pause();
        }
    }

    private static String awaitFailure(GpuGlsl.Preparation links, Runnable broken) {
        long deadline = System.nanoTime() + LIMIT;
        while (true) {
            try {
                assertFalse(links.run(broken), "It must not compile");
            } catch (GdxRuntimeException failed) {
                return failed.getMessage();
            }
            assertTrue(System.nanoTime() < deadline, "The failure must come back");
            pause();
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + LIMIT;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "It must happen");
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
        }
    }
}
