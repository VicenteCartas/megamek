/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import megamek.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartSavesTest {
    private static final String PLAYERS = "<players>"
          + "<entry><int>0</int><megamek.common.Player><name>Natasha</name><bot>false</bot>"
          + "<observer>false</observer></megamek.common.Player></entry>"
          + "<entry><int>1</int><megamek.common.Player><name>Princess</name><bot>true</bot>"
          + "</megamek.common.Player></entry>"
          + "<entry><int>2</int><megamek.common.Player><name>Watcher</name><observer>true</observer>"
          + "</megamek.common.Player></entry>"
          + "</players>";

    @TempDir
    Path folder;

    @Test
    void theListHasOnlySavesInTheFolderItselfNewestFirst() throws IOException {
        File old = touch("old.sav", 1_000_000);
        File recent = touch("recent.sav.gz", 3_000_000);
        File middle = touch("Middle.SAV", 2_000_000);
        touch("notes.txt", 4_000_000);
        Files.createDirectories(folder.resolve("nested"));
        Files.writeString(folder.resolve("nested").resolve("deeper.sav"), "");

        assertEquals(List.of(recent, middle, old), StartSaves.list(folder.toFile()));
        assertEquals(List.of(), StartSaves.list(folder.resolve("missing").toFile()));
    }

    @Test
    void aSaveIsTitledWithoutItsExtension() {
        assertEquals("autosave", StartSaves.title(new File("autosave.sav.gz")));
        assertEquals("Turn 3", StartSaves.title(new File("Turn 3.sav")));
        assertEquals("odd.name", StartSaves.title(new File("odd.name")));
    }

    @Test
    void aSaveWithoutAVersionNodeLoadsAndNamesItsPlayers() throws IOException {
        File plain = folder.resolve("plain.sav").toFile();
        Files.writeString(plain.toPath(), "<game>" + PLAYERS + "</game>");
        File zipped = folder.resolve("zipped.sav.gz").toFile();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(zipped.toPath()))) {
            out.write(("<game>" + PLAYERS + "</game>").getBytes(StandardCharsets.UTF_8));
        }

        for (File save : List.of(plain, zipped)) {
            StartSaves.Summary summary = StartSaves.read(save);
            assertTrue(summary.loadable(), save.getName());
            assertEquals(List.of("Natasha", "Princess", "Watcher"), summary.players());
            assertEquals(List.of("Princess"), summary.bots());
            assertEquals(List.of("Natasha"), summary.humans());
        }
    }

    @Test
    void aSaveLoadsAtOnceOnlyForItsOnlyPerson() {
        StartSaves.Summary solo = summary(seat("Natasha", false), seat("Princess", true));
        assertEquals("Natasha", solo.quickPlayer("Natasha"));
        assertNull(solo.quickPlayer("Someone else"));
        assertNull(summary(seat("Natasha", false), seat("Kai", false)).quickPlayer("Natasha"));
        assertNull(summary(seat("Princess", true)).quickPlayer("Natasha"));
        StartSaves.Summary old = new StartSaves.Summary(new File("old.sav"),
              List.of(seat("Natasha", false)), StartSaves.Problem.WRONG_VERSION, null);
        assertNull(old.quickPlayer("Natasha"));
    }

    @Test
    void howLongAgoASaveWasMadeReadsNaturally() {
        long now = 10_000_000_000L;
        long minute = 60_000;
        assertEquals("just now", StartSaves.ago(now - 20_000, now));
        assertEquals("1 minute ago", StartSaves.ago(now - minute, now));
        assertEquals("12 minutes ago", StartSaves.ago(now - 12 * minute, now));
        assertEquals("3 hours ago", StartSaves.ago(now - 3 * 60 * minute, now));
        assertEquals("yesterday", StartSaves.ago(now - 30 * 60 * minute, now));
        assertEquals("4 days ago", StartSaves.ago(now - 4 * 24 * 60 * minute, now));
    }

    private static StartSaves.Seat seat(String name, boolean bot) {
        return new StartSaves.Seat(name, bot, false);
    }

    private static StartSaves.Summary summary(StartSaves.Seat... seats) {
        return new StartSaves.Summary(new File("game.sav"), List.of(seats), null, null);
    }

    @Test
    void anEmptyVersionAnotherVersionOrAnUnreadableFileDoesNotLoad() throws IOException {
        File empty = folder.resolve("empty.sav").toFile();
        Files.writeString(empty.toPath(), "<game><version/>" + PLAYERS + "</game>");
        File other = folder.resolve("other.sav").toFile();
        Files.writeString(other.toPath(),
              "<game><version><major>0</major><minor>1</minor><patch>0</patch></version>" + PLAYERS + "</game>");
        File broken = folder.resolve("broken.sav").toFile();
        Files.writeString(broken.toPath(), "not a save");

        assertEquals(StartSaves.Problem.NO_VERSION, StartSaves.read(empty).problem());
        StartSaves.Summary wrong = StartSaves.read(other);
        assertEquals(StartSaves.Problem.WRONG_VERSION, wrong.problem());
        assertTrue(wrong.version().is(new Version(0, 1, 0)));
        assertEquals(StartSaves.Problem.UNREADABLE, StartSaves.read(broken).problem());
        assertFalse(StartSaves.read(broken).loadable());
    }

    @Test
    void aVersionLoadsOnlyItsOwnSaves() {
        Version running = new Version(0, 50, 7);
        assertTrue(StartSaves.loads(new Version(0, 50, 7), running));
        assertFalse(StartSaves.loads(new Version(0, 50, 6), running));
        assertFalse(StartSaves.loads(new Version(0, 49, 7), running));
    }

    @Test
    void aSaveLoadsIntoTheSameOrALaterRevisionOfItsRelease() {
        Version running = new Version("0.51.01.2");
        assertTrue(StartSaves.loads(new Version("0.51.01.1"), running));
        assertTrue(StartSaves.loads(new Version("0.51.01.2"), running));
        assertFalse(StartSaves.loads(new Version("0.51.01.3"), running));
    }

    private File touch(String name, long modified) throws IOException {
        File file = folder.resolve(name).toFile();
        Files.writeString(file.toPath(), "");
        assertTrue(file.setLastModified(modified));
        return file;
    }
}
