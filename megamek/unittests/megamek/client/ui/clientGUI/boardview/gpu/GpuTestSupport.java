/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** What the board tests share: Swing-thread calls, reflective access to the view's fields, and the staged data. */
final class GpuTestSupport {
    private GpuTestSupport() { }

    /** Runs an action on the Swing thread and returns its result. */
    static <T> T onSwing(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    /** Reads a private field, declared on the owner's class or a superclass. */
    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, String name) {
        try {
            return (T) declared(owner, name).get(owner);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name, e);
        }
    }

    /** Writes a private field, declared on the owner's class or a superclass. */
    static void set(Object owner, String name, Object value) {
        try {
            declared(owner, name).set(owner, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name, e);
        }
    }

    /**
     * A unit's stand-in on the ground of a hex at level 0, for {@link GpuTerrain#animate}: a building around it turns
     * see-through. The caller disposes its model.
     */
    static ModelInstance occupant(Coords at) {
        var box = new ModelBuilder().createBox(4, 4, 12, new Material(), VertexAttributes.Usage.Position);
        return new ModelInstance(box, BoardGeometry.center(at, 0).add(0, 0, 6));
    }

    private static Field declared(Object owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * Runs a test class against the staged data and restores the data folder afterwards: earlier test classes can
     * leave it on testresources.
     */
    static final class StagedData implements BeforeAllCallback, AfterAllCallback {
        private File original;

        @Override
        public void beforeAll(ExtensionContext context) {
            original = Configuration.dataDir();
            Configuration.setDataDir(null);
        }

        @Override
        public void afterAll(ExtensionContext context) {
            Configuration.setDataDir(original);
        }
    }
}
