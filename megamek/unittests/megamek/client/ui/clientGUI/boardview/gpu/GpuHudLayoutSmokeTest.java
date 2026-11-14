/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lays the HUD out with stand-ins of the prototype's measured panel sizes (r1 section 2) in place of the components,
 * at the 900 x 600 window minimum, 1280 x 720 and 1920 x 1080. Panels never overlap each other; side panels may cover
 * the full shortcut line. Slots stay inside the window, and maps are written over shots 02 and 15 at their sizes.
 */
@Tag("on-demand")
class GpuHudLayoutSmokeTest {
    private static final int[][] SIZES = { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } };
    /** Stand-ins of this height fill their slot, as a long list does. */
    private static final float FILL = 2000;
    /** The card unit of the layouts that show the unit sheet. */
    private static final int UNIT = 1;

    /** One screen of the prototype: the panels it shows and the HUD state that shows them. */
    private record Layout(String name, GamePhase phase, boolean grid, boolean overlays, List<String> panels) { }

    private static final List<String> BASE = List.of("phase-header", "forces-panel", "unit-card", "command-dock",
          "hint-line", "utility-bar", "minimap");
    private static final List<Layout> LAYOUTS = List.of(
          new Layout("movement", GamePhase.MOVEMENT, false, false, with("contacts-panel")),
          new Layout("firing", GamePhase.FIRING, false, false, with("weapons-panel", "solution-card")),
          new Layout("firing-extras", GamePhase.FIRING, true, false,
                with("weapons-panel", "solution-card", "conditions-card", "tactical-chip")),
          new Layout("initiative-extras", GamePhase.INITIATIVE, true, false,
                with("log-panel", "initiative-card", "conditions-card", "tactical-chip")),
          new Layout("overlays", GamePhase.MOVEMENT, false, true,
                List.of("record-sheet", "chat-panel", "help-dialog", "toast-stack")),
          new Layout("overview", GamePhase.MOVEMENT, false, true, List.of("force-overview")));
    /** Every slotted component actor, by name. */
    private static final List<String> SLOTTED = List.of("phase-header", "initiative-card", "conditions-card",
          "forces-panel", "unit-card", "record-sheet", "utility-bar", "tactical-chip", "hint-line",
          "minimap", "contacts-panel", "weapons-panel", "log-panel", "solution-card", "command-dock",
          "chat-panel", "force-overview", "help-dialog", "menu-panel", "players-panel",
          "toast-stack");
    /** The full-width shortcut line sits behind these side panels, which may cover it. */
    private static final Set<String> HINT_COVERING_PANELS = Set.of("phase-header", "forces-panel", "unit-card",
          "record-sheet", "utility-bar", "minimap", "contacts-panel", "weapons-panel", "solution-card", "log-panel",
          "chat-button");

    @Test
    void slotsNeverOverlapAndStayInTheWindowAtTheReferenceSizes() {
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            GpuBoardSource source = mock(GpuBoardSource.class);
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(), mock(GpuBoardTuning.class),
                  new GpuPlaybackHistory(new UnitPlayback()));
            try {
                float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                for (int[] size : SIZES) {
                    harness.size(size[0], size[1]);
                    // One stage unit per back-buffer pixel, drawn into the emulated window at the lower left.
                    hud.resize(Math.round(size[0] / density), Math.round(size[1] / density), 1 / density);
                    for (Layout layout : LAYOUTS) {
                        show(hud, harness, layout, size[0], size[1]);
                    }
                }
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    private static void show(GpuHud hud, GpuHudTestStage harness, Layout layout, int width, int height) {
        hud.state.forcesGrid = layout.grid();
        // The stand-ins take the open forces list's and conditions card's slots, not the rail's or the strip's.
        hud.state.forcesPinned = true;
        hud.state.conditionsExpanded = true;
        if (hud.state.logOpen() != layout.panels().contains("log-panel")) {
            hud.state.toggleLog();
        }
        hud.state.chatOpen = layout.panels().contains("chat-panel");
        hud.state.overview = layout.panels().contains("force-overview");
        hud.state.recordOpen = layout.panels().contains("record-sheet");
        // The unit sheet shows the card unit's record: an own unit, inspected.
        hud.state.inspected = hud.state.recordOpen ? UNIT : Entity.NONE;
        hud.state.dialog = layout.panels().contains("help-dialog") ? GpuHudState.Dialog.HELP : GpuHudState.Dialog.NONE;
        GpuHud.Metrics metrics = GpuHud.Metrics.of(width, height);
        for (String name : SLOTTED) {
            swap(hud, harness, name, layout.panels().contains(name), size(name, layout, metrics));
        }
        GpuBoardSource.Frame frame = frame(layout);
        hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());

        String name = "hud-slots-" + layout.name() + "-" + width + "x" + height;
        ScreenUtils.clear(.1f, .13f, .13f, 1, true);
        hud.draw();
        List<Actor> shown = shown(hud, layout);
        Map<String, Rectangle> slots = new LinkedHashMap<>();
        shown.forEach(actor -> slots.put(actor.getName(), GpuHudTestStage.bounds(actor)));
        if (!layout.overlays()) {
            Rectangle phase = slots.get("phase-header"), utilities = slots.get("utility-bar"), minimap = slots.get("minimap");
            assertEquals(metrics.gap(), phase.x, .5f);
            float phaseTop = utilities.x < metrics.left() + 2 * metrics.gap()
                  ? utilities.height + 2 * metrics.gap() : metrics.gap();
            assertEquals(phaseTop, height - phase.y - phase.height, .5f);
            assertEquals(metrics.gap(), height - utilities.y - utilities.height, .5f);
            assertEquals(metrics.gap(), utilities.y - minimap.y - minimap.height, .5f);
            Rectangle conditions = slots.get("conditions-card");
            if (conditions != null) {
                assertEquals(260, conditions.width, .5f, "Reference windows keep the conditions card usable");
                if (conditions.x + conditions.width + 12 > utilities.x) {
                    assertEquals(metrics.gap(), utilities.y - conditions.y - conditions.height, .5f,
                          "Conditions move below the toolbar when there is no room beside it");
                }
            }
            Rectangle dock = slots.get("command-dock"), hint = slots.get("hint-line");
            assertEquals(metrics.gap(), dock.y, .5f);
            if (hint != null) {
                assertEquals(12, hint.y - dock.y - dock.height, .5f, "shortcuts sit above the dock");
                assertEquals(dock.x + dock.width / 2, hint.x + hint.width / 2, .5f,
                      "The full shortcut line stays centred on the dock");
                Actor hintActor = hud.stage.getRoot().findActor("hint-line");
                Actor dockActor = hud.stage.getRoot().findActor("command-dock");
                var hintSlot = (Container<?>) hintActor.getParent();
                Actor hintLayer = hintSlot.getParent(), dockLayer = dockActor.getParent().getParent();
                assertFalse(hintSlot.getClip(), "Side panels cover the hint; its slot does not crop it");
                assertEquals(hintSlot.getPrefWidth(), hint.width, .5f, "Keep the complete shortcut line");
                assertSame(hintLayer.getParent(), dockLayer.getParent());
                assertTrue(hintLayer.getZIndex() < dockLayer.getZIndex(), "All panels draw above shortcuts");
                if (layout.grid()) {
                    assertTrue(shown.stream().filter(actor -> HINT_COVERING_PANELS.contains(actor.getName()))
                          .map(GpuHudTestStage::bounds).anyMatch(hint::overlaps),
                          "The long-line control must exercise actual panel coverage");
                }
            }
        }
        System.out.println(name + " (stage " + hud.stage.getWidth() + " x " + hud.stage.getHeight() + "): " + slots);
        for (Actor actor : shown) {
            if (layout.overlays()) {
                harness.assertLayout(actor);
            } else {
                harness.assertLayout(actor, shown.stream().filter(other -> other != actor)
                      .filter(other -> !hintUnderSidePanel(actor, other))
                      .map(GpuHudTestStage::bounds).toArray(Rectangle[]::new));
            }
        }
        // The panel bounds the components receive (for target cards and board labels) cover every shown panel.
        List<Rectangle> panels = hud.panelBounds();
        for (Actor actor : shown) {
            Rectangle area = GpuHudTestStage.bounds(actor);
            assertTrue(actor.getName().equals("toast-stack") || panels.stream().anyMatch(bound -> covers(bound, area)),
                  actor.getName() + " at " + area + " is outside the panel bounds " + panels);
        }
        harness.capture(name).dispose();
        String mock = width == 1920 && layout.name().equals("movement") ? "02-movement-fire-preview.jpg"
              : width == 1280 && layout.name().equals("firing") ? "15-compact-1280x720.jpg" : null;
        File file = mock == null ? null : new File(GpuHudTestStage.MOCK, mock);
        if (file != null && file.isFile()) {
            Texture shot = new Texture(new FileHandle(file));
            try {
                hud.stage.getViewport().apply();
                hud.stage.getBatch().setProjectionMatrix(hud.stage.getCamera().combined);
                hud.stage.getBatch().begin();
                hud.stage.getBatch().draw(shot, 0, 0, width, height);
                hud.stage.getBatch().end();
                hud.draw();
                harness.capture(name + "-over-" + mock.substring(0, 2)).dispose();
            } finally {
                shot.dispose();
            }
        }
        // The forces panel is as tall as its content (the user's rule of 2026-10-02): a long list ends 12 above the
        // unit card, a short one keeps its own height under the phase header.
        Rectangle forces = slots.get("forces-panel");
        Rectangle card = slots.get("unit-card");
        if (!layout.overlays() && forces != null && card != null) {
            assertEquals(12, forces.y - card.y - card.height, .5f, "A long forces list ends 12 above the card in " + name);
            swap(hud, harness, "forces-panel", true, new float[] { 300, 150 });
            hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
            hud.draw();
            Rectangle shortList = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("forces-panel"));
            assertEquals(150, shortList.height, .5f, "A short forces list keeps its height in " + name);
            assertEquals(forces.y + forces.height, shortList.y + shortList.height, .5f, "and its top in " + name);
        }
    }

    /** The prototype's size of a stand-in, width by height; fill-width slots ignore the width. */
    private static float[] size(String name, Layout layout, GpuHud.Metrics metrics) {
        boolean moving = layout.phase() == GamePhase.MOVEMENT;
        return switch (name) {
            // Header: 84 without the movement ribbon and 100 with it; 4 less with the narrow phase name (shot 15: 80).
            case "phase-header" -> new float[] { metrics.left(), (moving ? 100 : 84) - (metrics.narrow() ? 4 : 0) };
            // Unit card: 222 in shot 02, 252 with a chip line, which a window of 800 or less hides (226 in shot 15).
            case "unit-card" -> new float[] { metrics.left(), metrics.lowHeight() ? 226 : moving ? 222 : 252 };
            // Dock heights measured per variant: movement 174, weapons 158, initiative 116.
            case "command-dock" -> new float[] { metrics.dock(),
                  moving ? 174 : layout.phase() == GamePhase.FIRING ? 158 : 116 };
            // The extras also exercise a long shortcut line partially covered by both side columns.
            case "hint-line" -> new float[] { layout.grid() ? 1500 : 590, 13 };
            // Camera presets, labelled utilities, then icon-only Help and Menu.
            case "utility-bar" -> new float[] { 690, 34 };
            // Minimap: the prototype's 210 (180 at 800 or less), which its map fills inside the rails.
            case "minimap" -> new float[] { metrics.right(), metrics.lowHeight() ? 180 : 210 };
            case "solution-card" -> new float[] { metrics.right(), 145 };
            // The chip without the prototype's Back to 3D button, as measured.
            case "tactical-chip" -> new float[] { 153, 32 };
            case "conditions-card" -> new float[] { 260, 140 };
            case "initiative-card" -> new float[] { 560, 290 };
            case "record-sheet" -> new float[] { 260, 400 };
            case "help-dialog" -> new float[] { 620, 560 };
            case "toast-stack" -> new float[] { 420, 40 };
            default -> new float[] { 300, FILL };
        };
    }

    private static boolean hintUnderSidePanel(Actor first, Actor second) {
        return first.getName().equals("hint-line") && HINT_COVERING_PANELS.contains(second.getName())
              || second.getName().equals("hint-line") && HINT_COVERING_PANELS.contains(first.getName());
    }

    /**
     * Puts a stand-in of the given size where the named component's actor is, visible or hidden. A stand-in is a
     * translucent panel with its name, so the capture shows the slot map.
     */
    @SuppressWarnings("unchecked")
    private static void swap(GpuHud hud, GpuHudTestStage harness, String name, boolean visible, float[] size) {
        Actor current = hud.stage.getRoot().findActor(name);
        // Its label may be cut: only the size counts, as for a component that fits its content to its slot.
        Table standIn = new Table() {
            @Override
            public float getMinWidth() {
                return 0;
            }

            @Override
            public float getMinHeight() {
                return 0;
            }

            @Override
            public float getPrefWidth() {
                return size[0];
            }

            @Override
            public float getPrefHeight() {
                return size[1];
            }
        };
        standIn.setName(name);
        standIn.setVisible(visible);
        standIn.setBackground(harness.theme.skin.newDrawable("white", colour(name)));
        Label label = new Label(name, harness.theme.skin, "small");
        label.setEllipsis(true);
        standIn.add(label).minWidth(0).growX().top().left().pad(4);
        standIn.top().left();
        Group parent = current.getParent();
        if (parent instanceof Container<?> container) {
            ((Container<Actor>) container).setActor(standIn);
        } else {
            ((Table) parent).getCell(current).setActor(standIn);
        }
    }

    /** The stand-ins shown on screen, with every panel of the layout among them. */
    private static List<Actor> shown(GpuHud hud, Layout layout) {
        List<Actor> shown = new ArrayList<>();
        List<String> expected = layout.overlays() ? layout.panels()
              : Stream.concat(BASE.stream(), layout.panels().stream()).toList();
        for (String name : SLOTTED) {
            Actor actor = hud.stage.getRoot().findActor(name);
            boolean visible = true;
            for (Actor node = actor; node != null; node = node.getParent()) {
                visible &= node.isVisible();
            }
            if (visible && expected.contains(name)) {
                shown.add(actor);
            }
        }
        List<String> names = shown.stream().map(Actor::getName).toList();
        for (String name : expected) {
            // The narrow layouts hide the hint line, and the chip hides where the top row has no room for it.
            assertTrue(names.contains(name) || name.equals("hint-line") || name.equals("tactical-chip"),
                  name + " is not shown in " + layout.name());
        }
        return shown;
    }

    private static List<String> with(String... panels) {
        return Stream.concat(BASE.stream(), Stream.of(panels)).toList();
    }

    private static Color colour(String name) {
        Color colour = new Color().fromHsv(Math.floorMod(name.hashCode(), 360), .65f, .95f);
        colour.a = .45f;
        return colour;
    }

    /** True when {@code bound} contains {@code area}, give or take half a unit. */
    private static boolean covers(Rectangle bound, Rectangle area) {
        return area.x >= bound.x - .5f && area.y >= bound.y - .5f
              && area.x + area.width <= bound.x + bound.width + .5f
              && area.y + area.height <= bound.y + bound.height + .5f;
    }

    /**
     * The local turn of the layout's phase; the weapons panel shows while the fire orders are active, the contacts
     * panel while the move's fire preview is.
     */
    private static GpuBoardSource.Frame frame(Layout layout) {
        GpuFireOrders.Snapshot fire = !layout.panels().contains("weapons-panel") ? GpuFireOrders.Snapshot.EMPTY
              : new GpuFireOrders.Snapshot(true, true, 1, GpuFireOrders.Focus.NONE, -1, List.of(), List.of(),
                    List.of(), 0, false, false, "", null, null, null, List.of(), null, Map.of(), 0, 0, null);
        GpuFirePreview.Snapshot preview = !layout.panels().contains("contacts-panel") ? GpuFirePreview.Snapshot.NONE
              : new GpuFirePreview.Snapshot(true, true, UNIT, false, new Coords(5, 5), 0, 0, "None", 0, 0, "", false,
                    0, 0, List.of());
        GpuBattleStatus.UnitStatus unit = new GpuBattleStatus.UnitStatus(UNIT, GpuBattleStatus.Side.OWN, false,
              "Atlas AS7-D", "Atlas", "AS7-D", 100, "Assault", "", "Pilot", 4, 5, 1, 1, 0, 0, "30", 3, "5", 0, "", 0,
              0, 0, 0, false, false, false, false, Entity.DMG_NONE, List.of(), "", new Coords(5, 5), 0, null,
              List.of(), 0, 0, Player.PLAYER_NONE, List.of());
        return GpuHudFixtures.frame(GpuHudFixtures.status(3, layout.phase(), true, 1, 0, unit),
              new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY, fire,
                    GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY, preview, GpuChat.Snapshot.EMPTY,
                    GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY));
    }
}
