/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Component;
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

import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyFiles;

/**
 * The open and save file dialogs of the GPU windows: the system's own ({@link NativeFileChooser}) where it can show,
 * owned by the native window once it appears so clicking the window can't bury it, else Swing's chooser on the given
 * parent. The window takes no input while either is open.
 */
final class SystemFileDialog {
    private static final int OWN_DIALOG_POLL_MS = 50;
    private static final int OWN_DIALOG_TRIES = 100;

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

    private SystemFileDialog() {
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
        if (!NativeFileChooser.usable()) {
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
