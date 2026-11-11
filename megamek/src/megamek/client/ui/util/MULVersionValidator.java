/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.util;

import java.awt.Component;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import javax.swing.JOptionPane;

import megamek.SuiteConstants;
import megamek.client.ui.Messages;
import megamek.common.loaders.MULParser;

/**
 * Shared GUI helper that checks the version a {@link MULParser} loaded a MUL from against the currently running
 * version, and prompts the user when there is a mismatch. Call this from any user-initiated MUL file load, after
 * constructing the parser and before using its parsed contents.
 */
public final class MULVersionValidator {

    private MULVersionValidator() {
    }

    /**
     * Checks whether a loaded MUL is version-compatible with the running application, prompting the user when needed.
     * <ul>
     *     <li>If the MUL was made in a newer version, an error dialog is shown, and loading is refused (a newer MUL can
     *     never be loaded in an older version).</li>
     *     <li>If the MUL was made in an older version (or carries no version), the user is asked whether to proceed.</li>
     *     <li>Otherwise loading proceeds silently.</li>
     * </ul>
     *
     * @param parent the parent component for any dialog, which may be {@code null}
     * @param parser the parser that has already loaded the MUL
     *
     * @return {@code true} if loading should proceed, {@code false} if it must be aborted
     */
    public static boolean isCorrectVersion(final Component parent, final MULParser parser) {
        return isCorrectVersion(parser,
              (text, title) -> JOptionPane.showMessageDialog(parent, text, title, JOptionPane.ERROR_MESSAGE),
              (text, title) -> JOptionPane.showConfirmDialog(parent, text, title, JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
    }

    /**
     * {@link #isCorrectVersion(Component, MULParser)} with the caller's own prompts, such as a client's native ones.
     *
     * @param refuse tells the user why a newer MUL is refused, given its text and title
     * @param proceed asks the user whether to load an older MUL, given its text and title; true to proceed
     *
     * @return {@code true} if loading should proceed, {@code false} if it must be aborted
     */
    public static boolean isCorrectVersion(final MULParser parser, final BiConsumer<String, String> refuse,
          final BiPredicate<String, String> proceed) {
        if (parser.isNewerVersion()) {
            final String fileVersion = (parser.getFileVersion() == null) ? "?" : parser.getFileVersion().toString();
            refuse.accept(Messages.getString("MULParser.versionWarning.newerVersion", fileVersion,
                        SuiteConstants.VERSION),
                  Messages.getString("MULParser.versionWarning.newerVersion.title"));
            return false;
        }

        if (parser.isOlderVersion()) {
            final String fileVersion = (parser.getFileVersion() == null) ? "?" : parser.getFileVersion().toString();
            return proceed.test(Messages.getString("MULParser.versionWarning.olderVersion", fileVersion,
                        SuiteConstants.VERSION),
                  Messages.getString("MULParser.versionWarning.olderVersion.title"));
        }

        return true;
    }
}
