/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.Team;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.server.commands.ClientServerCommand;
import megamek.server.commands.arguments.Argument;
import megamek.server.commands.arguments.BooleanArgument;
import megamek.server.commands.arguments.CoordXArgument;
import megamek.server.commands.arguments.CoordYArgument;
import megamek.server.commands.arguments.EnumArgument;
import megamek.server.commands.arguments.IntegerArgument;
import megamek.server.commands.arguments.OptionalEnumArgument;
import megamek.server.commands.arguments.OptionalIntegerArgument;
import megamek.server.commands.arguments.OptionalPasswordArgument;
import megamek.server.commands.arguments.OptionalStringArgument;
import megamek.server.commands.arguments.PlayerArgument;
import megamek.server.commands.arguments.StringArgument;
import megamek.server.commands.arguments.TeamArgument;
import megamek.server.commands.arguments.UnitArgument;

/**
 * A server command as its card fills it in: one field per argument the command defines, in its order, and the chat
 * line that runs it, each argument as {@code name=value} as the Swing command dialog sent it.
 *
 * <p>A command opened on a hex keeps that hex's coordinates as hidden fields: the hex is named at the top of the card
 * and sent with the command, but not asked for again.</p>
 */
public final class ClientCommandForm {
    /** Board coordinates are shown and sent one-based. */
    private static final int FIRST_BOARD_COORDINATE = 1;
    private static final int LAST_BOARD_COORDINATE = 1_000_000;
    /** The optional enum's choice for no value. */
    static final String NO_CHOICE = "-";

    /** How a field asks for its argument. */
    public enum Kind {
        NUMBER, CHOICE, TEXT, SECRET, FLAG
    }

    /** One argument's field: its current value, and for a choice the items shown and the values they send. */
    public static final class Field {
        private final Argument<?> argument;
        private final Kind kind;
        private final boolean hidden;
        private final int min;
        private final int max;
        private final List<String> items;
        private final List<String> values;
        private int number;
        private int choice;
        private String text = "";
        private boolean flag;

        private Field(Argument<?> argument, Kind kind, boolean hidden, int min, int max, List<String> items,
              List<String> values) {
            this.argument = argument;
            this.kind = kind;
            this.hidden = hidden;
            this.min = min;
            this.max = Math.max(min, max);
            this.items = List.copyOf(items);
            this.values = List.copyOf(values);
        }

        private static Field number(Argument<?> argument, boolean hidden, int value, int min, int max) {
            Field field = new Field(argument, Kind.NUMBER, hidden, min, max, List.of(), List.of());
            field.number(value);
            return field;
        }

        private static Field choice(Argument<?> argument, List<String> items, List<String> values, int selected) {
            Field field = new Field(argument, Kind.CHOICE, false, 0, 0, items, values);
            field.choice(selected);
            return field;
        }

        private static Field text(Argument<?> argument, Kind kind, String value) {
            Field field = new Field(argument, kind, false, 0, 0, List.of(), List.of());
            field.text(value);
            return field;
        }

        private static Field flag(Argument<?> argument, boolean value) {
            Field field = new Field(argument, Kind.FLAG, false, 0, 0, List.of(), List.of());
            field.flag(value);
            return field;
        }

        public String name() {
            return argument.getName();
        }

        public String help() {
            return argument.getHelp();
        }

        public Kind kind() {
            return kind;
        }

        /** @return whether the field holds the hex the command was opened on, which the card names but does not ask */
        public boolean hidden() {
            return hidden;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        public int number() {
            return number;
        }

        public void number(int value) {
            number = Math.max(min, Math.min(max, value));
        }

        /** @return what a choice shows, in order */
        public List<String> items() {
            return items;
        }

        /** @return the index of the chosen item, or -1 when a choice has no items */
        public int choice() {
            return choice;
        }

        public void choice(int index) {
            choice = items.isEmpty() ? -1 : Math.max(0, Math.min(items.size() - 1, index));
        }

        public String text() {
            return text;
        }

        public void text(String value) {
            text = value == null ? "" : value;
        }

        public boolean flag() {
            return flag;
        }

        public void flag(boolean value) {
            flag = value;
        }

        /** @return the field as the command line takes it: {@code name=value}, or nothing for a value left out */
        String argument() {
            return switch (kind) {
                case NUMBER -> name() + "=" + number;
                case FLAG -> name() + "=" + flag;
                case TEXT, SECRET -> namedArgumentUnlessBlank(argument, text);
                case CHOICE -> {
                    String value = choice < 0 ? "" : values.get(choice);
                    yield value.isEmpty() ? "" : name() + "=" + value;
                }
            };
        }
    }

    private final ClientServerCommand command;
    private final Coords coords;
    private final List<Field> fields = new ArrayList<>();

    /**
     * @param command the command to fill in
     * @param game    the game, for the players, units and teams a choice offers
     * @param coords  the hex the command was opened on, or null when it was opened without one
     */
    public ClientCommandForm(ClientServerCommand command, Game game, @Nullable Coords coords) {
        this.command = command;
        this.coords = coords;
        for (Argument<?> argument : command.defineArguments()) {
            Field field = fieldFor(argument, game);
            if (field != null) {
                fields.add(field);
            }
        }
    }

    private @Nullable Field fieldFor(Argument<?> argument, Game game) {
        if (argument instanceof CoordXArgument) {
            return Field.number(argument, coords != null, coords == null ? FIRST_BOARD_COORDINATE : coords.getX() + 1,
                  FIRST_BOARD_COORDINATE, LAST_BOARD_COORDINATE);
        } else if (argument instanceof CoordYArgument) {
            return Field.number(argument, coords != null, coords == null ? FIRST_BOARD_COORDINATE : coords.getY() + 1,
                  FIRST_BOARD_COORDINATE, LAST_BOARD_COORDINATE);
        } else if (argument instanceof PlayerArgument) {
            List<String> items = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (Player player : game.getPlayersList()) {
                items.add(player.getId() + ":" + player.getName());
                values.add(String.valueOf(player.getId()));
            }
            return Field.choice(argument, items, values, 0);
        } else if (argument instanceof UnitArgument) {
            return unitField(argument, game);
        } else if (argument instanceof TeamArgument) {
            List<String> items = new ArrayList<>();
            for (Team team : game.getTeams()) {
                items.add(String.valueOf(team.getId()));
            }
            return Field.choice(argument, items, items, 0);
        } else if (argument instanceof IntegerArgument integer) {
            return Field.number(argument, false, integer.hasDefaultValue() ? integer.getValue() : 0,
                  integer.getMinValue(), integer.getMaxValue());
        } else if (argument instanceof OptionalIntegerArgument integer) {
            return Field.number(argument, false, Math.max(integer.getMinValue(), 0), integer.getMinValue(),
                  integer.getMaxValue());
        } else if (argument instanceof OptionalEnumArgument<?> optional) {
            List<String> items = new ArrayList<>();
            List<String> values = new ArrayList<>();
            int selected = 0;
            if (optional.getValue() == null) {
                items.add(NO_CHOICE);
                values.add("");
            }
            for (Enum<?> constant : optional.getEnumType().getEnumConstants()) {
                if (constant == optional.getValue()) {
                    selected = items.size();
                }
                items.add(constant.ordinal() + ": " + constant);
                values.add(String.valueOf(constant.ordinal()));
            }
            return Field.choice(argument, items, values, selected);
        } else if (argument instanceof EnumArgument<?> enumArgument) {
            List<String> items = new ArrayList<>();
            int selected = 0;
            for (Enum<?> constant : enumArgument.getEnumType().getEnumConstants()) {
                if (constant == enumArgument.getValue()) {
                    selected = items.size();
                }
                items.add(constant.name());
            }
            return Field.choice(argument, items, items, selected);
        } else if (argument instanceof OptionalPasswordArgument) {
            return Field.text(argument, Kind.SECRET, "");
        } else if (argument instanceof StringArgument string) {
            return Field.text(argument, Kind.TEXT, string.hasDefaultValue() ? string.getValue() : "");
        } else if (argument instanceof OptionalStringArgument string) {
            return Field.text(argument, Kind.TEXT, string.getValue().orElse(""));
        } else if (argument instanceof BooleanArgument flag) {
            return Field.flag(argument, flag.hasDefaultValue() && flag.getValue());
        }
        return null;
    }

    /** Every unit in the game; opened on a hex, the first unit standing there starts chosen. */
    private Field unitField(Argument<?> argument, Game game) {
        List<String> items = new ArrayList<>();
        List<String> values = new ArrayList<>();
        int selected = 0;
        Entity standing = null;
        if (coords != null) {
            Iterator<Entity> there = game.getEntities(coords);
            standing = there.hasNext() ? there.next() : null;
        }
        for (Entity entity : game.getEntitiesVector()) {
            if (standing != null && entity.getId() == standing.getId()) {
                selected = items.size();
            }
            items.add(entity.getId() + ":" + entity.getDisplayName());
            values.add(String.valueOf(entity.getId()));
        }
        return Field.choice(argument, items, values, selected);
    }

    /** @return the card's title: the command's long name and its chat name */
    public String title() {
        return command.getLongName() + " /" + command.getName();
    }

    /** @return the line naming the hex the command acts on, or null when it was opened without one */
    public @Nullable String target() {
        return coords == null ? null : Messages.getString("Gamemaster.cmd.targetHex", coords.getBoardNum());
    }

    /** @return the command's help, as HTML */
    public String helpHtml() {
        return command.getHelpHtml();
    }

    /** @return the fields, in the command's order, hidden ones included */
    public List<Field> fields() {
        return List.copyOf(fields);
    }

    /** @return the chat line that runs the command with the fields' values */
    public String command() {
        List<String> arguments = new ArrayList<>();
        for (Field field : fields) {
            arguments.add(field.argument());
        }
        return "/" + command.getName() + " " + String.join(" ", arguments);
    }

    /** Sends the command through {@code chat}, as the server reads a typed one. */
    public void execute(Consumer<String> chat) {
        chat.accept(command());
    }

    /**
     * Builds the "argumentName=argumentValue" form of a typed-in argument, or nothing when the field was left blank.
     *
     * <p>A blank field must be left out rather than sent as "argumentName=": the server splits each argument on "=",
     * and a name with nothing after the "=" breaks the parse, which the server then refuses.</p>
     *
     * @param argument The argument the field belongs to
     * @param value    What was typed into the field
     *
     * @return The named argument, or an empty string when the field was blank
     */
    public static String namedArgumentUnlessBlank(Argument<?> argument, String value) {
        if (value.isBlank()) {
            return "";
        }
        return argument.getName() + "=" + value;
    }
}
