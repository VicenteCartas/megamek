/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiTheme.alpha;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The board overlay: the route and its ghost, the front arc or the displayed weapon's arc and the
 * physical-attack neighbours as world meshes, with the plotted route's {@link GpuRoutePulse}
 * over them; and the marks of the units and the target hexes (side rings,
 * glows, the other targets' rings, the hovered unit's ring), drawn every frame
 * where the units stand, level as selection bands, so that the pointer lays no mark on the terrain
 * again. MegaMek's own movement envelope belongs to the tactical capture (GpuTactical), the displayed weapon's range
 * walls to GpuFireControl, and the view draws the hovered hex's ring.
 * It draws the frame's snapshots and decides no rule; the marks and meshes are made again only when what
 * they show changed. Owns its meshes, its pulse, its batch and its shape renderer on the GL thread.
 */
final class GpuBoardOverlay implements Disposable {
    /** One prototype pixel in hex radii at the prototype's Tactical View zoom, where 38 pixels are 1.25 radii. */
    static final float PIXEL = 1.25f / 38;
    /** The vertices of every route and movement mark (Sink). */
    private static final long VERTICES = VertexAttributes.Usage.Position | VertexAttributes.Usage.ColorPacked;
    // overlay.js:10-12, the selection palette of both views; movement uses the client's colours.
    private static final Color MINT = new Color(.45f, .92f, .80f, 1);
    private static final Color CORAL = new Color(1, .44f, .40f, 1);
    private static final Color BLIP = new Color(1, .66f, .34f, 1);
    private static final Color WRECK = new Color(.3f, .3f, .3f, 1);
    /** overlay.js:66: the displayed weapon's mount arc, a wedge from .8 to 2.2 hex radii in 28 steps. */
    private static final float WEDGE_INNER = .8f;
    private static final float WEDGE_OUTER = 2.2f;
    private static final int WEDGE_SEGMENTS = 28;
    /**
     * The ghost's cyan (engine.js material kind 8, without its scanlines and rim light), the share of it that glows
     * whatever the model's own colours, and its strength.
     */
    private static final Color GHOST = new Color(.25f, .72f, .95f, 1);
    private static final Color GHOST_GLOW = new Color(.15f, .43f, .57f, 1);
    private static final float GHOST_OPACITY = .75f;
    /** A destroyed unit's ghost ({@link #renderGhosts}): pale and fainter, not the plan's cyan. */
    private static final Color DEAD = new Color(.78f, .8f, .84f, 1);
    private static final Color DEAD_GLOW = new Color(.25f, .26f, .28f, 1);
    private static final float DEAD_OPACITY = .3f;
    /** The marks the board hides show at this opacity (occluded selection outlines). */
    private static final float HIDDEN_OPACITY = .5f;
    // overlay.js:20-31 and 56-57: the bands of a contact, a wreck, another target and the hovered unit.
    private static final List<Band> CONTACT = List.of(new Band(.05f, .95f, alpha(BLIP, .22f)),
          new Band(.08f, .035f, alpha(BLIP, .7f)));
    private static final List<Band> WRECKED = List.of(new Band(.1f, .03f, alpha(WRECK, .6f)));
    private static final List<Band> TARGETED = List.of(new Band(.04f, .05f, alpha(CORAL, .95f)));
    private static final List<Band> HOVERED = List.of(new Band(.05f, .06f, alpha(Color.WHITE, .85f)));
    private static final MMLogger LOGGER = MMLogger.create(GpuBoardOverlay.class);
    /** An incoming artillery round's target mark: a red ring and centre, pulsing once in this many seconds. */
    private static final Color INCOMING_RED = new Color(1, .12f, .08f, 1);
    private static final float INCOMING_PULSE_SECONDS = .7f;
    /** A missed round's drift line: amber, as the flat board draws it, dashed, ending in an arrowhead. */
    private static final Color DRIFT_AMBER = new Color(1, .75f, 0, .85f);
    private static final float DRIFT_HALF_WIDTH_HEXES = .022f;
    private static final float DRIFT_DASH_HEXES = .16f;
    private static final float DRIFT_GAP_HEXES = .1f;
    private static final float DRIFT_HEAD_HEXES = .24f;

    private final ModelBatch batch = new ModelBatch(GpuShaderManager.provider("Route markers", GpuUnitShader::unlitProvider),
          (camera, renderables) -> { });
    private final ModelBatch ghostBatch = new ModelBatch(GpuShaderManager.provider("Movement ghosts", GpuUnitShader::linearDisplayProvider),
          (camera, renderables) -> { });
    /** Draws the marks every frame, GpuBattleView draws its selection bands. */
    private final ShapeRenderer shapes;
    private final BoardSurface.Cache surfaces = new BoardSurface.Cache();
    /** Light for the 3D ghost: enough ambient to keep it bright, one light from above to show its shape. */
    private final Environment ghostLight = new Environment();
    private final GpuLinearOverlay ghostBlend = new GpuLinearOverlay();
    private ModelInstance overlay;
    private ModelInstance movementMarkers;
    private long builds;
    /** The marks' bands by unit and by hex, in drawing order. */
    private Map<Integer, List<Band>> unitMarks = Map.of();
    private Map<Coords, List<Band>> hexMarks = Map.of();
    /** The hexes artillery rounds in the air are coming down on, marked until each lands. */
    private List<Coords> incomingTargets = List.of();
    private float incomingClock;
    /**
     * The strongest bands of the acting unit's, the focused target's and the hovered unit's marks once more, drawn only
     * where the terrain or a model hides them, so that a unit behind a building keeps its mark (3D only).
     */
    private Map<Integer, List<Band>> hiddenUnitMarks = Map.of();
    private Map<Coords, List<Band>> hiddenHexMarks = Map.of();
    // The inputs of the shown overlay: snapshots by identity, the scene by what the overlay reads of it, view state
    // by value.
    private BoardScene scene;
    private GpuBattleStatus.Snapshot status;
    private List<GpuBattleStatus.UnitStatus> units;
    private GpuMovePlan.Snapshot move;
    private GpuFireOrders.Snapshot fire;
    private GpuPhysicalOptions.Snapshot physical;
    /** The client's movement palette, captured on the EDT for both views and the route pulse. */
    private Map<GpuMovePlan.Band, Integer> movementColors = Map.of();
    private int hoveredUnit = Entity.NONE;
    private int inspected = Entity.NONE;
    private boolean tactical;
    private boolean columns;
    private int revision = -1;
    /** The enemy the shown overlay focuses (its coral glow), or {@code Entity.NONE}. */
    private int focused = Entity.NONE;
    /** The acting unit the shown overlay marks (its mint glow), or {@code Entity.NONE}. */
    private int acting = Entity.NONE;
    /** The hex radius in world units while building. */
    private float radius;
    // The ghost of the plotted route: the moving unit as the scene shows it and where the route leaves it, or null.
    private BoardScene.Unit mover;
    private BoardScene.Waypoint arrival;
    /** The ghost last drawn: a copy of {@link #ghostSource} as it looked at build {@link #ghostBuild}. */
    private ModelInstance ghost;
    private ModelInstance ghostSource;
    private long ghostBuild;
    /** Each ghost material's opacity at rest, which the pulse's surge raises. */
    private float[] ghostOpacity;
    /** The plotted route's pulse, drawn over the route each frame; the meshes above never change for it. */
    private final GpuRoutePulse pulse = new GpuRoutePulse();

    /**
     * A band of a mark, level as selection bands: its outer edge {@code inset} hex radii inside the hex
     * edge, {@code width} radii wide; one that reaches the centre fills the hex.
     */
    private record Band(float inset, float width, Color color) { }

    // Without a shared program the shape renderer compiles and owns libGDX's own.
    GpuBoardOverlay() {
        this(null);
    }

    /** The marks draw with the view's shape program, which their renderer does not own. */
    GpuBoardOverlay(ShaderProgram program) {
        shapes = new ShapeRenderer(5000, program);
        ghostLight.set(new ColorAttribute(ColorAttribute.AmbientLight, .6f, .6f, .6f, 1));
        ghostLight.add(new DirectionalLight().set(.5f, .5f, .5f, -.4f, .3f, -1));
    }

    /** Switch ground-contact sampling with the installed terrain mode and rebuild draped marks once. */
    void columns(boolean value) {
        if (columns != value) { columns = value; surfaces.columns(value); revision = -1; }
    }

    /**
     * Shows the frame's overlay. The marks are worked out again only when what they show changed: the scene's units,
     * the status, the presented units, the fire or physical snapshot or the view state; the meshes are rebuilt only
     * when what they draw changed: the scene's units or terrain, the movement, fire or physical snapshot, the movement
     * colours or the view state. {@code view} gives the Tactical View flag and the hovered unit; {@code state} the
     * inspected unit and the presented units (C.6). Called once a frame, it also moves the route's pulse on by the
     * frame's time.
     */
    void update(GpuBoardSource.Frame captured, GpuHud.HudView view, GpuBoardSource.UiPreferences preferences,
          GpuHudState state) {
        pulse.advance(Gdx.graphics.getDeltaTime());
        // As the HUD presents it: no route or orders while the player cleared the selection in the turn.
        GpuBoardSource.Frame frame = state.presented(captured);
        GpuHudData panels = frame.panels();
        // The source captures a new scene at every refresh; one that shows the same keeps the marks and meshes.
        boolean same = sameDrawing(scene, frame.scene()) && panels.fire() == fire && panels.physical() == physical
              && view.tactical() == tactical;
        boolean sameMarks = same && frame.status() == status && state.presentedUnits() == units
              && view.hoveredUnit() == hoveredUnit && state.inspected == inspected;
        // A hovered unit hides the hover route (G3), so for the meshes only whether one is hovered counts.
        boolean sameMeshes = same && panels.move() == move && preferences.movementColors().equals(movementColors)
              && (view.hoveredUnit() == Entity.NONE) == (hoveredUnit == Entity.NONE)
              && revision == BoardGeometry.revision();
        scene = frame.scene();
        status = frame.status();
        units = state.presentedUnits();
        move = panels.move();
        fire = panels.fire();
        physical = panels.physical();
        movementColors = preferences.movementColors();
        hoveredUnit = view.hoveredUnit();
        inspected = state.inspected;
        tactical = view.tactical();
        revision = BoardGeometry.revision();
        if (!sameMarks) {
            marks();
        }
        if (sameMeshes) {
            return;
        }
        builds++;
        mover = null;
        arrival = null;
        pulse.begin(tactical, BoardGeometry.WIDTH / 2);
        if (movementMarkers != null) {
            movementMarkers.model.dispose();
            movementMarkers = null;
        }
        if (overlay != null) {
            overlay.model.dispose();
            overlay = null;
        }
        if (scene == null) {
            return;
        }
        radius = BoardGeometry.WIDTH / 2;
        overlay = build();
    }

    /** How often the overlay was rebuilt. */
    long builds() {
        return builds;
    }

    /** The plotted route's pulse. */
    GpuRoutePulse pulse() {
        return pulse;
    }

    /**
     * The enemy the shown overlay focuses, whose glow is coral (overlay.js focusT): the declaration's focused target,
     * the physical attack's target, else the inspected enemy; {@code Entity.NONE} for none.
     */
    int focus() {
        return focused;
    }

    /**
     * The acting unit the shown overlay marks with its mint glow: the status's actor, a listed, living, friendly unit
     * outside the initiative phases; {@code Entity.NONE} for none. The Tactical View's base glows for it.
     */
    int actor() {
        return acting;
    }

    /** Whether two captures show the same overlay: the same units and terrain (by identity, as its caches compare). */
    private static boolean sameDrawing(BoardScene shown, BoardScene next) {
        return shown == next || shown != null && next != null && shown.boardId() == next.boardId()
              && shown.tiles() == next.tiles() && shown.units().equals(next.units());
    }

    /**
     * Draws the marks where the units stand this frame and on their hexes, level as selection bands, half
     * a unit above the unit's base ({@code poses} gives each scene unit's as the view placed it; a unit without one
     * shows none) or on a hex's floating plane: in 3D first the hidden bands faintly where the terrain or a model hides
     * them, then every band depth-tested; in the Tactical View flat over the board and the units' bases.
     */
    void renderMarks(Camera camera, Function<BoardScene.Unit, UnitFootprint.Pose> poses) {
        if (scene == null || unitMarks.isEmpty() && hexMarks.isEmpty() && incomingTargets.isEmpty()
              && driftLines().isEmpty()) {
            return;
        }
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        // Both passes compare against the scene, without adding the marks themselves to its depth.
        Gdx.gl.glDepthMask(false);
        shapes.setProjectionMatrix(camera.combined);
        try {
            if (tactical) {
                Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            } else {
                Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                Gdx.gl.glDepthFunc(GL20.GL_GREATER);
                drawMarks(poses, hiddenUnitMarks, hiddenHexMarks, HIDDEN_OPACITY);
                Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            }
            drawMarks(poses, unitMarks, hexMarks, 1);
        } finally {
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        }
    }

    private void drawMarks(Function<BoardScene.Unit, UnitFootprint.Pose> poses, Map<Integer, List<Band>> byUnit,
          Map<Coords, List<Band>> byHex, float opacity) {
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (BoardScene.Unit unit : scene.units()) {
            List<Band> bands = byUnit.get(unit.id());
            UnitFootprint.Pose pose = (bands == null) ? null : poses.apply(unit);
            if (pose == null) {
                continue;
            }
            for (Coords occupied : pose.unit().footprint()) {
                for (Band band : bands) {
                    band((corner, inset) -> pose.outlinePoint(occupied, corner, inset), band, opacity);
                }
            }
        }
        for (Map.Entry<Coords, List<Band>> entry : byHex.entrySet()) {
            Coords hex = entry.getKey();
            Vector3 center = BoardGeometry.center(hex, 0);
            float z = BoardTacticalGeometry.floatingZ(scene, hex);
            for (Band band : entry.getValue()) {
                band((corner, inset) -> BoardGeometry.inset(BoardGeometry.corner(hex, 0, corner), center, inset)
                      .add(0, 0, z), band, opacity);
            }
        }
        List<Band> incoming = incomingBands();
        for (Coords hex : incomingTargets) {
            Vector3 center = BoardGeometry.center(hex, 0);
            float planeHeight = BoardTacticalGeometry.floatingZ(scene, hex);
            for (Band band : incoming) {
                band((corner, inset) -> BoardGeometry.inset(BoardGeometry.corner(hex, 0, corner), center, inset)
                      .add(0, 0, planeHeight), band, opacity);
            }
        }
        // The Tactical View draws its own drift arrows, with the rest of the flat board's art.
        if (!tactical) {
            for (BoardMarker drift : driftLines()) {
                driftArrow(drift.coords(), drift.driftTo(), opacity);
            }
        }
        shapes.end();
    }

    /** The scene's artillery drift markers: each a missed round's aimed hex and where it came down. */
    private List<BoardMarker> driftLines() {
        List<BoardMarker> lines = new ArrayList<>();
        for (BoardMarker marker : scene.markers()) {
            if ((marker.kind() == BoardMarker.Kind.ARTILLERY_DRIFT) && (marker.driftTo() != null)) {
                lines.add(marker);
            }
        }
        return lines;
    }

    /** A dashed amber line from the hex a round was aimed at to where it came down, ending in an arrowhead. */
    private void driftArrow(Coords aimed, Coords landing, float opacity) {
        Vector3 from = BoardGeometry.center(aimed, 0).add(0, 0, BoardTacticalGeometry.floatingZ(scene, aimed));
        Vector3 to = BoardGeometry.center(landing, 0).add(0, 0, BoardTacticalGeometry.floatingZ(scene, landing));
        float hex = BoardGeometry.height();
        float length = Vector3.len(to.x - from.x, to.y - from.y, 0);
        if (length < hex * .5f) {
            return;
        }
        Vector3 along = new Vector3(to).sub(from).scl(1 / length);
        Vector3 side = new Vector3(-along.y, along.x, 0).nor();
        float color = Color.toFloatBits(DRIFT_AMBER.r, DRIFT_AMBER.g, DRIFT_AMBER.b, DRIFT_AMBER.a * opacity);
        float headLength = hex * DRIFT_HEAD_HEXES;
        float lineEnd = length - headLength;
        float halfWidth = hex * DRIFT_HALF_WIDTH_HEXES;
        for (float start = 0; start < lineEnd; start += hex * (DRIFT_DASH_HEXES + DRIFT_GAP_HEXES)) {
            float end = Math.min(lineEnd, start + hex * DRIFT_DASH_HEXES);
            Vector3 dashStart = new Vector3(from).mulAdd(along, start);
            Vector3 dashEnd = new Vector3(from).mulAdd(along, end);
            Vector3 startLeft = new Vector3(dashStart).mulAdd(side, halfWidth);
            Vector3 startRight = new Vector3(dashStart).mulAdd(side, -halfWidth);
            Vector3 endLeft = new Vector3(dashEnd).mulAdd(side, halfWidth);
            Vector3 endRight = new Vector3(dashEnd).mulAdd(side, -halfWidth);
            triangle(startLeft, startRight, endRight, color);
            triangle(startLeft, endRight, endLeft, color);
        }
        Vector3 headBase = new Vector3(from).mulAdd(along, lineEnd);
        Vector3 headLeft = new Vector3(headBase).mulAdd(side, headLength * .5f);
        Vector3 headRight = new Vector3(headBase).mulAdd(side, -headLength * .5f);
        triangle(headLeft, headRight, to, color);
    }

    /**
     * Marks the hexes the artillery rounds now in the air are coming down on, until each lands, and moves their pulse
     * on. Called once a frame, before {@link #renderMarks}.
     *
     * @param attacks the attacks playing now
     * @param seconds the frame's time
     */
    void setIncomingTargets(List<UnitAttack> attacks, float seconds) {
        List<Coords> targets = incomingTargets(attacks);
        if (!targets.equals(incomingTargets)) {
            LOGGER.debug("[ArtilleryTarget] marking {} incoming target hexes: {}", targets.size(), targets);
            if (incomingTargets.isEmpty()) {
                incomingClock = 0;
            }
        }
        incomingTargets = targets;
        incomingClock += seconds;
    }

    /** The hexes the artillery rounds among {@code attacks} are coming down on, each once, while they are in the air. */
    static List<Coords> incomingTargets(List<UnitAttack> attacks) {
        List<Coords> targets = new ArrayList<>();
        for (UnitAttack attack : attacks) {
            Coords target = attack.event.destination().coords();
            if (attack.artilleryIncoming() && !targets.contains(target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    /** The red ring and centre of an incoming round's mark, at this moment of its pulse: brighter and wider, then not. */
    private List<Band> incomingBands() {
        float pulse = .5f + .5f * MathUtils.sin(incomingClock * MathUtils.PI2 / INCOMING_PULSE_SECONDS);
        return List.of(new Band(.03f, .08f + .05f * pulse, alpha(INCOMING_RED, .55f + .4f * pulse)),
              new Band(.74f, .26f, alpha(INCOMING_RED, .35f + .45f * pulse)));
    }

    /** A point of a mark's outline: the hex corner {@code corner} moved {@code inset} hex radii toward the centre. */
    private interface Outline {
        Vector3 point(int corner, float inset);
    }

    /** A band as two triangles a side; one that reaches the centre as one. */
    private void band(Outline outline, Band band, float opacity) {
        Color color = band.color();
        float bits = Color.toFloatBits(color.r, color.g, color.b, color.a * opacity);
        float reach = Math.min(1, band.inset() + band.width());
        Vector3[] outer = new Vector3[6];
        Vector3[] inner = new Vector3[6];
        for (int corner = 0; corner < 6; corner++) {
            outer[corner] = outline.point(corner, band.inset());
            inner[corner] = outline.point(corner, reach);
        }
        for (int corner = 0; corner < 6; corner++) {
            int next = (corner + 1) % 6;
            triangle(outer[corner], outer[next], inner[next], bits);
            if (reach < 1) {
                triangle(outer[corner], inner[next], inner[corner], bits);
            }
        }
    }

    private void triangle(Vector3 a, Vector3 b, Vector3 c, float color) {
        ImmediateModeRenderer renderer = shapes.getRenderer();
        if (renderer.getNumVertices() + 3 > renderer.getMaxVertices()) {
            shapes.flush();
        }
        renderer.color(color);
        renderer.vertex(a.x, a.y, a.z);
        renderer.color(color);
        renderer.vertex(b.x, b.y, b.z);
        renderer.color(color);
        renderer.vertex(c.x, c.y, c.z);
    }

    /** The unlit programs of the route and movement marks and of the route's pulse, behind the loading stage. */
    void prepare() {
        GpuUnitShader.prepareUnlit(batch, material(true), MeshBuilder.createAttributes(VERTICES));
        batch.getShaderProvider().getShader(pulse.prepare());
    }

    /**
     * Draws the route, pulse and movement markers through scene geometry. Other overlays retain their depth policy.
     */
    void render(Camera camera) {
        if (scene == null) {
            return;
        }
        batch.begin(camera);
        if (overlay != null) {
            batch.render(overlay);
        }
        Renderable pulsing = pulse.renderable(camera);
        if (pulsing != null) {
            batch.render(pulsing);
        }
        if (movementMarkers != null) {
            batch.render(movementMarkers);
        }
        batch.end();
    }

    /**
     * The plotted route's ghost (rebuild plan A.7 G4): a copy of the instance the view draws for the moving unit
     * ({@code shown} gives it for a scene unit, or null), sharing its meshes, at the route's destination in the final
     * facing: a translucent cyan copy of the model, or of the meeple in the Tactical View. It shows through scenery
     * without writing scene depth, drawn after the route and the units.
     * When the route's pulse lands, the ghost surges. Nothing without a plotted route of the planner.
     */
    void renderGhost(Camera camera, Function<BoardScene.Unit, ModelInstance> shown) {
        ModelInstance source = mover == null ? null : shown.apply(mover);
        if (source == null) {
            return;
        }
        if (ghost == null || ghostSource != source || ghostBuild != builds) {
            // The copy owns copies of the shown materials; the meshes stay the shown model's.
            ghost = new ModelInstance(source);
            ghostOpacity = new float[ghost.materials.size];
            for (int index = 0; index < ghost.materials.size; index++) {
                Material material = ghost.materials.get(index);
                ghostly(material);
                ghostOpacity[index] = ((BlendingAttribute) material.get(BlendingAttribute.Type)).opacity;
            }
            ghostSource = source;
            ghostBuild = builds;
        }
        surge(pulse.surge());
        // Moves the shown placement from the unit's hex to the destination; facings turn clockwise, Z rotations not.
        Vector3 from = BoardGeometry.center(mover.location().coords(), mover.location().elevation());
        Vector3 to = BoardGeometry.center(arrival.coords(), arrival.elevation());
        ghost.transform.setToTranslation(to).rotate(Vector3.Z, (mover.location().facing() - arrival.facing()) * 60)
              .translate(-from.x, -from.y, -from.z).mul(source.transform);
        ghostBlend.begin();
        try {
            ghostBatch.begin(camera);
            ghostBatch.render(ghost, ghostLight);
            ghostBatch.end();
        } finally { ghostBlend.end(); }
    }

    /**
     * Destroyed units in the playback of what they still fired or took (GpuBattleView#ghost): pale copies of the
     * instances the view placed for them, each at its share of {@link #DEAD_OPACITY}, drawn as the route's ghost is.
     */
    void renderGhosts(Camera camera, Map<ModelInstance, Float> shown) {
        if (shown.isEmpty()) { return; }
        ghostBlend.begin();
        try {
            ghostBatch.begin(camera);
            shown.forEach((source, opacity) -> {
                var ghost = new ModelInstance(source);
                for (Material material : ghost.materials) {
                    ghostly(material, DEAD, DEAD_GLOW);
                    ((BlendingAttribute) material.get(BlendingAttribute.Type)).opacity = DEAD_OPACITY * opacity;
                }
                ghostBatch.render(ghost, ghostLight);
            });
            ghostBatch.end();
        } finally { ghostBlend.end(); }
    }

    /**
     * The pulse's surge on the ghost's copied materials: its glow rises and it turns more opaque, then both ease back
     * as the surge does. It never grows: units keep their size.
     */
    private void surge(float surge) {
        float opaque = Math.min(1, surge) * GpuRoutePulse.GHOST_SURGE_OPACITY;
        Color authoredGlow = pulse.arrivalColor(new Color()).mul(surge * GpuRoutePulse.GHOST_SURGE).add(GHOST_GLOW);
        for (int index = 0; index < ghost.materials.size; index++) {
            Material material = ghost.materials.get(index);
            ((BlendingAttribute) material.get(BlendingAttribute.Type)).opacity =
                  ghostOpacity[index] + (1 - ghostOpacity[index]) * opaque;
            Color glow = ((ColorAttribute) material.get(ColorAttribute.Emissive)).color;
            glow.set(GpuLinearColors.authored(material, authoredGlow));
        }
    }

    /**
     * Turns a copied material into the ghost's: a lit, translucent cyan that keeps only a cut-out (alpha-tested)
     * texture, so that a flat sprite keeps its outline.
     */
    static void ghostly(Material material) { ghostly(material, GHOST, GHOST_GLOW); }

    /** The ghost in {@code colour}, glowing {@code glow}: the board editor's Erase preview is red. */
    static void ghostly(Material material, Color colour, Color glow) {
        Attribute alphaTest = material.get(FloatAttribute.AlphaTest);
        Attribute cutOut = alphaTest == null ? null : material.get(TextureAttribute.Diffuse);
        Attribute cullFace = material.get(IntAttribute.CullFace);
        Attribute linearColors = material.get(GpuLinearColors.TYPE);
        material.clear();
        if (linearColors != null) { material.set(linearColors); }
        material.set(ColorAttribute.createDiffuse(GpuLinearColors.authored(material, colour)),
              ColorAttribute.createEmissive(GpuLinearColors.authored(material, glow)),
              new BlendingAttribute(GHOST_OPACITY), new DepthTestAttribute(GL20.GL_ALWAYS, false));
        if (cutOut != null) {
            material.set(alphaTest, cutOut);
        }
        if (cullFace != null) {
            material.set(cullFace);
        }
    }

    /** The prototype's layers that lie on hexes, in its drawing order; the batch keeps that order. */
    private ModelInstance build() {
        Sink sink = new Sink(material(tactical));
        Sink markers = new Sink(material(true));
        Map<Integer, BoardScene.Unit> shown = new HashMap<>();
        scene.units().forEach(unit -> shown.putIfAbsent(unit.id(), unit));
        BoardScene.Unit moving = shown.get(move.entityId());
        if (move.active() && move.planner() && moving != null) {
            sink.material(material(true));
            route(sink, markers, moving);
            sink.material(material(tactical));
        }
        if (fire.active()) {
            if (fire.frontArc() != null) {
                outline(sink, fire.frontArc().hexes(), .03f, false, alpha(Color.WHITE, .3f));
            }
            if (fire.solution() != null && fire.solution().wedge() != null) {
                wedge(sink, fire.solution().wedge());
            }
        }
        BoardScene.Unit attacker = shown.get(physical.actorId());
        if (physical.active() && attacker != null) {
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile tile = scene.tile(attacker.location().coords().translated(direction));
                if (tile != null && tactical) {
                    ring(sink, tile, 1.5f * PIXEL, .08f - .75f * PIXEL, 0, alpha(Color.WHITE, .35f));
                } else if (tile != null) {
                    ring(sink, tile, .03f, .1f, .02f, alpha(Color.WHITE, .22f));
                }
            }
        }
        movementMarkers = markers.end();
        return sink.end();
    }

    /** A glow needs a listed, shown, living unit that is no contact: the actor is friendly, the focus an enemy. */
    private static boolean eligible(GpuBattleStatus.UnitStatus listing, BoardScene.Unit unit, boolean enemy) {
        return listing != null && unit != null && !unit.sensorContact() && !listing.destroyed()
              && (listing.side() == GpuBattleStatus.Side.ENEMY) == enemy;
    }

    /**
     * overlay.js:20-31 and 56-57: the contacts, the wrecks, the acting and focused glows and the side rings (moved
     * units fade), a focused hex's glow, the other targets' rings and the hovered unit's ring. In the Tactical View the
     * units' bases show the side, the glows, contacts and wrecks (GpuUnitBases), so only the hex and target marks stay.
     * Glows mark the acting unit and the focused enemy outside the initiative phases (overlay.js:21-28).
     */
    private void marks() {
        Map<Integer, List<Band>> byUnit = new HashMap<>();
        Map<Coords, List<Band>> byHex = new LinkedHashMap<>();
        Map<Integer, List<Band>> hiddenByUnit = new HashMap<>();
        Map<Coords, List<Band>> hiddenByHex = new LinkedHashMap<>();
        focused = Entity.NONE;
        acting = Entity.NONE;
        if (scene != null) {
            GamePhase phase = status.phase();
            Map<Integer, GpuBattleStatus.UnitStatus> listed = units.stream()
                  .collect(Collectors.toMap(GpuBattleStatus.UnitStatus::id, unit -> unit, (a, b) -> a));
            Map<Integer, BoardScene.Unit> shown = new LinkedHashMap<>();
            scene.units().forEach(unit -> shown.putIfAbsent(unit.id(), unit));
            boolean glows = !phase.isInitiative() && !phase.isInitiativeReport();
            int actor = glows && eligible(listed.get(status.actorId()), shown.get(status.actorId()), false)
                  ? status.actorId() : Entity.NONE;
            // The local declaration's focused target; a read-only draft (H33) has none, so the inspected unit glows.
            int focusId = fire.editable() ? fire.focus().key().unitId() : physical.active() ? physical.targetId()
                  : inspected;
            focused = glows && eligible(listed.get(focusId), shown.get(focusId), true) ? focusId : Entity.NONE;
            acting = actor;
            // A focus that is no unit, a hex, a building or a minefield, glows on its hex as a focused enemy does.
            Coords focusHex = glows && fire.editable() ? fire.focus().hex() : null;
            boolean focusShown = focusHex != null && scene.tile(focusHex) != null;
            for (BoardScene.Unit unit : tactical ? List.<BoardScene.Unit>of() : shown.values()) {
                int id = unit.id();
                GpuBattleStatus.UnitStatus listing = listed.get(id);
                if (unit.sensorContact()) {
                    add(byUnit, id, CONTACT);
                } else if (listing != null && listing.destroyed()) {
                    add(byUnit, id, WRECKED);
                } else if (listing != null && (id == actor || id == focused)) {
                    glow(byUnit, hiddenByUnit, id, id == actor ? MINT : CORAL);
                } else if (listing != null) {
                    boolean enemy = listing.side() == GpuBattleStatus.Side.ENEMY;
                    float strength = phase.isMovement() && listing.done() ? .2f : enemy ? .5f : .45f;
                    add(byUnit, id, List.of(new Band(.08f, .03f, alpha(enemy ? CORAL : MINT, strength))));
                }
            }
            if (focusShown) {
                glow(byHex, hiddenByHex, focusHex, CORAL);
            }
            if (fire.active()) {
                for (GpuFireOrders.Target target : fire.targets()) {
                    if (target.key().equals(fire.focus().key())) {
                        continue;
                    }
                    if (target.hex() == null) {
                        add(byUnit, target.key().unitId(), TARGETED);
                    } else if (scene.tile(target.hex()) != null) {
                        add(byHex, target.hex(), TARGETED);
                    }
                }
            }
            if (hoveredUnit != Entity.NONE && hoveredUnit != actor) {
                add(byUnit, hoveredUnit, HOVERED);
                add(hiddenByUnit, hoveredUnit, HOVERED);
            }
        }
        unitMarks = byUnit;
        hexMarks = byHex;
        hiddenUnitMarks = hiddenByUnit;
        hiddenHexMarks = hiddenByHex;
    }

    /** overlay.js:20: a fill and three rings of rising strength; the strongest also shows where hidden. */
    private static <K> void glow(Map<K, List<Band>> marks, Map<K, List<Band>> hidden, K key, Color color) {
        Band strongest = new Band(.04f, .045f, alpha(color, 1));
        add(marks, key, List.of(new Band(.08f, .92f, alpha(color, .16f)), new Band(-.03f, .2f, alpha(color, .14f)),
              new Band(0, .1f, alpha(color, .3f)), strongest));
        add(hidden, key, List.of(strongest));
    }

    private static <K> void add(Map<K, List<Band>> marks, K key, List<Band> bands) {
        marks.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(bands);
    }

    /**
     * The plotted route, else the hover preview at half strength while no unit is hovered (overlay.js:40-47,
     * flat.js:43-47). It follows each step's hex and floor, including vertical steps in one hex, coloured by that
     * step's band, and ends in the destination ring, facing arrow and waypoints. A jump is one arc from
     * where it starts to where it lands, as in the prototype, although MegaMek's jump path lists every hex it passes.
     * A plotted route also places the ghost and hands the pulse its line and marks.
     */
    private void route(Sink sink, Sink markers, BoardScene.Unit moving) {
        Coords origin = moving.location().coords();
        boolean plotted = !move.route().isEmpty();
        List<GpuMovePlan.Step> steps = plotted ? move.route()
              : hoveredUnit == Entity.NONE ? move.hover() : List.of();
        if (steps.isEmpty() || scene.tile(origin) == null) {
            return;
        }
        float strength = plotted ? 1 : .5f;
        List<BoardScene.Waypoint> points = new ArrayList<>(List.of(moving.location()));
        List<GpuMovePlan.Band> bands = new ArrayList<>();
        bands.add(null);
        for (GpuMovePlan.Step step : steps) {
            if (step.boardId() != scene.boardId()
                  || step.coords().equals(points.getLast().coords())
                        && (tactical || step.level() == points.getLast().elevation())
                  || scene.tile(step.coords()) == null) {
                continue;
            }
            BoardScene.Waypoint point = new BoardScene.Waypoint(step.coords(), step.level(), step.facing());
            if (step.band() == GpuMovePlan.Band.JUMP && bands.getLast() == GpuMovePlan.Band.JUMP) {
                points.set(points.size() - 1, point);
            } else {
                points.add(point);
                bands.add(step.band());
            }
        }
        GpuMovePlan.Step end = steps.getLast();
        int facing = plotted && move.facing() >= 0 ? move.facing() : end.facing();
        BoardScene.Waypoint destination = points.getLast();
        if (plotted && end.boardId() == scene.boardId() && scene.tile(end.coords()) != null) {
            mover = moving;
            arrival = new BoardScene.Waypoint(end.coords(), end.level(), facing);
            // The pulse runs to the ghost; it keeps its rhythm while the plan stays the same.
            int hexes = 0;
            for (int i = 1; i < points.size(); i++) {
                hexes += Math.max(points.get(i - 1).coords().distance(points.get(i).coords()),
                      Math.round(Math.abs(points.get(i - 1).elevation() - points.get(i).elevation())));
            }
            pulse.arrive(List.of(moving.id(), origin, steps), hexes,
                  markerCenter(destination, 0),
                  moveColor(bands.getLast() == null ? end.band() : bands.getLast()));
        }
        if (points.size() == 1) {
            arrow(markers, destination, facing, 1);
            return;
        }
        boolean pulsing = mover != null;
        List<Vector3> at = new ArrayList<>();
        for (BoardScene.Waypoint point : points) {
            Vector3 center = BoardGeometry.center(point.coords(), point.elevation());
            at.add(tactical || onGround(point) ? on(scene.tile(point.coords()), center, .07f)
                  : center.add(0, 0, .07f * radius));
        }
        float travelled = 0;
        for (int i = 1; i < at.size(); i++) {
            Color color = alpha(moveColor(bands.get(i)), strength);
            Vector3 a = at.get(i - 1);
            Vector3 b = at.get(i);
            float length = a.dst(b) / radius;
            boolean jump = !tactical && bands.get(i) == GpuMovePlan.Band.JUMP;
            boolean draped = tactical || onGround(points.get(i - 1)) && onGround(points.get(i));
            if (pulsing) {
                trace(a, b, jump, draped, travelled, length, color);
            }
            if (tactical) {
                // flat.js:44: one 3-pixel line dashed 7 on, 5 off along the whole route.
                float period = 12 * PIXEL;
                for (float start = (float) Math.floor(travelled / period) * period; start < travelled + length;
                      start += period) {
                    float from = Math.max(start, travelled);
                    float to = Math.min(start + 7 * PIXEL, travelled + length);
                    if (to > from) {
                        dash(sink, above(a, b, (from - travelled) / length, draped),
                              above(a, b, (to - travelled) / length, draped),
                              1.5f * PIXEL, (from + to) / 2, color, pulsing);
                    }
                }
            } else if (jump) {
                for (int j = 1; j <= 14; j++) {
                    float t = j / 15f;
                    Vector3 dot = arc(a, b, t);
                    disc(sink, dot, .05f, 10, color);
                    if (pulsing) {
                        pulse.mark(dot, dot, .05f, travelled + t * length, color);
                    }
                }
            } else {
                for (float d = .12f; d < length - .1f; d += .42f) {
                    dash(sink, above(a, b, d / length, draped),
                          above(a, b, Math.min(1, (d + .24f) / length), draped), .04f,
                          travelled + d + .12f, color, pulsing);
                }
            }
            if (pulsing && !tactical) {
                // The step's disc at its hex's centre, drawn below.
                pulse.mark(b, b, .085f, travelled + length, color);
            }
            travelled += length;
        }
        Color last = moveColor(bands.getLast());
        if (tactical) {
            ring(markers, scene.tile(destination.coords()), 3 * PIXEL, .06f - 1.5f * PIXEL, 0, last,
                  markerCenter(destination, 0).z);
        } else {
            for (int i = 1; i < at.size(); i++) {
                disc(sink, at.get(i), .085f, 16, alpha(moveColor(bands.get(i)), strength));
            }
            ring(markers, scene.tile(destination.coords()), .05f, .02f, .02f, alpha(last, strength),
                  markerCenter(destination, 0).z);
        }
        arrow(markers, destination, facing, strength);
        for (GpuMovePlan.Step pin : move.pins()) {
            BoardScene.Tile tile = scene.tile(pin.coords());
            if (tile == null || pin.boardId() != scene.boardId()) {
                continue;
            }
            BoardScene.Waypoint point = new BoardScene.Waypoint(pin.coords(), pin.level(), pin.facing());
            if (tactical) {
                disc(markers, markerCenter(point, 0), 8 * PIXEL, 24, alpha(Color.WHITE, .95f));
            } else {
                ring(markers, tile, .06f, .2f, .02f, alpha(Color.WHITE, .95f), markerCenter(point, 0).z);
                disc(markers, markerCenter(point, .1f), .16f, 20, alpha(Color.WHITE, .9f));
            }
        }
    }

    /**
     * The facing arrow at the destination: overlay.js:48, a notched white head in 3D; flat.js:101, a plain
     * triangle in the Tactical View, always at full strength. Terrain must never hide the facing cue.
     */
    private void arrow(Sink sink, BoardScene.Waypoint point, int facing, float strength) {
        Vector3 center = markerCenter(point, .09f);
        float angle = facing * 60 * MathUtils.degreesToRadians;
        Vector3 ahead = new Vector3(MathUtils.sin(angle), MathUtils.cos(angle), 0).scl(radius);
        Vector3 across = new Vector3(ahead.y, -ahead.x, 0);
        if (tactical) {
            sink.triangle(point(center, ahead, .85f, across, 0), point(center, ahead, .45f, across, .22f),
                  point(center, ahead, .45f, across, -.22f), Color.WHITE);
        } else {
            Color color = alpha(Color.WHITE, strength);
            Vector3 tip = point(center, ahead, .9f, across, 0);
            Vector3 notch = point(center, ahead, .65f, across, 0);
            sink.triangle(tip, point(center, ahead, .55f, across, .24f), notch, color);
            sink.triangle(tip, notch, point(center, ahead, .55f, across, -.24f), color);
        }
    }

    /**
     * Interpolates between the actual step heights. Ground routes follow the terrain they cross; building floors
     * and other elevated or underground routes retain their levels instead of snapping to the ground.
     */
    private Vector3 above(Vector3 a, Vector3 b, float t, boolean draped) {
        Vector3 point = lerp(a, b, t);
        if (!draped) { return point; }
        float ground = UnitLandingSupports.surface(scene, point.x, point.y, surfaces);
        if (Float.isFinite(ground)) {
            point.z = Math.max(point.z, ground + .07f * radius);
        }
        return point;
    }

    /** overlay.js:86: a point of a jump's arc, a sine 1.2 hex radii high over the line between its ends. */
    private Vector3 arc(Vector3 a, Vector3 b, float t) {
        return lerp(a, b, t).add(0, 0, MathUtils.sin(MathUtils.PI * t) * 1.2f * radius);
    }

    /** A dash of the route, {@code distance} hex radii along it; the pulse lights it as it passes. */
    private void dash(Sink sink, Vector3 from, Vector3 to, float halfWidth, float distance, Color color,
          boolean pulsing) {
        segment(sink, from, to, halfWidth, color);
        if (pulsing) {
            pulse.mark(from, to, halfWidth, distance, color);
        }
    }

    /**
     * Hands the pulse the route's line from {@code a} to {@code b} as the route is drawn, kept over the ground or along
     * the jump's arc, from {@code from} hex radii along the route.
     */
    private void trace(Vector3 a, Vector3 b, boolean jump, boolean draped, float from, float length, Color color) {
        int steps = jump ? 24 : Math.max(1, MathUtils.ceil(length / .25f));
        for (int step = 0; step <= steps; step++) {
            float t = step / (float) steps;
            Vector3 point = jump ? arc(a, b, t) : above(a, b, t, draped);
            float ground = draped ? UnitLandingSupports.surface(scene, point.x, point.y, surfaces)
                  : lerp(a, b, t).z - .07f * radius;
            pulse.point(point, Float.isFinite(ground) ? ground : point.z - .07f * radius, from + t * length, color);
        }
    }

    private static Vector3 point(Vector3 center, Vector3 ahead, float along, Vector3 across, float aside) {
        return new Vector3(center).mulAdd(ahead, along).mulAdd(across, aside);
    }

    /**
     * overlay.js:66: the displayed weapon's mount arc as a flat mint wedge around the actor, from its start to its
     * end clockwise, measured from the facing the weapon fires from (E3b's WeaponArc).
     */
    private void wedge(Sink sink, GpuFireOrders.WeaponArc arc) {
        BoardScene.Tile tile = scene.tile(arc.origin());
        if (tile == null) {
            return;
        }
        Vector3 center = on(tile, BoardGeometry.center(arc.origin(), 0), .08f);
        // FacingArc's end below its start wraps through 0; 0 to 360 is all around.
        int degrees = arc.end() > arc.start() ? arc.end() - arc.start() : arc.end() + 360 - arc.start();
        float from = (arc.facing() * 60 + arc.start()) * MathUtils.degreesToRadians;
        float sweep = degrees * MathUtils.degreesToRadians / WEDGE_SEGMENTS;
        Color color = alpha(MINT, .22f);
        for (int step = 0; step < WEDGE_SEGMENTS; step++) {
            float a = from + step * sweep;
            float b = a + sweep;
            sink.quad(polar(center, a, WEDGE_INNER), polar(center, a, WEDGE_OUTER), polar(center, b, WEDGE_OUTER),
                  polar(center, b, WEDGE_INNER), color);
        }
    }

    /** The point {@code distance} hex radii from {@code center}, {@code angle} radians clockwise from north. */
    private Vector3 polar(Vector3 center, float angle, float distance) {
        return new Vector3(center).add(MathUtils.sin(angle) * distance * radius,
              MathUtils.cos(angle) * distance * radius, 0);
    }

    private Color moveColor(GpuMovePlan.Band band) {
        return rgb(movementColors.get(band));
    }

    // Primitives. Sizes are in hex radii, as the prototype's (its hex radius is 1); dy lifts above the surface.

    /** Engine.js hexRing: {@code width} radii wide, its outer edge {@code inset} radii inside the hex edge. */
    private void ring(Sink sink, BoardScene.Tile tile, float width, float inset, float dy, Color color) {
        ring(sink, tile, width, inset, dy, color, Float.NaN);
    }

    private void ring(Sink sink, BoardScene.Tile tile, float width, float inset, float dy, Color color, float plane) {
        Vector3[] outer = corners(tile, 1 - inset, dy, plane);
        Vector3[] inner = corners(tile, 1 - inset - width, dy, plane);
        for (int corner = 0; corner < 6; corner++) {
            int next = (corner + 1) % 6;
            sink.quad(outer[corner], outer[next], inner[next], inner[corner], color);
        }
    }

    /** The border of a set of hexes: the sides whose neighbour is outside it (overlay.js outlineSet). */
    private void outline(Sink sink, Set<Coords> set, float halfWidth, boolean dashed, Color color) {
        for (Coords coords : set) {
            BoardScene.Tile tile = scene.tile(coords);
            if (tile == null) {
                continue;
            }
            for (int direction = 0; direction < 6; direction++) {
                if (!set.contains(coords.translated(direction))) {
                    side(sink, tile, direction, halfWidth, dashed, color);
                }
            }
        }
    }

    /** A side of a set's border, just inside the hex edge (overlay.js outlineSet). */
    private void side(Sink sink, BoardScene.Tile tile, int direction, float halfWidth, boolean dashed, Color color) {
        edge(sink, tile, direction, .97f, halfWidth, .035f, dashed, color);
    }

    /**
     * The side of a hex toward MegaMek direction {@code direction} at {@code fraction} of the radius: solid, or the
     * prototype's three dashes of 20 % every 34 %.
     */
    private void edge(Sink sink, BoardScene.Tile tile, int direction, float fraction, float halfWidth, float dy,
          boolean dashed, Color color) {
        Vector3[] corners = corners(tile, fraction, dy);
        int edge = Math.floorMod(1 - direction, 6);
        Vector3 a = corners[edge];
        Vector3 b = corners[(edge + 1) % 6];
        if (!dashed) {
            segment(sink, a, b, halfWidth, color);
            return;
        }
        for (float t = 0; t < 1; t += .34f) {
            segment(sink, lerp(a, b, t), lerp(a, b, Math.min(1, t + .2f)), halfWidth, color);
        }
    }

    /** A band from a to b; vertical floor changes use crossed ribbons so every camera bearing can see them. */
    private void segment(Sink sink, Vector3 a, Vector3 b, float halfWidth, Color color) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float length = (float) Math.hypot(dx, dy);
        if (length < .001f) {
            if (Math.abs(a.z - b.z) >= .001f) {
                float width = halfWidth * radius;
                sink.quad(a.cpy().add(width, 0, 0), a.cpy().add(-width, 0, 0),
                      b.cpy().add(-width, 0, 0), b.cpy().add(width, 0, 0), color);
                sink.quad(a.cpy().add(0, width, 0), a.cpy().add(0, -width, 0),
                      b.cpy().add(0, -width, 0), b.cpy().add(0, width, 0), color);
            }
            return;
        }
        float scale = halfWidth * radius / length;
        float x = -dy * scale;
        float y = dx * scale;
        sink.quad(new Vector3(a.x + x, a.y + y, a.z), new Vector3(a.x - x, a.y - y, a.z),
              new Vector3(b.x - x, b.y - y, b.z), new Vector3(b.x + x, b.y + y, b.z), color);
    }

    private void disc(Sink sink, Vector3 center, float discRadius, int segments, Color color) {
        float size = discRadius * radius;
        for (int i = 0; i < segments; i++) {
            float from = MathUtils.PI2 * i / segments;
            float to = MathUtils.PI2 * (i + 1) / segments;
            sink.triangle(center, new Vector3(center).add(MathUtils.cos(from) * size, MathUtils.sin(from) * size, 0),
                  new Vector3(center).add(MathUtils.cos(to) * size, MathUtils.sin(to) * size, 0), color);
        }
    }

    /** The six corners of a hex scaled by {@code fraction} about its centre, on its surface. */
    private Vector3[] corners(BoardScene.Tile tile, float fraction, float dy) {
        return corners(tile, fraction, dy, Float.NaN);
    }

    private Vector3[] corners(BoardScene.Tile tile, float fraction, float dy, float plane) {
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        Vector3[] corners = new Vector3[6];
        for (int corner = 0; corner < 6; corner++) {
            Vector3 point = BoardGeometry.inset(BoardGeometry.corner(tile.coords(), 0, corner), center, 1 - fraction);
            if (Float.isFinite(plane)) {
                point.z = plane + dy * radius;
            } else {
                on(tile, point, dy);
            }
            corners[corner] = point;
        }
        return corners;
    }

    private boolean onGround(BoardScene.Waypoint point) {
        return point.elevation() == scene.tile(point.coords()).elevation();
    }

    private Vector3 markerCenter(BoardScene.Waypoint point, float dy) {
        float z = tactical || onGround(point) ? BoardTacticalGeometry.floatingZ(scene, point.coords())
              : point.elevation() * BoardGeometry.level() + BoardTacticalGeometry.HEX_PLANE_CLEARANCE;
        return BoardGeometry.center(point.coords(), 0).add(0, 0, z + dy * radius);
    }

    /**
     * Sets a point's height to its hex's own surface there, water included, as units stand on it, lifted by
     * {@code dy} radii. In the hex's footprint that is the drawn ground, which beside a step can be the step's face
     * lying back over the hex rather than its top (the user's report of 2026-10-03: the envelope's border lay under
     * those faces). The hex's own surface keeps a ring that reaches past the hex edge at the hex's level.
     */
    private Vector3 on(BoardScene.Tile tile, Vector3 point, float dy) {
        float ground = tile.frozen() ? BoardGeometry.surfaceZ(tile)
              : BoardGeometry.contains(tile.coords(), point.x, point.y)
              ? UnitLandingSupports.surface(scene, point.x, point.y, surfaces)
              : surfaces.get(scene, tile).height(point.x, point.y);
        if (tile.liquid().present()) {
            ground = Math.max(ground, BoardGeometry.waterZ(tile));
        }
        point.z = ground + dy * radius;
        return point;
    }

    private static Vector3 lerp(Vector3 a, Vector3 b, float t) {
        return new Vector3(a).lerp(b, t);
    }

    private static Color rgb(int rgb) {
        return new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1);
    }

    /**
     * Unlit vertex colours blended in drawing order, without writing depth: depth-tested against the terrain and
     * models by default, or always visible for movement previews and the flat Tactical View.
     */
    private static Material material(boolean alwaysVisible) {
        return new Material(ColorAttribute.createDiffuse(Color.WHITE),
              new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
              new DepthTestAttribute(alwaysVisible ? GL20.GL_ALWAYS : GL20.GL_LEQUAL, false),
              IntAttribute.createCullFace(GL20.GL_NONE));
    }

    @Override
    public void dispose() {
        if (movementMarkers != null) {
            movementMarkers.model.dispose();
            movementMarkers = null;
        }
        if (overlay != null) {
            overlay.model.dispose();
            overlay = null;
        }
        // The ghost owns no GL object: its meshes and textures are the shown model's.
        ghost = null;
        ghostSource = null;
        pulse.dispose();
        surfaces.clear();
        batch.dispose();
        ghostBatch.dispose();
        ghostBlend.dispose();
        shapes.dispose();
    }

    /** One model of vertex-coloured triangles, in parts small enough for 16-bit indices. */
    private static final class Sink {
        /** ModelBuilder starts a new mesh once one holds 32768 vertices, so a part may add this many more. */
        private static final int PART_VERTICES = 30000;
        private final ModelBuilder builder = new ModelBuilder();
        private Material material;
        private MeshPartBuilder part;
        private int vertices;
        private int parts;

        Sink(Material material) {
            this.material = material;
            builder.begin();
        }

        /** Starts a separate mesh part so changing depth policy cannot affect earlier triangles. */
        void material(Material next) {
            material = next;
            part = null;
        }

        void triangle(Vector3 a, Vector3 b, Vector3 c, Color color) {
            MeshPartBuilder mesh = reserve(3);
            mesh.triangle(mesh.vertex(a, null, color, null), mesh.vertex(b, null, color, null),
                  mesh.vertex(c, null, color, null));
        }

        void quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Color color) {
            MeshPartBuilder mesh = reserve(4);
            short first = mesh.vertex(a, null, color, null);
            short second = mesh.vertex(b, null, color, null);
            short third = mesh.vertex(c, null, color, null);
            short fourth = mesh.vertex(d, null, color, null);
            mesh.triangle(first, second, third);
            mesh.triangle(first, third, fourth);
        }

        private MeshPartBuilder reserve(int count) {
            if (part == null || vertices + count > PART_VERTICES) {
                part = builder.part("overlay-" + parts++, GL20.GL_TRIANGLES, VERTICES, material);
                vertices = 0;
            }
            vertices += count;
            return part;
        }

        /** The finished model, or null when nothing was drawn. */
        ModelInstance end() {
            Model model = builder.end();
            if (parts == 0) {
                model.dispose();
                return null;
            }
            return new ModelInstance(model);
        }
    }
}
