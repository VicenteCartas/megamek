/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Set;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import jakarta.annotation.Nullable;

/** One owned unit model with shared placement and annotation geometry. */
final class GpuUnitModel implements Disposable {
    /**
     * The Atlas bare body spans two terrain levels at unit scale 1. Every modular family uses this same
     * conversion, retaining the library's proportions and relative sizes around the Atlas and Mackie.
     * Antennas on other bodies may extend higher; never normalize each model by its own bounding box.
     * Raising this value means everything will scale down.
     * Lowering this value means everything will scale up!
     */
    private static final float REFERENCE_ASSAULT_HEIGHT = 54.858f;

    private final Model model;
    final ModelInstance instance;
    private final BoundingBox bounds;
    /** The arm nodes an authored Mek body carries, named as the game names those locations. */
    private static final String[] ARM_NODES = { "LA", "RA" };

    private final String upperBodyNode;
    private final boolean modularCoordinates;
    private final List<UnitEquipmentAssembly.Binding> equipment;
    private final List<UnitRig> rigs;
    private final Vector3 restDimensions;
    private final UnitFamilyScale familyScale;
    private final boolean damageLocations;
    private DetailLevels detailLevels = DetailLevels.NONE;
    private List<WallAnchor> walls = List.of();
    /**
     * World units between a casemate's back and its facade, which stands on the hex edge: no part of it may be buried in
     * the wall, where the see-through pass would outline it.
     */
    private static final float WALL_GAP = .15f;
    /** The share of a face's length casemates side by side may span, clear of its corners. */
    private static final float WALL_SPAN = .8f;

    /**
     * A building hex's wall casemate, the node {@code node} of this model, placed on the face {@code face} (0-5 clockwise
     * from the building's front) at the middle of floor {@code floor} of a hex {@code height} levels high. The model
     * stands on that hex's roof. It is number {@code slot} of {@code slots} casemates side by side on that wall, each
     * within {@code bounds} in its own model units, +Y out of the wall.
     */
    record WallAnchor(String node, int face, int floor, int height, int slot, int slots, BoundingBox bounds) { }

    /**
     * Resolved mesh sets at each detail level. Sets overlap when a component reuses a preceding level.
     *
     * @param lod0       the most detailed meshes
     * @param lod1       the middle-distance meshes, including components which retain LOD0
     * @param lod2       the distant meshes, including components which retain LOD1 or LOD0
     * @param lod1Pixels the height on screen, in framebuffer pixels, below which LOD1 shows
     * @param lod2Pixels the height on screen, in framebuffer pixels, below which LOD2 shows
     */
    record DetailLevels(Set<Mesh> lod0, Set<Mesh> lod1, Set<Mesh> lod2, float lod1Pixels, float lod2Pixels) {
        static final DetailLevels NONE = new DetailLevels(Set.of(), Set.of(), Set.of(), 0, 0);

        DetailLevels {
            lod0 = Set.copyOf(lod0);
            lod1 = Set.copyOf(lod1);
            lod2 = Set.copyOf(lod2);
        }

        Set<Mesh> meshes(int level) {
            return level == 2 ? lod2 : level == 1 ? lod1 : lod0;
        }

        int resolvedLevel(int requested) {
            while (requested > 0 && meshes(requested).equals(meshes(requested - 1))) { requested--; }
            return requested;
        }
    }

    GpuUnitModel(Model model) {
        this(model, null);
    }

    /**
     * @param upperBodyNode the part of the model that carries everything above the waist, as named by the model's
     *                      descriptor, or {@code null} for a model that turns as one piece
     */
    GpuUnitModel(Model model, @Nullable String upperBodyNode) {
        this(model, upperBodyNode, false);
    }

    GpuUnitModel(Model model, @Nullable String upperBodyNode, UnitFamilyScale familyScale) {
        this(model, upperBodyNode, false, List.of(), null, List.of(), familyScale);
    }

    GpuUnitModel(Model model, @Nullable String upperBodyNode, boolean modularCoordinates) {
        this(model, upperBodyNode, modularCoordinates, List.of());
    }

    GpuUnitModel(Model model, @Nullable String upperBodyNode, boolean modularCoordinates, List<UnitEquipmentAssembly.Binding> equipment) {
        this(model, upperBodyNode, modularCoordinates, equipment, null, List.of());
    }

    GpuUnitModel(Model model, @Nullable String upperBodyNode, boolean modularCoordinates,
          List<UnitEquipmentAssembly.Binding> equipment, @Nullable Vector3 restDimensions,
          List<UnitRig> rigs) {
        this(model, upperBodyNode, modularCoordinates, equipment, restDimensions, rigs, UnitFamilyScale.DEFAULT);
    }

    GpuUnitModel(Model model, @Nullable String upperBodyNode, boolean modularCoordinates,
          List<UnitEquipmentAssembly.Binding> equipment, @Nullable Vector3 restDimensions,
          List<UnitRig> rigs, UnitFamilyScale familyScale) {
        this.modularCoordinates = modularCoordinates;
        this.familyScale = familyScale;
        this.equipment = List.copyOf(equipment);
        this.rigs = List.copyOf(rigs);
        this.upperBodyNode = ((upperBodyNode != null) && (model.getNode(upperBodyNode) != null))
              ? upperBodyNode : null;
        this.model = model;
        damageLocations = java.util.Arrays.stream(UnitDamageDisplay.Location.values())
              .anyMatch(location -> location != UnitDamageDisplay.Location.ALL && model.getNode(location.node) != null);
        instance = new ModelInstance(model);
        bounds = instance.calculateBoundingBox(new BoundingBox());
        if (!bounds.isValid()) {
            bounds.set(Vector3.Zero, Vector3.Zero);
        }
        this.restDimensions = restDimensions == null ? bounds.getDimensions(new Vector3()) : new Vector3(restDimensions);
    }

    /** Flat artwork for unavailable models in 3D Models mode. */
    static GpuUnitModel sprite(BoardScene.Pixels pixels, TextureRegion region) {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder mesh = builder.part("sprite", GL20.GL_TRIANGLES,
              VertexAttributes.Usage.Position | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.Normal,
              new Material(TextureAttribute.createDiffuse(region.getTexture()), new BlendingAttribute(1f),
                    FloatAttribute.createAlphaTest(.1f), IntAttribute.createCullFace(GL20.GL_NONE)));
        mesh.setUVRange(region);
        float x = pixels.width() * .5f, y = pixels.height() * .5f;
        mesh.rect(-x, -y, 0, x, -y, 0, x, y, 0, -x, y, 0, 0, 0, 1);
        return new GpuUnitModel(builder.end());
    }

    /** Backported libGDX token: textured alpha contour and solid walls. */
    static GpuUnitModel meeple(BoardScene.Pixels pixels, TextureRegion region) {
        return new GpuUnitModel(MeepleVisual.build(pixels, region));
    }

    /** The Tactical View's token: the unit's flat icon. */
    static GpuUnitModel icon(BoardScene.Pixels pixels, TextureRegion region) {
        return new GpuUnitModel(MeepleVisual.icon(pixels, region));
    }

    boolean meeple() { return MeepleVisual.isMeeple(instance); }

    /** @return {@code true} if the upper body can turn on its own, so a torso twist leaves the legs where they are */
    boolean turnsUpperBody() {
        return upperBodyNode != null;
    }

    boolean modularCoordinates() {
        return modularCoordinates;
    }

    List<UnitEquipmentAssembly.Binding> equipment() {
        return equipment;
    }

    List<UnitRig> rigs() {
        return rigs;
    }

    /**
     * Marks the detail levels by their meshes, a formation's figures or a Mek's body/equipment: optional parts start
     * hidden and {@link GpuUnitInstance} swaps them in for the near ones while the unit is small on screen.
     *
     * @return this model
     */
    GpuUnitModel detailLevels(DetailLevels levels) {
        detailLevels = levels;
        return this;
    }

    DetailLevels detailLevels() {
        return detailLevels;
    }

    /** Wall casemates to place on their facades whenever the model is placed. */
    GpuUnitModel walls(List<WallAnchor> anchors) {
        walls = List.copyOf(anchors);
        return this;
    }

    /**
     * The standing height in model units that the level of detail measures: one figure's, for a formation whose
     * figures all stand on the ground, or the whole unit's otherwise.
     */
    float figureHeight() {
        return restDimensions.z;
    }

    boolean infantry() {
        return familyScale == UnitFamilyScale.INFANTRY || familyScale == UnitFamilyScale.BATTLE_ARMOR;
    }

    String damageLocation(UnitDamageDisplay.Location selected) {
        if (!damageLocations || selected == UnitDamageDisplay.Location.ALL) { return "*"; }
        return model.getNode(selected.node) == null ? null : selected.node;
    }

    /** Apply to a fresh instance, alongside location damage, so repairs restore the original shared artwork. */
    void showEquipment(ModelInstance placed, UnitModelState.Appearance appearance) {
        for (var binding : equipment) {
            var effective = binding.memberId() < 0 ? appearance : appearance.fighters().get(binding.memberId());
            if (effective == null) {
                continue;
            }
            boolean broken = effective.inoperableEquipment().contains(binding.index());
            boolean lamp = binding.emitters().stream().anyMatch(emitter -> "lamp".equals(emitter.role()));
            if (broken || lamp) {
                showEquipment(placed.getNode(binding.node()), broken, lamp && effective.searchlightOn());
            }
        }
    }

    private static void showEquipment(Node node, boolean broken, boolean lit) {
        for (var part : node.parts) {
            var material = part.material.copy();
            boolean wrecked = broken || material.id.endsWith(UnitDamageDisplay.WRECKED_SUFFIX);
            if (wrecked) {
                material = UnitDamageDisplay.wrecked(material);
            } else if ("detail".equals(material.id)) {
                material.set(ColorAttribute.createEmissive(GpuLinearColors.authored(material,
                      new com.badlogic.gdx.graphics.Color(lit ? .8f : 0, lit ? .75f : 0, lit ? .45f : 0, 1))));
            }
            part.material = material;
        }
        node.getChildren().forEach(child -> showEquipment(child, broken, lit));
    }

    /**
     * Shows a torso twist: turns the upper body about its own pivot and leaves the rest of the model alone.
     *
     * @param placed  an instance of this model
     * @param degrees the turn in degrees, clockwise seen from above, measured from the legs
     */
    void turnUpperBody(ModelInstance placed, float degrees) {
        Node upperBody = (upperBodyNode == null) ? null : placed.getNode(upperBodyNode);
        if (upperBody == null) {
            return;
        }
        // Facings run clockwise and rotation about Z runs the other way, as in place().
        upperBody.rotation.set(Vector3.Z, -degrees);
        placed.calculateTransforms();
    }

    /**
     * Shows flipped arms: swings both arms about their own shoulder pivots and leaves the rest of the model alone.
     * The rotation is about each arm node's left-right axis, so an arm rises forward, passes over the shoulder and
     * comes to rest reaching behind the unit - the movement a Mek makes to bring its arm weapons onto a rear arc.
     * <p>
     * Half a turn maps each arm onto the space it already occupied, mirrored front to back, so the flipped pose
     * needs no more room around the shoulder than the resting pose does.
     * </p>
     *
     * @param placed  an instance of this model
     * @param degrees how far over the arms are swung, {@code 0} for forward and {@code 180} for fully flipped
     */
    void flipArms(ModelInstance placed, float degrees) {
        // Zero means the arms are forward, and the animator owns them: leave its walk sway alone
        // rather than pinning both arms to their rest pose on every unflipped unit, every frame.
        if (degrees == 0) {
            return;
        }
        boolean posed = false;
        for (String arm : ARM_NODES) {
            Node node = placed.getNode(arm);
            Node rest = model.getNode(arm);
            if (node != null && rest != null) {
                // Build from the authored rest pose, not from whatever the animator left behind, so
                // running every frame holds the arm at one angle instead of winding it further round.
                node.rotation.set(rest.rotation).mul(new Quaternion(Vector3.X, degrees));
                posed = true;
            }
        }
        if (posed) {
            placed.calculateTransforms();
        }
    }

    /** @return {@code true} if this model has arms that can be shown flipped */
    boolean flipsArms() {
        for (String arm : ARM_NODES) {
            if (instance.getNode(arm) != null) {
                return true;
            }
        }
        return false;
    }

    Vector3 place(ModelInstance placed, Camera camera, Vector3 ground, float facing, int height, boolean multiHex) {
        return place(placed, camera, ground, facing, height, multiHex, familyScale);
    }

    private Vector3 place(ModelInstance placed, Camera camera, Vector3 ground, float facing, int height, boolean multiHex,
          UnitFamilyScale scaleTuning) {
        if (modularCoordinates) {
            float scale = horizontalScale(null, scaleTuning);
            return placeScaled(placed, camera, ground, facing, scale, verticalScale(scale, scaleTuning));
        }
        // Schema-1 sprite sections retain their original placement while external compatibility is supported.
        float scale = (multiHex ? 1 : BoardGeometry.unitScale()) * scaleTuning.unitScale();
        float thickness = height * BoardGeometry.level() * BoardGeometry.unitHeightScale()
              * scaleTuning.unitScale() * scaleTuning.heightScale();
        if (meeple()) { thickness /= MeepleVisual.HEIGHT; }
        return placeScaled(placed, camera, ground, facing, scale, thickness);
    }

    Vector3 place(ModelInstance placed, Camera camera, Vector3 ground, float facing, BoardScene.Unit unit) {
        var scaleTuning = familyScale.forUnit(unit);
        if (!modularCoordinates) {
            return place(placed, camera, ground, facing, unit.height(), unit.part() >= 0, scaleTuning);
        }
        var footprint = unit.footprint().size() > 1 ? UnitFootprint.layout(unit.location().coords(), unit.footprint(), facing) : null;
        float scale = horizontalScale(footprint, scaleTuning);
        if (footprint != null) {
            ground = new Vector3(ground).add(footprint.offsetX(), footprint.offsetY(), 0);
        }
        // Rest geometry controls shape. Gameplay height changes when prone/flying and must not flatten the rig.
        float thickness = verticalScale(scale, scaleTuning);
        return placeScaled(placed, camera, ground, facing, scale, thickness);
    }

    /** Horizontal distance conversion shared by placement and the distance-driven gait/wheel animation. */
    float horizontalScale(BoardScene.Unit unit) {
        return horizontalScale(unit.footprint().size() > 1
              ? UnitFootprint.layout(unit.location().coords(), unit.footprint(), unit.location().facing() * 60) : null,
              familyScale.forUnit(unit));
    }

    float verticalScale(float horizontalScale, BoardScene.Unit unit) {
        return verticalScale(horizontalScale, familyScale.forUnit(unit));
    }

    private float verticalScale(float horizontalScale, UnitFamilyScale scaleTuning) {
        // Model coordinates have the same units on every axis. Only explicit height controls alter proportions.
        return horizontalScale * BoardGeometry.unitHeightScale() * scaleTuning.heightScale();
    }

    private float horizontalScale(UnitFootprint.Layout footprint, UnitFamilyScale scaleTuning) {
        if (footprint == null) {
            return 2 * BoardGeometry.level() / REFERENCE_ASSAULT_HEIGHT * BoardGeometry.unitScale() * scaleTuning.unitScale();
        }
        // Large units retain their occupied-footprint fitting, uniformly on all axes including height.
        float scale = scaleTuning.unitScale() * BoardGeometry.tuning().levelHeight()
              / BoardGeometry.DEFAULTS.levelHeight();
        return BoardGeometry.multiHexUnitScale() * scale * Math.min(footprint.width() / Math.max(1, restDimensions.x),
              footprint.depth() / Math.max(1, restDimensions.y));
    }

    private Vector3 placeScaled(ModelInstance placed, Camera camera, Vector3 ground, float facing, float scale,
          float thickness) {
        // Keep feet half a world unit above the ground.
        placed.transform.set(ground, new Quaternion(Vector3.Z, -facing))
              .translate(0, 0, 0.5f)
              .scale(scale, scale, thickness);
        if (!walls.isEmpty()) { placeWalls(placed, scale, thickness); }
        return anchor(placed, camera);
    }

    /**
     * Casemates stand on the facade of their face at the middle of their floor, in world units below the roof the
     * model stands on, whatever the unit scale: walls and floors are the board's, not the unit's.
     */
    private void placeWalls(ModelInstance placed, float scale, float thickness) {
        float facade = (BoardGeometry.height() / 2 + WALL_GAP) / scale;
        float span = BoardGeometry.height() / (float) Math.sqrt(3) * WALL_SPAN / scale;
        for (var wall : walls) {
            Node node = placed.getNode(wall.node());
            if (node == null) { continue; }
            float reach = facade - wall.bounds().min.y;
            // Casemates side by side close up rather than run past the face's corners.
            float width = wall.bounds().getWidth();
            float spacing = wall.slots() < 2 ? 0 : Math.max(0, Math.min(width * 1.15f, (span - width) / (wall.slots() - 1)));
            float along = (wall.slot() - (wall.slots() - 1) / 2f) * spacing;
            // Faces run clockwise like facings, and rotation about Z the other way, as in placeScaled.
            float angle = wall.face() * 60;
            float sin = com.badlogic.gdx.math.MathUtils.sinDeg(angle), cos = com.badlogic.gdx.math.MathUtils.cosDeg(angle);
            node.rotation.set(Vector3.Z, -angle);
            node.translation.set(sin * reach + cos * along, cos * reach - sin * along,
                  ((wall.floor() + .5f - wall.height()) * BoardGeometry.level() - .5f) / thickness);
        }
        placed.calculateTransforms();
    }

    Vector3 anchor(ModelInstance placed, Camera camera) {
        BoundingBox posed = UnitBounds.local(placed);
        if (!posed.isValid()) {
            posed.set(bounds);
        }
        float top = -Float.MAX_VALUE;
        for (float horizontal : new float[] { posed.min.x, posed.max.x }) {
            for (float vertical : new float[] { posed.min.y, posed.max.y }) {
                for (float depth : new float[] { posed.min.z, posed.max.z }) {
                    top = Math.max(top, new Vector3(horizontal, vertical, depth).rot(placed.transform).dot(camera.up));
                }
            }
        }
        return placed.transform.getTranslation(new Vector3()).mulAdd(camera.up, top);
    }

    @Override
    public void dispose() {
        model.dispose();
    }
}
