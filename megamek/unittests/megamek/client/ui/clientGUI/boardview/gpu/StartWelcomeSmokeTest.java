/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the start screen's welcome through the stage: it unfolds from a line and its sections rise in after it, the
 * screen behind takes no click, its links open their pages, and Acknowledge (with or without the box ticked), Quit and
 * Escape answer as the Swing form did.
 */
@Tag("on-demand")
class StartWelcomeSmokeTest {

    @Test
    void theWelcomeUnfoldsThenItsSectionsRiseIn() {
        UiTestStage.run(ui -> {
            List<String> said = new ArrayList<>();
            StartWelcome welcome = new StartWelcome(ui.kit, true, answers(said));
            welcome.show(ui.stage);
            ui.draw();
            UiDialog dialog = welcome.dialog();
            assertTrue(dialog.unfolding(), "the card opens from a line");
            assertEquals(UiDialog.FOLDED, dialog.card().getScaleY(), 1e-4f);
            assertEquals(0, welcome.bands().shown(0), "the sections wait for the card");
            float last = dialog.card().getScaleY();
            for (int frame = 0; frame < 6; frame++) {
                ui.stage.act(1 / 60f);
                float now = dialog.card().getScaleY();
                assertTrue(now >= last, "the card only ever unfolds");
                last = now;
            }
            assertTrue(last > UiDialog.FOLDED, "the card has begun to unfold");
            for (int frame = 0; frame < 120; frame++) {
                ui.stage.act(1 / 60f);
            }
            assertFalse(dialog.unfolding(), "the card has unfolded");
            assertFalse(dialog.card().isTransform(), "an unfolded card draws as any other");
            assertTrue(welcome.bands().done(), "every section has risen in");
            assertEquals(1, welcome.bands().shown(0));
            ui.draw();
            UiTestStage.assertTexts(dialog.layer());
        });
    }

    @Test
    void theWelcomeBlocksTheScreenAndAnswersEachButton() {
        UiTestStage.run(ui -> {
            int[] behind = { 0 };
            Actor screen = new Actor();
            screen.setBounds(0, 0, ui.stage.getWidth(), ui.stage.getHeight());
            screen.setTouchable(Touchable.enabled);
            screen.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    behind[0]++;
                }
            });
            ui.window.addActor(screen);
            List<String> said = new ArrayList<>();
            StartWelcome welcome = new StartWelcome(ui.kit, false, answers(said));
            welcome.show(ui.stage);
            ui.draw();
            assertFalse(welcome.dialog().unfolding(), "without motion the welcome opens at once");
            assertTrue(welcome.bands().done());

            clickScreen(ui, 4, 4);
            assertEquals(0, behind[0], "the screen behind takes no click");
            assertTrue(welcome.isOpen());

            for (String link : List.of("welcome-rules", "welcome-wiki", "welcome-discord")) {
                click(ui, ui.stage.getRoot().findActor(link));
            }
            assertEquals(List.of("browse " + Messages.getString("LicensingDialog.gameContentRulesUrl"),
                  "browse " + Messages.getString("LicensingDialog.wikiUrl"),
                  "browse " + Messages.getString("LicensingDialog.discordUrl")), said);
            assertTrue(welcome.isOpen(), "a link leaves the welcome open");

            said.clear();
            click(ui, welcome.dontShowAgain());
            assertTrue(welcome.dontShowAgain().isTicked());
            click(ui, welcome.acknowledge());
            assertEquals(List.of("acknowledged true"), said);
            assertFalse(welcome.isOpen(), "Acknowledge closes the welcome");

            said.clear();
            StartWelcome again = new StartWelcome(ui.kit, false, answers(said));
            again.show(ui.stage);
            ui.draw();
            ui.stage.keyDown(Input.Keys.ENTER);
            assertEquals(List.of("acknowledged false"), said, "Enter acknowledges, the box unticked");

            said.clear();
            StartWelcome quit = new StartWelcome(ui.kit, false, answers(said));
            quit.show(ui.stage);
            ui.draw();
            click(ui, quit.quit());
            ui.stage.keyDown(Input.Keys.ESCAPE);
            assertEquals(List.of("quit", "quit"), said, "Quit and Escape end MegaMek, as closing the Swing form did");
            ui.draw();
            ui.capture("start-welcome").dispose();
        });
    }

    @Test
    void theSwingTextsReadAsPlainSentences() {
        assertEquals("an unofficial game", StartWelcome.plain("an <b>unofficial</b> game"));
        assertEquals("visit our wiki.", StartWelcome.sentence("visit our wiki: "));
        assertEquals("join us.", StartWelcome.sentence("join us."));
    }

    private static StartWelcome.Answer answers(List<String> said) {
        return new StartWelcome.Answer() {
            @Override
            public void acknowledged(boolean dontShowAgain) {
                said.add("acknowledged " + dontShowAgain);
            }

            @Override
            public void quit() {
                said.add("quit");
            }

            @Override
            public void browse(String url) {
                said.add("browse " + url);
            }
        };
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        clickScreen(ui, Math.round(point.x), Math.round(point.y));
    }

    private static void clickScreen(UiTestStage ui, int x, int y) {
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
