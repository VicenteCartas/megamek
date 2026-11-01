/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.generator.enums.SkillGeneratorType;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.lobby.PlayerSettingsForm.Field;
import megamek.client.lobby.PlayerSettingsForm.GroundObject;
import megamek.client.lobby.PlayerSettingsForm.Position;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.containers.MunitionTree;
import megamek.common.enums.SkillLevel;

/**
 * The lobby's player settings in a {@link UiDialog}, the sections of the Swing {@code PlayerSettingsDialog} in its
 * order: the player and their camo; the ammunition auto-configuration (faction, Autoconfig, Randomize, Restore, the
 * randomizer's options, and saving and loading a loadout); the bot's settings for a bot; the deployment area (the
 * grid of start positions with the other players deploying at each, the offset and width, and the custom area for
 * "Any" with its Apply); the initiative modifier; the minefields where the rules allow them; fortified hexes; the
 * carryable ground objects; the skill rolling method; and the round report email for a human player. The values are
 * held by a {@link PlayerSettingsForm}, which checks the numbers: OK and Apply stay disabled while one is wrong.
 * <p>
 * GPU thread. Whatever reads or changes the game runs on the Swing thread through {@link Host#edt}.
 */
final class LobbyPlayerSettingsView {
    /** What the view asks of the window, on the Swing thread. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Asks for a file and saves {@code tree} to it; {@code then} runs back on the GPU thread once done. */
        void saveLoadout(MunitionTree tree, Runnable then);

        /**
         * Asks for a loadout file and reads it; {@code then} gets the loadout back on the GPU thread, null when none
         * was chosen or it could not be read.
         */
        void loadLoadout(Consumer<MunitionTree> then);

        /** Opens the bot's own settings. */
        void botSettings();
    }

    private final UiKit kit;
    private final UiDialog dialog;
    private final PlayerSettingsForm form;
    private final List<Position> positions;
    private final Host host;
    private final Runnable close;
    private final Map<Field, UiFormField> fields = new EnumMap<>(Field.class);
    private final List<UiButton> positionButtons = new ArrayList<>();
    private final Table groundList = new Table();
    private final UiButton ok;
    private UiButton applyButton;
    private UiButton autoconfig;
    private UiButton randomize;
    private UiButton restore;
    private Label widthNote;
    private Texture camoTexture;
    private boolean busy;

    /**
     * Fills {@code dialog}'s body and footer.
     *
     * @param positions the start positions, as {@link PlayerSettingsForm#positions} read them on the Swing thread
     * @param camo      the player's camo picture, or null
     * @param close     closes the dialog
     */
    LobbyPlayerSettingsView(UiKit kit, UiDialog dialog, PlayerSettingsForm form, List<Position> positions,
          @Nullable BufferedImage camo, Host host, Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.form = form;
        this.positions = List.copyOf(positions);
        this.host = host;
        this.close = close;
        Table body = dialog.body();
        body.defaults().left().growX();

        Table header = new Table();
        header.left();
        if (camo != null) {
            camoTexture = GpuStartScreen.texture(camo, true);
            camoTexture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            header.add(new Image(new TextureRegionDrawable(camoTexture), Scaling.fit)).size(size("icon.lg") * 1.5f)
                  .padRight(space("md"));
        }
        header.add(kit.typeLabel(form.player().getName(), "title", "text.primary")).left();
        body.add(header).row();

        section("PlayerSettingsDialog.header.autoConfig", autoConfig());
        if (form.bot()) {
            UiButton bot = kit.button("button-secondary", null, Messages.getString("PlayerSettingsDialog.botSettings"),
                  null);
            bot.setName("settings-bot");
            UiKit.onChange(bot, host::botSettings);
            Table row = new Table();
            row.left();
            row.add(bot);
            section("PlayerSettingsDialog.header.botPlayer", row);
        }
        section("PlayerSettingsDialog.header.startPos", deployment());
        section("PlayerSettingsDialog.header.initMod", pair(field(Field.INIT, "PlayerSettingsDialog.initMod",
              "PlayerSettingsDialog.initModTT"), null));
        if (form.minefields()) {
            section("PlayerSettingsDialog.header.minefields", minefields());
        }
        section("PlayerSettingsDialog.header.fortifications", pair(field(Field.FORTIFIED,
              "PlayerSettingsDialog.labFortifiedHexes", "PlayerSettingsDialog.fortifiedHexesTT"), null));
        section("PlayerSettingsDialog.header.GroundObjects", groundObjects());
        section("PlayerSettingsDialog.header.skills", skills());
        if (!form.bot()) {
            UiTextField email = kit.input(form.email(), true);
            email.setName("settings-email");
            UiKit.onChange(email, () -> form.email(email.getText()));
            section("PlayerSettingsDialog.header.email", pair(kit.formField(
                  Messages.getString("PlayerSettingsDialog.labEmail"), email), null));
        }

        ok = dialog.primary(Messages.getString("Ok.text"), this::confirm);
        ok.setName("settings-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("settings-cancel");
        refresh();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private void section(String key, Table content) {
        Table body = dialog.body();
        body.add(kit.caption(Messages.getString(key), true)).padTop(space("xl")).row();
        body.add(content).padTop(space("sm")).row();
    }

    /** Two cells side by side, each half the width; null leaves the second empty. */
    private static Table pair(Table first, @Nullable Table second) {
        Table row = new Table();
        row.top().left();
        row.add(first).growX().uniformX().top();
        if (second != null) {
            row.add(second).growX().uniformX().top().padLeft(space("md"));
        } else {
            row.add().growX().uniformX();
        }
        return row;
    }

    /** A whole-number field of the form: its label without the colon, and its error under it. */
    private UiFormField field(Field field, String label, @Nullable String tip) {
        UiTextField input = kit.input(form.text(field), true);
        input.setName("settings-" + field.name().toLowerCase(java.util.Locale.ROOT));
        input.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || (typed == '-' && field.signed()));
        UiKit.onChange(input, () -> {
            form.text(field, input.getText());
            refresh();
        });
        String text = Messages.getString(label);
        UiFormField formField = kit.formField(text.endsWith(":") ? text.substring(0, text.length() - 1) : text,
              input);
        if (tip != null) {
            kit.tip(input, true).getActor().setText(Messages.getString(tip));
        }
        fields.put(field, formField);
        return formField;
    }

    // ---- Sections ----

    private Table autoConfig() {
        Table content = new Table();
        content.top().left().defaults().left();
        List<PlayerSettingsForm.Faction> factions = form.factions();
        String[] names = factions.stream().map(PlayerSettingsForm.Faction::name).toArray(String[]::new);
        UiSelect faction = kit.tokenSelect(true, names.length == 0 ? new String[] { form.faction() } : names);
        faction.setName("settings-faction");
        for (int index = 0; index < factions.size(); index++) {
            if (factions.get(index).code().equals(form.faction())) {
                faction.select(index);
            }
        }
        kit.tip(faction, true).getActor().setText(Messages.getString("CustomMekDialog.acfFactionChooser"));
        UiKit.onChange(faction, () -> {
            if (!factions.isEmpty()) {
                form.faction(factions.get(faction.getSelectedIndex()).code());
                refresh();
            }
        });
        String label = Messages.getString("PlayerSettingsDialog.autoConfigFaction");
        content.add(kit.formField(label.endsWith(":") ? label.substring(0, label.length() - 1) : label, faction))
              .growX().row();

        autoconfig = tool("settings-autoconfig", "PlayerSettingsDialog.autoConfig", "CustomMekDialog.acfExecuteConfig",
              () -> onEdt(form::autoconfigure));
        randomize = tool("settings-randomize", "PlayerSettingsDialog.randomize", "CustomMekDialog.acfRandomizer",
              () -> onEdt(form::randomize));
        restore = tool("settings-restore", "PlayerSettingsDialog.restore", "CustomMekDialog.acfRestoreMunitionTree",
              () -> {
                  form.restore();
                  refresh();
              });
        Table buttons = new Table();
        buttons.left();
        buttons.add(autoconfig);
        buttons.add(randomize).padLeft(space("sm"));
        buttons.add(restore).padLeft(space("sm"));
        content.add(buttons).padTop(space("sm")).row();

        UiChoice trulyRandom = kit.check(Messages.getString("Lobby.settings.trulyRandom"), form.trulyRandom());
        trulyRandom.setName("settings-truly-random");
        kit.tip(trulyRandom, true).getActor().setText(Messages.getString("CustomMekDialog.acfTrulyRandom"));
        UiKit.onChange(trulyRandom, () -> form.trulyRandom(trulyRandom.isChecked()));
        UiChoice banNukes = kit.check(Messages.getString("Lobby.settings.banNukes"), form.banNukes());
        banNukes.setName("settings-ban-nukes");
        kit.tip(banNukes, true).getActor().setText(Messages.getString("CustomMekDialog.acfBanNukes"));
        UiKit.onChange(banNukes, () -> form.banNukes(banNukes.isChecked()));
        Table checks = new Table();
        checks.left();
        checks.add(trulyRandom);
        checks.add(banNukes).padLeft(space("lg"));
        content.add(checks).padTop(space("sm")).row();

        UiButton save = tool("settings-save-loadout", "PlayerSettingsDialog.saveADF", "CustomMekDialog.acfSaveADF",
              () -> host.saveLoadout(form.loadoutToSave(), () -> { }));
        UiButton load = tool("settings-load-loadout", "PlayerSettingsDialog.loadADF", "CustomMekDialog.acfLoadADF",
              () -> host.loadLoadout(loaded -> {
                  if (loaded != null) {
                      form.loaded(loaded);
                      refresh();
                  }
              }));
        Table files = new Table();
        files.left();
        files.add(save);
        files.add(load).padLeft(space("sm"));
        content.add(files).padTop(space("sm")).row();
        return content;
    }

    private UiButton tool(String name, String text, String tip, Runnable action) {
        UiButton button = kit.button("button-secondary-compact", null, Messages.getString(text), null);
        button.setName(name);
        kit.tip(button, true).getActor().setText(Messages.getString(tip));
        UiKit.onChange(button, () -> {
            if (!busy) {
                action.run();
            }
        });
        return button;
    }

    /** Runs a change of the form that reads the game on the Swing thread, holding the buttons meanwhile. */
    private void onEdt(Runnable change) {
        busy = true;
        refresh();
        host.edt(change, () -> {
            busy = false;
            refresh();
        });
    }

    private Table deployment() {
        Table content = new Table();
        content.top().left().defaults().left();
        Table grid = new Table();
        grid.defaults().growX().uniformX().pad(space("xxs"));
        int column = 0;
        for (Position position : positions) {
            grid.add(positionButton(position));
            if (++column % 3 == 0) {
                grid.row();
            }
            // The custom zones start a row of their own, below "Centre" and "Deep"
            if (position.id() == PlayerSettingsForm.GRID[3][1]) {
                grid.add();
                grid.row();
                column = 0;
            }
        }
        content.add(grid).growX().row();

        content.add(pair(field(Field.OFFSET, "CustomMekDialog.labDeploymentOffset",
              "CustomMekDialog.labDeploymentOffsetTip"), field(Field.WIDTH, "CustomMekDialog.labDeploymentWidth",
              "CustomMekDialog.labDeploymentWidthTip"))).growX().padTop(space("md")).row();
        if (form.walkOn()) {
            content.add(kit.typeLabel(Messages.getString("PlayerSettingsDialog.labWalkOnDeployment"), "small",
                  "text.secondary")).padTop(space("xs")).row();
        }
        widthNote = kit.typeLabel(Messages.getString("PlayerSettingsDialog.labDeploymentWidthWalkOn"), "small",
              "text.secondary");
        content.add(widthNote).padTop(space("xs")).row();

        content.add(kit.typeLabel(Messages.getString("CustomMekDialog.labDeploymentCustomBox"), "small",
              "text.secondary")).padTop(space("md")).row();
        String hint = form.boardWidth() + " \u00d7 " + form.boardHeight();
        Table box = new Table();
        box.top().left();
        box.add(field(Field.ANY_NW_X, "CustomMekDialog.labDeploymentAnyNW", null)).growX().uniformX().top();
        box.add(field(Field.ANY_NW_Y, "Lobby.settings.y", null)).growX().uniformX().top().padLeft(space("sm"));
        box.add(field(Field.ANY_SE_X, "CustomMekDialog.labDeploymentAnySE", null)).growX().uniformX().top()
              .padLeft(space("md"));
        box.add(field(Field.ANY_SE_Y, "Lobby.settings.y", null)).growX().uniformX().top().padLeft(space("sm"));
        content.add(box).growX().padTop(space("xs")).row();
        fields.get(Field.ANY_NW_X).helper("x (1\u2013" + form.boardWidth() + ")");
        fields.get(Field.ANY_NW_Y).helper("y (1\u2013" + form.boardHeight() + ")");
        fields.get(Field.ANY_SE_X).helper("x (1\u2013" + form.boardWidth() + ")");
        fields.get(Field.ANY_SE_Y).helper("y (1\u2013" + form.boardHeight() + ")");
        applyButton = kit.button("button-secondary-compact", null,
              Messages.getString("CustomMekDialog.BtnDeploymentApply"), null);
        applyButton.setName("settings-apply");
        kit.tip(applyButton, true).getActor().setText(Messages.getString("CustomMekDialog.BtnDeploymentApplyTip")
              + " (" + hint + ")");
        UiKit.onChange(applyButton, () -> {
            if (form.valid() && !busy) {
                onEdt(form::apply);
            }
        });
        content.add(applyButton).padTop(space("sm")).row();
        return content;
    }

    private UiButton positionButton(Position position) {
        UiButton button = kit.button("button-toggle-compact", null, position.label(), null);
        button.setName("settings-start-" + position.id());
        button.setProgrammaticChangeEvents(false);
        button.getLabel().setEllipsis(true);
        button.getLabelCell().minWidth(0);
        if (!position.valid()) {
            button.getLabel().setColor(UiTheme.tokenColor("status.warning"));
        }
        if (!position.others().isEmpty()) {
            Table marks = new Table();
            for (PlayerSettingsForm.Deployer other : position.others()) {
                marks.add(kit.solid(other.relation() == LobbySnapshot.Relation.ENEMY ? "status.danger"
                      : "accent.primary")).size(space("sm")).pad(0, space("xxs"), 0, space("xxs"));
            }
            button.add(marks).padLeft(space("xs"));
        }
        StringBuilder tip = new StringBuilder(position.tip());
        if (!position.valid()) {
            tip.append(tip.isEmpty() ? "" : "\n\n").append(Messages.getString("PlayerSettingsDialog.invalidStartPosTT"));
        }
        if (!position.others().isEmpty()) {
            tip.append(tip.isEmpty() ? "" : "\n\n").append(Messages.getString("PlayerSettingsDialog.deployingHere"));
            for (PlayerSettingsForm.Deployer other : position.others()) {
                tip.append('\n').append(other.name());
            }
        }
        if (!tip.isEmpty()) {
            kit.tip(button, true).getActor().setText(tip.toString());
        }
        UiKit.onChange(button, () -> {
            form.startPos(position.id());
            refresh();
        });
        positionButtons.add(button);
        return button;
    }

    private Table minefields() {
        Table content = new Table();
        content.top().left();
        List<Field> mines = List.of(Field.CONVENTIONAL, Field.VIBRABOMB, Field.ACTIVE, Field.INFERNO, Field.EMP,
              Field.TRIPWIRE, Field.PITFALL);
        Map<Field, String> labels = Map.of(Field.CONVENTIONAL, "PlayerSettingsDialog.labConventional",
              Field.VIBRABOMB, "PlayerSettingsDialog.labVibrabomb", Field.ACTIVE, "PlayerSettingsDialog.labActive",
              Field.INFERNO, "PlayerSettingsDialog.labInferno", Field.EMP, "PlayerSettingsDialog.labEMP",
              Field.TRIPWIRE, "PlayerSettingsDialog.labTripwire", Field.PITFALL, "PlayerSettingsDialog.labPitfall");
        Map<Field, String> tips = Map.of(Field.TRIPWIRE, "PlayerSettingsDialog.tripwireTT", Field.PITFALL,
              "PlayerSettingsDialog.pitfallTT");
        for (int index = 0; index < mines.size(); index += 2) {
            Field first = mines.get(index);
            Field second = index + 1 < mines.size() ? mines.get(index + 1) : null;
            content.add(pair(field(first, labels.get(first), tips.get(first)),
                  second == null ? null : field(second, labels.get(second), tips.get(second))))
                  .growX().padTop(index == 0 ? 0 : space("sm")).row();
        }
        return content;
    }

    private Table groundObjects() {
        Table content = new Table();
        content.top().left().defaults().left();
        kit.tip(content, true).getActor().setText(Messages.getString("Lobby.settings.objectsTip"));
        groundList.top().left().defaults().left();
        content.add(groundList).growX().row();
        UiTextField name = kit.input("", true);
        name.setName("settings-object-name");
        name.setMaxLength(PlayerSettingsForm.MAX_OBJECT_NAME);
        UiTextField tonnage = kit.input("", true);
        tonnage.setName("settings-object-tonnage");
        tonnage.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || typed == '.');
        UiChoice invulnerable = kit.check(Messages.getString("Lobby.settings.objectInvulnerable"), false);
        invulnerable.setName("settings-object-invulnerable");
        UiButton add = kit.button("button-secondary-compact", "add", Messages.getString("Lobby.settings.objectAdd"),
              null);
        add.setName("settings-object-add");
        UiKit.onChange(add, () -> {
            form.addGroundObject(name.getText(), tonnage.getText(), invulnerable.isChecked());
            name.setText("");
            tonnage.setText("");
            invulnerable.setChecked(false);
            fillGroundObjects();
        });
        Table row = new Table();
        row.bottom().left();
        row.add(kit.formField(Messages.getString("Lobby.settings.objectName"), name)).growX().bottom();
        row.add(kit.formField(Messages.getString("Lobby.settings.objectTonnage"), tonnage)).width(size("dialog.sm")
              / 4).bottom().padLeft(space("sm"));
        row.add(invulnerable).bottom().padLeft(space("sm")).padBottom(space("xs"));
        row.add(add).bottom().padLeft(space("sm"));
        content.add(row).growX().padTop(space("sm")).row();
        fillGroundObjects();
        return content;
    }

    private void fillGroundObjects() {
        groundList.clearChildren();
        List<GroundObject> objects = form.groundObjects();
        if (objects.isEmpty()) {
            groundList.add(kit.typeLabel(Messages.getString("Lobby.settings.objectsNone"), "small", "text.muted"))
                  .row();
        }
        for (int index = 0; index < objects.size(); index++) {
            GroundObject object = objects.get(index);
            Label line = kit.typeLabel(Messages.getString("Lobby.settings.objectLine", object.name(),
                  object.tonnage(), object.invulnerable() ? Messages.getString("Lobby.settings.objectInvulnerableMark")
                        : ""), "body", "text.primary");
            line.setEllipsis(true);
            UiButton remove = kit.button("button-icon-compact", "close", null, null);
            remove.setName("settings-object-remove-" + index);
            kit.tip(remove, true).getActor().setText(Messages.getString("Lobby.settings.objectRemove",
                  object.name()));
            int removed = index;
            UiKit.onChange(remove, () -> {
                form.removeGroundObject(removed);
                fillGroundObjects();
            });
            groundList.add(line).growX().minWidth(0);
            groundList.add(remove).padLeft(space("sm")).row();
        }
    }

    private Table skills() {
        SkillGeneratorMethod[] methods = SkillGeneratorMethod.values();
        UiSelect method = select("settings-skill-method", methods, form.method());
        UiKit.onChange(method, () -> form.method(methods[method.getSelectedIndex()]));
        kit.tip(method, true).getActor().setText(Messages.getString("lblMethod.toolTipText"));
        SkillGeneratorType[] types = SkillGeneratorType.values();
        UiSelect type = select("settings-skill-type", types, form.type());
        UiKit.onChange(type, () -> form.type(types[type.getSelectedIndex()]));
        kit.tip(type, true).getActor().setText(Messages.getString("lblType.toolTipText"));
        SkillLevel[] levels = PlayerSettingsForm.levels().toArray(SkillLevel[]::new);
        UiSelect level = select("settings-skill-level", levels, form.level());
        UiKit.onChange(level, () -> form.level(levels[level.getSelectedIndex()]));
        kit.tip(level, true).getActor().setText(Messages.getString("lblSkillLevel.toolTipText"));
        UiChoice forceClose = kit.check(Messages.getString("tglForceClose.text"), form.forceClose());
        forceClose.setName("settings-force-close");
        kit.tip(forceClose, true).getActor().setText(Messages.getString("tglForceClose.toolTipText"));
        UiKit.onChange(forceClose, () -> form.forceClose(forceClose.isChecked()));

        Table content = new Table();
        content.top().left().defaults().left();
        content.add(pair(kit.formField(colonless("lblMethod.text"), method), kit.formField(colonless("lblType.text"),
              type))).growX().row();
        content.add(pair(kit.formField(colonless("lblSkillLevel.text"), level), null)).growX().padTop(space("sm"))
              .row();
        content.add(forceClose).padTop(space("sm")).row();
        return content;
    }

    private static String colonless(String key) {
        String text = Messages.getString(key);
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    private <T> UiSelect select(String name, T[] values, T current) {
        String[] names = new String[values.length];
        int selected = 0;
        for (int index = 0; index < values.length; index++) {
            names[index] = String.valueOf(values[index]);
            if (values[index] == current) {
                selected = index;
            }
        }
        UiSelect select = kit.tokenSelect(true, names);
        select.setName(name);
        select.select(selected);
        return select;
    }

    // ---- State ----

    /** Shows the form's state: the chosen position, the fields that apply and their errors, and what may be pressed. */
    private void refresh() {
        for (int index = 0; index < positionButtons.size(); index++) {
            positionButtons.get(index).setChecked(positions.get(index).id() == form.startPos());
        }
        fields.forEach((field, formField) -> {
            boolean applies = form.applies(field);
            if (formField.input() instanceof UiTextField input) {
                input.setDisabled(!applies);
            }
            formField.error(form.error(field).map(Messages::getString).orElse(null));
        });
        if (widthNote != null) {
            widthNote.setVisible(form.widthRestricted());
        }
        boolean valid = form.valid();
        ok.setDisabled(!valid || busy);
        applyButton.setDisabled(!valid || busy || !form.anyBox());
        autoconfig.setDisabled(busy || !form.autoconfigEnabled());
        randomize.setDisabled(busy || !form.randomizeEnabled());
        restore.setDisabled(busy || !form.restoreEnabled());
    }

    private void confirm() {
        if (!form.valid() || busy) {
            return;
        }
        busy = true;
        refresh();
        boolean[] applied = new boolean[1];
        host.edt(() -> applied[0] = form.apply(), () -> {
            busy = false;
            if (applied[0]) {
                close.run();
            } else {
                refresh();
            }
        });
    }

    /** GPU thread, once the dialog has closed: gives the camo picture back. */
    void dispose() {
        if (camoTexture != null) {
            camoTexture.dispose();
            camoTexture = null;
        }
    }

    // ---- For the probe and the tests ----

    PlayerSettingsForm form() {
        return form;
    }

    UiDialog dialog() {
        return dialog;
    }

    @Nullable UiFormField field(Field field) {
        return fields.get(field);
    }

    void onPositionChange(Consumer<Integer> listener) {
        positionButtons.forEach(button -> UiKit.onChange(button, () -> listener.accept(form.startPos())));
    }
}
