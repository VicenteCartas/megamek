/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import megamek.client.lobby.ClientCommandForm.Field;
import megamek.client.lobby.ClientCommandForm.Kind;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.server.commands.KickCommand;
import megamek.server.commands.OrbitalBombardmentCommand;
import megamek.server.commands.arguments.Argument;
import megamek.server.commands.arguments.Arguments;
import megamek.server.commands.arguments.ArgumentsParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The command card's form: the fields a command's arguments ask for, and the chat line it sends. The Kick Player button
 * opens it; on a server with no password the password field is left blank, which the Swing form once sent anyway as
 * "password=", and the server refused it.
 */
class ClientCommandFormTest {

    private static final String KICKED_PLAYER = "2";
    private static final String PASSWORD = "password";

    private Game game;

    @BeforeEach
    void setUp() {
        game = mock(Game.class);
        when(game.getPlayersList()).thenReturn(List.of(new Player(1, "Local"), new Player(2, "Other")));
    }

    /** @return the chat command split the way the server splits it, for parsing */
    private static String[] asTheServerSplitsIt(String chatCommand) {
        return chatCommand.split("\\s+");
    }

    private static Argument<?> argumentNamed(List<Argument<?>> definitions, String argumentName) {
        for (Argument<?> argument : definitions) {
            if (argument.getName().equals(argumentName)) {
                return argument;
            }
        }
        throw new IllegalStateException("KickCommand has no argument named " + argumentName);
    }

    private static Argument<?> kickPasswordArgument() {
        return argumentNamed(new KickCommand(null, null).defineArguments(), PASSWORD);
    }

    @Test
    void aBlankFieldIsLeftOut() {
        Argument<?> password = kickPasswordArgument();

        assertEquals("", ClientCommandForm.namedArgumentUnlessBlank(password, ""));
        assertEquals("", ClientCommandForm.namedArgumentUnlessBlank(password, "   "));
    }

    @Test
    void aFilledFieldIsSentByName() {
        Argument<?> password = kickPasswordArgument();

        assertEquals(PASSWORD + "=" + "letmein", ClientCommandForm.namedArgumentUnlessBlank(password, "letmein"));
    }

    @Test
    void theOldBlankPasswordFormIsWhatTheServerRefused() {
        // pins the cause, so the kick tests below are known to cover the reported failure and not a neighbouring one
        List<Argument<?>> definitions = new KickCommand(null, null).defineArguments();
        String[] oldForm = asTheServerSplitsIt("/kick playerID=" + KICKED_PLAYER + " " + PASSWORD + "=");

        assertThrows(IllegalArgumentException.class, () -> ArgumentsParser.parse(oldForm, definitions));
    }

    @Test
    void theKickFormOffersThePlayersAndASecretPassword() {
        ClientCommandForm form = new ClientCommandForm(new KickCommand(null, null), game, null);

        List<Field> fields = form.fields();
        assertEquals(2, fields.size());
        assertEquals(Kind.CHOICE, fields.get(0).kind());
        assertEquals(List.of("1:Local", "2:Other"), fields.get(0).items());
        assertEquals(Kind.SECRET, fields.get(1).kind());
        assertNull(form.target(), "opened from the menu, the kick names no hex");
        assertTrue(form.title().endsWith("/kick"));
    }

    @Test
    void aKickWithNoPasswordParsesOnTheServer() {
        ClientCommandForm form = new ClientCommandForm(new KickCommand(null, null), game, null);
        form.fields().get(0).choice(1);
        List<String> sent = new ArrayList<>();

        form.execute(sent::add);

        assertEquals(1, sent.size());
        Arguments parsed = ArgumentsParser.parse(asTheServerSplitsIt(sent.get(0)),
              new KickCommand(null, null).defineArguments());
        assertEquals(2, (int) parsed.get("playerID").getValue(), "the chosen player is the one kicked");
        assertTrue(((Optional<?>) parsed.get(PASSWORD).getValue()).isEmpty(),
              "a blank password reaches the server as no password at all");
    }

    @Test
    void aKickWithAPasswordSendsItByName() {
        ClientCommandForm form = new ClientCommandForm(new KickCommand(null, null), game, null);
        form.fields().get(1).text("letmein");

        assertEquals("/kick playerID=1 " + PASSWORD + "=" + "letmein", form.command());
    }

    @Test
    void aCommandOpenedOnAHexSendsThatHexWithoutAskingForIt() {
        ClientCommandForm form = new ClientCommandForm(new OrbitalBombardmentCommand(null, null), game,
              new Coords(4, 7));

        List<Field> fields = form.fields();
        assertTrue(fields.get(0).hidden());
        assertTrue(fields.get(1).hidden());
        assertFalse(fields.get(2).hidden());
        assertEquals("0508", new Coords(4, 7).getBoardNum());
        assertTrue(form.target().contains("0508"));
        assertEquals("/ob x=5 y=8 dmg=100 radius=4", form.command(), "the hex is sent one-based, as typed");
    }

    @Test
    void aNumberStaysWithinTheArgumentsBounds() {
        ClientCommandForm form = new ClientCommandForm(new OrbitalBombardmentCommand(null, null), game, null);
        Field radius = form.fields().get(3);

        assertFalse(form.fields().get(0).hidden(), "opened without a hex, the hex is asked for");
        assertEquals(1, radius.min());
        assertEquals(10, radius.max());
        radius.number(50);
        assertEquals(10, radius.number());
        radius.number(-3);
        assertEquals(1, radius.number());
    }
}
