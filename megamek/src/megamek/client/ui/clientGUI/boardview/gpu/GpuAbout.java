/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import javax.swing.SwingUtilities;

import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.util.UIUtil;

/**
 * The libGDX About card, which replaces the Swing {@code MMAboutDialog} over the battle, in the lobby and in the 3D map
 * editor and preview: the program's version, then the suite's legal notice as the start screen's welcome shows it, with
 * its links, and Close.
 */
final class GpuAbout {
    private GpuAbout() { }

    /** About's title, "About MegaMek". */
    static String title() {
        return Messages.getString("about.title", MMConstants.PROJECT_NAME);
    }

    /** Fills {@code dialog}, a card titled {@link #title()}, with About; Close runs {@code close}. */
    static void fill(UiKit kit, UiDialog dialog, Runnable close) {
        dialog.card().setName("about-card");
        Table body = new Table();
        body.top().left();
        body.defaults().growX().minWidth(0).left();
        body.add(kit.typeLabel(MMConstants.PROJECT_NAME, "title", "text.primary")).row();
        body.add(kit.typeLabel(Messages.getString("StartWelcome.version", MMConstants.VERSION), "caption",
              "text.muted")).padTop(SuiteLegalNotice.space("xs")).row();
        new SuiteLegalNotice(kit, GpuAbout::browse, "about").addTo(actor -> {
            var cell = body.add(actor);
            body.row();
            return cell;
        });
        dialog.body().add(body).growX().minWidth(0);
        dialog.primary(Messages.getString("Close"), close).setName("about-close");
    }

    /** Opens {@code url} in the browser, from the Swing thread as the Swing About's links do. */
    static void browse(String url) {
        SwingUtilities.invokeLater(() -> UIUtil.browse(url));
    }
}
