/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * Planetary conditions beside the forces (C.1 G1, plan B11), shown while the View menu's planetary conditions
 * preference is on. Collapsed it is a one-line strip of only the conditions that differ from the standard ones
 * ("Normal conditions" without any); a click expands it to the lines of MegaMek's planetary conditions overlay, and a
 * click on that card's header collapses it again. The close button runs the View-menu item.
 */
final class GpuConditionsCard implements GpuHud.Component {
    private static final String FONT = "hud-body";
    private static final String SEPARATOR = " \u00B7 ";

    private final UiKit ui;
    private final GpuHudState state;
    private final Table root;
    private final Cell<Table> content;
    private final Table strip = new Table();
    private final Label summary;
    private final Table full = new Table();
    private final Table lines = new Table();
    private final UiButton close;
    private List<String> shown;
    /** The View-menu item's command of the current frame, or null when it is missing or disabled. */
    private Runnable toggle;

    GpuConditionsCard(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.state = state;
        root = ui.panel();
        root.setName("conditions-card");

        strip.setName("conditions-strip");
        strip.pad(8, 14, 8, 10);
        summary = ui.label("", FONT, 12.5f, UiTheme.ACCENT);
        summary.setEllipsis(true);
        summary.setName("conditions-summary");
        strip.add(summary).growX().minWidth(0).left();
        strip.add(ui.icon("chevron-down", 16, UiTheme.MUTED)).padLeft(8);
        strip.setTouchable(Touchable.enabled);
        ui.tip(strip).getActor().setText(Messages.getString("GpuBoard.hud.conditions.expandTip"));
        strip.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                state.conditionsExpanded = true;
            }
        });

        close = ui.closeButton(() -> {
            if (toggle != null) {
                toggle.run();
            }
        });
        close.setName("conditions-close");
        Table header = ui.header(Messages.getString("GpuBoard.hud.conditions.title"), null,
              ui.icon("chevron-up", 16, UiTheme.MUTED), close);
        header.setName("conditions-header");
        header.setTouchable(Touchable.enabled);
        ui.tip(header).getActor().setText(Messages.getString("GpuBoard.hud.conditions.collapseTip"));
        header.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!close.isAscendantOf(event.getTarget())) {
                    state.conditionsExpanded = false;
                }
            }
        });
        full.add(header).growX().row();
        lines.top().left();
        lines.defaults().left().growX().minWidth(0);
        full.add(lines).growX().pad(0, 14, 12, 14);
        content = root.add(strip).growX();
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The strip's width for its summary, at most {@code room}. */
    float stripWidth(float room) {
        return Math.min(room, root.getPadLeft() + root.getPadRight() + strip.getPrefWidth());
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        root.setVisible(inputs.preferences().conditionsVisible());
        GpuBoardActions.PhaseInfo phase = inputs.frame().panels().phase();
        List<String> notable = phase.notable();
        summary.setText(notable.isEmpty() ? Messages.getString("GpuBoard.hud.conditions.normal")
              : String.join(SEPARATOR, notable));
        List<String> next = phase.conditions();
        if (!next.equals(shown)) {
            shown = next;
            lines.clearChildren();
            BitmapFont font = ui.skin.getFont(FONT);
            for (String line : next) {
                String text = drawable(line, font);
                // A line of indicators only (labels and values off) may have nothing left to draw.
                if (!text.isEmpty()) {
                    Label label = ui.label(text, FONT, 12.5f, UiTheme.ACCENT);
                    label.setEllipsis(true);
                    // CSS line-height 1.35
                    lines.add(label).height(17).row();
                }
            }
        }
        Table wanted = state.conditionsExpanded ? full : strip;
        if (content.getActor() != wanted) {
            content.setActor(wanted);
        }
        BoardScene.Command command = GpuBoardActions.menuItem(inputs.frame().globalCommands(),
              ClientGUI.VIEW_PLANETARY_CONDITIONS_OVERLAY);
        toggle = command == null || !command.enabled() ? null : command.action();
        close.setDisabled(toggle == null);
    }

    /**
     * The line as {@code font} can draw it. The indicator characters that no face of the skin's font chain has are
     * left out instead of drawing as blanks: the emoji outside the Basic Multilingual Plane (a BitmapFont keys its
     * glyphs by UTF-16 char; the thermometer and flame of the temperature, the eye and wind face of fog and blowing
     * sand) and such symbols as the fog's and pitch black's full block, the hail's shade or the calm wind's flag.
     */
    private static String drawable(String line, BitmapFont font) {
        BitmapFont.BitmapFontData data = font.getData();
        StringBuilder text = new StringBuilder();
        line.codePoints().filter(point -> Character.isWhitespace(point)
              || (Character.isBmpCodePoint(point) && hasGlyph(data, (char) point))).forEach(text::appendCodePoint);
        return text.toString().strip();
    }

    private static boolean hasGlyph(BitmapFont.BitmapFontData data, char character) {
        BitmapFont.Glyph glyph = data.getGlyph(character);
        return glyph != null && glyph != data.missingGlyph;
    }
}
