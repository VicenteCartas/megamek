/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords.Severity;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/**
 * The Tactical View's bases show what the client decided: the facing and twist, the done and ready
 * states, the destroyed cross, the modifier's chevrons once it shows, the heat pip, the elevation and the two most
 * severe conditions, from the battle status alone.
 */
class GpuUnitBasesTest {
    private static final GpuBattleStatus.Marks MARKS = new GpuBattleStatus.Marks(0, 0xff00ff00, 0xff00ff00);

    /** A listed unit with the fields the base reads; the rest as GpuBattleStatus captures an idle unit. */
    private static GpuBattleStatus.UnitStatus unit(GpuBattleStatus.Side side, int facing, int twist, boolean done,
          boolean canActNow, boolean destroyed, int heat, String moved, int tmm, boolean tmmShown,
          List<UnitStatusWords.StatusWord> words, List<UnitStatusWords.StatusWord> tiles) {
        return new GpuBattleStatus.UnitStatus(7, side, false, "Quickdraw QKD-8X", "Quickdraw", "QKD-8X", 60, "Medium",
              "", "", 4, 5, .8, .9, heat, heat > 0 ? 0xffaa00 : 0, "", 5, "8", 5, moved, 0, 0, facing, tmm, canActNow,
              true, done, destroyed, Entity.DMG_NONE, List.of(), "", new Coords(3, 3), 0, null, words, 2, 0,
              Player.PLAYER_NONE, tiles, "Quickdraw", MARKS, twist, 0x00ff00, tmmShown);
    }

    private static GpuBattleStatus.UnitStatus idle(GpuBattleStatus.Side side) {
        return unit(side, 1, 1, false, false, false, 0, "", 0, false, List.of(), List.of());
    }

    private static GpuUnitBases.BaseSpec spec(GpuBattleStatus.UnitStatus status) {
        return GpuUnitBases.spec(status, false, false, Entity.NONE, Entity.NONE, null, false, List.of(), true);
    }

    @Test
    void theRingAndNotchesFollowTheSideFacingAndTwist() {
        GpuUnitBases.BaseSpec own = spec(unit(GpuBattleStatus.Side.OWN, 2, 3, false, false, false, 0, "", 0, false,
              List.of(), List.of()));
        assertEquals(GpuUnitBases.Look.FRIEND, own.look());
        assertTrue(own.facing());
        assertEquals(1, own.twist(), "The twist notch one hexside clockwise of the facing");
        assertEquals(5, spec(unit(GpuBattleStatus.Side.ALLY, 1, 0, false, false, false, 0, "", 0, false, List.of(),
              List.of())).twist(), "Counterclockwise twists wrap around");
        assertEquals(0, spec(idle(GpuBattleStatus.Side.OWN)).twist(), "No twist notch without a twist");
        assertEquals(0, spec(unit(GpuBattleStatus.Side.OWN, 1, -1, false, false, false, 0, "", 0, false, List.of(),
              List.of())).twist(), "Nor without a secondary facing");
        assertEquals(GpuUnitBases.Look.ENEMY, spec(idle(GpuBattleStatus.Side.ENEMY)).look());
        assertFalse(spec(unit(GpuBattleStatus.Side.OWN, -1, -1, false, false, false, 0, "", 0, false, List.of(),
              List.of())).facing(), "Infantry that fires all around has no facing notch");
    }

    @Test
    void doneReadyAndDestroyedUnitsShowTheirState() {
        assertTrue(spec(unit(GpuBattleStatus.Side.OWN, 0, 0, true, false, false, 0, "", 0, false, List.of(),
              List.of())).done());
        GpuUnitBases.BaseSpec ready = spec(unit(GpuBattleStatus.Side.OWN, 0, 0, false, true, false, 0, "", 0, false,
              List.of(), List.of()));
        assertTrue(ready.ready(), "A unit the player can act with now pulses");
        assertFalse(spec(unit(GpuBattleStatus.Side.OWN, 0, 0, true, true, false, 0, "", 0, false, List.of(),
              List.of())).ready(), "One that has acted does not");
        var destroyed = unit(GpuBattleStatus.Side.ENEMY, 2, 2, true, false, true, 9, "Walked", 2,
              true, List.of(), List.of());
        assertFalse(spec(destroyed).destroyed(), "Live status must not show the cross before playback reaches death");
        GpuUnitBases.BaseSpec wreck = GpuUnitBases.spec(destroyed, false, true, Entity.NONE, Entity.NONE, null,
              false, List.of(), true);
        assertTrue(wreck.destroyed());
        assertEquals(GpuUnitBases.Look.NEUTRAL, wreck.look(), "A wreck's ring is grey");
        assertFalse(wreck.facing(), "without a notch");
        assertEquals(0, wreck.heatRgb(), "nor heat");
        GpuUnitBases.BaseSpec dead = GpuUnitBases.spec(idle(GpuBattleStatus.Side.OWN), false, true, Entity.NONE,
              Entity.NONE, null, false, List.of(), true);
        assertTrue(dead.destroyed(), "The pose's death counts before the status catches up");
        GpuUnitBases.BaseSpec contact = GpuUnitBases.spec(null, true, false, Entity.NONE, Entity.NONE, null, false,
              List.of(), true);
        assertEquals(GpuUnitBases.Look.CONTACT, contact.look());
        assertFalse(contact.facing() || contact.badges(), "A sensor contact reveals no facing or badges");
        assertEquals(GpuUnitBases.Look.NEUTRAL, GpuUnitBases.spec(null, false, false, Entity.NONE, Entity.NONE, null,
              false, List.of(), true).look(), "A unit the status does not list has a grey ring");
    }

    @Test
    void theActingUnitAndTheFocusedEnemyGlow() {
        var own = idle(GpuBattleStatus.Side.OWN);
        assertEquals(GpuUnitBases.Emphasis.ACTING, GpuUnitBases.spec(own, false, false, 7, Entity.NONE, null, false,
              List.of(), true).emphasis());
        assertEquals(GpuUnitBases.Emphasis.FOCUS, GpuUnitBases.spec(idle(GpuBattleStatus.Side.ENEMY), false, false,
              Entity.NONE, 7, null, false, List.of(), true).emphasis());
        assertEquals(GpuUnitBases.Emphasis.NONE, spec(own).emphasis());
    }

    @Test
    void chevronsShowTheModifierOnlyOnceItShows() {
        assertEquals(-1, spec(unit(GpuBattleStatus.Side.OWN, 0, 0, false, false, false, 0, "Ran", 2, false, List.of(),
              List.of())).chevrons(), "Before the modifier means something, none");
        GpuUnitBases.BaseSpec ran = spec(unit(GpuBattleStatus.Side.OWN, 0, 0, true, false, false, 0, "Ran", 2, true,
              List.of(), List.of()));
        assertEquals(2, ran.chevrons(), "One chevron per point");
        assertEquals(0x00ff00, ran.moveRgb(), "in the movement type's colour");
        assertEquals(0, spec(unit(GpuBattleStatus.Side.OWN, 0, 0, true, false, false, 0, "Walked", 0, true,
              List.of(), List.of())).chevrons(), "Moved without a modifier: one hollow chevron");
        assertEquals(-1, spec(unit(GpuBattleStatus.Side.OWN, 0, 0, true, false, false, 0, "", 0, true, List.of(),
              List.of())).chevrons(), "Unmoved without a modifier: none");
        assertEquals(6, spec(unit(GpuBattleStatus.Side.OWN, 0, 0, true, false, false, 0, "Jumped", 8, true,
              List.of(), List.of())).chevrons(), "At most six");
    }

    @Test
    void heatElevationAndConditionsComeFromTheLabel() {
        var hot = spec(unit(GpuBattleStatus.Side.OWN, 0, 0, false, false, false, 9, "", 0, false, List.of(),
              List.of(new UnitStatusWords.StatusWord("ELEV", "5", Severity.INFO))));
        assertEquals(0xffaa00, hot.heatRgb(), "The heat pip in the heat colour");
        assertEquals(5, hot.elevation());
        assertFalse(hot.altitude());
        assertEquals(0, spec(idle(GpuBattleStatus.Side.OWN)).heatRgb(), "No pip without heat");
        var aloft = spec(unit(GpuBattleStatus.Side.OWN, 0, 0, false, false, false, 0, "", 0, false, List.of(),
              List.of(new UnitStatusWords.StatusWord("ALT", "3", Severity.INFO))));
        assertEquals(3, aloft.elevation());
        assertTrue(aloft.altitude());
        assertEquals(-2, spec(unit(GpuBattleStatus.Side.OWN, 0, 0, false, false, false, 0, "", 0, false, List.of(),
              List.of(new UnitStatusWords.StatusWord("ELEV", "-2", Severity.INFO)))).elevation(), "Submerged");
        var troubled = spec(unit(GpuBattleStatus.Side.OWN, 0, 0, false, false, false, 0, "", 0, false,
              List.of(new UnitStatusWords.StatusWord("PRONE", "PRONE", Severity.CAUTION),
                    new UnitStatusWords.StatusWord("Withdrawing", "Withdrawing", Severity.CAUTION),
                    new UnitStatusWords.StatusWord("SHUTDOWN", "SD", Severity.WARNING)),
              List.of(new UnitStatusWords.StatusWord("N", "N", Severity.WARNING))));
        assertEquals(List.of(new GpuUnitBases.Condition("power", "", Severity.WARNING),
              new GpuUnitBases.Condition("", "N", Severity.WARNING)), troubled.conditions(),
              "The two most severe, icons for words and letters for tiles; words without an icon are the tag's");
        var spotter = GpuUnitBases.spec(idle(GpuBattleStatus.Side.OWN), false, false, Entity.NONE, Entity.NONE, null,
              false, List.of(new GpuBattleStatus.Link(7, 9, GpuBattleStatus.Link.Kind.SPOT)), true);
        assertEquals(List.of(new GpuUnitBases.Condition("spotting", "", Severity.PRECAUTION)), spotter.conditions());
    }

    @Test
    void anEmitterShowsItsSourceBadge() {
        var source = new BoardElectronicWarfare.Source(7, new Coords(3, 3), true, true, false, true,
              Set.of(new Coords(3, 3)));
        var badge = GpuUnitBases.spec(idle(GpuBattleStatus.Side.OWN), false, false, Entity.NONE, Entity.NONE, source,
              true, List.of(), true).ecm();
        assertEquals(new GpuUnitBases.Ecm(true, true, false, true, false, true), badge);
        var negated = new BoardElectronicWarfare.Source(7, new Coords(3, 3), true, true, false, false, Set.of());
        assertTrue(GpuUnitBases.spec(idle(GpuBattleStatus.Side.OWN), false, false, Entity.NONE, Entity.NONE, negated,
              false, List.of(), true).ecm().suppressed(), "TacOps ECCM negated its field: 40 %");
        assertNull(spec(idle(GpuBattleStatus.Side.OWN)).ecm());
    }

    @Test
    void badgesFadeOutBelowFortyPixelsPerHex() {
        assertEquals(1, GpuUnitBases.fade(GpuUnitBases.FADE_PIXELS));
        assertEquals(1, GpuUnitBases.fade(200));
        assertEquals(.5f, GpuUnitBases.fade(GpuUnitBases.FADE_PIXELS * .75f), .001f);
        assertEquals(0, GpuUnitBases.fade(GpuUnitBases.FADE_PIXELS / 2));
    }
}
