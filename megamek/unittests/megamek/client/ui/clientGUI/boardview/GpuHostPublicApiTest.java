/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JFrame;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.ApplicationListener;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHost;
import megamek.client.ui.clientGUI.boardview.gpu.GpuSceneDialogs;
import org.junit.jupiter.api.Test;

/**
 * The hosting API other programs of the suite (MekHQ) use for their scenes of the native window, used from outside
 * MegaMek's GPU package as they would: this only compiles while that part stays public. Nothing here opens the window.
 */
class GpuHostPublicApiTest {
    @Test
    void anotherProgramCanDescribeShowAndEndAScene() {
        List<Throwable> ended = new ArrayList<>();
        GpuHost.Scene scene = new GpuHost.Scene() {
            @Override
            public ApplicationListener listener() {
                return new ApplicationAdapter() { };
            }

            @Override
            public String title() {
                return "Campaign";
            }

            @Override
            public void closeRequested() {
                GpuHost.end(this);
            }

            @Override
            public void ended(Throwable failure) {
                ended.add(failure);
            }
        };
        Consumer<GpuHost.Scene> show = GpuHost::show;
        Consumer<Runnable> idle = GpuHost::whenIdle;
        Runnable focus = GpuHost::focus;
        assertTrue(show != null && idle != null && focus != null);

        assertFalse(GpuHost.isShowing(scene));
        // Never asked for, so ending it does nothing
        GpuHost.end(scene);
        assertTrue(ended.isEmpty());
    }

    @Test
    void anotherProgramsDialogsTakeTheScenesPath() {
        List<Boolean> blocks = new ArrayList<>();
        GpuSceneDialogs dialogs = new GpuSceneDialogs(new GpuSceneDialogs.Host() {
            @Override
            public JFrame frame() {
                return null;
            }

            @Override
            public void inputBlocked(boolean blocked) {
                blocks.add(blocked);
            }

            @Override
            public boolean closing() {
                return false;
            }
        });
        dialogs.install();
        try {
            assertNull(dialogs.parent());
            assertFalse(dialogs.blocked());
            dialogs.released();
        } finally {
            dialogs.uninstall();
        }
        assertTrue(blocks.isEmpty());
    }
}
