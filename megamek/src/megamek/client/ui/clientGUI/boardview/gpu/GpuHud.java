/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.TimeUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.NameplateMode;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * The battle HUD on the render thread (rebuild plan C.4): its Stage, the layout metrics and layers, the
 * components, key dispatch with the Esc chain and the hotkeys, and board-click routing. It presents snapshots and calls
 * the source's existing commands; the client keeps every rule.
 */
final class GpuHud implements GpuBoardHud {
    /** The prototype's spacing between stacked panels. */
    private static final float STACK = 12;
    /** The top of larger overlays and panels beside the right column. */
    private static final float SECOND_ROW = 90;
    private static final float RIGHT_BOTTOM = 70;
    private static final float TOAST_BOTTOM = 232;
    /** The initiative card's top, as a fraction of the window height. */
    private static final float INITIATIVE_TOP = .2f;
    /** The solution card moves beside the right column when the list would keep less than this height. */
    private static final float SOLUTION_ROOM = 430;
    private static final float CONDITIONS_WIDTH = 260;
    /**
     * The forces list's width, at most the left column's (the user's request of 2026-10-02: the prototype's column of
     * 300 left the rows much room to spare). The phase header and the unit card keep the column; the forces grid
     * keeps its own width.
     */
    static final float FORCES_WIDTH = 250;
    private static final float INITIATIVE_WIDTH = 560;
    /**
     * The side panels fade to {@link #FADED} once the camera moved this long, in seconds, over FADE_OUT seconds, and
     * come back over FADE_IN seconds when it stops.
     */
    private static final float GESTURE_DELAY = .15f;
    private static final float FADED = .2f;
    private static final float FADE_OUT = .2f;
    private static final float FADE_IN = .25f;
    /**
     * The binds MegaMek applies without a unit besides its menu bar's ({@link #unitless}): the camera, the chat, the
     * pause, the report keys, the turn timer and the HUD's own panels.
     */
    private static final Set<KeyCommandBind> UNITLESS_BINDS = EnumSet.of(KeyCommandBind.SCROLL_NORTH,
          KeyCommandBind.SCROLL_SOUTH, KeyCommandBind.SCROLL_EAST, KeyCommandBind.SCROLL_WEST,
          KeyCommandBind.CAMERA_ROTATE_LEFT, KeyCommandBind.CAMERA_ROTATE_RIGHT, KeyCommandBind.CAMERA_TILT_UP,
          KeyCommandBind.CAMERA_TILT_DOWN, KeyCommandBind.CAMERA_RESET, KeyCommandBind.CAMERA_FIT_BOARD,
          KeyCommandBind.TOGGLE_CHAT, KeyCommandBind.TOGGLE_CHAT_CMD, KeyCommandBind.PAUSE, KeyCommandBind.UNPAUSE,
          KeyCommandBind.REPORT_KEY_NEXT, KeyCommandBind.REPORT_KEY_PREV, KeyCommandBind.REPORT_KEY_SELECT_NEXT,
          KeyCommandBind.REPORT_KEY_SELECT_PREVIOUS, KeyCommandBind.REPORT_FILTER_KEY_SELECT_NEXT,
          KeyCommandBind.REPORT_KEY_FILTER, KeyCommandBind.EXTEND_TURN_TIMER, KeyCommandBind.BOT_COMMANDS,
          KeyCommandBind.FORCES_GRID, KeyCommandBind.PLAYBACK_TOGGLE, KeyCommandBind.PLAYBACK_PREV,
          KeyCommandBind.PLAYBACK_NEXT, KeyCommandBind.SHOW_NAMEPLATES, KeyCommandBind.UD_GENERAL,
          KeyCommandBind.UD_PILOT, KeyCommandBind.UD_ARMOR, KeyCommandBind.UD_WEAPONS, KeyCommandBind.UD_SYSTEMS,
          KeyCommandBind.UD_EXTRAS, KeyCommandBind.TACTICAL_OVERLAYS);

    /**
     * The prototype's layout sizes for one window, in stage units (its CSS pixels): the gap, the left and right column
     * widths, and the dock, log and target-card widths, with the breakpoints W <= 1500 (compact), W <= 1350 (narrow)
     * and H <= 800 (low height).
     */
    record Metrics(float width, float height, float gap, float left, float right, float dock, float log, float card,
          boolean compact, boolean narrow, boolean lowHeight) {
        static Metrics of(float width, float height) {
            boolean compact = width <= 1500;
            boolean narrow = width <= 1350;
            return new Metrics(width, height, GAP, narrow ? 250 : compact ? 270 : 300,
                  narrow ? 270 : compact ? 290 : 310, narrow ? 500 : compact ? 540 : 580,
                  narrow ? 310 : compact ? 340 : 380, narrow ? 270 : 300, compact, narrow, height <= 800);
        }

        /** The forces grid's width, min(660, W / 2 - 330) and at least the left column. */
        float grid() {
            return Math.max(left, Math.min(660, width / 2 - 330));
        }

        /** The forces list's width: FORCES_WIDTH, and at most the left column. */
        float forces() {
            return Math.min(FORCES_WIDTH, left);
        }
    }

    /**
     * The board view's facts of one frame, by unit id: the view mode and whether the playback still animates; each
     * drawn unit's screen rectangle and label anchor (head) in stage units, y up, left out while its head lies behind
     * the camera; each drawn unit's animated board position in world units; the hovered hex and unit; a hex's
     * width on the screen, corner to corner, in stage units; the height of the ring the view draws on a building
     * floor under the pointer, NaN while the pointer is on the hovered hex's ground (GpuBattleView.hoverTop); and the
     * anchor of each fire target that is no unit, its hex's centre, as a unit's head is its anchor.
     */
    record HudView(boolean tactical, boolean playbackBusy, Map<Integer, Rectangle> unitRects,
          Map<Integer, Vector2> unitHeads, Map<Integer, Vector2> unitPositions, Coords hovered, int hoveredUnit,
          float hexPixels, Map<TargetKey, Vector2> targetHeads) {
        static final HudView EMPTY = new HudView(false, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);

        HudView {
            unitRects = Map.copyOf(unitRects);
            unitHeads = Map.copyOf(unitHeads);
            unitPositions = Map.copyOf(unitPositions);
            targetHeads = Map.copyOf(targetHeads);
        }

        /** The facts while every fire target is a unit. */
        HudView(boolean tactical, boolean playbackBusy, Map<Integer, Rectangle> unitRects,
              Map<Integer, Vector2> unitHeads, Map<Integer, Vector2> unitPositions, Coords hovered, int hoveredUnit,
              float hexPixels) {
            this(tactical, playbackBusy, unitRects, unitHeads, unitPositions, hovered, hoveredUnit, hexPixels,
                  Map.of());
        }

        /** A fire target's anchor: a unit's head, else its hex's; null where the view draws neither. */
        Vector2 head(TargetKey target) {
            return (target.unitId() != Entity.NONE) ? unitHeads.get(target.unitId()) : targetHeads.get(target);
        }
    }

    /**
     * What every component reads in one frame. {@code dialog} is the pending native dialog, or null;
     * {@code panelBounds} are the stage bounds (y up) of the panels the last layout showed, which board labels and
     * target cards keep clear of (the prototype's HUD rectangles).
     */
    record Inputs(GpuBoardSource.Frame frame, HudView view, GpuBoardWindow.DialogRequest dialog,
          GpuBoardSource.UiPreferences preferences, Metrics metrics, List<Rectangle> panelBounds) { }

    /**
     * One hud component: it builds its actor once and shows the snapshots of each frame. Units
     * come from {@link GpuHudState#presentedUnits()} only. The Esc chain calls the {@code cancel()} of the context
     * menu, the dock, the LOS card, the unit sheet, the weapons panel and the modal; every other panel's open state is
     * a {@link GpuHudState} flag that the HUD closes.
     */
    interface Component {
        /** The actor the HUD places in the component's slot and layer. */
        Actor actor();

        /** One frame's snapshots and layout inputs. */
        void update(Inputs inputs);

        /** Frees what the component owns; the HUD calls it once. */
        default void dispose() {
        }
    }

    final Stage stage;
    final GpuHudState state;
    private final GpuBoardSource source;
    private final GpuHudKit kit;
    private final GpuContextMenu contextMenu;
    private final GpuLosPanel losPanel;
    /** The board labels draw the leaders to the target cards as the cards placed themselves this frame. */
    private final GpuBoardLabels boardLabels;
    private final GpuTargetCards targetCards;
    private final GpuRecordSheet recordSheet;
    private final GpuWeaponsPanel weapons;
    private final GpuCommandDock dock;
    private final GpuChatPanel chat;
    private final GpuLogPanel log;
    private final GpuModalDialog modal;
    /** The Tactical View's Overlays panel, which the layout may show collapsed for a frame. */
    private final GpuTacticalOverlays overlays;
    /** The conditions, a strip as wide as its summary while collapsed. */
    private final GpuConditionsCard conditionsCard;
    /** The forces, a rail at the window's edge until expanded. */
    private final GpuForcesPanel forces;
    /** The layer of Help, Menu, Players and the tuning panel: a focus in it is that dialog's Esc step. */
    private final Group dialogs;
    private final UiModal centeredDialogs;
    /** The game menu, whose pages Esc steps back through before it closes the menu. */
    private final GpuMenuPanel menu;
    /** Every component, bottom layer first and the developer tuning tool last: the order of updates. */
    private final List<Component> components;
    private final Container<Actor> phaseSlot;
    private final Container<Actor> initiativeSlot;
    private final Container<Actor> conditionsSlot;
    private final Container<Actor> forcesSlot;
    private final Container<Actor> unitSlot;
    private final Container<Actor> otherUnitSlot;
    private final Container<Actor> sheetSlot;
    private final Container<Actor> utilitySlot;
    private final Container<Actor> chipSlot;
    private final Container<Actor> hintSlot;
    private final Container<Actor> pickSlot;
    private final Container<Actor> minimapSlot;
    private final Container<Actor> overlaysSlot;
    private final Container<Actor> contactsSlot;
    private final Container<Actor> weaponsSlot;
    private final Container<Actor> solutionSlot;
    private final Container<Actor> logSlot;
    private final Container<Actor> dockSlot;
    private final Container<Actor> drawerSlot;
    private final Container<Actor> chatSlot;
    private final Container<Actor> overviewSlot;
    private final Container<Actor> helpSlot;
    private final Container<Actor> menuSlot;
    private final Container<Actor> playersSlot;
    private final Container<Actor> toastSlot;
    private final Container<Actor> modalSlot;
    /** The Tactical View's north mark: part of the board drawing, under the board labels. */
    private final Container<Actor> northSlot;
    /**
     * The slots that span the window: board labels, target cards, the context menu and the tuning panel. Their Table
     * roots take presses on their widgets only (Touchable.childrenOnly, a Table's default).
     */
    private final List<Container<Actor>> windowSlots;
    /** The prototype's panels and the hint line, whose shown bounds are the frame's panel bounds. */
    private final List<Container<Actor>> panelSlots = new ArrayList<>();
    /** Keys whose press the HUD consumed, so their release is consumed as well. */
    private final Set<Integer> consumedKeys = new HashSet<>();
    private Metrics metrics;
    private float scale = 1;
    private Inputs inputs;
    private int publishedFocus = Entity.NONE;
    private int publishedCard = Entity.NONE;
    private int publishedOwnCard = Entity.NONE;
    /** The unit the HUD selects once the local turn began (C.5), held while a dialog would drop the command. */
    private int pendingSelect = Entity.NONE;
    /** The key that opened the chat also types a character (Enter's newline) that must not start the message. */
    private boolean swallowTyped;
    /** The camera moves this frame; for how long, in seconds; the side panels' alpha; the last fade's nanoTime. */
    private boolean cameraMoving;
    private float gestureSeconds;
    private float fade = 1;
    private long fadeClock;

    /**
     * Builds the HUD on the render thread. The skin, the batch and the tuning model stay with their owner; the camera
     * is the board view's, which the utilities, minimap, dock, context menu and tuning panel use, and so is the
     * playback {@code history}, which the board feeds and the dock, the log, the labels and the keys review.
     */
    GpuHud(GpuBoardSource source, Skin skin, Batch batch, BoardCamera camera, GpuBoardTuning tuningModel,
          GpuPlaybackHistory history) {
        this.source = source;
        state = new GpuHudState(history);
        kit = new GpuHudKit(skin);
        stage = new Stage(new ScreenViewport(), batch) {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                // A press anywhere else ends a text field's or a grip's keyboard focus (rebuild plan A.17 Q1).
                Actor focus = getKeyboardFocus();
                Vector2 point = screenToStageCoordinates(new Vector2(screenX, screenY));
                Actor target = hit(point.x, point.y, true);
                if (focus != null && (target == null || !target.isDescendantOf(focus))) {
                    setKeyboardFocus(null);
                }
                return super.touchDown(screenX, screenY, pointer, button);
            }
        };
        // The prototype's stacking, bottom to top, with the native modal above everything.
        Group labels = layer();
        Group panels = layer();
        Group overviewLayer = layer();
        Group chatLayer = layer();
        Group popover = layer();
        dialogs = layer();
        centeredDialogs = new UiModal(kit.ui, () -> state.dialog = GpuHudState.Dialog.NONE);
        centeredDialogs.setName("dialog-backdrop");
        dialogs.addActor(centeredDialogs);
        Group toastLayer = layer();
        Group modalLayer = layer();
        // First: the components with unit menus and select lists open this one.
        contextMenu = new GpuContextMenu(kit, source, state, this::select);
        GpuNameplates nameplates = new GpuNameplates(kit, source, state);
        targetCards = new GpuTargetCards(kit, source, state, contextMenu);
        losPanel = new GpuLosPanel(kit.ui, stage, source, camera);
        GpuPhaseHeader phaseHeader = new GpuPhaseHeader(kit, source, state);
        GpuInitiativeCard initiativeCard = new GpuInitiativeCard(kit, source, state);
        conditionsCard = new GpuConditionsCard(kit, source, state);
        forces = new GpuForcesPanel(kit, source, state, contextMenu, this::select);
        // The unit panel's paperdoll geometry, read once per window (unit panel design 6.2).
        GpuPaperdolls paperdolls = new GpuPaperdolls();
        GpuUnitCard unitCard = new GpuUnitCard(kit, source, state, contextMenu, paperdolls, false);
        GpuUnitCard otherUnitCard = new GpuUnitCard(kit, source, state, contextMenu, paperdolls, true);
        recordSheet = new GpuRecordSheet(kit, source, state, contextMenu, paperdolls);
        GpuTuningPanel tuning = new GpuTuningPanel(kit, source, state, camera, tuningModel);
        GpuUtilityBar utilities = new GpuUtilityBar(kit, source, state, camera, tuningModel, tuning.button(), () -> {
            if (inputs != null) {
                camera.fit(inputs.frame().scene(), framingLeft(), framingWidth(), framingBottom(), framingTop());
            }
        });
        GpuHintLine hint = new GpuHintLine(kit, source, state, camera);
        GpuMinimap minimap = new GpuMinimap(kit, source, state, camera);
        overlays = new GpuTacticalOverlays(kit, source);
        GpuContactsPanel contacts = new GpuContactsPanel(kit, source, state, contextMenu, this::select);
        // The guides follow the contacts panel's toggles and open row; hex-anchored labels need the board camera.
        boardLabels = new GpuBoardLabels(kit, source, state, camera, contacts, nameplates);
        weapons = new GpuWeaponsPanel(kit, source, state, contextMenu);
        GpuSolutionCard solution = new GpuSolutionCard(kit, source, state);
        log = new GpuLogPanel(kit, source, state, contextMenu);
        chat = new GpuChatPanel(kit, source, state);
        dock = new GpuCommandDock(kit, source, state, camera, contextMenu, chat.button());
        GpuForceOverview overview = new GpuForceOverview(kit, source, state, contextMenu, this::select);
        GpuHelpDialog help = new GpuHelpDialog(kit, source, state);
        menu = new GpuMenuPanel(kit, source, state);
        GpuPlayersPanel players = new GpuPlayersPanel(kit, source, state);
        GpuToastStack toasts = new GpuToastStack(kit, source, state);
        modal = new GpuModalDialog(kit, source, state);
        GpuHexTip hexTip = new GpuHexTip(kit, state, contextMenu);
        GpuAimDialog aim = new GpuAimDialog(kit, source, state, paperdolls);
        List<Component> parts = new ArrayList<>(List.of(nameplates, boardLabels, targetCards, losPanel, phaseHeader,
              initiativeCard, conditionsCard, forces, unitCard, otherUnitCard, recordSheet, utilities, hint, minimap, overlays,
              contacts, weapons, solution, log, dock, chat, overview, help, menu, players, contextMenu, hexTip, aim,
              toasts, modal));

        // The Tactical View's north mark first: the prototype draws it on the board, under the labels (G4). Then the
        // prototype's #fx strokes (guides, traces, leaders) under every board label, the nameplates included (G8b).
        northSlot = slot(labels, utilities.north());
        // The hex's hover card, the unit panel's hover card and slot popover share the popover layer with the context
        // menu, which opens over them.
        List<Container<Actor>> spanning = new ArrayList<>(List.of(slot(labels, boardLabels.fx()),
              slot(labels, nameplates.actor()), slot(labels, boardLabels.actor()), slot(labels, targetCards.actor()), slot(labels, losPanel.actor()),
              slot(popover, hexTip.actor()), slot(popover, unitCard.overlay()), slot(popover, otherUnitCard.overlay()),
              slot(popover, recordSheet.overlay()),
              slot(popover, contextMenu.actor()), slot(popover, dock.confirmation().actor())));
        phaseSlot = panel(panels, phaseHeader.actor()).top().left().fillX();
        conditionsSlot = panel(panels, conditionsCard.actor()).top().left().fillX();
        forcesSlot = panel(panels, forces.actor()).top().left().fillX();
        unitSlot = panel(panels, unitCard.actor()).bottom().left().fillX();
        otherUnitSlot = panel(panels, otherUnitCard.actor()).bottom().right().fillX();
        sheetSlot = panel(panels, recordSheet.actor()).fill();
        utilitySlot = panel(panels, utilities.actor()).top().right();
        chipSlot = panel(panels, utilities.chip()).top();
        minimapSlot = panel(panels, minimap.actor()).top().fillX();
        overlaysSlot = panel(panels, overlays.actor()).top().fillX();
        contactsSlot = panel(panels, contacts.actor()).top().fillX();
        weaponsSlot = panel(panels, weapons.actor()).top().fillX();
        logSlot = panel(panels, log.actor()).top().fillX();
        solutionSlot = panel(panels, solution.actor()).top().fillX();
        initiativeSlot = panel(panels, initiativeCard.actor()).top().fillX();
        // Side panels cover the hint line; it stays centred on the dock at its full width.
        hintSlot = slot(labels, hint.actor());
        panelSlots.add(hintSlot);
        // Where the window has no room for the hint line, a pick's chip stands on the dock with Done and Cancel.
        pickSlot = panel(panels, hint.chip());
        dockSlot = panel(panels, dock.actor()).bottom().fillX();
        // The dock's More panel slides out of the dock's right side, over the board beside it.
        chatSlot = panel(chatLayer, chat.actor()).fill();
        drawerSlot = panel(chatLayer, dock.drawer().actor()).fill();
        overviewSlot = panel(overviewLayer, overview.actor()).fill();
        helpSlot = panel(centeredDialogs, help.actor()).fillX();
        menuSlot = panel(centeredDialogs, menu.actor()).fillX();
        playersSlot = panel(centeredDialogs, players.actor()).fillX();
        toastSlot = slot(toastLayer, toasts.actor()).bottom();
        // The modal's window-sized root is its scrim: every press around the dialog lands on it.
        modal.actor().setTouchable(Touchable.enabled);
        modalSlot = slot(modalLayer, modal.actor()).fill();

        // The aimed shot dialog's window-sized scrim over the panels and menus, under the toasts and the native modal.
        spanning.add(slot(dialogs, aim.actor()));

        // The developer tuning utility sits before Help and Menu; its panel places itself.
        // Before release, delete this block, the tuningModel parameter, GpuHudState.Dialog.TUNING and GpuTuningPanel.
        parts.add(tuning);
        spanning.add(slot(dialogs, tuning.actor()));

        components = List.copyOf(parts);
        windowSlots = List.copyOf(spanning);
        // Plain Actors otherwise use their previous size as the minimum and cannot shrink after a UI-scale change.
        windowSlots.forEach(slot -> slot.minSize(0).fill());
        metrics = Metrics.of(stage.getWidth(), stage.getHeight());
    }

    private Group layer() {
        Group layer = new Group();
        layer.setTransform(false);
        layer.setTouchable(Touchable.childrenOnly);
        stage.addActor(layer);
        return layer;
    }

    /** A slot the HUD positions; its own empty area never takes a press meant for the board. */
    private static Container<Actor> slot(Group layer, Actor actor) {
        Container<Actor> slot = new Container<>(actor);
        slot.setTouchable(Touchable.childrenOnly);
        layer.addActor(slot);
        return slot;
    }

    /**
     * A slot for one of the prototype's panels. As there, the panel takes every press on its area, padding included,
     * so none reaches the board below it.
     */
    private Container<Actor> panel(Group layer, Actor actor) {
        actor.setTouchable(Touchable.enabled);
        Container<Actor> slot = slot(layer, actor);
        panelSlots.add(slot);
        return slot;
    }

    /** The window's logical size and the display scale; one stage unit is one prototype CSS pixel. */
    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public megamek.client.ui.gdx.UiKit uiKit() { return kit.ui; }

    @Override
    public void resize(int width, int height, float displayScale) {
        scale = displayScale;
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / displayScale);
        stage.getViewport().update(width, height, true);
        metrics = Metrics.of(stage.getWidth(), stage.getHeight());
        layout();
    }

    /**
     * One frame's snapshots. Applies the frame's state ({@link #updateState}), updates every component and lays them
     * out.
     */
    @Override
    public void update(GpuBoardSource.Frame frame, HudView view, GpuBoardWindow.DialogRequest dialog,
          GpuBoardSource.UiPreferences preferences) {
        // The panels stay where the last layout put them until this frame's layout. A cleared selection presents the
        // local turn without an acting unit (GpuHudState.presented).
        updateState(frame, view.playbackBusy(), dialog);
        inputs = new Inputs(state.presented(frame), view, dialog, preferences, metrics, panelBounds());
        for (Component component : components) {
            component.update(inputs);
        }
        // The leaders run to the cards where the cards placed themselves in this frame (G9).
        boardLabels.cards(targetCards.placed());
        layout();
        fadeForCamera();
        keepModalFocus();
    }

    /** Whether the player pans, orbits or turns the camera this frame; GpuBattleView reports it before the update. */
    @Override
    public void cameraGesture(boolean moving) {
        cameraMoving = moving;
    }

    /**
     * Fades the side panels while the camera moves, so the board around them shows, and brings them back when it
     * stops; the phase header, the dock, the toolbar and the minimap stay.
     */
    private void fadeForCamera() {
        long now = TimeUtils.nanoTime();
        float delta = fadeClock == 0 ? 0 : Math.min(.1f, (now - fadeClock) / 1e9f);
        fadeClock = now;
        gestureSeconds = cameraMoving ? gestureSeconds + delta : 0;
        if (gestureSeconds > GESTURE_DELAY) {
            fade = Math.max(FADED, fade - delta / FADE_OUT);
        } else {
            fade = Math.min(1, fade + delta / FADE_IN);
        }
        for (Container<Actor> slot : List.of(forcesSlot, unitSlot, otherUnitSlot, sheetSlot, conditionsSlot,
              contactsSlot, logSlot, weaponsSlot, solutionSlot, overlaysSlot, initiativeSlot)) {
            slot.getColor().a = fade;
        }
    }

    /**
     * Applies the focus rule (C.5) and the presented units (C.6) and publishes the focus and card units to the source.
     * They open and close the log and the sheet, and with them the board area between the columns, so the board view
     * applies them before its camera frames the frame; called again with the same frame, it applies nothing twice.
     */
    void updateState(GpuBoardSource.Frame frame, boolean playbackBusy, GpuBoardWindow.DialogRequest dialog) {
        int select = state.update(frame, playbackBusy);
        if (select != Entity.NONE) {
            pendingSelect = select;
        }
        if (pendingSelect != Entity.NONE && (!frame.status().myTurn() || state.focus() != pendingSelect)) {
            // The turn or the focus moved on first.
            pendingSelect = Entity.NONE;
        } else if (pendingSelect != Entity.NONE && dialog == null && !playbackBusy) {
            // Not while a dialog is pending: the source's input guard would drop the command.
            source.selectUnit(pendingSelect);
            pendingSelect = Entity.NONE;
        }
        if (state.focus() != publishedFocus) {
            publishedFocus = state.focus();
            source.setFocusUnit(publishedFocus);
        }
        int card = state.cardUnit();
        if (card != publishedCard) {
            publishedCard = card;
            source.setCardUnit(card);
        }
        int ownCard = state.ownCardUnit();
        if (ownCard != publishedOwnCard) {
            publishedOwnCard = ownCard;
            source.setOwnCardUnit(ownCard);
        }
    }

    /** Only the highest modal owns focus, including file pickers above a pending game prompt. */
    private void keepModalFocus() {
        UiModal active = UiModal.active(stage);
        if (active == modal.actor()) { modal.focus(); }
        else if (active != null) { active.focus(); }
    }

    /** The stage bounds (y up) of the shown panels, as the last layout placed them. */
    List<Rectangle> panelBounds() {
        List<Rectangle> bounds = new ArrayList<>();
        for (Container<Actor> slot : panelSlots) {
            if (shown(slot)) {
                slot.validate();
                Actor panel = slot.getActor();
                bounds.add(new Rectangle(slot.getX() + panel.getX(), slot.getY() + panel.getY(), panel.getWidth(),
                      panel.getHeight()));
            }
        }
        return List.copyOf(bounds);
    }

    /** Draws the HUD over the board. The kit's sprite masks follow the units, reports and toasts of the snapshots. */
    @Override
    public void draw() {
        Set<BoardScene.Pixels> sprites = new HashSet<>();
        if (inputs != null) {
            state.presentedUnits().stream().map(GpuBattleStatus.UnitStatus::icon).forEach(sprites::add);
            inputs.frame().status().units().stream().map(GpuBattleStatus.UnitStatus::icon).forEach(sprites::add);
            sprites.addAll(inputs.frame().reports().icons().values());
            inputs.frame().panels().toasts().toasts().stream().map(GpuToasts.Toast::icon).forEach(sprites::add);
            sprites.remove(null);
        }
        kit.update(sprites);
        stage.getViewport().apply();
        stage.act(Math.min(Gdx.graphics.getDeltaTime(), .1f));
        stage.draw();
    }

    /** True over a HUD widget, and everywhere while a dialog is pending (its scrim takes every press). */
    @Override
    public boolean hit(int x, int y) {
        if (inputs != null && inputs.dialog() != null) {
            return true;
        }
        Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
        return stage.hit(point.x, point.y, true) != null;
    }

    /** True over the minimap's canvas, which a drag moves the camera on (the prototype's grab pointer). */
    @Override
    public boolean dragsCamera(int x, int y) {
        Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
        Actor target = stage.hit(point.x, point.y, true);
        return target != null && "minimap-canvas".equals(target.getName());
    }

    @Override
    public boolean isTextEditing() {
        return stage.getKeyboardFocus() instanceof TextField;
    }

    private boolean centeredDialogOpen() {
        return state.dialog == GpuHudState.Dialog.HELP || state.dialog == GpuHudState.Dialog.MENU
              || state.dialog == GpuHudState.Dialog.PLAYERS;
    }

    @Override
    public boolean isModal() {
        return otherModal() != null || centeredDialogOpen() || inputs != null && inputs.dialog() != null;
    }

    /**
     * The stage's open modal unless it is the centred dialogs' or the pending dialog's layer: the HUD state opens and
     * closes those two, and their layers only follow it at the next update, so a closed dialog holds no key a frame
     * longer.
     */
    private UiModal otherModal() {
        UiModal active = UiModal.active(stage);
        return active == centeredDialogs || active == modal.actor() ? null : active;
    }

    /**
     * A key press (C.4), with its libGDX code and its AWT code and modifiers. Returns true when the HUD consumed it;
     * the board view then neither moves the camera nor forwards it to Swing. The order: a pending dialog takes every
     * key; the CANCEL bind runs the Esc chain; a focused text field takes every other key; a focused grip or menu gets
     * the key first; then the hotkeys.
     */
    @Override
    public boolean keyDown(int key, int awt, int modifiers) {
        swallowTyped = false;
        UiModal active = otherModal();
        if (active != null) {
            active.focus(); stage.keyDown(key); consumedKeys.add(key); return true;
        }
        boolean consumed = inputs != null && dispatch(key, binds(awt, modifiers));
        if (consumed) {
            consumedKeys.add(key);
        }
        return consumed;
    }

    private boolean dispatch(int key, Set<KeyCommandBind> binds) {
        if (inputs.dialog() != null) {
            // The modal's controls handle Enter and the arrows; CANCEL presses its cancel button.
            if (binds.contains(KeyCommandBind.CANCEL)) {
                modal.cancel();
            } else {
                stage.keyDown(key);
            }
            return true;
        }
        if (binds.contains(KeyCommandBind.GAME_MENU)) {
            state.toggle(GpuHudState.Dialog.MENU);
            stage.setKeyboardFocus(null);
            return true;
        }
        if (centeredDialogOpen()) {
            if (binds.contains(KeyCommandBind.CANCEL)) {
                // The menu's pages go back one at a time; Esc on its first page, or over another dialog, closes it
                if (state.dialog != GpuHudState.Dialog.MENU || !menu.back()) {
                    state.dialog = GpuHudState.Dialog.NONE;
                    stage.setKeyboardFocus(null);
                }
            } else if (binds.contains(KeyCommandBind.KEY_BINDS)) {
                state.toggle(GpuHudState.Dialog.HELP);
            } else {
                centeredDialogs.focus();
                stage.keyDown(key);
            }
            return true;
        }
        if (binds.contains(KeyCommandBind.CANCEL) && dock.cancel()) {
            return true;
        }
        if (binds.contains(KeyCommandBind.CANCEL)) {
            return escape() || state.turnLocked(inputs.frame().status());
        }
        Actor focus = stage.getKeyboardFocus();
        if (focus instanceof TextField) {
            stage.keyDown(key);
            return true;
        }
        // A focused grip reorders on Alt+Up/Down and an open menu takes its navigation keys.
        return focus != null && stage.keyDown(key) || hotkey(binds, focus != null);
    }

    /**
     * True when the release belongs to a press the HUD consumed. Releasing the nameplate key ends the nameplates,
     * whatever other modifier is still held, unless the key toggles them.
     */
    @Override
    public boolean keyUp(int key, int awt) {
        if (inputs != null && !toggledNameplates() && inputs.preferences().binds().stream()
              .anyMatch(bind -> bind.command() == KeyCommandBind.SHOW_NAMEPLATES && bind.keyCode() == awt)) {
            state.allNameplates = false;
        }
        stage.keyUp(key);
        return consumedKeys.remove(key);
    }

    /** A typed character; true while a text field or a pending dialog takes the keyboard. */
    @Override
    public boolean keyTyped(char character) {
        if (swallowTyped) {
            swallowTyped = false;
            return true;
        }
        boolean typing = isTextEditing() || isModal();
        return stage.keyTyped(character) || typing;
    }

    /** The window lost the focus: no key is held any more. */
    @Override
    public void focusLost() {
        if (!toggledNameplates()) {
            state.allNameplates = false;
        }
        consumedKeys.clear();
        losPanel.dismiss();
        // A modal Swing dialog blocks the input, so a press held now never sees its release.
        stage.cancelTouchFocus();
    }

    /** The nameplate key toggles every nameplate instead of showing them while it is held. */
    private boolean toggledNameplates() {
        return inputs != null && inputs.preferences().nameplates() == NameplateMode.TOGGLE;
    }

    private Set<KeyCommandBind> binds(int awt, int modifiers) {
        return binds(inputs.preferences().binds(), awt, modifiers);
    }

    /**
     * The commands of {@code binds}, the key bindings the client captured on the Swing thread, that a key press with
     * the AWT code and modifiers {@code awt} and {@code modifiers} invokes.
     */
    static Set<KeyCommandBind> binds(List<GpuBoardSource.Bind> binds, int awt, int modifiers) {
        Set<KeyCommandBind> matched = EnumSet.noneOf(KeyCommandBind.class);
        for (GpuBoardSource.Bind bind : binds) {
            if (KeyCommandBind.matches(bind.keyCode(), bind.modifiers(), awt, modifiers)) {
                matched.add(bind.command());
            }
        }
        return matched;
    }

    /**
     * The Esc chain (C.4), one step per press: what is open closes, then a pending action is dropped (a pick, the
     * armed weapon, the route, the chosen physical attack; MegaMek's own movement, aerospace and the other gears the
     * planner leaves to it, gets CANCEL while its display has a path, gear or hex pick to drop), then the inspected
     * unit goes, a running playback skips to its results as the dock's Skip does, and the selection clears as the
     * card's close button clears it. With nothing left, the Menu opens, as a game's Esc does. CANCEL never reaches
     * FIRING, TARGETING, OFFBOARD or PHYSICAL, where it would clear the declared attacks.
     */
    private boolean escape() {
        // A row dragged in a list (attacks or weapons) goes home first.
        if (targetCards.cancelDrag() || recordSheet.cancelDrag() || weapons.cancelDrag()) {
            return true;
        }
        GamePhase phase = inputs.frame().status().phase();
        Actor focus = stage.getKeyboardFocus();
        // A popover's content holds the focus while it is open; its owner's step closes it. So do the list of an open
        // Help, Menu or Players dialog and the tuning panel's choice list: the dialog's step closes it with them.
        boolean inDialog = focus != null && state.dialog != GpuHudState.Dialog.NONE && focus.isDescendantOf(dialogs);
        if (focus != null && !inDialog && !focus.isDescendantOf(contextMenu.actor())
              && !focus.isDescendantOf(dock.drawer().actor())
              && !focus.isDescendantOf(recordSheet.overlay())) {
            stage.setKeyboardFocus(null);
        } else if (contextMenu.back() || dock.drawer().close()) {
            return true;
        } else if (state.dialog != GpuHudState.Dialog.NONE) {
            state.dialog = GpuHudState.Dialog.NONE;
            if (inDialog) {
                stage.setKeyboardFocus(null);
            }
        } else if (state.overview) {
            state.overview = false;
        } else if (state.chatOpen) {
            state.chatOpen = false;
        } else if (picking(inputs)) {
            source.players().endPick(false);
        } else if (losPanel.cancel() || recordSheet.cancel()) {
            // The sheet's step: its popover, the expanded weapon row, then the sheet (U3).
            return true;
        } else if (weapons.cancel()) {
            return true;
        } else if (planning(inputs) && !inputs.frame().panels().move().route().isEmpty()) {
            source.moves().clearRoute();
        } else if (dock.releaseChosen()) {
            return true;
        } else if (swingMovement(inputs)) {
            return false;
        } else if (state.inspected != Entity.NONE) {
            state.inspected = Entity.NONE;
        } else if (!phase.isEndReport() && state.history.running()) {
            state.history.skip();
        } else if (state.ownCardUnit() != Entity.NONE) {
            state.clearSelection();
        } else {
            state.dialog = GpuHudState.Dialog.MENU;
        }
        return true;
    }

    /**
     * The local movement turn outside the planner (G20) while MegaMek's own movement display has a path, gear or hex
     * pick to drop: its CANCEL takes Esc.
     */
    private static boolean swingMovement(Inputs inputs) {
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        return inputs.frame().status().myTurn() && inputs.frame().status().phase().isMovement() && move.active()
              && !move.planner() && move.cancellable();
    }

    /**
     * The local movement turn of a unit the HUD plans for. Other movement (aerospace, vector movement, charge or DFA
     * gears; plan A.7 G20) keeps MegaMek's own keys, CANCEL and board tool. The hint line and the hex menu read it too.
     */
    static boolean planning(Inputs inputs) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        return status.myTurn() && status.phase().isMovement() && inputs.frame().panels().move().planner();
    }

    /**
     * A bot order picks hexes (its HexTargetPicker runs): a left board click picks a hex, the Done key ends the pick
     * with the order and Esc without it. The hint line reads it too.
     */
    static boolean picking(Inputs inputs) {
        return inputs.frame().panels().players().pick() != null;
    }

    /**
     * The C.4 hotkey table; binds it does not list stay with the camera and Swing. The targeting display's phases
     * (TARGETING, OFFBOARD) keep MegaMek's own keys and board tool, as its turn has no native orders (lead decision
     * D1). The physical attack keys PHYS_PUNCH, PHYS_KICK and PHYS_PUSH stay MegaMek's too: with the native dialogs,
     * which skip the physical "To hit" question, they declare their attack at once.
     */
    private boolean hotkey(Set<KeyCommandBind> binds, boolean focused) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GamePhase phase = status.phase();
        boolean moving = planning(inputs);
        boolean locked = state.turnLocked(status);
        boolean firing = status.myTurn() && phase.isFiring() && !locked;
        GpuMovePlan.Mode mode = binds.contains(KeyCommandBind.MOVE_MODE_WALK) ? GpuMovePlan.Mode.WALK
              : binds.contains(KeyCommandBind.MOVE_MODE_RUN) ? GpuMovePlan.Mode.RUN
              : binds.contains(KeyCommandBind.MOVE_MODE_JUMP) ? GpuMovePlan.Mode.JUMP : null;
        int framed = status.actorId() != Entity.NONE ? status.actorId() : state.inspected;
        if (state.logOpen() && log.key(binds)) {
            // The report keys (REPORT_KEY_NEXT and the like) find and filter in the open log.
            return true;
        }
        if (binds.contains(KeyCommandBind.SHOW_NAMEPLATES)) {
            // Alt with a focused grip belongs to the grip's reordering.
            if (!toggledNameplates()) {
                state.allNameplates = !focused;
            } else if (!focused) {
                state.allNameplates = !state.allNameplates;
            }
        } else if (binds.contains(KeyCommandBind.ROUND_REPORT)) {
            state.toggleLog();
        } else if (binds.contains(KeyCommandBind.KEY_BINDS)) {
            state.toggle(GpuHudState.Dialog.HELP);
        } else if (binds.contains(KeyCommandBind.BOT_COMMANDS)) {
            state.toggle(GpuHudState.Dialog.PLAYERS);
        } else if (binds.contains(KeyCommandBind.LOS_SETTING)) {
            // View > Ruler / LOS Tool opens the native measurement panel.
            BoardScene.Command ruler = GpuBoardActions.menuItem(inputs.frame().globalCommands(),
                  ClientGUI.VIEW_LOS_SETTING);
            if (ruler != null && ruler.enabled()) {
                ruler.action().run();
            }
        } else if (binds.contains(KeyCommandBind.UNIT_OVERVIEW) || binds.contains(KeyCommandBind.FORCE_DISPLAY)) {
            state.overview = !state.overview;
        } else if (binds.contains(KeyCommandBind.UNIT_DISPLAY)) {
            // With the overview open, the key closes it and shows the sheet (unit panel design 12).
            state.recordOpen = state.overview || !state.recordOpen;
            state.overview = false;
        } else if (sheetTab(binds) != null) {
            GpuRecordSheet.show(state, sheetTab(binds));
        } else if (binds.contains(KeyCommandBind.FORCES_GRID)) {
            // One wide left panel at a time: the key closes an open sheet and shows the grid.
            state.forcesGrid = GpuRecordSheet.open(state) || !state.forcesGrid;
            state.recordOpen = false;
        } else if (binds.contains(KeyCommandBind.TACTICAL_OVERLAYS) && inputs.view().tactical()) {
            // The Overlays panel's client setting, which the Swing thread changes.
            source.command(() -> GUIPreferences.getInstance().toggleTacticalOverlaysOpen());
        } else if (binds.contains(KeyCommandBind.TOGGLE_CHAT) || binds.contains(KeyCommandBind.TOGGLE_CHAT_CMD)) {
            state.chatOpen = true;
            chat.focus();
            // The "/" of TOGGLE_CHAT_CMD starts the message; the character of TOGGLE_CHAT (Enter's newline) does not.
            swallowTyped = !binds.contains(KeyCommandBind.TOGGLE_CHAT_CMD);
        } else if (binds.contains(KeyCommandBind.DONE) && picking(inputs)) {
            source.players().endPick(true);
        } else if (binds.contains(KeyCommandBind.DONE)) {
            if (!locked) {
                dock.main();
            }
        } else if (binds.contains(KeyCommandBind.NEXT_UNIT) || binds.contains(KeyCommandBind.PREV_UNIT)) {
            if (inputs.view().playbackBusy()) { return true; }
            // MegaMek's next or previous unit becomes the selected one, also when it is the current unit again.
            state.restoreSelection();
            return false;
        } else if (moving && mode != null) {
            source.moves().setMode(mode);
        } else if (moving && (binds.contains(KeyCommandBind.TURN_LEFT) || binds.contains(KeyCommandBind.TURN_RIGHT))) {
            source.moves().turn(binds.contains(KeyCommandBind.TURN_LEFT) ? -1 : 1);
        } else if (firing
              && (binds.contains(KeyCommandBind.TWIST_LEFT) || binds.contains(KeyCommandBind.TWIST_RIGHT))) {
            dock.twist(binds.contains(KeyCommandBind.TWIST_LEFT) ? -1 : 1);
        } else if (moving && binds.contains(KeyCommandBind.UNDO_LAST_STEP)) {
            source.moves().undo();
        } else if (firing && binds.contains(KeyCommandBind.UNDO_LAST_STEP)) {
            source.fire().removeLast();
        } else if (moving && binds.contains(KeyCommandBind.CLEAR_ORDERS)) {
            source.moves().clearRoute();
        } else if (firing && binds.contains(KeyCommandBind.CLEAR_ORDERS)) {
            dock.clearOrders();
        } else if (phase.isReport() && binds.contains(KeyCommandBind.PLAYBACK_TOGGLE)) {
            state.history.togglePaused();
        } else if (phase.isReport()
              && (binds.contains(KeyCommandBind.PLAYBACK_PREV) || binds.contains(KeyCommandBind.PLAYBACK_NEXT))) {
            state.history.step(binds.contains(KeyCommandBind.PLAYBACK_PREV) ? -1 : 1);
        } else if (!phase.isReport() && binds.contains(KeyCommandBind.CENTER_ON_SELECTED) && framed != Entity.NONE) {
            source.locateUnit(framed);
        } else {
            // MegaMek gets the rest, but with the selection cleared in the local turn only the binds of no unit.
            return locked && !binds.stream().allMatch(GpuHud::unitless);
        }
        return true;
    }

    /**
     * Whether MegaMek may get {@code bind} while the player cleared the selection in the local turn: its menu bar's
     * binds and {@link #UNITLESS_BINDS} act on no unit. Every other bind stays with the HUD, so MegaMek's current unit
     * never acts.
     */
    private static boolean unitless(KeyCommandBind bind) {
        return bind.isMenuBar || UNITLESS_BINDS.contains(bind);
    }

    /** The unit sheet's tab of the Unit Display's keys F1 to F6 ({@code UD_GENERAL} to {@code UD_EXTRAS}), or null. */
    private static GpuHudState.SheetTab sheetTab(Set<KeyCommandBind> binds) {
        for (GpuHudState.SheetTab tab : GpuHudState.SheetTab.values()) {
            if (binds.contains(tab.key)) {
                return tab;
            }
        }
        return null;
    }

    /**
     * A press on the board, outside every panel; GpuBattleView calls it on every board pointer-down, before a drag
     * pans or orbits. As a press outside them in the prototype, it ends a keyboard focus and closes the open menu.
     */
    @Override
    public void boardPress() {
        if (inputs != null && inputs.dialog() == null) {
            stage.setKeyboardFocus(null);
            contextMenu.cancel();
        }
    }

    /**
     * A short board click (C.4) on {@code coords} and the unit {@code unitId} picked there ({@code Entity.NONE} for
     * none), at screen pixel ({@code x}, {@code y}), with the world height of the terrain the pointer hit
     * ({@code pointedZ}; NaN on a unit, or in the Tactical View). A right click opens the context menu and never
     * changes orders; Alt opens the native LOS ruler at the pointed height, and a plain left
     * click ends a measurement waiting for its second point with that measurement's modifier;
     * while a bot order picks hexes, a left click picks one, as the Legacy 2D board's click does. Any other left
     * click takes the unit {@link #clickedUnit} gives.
     */
    void boardClick(Coords coords, int unitId, int button, int modifiers, int x, int y, float pointedZ) {
        if (inputs == null || inputs.dialog() != null || coords == null || inputs.frame().scene() == null) {
            return;
        }
        int clickModifiers = GpuBoardSource.isMeasurement(modifiers) ? modifiers
              : modifiers | inputs.frame().panels().los().pending();
        if (button == Input.Buttons.RIGHT) {
            Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
            contextMenu.open(coords, unitId, point.x, point.y, pointedZ);
        } else if (button == Input.Buttons.LEFT && GpuBoardSource.isMeasurement(clickModifiers)) {
            source.measure(coords, clickModifiers, pointedZ);
        } else if (button == Input.Buttons.LEFT && picking(inputs)) {
            source.click(coords, false, clickModifiers);
        } else if (button == Input.Buttons.LEFT) {
            leftClick(coords, GpuHudState.unit(inputs.frame().status(), clickedUnit(coords, unitId)), modifiers,
                  pointedZ);
        }
    }

    /**
     * The unit a left click with this pick takes, which the board view's hover shows too (its cursor, outline, tag and
     * route preview): the unit {@link #boardUnit} gives, or the next of its hex's units ({@link #cycled}).
     */
    int clickedUnit(Coords coords, int unitId) {
        int taken = boardUnit(coords, unitId);
        return taken == Entity.NONE || inputs == null ? taken : cycled(coords, taken);
    }

    /**
     * The unit a left click with this pick takes before a stack's turn ({@link #clickedUnit}). In the local planning
     * turn it is none, so the click plans the move there as in an empty hex, on a hex of the plan's envelope, the legal
     * end hexes the board highlights on the actor's board, other than the actor's own hex: a unit's model there as
     * well as the ground; and on a transport the actor can
     * board ({@link GpuMovePlan.Snapshot#mounts}). Otherwise it is the picked unit, which a click selects or inspects.
     */
    int boardUnit(Coords coords, int unitId) {
        if (unitId == Entity.NONE || coords == null || inputs == null || inputs.frame().scene() == null
              || !planning(inputs) || state.turnLocked(inputs.frame().status())) {
            return unitId;
        }
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuBattleStatus.UnitStatus actor = GpuHudState.unit(status, status.actorId());
        boolean destination = actor != null && actor.boardId() == inputs.frame().scene().boardId()
              && move.envelope().containsKey(coords) && !coords.equals(actor.position());
        return destination || move.mounts().contains(unitId) ? Entity.NONE : unitId;
    }

    /**
     * The unit a left click on {@code coords} takes instead of the picked {@code unitId}: while the unit the HUD shows
     * as taken (the fire orders' focused target, else the card's unit) stands in that hex, the next of the hex's units
     * after it, so repeated clicks on a stack select, inspect or focus its units in turn.
     * Only units the status lists take part, not the wrecks the board draws too, and while a click focuses
     * targets (the local FIRING turn) no sensor contact, which it cannot focus.
     */
    private int cycled(Coords coords, int unitId) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        boolean aiming = status.myTurn() && status.phase().isFiring() && !state.turnLocked(status);
        List<Integer> stack = GpuContextMenu.stack(inputs.frame().scene(), coords).stream().filter(id -> {
            GpuBattleStatus.UnitStatus unit = GpuHudState.unit(status, id);
            return unit != null && !(aiming && unit.sensorContact());
        }).toList();
        for (int shown : new int[] { inputs.frame().panels().fire().focus().key().unitId(), state.cardUnit() }) {
            int at = shown == Entity.NONE ? -1 : stack.indexOf(shown);
            if (at >= 0) {
                return stack.get((at + 1) % stack.size());
            }
        }
        return unitId;
    }

    /**
     * Left click by phase: the HUD's own gestures during the local turn, MegaMek's board tool for everything else of
     * that turn, and the selection rule outside it, as for a unit row.
     */
    private void leftClick(Coords coords, GpuBattleStatus.UnitStatus unit, int modifiers, float pointedZ) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuHudData data = inputs.frame().panels();
        GamePhase phase = status.phase();
        boolean own = unit != null && unit.side() == GpuBattleStatus.Side.OWN;
        boolean shift = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        boolean control = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
        if (!status.myTurn() || state.turnLocked(status)) {
            // Outside the local turn, and in it with the selection cleared: a unit is selected or inspected, a hex
            // does nothing.
            if (unit != null) {
                select(unit.id());
            }
        } else if (planning(inputs)) {
            if (shift) {
                source.moves().faceToward(coords, inputs.frame().scene().boardId());
            } else if (unit == null || control) {
                source.moves().planTo(coords, inputs.frame().scene().boardId(), control, pointedZ);
            } else {
                select(unit.id());
            }
        } else if (phase.isFiring() && shift) {
            // Shift+click twists the torso toward the hex, also one a unit stands in, as MegaMek's Shift+click does
            // (FiringDisplay.hexMoused): it selects and targets nothing, and the armed weapon stays armed.
            source.hover(coords, modifiers);
            source.click(coords, false, modifiers);
        } else if (phase.isFiring() && unit != null) {
            if (!own) { state.inspected = unit.id(); }
            if (own) {
                select(unit.id());
            } else if (unit.sensorContact()) {
                // Not targetable (H19): the fire orders refuse it with their toast, and the armed weapon stays armed.
                source.fire().focusTarget(TargetKey.unit(unit.id()));
            } else {
                GpuContextMenu.focusTarget(source, state, TargetKey.unit(unit.id()));
            }
        } else if (phase.isFiring()) {
            // A hex: MegaMek's board click chooses its target, a building or a wooded hex too, and the armed weapon
            // fires at it once, as at an enemy.
            source.hover(coords, modifiers);
            source.fire().clickHex(coords, modifiers, state.armedWeapon);
            state.armedWeapon = -1;
        } else if (phase.isPhysical() && unit != null) {
            if (own) {
                select(unit.id());
            } else {
                state.inspected = unit.id();
                if (data.physical().adjacentTargets().contains(unit.id())) {
                    source.physical().target(unit.id());
                }
            }
        } else {
            // Deployment, the targeting display's phases (D1), non-planner movement and every other tool of the turn.
            if (unit != null && !own) { state.inspected = unit.id(); }
            source.hover(coords, modifiers);
            source.click(coords, false, modifiers);
        }
    }

    /**
     * The one selection rule (the prototype's app.js selectUnit), for board clicks and for the unit rows, cards and
     * menus of the components: an own unit that can act now in the local MOVEMENT, FIRING, TARGETING, OFFBOARD or
     * PHYSICAL turn is selected through that phase's command (the fire orders' in FIRING, the display's own selection
     * elsewhere). Outside the local turn, where MegaMek selects nothing, an own unit becomes the focus (C.5), which the
     * card and the panels show as the selected unit. Any other unit is inspected.
     * Selecting ends an inspection and a cleared selection (the card's ✕); selecting the acting unit again posts
     * nothing, so its plan stays, unless the selection was cleared.
     */
    void select(int unitId) {
        GpuBattleStatus.Snapshot status = inputs == null ? GpuBattleStatus.Snapshot.EMPTY : inputs.frame().status();
        GpuBattleStatus.UnitStatus unit = GpuHudState.unit(status, unitId);
        if (unit == null) {
            return;
        }
        if (inputs.view().playbackBusy()) {
            state.inspected = unitId;
            return;
        }
        if (unit.side() == GpuBattleStatus.Side.OWN && !status.myTurn()) {
            state.restoreSelection();
            state.inspected = Entity.NONE;
            state.pick(unitId);
            return;
        }
        boolean ready = acts(status, unit);
        // Only a selection ends a cleared one; an inspected unit leaves it cleared. With the selection cleared the
        // presented status has no actor, so MegaMek selects even its current unit again, afresh.
        state.inspected = ready ? Entity.NONE : unitId;
        if (ready) {
            state.restoreSelection();
        }
        if (ready && unitId != status.actorId()) {
            if (status.phase().isFiring()) {
                source.fire().selectUnit(unitId);
            } else {
                source.selectUnit(unitId);
            }
        }
    }

    /**
     * Whether {@link #select} makes the unit the actor: an own unit that can act now in the local MOVEMENT, FIRING,
     * TARGETING, OFFBOARD or PHYSICAL turn. The unit menus offer "Select" for it and "Inspect" for any other.
     */
    static boolean acts(GpuBattleStatus.Snapshot status, GpuBattleStatus.UnitStatus unit) {
        GamePhase phase = status.phase();
        return status.myTurn() && unit.side() == GpuBattleStatus.Side.OWN && unit.canActNow()
              && (phase.isFiring() || phase.isMovement() || phase.isPhysical() || phase.isTargeting()
              || phase.isOffboard());
    }

    @Override
    public float framingLeft() { return (2 * metrics.gap() + leftWidth()) * scale; }

    @Override
    public float framingWidth() {
        return Math.max(1, (metrics.width() - 4 * metrics.gap() - leftWidth() - rightWidth()) * scale);
    }

    /** The left column's width: the grid's while the unit sheet or the forces grid is open (unit panel design 3.6). */
    private float leftWidth() {
        return state.forcesGrid || GpuRecordSheet.open(state) && !state.overview ? metrics.grid() : metrics.left();
    }

    /** The right column's width: the log's while it is open (r1 3.15). */
    private float rightWidth() {
        return state.logOpen() ? metrics.log() : metrics.right();
    }

    /**
     * Places every slot as the prototype's CSS and layout() do (r1 section 2), from the metrics and the current
     * content sizes. Panels of the middle area stack below the top row's panels they would overlap and stay above
     * the dock; the tactical chip hides when the top row has no room for it (the pressed Tactical-view utility shows
     * the mode).
     */
    private void layout() {
        if (inputs == null) {
            return;
        }
        Metrics m = metrics;
        float width = m.width();
        float height = m.height();
        float gap = m.gap();
        boolean logOpen = state.logOpen();
        boolean weaponsOpen = !logOpen && inputs.frame().panels().fire().active();
        // The forces overview covers both columns and the middle; what lies under it is hidden, where the prototype
        // blurs it (shot 12).
        boolean covered = state.overview;
        unitSlot.setVisible(!covered);
        otherUnitSlot.setVisible(!covered);
        // The dock keeps its chat footer even when unit commands are unavailable.
        dockSlot.setVisible(true);
        hintSlot.setVisible(!covered && !state.chatOpen && !m.narrow());
        pickSlot.setVisible(!covered && (m.narrow() || state.chatOpen) && picking(inputs));
        initiativeSlot.setVisible(!covered);
        forcesSlot.setVisible(!covered);
        boolean sheet = GpuRecordSheet.open(state) && !covered;
        sheetSlot.setVisible(sheet);
        minimapSlot.setVisible(!covered && inputs.preferences().minimapEnabled());
        overlaysSlot.setVisible(!covered);
        logSlot.setVisible(!covered && logOpen);
        weaponsSlot.setVisible(!covered && weaponsOpen);
        solutionSlot.setVisible(!covered && weaponsOpen);
        // The Forces panel lists the contacts; this panel only shows while a move previews its fire exchange.
        contactsSlot.setVisible(!covered && !logOpen && !weaponsOpen && inputs.preferences().contactsEnabled()
              && inputs.frame().panels().preview().active());
        chatSlot.setVisible(state.chatOpen);
        overviewSlot.setVisible(state.overview);
        helpSlot.setVisible(state.dialog == GpuHudState.Dialog.HELP);
        menuSlot.setVisible(state.dialog == GpuHudState.Dialog.MENU);
        playersSlot.setVisible(state.dialog == GpuHudState.Dialog.PLAYERS);
        centeredDialogs.setBounds(0, 0, width, height);
        centeredDialogs.open(centeredDialogOpen());
        modalSlot.setVisible(inputs.dialog() != null);
        windowSlots.forEach(slot -> slot.setBounds(0, 0, width, height));
        modalSlot.setBounds(0, 0, width, height);

        float utilityWidth = utilitySlot.getPrefWidth();
        float utilityLeft = width - gap - utilityWidth;
        float utilityHeight = utilitySlot.getPrefHeight();
        place(utilitySlot, utilityLeft, gap, utilityWidth, utilityHeight);

        // Left column: phase header, forces between it and the unit card. While the unit sheet is open (unit panel
        // design 3.3) the forces shrink to their strip, the card to its mini form, and the sheet fills the column
        // between them at the grid's width; it does not move the dock.
        float phaseHeight = height(phaseSlot);
        float phaseTop = utilityLeft < m.left() + 2 * gap ? utilityHeight + 2 * gap : gap;
        place(phaseSlot, gap, phaseTop, m.left(), phaseHeight);
        float cardHeight = height(unitSlot);
        place(unitSlot, gap, height - gap - cardHeight, m.left(), cardHeight);
        float forcesWidth = state.forcesGrid && !sheet ? m.grid() : m.forces();
        float forcesTop = phaseTop + phaseHeight + STACK;
        float forcesBottom = cardHeight > 0 ? height - gap - cardHeight - STACK : height - gap;
        float forcesHeight = sheet ? height(forcesSlot) : forcesBottom - forcesTop;
        // The folded rail is as tall as its units, within the column.
        float shownForcesWidth = forces.folded() ? GpuForcesPanel.RAIL_WIDTH : forcesWidth;
        if (forces.folded()) {
            forcesHeight = Math.min(height(forcesSlot), forcesHeight);
        }
        place(forcesSlot, gap, forcesTop, shownForcesWidth, forcesHeight);
        float sheetTop = forcesTop + forcesHeight + STACK;
        place(sheetSlot, gap, sheetTop, m.grid(), forcesBottom - sheetTop);

        // The secondary inspector reserves the bottom of the right column before its other panels are sized.
        float otherCardHeight = height(otherUnitSlot);
        place(otherUnitSlot, width - gap - m.right(), height - gap - otherCardHeight, m.right(), otherCardHeight);
        float rightReserve = gap + (otherCardHeight > 0 ? otherCardHeight + STACK : 0);
        // Right column: utilities, minimap, contacts / weapons / log, solution card, secondary inspector.
        float rightStart = gap + utilityHeight + gap;
        float minimapHeight = height(minimapSlot);
        float columnMinimum = shown(weaponsSlot) ? weapons.minimumHeight()
              : shown(logSlot) ? logSlot.getMinHeight() + 60
              : shown(contactsSlot) ? contactsSlot.getMinHeight() + 40 : 0;
        if (columnMinimum > 0 && minimapHeight > 0) {
            // Keep the panel's controls and one list row usable above the inspector, even in a short window.
            minimapHeight = Math.min(minimapHeight, height - rightStart - gap - rightReserve - columnMinimum);
            if (minimapHeight < minimapSlot.getMinHeight()) {
                minimapSlot.setVisible(false);
                minimapHeight = 0;
            }
        }
        place(minimapSlot, width - gap - m.right(), rightStart, m.right(), minimapHeight);
        float rightWidth = rightWidth();
        float rightTop = minimapHeight > 0 ? rightStart + minimapHeight + gap : rightStart;
        float solutionHeight = height(solutionSlot);
        boolean beside = solutionHeight > 0
              && height - rightTop - rightReserve - solutionHeight - STACK < SOLUTION_ROOM;
        float rightBottom = height - rightReserve - (solutionHeight > 0 && !beside ? solutionHeight + STACK : 0);
        for (Container<Actor> column : List.of(contactsSlot, weaponsSlot, logSlot)) {
            place(column, width - gap - rightWidth, rightTop, rightWidth, rightBottom - rightTop);
        }
        place(solutionSlot, width - gap - m.right(), height - rightReserve - solutionHeight, m.right(),
              solutionHeight);

        // Middle band between the columns: the dock beside the unit card, one bar along the bottom edge, with the hint
        // line and a pick's chip above it. It starts after the left column, which the unit card fills, or the wider
        // grid and the open unit sheet.
        float bandLeft = gap + Math.max(sheet ? m.grid() : forcesWidth, m.left()) + gap;
        float bandRight = width - gap - rightWidth - gap;
        float dockWidth = Math.min(m.dock(), bandRight - bandLeft);
        float dockHeight = height(dockSlot);
        float dockTop = height - gap - dockHeight;
        place(dockSlot, bandLeft, dockTop, dockWidth, dockHeight);
        float pickHeight = height(pickSlot);
        float chatHeight = chat.height(dockTop - STACK - rightStart - (pickHeight > 0 ? pickHeight + STACK : 0));
        place(chatSlot, dockSlot.getX(), dockTop - STACK - chatHeight, dockWidth, chatHeight);
        float hintWidth = hintSlot.getPrefWidth();
        float hintHeight = hintSlot.getPrefHeight();
        float hintBottom = gap + (dockHeight > 0 ? dockHeight + STACK : 0);
        place(hintSlot, dockSlot.getX() + (dockSlot.getWidth() - hintWidth) / 2,
              height - hintBottom - hintHeight, hintWidth,
              hintHeight);
        float pickWidth = Math.min(pickSlot.getPrefWidth(), dockWidth);
        place(pickSlot, dockSlot.getX() + (dockWidth - pickWidth) / 2,
              dockTop - (state.chatOpen ? chatHeight + STACK : 0) - STACK - pickHeight,
              pickWidth, pickHeight);
        // The More panel, sized to its commands: right of the dock, up to the window edge, or up to the right column
        // while one of its panels reaches down beside the dock. It takes the columns that room holds, no more than the
        // dock's height needs, and grows upward from the dock's bottom when they need more rows. Without room for one
        // column there, it lies above the dock, at most the dock's width, and grows upward over the hint line.
        GpuMoreDrawer drawer = dock.drawer();
        drawerSlot.setVisible(shown(dockSlot));
        if (!drawerSlot.isVisible() || isModal()) {
            drawer.close();
        }
        boolean rightColumn = shown(contactsSlot) || shown(weaponsSlot) || shown(logSlot) || shown(solutionSlot)
              || shown(otherUnitSlot);
        float drawerX = dockSlot.getX() + dockSlot.getWidth() + STACK;
        float drawerRight = rightColumn ? bandRight + gap : width - gap;
        int besideColumns = Math.min(GpuMoreDrawer.columnsIn(drawerRight - drawerX), drawer.columnsAt(dockHeight));
        // A short drawer can fit below the minimap, but a taller one must fit to its left or above the dock.
        if (besideColumns > 0 && shown(minimapSlot)
              && overlapsHorizontally(minimapSlot, drawerX, GpuMoreDrawer.width(besideColumns))
              && gap + Math.max(dockHeight, drawer.height(besideColumns)) + STACK > minimapSlot.getY()) {
            besideColumns = Math.min(besideColumns,
                  GpuMoreDrawer.columnsIn(minimapSlot.getX() - drawerX - STACK));
        }
        drawer.above(besideColumns == 0);
        if (besideColumns > 0) {
            float drawerHeight = Math.min(Math.max(dockHeight, drawer.height(besideColumns)), dockTop + dockHeight - gap);
            place(drawerSlot, drawerX, dockTop + dockHeight - drawerHeight, GpuMoreDrawer.width(besideColumns),
                  drawerHeight);
        } else {
            float room = Math.max(0, dockTop - STACK - gap);
            int columns = Math.max(1, Math.min(GpuMoreDrawer.columnsIn(dockWidth),
                  drawer.columnsAt(Math.min(dockHeight, room))));
            float aboveHeight = Math.min(Math.max(dockHeight, drawer.height(columns)), room);
            place(drawerSlot, dockSlot.getX(), dockTop - STACK - aboveHeight, GpuMoreDrawer.width(columns),
                  aboveHeight);
        }

        // Top row: the conditions card beside the left column (the sheet's width while it is open), the tactical chip
        // centred in the room that is left.
        float conditionsX = gap + leftWidth() + STACK;
        // A narrow window has no space beside the toolbar; keep the card usable in the middle band below it.
        boolean conditionsBelow = conditionsX + CONDITIONS_WIDTH + STACK > utilityLeft;
        float conditionsRight = conditionsBelow ? bandRight : utilityLeft - STACK;
        float conditionsWidth = Math.max(0, Math.min(CONDITIONS_WIDTH, conditionsRight - conditionsX));
        if (!state.conditionsExpanded) {
            conditionsWidth = conditionsCard.stripWidth(conditionsWidth);
        }
        place(conditionsSlot, conditionsX, conditionsBelow ? rightStart : gap, conditionsWidth, height(conditionsSlot));
        float chipLeft = Math.max(gap + m.left(), shown(conditionsSlot) ? conditionsX + conditionsWidth : 0) + STACK;
        float chipRight = utilityLeft - STACK;
        float chipWidth = chipSlot.getPrefWidth();
        chipSlot.setVisible(chipRight - chipLeft >= chipWidth);
        place(chipSlot, centred(chipWidth, chipLeft, chipRight), gap, chipWidth, height(chipSlot));
        // The north mark stays centred under the chip's place, whether or not the chip has room (G4).
        float northWidth = northSlot.getPrefWidth();
        place(northSlot, (width - northWidth) / 2, GpuUtilityBar.NORTH_TOP, northWidth, northSlot.getPrefHeight());

        // The Tactical View's overlays: a column at the top right, left of the right column's top panel (the minimap,
        // else the panel that moves up in its place), at the window's right edge while the column is empty. Expanded
        // as saved, unless the window is too short for its rows or a card beside the right column needs the room:
        // then its header for the frame, and the card stacks below it.
        float initiativeWidth = Math.min(INITIATIVE_WIDTH, bandRight - bandLeft);
        float initiativeX = centred(initiativeWidth, bandLeft, bandRight);
        if (shown(overlaysSlot)) {
            float column = Math.max(shown(minimapSlot) ? m.right() : 0,
                  shown(contactsSlot) || shown(weaponsSlot) || shown(logSlot) || shown(otherUnitSlot) ? rightWidth : 0);
            float overlaysWidth = overlays.width();
            float overlaysX = width - gap - (column > 0 ? column + gap : 0) - overlaysWidth;
            boolean crowded = beside || shown(initiativeSlot) && initiativeX < overlaysX + overlaysWidth
                  && overlaysX < initiativeX + initiativeWidth;
            boolean expanded = overlays.open() && !crowded
                  && rightStart + overlays.height(true) <= height - RIGHT_BOTTOM;
            overlays.show(expanded);
            place(overlaysSlot, overlaysX, rightStart, overlaysWidth, overlays.height(expanded));
        }

        // Panels of the middle area, each below what it would overlap; one without room for its minimum is hidden.
        stack(initiativeSlot, initiativeX, height * INITIATIVE_TOP, initiativeWidth,
              List.of(conditionsSlot, chipSlot, overlaysSlot));
        if (beside) {
            stack(solutionSlot, width - gap - 2 * m.right() - STACK, SECOND_ROW, m.right(),
                  List.of(conditionsSlot, chipSlot, overlaysSlot));
        }

        // Overlays: forces overview, centred dialogs and toasts.
        place(overviewSlot, gap, SECOND_ROW, width - 2 * gap, height - SECOND_ROW - RIGHT_BOTTOM);
        float dialogWidth = Math.min(UiKit.DIALOG_WIDTH, width - 2 * gap);
        // The game menu is the lobby's, a column of buttons as wide as its dialog there
        float menuWidth = Math.min(DesignTokens.shipped().size().get("dialog.sm"), width - 2 * gap);
        for (Container<Actor> dialog : List.of(helpSlot, menuSlot, playersSlot)) {
            float dialogHeight = Math.min(height(dialog), height - UiKit.DIALOG_MARGIN);
            float shownWidth = dialog == menuSlot ? menuWidth : dialogWidth;
            place(dialog, (width - shownWidth) / 2, (height - dialogHeight) / 2, shownWidth, dialogHeight);
        }
        float toastWidth = Math.min(GpuToastStack.MAX_WIDTH, width - 2 * gap);
        place(toastSlot, (width - toastWidth) / 2, SECOND_ROW, toastWidth, height - SECOND_ROW - TOAST_BOTTOM);
        dockSlot.validate();
        dock.layoutConfirmation(width, height);
    }

    /**
     * Places a panel of the middle area at {@code top}, or below every shown panel of {@code above} it overlaps, and
     * keeps it above the dock, hint line and a pick's chip where they share columns and above the prototype's bottom margin
     * elsewhere. A panel with less room than its minimum height (a scrolling panel's header) is hidden for the frame:
     * its slot would keep that minimum and spill over the dock.
     */
    private void stack(Container<Actor> slot, float x, float top, float width, List<Container<Actor>> above) {
        float height = metrics.height();
        for (Container<Actor> other : above) {
            if (shown(other) && overlapsHorizontally(other, x, width)) {
                top = Math.max(top, height - other.getY() + STACK);
            }
        }
        float bottom = height - RIGHT_BOTTOM;
        for (Container<Actor> below : List.of(dockSlot, chatSlot, hintSlot, pickSlot, otherUnitSlot)) {
            if (shown(below) && overlapsHorizontally(below, x, width)) {
                bottom = Math.min(bottom, height - below.getY() - below.getHeight() - STACK);
            }
        }
        if (shown(slot) && bottom - top < slot.getMinHeight()) {
            slot.setVisible(false);
        }
        place(slot, x, top, width, Math.min(height(slot), bottom - top));
    }

    private static boolean overlapsHorizontally(Actor actor, float x, float width) {
        return x < actor.getX() + actor.getWidth() && actor.getX() < x + width;
    }

    /** Centred in the window when that fits between {@code left} and {@code right}, else as close as it can. */
    private float centred(float width, float left, float right) {
        return MathUtils.clamp((metrics.width() - width) / 2, left, right - width);
    }

    /** Places a slot by its distance from the window's top, as the prototype's CSS does. */
    private void place(Container<Actor> slot, float x, float top, float width, float height) {
        slot.setBounds(x, metrics.height() - top - Math.max(0, height), width, Math.max(0, height));
    }

    /** The slot's content height, or zero while the HUD or the component hides it. */
    private static float height(Container<Actor> slot) {
        return shown(slot) ? slot.getPrefHeight() : 0;
    }

    private static boolean shown(Container<Actor> slot) {
        return slot.isVisible() && slot.getActor() != null && slot.getActor().isVisible();
    }

    @Override
    public void dispose() {
        components.forEach(Component::dispose);
        stage.dispose();
        kit.dispose();
    }
}
