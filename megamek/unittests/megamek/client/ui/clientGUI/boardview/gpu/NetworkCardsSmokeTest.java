/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.client.Client;
import megamek.client.lobby.NetworkInformationForm;
import megamek.client.lobby.NetworkInformationForm.Kind;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The battle's native Network information and Nova network cards, which replaced their Swing dialogs. */
@Tag("on-demand")
class NetworkCardsSmokeTest {

    /**
     * Network information: the card opens while the addresses are looked up, then hides each found address until its
     * eye shows it; an address not found says so without an eye.
     */
    @Test
    void networkInformationHidesTheAddressesUntilShown() {
        UiTestStage.run(ui -> {
            NetworkInformationForm form = new NetworkInformationForm(() -> "192.168.1.5", () -> null, "localhost");
            LobbyNativeDialogs cards = new LobbyNativeDialogs(ui.kit, ui.stage, false);

            LobbyNetworkInfoView view = cards.networkInformation(form.pending());
            ui.draw();
            UiKit.TokenPassword local = view.field(Kind.LOCAL);
            assertEquals(Messages.getString("NetworkInformation.native.looking"), local.field.getText());
            assertTrue(local.field.isDisabled(), "nothing to show yet");
            assertTrue(local.shown(), "the looking-up text is not hidden");
            UiKit.TokenPassword host = view.field(Kind.HOST);
            assertEquals("localhost", host.field.getText());
            assertFalse(host.shown(), "the host is known at once and hidden");

            view.addresses(form.read());
            ui.draw();
            assertEquals("192.168.1.5", local.field.getText());
            assertFalse(local.field.isDisabled());
            assertFalse(local.shown(), "a found address is hidden");
            assertTrue(local.reveal.isVisible());
            UiKit.TokenPassword publicAddress = view.field(Kind.PUBLIC);
            assertEquals(Kind.PUBLIC.missing(), publicAddress.field.getText());
            assertTrue(publicAddress.field.isDisabled());
            assertTrue(publicAddress.shown());

            click(ui, local.reveal);
            assertTrue(local.shown(), "the eye shows the address");
            view.addresses(form.read());
            assertTrue(local.shown(), "a second lookup keeps a shown address shown");

            click(ui, view.closeButton());
            assertFalse(cards.isOpen());
            assertNull(cards.networkInfoView());
            cards.dispose();
        });
    }

    /** The Nova network view: the local player's units and the allies', each with its link chip and partners. */
    @Test
    void novaNetworksShowsEachUnitsLinks() {
        UiTestStage.run(ui -> {
            Player me = new Player(1, "Me");
            me.setTeam(1);
            Player ally = new Player(2, "Ally");
            ally.setTeam(1);
            Game game = mock(Game.class);
            when(game.getPlayer(1)).thenReturn(me);
            List<Entity> units = List.of(nova(7, "Atlas", me, "Nova.7"), nova(8, "Locust", ally, "Nova.7"),
                  nova(9, "Hunchback", me, null));
            when(game.getEntitiesVector()).thenReturn(units);
            ClientGUI gui = mock(ClientGUI.class);
            Client client = mock(Client.class);
            when(gui.getClient()).thenReturn(client);
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(me);
            LobbyNativeDialogs cards = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            Queue<Runnable> gl = new ConcurrentLinkedQueue<>();
            GpuBattleTools tools = new GpuBattleTools(gui, gl::add, () -> false, () -> cards);

            tools.novaNetworks();
            for (Runnable work = gl.poll(); work != null; work = gl.poll()) {
                work.run();
            }
            ui.draw();
            LobbyNovaNetworkView view = cards.novaNetworkView();
            assertNotNull(view, "the Nova networks show over the battle");
            assertEquals(2, view.form().yours().size());
            assertEquals(1, view.form().allied().size());
            assertNotNull(find(ui, "nova-yours"));
            assertNotNull(find(ui, "nova-allied"));
            assertEquals(Messages.getString("NovaNetworkViewDialog.native.partners",
                        Messages.getString("NovaNetworkViewDialog.native.partner", "Locust", 8)),
                  ((Label) find(ui, "nova-partners-yours-0")).getText().toString());
            assertEquals(Messages.getString("NovaNetworkViewDialog.native.alone"),
                  ((Label) find(ui, "nova-partners-yours-1")).getText().toString());
            assertEquals(Messages.getString("NovaNetworkViewDialog.native.linked", 2),
                  LobbyNovaNetworkView.chipText(view.form().yours().get(0)));
            assertNull(ui.stage.getRoot().findActor("nova-empty"));

            click(ui, view.closeButton());
            assertFalse(cards.isOpen());
            cards.dispose();
        });
    }

    private static Entity nova(int id, String name, Player owner, String network) {
        Entity entity = mock(Entity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getShortName()).thenReturn(name);
        when(entity.getOwner()).thenReturn(owner);
        when(entity.getOwnerId()).thenReturn(owner.getId());
        when(entity.hasNovaCEWS()).thenReturn(true);
        when(entity.getC3NetId()).thenReturn(network);
        return entity;
    }

    private static Actor find(UiTestStage ui, String name) {
        Actor actor = ui.stage.getRoot().findActor(name);
        assertNotNull(actor, name);
        return actor;
    }

    private static void click(UiTestStage ui, Actor actor) {
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
