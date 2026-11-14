/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import megamek.MMConstants;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.logging.MMLogger;

public record AutosaveService(AbstractGameManager gameManager) {
    private static final MMLogger LOGGER = MMLogger.create(AutosaveService.class);

    public static final String FILENAME_FORMAT = "Round-%d-autosave%s.sav.gz";

    public void performRollingAutosave() {
        final int maxNumberAutoSaves = gameManager.getGame().getOptions()
              .intOption(OptionsConstants.BASE_MAX_NUMBER_ROUND_SAVES);
        if (maxNumberAutoSaves > 0) {
            try {
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(
                      PreferenceManager.getClientPreferences().getStampFormat()));
                String fileName = String.format(FILENAME_FORMAT, gameManager.getGame().getCurrentRound(), stamp);
                gameManager.saveHandler.saveRollingGame(fileName, maxNumberAutoSaves,
                      gameManager.getGame().getOptions().booleanOption(OptionsConstants.BASE_AUTOSAVE_MSG));
            } catch (Exception ex) {
                LOGGER.error("", ex);
            }
        }
    }

    /** Runs on the save writer, so earlier queued saves already exist when resolving timestamp collisions. */
    static Path uniquePath(Path requested) {
        Path candidate = requested;
        String name = requested.getFileName().toString();
        String stem = name.substring(0, name.length() - MMConstants.SAVE_FILE_GZ_EXT.length());
        for (int suffix = 1; Files.exists(candidate); suffix++) {
            candidate = requested.resolveSibling(stem + "-" + suffix + MMConstants.SAVE_FILE_GZ_EXT);
        }
        return candidate;
    }

    /** Only remove old recovery points after the replacement save has been committed successfully. */
    static void prune(Path newest, int maximumSaves) {
        try (var entries = Files.list(newest.getParent())) {
            List<Path> older = entries.filter(Files::isRegularFile)
                  .filter(path -> path.getFileName().toString().startsWith("Round-") && !path.equals(newest))
                  .sorted(Comparator.comparingLong(path -> path.toFile().lastModified())).toList();
            for (int i = 0; i <= older.size() - maximumSaves; i++) {
                try {
                    Files.delete(older.get(i));
                } catch (IOException failure) {
                    LOGGER.error(failure, "Unable to delete old autosave {}", older.get(i));
                }
            }
        } catch (IOException failure) {
            LOGGER.error(failure, "Unable to prune autosaves after saving {}", newest);
        }
    }
}
