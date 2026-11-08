/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
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
 * GPU thread. The {@link HexBrush} itself lives on the Swing thread, as it reads the board: the panel draws only the
 * latest {@link HexBrush.View} it was given and sends each change the Game Master makes to the Swing thread through
 * its {@link Host}, which answers with a new view.
 */
final class GpuHexBrushPanel {
    /** What the panel asks of the battle, on the GPU thread. */
    interface Host {
        /** Turns board clicks into painting, or hands them back to the phase. */
        void painting(boolean on);

        /**
         * Changes the brush on the Swing thread, then hands {@code then} the brush's view on the GPU thread; null when
         * the change could not be made.
         */
        void edit(Consumer<HexBrush> change, Consumer<HexBrush.View> then);

        /** The panel closed; the brush lets go of the board. */
        void closed();
    }

    private final UiKit kit;
    private final Host host;
    private HexBrush.View shown;
    /** The changes sent to the brush whose view has not come back yet. */
    private int pending;
    private UiSelect size;
    private UiChoice changeGround;
    private UiStepper groundLevel;
    private UiFormField groundField;
    private UiChoice erase;
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

    /** @param view the brush as it opened */
    GpuHexBrushPanel(UiKit kit, Stage stage, HexBrush.View view, Host host) {
        this.kit = kit;
        this.host = host;
        shown = view;
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

    /** Has the brush changed on the Swing thread; the view it answers with is shown once no other change is out. */
    private void edit(Consumer<HexBrush> change) {
        pending++;
        host.edit(change, this::edited);
    }

    private void edited(@Nullable HexBrush.View view) {
        pending = Math.max(0, pending - 1);
        if (view != null) {
            show(view);
        }
    }

    /**
     * Shows the brush as {@code view} has it, unless a change sent from the panel has yet to come back: that change's
     * view is newer, and showing this one meanwhile would set back a control the Game Master is still working.
     */
    void show(HexBrush.View view) {
        if (!open || pending > 0) {
            return;
        }
        boolean otherControls = view.terrainIndex() != shown.terrainIndex()
              || !view.levelNames().equals(shown.levelNames()) || view.levelIndex() != shown.levelIndex()
              || !Objects.equals(view.bookFactorText(), shown.bookFactorText())
              || view.factorEnabled() != shown.factorEnabled() || view.factorMax() != shown.factorMax();
        shown = view;
        if (otherControls) {
            fill();
        } else {
            size.select(view.sevenHexes() ? 1 : 0);
            changeGround.setChecked(view.changeGround());
            groundField.setVisible(view.changeGround());
            groundLevel.value(view.ground());
            erase.setChecked(view.erasing());
            update();
        }
    }

    /** Builds the panel again from the brush's view, as choosing a terrain changes which controls it has. */
    private void fill() {
        content.clearChildren();
        content.top().left().defaults().left().growX();

        content.add(caption("HexEditDialog.brush")).row();
        size = kit.tokenSelect(true, HexBrush.sizeNames().toArray(String[]::new));
        size.setName("brush-size");
        size.select(shown.sevenHexes() ? 1 : 0);
        tip(size, "HexEditDialog.brushSize.tooltip");
        UiKit.onChange(size, () -> {
            boolean seven = size.getSelectedIndex() == 1;
            edit(brush -> brush.sevenHexes(seven));
        });
        content.add(kit.formField(label("HexEditDialog.brushSizeLabel"), size)).padTop(space("xs")).row();

        UiSelect terrain = kit.tokenSelect(true, HexBrush.terrainNames().toArray(String[]::new));
        terrain.setName("brush-terrain");
        terrain.select(shown.terrainIndex());
        UiKit.onChange(terrain, () -> {
            int index = terrain.getSelectedIndex();
            edit(brush -> brush.terrainIndex(index));
        });
        content.add(kit.formField(label("HexEditDialog.terrainLabel"), terrain)).padTop(space("xs")).row();

        List<String> levels = shown.levelNames();
        if (!levels.isEmpty()) {
            UiSelect level = kit.tokenSelect(true, levels.toArray(String[]::new));
            level.setName("brush-level");
            level.select(Math.max(0, shown.levelIndex()));
            UiKit.onChange(level, () -> {
                int index = level.getSelectedIndex();
                edit(brush -> brush.levelIndex(index));
            });
            content.add(kit.formField(label("HexEditDialog.levelLabel"), level)).padTop(space("xs")).row();
        }
        String book = shown.bookFactorText();
        if (book != null) {
            UiTextField factor = LobbyGameMasterViews.number(kit, "brush-factor", shown.factor(), 0,
                  () -> shown.factorMax(), value -> edit(brush -> brush.factor(value)), () -> { });
            factor.setDisabled(!shown.factorEnabled());
            tip(factor, "HexEditDialog.terrainFactor.tooltip");
            content.add(kit.formField(label("HexEditDialog.terrainFactorLabel"), factor)).padTop(space("xs"))
                  .row();
            content.add(kit.typeLabel(book, "small", "text.muted")).row();
        }

        changeGround = kit.check(Messages.getString("HexEditDialog.changeGround"), shown.changeGround());
        changeGround.setName("brush-change-ground");
        tip(changeGround, "HexEditDialog.changeGround.tooltip");
        groundLevel = kit.stepper(shown.ground(), HexBrush.LOWEST_GROUND, HexBrush.HIGHEST_GROUND);
        groundLevel.setName("brush-ground-level");
        groundLevel.up().setName("brush-ground-up");
        groundLevel.down().setName("brush-ground-down");
        groundField = kit.formField(label("HexEditDialog.hexLevel"), groundLevel);
        groundField.setVisible(shown.changeGround());
        UiKit.onChange(changeGround, () -> {
            boolean checked = changeGround.isChecked();
            groundField.setVisible(checked);
            edit(brush -> brush.changeGround(checked));
        });
        UiKit.onChange(groundLevel, () -> {
            int value = groundLevel.value();
            edit(brush -> brush.ground(value));
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
        erase = kit.toggleSwitch(Messages.getString("HexEditDialog.erase"), shown.erasing());
        erase.setName("brush-erase");
        tip(erase, "HexEditDialog.erase.tooltip");
        UiKit.onChange(erase, () -> {
            boolean erasing = erase.isChecked();
            edit(brush -> brush.erasing(erasing));
        });
        brushAll = button("button-secondary-compact", "HexEditDialog.brushToAll", "brush-all",
              () -> edit(HexBrush::brushAll));
        tip(brushAll, "HexEditDialog.brushToAll.tooltip");
        content.add(paint).padTop(space("md")).row();
        content.add(erase).padTop(space("xs")).row();
        content.add(brushAll).padTop(space("xs")).fill(false, false).row();

        content.add(caption("HexEditDialog.painted")).padTop(space("md")).row();
        content.add(painted).padTop(space("xs")).row();
        undoLast = button("button-secondary-compact", "HexEditDialog.undoLastHex", "brush-undo-last",
              () -> edit(HexBrush::undoLastHex));
        clear = button("button-secondary-compact", "HexEditDialog.clearHexes", "brush-clear",
              () -> edit(HexBrush::clear));
        tip(clear, "HexEditDialog.clearHexes.tooltip");
        Table listButtons = new Table();
        listButtons.add(undoLast).padRight(space("xs"));
        listButtons.add(clear);
        content.add(listButtons).padTop(space("xs")).fill(false, false).row();
        content.add(legality).padTop(space("md")).row();

        apply = button("button-primary", "HexEditDialog.execute", "brush-apply", () -> edit(HexBrush::apply));
        undo = button("button-secondary", "HexEditDialog.undo", "brush-undo", () -> edit(HexBrush::undo));
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

    /** Shows the brush's view again: the ground line, the painted hexes, whether the edit is legal, the buttons. */
    private void update() {
        ground.setText(GpuBoardWindow.plainText(shown.groundText()));
        painted.clearChildren();
        painted.top().left().defaults().left().growX();
        int row = 0;
        for (String entry : shown.paintedEntries()) {
            Label line = kit.typeLabel(entry, "small", "text.secondary");
            line.setName("brush-painted-" + row++);
            line.setEllipsis(true);
            painted.add(line).minWidth(0).row();
        }
        legality.setText(GpuBoardWindow.plainText(shown.legality()));
        boolean any = shown.anyPainted();
        apply.setDisabled(!shown.mayApply());
        undo.setDisabled(!shown.mayUndo());
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
