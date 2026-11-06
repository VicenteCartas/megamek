/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.annotation.Nullable;
import megamek.common.icons.AbstractIcon;
import megamek.common.util.fileUtils.AbstractDirectory;

/**
 * What an icon chooser offers, without a toolkit: the categories of an icon directory as a tree, and the icons in a
 * category or matching a search. It follows the Swing chooser ({@code abstractIconChooserPanel}): the tree's root,
 * {@link AbstractIcon#ROOT_CATEGORY}, holds any {@link #leading} categories and then the directory's categories in
 * their natural order; a category is named by its path in the tree, each name followed by a slash ("" for the root);
 * a search of three letters or more shows every icon of a category whose path holds it and every other icon whose
 * file name holds it.
 *
 * @param <T> the kind of icon, such as a camo or a portrait
 */
public abstract class IconCatalog<T extends AbstractIcon> {
    /** The shortest search that replaces the category's icons, as in the Swing chooser. */
    public static final int MIN_SEARCH = 3;

    /**
     * A category of the icon directory.
     *
     * @param name     its name in the tree
     * @param path     its directory path, as icons of it carry for their category, such as "Clans/Wolf/"
     * @param items    its icons' file names, in order
     * @param children its sub-categories, in order
     */
    public record Folder(String name, String path, List<String> items, List<Folder> children) {
        public Folder {
            items = List.copyOf(items);
            children = List.copyOf(children);
        }
    }

    /**
     * A category in the tree for the chooser to show.
     *
     * @param id       the category's tree path, such as "Clans/Wolf/" ("" for the root)
     * @param label    its name
     * @param children its sub-categories
     */
    public record Node(String id, String label, List<Node> children) {
        public Node {
            children = List.copyOf(children);
        }
    }

    private final Folder root;

    /** @param root the directory's root category, or null when there is no directory */
    protected IconCatalog(@Nullable Folder root) {
        this.root = root == null ? new Folder("", "", List.of(), List.of()) : root;
    }

    /** The directory's categories as folders; null for no directory. */
    public static @Nullable Folder of(@Nullable AbstractDirectory directory) {
        if (directory == null) {
            return null;
        }
        List<Folder> children = new ArrayList<>();
        for (AbstractDirectory child : directory.getCategories().values()) {
            children.add(of(child));
        }
        return new Folder(directory.getRootName(), directory.getRootPath(),
              new ArrayList<>(directory.getItems().keySet()), children);
    }

    /** The icon of category {@code category}, a directory path, and file {@code filename}. */
    protected abstract T icon(String category, String filename);

    /** The categories the tree lists before the directory's, which are not in the directory; none by default. */
    protected List<Node> leading() {
        return List.of();
    }

    /** The category tree: the root, the {@link #leading} categories, then the directory's categories. */
    public Node tree() {
        List<Node> top = new ArrayList<>(leading());
        for (Folder child : root.children()) {
            top.add(node(child, ""));
        }
        return new Node("", AbstractIcon.ROOT_CATEGORY, top);
    }

    private static Node node(Folder folder, String parent) {
        String id = parent + folder.name() + "/";
        List<Node> children = new ArrayList<>();
        for (Folder child : folder.children()) {
            children.add(node(child, id));
        }
        return new Node(id, folder.name(), children);
    }

    /**
     * The icons of the category {@code id}, a tree path, with those of its sub-categories when {@code nested}; none
     * for a category that is not there.
     */
    public List<T> items(String id, boolean nested) {
        List<T> found = new ArrayList<>();
        Folder folder = folder(id);
        if (folder != null) {
            add(folder, nested, found);
        }
        return found;
    }

    private void add(Folder folder, boolean nested, List<T> found) {
        for (String item : folder.items()) {
            found.add(icon(folder.path(), item));
        }
        if (nested) {
            for (Folder child : folder.children()) {
                add(child, true, found);
            }
        }
    }

    /** The category at tree path {@code id} ("" for the root), or null. */
    public @Nullable Folder folder(String id) {
        Folder at = root;
        for (String name : id.split("/")) {
            if (name.isEmpty()) {
                continue;
            }
            Folder next = null;
            for (Folder child : at.children()) {
                if (child.name().equals(name)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            at = next;
        }
        return at;
    }

    /**
     * The icons that match {@code text}: every icon of a category whose path holds it, and every other icon whose
     * file name holds it, ignoring case; none for a search shorter than {@link #MIN_SEARCH}.
     */
    public List<T> search(String text) {
        List<T> found = new ArrayList<>();
        String searched = text.trim().toLowerCase(Locale.ROOT);
        if (searched.length() < MIN_SEARCH) {
            return found;
        }
        search(root, searched, found);
        return found;
    }

    /** Depth first and a category after its sub-categories, as the directory lists its non-empty categories. */
    private void search(Folder folder, String searched, List<T> found) {
        for (Folder child : folder.children()) {
            search(child, searched, found);
        }
        if (folder.items().isEmpty()) {
            return;
        }
        boolean whole = folder.path().toLowerCase(Locale.ROOT).contains(searched);
        for (String item : folder.items()) {
            if (whole || item.toLowerCase(Locale.ROOT).contains(searched)) {
                found.add(icon(folder.path(), item));
            }
        }
    }

    /**
     * The tree path of {@code icon}'s category, found by its names in the tree, or "" (the root) when it is not there
     * or there is no icon.
     */
    public String categoryOf(@Nullable AbstractIcon icon) {
        if (icon == null) {
            return "";
        }
        Node at = tree();
        StringBuilder id = new StringBuilder();
        for (String name : icon.getCategory().split(Pattern.quote("/"))) {
            Node next = null;
            for (Node child : at.children()) {
                if (child.label().equals(name)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return "";
            }
            id.append(name).append('/');
            at = next;
        }
        return id.toString();
    }

    /** The key that tells two icons of the chooser apart: their category and file name. */
    public static String key(@Nullable AbstractIcon icon) {
        return icon == null ? "" : icon.getCategory() + '|' + icon.getFilename();
    }
}
