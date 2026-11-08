/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.common.board.Coords;
import megamek.common.options.OptionsConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Characterizes Swing's movement envelope for one unit in each movement gear: the MP map the MovementDisplay computes
 * and the bands and borders the envelope sprite handler draws from it. The values were recorded on the code before
 * stage E2a extracted the envelope start path and the band helper, and must not change.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuMoveEnvelopeCharacterizationTest {

    @Test
    void eachGearKeepsItsEnvelopeHexesBandsAndBorders() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            List<String> observed = new ArrayList<>();
            observed.add(observe(moving));
            moving.command(MoveCommand.MOVE_BACK_UP);
            observed.add(observe(moving));
            moving.command(MoveCommand.MOVE_JUMP);
            observed.add(observe(moving));
            moving.command(MoveCommand.MOVE_DFA);
            observed.add(observe(moving));
            onSwing(() -> {
                moving.board.game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_SPRINT)
                      .setValue(true);
                moving.display.selectEntity(moving.unit.getId());
                return null;
            });
            observed.add(observe(moving));

            assertEquals(List.of(
                  // Selected: walking and running.
                  "gear 0 hexes 26 mp {0=1, 1=1, 2=2, 3=4, 4=8, 5=10} walk 8/24 run 18/64 sprint 0/0 jump 0/0",
                  // Back up: walk MP only.
                  "gear 1 hexes 3 mp {0=1, 1=1, 3=1} walk 3/14 run 0/0 sprint 0/0 jump 0/0",
                  // Jump: the jump band, outlined at its edge.
                  "gear 2 hexes 36 mp {1=6, 2=12, 3=18} walk 0/0 run 0/0 sprint 0/0 jump 36/48",
                  // Death from above: the same jump hexes, each outlined on all six sides.
                  "gear 4 hexes 36 mp {1=6, 2=12, 3=18} walk 0/0 run 0/0 sprint 0/0 jump 36/216",
                  // TacOps sprinting adds the sprint band.
                  "gear 0 hexes 39 mp {0=1, 1=1, 2=2, 3=4, 4=8, 5=10, 6=13} walk 8/24 run 18/64 sprint 13/62 jump 0/0"),
                  observed);
        }
    }

    /**
     * The last envelope the display showed: its gear, hex count and MP histogram, then per band (sprite colour) the
     * hexes and the hex edges drawn as the band's border.
     */
    private static String observe(GpuMovementFixture moving) throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Coords, Integer>> maps = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Integer> gears = ArgumentCaptor.forClass(Integer.class);
        verify(moving.gui, atLeastOnce()).showMovementEnvelope(eq(moving.unit), maps.capture(), gears.capture());
        clearInvocations(moving.gui);
        Map<Coords, Integer> envelope = maps.getValue();
        Map<Integer, Long> histogram = new TreeMap<>(envelope.values().stream()
              .collect(Collectors.groupingBy(Function.identity(), Collectors.counting())));
        List<MovementEnvelopeSprite> sprites = onSwing(moving::envelopeSprites);
        StringBuilder text = new StringBuilder("gear ").append(gears.getValue())
              .append(" hexes ").append(envelope.size()).append(" mp ").append(histogram);
        GUIPreferences preferences = GUIPreferences.getInstance();
        Map<String, Color> bands = Map.of("walk", preferences.getMoveDefaultColor(),
              "run", preferences.getMoveRunColor(), "sprint", preferences.getMoveSprintColor(),
              "jump", preferences.getMoveJumpColor());
        assertEquals(4, bands.values().stream().distinct().count(), "The four band colours differ");
        for (String band : List.of("walk", "run", "sprint", "jump")) {
            List<MovementEnvelopeSprite> inBand = sprites.stream()
                  .filter(sprite -> field(sprite, "drawColor").equals(bands.get(band))).toList();
            int edges = inBand.stream().mapToInt(sprite -> Integer.bitCount((Integer) field(sprite, "borders")))
                  .sum();
            text.append(' ').append(band).append(' ').append(inBand.size()).append('/').append(edges);
        }
        return text.toString();
    }

    private static Object field(MovementEnvelopeSprite sprite, String name) {
        try {
            Field field = MovementEnvelopeSprite.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(sprite);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
