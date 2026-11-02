/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.client.ui.clientGUI.boardview.RulerModel;

/**
 * The per-frame HUD panel bundle: one immutable snapshot from each EDT service. The source keeps the previous
 * instance while every part is equal, so the render thread can compare bundles and parts by identity.
 */
record GpuHudData(GpuBoardActions.PhaseInfo phase, GpuMovePlan.Snapshot move, GpuFireOrders.Snapshot fire,
      GpuPhysicalOptions.Snapshot physical, GpuUnitRecord.Snapshot record, GpuFirePreview.Snapshot preview,
      GpuChat.Snapshot chat, GpuToasts.Snapshot toasts, RulerModel.Snapshot los, GpuPlayers.Snapshot players,
      GpuUnitRecord.Snapshot ownRecord) {
    static final GpuHudData EMPTY = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
          GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
          GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE,
          GpuPlayers.Snapshot.EMPTY);

    /** A frame with just one unit record, shared by the own card when it is that unit. */
    GpuHudData(GpuBoardActions.PhaseInfo phase, GpuMovePlan.Snapshot move, GpuFireOrders.Snapshot fire,
          GpuPhysicalOptions.Snapshot physical, GpuUnitRecord.Snapshot record, GpuFirePreview.Snapshot preview,
          GpuChat.Snapshot chat, GpuToasts.Snapshot toasts, RulerModel.Snapshot los, GpuPlayers.Snapshot players) {
        this(phase, move, fire, physical, record, preview, chat, toasts, los, players, record);
    }

    GpuHudData withLos(RulerModel.Snapshot value) {
        return new GpuHudData(phase, move, fire, physical, record, preview, chat, toasts, value, players, ownRecord);
    }
}
