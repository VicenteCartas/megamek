/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.AssetForm;
import megamek.client.lobby.ForceNameRequest;
import megamek.client.lobby.TrainOrderForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.enums.SkillLevel;
import megamek.common.units.Entity;

/**
 * The lobby's small prompts as {@link UiDialog} cards, the native counterparts of the Swing {@code LobbyPrompts}: a
 * force designation with suggestions, a squadron designation, a Battlefield Support Asset's crew and its damage, and the
 * order of a train. Each fills a dialog {@link LobbyNativeDialogs} opened; its answer goes to the Swing thread through
 * an {@link Edt}, after which the dialog closes.
 * <p>
 * GPU thread.
 */
final class LobbyPromptViews {
    /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
    @FunctionalInterface
    interface Edt {
        void edt(Runnable work, Runnable then);
    }

    private LobbyPromptViews() {
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static String strip(String text) {
        String trimmed = text.trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** A designation typed or picked from the request's suggestions; OK takes a name that is not blank. */
    static void forceName(UiKit kit, UiDialog dialog, ForceNameRequest request, Edt edt, Consumer<String> chosen,
          Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        UiTextField input = kit.input(request.currentName() == null ? "" : request.currentName(), false);
        input.setName("prompt-name");
        body.add(input).row();
        UiButton ok = dialog.primary(Messages.getString("Ok.text"), () -> ForceNameRequest.accept(input.getText())
              .ifPresent(name -> edt.edt(() -> chosen.accept(name), close)));
        ok.setName("prompt-ok");
        if (!request.suggestions().isEmpty()) {
            body.add(kit.caption(Messages.getString("Lobby.prompt.suggestions"), true)).padTop(space("md")).row();
            HorizontalGroup flow = new HorizontalGroup();
            flow.wrap().space(space("xs")).wrapSpace(space("xs"));
            int index = 0;
            for (String suggestion : request.suggestions()) {
                UiButton pick = kit.button("button-secondary-compact", null, suggestion, null);
                pick.setName("prompt-suggestion-" + index++);
                UiKit.onChange(pick, () -> {
                    input.setText(suggestion);
                    input.setCursorPosition(suggestion.length());
                    ok.setDisabled(false);
                });
                flow.addActor(pick);
            }
            body.add(flow).padTop(space("xs")).row();
        }
        UiKit.onChange(input, () -> ok.setDisabled(input.getText().isBlank()));
        ok.setDisabled(input.getText().isBlank());
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("prompt-cancel");
    }

    /** A squadron designation; OK takes one that is not blank. */
    static void squadron(UiKit kit, UiDialog dialog, Edt edt, Consumer<String> chosen, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        UiTextField input = kit.input("", false);
        input.setName("prompt-name");
        body.add(kit.formField(Messages.getString("Lobby.prompt.squadron"), input)).row();
        UiButton ok = dialog.primary(Messages.getString("Ok.text"), () -> {
            String name = input.getText().trim();
            if (!name.isEmpty()) {
                edt.edt(() -> chosen.accept(name), close);
            }
        });
        ok.setName("prompt-ok");
        ok.setDisabled(true);
        UiKit.onChange(input, () -> ok.setDisabled(input.getText().isBlank()));
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("prompt-cancel");
    }

    /** A Battlefield Support Asset's crew name and grade; read only unless the asset may be changed. */
    static void assetCrew(UiKit kit, UiDialog dialog, AssetForm form, Edt edt, Runnable applied, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        body.add(kit.typeLabel(form.asset().getDisplayName(), "title", "text.primary")).row();
        UiTextField name = kit.input(form.name(), false);
        name.setName("prompt-name");
        name.setDisabled(!form.editable());
        UiKit.onChange(name, () -> form.name(name.getText()));
        body.add(kit.formField(strip(Messages.getString("BattlefieldSupportAssetConfigDialog.name")), name))
              .padTop(space("md")).row();
        List<SkillLevel> grades = form.grades();
        UiSelect grade = kit.tokenSelect(true, grades.stream().map(String::valueOf).toArray(String[]::new));
        grade.setName("prompt-grade");
        grade.select(Math.max(0, grades.indexOf(form.grade())));
        grade.setDisabled(!form.editable() || form.gradeLocked());
        UiKit.onChange(grade, () -> form.grade(grades.get(grade.getSelectedIndex())));
        if (form.gradeLocked()) {
            kit.tip(grade, true).getActor()
                  .setText(Messages.getString("BattlefieldSupportAssetConfigDialog.gradeLockedTip"));
        }
        body.add(kit.formField(strip(Messages.getString("BattlefieldSupportAssetConfigDialog.grade")), grade))
              .padTop(space("md")).row();
        if (form.editable()) {
            dialog.primary(Messages.getString("Ok.text"), () -> edt.edt(() -> {
                form.applyCrew();
                applied.run();
            }, close)).setName("prompt-ok");
            dialog.cancelButton(Messages.getString("Cancel.text")).setName("prompt-cancel");
        } else {
            dialog.primary(Messages.getString("Close"), close).setName("prompt-ok");
        }
    }

    /** A Battlefield Support Asset's persistent damage, as its current Destroy Check. */
    static void assetDamage(UiKit kit, UiDialog dialog, AssetForm form, Edt edt, Runnable applied, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        body.add(kit.typeLabel(form.asset().getDisplayName(), "title", "text.primary")).row();
        Label info = kit.typeLabel(Messages.getString("BattlefieldSupportAssetDamageDialog.info"), "body",
              "text.secondary");
        info.setWrap(true);
        body.add(info).padTop(space("xs")).row();
        Table line = new Table();
        line.add(kit.typeLabel(strip(Messages.getString("BattlefieldSupportAssetDamageDialog.original")), "body",
              "text.secondary")).left().expandX();
        line.add(kit.typeLabel(String.valueOf(form.originalDestroyCheck()), "body.strong", "text.primary")).right();
        body.add(line).padTop(space("md")).row();
        UiStepper check = kit.stepper(form.destroyCheck(), 0, form.originalDestroyCheck());
        check.setName("prompt-destroy-check");
        check.up().setName("prompt-destroy-check-up");
        check.down().setName("prompt-destroy-check-down");
        UiKit.onChange(check, () -> form.destroyCheck(check.value()));
        Table current = new Table();
        current.add(kit.typeLabel(strip(Messages.getString("BattlefieldSupportAssetDamageDialog.current")), "body",
              "text.secondary")).left().expandX();
        current.add(check).right();
        body.add(current).padTop(space("sm")).row();
        dialog.primary(Messages.getString("Ok.text"), () -> edt.edt(() -> {
            form.applyDamage();
            applied.run();
        }, close)).setName("prompt-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("prompt-cancel");
    }

    /** A train's order: the tractor, then the trailers, each movable a place up or down. */
    static void trainOrder(UiKit kit, UiDialog dialog, TrainOrderForm form, Edt edt, Consumer<List<Entity>> ordered,
          Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        body.add(kit.caption(Messages.getString("TrainOrderDialog.order"), true)).row();
        Table list = new Table();
        list.top().left();
        body.add(list).padTop(space("xs")).row();
        Label line = kit.typeLabel("", "small", "text.secondary");
        line.setWrap(true);
        body.add(line).padTop(space("md")).row();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            list.clearChildren();
            list.add(car(kit, form.head().getShortName() + " " + Messages.getString("TrainOrderDialog.tractor"),
                  new ArrayList<>())).growX().row();
            List<Entity> trailers = form.trailers();
            for (int index = 0; index < trailers.size(); index++) {
                int at = index;
                UiButton up = kit.button("button-icon-compact", "chevron-left", null, null);
                up.setName("prompt-up-" + index);
                up.setDisabled(index == 0);
                kit.tip(up, true).getActor().setText(Messages.getString("TrainOrderDialog.moveUp"));
                UiKit.onChange(up, () -> {
                    form.move(at, -1);
                    build[0].run();
                });
                UiButton down = kit.button("button-icon-compact", "chevron-right", null, null);
                down.setName("prompt-down-" + index);
                down.setDisabled(index == trailers.size() - 1);
                kit.tip(down, true).getActor().setText(Messages.getString("TrainOrderDialog.moveDown"));
                UiKit.onChange(down, () -> {
                    form.move(at, 1);
                    build[0].run();
                });
                list.add(car(kit, (index + 1) + ". " + trailers.get(index).getShortName(), List.of(up, down)))
                      .growX().padTop(space("xs")).row();
            }
            line.setText(form.line());
        };
        build[0].run();
        dialog.primary(Messages.getString("TrainOrderDialog.connect"),
              () -> edt.edt(() -> ordered.accept(form.trailers()), close)).setName("prompt-ok");
        dialog.cancelButton(Messages.getString("TrainOrderDialog.cancel")).setName("prompt-cancel");
    }

    private static Table car(UiKit kit, String text, List<UiButton> buttons) {
        Table car = kit.card();
        Label label = kit.typeLabel(text, "body", "text.primary");
        label.setEllipsis(true);
        car.add(label).growX().minWidth(0).left();
        for (UiButton button : buttons) {
            car.add(button).padLeft(space("xs"));
        }
        return car;
    }
}
