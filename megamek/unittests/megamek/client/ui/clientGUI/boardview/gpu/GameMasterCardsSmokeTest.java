/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.client.Client;
import megamek.client.lobby.BuildingEditForm;
import megamek.client.lobby.ClientCommandForm;
import megamek.client.lobby.GameMasterPlayerSetup;
import megamek.client.lobby.HexBrush;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.MapNoteForm;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.dialogs.GameMasterEditMemory;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.SpecialHexDisplay;
import megamek.common.board.BuildingEditSpec;
import megamek.common.board.Coords;
import megamek.common.board.HexEditSpec;
import megamek.common.enums.BuildingType;
import megamek.common.game.Game;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.voting.Poll;
import megamek.common.voting.VoteThreshold;
import megamek.server.commands.KickCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opens the Game Master's cards over a stub battle, as the battle's own cards on its HUD instead of their Swing
 * dialogs: the map note, the vote and the new Game Master's notice, a command's form, the player setup, the building
 * and the terrain brush beside the board. Each shows its key controls and runs one action.
 */
@Tag("on-demand")
class GameMasterCardsSmokeTest {

    private static final LobbyPromptViews.Edt NOW = (work, then) -> {
        work.run();
        then.run();
    };

    @Test
    void theGameMastersCardsShowOverTheBattle() {
        UiTestStage.run(ui -> {
            Player local = new Player(1, "Master");
            local.setTeam(1);
            Player other = new Player(2, "Other");
            other.setTeam(2);
            Player joiner = new Player(3, "Joiner");
            joiner.setTeam(Player.TEAM_UNASSIGNED);
            Game game = mock(Game.class);
            GameOptions options = mock(GameOptions.class);
            when(game.getOptions()).thenReturn(options);
            when(options.booleanOption(OptionsConstants.GAME_MASTER_ALLOW)).thenReturn(true);
            when(game.getPlayersList()).thenReturn(List.of(local, other, joiner));
            when(game.getPlayer(1)).thenReturn(local);
            when(game.getPlayer(2)).thenReturn(other);
            when(game.getPlayer(3)).thenReturn(joiner);
            when(game.getPlayerEntities(any(Player.class), anyBoolean())).thenReturn(new ArrayList<>());
            ClientGUI gui = mock(ClientGUI.class);
            Client client = mock(Client.class);
            when(gui.getClient()).thenReturn(client);
            LobbyNativeDialogs cards = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            GpuBattleTools tools = new GpuBattleTools(gui, Runnable::run, () -> false, () -> cards);

            // A map note: its text and who reads it; Done hands it over
            SpecialHexDisplay note = MapNoteForm.noteOf(List.of(), local);
            note.setInfo("Mines");
            List<SpecialHexDisplay> notes = new ArrayList<>();
            boolean[] noteClosed = new boolean[1];
            cards.mapNote(new MapNoteForm(note), NOW, notes::add, () -> noteClosed[0] = true);
            ui.draw();
            assertTrue(cards.isOpen(), "the note shows over the battle");
            find(ui, "note-text");
            find(ui, "note-visibility");
            click(ui, find(ui, "note-done"));
            assertEquals(List.of(note), notes);
            assertEquals("Mines", note.getInfo());
            assertTrue(noteClosed[0]);
            assertFalse(cards.isOpen());

            // The vote: the ballots, and a voter's own ballot to send; it is filled again in place and closes as the
            // vote ends, withdrawing nothing for a voter
            Poll poll = new Poll(2, List.of(1, 2, 3), VoteThreshold.UNANIMOUS);
            tools.gameMasterVote(LobbyGameMaster.of(game, local, poll));
            ui.draw();
            find(ui, "vote-ballot-1");
            find(ui, "vote-choice");
            click(ui, find(ui, "vote-send"));
            verify(client, timeout(5000)).sendChat("/allowGM");
            poll.castVote(1, true);
            tools.gameMasterVote(LobbyGameMaster.of(game, local, poll));
            ui.draw();
            assertTrue(cards.isOpen(), "the open card follows the vote");
            tools.closeGameMasterVote();
            ui.draw();
            assertFalse(cards.isOpen(), "the card closes as the vote ends");
            flushSwing();
            verify(client, never()).sendChat("/cancelGM");

            // The new Game Master's notice
            tools.gameMasterAppointed("Other");
            ui.draw();
            click(ui, find(ui, "error-ok"));
            assertFalse(cards.isOpen());

            // A command's form, which asks before it sends
            List<String> chat = new ArrayList<>();
            cards.clientCommand(new ClientCommandForm(new KickCommand(null, null), game, null), NOW, chat::add);
            ui.draw();
            find(ui, "command-field-0");
            find(ui, "command-field-1");
            click(ui, find(ui, "command-execute"));
            assertTrue(chat.isEmpty(), "Execute asks first");
            click(ui, find(ui, "confirm-ok"));
            assertEquals(List.of("/kick playerID=1 "), chat);
            assertFalse(cards.isOpen());

            // The player setup: a joiner put on a team, then handed to the force generator
            chat.clear();
            GameMasterPlayerSetup setup = new GameMasterPlayerSetup(game, local.getId(), joiner);
            setup.team(3);
            cards.playerSetup(setup, NOW, chat::add, player -> { }, player -> { }, player -> { });
            ui.draw();
            find(ui, "setup-player");
            find(ui, "setup-team");
            find(ui, "setup-zone");
            click(ui, find(ui, "setup-apply"));
            assertEquals(List.of("/changeTeam playerID=3 teamID=2"), chat);
            assertFalse(cards.isOpen());
            List<Player> generated = new ArrayList<>();
            List<Player> requisitioned = new ArrayList<>();
            cards.playerSetup(new GameMasterPlayerSetup(game, local.getId(), other), NOW, chat::add,
                  requisitioned::add, player -> { }, generated::add);
            ui.draw();
            click(ui, find(ui, "setup-reinforce-generator"));
            assertEquals(List.of(other), generated, "the force generator is opened for the chosen player");
            assertFalse(cards.isOpen());
            cards.playerSetup(new GameMasterPlayerSetup(game, local.getId(), other), NOW, chat::add,
                  requisitioned::add, player -> { }, player -> { });
            ui.draw();
            click(ui, find(ui, "setup-reinforce-requisition"));
            assertEquals(List.of(other), requisitioned, "the Requisition is opened for the chosen player");
            assertFalse(cards.isOpen());

            // The building, put up in an empty hex
            Coords hex = new Coords(2, 3);
            List<BuildingEditSpec> buildings = new ArrayList<>();
            BuildingEditForm building = new BuildingEditForm(hex, 0, null, new Hex(), new BuildingEditForm.Host() {
                @Override
                public void sendBuildingEdit(BuildingEditSpec spec) {
                    buildings.add(spec);
                }

                @Override
                public List<Integer> fluffLevels(Hex described) {
                    return List.of();
                }
            });
            try {
                cards.building(building, NOW, (described, drawn) -> drawn.accept(null), () -> { });
                ui.draw();
                find(ui, "building-type");
                find(ui, "building-height");
                assertTrue(((UiButton) find(ui, "building-remove")).isDisabled(), "nothing stands there to remove");
                click(ui, find(ui, "building-apply"));
                assertEquals(1, buildings.size());
                assertEquals(BuildingType.MEDIUM, buildings.get(0).getBuildingType());
                assertFalse(cards.isOpen());
            } finally {
                GameMasterEditMemory.forget(hex);
            }

            // The terrain brush beside the board, which takes board clicks while it paints; the brush lives on the
            // Swing thread and the panel draws the views it is sent
            List<HexEditSpec> edits = new ArrayList<>();
            boolean[] readOffSwing = new boolean[1];
            HexBrush.Host brushHost = new HexBrush.Host() {
                @Override
                public Hex hex(Coords coords) {
                    readOffSwing[0] |= !SwingUtilities.isEventDispatchThread();
                    return coords.getX() >= 0 && coords.getY() >= 0 && coords.getX() < 8 && coords.getY() < 8
                          ? new Hex() : null;
                }

                @Override
                public boolean occupied(Coords coords) {
                    readOffSwing[0] |= !SwingUtilities.isEventDispatchThread();
                    return false;
                }

                @Override
                public void sendHexEdit(HexEditSpec spec) {
                    edits.add(spec);
                }

                @Override
                public void showMarks(Collection<Coords> painted) {
                }
            };
            HexBrush[] brush = new HexBrush[1];
            HexBrush.View[] opening = new HexBrush.View[1];
            onSwing(() -> {
                brush[0] = new HexBrush(0, hex, brushHost);
                opening[0] = brush[0].view();
            });
            Queue<Runnable> gpu = new ConcurrentLinkedQueue<>();
            List<Boolean> painting = new ArrayList<>();
            boolean[] brushClosed = new boolean[1];
            GpuHexBrushPanel panel = new GpuHexBrushPanel(ui.kit, ui.stage, opening[0], new GpuHexBrushPanel.Host() {
                @Override
                public void painting(boolean on) {
                    painting.add(on);
                }

                @Override
                public void edit(Consumer<HexBrush> change, Consumer<HexBrush.View> then) {
                    SwingUtilities.invokeLater(() -> {
                        change.accept(brush[0]);
                        HexBrush.View view = brush[0].view();
                        gpu.add(() -> then.accept(view));
                    });
                }

                @Override
                public void closed() {
                    brushClosed[0] = true;
                }
            });
            ui.draw();
            assertFalse(cards.isOpen(), "the brush is no card: the board stays clickable");
            find(ui, "brush-terrain");
            find(ui, "brush-size");
            find(ui, "brush-painted-0");
            onSwing(() -> {
                brush[0].brushHex(new Coords(5, 5));
                HexBrush.View view = brush[0].view();
                gpu.add(() -> panel.show(view));
            });
            settle(ui, gpu);
            find(ui, "brush-painted-1");
            click(ui, find(ui, "brush-apply"));
            settle(ui, gpu);
            assertEquals(1, edits.size());
            assertEquals(2, edits.get(0).getCoords().size());
            assertFalse(((UiButton) find(ui, "brush-undo")).isDisabled(), "the edit just applied may be undone");
            click(ui, find(ui, "brush-change-ground"));
            settle(ui, gpu);
            click(ui, find(ui, "brush-ground-up"));
            click(ui, find(ui, "brush-ground-up"));
            flushSwing();
            gpu.poll().run();
            ui.draw();
            assertEquals(2, ((UiStepper) find(ui, "brush-ground-level")).value(),
                  "a view coming back while another step is out does not set the stepper back");
            settle(ui, gpu);
            assertEquals(2, ((UiStepper) find(ui, "brush-ground-level")).value());
            onSwing(() -> assertEquals(2, brush[0].ground()));
            click(ui, find(ui, "brush-clear"));
            settle(ui, gpu);
            assertNull(ui.stage.getRoot().findActor("brush-painted-0"), "Clear empties the painted list");
            assertTrue(((UiButton) find(ui, "brush-apply")).isDisabled());
            assertFalse(readOffSwing[0], "the brush reads the board on the Swing thread only");
            click(ui, find(ui, "brush-close"));
            assertTrue(brushClosed[0]);
            assertEquals(List.of(true, false), painting, "closing hands board clicks back");
            assertNull(ui.stage.getRoot().findActor("hex-brush"));
            cards.dispose();
        });
    }

    private static void flushSwing() {
        onSwing(() -> { });
    }

    private static void onSwing(Runnable work) {
        try {
            SwingUtilities.invokeAndWait(work);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Lets the Swing thread finish what the panel sent it, then runs on this GPU thread what it sent back. */
    private static void settle(UiTestStage ui, Queue<Runnable> gpu) {
        flushSwing();
        for (Runnable work = gpu.poll(); work != null; work = gpu.poll()) {
            work.run();
        }
        ui.draw();
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
