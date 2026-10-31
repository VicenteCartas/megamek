/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.MMConstants;
import megamek.SuiteConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartScreen.Page;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.HostRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.LoadRequest;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;

/** The Load game page: the saves to load, and the reading of a chosen save. */
final class StartLoadPage {
    private static final DateTimeFormatter SAVED_AT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,
          FormatStyle.SHORT);

    private final GpuStartScreen screen;
    /** The Load game page's list, kept while it shows so its scroll stays; and its note, or null. */
    private Actor saveList;
    private Label loadNote;
    private UiButton browseSaves;

    StartLoadPage(GpuStartScreen screen) {
        this.screen = screen;
    }

    /** The saves in MegaMek's save folder, newest first, each a button that reads it. */
    void buildLoadPage() {
        loadNote = null;
        browseSaves = screen.kit.button("button-quiet", null, Messages.getString("StartScreen.browseSaves"), null);
        GpuStartScreen.leftAligned(browseSaves);
        UiKit.onChange(browseSaves, () -> screen.window.chooseFile(new File(MMConstants.SAVEGAME_DIR),
              Messages.getString("MegaMek.SaveGameDialog.title"), new FileNameExtensionFilter(
                    Messages.getString("StartScreen.saveFiles"), "sav", "gz"), file -> {
                  if (screen.page == Page.LOAD) {
                      readSave(file, null);
                  }
              }));
        List<File> saves = StartSaves.list(new File(MMConstants.SAVEGAME_DIR));
        if (saves.isEmpty()) {
            saveList = null;
            loadNote = screen.note(Messages.getString("StartScreen.noSaves"), "text.muted");
            return;
        }
        Table rows = new Table();
        rows.top().left();
        rows.defaults().growX().left();
        for (File save : saves) {
            UiButton row = screen.kit.button("button-quiet", null, StartSaves.title(save), savedAt(save));
            GpuStartScreen.leftAligned(row);
            UiKit.onChange(row, () -> readSave(save, row));
            // The way to the Resume page's choices for a save that would otherwise load at once
            UiButton options = screen.kit.button("button-quiet-compact", null,
                  Messages.getString("StartScreen.saveOptions"), null);
            UiKit.onChange(options, () -> readSave(save, null));
            Table line = new Table();
            line.add(row).growX().minWidth(0);
            line.add(options).padLeft(4);
            rows.add(line).row();
        }
        saveList = screen.kit.scrollList(rows, true);
    }

    void fillLoadColumn() {
        List<Actor> items = new ArrayList<>();
        items.add(screen.overline("StartScreen.loadGame"));
        if (saveList != null) {
            items.add(saveList);
        }
        if (loadNote != null) {
            items.add(loadNote);
        }
        items.add(browseSaves);
        items.add(screen.backButton());
        if (!screen.column.isEmpty() && screen.column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        screen.setColumn(items);
    }

    /** When {@code file} was last written, in the player's locale. */
    static String savedAt(File file) {
        return SAVED_AT.format(Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()));
    }

    /**
     * Reads {@code save} off the GPU thread, then, when {@code quick}, the button that asked, is given and it is the
     * last player's own game against bots, loads it at once, the button saying so meanwhile; otherwise opens it on the
     * Resume page, or says on the Load game page why it can't load.
     */
    void readSave(File save, UiButton quick) {
        if (quick != null && quick.isDisabled()) {
            return;
        }
        screen.generation++;
        if (quick != null) {
            screen.starting(quick, "StartScreen.starting");
        }
        if (screen.page == Page.LOAD) {
            showLoadNote(Messages.getString("StartScreen.readingSave", StartSaves.title(save)), "text.muted");
        }
        screen.background("save", () -> StartSaves.read(save), summary -> {
            ClientPreferences client = PreferenceManager.getClientPreferences();
            String player = quick != null ? summary.quickPlayer(client.getLastPlayerName()) : null;
            if (player != null) {
                String password = client.getLastServerPass();
                screen.window.load(new LoadRequest(summary.file(), new HostRequest(player,
                      password == null ? "" : password, client.getLastServerPort(), false, ""), false,
                      summary.bots()));
                return;
            }
            screen.startEnded();
            if (summary.loadable()) {
                screen.hostPage.resume = summary;
                screen.showPage(Page.RESUME);
            } else {
                if (screen.page != Page.LOAD) {
                    screen.showPage(Page.LOAD);
                }
                showLoadNote(saveProblem(summary), "status.danger");
            }
        });
    }

    private void showLoadNote(String text, String role) {
        loadNote = screen.note(text, role);
        fillLoadColumn();
    }

    /** Why {@code summary}'s save can't be loaded, for the page to say. */
    static String saveProblem(StartSaves.Summary summary) {
        String name = StartSaves.title(summary.file());
        return switch (summary.problem()) {
            case NO_VERSION -> Messages.getString("StartScreen.saveNoVersion", name, SuiteConstants.VERSION);
            case WRONG_VERSION -> Messages.getString("StartScreen.saveWrongVersion", name, summary.version(),
                  SuiteConstants.VERSION);
            default -> Messages.getString("StartScreen.saveUnreadable", name);
        };
    }
}
