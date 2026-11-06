/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import jakarta.annotation.Nullable;
import megamek.common.icons.Portrait;

/**
 * What the portrait chooser offers, without a toolkit: the categories of the portrait directory as a tree, and the
 * portraits in a category or matching a search, as the Swing chooser ({@code PortraitChooserPanel}) offers them. See
 * {@link IconCatalog}.
 */
public final class PortraitCatalog extends IconCatalog<Portrait> {
    /** @param root the portrait directory's root category, or null when there is no directory */
    public PortraitCatalog(@Nullable Folder root) {
        super(root);
    }

    @Override
    protected Portrait icon(String category, String filename) {
        return new Portrait(category, filename);
    }
}
