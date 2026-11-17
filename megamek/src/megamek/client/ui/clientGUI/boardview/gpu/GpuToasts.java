/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.overlay.BoardToastOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.units.Entity;

/** EDT service for the toast stack: the client's newest toasts with their level and duration. */
final class GpuToasts {
    /** One toast; {@code icon} is null when it has none. */
    record Toast(long id, ToastLevel level, String text, BoardScene.Pixels icon, int durationMs) { }

    /** The newest toasts, oldest first: at most the Swing toast overlay's stack depth. */
    record Snapshot(List<Toast> toasts) {
        static final Snapshot EMPTY = new Snapshot(List.of());

        Snapshot {
            toasts = List.copyOf(toasts);
        }
    }

    /** A toast of the snapshot as {@link #add} tells repeats: its level, text and unit, and when it stops showing. */
    private record Shown(long id, ToastLevel level, String text, int entityId, long untilMs) { }

    private final GpuBoardSource source;
    private final LongSupplier clockMs;
    private final List<Shown> shown = new ArrayList<>();
    private Snapshot snapshot = Snapshot.EMPTY;
    private long lastId;

    GpuToasts(GpuBoardSource source) {
        this(source, System::currentTimeMillis);
    }

    GpuToasts(GpuBoardSource source, LongSupplier clockMs) {
        this.source = source;
        this.clockMs = clockMs;
    }

    /**
     * Any thread: keeps a toast that {@link ClientGUI#addToast} shows, so the player's toast setting already applies.
     * Keeps the newest {@link BoardToastOverlay#MAX_VISIBLE} toasts, each with its level's duration taken now (a new
     * toast pushes the oldest out of a full stack, whereas the Swing overlay starts fading the oldest and drops a toast
     * that arrives while five drawn ones are shown). The icon is the unit's upright tileset icon, kept only for a unit
     * the local player may identify. A toast that repeats one still showing (same level, text and unit), such as a
     * warning raised on every step of a drag, is dropped.
     */
    void add(ToastLevel level, String text, @Nullable Entity entity) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> add(level, text, entity));
            return;
        }
        long now = clockMs.getAsLong();
        int entityId = entity == null ? Entity.NONE : entity.getId();
        if (shown.stream().anyMatch(toast -> toast.untilMs() > now && toast.level() == level
              && toast.entityId() == entityId && toast.text().equals(text))) {
            return;
        }
        Image image = entity != null && source.identified(entity)
              ? source.currentView().getTilesetManager().iconFor(entity) : null;
        List<Toast> toasts = new ArrayList<>(snapshot.toasts());
        int durationMs = level.getDefaultDurationMs();
        toasts.add(new Toast(++lastId, level, text, image == null ? null : BoardScene.Pixels.copy(image),
              durationMs));
        snapshot = new Snapshot(toasts.subList(Math.max(0, toasts.size() - BoardToastOverlay.MAX_VISIBLE),
              toasts.size()));
        shown.add(new Shown(lastId, level, text, entityId, now + durationMs));
        Set<Long> ids = snapshot.toasts().stream().map(Toast::id).collect(Collectors.toSet());
        shown.removeIf(toast -> !ids.contains(toast.id()));
    }

    /** EDT: the newest toasts; the same instance until a toast is added. */
    Snapshot capture() {
        GpuBoardSource.requireSwingThread();
        return snapshot;
    }

    /** GL-safe: shows a toast through the client's one toast entry point, behind the source's input guard. */
    void post(ToastLevel level, String text) {
        source.command(() -> {
            ClientGUI gui = source.currentView().getClientgui();
            if (gui != null) {
                gui.addToast(level, text);
            }
        });
    }
}
