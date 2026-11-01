/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyFiles;
import megamek.logging.MMLogger;

/**
 * The open and save file dialogs of the GPU windows, the start screen's and the lobby's alike. Each system gets its own
 * where it can: on Windows LWJGL's Native File Dialog ({@link NativeFileChooser}), owned by the native window once it
 * appears so clicking the window can't bury it; on macOS and Linux AWT's {@link FileDialog}, which is the system's
 * (NSOpenPanel and NSSavePanel; GTK's chooser under AWT's X11 toolkit): AWT already runs AppKit's main thread on macOS
 * (the GPU window's loop runs on a thread of its own), and on Linux it shows GTK's chooser with the GTK AWT has loaded
 * rather than a second one. Swing's chooser on the given parent stays the fallback: on any other system, headless, on
 * Linux without AWT's X11 toolkit or with GTK's file dialogs turned off, once a system dialog has failed in this
 * session, and everywhere with {@code -Dmegamek.nativeFileDialog=false}. The window takes no input while any is open.
 */
final class SystemFileDialog {
    private static final MMLogger LOGGER = MMLogger.create(SystemFileDialog.class);
    private static final int OWN_DIALOG_POLL_MS = 50;
    private static final int OWN_DIALOG_TRIES = 100;
    /** The switch that turns the system's file dialog off: {@code -Dmegamek.nativeFileDialog=false}. */
    static final String SWITCH = "megamek.nativeFileDialog";
    /** AWT's own switch, under which its X11 toolkit draws a Motif-like dialog in place of GTK's. */
    static final String GTK_OFF = "sun.awt.disableGtkFileDialogs";
    static final String X11_TOOLKIT = "sun.awt.X11.XToolkit";

    /** Which dialog shows. */
    enum Kind {
        /** LWJGL's Native File Dialog (Windows). */
        NATIVE,
        /** AWT's FileDialog, which is the system's (macOS, Linux). */
        AWT,
        /** Swing's JFileChooser. */
        SWING
    }

    /**
     * The file the player picked; for a save, with the filter's extension, and {@code replaceAsked} when the dialog has
     * already had them confirm replacing it.
     */
    record Choice(File file, boolean replaceAsked) {
        /** Whether the caller should still ask before replacing the file. */
        boolean askReplace() {
            return !replaceAsked && file.exists();
        }
    }

    /**
     * What a dialog asks: where it starts, its title, the files it shows, whether it saves and, for a save, the file
     * name it suggests, or null.
     */
    record Request(@Nullable File directory, String title, FileNameExtensionFilter filter, boolean save,
          @Nullable String name) {
        Request(@Nullable File directory, String title, FileNameExtensionFilter filter, boolean save) {
            this(directory, title, filter, save, null);
        }

        /** The dialog a lobby file route asks for. */
        static Request of(LobbyFiles.Ask ask) {
            return new Request(ask.directory(), ask.title(),
                  new FileNameExtensionFilter(ask.filterName(), ask.extensions().toArray(String[]::new)), ask.save(),
                  ask.name());
        }
    }

    /** Stands in for the dialogs while set: the smoke tests answer the requests with their own files. */
    private static volatile @Nullable BiConsumer<Request, Consumer<File>> fake;
    /** Turns false for the session once AWT's file dialog fails to show. */
    private static volatile boolean awtUsable = true;

    private SystemFileDialog() {
    }

    /** The dialog to show on this system now. */
    static Kind kind() {
        if (GraphicsEnvironment.isHeadless()) {
            return Kind.SWING;
        }
        return kind(SharedLibraryLoader.os, System.getProperty(SWITCH), Toolkit.getDefaultToolkit().getClass().getName(),
              System.getProperty(GTK_OFF), NativeFileChooser.usable(), awtUsable);
    }

    /**
     * The dialog to show on {@code os}, given the switch's value, the AWT toolkit's class name, AWT's GTK switch, and
     * whether each system dialog still works in this session.
     */
    static Kind kind(Os os, @Nullable String switchValue, @Nullable String toolkit, @Nullable String gtkOff,
          boolean nativeUsable, boolean awtWorks) {
        if (switchValue != null && "false".equalsIgnoreCase(switchValue.trim())) {
            return Kind.SWING;
        }
        return switch (os) {
            case Windows -> nativeUsable ? Kind.NATIVE : Kind.SWING;
            case MacOsX -> awtWorks ? Kind.AWT : Kind.SWING;
            case Linux -> awtWorks && X11_TOOLKIT.equals(toolkit) && !"true".equalsIgnoreCase(gtkOff)
                  ? Kind.AWT : Kind.SWING;
            default -> Kind.SWING;
        };
    }

    /**
     * Whether AWT's save dialog on {@code os} has already had the player confirm replacing a file: macOS's save panel
     * always asks. GTK's does too, but AWT may draw its own dialog in its place, so on Linux the caller still asks.
     */
    static boolean awtAsksReplace(Os os) {
        return os == Os.MacOsX;
    }

    /** For the smoke tests: answers every request with {@code answer} instead of a dialog; null shows dialogs again. */
    static void fake(@Nullable BiConsumer<Request, Consumer<File>> answer) {
        fake = answer;
    }

    /**
     * Any thread: shows the dialog for {@code request}. {@code busy} runs on the Swing thread with true as the system
     * dialog opens and false as it closes; {@code chosen} gets the player's choice on the Swing thread, null when they
     * cancel.
     */
    static void show(Request request, Supplier<GpuNativeDialogOwner> owner, Supplier<Component> parent,
          Consumer<Boolean> busy, Consumer<Choice> chosen) {
        BiConsumer<Request, Consumer<File>> answer = fake;
        if (answer != null) {
            answer.accept(request, file -> chosen.accept(file == null ? null : choice(request, file, true)));
            return;
        }
        Kind kind = kind();
        if (kind == Kind.AWT) {
            SwingUtilities.invokeLater(() -> awt(request, parent.get(), busy, chosen));
            return;
        }
        if (kind == Kind.SWING) {
            SwingUtilities.invokeLater(() -> swing(request, parent.get(), chosen));
            return;
        }
        AtomicBoolean showing = new AtomicBoolean(true);
        SwingUtilities.invokeLater(() -> {
            busy.accept(true);
            own(owner.get(), showing);
        });
        NativeFileChooser.show(request.directory(), request.filter(), request.save(), request.name(),
              file -> SwingUtilities.invokeLater(() -> {
                  showing.set(false);
                  busy.accept(false);
                  chosen.accept(file == null ? null : choice(request, file, true));
              }), () -> SwingUtilities.invokeLater(() -> {
                  showing.set(false);
                  busy.accept(false);
                  swing(request, parent.get(), chosen);
              }));
    }

    /** {@code file} with the filter's first extension when it has none of the filter's. */
    static File withExtension(File file, FileNameExtensionFilter filter) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String extension : filter.getExtensions()) {
            if (name.endsWith('.' + extension.toLowerCase(Locale.ROOT))) {
                return file;
            }
        }
        return new File(file.getPath() + '.' + filter.getExtensions()[0]);
    }

    private static Choice choice(Request request, File file, boolean dialogAsked) {
        if (!request.save()) {
            return new Choice(file, false);
        }
        File saved = withExtension(file, request.filter());
        return new Choice(saved, dialogAsked && saved.equals(file));
    }

    /**
     * Swing thread: the system's dialog opens without an owner, so clicking the window could bury it; once it shows,
     * the window becomes its owner, which keeps it in front the way a Swing form is. Gives up after a few seconds,
     * leaving it ownerless.
     */
    private static void own(@Nullable GpuNativeDialogOwner nativeOwner, AtomicBoolean showing) {
        if (!(nativeOwner instanceof GpuNativeDialogOwner.Win32 owner)) {
            return;
        }
        int[] tries = { 0 };
        Timer poll = new Timer(OWN_DIALOG_POLL_MS, null);
        poll.addActionListener(event -> {
            long dialog = showing.get() ? GpuNativeDialogOwner.Win32.ownerlessSystemDialog() : 0;
            if (!showing.get() || ++tries[0] > OWN_DIALOG_TRIES || (dialog != 0 && owner.own(dialog))) {
                poll.stop();
            }
        });
        poll.start();
    }

    /**
     * Swing thread: the system's file dialog through AWT (macOS, Linux). It blocks until closed while AWT keeps
     * dispatching events, and the GPU window keeps drawing on its own thread. Where it fails to show, Swing's chooser
     * shows instead, now and for the rest of the session.
     */
    private static void awt(Request request, @Nullable Component parent, Consumer<Boolean> busy,
          Consumer<Choice> chosen) {
        File file = null;
        boolean failed = false;
        busy.accept(true);
        FileDialog dialog = null;
        try {
            dialog = fileDialog(parent, request.title(), request.save() ? FileDialog.SAVE : FileDialog.LOAD);
            File directory = request.directory();
            if (directory != null && directory.isDirectory()) {
                dialog.setDirectory(directory.getAbsolutePath());
            }
            if (request.save() && request.name() != null) {
                dialog.setFile(request.name());
            }
            FileNameExtensionFilter filter = request.filter();
            dialog.setFilenameFilter((folder, name) -> filter.accept(new File(folder, name)));
            dialog.setVisible(true);
            String name = dialog.getFile();
            if (name != null) {
                file = new File(dialog.getDirectory(), name);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("The system file dialog couldn't show; using Swing's from now on: {}", failure.toString());
            awtUsable = false;
            failed = true;
        } finally {
            if (dialog != null) {
                dialog.dispose();
            }
            busy.accept(false);
        }
        if (failed) {
            swing(request, parent, chosen);
        } else {
            chosen.accept(file == null ? null : choice(request, file, awtAsksReplace(SharedLibraryLoader.os)));
        }
    }

    /** An AWT file dialog owned by {@code parent}'s window, or by none. */
    private static FileDialog fileDialog(@Nullable Component parent, String title, int mode) {
        Window window = parent == null || parent instanceof Window ? (Window) parent
              : SwingUtilities.getWindowAncestor(parent);
        if (window instanceof Dialog owner) {
            return new FileDialog(owner, title, mode);
        }
        return new FileDialog(window instanceof Frame owner ? owner : null, title, mode);
    }

    private static void swing(Request request, @Nullable Component parent, Consumer<Choice> chosen) {
        JFileChooser chooser = new JFileChooser(request.directory());
        chooser.setDialogTitle(request.title());
        chooser.setFileFilter(request.filter());
        if (request.save() && request.name() != null) {
            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), request.name()));
        }
        int result = request.save() ? chooser.showSaveDialog(parent) : chooser.showOpenDialog(parent);
        boolean approved = result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null;
        chosen.accept(approved ? choice(request, chooser.getSelectedFile(), false) : null);
    }
}
