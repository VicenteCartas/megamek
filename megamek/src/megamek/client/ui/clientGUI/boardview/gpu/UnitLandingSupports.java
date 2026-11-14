/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** Per-instance landing gear. Hull placement is finished before contacts are sampled; no game state is changed. */
final class UnitLandingSupports {
    private final ModelInstance instance;
    private final BoardSurface.Cache surfaces;
    private final List<Support> supports = new ArrayList<>();

    private static final class Support {
        final UnitModelDescriptor.LandingSupport definition;
        final Node parent, shaft, foot;
        final Vector3 restParent, restFoot, restScale;
        final Map<NodePart, Boolean> parts = new IdentityHashMap<>();

        Support(UnitModelDescriptor.LandingSupport definition, Node parent, Node shaft, Node foot,
              Node restParent, Node restFoot, Node restShaft) {
            this.definition = definition;
            this.parent = parent;
            this.shaft = shaft;
            this.foot = foot;
            this.restParent = new Vector3(restParent.translation);
            this.restFoot = new Vector3(restFoot.translation);
            restScale = new Vector3(restShaft.scale);
            remember(parent);
        }

        private void remember(Node node) {
            node.parts.forEach(part -> parts.put(part, part.enabled));
            node.getChildren().forEach(this::remember);
        }

        void visible(boolean visible) {
            parts.forEach((part, enabled) -> part.enabled = visible && enabled);
        }

        void reset() {
            parent.translation.set(restParent);
            foot.translation.set(restFoot);
            shaft.scale.set(restScale);
        }

        void deploy(float deployment, float reach) {
            parent.translation.mulAdd(UnitModelDescriptor.vector(definition.stowedOffset()), 1 - deployment);
            foot.translation.z -= reach * deployment;
            shaft.scale.z = restScale.z * (1 + reach * deployment / definition.length());
            // At zero the complete subtree is inside the opaque hull; omitting it also avoids internal picking/shadows.
            visible(deployment > 0);
        }
    }

    UnitLandingSupports(ModelInstance instance, ModelInstance rest, List<UnitRig> rigs, BoardSurface.Cache surfaces) {
        this.instance = instance;
        this.surfaces = surfaces;
        for (var rig : rigs) {
            Node container = rig.container() == null ? null : instance.getNode(rig.container());
            Node original = rig.container() == null ? null : rest.getNode(rig.container());
            if (rig.container() != null && (container == null || original == null)) {
                continue;
            }
            var nodes = container == null ? instance.nodes : container.getChildren();
            var originals = original == null ? rest.nodes : original.getChildren();
            for (var definition : rig.landingSupports()) {
                supports.add(new Support(definition, UnitAnimator.find(nodes, definition.node()),
                      UnitAnimator.find(nodes, definition.shaft()), UnitAnimator.find(nodes, definition.foot()),
                      UnitAnimator.find(originals, definition.node()), UnitAnimator.find(originals, definition.foot()),
                      UnitAnimator.find(originals, definition.shaft())));
            }
        }
    }

    boolean apply(BoardScene scene, BoardScene.Unit unit, UnitMotion.Sample motion) {
        // Non-Aero/building units never receive this behavior, even if an author reuses an aircraft mesh.
        if (supports.isEmpty() || unit.location().aeroState() == null) {
            return false;
        }
        boolean landed = !unit.sensorContact() && motion.aeroState(unit) == BoardScene.AeroState.LANDED;
        float deployment = !landed ? 0 : motion.gear() == null ? 1 : motion.gear().deployment();
        supports.forEach(Support::reset);
        instance.calculateTransforms();
        for (Support support : supports) {
            if (deployment == 0 || unit.footprint().size() < 2) {
                support.deploy(deployment, 0);
                continue;
            }
            Vector3 contact = UnitModelDescriptor.vector(support.definition.contact())
                  .mul(support.foot.globalTransform).mul(instance.transform);
            float ground = ground(scene, contact.x, contact.y, surfaces);
            Matrix4 frame = new Matrix4(instance.transform).mul(support.parent.globalTransform);
            Vector3 up = new Vector3(Vector3.Z).rot(frame);
            // Landed shafts are vertical. Reject invalid/tilted art or an absent surface rather than stretching sideways.
            if (!Float.isFinite(ground) || !Float.isFinite(up.z) || up.z <= .0001f
                  || Math.abs(up.x) + Math.abs(up.y) > .001f * up.z) {
                support.visible(false);
                continue;
            }
            // Sculpted ground can sit slightly above the rest pad. Compress as well as extend the shaft.
            float reach = (contact.z - ground) / up.z;
            float stretch = support.restScale.z * (1 + reach / support.definition.length());
            if (!Float.isFinite(reach) || !Float.isFinite(stretch) || stretch <= 0) {
                support.visible(false);
                continue;
            }
            support.deploy(deployment, reach);
        }
        instance.calculateTransforms();
        return true;
    }

    /** Reuse the rendered ground/road/bank triangles. Liquid cannot carry a pad; ice can. Elevation-0 is below roofs. */
    static float ground(BoardScene scene, float x, float y) {
        return ground(scene, x, y, null);
    }

    static float ground(BoardScene scene, float x, float y, BoardSurface.Cache surfaces) {
        return surface(scene, x, y, surfaces, false, true);
    }

    /** Units may clip Rough cover; support planes still follow the actual ground, banks and road ramps. */
    static float terrain(BoardScene scene, float x, float y, BoardSurface.Cache surfaces) {
        return surface(scene, x, y, surfaces, false, false);
    }

    /** The floor of the tiles last asked about: the scene object around them is new on every frame. */
    private static List<BoardScene.Tile> floorTiles;
    private static int floorRevision;
    private static float floor;

    /**
     * A hex's own ground at (x, y). With hex transitions or padding the slope or talus of a step can lie over the
     * hex's footprint instead of its top; it belongs to the walls of the higher hex, this one or a neighbour.
     */
    private static float ground(BoardScene scene, BoardScene.Tile tile, float x, float y, BoardSurface.Cache surfaces,
          boolean liquid, boolean rough) {
        float top = BoardSurface.sampleHeight(faces(scene, tile, surfaces, rough), x, y, Float.NaN);
        if (!Float.isNaN(top)) { return top; }
        // A neighbour's faces can reach over this footprint, as a water hex's shore does over a corner this land gives
        // up; a step's slope lying over it counts too, and the higher of them is what is drawn.
        float beside = beside(scene, tile, x, y, surfaces, liquid, rough);
        if (!BoardGeometry.tuning().stepsBetweenTops()) {
            return Float.isNaN(beside) ? BoardGeometry.groundZ(tile) : beside;
        }
        if (floorTiles != scene.tiles() || floorRevision != BoardGeometry.revision()) {
            floor = BoardGeometry.floor(scene);
            floorTiles = scene.tiles();
            floorRevision = BoardGeometry.revision();
        }
        float slope = Float.isNaN(beside) ? Float.NEGATIVE_INFINITY : beside;
        for (int direction = -1; direction < 6; direction++) {
            BoardScene.Tile owner = direction < 0 ? tile : scene.tile(tile.coords().translated(direction));
            if (owner == null) { continue; }
            var installed = surfaces == null ? null : surfaces.installed(owner.coords());
            // An installed hex has them; else the caller's cache keeps them with the hex's surface.
            List<BoardSurface.Face> slopes = installed != null ? installed.slopes()
                  : surfaces != null ? surfaces.lying(scene, owner, floor)
                  : BoardTacticalGeometry.lying(new BoardSurface(scene, owner).walls(scene, floor));
            for (BoardSurface.Face face : slopes) {
                slope = Math.max(slope, face.height(x, y));
            }
        }
        return Float.isFinite(slope) ? slope : BoardGeometry.groundZ(tile);
    }

    /**
     * Over a land hex's footprint where its own faces miss, the highest of its neighbours' faces there: a water hex's
     * bank or bed where its shore takes in a corner this hex gives up, or with {@code liquid} its water, or the ice
     * that covers it. NaN where no neighbour's face lies over the point, and on water hexes.
     */
    private static float beside(BoardScene scene, BoardScene.Tile tile, float x, float y, BoardSurface.Cache surfaces,
          boolean liquid, boolean rough) {
        float height = Float.NaN;
        for (int direction = 0; direction < 6 && !tile.liquid().present(); direction++) {
            BoardScene.Tile other = scene.tile(tile.coords().translated(direction));
            if (other == null) { continue; }
            float ground = BoardSurface.sampleHeight(faces(scene, other, surfaces, rough), x, y, Float.NaN);
            if (Float.isNaN(ground)) { continue; }
            // Ice lies level over the hex as the shore moves its corners (BoardSurface's ICE fan).
            float wet = !other.liquid().present() ? Float.NaN : other.frozen() ? BoardGeometry.surfaceZ(other)
                  : liquid ? waterHeight(scene, other, surfaces, x, y) : Float.NaN;
            height = Float.isNaN(height) ? ground : Math.max(height, ground);
            if (!Float.isNaN(wet)) { height = Math.max(height, wet); }
        }
        return height;
    }

    private static List<BoardSurface.Face> faces(BoardScene scene, BoardScene.Tile tile, BoardSurface.Cache surfaces,
          boolean rough) {
        var installed = surfaces == null ? null : surfaces.installed(tile.coords());
        if (installed != null) { return rough ? installed.faces() : installed.ground(); }
        var surface = surfaces == null ? new BoardSurface(scene, tile) : surfaces.get(scene, tile);
        return rough ? surface.faces : surface.groundFaces();
    }

    private static float waterHeight(BoardScene scene, BoardScene.Tile tile, BoardSurface.Cache surfaces, float x, float y) {
        var installed = surfaces == null ? null : surfaces.installed(tile.coords());
        return installed != null ? BoardSurface.sampleHeight(installed.water(), x, y, BoardGeometry.waterZ(tile))
              : (surfaces == null ? new BoardSurface(scene, tile) : surfaces.get(scene, tile)).waterHeight(x, y);
    }

    /** The visible hex surface, including liquid, for flat tactical artwork. */
    static float surface(BoardScene scene, float x, float y, BoardSurface.Cache surfaces) {
        return surface(scene, x, y, surfaces, true, true);
    }

    private static float surface(BoardScene scene, float x, float y, BoardSurface.Cache surfaces, boolean includeLiquid,
          boolean rough) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return Float.NaN;
        }
        int column = (int) Math.floor(x / (BoardGeometry.width() * .75f));
        int row = (int) Math.floor(-y / BoardGeometry.height());
        float height = Float.NEGATIVE_INFINITY;
        for (int cx = Math.max(0, column - 1); cx <= Math.min(scene.width() - 1, column + 1); cx++) {
            for (int cy = Math.max(0, row - 1); cy <= Math.min(scene.height() - 1, row + 1); cy++) {
                var tile = scene.tile(new Coords(cx, cy));
                if (tile == null || !BoardGeometry.contains(tile.coords(), x, y)) {
                    continue;
                }
                float sample = tile.frozen() ? BoardGeometry.surfaceZ(tile)
                      : ground(scene, tile, x, y, surfaces, includeLiquid, rough);
                float water = BoardGeometry.waterZ(tile);
                if (includeLiquid && tile.liquid().present() && !tile.frozen()) {
                    water = waterHeight(scene, tile, surfaces, x, y);
                    sample = Math.max(sample, water);
                }
                if (!tile.liquid().present() || tile.frozen() || sample >= water) {
                    height = Math.max(height, sample);
                }
            }
        }
        return Float.isFinite(height) ? height : Float.NaN;
    }
}
