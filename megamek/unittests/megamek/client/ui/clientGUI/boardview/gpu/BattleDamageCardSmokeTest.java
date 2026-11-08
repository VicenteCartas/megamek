/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.client.lobby.GameMasterUnitTools;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opens the Game Master's damage card of a unit in a running game, as the battle window shows it from the board's
 * Edit damage entry: its Game Master section, an Explode button, and Destroy, which asks and then sends the server's
 * kill command and closes the card.
 */
@Tag("on-demand")
class BattleDamageCardSmokeTest {

    private static final LobbyPromptViews.Edt EDT = (work, then) -> {
        work.run();
        then.run();
    };

    @Test
    void theGameMastersCardOffersItsToolsAndDestroysTheUnit() {
        UiTestStage.run(ui -> {
            Entity atlas = atlas();
            UnitDamageForm form = UnitDamageForm.of(atlas, UnitDamageForm.Options.inPlay((Game) atlas.getGame()));
            List<String> commands = new ArrayList<>();
            List<DamageEditSpec> edits = new ArrayList<>();
            boolean[] closed = {false};
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.gameMasterDamage(form, EDT, new GameMasterUnitTools.Host() {
                @Override
                public void sendDamageEdit(DamageEditSpec spec) {
                    edits.add(spec);
                }

                @Override
                public void sendChat(String command) {
                    commands.add(command);
                }
            }, () -> closed[0] = true);
            ui.draw();

            click(ui, find(ui, "damage-whole"));
            find(ui, "damage-gm");
            find(ui, "damage-owner");
            find(ui, "damage-modifier-0");
            find(ui, "damage-modifier-0-rounds");

            int explosive = form.places().stream().flatMap(place -> place.values().stream())
                  .mapToInt(UnitDamageForm.Value::explosive).filter(number -> number >= 0).findFirst().orElse(-1);
            assertTrue(explosive >= 0, "the Atlas's ammunition can explode");
            String explode = "damage-explode-" + explosive;
            for (int step = 0; step <= form.places().size() && ui.stage.getRoot().findActor(explode) == null;
                  step++) {
                click(ui, find(ui, "damage-next"));
            }
            find(ui, explode);

            click(ui, find(ui, "damage-destroy"));
            click(ui, find(ui, "yes-no-yes"));
            assertEquals(List.of("/kill " + atlas.getId() + " false"), commands, "Destroy sends the kill command");
            assertTrue(edits.isEmpty(), "Destroy drops the other edits");
            assertTrue(closed[0], "the act closes the card");
            assertFalse(dialogs.isOpen());
            dialogs.dispose();
        });
    }

    /** An Atlas of the first of two players on teams, in a new game. */
    private static Entity atlas() {
        try {
            Entity entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf"))
                  .getEntity();
            Game game = new Game();
            Player alice = new Player(0, "Alice");
            alice.setTeam(1);
            Player bob = new Player(1, "Bob");
            bob.setTeam(2);
            game.addPlayer(0, alice);
            game.addPlayer(1, bob);
            entity.setGame(game);
            entity.setId(game.getNextEntityId());
            entity.setOwner(alice);
            game.addEntity(entity);
            return entity;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Actor find(UiTestStage ui, String name) {
        Supplier<Actor> lookup = () -> ui.stage.getRoot().findActor(name);
        for (int i = 0; i < 20 && lookup.get() == null; i++) {
            ui.draw();
        }
        Actor actor = lookup.get();
        assertNotNull(actor, name);
        return actor;
    }

    private static void click(UiTestStage ui, Actor actor) {
        Actor scroll = actor;
        while (scroll != null && !(scroll instanceof ScrollPane)) {
            scroll = scroll.getParent();
        }
        if (scroll instanceof ScrollPane pane) {
            pane.layout();
            Vector2 spot = actor.localToAscendantCoordinates(pane.getActor(), new Vector2());
            pane.scrollTo(spot.x, spot.y, actor.getWidth(), actor.getHeight(), false, true);
            pane.updateVisualScroll();
        }
        ui.draw();
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
