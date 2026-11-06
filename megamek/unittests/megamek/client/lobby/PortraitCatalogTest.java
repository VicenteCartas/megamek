/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import megamek.client.lobby.IconCatalog.Folder;
import megamek.client.lobby.IconCatalog.Node;
import megamek.common.icons.AbstractIcon;
import megamek.common.icons.Portrait;
import org.junit.jupiter.api.Test;

class PortraitCatalogTest {
    private final Folder female = new Folder("Female", "MekWarrior/Female/", List.of("Anna.png"), List.of());
    private final Folder warriors = new Folder("MekWarrior", "MekWarrior/", List.of("Generic.png"),
          List.of(female));
    private final Folder pilots = new Folder("Pilot", "Pilot/", List.of("Ace.png"), List.of());
    private final PortraitCatalog catalog = new PortraitCatalog(new Folder("", "", List.of("default.gif"),
          List.of(warriors, pilots)));

    @Test
    void theTreeListsOnlyTheDirectorysCategories() {
        Node tree = catalog.tree();
        assertEquals(AbstractIcon.ROOT_CATEGORY, tree.label());
        assertEquals(List.of("MekWarrior/", "Pilot/"), tree.children().stream().map(Node::id).toList());
        assertEquals("MekWarrior/Female/", tree.children().getFirst().children().getFirst().id());
    }

    @Test
    void aCategoryHoldsPortraitsAndItsSubCategoriesOnesWhenNested() {
        assertEquals(List.of(new Portrait("MekWarrior/", "Generic.png")), catalog.items("MekWarrior/", false));
        assertEquals(List.of(new Portrait("MekWarrior/", "Generic.png"), new Portrait("MekWarrior/Female/",
              "Anna.png")), catalog.items("MekWarrior/", true));
        assertEquals(List.of(new Portrait("", "default.gif")), catalog.items("", false));
        assertEquals(4, catalog.items("", true).size(), "the root holds every portrait when nested");
    }

    @Test
    void aSearchMatchesCategoryPathsAndFileNames() {
        assertEquals(List.of(new Portrait("MekWarrior/Female/", "Anna.png")), catalog.search("fem"));
        assertEquals(List.of(new Portrait("Pilot/", "Ace.png")), catalog.search("ACE"));
        assertEquals(List.of(), catalog.search("an"), "a search needs three letters");
    }

    @Test
    void aPortraitsCategoryIsFoundInTheTree() {
        assertEquals("MekWarrior/Female/", catalog.categoryOf(new Portrait("MekWarrior/Female/", "Anna.png")));
        assertEquals("", catalog.categoryOf(new Portrait()), "the default portrait is in the root");
        assertEquals("", catalog.categoryOf(new Portrait("Gone/", "Old.png")));
        assertEquals("", catalog.categoryOf(null));
    }
}
