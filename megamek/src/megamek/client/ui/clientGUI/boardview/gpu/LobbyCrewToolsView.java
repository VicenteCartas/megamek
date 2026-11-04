/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
import megamek.client.lobby.RandomNamesForm;
import megamek.client.lobby.RandomSkillsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiSliderInput;

/**
 * The lobby's crew tools in a {@link UiDialog}: the random skills (the client whose units they are for, the method,
 * the type, the level and piloting = gunnery + 1) and the random names (the client, the faction, the share of female
 * crew and the historical ethnicity). Save keeps the settings as the client's defaults; Randomize keeps them and rolls
 * every crew of the client's units. The settings are a {@link RandomSkillsForm}'s or {@link RandomNamesForm}'s.
 * <p>
 * GPU thread. Save and Randomize run on the Swing thread through {@link Host#edt}.
 */
final class LobbyCrewToolsView {
    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Swing thread: saves the settings, the skills' with a chat line. */
        void save();

        /** Swing thread: saves the settings and rolls the crews. */
        void randomize();
    }

    private final UiKit kit;
    private final Table body;
    private final Host host;
    private final Runnable close;
    private final String prefix;
    private final List<Runnable> shows = new ArrayList<>();
    private final List<UiButton> buttons = new ArrayList<>();
    private boolean syncing;
    private boolean busy;

    private LobbyCrewToolsView(UiKit kit, UiDialog dialog, String prefix, Host host, Runnable close) {
        this.kit = kit;
        this.body = dialog.body();
        this.prefix = prefix;
        this.host = host;
        this.close = close;
        body.top().left().defaults().left().growX();
    }

    /** The random skills view. */
    static LobbyCrewToolsView skills(UiKit kit, UiDialog dialog, RandomSkillsForm form, Host host, Runnable close) {
        LobbyCrewToolsView view = new LobbyCrewToolsView(kit, dialog, "skills", host, close);
        view.clients(form.clientNames(), form::selected, form::select);
        view.select("method", "lblMethod", form.methods(), form::method, form::method);
        view.select("type", "lblType", form.types(), form::type, form::type);
        view.select("level", "lblSkillLevel", form.levels(), form::level, form::level);
        UiChoice forceClose = kit.check(Messages.getString("tglForceClose.text"), form.forceClose());
        forceClose.setName("skills-force-close");
        view.tip(forceClose, "tglForceClose.toolTipText");
        UiKit.onChange(forceClose, () -> {
            if (!view.syncing) {
                form.forceClose(forceClose.isChecked());
            }
        });
        view.shows.add(() -> forceClose.setChecked(form.forceClose()));
        view.body.add(forceClose).padTop(view.space("md")).fill(false, false).row();
        view.buttons(dialog, "SkillGenerationDialog.btnSave.toolTipText",
              "SkillGenerationDialog.btnRandomize.toolTipText");
        return view;
    }

    /** The random names view. */
    static LobbyCrewToolsView names(UiKit kit, UiDialog dialog, RandomNamesForm form, Host host, Runnable close) {
        LobbyCrewToolsView view = new LobbyCrewToolsView(kit, dialog, "names", host, close);
        view.clients(form.clientNames(), form::selected, form::select);
        view.select("faction", "RandomNameDialog.lblFaction", form.factions(), form::faction, form::faction);
        Slider female = new Slider(0, 100, 1, false, kit.tokenSliderStyle());
        female.setName("names-female");
        female.setProgrammaticChangeEvents(false);
        female.addListener(new UiSliderInput(female));
        Label shown = kit.typeLabel("", "body.strong", "text.primary");
        UiKit.onChange(female, () -> {
            form.percentFemale(Math.round(female.getValue()));
            shown.setText(form.percentFemale() + "%");
        });
        view.shows.add(() -> {
            female.setValue(form.percentFemale());
            shown.setText(form.percentFemale() + "%");
        });
        Table row = new Table();
        row.add(female).growX();
        row.add(shown).padLeft(view.space("sm")).width(DesignTokens.shipped().size().get("control.lg"));
        view.body.add(kit.formField(strip(Messages.getString("RandomNameDialog.lblGender")), row))
              .padTop(view.space("md")).row();
        UiSelect ethnicity = kit.tokenSelect(true, form.ethnicities().toArray(String[]::new));
        ethnicity.setName("names-ethnicity");
        view.tip(ethnicity, "RandomNameDialog.lblHistoricalEthnicity.toolTipText");
        UiKit.onChange(ethnicity, () -> {
            if (!view.syncing) {
                form.ethnicity(ethnicity.getSelectedIndex());
            }
        });
        view.shows.add(() -> ethnicity.select(form.ethnicity()));
        view.body.add(kit.formField(strip(Messages.getString("RandomNameDialog.lblHistoricalEthnicity")), ethnicity))
              .padTop(view.space("md")).row();
        view.buttons(dialog, null, null);
        return view;
    }

    private float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static String strip(String text) {
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    private void tip(Actor actor, String key) {
        kit.tip(actor, true).getActor().setText(Messages.getString(key).replaceAll("<[^>]+>", " ").trim());
    }

    /** The client chooser, when there are local bots; choosing one reads its settings into the view. */
    private void clients(List<String> names, IntSupplier selected, IntConsumer select) {
        if (names.size() < 2) {
            return;
        }
        UiSelect clients = kit.tokenSelect(true, names.toArray(String[]::new));
        clients.setName(prefix + "-client");
        clients.select(selected.getAsInt());
        UiKit.onChange(clients, () -> {
            if (!syncing) {
                select.accept(clients.getSelectedIndex());
                show();
            }
        });
        body.add(kit.formField(Messages.getString("Lobby.crew.client"), clients)).row();
    }

    private <T> void select(String name, String key, List<T> values, java.util.function.Supplier<T> value,
          Consumer<T> change) {
        String[] names = values.stream().map(String::valueOf).toArray(String[]::new);
        UiSelect select = kit.tokenSelect(true, names);
        select.setName(prefix + "-" + name);
        UiKit.onChange(select, () -> {
            if (!syncing && select.getSelectedIndex() >= 0) {
                change.accept(values.get(select.getSelectedIndex()));
            }
        });
        shows.add(() -> {
            int index = values.indexOf(value.get());
            if (index >= 0) {
                select.select(index);
            }
        });
        String label = Messages.getString(key.contains(".") ? key : key + ".text");
        if (!key.contains(".")) {
            tip(select, key + ".toolTipText");
        }
        float gap = body.getChildren().isEmpty() ? 0 : space("md");
        body.add(kit.formField(strip(label), select)).padTop(gap).row();
    }

    private void buttons(UiDialog dialog, @Nullable String saveTip, @Nullable String randomizeTip) {
        UiButton save = dialog.secondary(Messages.getString("Save.text"), () -> run(host::save));
        save.setName(prefix + "-save");
        UiButton randomize = dialog.primary(Messages.getString("Randomize.text"), () -> run(host::randomize));
        randomize.setName(prefix + "-randomize");
        if (saveTip != null) {
            tip(save, saveTip);
            tip(randomize, randomizeTip);
        }
        buttons.add(save);
        buttons.add(randomize);
        dialog.cancelButton(Messages.getString("Cancel.text")).setName(prefix + "-cancel");
        show();
    }

    private void show() {
        syncing = true;
        try {
            shows.forEach(Runnable::run);
        } finally {
            syncing = false;
        }
    }

    private void run(Runnable work) {
        if (busy) {
            return;
        }
        busy = true;
        buttons.forEach(button -> button.setDisabled(true));
        host.edt(work, close);
    }

    // ---- For the probe and the tests ----

    String prefix() {
        return prefix;
    }
}
