/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/** The same terrain tiles, worker tasks and shader progress in a board window or an embedded preview. */
final class GpuLoadingPanel extends Table {
    private final Label message;
    private final Label details;
    private final GpuLoadingGrid grid;
    private final Label waiting, active, ready;
    private final UiKit.Progress shaders;

    GpuLoadingPanel(Skin skin, String text) {
        setName("board-loading-panel");
        setTouchable(Touchable.disabled);
        Value edge = Value.percentHeight(.06f, this);
        pad(edge, Value.zero, edge, Value.zero);
        message = new Label(text, skin, "hud-phase");
        message.setName("board-loading-message");
        message.setWrap(true);
        message.setAlignment(Align.center);
        details = new Label("", skin, "hud-title");
        details.setName("board-loading-details");
        details.setWrap(true);
        details.setAlignment(Align.top | Align.center);
        grid = new GpuLoadingGrid(skin);
        Table legend = new Table();
        waiting = new Label("", skin, "hud-body");
        active = new Label("", skin, "hud-body");
        ready = new Label("", skin, "hud-body");
        waiting.setName("board-loading-waiting");
        active.setName("board-loading-active");
        ready.setName("board-loading-ready");
        waiting.setColor(UiTheme.MUTED);
        active.setColor(GpuLoadingGrid.LOADING);
        ready.setColor(GpuLoadingGrid.READY);
        for (Label entry : List.of(waiting, active, ready)) {
            legend.add(entry).padLeft(12).padRight(12);
        }
        // Keep the row when hidden, so tasks never jump as programs finish linking.
        shaders = new UiKit(skin).progress(Messages.getString("GpuBoard.loadingShaders"));
        shaders.setName("board-loading-shaders");
        shaders.setVisible(false);
        add(new Label("MEGAMEK", skin, "hud-small")).padBottom(12).row();
        add(message).width(Value.percentWidth(.9f, this)).maxWidth(900)
              .height(message.getStyle().font.getLineHeight() * 2).row();
        if (GpuGlsl.intelCacheFull()) {
            var notice = new Label(Messages.getString("GpuBoard.intelShaderCacheFull", GpuProgramCache.INTEL_CACHE),
                  skin, "hud-body");
            notice.setName("board-loading-notice");
            notice.setWrap(true);
            notice.setAlignment(Align.center);
            notice.setColor(UiTheme.AMBER);
            add(notice).width(Value.percentWidth(.9f, this)).maxWidth(900).padTop(6).row();
        }
        add(grid).width(Value.percentWidth(.9f, this)).growY().padTop(14).row();
        add(legend).padTop(10).row();
        add(shaders).width(Value.percentWidth(.9f, this)).maxWidth(420).padTop(14).row();
        add(details).width(Value.percentWidth(.9f, this)).maxWidth(900)
              .height(details.getStyle().font.getLineHeight() * GpuTerrain.BUILD_JOBS).padTop(14);
    }

    void message(String text) { message.setText(text); }

    void reset(String text) {
        message(text);
        details.setText("");
        grid.update(TerrainLoadProgress.Sections.EMPTY);
        waiting.setText("");
        active.setText("");
        ready.setText("");
        shaders.setVisible(false);
    }

    void update(GpuTerrain terrain) {
        // A finished build still installing (-1) is complete, not 0%.
        int built = terrain.buildProgress();
        message(Messages.getString("GpuBoard.loadingOverall", built < 0 ? 100 : built));
        var sections = terrain.buildSections();
        grid.update(sections);
        waiting.setText(Messages.getString("GpuBoard.loadingLegend.waiting",
              Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.WAITING)));
        active.setText(Messages.getString("GpuBoard.loadingLegend.active",
              Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.LOADING)));
        ready.setText(Messages.getString("GpuBoard.loadingLegend.ready",
              Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.READY), sections.states().size()));
        showShaders(shaders, terrain.shaderProgress());
        details.setText(terrain.buildDetails().stream().map(TerrainLoadProgress.Status::text)
              .collect(Collectors.joining("\n")));
    }

    static void showShaders(UiKit.Progress bar, TerrainLoadProgress.Shaders progress) {
        bar.setVisible(progress.linked() < progress.total());
        if (bar.isVisible()) { bar.set((float) progress.linked() / progress.total()); }
    }
}
