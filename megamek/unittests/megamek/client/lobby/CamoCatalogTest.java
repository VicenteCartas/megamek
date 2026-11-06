/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import megamek.client.lobby.IconCatalog.Folder;
import megamek.client.lobby.IconCatalog.Node;
import megamek.common.icons.AbstractIcon;
import megamek.common.icons.Camouflage;
import org.junit.jupiter.api.Test;

class CamoCatalogTest {
    private final Folder wolf = new Folder("Wolf", "Clans/Wolf/", List.of("Alpha.png", "Beta.png"), List.of());
    private final Folder clans = new Folder("Clans", "Clans/", List.of("Generic.png"), List.of(wolf));
    private final Folder steiner = new Folder("Steiner", "Steiner/", List.of("Lyran.png"), List.of());
    private final CamoCatalog catalog = new CamoCatalog(new Folder("", "", List.of(), List.of(clans, steiner)),
          List.of("RED", "BLUE"));

    @Test
    void theTreeListsTheColoursThenTheCategories() {
        Node tree = catalog.tree();
        assertEquals("", tree.id());
        assertEquals(AbstractIcon.ROOT_CATEGORY, tree.label());
        assertEquals(List.of(Camouflage.COLOUR_CAMOUFLAGE + "/", "Clans/", "Steiner/"),
              tree.children().stream().map(Node::id).toList());
        assertEquals("Clans/Wolf/", tree.children().get(1).children().get(0).id());
    }

    @Test
    void theColourCategoryHoldsTheColourCamos() {
        assertEquals(List.of(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "RED"),
                    new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "BLUE")),
              catalog.items(Camouflage.COLOUR_CAMOUFLAGE + "/", true));
    }

    @Test
    void aCategoryHoldsItsSubCategoriesCamosOnlyWhenNested() {
        assertEquals(List.of(new Camouflage("Clans/", "Generic.png")), catalog.items("Clans/", false));
        assertEquals(List.of(new Camouflage("Clans/", "Generic.png"), new Camouflage("Clans/Wolf/", "Alpha.png"),
              new Camouflage("Clans/Wolf/", "Beta.png")), catalog.items("Clans/", true));
        assertEquals(List.of(), catalog.items("Nowhere/", true));
        assertNull(catalog.folder("Clans/Nowhere/"));
    }

    @Test
    void aSearchMatchesCategoryPathsAndFileNames() {
        assertEquals(List.of(), catalog.search("wo"));
        assertEquals(List.of(new Camouflage("Clans/Wolf/", "Alpha.png"), new Camouflage("Clans/Wolf/", "Beta.png")),
              catalog.search(" WOL "));
        assertEquals(List.of(new Camouflage("Steiner/", "Lyran.png")), catalog.search("lyr"));
    }

    @Test
    void aCamosCategoryIsFoundInTheTree() {
        assertEquals("Clans/Wolf/", catalog.categoryOf(new Camouflage("Clans/Wolf/", "Alpha.png")));
        assertEquals(Camouflage.COLOUR_CAMOUFLAGE + "/",
              catalog.categoryOf(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "RED")));
        assertEquals("", catalog.categoryOf(new Camouflage("Gone/", "x.png")));
        assertEquals("", catalog.categoryOf(null));
    }

    @Test
    void thePlainCamoDropsTheAdjustments() {
        Camouflage turned = new Camouflage("Clans/Wolf/", "Alpha.png");
        turned.setRotationAngle(90);
        turned.setScale(5);
        assertEquals(new Camouflage("Clans/Wolf/", "Alpha.png"), CamoCatalog.plain(turned));
    }
}
