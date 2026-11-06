/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;

/** Token-only geometry, paint and attachment points. Shared renderers still own buffers, textures and effects. */
final class MeepleVisual {
    static final String ROOT = "meeple";
    static final float HEIGHT = 32;
    /** How bright the token's plain underside is, as a share of its walls' colour. */
    private static final float UNDERSIDE = .4f;
    // Reuse the four body overlays, limiting coverage so even heavy damage preserves the player's camouflage.
    static final float DAMAGE_OPACITY = .5f;
    /**
     * Unit icons' depth range ends here, nearer than anything on the board; markers draw at it, so under them alone. The
     * scene's water and effects see the board beneath an icon: its depth follows them (GpuUnitVisibility.renderDepth).
     */
    static final float ICON_DEPTH = .001f;

    private MeepleVisual() { }

    static boolean isMeeple(ModelInstance instance) {
        return instance != null && instance.getNode(ROOT) != null;
    }

    static Vector3 nozzle(ModelInstance instance, int side) {
        var bounds = UnitBounds.local(instance);
        return new Vector3(bounds.getCenterX() + side * bounds.getWidth() * .18f, bounds.getCenterY(), bounds.min.z)
              .mul(instance.transform);
    }

    /** Rock about the grip, rather than letting a swarmer drift across the carrier's surface. */
    static void swarm(Matrix4 socket, Vector3 suitSize, float clock, int id, float grip, boolean meepleHost) {
        float pulse = Math.max(0, com.badlogic.gdx.math.MathUtils.sin(clock * 7 + id)) * 5 * grip;
        var orientation = UnitAttachments.rotation(socket);
        var scale = UnitAttachments.scale(socket);
        var reach = new Vector3(0, suitSize.z * (meepleHost ? .25f : .34f), suitSize.z * .55f);
        var anchor = socket.getTranslation(new Vector3()).add(reach.cpy().mul(orientation));
        orientation.mul(new com.badlogic.gdx.math.Quaternion(Vector3.X, pulse));
        socket.set(anchor.sub(reach.mul(orientation)), orientation, scale);
    }

    static Model build(BoardScene.Pixels pixels, TextureRegion region) { return token(pixels, region, false); }

    /** The Tactical View's token: the unit's icon alone, flat, over the board and its markers. */
    static Model icon(BoardScene.Pixels pixels, TextureRegion region) { return token(pixels, region, true); }

    private static Model token(BoardScene.Pixels pixels, TextureRegion region, boolean icon) {
        var builder = new ModelBuilder();
        builder.begin();
        builder.node().id = ROOT;
        // ModelBuilder reuses builders for identical attributes; keep the textured caps and painted walls separate.
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.Normal;
        var caps = new MeshBuilder();
        var sides = new MeshBuilder();
        var underside = new MeshBuilder();
        caps.begin(attributes, GL20.GL_TRIANGLES);
        sides.begin(attributes, GL20.GL_TRIANGLES);
        underside.begin(attributes, GL20.GL_TRIANGLES);
        // The artwork shows on top only; underneath, as when the token lies on its back, it is plain and darker than its
        // walls.
        GpuCutout.extrude(pixels, region, Vector3.Zero, 1, HEIGHT, caps, sides, underside);
        var artwork = new Material("meeple-artwork", TextureAttribute.createDiffuse(region.getTexture()), new GpuLinearColors(),
              IntAttribute.createCullFace(GL20.GL_NONE));
        if (icon) { artwork.set(new DepthTestAttribute(GL20.GL_LEQUAL, 0, ICON_DEPTH, false)); }
        builder.part("cutout", caps.end(), GL20.GL_TRIANGLES, artwork);
        if (icon) { return builder.end(); }
        builder.part("sides", sides.end(), GL20.GL_TRIANGLES,
              new Material("paint", ColorAttribute.createDiffuse(GpuCutout.averageColor(pixels)), new GpuLinearColors(),
                    IntAttribute.createCullFace(GL20.GL_NONE)));
        builder.part("underside", underside.end(), GL20.GL_TRIANGLES,
              new Material("underside", ColorAttribute.createDiffuse(GpuCutout.averageColor(pixels).mul(UNDERSIDE, UNDERSIDE, UNDERSIDE, 1)),
                    new GpuLinearColors(), IntAttribute.createCullFace(GL20.GL_NONE)));
        return builder.end();
    }

    /** Caps already contain the tileset's clean camouflage; paint only the walls through the shared material path. */
    static void appearance(ModelInstance instance, GpuUnitModel model, UnitModelState.Appearance appearance,
          float preview, int id, GpuUnitCamouflage camouflage, UnitDamageDisplay damage) {
        if (appearance != null) { camouflage.apply(instance, model.instance, appearance); }
        var stage = preview >= 0 ? UnitDamageDisplay.bodyStage(preview) : appearance == null ? null
              : appearance.bodyStage();
        var texture = stage == null ? null : damage.overlay(stage);
        for (var part : instance.getNode(ROOT).parts) {
            if (!"paint".equals(part.material.id)) { continue; }
            part.material = part.material.copy();
            part.material.remove(UnitDamageDisplay.Overlay.TYPE);
            if (texture != null) {
                part.material.set(new UnitDamageDisplay.Overlay(texture, id, DAMAGE_OPACITY));
            }
        }
    }

    /** Actual alpha-contour grips: friendly riders use the rear, hostile swarmers the front. */
    static Matrix4 socket(ModelInstance host, int index, boolean hostile, Matrix4 suit, Vector3 suitSize,
          UnitPicking picking) {
        var bounds = UnitBounds.local(host);
        var center = bounds.getCenter(new Vector3());
        float sign = hostile ? 1 : -1;
        var frame = host.transform;
        float slot = (index % 3 - 1) * .25f;
        var target = new Vector3(center.x + slot * bounds.getWidth(), center.y,
              bounds.min.z + bounds.getDepth() * (index < 3 ? .8f : .45f)).mul(frame);
        var origin = new Vector3(center.x + slot * bounds.getWidth(), center.y + sign * bounds.getHeight() * 2,
              bounds.min.z + bounds.getDepth() * (index < 3 ? .8f : .45f)).mul(frame);
        var ray = new Ray(origin, target.cpy().sub(origin).nor());
        float distance = picking.distance(host, ray);
        var point = target;
        if (Float.isFinite(distance)) { point.set(origin).mulAdd(ray.direction, (float) Math.sqrt(distance)); }
        else {
            var surface = picking.surface(host, "*", origin, index + (hostile ? 71 : 13));
            if (surface != null) { surface.world(host, point); }
        }
        var turn = UnitAttachments.rotation(frame).mul(new com.badlogic.gdx.math.Quaternion(Vector3.Z, hostile ? 180 : 0));
        point.add(new Vector3(0, -suitSize.z * .25f, -suitSize.z * .55f).mul(turn));
        return new Matrix4(point, turn, UnitAttachments.scale(suit));
    }
}
