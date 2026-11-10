/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Disableable;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The game menu that Esc opens, in the lobby and on the battle board alike: a pause menu of full-width buttons in
 * groups, Resume first. A {@link Kind#PAGE} entry opens its page in the menu's place, below a Back row with the page's
 * name; Back, Backspace or the host's Esc ({@link #back()}) return one page, so a long menu never grows past one screen.
 * A {@link Kind#TOGGLE} entry is a switch, as it applies at once. The host fills its dialog's body with
 * {@link #actor()} and calls {@link #show} whenever its entries may have changed; the column is rebuilt only when what
 * it shows differs, and each entry runs the action of the newest entries.
 * <p>
 * Keys: Up and Down move the focus through the column, Enter and Space press the focused control, and Backspace or
 * Escape go back a page; on the first page Escape is left to the host, which closes the menu.
 */
final class GpuGameMenu {
    /** The name of a page's Back button. */
    static final String BACK = "game-menu-back";

    /** How an entry shows: the main button, a button, a switch, or a button that opens a page. */
    enum Kind { PRIMARY, BUTTON, TOGGLE, PAGE }

    /**
     * One entry of a page.
     *
     * @param id      the entry's name, unique in its page; a page's path is the ids of its entries
     * @param tip     the tooltip, such as the key that runs it, or ""
     * @param checked whether a toggle is on
     * @param action  what a button or toggle does; the menu closes first unless {@code keepsOpen}
     * @param page    the groups of a {@link Kind#PAGE} entry's page, else empty
     */
    record Entry(String id, Kind kind, String label, String tip, boolean enabled, boolean checked, boolean keepsOpen,
          Runnable action, List<List<Entry>> page) {
        Entry {
            List<List<Entry>> groups = new ArrayList<>(page.size());
            for (List<Entry> group : page) {
                groups.add(List.copyOf(group));
            }
            page = List.copyOf(groups);
        }

        /** A button that closes the menu, then runs {@code action}. */
        static Entry button(String id, String label, String tip, boolean enabled, Runnable action) {
            return new Entry(id, Kind.BUTTON, label, tip, enabled, false, false, action, List.of());
        }

        /** The main button, which closes the menu, then runs {@code action}. */
        static Entry primary(String id, String label, Runnable action) {
            return new Entry(id, Kind.PRIMARY, label, "", true, false, false, action, List.of());
        }

        /** A switch, which runs {@code action} and leaves the menu open, so several can be flipped in a row. */
        static Entry toggle(String id, String label, String tip, boolean enabled, boolean checked, Runnable action) {
            return new Entry(id, Kind.TOGGLE, label, tip, enabled, checked, true, action, List.of());
        }

        /** A button that opens {@code groups} as a page. */
        static Entry page(String id, String label, String tip, List<List<Entry>> groups) {
            return new Entry(id, Kind.PAGE, label, tip, true, false, true, () -> { }, groups);
        }

        /** What the column shows of the entry, which is all that decides whether it is rebuilt. */
        private Face face() {
            return new Face(id, kind, label, tip, enabled, checked);
        }
    }

    private record Face(String id, Kind kind, String label, String tip, boolean enabled, boolean checked) { }

    private final UiKit kit;
    private final Runnable close;
    private final Table column = new Table();
    /** The ids of the page entries from the root to the shown page; empty on the root page. */
    private final Deque<String> path = new ArrayDeque<>();
    /** The newest entry of each shown id, so an entry runs what the host showed last. */
    private final Map<String, Entry> entries = new HashMap<>();
    private List<List<Entry>> root = List.of();
    /** What the column shows: the page's path and the faces of its groups; null before the first build. */
    private List<Object> shown;
    /** The page entry Back just left, which takes the focus on its page; null otherwise. */
    private String returnTo;

    /** A menu of {@code kit}'s controls; {@code close} closes the host's dialog. */
    GpuGameMenu(UiKit kit, Runnable close) {
        this.kit = kit;
        this.close = close;
        column.top();
        column.setName("game-menu");
        column.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                // A switch flips itself on Space and Enter
                return !event.isHandled() && key(keycode);
            }
        });
    }

    /** The column, for the host's dialog body. */
    Table actor() {
        return column;
    }

    /** Shows the root page of {@code groups}, as the menu opens. */
    void open(List<List<Entry>> groups) {
        path.clear();
        shown = null;
        show(groups);
    }

    /**
     * Shows the newest entries: the page the player is on, or the deepest page of its path that is still there.
     *
     * @param groups the root page's groups of entries, in order
     */
    void show(List<List<Entry>> groups) {
        root = groups;
        List<List<Entry>> page = page();
        List<Object> faces = new ArrayList<>(path);
        entries.clear();
        for (List<Entry> group : page) {
            List<Face> groupFaces = new ArrayList<>(group.size());
            for (Entry entry : group) {
                groupFaces.add(entry.face());
                entries.put(entry.id(), entry);
            }
            faces.add(groupFaces);
        }
        if (!faces.equals(shown)) {
            boolean moved = shown == null || !path.equals(pathOf(shown));
            shown = faces;
            build(page, moved);
        }
    }

    /** Goes back a page; false on the root page, where the host's Esc closes the menu instead. */
    boolean back() {
        if (path.isEmpty()) {
            return false;
        }
        returnTo = path.removeLast();
        show(root);
        return true;
    }

    /** Whether a page other than the root shows. */
    boolean inPage() {
        return !path.isEmpty();
    }

    /** The shown page's groups; a page that went away takes the player back to its deepest parent still there. */
    private List<List<Entry>> page() {
        List<List<Entry>> page = root;
        int depth = 0;
        for (String id : path) {
            Entry entry = find(page, id);
            if (entry == null || entry.kind() != Kind.PAGE) {
                break;
            }
            page = entry.page();
            depth++;
        }
        while (path.size() > depth) {
            path.removeLast();
        }
        return page;
    }

    private static Entry find(List<List<Entry>> page, String id) {
        for (List<Entry> group : page) {
            for (Entry entry : group) {
                if (entry.id().equals(id)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static List<String> pathOf(List<Object> faces) {
        List<String> ids = new ArrayList<>();
        for (Object face : faces) {
            if (face instanceof String id) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void build(List<List<Entry>> page, boolean moved) {
        Stage stage = column.getStage();
        Actor focused = stage == null ? null : stage.getKeyboardFocus();
        String focusedName = focused != null && focused.isDescendantOf(column) ? focused.getName() : null;
        column.clearChildren();
        float gap = space("sm");
        float group = space("lg");
        if (!path.isEmpty()) {
            Table header = new Table();
            UiButton backButton = kit.button("button-quiet" + UiTheme.COMPACT, "chevron-left",
                  Messages.getString("Lobby.back"), null);
            backButton.setName(BACK);
            UiKit.onChange(backButton, this::back);
            header.add(backButton).left();
            Entry opened = find(parentPage(), path.getLast());
            String title = opened == null ? "" : opened.label();
            header.add(kit.roleTypeLabel("title", title, "text.secondary")).expandX().right();
            column.add(header).growX().row();
        }
        boolean first = path.isEmpty();
        for (List<Entry> entriesOfGroup : page) {
            boolean firstOfGroup = true;
            for (Entry entry : entriesOfGroup) {
                float above = first ? 0 : firstOfGroup ? group : gap;
                column.add(control(entry)).growX().padTop(above).row();
                first = false;
                firstOfGroup = false;
            }
        }
        column.invalidateHierarchy();
        if (moved) {
            scrollTo(returnTo != null ? named(returnTo) : null);
        }
        if (stage != null) {
            // A new page focuses its first control; back on the parent, the entry that opened the page
            Actor target = moved ? (returnTo != null ? named(returnTo) : firstControl()) : named(focusedName);
            stage.setKeyboardFocus(target != null ? target : column);
        }
        returnTo = null;
    }

    /**
     * A page change in a scrolling host: a new page shows from its top, with its Back button; back on the parent, the
     * entry that opened the page is scrolled into view.
     */
    private void scrollTo(Actor target) {
        for (Actor parent = column.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll) {
                scroll.layout();
                if (target == null) {
                    scroll.setScrollY(0);
                } else {
                    Vector2 position = target.localToAscendantCoordinates(scroll.getActor(), new Vector2());
                    scroll.scrollTo(position.x, position.y, target.getWidth(), target.getHeight(), false, true);
                }
                scroll.updateVisualScroll();
                return;
            }
        }
    }

    private List<List<Entry>> parentPage() {
        List<List<Entry>> page = root;
        int index = 0;
        for (String id : path) {
            if (++index == path.size()) {
                break;
            }
            Entry entry = find(page, id);
            if (entry == null) {
                return List.of();
            }
            page = entry.page();
        }
        return page;
    }

    private Actor control(Entry entry) {
        String id = entry.id();
        if (entry.kind() == Kind.TOGGLE) {
            UiChoice toggle = kit.toggleSwitch(entry.label(), entry.checked());
            // Switches line up in a column at the buttons' text inset, not each centred on its own width
            toggle.left().padLeft(space("md"));
            toggle.setName(id);
            toggle.setDisabled(!entry.enabled());
            UiKit.onChange(toggle, () -> run(id));
            tip(toggle, entry.tip());
            return toggle;
        }
        String style = entry.kind() == Kind.PRIMARY ? "button-primary" : "button-secondary";
        UiButton button = kit.button(style, null, entry.label(), null);
        button.setName(id);
        button.setDisabled(!entry.enabled());
        if (entry.kind() == Kind.PAGE) {
            button.setUserObject(Kind.PAGE);
            // A chevron at the end says the button opens a page; the label keeps the middle of the button
            float size = DesignTokens.shipped().size().get("icon.sm");
            float gap = space("sm");
            UiKit.Icon chevron = kit.icon("chevron-right", size, Color.WHITE);
            button.icons.add(chevron);
            button.getLabelCell().expandX().padLeft(size + gap);
            button.add(chevron).padLeft(gap);
            UiKit.onChange(button, () -> {
                Entry newest = entries.get(id);
                if (newest != null && newest.kind() == Kind.PAGE) {
                    path.addLast(id);
                    show(root);
                }
            });
        } else {
            UiKit.onChange(button, () -> run(id));
        }
        tip(button, entry.tip());
        return button;
    }

    private void tip(Actor actor, String tip) {
        if (!tip.isEmpty()) {
            kit.tip(actor, true).getActor().setText(tip);
        }
    }

    private void run(String id) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.enabled()) {
            return;
        }
        if (!entry.keepsOpen()) {
            close.run();
        }
        entry.action().run();
    }

    private boolean key(int keycode) {
        Stage stage = column.getStage();
        if (stage == null) {
            return false;
        }
        Actor focused = stage.getKeyboardFocus();
        switch (keycode) {
            case Keys.UP, Keys.DOWN -> {
                List<Actor> controls = controls();
                if (!controls.isEmpty()) {
                    int at = controls.indexOf(focused);
                    int step = keycode == Keys.DOWN ? 1 : -1;
                    int next = at < 0 ? (step > 0 ? 0 : controls.size() - 1)
                          : Math.floorMod(at + step, controls.size());
                    stage.setKeyboardFocus(controls.get(next));
                }
                return true;
            }
            case Keys.BACKSPACE, Keys.ESCAPE -> {
                // On a page, Escape goes back; on the first page it is left to the host, which closes the menu
                return back();
            }
            case Keys.ENTER, Keys.NUMPAD_ENTER, Keys.SPACE -> {
                return focused instanceof Button button && focused.isDescendantOf(column) && UiDialog.press(button);
            }
            default -> {
                return false;
            }
        }
    }

    /** The column's enabled controls, top to bottom. */
    private List<Actor> controls() {
        List<Actor> controls = new ArrayList<>();
        collect(column, controls);
        return controls;
    }

    private static void collect(Actor actor, List<Actor> found) {
        if (actor instanceof Button button) {
            if (!(button instanceof Disableable disableable && disableable.isDisabled())) {
                found.add(button);
            }
            return;
        }
        if (actor instanceof Table table) {
            for (Actor child : table.getChildren()) {
                collect(child, found);
            }
        }
    }

    /** The page's first control past its Back button, which Esc stands for. */
    private Actor firstControl() {
        List<Actor> controls = controls();
        for (Actor control : controls) {
            if (!BACK.equals(control.getName())) {
                return control;
            }
        }
        return controls.isEmpty() ? null : controls.getFirst();
    }

    private Actor named(String name) {
        for (Actor control : controls()) {
            if (Objects.equals(control.getName(), name)) {
                return control;
            }
        }
        return firstControl();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }
}
