/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.awt.geom.Area;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.utils.Array;
import org.junit.jupiter.api.Test;

class GpuOpaqueSorterTest {
    @Test
    void translucentFacetsKeepDepthOrderAtBoardCameraDistances() {
        var camera = new OrthographicCamera();
        camera.position.set(10_000, 0, 0);
        Shader shader = mock(Shader.class);
        var near = part(shader, 20);
        var far = part(shader, 0);
        near.material.set(new BlendingAttribute(true, .8f));
        far.material.set(new BlendingAttribute(true, .8f));
        var parts = new Array<>(new Renderable[] { near, far });
        new GpuOpaqueSorter().sort(camera, parts);
        assertSame(far, parts.first());
        camera.position.set(-10_000, 0, 0);
        new GpuOpaqueSorter().sort(camera, parts);
        assertSame(near, parts.first());
    }

    @Test
    void roadCoatsFollowTheirSurfaceOrderWithOtherTransparencyAndGroundCover() {
        var camera = new OrthographicCamera();
        var sorter = new GpuOpaqueSorter();
        Shader a = mock(Shader.class), b = mock(Shader.class);
        Renderable base = part(a, 1), paint = part(b, 20), ground = part(a, 2);
        base.material.set(new BlendingAttribute(true, 1), new TextureAttribute(GpuRoads.Mask.TYPE, mock(Texture.class)),
              GpuRoads.attribute(new GpuRoads.Patch(new Area(), "roads/asphalt", Color.WHITE, 1, .04f, null)));
        paint.material.set(new BlendingAttribute(true, 1), new TextureAttribute(GpuRoads.Mask.TYPE, mock(Texture.class)),
              GpuRoads.attribute(new GpuRoads.Patch(new Area(), "concrete-ground", Color.WHITE, 1, .065f, null)));
        // ModelInstance copies must retain coat ordering as well as the shader's transition value.
        paint.material = new Material(paint.material);
        Renderable near = part(a, 3), far = part(b, 30);
        near.material.set(new BlendingAttribute(true, .5f));
        far.material.set(new BlendingAttribute(true, .5f));
        for (boolean grass : new boolean[] { false, true }) {
            if (grass) { ground.material.set(new GpuGroundCover.Wind()); }
            var parts = new Array<>(new Renderable[] { near, paint, ground, base, far });
            sorter.sort(camera, parts);
            assertSame(ground, parts.get(0));
            assertSame(base, parts.get(1), "A distant coat cannot be drawn before its own base");
            assertSame(paint, parts.get(2));
            assertSame(far, parts.get(3), "Other transparency keeps its back-to-front ordering");
            assertSame(near, parts.get(4));
        }
    }

    @Test
    void recreatingProgramsPreservesDrawOrderAndDepthTies() {
        var camera = new OrthographicCamera();
        var sorter = new GpuOpaqueSorter();
        Shader a = mock(Shader.class), b = mock(Shader.class);
        var parts = new Array<>(new Renderable[] { part(a, 3), part(b, 3), part(a, 1), part(b, 2) });
        var reference = new Array<>(parts);
        sorter.sort(camera, reference);
        for (int attempt = 0; attempt < 16; attempt++) {
            Shader nextA = mock(Shader.class), nextB = mock(Shader.class);
            parts.get(0).shader = parts.get(2).shader = nextA;
            parts.get(1).shader = parts.get(3).shader = nextB;
            var recreated = new Array<>(parts);
            sorter.sort(camera, recreated);
            for (int i = 0; i < reference.size; i++) {
                assertSame(reference.get(i), recreated.get(i), "Recompiling programs must not reorder the scene");
            }
        }
    }

    @Test
    void coverUsesDefaultOrderingForTheWholePassAndGroupingResumesWithoutIt() {
        var camera = new OrthographicCamera();
        var sorter = new GpuOpaqueSorter();
        Shader a = mock(Shader.class), b = mock(Shader.class);
        Renderable nearA = part(a, 1), middleB = part(b, 2), middleA = part(a, 3);
        Renderable tiedB = part(b, 3), farB = part(b, 4);
        Renderable glassNear = part(a, 1.5f), glassFar = part(b, 5);
        glassNear.material.set(new BlendingAttribute(true, .5f));
        glassFar.material.set(new BlendingAttribute(true, .5f));
        var source = new Array<>(new Renderable[] { farB, glassNear, middleA, nearA, glassFar, middleB, tiedB });

        Array<Renderable> grouped = new Array<>(source);
        sorter.sort(camera, grouped);
        assertEquals(1, shaderChanges(grouped, 5), "An overview still groups its opaque shader programs");
        assertSame(glassFar, grouped.get(5));
        assertSame(glassNear, grouped.get(6));

        middleB.material.set(new GpuGroundCover.Wind());
        Array<Renderable> reference = new Array<>(source), covered = new Array<>(source);
        new DefaultRenderableSorter().sort(camera, reference);
        sorter.sort(camera, covered);
        for (int i = 0; i < reference.size; i++) {
            assertSame(reference.get(i), covered.get(i), "Cover preserves every part's order, including depth ties");
        }
        assertSame(middleA, covered.get(2));
        assertSame(tiedB, covered.get(3));

        middleB.material.remove(GpuGroundCover.Wind.TYPE);
        Array<Renderable> nextOverview = new Array<>(source);
        sorter.sort(camera, nextOverview);
        for (int i = 0; i < grouped.size; i++) {
            assertSame(grouped.get(i), nextOverview.get(i), "Cover in an earlier pass must not disable later grouping");
        }
    }

    private static Renderable part(Shader shader, float distance) {
        var part = new Renderable();
        part.material = new Material();
        part.shader = shader;
        part.worldTransform.setToTranslation(distance, 0, 0);
        return part;
    }

    private static int shaderChanges(Array<Renderable> parts, int count) {
        int changes = 0;
        for (int i = 1; i < count; i++) {
            if (parts.get(i - 1).shader != parts.get(i).shader) { changes++; }
        }
        return changes;
    }
}
