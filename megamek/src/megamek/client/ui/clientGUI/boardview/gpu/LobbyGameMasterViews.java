/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.BuildingEditForm;
import megamek.client.lobby.ClientCommandForm;
import megamek.client.lobby.GameMasterPlayerSetup;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyGameMaster.Ballot;
import megamek.client.lobby.LobbyGameMaster.Choice;
import megamek.client.lobby.MapNoteForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.SpecialHexDisplay;
import megamek.common.enums.BasementType;
import megamek.common.enums.BuildingType;

/**
 * The Game Master's small cards over the battle, the native counterparts of the Swing dialogs that once opened over it:
 * a map note, the Game Master vote, a command's arguments, a player's setup and a building. Each fills a dialog
 * {@link LobbyNativeDialogs} opened; what it sends goes to the Swing thread through an {@link LobbyPromptViews.Edt}.
 * <p>
 * GPU thread.
 */
final class LobbyGameMasterViews {
    /** Asks before going on, as a confirmation card over the one that asks. */
    @FunctionalInterface
    interface Confirm {
        void confirm(String title, String text, Runnable yes);
    }

    /** What the vote card's buttons do, on the GPU thread. */
    interface VoteActions {
        void vote(boolean yes);

        void withdraw();
    }

    /** Draws a building as the tileset would, handing the picture, or null for none, back on the GPU thread. */
    @FunctionalInterface
    interface BuildingPicture {
        void draw(Hex building, Consumer<BufferedImage> drawn);
    }

    private LobbyGameMasterViews() {
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static String strip(String text) {
        String trimmed = text.trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String label(String key) {
        return strip(Messages.getString(key));
    }

    private static Label text(UiKit kit, String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    private static void tip(UiKit kit, Actor actor, String key) {
        kit.tip(actor, true).getActor().setText(GpuBoardWindow.plainText(Messages.getString(key)));
    }

    /**
     * A whole number typed in, held to {@code min} and {@code max}: a value out of range or not a number marks the
     * field and is not taken. For ranges too wide to step through one at a time, such as a Construction Factor.
     */
    static UiTextField number(UiKit kit, String name, int value, int min, IntSupplier max, IntConsumer changed,
          Runnable after) {
        UiTextField field = kit.input(String.valueOf(value), true);
        field.setName(name);
        field.setTextFieldFilter((typed, character) -> Character.isDigit(character)
              || (character == '-' && min < 0 && typed.getCursorPosition() == 0));
        UiKit.onChange(field, () -> {
            Integer parsed = parse(field.getText());
            boolean valid = parsed != null && parsed >= min && parsed <= max.getAsInt();
            field.status(valid ? UiTextField.Status.NONE : UiTextField.Status.ERROR);
            if (valid) {
                changed.accept(parsed);
            }
            after.run();
        });
        return field;
    }

    private static @Nullable Integer parse(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static boolean anyInvalid(List<UiTextField> fields) {
        for (UiTextField field : fields) {
            if (field.status() == UiTextField.Status.ERROR) {
                return true;
            }
        }
        return false;
    }

    // ---- A map note ----

    /** A note's text and who sees it; Done hands the note to {@code done} on the Swing thread. */
    static void mapNote(UiKit kit, UiDialog dialog, MapNoteForm form, LobbyPromptViews.Edt edt,
          Consumer<SpecialHexDisplay> done, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        UiTextField.Area area = kit.area(form.text(), 5);
        area.setName("note-text");
        UiKit.onChange(area, () -> form.text(area.getText()));
        body.add(kit.formField(label("NoteDialog.note"), area)).row();
        UiSelect visibility = kit.tokenSelect(true, MapNoteForm.visibilityNames().toArray(String[]::new));
        visibility.setName("note-visibility");
        visibility.select(form.visibility());
        UiKit.onChange(visibility, () -> form.visibility(visibility.getSelectedIndex()));
        body.add(kit.formField(label("NoteDialog.visibility"), visibility)).padTop(space("md")).row();
        dialog.primary(Messages.getString("NoteDialog.Done"), () -> edt.edt(() -> done.accept(form.apply()), close))
              .setName("note-done");
        dialog.cancelButton(Messages.getString("NoteDialog.Cancel")).setName("note-cancel");
    }

    // ---- The Game Master vote ----

    /**
     * The running vote: who asked, each ballot, what decides it, and what the local player may do about it. A voter
     * picks yes or no and sends it, and may change it while the vote runs; the requester may withdraw. Filled again
     * in place as the server shares the vote, keeping the voter's pick.
     */
    static final class VoteCard {
        private final UiKit kit;
        private final UiDialog dialog;
        private final VoteActions actions;
        private int pick;

        VoteCard(UiKit kit, UiDialog dialog, VoteActions actions) {
            this.kit = kit;
            this.dialog = dialog;
            this.actions = actions;
        }

        UiDialog dialog() {
            return dialog;
        }

        void fill(LobbyGameMaster vote) {
            Table body = dialog.body();
            body.clearChildren();
            dialog.clearFooter();
            body.top().left().defaults().left().growX();
            String requester = vote.requester().isEmpty()
                  ? Messages.getString("GameMasterVoteDialog.unknownPlayer") : vote.requester();
            body.add(text(kit, Messages.getString("GameMasterVoteDialog.header", requester), "body.strong",
                  "text.primary")).row();
            Table ballots = new Table();
            ballots.defaults().left().padTop(space("xs"));
            int row = 0;
            for (Ballot ballot : vote.ballots()) {
                String name = ballot.name().isEmpty() ? Messages.getString("GameMasterVoteDialog.unknownPlayer")
                      : ballot.name();
                Label voter = kit.typeLabel(ballot.self() ? Messages.getString("Lobby.gm.you", name) : name, "body",
                      "text.primary");
                voter.setEllipsis(true);
                ballots.add(voter).growX().minWidth(0);
                Label choice = kit.typeLabel(Messages.getString(switch (ballot.choice()) {
                    case YES -> "GameMasterVoteDialog.vote.yes";
                    case NO -> "GameMasterVoteDialog.vote.no";
                    case PENDING -> "GameMasterVoteDialog.vote.pending";
                }), "small", ballot.choice() == Choice.PENDING ? "text.muted" : "text.secondary");
                choice.setName("vote-ballot-" + row++);
                ballots.add(choice).right().padLeft(space("md")).row();
            }
            body.add(ballots).padTop(space("sm")).row();
            body.add(text(kit, Messages.getString(vote.majority() ? "GameMasterVoteDialog.threshold.majority"
                  : "GameMasterVoteDialog.threshold.unanimous"), "small", "text.muted")).padTop(space("sm")).row();
            if (vote.votesSelf()) {
                UiSelect choice = kit.tokenSelect(true, Messages.getString("GameMasterVoteDialog.yes"),
                      Messages.getString("GameMasterVoteDialog.no"));
                choice.setName("vote-choice");
                choice.select(pick);
                UiKit.onChange(choice, () -> pick = choice.getSelectedIndex());
                body.add(choice).padTop(space("md")).left().fill(false, false).row();
                dialog.primary(Messages.getString("GameMasterVoteDialog.send"), () -> actions.vote(pick == 0))
                      .setName("vote-send");
            } else if (vote.requestedBySelf()) {
                UiButton withdraw = dialog.primary(Messages.getString("GameMasterVoteDialog.cancel"),
                      actions::withdraw);
                withdraw.setName("vote-withdraw");
                tip(kit, withdraw, "GameMasterVoteDialog.cancel.tooltip");
            }
        }
    }

    // ---- A command's arguments ----

    /**
     * A command as a form of its arguments: the hex it was opened on, its help, then a control for each argument.
     * Execute asks first, then sends the command as a typed one would be sent.
     */
    static void clientCommand(UiKit kit, UiDialog dialog, ClientCommandForm form, Confirm confirm,
          LobbyPromptViews.Edt edt, Consumer<String> chat, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        String target = form.target();
        if (target != null) {
            Label hex = kit.typeLabel(target, "body.strong", "text.primary");
            hex.setName("command-target");
            body.add(hex).row();
        }
        body.add(text(kit, GpuBoardWindow.plainText(form.helpHtml()), "small", "text.secondary")).padTop(space("xs"))
              .row();
        List<UiTextField> numbers = new ArrayList<>();
        UiButton[] execute = new UiButton[1];
        Runnable validate = () -> {
            if (execute[0] != null) {
                execute[0].setDisabled(anyInvalid(numbers));
            }
        };
        int index = 0;
        for (ClientCommandForm.Field field : form.fields()) {
            int at = index++;
            if (field.hidden()) {
                continue;
            }
            Actor control = switch (field.kind()) {
                case NUMBER -> {
                    UiTextField number = number(kit, "command-field-" + at, field.number(), field.min(),
                          field::max, field::number, validate);
                    numbers.add(number);
                    yield number;
                }
                case CHOICE -> {
                    UiSelect select = kit.tokenSelect(true, field.items().toArray(String[]::new));
                    select.setName("command-field-" + at);
                    if (field.choice() >= 0 && field.choice() < select.count()) {
                        select.select(field.choice());
                    }
                    UiKit.onChange(select, () -> field.choice(select.getSelectedIndex()));
                    yield select;
                }
                case TEXT, SECRET -> {
                    UiTextField input = field.kind() == ClientCommandForm.Kind.SECRET ? kit.password(true)
                          : kit.input(field.text(), true);
                    input.setName("command-field-" + at);
                    UiKit.onChange(input, () -> field.text(input.getText()));
                    yield input;
                }
                case FLAG -> {
                    UiChoice check = kit.check(field.name(), field.flag());
                    check.setName("command-field-" + at);
                    UiKit.onChange(check, () -> field.flag(check.isChecked()));
                    yield check;
                }
            };
            if (!field.help().isBlank()) {
                kit.tip(control, true).getActor().setText(GpuBoardWindow.plainText(field.help()));
            }
            body.add(kit.formField(field.kind() == ClientCommandForm.Kind.FLAG ? "" : field.name(), control))
                  .padTop(space("sm")).row();
        }
        execute[0] = dialog.primary(Messages.getString("ClientCommandDialog.execute"),
              () -> confirm.confirm(Messages.getString("ClientCommandDialog.confirm.title"),
                    Messages.getString("ClientCommandDialog.confirm.text"), () -> {
                        close.run();
                        edt.edt(() -> form.execute(chat), () -> { });
                    }));
        execute[0].setName("command-execute");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("command-cancel");
    }

    // ---- A player's setup ----

    /**
     * Who to set up, their team and deployment zone, with what applying them means; Apply sends what changed, and
     * each Reinforce button applies, then hands the player over to a way of giving them units. Applying closes the
     * card first, so the card a reinforcement opens is not closed with it.
     *
     * @param fromCatalogue on the Swing thread: gives the player units picked from the Requisition's catalogue
     * @param fromFile      on the Swing thread: gives the player units from a file
     * @param fromGenerator on the Swing thread: gives the player units from the force generator
     */
    static void playerSetup(UiKit kit, UiDialog dialog, GameMasterPlayerSetup setup, LobbyPromptViews.Edt edt,
          Consumer<String> chat, Consumer<Player> fromCatalogue, Consumer<Player> fromFile,
          Consumer<Player> fromGenerator, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        List<String> players = setup.playerNames();
        UiSelect player = kit.tokenSelect(true, players.toArray(String[]::new));
        player.setName("setup-player");
        if (setup.player() >= 0) {
            player.select(setup.player());
        }
        tip(kit, player, "GameMasterPlayerSetupDialog.player.tooltip");
        body.add(kit.formField(label("GameMasterPlayerSetupDialog.player"), player)).row();
        UiSelect team = kit.tokenSelect(true, setup.teamNames().toArray(String[]::new));
        team.setName("setup-team");
        team.select(setup.team());
        tip(kit, team, "GameMasterPlayerSetupDialog.teamLabel.tooltip");
        body.add(kit.formField(label("GameMasterPlayerSetupDialog.teamLabel"), team)).padTop(space("sm")).row();
        UiSelect zone = kit.tokenSelect(true, GameMasterPlayerSetup.zoneNames().toArray(String[]::new));
        zone.setName("setup-zone");
        zone.select(setup.zone());
        tip(kit, zone, "GameMasterPlayerSetupDialog.zoneLabel.tooltip");
        body.add(kit.formField(label("GameMasterPlayerSetupDialog.zoneLabel"), zone)).padTop(space("sm")).row();
        body.add(text(kit, GpuBoardWindow.plainText(Messages.getString("GameMasterPlayerSetupDialog.whenApplied")),
              "small", "text.secondary")).padTop(space("md")).row();
        Label status = text(kit, "", "body", "text.primary");
        status.setName("setup-status");
        body.add(status).padTop(space("sm")).row();
        UiButton fromCatalogueButton = kit.button("button-secondary", null,
              Messages.getString("GameMasterPlayerSetupDialog.reinforceFromRequisition"), null);
        fromCatalogueButton.setName("setup-reinforce-requisition");
        UiButton fromFileButton = kit.button("button-secondary", null,
              Messages.getString("GameMasterPlayerSetupDialog.reinforceFromFile"), null);
        fromFileButton.setName("setup-reinforce-file");
        UiButton fromGeneratorButton = kit.button("button-secondary", null,
              Messages.getString("GameMasterPlayerSetupDialog.reinforceFromGenerator"), null);
        fromGeneratorButton.setName("setup-reinforce-generator");
        Table reinforce = new Table();
        reinforce.defaults().left().growX();
        reinforce.add(fromCatalogueButton).row();
        reinforce.add(fromFileButton).padTop(space("xs")).row();
        reinforce.add(fromGeneratorButton).padTop(space("xs")).row();
        body.add(reinforce).padTop(space("md")).row();
        UiButton apply = dialog.primary(Messages.getString("GameMasterPlayerSetupDialog.apply"), () -> {
            close.run();
            edt.edt(() -> setup.apply(chat), () -> { });
        });
        apply.setName("setup-apply");
        tip(kit, apply, "GameMasterPlayerSetupDialog.apply.tooltip");
        dialog.cancelButton(Messages.getString("Close")).setName("setup-close");
        UiKit.onChange(fromCatalogueButton, () -> reinforce(setup, edt, chat, fromCatalogue, close));
        UiKit.onChange(fromFileButton, () -> reinforce(setup, edt, chat, fromFile, close));
        UiKit.onChange(fromGeneratorButton, () -> reinforce(setup, edt, chat, fromGenerator, close));
        TextTooltip catalogueTip = kit.tip(fromCatalogueButton, true);
        TextTooltip fileTip = kit.tip(fromFileButton, true);
        TextTooltip generatorTip = kit.tip(fromGeneratorButton, true);
        Runnable refresh = () -> {
            status.setText(GpuBoardWindow.plainText(setup.status()));
            apply.setDisabled(setup.problem() != null || setup.chosenPlayer() == null);
            boolean may = setup.mayReinforce();
            fromCatalogueButton.setDisabled(!may);
            fromFileButton.setDisabled(!may);
            fromGeneratorButton.setDisabled(!may);
            String reinforceTip = Messages.getString(may ? "GameMasterPlayerSetupDialog.reinforce.tooltip"
                  : "GameMasterPlayerSetupDialog.reinforce.notYet");
            catalogueTip.getActor().setText(reinforceTip);
            fileTip.getActor().setText(reinforceTip);
            generatorTip.getActor().setText(reinforceTip);
        };
        UiKit.onChange(player, () -> {
            setup.player(player.getSelectedIndex());
            team.select(setup.team());
            zone.select(setup.zone());
            refresh.run();
        });
        UiKit.onChange(team, () -> {
            setup.team(team.getSelectedIndex());
            refresh.run();
        });
        UiKit.onChange(zone, () -> {
            setup.zone(zone.getSelectedIndex());
            refresh.run();
        });
        refresh.run();
    }

    private static void reinforce(GameMasterPlayerSetup setup, LobbyPromptViews.Edt edt, Consumer<String> chat,
          Consumer<Player> reinforcement, Runnable close) {
        Player chosen = setup.chosenPlayer();
        if (chosen == null || !setup.mayReinforce()) {
            return;
        }
        close.run();
        edt.edt(() -> {
            setup.apply(chat);
            reinforcement.accept(chosen);
        }, () -> { });
    }

    // ---- A building ----

    /**
     * A building or fuel tank in a hex: what it is, its condition, how it looks or how hard it goes up, and what the
     * hex held before a Game Master first changed it. Apply, Remove and Restore send and close; a building in water
     * is refused with an alert, leaving the card open.
     *
     * @param alert   shows a message over the card
     * @param picture draws the building as the tileset would
     * @return what gives the card's picture back once it has closed
     */
    static Runnable building(UiKit kit, UiDialog dialog, BuildingEditForm form, LobbyPromptViews.Edt edt,
          Confirm alert, BuildingPicture picture, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        Texture[] shown = new Texture[1];
        Runnable dispose = () -> {
            if (shown[0] != null) {
                shown[0].dispose();
                shown[0] = null;
            }
        };

        UiSelect structure = kit.tokenSelect(true, BuildingEditForm.structureNames().toArray(String[]::new));
        structure.setName("building-structure");
        structure.select(form.fuelTank() ? 1 : 0);
        List<BuildingType> types = form.types();
        String[] typeNames = new String[types.size()];
        for (int index = 0; index < typeNames.length; index++) {
            typeNames[index] = types.get(index).toString();
        }
        UiSelect type = kit.tokenSelect(true, typeNames);
        type.setName("building-type");
        type.select(Math.max(0, types.indexOf(form.type())));
        UiSelect buildingClass = kit.tokenSelect(true, BuildingEditForm.classNames().toArray(String[]::new));
        buildingClass.setName("building-class");
        buildingClass.select(form.buildingClass());
        BasementType[] basements = BasementType.values();
        String[] basementNames = new String[basements.length];
        for (int index = 0; index < basements.length; index++) {
            basementNames[index] = basements[index].toString();
        }
        UiSelect basement = kit.tokenSelect(true, basementNames);
        basement.setName("building-basement");
        basement.select(form.basement().ordinal());

        List<UiTextField> numbers = new ArrayList<>();
        UiButton[] apply = new UiButton[1];
        Label band = kit.typeLabel("", "small", "text.muted");
        band.setName("building-band");
        Label effect = text(kit, "", "small", "text.secondary");
        effect.setName("building-effect");
        Runnable validate = () -> {
            effect.setText(GpuBoardWindow.plainText(form.magnitudeEffect()));
            if (apply[0] != null) {
                apply[0].setDisabled(anyInvalid(numbers));
            }
        };
        UiTextField constructionFactor = number(kit, "building-cf", form.constructionFactor(), 0,
              form::constructionFactorMax, form::constructionFactor, validate);
        UiTextField armor = number(kit, "building-armor", form.armor(), 0, () -> BuildingEditForm.MAX_ARMOR,
              form::armor, validate);
        UiStepper height = kit.stepper(form.height(), 1, BuildingEditForm.MAX_HEIGHT);
        height.setName("building-height");
        UiKit.onChange(height, () -> form.height(height.value()));
        UiTextField magnitude = number(kit, "building-magnitude", form.magnitude(),
              BuildingEditForm.SMALLEST_USEFUL_MAGNITUDE, () -> BuildingEditForm.MAX_MAGNITUDE, form::magnitude,
              validate);
        tip(kit, magnitude, "BuildingEditDialog.magnitude.tooltip");
        numbers.add(constructionFactor);
        numbers.add(armor);
        numbers.add(magnitude);

        Table fluffRow = new Table();
        Image preview = new Image();
        preview.setScaling(Scaling.fit);
        preview.setName("building-preview");
        Label noPreview = kit.typeLabel(Messages.getString("BuildingEditDialog.fluffPreview.none"), "small",
              "text.muted");
        Table previewCell = new Table();

        Table structureSection = section(kit, "BuildingEditDialog.section.structure");
        structureSection.add(kit.formField(label("BuildingEditDialog.structure"), structure)).row();
        structureSection.add(kit.formField(label("BuildingEditDialog.type"), type)).padTop(space("sm")).row();
        structureSection.add(kit.formField(label("BuildingEditDialog.buildingClass"), buildingClass))
              .padTop(space("sm")).row();
        structureSection.add(kit.formField(label("BuildingEditDialog.basement"), basement)).padTop(space("sm")).row();
        body.add(structureSection).row();

        Table condition = section(kit, "BuildingEditDialog.section.condition");
        condition.add(kit.formField(label("BuildingEditDialog.constructionFactor"), constructionFactor)).row();
        condition.add(band).row();
        condition.add(kit.formField(label("BuildingEditDialog.armor"), armor)).padTop(space("sm")).row();
        condition.add(kit.formField(label("BuildingEditDialog.height"), height)).padTop(space("sm")).row();
        body.add(condition).padTop(space("md")).row();

        Table appearance = section(kit, "BuildingEditDialog.section.appearance");
        appearance.add(kit.formField(label("BuildingEditDialog.fluffImage"), fluffRow)).row();
        appearance.add(kit.formField(label("BuildingEditDialog.fluffPreview"), previewCell)).padTop(space("sm"))
              .row();
        Table explosion = section(kit, "BuildingEditDialog.section.explosion");
        explosion.add(kit.formField(label("BuildingEditDialog.magnitude"), magnitude)).row();
        explosion.add(kit.formField(label("BuildingEditDialog.magnitudeEffect"), effect)).padTop(space("sm")).row();
        Cell<Table> lastSection = body.add(appearance).padTop(space("md"));
        body.row();

        Label original = text(kit, GpuBoardWindow.plainText(form.original()), "small", "text.secondary");
        original.setName("building-original");
        body.add(original).padTop(space("md")).row();

        Runnable[] drawPreview = new Runnable[1];
        drawPreview[0] = () -> picture.draw(form.buildingAsHex(form.fluffImage()), image -> {
            if (!dialog.isOpen()) {
                return;
            }
            dispose.run();
            previewCell.clearChildren();
            if (image == null) {
                previewCell.add(noPreview).left();
            } else {
                shown[0] = GpuStartScreen.texture(image, true);
                preview.setDrawable(new TextureRegionDrawable(shown[0]));
                previewCell.add(preview).size(image.getWidth(), image.getHeight()).left();
            }
        });
        Runnable fillFluff = () -> {
            fluffRow.clearChildren();
            List<Integer> levels = form.fluffLevels();
            String[] names = new String[levels.size() + 1];
            names[0] = BuildingEditForm.fluffName(0);
            for (int index = 0; index < levels.size(); index++) {
                names[index + 1] = BuildingEditForm.fluffName(levels.get(index));
            }
            UiSelect fluff = kit.tokenSelect(true, names);
            fluff.setName("building-fluff");
            fluff.select(Math.max(0, levels.indexOf(form.fluffImage()) + 1));
            UiKit.onChange(fluff, () -> {
                int chosen = fluff.getSelectedIndex();
                form.fluffImage(chosen == 0 ? 0 : levels.get(chosen - 1));
                drawPreview[0].run();
            });
            fluffRow.add(fluff).growX();
            drawPreview[0].run();
        };
        Runnable refresh = () -> {
            boolean tank = form.fuelTank();
            type.setDisabled(tank);
            buildingClass.setDisabled(tank);
            basement.setDisabled(tank);
            lastSection.setActor(tank ? explosion : appearance);
            String bandText = form.band();
            band.setText(tank || bandText == null ? "" : bandText);
            constructionFactor.setText(String.valueOf(form.constructionFactor()));
            constructionFactor.status(UiTextField.Status.NONE);
            validate.run();
            if (!tank) {
                fillFluff.run();
            }
        };
        UiKit.onChange(structure, () -> {
            form.fuelTank(structure.getSelectedIndex() == 1);
            refresh.run();
        });
        UiKit.onChange(type, () -> {
            form.type(types.get(type.getSelectedIndex()));
            refresh.run();
        });
        UiKit.onChange(buildingClass, () -> {
            form.buildingClass(buildingClass.getSelectedIndex());
            fillFluff.run();
        });
        UiKit.onChange(basement, () -> {
            form.basement(basements[basement.getSelectedIndex()]);
            fillFluff.run();
        });

        apply[0] = dialog.primary(Messages.getString("BuildingEditDialog.apply"), () -> {
            if (form.inWater()) {
                alert.confirm(Messages.getString("BuildingEditDialog.cannotBuild.title"),
                      Messages.getString("BuildingEditDialog.cannotBuild.inWater"), () -> { });
                return;
            }
            close.run();
            edt.edt(form::apply, () -> { });
        });
        apply[0].setName("building-apply");
        UiButton remove = dialog.secondary(Messages.getString("BuildingEditDialog.remove"), () -> {
            close.run();
            edt.edt(form::remove, () -> { });
        });
        remove.setName("building-remove");
        remove.setDisabled(!form.mayRemove());
        tip(kit, remove, "BuildingEditDialog.remove.tooltip");
        UiButton restore = dialog.tool(Messages.getString("BuildingEditDialog.restore"), () -> {
            close.run();
            edt.edt(form::restore, () -> { });
        });
        restore.setName("building-restore");
        restore.setDisabled(!form.mayRestore());
        tip(kit, restore, "BuildingEditDialog.restore.tooltip");
        dialog.cancelButton(Messages.getString("HexEditDialog.close")).setName("building-close");
        refresh.run();
        return dispose;
    }

    private static Table section(UiKit kit, String key) {
        Table section = new Table();
        section.top().left().defaults().left().growX();
        section.add(kit.typeLabel(Messages.getString(key), "body.strong", "text.primary")).padBottom(space("xs"))
              .row();
        return section;
    }
}
