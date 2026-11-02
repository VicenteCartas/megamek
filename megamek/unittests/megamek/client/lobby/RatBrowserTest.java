/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.client.generator.RandomUnitGenerator.RatTreeNode;
import megamek.client.lobby.RatBrowser.Entry;
import megamek.client.lobby.RatBrowser.Folder;
import org.junit.jupiter.api.Test;

class RatBrowserTest {

    private static Folder tree() {
        Folder mek = new Folder("Mek", List.of(), List.of("3085 Rim Medium Mek", "3085 Rim Heavy Mek"));
        Folder tank = new Folder("Tank", List.of(), List.of("3085 Rim Medium Tank"));
        Folder rim = new Folder("Rim Commonality", List.of(mek, tank), List.of());
        Folder fm = new Folder("(3076 - 3100) - FM 3085", List.of(rim), List.of());
        Folder unofficial = new Folder("Unofficial", List.of(), List.of("Pirate Raiders"));
        return new Folder("RATs", List.of(fm, unofficial), List.of());
    }

    @Test
    void theTopListsSourcesWithTheirYearsAndCounts() {
        RatBrowser browser = new RatBrowser(tree());
        assertEquals(4, browser.size());
        List<Entry> entries = browser.entries();
        assertEquals(2, entries.size());
        Entry fm = entries.get(0);
        assertTrue(fm.folder());
        assertEquals("FM 3085", fm.title());
        assertEquals("3076\u20133100", fm.era());
        assertEquals(3, fm.tables());
        assertEquals("Unofficial", entries.get(1).title());
        assertEquals(null, entries.get(1).era());
    }

    @Test
    void foldersOpenAndCloseWithACrumbTrail() {
        RatBrowser browser = new RatBrowser(tree());
        assertFalse(browser.canGoUp());
        browser.open("(3076 - 3100) - FM 3085");
        browser.open("Rim Commonality");
        browser.open("Mek");
        assertEquals(List.of("FM 3085", "Rim Commonality", "Mek"), browser.path());
        List<Entry> entries = browser.entries();
        assertEquals(2, entries.size());
        assertFalse(entries.get(0).folder());
        assertEquals("3085 Rim Medium Mek", entries.get(0).name());
        browser.up();
        assertEquals(List.of("FM 3085", "Rim Commonality"), browser.path());
        browser.top();
        assertTrue(browser.path().isEmpty());
    }

    @Test
    void openingAnUnknownFolderStaysPut() {
        RatBrowser browser = new RatBrowser(tree());
        browser.open("Nowhere");
        assertTrue(browser.path().isEmpty());
    }

    @Test
    void locateOpensTheTablesFolder() {
        RatBrowser browser = new RatBrowser(tree());
        assertTrue(browser.locate("3085 Rim Medium Tank"));
        assertEquals(List.of("FM 3085", "Rim Commonality", "Tank"), browser.path());
        assertEquals("FM 3085 \u203A Rim Commonality \u203A Tank", browser.pathOf("3085 Rim Medium Tank"));
        assertFalse(browser.locate("No Such Table"));
        assertEquals("", browser.pathOf("No Such Table"));
    }

    @Test
    void searchMatchesEveryWordInTheNameOrFolders() {
        RatBrowser browser = new RatBrowser(tree());
        browser.search("  rim MEDIUM ");
        assertTrue(browser.searching());
        List<Entry> entries = browser.entries();
        assertEquals(2, entries.size());
        assertEquals(2, browser.matches());
        browser.search("tank 3085");
        assertEquals(1, browser.entries().size());
        browser.search("pirate fm");
        assertTrue(browser.entries().isEmpty());
        browser.search("unofficial");
        assertEquals("Pirate Raiders", browser.entries().get(0).name());
        browser.search("");
        assertFalse(browser.searching());
        assertEquals(0, browser.matches());
    }

    @Test
    void searchShowsAtMostTheLimitButCountsAll() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < RatBrowser.SEARCH_LIMIT + 5; i++) {
            many.add("Table " + i);
        }
        RatBrowser browser = new RatBrowser(new Folder("RATs", List.of(), many));
        browser.search("table");
        assertEquals(RatBrowser.SEARCH_LIMIT, browser.entries().size());
        assertEquals(RatBrowser.SEARCH_LIMIT + 5, browser.matches());
    }

    @Test
    void aYearMatchesTheSourcesThatCoverIt() {
        RatBrowser browser = new RatBrowser(tree());
        browser.search("3090 tank");
        assertEquals(1, browser.matches(), "3090 is within 3076-3100");
        browser.search("3070 tank");
        assertEquals(0, browser.matches(), "3070 is before the source");
        Folder late = new Folder("(3130 on) - FM 3145", List.of(), List.of("Late Table"));
        RatBrowser open = new RatBrowser(new Folder("RATs", List.of(late), List.of()));
        open.search("3150");
        assertEquals(1, open.matches(), "an open-ended source covers every later year");
    }

    @Test
    void openingAFolderEndsASearch() {
        RatBrowser browser = new RatBrowser(tree());
        browser.search("rim");
        browser.open("Unofficial");
        assertFalse(browser.searching());
        assertEquals(List.of("Unofficial"), browser.path());
    }

    @Test
    void theGeneratorsTreeBecomesFolders() {
        RatTreeNode root = new RatTreeNode("RATs");
        RatTreeNode source = new RatTreeNode("(3039) - War of 39");
        source.children.add(new RatTreeNode("Davion Assault"));
        root.children.add(source);
        Folder folder = RatBrowser.of(root);
        assertEquals(1, folder.folders().size());
        assertEquals(List.of("Davion Assault"), folder.folders().get(0).tables());
        assertTrue(folder.tables().isEmpty());
    }

    @Test
    void namesSplitIntoYearsAndTitle() {
        assertArrayEquals(new String[] { "3039", "War of 39" }, RatBrowser.split("(3039) - War of 39"));
        assertArrayEquals(new String[] { "3130 on", "FM 3145" }, RatBrowser.split("(3130 on) - FM 3145"));
        assertArrayEquals(new String[] { null, "Building RATs" }, RatBrowser.split("Building RATs"));
    }
}
