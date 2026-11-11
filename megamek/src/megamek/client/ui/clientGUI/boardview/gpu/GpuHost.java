/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.ui.TooltipManager;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.LauncherReadySignal;
import megamek.client.ui.util.ScreenFit;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * MegaMek's one native (libGDX) window. It runs one scene at a time (the start screen, a game's board, the map
 * editor, a map preview or the UI library), and switching scenes keeps the window, its place and its OpenGL context.
 * When MegaMek runs its own start screen the window stays open from the greeting to Quit ({@link #keepOpen}); without
 * it (a game MekHQ starts) the window opens with a scene and closes when the scene ends, as before.
 *
 * <p>Scenes are asked for on any thread and switch on the GPU thread, between frames: the old scene's listener is
 * paused and disposed, the shared OpenGL state is reset, and the new scene's listener is created and sized. A scene
 * that fails ends with the failure; the window stays for the next scene.</p>
 *
 * <p>Other programs of the suite (MekHQ) host their own scenes through the public part: a {@link Scene}, shown with
 * {@link #show} and ended with {@link #end}, work handed to its GPU thread with {@link #post}, and
 * {@link GpuSceneDialogs} for the Swing dialogs it opens over the window. A scene never touches its program's model
 * on the GPU thread; it draws snapshots its Swing thread posts.</p>
 */
public final class GpuHost {
    private static final MMLogger LOGGER = MMLogger.create(GpuHost.class);
    /** Frames between saves of the window's bounds. */
    private static final int BOUNDS_SAVE_FRAMES = 15;

    /** What runs in the window. The callbacks other than {@link #ended} run on the GPU thread. */
    public interface Scene {
        /** GPU thread: the listener that draws the scene, made as the scene starts. */
        ApplicationListener listener();

        /** The window's title while the scene shows. */
        String title();

        /** GPU thread, before the listener is created: the window the scene shows in. */
        default void started(long windowHandle) { }

        /** GPU thread: the window's close box was clicked. The window never closes by itself. */
        void closeRequested();

        /** GPU thread: the window took the focus. */
        default void focusGained() { }

        /** GPU thread: the window lost the focus. */
        default void focusLost() { }

        /** Whether the scene is a board view (a game, the editor, a map preview): the shader warm-up waits meanwhile. */
        default boolean board() { return false; }

        /** GPU thread: whether the scene shows its own marker while programs link (the editor's), so the window's doesn't. */
        default boolean marksLinks() { return false; }

        /**
         * Any thread: the scene has ended, its listener disposed if it was ever created; {@code failure} when it
         * ended because it failed, null otherwise. Runs once, and without OpenGL.
         */
        void ended(Throwable failure);
    }

    private static final Object LOCK = new Object();
    /** Guarded by {@link #LOCK}: the scene asked for, which the GPU thread shows next; null for none. */
    private static Scene requested;
    /** Guarded by {@link #LOCK}: the GPU thread, or null while the window is closed. */
    private static Thread thread;
    private static volatile boolean keepOpen;
    private static volatile Runnable idle;
    private static volatile Lwjgl3Application application;
    private static volatile long windowHandle;
    /** Whether a window ever opened in this run; a failure before then means OpenGL itself could not start. */
    private static volatile boolean everOpened;
    /** The window's client area in GLFW screen coordinates, followed every frame; null while it is closed. */
    private static volatile Rectangle clientBounds;

    /** Written under {@link #LOCK} by the GPU thread: the scene shown now. */
    private static volatile Scene current;
    // GPU thread only
    private static ApplicationListener listener;
    /** The board skin every scene of the window shares ({@link #skin}); disposed with the window. */
    private static GpuBoardSkin skin;
    /** Shows over every scene while programs link in the background; made when it first shows. */
    private static LinkMarker marker;
    private static GpuWindowBounds startBounds;
    private static GpuWindowBounds normalBounds;
    private static GpuWindowBounds publishedBounds;
    private static int framesSinceSave;
    private static volatile boolean boundsKept;

    private GpuHost() {
    }

    /**
     * Any time: stops saving the window's bounds for the rest of the run, so a developer probe that sizes the window
     * leaves the size saved for the next start as it was.
     */
    static void keepSavedBounds() {
        boundsKept = true;
    }

    /**
     * The window's board skin, which its start screen, lobby and board views share, so glyphs one of them rasterised
     * stay for the next; made at first use and after a palette change. Null off the window's GPU thread (a test's own
     * application), where a scene makes and owns its own ({@link GpuBoardSkin#forScene}).
     */
    static GpuBoardSkin skin() {
        synchronized (LOCK) {
            if (Thread.currentThread() != thread) { return null; }
        }
        // The palette changed since the last scene (Settings, the UI library): a new skin, as refreshing this one's
        // tokens would leave the textures it replaces undisposed
        if (skin != null && !skin.inPalette()) {
            skin.dispose();
            skin = null;
        }
        if (skin == null) { skin = new GpuBoardSkin(true); }
        return skin;
    }

    /**
     * Any time: whether the window stays open between scenes. MegaMek's own start screen sets it, so the window shows
     * from the greeting to Quit; a game started by MekHQ leaves it off, so its window closes with the game.
     */
    public static void keepOpen(boolean open) {
        keepOpen = open;
    }

    /** Whether the window stays open between scenes. */
    static boolean keepsOpen() {
        return keepOpen;
    }

    /**
     * Any time: what the open window shows when a scene ends and none takes its place, run on the Swing thread after
     * the scene's {@link Scene#ended}; the start screen reopens itself this way. Null leaves the window empty.
     */
    public static void whenIdle(Runnable then) {
        idle = then;
    }

    /**
     * Any thread: shows {@code scene} in the window, opening the window when it is closed, and ends the scene shown
     * now. A scene asked for but not shown yet ends without starting.
     */
    public static void show(Scene scene) {
        if (scene.board()) {
            // Its board's links come first: the warm-up ends now, not when the scene starts.
            GpuShaderWarmup.showing(true);
        }
        Scene replaced;
        synchronized (LOCK) {
            replaced = requested;
            requested = scene;
            if (thread == null) {
                startThread();
            }
            if (replaced == scene || current == replaced) {
                // The GPU thread ends the shown one as it switches
                replaced = null;
            }
        }
        if (replaced != null) {
            replaced.ended(null);
        }
    }

    /**
     * Any thread: ends {@code scene} if it shows or is about to. The window then waits for the next scene, or closes
     * when it doesn't stay open. Nothing when another scene has taken its place.
     */
    public static void end(Scene scene) {
        boolean pending;
        boolean empty;
        synchronized (LOCK) {
            if (requested != scene) {
                return;
            }
            requested = null;
            pending = current != scene;
            empty = current == null;
        }
        if (pending) {
            scene.ended(null);
            if (empty) {
                // No shown scene stops in its place, which would bring the idle scene back
                SwingUtilities.invokeLater(GpuHost::idled);
            }
        }
        Lwjgl3Application app = application;
        if (app != null) {
            // Wakes the loop when it renders on demand
            app.postRunnable(() -> { });
        }
    }

    /** Any thread: whether {@code scene} is the one asked for, shown or about to be. */
    public static boolean isShowing(Scene scene) {
        return wanted() == scene;
    }

    private static Scene wanted() {
        synchronized (LOCK) {
            return requested;
        }
    }

    /** Any thread: whether the window is open or opening. */
    public static boolean isOpen() {
        synchronized (LOCK) {
            return thread != null;
        }
    }

    /** GPU thread: the shown scene's listener, or null between scenes; {@code Gdx.app}'s listener is the host's. */
    static ApplicationListener sceneListener() {
        return listener;
    }

    /** GPU thread: the scene's own input processor when {@code processor} is the host's guard in front of it. */
    static InputProcessor unguarded(InputProcessor processor) {
        return processor == Root.GUARD ? Root.sceneInput : processor;
    }

    /** Whether a window ever opened in this run. */
    static boolean everOpened() {
        return everOpened;
    }

    /** Any thread: runs {@code work} on the GPU thread, before the next frame; nothing while the window is closed. */
    public static void post(Runnable work) {
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(work);
        }
    }

    /** The open window's client area in GLFW screen coordinates, or null while it is closed. */
    static Rectangle clientBounds() {
        return clientBounds;
    }

    /**
     * The open window's client area in Swing's coordinates, or null while it is closed: the area a dialog of a hidden
     * Swing frame should size and centre itself by while the window shows in its place.
     */
    public static Rectangle swingClientBounds() {
        Rectangle bounds = clientBounds;
        return bounds == null ? null : GpuStartWindow.toSwing(bounds);
    }

    /** Any thread: brings the window to the front and gives it the keyboard. */
    public static void focus() {
        post(() -> {
            if (Gdx.graphics instanceof Lwjgl3Graphics graphics) {
                Lwjgl3Window window = graphics.getWindow();
                window.setVisible(true);
                if (window.isIconified()) {
                    window.restoreWindow();
                }
                window.focusWindow();
            }
        });
    }

    private static void startThread() {
        Thread started = new Thread(GpuHost::run, "MegaMek-GPU");
        started.setDaemon(true);
        thread = started;
        started.start();
    }

    private static void run() {
        Throwable failure = null;
        try {
            startBounds = loadBounds();
            normalBounds = startBounds;
            publishedBounds = startBounds;
            Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(true);
            configuration.setWindowedMode(startBounds.width(), startBounds.height());
            if (!startBounds.centred()) {
                configuration.setWindowPosition(startBounds.x(), startBounds.y());
            }
            configuration.setMaximized(startBounds.maximized());
            // The start screen's hex reveal draws through a stencil mask; no multisampling, which the board's blits
            // to the window could not resolve into
            configuration.setBackBufferConfig(8, 8, 8, 8, 24, 8, 0);
            configuration.setWindowListener(new Lwjgl3WindowAdapter() {
                @Override
                public void created(Lwjgl3Window window) {
                    everOpened = true;
                    GpuGlsl.detect();
                    GpuShaderWarmup.open();
                    GpuNativeDialogOwner.Win32.useWindowIconsForClass(window.getWindowHandle());
                    fitOnScreen(window);
                    windowHandle = window.getWindowHandle();
                    LauncherReadySignal.nativeWindowOpened();
                }

                @Override
                public void focusGained() {
                    if (current != null) {
                        current.focusGained();
                    }
                }

                @Override
                public void focusLost() {
                    if (current != null) {
                        current.focusLost();
                    }
                }

                @Override
                public boolean closeRequested() {
                    if (current != null) {
                        current.closeRequested();
                        return false;
                    }
                    return !keepOpen;
                }
            });
            new Lwjgl3Application(new Root(), configuration);
        } catch (RuntimeException | LinkageError error) {
            failure = error;
            try {
                String detail = GpuGlsl.glfwError();
                if (!detail.isEmpty()) {
                    failure = new GdxRuntimeException(detail, error);
                }
            } catch (RuntimeException | LinkageError diagnosticFailure) {
                error.addSuppressed(diagnosticFailure);
            }
        } finally {
            closed(failure);
        }
    }

    /** The window's thread, as it ends: ends the scenes it leaves, or opens it again for one asked for meanwhile. */
    private static void closed(Throwable failure) {
        application = null;
        windowHandle = 0;
        clientBounds = null;
        listener = null;
        // Disposed by Root.dispose, unless the window failed: its textures then went with the OpenGL context
        skin = null;
        marker = null;
        // No menu shows now (MekHQ goes on without the window): nothing warms until a window opens again.
        GpuShaderWarmup.close();
        Scene shown;
        Scene waiting;
        synchronized (LOCK) {
            shown = current;
            current = null;
            thread = null;
            waiting = requested;
            if (failure != null || waiting == shown) {
                requested = null;
            }
            if (failure == null && waiting != null && waiting != shown) {
                startThread();
                waiting = null;
            }
        }
        if (failure != null) {
            LOGGER.error(failure, "The native window failed");
        }
        if (shown != null) {
            shown.ended(failure);
        }
        if (waiting != null && waiting != shown) {
            waiting.ended(failure);
        }
    }

    /** EDT, after a scene ended: shows the idle scene when the window stays open and nothing else was asked for. */
    private static void idled() {
        Runnable then = idle;
        if (then != null && keepOpen && isOpen() && wanted() == null) {
            then.run();
        }
    }

    private static GpuWindowBounds loadBounds() {
        GpuWindowBounds[] bounds = new GpuWindowBounds[1];
        try {
            SwingUtilities.invokeAndWait(() -> bounds[0] = GpuWindowBounds.load(GUIPreferences.getInstance()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new IllegalStateException(failure.getCause());
        }
        return bounds[0];
    }

    /**
     * A window saved on a monitor that has since been unplugged, or moved off the desktop, comes back onto the nearest
     * monitor's work area. A centred window needs no check.
     */
    private static void fitOnScreen(Lwjgl3Window window) {
        if (startBounds.centred()) {
            return;
        }
        var fitted = ScreenFit.fit(startBounds.rectangle(), GpuWindowBounds.workAreas());
        if (fitted.equals(startBounds.rectangle())) {
            return;
        }
        LOGGER.info("The window saved at {} is off screen; moved to {}", startBounds.rectangle(), fitted);
        long handle = window.getWindowHandle();
        boolean maximized = startBounds.maximized();
        if (maximized) {
            window.restoreWindow();
        }
        GLFW.glfwSetWindowSize(handle, fitted.width, fitted.height);
        window.setPosition(fitted.x, fitted.y);
        if (maximized) {
            window.maximizeWindow();
        }
        normalBounds = new GpuWindowBounds(fitted.x, fitted.y, fitted.width, fitted.height, maximized);
    }

    /**
     * Follows the window as the user moves, resizes or maximizes it: its client area every frame, and the bounds to
     * save a few times a second. The normal bounds update only while the window is neither maximized nor
     * minimized, so a maximized window keeps the size it restores to, and a minimized one does not save the far-off
     * position Windows reports for it.
     */
    private static void trackBounds() {
        if (!(Gdx.graphics instanceof Lwjgl3Graphics graphics)) {
            return;
        }
        Lwjgl3Window window = graphics.getWindow();
        if (window == null || window.isIconified()) {
            return;
        }
        long handle = window.getWindowHandle();
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetWindowSize(handle, width, height);
        Rectangle client = new Rectangle(window.getPositionX(), window.getPositionY(), width[0], height[0]);
        clientBounds = client;

        // Fullscreen keeps the windowed bounds it returns to, which are also what the next start opens.
        if (boundsKept || graphics.isFullscreen() || ++framesSinceSave < BOUNDS_SAVE_FRAMES) {
            return;
        }
        framesSinceSave = 0;
        boolean maximized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
        GpuWindowBounds normal = normalBounds;
        if (!maximized) {
            normal = new GpuWindowBounds(client.x, client.y, client.width, client.height, false);
            normalBounds = normal;
        }
        GpuWindowBounds saved = new GpuWindowBounds(normal.x(), normal.y(), normal.width(), normal.height(), maximized);
        if (!saved.equals(publishedBounds)) {
            publishedBounds = saved;
            SwingUtilities.invokeLater(() -> saved.save(GUIPreferences.getInstance()));
        }
    }

    /** GPU thread: the window's tooltips show after the delay the player set. */
    static void tooltipDelay() {
        TooltipManager.getInstance().initialTime = Math.max(0, GUIPreferences.getInstance().getTooltipDelay()) / 1000f;
    }

    /** The window's listener: hands each frame to the scene and switches scenes between frames. */
    private static final class Root implements ApplicationListener {
        /** The scene's own input, which {@link #GUARD} passes events to. */
        private static InputProcessor sceneInput;
        private static final InputProcessor GUARD = new Guard();

        @Override
        public void create() {
            application = (Lwjgl3Application) Gdx.app;
            // Its one program links as the window opens, never beside a board's frames.
            marker = new LinkMarker();
            tooltipDelay();
        }

        @Override
        public void resize(int width, int height) {
            if (listener != null && width > 0 && height > 0) {
                try {
                    listener.resize(width, height);
                } catch (RuntimeException | LinkageError failure) {
                    stop(failure);
                }
            }
        }

        @Override
        public void render() {
            if (current != null && current != wanted()) {
                stop(null);
            }
            if (current == null) {
                Scene next;
                synchronized (LOCK) {
                    next = requested;
                    current = next;
                }
                if (next != null) {
                    start(next);
                } else if (!keepOpen) {
                    Gdx.app.exit();
                    return;
                }
            }
            trackBounds();
            if (listener != null) {
                try {
                    listener.render();
                } catch (RuntimeException | LinkageError failure) {
                    stop(failure);
                }
            }
            if (listener == null) {
                Gdx.gl.glClearColor(.045f, .065f, .075f, 1);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            }
            GpuShaderWarmup.frame();
            marker.render(current);
            guardInput();
        }

        private void start(Scene scene) {
            LOGGER.info("Native window scene starts: {}", scene.title());
            GpuShaderWarmup.showing(scene.board());
            resetGl();
            Gdx.input.setInputProcessor(null);
            sceneInput = null;
            Gdx.graphics.setForegroundFPS(GpuBoardWindow.DEFAULT_VSYNC ? 0 : 60);
            if (Gdx.graphics instanceof Lwjgl3Graphics graphics) {
                graphics.getWindow().setTitle(scene.title());
            }
            try {
                scene.started(windowHandle);
                listener = scene.listener();
                listener.create();
                if (Gdx.graphics.getWidth() > 0 && Gdx.graphics.getHeight() > 0) {
                    listener.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                }
            } catch (RuntimeException | LinkageError failure) {
                stop(failure);
            }
        }

        /** Ends the current scene, with {@code failure} when it failed; a failed scene is not shown again. */
        private void stop(Throwable failure) {
            Scene scene = current;
            if (scene != null) {
                LOGGER.info("Native window scene ends: {}", scene.title());
            }
            ApplicationListener ending = listener;
            listener = null;
            if (ending != null) {
                try {
                    ending.pause();
                    ending.dispose();
                } catch (RuntimeException | LinkageError disposal) {
                    if (failure == null) {
                        failure = disposal;
                    } else {
                        failure.addSuppressed(disposal);
                    }
                }
            }
            Gdx.input.setInputProcessor(null);
            sceneInput = null;
            resetGl();
            synchronized (LOCK) {
                current = null;
                if (failure != null && requested == scene) {
                    requested = null;
                }
            }
            if (scene != null) {
                scene.ended(failure);
                SwingUtilities.invokeLater(GpuHost::idled);
            }
        }


        /** Keeps the scene's input behind the guard, whichever processor the scene set last. */
        private void guardInput() {
            InputProcessor input = Gdx.input.getInputProcessor();
            if (input != GUARD) {
                sceneInput = input;
                Gdx.input.setInputProcessor(GUARD);
            }
        }

        @Override
        public void pause() {
            if (listener != null) {
                listener.pause();
            }
        }

        @Override
        public void resume() {
            if (listener != null) {
                listener.resume();
            }
        }

        @Override
        public void dispose() {
            if (current != null) {
                stop(null);
            }
            // The shared skin's textures go with the window's OpenGL context.
            if (skin != null) {
                skin.dispose();
                skin = null;
            }
            if (marker != null) {
                marker.dispose();
                marker = null;
            }
        }

        /** Passes input to the scene, whichever processor it set last. */
        private static final class Guard extends InputAdapter {
            private InputProcessor target() {
                return sceneInput;
            }

            @Override
            public boolean keyDown(int keycode) {
                InputProcessor target = target();
                return target != null && target.keyDown(keycode);
            }

            @Override
            public boolean keyUp(int keycode) {
                InputProcessor target = target();
                return target != null && target.keyUp(keycode);
            }

            @Override
            public boolean keyTyped(char character) {
                InputProcessor target = target();
                return target != null && target.keyTyped(character);
            }

            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                InputProcessor target = target();
                return target != null && target.touchDown(screenX, screenY, pointer, button);

            }

            @Override
            public boolean touchUp(int screenX, int screenY, int pointer, int button) {
                InputProcessor target = target();
                return target != null && target.touchUp(screenX, screenY, pointer, button);
            }

            @Override
            public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
                InputProcessor target = target();
                return target != null && target.touchCancelled(screenX, screenY, pointer, button);
            }

            @Override
            public boolean touchDragged(int screenX, int screenY, int pointer) {
                InputProcessor target = target();
                return target != null && target.touchDragged(screenX, screenY, pointer);
            }

            @Override
            public boolean mouseMoved(int screenX, int screenY) {
                InputProcessor target = target();
                return target != null && target.mouseMoved(screenX, screenY);
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                InputProcessor target = target();
                return target != null && target.scrolled(amountX, amountY);
            }
        }
    }

    /**
     * The window's marker while programs link in the background (the shader warm-up, the link helper, a board's links
     * deferred beside its frames): the editor's spinner (GpuBoardEditor), translucent in the window's corner over any
     * scene. It takes no input; the editor shows its own in its place.
     */
    private static final class LinkMarker implements Disposable {
        /** Stage units, and the editor's marker's timing: quick work never flashes it. */
        private static final float SIZE = 24, MARGIN = 12, SHOW = .3f, HIDE = .25f, OPACITY = .6f;
        private final GpuDisplayScale displayScale = new GpuDisplayScale();
        private final IntBuffer viewport = BufferUtils.newIntBuffer(16);
        private final ByteBuffer depthMask = BufferUtils.newByteBuffer(16);
        private static final int[] CAPABILITIES = { GL20.GL_DEPTH_TEST, GL20.GL_SCISSOR_TEST, GL20.GL_STENCIL_TEST,
              GL20.GL_BLEND, GpuLinearOverlay.FRAMEBUFFER_SRGB };
        private final boolean[] enabled = new boolean[CAPABILITIES.length];
        private final SpriteBatch batch = new SpriteBatch(1);
        private int unlinked = GpuShaderProvider.unlinkedDraws();
        private float busyFor, idleFor, angle;
        /** It failed once: the scenes go on without it. */
        private boolean broken;

        /** GPU thread, after the scene's frame. */
        void render(Scene scene) {
            float delta = Gdx.graphics.getDeltaTime();
            int skipped = GpuShaderProvider.unlinkedDraws();
            GpuLinkHelper helper = GpuGlsl.linkHelper;
            boolean busy = skipped != unlinked || GpuShaderWarmup.busy() || helper != null && helper.busy();
            unlinked = skipped;
            if (busy) {
                busyFor += delta;
                idleFor = 0;
            } else if ((idleFor += delta) > HIDE) {
                busyFor = 0;
            }
            angle = (angle - 360 * delta) % 360;
            if (broken || busyFor <= SHOW || scene != null && scene.marksLinks()) {
                return;
            }
            try {
                // The scenes' own skin as it is: skin() would replace one from another palette under the scene.
                GpuBoardSkin shared = skin;
                if (shared != null && shared.skin.has("icon-progress", Drawable.class)
                      && shared.skin.getDrawable("icon-progress") instanceof TransformDrawable icon) {
                    draw(icon);
                }
            } catch (RuntimeException | LinkageError failure) {
                broken = true;
                LOGGER.warn(failure, "The window's shader marker failed; it stays hidden");
            }
        }

        /** Over whatever the scene left bound and enabled, which it gets back. */
        private void draw(TransformDrawable icon) {
            GL20 gl = Gdx.gl;
            int drawing = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int reading = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
            gl.glGetBooleanv(GL20.GL_DEPTH_WRITEMASK, depthMask);
            for (int i = 0; i < CAPABILITIES.length; i++) {
                enabled[i] = gl.glIsEnabled(CAPABILITIES[i]);
                gl.glDisable(CAPABILITIES[i]);
            }
            try {
                gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
                gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                float width = Gdx.graphics.getWidth(), height = Gdx.graphics.getHeight();
                float size = SIZE * DisplayScale.read(1, displayScale.contentScale()), margin = MARGIN * size / SIZE;
                batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
                batch.begin();
                batch.setColor(UiTheme.MUTED.r, UiTheme.MUTED.g, UiTheme.MUTED.b, OPACITY);
                icon.draw(batch, width - margin - size, margin, size / 2, size / 2, size, size, 1, 1, angle);
                batch.end();
            } finally {
                for (int i = 0; i < CAPABILITIES.length; i++) {
                    if (enabled[i]) {
                        gl.glEnable(CAPABILITIES[i]);
                    } else {
                        gl.glDisable(CAPABILITIES[i]);
                    }
                }
                gl.glDepthMask(depthMask.get(0) != 0);
                gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawing);
                gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, reading);
                gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            }
        }

        @Override
        public void dispose() {
            batch.dispose();
        }
    }

    /** The OpenGL state every scene starts from, whatever the one before left. */
    private static void resetGl() {
        GL20 gl = Gdx.gl;
        gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
        gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        gl.glDisable(GL20.GL_BLEND);
        gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDisable(GL20.GL_SCISSOR_TEST);
        gl.glDisable(GL20.GL_STENCIL_TEST);
        gl.glDisable(GL20.GL_CULL_FACE);
        gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        gl.glColorMask(true, true, true, true);
        gl.glDepthMask(true);
        gl.glDepthFunc(GL20.GL_LESS);
        gl.glUseProgram(0);
        gl.glActiveTexture(GL20.GL_TEXTURE0);
        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        // A context without sRGB framebuffers reports the disable as an error; it changes nothing there.
        while (gl.glGetError() != GL20.GL_NO_ERROR) {
            // drain
        }
    }
}
