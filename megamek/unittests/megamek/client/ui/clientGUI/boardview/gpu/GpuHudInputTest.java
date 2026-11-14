/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.boardview.NameplateMode;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GpuHud's key dispatch, Esc chain, focus rule, presented units and board-click routing, without a GL context: the
 * source and its services are mocks, so every command the HUD posts is recorded and nothing reaches Swing.
 */
class GpuHudInputTest {
    private static final int SECOND = 2;
    private static final int THIRD = 3;
    private static final int FOE = 7;
    private static final int BLIP = 8;
    private static final GpuMovePlan.Step STEP = new GpuMovePlan.Step(GpuHudFixtures.HEX, 0, 0, 0, GpuMovePlan.Band.WALK, false);

    /** A key as the board view passes it: the libGDX code and the AWT code and modifiers. */
    private record Key(int gdx, int awt, int modifiers) { }

    private Application application;
    private Files files;
    private Graphics graphics;
    private GL20 gl;
    private GL20 gl20;
    private Input input;
    private final GpuBoardSource source = mock(GpuBoardSource.class);
    private final GpuMovePlan moves = mock(GpuMovePlan.class);
    private final GpuFireOrders fire = mock(GpuFireOrders.class);
    private final GpuPhysicalOptions physical = mock(GpuPhysicalOptions.class);
    private final GpuUnitRecord record = mock(GpuUnitRecord.class);
    private GpuBoardSkin theme;
    private GpuHud hud;

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @BeforeEach
    void createHud() {
        application = Gdx.app;
        files = Gdx.files;
        graphics = Gdx.graphics;
        gl = Gdx.gl;
        gl20 = Gdx.gl20;
        input = Gdx.input;
        // Scene2D reads these statics (a Table cell needs Gdx.files); the HUD never draws in this test. The components
        // build their widgets from the HUD skin, which bakes its fonts and icons against the mocked GL (libGDX's
        // desktop mipmap path reads Gdx.gl20).
        Gdx.app = mock(Application.class);
        Gdx.files = mock(Files.class);
        // The HUD skin reads its enemy icon from the classpath.
        when(Gdx.files.classpath(anyString())).thenAnswer(call -> new Lwjgl3Files().classpath(call.getArgument(0)));
        Gdx.graphics = mock(Graphics.class);
        Gdx.gl = mock(GL20.class);
        AtomicInteger textures = new AtomicInteger();
        when(Gdx.gl.glGenTexture()).thenAnswer(call -> textures.incrementAndGet());
        Gdx.gl20 = Gdx.gl;
        Gdx.input = mock(Input.class);
        when(source.moves()).thenReturn(moves);
        when(source.fire()).thenReturn(fire);
        when(source.physical()).thenReturn(physical);
        when(source.record()).thenReturn(record);
        theme = new GpuBoardSkin();
        hud = new GpuHud(source, theme.skin, mock(Batch.class), new BoardCamera(), mock(GpuBoardTuning.class),
              new GpuPlaybackHistory(new UnitPlayback()));
        hud.resize(1920, 1080, 1);
    }

    @AfterEach
    void restoreGdx() {
        try {
            if (hud != null) {
                hud.dispose();
            }
            if (theme != null) {
                theme.dispose();
            }
        } finally {
            Gdx.app = application;
            Gdx.files = files;
            Gdx.graphics = graphics;
            Gdx.gl = gl;
            Gdx.gl20 = gl20;
            Gdx.input = input;
        }
    }

    @Test
    void escapeWithTheOverviewOpenClosesOnlyTheOverviewThenWalksTheChain() {
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(FOE, ENEMY, false, false));
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        hud.state.overview = true;
        hud.state.chatOpen = true;
        hud.state.inspected = FOE;

        assertTrue(escape());
        assertFalse(hud.state.overview);
        assertTrue(hud.state.chatOpen);
        assertEquals(FOE, hud.state.inspected);
        verify(moves, never()).clearRoute();

        assertTrue(escape());
        assertFalse(hud.state.chatOpen);
        assertTrue(escape());
        verify(moves, times(1)).clearRoute();
        assertEquals(FOE, hud.state.inspected);
        // The EDT publishes the cleared route.
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertTrue(escape());
        assertEquals(Entity.NONE, hud.state.inspected);
        assertFalse(hud.state.turnLocked(moving));
        assertTrue(escape(), "then the selection clears, as the card's close button clears it");
        assertTrue(hud.state.turnLocked(moving));
        assertEquals(Entity.NONE, hud.state.ownCardUnit());
        assertTrue(escape(), "with nothing left to close, Esc opens the Menu instead of reaching the phase display");
        assertEquals(GpuHudState.Dialog.MENU, hud.state.dialog);
        assertTrue(escape());
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog, "Esc closes the Menu again");
        assertTrue(escape());
        assertEquals(GpuHudState.Dialog.MENU, hud.state.dialog, "and opens it again: Esc alternates");
        hud.state.dialog = GpuHudState.Dialog.NONE;

        // OFFBOARD runs the targeting display, whose CANCEL clears the declared attacks as well. A new phase restores
        // the selection, so its first Esc clears it again.
        for (GamePhase phase : List.of(GamePhase.FIRING, GamePhase.TARGETING, GamePhase.OFFBOARD,
              GamePhase.PHYSICAL)) {
            update(GpuHudFixtures.status(3, phase, true, GpuHudFixtures.FIRST, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true)), GpuHudData.EMPTY, null);
            assertTrue(escape());
            assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog, phase + ": the selection goes first");
            assertTrue(escape(), "CANCEL would clear declared attacks, so " + phase + " never forwards it");
            assertEquals(GpuHudState.Dialog.MENU, hud.state.dialog, phase + ": the last Esc opens the Menu");
            hud.state.dialog = GpuHudState.Dialog.NONE;
        }
        verify(moves, times(1)).clearRoute();
    }

    @Test
    void theGameMenuKeyTogglesTheMenuOverOtherDialogs() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0,
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true)));
        assertTrue(press(Input.Keys.F10, KeyEvent.VK_F10, 0), "F10 is the HUD's");
        assertEquals(GpuHudState.Dialog.MENU, hud.state.dialog);
        assertTrue(press(Input.Keys.F10, KeyEvent.VK_F10, 0));
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog, "F10 closes the Menu again");
        hud.state.dialog = GpuHudState.Dialog.HELP;
        assertTrue(press(Input.Keys.F10, KeyEvent.VK_F10, 0));
        assertEquals(GpuHudState.Dialog.MENU, hud.state.dialog, "F10 over Help switches to the Menu");
    }

    @Test
    void theSharedSelectMenuRestoresTheSameActorAfterTheCardClearsSelection() {
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1,
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true));
        GpuHudData panels = GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of(STEP)), GpuFireOrders.Snapshot.EMPTY,
              GpuPhysicalOptions.Snapshot.EMPTY, record(GpuHudFixtures.FIRST));
        update(moving, panels, null);
        hud.stage.getRoot().findActor("unit-card-close").fire(new ChangeListener.ChangeEvent());
        assertTrue(hud.state.turnLocked(moving));
        assertEquals(Entity.NONE, hud.state.cardUnit());
        update(moving, panels, null);
        clearInvocations(source);

        hud.boardClick(GpuHudFixtures.HEX, GpuHudFixtures.FIRST, Input.Buttons.RIGHT, 0, 100, 100, Float.NaN);
        verify(source).inspect(GpuHudFixtures.HEX, GpuHudFixtures.FIRST, true);
        BoardScene.Command select = new BoardScene.Command("select-current", "Select current unit", "", true,
              false, false, List.of(), () -> {
                  throw new AssertionError("The shared Select must route through the HUD selection controller");
              }, "", null, new MapMenu.UnitAction(MapMenu.UnitActionType.SELECT, GpuHudFixtures.FIRST));
        BoardScene scene = mock(BoardScene.class);
        BoardScene.Context stale = new BoardScene.Context(GpuHudFixtures.HEX, GpuHudFixtures.FIRST, List.of(select));
        hud.update(new GpuBoardSource.Frame(scene, List.of(), stale, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, moving, panels), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        Actor menu = hud.stage.getRoot().findActor("context-menu-popover");
        assertTrue(menu.isVisible());
        assertNull(menuButton(menu, select.label()), "A capture made before selection cleared stays masked");

        BoardScene.Context cleared = new BoardScene.Context(GpuHudFixtures.HEX, GpuHudFixtures.FIRST, List.of(select), true);
        hud.update(new GpuBoardSource.Frame(scene, List.of(), cleared, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, moving, panels), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        UiButton item = menuButton(menu, select.label());
        assertNotNull(item, "The menu captured without an actor can restore the cleared selection");
        assertFalse(item.isDisabled());
        item.fire(new ChangeListener.ChangeEvent());

        assertFalse(hud.state.turnLocked(moving));
        assertEquals(GpuHudFixtures.FIRST, hud.state.cardUnit());
        verify(source).selectUnit(GpuHudFixtures.FIRST);
    }

    private static UiButton menuButton(Actor actor, String label) {
        if (actor instanceof UiButton item) {
            return item.getText().toString().equals(label) ? item : null;
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                UiButton found = menuButton(child, label);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    @Test
    void theUnitDisplayKeysOpenTheUnitSheetOnTheirTabs() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true)));
        int[] keys = { KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4, KeyEvent.VK_F5, KeyEvent.VK_F6 };
        for (int index = 0; index < keys.length; index++) {
            assertTrue(press(Input.Keys.F1 + index, keys[index], 0), "F" + (index + 1) + " is the HUD's");
            assertEquals(GpuHudState.SheetTab.values()[index], hud.state.sheetTab);
            assertTrue(hud.state.recordOpen);
        }
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true)));
        verify(record).setSheetOpen(true);
        assertTrue(press(Input.Keys.D, KeyEvent.VK_D, CTRL_DOWN_MASK));
        assertFalse(hud.state.recordOpen, "Ctrl+D toggles the sheet");
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true)));
        verify(record).setSheetOpen(false);
        hud.state.overview = true;
        assertTrue(press(Input.Keys.D, KeyEvent.VK_D, CTRL_DOWN_MASK));
        assertTrue(hud.state.recordOpen && !hud.state.overview, "over the overview it closes it and shows the sheet");
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true)));
        assertTrue(press(Input.Keys.G, KeyEvent.VK_G, 0));
        assertTrue(hud.state.forcesGrid && !hud.state.recordOpen, "one wide left panel at a time: G shows the grid");
        assertEquals(GpuHudState.SheetTab.EXTRAS, hud.state.sheetTab, "the tab stays for the next opening");
    }

    @Test
    void nonPlannerMovementKeepsMegaMeksOwnKeys() {
        // An aerospace or vector route is MegaMek's own (plan A.7 G20): its keys and CANCEL reach the phase display.
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true)),
              GpuHudFixtures.panels(GpuHudFixtures.move(false, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        for (Key key : List.of(new Key(Input.Keys.A, KeyEvent.VK_A, SHIFT_DOWN_MASK),
              new Key(Input.Keys.D, KeyEvent.VK_D, SHIFT_DOWN_MASK),
              new Key(Input.Keys.BACKSPACE, KeyEvent.VK_BACK_SPACE, 0),
              new Key(Input.Keys.FORWARD_DEL, KeyEvent.VK_DELETE, 0), new Key(Input.Keys.NUM_1, KeyEvent.VK_1, 0),
              new Key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0))) {
            assertFalse(press(key.gdx(), key.awt(), key.modifiers()), "key " + key + " was consumed");
        }
        // A hex pick or a gear without a path is MegaMek's to drop as well.
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1,
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true));
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(false, List.of(), true), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertFalse(escape(), "Esc was consumed");
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);
        // With nothing of MegaMek's to drop, Esc goes on: the selection clears, then the Menu opens.
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(false, List.of(), false), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertTrue(escape());
        assertTrue(hud.state.turnLocked(moving));
        assertTrue(escape());
        assertEquals(GpuHudState.Dialog.MENU, hud.state.dialog);
        verifyNoInteractions(moves);
    }

    @Test
    void escapeReleasesAChosenPhysicalAttackBeforeTheSelection() {
        GpuPhysicalOptions.Option kick = new GpuPhysicalOptions.Option("kickLeft", "Kick L", "L", true, "", 4, 92, 20,
              "Kick table", List.of(), false);
        GpuBattleStatus.Snapshot turn = GpuHudFixtures.status(3, GamePhase.PHYSICAL, true, GpuHudFixtures.FIRST, 1,
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true), GpuHudFixtures.unit(FOE, ENEMY, false, false));
        GpuHudData panels = GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY, new GpuPhysicalOptions.Snapshot(true,
              GpuHudFixtures.FIRST, FOE, List.of(FOE), List.of(kick)));
        update(turn, panels, null);
        UiButton option = hud.stage.getRoot().findActor("dock-option-kickLeft");
        option.fire(new ChangeListener.ChangeEvent());
        update(turn, panels, null);
        assertTrue(option.isChecked());

        assertTrue(escape());
        update(turn, panels, null);
        assertFalse(option.isChecked(), "the kick is released, undeclared");
        assertFalse(hud.state.turnLocked(turn), "the selection stays for the next Esc");
        verify(physical, never()).declare(any());
        assertTrue(escape());
        assertTrue(hud.state.turnLocked(turn));
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);
    }

    @Test
    void aFocusedGripReordersOnAltUpAndLeavesOtherKeysToTheHotkeys() {
        update(GpuHudFixtures.status(3, GamePhase.FIRING, true, GpuHudFixtures.FIRST, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true)), GpuHudData.EMPTY, null);
        Table grip = new Table();
        grip.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.UP && UIUtils.alt()) {
                    source.fire().move(5, -1);
                    return true;
                }
                return false;
            }
        });
        hud.stage.addActor(grip);
        hud.stage.setKeyboardFocus(grip);
        when(Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn(true);

        assertTrue(press(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK));
        assertFalse(hud.state.allNameplates, "Alt with a focused grip belongs to the reordering, not to the nameplates");
        assertTrue(press(Input.Keys.UP, KeyEvent.VK_UP, ALT_DOWN_MASK), "never forwarded as CALLED_SHOT_HIGH");
        verify(fire, times(1)).move(5, -1);

        assertTrue(press(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK));
        assertTrue(hud.state.logOpen(), "a key the grip does not use still reaches the hotkeys");
        assertTrue(escape());
        assertNull(hud.stage.getKeyboardFocus(), "the first Esc only ends the grip's focus");
        assertTrue(hud.state.logOpen());

        hud.stage.setKeyboardFocus(grip);
        hud.boardPress();
        assertNull(hud.stage.getKeyboardFocus(), "a press on the board ends the grip's focus as well");
    }

    @Test
    void aFocusedTextFieldTakesTheDigitThatWouldOtherwiseSetTheMovementMode() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true)),
              GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        TextField field = textField();
        hud.stage.addActor(field);
        hud.stage.setKeyboardFocus(field);

        assertTrue(hud.isTextEditing());
        assertTrue(press(Input.Keys.NUM_1, KeyEvent.VK_1, 0));
        assertTrue(hud.keyTyped('1'));
        assertEquals("1", field.getText());
        verify(moves, never()).setMode(any());
        // A shortcut the field itself ignores is kept from the hotkeys as well.
        when(Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(true);
        assertTrue(press(Input.Keys.K, KeyEvent.VK_K, CTRL_DOWN_MASK));
        when(Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(false);
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);

        assertTrue(escape());
        assertFalse(hud.isTextEditing());
        assertTrue(press(Input.Keys.NUM_1, KeyEvent.VK_1, 0));
        verify(moves, times(1)).setMode(GpuMovePlan.Mode.WALK);
        assertEquals("1", field.getText());
    }

    @Test
    void theEnterThatOpensTheChatIsNotTypedIntoIt() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0));
        assertTrue(press(Input.Keys.ENTER, KeyEvent.VK_ENTER, 0));
        assertTrue(hud.state.chatOpen);
        // GLFW follows Enter with a typed newline, which reaches the chat's input focused by then (G15).
        List<Character> typed = new ArrayList<>();
        TextField chatInput = textField();
        chatInput.setTextFieldListener((field, character) -> typed.add(character));
        hud.stage.addActor(chatInput);
        hud.stage.setKeyboardFocus(chatInput);

        assertTrue(hud.keyTyped('\n'));
        assertTrue(hud.keyTyped('1'));
        assertEquals(List.of('1'), typed, "only the message's own characters reach the input");
    }

    @Test
    void releasingTheNameplateKeyEndsTheNameplates() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0));
        assertTrue(press(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK));
        assertTrue(hud.state.allNameplates);
        // The release carries the key only, so a Shift or Ctrl still held does not keep the plates.
        assertTrue(hud.keyUp(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT), "the consumed press's release is consumed too");
        assertFalse(hud.state.allNameplates);
    }

    @Test
    void theToggleSettingKeepsTheNameplatesUntilTheNextPress() {
        hud.update(GpuHudFixtures.frame(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0), GpuHudData.EMPTY),
              GpuHud.HudView.EMPTY, null, GpuHudFixtures.with(GpuHudFixtures.preferences(), Map.of("nameplates", NameplateMode.TOGGLE)));
        assertTrue(press(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK));
        hud.keyUp(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT);
        hud.focusLost();
        assertTrue(hud.state.allNameplates, "neither the release nor a lost focus ends toggled nameplates");
        assertTrue(press(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK));
        assertFalse(hud.state.allNameplates);
    }

    @Test
    void aPendingDialogTakesEveryKeyAndClickAndPostsNoCommand() {
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(FOE, ENEMY, false, false));
        GpuHudData planned = GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY);
        update(moving, planned, null);
        hud.state.overview = true;
        hud.state.inspected = FOE;
        update(moving, planned, message());
        Actor modal = hud.stage.getRoot().findActor("modal-dialog");
        assertSame(modal, hud.stage.getKeyboardFocus(), "the pending dialog holds the keyboard focus");
        List<Integer> modalKeys = new ArrayList<>();
        modal.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                modalKeys.add(keycode);
                return false;
            }
        });
        clearInvocations(source);

        for (Key key : List.of(new Key(Input.Keys.NUM_1, KeyEvent.VK_1, 0), new Key(Input.Keys.T, KeyEvent.VK_T, 0),
              new Key(Input.Keys.G, KeyEvent.VK_G, 0), new Key(Input.Keys.SPACE, KeyEvent.VK_SPACE, 0),
              new Key(Input.Keys.FORWARD_DEL, KeyEvent.VK_DELETE, 0),
              new Key(Input.Keys.BACKSPACE, KeyEvent.VK_BACK_SPACE, 0),
              new Key(Input.Keys.A, KeyEvent.VK_A, SHIFT_DOWN_MASK),
              new Key(Input.Keys.M, KeyEvent.VK_M, CTRL_DOWN_MASK),
              new Key(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK),
              new Key(Input.Keys.U, KeyEvent.VK_U, CTRL_DOWN_MASK),
              new Key(Input.Keys.K, KeyEvent.VK_K, CTRL_DOWN_MASK),
              new Key(Input.Keys.ENTER, KeyEvent.VK_ENTER, CTRL_DOWN_MASK),
              new Key(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK),
              new Key(Input.Keys.ENTER, KeyEvent.VK_ENTER, 0), new Key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0),
              new Key(Input.Keys.UP, KeyEvent.VK_UP, 0), new Key(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0),
              new Key(Input.Keys.LEFT, KeyEvent.VK_LEFT, 0), new Key(Input.Keys.RIGHT, KeyEvent.VK_RIGHT, 0))) {
            assertTrue(press(key.gdx(), key.awt(), key.modifiers()), "key " + key + " was not consumed");
        }
        assertTrue(hud.hit(10, 10), "the scrim takes every press");
        hud.boardPress();
        hud.boardClick(GpuHudFixtures.HEX, Entity.NONE, Input.Buttons.LEFT, 0, 10, 10, Float.NaN);
        assertSame(modal, hud.stage.getKeyboardFocus());

        assertTrue(modalKeys.containsAll(List.of(Input.Keys.ENTER, Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT,
              Input.Keys.RIGHT)), "the dialog's own controls receive Enter and the arrows: " + modalKeys);
        assertFalse(modalKeys.contains(Input.Keys.ESCAPE), "Esc presses the dialog's cancel button instead");
        verifyNoInteractions(moves, fire, physical);
        verify(source, never()).selectUnit(anyInt());
        verify(source, never()).locateUnit(anyInt());
        verify(source, never()).click(any(), anyBoolean(), anyInt());
        verify(source, never()).hover(any(), anyInt());
        verify(source, never()).key(anyInt(), anyBoolean(), anyInt());
        verify(source, never()).command(any());
        assertTrue(hud.state.overview, "the Esc chain behind the dialog did not run");
        assertEquals(FOE, hud.state.inspected);
        assertFalse(hud.state.logOpen());
        assertFalse(hud.state.chatOpen);
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);

        update(moving, planned, null);
        assertNull(hud.stage.getKeyboardFocus(), "an answered dialog keeps no focus");
    }

    @Test
    void aGameDialogStillUsesTheConfiguredCancelKey() {
        var waiting = GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 1);
        var preferences = GpuHudFixtures.with(GpuHudFixtures.preferences(), Map.of("binds", List.of(
              new GpuBoardSource.Bind(KeyCommandBind.CANCEL, KeyEvent.VK_Q, CTRL_DOWN_MASK, "Ctrl+Q"))));
        hud.update(GpuHudFixtures.frame(waiting, GpuHudData.EMPTY), GpuHud.HudView.EMPTY, message(), preferences);
        clearInvocations(source);
        assertTrue(press(Input.Keys.Q, KeyEvent.VK_Q, CTRL_DOWN_MASK));
        verify(source).answer(message().id(), GpuBoardWindow.DialogAnswer.cancelled(message()));
    }

    @Test
    void anOverlayKeepsItsTextFocusWhenAGameDialogArrivesUnderneath() {
        var waiting = GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 1);
        update(waiting);
        UiModal overlay = new UiModal(new UiKit(theme.skin), () -> { }, false);
        TextField field = textField();
        overlay.add(field);
        hud.stage.addActor(overlay);
        overlay.open(true);
        hud.stage.setKeyboardFocus(field);
        update(waiting, GpuHudData.EMPTY, message());
        assertSame(field, hud.stage.getKeyboardFocus(), "The arriving game prompt must not steal the picker's text focus");
        update(waiting, GpuHudData.EMPTY, message());
        assertSame(field, hud.stage.getKeyboardFocus());
        assertSame(overlay, UiModal.active(hud.stage));
        overlay.open(false);
        overlay.remove();
        update(waiting, GpuHudData.EMPTY, message());
        assertSame(hud.stage.getRoot().findActor("modal-dialog"), hud.stage.getKeyboardFocus());
    }

    @Test
    void theFocusFollowsPhaseStartCommitAndTheBeginningOfTheLocalTurn() {
        // Phase start, the opponent's turn: the first pending own unit by id.
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, GpuHudFixtures.unit(THIRD, OWN, false, true),
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true), GpuHudFixtures.unit(SECOND, OWN, false, true), GpuHudFixtures.unit(FOE, ENEMY, false, true)));
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());
        verify(source).setFocusUnit(GpuHudFixtures.FIRST);

        // The local turn begins with a dialog pending and the phase display on the third unit: the HUD selects the
        // focus once, as soon as no dialog would drop the command.
        GpuBattleStatus.Snapshot begun = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, THIRD, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true), GpuHudFixtures.unit(THIRD, OWN, true, true));
        update(begun, GpuHudData.EMPTY, message());
        verify(source, never()).selectUnit(anyInt());
        update(begun);
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, THIRD, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true), GpuHudFixtures.unit(THIRD, OWN, true, true)));
        verify(source, times(1)).selectUnit(GpuHudFixtures.FIRST);
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true), GpuHudFixtures.unit(THIRD, OWN, true, true)));
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());

        // A selection during the local turn moves the focus.
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, THIRD, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true), GpuHudFixtures.unit(THIRD, OWN, true, true)));
        assertEquals(THIRD, hud.state.focus());

        // The actor commits: its update arrives before the turn ends, and the focus moves once, to the next pending
        // unit after it, wrapping around to the first.
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, Entity.NONE, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true), GpuHudFixtures.unit(THIRD, OWN, false, false)));
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, THIRD, 2, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true),
              GpuHudFixtures.unit(SECOND, OWN, false, true), GpuHudFixtures.unit(THIRD, OWN, false, false)));
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());

        // A turn for one unit only: the focus cannot act, so the HUD adopts the display's actor and selects nothing.
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, SECOND, 4, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true), GpuHudFixtures.unit(THIRD, OWN, false, false)));
        assertEquals(SECOND, hud.state.focus());

        // Its commit leaves the first unit as the only candidate.
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, SECOND, 5, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true),
              GpuHudFixtures.unit(SECOND, OWN, false, false), GpuHudFixtures.unit(THIRD, OWN, false, false)));
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());

        // The next phase starts at the first pending unit again.
        update(GpuHudFixtures.status(3, GamePhase.FIRING, false, Entity.NONE, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true),
              GpuHudFixtures.unit(SECOND, OWN, false, true), GpuHudFixtures.unit(THIRD, OWN, false, true)));
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());
        verify(source, times(1)).selectUnit(anyInt());
    }

    @Test
    void theLogOpensForTheReportPhasesAndKeepsALogThePlayerOpened() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0));
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT_REPORT, false, Entity.NONE, 0));
        assertTrue(hud.state.logOpen(), "the report phase opens the log");
        update(GpuHudFixtures.status(3, GamePhase.FIRING, false, Entity.NONE, 0));
        assertFalse(hud.state.logOpen(), "and the next phase closes it again");

        assertTrue(press(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK));
        update(GpuHudFixtures.status(3, GamePhase.FIRING_REPORT, false, Entity.NONE, 0));
        update(GpuHudFixtures.status(3, GamePhase.PHYSICAL, false, Entity.NONE, 0));
        assertTrue(hud.state.logOpen(), "a log the player opened stays open after the report phase");

        assertTrue(press(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK));
        update(GpuHudFixtures.status(4, GamePhase.INITIATIVE_REPORT, false, Entity.NONE, 0));
        assertFalse(hud.state.logOpen(), "the initiative report does not open it");
    }

    @Test
    void unitsStayPresentedUntilThePlaybackHasShownTheirEvents() {
        GpuBattleStatus.UnitStatus before = GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, false);
        GpuBattleStatus.UnitStatus after = GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, false, .4);
        GpuUnitRecord.Snapshot record = record(GpuHudFixtures.FIRST);
        GpuUnitRecord.Snapshot damaged = record(GpuHudFixtures.FIRST);
        hud.update(GpuHudFixtures.frame(GpuHudFixtures.status(3, GamePhase.FIRING_REPORT, false, Entity.NONE, 0, before),
              GpuHudFixtures.panels(record)), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        GpuHud.HudView playing = new GpuHud.HudView(false, true, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);

        GpuBattleStatus.Snapshot newer = GpuHudFixtures.status(3, GamePhase.FIRING_REPORT, false, Entity.NONE, 0, after);
        hud.update(GpuHudFixtures.frame(newer, GpuHudFixtures.panels(damaged)), playing, null, GpuHudFixtures.preferences());
        assertEquals(List.of(before), hud.state.presentedUnits());
        assertSame(record, hud.state.presentedRecord());
        GpuUnitRecord.Snapshot other = record(SECOND);
        hud.update(GpuHudFixtures.frame(newer, GpuHudFixtures.panels(other)), playing, null, GpuHudFixtures.preferences());
        assertSame(record, hud.state.presentedRecord(), "an automatic next-unit record must wait for playback");
        hud.state.inspected = SECOND;
        hud.update(GpuHudFixtures.frame(newer, GpuHudFixtures.panels(record(SECOND))), playing, null, GpuHudFixtures.preferences());
        assertEquals(SECOND, hud.state.presentedRecord().unitId(), "explicit inspection is still available");

        hud.update(GpuHudFixtures.frame(newer, GpuHudFixtures.panels(damaged)), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        assertEquals(List.of(after), hud.state.presentedUnits());
        assertSame(damaged, hud.state.presentedRecord());
    }

    @Test
    void enemyAndAllyInspectionsKeepTheOwnCardAndClosingThemKeepsTheTurn() {
        for (GpuBattleStatus.Side side : List.of(ENEMY, GpuBattleStatus.Side.ALLY)) {
            GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 0,
                  GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true), GpuHudFixtures.unit(FOE, side, false, false));
            GpuUnitRecord.Snapshot own = record(GpuHudFixtures.FIRST);
            GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuHudFixtures.move(true, List.of()),
                  GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, record(FOE),
                  GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
                  RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY, own);
            update(moving, panels, null);
            hud.select(FOE);
            update(moving, panels, null);
            assertEquals(GpuHudFixtures.FIRST, hud.state.ownCardUnit());
            assertEquals(FOE, hud.state.otherCardUnit());
            assertSame(own, hud.state.presentedRecord(GpuHudFixtures.FIRST));
            assertTrue(shown(hud.stage.getRoot().findActor("unit-card")));
            assertTrue(shown(hud.stage.getRoot().findActor("other-unit-card")));
            hud.stage.getRoot().findActor("other-unit-card-close").fire(new ChangeListener.ChangeEvent());
            update(moving, panels, null);
            assertEquals(Entity.NONE, hud.state.otherCardUnit());
            assertEquals(GpuHudFixtures.FIRST, hud.state.ownCardUnit());
            assertFalse(hud.state.turnLocked(moving));
        }
    }

    @Test
    void bothInspectorRecordsWaitForPlaybackAndOwnInspectionSurvivesAForeignClick() {
        GpuBattleStatus.Snapshot report = GpuHudFixtures.status(3, GamePhase.FIRING_REPORT, false, Entity.NONE, 0,
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, false), GpuHudFixtures.unit(SECOND, OWN, false, false), GpuHudFixtures.unit(FOE, ENEMY, false, false));
        GpuUnitRecord.Snapshot own = record(SECOND);
        GpuUnitRecord.Snapshot enemy = record(FOE);
        GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, own,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY, own);
        update(report, panels, null);
        GpuHud.HudView playing = new GpuHud.HudView(false, true, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);
        hud.update(GpuHudFixtures.frame(report, panels), playing, null, GpuHudFixtures.preferences());
        hud.select(SECOND);
        hud.update(GpuHudFixtures.frame(report, panels), playing, null, GpuHudFixtures.preferences());
        hud.select(FOE);
        GpuHudData inspected = new GpuHudData(panels.phase(), panels.move(), panels.fire(), panels.physical(),
              enemy, panels.preview(), panels.chat(), panels.toasts(), panels.los(), panels.players(), own);
        hud.update(GpuHudFixtures.frame(report, inspected), playing, null, GpuHudFixtures.preferences());
        assertEquals(SECOND, hud.state.ownCardUnit());
        assertEquals(FOE, hud.state.otherCardUnit());
        GpuUnitRecord.Snapshot changedOwn = record(SECOND);
        GpuUnitRecord.Snapshot changedEnemy = record(FOE);
        GpuHudData changed = new GpuHudData(panels.phase(), panels.move(), panels.fire(), panels.physical(),
              changedEnemy, panels.preview(), panels.chat(), panels.toasts(), panels.los(), panels.players(), changedOwn);
        hud.update(GpuHudFixtures.frame(report, changed), playing, null, GpuHudFixtures.preferences());
        assertSame(own, hud.state.presentedRecord(SECOND));
        assertSame(enemy, hud.state.presentedRecord(FOE));
        hud.update(GpuHudFixtures.frame(report, changed), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        assertSame(changedOwn, hud.state.presentedRecord(SECOND));
        assertSame(changedEnemy, hud.state.presentedRecord(FOE));
    }

    @Test
    void escSkipsARunningPlaybackOnceNothingIsLeftToClose() {
        BoardScene.Waypoint from = new BoardScene.Waypoint(GpuHudFixtures.HEX, 0, 0);
        BoardScene.Unit mover = new BoardScene.Unit(FOE, -1, "Unit " + FOE, from, null, false, null, 1, false);
        hud.state.history.accept(List.of(new BoardScene.Movement(FOE, 0,
              List.of(from, new BoardScene.Waypoint(new Coords(5, 6), 0, 0)), EntityMovementType.MOVE_WALK, 0, 4,
              mover)), mock(BoardScene.class), GpuReportLog.Snapshot.EMPTY, ignored -> false);
        assertTrue(hud.state.history.running());
        GpuHud.HudView playing = new GpuHud.HudView(false, true, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);
        hud.update(GpuHudFixtures.frame(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, false),
              GpuHudFixtures.unit(FOE, ENEMY, false, false)), GpuHudData.EMPTY), playing, null, GpuHudFixtures.preferences());

        assertTrue(press(Input.Keys.SPACE, KeyEvent.VK_SPACE, 0));
        assertEquals(UnitMotion.Speed.NORMAL, hud.state.history.speed(), "Space keeps its own binds");
        hud.state.inspected = FOE;
        assertTrue(escape());
        assertEquals(Entity.NONE, hud.state.inspected);
        assertEquals(UnitMotion.Speed.NORMAL, hud.state.history.speed(), "Esc closes what is open first");
        assertTrue(escape());
        assertEquals(UnitMotion.Speed.INSTANT, hud.state.history.speed(), "then skips to the results");
    }

    @Test
    void boardClicksFollowThePhaseAndTheLocalTurn() {
        GpuBattleStatus.UnitStatus actor = GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true);
        GpuBattleStatus.UnitStatus ready = GpuHudFixtures.unit(SECOND, OWN, true, true);
        GpuBattleStatus.UnitStatus foe = GpuHudFixtures.unit(FOE, ENEMY, false, false);
        GpuBattleStatus.UnitStatus blip = GpuHudFixtures.unit(BLIP, ENEMY, false, false, 1, true);
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, actor, ready, foe);
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(GpuHudFixtures.HEX, Entity.NONE, CTRL_DOWN_MASK);
        verify(moves).planTo(GpuHudFixtures.HEX, 0, true, Float.NaN);
        click(GpuHudFixtures.HEX, SECOND, 0);
        verify(source).selectUnit(SECOND);
        click(GpuHudFixtures.HEX, FOE, 0);
        assertEquals(FOE, hud.state.inspected);
        // Selecting the acting unit again would reset its path (MovementDisplay.selectEntity clears it).
        click(GpuHudFixtures.HEX, GpuHudFixtures.FIRST, 0);
        assertEquals(Entity.NONE, hud.state.inspected, "selecting ends the inspection");
        click(GpuHudFixtures.HEX, Entity.NONE, CTRL_DOWN_MASK);
        verify(source, never()).measure(GpuHudFixtures.HEX, CTRL_DOWN_MASK, Float.NaN);
        // At the height the pointer shows, ser's decision of 2026-10-03), the terrain hit's world height.
        hud.boardClick(GpuHudFixtures.HEX, Entity.NONE, Input.Buttons.LEFT, ALT_DOWN_MASK, 100, 100, 3.5f);
        verify(source).measure(GpuHudFixtures.HEX, ALT_DOWN_MASK, 3.5f);
        // Ctrl on an own unit pins a waypoint in its hex instead of selecting it.
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(GpuHudFixtures.HEX, SECOND, CTRL_DOWN_MASK);
        verify(moves, times(3)).planTo(GpuHudFixtures.HEX, 0, true, Float.NaN);
        verify(source, times(1)).selectUnit(anyInt());

        update(GpuHudFixtures.status(3, GamePhase.FIRING, true, GpuHudFixtures.FIRST, 1, actor, ready, foe, blip), GpuHudData.EMPTY, null);
        click(GpuHudFixtures.HEX, FOE, 0);
        verify(fire).focusTarget(TargetKey.unit(FOE));
        click(GpuHudFixtures.HEX, BLIP, 0);
        // The fire orders refuse a sensor contact with their toast (H19).
        verify(fire).focusTarget(TargetKey.unit(BLIP));
        click(GpuHudFixtures.HEX, SECOND, 0);
        click(GpuHudFixtures.HEX, GpuHudFixtures.FIRST, 0);
        verify(fire, times(1)).selectUnit(anyInt());
        verify(fire).selectUnit(SECOND);
        verify(fire, times(2)).focusTarget(any(TargetKey.class));

        GpuPhysicalOptions.Snapshot adjacent = new GpuPhysicalOptions.Snapshot(true, GpuHudFixtures.FIRST, Entity.NONE, List.of(FOE),
              List.of());
        update(GpuHudFixtures.status(3, GamePhase.PHYSICAL, true, GpuHudFixtures.FIRST, 1, actor, foe), GpuHudFixtures.panels(GpuHudFixtures.move(false, List.of()), adjacent),
              null);
        click(GpuHudFixtures.HEX, FOE, 0);
        verify(physical).target(FOE);

        update(GpuHudFixtures.status(3, GamePhase.DEPLOYMENT, true, GpuHudFixtures.FIRST, 1, actor), GpuHudData.EMPTY, null);
        click(GpuHudFixtures.HEX, Entity.NONE, 0);
        verify(source).hover(GpuHudFixtures.HEX, 0);
        verify(source).click(GpuHudFixtures.HEX, false, 0);

        // Outside the local turn an own unit becomes the shown unit, the focus:
        // MegaMek selects nothing then, and the orders stay as they are.
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 2, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true),
              GpuHudFixtures.unit(SECOND, OWN, false, true)), GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());
        clearInvocations(moves, source);
        click(GpuHudFixtures.HEX, SECOND, 0);
        assertEquals(Entity.NONE, hud.state.inspected);
        assertEquals(SECOND, hud.state.focus());
        verify(source, never()).selectUnit(anyInt());
        hud.boardClick(GpuHudFixtures.HEX, SECOND, Input.Buttons.RIGHT, 0, 30, 40, Float.NaN);
        verifyNoInteractions(moves);
    }

    /**
     * Repeated clicks on a stack take its units in turn in every phase, here
     * outside the local turn: while the shown unit stands in the clicked hex, a click takes the next unit there, so the
     * own units become the focus one after the other and the enemies, a sensor contact among them, are inspected one
     * after the other, whichever unit the pick hit. A lone unit stays taken.
     */
    @Test
    void repeatedClicksOnAStackTakeItsUnitsInTurn() {
        Coords enemies = new Coords(9, 9);
        Coords alone = new Coords(3, 3);
        GpuBattleStatus.Snapshot waiting = GpuHudFixtures.status(3, GamePhase.FIRING, false, Entity.NONE, 2,
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, false, true), GpuHudFixtures.unit(SECOND, OWN, false, true), GpuHudFixtures.unit(THIRD, OWN, false, true),
              GpuHudFixtures.unit(FOE, ENEMY, false, false), GpuHudFixtures.unit(BLIP, ENEMY, false, false, 1, true));
        BoardScene scene = scene(Map.of(GpuHudFixtures.HEX, List.of(GpuHudFixtures.FIRST, SECOND), enemies, List.of(FOE, BLIP), alone,
              List.of(THIRD)));
        hud.update(GpuHudFixtures.frame(waiting, GpuHudData.EMPTY, scene), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        assertEquals(GpuHudFixtures.FIRST, hud.state.focus());
        assertEquals(SECOND, hud.clickedUnit(GpuHudFixtures.HEX, GpuHudFixtures.FIRST), "the hover shows the unit the click takes");
        List<Integer> taken = new ArrayList<>();
        for (int count = 0; count < 3; count++) {
            click(GpuHudFixtures.HEX, GpuHudFixtures.FIRST, 0);
            taken.add(hud.state.focus());
        }
        assertEquals(List.of(SECOND, GpuHudFixtures.FIRST, SECOND), taken, "the focus stands in the hex: the next unit first");
        taken.clear();
        for (int count = 0; count < 3; count++) {
            click(enemies, BLIP, 0);
            taken.add(hud.state.inspected);
        }
        assertEquals(List.of(BLIP, FOE, BLIP), taken, "the picked unit first");
        click(alone, THIRD, 0);
        click(alone, THIRD, 0);
        assertEquals(List.of(THIRD, Entity.NONE), List.of(hud.state.focus(), hud.state.inspected));
        verify(source, never()).selectUnit(anyInt());
    }

    /**
     * In the local FIRING turn a stack's turn starts from the focused target, so repeated clicks focus its enemies in
     * turn, the one the card inspects last, and pass over its sensor contact, which a click cannot focus, and the wreck
     * the board draws there, which the status does not list: no click falls through to the hex's target choice.
     */
    @Test
    void repeatedClicksInTheFiringTurnFocusAStacksEnemiesInTurn() {
        int other = 9;
        int wreck = 10;
        Coords enemies = new Coords(9, 9);
        GpuBattleStatus.Snapshot firing = GpuHudFixtures.status(3, GamePhase.FIRING, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(FOE, ENEMY, false, false), GpuHudFixtures.unit(BLIP, ENEMY, false, false, 1, true),
              GpuHudFixtures.unit(other, ENEMY, false, false));
        BoardScene scene = scene(Map.of(GpuHudFixtures.HEX, List.of(GpuHudFixtures.FIRST), enemies, List.of(FOE, BLIP, wreck, other)));
        hud.update(GpuHudFixtures.frame(firing, GpuHudData.EMPTY, scene), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        hud.state.inspected = FOE;
        for (int next : List.of(other, FOE, other)) {
            assertEquals(next, hud.clickedUnit(enemies, BLIP), "the hover shows the unit the click takes");
            click(enemies, BLIP, 0);
            verify(fire).focusTarget(any(TargetKey.class));
            verify(fire).focusTarget(TargetKey.unit(next));
            clearInvocations(fire);
            hud.update(GpuHudFixtures.frame(firing, GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY, focused(next), GpuPhysicalOptions.Snapshot.EMPTY,
                  GpuUnitRecord.Snapshot.EMPTY), scene), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
        }
        verify(fire, never()).clickHex(any(), anyInt(), anyInt());
    }

    @Test
    void shiftClickOrientsThePlannedEndpointWithoutSelectingUnitsOrAddingWaypoints() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true), GpuHudFixtures.unit(FOE, ENEMY, false, false)),
              GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        for (int target : new int[] { Entity.NONE, SECOND, FOE }) {
            click(GpuHudFixtures.HEX, target, SHIFT_DOWN_MASK);
        }
        verify(moves, times(3)).faceToward(GpuHudFixtures.HEX, 0);
        verify(moves, never()).planTo(any(), anyInt(), anyBoolean(), anyFloat());
        verify(source, never()).selectUnit(anyInt());
        verify(source, never()).measure(any(), anyInt(), anyFloat());
    }

    @Test
    void movementClicksCarryThePickedFloorForRoutesAndWaypoints() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true)),
              GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        float floor = 21 * BoardGeometry.level();
        hud.boardClick(GpuHudFixtures.HEX, Entity.NONE, Input.Buttons.LEFT, 0, 100, 100, floor);
        verify(moves).planTo(GpuHudFixtures.HEX, 0, false, floor);
        hud.boardClick(GpuHudFixtures.HEX, Entity.NONE, Input.Buttons.LEFT, CTRL_DOWN_MASK, 100, 100, floor);
        verify(moves).planTo(GpuHudFixtures.HEX, 0, true, floor);
    }

    /**
     * Movement the HUD does not plan (G20), as while the movement display picks a hex (E2d), keeps MegaMek's board
     * tool: a left click is its press and click, never a plan command.
     */
    @Test
    void aMovementClickTheHudDoesNotPlanGoesToMegaMeksBoardTool() {
        update(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true)),
              GpuHudFixtures.panels(GpuHudFixtures.move(false, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(GpuHudFixtures.HEX, Entity.NONE, 0);
        verify(source).hover(GpuHudFixtures.HEX, 0);
        verify(source).click(GpuHudFixtures.HEX, false, 0);
        verifyNoInteractions(moves);
    }

    /**
     * While a bot order picks hexes (N7c), a left board click is MegaMek's board click, which the order's picker
     * takes, on a unit and in the planner's turn too; the Done key ends the pick with the order instead of the turn,
     * and Esc ends it without the order before any other step; the hint line shows the pick.
     */
    @Test
    void aBotOrdersHexPickTakesTheBoardClicksTheDoneKeyAndEsc() {
        GpuPlayers players = mock(GpuPlayers.class);
        when(source.players()).thenReturn(players);
        GpuPlayers.Pick pick = new GpuPlayers.Pick("Click hexes on the board: Strategic Target",
              "1 hex(es) selected: 0507");
        GpuHudData picking = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuHudFixtures.move(true, List.of(STEP)),
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null, pick));
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true),
              GpuHudFixtures.unit(SECOND, OWN, true, true));
        update(moving, picking, null);
        click(GpuHudFixtures.HEX, Entity.NONE, 0);
        click(GpuHudFixtures.HEX, SECOND, 0);
        verify(source, times(2)).click(GpuHudFixtures.HEX, false, 0);
        verify(source, never()).hover(any(), anyInt());
        verify(source, never()).selectUnit(anyInt());

        assertTrue(press(Input.Keys.ENTER, KeyEvent.VK_ENTER, CTRL_DOWN_MASK), "the Done key");
        verify(players).endPick(true);
        assertTrue(escape());
        verify(players).endPick(false);
        verifyNoInteractions(moves);
        assertEquals(List.of(UiKit.text("GpuBoard.hud.mouse.leftClick"), pick.instructions() + " · " + pick.status(),
              "", UiKit.text("BotCommandPanel.HexPicker.done"), "", UiKit.text("BotCommandPanel.HexPicker.cancel")),
              GpuHintLine.items(new GpuHud.Inputs(GpuHudFixtures.frame(moving, picking), GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences(),
                    GpuHud.Metrics.of(1920, 1080), List.of())), "the pick's line; this test's keys have no text");
    }

    /**
     * A native LOS measurement waiting for its second point (Alt-click starts it)
     * takes a plain left click with its modifier, ahead of the phase's own gestures, and the hint line names it; with
     * none waiting, a plain click plans again.
     */
    @Test
    void aPlainClickEndsAMeasurementWaitingForItsSecondPoint() {
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true));
        for (int pending : new int[] { ALT_DOWN_MASK }) {
            GpuHudData waiting = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuHudFixtures.move(true, List.of()),
                  GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
                  GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
                  new RulerModel.Snapshot(true, pending, null, null, 0, "", "", "", "", true, false, null, List.of()), GpuPlayers.Snapshot.EMPTY);
            update(moving, waiting, null);
            click(GpuHudFixtures.HEX, Entity.NONE, 0);
            verify(source).measure(GpuHudFixtures.HEX, pending, Float.NaN);
            assertEquals(UiKit.text("GpuBoard.hud.hint.completeLos"),
                  GpuHintLine.items(new GpuHud.Inputs(GpuHudFixtures.frame(moving, waiting), GpuHud.HudView.EMPTY, null,
                        GpuHudFixtures.preferences(), GpuHud.Metrics.of(1920, 1080), List.of())).get(1));
        }
        verifyNoInteractions(moves);
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(GpuHudFixtures.HEX, Entity.NONE, 0);
        verify(moves).planTo(GpuHudFixtures.HEX, 0, false, Float.NaN);
    }

    /**
     * Where the window has no room for the hint line (W <= 1350), a pick shows in its chip on the dock: its
     * instructions, the picked hexes once there are some, and its Done and Cancel buttons, which end the pick with and
     * without the order. A panel of the middle area (the LOS card) ends above the chip, as above the dock. In a wider
     * window the hint line shows the pick and the chip is hidden, and without a pick there is no chip.
     */
    @Test
    void aPickShowsItsChipWithDoneAndCancelWhereTheHintLineIsHidden() {
        GpuPlayers players = mock(GpuPlayers.class);
        when(source.players()).thenReturn(players);
        GpuPlayers.Pick pick = new GpuPlayers.Pick("Click hexes on the board: Strategic Target",
              "1 hex(es) selected: 0507");
        GpuHudData picking = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuHudFixtures.move(true, List.of(STEP)),
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null, pick));
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true));
        Actor chip = hud.stage.getRoot().findActor("pick-chip");
        Actor hint = hud.stage.getRoot().findActor("hint-line");

        hud.resize(1280, 720, 1);
        update(moving, picking, null);
        assertTrue(shown(chip), "the pick's chip in a window without the hint line");
        assertFalse(shown(hint));
        assertEquals(List.of(pick.instructions(), pick.status()), Stream.of("pick-instructions", "pick-status")
              .map(name -> hud.stage.getRoot().<Label>findActor(name).getText().toString()).toList());
        hud.stage.getRoot().findActor("pick-done").fire(new ChangeListener.ChangeEvent());
        verify(players).endPick(true);
        hud.stage.getRoot().findActor("pick-cancel").fire(new ChangeListener.ChangeEvent());
        verify(players).endPick(false);
        GpuPlayers.Pick started = new GpuPlayers.Pick(pick.instructions(), "");
        update(moving, new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuHudFixtures.move(true, List.of(STEP)),
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null, started)), null);
        assertNull(hud.stage.getRoot().findActor("pick-status"), "no picked hexes, no second line");

        hud.resize(1920, 1080, 1);
        update(moving, picking, null);
        assertFalse(shown(chip), "the hint line shows the pick");
        assertTrue(shown(hint));
        hud.resize(1280, 720, 1);
        update(moving, GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertFalse(shown(chip), "no pick, no chip");
    }

    @Test
    void boardGuidesStayAlignedWhenUiScaleShrinksAndReturns() {
        GpuBattleStatus.Snapshot moving = GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true));
        Actor guides = hud.stage.getRoot().findActor("board-fx");
        for (float uiScale : new float[] { 1, .5f, 1, .5f, 1 }) {
            hud.resize(1920, 1080, uiScale);
            update(moving);
            ((Container<?>) guides.getParent()).validate();
            Vector2 projectedUnit = new Vector2(hud.stage.getWidth() * .7f, hud.stage.getHeight() * .6f);
            Vector2 drawnGuide = guides.localToStageCoordinates(new Vector2(projectedUnit));
            assertEquals(projectedUnit.x, drawnGuide.x, .001f, "guide must stay at the unit's projected x");
            assertEquals(projectedUnit.y, drawnGuide.y, .001f, "guide must stay at the unit's projected y");
        }
    }

    /** Whether the actor and every group above it are visible. */
    private static boolean shown(Actor actor) {
        for (Actor current = actor; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void anAssignedOrClearedWeaponIsDisarmed() {
        update(GpuHudFixtures.status(3, GamePhase.FIRING, true, GpuHudFixtures.FIRST, 1, GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true), GpuHudFixtures.unit(FOE, ENEMY, false,
              false)), GpuHudData.EMPTY, null);
        hud.state.armedWeapon = 4;
        click(GpuHudFixtures.HEX, FOE, 0);
        verify(fire).assign(4, TargetKey.unit(FOE));
        assertEquals(-1, hud.state.armedWeapon);
        click(GpuHudFixtures.HEX, FOE, 0);
        verify(fire, times(2)).focusTarget(TargetKey.unit(FOE));
        verify(fire, times(1)).assign(anyInt(), any(TargetKey.class));
        // A hex: MegaMek's click chooses the target there, a wooded hex or a building too, and the armed weapon fires
        // at it (the user's report of 2026-10-03: terrain kept "Hold fire").
        hud.state.armedWeapon = 6;
        click(GpuHudFixtures.HEX, Entity.NONE, 0);
        verify(fire).clickHex(GpuHudFixtures.HEX, 0, 6);
        assertEquals(-1, hud.state.armedWeapon);
        verify(source, never()).click(any(), anyBoolean(), anyInt());

        hud.state.armedWeapon = 5;
        assertTrue(press(Input.Keys.FORWARD_DEL, KeyEvent.VK_DELETE, 0));
        verify(fire).clearAll();
        assertEquals(-1, hud.state.armedWeapon);
    }

    @Test
    void moreRunsTheLatestCommandAfterAnUpdateAndAfterReopening() {
        List<String> ran = new ArrayList<>();
        updateMore(List.of(extra("extra", () -> ran.add("old"))));
        change("dock-more");
        UiButton button = hud.stage.getRoot().findActor("dock-more-extra");
        updateMore(List.of(extra("extra", () -> ran.add("updated"))));
        assertSame(button, hud.stage.getRoot().findActor("dock-more-extra"), "unchanged captions keep their buttons");
        change("dock-more-extra");
        assertEquals(List.of("updated"), ran);
        // The closing animation still draws the buttons, but they must no longer execute commands.
        change("dock-more-extra");
        assertEquals(List.of("updated"), ran);

        updateMore(List.of(extra("extra", () -> ran.add("reopened"))));
        change("dock-more");
        change("dock-more-extra");
        assertEquals(List.of("updated", "reopened"), ran);
    }

    @Test
    void moreDoesNotRunACommandThatBecameUnavailable() {
        AtomicInteger ran = new AtomicInteger();
        updateMore(List.of(extra("extra", ran::incrementAndGet)));
        change("dock-more");
        Actor oldButton = hud.stage.getRoot().findActor("dock-more-extra");
        updateMore(List.of(new BoardScene.Command("extra", "extra", "", false, false, List.of(),
              ran::incrementAndGet)));
        assertNull(hud.stage.getRoot().findActor("dock-more"), "without a usable command More closes and goes");
        oldButton.fire(new ChangeListener.ChangeEvent());
        assertEquals(0, ran.get(), "a detached button cannot run its old action");
    }

    @Test
    void moreKeepsKeyboardNavigationAndTheLatestActionAcrossRebuilds() {
        List<String> ran = new ArrayList<>();
        BoardScene.Command first = extra("first", () -> ran.add("first"));
        BoardScene.Command last = extra("last", () -> ran.add("old"));
        updateMore(List.of(first, last));
        change("dock-more");
        assertSame(hud.stage.getRoot().findActor("dock-more-commands"), hud.stage.getKeyboardFocus());
        assertTrue(press(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0));
        assertTrue(press(Input.Keys.UP, KeyEvent.VK_UP, 0));
        assertTrue(hud.stage.getRoot().<UiButton>findActor("dock-more-last").isChecked(), "Up wraps to the last item");
        assertTrue(press(Input.Keys.HOME, KeyEvent.VK_HOME, 0));
        assertTrue(hud.stage.getRoot().<UiButton>findActor("dock-more-first").isChecked());
        assertTrue(press(Input.Keys.END, KeyEvent.VK_END, 0));
        updateMore(List.of(last, first, extra("new", () -> ran.add("new"))));
        assertTrue(hud.stage.getRoot().<UiButton>findActor("dock-more-last").isChecked(),
              "reordering the grid preserves the highlighted command");
        assertTrue(press(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0));
        assertTrue(hud.stage.getRoot().<UiButton>findActor("dock-more-first").isChecked());
        updateMore(List.of(extra("last", () -> ran.add("latest"))));
        assertTrue(press(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0));
        assertTrue(press(Input.Keys.ENTER, KeyEvent.VK_ENTER, 0));
        assertEquals(List.of("latest"), ran, "removed buttons are not kept in the keyboard order");
        assertFalse(hud.state.chatOpen, "Enter chooses the command instead of opening chat");
        assertNull(hud.stage.getKeyboardFocus());
    }

    @Test
    void moreClosesOnOneEscapeAndReleasesFocusWhenHidden() {
        List<BoardScene.Command> commands = List.of(extra("extra", () -> { }));
        updateMore(commands);
        change("dock-more");
        assertTrue(escape());
        assertNull(hud.stage.getKeyboardFocus());
        hud.stage.act(1);
        assertFalse(hud.stage.getRoot().findActor("dock-more-drawer").isVisible());
        verify(moves, never()).clearRoute();

        change("dock-more");
        assertNotNull(hud.stage.getKeyboardFocus());
        change("dock-more-close");
        assertNull(hud.stage.getKeyboardFocus());
        change("dock-more");
        hud.state.overview = true;
        updateMore(commands);
        assertNull(hud.stage.getKeyboardFocus(), "a drawer covered by the overview cannot keep the keyboard");
        assertTrue(escape());
        assertFalse(hud.state.overview, "the hidden drawer must not consume the overview's Esc");
    }

    @Test
    void moreGrowsIntoAvailableSpaceAndScrollsOnlyWhenTheWindowIsFull() {
        for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
            hud.resize(size[0], size[1], 1);
            for (int count : new int[] { 8, 12, 40 }) {
                List<BoardScene.Command> commands = IntStream.range(0, count)
                      .mapToObj(index -> extra("extra" + index, () -> { })).toList();
                updateMore(commands);
                change("dock-more");
                updateMore(commands);
                hud.stage.act(1);
                validateLayout(hud.stage.getRoot());
                Actor drawer = hud.stage.getRoot().findActor("dock-more-drawer");
                Actor slot = drawer.getParent();
                ScrollPane scroll = hud.stage.getRoot().findActor("dock-more-scroll");
                String context = size[0] + "x" + size[1] + ", " + count + " commands";
                float gap = GpuHud.Metrics.of(size[0], size[1]).gap();
                assertTrue(slot.getY() >= gap - .5f, context);
                assertTrue(slot.getTop() <= size[1] - gap + .5f, context);
                if (count < 40 || size[0] == 1920) {
                    assertEquals(0, scroll.getMaxY(), .5f, "all commands fit without scrolling: " + context);
                } else {
                    assertTrue(scroll.getMaxY() > 0, "a panel taller than the window remains scrollable: " + context);
                    assertEquals(size[1] - gap, slot.getTop(), .5f, "use all free height before scrolling");
                    assertTrue(press(Input.Keys.END, KeyEvent.VK_END, 0));
                    assertTrue(scroll.getScrollY() > 0, "keyboard navigation reveals the last command");
                }
                change("dock-more");
                hud.stage.act(1);
            }
        }
    }

    @Test
    void moreStaysClearOfTheMinimapWhenContactsAreHidden() {
        hud.resize(1280, 720, 1);
        GpuBoardSource.UiPreferences prefs = GpuHudFixtures.with(GpuHudFixtures.preferences(), Map.of("contactsEnabled", false));
        for (int count : new int[] { 4, 12, 40 }) {
            List<BoardScene.Command> commands = IntStream.range(0, count)
                  .mapToObj(index -> extra("extra" + index, () -> { })).toList();
            updateMore(commands, prefs);
            change("dock-more");
            updateMore(commands, prefs);
            hud.stage.act(1);
            validateLayout(hud.stage.getRoot());
            Rectangle drawer = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("dock-more-drawer"));
            Actor minimap = hud.stage.getRoot().findActor("minimap");
            assertTrue(minimap.ascendantsVisible());
            assertFalse(drawer.overlaps(GpuHudTestStage.bounds(minimap)), count + " commands overlap the minimap");
            if (count == 4) {
                Rectangle dock = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("command-dock"));
                assertTrue(drawer.x >= dock.x + dock.width, "short drawers still fit beside the dock");
            }
            change("dock-more");
            hud.stage.act(1);
        }
    }

    @Test
    void moreDismissesForDialogsAndCanTakeKeyboardFocusAgain() {
        when(source.players()).thenReturn(mock(GpuPlayers.class));
        AtomicInteger ran = new AtomicInteger();
        List<BoardScene.Command> commands = List.of(extra("extra", ran::incrementAndGet));
        for (GpuHudState.Dialog dialog : List.of(GpuHudState.Dialog.HELP, GpuHudState.Dialog.MENU,
              GpuHudState.Dialog.PLAYERS)) {
            updateMore(commands);
            change("dock-more");
            assertTrue(press(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0));
            hud.state.dialog = dialog;
            updateMore(commands);
            hud.stage.act(1);
            assertFalse(hud.stage.getRoot().findActor("dock-more-drawer").isVisible(), dialog.toString());
            assertTrue(escape());
            updateMore(commands);
            assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);
            change("dock-more");
            assertSame(hud.stage.getRoot().findActor("dock-more-commands"), hud.stage.getKeyboardFocus());
            assertTrue(press(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0));
            assertTrue(press(Input.Keys.ENTER, KeyEvent.VK_ENTER, 0));
        }
        assertEquals(3, ran.get());
    }

    @Test
    void moreKeepsTheHighlightedCommandVisibleWhenTheWindowShrinks() {
        List<BoardScene.Command> commands = IntStream.range(0, 40)
              .mapToObj(index -> extra("extra" + index, () -> { })).toList();
        updateMore(commands);
        change("dock-more");
        updateMore(commands);
        hud.stage.act(1);
        validateLayout(hud.stage.getRoot());
        assertTrue(press(Input.Keys.END, KeyEvent.VK_END, 0));
        for (int[] size : new int[][] { { 1280, 900 }, { 1280, 720 }, { 900, 600 } }) {
            hud.resize(size[0], size[1], 1);
            updateMore(commands);
            ScrollPane scroll = hud.stage.getRoot().findActor("dock-more-scroll");
            // A draw updates the widget's position from the visual scroll amount; layout does the same without GL.
            scroll.updateVisualScroll();
            scroll.layout();
            UiMenuList grid = hud.stage.getRoot().findActor("dock-more-commands");
            Actor selected = hud.stage.getRoot().findActor("dock-more-extra39");
            assertSame(selected, grid.highlighted());
            Rectangle viewport = GpuHudTestStage.bounds(scroll);
            Rectangle selectedBounds = GpuHudTestStage.bounds(selected);
            assertTrue(selectedBounds.y >= viewport.y - .5f
                  && selectedBounds.y + selectedBounds.height <= viewport.y + viewport.height + .5f,
                  size[0] + "x" + size[1] + ": " + selectedBounds + " outside " + viewport);
        }
        ScrollPane scroll = hud.stage.getRoot().findActor("dock-more-scroll");
        scroll.setScrollY(0);
        updateMore(commands);
        assertEquals(0, scroll.getScrollY(), .5f, "ordinary updates must not undo manual scrolling");
    }

    @Test
    void moreIsOnlyOfferedWithACommandToUse() {
        updateMore(List.of());
        assertNull(hud.stage.getRoot().findActor("dock-more"));
        updateMore(List.of(new BoardScene.Command("extra", "extra", "", false, false, List.of(), () -> { })));
        assertNull(hud.stage.getRoot().findActor("dock-more"), "unavailable commands offer no More");
        updateMore(List.of(extra("extra", () -> { })));
        assertNotNull(hud.stage.getRoot().findActor("dock-more"));
    }

    private static BoardScene.Command extra(String id, Runnable action) {
        return new BoardScene.Command(id, id, "", true, false, List.of(), action);
    }

    /** A movement frame with the supplied overflow commands, settled as after the HUD's layout and draw. */
    private void updateMore(List<BoardScene.Command> commands) {
        updateMore(commands, GpuHudFixtures.preferences());
    }

    private void updateMore(List<BoardScene.Command> commands, GpuBoardSource.UiPreferences prefs) {
        BoardScene scene = mock(BoardScene.class);
        when(scene.commands()).thenReturn(commands);
        GpuBoardSource.Frame frame = GpuHudFixtures.frame(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, GpuHudFixtures.FIRST, 0,
              GpuHudFixtures.unit(GpuHudFixtures.FIRST, OWN, true, true)), GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), scene);
        for (int pass = 0; pass < 3; pass++) {
            hud.update(frame, GpuHud.HudView.EMPTY, null, prefs);
            validateLayout(hud.stage.getRoot());
        }
    }

    private void change(String name) {
        Actor actor = hud.stage.getRoot().findActor(name);
        assertNotNull(actor, name);
        actor.fire(new ChangeListener.ChangeEvent());
    }

    private static void validateLayout(Actor actor) {
        if (actor instanceof Layout layout) {
            layout.validate();
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(GpuHudInputTest::validateLayout);
        }
    }

    private boolean escape() {
        return press(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
    }

    private boolean press(int key, int awt, int modifiers) {
        return hud.keyDown(key, awt, modifiers);
    }

    private void click(Coords coords, int unitId, int modifiers) {
        hud.boardClick(coords, unitId, Input.Buttons.LEFT, modifiers, 100, 100, Float.NaN);
    }

    private void update(GpuBattleStatus.Snapshot status) {
        update(status, GpuHudData.EMPTY, null);
    }

    private void update(GpuBattleStatus.Snapshot status, GpuHudData panels, GpuBoardWindow.DialogRequest dialog) {
        hud.update(GpuHudFixtures.frame(status, panels), GpuHud.HudView.EMPTY, dialog, GpuHudFixtures.preferences());
    }

    /** The local actor's orders, focused on the unit {@code id}. */
    private static GpuFireOrders.Snapshot focused(int id) {
        return new GpuFireOrders.Snapshot(true, true, GpuHudFixtures.FIRST, new GpuFireOrders.Focus(TargetKey.unit(id), "Unit " + id,
              null), -1, List.of(), List.of(), List.of(), 0, false, false, "", null, null, null, List.of(), null,
              Map.of(), 0, 0, null);
    }

    /** A scene of board 0 that draws the units listed under each hex there, in the listed order. */
    private static BoardScene scene(Map<Coords, List<Integer>> hexes) {
        List<BoardScene.Unit> units = new ArrayList<>();
        hexes.forEach((hex, ids) -> ids.forEach(id -> units.add(new BoardScene.Unit(id, -1, "Unit " + id,
              new BoardScene.Waypoint(hex, 0, 0), null, false, null, 1, false))));
        BoardScene scene = mock(BoardScene.class);
        when(scene.units()).thenReturn(units);
        return scene;
    }

    private static GpuUnitRecord.Snapshot record(int unitId) {
        return new GpuUnitRecord.Snapshot(unitId, true, false, "", "", List.of(), List.of(), List.of(), List.of(),
              List.of(), List.of(), 0, new WeaponDisplayData.HeatBuildup(0, 0, ""), List.of(), List.of(), List.of(),
              List.of(), List.of(), List.of(), List.of(), List.of(), "", "", List.of(), List.of(), "", List.of());
    }

    private static GpuBoardWindow.DialogRequest message() {
        return new GpuBoardWindow.DialogRequest(1, GpuBoardWindow.DialogKind.MESSAGE, "Title", "Message", false,
              List.of("OK", "Cancel"), 0, 1, List.of(), List.of(), "", false, "", null, null, List.of());
    }

    /** A real TextField whose font has glyphs for the digits and the space only, enough without a GL context. */
    private static TextField textField() {
        BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData();
        for (char character : " 0123456789".toCharArray()) {
            BitmapFont.Glyph glyph = new BitmapFont.Glyph();
            glyph.id = character;
            glyph.width = 6;
            glyph.height = 10;
            glyph.xadvance = 7;
            data.setGlyph(character, glyph);
        }
        Texture page = mock(Texture.class);
        when(page.getWidth()).thenReturn(64);
        when(page.getHeight()).thenReturn(64);
        BitmapFont font = new BitmapFont(data, new TextureRegion(page), false);
        return new TextField("", new TextField.TextFieldStyle(font, Color.WHITE, null, null, null));
    }
}
