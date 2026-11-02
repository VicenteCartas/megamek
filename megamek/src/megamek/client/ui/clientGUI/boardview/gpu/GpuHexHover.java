/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** The same hovered hex and building-floor outline in the game and both map previews. */
final class GpuHexHover {
    private GpuHexHover() { }

    static float top(BoardScene scene, Coords coords, float pointedZ) {
        if (coords == null || scene.tile(coords) == null || Float.isNaN(pointedZ)) { return Float.NaN; }
        float base = BoardTacticalGeometry.floatingZ(scene, coords);
        float top = Math.max(base, GpuLosResult.pointedLevel(pointedZ) * BoardGeometry.level()
              + .5f * BoardGeometry.hexScale());
        return top > base + BoardGeometry.hexScale() ? top : Float.NaN;
    }

    static void render(ShapeRenderer lines, Camera camera, BoardScene scene, Coords coords, float top) {
        if (coords == null || scene.tile(coords) == null) { return; }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(camera.combined);
        try {
            Gdx.gl.glDepthFunc(GL20.GL_GREATER);
            lines.begin(ShapeRenderer.ShapeType.Line);
            draw(lines, scene, coords, top, GpuBattleView.HOVER_OCCLUDED_ALPHA);
            lines.end();
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            lines.begin(ShapeRenderer.ShapeType.Line);
            draw(lines, scene, coords, top, 1);
            lines.end();
        } finally {
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    static void draw(ShapeRenderer lines, BoardScene scene, Coords coords, float top, float alpha) {
        float base = BoardTacticalGeometry.floatingZ(scene, coords);
        lines.setColor(1, 1, 1, alpha);
        ring(lines, coords, Float.isNaN(top) ? base : top);
        if (!Float.isNaN(top)) {
            lines.setColor(1, 1, 1, alpha * .25f);
            ring(lines, coords, base);
            Vector3 center = BoardGeometry.center(coords, 0);
            for (int edge = 0; edge < 6; edge++) {
                Vector3 corner = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, GpuBattleView.HOVER_HEX_INSET);
                lines.line(corner.x, corner.y, base, corner.x, corner.y, top);
            }
        }
    }

    private static void ring(ShapeRenderer lines, Coords coords, float z) {
        Vector3 center = BoardGeometry.center(coords, 0);
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, GpuBattleView.HOVER_HEX_INSET);
            Vector3 b = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge + 1), center, GpuBattleView.HOVER_HEX_INSET);
            lines.line(a.x, a.y, z, b.x, b.y, z);
        }
    }
}
