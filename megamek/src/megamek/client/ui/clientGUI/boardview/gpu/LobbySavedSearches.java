/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.lobby.SavedAdvancedSearches;
import megamek.client.lobby.SavedAdvancedSearches.Loaded;
import megamek.client.lobby.SavedAdvancedSearches.Saved;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFileDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.logging.MMLogger;

/**
 * The advanced search's saved searches: a header button opening a popover that saves the criteria under a name, and
 * lists the searches saved before (the most recently used first) to load, rename or delete. The searches are the unit
 * selector's ({@link SavedAdvancedSearches}), so a search saved in either shows in both.
 */
final class LobbySavedSearches {
    private static final MMLogger LOGGER = MMLogger.create(LobbySavedSearches.class);
    private static final String KEY = "Lobby.requisition.advanced.saved.";
    /** The popover's width, in control.lg. */
    private static final float WIDTH_CONTROLS = 9;
    /** The saved searches listed before the list scrolls. */
    private static final int ROWS = 8;
    /** The space between the content and the popover's sides, and between the name and Save: md, as the header's. */
    private static final float SIDE = space("md");

    private final UiKit kit;
    private final SavedAdvancedSearches store;
    private final Supplier<RequisitionAdvancedSearch> criteria;
    private final Consumer<Loaded> loaded;
    private final UiButton button;
    private final UiTextField name;
    private final UiButton save;
    private final TextTooltip saveTip;
    private final Label status;
    private final Label count;
    private final UiMenuList list;
    private final Table content = new Table();
    private @Nullable UiPopover popover;
    /** The search whose name is being edited, or {@code null}. */
    private @Nullable Saved renaming;
    /** The search whose delete was pressed once, waiting for the second press, or {@code null}. */
    private @Nullable Saved deleting;
    /** The file the criteria were loaded from, until they are cleared, or {@code null}. */
    private @Nullable Path loadedFrom;

    /**
     * The saved searches of {@code store}; {@code loaded} takes a search loaded from it. A save closes the popover, and
     * only a failure says anything.
     */
    LobbySavedSearches(UiKit kit, SavedAdvancedSearches store, Supplier<RequisitionAdvancedSearch> criteria,
          Consumer<Loaded> loaded) {
        this.kit = kit;
        this.store = store;
        this.criteria = criteria;
        this.loaded = loaded;
        button = kit.button("button-quiet" + UiTheme.COMPACT, "bookmark", text("open"), null);
        button.setName("lobby-requisition-advanced-saved");
        kit.tip(button, true).getActor().setText(text("openTip"));
        UiKit.onChange(button, this::toggle);

        name = kit.input("", true);
        name.setName("lobby-requisition-advanced-saved-name");
        name.setMessageText(text("name"));
        UiKit.onChange(name, this::refreshSave);
        onKeys(name, this::save, null);
        save = kit.button("button-secondary" + UiTheme.COMPACT, "save", text("save"), null);
        save.setName("lobby-requisition-advanced-saved-save");
        saveTip = kit.tip(save, true);
        UiKit.onChange(save, this::save);
        status = kit.typeLabel("", "small", "text.secondary");
        status.setName("lobby-requisition-advanced-saved-status");
        status.setWrap(true);
        list = new UiMenuList(kit, UiTheme.TOKEN_MENU_ROW);
        list.setName("lobby-requisition-advanced-saved-list");
        UiButton fromFile = kit.button("button-quiet" + UiTheme.COMPACT, "folder", text("fromFile"), null);
        fromFile.setName("lobby-requisition-advanced-saved-file");
        kit.tip(fromFile, true).getActor().setText(text("fromFileTip"));
        UiKit.onChange(fromFile, this::openFile);

        Table nameRow = new Table();
        nameRow.add(name).growX().minWidth(0);
        nameRow.add(save).padLeft(SIDE);
        count = kit.typeLabel("", "small", "text.muted");
        count.setName("lobby-requisition-advanced-saved-count");
        // The content's sides match the header's and the gap between the name and Save
        content.top().left().pad(space("xs"), SIDE, SIDE, SIDE);
        content.add(section(text("saveSection"), null)).growX().row();
        content.add(nameRow).growX().padTop(space("xs")).row();
        content.add(status).growX().minWidth(0).padTop(space("xs")).row();
        float rowHeight = size("control.sm");
        content.add(section(text("listSection"), count)).growX().padTop(SIDE).row();
        content.add(kit.scrollList(list, true, false)).growX().maxHeight(rowHeight * ROWS).padTop(space("xs")).row();
        content.add(new Image(kit.skin.getDrawable("token-divider"))).growX().height(1).padTop(space("sm")).row();
        // The quiet button's icon lines up with the rest, its hover reaching into the side space
        content.add(fromFile).left().padTop(space("sm")).padLeft(-fromFile.getPadLeft());
        content.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                // The lobby's own Esc would first only take the focus from the name field
                if (keycode == Input.Keys.ESCAPE) {
                    event.stop();
                    back();
                    return true;
                }
                return false;
            }
        });
        say("", null);
    }

    /** A section's overline title in text.secondary, with {@code trailing} at the right when there is one. */
    private Table section(String title, @Nullable Actor trailing) {
        Table section = new Table();
        section.add(kit.roleTypeLabel("overline", title, "text.secondary")).left().expandX();
        if (trailing != null) {
            section.add(trailing).right();
        }
        return section;
    }

    /** The header button that opens the popover. */
    UiButton button() {
        return button;
    }

    /** The popover while it shows, or {@code null}; a test reads it. */
    @Nullable UiPopover popover() {
        return (popover != null) && popover.isVisible() ? popover : null;
    }

    /** The list of saved searches; a test clicks its rows. */
    UiMenuList list() {
        return list;
    }

    UiTextField nameField() {
        return name;
    }

    Label status() {
        return status;
    }

    /** One Esc step: leaves a rename, then closes the popover; {@code true} when either happened. */
    boolean back() {
        if (renaming != null) {
            renaming = null;
            fill();
            return true;
        }
        return (popover != null) && popover.back();
    }

    /** Closes the popover, as when the view leaves the stage. */
    void close() {
        if (popover != null) {
            popover.cancel();
        }
    }

    void toggle() {
        if (popover != null && popover.isVisible()) {
            popover.cancel();
            return;
        }
        Stage stage = button.getStage();
        if (stage == null) {
            return;
        }
        if (popover == null) {
            popover = new UiPopover(kit, () -> {
                renaming = null;
                deleting = null;
            }, UiTheme.TOKEN);
            popover.setName("lobby-requisition-advanced-saved-popover");
            stage.addActor(popover);
        }
        say("", null);
        fill();
        popover.header(text("title"), text("subtitle")).openedFrom(button).content(content);
        popover.showAnchored(button, Math.max(button.getWidth(), size("control.lg") * WIDTH_CONTROLS));
        if (popover.getStage() != null) {
            popover.getStage().setKeyboardFocus(name);
            name.selectAll();
        }
    }

    // ---- The list ----

    private void fill() {
        list.clearChildren(true);
        List<Saved> shown = store.list();
        count.setText(shown.isEmpty() ? "" : String.valueOf(shown.size()));
        if (shown.isEmpty()) {
            list.add(emptyState()).growX().row();
        }
        UiTextField renameField = null;
        for (Saved each : shown) {
            if (each.equals(renaming)) {
                renameField = kit.input(each.name(), true);
                list.add(renameRow(each, renameField)).growX().row();
                continue;
            }
            boolean editable = store.editable(each);
            String detail = editable ? null : String.valueOf(each.file().getFileName());
            UiButton row = list.item(each.name(), detail, null, false, true, () -> load(each.file()));
            row.setName("lobby-requisition-advanced-saved-" + each.name());
            if (editable) {
                row.trailingAction(tools(each), size("control.sm"));
            }
        }
        refreshSave();
        if (popover != null && popover.isVisible()) {
            popover.reposition();
            if (renameField != null) {
                popover.getStage().setKeyboardFocus(renameField);
                renameField.selectAll();
            }
        }
    }

    /** The list before anything is saved: a bookmark over what to do to fill it. */
    private Table emptyState() {
        Table empty = new Table();
        empty.setName("lobby-requisition-advanced-saved-empty");
        empty.pad(SIDE, 0, SIDE, 0);
        empty.add(kit.icon("bookmark", size("icon.lg"), UiTheme.tokenColor("text.muted"))).row();
        empty.add(kit.typeLabel(text("none"), "body.strong", "text.secondary")).padTop(space("sm")).row();
        Label hint = kit.typeLabel(text("noneHint"), "small", "text.muted");
        hint.setWrap(true);
        hint.setAlignment(Align.center);
        empty.add(hint).growX().minWidth(0).padTop(space("xxs"));
        return empty;
    }

    /** A saved search's rename and delete; the delete asks again before it deletes. */
    private Table tools(Saved saved) {
        Table tools = new Table();
        UiButton rename = UiKit.ownEvents(kit.button("button-icon" + UiTheme.COMPACT, "edit", null, null));
        rename.setName("lobby-requisition-advanced-saved-rename-" + saved.name());
        kit.tip(rename, true).getActor().setText(text("rename"));
        UiKit.onChange(rename, () -> {
            renaming = saved;
            deleting = null;
            fill();
        });
        boolean confirming = saved.equals(deleting);
        UiButton delete = UiKit.ownEvents(confirming
              ? kit.button("button-danger" + UiTheme.COMPACT, null, text("deleteConfirm"), null)
              : kit.button("button-icon" + UiTheme.COMPACT, "delete", null, null));
        delete.setName("lobby-requisition-advanced-saved-delete-" + saved.name());
        kit.tip(delete, true).getActor().setText(text(confirming ? "deleteConfirmTip" : "delete"));
        UiKit.onChange(delete, () -> {
            if (confirming) {
                delete(saved);
            } else {
                deleting = saved;
                renaming = null;
                fill();
            }
        });
        tools.add(rename);
        tools.add(delete).padLeft(space("xs"));
        return tools;
    }

    /** A saved search's name in {@code field}: Enter renames it, Esc leaves it as it was. */
    private Table renameRow(Saved saved, UiTextField field) {
        field.setName("lobby-requisition-advanced-saved-renaming");
        onKeys(field, () -> rename(saved, field.getText()), () -> {
            renaming = null;
            fill();
        });
        UiButton done = kit.button("button-icon" + UiTheme.COMPACT, "check", null, null);
        kit.tip(done, true).getActor().setText(text("rename"));
        UiKit.onChange(done, () -> rename(saved, field.getText()));
        Table row = new Table();
        row.add(field).growX().minWidth(0);
        row.add(done).padLeft(space("xs"));
        return row;
    }

    private void refreshSave() {
        boolean replaces = store.exists(name.getText());
        save.setText(kit.cased("button", text(replaces ? "replace" : "save")));
        save.setDisabled((store.fileFor(name.getText()) == null) || criteria.get().isEmpty());
        saveTip.getActor().setText(text(criteria.get().isEmpty() ? "saveEmptyTip"
              : replaces ? "replaceTip" : "saveTip"));
    }

    // ---- Actions ----

    private void save() {
        if (save.isDisabled()) {
            return;
        }
        String named = name.getText().strip();
        try {
            Saved saved = store.save(named, criteria.get(), loadedFrom);
            name.setText(saved.name());
            close();
        } catch (IOException | RuntimeException exception) {
            LOGGER.error(exception, "Saving the advanced search {}", named);
            say(Messages.getString(KEY + "saveFailed", reason(exception)), "status.danger");
        }
    }

    private void rename(Saved saved, String to) {
        if (store.fileFor(to) == null) {
            say(text("badName"), "status.danger");
            return;
        }
        try {
            Saved renamed = store.rename(saved, to);
            renaming = null;
            if (saved.file().equals(loadedFrom)) {
                loadedFrom = renamed.file();
            }
            if (name.getText().strip().equals(saved.name())) {
                name.setText(renamed.name());
            }
            fill();
            say("", null);
        } catch (FileAlreadyExistsException exception) {
            say(Messages.getString(KEY + "exists", to.strip()), "status.danger");
        } catch (IOException | RuntimeException exception) {
            LOGGER.error(exception, "Renaming the advanced search {}", saved.file());
            say(Messages.getString(KEY + "renameFailed", reason(exception)), "status.danger");
        }
    }

    private void delete(Saved saved) {
        try {
            store.delete(saved);
            deleting = null;
            if (saved.file().equals(loadedFrom)) {
                loadedFrom = null;
            }
            fill();
            say("", null);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error(exception, "Deleting the advanced search {}", saved.file());
            say(Messages.getString(KEY + "deleteFailed", reason(exception)), "status.danger");
        }
    }

    /**
     * Forgets the file the criteria were loaded from, once the criteria a replace would keep from it (those this search
     * has no controls for) are cleared.
     */
    void forget() {
        loadedFrom = null;
    }

    /**
     * Loads the search in {@code file}, which replaces the criteria of every tab, and closes the popover; when it
     * cannot, the popover shows why.
     */
    void load(Path file) {
        try {
            Loaded search = store.load(file);
            name.setText(search.name());
            loadedFrom = file;
            close();
            loaded.accept(search);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error(exception, "Loading the advanced search {}", file);
            if (popover == null || !popover.isVisible()) {
                toggle();
            } else {
                fill();
            }
            say(Messages.getString(KEY + "loadFailed", String.valueOf(file.getFileName()), reason(exception)),
                  "status.danger");
        }
    }

    private void openFile() {
        Stage stage = button.getStage();
        close();
        if (stage == null) {
            return;
        }
        UiFileDialog dialog = new UiFileDialog(kit);
        dialog.show(stage, new UiFileDialog.Options(text("fileTitle"), UiFileDialog.Mode.OPEN,
                    "lobby.advancedSearch", store.folder().toAbsolutePath(), "",
                    new UiFileDialog.Filter(text("fileFilter"), "json")),
              path -> {
                  dialog.close();
                  if (path != null) {
                      load(path);
                  }
              });
    }

    // ---- Helpers ----

    private void say(String message, @Nullable String role) {
        status.setText(message);
        if (role != null) {
            status.getStyle().fontColor = UiTheme.tokenColor(role);
        }
        content.getCell(status).padTop(message.isEmpty() ? 0 : space("xs"));
        status.setVisible(!message.isEmpty());
        if (popover != null && popover.isVisible()) {
            popover.reposition();
        }
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return (message == null || message.isBlank()) ? exception.getClass().getSimpleName() : message;
    }

    /**
     * Enter in {@code field} runs {@code enter}, and Esc runs {@code escape} when there is one; a key it runs does not
     * reach the lobby.
     */
    private static void onKeys(UiTextField field, Runnable enter, @Nullable Runnable escape) {
        field.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                    event.stop();
                    enter.run();
                    return true;
                }
                if (keycode == Input.Keys.ESCAPE && escape != null) {
                    event.stop();
                    escape.run();
                    return true;
                }
                return false;
            }
        });
    }

    private static String text(String key) {
        return Messages.getString(KEY + key);
    }

    private static float space(String token) {
        return DesignTokens.shipped().space().get(token);
    }

    private static float size(String token) {
        return DesignTokens.shipped().size().get(token);
    }
}
