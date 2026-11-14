/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.CENTURION;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.CONTACT;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.TANK;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.TRIPOD;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.WRECK;
import static megamek.client.ui.gdx.UiKit.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuCriticalTable.Strike;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.SheetTab;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.ResolvedAttack;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The unit panel in the real HUD (unit panel design 14 U3), without a GL context, over records the board source
 * captured from real units: the sheet's slot at the three reference stages, the alert strip, the Atlas AS7-K's and the
 * Centurion CN11-OD's critical tables, the dolls of the card and the ARMOR tab, shields, the Esc chain and the blink
 * memory across units.
 */
@Timeout(180)
class GpuUnitPanelTest {
    private static GpuUnitPanelFixture fixture;
    private Application application;
    private Files files;
    private Graphics graphics;
    private GL20 gl;
    private GL20 gl20;
    private Input input;
    private final GpuBoardSource source = mock(GpuBoardSource.class);
    private final GpuUnitRecord records = mock(GpuUnitRecord.class);
    private final GpuFireOrders orders = mock(GpuFireOrders.class);
    private GpuBoardSkin theme;
    private GpuHud hud;

    @BeforeAll
    static void captureUnits() throws Exception {
        GdxNativesLoader.load();
        fixture = GpuUnitPanelFixture.create();
    }

    @AfterAll
    static void closeFixture() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @BeforeEach
    void createHud() {
        application = Gdx.app;
        files = Gdx.files;
        graphics = Gdx.graphics;
        gl = Gdx.gl;
        gl20 = Gdx.gl20;
        input = Gdx.input;
        // As GpuHudInputTest: Scene2D reads these statics and the HUD skin bakes its fonts against the mocked GL.
        Gdx.app = mock(Application.class);
        // The stage fires the mouse's enter and exit events in act() on a desktop only.
        when(Gdx.app.getType()).thenReturn(Application.ApplicationType.Desktop);
        Gdx.files = mock(Files.class);
        // The HUD skin reads its enemy icon from the classpath.
        when(Gdx.files.classpath(anyString())).thenAnswer(call -> new Lwjgl3Files().classpath(call.getArgument(0)));
        Gdx.graphics = mock(Graphics.class);
        Gdx.gl = mock(GL20.class);
        AtomicInteger textures = new AtomicInteger();
        when(Gdx.gl.glGenTexture()).thenAnswer(call -> textures.incrementAndGet());
        Gdx.gl20 = Gdx.gl;
        Gdx.input = mock(Input.class);
        when(source.record()).thenReturn(records);
        when(source.fire()).thenReturn(orders);
        theme = new GpuBoardSkin();
        hud = new GpuHud(source, theme.skin, mock(Batch.class), new BoardCamera(), mock(GpuBoardTuning.class),
              new GpuPlaybackHistory(new UnitPlayback()));
        hud.resize(1920, 1080, 1.08f);
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
    void theSheetFillsTheLeftColumnAboveTheMiniCardAndKeepsClearOfTheDock() throws Exception {
        // Window, display scale, sheet height and dock clearance at 1778 x 1000, 1536 x 864 and 1280 x 720 stages.
        // The shared HUD margin is 10, the sheet's distance from the dock beside it; normalize the changing phase header
        // to 84 units (80 when narrow).
        float[][] stages = { { 1920, 1080, 1.08f, 772, 10, 84 }, { 1920, 1080, 1.25f, 636, 10, 84 },
              { 1280, 720, 1, 496, 10, 80 } };
        for (float[] stage : stages) {
            hud.resize((int) stage[0], (int) stage[1], stage[2]);
            show(ATLAS, SheetTab.SYSTEMS, true, GamePhase.FIRING);
            Rectangle phase = bounds("phase-header");
            Rectangle sheet = bounds("record-sheet");
            Rectangle card = bounds("unit-card");
            Rectangle strip = bounds("forces-panel");
            String size = hud.stage.getWidth() + " x " + hud.stage.getHeight();
            assertEquals(stage[3], sheet.height + phase.height - stage[5], .5f, "sheet height at " + size);
            assertEquals(12, phase.y - strip.y - strip.height, .5f, "the strip 12 below the phase header");
            assertEquals(44, card.height, .5f, "the mini card while the sheet is open at " + size);
            assertEquals(44, strip.height, .5f, "the forces strip at " + size);
            assertEquals(GpuHud.Metrics.of(hud.stage.getWidth(), hud.stage.getHeight()).grid(), sheet.width, .5f);
            assertEquals(12, sheet.y - card.y - card.height, .5f, "12 above the mini card");
            assertEquals(12, strip.y - sheet.y - sheet.height, .5f, "12 below the strip");
            Rectangle dock = bounds("command-dock");
            assertEquals(stage[4], dock.x - sheet.x - sheet.width, .5f, "the sheet keeps clear of the dock");

            // Closed, the card has its full height, the same for a heavily damaged and an undamaged unit.
            show(ATLAS, SheetTab.SYSTEMS, false, GamePhase.FIRING);
            float full = bounds("unit-card").height;
            show(TRIPOD, SheetTab.SYSTEMS, false, GamePhase.FIRING);
            assertEquals(full, bounds("unit-card").height, .5f);
            assertEquals(stage[1] / stage[2] <= 800 ? 252 : 290, full, .5f, "the full card at " + size);
            assertFalse(visible(hud.stage.getRoot().findActor("record-sheet")));
        }
    }

    @Test
    void theAlertStripLeavesOutTheChipsWhoseHomeIsTheOpenTab() throws Exception {
        // The Atlas's chips by their keys ("ENGINE HIT 1/3", "PILOT 1 HIT", "NARC" in the text pass's English), the
        // coral one first, each with the tab of the section that explains it.
        String engine = chip("GpuBoard.hud.unit.chip.hits", text("GpuBoard.hud.unit.track.ENGINE"), 1, 3);
        String pilot = chip("GpuBoard.hud.unit.chip.crewHits", "Pilot", 1);
        String narc = chip("GpuBoard.hud.unit.chip.narc");
        GpuBoardSource.Frame frame = fixture.frame(ATLAS);
        List<GpuUnitCard.Chip> chips = GpuUnitCard.chips(GpuHudState.unit(frame.status(), ATLAS),
              frame.panels().record(), GamePhase.FIRING);
        assertEquals(List.of(engine, pilot, narc), chips.stream().map(chip -> UiTheme.upper(chip.text())).toList());
        assertEquals(List.of(SheetTab.SYSTEMS, SheetTab.CREW, SheetTab.STATUS),
              chips.stream().map(GpuUnitCard.Chip::home).toList());
        show(ATLAS, SheetTab.STATUS, false, GamePhase.FIRING);
        assertShows(List.of(engine, pilot, narc), texts(find("unit-card-chips")));
        show(ATLAS, SheetTab.SYSTEMS, true, GamePhase.FIRING);
        assertShows(List.of(pilot, narc), texts(find("record-sheet-alerts")));
        show(ATLAS, SheetTab.CREW, true, GamePhase.FIRING);
        assertShows(List.of(engine, narc), texts(find("record-sheet-alerts")));
        show(ATLAS, SheetTab.ARMOR, true, GamePhase.FIRING);
        assertShows(List.of(engine, pilot, narc), texts(find("record-sheet-alerts")));

        // A unit whose only chip lives on the open tab has no strip.
        fixture.edit(() -> fixture.entity(CENTURION).getCrew().setHits(2, 0));
        try {
            show(CENTURION, SheetTab.CREW, true, GamePhase.FIRING);
            Table alerts = find("record-sheet-alerts");
            assertEquals(0, alerts.getHeight(), .5f, "no strip: " + texts(alerts));
            show(CENTURION, SheetTab.STATUS, true, GamePhase.FIRING);
            assertShows(List.of(chip("GpuBoard.hud.unit.chip.crewHits", "Pilot", 2)),
                  texts(find("record-sheet-alerts")));
        } finally {
            fixture.edit(() -> fixture.entity(CENTURION).getCrew().setHits(0, 0));
        }
    }

    @Test
    void theAtlasCriticalTableShowsTheRecordSheetsSlots() throws Exception {
        show(ATLAS, SheetTab.SYSTEMS, true, GamePhase.FIRING);
        // The destroyed left arm: its heading in ink, its filled slots red-struck, its empty ones dim "Roll Again".
        assertEquals(UiTheme.TEXT, heading("LEFT ARM").getStyle().fontColor);
        for (int slot = 0; slot < 12; slot++) {
            GpuCriticalTable.StruckLabel name = slotName("LA", slot);
            if (slot < 9) {
                assertEquals(Strike.RED, strike(name), "LA slot " + slot);
            } else {
                assertEquals("Roll Again", name.getText().toString());
                assertEquals(UiTheme.DISABLED, name.getStyle().fontColor);
            }
        }
        // Blocks of six: twelve slots in two blocks, six in one.
        assertEquals(12, lines("LA"));
        assertEquals(6, lines("LL"));
        assertTrue(cell("LA", 6).getPadTop() > 0 && cell("LL", 1).getPadTop() == 0, "the gap between two blocks");
        // The engine slot hit in the centre torso; the rear pulse laser destroyed; a lower leg actuator hit.
        assertEquals(Strike.RED, strike(slotName("CT", 0)));
        assertEquals(Strike.RED, strike(slotName("CT", 11)));
        assertEquals(Strike.NONE, strike(slotName("CT", 10)));
        assertEquals(Strike.RED, strike(slotName("RL", 2)));
        // CASE in both side torsos, its slot dim (not hittable).
        assertTrue(texts(heading("LEFT TORSO").getParent()).contains("CASE"));
        assertTrue(texts(heading("RIGHT TORSO").getParent()).contains("CASE"));
        assertEquals(UiTheme.DISABLED, slotName("LT", 11).getStyle().fontColor);
        // The LRM 20's five slots under one rail, capped where the block of six cuts it.
        List<String> rails = new ArrayList<>();
        for (int slot = 3; slot < 8; slot++) {
            boolean[] rail = line("LT", slot).rails();
            rails.add(slot + ":" + (rail[0] ? "r" : "") + (rail[1] ? "s" : "") + (rail[2] ? "e" : ""));
        }
        assertEquals(List.of("3:rs", "4:r", "5:re", "6:rs", "7:re"), rails);
        assertFalse(line("LT", 8).rails()[0], "a bin of its own has no rail");
        // Ammunition: 4 of 6 LRM 20 shots as 3 of 5 dots and the badge 4.
        assertEquals(List.of("Ammo (LRM 20)", "4"), texts(line("LT", 8)));
        assertEquals(3, filledDots(line("LT", 8)));
        assertEquals(5, filledDots(line("LT", 9)));
        // The hit box: one engine hit of three, the gyro, sensors and life support whole.
        assertEquals(List.of(1, 0, 0, 0), hitBox(List.of("ENGINE", "GYRO", "SENSORS", "LIFE_SUPPORT")));
    }

    @Test
    void theCenturionCriticalTableShowsItsShieldEndoSteelCaseIiAndBreachedLeg() throws Exception {
        show(CENTURION, SheetTab.SYSTEMS, true, GamePhase.FIRING);
        GpuUnitRecord.Location leftArm = location(CENTURION, "LA");
        List<Integer> shield = new ArrayList<>();
        leftArm.slots().stream().filter(slot -> slot.text().equals("Shield (Medium)"))
              .forEach(slot -> shield.add(slot.index()));
        assertTrue(shield.size() > 1);
        assertTrue(shield.stream().allMatch(slot -> line("LA", slot).rails()[0]), "the shield's slots share a rail");
        assertTrue(texts(heading("LEFT TORSO").getParent()).contains("CASE II"));
        for (GpuUnitRecord.Slot slot : location(CENTURION, "LT").slots()) {
            if (slot.text().equals("CASE II") || slot.text().equals("Endo Steel")) {
                assertEquals(UiTheme.DISABLED, slotName("LT", slot.index()).getStyle().fontColor, slot.text());
            }
        }
        for (int slot = 0; slot < 6; slot++) {
            assertEquals(Strike.GREY, strike(slotName("LL", slot)), "the breached leg's slot " + slot);
        }
    }

    @Test
    void theArmorTabLeavesExposedAndDestroyedValuesToTheStructureDoll() throws Exception {
        show(ATLAS, SheetTab.STATUS, false, GamePhase.FIRING);
        List<String> card = texts(find("unit-card-doll"));
        assertTrue(card.contains("(24)") && card.contains("•"), "the card's exposed CT with its crit dot: " + card);
        assertTrue(card.contains(GpuPaperdoll.CROSS), "the card's destroyed LA: " + card);

        show(ATLAS, SheetTab.ARMOR, true, GamePhase.FIRING);
        List<String> front = texts(find("record-doll-armor"));
        assertFalse(front.contains("(24)") || front.contains(GpuPaperdoll.CROSS), "front armor: " + front);
        assertTrue(front.contains("•"), "the exposed CT keeps its crit dot: " + front);
        assertTrue(texts(find("record-doll-structure")).contains(GpuPaperdoll.CROSS), "LA's cross");
        assertTrue(texts(find("record-doll-armor-rear")).contains("4"), "the right torso's rear armor");
    }

    @Test
    void aShieldIsDrawnOnlyOnTheArmThatMountsOne() throws Exception {
        assertTrue(dollShields(ATLAS).isEmpty());
        assertEquals(List.of("LA"), dollShields(CENTURION));
        assertEquals(List.of("RA"), dollShields(TRIPOD));
        show(CENTURION, SheetTab.STATUS, false, GamePhase.FIRING);
        List<String> card = texts(find("unit-card-doll"));
        assertTrue(card.contains("11") && card.contains("5"), "both shield numbers on the card: " + card);

        // A shield that no longer works (its arm blown off) is one destroyed object: one cross, the strip's fill.
        GpuUnitRecord.Snapshot record = fixture.frame(CENTURION).panels().record();
        assertEquals(List.of(), GpuUnitSheetTabs.pending(record), "a shield without a queued mode switch");
        GpuUnitRecord.Shield working = record.shields().getFirst();
        GpuUnitRecord.Snapshot blownOff = withShield(record, new GpuUnitRecord.Shield(working.location(),
              working.eqNum(), working.name(), false, working.absorption(), working.baseAbsorption(),
              working.capacity(), working.baseCapacity()));
        Map<String, GpuPaperdoll.Cell> cells = GpuUnitCard.frontCells(blownOff, GpuPaperdoll.View.CARD,
              GpuUnitCard.Deltas.NONE);
        assertEquals(GpuPaperdoll.CROSS, cells.get("DCLA").text());
        assertEquals("", cells.get("DALA").text());
        assertEquals(GpuPaperdoll.DamageTier.DESTROYED, cells.get("DALA").tier());
    }

    @Test
    void escapeClosesThePopoverThenTheSheet() throws Exception {
        show(ATLAS, SheetTab.SYSTEMS, true, GamePhase.FIRING);
        click(line("LT", 8));
        update(ATLAS, GamePhase.FIRING);
        Actor popover = popover();
        assertTrue(popover.isVisible(), "a slot's popover");
        assertTrue(texts((Group) popover).contains("4 / 6"), "its shots: " + texts((Group) popover));

        assertTrue(escape());
        assertFalse(popover.isVisible());
        assertTrue(hud.state.recordOpen);
        assertTrue(escape());
        assertFalse(hud.state.recordOpen);
    }

    /** Esc while a weapon row is dragged sends the row home first; the sheet stays, and the next Esc closes it. */
    @Test
    void escapeFirstSendsADraggedWeaponRowHome() throws Exception {
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        hover(find("record-weapon-1"));
        Vector2 start = centre(((Table) find("record-weapon-1")).getChildren().first());
        Vector2 end = centre(find("record-weapon-3"));
        hud.stage.touchDown((int) start.x, (int) start.y, 0, Input.Buttons.LEFT);
        hud.stage.touchDragged((int) end.x, (int) end.y, 0);
        assertTrue(escape(), "Esc ends the drag");
        assertTrue(hud.state.recordOpen, "and only the drag");
        hud.stage.touchUp((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
        UiTestStage.settle(hud.stage);
        verify(records, never()).moveWeapon(anyInt(), anyInt(), anyInt());
        assertTrue(escape());
        assertFalse(hud.state.recordOpen, "the next Esc closes the sheet");
    }

    @Test
    void arrowsMoveTheSelectionOnlyWhileTheSheetsListsHaveTheFocus() throws Exception {
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        assertFalse(hud.keyDown(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0), "without list focus the arrows go on");
        click(find("record-weapon-0"));
        update(ATLAS, GamePhase.FIRING);
        assertTrue(hud.keyDown(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0), "the focused list takes them");
        assertTrue(escape(), "the first Esc ends the list focus");
        assertFalse(hud.keyDown(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0));
    }

    @Test
    void anotherUnitKeepsTheLocationAndExpandedWeaponItHas() throws Exception {
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        hud.state.selectedLocation = "LT";
        hud.state.expandedWeapon = "LRM 20";
        hud.state.expandedOrdinal = 0;
        // The Archer has a left torso and an LRM 20 as well.
        show(WRECK, SheetTab.WEAPONS, true, GamePhase.FIRING);
        assertEquals(List.of("LT", "LRM 20", SheetTab.WEAPONS),
              List.of(hud.state.selectedLocation, hud.state.expandedWeapon, hud.state.sheetTab));
        // A tank has neither: both are cleared, the tab stays.
        show(TANK, SheetTab.WEAPONS, true, GamePhase.FIRING);
        assertEquals(List.of("", "", SheetTab.WEAPONS),
              List.of(hud.state.selectedLocation, hud.state.expandedWeapon, hud.state.sheetTab));
    }

    /**
     * A doll location's click opens the critical table and flashes that location's block, a highlight that fades:
     * no filter chip, nothing selected. The ARMOR dolls' arrows still step
     * through the locations, the shield after its arm, and Enter opens the stepped location as a click does.
     */
    @Test
    void aDollClickFlashesItsLocationInTheCriticalTableAndTheArrowsStepThroughTheDoll() throws Exception {
        show(CENTURION, SheetTab.ARMOR, true, GamePhase.FIRING);
        clickRegion(find("record-doll-armor"), "LA");
        update(CENTURION, GamePhase.FIRING);
        assertEquals(List.of(SheetTab.SYSTEMS, ""), List.of(hud.state.sheetTab, hud.state.selectedLocation),
              "the critical table opens and nothing is selected");
        Table arm = find(GpuCriticalTable.blockName("LA"));
        assertNotNull(arm.findActor("record-flash"), "the arm's block flashes");
        hud.stage.act(1);
        hud.stage.act(0);
        assertNull(arm.findActor("record-flash"), "the flash fades away");
        // The shield's own code flashes its arm's block.
        hud.state.sheetTab = SheetTab.ARMOR;
        update(CENTURION, GamePhase.FIRING);
        clickRegion(find("record-doll-armor"), "DCLA");
        update(CENTURION, GamePhase.FIRING);
        assertNotNull(((Table) find(GpuCriticalTable.blockName("LA"))).findActor("record-flash"));

        // The keyboard on the ARMOR dolls, once the sheet's lists have the focus (as after a click on a row).
        hud.state.sheetTab = SheetTab.ARMOR;
        hud.state.selectedLocation = "LA";
        update(CENTURION, GamePhase.FIRING);
        hud.stage.setKeyboardFocus(find("record-sheet-body"));
        List<String> visited = new ArrayList<>();
        for (int key : new int[] { Input.Keys.RIGHT, Input.Keys.RIGHT, Input.Keys.LEFT, Input.Keys.LEFT }) {
            assertTrue(hud.keyDown(key, key == Input.Keys.LEFT ? KeyEvent.VK_LEFT : KeyEvent.VK_RIGHT, 0));
            update(CENTURION, GamePhase.FIRING);
            visited.add(hud.state.selectedLocation);
        }
        assertEquals(List.of("DCLA", "RL", "DCLA", "LA"), visited, "MegaMek's order, the shield after its arm");
        assertTrue(hud.keyDown(Input.Keys.ENTER, KeyEvent.VK_ENTER, 0));
        update(CENTURION, GamePhase.FIRING);
        assertEquals(SheetTab.SYSTEMS, hud.state.sheetTab, "Enter opens the location as a click does");
        assertNotNull(((Table) find(GpuCriticalTable.blockName("LA"))).findActor("record-flash"));

        // A vehicle has no critical table: its SYSTEMS tab opens on the location's equipment.
        show(TANK, SheetTab.ARMOR, true, GamePhase.FIRING);
        GpuPaperdoll tank = find("record-doll-armor");
        String location = tank.pick(tank.getWidth() / 2, tank.getHeight() / 2);
        assertNotNull(location, "the doll's middle is a location");
        clickRegion(tank, location);
        update(TANK, GamePhase.FIRING);
        assertEquals(SheetTab.SYSTEMS, hud.state.sheetTab);
    }

    @Test
    void theSheetsControlsPostTheRecordsCommandsForTheShownUnit() throws Exception {
        // The heat sink stepper: one sink fewer from the next round.
        show(ATLAS, SheetTab.STATUS, true, GamePhase.FIRING);
        GpuUnitRecord.Snapshot record = fixture.frame(ATLAS).panels().record();
        int sinks = record.systems().stream().filter(control -> control.id().equals(GpuUnitRecord.HEAT_SINKS))
              .findFirst().orElseThrow().selected();
        click(find("record-step-previous-" + GpuUnitRecord.HEAT_SINKS));
        verify(records).setSystem(ATLAS, GpuUnitRecord.HEAT_SINKS, sinks - 1);

        // Alt+Down on the focused weapon row flies it one place down, then moves the weapon once it lands.
        List<GpuUnitRecord.RecordWeapon> weapons = record.weapons();
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        click(find("record-weapon-0"));
        update(ATLAS, GamePhase.FIRING);
        when(Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn(true);
        assertTrue(hud.keyDown(Input.Keys.DOWN, KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK));
        when(Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn(false);
        verify(records, never()).moveWeapon(anyInt(), anyInt(), anyInt());
        UiTestStage.settle(hud.stage);
        verify(records).moveWeapon(ATLAS, weapons.get(0).eqNum(), 1);
        // The mocked record keeps its order, as a refused move would: after a second the rows go back to it. Then
        // the second row's grip, shown under the pointer, dragged onto the fourth row's place moves it two places.
        for (int frame = 0; frame < 80; frame++) {
            hud.stage.act(1 / 60f);
        }
        UiTestStage.settle(hud.stage);
        update(ATLAS, GamePhase.FIRING);
        hover(find("record-weapon-1"));
        Table second = find("record-weapon-1");
        drag(second.getChildren().first(), find("record-weapon-3"));
        UiTestStage.settle(hud.stage);
        verify(records).moveWeapon(ATLAS, weapons.get(1).eqNum(), 2);
    }

    @Test
    void thisRoundSumsWhatTheUnitTookAndDealtAndListsItsCriticalHitsAndRolls() throws Exception {
        List<GpuBattleStatus.UnitStatus> units = fixture.frame(ATLAS).status().units();
        String tank = GpuUnitCard.unitName(GpuHudState.unit(fixture.frame(ATLAS).status(), TANK));
        List<GpuReportLog.CombatEvent> combat = List.of(
              shot(CONTACT, ATLAS, true, new ResolvedAttack.Impact("RT", false, 6),
                    new ResolvedAttack.Impact("CT", false, 4)),
              shot(TANK, ATLAS, false),
              shot(TANK, ATLAS, true, new ResolvedAttack.Impact("RT", false, 5)),
              shot(ATLAS, TANK, true, new ResolvedAttack.Impact("FR", false, 12),
                    new ResolvedAttack.Impact("LS", false, 8)));
        List<GpuReportLog.PsrItem> rolls = List.of(
              new GpuReportLog.PsrItem(1, 4, GamePhase.FIRING, ATLAS, 7, 5, false, " took 20+ damage "),
              new GpuReportLog.PsrItem(2, 4, GamePhase.FIRING, CENTURION, 5, 9, true, ""));
        List<GpuReportLog.CritItem> crits = List.of(
              new GpuReportLog.CritItem(3, 4, GamePhase.FIRING, ATLAS, "RT", "Heat Sink"),
              new GpuReportLog.CritItem(4, 4, GamePhase.FIRING, TANK, "FR", "Driver injured!"),
              new GpuReportLog.CritItem(5, 4, GamePhase.FIRING, ATLAS, "", "Engine"));
        GpuReportLog.Snapshot reports = new GpuReportLog.Snapshot(4, GamePhase.FIRING_REPORT, List.of(), Map.of(),
              combat, rolls, List.of(), List.of(), crits);
        // The sensor contact stays unidentified; a miss adds nothing; another unit's roll and critical hit are not the
        // Atlas's.
        assertEquals(List.of(
              new GpuUnitSheetTabs.Round(text("GpuBoard.hud.unit.took", 15),
                    String.join(" · ", "RT 11", "CT 4", text("GpuBoard.hud.common.sensorContact"), tank)),
              new GpuUnitSheetTabs.Round(text("GpuBoard.hud.unit.crit"), "RT · Heat Sink"),
              new GpuUnitSheetTabs.Round(text("GpuBoard.hud.unit.crit"), "Engine"),
              new GpuUnitSheetTabs.Round(text("GpuBoard.hud.unit.psr", 7),
                    text("GpuBoard.hud.unit.psrFailed", 5) + " · took 20+ damage"),
              new GpuUnitSheetTabs.Round(text("GpuBoard.hud.unit.dealt", 20), String.join(" · ", tank, "FR 12",
                    "LS 8"))), GpuUnitSheetTabs.thisRound(reports, ATLAS, units));
    }

    @Test
    void aLossShowsAsAPlaybackDeltaOnTheDollsForTwoSeconds() throws Exception {
        show(CENTURION, SheetTab.ARMOR, true, GamePhase.FIRING);
        GpuUnitRecord.Snapshot before = fixture.frame(CENTURION).panels().record();
        Entity centurion = fixture.entity(CENTURION);
        int armor = centurion.getArmor(Mek.LOC_RIGHT_TORSO);
        fixture.edit(() -> centurion.setArmor(armor - 5, Mek.LOC_RIGHT_TORSO));
        try {
            update(CENTURION, GamePhase.FIRING);
            assertTrue(texts(find("record-doll-armor")).contains("\u22125"), "the right torso's loss");
            GpuUnitRecord.Snapshot after = fixture.frame(CENTURION).panels().record();
            GpuUnitCard.Deltas deltas = GpuUnitCard.Deltas.NONE.next(before, after, 1000);
            assertEquals(List.of(Map.of("RT", 5), Map.of(), Map.of()),
                  List.of(deltas.front(), deltas.rear(), deltas.structure()));
            assertSame(deltas, deltas.shown(2999));
            assertSame(GpuUnitCard.Deltas.NONE, deltas.shown(3000));
            // Neither a change without a loss nor another unit's record replaces them.
            assertSame(deltas, deltas.next(after, before, 1500));
            assertSame(deltas, deltas.next(fixture.frame(ATLAS).panels().record(), after, 1500));
        } finally {
            fixture.edit(() -> centurion.setArmor(armor, Mek.LOC_RIGHT_TORSO));
        }
    }

    @Test
    void hoveringALocationLinksItOnTheOtherDollsInTheInspectorAndOnItsRowsAndBlocks() throws Exception {
        show(ATLAS, SheetTab.ARMOR, true, GamePhase.FIRING);
        GpuPaperdoll front = find("record-doll-armor");
        GpuPaperdoll structure = find("record-doll-structure");
        assertEquals(List.of(), texts(find("record-inspector")), "no inspector without a selection");
        hoverRegion(front, "LA");
        update(ATLAS, GamePhase.FIRING);
        assertEquals(List.of("LA", "", "LA"), List.of(hud.state.linkedLocation, front.linked(), structure.linked()),
              "the hovered doll draws its hover, the other dolls the linked outline");
        assertEquals(UiTheme.upper(location(ATLAS, "LA").name()), texts(find("record-inspector")).getFirst());
        away();
        update(ATLAS, GamePhase.FIRING);
        assertEquals(List.of("", "", List.of()), List.of(hud.state.linkedLocation, structure.linked(),
              texts(find("record-inspector"))), "the pointer gone, so are the link and the inspector");

        // The critical table: a slot links its location, whose block takes the tint.
        show(ATLAS, SheetTab.SYSTEMS, true, GamePhase.FIRING);
        hover(line("LT", 8));
        update(ATLAS, GamePhase.FIRING);
        assertEquals("LT", hud.state.linkedLocation);
        assertNotNull(((Table) find("crit-block-LT")).getBackground());
        assertEquals(null, ((Table) find("crit-block-RT")).getBackground());
        // The weapons: a row links its location and tints the rows of it.
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        List<GpuUnitRecord.RecordWeapon> weapons = fixture.frame(ATLAS).panels().record().weapons();
        hover(find("record-weapon-0"));
        update(ATLAS, GamePhase.FIRING);
        assertEquals("LA", hud.state.linkedLocation);
        for (int index = 0; index < weapons.size(); index++) {
            Table row = find("record-weapon-" + index);
            assertEquals(weapons.get(index).location().equals("LA"), row.getBackground() != null,
                  weapons.get(index).name() + " in " + weapons.get(index).location());
        }
        away();
        update(ATLAS, GamePhase.FIRING);
        assertEquals("", hud.state.linkedLocation);
    }

    @Test
    void theGripShowsUnderThePointerAndOnTheFocusedRowOnly() throws Exception {
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        assertFalse(grip(2).isVisible());
        hover(find("record-weapon-2"));
        assertTrue(grip(2).isVisible(), "on hover");
        away();
        assertFalse(grip(2).isVisible());
        click(find("record-weapon-1"));
        update(ATLAS, GamePhase.FIRING);
        away();
        assertTrue(grip(1).isVisible(), "the focused list's selected row");
        assertTrue(escape(), "the first Esc ends the list focus");
        update(ATLAS, GamePhase.FIRING);
        assertFalse(grip(1).isVisible());
    }

    @Test
    void theMovementSectionNamesWhyTheMpAreReduced() throws Exception {
        // The unit tooltip's causes: the Atlas runs hot with a hit lower leg actuator; the Centurion's breached leg and
        // its shield.
        show(ATLAS, SheetTab.STATUS, true, GamePhase.FIRING);
        assertEquals(List.of(chip("GpuBoard.hud.unit.cause.HEAT", 1), chip("GpuBoard.hud.unit.cause.DAMAGE"),
              text("GpuBoard.hud.unit.mpFrom", "3 / 5 / 0")), texts(find("record-mp-causes")));
        show(CENTURION, SheetTab.STATUS, true, GamePhase.FIRING);
        assertEquals(List.of(chip("GpuBoard.hud.unit.cause.DAMAGE"), chip("GpuBoard.hud.unit.cause.SHIELD"),
              text("GpuBoard.hud.unit.mpFrom", "5 / 8 / 4")), texts(find("record-mp-causes")));
    }

    @Test
    void ammunitionDotsNeverOverstateTheBin() {
        assertArrayEquals(new int[] { 5, 3 }, GpuCriticalTable.dots(4, 6));
        assertArrayEquals(new int[] { 5, 3 }, GpuCriticalTable.dots(5, 8));
        assertArrayEquals(new int[] { 5, 5 }, GpuCriticalTable.dots(12, 12));
        assertArrayEquals(new int[] { 5, 4 }, GpuCriticalTable.dots(19, 20), "never full unless the bin is");
        assertArrayEquals(new int[] { 5, 1 }, GpuCriticalTable.dots(1, 20), "never empty while a shot is left");
        assertArrayEquals(new int[] { 5, 0 }, GpuCriticalTable.dots(0, 6));
        assertArrayEquals(new int[] { 2, 1 }, GpuCriticalTable.dots(1, 2), "one dot per shot up to five");
    }

    @Test
    void aSlotsPopoverDumpsItsBinOrNamesTheRuleThatBlocksIt() throws Exception {
        GpuUnitRecord.Slot lrm = location(ATLAS, "LT").slots().get(8);
        // The fixture's game is in the movement phase, when no ammunition can be dumped
        show(ATLAS, SheetTab.SYSTEMS, true, GamePhase.MOVEMENT);
        click(line("LT", 8));
        update(ATLAS, GamePhase.MOVEMENT);
        UiButton dump = find("record-slot-dump-" + lrm.eqNum());
        assertTrue(dump.isDisabled());
        assertEquals(text("MekDisplay.DumpBlocked.phase"), tip(dump));
        click(dump);
        verify(records, never()).setDumping(anyInt(), anyInt(), anyBoolean());

        // After movement under rules that allow it, the button dumps the bin and closes the popover
        RulesManager rules = Game.rulesManager;
        try {
            fixture.edit(() -> {
                fixture.board.game.initializeRulesManager(OptionsConstants.RULES_TW);
                fixture.board.game.setPhase(GamePhase.FIRING);
            });
            show(ATLAS, SheetTab.SYSTEMS, true, GamePhase.FIRING);
            click(line("LT", 8));
            update(ATLAS, GamePhase.FIRING);
            dump = find("record-slot-dump-" + lrm.eqNum());
            assertFalse(dump.isDisabled());
            assertEquals(UiTheme.upper(text("MekDisplay.m_bDumpAmmo")), dump.getText().toString());
            click(dump);
            verify(records).setDumping(ATLAS, lrm.eqNum(), true);
            assertFalse(popover().isVisible());
        } finally {
            Game.rulesManager = rules;
            fixture.edit(() -> fixture.board.game.setPhase(GamePhase.MOVEMENT));
        }
    }

    @Test
    void eachBinOfASlotWithTwoHasItsOwnControls() throws Exception {
        GpuUnitRecord.Snapshot record = fixture.frame(ATLAS).panels().record();
        List<GpuUnitRecord.Slot> bins = location(ATLAS, "RA").slots().stream().filter(slot -> slot.shots() >= 0)
              .toList();
        GpuUnitRecord.Slot first = bins.get(0);
        GpuUnitRecord.Slot second = bins.get(1);
        GpuBoardSource.Frame frame = with(fixture.frame(ATLAS), withDualSlot(record), null, List.of());
        hud.state.inspected = ATLAS;
        hud.state.recordOpen = true;
        hud.state.sheetTab = SheetTab.SYSTEMS;
        update(frame, GamePhase.FIRING);
        click(line("RA", first.index()));
        update(frame, GamePhase.FIRING);
        List<String> shown = texts((Group) popover());
        String name = record.ammo().stream().filter(bin -> bin.eqNum() == second.eqNum()).findFirst().orElseThrow()
              .name();
        assertEquals(2, shown.stream().filter(line -> line.startsWith(name)).count(), "both bins named: " + shown);

        click(find("record-slot-dump-" + second.eqNum()));
        verify(records).setDumping(ATLAS, second.eqNum(), true);
        verify(records, never()).setDumping(ATLAS, first.eqNum(), true);
    }

    @Test
    void anOwnWeaponLoadsTheBinPickedThroughTheRecordOrTheActorsOrders() throws Exception {
        GpuUnitRecord.Snapshot record = fixture.frame(ATLAS).panels().record();
        GpuUnitRecord.RecordWeapon lrm = record.weapons().stream().filter(weapon -> weapon.name().equals("LRM 20"))
              .findFirst().orElseThrow();
        assertEquals(List.of("[LT] LRM 20  (4)", "[LT] LRM 20  (6)"), lrm.ammoChoices().stream()
              .map(GpuUnitRecord.AmmoChoice::label).toList());
        hud.state.expandedWeapon = lrm.name();
        hud.state.expandedOrdinal = 0;
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        UiButton select = find("record-weapon-ammo");
        assertEquals(lrm.ammoChoices().get(lrm.loadedAmmo()).label(), select.getText().toString());
        click(select);
        update(ATLAS, GamePhase.FIRING);
        click(menuItems().get(1));
        verify(records).setAmmo(ATLAS, lrm.eqNum(), lrm.ammoChoices().get(1));

        // The local firing actor's weapon goes through the fire orders, which declare its queued attack again (H30)
        GpuBoardSource.Frame firing = with(fixture.frame(ATLAS), record, actor(-1), List.of());
        update(firing, GamePhase.FIRING);
        click(find("record-weapon-ammo"));
        update(firing, GamePhase.FIRING);
        click(menuItems().get(1));
        verify(orders).setAmmo(lrm.eqNum(), lrm.ammoChoices().get(1));
        verify(records, times(1)).setAmmo(anyInt(), anyInt(), any());
    }

    /**
     * P1 H9: MegaMek names the LRM's normal mode "" (the Unit Display lists a blank entry). The mode select and its
     * list read "Standard" there, and the entry still switches to MegaMek's own mode, by its index.
     */
    @Test
    void theNormalModeOfAnLrmReadsStandardAndPicksMegaMeksOwnMode() throws Exception {
        GpuUnitRecord.Snapshot record = fixture.frame(ATLAS).panels().record();
        GpuUnitRecord.RecordWeapon lrm = record.weapons().stream().filter(weapon -> weapon.name().equals("LRM 20"))
              .findFirst().orElseThrow();
        GpuUnitRecord.Equipment modes = record.equipment().stream().filter(item -> item.eqNum() == lrm.eqNum())
              .findFirst().orElseThrow();
        int normal = modes.modes().indexOf("");
        assertEquals(normal, modes.selected(), "the LRM fires in its normal mode: " + modes.modes());
        hud.state.expandedWeapon = lrm.name();
        hud.state.expandedOrdinal = 0;
        show(ATLAS, SheetTab.WEAPONS, true, GamePhase.FIRING);
        UiButton select = find("record-weapon-mode");
        String standard = text("GpuBoard.hud.unit.modeStandard");
        assertEquals(standard, select.getText().toString());
        click(select);
        update(ATLAS, GamePhase.FIRING);
        List<String> shown = modes.modes().stream().map(mode -> mode.isEmpty() ? standard : mode).toList();
        assertEquals(shown, menuItems().stream().map(item -> item.getText().toString()).toList());
        click(menuItems().get(normal));
        verify(records).setMode(ATLAS, lrm.eqNum(), normal);
    }

    @Test
    void theFieldOfFireButtonSelectsTheActorsWeaponOrSwitchesTheViewMenusFieldOfFire() throws Exception {
        GpuUnitRecord.Snapshot record = fixture.frame(ATLAS).panels().record();
        GpuUnitRecord.RecordWeapon gauss = record.weapons().stream()
              .filter(weapon -> weapon.name().equals("Gauss Rifle")).findFirst().orElseThrow();
        AtomicInteger switched = new AtomicInteger();
        hud.state.expandedWeapon = gauss.name();
        hud.state.expandedOrdinal = 0;
        hud.state.inspected = ATLAS;
        hud.state.recordOpen = true;
        hud.state.sheetTab = SheetTab.WEAPONS;

        // Any weapon but the actor's: the View menu's switch alone, so no selection changes
        update(with(fixture.frame(ATLAS), record, null, fieldOfFire(false, switched)), GamePhase.FIRING);
        UiButton field = find("record-weapon-field");
        assertFalse(field.isChecked());
        click(field);
        assertEquals(1, switched.get());
        verify(orders, never()).selectWeapon(anyInt());
        verify(records, never()).setAmmo(anyInt(), anyInt(), any());

        // The actor's weapon: selected first, then the field of fire switched on; pressed while both hold
        update(with(fixture.frame(ATLAS), record, actor(-1), fieldOfFire(false, switched)), GamePhase.FIRING);
        click(find("record-weapon-field"));
        verify(orders).selectWeapon(gauss.eqNum());
        assertEquals(2, switched.get());
        update(with(fixture.frame(ATLAS), record, actor(gauss.eqNum()), fieldOfFire(true, switched)),
              GamePhase.FIRING);
        assertTrue(((UiButton) find("record-weapon-field")).isChecked());
        click(find("record-weapon-field"));
        assertEquals(3, switched.get(), "pressed, it switches the field of fire off");
        verify(orders, times(1)).selectWeapon(anyInt());
    }

    @Test
    void cancelInPendingChangesRestoresTheValueInEffectThroughTheSameCommand() throws Exception {
        Entity atlas = fixture.entity(ATLAS);
        fixture.edit(() -> atlas.setNextSensor(atlas.getSensors().get(1)));
        try {
            show(ATLAS, SheetTab.STATUS, true, GamePhase.FIRING);
            GpuUnitRecord.SystemControl sensors = fixture.frame(ATLAS).panels().record().systems().stream()
                  .filter(control -> control.id().equals(GpuUnitRecord.SENSORS)).findFirst().orElseThrow();
            assertEquals(List.of(1, 0), List.of(sensors.selected(), sensors.current()));
            click(find("record-pending-cancel-0"));
            verify(records).setSystem(ATLAS, GpuUnitRecord.SENSORS, 0);
        } finally {
            fixture.edit(() -> atlas.setNextSensor(atlas.getSensors().get(0)));
        }

        // A queued mode goes back to the mode in effect, a dump is cancelled while the rules let it be; another
        // player's changes have no Cancel
        GpuUnitRecord.Equipment sink = new GpuUnitRecord.Equipment(7, "Heat Sink", "LA", List.of("On", "Off"), 1, 0,
              1, true);
        GpuUnitRecord.AmmoBin dumping = new GpuUnitRecord.AmmoBin(9, "AC/20 Ammo", "RT", 5, 5, true, false, false,
              false, false, "MekDisplay.DumpBlocked.dumping");
        GpuUnitRecord.Snapshot own = new GpuUnitRecord.Snapshot(ATLAS, true, List.of(), List.of(), 0, List.of(),
              List.of(sink), List.of(), List.of(), List.of(), List.of(dumping), List.of(), List.of(), "", "", "");
        List<GpuUnitSheetTabs.Pending> pending = GpuUnitSheetTabs.pending(own);
        assertEquals(List.of(true, false), pending.stream().map(GpuUnitSheetTabs.Pending::enabled).toList());
        assertEquals("MekDisplay.DumpBlocked.dumping", pending.get(1).blocker());
        GpuUnitRecord commands = mock(GpuUnitRecord.class);
        pending.forEach(change -> change.cancel().accept(commands));
        verify(commands).setMode(ATLAS, 7, 0);
        verify(commands).setDumping(ATLAS, 9, false);
        GpuUnitRecord.Snapshot enemy = new GpuUnitRecord.Snapshot(TANK, false, List.of(), List.of(), 0, List.of(),
              List.of(), List.of(), List.of(), List.of(), List.of(dumping), List.of(), List.of(), "", "", "");
        assertNull(GpuUnitSheetTabs.pending(enemy).getFirst().cancel());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Shows {@code unit} on the card (inspected) with the sheet open or closed on {@code tab}, in {@code phase}. */
    private void show(int unit, SheetTab tab, boolean open, GamePhase phase) throws Exception {
        hud.state.inspected = unit;
        hud.state.recordOpen = open;
        hud.state.sheetTab = tab;
        update(unit, phase);
    }

    /**
     * The chip line shows the chips in order, and those that do not fit as one "+n" at its end (the widths depend on
     * the messages, which the text pass adds).
     */
    private static void assertShows(List<String> chips, List<String> shown) {
        int fitted = shown.size() > 0 && shown.getLast().startsWith("+") ? shown.size() - 1 : shown.size();
        List<String> expected = new ArrayList<>(chips.subList(0, fitted));
        if (fitted < chips.size()) {
            expected.add("+" + (chips.size() - fitted));
        }
        assertEquals(expected, shown);
    }

    /** A chip's text as the card draws it: the message upper-cased. */
    private static String chip(String key, Object... arguments) {
        return UiTheme.upper(text(key, arguments));
    }

    /** Two frames of the unit's capture in {@code phase}, so that the layout follows the components' content. */
    private void update(int unit, GamePhase phase) throws Exception {
        update(fixture.frame(unit), phase);
    }

    /** Frames of a capture in {@code phase}, so that the layout follows the components' content. */
    private void update(GpuBoardSource.Frame captured, GamePhase phase) {
        GpuBattleStatus.Snapshot status = captured.status();
        GpuBoardSource.Frame frame = new GpuBoardSource.Frame(captured.scene(), captured.timeline(),
              captured.context(), captured.globalCommands(), captured.tooltip(),
              captured.centerRequest(), captured.boardGeneration(), captured.actorName(),
              captured.scenarioAtmosphere(), captured.reports(),
              new GpuBattleStatus.Snapshot(status.round(), phase, false, status.localPlayerId(), Entity.NONE, List.of(),
                    -1, status.units(), List.of(), false), captured.panels());
        for (int pass = 0; pass < 3; pass++) {
            hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
            validate(hud.stage.getRoot());
            hud.stage.act(0);
        }
    }

    /**
     * The frame with another record, the fire orders' snapshot ({@code fire}; null keeps the frame's) and the View
     * menu's commands.
     */
    static GpuBoardSource.Frame with(GpuBoardSource.Frame frame, GpuUnitRecord.Snapshot record,
          GpuFireOrders.Snapshot fire, List<BoardScene.Command> commands) {
        GpuHudData panels = frame.panels();
        GpuHudData changed = new GpuHudData(panels.phase(), panels.move(), fire == null ? panels.fire() : fire,
              panels.physical(), record, panels.preview(), panels.chat(), panels.toasts(), panels.los(),
              panels.players());
        return new GpuBoardSource.Frame(frame.scene(), frame.timeline(), frame.context(), commands,
              frame.tooltip(), frame.centerRequest(), frame.boardGeneration(), frame.actorName(),
              frame.scenarioAtmosphere(), frame.reports(), frame.status(), changed);
    }

    /** The local declaration of the Atlas, with {@code weapon} selected (-1: none). */
    private static GpuFireOrders.Snapshot actor(int weapon) {
        GpuFireOrders.Snapshot fire = mock(GpuFireOrders.Snapshot.class);
        when(fire.active()).thenReturn(true);
        when(fire.editable()).thenReturn(true);
        when(fire.actorId()).thenReturn(ATLAS);
        when(fire.selectedWeapon()).thenReturn(weapon);
        when(fire.focus()).thenReturn(GpuFireOrders.Focus.NONE);
        return fire;
    }

    /** The View menu's field of fire switch, shown or not, counting its runs. */
    static List<BoardScene.Command> fieldOfFire(boolean shown, AtomicInteger runs) {
        return List.of(new BoardScene.Command("view/" + ClientGUI.VIEW_TOGGLE_FIELD_OF_FIRE + ":Field of Fire",
              "Field of Fire", "", true, false, false, List.of(), runs::incrementAndGet, "R", shown));
    }

    /**
     * The Atlas AS7-K's record with its two Gauss bins as the two bins of one slot (at the first's place), as a
     * superheavy Mek has them, both dumpable now.
     */
    static GpuUnitRecord.Snapshot withDualSlot(GpuUnitRecord.Snapshot record) {
        GpuUnitRecord.Location arm = record.locations().stream().filter(location -> location.abbr().equals("RA"))
              .findFirst().orElseThrow();
        List<GpuUnitRecord.Slot> bins = arm.slots().stream().filter(slot -> slot.shots() >= 0).toList();
        GpuUnitRecord.Slot first = bins.get(0);
        GpuUnitRecord.Slot second = bins.get(1);
        GpuUnitRecord.Slot dual = new GpuUnitRecord.Slot(first.index(), first.text(), first.eqNum(), second.eqNum(),
              -1, false, false, false, false, false, false, false, first.shots() + second.shots(),
              first.fullShots() + second.fullShots(), false, false);
        List<GpuUnitRecord.AmmoBin> ammo = record.ammo().stream().map(bin -> bin.eqNum() == first.eqNum()
              || bin.eqNum() == second.eqNum() ? new GpuUnitRecord.AmmoBin(bin.eqNum(), bin.name(), bin.location(),
              bin.shots(), bin.fullShots(), false, false, false, false, true, "") : bin).toList();
        return withSlot(record, "RA", dual, ammo);
    }

    /** The record with one slot of a location replaced and other ammunition bins. */
    private static GpuUnitRecord.Snapshot withSlot(GpuUnitRecord.Snapshot record, String abbr, GpuUnitRecord.Slot slot,
          List<GpuUnitRecord.AmmoBin> ammo) {
        List<GpuUnitRecord.Location> locations = record.locations().stream().map(location -> {
            if (!location.abbr().equals(abbr)) {
                return location;
            }
            List<GpuUnitRecord.Slot> slots = location.slots().stream()
                  .map(old -> old.index() == slot.index() ? slot : old).toList();
            return new GpuUnitRecord.Location(location.abbr(), location.name(), location.armor(),
                  location.maxArmor(), location.rear(), location.maxRear(), location.internal(),
                  location.maxInternal(), location.destroyed(), location.blownOff(), location.breached(),
                  location.transferTo(), location.dependent(), location.bar(), location.threshold(),
                  location.caseTag(), location.slotCount(), slots);
        }).toList();
        return new GpuUnitRecord.Snapshot(record.unitId(), record.own(), record.removed(), record.paperdoll(),
              record.damageLevel(), locations, record.vitals(), record.hitTracks(), record.shields(),
              record.heatTicks(), record.heatTickEffects(), record.heatScale(), record.heatBuildup(), record.weapons(),
              record.equipment(), record.systems(), record.crew(), record.abilities(), ammo, record.conditions(),
              record.carried(), record.unused(), record.lastTarget(), record.info(), record.readoutRows(),
              record.readout(), record.damage());
    }

    /** The tooltip text of an actor (its TextTooltip's label). */
    private static String tip(Actor actor) {
        for (EventListener listener : actor.getListeners()) {
            if (listener instanceof TextTooltip tooltip) {
                return tooltip.getActor().getText().toString();
            }
        }
        throw new AssertionError("no tooltip on " + actor);
    }

    /** The items of the open context menu or select list, in order. */
    private List<UiButton> menuItems() {
        List<UiButton> items = new ArrayList<>();
        collectButtons(find("context-menu-popover"), items);
        return items;
    }

    private static void collectButtons(Actor actor, List<UiButton> found) {
        if (actor instanceof UiButton button) {
            found.add(button);
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collectButtons(child, found));
        }
    }

    /** Lays out every widget from the top, as drawing would: a parent places its children before they lay out. */
    private static void validate(Actor actor) {
        if (actor instanceof Layout layout) {
            layout.validate();
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(GpuUnitPanelTest::validate);
        }
    }

    private <T extends Actor> T find(String name) {
        T actor = hud.stage.getRoot().findActor(name);
        assertNotNull(actor, name);
        return actor;
    }

    private Rectangle bounds(String name) {
        Actor actor = find(name);
        Vector2 corner = actor.localToStageCoordinates(new Vector2());
        return new Rectangle(corner.x, corner.y, actor.getWidth(), actor.getHeight());
    }

    private static boolean visible(Actor actor) {
        for (Actor node = actor; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** The texts of the visible labels under an actor, in tree order. */
    private static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        if (!actor.isVisible()) {
            return texts;
        }
        if (actor instanceof Label label) {
            texts.add(label.getText().toString());
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> texts.addAll(texts(child)));
        }
        return texts;
    }

    private GpuCriticalTable.Line line(String location, int slot) {
        return find("crit-" + location + "-" + slot);
    }

    private GpuCriticalTable.StruckLabel slotName(String location, int slot) {
        for (Actor child : line(location, slot).getChildren()) {
            if (child instanceof GpuCriticalTable.StruckLabel label) {
                return label;
            }
        }
        throw new AssertionError("no name on " + location + " " + slot);
    }

    private static Strike strike(GpuCriticalTable.StruckLabel label) {
        return label.strike();
    }

    private com.badlogic.gdx.scenes.scene2d.ui.Cell<Actor> cell(String location, int slot) {
        GpuCriticalTable.Line line = line(location, slot);
        return ((Table) line.getParent()).getCell(line);
    }

    private int lines(String location) {
        int count = 0;
        while (hud.stage.getRoot().findActor("crit-" + location + "-" + count) != null) {
            count++;
        }
        return count;
    }

    /** The heading of a location's block, found by its text. */
    private Label heading(String text) {
        List<Label> found = new ArrayList<>();
        collect(find("record-sheet-body"), text, found);
        assertEquals(1, found.size(), text);
        return found.getFirst();
    }

    private static void collect(Actor actor, String text, List<Label> found) {
        if (actor instanceof Label label && label.getText().toString().equals(text)) {
            found.add(label);
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, text, found));
        }
    }

    /** The filled ammunition dots of a slot line (coloured ink2 discs). */
    private static int filledDots(Group line) {
        int filled = 0;
        for (Actor child : line.getChildren()) {
            if (child instanceof Table pips) {
                for (Actor dot : pips.getChildren()) {
                    if (dot instanceof Image image && colourOf(image).equals(UiTheme.ACCENT)) {
                        filled++;
                    }
                }
            }
        }
        return filled;
    }

    /** The coral circles of each hit box row, by its label. */
    private List<Integer> hitBox(List<String> labels) {
        List<Integer> hits = new ArrayList<>();
        for (String track : labels) {
            Label label = heading(UiTheme.upper(text("GpuBoard.hud.unit.track." + track)));
            Table box = (Table) label.getParent();
            Table marks = (Table) box.getChildren().get(box.getChildren().indexOf(label, true) + 1);
            hits.add((int) java.util.stream.StreamSupport.stream(marks.getChildren().spliterator(), false)
                  .filter(dot -> dot instanceof Image image && colourOf(image).equals(UiTheme.CORAL)).count());
        }
        return hits;
    }

    private static Color colourOf(Image image) {
        return ((NinePatchDrawable) image.getDrawable()).getPatch().getColor();
    }

    private List<String> dollShields(int unit) throws Exception {
        GpuUnitRecord.Snapshot record = fixture.frame(unit).panels().record();
        GpuPaperdolls.Doll doll = GpuUnitCard.doll(new GpuPaperdolls(), record, GpuPaperdolls.ARMOR);
        assertNotNull(doll, "a doll for " + record.paperdoll());
        return doll.shields().stream().map(GpuPaperdolls.Shield::arm).toList();
    }

    private static GpuUnitRecord.Location location(int unit, String abbr) throws Exception {
        return fixture.frame(unit).panels().record().locations().stream()
              .filter(location -> location.abbr().equals(abbr)).findFirst().orElseThrow();
    }

    private static GpuUnitRecord.Snapshot withShield(GpuUnitRecord.Snapshot record, GpuUnitRecord.Shield shield) {
        return new GpuUnitRecord.Snapshot(record.unitId(), record.own(), record.removed(), record.paperdoll(),
              record.damageLevel(), record.locations(), record.vitals(), record.hitTracks(), List.of(shield),
              record.heatTicks(), record.heatTickEffects(), record.heatScale(), record.heatBuildup(), record.weapons(),
              record.equipment(), record.systems(), record.crew(), record.abilities(), record.ammo(),
              record.conditions(), record.carried(), record.unused(), record.lastTarget(), record.info(),
              record.readoutRows(), record.readout(), record.damage());
    }

    /** The sheet's popover: the window-sized overlay's one child. */
    private Actor popover() {
        Group overlay = (Group) find("record-sheet").getStage().getRoot().findActor("record-sheet-popover");
        assertNotNull(overlay);
        return overlay;
    }

    /** Clicks an actor at its centre through the HUD's stage. */
    private void click(Actor actor) {
        Vector2 point = centre(actor);
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    /** Drags from one actor's centre to another's through the HUD's stage. */
    private void drag(Actor from, Actor to) {
        Vector2 start = centre(from);
        Vector2 end = centre(to);
        hud.stage.touchDown((int) start.x, (int) start.y, 0, Input.Buttons.LEFT);
        hud.stage.touchDragged((int) end.x, (int) end.y, 0);
        hud.stage.touchUp((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
    }

    private Vector2 centre(Actor actor) {
        return hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }

    /** Moves the pointer onto an actor's centre; the stage's act fires the enter and exit events. */
    private void hover(Actor actor) {
        Vector2 point = centre(actor);
        hud.stage.mouseMoved((int) point.x, (int) point.y);
        hud.stage.act(0);
    }

    /** Moves the pointer off the sheet, to the window's bottom right corner. */
    private void away() {
        hud.stage.mouseMoved(hud.stage.getViewport().getScreenWidth() - 1,
              hud.stage.getViewport().getScreenHeight() - 1);
        hud.stage.act(0);
    }

    /** Moves the pointer into a doll's location. */
    private void hoverRegion(GpuPaperdoll doll, String code) {
        Vector2 point = hud.stage.stageToScreenCoordinates(doll.localToStageCoordinates(inside(doll, code)));
        hud.stage.mouseMoved((int) point.x, (int) point.y);
        hud.stage.act(0);
    }

    /**
     * A point well inside a doll's location: of the points 2 units apart that pick it, the one nearest their centre,
     * so that the round trip through screen pixels stays in the region.
     */
    static Vector2 inside(GpuPaperdoll doll, String code) {
        List<Vector2> points = new ArrayList<>();
        for (float y = 1; y < doll.getHeight(); y += 2) {
            for (float x = 1; x < doll.getWidth(); x += 2) {
                if (code.equals(doll.pick(x, y))) {
                    points.add(new Vector2(x, y));
                }
            }
        }
        if (points.isEmpty()) {
            throw new AssertionError("no " + code + " on the doll");
        }
        Vector2 centre = new Vector2();
        points.forEach(centre::add);
        centre.scl(1f / points.size());
        return points.stream().min(java.util.Comparator.comparingDouble(point -> point.dst2(centre))).orElseThrow();
    }

    /** A weapon row's grip, its first cell. */
    private Actor grip(int row) {
        return ((Table) find("record-weapon-" + row)).getChildren().first();
    }

    /** Clicks a doll inside a location. */
    private void clickRegion(GpuPaperdoll doll, String code) {
        Vector2 point = hud.stage.stageToScreenCoordinates(doll.localToStageCoordinates(inside(doll, code)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    private static GpuReportLog.CombatEvent shot(int attacker, int target, boolean hit,
          ResolvedAttack.Impact... impacts) {
        int damage = java.util.Arrays.stream(impacts).mapToInt(ResolvedAttack.Impact::weight).sum();
        return new GpuReportLog.CombatEvent(UUID.randomUUID(), 4, GamePhase.FIRING, ResolvedAttack.Kind.SHOT, attacker,
              target, "Medium Laser", "RA", hit, damage, List.of(impacts), null, 0);
    }

    private boolean escape() {
        return hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
    }
}
