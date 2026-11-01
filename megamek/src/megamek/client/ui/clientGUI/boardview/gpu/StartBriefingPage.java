/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.ImageIcon;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartScreen.Page;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Role;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.ScenarioRequest;
import megamek.client.ui.dialogs.buttonDialogs.GameOptionsDialog;
import megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog;
import megamek.client.ui.dialogs.iconChooser.CamoChooserDialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.game.Game;
import megamek.common.game.IGame;
import megamek.common.icons.Camouflage;
import megamek.common.interfaces.PlanetaryConditionsUsing;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.scenario.Scenario;
import megamek.common.scenario.ScenarioLoader;

/** The briefing: a scenario's description, who plays each side, its rules and conditions, and Start. */
final class StartBriefingPage {
    /** A camouflage swatch on the briefing, about a hex's proportions. */
    private static final int SWATCH_WIDTH = 28;
    private static final int SWATCH_HEIGHT = 24;

    /** A scenario read and its game made for the briefing, with each player's camouflage swatch, or why that failed. */
    record Briefing(Scenario scenario, IGame game, List<BufferedImage> swatches, String error) { }

    private final GpuStartScreen screen;
    private final StartServerInputs inputs;
    /** The briefing's scenario file, the page Back returns to, and the briefing once it's made. */
    private File briefingFile;
    Page briefingBack = Page.SCENARIOS;
    Briefing briefing;
    private Table briefingHeader;
    private ScrollPane briefingText;
    /** The briefing's map and its caption, or null when its game has no board to draw. */
    private StartMapFrame briefingMap;
    private Table playerRows;
    private final List<UiSelect> roleSelects = new ArrayList<>();
    /** Each player's camouflage swatch on the briefing, and the textures they show. */
    private final List<Image> swatches = new ArrayList<>();
    private final List<Texture> swatchTextures = new ArrayList<>();
    /** The scenario's own value of each rule, so the briefing can count the ones the player changed. */
    private final Map<String, Object> ruleBaseline = new HashMap<>();
    private Table rulesLine;
    private Label rulesValue;
    private Table conditionsLine;
    private Label conditionsValue;
    private UiButton startButton;

    StartBriefingPage(GpuStartScreen screen) {
        this.screen = screen;
        this.inputs = screen.inputs;
    }

    /** Opens the briefing for the scenario in {@code file}; Back returns to {@code back}. */
    void openBriefing(File file, Page back) {
        briefingFile = file;
        briefingBack = back;
        briefing = null;
        screen.showPage(Page.BRIEFING);
    }

    /** Reads the scenario and makes its game off the GPU thread, then shows the briefing. */
    void showBriefing() {
        File file = briefingFile;
        screen.setColumn(List.of(screen.overline("StartScreen.briefing"),
              screen.note(Messages.getString("StartScreen.preparingBriefing"), "text.muted"),
              screen.backButton(briefingBack)));
        screen.background("briefing", () -> prepare(file), made -> {
            briefing = made;
            if (made.error() == null) {
                buildBriefing();
            }
            fillBriefingColumn();
            screen.stage.setKeyboardFocus(startButton);
        });
    }

    /** Blocks: reads {@code file}'s scenario and makes its game, or says why it couldn't. */
    private static Briefing prepare(File file) {
        try {
            Scenario scenario = new ScenarioLoader(file).load();
            IGame game = scenario.createGame();
            List<Player> players = game.getPlayersList();
            colourSides(players);
            return new Briefing(scenario, game, players.stream().map(StartBriefingPage::swatch).toList(), null);
        } catch (Exception ex) {
            GpuStartScreen.LOGGER.error(ex, "The start screen could not prepare the scenario {}", file);
            return new Briefing(null, null, List.of(), Messages.getString("StartScreen.briefingFailed",
                  file.getName(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    /**
     * As the old players dialog did for multiplayer, the sides take MegaMek's player colours in order, in single player
     * too; a side still in the default blue camouflage wears its colour's, so the briefing's swatches, the map's
     * deployment zones and the units in the game tell the sides apart.
     */
    static void colourSides(List<Player> players) {
        PlayerColour[] colours = PlayerColour.values();
        Camouflage untouched = Camouflage.of(PlayerColour.BLUE);
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            PlayerColour colour = colours[i % colours.length];
            player.setColour(colour);
            if (untouched.equals(player.getCamouflage())) {
                player.setCamouflage(Camouflage.of(colour));
            }
        }
    }

    /** Blocks: a side's camouflage as the briefing shows it, a small rounded patch of it, or of its colour. */
    private static BufferedImage swatch(Player player) {
        java.awt.Image camo = null;
        try {
            camo = player.getCamouflage().getBaseImage();
        } catch (RuntimeException ex) {
            GpuStartScreen.LOGGER.warn("No camouflage image for {}: {}", player.getName(), ex.getMessage());
        }
        if (camo != null) {
            // Waits for an image the toolkit loads in the background
            camo = new ImageIcon(camo).getImage();
        }
        int width = SWATCH_WIDTH * 2;
        int height = SWATCH_HEIGHT * 2;
        BufferedImage swatch = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = swatch.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setClip(new RoundRectangle2D.Float(0, 0, width, height, 10, 10));
        if (camo != null && camo.getWidth(null) > 0) {
            g.drawImage(camo, 0, 0, width, height, null);
        } else {
            g.setColor(player.getDisplayColour().getColour());
            g.fillRect(0, 0, width, height);
        }
        g.dispose();
        return swatch;
    }

    /**
     * The briefing's parts: its name, where and how it's played, its description, and who plays each side. In a
     * single-player scenario the host takes the first side and bots the rest; in a multiplayer one each side is the
     * host's, another person's or a bot's, and the host names the game's password and port.
     */
    private void buildBriefing() {
        Scenario scenario = briefing.scenario();
        StartScenarios.Entry entry = new StartScenarios.Entry("", scenario);
        briefingHeader = new Table();
        briefingHeader.left().top();
        briefingHeader.defaults().left().growX().minWidth(0);
        Label title = screen.kit.roleTypeLabel("title", entry.name(), "text.primary");
        title.setEllipsis(true);
        briefingHeader.add(title).row();
        List<String> about = new ArrayList<>();
        String planet = scenario.getPlanet();
        if (planet != null && !planet.isBlank()) {
            about.add(planet.trim());
        }
        about.add(Messages.getString(scenario.isSinglePlayer() ? "StartScreen.singlePlayer"
              : "StartScreen.multiplayer"));
        briefingHeader.add(screen.line(String.join("  \u00b7  ", about), "small", "text.muted")).padTop(2);

        disposeBriefingMap();
        Board board = briefing.game().getBoard();
        if (board != null && board.getWidth() > 0 && board.getHeight() > 0) {
            briefingMap = new StartMapFrame(screen.boardSkin.skin.getDrawable(UiTheme.TOKEN_PANEL_PLAIN),
                  screen.kit.typeLabel(StartMapThumbnail.caption(briefing.game(), scenario), "small", "text.muted"),
                  screen.motion);
            bakeMap();
        }

        String description = scenario.getDescription();
        briefingText = null;
        if (description != null && !description.isBlank()) {
            Label text = screen.kit.typeLabel(description.trim(), "body", "text.secondary");
            text.setWrap(true);
            text.setAlignment(Align.topLeft);
            briefingText = screen.kit.scrollList(text);
        }

        roleSelects.clear();
        disposeSwatches();
        playerRows = new Table();
        playerRows.left().top();
        playerRows.defaults().left().padBottom(4);
        List<Player> players = briefing.game().getPlayersList();
        String[] roles = { Messages.getString("StartScreen.you"), Messages.getString("StartScreen.human"),
                           Messages.getString("StartScreen.bot") };
        for (int i = 0; i < players.size(); i++) {
            playerRows.add(swatchButton(i)).padRight(8);
            playerRows.add(screen.line(players.get(i).getName(), "body", "text.primary")).growX().minWidth(0);
            if (scenario.isSinglePlayer()) {
                playerRows.add(screen.kit.typeLabel(roles[i == 0 ? 0 : 2], "body", "text.muted"))
                      .width(GpuStartScreen.ROLE_WIDTH)
                      .padLeft(12);
            } else {
                // Most multiplayer games are played against bots: the host takes the first side, bots the rest
                UiSelect role = screen.kit.tokenSelect(true, roles);
                role.select(i == 0 ? Role.YOU.ordinal() : Role.BOT.ordinal());
                UiKit.onChange(role, () -> roleChosen(role));
                roleSelects.add(role);
                playerRows.add(role).width(GpuStartScreen.ROLE_WIDTH).padLeft(12);
            }
            playerRows.row();
        }

        ruleBaseline.clear();
        if (briefing.game() instanceof Game twGame) {
            twGame.getOptions().getOptionsList().forEach(option -> ruleBaseline.put(option.getName(),
                  option.getValue()));
        }
        rulesValue = screen.kit.typeLabel("", "body", "text.primary");
        rulesLine = settingLine("StartScreen.rules", rulesValue,
              scenario.hasFixedGameOptions() || !(briefing.game() instanceof Game) ? null : this::changeRules);
        conditionsValue = screen.kit.typeLabel("", "body", "text.primary");
        conditionsLine = briefing.game() instanceof PlanetaryConditionsUsing
              ? settingLine("StartScreen.conditions", conditionsValue,
                    scenario.hasFixedPlanetaryConditions() ? null : this::changeConditions)
              : null;
        describeSettings();

        ClientPreferences client = PreferenceManager.getClientPreferences();
        inputs.nameInput = inputs.field(client.getLastPlayerName());
        inputs.nameField = screen.kit.formField(Messages.getString("StartScreen.observerName"), inputs.nameInput)
              .helper(Messages.getString("StartScreen.observerHelper"));
        inputs.buildServerInputs(client.getLastServerPass(), client.getLastServerPort());
        startButton = screen.kit.button("button-primary", null, Messages.getString("StartScreen.startScenario"), null);
        GpuStartScreen.leftAligned(startButton);
        UiKit.onChange(startButton, this::submitBriefing);
    }

    /** A side's camouflage swatch; a click chooses another camouflage for it. */
    private UiButton swatchButton(int index) {
        Texture texture = GpuStartScreen.texture(briefing.swatches().get(index), false);
        swatchTextures.add(texture);
        Image image = new Image(texture);
        swatches.add(image);
        UiButton button = screen.kit.button("button-quiet-compact", null, null, null);
        button.add(image).size(SWATCH_WIDTH, SWATCH_HEIGHT);
        UiKit.onChange(button, () -> chooseCamo(index));
        return button;
    }

    /** Opens the camouflage chooser for side {@code index}, then shows what was chosen. */
    private void chooseCamo(int index) {
        Player player = briefing.game().getPlayersList().get(index);
        Briefing asked = briefing;
        screen.window.onSwing(frame -> {
            CamoChooserDialog dialog = new CamoChooserDialog(frame, player.getCamouflage());
            try {
                if (!dialog.showDialog().isConfirmed()) {
                    return null;
                }
                player.setCamouflage(dialog.getSelectedItem());
                return swatch(player);
            } finally {
                dialog.dispose();
            }
        }, chosen -> {
            if (chosen == null || briefing != asked || screen.page != Page.BRIEFING) {
                return;
            }
            Texture texture = GpuStartScreen.texture(chosen, false);
            swatchTextures.set(index, texture).dispose();
            swatches.get(index).setDrawable(new TextureRegionDrawable(texture));
        });
    }

    void disposeSwatches() {
        swatchTextures.forEach(Texture::dispose);
        swatchTextures.clear();
        swatches.clear();
    }

    /** Draws the briefing's map off the GPU thread, then fades it into its frame, which already holds its room. */
    private void bakeMap() {
        Briefing asked = briefing;
        StartMapFrame frame = briefingMap;
        screen.background("map", () -> {
            try {
                return StartMapThumbnail.bake(asked.game().getBoard(), asked.game().getPlayersList(),
                      StartMapThumbnail.LONG_SIDE);
            } catch (RuntimeException ex) {
                GpuStartScreen.LOGGER.warn(ex, "The briefing could not draw the map of {}", briefingFile);
                return null;
            }
        }, image -> {
            if (image == null || briefing != asked || briefingMap != frame) {
                return;
            }
            Texture texture = GpuStartScreen.texture(image, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            frame.setMap(texture);
        });
    }

    void disposeBriefingMap() {
        if (briefingMap != null) {
            briefingMap.remove();
            briefingMap.dispose();
            briefingMap = null;
        }
    }

    /**
     * A line of the briefing's settings: its name, what it holds, and a Change button when the scenario leaves it to
     * the player, or that the scenario sets it when it doesn't.
     */
    private Table settingLine(String key, Label value, Runnable change) {
        Table line = new Table();
        line.left();
        line.add(screen.kit.typeLabel(Messages.getString(key), "body", "text.muted"))
              .width(GpuStartScreen.ROLE_WIDTH - 30).left();
        value.setEllipsis(true);
        line.add(value).growX().minWidth(0).left();
        if (change != null) {
            UiButton button = screen.kit.button("button-quiet-compact", null, Messages.getString("StartScreen.change"),
                  null);
            UiKit.onChange(button, change);
            line.add(button).padLeft(8);
        }
        return line;
    }

    /** Fills the Rules and Conditions lines from the briefing's game. */
    private void describeSettings() {
        Scenario scenario = briefing.scenario();
        if (scenario.hasFixedGameOptions() || !(briefing.game() instanceof Game twGame)) {
            rulesValue.setText(Messages.getString("StartScreen.setByScenario"));
        } else {
            long changed = twGame.getOptions().getOptionsList().stream()
                  .filter(option -> !Objects.equals(option.getValue(), ruleBaseline.get(option.getName())))
                  .count();
            rulesValue.setText(changed == 0 ? Messages.getString("StartScreen.rulesDefault")
                  : changed == 1 ? Messages.getString("StartScreen.rulesChangedOne")
                  : Messages.getString("StartScreen.rulesChanged", changed));
        }
        if (conditionsLine != null) {
            conditionsValue.setText(scenario.hasFixedPlanetaryConditions()
                  ? Messages.getString("StartScreen.setByScenario")
                  : conditions(((PlanetaryConditionsUsing) briefing.game()).getPlanetaryConditions()));
        }
    }

    /** The conditions in a few words: the light, and whatever else isn't the usual. */
    static String conditions(PlanetaryConditions conditions) {
        List<String> parts = new ArrayList<>();
        parts.add(conditions.getLight().toString());
        if (!conditions.getWeather().isClear()) {
            parts.add(conditions.getWeather().toString());
        }
        if (!conditions.getWind().isCalm()) {
            parts.add(conditions.getWind().toString());
        }
        if (!conditions.getFog().isFogNone()) {
            parts.add(conditions.getFog().toString());
        }
        if (conditions.getTemperature() != 25) {
            parts.add(Messages.getString("StartScreen.temperature", conditions.getTemperature()));
        }
        if (conditions.getGravity() != 1) {
            parts.add(Messages.getString("StartScreen.gravity", conditions.getGravity()));
        }
        return String.join(", ", parts);
    }

    /** Opens the game options the scenario leaves open; confirming them sets them on the briefing's game. */
    private void changeRules() {
        Briefing asked = briefing;
        Game game = (Game) briefing.game();
        Set<String> locked = briefing.scenario().lockedGameOptions();
        screen.window.onSwing(frame -> {
            GameOptionsDialog dialog = new GameOptionsDialog(frame, game.getOptions(), false);
            try {
                dialog.update(game.getOptions());
                dialog.setEditable(true);
                // The options the scenario calls its mission stay as written; the rest are the player's
                dialog.lockOptions(locked);
                return dialog.showDialog().isConfirmed() ? List.copyOf(dialog.getOptions()) : null;
            } finally {
                dialog.dispose();
            }
        }, chosen -> {
            if (chosen == null || briefing != asked) {
                return;
            }
            for (IBasicOption option : chosen) {
                IOption rule = game.getOptions().getOption(option.getName());
                if (rule != null) {
                    rule.setValue(option.getValue());
                }
            }
            describeSettings();
        });
    }

    /** Opens the planetary conditions; confirming them sets them on the briefing's game. */
    private void changeConditions() {
        Briefing asked = briefing;
        PlanetaryConditionsUsing game = (PlanetaryConditionsUsing) briefing.game();
        PlanetaryConditions current = game.getPlanetaryConditions();
        screen.window.onSwing(frame -> {
            PlanetaryConditionsDialog dialog = new PlanetaryConditionsDialog(frame, current);
            try {
                return dialog.showDialog() ? dialog.getConditions() : null;
            } finally {
                dialog.dispose();
            }
        }, chosen -> {
            if (chosen == null || briefing != asked) {
                return;
            }
            game.setPlanetaryConditions(chosen);
            describeSettings();
        });
    }

    /** A side became {@code chosen}'s choice; the host takes one side at most, so You moves rather than copies. */
    private void roleChosen(UiSelect chosen) {
        if (chosen.getSelectedIndex() == Role.YOU.ordinal()) {
            for (UiSelect other : roleSelects) {
                if (other != chosen && other.getSelectedIndex() == Role.YOU.ordinal()) {
                    other.select(Role.HUMAN.ordinal());
                }
            }
        }
        fillBriefingColumn();
    }

    private void fillBriefingColumn() {
        List<Actor> items = new ArrayList<>();
        items.add(screen.overline("StartScreen.briefing"));
        if (briefing.error() != null) {
            items.add(screen.note(briefing.error(), "status.danger"));
        } else {
            items.add(briefingHeader);
            if (briefingMap != null) {
                items.add(briefingMap);
            }
            if (briefingText != null) {
                items.add(briefingText);
            }
            items.add(screen.overline("StartScreen.players"));
            items.add(playerRows);
            if (!briefing.scenario().isSinglePlayer()) {
                List<Role> roles = roles();
                if (!roles.contains(Role.YOU)) {
                    items.add(inputs.nameField);
                }
                // Only another person needs the password and port to join
                if (roles.contains(Role.HUMAN)) {
                    items.add(inputs.passwordRow);
                }
            }
            items.add(rulesLine);
            if (conditionsLine != null) {
                items.add(conditionsLine);
            }
            items.add(startButton);
        }
        items.add(screen.backButton(briefingBack));
        if (!screen.column.isEmpty() && screen.column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        screen.setColumn(items);
    }

    /** Who plays each of the scenario's sides, as the briefing shows it. */
    private List<Role> roles() {
        int count = briefing.game().getPlayersList().size();
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (briefing.scenario().isSinglePlayer()) {
                roles.add(i == 0 ? Role.YOU : Role.BOT);
            } else {
                roles.add(Role.values()[roleSelects.get(i).getSelectedIndex()]);
            }
        }
        return roles;
    }

    /** Whether the briefing has loaded and its Start button may be pressed. */
    boolean canStart() {
        return startButton != null && !startButton.isDisabled();
    }

    /** Checks the briefing; asks MegaMek to host the scenario with its sides as chosen. */
    void submitBriefing() {
        if (!canStart()) {
            return;
        }
        List<Role> roles = roles();
        boolean single = briefing.scenario().isSinglePlayer();
        // Without another person to join, the game takes the default port and no password, as single player does
        boolean online = !single && roles.contains(Role.HUMAN);
        int you = roles.indexOf(Role.YOU);
        String name = you >= 0 ? briefing.game().getPlayersList().get(you).getName()
              : inputs.nameInput.getText().trim();
        int port = online ? StartHostForm.port(inputs.portInput.getText()) : MMConstants.DEFAULT_PORT;
        if (you < 0) {
            inputs.nameField.error(name.isEmpty() ? Messages.getString("MegaMek.GameConnection.nameError") : null);
        }
        if (online) {
            inputs.portField.error(StartServerInputs.portError(port));
        }
        if (name.isEmpty()) {
            screen.stage.setKeyboardFocus(inputs.nameInput);
            return;
        }
        if (port < 0) {
            screen.stage.setKeyboardFocus(inputs.portInput);
            return;
        }
        startButton.setDisabled(true);
        startButton.setText(screen.kit.cased("button", Messages.getString("StartScreen.starting")));
        screen.window.scenario(new ScenarioRequest(briefing.scenario(), briefing.game(), roles, name,
              online ? inputs.passwordInput.getText() : "", port));
    }

    /**
     * The scenario didn't start; MegaMek has said why. Start works again, once the briefing has made its game again when
     * {@code gameUsed}, keeping the sides' camouflage, the rules and the conditions the player chose.
     */
    void startFailed(boolean gameUsed) {
        if (screen.page != Page.BRIEFING || briefing == null || briefing.error() != null) {
            return;
        }
        if (!gameUsed) {
            enableStart();
            return;
        }
        Briefing used = briefing;
        screen.background("briefing", () -> prepare(briefingFile), made -> {
            if (made.error() != null) {
                briefing = made;
                fillBriefingColumn();
                return;
            }
            List<Player> before = used.game().getPlayersList();
            List<Player> after = made.game().getPlayersList();
            for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
                after.get(i).setCamouflage(before.get(i).getCamouflage());
            }
            if (used.game() instanceof Game oldGame && made.game() instanceof Game newGame) {
                for (IOption option : oldGame.getOptions().getOptionsList()) {
                    IOption rule = newGame.getOptions().getOption(option.getName());
                    if (rule != null) {
                        rule.setValue(option.getValue());
                    }
                }
            }
            if (used.game() instanceof PlanetaryConditionsUsing oldConditions
                  && made.game() instanceof PlanetaryConditionsUsing newConditions) {
                newConditions.setPlanetaryConditions(
                      (PlanetaryConditions) oldConditions.getPlanetaryConditions().clone());
            }
            briefing = new Briefing(made.scenario(), made.game(), used.swatches(), null);
            if (briefingMap != null) {
                // A random map is rolled again with the game; the frame keeps its room while the new one fades in
                briefingMap.setCaption(StartMapThumbnail.caption(made.game(), made.scenario()));
                bakeMap();
            }
            enableStart();
        });
    }

    private void enableStart() {
        startButton.setDisabled(false);
        startButton.setText(screen.kit.cased("button", Messages.getString("StartScreen.startScenario")));
    }
}
