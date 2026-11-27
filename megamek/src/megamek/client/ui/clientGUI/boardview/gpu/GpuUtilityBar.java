/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Top-right utilities, the Tactical View chip and the Tactical View's north mark (C.1 G4). One dark plate holds the
 * playback speeds, then Overlays and View, then the minimap, contacts, the log, the chat and the bot orders (only while
 * the local player commands a bot), then the Menu, the groups
 * split by hairlines; the developer's Tuning is a pill under it. View opens a popover with the 3D / Tactical switch,
 * the camera presets and the wireframe view; Overlays opens GpuTacticalOverlays. They show the playback's speed, the
 * camera, the wireframe view, the minimap and contacts preferences, the log's presented events and the HUD's open
 * panels, and run the playback history's speed, the camera's Tactical View, the tuning model's wireframe view, the
 * View menu's minimap item, the contacts preference and the HUD's own toggles.
 */
final class GpuUtilityBar implements GpuHud.Component {
    /** The row's gap (#sys). */
    static final float GAP = 8;
    static final float HEIGHT = 34;
    /** The developer's Tuning pill under the row. */
    static final float DEV_HEIGHT = 24;
    /** A flat button inside the plate. */
    private static final float BUTTON_WIDTH = 32;
    private static final float BUTTON_HEIGHT = 28;
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
    /** The playback's speeds, Instant last. */
    private static final List<UnitMotion.Speed> SPEEDS = List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL,
          UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE, UnitMotion.Speed.INSTANT);

    private final GpuHudState state;
    private final BoardCamera camera;
    private final GpuBoardTuning tuning;
    private final Table root = new Table();
    private final UiKit.Segmented speeds;
    private final Container<Label> north;
    private final Table chip = new Table();
    private final UiButton view;
    private final Table viewMenu = new Table();
    private final UiButton overlays;
    private final GpuTacticalOverlays overlaysMenu;
    private final Group popover = new Group();
    private final UiKit.Segmented viewMode;
    private final UiButton isometric;
    private final UiButton top;
    private final UiButton fit;
    /** Whether the View popover's rows are laid out for the Tactical View. */
    private boolean viewTactical;
    private final UiButton wireframe;
    private final Actor wireframeMark;
    private final UiButton map;
    private final UiButton contacts;
    private final UiButton log;
    private final UiButton chat;
    /** Opens the bot sheet; shown only while the local player commands a bot. */
    private final UiButton bots;
    private final Cell<UiButton> botsCell;
    private final UiButton menu;
    private final Label menuTip;
    /** The row's tooltips, which the open popover takes off their buttons. */
    private final Map<Actor, TextTooltip> tips = new HashMap<>();
    /** Closes the open popover on a press anywhere outside it and its button. */
    private final InputListener outside = new InputListener() {
        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
            Actor target = event.getTarget();
            if (openMenu != null && target != null && !target.isDescendantOf(openMenu)
                  && !target.isDescendantOf(opener())) {
                setOpen(null);
            }
            return false;
        }
    };
    private Stage listening;
    /** The open popover, View's or the Overlays', or null. */
    private Actor openMenu;
    private GpuHudState.Dialog dialogAtOpen = GpuHudState.Dialog.NONE;
    private GpuHud.Inputs inputs;

    GpuUtilityBar(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera,
          GpuBoardTuning tuning, Actor tuningButton, GpuTacticalOverlays overlaysMenu, UiButton chat,
          Runnable fitBoard) {
        this.state = state;
        this.camera = camera;
        this.tuning = tuning;
        this.overlaysMenu = overlaysMenu;
        this.chat = chat;
        root.setName("utility-bar");
        chip.setName("tactical-chip");
        UiKit ui = kit.ui;
        // The row keeps what a player reaches for in play; the camera presets, the Tactical View and the wireframe
        // view sit in the View popover, the board's overlays in the Overlays popover, and Help is a page of the Menu.
        overlays = iconButton(ui, "layers", Messages.getString("GpuBoard.hud.overlays.title"), "utility-overlays");
        view = iconButton(ui, "show", Messages.getString("GpuBoard.hud.util.view"), "utility-view");
        map = iconButton(ui, "map", Messages.getString("GpuBoard.hud.util.map"), "utility-map");
        contacts = iconButton(ui, "enemy", Messages.getString("GpuBoard.hud.common.contacts"), "utility-contacts");
        log = iconButton(ui, "report", Messages.getString("GpuBoard.hud.util.log"), "utility-log");
        bots = iconButton(ui, "bot", Messages.getString("GpuBoard.hud.bots.title"), "utility-bots");
        menu = iconButton(ui, "menu", Messages.getString("GpuBoard.hud.menu.title"), "utility-menu");
        menuTip = tipLabel(menu);
        // One plate: the playback speeds, then the board (Overlays, View), the panels (minimap, contacts, log, chat)
        // and the Menu, split by hairlines. The developer's Tuning is a pill on a line of its own under the plate's
        // right end, near the top where the developers look for it, and goes before release.
        speeds = speeds(ui, state);
        Table plate = new Table();
        plate.setName("utility-plate");
        plate.setBackground(ui.skin.getDrawable("utility-plate"));
        plate.add(speeds).height(BUTTON_HEIGHT - 2);
        List<List<UiButton>> groups = List.of(List.of(overlays, view), List.of(map, contacts, log, chat, bots),
              List.of(menu));
        Cell<UiButton> botsAt = null;
        for (List<UiButton> group : groups) {
            plate.add(new Image(ui.skin.newDrawable("white", UiTheme.QUIET))).size(1, 20).padLeft(6).padRight(4);
            for (UiButton utility : group) {
                utility.setStyle(ui.skin.get("hud-utility-flat", TextButton.TextButtonStyle.class));
                utility.pad(0);
                Cell<UiButton> cell = plate.add(utility).size(BUTTON_WIDTH, BUTTON_HEIGHT).padLeft(2);
                if (utility == bots) {
                    botsAt = cell;
                }
            }
        }
        botsCell = botsAt;
        showBots(false);
        root.add(plate).height(HEIGHT).right();
        root.row();
        root.add(tuningButton).right().height(DEV_HEIGHT).padTop(GAP);

        viewMenu.setName("utility-view-menu");
        viewMenu.setBackground(ui.skin.getDrawable("panel-pop"));
        viewMenu.pad(6);
        viewMenu.defaults().height(HEIGHT).growX();
        // The camera's mode first: the 3D view or the Tactical View (the T key).
        viewMode = ui.segmented("hud-pill", true, Messages.getString("GpuBoard.hud.util.view3d"),
              Messages.getString("GpuBoard.hud.util.tactical"));
        viewMode.setName("view-mode");
        viewMode.buttons.get(0).setName("view-mode-3d");
        viewMode.buttons.get(1).setName("utility-tactical");
        for (UiButton mode : viewMode.buttons) {
            mode.pad(4, 14, 4, 14);
        }
        top = popoverItem(ui, "hex", "GpuBoard.hud.util.viewTop", "map-view-top");
        isometric = popoverItem(ui, "layers", "GpuBoard.hud.util.viewIsometric", "map-view-isometric");
        fit = popoverItem(ui, "expand", "GpuBoard.hud.util.viewFit", "map-view-fit");
        wireframe = popoverItem(ui, "wireframe", "GpuBoard.hud.util.wireframe", "utility-wireframe");
        wireframeMark = ui.icon("check", 14, Color.WHITE);
        wireframe.icons.add(wireframeMark);
        wireframe.add(wireframeMark).padLeft(10);
        layoutViewMenu(false);
        viewMenu.setVisible(false);
        viewMenu.setTouchable(Touchable.childrenOnly);
        overlaysMenu.actor().setVisible(false);
        overlaysMenu.actor().setTouchable(Touchable.childrenOnly);
        // The HUD lays this layer over the panels, so the popovers are not covered by the minimap or the right column.
        popover.setName("utility-view-layer");
        popover.setTouchable(Touchable.childrenOnly);
        popover.addActor(viewMenu);
        popover.addActor(overlaysMenu.actor());

        onChange(view, () -> setOpen(openMenu == viewMenu ? null : viewMenu));
        onChange(overlays, this::toggleOverlays);
        onChange(viewMode.buttons.get(0), () -> setTactical(false));
        onChange(viewMode.buttons.get(1), () -> setTactical(true));
        onChange(top, () -> closeAfter(() -> camera.setIsometric(false)));
        onChange(isometric, () -> closeAfter(() -> camera.setIsometric(true)));
        onChange(fit, () -> closeAfter(fitBoard));
        // The thermal wireframe view, a switch of the tuning model the board reads; the popover stays to show it.
        onChange(wireframe, () -> tuning.setWireframe(!tuning.wireframe()));
        onChange(map, () -> runMinimap(inputs));
        // The contacts panel's preference, remembered as the minimap's is; the client's settings change on Swing.
        onChange(contacts, () -> source.command(() -> GUIPreferences.getInstance().toggleGpuContactsEnabled()));
        onChange(log, state::toggleLog);
        onChange(bots, () -> state.toggleBots(inputs == null ? GpuBots.Snapshot.EMPTY
              : inputs.frame().panels().bots()));
        onChange(menu, () -> state.toggle(GpuHudState.Dialog.MENU));

        // The chip names the mode only; the View popover's switch and the T key switch back (the user's decision of
        // 2026-10-02).
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

    /**
     * The playback speeds as one utility of sections, 0.5x to 4x and Instant (a skip icon), the history's speed
     * pressed. They pace the playback and the other players' moves, in every phase and turn.
     */
    private static UiKit.Segmented speeds(UiKit ui, GpuHudState state) {
        UiKit.Segmented speeds = ui.segmented("hud-pill", false, SPEEDS.stream()
              .map(speed -> speed == UnitMotion.Speed.INSTANT ? ""
                    : Messages.getString("GpuBoard.hud.util.speedRate", speed.rate / UnitMotion.Speed.NORMAL.rate))
              .toArray(String[]::new));
        speeds.setName("utility-speeds");
        // On the utility plate the speeds need no frame or hairlines of their own: the plate is their frame.
        speeds.setBackground((Drawable) null);
        speeds.pad(0);
        speeds.clearChildren();
        for (UiButton button : speeds.buttons) {
            speeds.add(button);
        }
        for (int i = 0; i < SPEEDS.size(); i++) {
            UnitMotion.Speed speed = SPEEDS.get(i);
            UiButton button = speeds.buttons.get(i);
            button.setName("utility-speed-" + speed.name().toLowerCase(Locale.ROOT));
            button.pad(0, 7, 0, 7);
            UiKit.size(button.getLabel(), "hud-small", 11);
            String tip = Messages.getString("GpuBoard.hud.util.speed");
            if (speed == UnitMotion.Speed.INSTANT) {
                Actor icon = ui.icon("skip", 16, Color.WHITE);
                button.icons.add(icon);
                button.clearChildren();
                button.add(icon).size(16);
                tip = Messages.getString("GpuBoard.hud.util.instant");
            }
            ui.tip(button).getActor().setText(tip);
            onChange(button, () -> state.history.speed(speed));
        }
        return speeds;
    }

    /** A row of the View popover. */
    /**
     * The View popover's rows: the mode switch, then the camera presets (3D only, as the Tactical View is a top-down
     * camera of its own), fit board and the wireframe view.
     */
    private void layoutViewMenu(boolean tactical) {
        viewTactical = tactical;
        viewMenu.clearChildren();
        viewMenu.add(viewMode).height(30).pad(2, 4, 6, 4).row();
        if (!tactical) {
            viewMenu.add(top).row();
            viewMenu.add(isometric).row();
        }
        viewMenu.add(fit).row();
        viewMenu.add(wireframe).row();
    }

    /**
     * A popover row (.pop .it): its icon and name on a flat face that fills under the pointer, never the bracketed
     * utility face, which would frame a frame.
     */
    static UiButton popoverItem(UiKit ui, String icon, String label, String name) {
        UiButton item = new UiButton(ui, "hud-menu-row");
        item.setName(name);
        Actor image = ui.icon(icon, 16, Color.WHITE);
        item.icons.add(image);
        item.add(image).padRight(10);
        item.setText(Messages.getString(label));
        item.getLabel().setAlignment(Align.left);
        item.add(item.getLabel()).growX();
        return item;
    }

    /** A camera preset closes the popover once it has run. */
    private void closeAfter(Runnable action) {
        setOpen(null);
        action.run();
    }

    /** The row's utilities after the playback speeds, in their order. */
    private List<UiButton> utilities() {
        return List.of(overlays, view, map, contacts, log, chat, menu);
    }

    /** The button the open popover hangs from. */
    private UiButton opener() {
        return openMenu == overlaysMenu.actor() ? overlays : view;
    }

    /** The Overlays button and the TACTICAL_OVERLAYS key: open or close the Overlays popover. */
    void toggleOverlays() {
        setOpen(openMenu == overlaysMenu.actor() ? null : overlaysMenu.actor());
    }

    /** Opens {@code menu}, one of the popovers, in place of the open one; null closes it. */
    private void setOpen(Actor menu) {
        if (openMenu != null) {
            openMenu.setVisible(false);
        }
        openMenu = menu;
        dialogAtOpen = state.dialog;
        if (menu != null) {
            menu.setVisible(true);
            if (menu instanceof Table table) {
                table.pack();
            }
            placeMenu();
        }
        // The row's tooltips would cover the open popover.
        for (UiButton utility : utilities()) {
            TextTooltip tip = tip(utility);
            if (menu != null) {
                tip.hide();
                utility.removeListener(tip);
            } else if (!utility.getListeners().contains(tip, true)) {
                utility.addListener(tip);
            }
        }
        // The buttons keep no keyboard focus, so the HUD's keys still reach the board.
        if (view.getStage() != null) {
            view.getStage().setKeyboardFocus(null);
        }
    }

    /**
     * The popover hangs one gap below its button, its right edge at the button's right edge or further left, so it
     * stays in the window.
     */
    private void placeMenu() {
        UiButton button = opener();
        if (openMenu == null || button.getStage() == null || popover.getStage() == null) {
            return;
        }
        Vector2 corner = button.localToActorCoordinates(popover, new Vector2(button.getWidth(), 0));
        float x = openMenu == viewMenu ? corner.x - button.getWidth() : corner.x - openMenu.getWidth();
        x = Math.min(x, popover.getStage().getWidth() - openMenu.getWidth() - GAP);
        openMenu.setPosition(Math.max(GAP, x), corner.y - GAP - openMenu.getHeight());
    }

    /** Esc's first step while a popover is open: closes it; false when none was open. */
    boolean closeView() {
        if (openMenu == null) {
            return false;
        }
        setOpen(null);
        return true;
    }

    /** The layer over the HUD's panels that holds the View popover. */
    Actor popover() {
        return popover;
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
        speeds.select(SPEEDS.indexOf(state.history.speed()));
        boolean tacticalView = inputs.view().tactical();
        BoardScene.Command minimap = minimapCommand(inputs);
        // K12: the round's reviewable events the board has presented, which the log lists (C.6; game.js S.events)
        int reviewable = state.history.played().size();
        if (tacticalView != viewTactical) {
            layoutViewMenu(tacticalView);
        }
        viewMode.select(tacticalView ? 1 : 0);
        wireframe.setDisabled(!GpuWireframe.supported());
        wireframeMark.setVisible(tuning.wireframe());
        // A dialog opened over the row closes the popover; one already open when it opened (Tuning) keeps it.
        if (openMenu != null && state.dialog != dialogAtOpen) {
            setOpen(null);
        }
        view.pressed(openMenu == viewMenu || tuning.wireframe());
        overlays.pressed(openMenu == overlaysMenu.actor());
        if (openMenu != null) {
            // The Overlays popover grows a line while deploying.
            if (openMenu instanceof Table table && table.getHeight() != table.getPrefHeight()) {
                table.pack();
            }
            placeMenu();
        }
        Stage stage = root.getStage();
        if (stage != listening) {
            if (listening != null) {
                listening.removeCaptureListener(outside);
            }
            listening = stage;
            if (stage != null) {
                stage.addCaptureListener(outside);
            }
        }
        map.pressed(inputs.preferences().minimapEnabled()).setDisabled(minimap == null || !minimap.enabled());
        contacts.pressed(inputs.preferences().contactsEnabled());
        log.pressed(state.logOpen()).badge(reviewable > 0 ? String.valueOf(reviewable) : null);
        boolean commandsBot = !inputs.frame().panels().bots().bots().isEmpty();
        if (commandsBot != bots.isVisible()) {
            showBots(commandsBot);
        }
        bots.pressed(state.dialog == GpuHudState.Dialog.BOTS);
        menu.pressed(state.dialog == GpuHudState.Dialog.MENU);
        menuTip.setText(menuTip(inputs.preferences()));
        chip.setVisible(tacticalView);
        north.setVisible(tacticalView);
        // Scene2D turns counterclockwise; the camera's azimuth turns the board's north that far clockwise on screen.
        north.setOrigin(Align.center);
        north.setRotation(-camera.azimuth());
    }

    /** Shows the bot utility in the plate, or takes it out with its gap. */
    private void showBots(boolean shown) {
        bots.setVisible(shown);
        botsCell.size(shown ? BUTTON_WIDTH : 0, BUTTON_HEIGHT).padLeft(shown ? 2 : 0);
        botsCell.getTable().invalidateHierarchy();
    }

    /** As the T key: the camera enters the Tactical View or returns to the 3D pose it replaced. */
    private void setTactical(boolean enabled) {
        if (inputs != null && camera.tactical() != enabled) {
            camera.setTactical(enabled, inputs.frame().scene());
        }
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
        return findTip(button).getActor();
    }

    /** The tooltip {@link #iconButton} gave {@code button}, remembered while the popover takes it off. */
    private TextTooltip tip(UiButton button) {
        return tips.computeIfAbsent(button, GpuUtilityBar::findTip);
    }

    private static TextTooltip findTip(Actor button) {
        for (EventListener listener : button.getListeners()) {
            if (listener instanceof TextTooltip tip) {
                return tip;
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
