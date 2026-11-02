/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/**
 * The Tactical View's unit bases, under and over each meeple, in world space: sized in hex
 * apothems, they zoom with the board and stay inside the unit's hex. The base, drawn before the units: a ring in the
 * side's colour with a glow for the acting unit and the focused enemy, an outer ring that pulses while the unit can
 * act, the facing notch and the thinner twist notch. The badges, drawn after the units: the heat pip, the elevation or
 * altitude, the armour and structure bars with the damage tile, the movement modifier chevrons in the movement type's
 * colour, the two most severe condition icons, the E(C)CM source badge and a wreck's cross. Every value is the client's
 * (the battle status, the E(C)CM record): {@link #spec} maps them, and a unit's models are built again only when its
 * spec changes. Per frame only the transforms follow the animated poses, the notches turn with the animated facing,
 * and the pulse and the zoom fade change material colours. GL thread; owns its models and batch.
 */
final class GpuUnitBases implements Disposable {
    /** The base ring's radius; the HUD anchors a unit's labels at its north point. */
    static final float RING = .80f;
    private static final float RING_WIDTH = .075f;
    private static final float CASING = .04f;
    private static final float GLOW = .85f;
    private static final float GLOW_WIDTH = .22f;
    private static final float READY_RING = .94f;
    private static final float READY_WIDTH = .035f;
    private static final float NOTCH_BASE = .70f;
    private static final float NOTCH_TIP = 1.0f;
    private static final float NOTCH_HALF = .17f;
    private static final float TWIST_BASE = .76f;
    private static final float TWIST_TIP = .99f;
    private static final float TWIST_HALF = .075f;
    private static final float CROSS_END = .52f;
    private static final float CROSS_WIDTH = .085f;
    /** The badge slots, between the notches: the top band, the bottom band, the west and east columns. */
    private static final float TOP = .80f;
    private static final float BAND_X = .42f;
    private static final float BOTTOM = -.865f;
    private static final float COLUMN_X = .93f;
    private static final float COLUMN_Y = .19f;
    private static final float PIP = .105f;
    private static final float BADGE = .15f;
    /** Opacities of a unit that acted this phase, and the ready pulse's range at 1 Hz. */
    private static final float DONE_RING = .42f;
    private static final float DONE_NOTCH = .6f;
    private static final float DONE_BADGES = .7f;
    private static final float READY_LOW = .65f;
    /** Below this many screen pixels per hex width the badges fade out; ring, notches, heat pip and cross stay. */
    static final float FADE_PIXELS = 40;
    private static final int SEGMENTS = 48;
    private static final Color INK = new Color(.06f, .08f, .08f, 1);
    private static final Color PANEL = new Color(.09f, .13f, .13f, .92f);
    private static final Color GREY = Color.valueOf("9AA6A3");
    private static final Color ACTING_GLOW = Color.valueOf("a3ffe0");
    private static final Color FOCUS_GLOW = Color.valueOf("ff7a70");
    private static final Color CROSS = UiTheme.CORAL;
    private static final Color TRACK = Color.valueOf("4a5553");
    /** The condition icons in the HUD's icon font by status word key or label tile; other tiles show their letter. */
    private static final Map<String, String> CONDITION_ICONS = Map.ofEntries(Map.entry("SHUTDOWN", "power"),
          Map.entry("PRONE", "prone"), Map.entry("IMMOBILE", "anchor"), Map.entry("STUCK", "anchor"),
          Map.entry("Jammed", "jammed"), Map.entry("HULLDOWN", "hull-down"), Map.entry("HIDDEN", "hidden"),
          Map.entry("U", "hidden"), Map.entry("H", "hidden"));
    private static final Set<String> LETTER_TILES = Set.of("T", "N", "D", "E");
    // Material ids: each instance owns copies, whose colour carries the frame's opacity.
    private static final String GLOW_PART = "glow";
    private static final String RING_PART = "ring";
    private static final String READY_PART = "ready";
    private static final String NOTCH_PART = "notch";
    private static final String CORE_PART = "core";
    private static final String DETAIL_PART = "detail";

    /** The look of a base: its side's colour, a sensor contact's, or grey for a wreck or a unit the status lacks. */
    enum Look { FRIEND, ENEMY, CONTACT, NEUTRAL }

    /** The glow of the acting unit and of the focused enemy. */
    enum Emphasis { NONE, ACTING, FOCUS }

    /** A condition: an icon of the HUD's icon set, or a letter; {@code severity} orders and colours it. */
    record Condition(String icon, String letter, UnitStatusWords.Severity severity) { }

    /**
     * A unit's E(C)CM source badge: its side as the E(C)CM record has it, what it emits, whether TacOps ECCM negated its
     * field and whether its field is open (hovered or selected); null for none.
     */
    record Ecm(boolean own, boolean ecm, boolean eccm, boolean angel, boolean suppressed, boolean open) { }

    /**
     * Everything a unit's base and badges show; equal specs draw the same models. {@code twist} is the twist notch in
     * hexsides clockwise of the facing, 0 for none; {@code elevation} is signed, 0 for none, {@code altitude} marks an
     * aerospace altitude; {@code chevrons} is the number of movement modifier chevrons, -1 for none and 0 for one
     * hollow chevron (moved, no modifier); {@code badges}: this unit shows its badges (one per stack of units).
     */
    record BaseSpec(Look look, boolean facing, int twist, Emphasis emphasis, boolean done, boolean ready,
          int heatRgb, int elevation, boolean altitude, GpuBattleStatus.Marks marks, float armor, float structure,
          int chevrons, int moveRgb, List<Condition> conditions, Ecm ecm, boolean destroyed, boolean badges) {
        BaseSpec {
            conditions = List.copyOf(conditions);
        }

        /**
         * This base without its status badges (the Overlays panel's Status badges off): no heat pip, armour and
         * structure bars, damage tile, chevrons, conditions or E(C)CM badge. Ring, notches, the elevation or altitude
         * pill and a wreck's cross stay.
         */
        BaseSpec withoutStatus() {
            return new BaseSpec(look, facing, twist, emphasis, done, ready, 0, elevation, altitude,
                  GpuBattleStatus.Marks.NONE, 0, 0, -1, 0, List.of(), null, destroyed, badges);
        }
    }

    /** A unit's two models and what they were built from: the spec, and a multi-hex unit's hexes. */
    private record Shown(BaseSpec spec, List<Coords> footprint, Model base, Model badges, ModelInstance baseInstance,
          ModelInstance badgeInstance) { }

    private final Skin skin;
    private final Map<String, Shown> shown = new HashMap<>();
    /** This frame's placed units, in drawing order. */
    private final List<Shown> placed = new ArrayList<>();
    private ModelBatch batch;
    private float clock;
    private float fade = 1;

    /** {@code skin}: the HUD's skin, whose white texel, icon sheet and default font the badges borrow. */
    GpuUnitBases(Skin skin) {
        this.skin = skin;
    }

    /**
     * Maps one listed unit to its base: the status ({@code null} for a unit the status does not list), whether its
     * pose is dead, the actor and focused enemy (as the board overlay decided them), the E(C)CM source it is, if any,
     * and the links it starts. A sensor contact shows its look alone; a wreck a grey ring and the cross.
     */
    static BaseSpec spec(GpuBattleStatus.UnitStatus status, boolean sensorContact, boolean dead, int actor, int focus,
          BoardElectronicWarfare.Source source, boolean open, List<GpuBattleStatus.Link> links, boolean badges) {
        if (sensorContact || status == null || status.sensorContact()) {
            Look look = sensorContact || status != null ? Look.CONTACT : Look.NEUTRAL;
            return new BaseSpec(look, false, 0, Emphasis.NONE, false, false, 0, 0, false,
                  GpuBattleStatus.Marks.NONE, 0, 0, -1, 0, List.of(), null, dead, badges && dead);
        }
        // The playback pose decides when death is shown; the live status can already describe a later frame.
        if (dead) {
            return new BaseSpec(Look.NEUTRAL, false, 0, Emphasis.NONE, false, false, 0, 0, false,
                  GpuBattleStatus.Marks.NONE, 0, 0, -1, 0, List.of(), null, true, badges);
        }
        Look look = status.side() == GpuBattleStatus.Side.ENEMY ? Look.ENEMY : Look.FRIEND;
        boolean facing = status.facing() >= 0;
        int twist = facing && status.twist() >= 0 ? Math.floorMod(status.twist() - status.facing(), 6) : 0;
        Emphasis emphasis = status.id() == focus ? Emphasis.FOCUS : status.id() == actor ? Emphasis.ACTING
              : Emphasis.NONE;
        int elevation = 0;
        boolean altitude = false;
        for (UnitStatusWords.StatusWord tile : status.statusTiles()) {
            if (tile.key().equals("ELEV") || tile.key().equals("ALT")) {
                elevation = parse(tile.label());
                altitude = tile.key().equals("ALT");
            }
        }
        int chevrons = !status.tmmShown() || status.moved().isEmpty() && status.tmm() <= 0 ? -1
              : Math.clamp(status.tmm(), 0, 6);
        Ecm ecm = source == null ? null
              : new Ecm(source.own(), source.ecm(), source.eccm(), source.angel(), source.suppressed(), open);
        return new BaseSpec(look, facing, twist, emphasis, status.done(), status.canActNow() && !status.done(),
              status.heat() > 0 ? status.heatRgb() : 0, elevation, altitude, status.marks(),
              (float) Math.max(0, status.armor()), (float) Math.max(0, status.structure()), chevrons,
              status.moveRgb(), conditions(status, links), ecm, false, badges);
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * The two most severe conditions, from the label's status words and tiles in their order, and the spotting or TAG
     * links the unit starts; words without an icon are left to the tag, which lists them all.
     */
    static List<Condition> conditions(GpuBattleStatus.UnitStatus status, List<GpuBattleStatus.Link> links) {
        List<Condition> all = new ArrayList<>();
        for (UnitStatusWords.StatusWord word : status.statusWords()) {
            String icon = CONDITION_ICONS.get(word.key());
            if (icon != null) { all.add(new Condition(icon, "", word.severity())); }
        }
        for (UnitStatusWords.StatusWord tile : status.statusTiles()) {
            String icon = CONDITION_ICONS.get(tile.key());
            if (icon != null) {
                all.add(new Condition(icon, "", tile.severity()));
            } else if (LETTER_TILES.contains(tile.key())) {
                all.add(new Condition("", tile.key(), tile.severity()));
            }
        }
        for (GpuBattleStatus.Link link : links) {
            if (link.fromId() == status.id()) {
                all.add(new Condition(link.kind() == GpuBattleStatus.Link.Kind.SPOT ? "spotting" : "locate", "",
                      UnitStatusWords.Severity.PRECAUTION));
            }
        }
        // Stable: equal severities keep the label's order.
        return all.stream().distinct().sorted(Comparator.comparing(Condition::severity)).limit(2).toList();
    }

    /** The unit models of this frame, as {@link #update} placed them. */
    ModelInstance base(BoardScene.Unit unit) {
        Shown entry = shown.get(key(unit));
        return entry == null ? null : entry.baseInstance();
    }

    ModelInstance badges(BoardScene.Unit unit) {
        Shown entry = shown.get(key(unit));
        return entry == null ? null : entry.badgeInstance();
    }

    BaseSpec spec(BoardScene.Unit unit) {
        Shown entry = shown.get(key(unit));
        return entry == null ? null : entry.spec();
    }

    /** The badges' opacity at the frame's zoom: 1 from 40 screen pixels per hex width, 0 at half of that. */
    static float fade(float hexPixels) {
        return MathUtils.clamp((hexPixels - FADE_PIXELS / 2) / (FADE_PIXELS / 2), 0, 1);
    }

    private static String key(BoardScene.Unit unit) {
        return unit.id() + ":" + unit.part();
    }

    /**
     * Places a base for every scene unit with a pose, from the frame's status and E(C)CM record. {@code actor} and
     * {@code focus} are the board overlay's acting unit and focused enemy, {@code hoveredUnit} and {@code hovered}
     * the pointer's; {@code hexPixels} a hex's width on the screen and {@code seconds} the frame's time;
     * {@code statusBadges} the client setting that shows the status badges.
     */
    void update(BoardScene scene, GpuBattleStatus.Snapshot status, Map<BoardScene.Unit, UnitFootprint.Pose> poses,
          int actor, int focus, int hoveredUnit, Coords hovered, float hexPixels, float seconds,
          boolean statusBadges, List<MeepleCasualties.Casualty> casualties) {
        clock = (clock + seconds) % 1;
        fade = fade(hexPixels);
        Map<Integer, GpuBattleStatus.UnitStatus> listed = new HashMap<>();
        status.units().forEach(unit -> listed.put(unit.id(), unit));
        BoardElectronicWarfare warfare = scene.electronicWarfare();
        boolean ecmShown = warfare != null && warfare.strength() > 0;
        var open = ecmShown ? GpuElectronicWarfare.open(warfare, hoveredUnit, hovered, scene.selectedId()) : null;
        Map<Integer, BoardElectronicWarfare.Source> sources = new HashMap<>();
        if (ecmShown) {
            warfare.sources().stream().filter(source -> source.entityId() != Entity.NONE)
                  .forEach(source -> sources.putIfAbsent(source.entityId(), source));
        }
        Set<String> badged = badged(scene, hoveredUnit);
        placed.clear();
        Set<String> keys = new java.util.HashSet<>();
        for (BoardScene.Unit unit : scene.units()) {
            UnitFootprint.Pose pose = poses.get(unit);
            if (pose == null || unit.attachment() != null) { continue; }
            String key = key(unit);
            keys.add(key);
            var source = sources.get(unit.id());
            BaseSpec spec = spec(listed.get(unit.id()), unit.sensorContact(), false, actor, focus, source,
                  open != null && source != null && open.source().entityId() == unit.id(), status.links(),
                  badged.contains(key) && unit.part() <= 0);
            if (!statusBadges) { spec = spec.withoutStatus(); }
            show(unit, pose, spec, 1);
        }
        for (var casualty : casualties) {
            if (casualty.pose == null || casualty.unit.attachment() != null) { continue; }
            keys.add(key(casualty.unit));
            show(casualty.unit, casualty.pose,
                  spec(null, false, true, Entity.NONE, Entity.NONE, null, false, List.of(), true), casualty.opacity());
        }
        shown.entrySet().removeIf(entry -> {
            boolean gone = !keys.contains(entry.getKey());
            if (gone) { dispose(entry.getValue()); }
            return gone;
        });
    }

    private void show(BoardScene.Unit unit, UnitFootprint.Pose pose, BaseSpec spec, float opacity) {
        String key = key(unit);
        Shown entry = shown.get(key);
        if (entry == null || !entry.spec().equals(spec) || !sameFootprint(entry, unit)) {
            if (entry != null) { dispose(entry); }
            entry = build(spec, unit);
            shown.put(key, entry);
        }
        place(entry, unit, pose);
        for (var instance : List.of(entry.baseInstance(), entry.badgeInstance())) {
            if (!(instance.userData instanceof GpuUnitShader.Shown)) { instance.userData = new GpuUnitShader.Shown(); }
            ((GpuUnitShader.Shown) instance.userData).opacity = opacity;
        }
        placed.add(entry);
    }

    /**
     * The units that show their badges: one per stack sharing a hex, the hovered or selected unit if it is in the
     * stack, else the first; the others show their rings only.
     */
    private static Set<String> badged(BoardScene scene, int hoveredUnit) {
        Map<Coords, List<BoardScene.Unit>> stacks = new LinkedHashMap<>();
        for (BoardScene.Unit unit : scene.units()) {
            if (unit.attachment() == null && unit.part() <= 0) {
                stacks.computeIfAbsent(unit.location().coords(), coords -> new ArrayList<>()).add(unit);
            }
        }
        return stacks.values().stream().map(stack -> stack.stream().filter(unit -> unit.id() == hoveredUnit)
                    .findFirst().or(() -> stack.stream().filter(unit -> unit.id() == scene.selectedId()).findFirst())
                    .orElse(stack.getFirst()))
              .map(GpuUnitBases::key).collect(Collectors.toSet());
    }

    /** A multi-hex unit's rings lie where its hexes lay when they were built; one hex is always the unit's own. */
    private static boolean sameFootprint(Shown entry, BoardScene.Unit unit) {
        return footprint(unit).equals(entry.footprint());
    }

    private static List<Coords> footprint(BoardScene.Unit unit) {
        return unit.footprint().size() <= 1 ? List.of() : unit.footprint();
    }

    /** The frame's transforms and opacities: the pose's position, the notches turned with the animated facing. */
    private void place(Shown entry, BoardScene.Unit unit, UnitFootprint.Pose pose) {
        float apothem = BoardGeometry.height() / 2;
        Vector3 position = pose.position();
        for (ModelInstance instance : List.of(entry.baseInstance(), entry.badgeInstance())) {
            instance.transform.setToTranslation(position.x, position.y, position.z).scale(apothem, apothem, 1);
        }
        BaseSpec spec = entry.spec();
        // Facings run clockwise seen from above; rotations about +Z run counterclockwise. Other footprint hexes turn
        // with the unit as the board overlay's bands do.
        float facing = pose.facing();
        float turn = unit.location().facing() * 60 - facing;
        for (Node node : entry.baseInstance().nodes) {
            if (node.id.equals("facing")) { node.rotation.set(Vector3.Z, -facing); }
            if (node.id.equals("twist")) { node.rotation.set(Vector3.Z, -facing - spec.twist() * 60); }
            if (node.id.startsWith("hex:")) { node.rotation.set(Vector3.Z, turn); }
        }
        entry.baseInstance().calculateTransforms();
        float pulse = READY_LOW + (1 - READY_LOW) * (.5f + .5f * MathUtils.cos(MathUtils.PI2 * clock));
        opacity(entry.baseInstance(), RING_PART, spec.done() ? DONE_RING : spec.ready() ? pulse : 1);
        opacity(entry.baseInstance(), READY_PART, pulse);
        opacity(entry.baseInstance(), NOTCH_PART, spec.done() ? DONE_NOTCH : 1);
        opacity(entry.badgeInstance(), DETAIL_PART, fade * (spec.done() ? DONE_BADGES : 1));
    }

    private static void opacity(ModelInstance instance, String id, float alpha) {
        for (Material material : instance.materials) {
            if (material.id.startsWith(id)) {
                ((ColorAttribute) material.get(ColorAttribute.Diffuse)).color.a = alpha;
            }
        }
    }

    /**
     * The bases, before the units: the ring and notches cover the ground marks and write depth, so the draped overlays
     * drawn later stay under them; the meeples drawn next cover them.
     */
    void renderBases(Camera camera) {
        render(camera, true);
    }

    /** The badges and crosses, over the units. */
    void renderBadges(Camera camera) {
        render(camera, false);
    }

    private void render(Camera camera, boolean bases) {
        if (placed.isEmpty()) { return; }
        if (batch == null) {
            // Bases and badges blend into the linear scene before its final display conversion.
            batch = new ModelBatch(GpuShaderManager.provider("Unit bases", GpuUnitShader::provider),
                  (sortCamera, renderables) -> { });
        }
        float reach = BoardGeometry.height();
        batch.begin(camera);
        for (Shown entry : placed) {
            ModelInstance instance = bases ? entry.baseInstance() : entry.badgeInstance();
            Vector3 at = instance.transform.getTranslation(new Vector3());
            if (instance.nodes.notEmpty() && camera.frustum.sphereInFrustum(at, reach * 2)) {
                batch.render(instance);
            }
        }
        batch.end();
    }

    private static void dispose(Shown entry) {
        entry.base().dispose();
        entry.badges().dispose();
    }

    @Override
    public void dispose() {
        shown.values().forEach(GpuUnitBases::dispose);
        shown.clear();
        placed.clear();
        if (batch != null) {
            batch.dispose();
            batch = null;
        }
    }

    // Building the models, in apothems around the unit, north up (+y).

    private Shown build(BaseSpec spec, BoardScene.Unit unit) {
        Model base = base(spec, unit);
        Model badges = badges(spec);
        return new Shown(spec, footprint(unit), base, badges, new ModelInstance(base), new ModelInstance(badges));
    }

    private Model base(BaseSpec spec, BoardScene.Unit unit) {
        Color side = color(spec.look());
        Shapes shapes = new Shapes();
        // The facing node holds the rings, which a turn leaves alone, and the facing notch.
        shapes.node("facing");
        if (spec.emphasis() != Emphasis.NONE) {
            Color glow = spec.emphasis() == Emphasis.ACTING ? ACTING_GLOW : FOCUS_GLOW;
            shapes.softRing(GLOW_PART, false, 0, 0, GLOW, GLOW_WIDTH, glow, .55f);
        }
        ring(shapes, 0, 0, side);
        if (spec.ready()) {
            shapes.ring(READY_PART, true, 0, 0, READY_RING, READY_WIDTH, alpha(side, .9f));
        }
        if (spec.facing()) {
            notch(shapes, NOTCH_PART, NOTCH_BASE, NOTCH_TIP, NOTCH_HALF, side, true);
        }
        shapes.node("twist");
        if (spec.facing() && spec.twist() != 0) {
            notch(shapes, NOTCH_PART, TWIST_BASE, TWIST_TIP, TWIST_HALF, Color.WHITE, false);
        }
        // Each other hex of a multi-hex unit gets its ring, turned with the unit about its origin.
        Coords origin = unit.location().coords();
        Vector3 centre = BoardGeometry.center(origin, 0);
        float apothem = BoardGeometry.height() / 2;
        for (Coords occupied : unit.footprint()) {
            if (occupied.equals(origin)) { continue; }
            Vector3 offset = BoardGeometry.center(occupied, 0).sub(centre).scl(1 / apothem);
            shapes.node("hex:" + occupied.getX() + "," + occupied.getY());
            ring(shapes, offset.x, offset.y, side);
        }
        return shapes.end();
    }

    private void ring(Shapes shapes, float x, float y, Color side) {
        shapes.ring(RING_PART, true, x, y, RING, RING_WIDTH + 2 * CASING, alpha(INK, .55f));
        shapes.ring(RING_PART, true, x, y, RING, RING_WIDTH, side);
    }

    /** A notch from {@code from} to its tip at {@code to}, pointing north (the facing), with a dark casing. */
    private static void notch(Shapes shapes, String part, float from, float to, float half, Color color,
          boolean casing) {
        if (casing) {
            float grow = .035f;
            shapes.triangle(part, true, -half - grow, from - grow, half + grow, from - grow, 0, to + grow,
                  alpha(INK, .6f));
        }
        shapes.triangle(part, true, -half, from, half, from, 0, to, color);
    }

    private Model badges(BaseSpec spec) {
        Shapes shapes = new Shapes();
        shapes.node("badges");
        if (spec.destroyed()) {
            // Two bars corner to corner over the unit, on a dark casing.
            for (float sign : new float[] { 1, -1 }) {
                shapes.bar(CORE_PART, -CROSS_END, -sign * CROSS_END, CROSS_END, sign * CROSS_END,
                      CROSS_WIDTH + .05f, alpha(INK, .7f));
                shapes.bar(CORE_PART, -CROSS_END, -sign * CROSS_END, CROSS_END, sign * CROSS_END, CROSS_WIDTH,
                      CROSS);
            }
        }
        if (spec.heatRgb() != 0) {
            shapes.disc(CORE_PART, -BAND_X, TOP, PIP + .04f, PANEL);
            shapes.disc(CORE_PART, -BAND_X, TOP, PIP, rgb(spec.heatRgb()));
        }
        if (!spec.badges()) { return shapes.end(); }
        if (spec.elevation() != 0) {
            elevation(shapes, spec.elevation(), spec.altitude());
        }
        if (spec.marks().shown()) {
            marks(shapes, spec);
        }
        if (spec.chevrons() >= 0) {
            chevrons(shapes, spec.chevrons(), rgb(spec.moveRgb()));
        }
        for (int index = 0; index < spec.conditions().size(); index++) {
            condition(shapes, spec.conditions().get(index), -COLUMN_X, index == 0 ? COLUMN_Y : -COLUMN_Y);
        }
        if (spec.ecm() != null) {
            ecm(shapes, spec.ecm());
        }
        return shapes.end();
    }

    /** The NE slot: a dark pill with an up or down triangle and the digits. */
    private void elevation(Shapes shapes, int elevation, boolean altitude) {
        String digits = (altitude ? "A" : "") + Math.abs(elevation);
        float height = .2f;
        float textWidth = shapes.textWidth(digits, height * .62f);
        float width = height * .8f + textWidth + .07f;
        float left = BAND_X - width / 2;
        shapes.pill(DETAIL_PART, left, TOP - height / 2, width, height, PANEL);
        float cx = left + .05f + height * .3f;
        float half = height * .26f;
        float tip = elevation > 0 ? half : -half;
        shapes.triangle(DETAIL_PART, false, cx - half, TOP - tip * .8f, cx + half, TOP - tip * .8f, cx, TOP + tip,
              Color.WHITE);
        shapes.text(DETAIL_PART, digits, cx + half + .03f, TOP, height * .62f, Color.WHITE);
    }

    /** The bottom band's left plate: the damage tile and the armour bar over the structure bar, as the 2D label. */
    private void marks(Shapes shapes, BaseSpec spec) {
        GpuBattleStatus.Marks marks = spec.marks();
        float left = -.66f;
        float right = -.12f;
        float height = .15f;
        shapes.pill(DETAIL_PART, left, BOTTOM - height / 2, right - left, height, PANEL);
        float x = left + .04f;
        if (marks.damageArgb() != 0) {
            float tile = .09f;
            shapes.quad(DETAIL_PART, x, BOTTOM - tile / 2, tile, tile, argb(marks.damageArgb()));
            x += tile + .03f;
        }
        float length = right - .04f - x;
        boolean structure = marks.structureArgb() != 0;
        float bar = .035f;
        float armorY = structure ? BOTTOM + .01f : BOTTOM - bar / 2;
        shapes.quad(DETAIL_PART, x, armorY, length, bar, TRACK);
        shapes.quad(DETAIL_PART, x, armorY, length * Math.min(1, spec.armor()), bar, argb(marks.armorArgb()));
        if (structure) {
            float structureY = BOTTOM - .01f - bar;
            shapes.quad(DETAIL_PART, x, structureY, length, bar, TRACK);
            shapes.quad(DETAIL_PART, x, structureY, length * Math.min(1, spec.structure()), bar,
                  argb(marks.structureArgb()));
        }
    }

    /** The bottom band's right pill: one chevron per modifier point, or one hollow chevron for none. */
    private void chevrons(Shapes shapes, int count, Color color) {
        float step = .085f;
        float height = .15f;
        float left = .12f;
        float width = Math.max(1, count) * step + .07f;
        shapes.pill(DETAIL_PART, left, BOTTOM - height / 2, width, height, PANEL);
        for (int i = 0; i < Math.max(1, count); i++) {
            float x = left + .05f + i * step;
            float tip = x + .05f;
            float rise = .045f;
            float thick = .022f;
            Color shade = count == 0 ? alpha(color, .55f) : color;
            shapes.bar(DETAIL_PART, x, BOTTOM + rise, tip, BOTTOM, thick, shade);
            shapes.bar(DETAIL_PART, x, BOTTOM - rise, tip, BOTTOM, thick, shade);
        }
    }

    /** A west column condition: a dark disc ringed in the severity's colour, with its icon or letter. */
    private void condition(Shapes shapes, Condition condition, float x, float y) {
        Color color = severity(condition.severity());
        shapes.disc(DETAIL_PART, x, y, BADGE, PANEL);
        shapes.ring(DETAIL_PART, false, x, y, BADGE - .015f, .03f, color);
        if (!condition.icon().isEmpty()) {
            shapes.icon(DETAIL_PART, condition.icon(), x, y, BADGE * 1.35f, color);
        } else {
            shapes.text(DETAIL_PART, condition.letter(), x - shapes.textWidth(condition.letter(), BADGE) / 2, y,
                  BADGE, color);
        }
    }

    /**
     * The east slot: the E(C)CM source badge in its side's colour, a broadcast glyph for ECM, a shield for ECCM alone,
     * a small "A" for an Angel suite; at 40 % when TacOps ECCM negated its field, with a thicker border while open.
     */
    private void ecm(Shapes shapes, Ecm ecm) {
        float x = COLUMN_X;
        float y = COLUMN_Y;
        Color color = alpha(ecm.own() ? UiTheme.MINT : UiTheme.CORAL, ecm.suppressed() ? .4f : 1);
        shapes.disc(DETAIL_PART, x, y, BADGE, alpha(PANEL, ecm.suppressed() ? .5f : PANEL.a));
        shapes.ring(DETAIL_PART, false, x, y, BADGE - .015f, ecm.open() ? .055f : .03f, color);
        shapes.icon(DETAIL_PART, ecm.ecm() ? "ecm" : "eccm", x, y, BADGE * 1.35f, color);
        if (ecm.angel()) {
            shapes.text(DETAIL_PART, "A", x + BADGE * .55f, y - BADGE * .55f, BADGE * .6f, color);
        }
    }

    private static Color color(Look look) {
        return switch (look) {
            case FRIEND -> UiTheme.MINT;
            case ENEMY -> UiTheme.CORAL;
            case CONTACT -> UiTheme.BLIP;
            case NEUTRAL -> GREY;
        };
    }

    private static Color severity(UnitStatusWords.Severity severity) {
        return switch (severity) {
            case WARNING -> Color.valueOf("ff6b5e");
            case CAUTION -> Color.valueOf("ffc454");
            case PRECAUTION -> Color.valueOf("8cc4ff");
            case INFO -> Color.valueOf("c8d0cc");
        };
    }

    private static Color alpha(Color color, float alpha) {
        return new Color(color.r, color.g, color.b, color.a * alpha);
    }

    private static Color rgb(int rgb) {
        return new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1);
    }

    private static Color argb(int argb) {
        Color color = new Color();
        Color.argb8888ToColor(color, argb);
        return color;
    }

    /**
     * One model in the making: nodes of vertex-coloured triangles, one part per material and texture (the white
     * texel, the icon sheet, the font's pages), in drawing order. Base parts write depth; the glow does not.
     */
    private final class Shapes {
        private final ModelBuilder builder = new ModelBuilder();
        private final Map<String, MeshPartBuilder> parts = new HashMap<>();
        private final MeshPartBuilder.VertexInfo vertex = new MeshPartBuilder.VertexInfo();
        private final Texture white = skin.get("white", Texture.class);
        private final BitmapFont font = skin.getFont("default-font");

        Shapes() {
            builder.begin();
        }

        void node(String id) {
            builder.node().id = id;
            parts.clear();
        }

        Model end() {
            return builder.end();
        }

        private MeshPartBuilder part(String id, boolean depth, Texture texture) {
            String key = id + ":" + depth + ":" + texture.getTextureObjectHandle();
            return parts.computeIfAbsent(key, ignored -> {
                Material material = new Material(id + "-" + parts.size(), ColorAttribute.createDiffuse(Color.WHITE),
                      TextureAttribute.createDiffuse(texture),
                      new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
                      new DepthTestAttribute(GL20.GL_ALWAYS, depth), IntAttribute.createCullFace(GL20.GL_NONE));
                return builder.part(key + "-" + parts.size(), GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
                      | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates, material);
            });
        }

        private short point(MeshPartBuilder part, float x, float y, Color color, float u, float v) {
            return part.vertex(vertex.setPos(x, y, 0).setCol(color).setUV(u, v));
        }

        void triangle(String id, boolean depth, float ax, float ay, float bx, float by, float cx, float cy,
              Color color) {
            MeshPartBuilder part = part(id, depth, white);
            part.triangle(point(part, ax, ay, color, .5f, .5f), point(part, bx, by, color, .5f, .5f),
                  point(part, cx, cy, color, .5f, .5f));
        }

        void quad(String id, float x, float y, float width, float height, Color color) {
            MeshPartBuilder part = part(id, false, white);
            short a = point(part, x, y, color, .5f, .5f);
            short b = point(part, x + width, y, color, .5f, .5f);
            short c = point(part, x + width, y + height, color, .5f, .5f);
            short d = point(part, x, y + height, color, .5f, .5f);
            part.triangle(a, b, c);
            part.triangle(a, c, d);
        }

        /** A bar of {@code width} from one point to another. */
        void bar(String id, float ax, float ay, float bx, float by, float width, Color color) {
            float dx = bx - ax;
            float dy = by - ay;
            float length = (float) Math.hypot(dx, dy);
            float nx = -dy / length * width / 2;
            float ny = dx / length * width / 2;
            MeshPartBuilder part = part(id, false, white);
            short a = point(part, ax + nx, ay + ny, color, .5f, .5f);
            short b = point(part, ax - nx, ay - ny, color, .5f, .5f);
            short c = point(part, bx - nx, by - ny, color, .5f, .5f);
            short d = point(part, bx + nx, by + ny, color, .5f, .5f);
            part.triangle(a, b, c);
            part.triangle(a, c, d);
        }

        void ring(String id, boolean depth, float x, float y, float radius, float width, Color color) {
            band(id, depth, x, y, radius - width / 2, radius + width / 2, color, color);
        }

        /** A glow: a band fading out to both sides of {@code radius}. */
        void softRing(String id, boolean depth, float x, float y, float radius, float width, Color color,
              float peak) {
            Color strong = alpha(color, peak);
            Color clear = alpha(color, 0);
            band(id, depth, x, y, radius - width / 2, radius, clear, strong);
            band(id, depth, x, y, radius, radius + width / 2, strong, clear);
        }

        private void band(String id, boolean depth, float x, float y, float inner, float outer, Color in, Color out) {
            MeshPartBuilder part = part(id, depth, white);
            for (int i = 0; i < SEGMENTS; i++) {
                float a = MathUtils.PI2 * i / SEGMENTS;
                float b = MathUtils.PI2 * (i + 1) / SEGMENTS;
                short p = point(part, x + MathUtils.cos(a) * inner, y + MathUtils.sin(a) * inner, in, .5f, .5f);
                short q = point(part, x + MathUtils.cos(a) * outer, y + MathUtils.sin(a) * outer, out, .5f, .5f);
                short r = point(part, x + MathUtils.cos(b) * outer, y + MathUtils.sin(b) * outer, out, .5f, .5f);
                short s = point(part, x + MathUtils.cos(b) * inner, y + MathUtils.sin(b) * inner, in, .5f, .5f);
                part.triangle(p, q, r);
                part.triangle(p, r, s);
            }
        }

        void disc(String id, float x, float y, float radius, Color color) {
            MeshPartBuilder part = part(id, false, white);
            short centre = point(part, x, y, color, .5f, .5f);
            int segments = SEGMENTS / 2;
            for (int i = 0; i < segments; i++) {
                float a = MathUtils.PI2 * i / segments;
                float b = MathUtils.PI2 * (i + 1) / segments;
                part.triangle(centre, point(part, x + MathUtils.cos(a) * radius, y + MathUtils.sin(a) * radius,
                      color, .5f, .5f), point(part, x + MathUtils.cos(b) * radius, y + MathUtils.sin(b) * radius,
                      color, .5f, .5f));
            }
        }

        /** A pill: a rectangle with round ends of half its height. */
        void pill(String id, float x, float y, float width, float height, Color color) {
            float radius = height / 2;
            quad(id, x + radius, y, Math.max(0, width - height), height, color);
            disc(id, x + radius, y + radius, radius, color);
            disc(id, x + width - radius, y + radius, radius, color);
        }

        /** An icon of the HUD's icon sheet, {@code size} apothems square, centred. */
        void icon(String id, String name, float x, float y, float size, Color color) {
            TextureRegion region = ((TextureRegionDrawable) skin.getDrawable("icon-" + name)).getRegion();
            MeshPartBuilder part = part(id, false, region.getTexture());
            float half = size / 2;
            short a = point(part, x - half, y - half, color, region.getU(), region.getV2());
            short b = point(part, x + half, y - half, color, region.getU2(), region.getV2());
            short c = point(part, x + half, y + half, color, region.getU2(), region.getV());
            short d = point(part, x - half, y + half, color, region.getU(), region.getV());
            part.triangle(a, b, c);
            part.triangle(a, c, d);
        }

        /** The width of {@code text} at a cap height of {@code capHeight} apothems. */
        float textWidth(String text, float capHeight) {
            return new GlyphLayout(font, text).width * capHeight / font.getCapHeight();
        }

        /** {@code text} (capitals and digits) from its left end at {@code x}, centred on {@code y}, in the HUD's font. */
        void text(String id, String text, float x, float y, float capHeight, Color color) {
            float scale = capHeight / font.getCapHeight();
            BitmapFontCache cache = new BitmapFontCache(font, false);
            cache.setText(text, 0, 0);
            // Centred on its glyphs' own extent, whatever the font's line metrics.
            float low = Float.POSITIVE_INFINITY;
            float high = Float.NEGATIVE_INFINITY;
            for (int page = 0; page < font.getRegions().size; page++) {
                float[] vertices = cache.getVertices(page);
                for (int at = 1; at < cache.getVertexCount(page); at += 5) {
                    low = Math.min(low, vertices[at]);
                    high = Math.max(high, vertices[at]);
                }
            }
            float middle = (low + high) / 2;
            for (int page = 0; page < font.getRegions().size; page++) {
                float[] vertices = cache.getVertices(page);
                int count = cache.getVertexCount(page);
                if (count == 0) { continue; }
                MeshPartBuilder part = part(id, false, font.getRegions().get(page).getTexture());
                for (int glyph = 0; glyph < count; glyph += 20) {
                    short[] corners = new short[4];
                    for (int corner = 0; corner < 4; corner++) {
                        int at = glyph + corner * 5;
                        corners[corner] = point(part, x + vertices[at] * scale, y + (vertices[at + 1] - middle) * scale,
                              color, vertices[at + 3], vertices[at + 4]);
                    }
                    part.triangle(corners[0], corners[1], corners[2]);
                    part.triangle(corners[2], corners[3], corners[0]);
                }
            }
        }
    }
}
