/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.loaders.MekSummary;
import megamek.common.loaders.MekSummaryCache;

/**
 * The Unit cache page, under Tools: how many units MegaMek has read, how far a load has come, and Refresh and Rebuild,
 * which read the unit files again. It stands in for the Swing unit loading window and the old menu bar's cache items.
 */
final class StartUnitCachePage {
    /** The most of the bar a running load fills, as only the cache knows when it is done. */
    static final float MOST = 0.95f;
    /** The seconds over which a load without a known size fills about two thirds of the bar. */
    private static final double SETTLE_SECONDS = 6;

    private final GpuStartScreen screen;
    private UiKit.Meter meter;
    private Label sources;
    private UiButton refreshButton;
    private UiButton rebuildButton;
    /** The units the cache held when last seen ready, which a rebuild reads again; 0 before then. */
    private int known;
    /** The caption of the load this page asked for, or null for the load MegaMek starts by itself. */
    private String asked;
    /** When this page first saw the running load, in nanoseconds; 0 while none runs. */
    private long loadSeenAt;

    StartUnitCachePage(GpuStartScreen screen) {
        this.screen = screen;
    }

    /** Fills the column with the page and shows the cache as it is now. */
    void showUnitCache() {
        meter = screen.kit.meter(Messages.getString("StartScreen.unitCacheReading"), false, true);
        meter.setName("unit-cache-meter");
        sources = screen.line("", "small", "text.muted");
        sources.setName("unit-cache-sources");
        refreshButton = action("button-secondary", "StartScreen.unitCacheRefresh",
              "StartScreen.unitCacheRefreshDetail", () -> reread("StartScreen.unitCacheRefreshing", false));
        rebuildButton = action("button-quiet", "StartScreen.unitCacheRebuild", "StartScreen.unitCacheRebuildDetail",
              () -> reread("StartScreen.unitCacheRebuilding", true));
        update();
        screen.setColumn(List.of(screen.overline("StartScreen.unitCache"),
              screen.note(Messages.getString("StartScreen.unitCacheAbout"), "text.secondary"), meter, sources,
              refreshButton, rebuildButton, screen.backButton()));
    }

    private UiButton action(String style, String key, String detailKey, Runnable action) {
        UiButton button = screen.kit.button(style, null, Messages.getString(key), Messages.getString(detailKey));
        GpuStartScreen.leftAligned(button);
        UiKit.onChange(button, () -> {
            if (!button.isDisabled()) {
                action.run();
            }
        });
        return button;
    }

    /** Has the cache read the unit files again: the changed ones, or with {@code rebuild} every one. */
    private void reread(String captionKey, boolean rebuild) {
        asked = captionKey;
        loadSeenAt = 0;
        if (rebuild) {
            MekSummaryCache.rebuildUnitData(false);
        } else {
            MekSummaryCache.refreshUnitData(false);
        }
        update();
    }

    /** GPU thread, each frame the page shows: the cache's progress and whether it can be read again now. */
    void update() {
        if (meter == null) {
            return;
        }
        MekSummaryCache cache = MekSummaryCache.getInstance();
        int read = cache.getCacheCount() + cache.getFileCount() + cache.getZipCount();
        sources.setText(Messages.getString("StartScreen.unitCacheSources", cache.getCacheCount(),
              cache.getFileCount(), cache.getZipCount()));
        boolean loading = !cache.isInitialized();
        refreshButton.setDisabled(loading);
        rebuildButton.setDisabled(loading);
        if (loading) {
            if (loadSeenAt == 0) {
                loadSeenAt = System.nanoTime();
            }
            double seconds = (System.nanoTime() - loadSeenAt) / 1e9;
            meter.caption(Messages.getString(asked != null ? asked : "StartScreen.unitCacheReading"));
            meter.set(Messages.getString("StartScreen.unitCacheCount", read), fraction(read, known, seconds),
                  UiTheme.tokenColor("accent.primary"));
        } else {
            loadSeenAt = 0;
            asked = null;
            known = units(cache);
            meter.caption(Messages.getString("StartScreen.unitCacheReady"));
            meter.set(Messages.getString("StartScreen.unitCacheCount", known), 1,
                  UiTheme.tokenColor("accent.primary"));
        }
    }

    /** The Tools button's detail: the units ready, or that MegaMek is still reading them. */
    static String detail() {
        MekSummaryCache cache = MekSummaryCache.getInstance();
        return cache.isInitialized() ? Messages.getString("StartScreen.unitCacheCount", units(cache))
              : Messages.getString("StartScreen.unitCacheReading");
    }

    /** The units of a ready cache; none when its load failed. */
    private static int units(MekSummaryCache cache) {
        MekSummary[] units = cache.getAllMeks();
        return units == null ? 0 : units.length;
    }

    /**
     * The share of the bar a running load fills: the units read of the {@code known} it held before, when there is
     * such a count and the load reads them all, or else a share that grows with the {@code seconds} it has run. A
     * refresh reads only the changed files, so its time keeps the bar moving. It never reaches {@link #MOST}, as only
     * the cache knows when it is done.
     */
    static float fraction(int read, int known, double seconds) {
        float byTime = (float) (MOST * (1 - Math.exp(-Math.max(0, seconds) / SETTLE_SECONDS)));
        float byCount = known > 0 ? MOST * Math.min(1f, (float) read / known) : 0;
        return Math.min(MOST, Math.max(byTime, byCount));
    }
}
