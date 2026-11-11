/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Comm feeds on a stage: tuning in from snow to settled, clean and rough, the last talking under its voice trace. The
 * shader must compile; ui-signal-feed.png shows them.
 */
@Tag("on-demand")
class UiSignalFeedSmokeTest {
    private static final String PORTRAIT = "data/images/portraits/Female/Administrator/Adm_Com_F_1.png";
    private static final float[] TUNED = { .1f, .35f, .6f, 1, 1 };
    private static final UiSignalFeed.Signal ROUGH = new UiSignalFeed.Signal(.22f, 2, .3f, .105f, .44f, .2f, 2, 5, 20,
          8, 32, .8f);

    @Test
    void feedsTuneInAndTalk() {
        UiTestStage.run(ui -> {
            Texture texture = new Texture(new FileHandle(PORTRAIT));
            TextureRegion white = new TextureRegion(ui.theme.skin.get("white", Texture.class));
            List<UiSignalFeed> feeds = new ArrayList<>();
            try {
                UiSignalFeed.Signal[] signals = { UiSignalFeed.Signal.CLEAN, ROUGH };
                for (int row = 0; row < signals.length; row++) {
                    for (int column = 0; column < TUNED.length; column++) {
                        UiSignalFeed feed = new UiSignalFeed(new TextureRegion(texture), white, 200, 280,
                              Color.valueOf("56D0C5"), Color.valueOf("EBB14C"), signals[row], true);
                        feeds.add(feed);
                        feed.setBounds(40 + column * 240, 60 + (1 - row) * 360, 200, 280);
                        ui.stage.addActor(feed);
                        feed.act(TUNED[column] * .65f);
                        if (column == TUNED.length - 1) {
                            UiVoiceTrace trace = new UiVoiceTrace(white, 200, 16, Color.valueOf("56D0C5"), true)
                                  .drive(feed);
                            trace.setBounds(feed.getX(), feed.getY() - 26, 200, 16);
                            ui.stage.addActor(trace);
                            trace.speaking(true);
                            for (int frame = 0; frame < 20; frame++) {
                                trace.act(1 / 60f);
                            }
                        }
                    }
                }
                ui.draw();
                ui.capture("ui-signal-feed").dispose();
                for (UiSignalFeed feed : feeds) {
                    assertFalse(feed.plain(), "the feed's shader compiles");
                }
            } finally {
                for (UiSignalFeed feed : feeds) {
                    feed.dispose();
                }
                texture.dispose();
            }
        });
    }
}
