/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;

/**
 * Reusable desktop file picker drawn entirely in the caller's Scene2D stage. Construct/show/close on the render
 * thread; close with the owning scene. The kit and stage remain caller-owned. Directory enumeration and validation
 * run on interruptible workers; only the current operation is applied in act(), so a closed/reused dialog cannot
 * receive stale results. Callbacks run on the render thread, once, with a path or null for cancellation.
 *
 * The preference key separates locations for boards, panoramas, etc. Locations use Java's per-user desktop
 * preferences. All paths use the host filesystem, including Windows drives/UNC paths and Unix roots/symlinks.
 */
public final class UiFileDialog implements AutoCloseable {
    /** FOLDER lists only folders and chooses one: the selected folder, or the folder shown. */
    public enum Mode { OPEN, SAVE, FOLDER }

    /** Extensions have no leading dot. An empty list accepts any regular file. */
    public record Filter(String description, List<String> extensions) {
        public Filter {
            Objects.requireNonNull(description);
            extensions = extensions.stream().map(value -> value.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT)).toList();
            if (extensions.stream().anyMatch(value -> value.isBlank() || value.contains("/") || value.contains("\\"))) {
                throw new IllegalArgumentException("Invalid file extension");
            }
        }
        public Filter(String description, String... extensions) { this(description, List.of(extensions)); }
        public boolean accepts(Path file) {
            if (file.getFileName() == null) { return false; }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            return extensions.isEmpty() || extensions.stream().anyMatch(extension -> name.endsWith("." + extension));
        }
    }

    public record Options(String title, Mode mode, String preferenceKey, Path directory, String fileName, Filter filter) {
        public Options {
            Objects.requireNonNull(title);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(preferenceKey);
            Objects.requireNonNull(fileName);
            Objects.requireNonNull(filter);
        }
    }

    private final UiKit ui;
    private final Preferences preferences;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final UiModal layer;
    private final Table body;
    private final Table places = new Table();
    private final TextField location;
    private final TextField name;
    private final TextField search;
    private final com.badlogic.gdx.scenes.scene2d.ui.List<FileDialogPaths.Entry> entries;
    private final ScrollPane scroll;
    private final Label status;
    private final TextTooltip statusTip;
    private final Label filterLabel;
    private final Table nameRow = new Table();
    private final UiButton back, forward, up, accept;
    private final List<Actor> tabOrder = new ArrayList<>();
    private final List<Path> history = new ArrayList<>();
    private List<FileDialogPaths.Entry> listing = List.of();
    private int historyAt = -1;
    private Path directory;
    private Options options;
    private Consumer<Path> done;
    private Actor previousFocus;
    private Actor previousScroll;
    private Future<Runnable> pending;
    private UiModal overwrite;
    private boolean updating;
    private boolean disposed;
    private Path lastClick;

    public UiFileDialog(UiKit ui) {
        this(ui, Preferences.userNodeForPackage(UiFileDialog.class));
    }

    UiFileDialog(UiKit ui, Preferences preferences) {
        this.ui = ui;
        this.preferences = preferences;
        layer = new UiModal(ui, () -> finish(null), false);
        layer.setName("file-dialog");
        layer.setFillParent(true);
        body = new Table() {
            @Override public void act(float delta) {
                poll();
                layer.focus();
                super.act(delta);
            }
        };
        body.defaults().growX();
        location = field("file-location");
        location.setMessageText(UiKit.text("FileDialog.location"));
        name = field("file-name");
        search = field("file-search");
        search.setProgrammaticChangeEvents(true);
        search.setMessageText(UiKit.text("FileDialog.search"));
        UiKit.onChange(search, this::filter);

        Table navigation = new Table();
        back = button("file-back", "FileDialog.back", () -> navigate(history.get(historyAt - 1), historyAt - 1));
        forward = button("file-forward", "FileDialog.forward", () -> navigate(history.get(historyAt + 1), historyAt + 1));
        up = button("file-up", "FileDialog.up", this::parent);
        navigation.add(back).padRight(4);
        navigation.add(forward).padRight(4);
        navigation.add(up).padRight(8);
        navigation.add(location).growX().minWidth(0).height(32);
        UiButton go = button("file-go", "FileDialog.go", this::go);
        UiButton refresh = button("file-refresh", "FileDialog.refresh", this::refresh);
        navigation.add(go).padLeft(6);
        navigation.add(refresh).padLeft(6);
        body.add(navigation).padBottom(10).row();
        body.add(search).height(32).padBottom(10).row();

        var style = new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(ui.skin.getFont("hud-body"),
              UiTheme.TEXT, UiTheme.TEXT, ui.skin.getDrawable("button-auto"));
        entries = new com.badlogic.gdx.scenes.scene2d.ui.List<>(style) {
            @Override public String toString(FileDialogPaths.Entry entry) { return entry.name() + (entry.directory() ? "/" : ""); }
        };
        entries.setName("file-entries");
        entries.getSelection().setRequired(false);
        entries.getSelection().setMultiple(false);
        entries.setTypeToSelect(true);
        // A typed/pasted filename replaces the list selection; programmatic list-to-name updates do not fire this.
        UiKit.onChange(name, () -> entries.getSelection().clear());
        scroll = ui.scrollList(entries);
        scroll.setName("file-scroll");
        UiKit.onChange(entries, () -> {
            if (updating) { return; }
            var selected = entries.getSelected();
            if (selected != null && !selected.directory()) { name.setText(selected.name()); }
            revealSelection();
        });
        entries.addListener(new ClickListener(Input.Buttons.LEFT) {
            @Override public void clicked(InputEvent event, float x, float y) {
                var selected = entries.getItemAt(y);
                if (selected == null) { lastClick = null; setTapCount(0); return; }
                if (!selected.path().equals(lastClick)) { setTapCount(1); }
                else if (getTapCount() == 2) { setTapCount(0); activate(); }
                lastClick = selected.path();
            }
        });
        places.top().left();
        places.defaults().growX().minWidth(0).padBottom(4);
        Table browser = new Table();
        browser.add(ui.scrollList(places)).width(145).growY().padRight(12);
        browser.add(scroll).grow().minSize(0);
        body.add(browser).grow().minSize(0).row();
        filterLabel = ui.label("", "hud-small", 12, UiTheme.MUTED);
        filterLabel.setEllipsis(true);
        body.add(filterLabel).minWidth(0).padTop(10).row();
        nameRow.add(ui.label(UiKit.text("FileDialog.name"), "hud-small", 13, UiTheme.TEXT)).padRight(10);
        nameRow.add(name).height(32).growX().minWidth(0);
        body.add(nameRow).padTop(8).row();
        status = ui.label("", "hud-small", 12, UiTheme.MUTED);
        status.setEllipsis(true);
        status.setName("file-status");
        statusTip = ui.tip(status);
        Table footer = new Table();
        footer.add(status).growX().minWidth(0).padRight(12);
        UiButton cancel = button("file-cancel", "Cancel", () -> finish(null));
        footer.add(cancel).minHeight(34).padRight(8);
        accept = button("file-accept", "FileDialog.open", this::submit);
        footer.add(accept).minHeight(34);
        body.add(footer).padTop(12);
        tabOrder.addAll(List.of(back, forward, up, location, go, refresh, search, entries, name, cancel, accept));
        layer.addCaptureListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int key) {
                if (keys(key)) { event.stop(); return true; }
                return false;
            }
            @Override public boolean keyTyped(InputEvent event, char character) {
                // Focus/activation was handled on keyDown. Do not insert its typed event into the new text field.
                if (character == '\t' || character == '\r' || character == '\n') { event.stop(); return true; }
                return false;
            }
        });
    }

    /** Reopens this picker; an already-open picker is left intact. */
    public void show(Stage stage, Options options, Consumer<Path> done) {
        if (disposed) { throw new IllegalStateException("File dialog is closed"); }
        if (isOpen()) { return; }
        this.options = options;
        this.done = Objects.requireNonNull(done);
        previousFocus = stage.getKeyboardFocus();
        previousScroll = stage.getScrollFocus();
        directory = null;
        history.clear(); historyAt = -1; listing = List.of();
        location.setText(""); name.setText(options.fileName()); search.setText("");
        entries.clearItems(); places.clearChildren(); lastClick = null;
        filterLabel.setText(options.filter().description());
        accept.setText(UiTheme.upper(UiKit.text(switch (options.mode()) {
            case OPEN -> "FileDialog.open";
            case SAVE -> "FileDialog.save";
            case FOLDER -> "FileDialog.chooseFolder";
        })));
        nameRow.setVisible(options.mode() != Mode.FOLDER);
        Table dialog = ui.dialog(options.title(), () -> finish(null));
        dialog.setName("file-dialog-card");
        dialog.setTouchable(Touchable.enabled);
        dialog.add(body).grow().minSize(0);
        layer.clearChildren();
        layer.add(dialog).grow().maxWidth(960).maxHeight(640).pad(16);
        stage.addActor(layer);
        layer.open(true);
        layer.validate();
        stage.setKeyboardFocus(options.mode() == Mode.FOLDER ? entries : name);
        work(() -> {
            String remembered = preferences.get(options.preferenceKey(), "");
            Path initial = FileDialogPaths.initial(remembered, options.directory());
            var found = FileDialogPaths.list(initial, false, options.filter());
            var shortcuts = FileDialogPaths.places(options.directory());
            return () -> { showPlaces(shortcuts); listed(initial, found, -1); };
        });
    }

    public boolean isOpen() { return layer.getStage() != null && layer.isVisible(); }

    private TextField field(String id) {
        TextField field = new TextField("", ui.skin, "hud");
        field.setName(id);
        field.setOnlyFontChars(false);
        field.setFocusTraversal(false);
        return field;
    }

    private UiButton button(String id, String key, Runnable action) {
        UiButton button = ui.button("hud-mini", null, UiKit.text(key), null);
        var style = new TextButton.TextButtonStyle(button.getStyle());
        style.focused = ui.skin.getDrawable("button-auto");
        button.setStyle(style);
        button.setName(id);
        UiKit.onChange(button, action);
        return button;
    }

    private void showPlaces(List<Path> paths) {
        places.clearChildren();
        places.add(ui.caption(UiKit.text("FileDialog.places"))).left().padBottom(10).row();
        for (Path path : paths) {
            String label = path.equals(FileDialogPaths.home()) ? UiKit.text("FileDialog.home")
                  : path.getFileName() == null ? path.toString() : path.getFileName().toString();
            UiButton place = ui.button("hud-mini", null, label, null);
            place.getLabel().setEllipsis(true);
            place.getCell(place.getLabel()).growX().minWidth(0);
            UiKit.onChange(place, () -> navigate(path, -1));
            ui.tip(place).getActor().setText(path.toString());
            places.add(place).minHeight(30).row();
        }
    }

    private void parent() { if (directory != null && directory.getParent() != null) { navigate(directory.getParent(), -1); } }
    private void refresh() { if (directory != null) { navigate(directory, historyAt); } }
    private void go() {
        try { navigate(FileDialogPaths.resolve(directory == null ? Path.of(".") : directory, location.getText()), -1); }
        catch (IllegalArgumentException failure) { error(failure); }
    }

    private void navigate(Path path, int historyIndex) {
        Filter filter = options.filter();
        work(() -> {
            var found = FileDialogPaths.list(path, false, filter);
            return () -> listed(path, found, historyIndex);
        });
    }

    private void listed(Path path, List<FileDialogPaths.Entry> found, int historyIndex) {
        boolean changed = !path.equals(directory);
        directory = path;
        listing = found;
        location.setText(path.toString());
        location.setCursorPosition(location.getText().length());
        if (historyIndex >= 0) { historyAt = historyIndex; }
        else if (historyAt < 0 || !history.get(historyAt).equals(path)) {
            history.subList(historyAt + 1, history.size()).clear();
            history.add(path); historyAt = history.size() - 1;
        }
        if (changed) {
            search.setText("");
            if (options.mode() == Mode.OPEN) { name.setText(""); }
        }
        preferences.put(options.preferenceKey(), path.toString());
        filter(); controls();
    }

    private void filter() {
        if (entries == null) { return; }
        Path selected = entries.getSelected() == null ? null : entries.getSelected().path();
        String query = FileDialogPaths.search(search.getText());
        Array<FileDialogPaths.Entry> visible = new Array<>();
        listing.stream().filter(entry -> options.mode() != Mode.FOLDER || entry.directory())
              .filter(entry -> FileDialogPaths.search(entry.name()).contains(query)).forEach(visible::add);
        updating = true;
        entries.setItems(visible);
        entries.getSelection().clear();
        for (var entry : visible) { if (entry.path().equals(selected)) { entries.setSelected(entry); } }
        updating = false;
        scroll.setScrollY(0);
        lastClick = null;
        if (pending == null) {
            status.setColor(UiTheme.MUTED);
            status.setText(UiKit.text(visible.isEmpty() ? "FileDialog.empty" : "FileDialog.count", visible.size));
        }
    }

    private void revealSelection() {
        int index = entries.getSelectedIndex();
        if (index >= 0) {
            scroll.layout();
            float height = entries.getItemHeight();
            scroll.scrollTo(0, entries.getHeight() - (index + 1) * height, 1, height);
        }
    }

    private void activate() {
        var selected = entries.getSelected();
        if (selected == null) { return; }
        if (selected.directory()) { navigate(selected.path(), -1); }
        else { name.setText(selected.name()); submit(); }
    }

    private void submit() {
        if (directory == null || pending != null) { return; }
        var selected = entries.getSelected();
        if (options.mode() == Mode.FOLDER) {
            finish(selected != null && selected.directory() ? selected.path() : directory);
            return;
        }
        if (selected != null && selected.directory()) {
            navigate(selected.path(), -1); return;
        }
        if (name.getText().isBlank()) {
            error(new IOException(UiKit.text("FileDialog.chooseFile")));
            return;
        }
        try {
            Path path = FileDialogPaths.resolve(directory, name.getText());
            Options choice = options;
            work(() -> {
                if (Files.isDirectory(path)) {
                    var found = FileDialogPaths.list(path, false, choice.filter());
                    return () -> listed(path, found, -1);
                }
                Path target = choice.mode() == Mode.SAVE ? FileDialogPaths.saveTarget(path, choice.filter()) : path;
                if (choice.mode() == Mode.OPEN) {
                    if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
                        throw new IOException(UiKit.text("FileDialog.unreadable", target));
                    }
                    if (!choice.filter().accepts(target)) { throw new IOException(UiKit.text("FileDialog.wrongType", choice.filter().description())); }
                } else if (target.getParent() == null || !Files.isDirectory(target.getParent())
                      || !Files.isWritable(target.getParent()) || Files.exists(target) && (!Files.isRegularFile(target) || !Files.isWritable(target))) {
                    throw new IOException(UiKit.text("FileDialog.unwritable", target));
                }
                boolean exists = choice.mode() == Mode.SAVE && Files.exists(target);
                return () -> {
                    if (exists) {
                        overwrite = UiMessageDialog.show(ui, layer.getStage(), UiKit.text("FileDialog.replaceTitle"),
                              UiKit.text("FileDialog.replace", target.getFileName()),
                              List.of(UiKit.text("FileDialog.replaceButton"), UiKit.text("Cancel")), answer -> {
                                  overwrite = null;
                                  if (answer == 0) { finish(target); }
                              });
                    } else { finish(target); }
                };
            });
        } catch (IllegalArgumentException failure) { error(failure); }
    }

    private boolean keys(int key) {
        Stage stage = layer.getStage();
        boolean command = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
              || Gdx.input.isKeyPressed(Input.Keys.SYM);
        boolean alt = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);
        if (key == Input.Keys.ESCAPE) { finish(null); return true; }
        if (key == Input.Keys.TAB) {
            int direction = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT) ? -1 : 1;
            int index = tabOrder.indexOf(stage.getKeyboardFocus());
            do { index = Math.floorMod(index + direction, tabOrder.size()); }
            while (tabOrder.get(index) instanceof UiButton button && button.isDisabled());
            stage.setKeyboardFocus(tabOrder.get(index));
            return true;
        }
        if (command && key == Input.Keys.L) { stage.setKeyboardFocus(location); location.selectAll(); return true; }
        if (command && key == Input.Keys.F) { stage.setKeyboardFocus(search); search.selectAll(); return true; }
        if (key == Input.Keys.F5 || command && key == Input.Keys.R) { refresh(); return true; }
        if (alt && key == Input.Keys.UP || key == Input.Keys.BACKSPACE && stage.getKeyboardFocus() == entries) { parent(); return true; }
        if (alt && key == Input.Keys.LEFT && historyAt > 0) { navigate(history.get(historyAt - 1), historyAt - 1); return true; }
        if (alt && key == Input.Keys.RIGHT && historyAt + 1 < history.size()) { navigate(history.get(historyAt + 1), historyAt + 1); return true; }
        if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER) {
            if (stage.getKeyboardFocus() instanceof UiButton button) {
                if (!button.isDisabled()) { button.fire(new ChangeEvent()); }
            } else if (stage.getKeyboardFocus() == location) { go(); }
            else if (stage.getKeyboardFocus() == search) {
                stage.setKeyboardFocus(entries);
                if (entries.getSelected() == null && entries.getItems().size > 0) { entries.setSelectedIndex(0); }
                activate();
            }
            else if (stage.getKeyboardFocus() == entries) { activate(); }
            else { submit(); }
            return true;
        }
        if (key == Input.Keys.DOWN && stage.getKeyboardFocus() == search) {
            stage.setKeyboardFocus(entries);
            if (entries.getItems().size > 0) { entries.setSelectedIndex(0); }
            return true;
        }
        if ((key == Input.Keys.PAGE_UP || key == Input.Keys.PAGE_DOWN) && stage.getKeyboardFocus() == entries) {
            if (entries.getItems().notEmpty()) {
                int step = Math.max(1, (int) (scroll.getHeight() / entries.getItemHeight()) - 1);
                int index = entries.getSelectedIndex() + (key == Input.Keys.PAGE_UP ? -step : step);
                entries.setSelectedIndex(Math.max(0, Math.min(entries.getItems().size - 1, index)));
            }
            return true;
        }
        return false;
    }

    @FunctionalInterface private interface Operation { Runnable run() throws IOException; }

    private void work(Operation operation) {
        if (pending != null) { pending.cancel(true); }
        pending = worker.submit(() -> {
            try { return operation.run(); }
            catch (IOException | RuntimeException failure) { return () -> error(failure); }
        });
        status.setColor(UiTheme.MUTED);
        status.setText(UiKit.text("FileDialog.loading"));
        controls();
    }

    private void poll() {
        if (pending == null || !pending.isDone()) { return; }
        Future<Runnable> completed = pending;
        pending = null;
        status.setText(UiKit.text(entries.getItems().isEmpty() ? "FileDialog.empty" : "FileDialog.count", entries.getItems().size));
        statusTip.getActor().setText("");
        try { completed.get().run(); }
        catch (Exception failure) { error(failure); }
        controls();
    }

    private void controls() {
        back.setDisabled(historyAt <= 0);
        forward.setDisabled(historyAt + 1 >= history.size());
        up.setDisabled(directory == null || directory.getParent() == null);
        accept.setDisabled(directory == null || pending != null);
    }

    private void error(Throwable failure) {
        status.setColor(UiTheme.CORAL);
        String key = failure instanceof java.nio.file.NoSuchFileException ? "FileDialog.notFound"
              : failure instanceof java.nio.file.AccessDeniedException ? "FileDialog.denied"
              : failure instanceof java.nio.file.NotDirectoryException ? "FileDialog.notDirectory" : "FileDialog.error";
        status.setText(UiKit.text(key, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        statusTip.getActor().setText(status.getText());
        // The displayed location must continue to describe the list after a failed navigation.
        if (directory != null) { location.setText(directory.toString()); }
    }

    private void finish(Path path) {
        if (!isOpen()) { return; }
        if (path != null && path.getParent() != null) { preferences.put(options.preferenceKey(), path.getParent().toString()); }
        Consumer<Path> callback = done;
        dismiss();
        callback.accept(path);
    }

    private void dismiss() {
        if (pending != null) { pending.cancel(true); pending = null; }
        if (overwrite != null) { overwrite.open(false); overwrite.remove(); overwrite = null; }
        Stage stage = layer.getStage();
        layer.open(false); layer.remove(); done = null;
        if (stage != null) {
            if (previousFocus != null && previousFocus.getStage() == stage && previousFocus.ascendantsVisible()) { stage.setKeyboardFocus(previousFocus); }
            if (previousScroll != null && previousScroll.getStage() == stage && previousScroll.ascendantsVisible()) { stage.setScrollFocus(previousScroll); }
        }
        previousFocus = null; previousScroll = null;
    }

    /** Cancels pending work without invoking callbacks into a scene being disposed. */
    @Override public void close() { disposed = true; dismiss(); worker.shutdownNow(); }
}
