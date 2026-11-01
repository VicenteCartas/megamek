/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.UnitEquipmentForm;
import megamek.client.lobby.UnitEquipmentForm.AmmoBin;
import megamek.client.lobby.UnitEquipmentForm.BayRow;
import megamek.client.lobby.UnitEquipmentForm.BombBay;
import megamek.client.lobby.UnitEquipmentForm.InfantryArmor;
import megamek.client.lobby.UnitEquipmentForm.Toggle;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.equipment.enums.BombType.BombTypeEnum;
import megamek.common.units.ConvInfantry;

/**
 * The unit bay's equipment page, the native take on the Swing {@code EquipChoicePanel}: the ammunition bins as cards
 * grouped by the weapons they feed, each with its munition, shots and load; the weapons' starting feeds; transport bay
 * munitions and bomb loadouts with their capacity as a bar; battle armor mounts and manipulators; infantry armor; the
 * electronics' modes, the sensor and C3; the yes-or-no fittings as chips. The values are held by a
 * {@link UnitEquipmentForm}.
 * <p>
 * GPU thread.
 */
final class LobbyUnitBayEquipment {
    /** The bins in a row of a weapon group. */
    private static final int BINS_PER_ROW = 3;

    private final UiKit kit;
    private final UnitEquipmentForm form;
    private final LobbyUnitBayView bay;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final Table page = new Table();

    LobbyUnitBayEquipment(UiKit kit, UnitEquipmentForm form, LobbyUnitBayView bay) {
        this.kit = kit;
        this.form = form;
        this.bay = bay;
        page.top().left().defaults().left().growX();
        if (!form.ammo().isEmpty()) {
            page.add(card("CustomMekDialog.MunitionsPanelTitle", ammo())).row();
        }
        if (!form.feeds().isEmpty()) {
            page.add(card("CustomMekDialog.WeaponSelectionTitle", feeds())).row();
        }
        if (!form.bays().isEmpty()) {
            page.add(card("Lobby.bay.bays", bays())).row();
        }
        for (BombBay bombBay : form.bombs()) {
            page.add(card(bombBay.internal() ? "Lobby.bay.bombsInternal" : "Lobby.bay.bombsExternal",
                  bombs(bombBay))).row();
        }
        if (!form.smallSv().isEmpty() || form.infantrySrm().isPresent()) {
            page.add(card("CustomMekDialog.MunitionsPanelTitle", infantryMunitions())).row();
        }
        if (!form.apMounts().isEmpty()) {
            page.add(card("CustomMekDialog.APMountPanelTitle", apMounts())).row();
        }
        form.manipulators().ifPresent(manipulators -> page.add(card("CustomMekDialog.MEAPanelTitle",
              manipulators(manipulators))).row());
        form.infantryArmor().ifPresent(armor -> {
            if (armor.showsArmor()) {
                page.add(card("CustomMekDialog.infArmorSection", armor(armor))).row();
            }
            page.add(card("CustomMekDialog.infSpecSection", specializations(armor))).row();
            if (armor.showsDisposable()) {
                page.add(card("CustomMekDialog.infDisposableSection", disposable(armor))).row();
            }
        });
        if (!form.rapidFire().isEmpty()) {
            page.add(card("CustomMekDialog.rapidFireSection", rapidFire())).row();
        }
        form.vrt().ifPresent(vrt -> page.add(card("CustomMekDialog.VRTPanelTitle", vrt())).row());
        if (!form.mines().isEmpty()) {
            page.add(card("CustomMekDialog.mineSection", mines())).row();
        }
        if (!form.modes().isEmpty() || form.sensor().isPresent() || !form.c3().isEmpty()) {
            page.add(card("CustomMekDialog.ecmSection", electronics())).row();
        }
        List<Toggle> ejection = form.toggles().stream().filter(toggle -> toggle.name().startsWith("EJECT")
              || toggle == Toggle.AUTO_EJECT).toList();
        if (!ejection.isEmpty()) {
            page.add(card("Lobby.bay.ejection", toggles(ejection))).row();
        }
        List<Toggle> misc = form.toggles().stream().filter(toggle -> !ejection.contains(toggle)).toList();
        if (!misc.isEmpty()) {
            page.add(card("CustomMekDialog.miscSection", toggles(misc))).row();
        }
    }

    private static float space(String name) {
        return LobbyUnitBayView.space(name);
    }

    private Table card(String key, Actor content) {
        return bay.titledCard(LobbyUnitBayView.colonless(key), content);
    }

    private <T extends Actor> T name(T actor, String name) {
        return bay.name(actor, name);
    }

    private void changed() {
        bay.refresh();
    }

    // ---- Ammunition ----

    private Table ammo() {
        Map<String, List<AmmoBin>> groups = new LinkedHashMap<>();
        for (AmmoBin bin : form.ammo()) {
            groups.computeIfAbsent(bin.group(), group -> new ArrayList<>()).add(bin);
        }
        Table content = new Table();
        content.top().left().defaults().left().growX();
        boolean first = true;
        for (Map.Entry<String, List<AmmoBin>> group : groups.entrySet()) {
            Label title = kit.typeLabel(group.getKey(), "body.strong", "text.primary");
            title.setWrap(true);
            content.add(title).padTop(first ? 0 : space("lg")).row();
            first = false;
            Table bins = new Table();
            bins.top().left();
            List<AmmoBin> list = group.getValue();
            for (int i = 0; i < list.size(); i++) {
                bins.add(bin(list.get(i))).growX().uniformX().top().padRight(space("sm")).padBottom(space("sm"));
                if (i % BINS_PER_ROW == BINS_PER_ROW - 1) {
                    bins.row();
                }
            }
            for (int i = list.size() % BINS_PER_ROW; i > 0 && i < BINS_PER_ROW; i++) {
                bins.add().growX().uniformX();
            }
            content.add(bins).padTop(space("xs")).row();
        }
        return content;
    }

    /** An ammunition bin as a small card: where it sits, its munition, its shots and its load. */
    private Table bin(AmmoBin bin) {
        String prefix = "bay-ammo-" + bin.number();
        Table tile = kit.plainPanel();
        tile.top().left().defaults().left().growX();
        tile.pad(space("md"));
        tile.add(kit.typeLabel(bin.location(), "overline", "text.secondary")).row();
        UiSelect munition = select(prefix + "-munition", bin.munitions(), bin::munition, bin::munition,
              bin::munitionEnabled);
        tile.add(munition).padTop(space("xs")).row();
        Table shots = new Table();
        shots.left();
        shots.add(kit.typeLabel(LobbyUnitBayView.colonless("Lobby.bay.shots"), "small", "text.secondary"))
              .padRight(space("sm"));
        shots.add(stepper(prefix + "-shots", bin::shots, bin::maxShots, value -> {
            int current = bin.shots();
            bin.shots((value > current) ? current + bin.step() : current - bin.step());
        }, () -> form.editable() && !bin.oneShot()));
        tile.add(shots).padTop(space("sm")).row();
        Table chips = new Table();
        chips.left();
        if (!bin.oneShot()) {
            chips.add(chip(prefix + "-half", "CustomMekDialog.halfAmmo", bin::half, bin::half, form::editable))
                  .padRight(space("xs"));
        }
        if (bin.hotLoadable()) {
            chips.add(chip(prefix + "-hot", "CustomMekDialog.switchToHotLoading", bin::hotLoad, bin::hotLoad,
                  form::editable));
        }
        if (chips.getChildren().size > 0) {
            tile.add(chips).padTop(space("sm")).row();
        }
        return tile;
    }

    private Table feeds() {
        Table content = new Table();
        content.top().left();
        List<Actor> cells = new ArrayList<>();
        List<UnitEquipmentForm.WeaponFeed> feeds = form.feeds();
        for (int i = 0; i < feeds.size(); i++) {
            UnitEquipmentForm.WeaponFeed feed = feeds.get(i);
            cells.add(kit.formField(feed.label(), dynamicSelect("bay-feed-" + i, feed::bins, feed::index,
                  feed::index, feed::enabled)));
        }
        grid(content, cells, 2);
        return content;
    }

    // ---- Bays and bombs ----

    private Table bays() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        List<BayRow> rows = form.bays();
        for (int i = 0; i < rows.size(); i++) {
            BayRow row = rows.get(i);
            UiKit.Meter meter = kit.meter(row.label(), false, true);
            refreshers.add(() -> meter.set(row.tonnageText(), row.used(), UiTheme.tokenColor(row.used() > 1
                  ? "status.danger" : "accent.primary")));
            content.add(meter).padTop(i == 0 ? 0 : space("lg")).row();
            Table munitions = new Table();
            munitions.top().left();
            List<Actor> cells = new ArrayList<>();
            List<String> names = row.munitions();
            for (int m = 0; m < names.size(); m++) {
                int munition = m;
                Table cell = new Table();
                cell.left();
                Label label = kit.typeLabel(names.get(m), "small", "text.secondary");
                kit.tip(label, true).getActor().setText(row.tooltip(m));
                cell.add(label).growX().minWidth(0).padRight(space("sm"));
                cell.add(stepper("bay-bay-" + i + "-" + m, () -> row.shots(munition), () -> row.maxShots(munition),
                      value -> row.shots(munition, value), () -> row.enabled(munition)));
                cells.add(cell);
            }
            grid(munitions, cells, 2);
            content.add(munitions).padTop(space("sm")).row();
        }
        return content;
    }

    /** A bomb loadout: the points as a bar over a stepper for each bomb type. */
    private Table bombs(BombBay bombBay) {
        String prefix = bombBay.internal() ? "bay-bomb-int-" : "bay-bomb-ext-";
        Table content = new Table();
        content.top().left().defaults().left().growX();
        UiKit.Meter points = name(kit.meter(LobbyUnitBayView.colonless("CustomMekDialog.bombSection"), false, true),
              prefix + "points");
        refreshers.add(() -> points.set(Messages.getString("Lobby.bay.bombPoints", bombBay.used(),
              bombBay.maxPoints()), (bombBay.maxPoints() == 0) ? 0 : (float) bombBay.used() / bombBay.maxPoints(),
              UiTheme.tokenColor("accent.primary")));
        content.add(points).row();
        Table types = new Table();
        types.top().left();
        List<Actor> cells = new ArrayList<>();
        for (BombTypeEnum type : bombBay.types()) {
            Table cell = new Table();
            cell.left();
            Label label = kit.typeLabel(type.getDisplayName(), "small", "text.secondary");
            label.setEllipsis(true);
            cell.add(label).growX().minWidth(0).padRight(space("sm"));
            cell.add(kit.chip(Integer.toString(type.getCost()), UiKit.Tone.NORMAL, true)).padRight(space("sm"));
            cell.add(stepper(prefix + type.name().toLowerCase(Locale.ROOT), () -> bombBay.count(type),
                  () -> bombBay.max(type), value -> bombBay.count(type, value),
                  () -> bombBay.enabled(type) || (bombBay.count(type) > 0 && form.editable())));
            cells.add(cell);
        }
        grid(types, cells, 3);
        content.add(types).padTop(space("md")).row();
        return content;
    }

    // ---- Infantry, battle armor ----

    private Table infantryMunitions() {
        Table content = new Table();
        content.top().left();
        List<Actor> cells = new ArrayList<>();
        List<UnitEquipmentForm.SmallSvRow> rows = form.smallSv();
        for (int i = 0; i < rows.size(); i++) {
            UnitEquipmentForm.SmallSvRow row = rows.get(i);
            Table cell = new Table();
            cell.top().left().defaults().left();
            cell.add(kit.typeLabel(row.label(), "body.strong", "text.primary")).colspan(2).row();
            cell.add(kit.typeLabel(Messages.getString("Lobby.bay.standardShots"), "small", "text.secondary"))
                  .padRight(space("sm"));
            cell.add(stepper("bay-smallsv-" + i + "-standard", row::standard, row::maxStandard, row::standard,
                  form::editable)).row();
            cell.add(kit.typeLabel(Messages.getString("Lobby.bay.infernoShots"), "small", "text.secondary"))
                  .padRight(space("sm"));
            cell.add(stepper("bay-smallsv-" + i + "-inferno", row::inferno, row::maxInferno, row::inferno,
                  form::editable)).row();
            Label text = kit.typeLabel("", "small", "text.secondary");
            refreshers.add(() -> text.setText(row.text()));
            cell.add(text).colspan(2).padTop(space("xs")).row();
            cells.add(cell);
        }
        form.infantrySrm().ifPresent(srm -> cells.add(kit.formField(srm.label(), segmented("bay-infantry-srm",
              srm.choices(), () -> srm.inferno() ? 1 : 0, index -> srm.inferno(index == 1), form::editable))));
        grid(content, cells, 2);
        return content;
    }

    private Table apMounts() {
        Table content = new Table();
        content.top().left();
        List<Actor> cells = new ArrayList<>();
        List<UnitEquipmentForm.ApMount> mounts = form.apMounts();
        for (int i = 0; i < mounts.size(); i++) {
            UnitEquipmentForm.ApMount mount = mounts.get(i);
            cells.add(kit.formField(mount.label(), select("bay-ap-" + i, mount.choices(), mount::index, mount::index,
                  form::editable)));
        }
        grid(content, cells, 2);
        return content;
    }

    private Table manipulators(UnitEquipmentForm.Manipulators manipulators) {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        List<Actor> cells = new ArrayList<>();
        cells.add(kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.ba.leftArm"), select("bay-manip-left",
              manipulators.choices(), manipulators::left, manipulators::left, manipulators::leftEnabled)));
        cells.add(kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.ba.rightArm"),
              select("bay-manip-right", manipulators.choices(), manipulators::right, manipulators::right,
                    manipulators::rightEnabled)));
        Table arms = new Table();
        arms.top().left();
        grid(arms, cells, 2);
        content.add(arms).row();
        if (!manipulators.free().isEmpty()) {
            content.add(kit.typeLabel(manipulators.free(), "small", "text.secondary")).row();
        }
        Table cargo = new Table();
        cargo.left();
        cargo.add(kit.typeLabel(Messages.getString("Lobby.bay.cargo"), "small", "text.secondary"))
              .padRight(space("sm"));
        cargo.add(stepper("bay-manip-cargo", manipulators::cargo, () -> UnitEquipmentForm.Manipulators.MAX_CARGO,
              manipulators::cargo, form::editable));
        Label cargoText = kit.typeLabel("", "small", "text.secondary");
        cargo.add(cargoText).padLeft(space("md"));
        Container<Table> cargoRow = new Container<>();
        cargoRow.left();
        content.add(cargoRow).padTop(space("sm")).row();
        UiSelect glove = select("bay-manip-glove", manipulators.gloves(), manipulators::glove, manipulators::glove,
              form::editable);
        UiFormField gloveField = kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.ba.armGloveWeapon"),
              glove);
        Container<UiFormField> gloveRow = new Container<>();
        gloveRow.left();
        content.add(gloveRow).padTop(space("sm")).row();
        refreshers.add(() -> {
            cargoRow.setActor(manipulators.showsCargo() ? cargo : null);
            cargoText.setText(manipulators.showsCargo() ? manipulators.cargoText() : "");
            gloveRow.setActor(manipulators.showsGlove() ? gloveField : null);
        });
        return content;
    }

    private Table armor(InfantryArmor armor) {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        List<Actor> cells = new ArrayList<>();
        cells.add(kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.labInfantryArmor"),
              select("bay-armor-kit", armor.kits(), armor::kit, armor::kit, form::editable)));
        UiTextField divisor = name(kit.input(armor.divisor(), true), "bay-armor-divisor");
        divisor.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || typed == '.');
        UiFormField divisorField = kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.labDamageDivisor"),
              divisor);
        UiKit.onChange(divisor, () -> {
            armor.divisor(divisor.getText());
            changed();
        });
        refreshers.add(() -> {
            if (!divisor.getText().equals(armor.divisor())) {
                divisor.setText(armor.divisor());
            }
            divisor.setDisabled(!form.editable() || !armor.custom());
            String error = armor.custom() ? armor.divisorError() : "";
            divisorField.error(error.isEmpty() ? null : error);
        });
        cells.add(divisorField);
        Table fields = new Table();
        fields.top().left();
        grid(fields, cells, 2);
        content.add(fields).row();
        Table chips = new Table();
        chips.left();
        BooleanSupplier custom = () -> form.editable() && armor.custom();
        chips.add(chip("bay-armor-encumbering", "CustomMekDialog.labEncumber", armor::encumbering,
              armor::encumbering, custom)).padRight(space("xs"));
        chips.add(chip("bay-armor-space-suit", "CustomMekDialog.labSpaceSuit", armor::spaceSuit, armor::spaceSuit,
              custom)).padRight(space("xs"));
        chips.add(chip("bay-armor-dest", "CustomMekDialog.labDEST", armor::dest, armor::dest, custom))
              .padRight(space("xs"));
        BooleanSupplier sneak = () -> form.editable() && armor.sneakEnabled();
        chips.add(chip("bay-armor-sneak-camo", "CustomMekDialog.labSneakCamo", armor::sneakCamo, armor::sneakCamo,
              sneak)).padRight(space("xs"));
        chips.add(chip("bay-armor-sneak-ir", "CustomMekDialog.labSneakIR", armor::sneakIr, armor::sneakIr, sneak))
              .padRight(space("xs"));
        chips.add(chip("bay-armor-sneak-ecm", "CustomMekDialog.labSneakECM", armor::sneakEcm, armor::sneakEcm,
              sneak));
        content.add(chips).padTop(space("sm")).row();
        return content;
    }

    private Table specializations(InfantryArmor armor) {
        Table content = new Table();
        content.top().left();
        int perRow = 4;
        for (int i = 0; i < ConvInfantry.NUM_SPECIALIZATIONS; i++) {
            int index = i;
            UiButton chip = toggleChip("bay-spec-" + i, ConvInfantry.getSpecializationName(1 << i),
                  () -> armor.specialization(index), value -> armor.specialization(index, value), form::editable);
            kit.tip(chip, true).getActor().setText(ConvInfantry.getSpecializationTooltip(1 << i));
            content.add(chip).left().padRight(space("xs")).padBottom(space("xs"));
            if (i % perRow == perRow - 1) {
                content.row();
            }
        }
        return content;
    }

    private Table disposable(InfantryArmor armor) {
        Table content = new Table();
        content.top().left();
        content.add(kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.labDisposableWeapon"),
              select("bay-disposable", armor.disposables(), armor::disposable, armor::disposable, form::editable)))
              .left();
        return content;
    }

    // ---- Weapons' modes, mines ----

    private Table rapidFire() {
        Table content = new Table();
        content.top().left();
        List<UnitEquipmentForm.RapidFire> guns = form.rapidFire();
        int perRow = 4;
        for (int i = 0; i < guns.size(); i++) {
            UnitEquipmentForm.RapidFire gun = guns.get(i);
            content.add(toggleChip("bay-rapid-" + i, gun.label() + " \u00b7 "
                        + Messages.getString("CustomMekDialog.burstFireMachineGun"), gun::on, gun::on,
                  form::editable)).left().padRight(space("xs")).padBottom(space("xs"));
            if (i % perRow == perRow - 1) {
                content.row();
            }
        }
        return content;
    }

    private Table vrt() {
        UnitEquipmentForm.Vrt vrt = form.vrt().orElseThrow();
        Table content = new Table();
        content.top().left();
        content.add(segmented("bay-vrt", List.of(Messages.getString("CustomMekDialog.VRTModeShort"),
              Messages.getString("CustomMekDialog.VRTModeLong")), () -> vrt.longRange() ? 1 : 0,
              index -> vrt.longRange(index == 1), form::editable)).left();
        return content;
    }

    private Table mines() {
        Table content = new Table();
        content.top().left();
        List<Actor> cells = new ArrayList<>();
        List<UnitEquipmentForm.Mine> mines = form.mines();
        for (int i = 0; i < mines.size(); i++) {
            UnitEquipmentForm.Mine mine = mines.get(i);
            cells.add(kit.formField(mine.label(), select("bay-mine-" + i, mine.choices(), mine::index, mine::index,
                  form::editable)));
        }
        grid(content, cells, 2);
        return content;
    }

    // ---- Electronics ----

    private Table electronics() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        List<UnitEquipmentForm.ModeRow> modes = form.modes();
        for (int i = 0; i < modes.size(); i++) {
            UnitEquipmentForm.ModeRow row = modes.get(i);
            Table line = new Table();
            line.left();
            Label label = kit.typeLabel(row.label(), "small", "text.secondary");
            label.setEllipsis(true);
            line.add(label).width(LobbyUnitBayView.size("dialog.sm") / 2).padRight(space("md"));
            line.add(segmented("bay-mode-" + i, row.choices(), row::index, row::index, form::editable));
            content.add(line).padBottom(space("sm")).row();
        }
        if (modes.size() > 1) {
            content.add(ecmChoice()).row();
        }
        List<Actor> cells = new ArrayList<>();
        form.sensor().ifPresent(sensor -> {
            Table cell = new Table();
            cell.top().left().defaults().left();
            cell.add(kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.labSensors"), select("bay-sensor",
                  sensor.sensors(), sensor::index, sensor::index, form::editable))).growX().row();
            if (sensor.remembers()) {
                cell.add(chip("bay-sensor-remember", "CustomMekDialog.labRememberSensor", sensor::remember,
                      sensor::remember, form::editable)).padTop(space("xs")).row();
            }
            cells.add(cell);
        });
        if (!form.c3().isEmpty()) {
            List<String> labels = form.c3().stream().map(UnitEquipmentForm.C3Choice::label).toList();
            cells.add(kit.formField(LobbyUnitBayView.colonless("CustomMekDialog.labC3"), select("bay-c3", labels,
                  form::c3Index, form::c3Index, form::editable)));
        }
        if (!cells.isEmpty()) {
            Table row = new Table();
            row.top().left();
            grid(row, cells, 2);
            content.add(row).padTop(space("sm")).row();
        }
        return content;
    }

    /**
     * With more than one ECM suite in use, asks inline which stays on, as Swing's ECM suite dialog does: a chip per
     * suite, and the one OK keeps unless the player chooses.
     */
    private Table ecmChoice() {
        Table choice = new Table();
        choice.top().left().defaults().left();
        Label notice = kit.typeLabel("", "small", "status.warning");
        notice.setWrap(true);
        Table chips = new Table();
        chips.left();
        List<UnitEquipmentForm.ModeRow> shown = new ArrayList<>();
        refreshers.add(() -> {
            List<UnitEquipmentForm.ModeRow> inUse = form.ecmInUse();
            notice.setText(form.ecmDefault().map(kept -> Messages.getString("Lobby.bay.ecmKeep", kept.label()))
                  .orElse(""));
            if (inUse.equals(shown)) {
                return;
            }
            shown.clear();
            shown.addAll(inUse);
            chips.clearChildren();
            for (UnitEquipmentForm.ModeRow row : inUse) {
                int number = form.modes().indexOf(row);
                UiButton keep = name(kit.button("button-toggle-compact", "check", row.label() + " · " + row.mode(),
                      null), "bay-ecm-keep-" + number);
                UiKit.onChange(keep, () -> {
                    form.keepEcm(row);
                    changed();
                });
                keep.setDisabled(!form.editable());
                chips.add(keep).padRight(space("xs"));
            }
            choice.invalidateHierarchy();
        });
        choice.add(notice).growX().row();
        choice.add(chips).padTop(space("xs")).row();
        return choice;
    }

    private Table toggles(List<Toggle> toggles) {
        Table content = new Table();
        content.top().left();
        int perRow = 3;
        for (int i = 0; i < toggles.size(); i++) {
            Toggle toggle = toggles.get(i);
            content.add(chip("bay-toggle-" + toggle.name().toLowerCase(Locale.ROOT).replace('_', '-'), toggle.key(),
                  () -> form.value(toggle), value -> form.value(toggle, value), () -> form.enabled(toggle)))
                  .left().padRight(space("xs")).padBottom(space("xs"));
            if (i % perRow == perRow - 1) {
                content.row();
            }
        }
        return content;
    }

    // ---- Controls ----

    /** Lays {@code cells} out {@code columns} to a row, of even width. */
    private static void grid(Table content, List<Actor> cells, int columns) {
        for (int i = 0; i < cells.size(); i++) {
            content.add(cells.get(i)).growX().uniformX().top().left().padRight(space("lg"))
                  .padBottom(space("sm"));
            if (i % columns == columns - 1) {
                content.row();
            }
        }
        for (int i = cells.size() % columns; i > 0 && i < columns; i++) {
            content.add().growX().uniformX();
        }
    }

    private UiButton chip(String name, String key, BooleanSupplier value, Consumer<Boolean> set,
          BooleanSupplier enabled) {
        return toggleChip(name, LobbyUnitBayView.colonless(key), value, set, enabled);
    }

    /** A yes-or-no choice as a chip that is on or off. */
    private UiButton toggleChip(String name, String text, BooleanSupplier value, Consumer<Boolean> set,
          BooleanSupplier enabled) {
        UiButton chip = name(kit.button("button-toggle-compact", null, text, null), name);
        chip.setProgrammaticChangeEvents(false);
        UiKit.onChange(chip, () -> {
            set.accept(!value.getAsBoolean());
            changed();
        });
        refreshers.add(() -> {
            chip.setChecked(value.getAsBoolean());
            chip.setDisabled(!enabled.getAsBoolean());
        });
        return chip;
    }

    private UiSelect select(String name, List<String> items, IntSupplier index, IntConsumer set,
          BooleanSupplier enabled) {
        UiSelect select = name(kit.tokenSelect(true, items.toArray(String[]::new)), name);
        UiKit.onChange(select, () -> {
            set.accept(select.getSelectedIndex());
            changed();
        });
        refreshers.add(() -> {
            int current = index.getAsInt();
            if (current >= 0 && current < select.count() && current != select.getSelectedIndex()) {
                select.select(current);
            }
            select.setDisabled(!enabled.getAsBoolean());
        });
        return select;
    }

    /** A select whose items change with the other choices, such as the bins named with their pending munition. */
    private Container<UiSelect> dynamicSelect(String name, Supplier<List<String>> items, IntSupplier index,
          IntConsumer set, BooleanSupplier enabled) {
        Container<UiSelect> holder = new Container<>();
        holder.left().fillX();
        List<List<String>> shown = new ArrayList<>(List.of(List.of()));
        refreshers.add(() -> {
            List<String> now = items.get();
            if (!now.equals(shown.get(0))) {
                shown.set(0, now);
                UiSelect select = name(kit.tokenSelect(true, now.toArray(String[]::new)), name);
                UiKit.onChange(select, () -> {
                    set.accept(select.getSelectedIndex());
                    changed();
                });
                holder.setActor(select);
            }
            UiSelect select = holder.getActor();
            int current = index.getAsInt();
            if (current >= 0 && current < select.count() && current != select.getSelectedIndex()) {
                select.select(current);
            }
            select.setDisabled(!enabled.getAsBoolean());
        });
        return holder;
    }

    private UiSegmented segmented(String name, List<String> items, IntSupplier index, IntConsumer set,
          BooleanSupplier enabled) {
        UiSegmented segmented = name(kit.tokenSegmented(true, items.toArray(String[]::new)), name);
        for (int i = 0; i < items.size(); i++) {
            segmented.option(i).setName(name + "-" + i);
        }
        segmented.select(index.getAsInt());
        UiKit.onChange(segmented, () -> {
            set.accept(segmented.getSelectedIndex());
            changed();
        });
        refreshers.add(() -> {
            if (segmented.getSelectedIndex() != index.getAsInt()) {
                segmented.select(index.getAsInt());
            }
            segmented.setDisabled(!enabled.getAsBoolean());
        });
        return segmented;
    }

    /**
     * A stepper from 0 to a bound that may move with the other choices, such as the shots of a munition or the bombs
     * the remaining points allow; it is made afresh when the bound moves.
     */
    private Container<UiStepper> stepper(String name, IntSupplier value, IntSupplier max, IntConsumer set,
          BooleanSupplier enabled) {
        Container<UiStepper> holder = new Container<>();
        holder.left();
        int[] bound = { Integer.MIN_VALUE };
        refreshers.add(() -> {
            int most = Math.max(max.getAsInt(), value.getAsInt());
            if (most != bound[0]) {
                bound[0] = most;
                UiStepper stepper = name(kit.stepper(null, value.getAsInt(), 0, most), name);
                name(stepper.down(), name + "-down");
                name(stepper.up(), name + "-up");
                UiKit.onChange(stepper, () -> {
                    set.accept(stepper.value());
                    changed();
                });
                holder.setActor(stepper);
            }
            UiStepper stepper = holder.getActor();
            stepper.value(value.getAsInt());
            boolean on = enabled.getAsBoolean();
            stepper.down().setDisabled(!on || stepper.value() <= 0);
            stepper.up().setDisabled(!on || stepper.value() >= bound[0]);
        });
        return holder;
    }

    /** Shows the form's state. */
    void refresh() {
        refreshers.forEach(Runnable::run);
    }

    /** The page's content. */
    Actor page() {
        refresh();
        return page;
    }
}
