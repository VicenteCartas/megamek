/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.server.SmokeCloud;
import org.junit.jupiter.api.Test;

class LosEffectsTraceTest {
    private static final Coords START = new Coords(0, 0);
    private static final Coords END = new Coords(0, 6);

    private static Game game() {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(8, 8));
        return game;
    }

    private static LosEffects trace(Game game, Coords from, Coords to, int height) {
        LosEffects ordinary = LosEffects.calculateLos(game, info(game, from, to, height));
        LosEffects traced = LosEffects.calculateLos(game, info(game, from, to, height), true);
        assertEquals(ordinary.canSee(), traced.canSee(), "Diagnostics cannot change visibility");
        assertEquals(ordinary.losModifiers(game).getDesc(), traced.losModifiers(game).getDesc());
        assertEquals(ordinary.getLightWoods(), traced.getLightWoods());
        assertEquals(ordinary.getTargetCover(), traced.getTargetCover());
        assertNull(ordinary.getBlockingHex(), "Ordinary LOS does not collect a diagnostic path");
        assertNull(ordinary.getModifierHex(), "Ordinary LOS does not collect modifier locations");
        return traced;
    }

    private static LosEffects.AttackInfo info(Game game, Coords from, Coords to, int height) {
        // The engine uses zero-based heights; the ruler displays height 2 for a standing Mek.
        return LosEffects.buildAttackInfo(from, to, 0, height - 1, height - 1,
              game.getBoard().getHex(from).getLevel(), game.getBoard().getHex(to).getLevel());
    }

    @Test
    void theFirstBlockingHexFollowsDirectionHeightAndRuleMode() {
        for (String option : new String[] { "", OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1,
              OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES }) {
            Game game = game();
            if (!option.isEmpty()) { game.getOptions().getOption(option).setValue(true); }
            assertTrue(trace(game, START, END, 2).canSee());
            assertNull(trace(game, START, END, 2).getBlockingHex());
            assertNull(trace(game, START, END, 2).getModifierHex());
            game.getBoard().getHex(new Coords(0, 2)).setLevel(2);
            game.getBoard().getHex(new Coords(0, 4)).setLevel(2);
            assertEquals(new Coords(0, 2), trace(game, START, END, 2).getBlockingHex());
            assertNull(trace(game, START, END, 2).getModifierHex(), "A solid blocker is not a modifier");
            assertEquals(new Coords(0, 4), trace(game, END, START, 2).getBlockingHex());
            assertNull(trace(game, START, END, 5).getBlockingHex());
        }
    }

    @Test
    void accumulatedWoodsBlockAtTheThirdHexRatherThanTheFirstTree() {
        Game game = game();
        for (int y = 1; y <= 3; y++) {
            Hex hex = game.getBoard().getHex(new Coords(0, y));
            hex.addTerrain(new Terrain(Terrains.WOODS, 1));
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        }
        assertEquals(new Coords(0, 3), trace(game, START, END, 2).getBlockingHex());
        assertEquals(new Coords(0, 1), trace(game, START, END, 2).getModifierHex());
        assertEquals(new Coords(0, 1), trace(game, END, START, 2).getBlockingHex());
        assertEquals(new Coords(0, 3), trace(game, END, START, 2).getModifierHex());
        assertNull(trace(game, START, END, 4).getBlockingHex());
        assertNull(trace(game, START, END, 4).getModifierHex());
    }

    @Test
    void modifiersFollowTheFirstEffectiveTerrainInEachDirectionAndRuleMode() {
        for (String option : new String[] { "", OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1,
              OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES }) {
            Game game = game();
            if (!option.isEmpty()) { game.getOptions().getOption(option).setValue(true); }
            Coords woods = new Coords(0, 2), smoke = new Coords(0, 4);
            game.getBoard().getHex(woods).addTerrain(new Terrain(Terrains.WOODS, 1));
            game.getBoard().getHex(woods).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
            game.getBoard().getHex(smoke).addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_LIGHT));
            assertTrue(trace(game, START, END, 2).canSee());
            assertEquals(woods, trace(game, START, END, 2).getModifierHex());
            assertEquals(smoke, trace(game, END, START, 2).getModifierHex());
            assertNull(trace(game, START, END, 4).getModifierHex());
        }
    }

    @Test
    void plantedFieldsBecomeAModifierOnlyWhenTheirAccumulatedPenaltyIsPositive() {
        Game game = game();
        game.getBoard().getHex(new Coords(0, 2)).addTerrain(new Terrain(Terrains.FIELDS, 1));
        assertNull(trace(game, START, END, 1).getModifierHex());
        game.getBoard().getHex(new Coords(0, 4)).addTerrain(new Terrain(Terrains.FIELDS, 1));
        assertEquals(new Coords(0, 4), trace(game, START, END, 1).getModifierHex());
        assertEquals(new Coords(0, 2), trace(game, END, START, 1).getModifierHex());
    }

    @Test
    void splitPathModifierUsesTheChosenSideInsteadOfEarlierTerrainOnTheOtherSide() {
        Game game = game();
        Coords from = new Coords(0, 2), to = new Coords(6, 2);
        var path = Coords.intervening(from, to, true);
        Coords light = path.get(1), heavy = path.get(5);
        game.getBoard().getHex(light).addTerrain(new Terrain(Terrains.WOODS, 1));
        game.getBoard().getHex(light).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        game.getBoard().getHex(heavy).addTerrain(new Terrain(Terrains.WOODS, 2));
        game.getBoard().getHex(heavy).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        LosEffects result = trace(game, from, to, 2);
        assertTrue(result.canSee());
        assertEquals(2, result.losModifiers(game).getValue());
        assertEquals(heavy, result.getModifierHex());
    }

    @Test
    void partialCoverDoesNotMarkAStatusThatTheTargetsQuirkIgnores() {
        Game game = game();
        Coords cover = new Coords(0, 5);
        game.getBoard().getHex(cover).setLevel(1);
        var attack = info(game, START, END, 2);
        attack.targetIsMek = true;
        assertEquals(cover, LosEffects.calculateLos(game, attack, true).getModifierHex());
        attack.targetIsOversized = true;
        LosEffects oversized = LosEffects.calculateLos(game, attack, true);
        assertEquals(0, oversized.losModifiers(game).getValue());
        assertNull(oversized.getModifierHex());
    }

    @Test
    void splitPathUsesTheEngineChosenSideEvenBeforeALaterCommonBlocker() {
        Game game = game();
        Coords from = new Coords(0, 2), to = new Coords(6, 2);
        var path = Coords.intervening(from, to, true);
        assertEquals(30, from.degree(to) % 60, .0001);
        Coords first = path.get(1);
        game.getBoard().getHex(first).setLevel(3);
        assertFalse(trace(game, from, to, 2).canSee());
        assertEquals(first, trace(game, from, to, 2).getBlockingHex());
        game.getBoard().getHex(path.get(3)).setLevel(3);
        assertEquals(first, trace(game, from, to, 2).getBlockingHex());
    }
}
