/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.common.icons.Camouflage;

/**
 * What the camo chooser offers, without a toolkit: the categories of the camo directory as a tree, with the colour
 * camos as their own category first, and the camos in a category or matching a search, as the Swing chooser
 * ({@code CamoChooserPanel}) offers them. See {@link IconCatalog}.
 */
public final class CamoCatalog extends IconCatalog<Camouflage> {
    private final List<String> colours;

    /**
     * @param root    the camo directory's root category, or null when there is no directory
     * @param colours the colour camos' names, such as the {@code PlayerColour} names
     */
    public CamoCatalog(@Nullable Folder root, List<String> colours) {
        super(root);
        this.colours = List.copyOf(colours);
    }

    @Override
    protected Camouflage icon(String category, String filename) {
        return new Camouflage(category, filename);
    }

    @Override
    protected List<Node> leading() {
        return List.of(new Node(Camouflage.COLOUR_CAMOUFLAGE + "/", Camouflage.COLOUR_CAMOUFLAGE, List.of()));
    }

    /** The camos of the category {@code id}, as {@link IconCatalog#items}; the colour camos for their category. */
    @Override
    public List<Camouflage> items(String id, boolean nested) {
        if (id.startsWith(Camouflage.COLOUR_CAMOUFLAGE)) {
            List<Camouflage> found = new ArrayList<>();
            for (String colour : colours) {
                found.add(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, colour));
            }
            return found;
        }
        return super.items(id, nested);
    }

    /** The camo as the chooser lists it, without rotation, scale or overlay, to find it among the items. */
    public static Camouflage plain(Camouflage camo) {
        return new Camouflage(camo.getCategory(), camo.getFilename());
    }
}
