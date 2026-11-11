/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import org.junit.jupiter.api.Test;

/** A speaker's voice trace and the comm feed it drives, without drawing them. */
class UiVoiceTraceTest {
    private static final float FRAME = 1 / 60f;

    private static UiSignalFeed feed(UiSignalFeed.Signal signal, boolean motion) {
        return new UiSignalFeed(new TextureRegion(), new TextureRegion(), 100, 140, Color.CYAN, Color.ORANGE, signal,
              motion);
    }

    private static void run(UiVoiceTrace trace, float seconds) {
        for (float time = 0; time < seconds; time += FRAME) {
            trace.act(FRAME);
        }
    }

    @Test
    void theVoiceRisesWhileSpeakingAndSinksAfter() {
        UiVoiceTrace trace = new UiVoiceTrace(new TextureRegion(), 100, 16, Color.CYAN, true);
        float loudest = 0;

        trace.speaking(true);
        for (int i = 0; i < 120; i++) {
            trace.act(FRAME);
            loudest = Math.max(loudest, trace.level());
        }
        trace.speaking(false);
        run(trace, 1);

        assertTrue(loudest > .2f, "the voice rises while speaking");
        assertTrue(trace.level() < .01f, "and sinks after");
    }

    @Test
    void withoutMotionTheTraceStaysFlat() {
        UiVoiceTrace trace = new UiVoiceTrace(new TextureRegion(), 100, 16, Color.CYAN, false);

        trace.speaking(true);
        run(trace, 1);

        assertFalse(trace.speaking());
        assertEquals(0, trace.level());
    }

    @Test
    void theFeedTunesInAndSettlesAtOnceWhenSkipped() {
        UiSignalFeed tuning = feed(UiSignalFeed.Signal.CLEAN, true);

        assertFalse(tuning.booted());
        tuning.settle();

        assertTrue(tuning.booted());
        assertTrue(feed(UiSignalFeed.Signal.CLEAN, false).booted(), "without motion it shows tuned in");
    }

    @Test
    void roughnessGrowsWithStaticAndGlitches() {
        UiSignalFeed.Signal rough = new UiSignalFeed.Signal(.22f, 2, .3f, .105f, .44f, .2f, 2, 5, 20, 8, 32, .8f);

        assertEquals(0, feed(UiSignalFeed.Signal.CLEAN, true).roughness());
        assertEquals(1, feed(rough, true).roughness());
    }
}
