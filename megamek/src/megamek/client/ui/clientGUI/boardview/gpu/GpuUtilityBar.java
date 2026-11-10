/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.List;
import java.util.StringJoiner;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Top-right camera controls and utilities, the Tactical View chip and the
 * Tactical View's north mark (C.1 G4). They show the camera, the wireframe view, the minimap and contacts preferences,
 * the log's presented events and the HUD's open panels, and run the camera's Tactical View, the tuning model's
 * wireframe view, the View menu's minimap item, the contacts preference and the HUD's own toggles. The Tactical View's
 * overlays have their own panel, GpuTacticalOverlays.
 */
final class GpuUtilityBar implements GpuHud.Component {
    /** The row's gap (#sys). */
    static final float GAP = 8;
    static final float HEIGHT = 34;
    /**
     * The top of the Tactical View's north mark in the window, centred: its baseline then lies at the prototype's
     * y 96 (flat.js), whether or not the chip above it has room.
     */
    static final float NORTH_TOP = 84;
    /** The chip's gap between its icon and caption (#mapchip). */
    private static final float CHIP_GAP = 10;
    /** The chip's padding at its sides, its rails' 14 at the left (GpuBoardSkin's panel-rails). */
    private static final float CHIP_SIDE = 14;
    private static final Color NORTH = new Color(226 / 255f, 235 / 255f, 215 / 255f, .7f);

    private final GpuHudState state;
    private final BoardCamera camera;
    private final GpuBoardTuning tuning;
    private final Table root = new Table();
    private final Container<Label> north;
    private final Table chip = new Table();
    private final UiButton isometric;
    private final UiButton tactical;
    private final UiButton wireframe;
    private final UiButton map;
    private final UiButton contacts;
    private final UiButton log;
    private final UiButton help;
    private final UiButton menu;
    private final Label menuTip;
    private GpuHud.Inputs inputs;

    GpuUtilityBar(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera,
          GpuBoardTuning tuning, Actor tuningButton, Runnable fitBoard) {
        this.state = state;
        this.camera = camera;
        this.tuning = tuning;
        root.setName("utility-bar");
        chip.setName("tactical-chip");
        UiKit ui = kit.ui;
        isometric = cameraButtons(ui, root, camera, fitBoard);
        // Icons only, as the camera buttons beside them: their names are the tooltips.
        tactical = iconButton(ui, "tactical", Messages.getString("GpuBoard.hud.util.tactical"), "utility-tactical");
        wireframe = iconButton(ui, "wireframe", Messages.getString("GpuBoard.hud.util.wireframe"),
              "utility-wireframe");
        map = iconButton(ui, "map", Messages.getString("GpuBoard.hud.util.map"), "utility-map");
        contacts = iconButton(ui, "enemy", Messages.getString("GpuBoard.hud.common.contacts"), "utility-contacts");
        log = iconButton(ui, "report", Messages.getString("GpuBoard.hud.util.log"), "utility-log");
        help = iconButton(ui, null, Messages.getString("GpuBoard.hud.util.help"), "utility-help");
        help.setText("?");
        UiKit.size(help.getLabel(), "hud-medium", 19);
        help.add(help.getLabel());
        menu = iconButton(ui, "menu", Messages.getString("GpuBoard.hud.menu.title"), "utility-menu");
        menuTip = tipLabel(menu);
        for (UiButton utility : List.of(tactical, wireframe, map, contacts, log)) {
            root.add(utility).size(HEIGHT).padLeft(GAP);
        }
        root.add(tuningButton).height(HEIGHT).padLeft(GAP);
        root.add(help).size(HEIGHT).padLeft(GAP);
        root.add(menu).size(HEIGHT).padLeft(GAP);
        onChange(tactical, () -> setTactical(!camera.tactical()));
        // The thermal wireframe view, a switch of the tuning model the board reads.
        onChange(wireframe, () -> tuning.setWireframe(!tuning.wireframe()));
        onChange(map, () -> runMinimap(inputs));
        // The contacts panel's preference, remembered as the minimap's is; the client's settings change on Swing.
        onChange(contacts, () -> source.command(() -> GUIPreferences.getInstance().toggleGpuContactsEnabled()));
        onChange(log, state::toggleLog);
        onChange(help, () -> state.toggle(GpuHudState.Dialog.HELP));
        onChange(menu, () -> state.toggle(GpuHudState.Dialog.MENU));

        // The chip names the mode only; the pressed Tactical view utility and its key switch back (the user's
        // decision of 2026-10-02).
        chip.setBackground(ui.skin.getDrawable("panel-rails"));
        // The caption ends as far from the right edge as the icon starts from the left one.
        chip.padRight(CHIP_SIDE);
        chip.add(ui.icon("map", 16, UiTheme.MINT));
        chip.add(ui.label(UiTheme.upper(Messages.getString("GpuBoard.hud.util.tactical")), "hud-caption", 12,
              UiTheme.MINT)).padLeft(CHIP_GAP);
        north = new Container<>(ui.label(Messages.getString("GpuBoard.hud.minimap.north"), "hud-medium", 12, NORTH));
        north.setName("tactical-north");
        // Turned with the camera, so it points north while the view rotates.
        north.setTransform(true);
        // Part of the board drawing in the prototype: presses pass through it to the board.
        north.setTouchable(Touchable.disabled);
    }

    /** A compact top-right utility shared by gameplay, map previews and the editor. */
    static UiButton utility(UiKit ui, String icon, String label, String name) {
        UiButton button = ui.button("hud-utility", icon, Messages.getString(label), null);
        button.setName(name);
        return compact(ui, button);
    }

    static UiButton compact(UiKit ui, UiButton button) {
        button.setStyle(ui.skin.get("hud-utility-small", TextButton.TextButtonStyle.class));
        button.clearChildren();
        button.icons.forEach(icon -> button.add(icon).size(14));
        button.add(button.getLabel()).padLeft(5);
        return button;
    }

    static UiButton iconButton(UiKit ui, String icon, String caption, String name) {
        UiButton button = ui.button("hud-utility-small", icon, "", null);
        button.setName(name);
        button.clearChildren();
        button.pad(0);
        button.icons.forEach(image -> button.add(image).size(19));
        ui.tip(button).getActor().setText(caption);
        return button;
    }

    /** Shared camera controls; the owner disables the returned isometric preset in the Tactical View. */
    static UiButton cameraButtons(UiKit ui, Table row, BoardCamera camera, Runnable fitBoard) {
        cameraButton(ui, row, "top", "hex", "Top view", () -> camera.setIsometric(false));
        UiButton isometric = cameraButton(ui, row, "isometric", "layers", "Isometric view",
              () -> camera.setIsometric(true));
        cameraButton(ui, row, "fit", "expand", "Fit board", fitBoard);
        return isometric;
    }

    private static UiButton cameraButton(UiKit ui, Table row, String name, String icon, String caption,
          Runnable action) {
        UiButton button = iconButton(ui, icon, caption, "map-view-" + name);
        float gap = row.getCells().isEmpty() ? 0 : GAP;
        row.add(button).size(HEIGHT).padLeft(gap);
        onChange(button, () -> {
            if (button.getStage() != null) { button.getStage().setKeyboardFocus(null); }
            action.run();
        });
        return button;
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The chip centred at the top while the Tactical View is on. */
    Actor chip() {
        return chip;
    }

    /**
     * The Tactical View's north mark, shown while that view is on. It is placed apart from the chip, centred in the
     * window with its top at {@link #NORTH_TOP}, so it stays when the chip has no room.
     */
    Actor north() {
        return north;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        boolean tacticalView = inputs.view().tactical();
        BoardScene.Command minimap = minimapCommand(inputs);
        // K12: the round's reviewable events the board has presented, which the log lists (C.6; game.js S.events)
        int reviewable = state.history.played().size();
        isometric.setDisabled(camera.tactical());
        tactical.pressed(tacticalView);
        wireframe.pressed(tuning.wireframe()).setDisabled(!GpuWireframe.supported());
        map.pressed(inputs.preferences().minimapEnabled()).setDisabled(minimap == null || !minimap.enabled());
        contacts.pressed(inputs.preferences().contactsEnabled());
        log.pressed(state.logOpen()).badge(reviewable > 0 ? String.valueOf(reviewable) : null);
        help.pressed(state.dialog == GpuHudState.Dialog.HELP);
        menu.pressed(state.dialog == GpuHudState.Dialog.MENU);
        menuTip.setText(menuTip(inputs.preferences()));
        chip.setVisible(tacticalView);
        north.setVisible(tacticalView);
        // Scene2D turns counterclockwise; the camera's azimuth turns the board's north that far clockwise on screen.
        north.setOrigin(Align.center);
        north.setRotation(-camera.azimuth());
    }

    /** As the T key: the camera enters the Tactical View or returns to the 3D pose it replaced. */
    private void setTactical(boolean enabled) {
        camera.setTactical(enabled, inputs.frame().scene());
    }

    /**
     * The Menu utility's tooltip with the keys that open the Menu: the CANCEL bind (Esc) and the GAME_MENU bind. An
     * unbound key (code 0) is left out.
     */
    static String menuTip(GpuBoardSource.UiPreferences preferences) {
        StringJoiner keys = new StringJoiner(" / ");
        for (KeyCommandBind command : List.of(KeyCommandBind.CANCEL, KeyCommandBind.GAME_MENU)) {
            preferences.binds().stream().filter(bind -> bind.command() == command && bind.keyCode() != 0)
                  .map(GpuBoardSource.Bind::text).filter(text -> !text.isEmpty()).findFirst().ifPresent(keys::add);
        }
        return keys.length() == 0 ? Messages.getString("GpuBoard.hud.menu.title")
              : Messages.getString("GpuBoard.hud.menu.tip", keys.toString());
    }

    /** The text of the tooltip {@link #iconButton} gave {@code button}. */
    private static Label tipLabel(Actor button) {
        for (EventListener listener : button.getListeners()) {
            if (listener instanceof TextTooltip tip) {
                return tip.getActor();
            }
        }
        throw new IllegalStateException("No tooltip on " + button.getName());
    }

    /**
     * The View menu's minimap item, or null: the one switch of the minimap preference, shared with the minimap's close
     * button and the MINIMAP key (plan A.5 E2).
     */
    static BoardScene.Command minimapCommand(GpuHud.Inputs inputs) {
        return GpuBoardActions.menuItem(inputs.frame().globalCommands(), ClientGUI.VIEW_MINI_MAP);
    }

    /** Runs the View menu's minimap item, which toggles the preference on the Swing thread. */
    static void runMinimap(GpuHud.Inputs inputs) {
        BoardScene.Command command = minimapCommand(inputs);
        if (command != null && command.enabled()) {
            command.action().run();
        }
    }
}
