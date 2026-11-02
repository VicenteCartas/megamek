/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.CONTACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFirePreview.Contact;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFirePreview.Line;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFirePreview.Side;
import megamek.client.ui.clientGUI.unitDisplay.HeatEffects;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.FirePreview;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The contacts and fire preview panel (G5) in the component harness: the preview beside the hud-v3 shots 02, 03, 04,
 * 14 and 16 and the plain contacts beside 01 and 09, its layout at the three window sizes, and what its rows and
 * guide toggles do. The preview records are display values read off the mock's pictures, as E5 would publish them.
 */
@Tag("on-demand")
class GpuContactsPanelSmokeTest {
    private static final GpuBattleStatus.Snapshot MOCK = GpuHudFixtures.status();
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    private static final int LOCUST = 10;
    /** The right column at 1920 x 1080: below the minimap (y 90-300 in shot 02) and 70 above the bottom. */
    private static final int LEFT = 1590;
    private static final int TOP = 312;
    private static final int BOTTOM = 1080 - 70;
    private static final int WIDTH = 310;
    private static final String OUT_OF_ARC = Messages.getString("WeaponAttackAction.OutOfArc");

    /** A panel under test with the HUD state it reads and the menu it opens. */
    private static final class Panel {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuContextMenu menu = mock(GpuContextMenu.class);
        final GpuContactsPanel contacts;
        final Table root;

        Panel(GpuHudTestStage hud) {
            // The HUD's selection rule inspects an enemy (GpuHud.select): a row's click hands it the row's unit.
            contacts = new GpuContactsPanel(hud.kit, mock(GpuBoardSource.class), state, menu,
                  id -> state.inspected = id);
            root = (Table) contacts.actor();
            hud.window.addActor(root);
        }

        /** One frame of the HUD: the status through the state's focus rule, then the panel. */
        void update(GpuHudTestStage hud, GpuBattleStatus.Snapshot status, GpuFirePreview.Snapshot preview) {
            state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
            status.units().stream().map(UnitStatus::icon).forEach(hud.sprites::add);
            contacts.update(new GpuHud.Inputs(frame(status, preview), GpuHud.HudView.EMPTY, null,
                  GpuHudInputTest.preferences(), GpuHud.Metrics.of(hud.width(), hud.height()), List.of()));
        }

        /**
         * Lays the panel out as its slot does: the given width, its content height up to {@code bottom}, y down. The
         * second pass settles the wrapped lines, whose height follows the width of the first.
         */
        void place(GpuHudTestStage hud, float x, float top, float width, float bottom) {
            for (int pass = 0; pass < 2; pass++) {
                root.setWidth(width);
                root.validate();
                float height = Math.min(root.getPrefHeight(), bottom - top);
                root.setBounds(x, hud.height() - top - height, width, height);
                root.validate();
            }
        }

        <T extends Actor> T find(String name) {
            return root.findActor(name);
        }

        /** The shown texts under the named actor, in drawing order. */
        List<String> texts(String name) {
            Actor actor = find(name);
            assertNotNull(actor, name);
            List<String> texts = new ArrayList<>();
            collect(actor, texts);
            return texts;
        }
    }

    @Test
    void previewBesideTheMock() {
        GpuHudTestStage.run(hud -> {
            // Shot 02: the Atlas plans a walk; the Timber Wolf row is open.
            Panel panel = fresh(hud);
            panel.update(hud, MOCK, shot02());
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            hud.draw();
            click(hud, panel, "contacts-preview-" + TIMBER_WOLF, Input.Buttons.LEFT);
            panel.update(hud, MOCK, shot02());
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            assertEquals(TIMBER_WOLF, panel.state.inspected, "The row's click inspects the enemy, which opens it");
            assertEquals(List.of("CONTACTS", "OUTGOING", "INCOMING"), panel.texts("contacts-header"));
            assertEquals(List.of("FROM DESTINATION", "3 targets \u00B7 ", "3 threats",
                  "Hex 1512 \u00B7 facing N \u00B7 Walked +1 \u00B7 TMM +0"), panel.texts("contacts-from"));
            assertEquals(List.of("Prime", "TIMBER WOLF", "5/7 weapons \u00B7 torso forward",
                  "\u25C2 4 weapons can return fire \u00B7 best 3+", "4+", "92%"),
                  panel.texts("contacts-preview-" + TIMBER_WOLF));
            List<String> detail = panel.texts("contacts-detail-" + TIMBER_WOLF);
            assertEquals(List.of("TORSO FORWARD \u00B7 7 HEXES", "ROLL / HIT", "AC/20", "RT", "8+", "42%"),
                  detail.subList(0, 6));
            assertTrue(detail.containsAll(List.of("INCOMING \u00B7 TIMBER WOLF TORSO FORWARD", "ER Medium Laser",
                  "5+", "83%", OUT_OF_ARC)), detail.toString());
            assertFalse(detail.contains("Machine Gun"), "Only the enemy weapons that can fire back are listed");
            assertEquals(List.of("KGC-000", "KING CRAB", "5/7 weapons \u00B7 torso forward",
                  "\u25C2 4 weapons can return fire \u00B7 best 6+", "6+", "72%"),
                  panel.texts("contacts-preview-" + KING_CRAB));
            assertTrue(panel.<ScrollPane>find("contacts-list").isScrollY(), "The open row fills the column");
            shoot(hud, panel, "contacts-02-preview", "02-movement-fire-preview.jpg");

            // Shot 03: the Panther runs to a waypoint; every target is in its left twist; a Locust out of reach.
            panel = fresh(hud);
            panel.update(hud, status(GamePhase.MOVEMENT, true, 4, 0, MOCK.units()), shot03());
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            assertEquals("Hex 2012 \u00B7 facing NE \u00B7 Ran +2 \u00B7 TMM +1", panel.texts("contacts-from").get(3));
            assertEquals("1/2 weapons \u00B7 twist left", panel.texts("contacts-preview-" + BATTLEMASTER).get(2));
            assertEquals(List.of("LCT-1M", "LOCUST", "No available shot \u00B7 7 hexes", "\u2014"),
                  panel.texts("contacts-preview-" + LOCUST));
            assertEquals(.7f, panel.<Actor>find("contacts-preview-" + LOCUST).getColor().a, 1e-4);
            assertEquals(List.of("?", "Unidentified", "SENSOR CONTACT", "Sensor return \u00B7 13 hexes", "\u2014"),
                  panel.texts("contacts-preview-" + CONTACT));
            shoot(hud, panel, "contacts-03-waypoint", "03-waypoint-route.jpg");

            // Shot 04: the opponent moves; the Warhammer, up next, is previewed where it stands.
            panel = fresh(hud);
            panel.update(hud, status(GamePhase.MOVEMENT, false, Entity.NONE, 1, archerIdentified(MOCK.units())),
                  shot04());
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            assertEquals(List.of("FROM CURRENT HEX", "3 targets \u00B7 ", "3 threats",
                  "Hex 1913 \u00B7 facing N \u00B7 not moved yet"), panel.texts("contacts-from"));
            assertEquals("ARCHER", panel.texts("contacts-preview-" + CONTACT).get(1));
            shoot(hud, panel, "contacts-04-opponent", "04-opponent-turn.jpg");

            // Shot 16: the Atlas where it stands, before its turn (the mock's chat covers the lower rows).
            panel = fresh(hud);
            panel.update(hud, MOCK, shot16());
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            assertEquals("Hex 1514 \u00B7 facing N \u00B7 not moved yet", panel.texts("contacts-from").get(3));
            shoot(hud, panel, "contacts-16-chat", "16-chat.jpg");

            // Shot 14: 56 targets and 56 threats.
            panel = fresh(hud);
            GpuBattleStatus.Snapshot large = large();
            panel.update(hud, large, shot14());
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            assertEquals(List.of("FROM DESTINATION", "56 targets \u00B7 ", "56 threats",
                  "Hex 1711 \u00B7 facing N \u00B7 Ran +2 \u00B7 TMM +1"), panel.texts("contacts-from"));
            shoot(hud, panel, "contacts-14-large", "14-large-battle-100v100.jpg");
        });
    }

    @Test
    void contactsBesideTheMock() {
        GpuHudTestStage.run(hud -> {
            // Shot 01: initiative; nobody acts, so no distances; the Archer is a sensor contact.
            Panel panel = fresh(hud);
            panel.update(hud, status(GamePhase.INITIATIVE, false, Entity.NONE, -1,
                  change(MOCK.units(), unit -> unit.side() == OWN ? copy(unit, unit.position(), false, List.of())
                        : unit)), GpuFirePreview.Snapshot.NONE);
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            assertEquals(List.of("CONTACTS", "5"), panel.texts("contacts-header"));
            assertEquals(List.of("Prime", "TIMBER WOLF", "Visual"), panel.texts("contacts-unit-" + TIMBER_WOLF));
            assertEquals(List.of("?", "Unidentified", "SENSOR CONTACT", "Unidentified \u00B7 sensor return"),
                  panel.texts("contacts-unit-" + CONTACT));
            assertEquals(List.of("Includes unidentified sensor contacts"), panel.texts("contacts-footer"));
            assertNull(panel.find("contacts-from"), "No preview, no from block");
            shoot(hud, panel, "contacts-01-initiative", "01-initiative.jpg");

            // Shot 09: the Atlas's physical attack; distances from it, the King Crab prone, every contact identified.
            panel = fresh(hud);
            panel.update(hud, status(GamePhase.PHYSICAL, true, ATLAS, 0, physical()), GpuFirePreview.Snapshot.NONE);
            panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
            assertEquals(List.of("KGC-000", "KING CRAB", "Prone", "7 hex"), panel.texts("contacts-unit-" + KING_CRAB));
            assertEquals(UiTheme.CORAL, panel.<GpuHudKit.UnitRow>find("contacts-unit-" + KING_CRAB).line
                  .getColor());
            assertEquals("1 hex", panel.texts("contacts-unit-" + TIMBER_WOLF).get(3));
            assertEquals(List.of("All contacts identified"), panel.texts("contacts-footer"));
            shoot(hud, panel, "contacts-09-physical", "09-physical-attacks.jpg");
        });
    }

    @Test
    void layoutStaysInItsSlotAtTheThreeWindowSizes() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            GpuBattleStatus.Snapshot large = large();
            GpuFirePreview.Snapshot preview = shot14();
            for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                hud.size(size[0], size[1]);
                GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                // The prototype's minimap: header 36, canvas 150 (120 at H <= 800), padding 12 and rails; 90-300.
                float map = metrics.lowHeight() ? 180 : 210;
                float left = size[0] - metrics.gap() - metrics.right();
                // y up, as the stage: the utility row, the minimap and the chat button around the column.
                Rectangle utilities = new Rectangle(size[0] - metrics.gap() - 420, size[1] - 18 - 56, 420, 56);
                Rectangle minimap = new Rectangle(left, size[1] - 90 - map, metrics.right(), map);
                Rectangle chat = new Rectangle(size[0] - metrics.gap() - 80, 18, 80, 36);
                for (GpuFirePreview.Snapshot shown : List.of(preview, GpuFirePreview.Snapshot.NONE)) {
                    panel.update(hud, large, shown);
                    panel.place(hud, left, 90 + map + 12, metrics.right(), size[1] - 70);
                    if (shown.active() && panel.state.inspected != 300) {
                        // An open row: the detail's lines must stay inside the narrow column too.
                        click(hud, panel, "contacts-preview-300", Input.Buttons.LEFT);
                        panel.update(hud, large, shown);
                        panel.place(hud, left, 90 + map + 12, metrics.right(), size[1] - 70);
                    }
                    hud.draw();
                    hud.capture("contacts-layout-" + (shown.active() ? "preview-" : "list-") + size[0] + "x"
                          + size[1]).dispose();
                    hud.assertLayout(panel.root, utilities, minimap, chat);
                    assertTrue(panel.<ScrollPane>find("contacts-list").isScrollY(), "The list scrolls at " + size[0]);
                }
            }
        });
    }

    @Test
    void rowsTogglesAndMenusFollowTheSnapshots() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            GpuFirePreview.Snapshot preview = shot02();
            show(hud, panel, MOCK, preview);

            // The open row is the inspected enemy: a row's click inspects
            // its enemy, which opens its detail and gives it the coral edges; another row's click moves both; the open
            // row's click ends the inspection, which closes it; only one row is open.
            click(hud, panel, "contacts-preview-" + KING_CRAB, Input.Buttons.LEFT);
            show(hud, panel, MOCK, preview);
            assertEquals(KING_CRAB, panel.state.inspected);
            assertNotNull(panel.find("contacts-detail-" + KING_CRAB));
            assertSame(hud.kit.ui.skin.getDrawable("row-foe"),
                  panel.<GpuHudKit.UnitRow>find("contacts-preview-" + KING_CRAB).getBackground());
            click(hud, panel, "contacts-preview-" + TIMBER_WOLF, Input.Buttons.LEFT);
            show(hud, panel, MOCK, preview);
            assertEquals(TIMBER_WOLF, panel.state.inspected);
            assertNull(panel.find("contacts-detail-" + KING_CRAB));
            assertNotNull(panel.find("contacts-detail-" + TIMBER_WOLF));
            assertNotSame(hud.kit.ui.skin.getDrawable("row-foe"),
                  panel.<GpuHudKit.UnitRow>find("contacts-preview-" + KING_CRAB).getBackground(), "one highlight");
            click(hud, panel, "contacts-preview-" + TIMBER_WOLF, Input.Buttons.LEFT);
            show(hud, panel, MOCK, preview);
            assertEquals(Entity.NONE, panel.state.inspected);
            assertNull(panel.find("contacts-detail-" + TIMBER_WOLF));
            // An enemy inspected elsewhere, on the board or in the forces list, opens its row too.
            panel.state.inspected = KING_CRAB;
            show(hud, panel, MOCK, preview);
            assertNotNull(panel.find("contacts-detail-" + KING_CRAB));
            panel.state.inspected = Entity.NONE;
            // A sensor contact's row has no detail and no menu; a right click on a unit's row opens its menu (C13).
            click(hud, panel, "contacts-preview-" + CONTACT, Input.Buttons.LEFT);
            click(hud, panel, "contacts-preview-" + CONTACT, Input.Buttons.RIGHT);
            assertEquals(Entity.NONE, panel.state.inspected);
            verify(panel.menu, never()).open(any(), eq(CONTACT), anyFloat(), anyFloat());
            click(hud, panel, "contacts-preview-" + KING_CRAB, Input.Buttons.RIGHT);
            verify(panel.menu).open(eq(unit(MOCK, KING_CRAB).position()), eq(KING_CRAB), anyFloat(), anyFloat());
            assertEquals(Entity.NONE, panel.state.inspected, "A right click opens nothing else");

            // The guide toggles start pressed and flip the board's guides (F3).
            assertTrue(panel.contacts.outgoingGuides() && panel.contacts.incomingGuides());
            click(hud, panel, "contacts-outgoing", Input.Buttons.LEFT);
            assertFalse(panel.contacts.outgoingGuides());
            assertFalse(panel.<Button>find("contacts-outgoing").isChecked());
            click(hud, panel, "contacts-incoming", Input.Buttons.LEFT);
            assertFalse(panel.contacts.incomingGuides());
            hud.draw();
            hud.capture("contacts-toggles-off").dispose();
            click(hud, panel, "contacts-outgoing", Input.Buttons.LEFT);
            assertTrue(panel.contacts.outgoingGuides());
            assertTrue(panel.<Button>find("contacts-outgoing").isChecked());

            // While the preview is recomputed its rows stay, dimmed, and the counts give way to "Updating..." (F4).
            show(hud, panel, MOCK, preview.updating());
            assertEquals(List.of("FROM DESTINATION", "Updating\u2026",
                  "Hex 1512 \u00B7 facing N \u00B7 Walked +1 \u00B7 TMM +0"), panel.texts("contacts-from"));
            assertEquals(.5f, panel.<Actor>find("contacts-preview-" + TIMBER_WOLF).getColor().a, 1e-4);
            hud.capture("contacts-updating").dispose();
            // A unit the rules cannot preview says why; water that breaches is flagged (F4, F11).
            String illegal = FirePreview.Reason.ILLEGAL_PATH.description();
            show(hud, panel, MOCK, with(preview, illegal, false));
            assertEquals("Not previewed: " + illegal, panel.texts("contacts-from").get(3));
            show(hud, panel, MOCK, with(preview, "", true));
            assertEquals("Hex 1512 \u00B7 facing N \u00B7 Walked +1 \u00B7 TMM +0 \u00B7 breach rolls not predicted",
                  panel.texts("contacts-from").get(3));

            // A turret names its facing; a roll above 12 shows its number, dim, instead of its modifier list; no return
            // fire says so (F5, F6).
            GpuFirePreview.Snapshot turret = preview(false, new Coords(14, 11), 0, "None", 0, 0, ATLAS, 1, 0,
                  List.of(contact(KING_CRAB, 4, new Side("", 1, true, 1, 2, 7, 58.33,
                  List.of(line("AC/10", "T", 7, 58.33), new Line("LRM 20", "T", 13, 0,
                        "4 (gunnery skill) + 4 (long range) + 5 (target moved 10-17 hexes)"))), Side.NONE),
                  contact(TIMBER_WOLF, 5, side(-1, 1, 2, 6, 72.22, line("Medium Laser", "RA", 6, 72.22),
                  blocked("Medium Laser", "LA")), Side.NONE)));
            show(hud, panel, MOCK, turret);
            assertEquals("1/2 weapons \u00B7 turret NE", panel.texts("contacts-preview-" + KING_CRAB).get(2));
            click(hud, panel, "contacts-preview-" + KING_CRAB, Input.Buttons.LEFT);
            show(hud, panel, MOCK, turret);
            assertEquals(List.of("TURRET NE \u00B7 4 HEXES", "ROLL / HIT", "AC/10", "T", "7+", "58%", "LRM 20", "T",
                  "13+", "0%", "No return fire at this hex"), panel.texts("contacts-detail-" + KING_CRAB));
            hud.capture("contacts-turret-twist").dispose();
            // A twist gives its angle in the detail.
            click(hud, panel, "contacts-preview-" + TIMBER_WOLF, Input.Buttons.LEFT);
            show(hud, panel, MOCK, turret);
            List<String> detail = panel.texts("contacts-detail-" + TIMBER_WOLF);
            assertEquals(UiTheme.upper(Messages.getString("GpuBoard.hud.contacts.detailHead",
                  Messages.getString("GpuBoard.hud.contacts.twistDegrees", "twist left", 60), 5)), detail.get(0));
            assertEquals("No return fire at this hex", detail.get(detail.size() - 1));
            hud.capture("contacts-twist").dispose();

            // Without a preview the column lists the contacts: a click inspects, a right click opens the menu.
            show(hud, panel, MOCK, GpuFirePreview.Snapshot.NONE);
            assertNull(panel.find("contacts-from"));
            assertEquals("9 hex", panel.texts("contacts-unit-" + TIMBER_WOLF).get(3), "Distance from the acting Atlas");
            click(hud, panel, "contacts-unit-" + BATTLEMASTER, Input.Buttons.LEFT);
            assertEquals(BATTLEMASTER, panel.state.inspected);
            show(hud, panel, MOCK, GpuFirePreview.Snapshot.NONE);
            assertSame(hud.kit.ui.skin.getDrawable("row-foe"),
                  panel.<GpuHudKit.UnitRow>find("contacts-unit-" + BATTLEMASTER).getBackground(),
                  "The inspected enemy has coral edges");
            click(hud, panel, "contacts-unit-" + CONTACT, Input.Buttons.RIGHT);
            verify(panel.menu).open(eq(unit(MOCK, CONTACT).position()), eq(CONTACT), anyFloat(), anyFloat());
            // Heat with effects (the status carries the heat table's text, "" without effects) shows the amber flame
            // instead of the distance (C5).
            String effects = HeatEffects.getHeatEffects(5, false, false);
            List<UnitStatus> hot = change(MOCK.units(), unit -> unit.id() == TIMBER_WOLF
                  ? copy(unit, unit.position(), unit.pending(), unit.statusWords(), effects) : unit);
            show(hud, panel, status(GamePhase.MOVEMENT, true, ATLAS, 0, hot), GpuFirePreview.Snapshot.NONE);
            assertEquals(UiTheme.AMBER, panel.<GpuHudKit.UnitRow>find("contacts-unit-" + TIMBER_WOLF).right
                  .getActor().getColor());
        });
    }

    /** A new panel in the emptied window: each shot starts from a fresh HUD state. */
    private static Panel fresh(GpuHudTestStage hud) {
        hud.window.clearChildren();
        return new Panel(hud);
    }

    /** Draws the panel, captures {name}.png and writes it beside the mock's same area. */
    private static void shoot(GpuHudTestStage hud, Panel panel, String name, String mock) {
        hud.draw();
        hud.assertLayout(panel.root);
        Pixmap image = hud.capture(name);
        try {
            hud.compare(name, image, panel.root, mock, LEFT, TOP);
        } finally {
            image.dispose();
        }
    }

    /** One frame at the right column's place at 1920 x 1080, drawn so that clicks find laid-out actors. */
    private static void show(GpuHudTestStage hud, Panel panel, GpuBattleStatus.Snapshot status,
          GpuFirePreview.Snapshot preview) {
        panel.update(hud, status, preview);
        panel.place(hud, LEFT, TOP, WIDTH, BOTTOM);
        hud.draw();
    }

    /** A press and release at the named actor's centre through the stage, as the board view hands HUD input on. */
    private static void click(GpuHudTestStage hud, Panel panel, String name, int button) {
        Actor actor = panel.find(name);
        assertNotNull(actor, name);
        Vector2 point = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, button);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, button);
    }

    /** The texts of the visible, non-empty labels under {@code actor}, button captions included. */
    private static void collect(Actor actor, List<String> texts) {
        if (!actor.isVisible()) {
            return;
        }
        if (actor instanceof Label label && label.getText().length() > 0) {
            texts.add(label.getText().toString());
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, texts));
        }
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuFirePreview.Snapshot preview) {
        GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY, preview,
              GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
        return new GpuBoardSource.Frame(null, List.of(), null, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, panels);
    }

    private static GpuBattleStatus.Snapshot status(GamePhase phase, boolean myTurn, int actor, int turnIndex,
          List<UnitStatus> units) {
        return new GpuBattleStatus.Snapshot(MOCK.round(), phase, myTurn, MOCK.localPlayerId(), actor, MOCK.turns(),
              turnIndex, units, List.of(), false);
    }

    /** Shot 02: from the Atlas's walk two hexes north; the Timber Wolf's weapons and return fire in detail. */
    static GpuFirePreview.Snapshot shot02() {
        Side timberWolf = side(0, 5, 7, 4, 91.67, line("AC/20", "RT", 8, 41.67), line("LRM 20", "LT", 4, 91.67),
              line("SRM 6", "LT", 8, 41.67), line("Medium Laser", "LA", 8, 41.67), line("Medium Laser", "RA", 8, 41.67),
              blocked("Medium Laser", "CT(R)"), blocked("Medium Laser", "CT(R)"));
        Side returnFire = side(0, 4, 5, 3, 97.22, line("ER Large Laser", "LA", 3, 97.22),
              line("ER Large Laser", "RA", 3, 97.22), line("LRM 20", "LT", 3, 97.22),
              line("ER Medium Laser", "CT", 5, 83.33), blocked("Machine Gun", "RA"));
        return preview(true, new Coords(14, 11), 0, "Walked", 1, 0, ATLAS, 3, 3, List.of(
              contact(TIMBER_WOLF, 7, timberWolf, returnFire),
              contact(KING_CRAB, 9, side(0, 5, 7, 6, 72.22, line("AC/20", "RT", 6, 72.22),
                    line("LRM 20", "LT", 6, 72.22), line("SRM 6", "LT", 9, 27.78), line("Medium Laser", "LA", 8, 41.67),
                    line("Medium Laser", "RA", 8, 41.67), blocked("Medium Laser", "CT(R)"),
                    blocked("Medium Laser", "CT(R)")), side(0, 4, 6, 6, 72.22)),
              contact(BATTLEMASTER, 10, side(0, 1, 7, 6, 72.22), side(0, 1, 6, 6, 72.22)),
              contact(LOCUST, 11, side(0, 0, 7, TargetRoll.IMPOSSIBLE, 0), Side.NONE), sensor(CONTACT, 13)));
    }

    /** Shot 03: from the Panther's run past a waypoint, every target in its left twist. */
    static GpuFirePreview.Snapshot shot03() {
        return preview(true, new Coords(19, 11), 1, "Ran", 2, 1, 4, 3, 3, List.of(
              contact(TIMBER_WOLF, 6, side(-1, 1, 2, 8, 41.67), side(0, 4, 7, 7, 58.33)),
              contact(KING_CRAB, 8, side(-1, 1, 2, 8, 41.67), side(0, 1, 7, 8, 41.67)),
              contact(BATTLEMASTER, 9, side(-1, 1, 2, 10, 16.67), side(0, 1, 6, 10, 16.67)),
              contact(LOCUST, 7, side(0, 0, 2, TargetRoll.IMPOSSIBLE, 0), Side.NONE), sensor(CONTACT, 13)));
    }

    /** Shot 04: the Warhammer where it stands, during the opponent's movement turn. */
    static GpuFirePreview.Snapshot shot04() {
        return preview(false, new Coords(18, 12), 0, "None", 0, 0, 2, 3, 3, List.of(
              contact(TIMBER_WOLF, 9, side(0, 2, 5, 6, 72.22), side(0, 4, 7, 5, 83.33)),
              contact(KING_CRAB, 10, side(0, 2, 5, 6, 72.22), side(0, 1, 7, 6, 72.22)),
              contact(CONTACT, 10, side(0, 2, 5, 8, 41.67), side(0, 2, 5, 6, 72.22)),
              contact(LOCUST, 8, side(0, 0, 5, TargetRoll.IMPOSSIBLE, 0), Side.NONE),
              contact(BATTLEMASTER, 10, side(0, 0, 5, TargetRoll.IMPOSSIBLE, 0), Side.NONE)));
    }

    /** Shot 16: the Atlas where it stands, before its movement turn. */
    private static GpuFirePreview.Snapshot shot16() {
        return preview(false, new Coords(14, 13), 0, "None", 0, 0, ATLAS, 3, 3, List.of(
              contact(TIMBER_WOLF, 9, side(0, 5, 7, 5, 83.33), side(0, 4, 7, 5, 83.33)),
              contact(KING_CRAB, 10, side(0, 1, 7, 5, 83.33), side(0, 1, 7, 6, 72.22)),
              contact(BATTLEMASTER, 11, side(0, 1, 7, 6, 72.22), side(0, 1, 6, 9, 27.78)),
              contact(LOCUST, 11, side(0, 0, 7, TargetRoll.IMPOSSIBLE, 0), Side.NONE), sensor(CONTACT, 13)));
    }

    /** Shot 14: 56 enemies in range of the Atlas's run, in the mock's first rows' pattern. */
    private static GpuFirePreview.Snapshot shot14() {
        List<Contact> rows = new ArrayList<>();
        for (int index = 0; index < 56; index++) {
            int best = index < 6 ? 5 : 6;
            double odds = index < 6 ? 83.33 : 72.22;
            rows.add(new Contact(300 + index, false, 6 + index / 8, side(0, 5, 7, best, odds,
                  line("AC/20", "RT", best, odds), line("LRM 20", "LT", best, odds), line("SRM 6", "LT", best, odds),
                  line("Medium Laser", "LA", 8, 41.67), line("Medium Laser", "RA", 8, 41.67),
                  blocked("Medium Laser", "CT(R)"), blocked("Medium Laser", "CT(R)")),
                  side(0, 4 - index % 2, 7, 5 + index % 3, 83.33)));
        }
        return new GpuFirePreview.Snapshot(true, true, ATLAS, true, new Coords(16, 10), 0, 0, "Ran", 2, 1, "", false,
              56, 56, rows);
    }

    private static GpuFirePreview.Snapshot preview(boolean destination, Coords from, int facing, String moved,
          int modifier, int tmm, int unit, int targets, int threats, List<Contact> contacts) {
        return new GpuFirePreview.Snapshot(true, true, unit, destination, from, 0, facing, moved, modifier, tmm, "",
              false, targets, threats, contacts);
    }

    /** The same preview, unavailable for {@code reason} or with a breach warning. */
    private static GpuFirePreview.Snapshot with(GpuFirePreview.Snapshot preview, String reason, boolean breach) {
        return new GpuFirePreview.Snapshot(true, true, preview.unitId(), preview.fromDestination(), preview.from(),
              preview.boardId(), preview.facing(), preview.moved(), preview.attackerModifier(), preview.tmm(), reason,
              breach, preview.targets(), preview.threats(), preview.contacts());
    }

    private static Contact contact(int id, int distance, Side outgoing, Side incoming) {
        return new Contact(id, false, distance, outgoing, incoming);
    }

    private static Contact sensor(int id, int distance) {
        return new Contact(id, true, distance, Side.NONE, Side.NONE);
    }

    private static Side side(int twist, int available, int total, int best, double odds, Line... lines) {
        return new Side("", twist, false, available, total, best, odds, List.of(lines));
    }

    private static Line line(String weapon, String location, int value, double odds) {
        return new Line(weapon, location, value, odds, "");
    }

    private static Line blocked(String weapon, String location) {
        return new Line(weapon, location, TargetRoll.IMPOSSIBLE, 0, OUT_OF_ARC);
    }

    private static UnitStatus unit(GpuBattleStatus.Snapshot status, int id) {
        return status.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    private static List<UnitStatus> change(List<UnitStatus> units, UnaryOperator<UnitStatus> change) {
        return units.stream().map(change).toList();
    }

    /** The mock's units with the Archer seen (shots 04 and 09 name it). */
    private static List<UnitStatus> archerIdentified(List<UnitStatus> units) {
        return change(units, unit -> unit.id() == CONTACT ? archer(unit.position()) : unit);
    }

    /**
     * Shot 09: the Atlas acts; the enemies at 1, 7, 2, 9 and 9 hexes from it, the King Crab prone, the Archer
     * identified.
     */
    private static List<UnitStatus> physical() {
        Coords atlas = unit(MOCK, ATLAS).position();
        Coords north1 = new Coords(atlas.getX(), atlas.getY() - 1);
        Coords north2 = new Coords(atlas.getX(), atlas.getY() - 2);
        Coords north7 = new Coords(atlas.getX(), atlas.getY() - 7);
        Coords north9 = new Coords(atlas.getX(), atlas.getY() - 9);
        List<UnitStatusWords.StatusWord> prone = List.of(new UnitStatusWords.StatusWord("PRONE", "Prone",
              UnitStatusWords.Severity.WARNING));
        return change(archerIdentified(MOCK.units()), unit -> switch (unit.id()) {
            case TIMBER_WOLF -> copy(unit, north1, false, List.of());
            case KING_CRAB -> copy(unit, north7, false, prone);
            case BATTLEMASTER -> copy(unit, north2, false, List.of());
            case CONTACT, LOCUST -> copy(unit, north9, false, List.of());
            default -> unit;
        });
    }

    /**
     * 100 v 100 for shot 14: the mock's own units and 56 enemies, the Timber Wolf, Locust and BattleMaster in the
     * order of the mock's first rows.
     */
    private static GpuBattleStatus.Snapshot large() {
        List<UnitStatus> units = new ArrayList<>(MOCK.units().stream().filter(unit -> unit.side() == OWN).toList());
        int[] pattern = { TIMBER_WOLF, LOCUST, LOCUST, TIMBER_WOLF, TIMBER_WOLF, BATTLEMASTER, BATTLEMASTER };
        for (int index = 0; index < 56; index++) {
            units.add(copy(unit(MOCK, pattern[index % pattern.length]), 300 + index));
        }
        return status(GamePhase.MOVEMENT, true, ATLAS, 0, units);
    }

    /** The Archer as the local side sees it once identified (shots 04 and 09: "ARC-2K"). */
    private static UnitStatus archer(Coords position) {
        return new UnitStatus(CONTACT, ENEMY, false, "Archer ARC-2K", "Archer", "ARC-2K", 70, "Heavy",
              "Opposition lance 1", "MW S. Iga", 4, 5, 1, 1, 6, 0, "10", 4, "6", 0, "", 0, 0, 3, 0, false, true,
              false, false, Entity.DMG_NONE, List.of(), "", position, 0, GpuHudFixtures.sprite("Archer"), List.of(),
              EntityWeightClass.WEIGHT_HEAVY, 0, Player.PLAYER_NONE, List.of());
    }

    private static UnitStatus copy(UnitStatus unit, int id) {
        return new UnitStatus(id, unit.side(), unit.sensorContact(), unit.name(), unit.chassis(), unit.model(),
              unit.tons(), unit.weightClass(), unit.formation(), unit.pilot(), unit.gunnery(), unit.piloting(),
              unit.armor(), unit.structure(), unit.heat(), unit.heatRgb(), unit.heatCapacity(), unit.walk(), unit.run(),
              unit.jump(), unit.moved(), unit.mpUsed(), unit.hexesMoved(), unit.facing(), unit.tmm(),
              unit.canActNow(), unit.pending(), unit.done(), unit.destroyed(), unit.damageLevel(),
              unit.destroyedLocations(), unit.heatEffects(), unit.position(), unit.boardId(), unit.icon(),
              unit.statusWords(), unit.weightClassIndex(), unit.declaredAttacks(), unit.ownerId(), unit.statusTiles());
    }

    /** A copy at another hex, with or without a turn left in the phase and with other board-label words. */
    private static UnitStatus copy(UnitStatus unit, Coords position, boolean pending,
          List<UnitStatusWords.StatusWord> words) {
        return copy(unit, position, pending, words, unit.heatEffects());
    }

    /** The same with another heat-effects text. */
    private static UnitStatus copy(UnitStatus unit, Coords position, boolean pending,
          List<UnitStatusWords.StatusWord> words, String heatEffects) {
        return new UnitStatus(unit.id(), unit.side(), unit.sensorContact(), unit.name(), unit.chassis(), unit.model(),
              unit.tons(), unit.weightClass(), unit.formation(), unit.pilot(), unit.gunnery(), unit.piloting(),
              unit.armor(), unit.structure(), unit.heat(), unit.heatRgb(), unit.heatCapacity(), unit.walk(), unit.run(),
              unit.jump(), unit.moved(), unit.mpUsed(), unit.hexesMoved(), unit.facing(), unit.tmm(), false, pending,
              unit.done(), unit.destroyed(), unit.damageLevel(), unit.destroyedLocations(), heatEffects, position,
              unit.boardId(), unit.icon(), words, unit.weightClassIndex(), unit.declaredAttacks(), unit.ownerId(),
              unit.statusTiles());
    }
}
