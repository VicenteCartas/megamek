/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Objects;

import jakarta.annotation.Nullable;

/** One entry of a start screen's menu column ({@link StartScreenContent#menu}, {@link StartScreenContent#help}). */
public sealed interface StartMenuItem {
    /** How much a button stands out: the way in first, then the main choices, then tools. */
    enum Emphasis { PRIMARY, SECONDARY, QUIET }

    /**
     * A button that runs {@code action} on the Swing thread, unless a window it launched holds the screen.
     *
     * @param detail a second line under the label, or null for none
     */
    record Button(String label, @Nullable String detail, Emphasis emphasis, Runnable action)
          implements StartMenuItem {
        public Button {
            Objects.requireNonNull(label);
            Objects.requireNonNull(emphasis);
            Objects.requireNonNull(action);
        }

        public Button(String label, Emphasis emphasis, Runnable action) {
            this(label, null, emphasis, action);
        }
    }

    /** A section's heading. */
    record Heading(String label) implements StartMenuItem {
        public Heading {
            Objects.requireNonNull(label);
        }
    }
}
