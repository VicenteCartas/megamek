/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.BATTLE_ARMOR;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.CENTURION;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.CONTACT;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.DROPSHIP;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.FIGHTER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.INFANTRY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.TANK;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.TRIPOD;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.VTOL;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.WARSHIP;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.WRECK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.SheetTab;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The unit panel in the real HUD at the approved mockup's three stages (unit panel design 14 U3): 1778 x 1000 (1920 x
 * 1080 at 1.08), 1536 x 864 (at 1.25) and 1280 x 720. For every family of the fixture it writes the full card with
 * the sheet closed, and the sheet with the mini card on the critical table or SYSTEMS and on ARMOR; the Atlas AS7-K's
 * other tabs at 1778. Every label must show a message (the unit panel's keys arrive with the text pass T1). The
 * comparison with the mockup renders is made by eye.
 */
@Tag("on-demand")
class GpuUnitCardSmokeTest {
    /** The stages: window width and height in back-buffer pixels, HUD scale, and their names. */
    private static final float[][] STAGES = { { 1920, 1080, 1.08f }, { 1920, 1080, 1.25f }, { 1280, 720, 1 } };
    private static final List<String> STAGE_NAMES = List.of("1778", "1536", "1280");

    @Test
    void theCardAndSheetOfEveryFamilyAtTheReferenceStages() throws Exception {
        Map<String, Integer> units = new LinkedHashMap<>();
        units.put("atlas", ATLAS);
        units.put("centurion", CENTURION);
        units.put("tripod", TRIPOD);
        units.put("tank", TANK);
        units.put("vtol", VTOL);
        units.put("battle-armor", BATTLE_ARMOR);
        units.put("infantry", INFANTRY);
        units.put("fighter", FIGHTER);
        units.put("dropship", DROPSHIP);
        units.put("warship", WARSHIP);
        units.put("destroyed", WRECK);
        units.put("contact", CONTACT);
        Map<Integer, GpuBoardSource.Frame> frames = new HashMap<>();
        try (GpuUnitPanelFixture fixture = GpuUnitPanelFixture.create()) {
            for (int unit : units.values()) {
                frames.put(unit, firing(fixture.frame(unit)));
            }
            GpuHudTestStage.run(harness -> {
                SpriteBatch batch = new SpriteBatch();
                GpuBoardSource source = mock(GpuBoardSource.class);
                when(source.record()).thenReturn(mock(GpuUnitRecord.class));
                GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(),
                      mock(GpuBoardTuning.class), new GpuPlaybackHistory(new UnitPlayback()));
                try {
                    float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                    for (int index = 0; index < STAGES.length; index++) {
                        float[] stage = STAGES[index];
                        harness.size((int) stage[0], (int) stage[1]);
                        hud.resize(Math.round(stage[0] / density), Math.round(stage[1] / density),
                              stage[2] / density);
                        for (Map.Entry<String, Integer> unit : units.entrySet()) {
                            GpuBoardSource.Frame frame = frames.get(unit.getValue());
                            String name = "unit-panel-" + STAGE_NAMES.get(index) + "-" + unit.getKey();
                            capture(harness, hud, frame, unit.getValue(), null, name + "-card");
                            if (unit.getValue() == CONTACT) {
                                continue;
                            }
                            boolean mek = !frame.panels().record().hitTracks().isEmpty();
                            capture(harness, hud, frame, unit.getValue(), SheetTab.SYSTEMS,
                                  name + (mek ? "-critical" : "-systems"));
                            capture(harness, hud, frame, unit.getValue(), SheetTab.ARMOR, name + "-armor");
                            if (unit.getValue() == ATLAS) {
                                // Every stage: STATUS (the MP cause chips) and WEAPONS (two-line rows when narrow).
                                for (SheetTab tab : index == 0 ? List.of(SheetTab.STATUS, SheetTab.CREW,
                                      SheetTab.WEAPONS, SheetTab.EXTRAS) : List.of(SheetTab.STATUS, SheetTab.WEAPONS)) {
                                    capture(harness, hud, tab == SheetTab.EXTRAS ? withSensorRanges(frame) : frame,
                                          ATLAS, tab, name + "-" + tab.name().toLowerCase(java.util.Locale.ROOT));
                                }
                                weaponDetail(harness, hud, frame, name);
                                if (index == 0) {
                                    onDemand(harness, hud, frame, name);
                                    linked(harness, hud, frame, name);
                                    actions(harness, hud, fixture, frame, name);
                                    weaponDrag(harness, hud, frame, name);
                                }
                            }
                            if (unit.getValue() == CENTURION && index == 0) {
                                // The shield selected: one outline around both parts, the inspector's maxima.
                                hud.state.selectedLocation = "DCLA";
                                capture(harness, hud, frame, CENTURION, SheetTab.ARMOR, name + "-armor-shield");
                                hud.state.selectedLocation = "";
                            }
                        }
                    }
                } finally {
                    hud.dispose();
                    batch.dispose();
                }
            });
        }
    }

    /**
     * What shows on demand (design appendix): LA selected on the ARMOR tab with its inspector, a critical slot's
     * popover, and the card doll's hover card over the right leg.
     */
    private static void onDemand(GpuHudTestStage harness, GpuHud hud, GpuBoardSource.Frame frame, String name) {
        hud.state.selectedLocation = "LA";
        capture(harness, hud, frame, ATLAS, SheetTab.ARMOR, name + "-armor-selected");
        hud.state.selectedLocation = "";
        capture(harness, hud, frame, ATLAS, SheetTab.SYSTEMS, name + "-critical");
        Actor slot = hud.stage.getRoot().findActor("crit-LT-8");
        Vector2 point = hud.stage.stageToScreenCoordinates(slot.localToStageCoordinates(new Vector2(20, 7)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        capture(harness, hud, frame, ATLAS, SheetTab.SYSTEMS, name + "-popover");
        hud.keyDown(Input.Keys.ESCAPE, java.awt.event.KeyEvent.VK_ESCAPE, 0);
        capture(harness, hud, frame, ATLAS, null, name + "-card");
        GpuPaperdoll doll = hud.stage.getRoot().findActor("unit-card-doll");
        Vector2 leg = null;
        for (float y = 0; y < doll.getHeight() && leg == null; y += 2) {
            for (float x = 0; x < doll.getWidth() && leg == null; x += 2) {
                if ("RL".equals(doll.pick(x, y))) {
                    leg = new Vector2(x + 4, y + 8);
                }
            }
        }
        Vector2 screen = hud.stage.stageToScreenCoordinates(doll.localToStageCoordinates(leg));
        hud.stage.mouseMoved((int) screen.x, (int) screen.y);
        capture(harness, hud, frame, ATLAS, null, name + "-hover");
        hud.stage.mouseMoved(0, 0);
    }

    /**
     * The pointer's links (graft 6): over the front doll's left arm (the other dolls outline it, the inspector shows
     * it), over a slot of the left torso (its block tinted) and over a weapon row (its grip, the rows of its location
     * tinted).
     */
    private static void linked(GpuHudTestStage harness, GpuHud hud, GpuBoardSource.Frame frame, String name) {
        show(hud, frame, ATLAS, SheetTab.ARMOR);
        GpuPaperdoll front = hud.stage.getRoot().findActor("record-doll-armor");
        Vector2 arm = null;
        for (float y = 0; y < front.getHeight() && arm == null; y += 2) {
            for (float x = 0; x < front.getWidth() && arm == null; x += 2) {
                if ("LA".equals(front.pick(x, y))) {
                    arm = new Vector2(x + 2, y + 6);
                }
            }
        }
        point(hud, front, arm);
        capture(harness, hud, frame, ATLAS, SheetTab.ARMOR, name + "-armor-hover");
        show(hud, frame, ATLAS, SheetTab.SYSTEMS);
        Actor slot = hud.stage.getRoot().findActor("crit-LT-4");
        point(hud, slot, new Vector2(30, 7));
        capture(harness, hud, frame, ATLAS, SheetTab.SYSTEMS, name + "-critical-hover");
        show(hud, frame, ATLAS, SheetTab.WEAPONS);
        Actor row = hud.stage.getRoot().findActor("record-weapon-4");
        point(hud, row, new Vector2(row.getWidth() / 2, row.getHeight() / 2));
        capture(harness, hud, frame, ATLAS, SheetTab.WEAPONS, name + "-weapons-hover");
        hud.stage.mouseMoved(0, 0);
    }

    /**
     * A weapon row dragged in the WEAPONS tab (H29 in the sheet): the third row's grip, shown under the pointer, held
     * a row and a half lower: the row floats over the table with its shadow and the next row rose into its place.
     */
    private static void weaponDrag(GpuHudTestStage harness, GpuHud hud, GpuBoardSource.Frame frame, String name) {
        show(hud, frame, ATLAS, SheetTab.WEAPONS);
        Table row = hud.stage.getRoot().findActor("record-weapon-2");
        point(hud, row, new Vector2(row.getWidth() / 2, row.getHeight() / 2));
        hud.stage.act(0);
        Actor grip = row.getChildren().first();
        Vector2 from = grip.localToStageCoordinates(new Vector2(grip.getWidth() / 2, grip.getHeight() / 2));
        Vector2 to = new Vector2(from.x, from.y - 1.4f * (row.getHeight() + 1));
        Vector2 press = hud.stage.stageToScreenCoordinates(from.cpy());
        hud.stage.touchDown(Math.round(press.x), Math.round(press.y), 0, Input.Buttons.LEFT);
        for (int step = 1; step <= 6; step++) {
            Vector2 point = hud.stage.stageToScreenCoordinates(from.cpy().lerp(to, step / 6f));
            hud.stage.touchDragged(Math.round(point.x), Math.round(point.y), 0);
        }
        for (int step = 0; step < 24; step++) {
            hud.stage.act(1 / 60f);
        }
        capture(harness, hud, frame, ATLAS, SheetTab.WEAPONS, name + "-weapons-drag");
        Vector2 drop = hud.stage.stageToScreenCoordinates(to.cpy());
        hud.stage.touchUp(Math.round(drop.x), Math.round(drop.y), 0, Input.Buttons.LEFT);
        UiTestStage.settle(hud.stage);
        hud.stage.mouseMoved(0, 0);
    }

    private static void point(GpuHud hud, Actor actor, Vector2 local) {
        Vector2 screen = hud.stage.stageToScreenCoordinates(actor.localToStageCoordinates(local));
        hud.stage.mouseMoved((int) screen.x, (int) screen.y);
    }

    /** The LRM 20's detail (design 14 U4): the bin it loads and its field of fire, with the View menu's switch. */
    private static void weaponDetail(GpuHudTestStage harness, GpuHud hud, GpuBoardSource.Frame frame, String name) {
        hud.state.expandedWeapon = "LRM 20";
        hud.state.expandedOrdinal = 0;
        capture(harness, hud, GpuUnitPanelTest.with(frame, frame.panels().record(), null,
              GpuUnitPanelTest.fieldOfFire(false, new AtomicInteger())), ATLAS, SheetTab.WEAPONS,
              name + "-weapons-detail");
        hud.state.expandedWeapon = "";
    }

    /**
     * The unit panel's actions (design 14 U4): a sensor change pending with its Cancel, and the popover of a slot with
     * two bins, each with its own Dump.
     */
    private static void actions(GpuHudTestStage harness, GpuHud hud, GpuUnitPanelFixture fixture,
          GpuBoardSource.Frame frame, String name) throws Exception {
        Entity atlas = fixture.entity(ATLAS);
        fixture.edit(() -> atlas.setNextSensor(atlas.getSensors().get(1)));
        try {
            capture(harness, hud, firing(fixture.frame(ATLAS)), ATLAS, SheetTab.STATUS, name + "-status-pending");
        } finally {
            fixture.edit(() -> atlas.setNextSensor(atlas.getSensors().get(0)));
        }
        GpuUnitRecord.Snapshot dual = GpuUnitPanelTest.withDualSlot(frame.panels().record());
        GpuBoardSource.Frame dualFrame = GpuUnitPanelTest.with(frame, dual, null, List.of());
        show(hud, dualFrame, ATLAS, SheetTab.SYSTEMS);
        int index = dual.locations().stream().filter(location -> location.abbr().equals("RA")).findFirst()
              .orElseThrow().slots().stream().filter(slot -> slot.eqNum2() >= 0).findFirst().orElseThrow().index();
        Actor slot = hud.stage.getRoot().findActor("crit-RA-" + index);
        Vector2 point = hud.stage.stageToScreenCoordinates(slot.localToStageCoordinates(new Vector2(20, 7)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        capture(harness, hud, dualFrame, ATLAS, SheetTab.SYSTEMS, name + "-popover-dual");
        hud.keyDown(Input.Keys.ESCAPE, java.awt.event.KeyEvent.VK_ESCAPE, 0);
    }

    /** The frame with the View menu's sensor range switch, on, as a running game's menu has it. */
    private static GpuBoardSource.Frame withSensorRanges(GpuBoardSource.Frame frame) {
        BoardScene.Command ranges = new BoardScene.Command("view/" + ClientGUI.VIEW_TOGGLE_SENSOR_RANGE
              + ":Sensor ranges", "Sensor ranges", "", true, false, false, List.of(), () -> { }, "C", true);
        return new GpuBoardSource.Frame(frame.scene(), frame.timeline(), frame.context(), List.of(ranges),
              frame.tooltip(), frame.centerRequest(), frame.boardGeneration(), frame.actorName(),
              frame.scenarioAtmosphere(), frame.reports(), frame.status(), frame.panels());
    }

    /** The unit on the card, the sheet open on {@code tab} (null: closed), drawn and written to {name}.png. */
    private static void capture(GpuHudTestStage harness, GpuHud hud, GpuBoardSource.Frame frame, int unit,
          SheetTab tab, String name) {
        show(hud, frame, unit, tab);
        UiTestStage.assertTexts(hud.stage.getRoot());
        harness.capture(name).dispose();
    }

    /** The unit on the card, the sheet open on {@code tab} (null: closed), laid out and drawn. */
    private static void show(GpuHud hud, GpuBoardSource.Frame frame, int unit, SheetTab tab) {
        hud.state.inspected = unit;
        hud.state.recordOpen = tab != null;
        if (tab != null) {
            hud.state.sheetTab = tab;
        }
        for (int pass = 0; pass < 3; pass++) {
            hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
            ScreenUtils.clear(.1f, .13f, .13f, 1, true);
            hud.draw();
        }
    }

    /** The fixture's frame in the firing phase of another player's turn: no movement ribbon, nobody acting. */
    private static GpuBoardSource.Frame firing(GpuBoardSource.Frame captured) {
        GpuBattleStatus.Snapshot status = captured.status();
        return GpuHudFixtures.frame(new GpuBattleStatus.Snapshot(status.round(), GamePhase.FIRING, false,
              status.localPlayerId(), Entity.NONE, List.of(), -1, status.units(), List.of(), false), captured.panels());
    }
}
