/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding MegaMekLab to print a unit list, saying why it cannot, and the command that prints. */
class MegaMekLabPrintTest {
    @TempDir
    Path folder;

    @Test
    void noPathLooksBesideMegaMekAndSaysWhenItIsNotThere() {
        MegaMekLabPrint print = MegaMekLabPrint.of(" ");

        assertEquals(new File(MegaMekLabPrint.DETECTED), print.executable());
        assertEquals(MegaMekLabPrint.Problem.NOT_DETECTED, print.problem());
        assertTrue(print.problem().message(print.executable()).contains("Settings"));
    }

    @Test
    void aMissingPathIsNotFound() {
        File missing = folder.resolve("NoSuchLab.jar").toFile();
        MegaMekLabPrint print = MegaMekLabPrint.of(missing.getPath());

        assertEquals(MegaMekLabPrint.Problem.NOT_FOUND, print.problem());
        assertTrue(print.problem().message(missing).contains("NoSuchLab.jar"));
    }

    @Test
    void aFileThatCannotRunIsNotExecutable() throws IOException {
        File script = Files.writeString(folder.resolve("MegaMekLab.sh"), "#!/bin/sh\n").toFile();
        assumeTrue(script.setExecutable(false) && !script.canExecute(), "this system runs any file");

        assertEquals(MegaMekLabPrint.Problem.NOT_EXECUTABLE, MegaMekLabPrint.of(script.getPath()).problem());
    }

    @Test
    void aJarRunsOnThisJavaFromItsFolder() throws IOException {
        File jar = folder.resolve("MegaMekLab.jar").toFile();
        try (OutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            out.flush();
        }
        File units = folder.resolve("units.mul").toFile();
        MegaMekLabPrint print = MegaMekLabPrint.of(jar.getPath());

        assertNull(print.problem());
        ProcessBuilder command = print.command(units);
        List<String> args = command.command();
        assertEquals(List.of("-jar", jar.getAbsolutePath(), units.getAbsolutePath(), "--no-startup"),
              args.subList(1, args.size()));
        assertEquals(folder.toFile().getAbsoluteFile(), command.directory());
    }

    @Test
    void theGradleWrapperBuildsAndRunsMegaMekLab() throws IOException {
        File wrapper = Files.writeString(folder.resolve("gradlew.bat"), "@echo off\r\n").toFile();
        assumeTrue(wrapper.setExecutable(true) && wrapper.canExecute());
        File units = folder.resolve("units.mul").toFile();
        MegaMekLabPrint print = MegaMekLabPrint.of(wrapper.getPath());

        assertNull(print.problem());
        assertEquals(List.of(wrapper.getAbsolutePath(), "run", "--args=" + units.getAbsolutePath() + " --no-startup"),
              print.command(units).command());
    }
}
