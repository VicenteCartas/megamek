/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;

/**
 * Prints unit lists through MegaMekLab, without a toolkit: finds MegaMekLab (the path set in Settings, or else
 * MegaMekLab.jar in MegaMek's folder, where the launcher installs the suite), says why it cannot run, and builds the
 * command that opens MegaMekLab's print dialog for a unit file. MegaMekLab exits once its print dialog closes.
 */
public final class MegaMekLabPrint {
    /** What MegaMek looks for when no path is set: the suite installs MegaMekLab beside MegaMek. */
    static final String DETECTED = "MegaMekLab.jar";

    /** Why MegaMekLab cannot print, with the message that says so. */
    public enum Problem {
        /** No path is set and MegaMekLab is not in MegaMek's folder. */
        NOT_DETECTED("MegaMekLabPrint.notDetected"),
        /** The set path does not exist. */
        NOT_FOUND("MegaMekLabPrint.notFound"),
        /** The set path is neither a jar nor a program this system may run. */
        NOT_EXECUTABLE("MegaMekLabPrint.notExecutable");

        private final String key;

        Problem(String key) {
            this.key = key;
        }

        /** What to tell the player, naming the executable's file. */
        public String message(File executable) {
            return Messages.getString(key, executable.getName());
        }
    }

    private final File executable;
    private final boolean detected;

    private MegaMekLabPrint(File executable, boolean detected) {
        this.executable = executable;
        this.detected = detected;
    }

    /**
     * @param configuredPath the MegaMekLab path from Settings; null or blank looks for MegaMekLab beside MegaMek
     */
    public static MegaMekLabPrint of(@Nullable String configuredPath) {
        boolean detected = (configuredPath == null) || configuredPath.isBlank();
        return new MegaMekLabPrint(new File(detected ? DETECTED : configuredPath), detected);
    }

    /** The MegaMekLab jar or program that prints. */
    public File executable() {
        return executable;
    }

    /** Why MegaMekLab cannot print, or null when it can. */
    public @Nullable Problem problem() {
        if (isJar()) {
            return null;
        }
        if (detected) {
            return executable.canExecute() ? null : Problem.NOT_DETECTED;
        }
        if (!executable.exists()) {
            return Problem.NOT_FOUND;
        }
        return executable.canExecute() ? null : Problem.NOT_EXECUTABLE;
    }

    /**
     * The command that opens MegaMekLab's print dialog for {@code unitFile}, from MegaMekLab's folder. A jar runs on
     * this Java; a Gradle wrapper (MegaMekLab's source tree) builds and runs it.
     */
    public ProcessBuilder command(File unitFile) {
        String unitPath = unitFile.getAbsolutePath();
        List<String> command;
        if (isJar()) {
            String java = ProcessHandle.current().info().command().orElse("java");
            command = List.of(java, "-jar", executable.getAbsolutePath(), unitPath, "--no-startup");
        } else if (executable.getName().toLowerCase(Locale.ROOT).contains("gradle")) {
            command = List.of(executable.getAbsolutePath(), "run", "--args=%s --no-startup".formatted(unitPath));
        } else {
            command = List.of(executable.getAbsolutePath(), unitPath, "--no-startup");
        }
        return new ProcessBuilder(command).directory(executable.getAbsoluteFile().getParentFile()).inheritIO();
    }

    private boolean isJar() {
        try (JarFile ignored = new JarFile(executable)) {
            return true;
        } catch (IOException notAJar) {
            return false;
        }
    }
}
