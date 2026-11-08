/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.HexBrush;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTextField;

/**
 * The Game Master's terrain brush, the native counterpart of the Swing {@code HexEditDialog}: a panel at the battle's
 * top right edge, beside the board rather than over it, so that clicking the board paints hexes while it shows. It
 * holds what the brush paints, the switches that turn board clicks into painting or erasing, the hexes painted so far,
 * whether the edit is legal, and Apply, Undo and Close. Unlike a card it is not modal: only the panel itself takes
 * clicks.
 * <p>
 * GPU thread.
 */
final class GpuHexBrushPanel {
    /** What the panel asks of the battle, on the GPU thread. */
    interface Host {
        /** Turns board clicks into painting, or hands them back to the phase. */
        void painting(boolean on);

        /** The panel closed; the brush lets go of the board. */
        void closed();
    }

    private final UiKit kit;
    private final HexBrush brush;
    private final Host host;
    private final Table root = new Table();
    private final Table content = new Table();
    private final Label ground;
    private final Label legality;
    private final Table painted = new Table();
    private UiButton apply;
    private UiButton undo;
    private UiButton brushAll;
    private UiButton undoLast;
    private UiButton clear;
    private boolean paintingOn = true;
    private boolean open = true;

    GpuHexBrushPanel(UiKit kit, Stage stage, HexBrush brush, Host host) {
        this.kit = kit;
        this.brush = brush;
        this.host = host;
        ground = text("small", "text.secondary");
        ground.setName("brush-ground");
        legality = text("body", "text.primary");
        legality.setName("brush-legality");
        root.setFillParent(true);
        root.setTouchable(Touchable.childrenOnly);
        root.top().right();
        root.setName("hex-brush");
        Table card = kit.card();
        card.setTouchable(Touchable.enabled);
        ScrollPane scroll = kit.scrollList(content, true, false);
        scroll.setFadeScrollBars(false);
        card.add(kit.typeLabel(Messages.getString("HexEditDialog.title"), "title", "text.primary")).left().growX()
              .row();
        card.add(scroll).grow().padTop(space("sm"));
        float width = DesignTokens.shipped().size().get("dialog.sm");
        float margin = space("lg");
        root.add(card).width(width).maxHeight(Math.max(width, stage.getHeight() - 2 * margin)).pad(margin);
        fill();
        stage.addActor(root);
        host.painting(true);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private Label text(String type, String role) {
        Label label = kit.typeLabel("", type, role);
        label.setWrap(true);
        return label;
    }

    private void tip(Actor actor, String key) {
        kit.tip(actor, true).getActor().setText(GpuBoardWindow.plainText(Messages.getString(key)));
    }

    private Label caption(String key) {
        return kit.typeLabel(Messages.getString(key), "body.strong", "text.primary");
    }

    private static String label(String key) {
        String trimmed = Messages.getString(key).trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** Builds the panel again from the brush, as choosing a terrain changes which controls it has. */
    private void fill() {
        content.clearChildren();
        content.top().left().defaults().left().growX();

        content.add(caption("HexEditDialog.brush")).row();
        UiSelect size = kit.tokenSelect(true, HexBrush.sizeNames().toArray(String[]::new));
        size.setName("brush-size");
        size.select(brush.sevenHexes() ? 1 : 0);
        tip(size, "HexEditDialog.brushSize.tooltip");
        UiKit.onChange(size, () -> {
            brush.sevenHexes(size.getSelectedIndex() == 1);
            update();
        });
        content.add(kit.formField(label("HexEditDialog.brushSizeLabel"), size)).padTop(space("xs")).row();

        UiSelect terrain = kit.tokenSelect(true, HexBrush.terrainNames().toArray(String[]::new));
        terrain.setName("brush-terrain");
        terrain.select(brush.terrainIndex());
        UiKit.onChange(terrain, () -> {
            brush.terrainIndex(terrain.getSelectedIndex());
            fill();
        });
        content.add(kit.formField(label("HexEditDialog.terrainLabel"), terrain)).padTop(space("xs")).row();

        List<String> levels = brush.levelNames();
        if (!levels.isEmpty()) {
            UiSelect level = kit.tokenSelect(true, levels.toArray(String[]::new));
            level.setName("brush-level");
            level.select(Math.max(0, brush.levelIndex()));
            UiKit.onChange(level, () -> {
                brush.levelIndex(level.getSelectedIndex());
                fill();
            });
            content.add(kit.formField(label("HexEditDialog.levelLabel"), level)).padTop(space("xs")).row();
        }
        String book = brush.bookFactorText();
        if (book != null) {
            UiTextField factor = LobbyGameMasterViews.number(kit, "brush-factor", brush.factor(), 0,
                  brush::factorMax, brush::factor, this::update);
            factor.setDisabled(!brush.factorEnabled());
            tip(factor, "HexEditDialog.terrainFactor.tooltip");
            content.add(kit.formField(label("HexEditDialog.terrainFactorLabel"), factor)).padTop(space("xs"))
                  .row();
            content.add(kit.typeLabel(book, "small", "text.muted")).row();
        }

        UiChoice changeGround = kit.check(Messages.getString("HexEditDialog.changeGround"), brush.changeGround());
        changeGround.setName("brush-change-ground");
        tip(changeGround, "HexEditDialog.changeGround.tooltip");
        UiStepper groundLevel = kit.stepper(brush.ground(), HexBrush.LOWEST_GROUND, HexBrush.HIGHEST_GROUND);
        groundLevel.setName("brush-ground-level");
        groundLevel.up().setName("brush-ground-up");
        groundLevel.down().setName("brush-ground-down");
        UiFormField groundField = kit.formField(label("HexEditDialog.hexLevel"), groundLevel);
        groundField.setVisible(brush.changeGround());
        UiKit.onChange(changeGround, () -> {
            brush.changeGround(changeGround.isChecked());
            groundField.setVisible(changeGround.isChecked());
            update();
        });
        UiKit.onChange(groundLevel, () -> {
            brush.ground(groundLevel.value());
            update();
        });
        content.add(changeGround).padTop(space("sm")).row();
        content.add(groundField).padTop(space("xs")).row();
        content.add(ground).padTop(space("xs")).row();

        UiChoice paint = kit.toggleSwitch(Messages.getString("HexEditDialog.paintOnMap"), paintingOn);
        paint.setName("brush-paint");
        tip(paint, "HexEditDialog.paintOnMap.tooltip");
        UiKit.onChange(paint, () -> {
            paintingOn = paint.isChecked();
            host.painting(paintingOn);
        });
        UiChoice erase = kit.toggleSwitch(Messages.getString("HexEditDialog.erase"), brush.erasing());
        erase.setName("brush-erase");
        tip(erase, "HexEditDialog.erase.tooltip");
        UiKit.onChange(erase, () -> brush.erasing(erase.isChecked()));
        brushAll = button("button-secondary-compact", "HexEditDialog.brushToAll", "brush-all", () -> {
            brush.brushAll();
            update();
        });
        tip(brushAll, "HexEditDialog.brushToAll.tooltip");
        content.add(paint).padTop(space("md")).row();
        content.add(erase).padTop(space("xs")).row();
        content.add(brushAll).padTop(space("xs")).fill(false, false).row();

        content.add(caption("HexEditDialog.painted")).padTop(space("md")).row();
        content.add(painted).padTop(space("xs")).row();
        undoLast = button("button-secondary-compact", "HexEditDialog.undoLastHex", "brush-undo-last", () -> {
            brush.undoLastHex();
            update();
        });
        clear = button("button-secondary-compact", "HexEditDialog.clearHexes", "brush-clear", () -> {
            brush.clear();
            update();
        });
        tip(clear, "HexEditDialog.clearHexes.tooltip");
        Table listButtons = new Table();
        listButtons.add(undoLast).padRight(space("xs"));
        listButtons.add(clear);
        content.add(listButtons).padTop(space("xs")).fill(false, false).row();
        content.add(legality).padTop(space("md")).row();

        apply = button("button-primary", "HexEditDialog.execute", "brush-apply", () -> {
            brush.apply();
            update();
        });
        undo = button("button-secondary", "HexEditDialog.undo", "brush-undo", () -> {
            brush.undo();
            update();
        });
        tip(undo, "HexEditDialog.undo.tooltip");
        UiButton close = button("button-quiet", "HexEditDialog.close", "brush-close", this::close);
        Table footer = new Table();
        footer.right();
        footer.add(close).padRight(space("sm"));
        footer.add(undo).padRight(space("sm"));
        footer.add(apply);
        content.add(footer).padTop(space("md")).right().row();
        update();
    }

    private UiButton button(String style, String key, String name, Runnable action) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        button.setName(name);
        UiKit.onChange(button, action);
        return button;
    }

    /** Shows the brush's state again: the ground line, the painted hexes, whether the edit is legal, the buttons. */
    void update() {
        ground.setText(GpuBoardWindow.plainText(brush.groundText()));
        painted.clearChildren();
        painted.top().left().defaults().left().growX();
        int row = 0;
        for (String entry : brush.paintedEntries()) {
            Label line = kit.typeLabel(entry, "small", "text.secondary");
            line.setName("brush-painted-" + row++);
            line.setEllipsis(true);
            painted.add(line).minWidth(0).row();
        }
        legality.setText(GpuBoardWindow.plainText(brush.legality()));
        boolean any = !brush.painted().isEmpty();
        apply.setDisabled(!brush.mayApply());
        undo.setDisabled(!brush.mayUndo());
        brushAll.setDisabled(!any);
        undoLast.setDisabled(!any);
        clear.setDisabled(!any);
    }

    boolean isOpen() {
        return open;
    }

    /** Takes the panel away and lets go of the board, as Close does. */
    void close() {
        if (!open) {
            return;
        }
        open = false;
        root.remove();
        if (paintingOn) {
            host.painting(false);
        }
        host.closed();
    }

    // ---- For the tests ----

    Table root() {
        return root;
    }

    boolean painting() {
        return paintingOn;
    }
}
