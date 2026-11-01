/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.lwjgl.util.nfd.NativeFileDialog.NFD_CANCEL;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_FreePath;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_GetError;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_Init;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_OpenDialog;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_SaveDialog;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import megamek.logging.MMLogger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;

/**
 * The system's own open and save file dialogs of the GPU windows, through LWJGL's Native File Dialog, on Windows only:
 * on macOS it must run on AppKit's main thread, which AWT holds, and on Linux it would load a second GTK beside AWT's;
 * there {@link SystemFileDialog} shows AWT's FileDialog, which is the system's too. It runs on a thread of its own, so
 * the window keeps drawing while it is open; where it can't show, the caller falls back to Swing's chooser.
 */
final class NativeFileChooser {
    private static final MMLogger LOGGER = MMLogger.create(NativeFileChooser.class);
    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Native file dialog");
        thread.setDaemon(true);
        return thread;
    });
    /** Turns false for the session once the dialog fails to show. */
    private static volatile boolean usable = SharedLibraryLoader.os == Os.Windows;

    private NativeFileChooser() {
    }

    static boolean usable() {
        return usable;
    }

    /**
     * Any thread: shows the open, or the {@code save}, dialog at {@code directory} for {@code filter}'s files, a save
     * suggesting {@code name} when not null, then, on the dialog's thread, hands {@code done} the file chosen, or null
     * when the player cancels, or runs {@code failed} when it couldn't show.
     */
    static void show(File directory, FileNameExtensionFilter filter, boolean save, String name, Consumer<File> done,
          Runnable failed) {
        THREAD.execute(() -> {
            File chosen;
            try {
                chosen = blocking(directory, filter, save, name);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("The system file dialog couldn't show; using Swing's from now on: {}", failure.toString());
                usable = false;
                failed.run();
                return;
            }
            done.accept(chosen);
        });
    }

    /** Blocks: the file chosen, or null when the player cancels. */
    private static File blocking(File directory, FileNameExtensionFilter filter, boolean save, String name) {
        if (NFD_Init() != NFD_OKAY) {
            throw new IllegalStateException(NFD_GetError());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NFDFilterItem.Buffer filters = NFDFilterItem.malloc(1, stack);
            filters.get(0)
                  .name(stack.UTF8(filter.getDescription()))
                  .spec(stack.UTF8(String.join(",", filter.getExtensions())));
            PointerBuffer path = stack.mallocPointer(1);
            String start = directory != null && directory.isDirectory()
                  ? directory.toPath().toAbsolutePath().normalize().toString()
                  : null;
            int result = save ? NFD_SaveDialog(path, filters, start, name) : NFD_OpenDialog(path, filters, start);
            if (result == NFD_OKAY) {
                File file = new File(path.getStringUTF8(0));
                NFD_FreePath(path.get(0));
                return file;
            }
            if (result == NFD_CANCEL) {
                return null;
            }
            throw new IllegalStateException(NFD_GetError());
        } finally {
            NFD_Quit();
        }
    }
}
