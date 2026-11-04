/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiTheme.alpha;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The paperdoll (unit panel design 6): the converted regions of one view ({@link GpuPaperdolls}), each filled and
 * outlined by its damage tier, one number per location fitted to its anchor (6.6), the crit dot, the breached outline,
 * a mounted shield's outline and split, and the hover, selection and linked outlines (6.5). It draws only the geometry
 * and the cells it is given, with the skin's white texel, hud font and theme colours; it knows no unit, board or HUD.
 */
final class GpuPaperdoll extends WidgetGroup {
    /** Where a doll is shown: the card and hover card, a sheet armor doll, or the sheet structure doll (6.3). */
    enum View { CARD, ARMOR, STRUCTURE }

    /**
     * A tier of the one damage ramp (unit panel design 6.4), shared by the doll, the card and the sheet: the fill (its
     * alpha is the tier's), the outline colour and width in units, and the number colour. The doll draws the fill
     * pre-blended over the panel ({@code opaque}), as the approved mockup does, so a later region hides earlier lines.
     */
    enum DamageTier {
        INTACT(alpha(UiTheme.ACCENT, .08f), UiTheme.RAIL, 1, UiTheme.ACCENT),
        LIGHT(alpha(UiTheme.TEXT, .2f), alpha(UiTheme.TEXT, .55f), 1, UiTheme.TEXT),
        DAMAGED(alpha(UiTheme.AMBER, .3f), UiTheme.AMBER, 1, UiTheme.AMBER),
        CRITICAL(alpha(UiTheme.CORAL, .38f), UiTheme.CORAL, 1, UiTheme.CORAL),
        /** Armor gone, structure left: the approved "(structure)" cue. */
        EXPOSED(alpha(UiTheme.CORAL, .8f), UiTheme.CORAL, 1.5f, UiTheme.FILL_INK),
        DESTROYED(Color.valueOf("3A1A18"), alpha(UiTheme.CORAL, .6f), 1, UiTheme.CORAL),
        /** A value the unit does not have (original 0). */
        NONE(Color.CLEAR, UiTheme.RAIL, 1, UiTheme.MUTED);

        final Color fill;
        final Color outline;
        final float width;
        final Color number;
        final Color opaque;

        DamageTier(Color fill, Color outline, float width, Color number) {
            this.fill = fill;
            this.outline = outline;
            this.width = width;
            this.number = number;
            opaque = over(fill);
        }

        /** A fill over the panel colour: opaque where it is drawn at all. */
        static Color over(Color fill) {
            Color panel = UiTheme.PANEL;
            float a = fill.a;
            return a <= 0 ? Color.CLEAR : new Color(fill.r * a + panel.r * (1 - a), fill.g * a + panel.g * (1 - a),
                  fill.b * a + panel.b * (1 - a), 1);
        }
    }

    /**
     * What one region shows: its tier, its text (a value, the exposed "(structure)", a cross, a dash or nothing), a
     * critical hit dot, a breached outline and a playback change drawn as "-n" above it (0 for none).
     */
    record Cell(DamageTier tier, String text, boolean crit, boolean breached, int delta) {
        Cell with(boolean nextBreached, int nextDelta) {
            return new Cell(tier, text, crit, nextBreached, nextDelta);
        }
    }

    static final String CROSS = "\u2715";
    private static final String FONT = "hud-heading";
    /** The number fit (6.6): f = min(maxF, 1.6 rho, 2.3 rho / w), drawn only when f >= 8. */
    private static final float FIT_HEIGHT = 1.6f;
    private static final float FIT_WIDTH = 2.3f;
    private static final float MIN_FONT = 8;
    /** A shield's two numbers may be smaller: the card shows both (user correction 17, "smaller text if needed"). */
    private static final float MIN_SHIELD_FONT = 6;
    private static final float DELTA_SIZE = .85f;
    /** Outline widths in units; the feather is the anti-aliased edge on each side of a line. */
    private static final float MARK_WIDTH = 2;
    private static final float BREACHED_WIDTH = 1.5f;
    private static final float SPLIT_WIDTH = .6f;
    private static final float FEATHER = .75f;
    private static final float MITER_LIMIT = 4;
    /** A destroyed unit's regions, except its destroyed ones (6.5). */
    private static final float DIM = .45f;
    private static final Color SELECTED = Color.WHITE;
    /** A breached location's outline: the approved cue's blue (U-1), a doll colour of its own. */
    private static final Color BREACHED = Color.valueOf("9FB2F0");
    /** The hull that belongs to no location, and a region without a cell (such as a ProtoMek's absent main gun). */
    private static final Color NEUTRAL = DamageTier.over(alpha(UiTheme.ACCENT, .05f));
    /** A critical region's fill on a {@link #vivid} doll: a red that stands apart from the damaged amber. */
    private static final Color VIVID_CRITICAL = DamageTier.over(alpha(UiTheme.CORAL, .62f));

    private final Texture white;
    private final BitmapFont font;
    private final float fontSize;
    private final List<Figure> figures = new ArrayList<>();
    private GpuPaperdolls.Doll doll;
    private Map<String, Cell> armor = Map.of();
    private Map<String, Cell> structure = Map.of();
    private float maxFont = 14;
    private boolean dimmed;
    /** Whether a critical region takes the stronger red, as the lobby's damage editor asks; the board's never do. */
    private boolean vivid;
    private String hover = "";
    private String selected = "";
    private String linked = "";
    // The layout: doll units to local units, and the geometry in local units.
    private float scale;
    private float left;
    private float top;
    private final List<float[]> fills = new ArrayList<>();
    private final List<List<Ring>> outlines = new ArrayList<>();
    private final List<List<Ring>> shieldOutlines = new ArrayList<>();
    private final List<Ring> splits = new ArrayList<>();
    private float[] hullFill;
    private List<Ring> hullOutline = List.of();
    private float[] scratch = new float[0];

    /** A region's labels: its number, the crit dot after it and the playback change above it; each may be null. */
    private record Figure(GpuPaperdolls.Region region, Label text, Label dot, Label delta) { }

    GpuPaperdoll(Skin skin) {
        white = skin.get("white", Texture.class);
        font = skin.getFont(FONT);
        fontSize = UiTheme.HUD_FONTS.stream().filter(hud -> hud.name().equals(FONT)).findFirst().orElseThrow()
              .size();
        setTransform(false);
    }

    /**
     * The tier of a value against its original, for the doll, the card, the sheet, the hover card and the shields,
     * with the card silhouette's thresholds (.70 / .40): destroyed, none without an original, exposed at 0, else by
     * ratio.
     */
    static DamageTier damageFill(int current, int original, boolean destroyed) {
        if (destroyed) {
            return DamageTier.DESTROYED;
        } else if (original <= 0) {
            return DamageTier.NONE;
        } else if (current <= 0) {
            return DamageTier.EXPOSED;
        }
        float ratio = current / (float) original;
        return ratio >= 1 ? DamageTier.INTACT : ratio > .7f ? DamageTier.LIGHT : ratio > .4f ? DamageTier.DAMAGED
              : DamageTier.CRITICAL;
    }

    /**
     * The cell of an armor, rear armor or structure value (6.3, 6.4): its tier by {@link #damageFill}, a layer at 0
     * over remaining structure being exposed and without structure destroyed. The card prints "(structure)" on an
     * exposed region and a cross on a destroyed one; the sheet's armor dolls print neither, as the structure doll
     * beside them shows that number or a cross (R3-3), but keep the crit dot.
     */
    static Cell cell(int current, int original, boolean destroyed, int structure, boolean crit, View view) {
        DamageTier tier = damageFill(current, original, destroyed);
        if (tier == DamageTier.EXPOSED && structure <= 0) {
            tier = DamageTier.DESTROYED;
        }
        String text = switch (tier) {
            case DESTROYED -> CROSS;
            case NONE -> "\u2014";
            case EXPOSED -> "(" + structure + ")";
            default -> String.valueOf(current);
        };
        if (view == View.ARMOR && (tier == DamageTier.EXPOSED || tier == DamageTier.DESTROYED)) {
            text = "";
        }
        return new Cell(tier, text, crit && tier != DamageTier.DESTROYED, false, 0);
    }

    /**
     * The cells of a mounted shield on {@code arm} (6.3): the capacity panel "DC{arm}" and the absorption strip
     * "DA{arm}". A shield that is not active or has no capacity left is one destroyed object: one cross in the panel
     * and the destroyed fill in the strip; an absorption of 0 alone gives the strip the destroyed fill. Never exposed.
     */
    static Map<String, Cell> shieldCells(String arm, boolean active, int capacity, int baseCapacity, int absorption,
          int baseAbsorption) {
        Cell spent = new Cell(DamageTier.DESTROYED, "", false, false, 0);
        if (!active || capacity <= 0) {
            return Map.of("DC" + arm, new Cell(DamageTier.DESTROYED, CROSS, false, false, 0), "DA" + arm, spent);
        }
        return Map.of("DC" + arm, cell(capacity, baseCapacity, false, 1, false, View.CARD), "DA" + arm,
              absorption <= 0 ? spent : cell(absorption, baseAbsorption, false, 1, false, View.CARD));
    }

    /**
     * Shows a view with the cells of its regions: {@code armor} by code for the armor, rear armor and shield regions,
     * {@code structure} by code for the structure regions and value boxes. A region without a cell is drawn neutral.
     */
    void show(GpuPaperdolls.Doll next, Map<String, Cell> nextArmor, Map<String, Cell> nextStructure) {
        if (next == doll && nextArmor.equals(armor) && nextStructure.equals(structure)) {
            return;
        }
        doll = next;
        armor = Map.copyOf(nextArmor);
        structure = Map.copyOf(nextStructure);
        clearChildren();
        figures.clear();
        if (doll != null) {
            for (GpuPaperdolls.Region region : doll.regions()) {
                Cell cell = cell(region);
                if (cell == null || cell.text().isEmpty() && !cell.crit() && cell.delta() == 0) {
                    continue;
                }
                boolean lit = vivid && cell.tier() == DamageTier.CRITICAL;
                Label text = cell.text().isEmpty() ? null : label(cell.text(), lit ? UiTheme.TEXT
                      : cell.tier().number);
                Label dot = cell.crit() ? label("\u2022", cell.tier() == DamageTier.EXPOSED ? UiTheme.FILL_INK
                      : UiTheme.AMBER) : null;
                Label delta = cell.delta() > 0 ? label("\u2212" + cell.delta(), lit ? UiTheme.TEXT
                      : vivid && cell.tier() == DamageTier.EXPOSED ? UiTheme.FILL_INK : UiTheme.CORAL) : null;
                Figure figure = new Figure(region, text, dot, delta);
                figures.add(figure);
                for (Label label : new Label[] { figure.text(), figure.dot(), figure.delta() }) {
                    if (label != null) {
                        addActor(label);
                    }
                }
            }
        }
        applyDim();
        invalidateHierarchy();
    }

    /** The largest number size in units (6.6: 14 on the card, 10 at 1280, 16 on the sheet). */
    void maxFont(float size) {
        if (size != maxFont) {
            maxFont = size;
            invalidate();
        }
    }

    /**
     * Gives a critical region a stronger red with its numbers in text.primary, so it stands apart from a damaged one
     * at a glance, and an exposed region's change in the ink its number takes; set before {@link #show}. The lobby's
     * damage editor asks for it; the board's dolls keep the ramp.
     */
    void vivid(boolean on) {
        vivid = on;
    }

    /** A destroyed unit: every region at .45 alpha except its destroyed ones. */
    void dimmed(boolean destroyedUnit) {
        dimmed = destroyedUnit;
        applyDim();
    }

    /** The outlined locations (codes; a shield is "DC" + its arm, "" for none): hovered, selected, linked (6.5). */
    void marks(String hovered, String chosen, String linkedRow) {
        hover = hovered;
        selected = chosen;
        linked = linkedRow;
    }

    /** The location outlined as the linked row's (6.5), "" for none. */
    String linked() {
        return linked;
    }

    /** The location at a local point: a region's code, "DC" + arm for either part of a shield, or null. */
    String pick(float x, float y) {
        validate();
        if (doll == null || scale <= 0) {
            return null;
        }
        return doll.pick(doll.bounds()[0] + (x - left) / scale, doll.bounds()[1] + (top - y) / scale);
    }

    @Override
    public float getPrefWidth() {
        return doll == null ? 0 : doll.bounds()[2];
    }

    @Override
    public float getPrefHeight() {
        return doll == null ? 0 : doll.bounds()[3];
    }

    private Cell cell(GpuPaperdolls.Region region) {
        return (region.layer().equals(GpuPaperdolls.STRUCTURE_LAYER) ? structure : armor).get(region.code());
    }

    private Label label(String text, Color color) {
        return new Label(text, new Label.LabelStyle(font, color));
    }

    private void applyDim() {
        for (Figure figure : figures) {
            Cell cell = cell(figure.region());
            float alpha = dimmed && cell.tier() != DamageTier.DESTROYED ? DIM : 1;
            for (Label label : new Label[] { figure.text(), figure.dot(), figure.delta() }) {
                if (label != null) {
                    label.getColor().a = alpha;
                }
            }
        }
    }

    @Override
    public void layout() {
        fills.clear();
        outlines.clear();
        shieldOutlines.clear();
        splits.clear();
        hullFill = null;
        hullOutline = List.of();
        if (doll == null) {
            return;
        }
        float[] bounds = doll.bounds();
        float inset = MARK_WIDTH / 2 + FEATHER;
        scale = Math.max(0, Math.min((getWidth() - 2 * inset) / bounds[2], (getHeight() - 2 * inset) / bounds[3]));
        left = (getWidth() - bounds[2] * scale) / 2;
        top = (getHeight() + bounds[3] * scale) / 2;
        for (GpuPaperdolls.Region region : doll.regions()) {
            fills.add(quads(region));
            outlines.add(rings(region.rings()));
        }
        if (doll.hull() != null) {
            hullFill = quads(doll.hull());
            hullOutline = rings(doll.hull().rings());
        }
        for (GpuPaperdolls.Shield shield : doll.shields()) {
            shieldOutlines.add(rings(shield.outline()));
            splits.add(new Ring(local(shield.split()), false));
        }
        for (Figure figure : figures) {
            place(figure, figure.region().anchor());
        }
        // Where a shield's strip is too thin for its number (the card), both numbers share the capacity panel, one in
        // each half (user correction 17: the card shows capacity and absorption).
        for (GpuPaperdolls.Shield shield : doll.shields()) {
            Figure capacity = figure("DC" + shield.arm());
            Figure absorption = figure("DA" + shield.arm());
            if (absorption != null && !shown(absorption)) {
                place(absorption, shield.absorptionAnchor());
                if (capacity != null) {
                    place(capacity, shield.capacityAnchor());
                }
            }
        }
    }

    private Figure figure(String shieldPart) {
        return figures.stream().filter(figure -> figure.region().layer().equals(GpuPaperdolls.SHIELD_LAYER)
              && figure.region().code().equals(shieldPart)).findFirst().orElse(null);
    }

    private static boolean shown(Figure figure) {
        Label label = figure.text() != null ? figure.text() : figure.dot();
        return label == null || label.isVisible();
    }

    /** Fits a region's labels to an anchor (6.6) and centres them there; they hide below the smallest size. */
    private void place(Figure figure, float[] anchor) {
        float rho = anchor[2] * scale - .5f;
        Label[] row = { figure.text(), figure.dot() };
        // The text's width in em, measured at the font's own size.
        float em = 0;
        for (Label label : row) {
            if (label != null) {
                size(label, fontSize);
                em += label.getPrefWidth() / fontSize;
            }
        }
        float size = Math.min(maxFont, FIT_HEIGHT * rho);
        if (em > 0) {
            size = Math.min(size, FIT_WIDTH * rho / em);
        }
        boolean shown = size >= (figure.region().layer().equals(GpuPaperdolls.SHIELD_LAYER) ? MIN_SHIELD_FONT
              : MIN_FONT);
        for (Label label : new Label[] { figure.text(), figure.dot(), figure.delta() }) {
            if (label != null) {
                label.setVisible(shown);
            }
        }
        if (!shown) {
            // The fill and outline carry the state; the hover card holds the value.
            return;
        }
        float width = 0;
        for (Label label : row) {
            if (label != null) {
                size(label, size);
                width += label.getPrefWidth();
            }
        }
        float x = left + (anchor[0] - doll.bounds()[0]) * scale - width / 2;
        float y = top - (anchor[1] - doll.bounds()[1]) * scale;
        for (Label label : row) {
            if (label != null) {
                label.setBounds(x, y - label.getPrefHeight() / 2, label.getPrefWidth(), label.getPrefHeight());
                x += label.getPrefWidth();
            }
        }
        if (figure.delta() != null) {
            Label delta = figure.delta();
            size(delta, Math.max(DELTA_SIZE * size, MIN_FONT));
            delta.setBounds(left + (anchor[0] - doll.bounds()[0]) * scale - delta.getPrefWidth() / 2,
                  y + size * .55f, delta.getPrefWidth(), delta.getPrefHeight());
        }
    }

    /** The doll's font at {@code size} units, the label sized to its text. */
    private static void size(Label label, float size) {
        UiKit.size(label, FONT, size);
        label.pack();
    }

    private float[] quads(GpuPaperdolls.Region region) {
        short[] triangles = region.triangles();
        float[] vertices = region.vertices();
        float[] quads = new float[triangles.length / 3 * 8];
        int at = 0;
        for (int index = 0; index < triangles.length; index += 3) {
            // Each triangle is a quad whose last corner repeats its third, for the sprite batch.
            for (int corner = 0; corner < 4; corner++) {
                int vertex = 2 * triangles[index + Math.min(corner, 2)];
                quads[at++] = left + (vertices[vertex] - doll.bounds()[0]) * scale;
                quads[at++] = top - (vertices[vertex + 1] - doll.bounds()[1]) * scale;
            }
        }
        return quads;
    }

    private List<Ring> rings(List<float[]> rings) {
        List<Ring> local = new ArrayList<>();
        rings.forEach(ring -> local.add(new Ring(local(ring), true)));
        return local;
    }

    private float[] local(float[] points) {
        float[] local = new float[points.length];
        for (int index = 0; index < points.length; index += 2) {
            local[index] = left + (points[index] - doll.bounds()[0]) * scale;
            local[index + 1] = top - (points[index + 1] - doll.bounds()[1]) * scale;
        }
        return local;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        if (doll == null || scale <= 0) {
            return;
        }
        float alpha = getColor().a * parentAlpha;
        float rest = alpha * (dimmed ? DIM : 1);
        float previous = batch.getPackedColor();
        if (hullFill != null) {
            fill(batch, hullFill, NEUTRAL, rest);
            hullOutline.forEach(ring -> line(batch, ring, 1, UiTheme.RAIL, rest));
        }
        List<GpuPaperdolls.Region> regions = doll.regions();
        int shield = 0;
        for (int index = 0; index < regions.size(); index++) {
            GpuPaperdolls.Region region = regions.get(index);
            // A shield's outline follows its panel and strip, so that the arm redrawn over it covers it.
            while (shield < doll.shields().size() && doll.shields().get(shield).z() < region.z()) {
                shield(batch, shield++, rest);
            }
            Cell cell = cell(region);
            float regionAlpha = cell != null && cell.tier() == DamageTier.DESTROYED ? alpha : rest;
            Color fill = cell == null ? NEUTRAL : vivid && cell.tier() == DamageTier.CRITICAL ? VIVID_CRITICAL
                  : cell.tier().opaque;
            if (fill.a > 0) {
                fill(batch, fills.get(index), fill, regionAlpha);
            }
            if (!region.layer().equals(GpuPaperdolls.SHIELD_LAYER)) {
                outline(batch, outlines.get(index), region.location(), cell, regionAlpha);
            }
        }
        while (shield < doll.shields().size()) {
            shield(batch, shield++, rest);
        }
        batch.setPackedColor(previous);
        super.draw(batch, parentAlpha);
    }

    /** A region's outline: a mark (selected, hovered, linked row), else breached blue, else its tier's (6.5). */
    private void outline(Batch batch, List<Ring> rings, String location, Cell cell, float alpha) {
        Color color = mark(location);
        float width = MARK_WIDTH;
        if (color == null && cell != null && cell.breached()) {
            color = BREACHED;
            width = BREACHED_WIDTH;
        } else if (color == null) {
            DamageTier tier = cell == null ? DamageTier.NONE : cell.tier();
            color = tier.outline;
            width = tier.width;
        }
        for (Ring ring : rings) {
            line(batch, ring, width, color, alpha);
        }
    }

    /** A mounted shield as one object: its outline in ink2 (or its mark) and the rail hairline at its split. */
    private void shield(Batch batch, int index, float alpha) {
        Color mark = mark("DC" + doll.shields().get(index).arm());
        for (Ring ring : shieldOutlines.get(index)) {
            line(batch, ring, mark == null ? 1 : MARK_WIDTH, mark == null ? UiTheme.ACCENT : mark, alpha);
        }
        line(batch, splits.get(index), SPLIT_WIDTH, UiTheme.RAIL, alpha);
    }

    private Color mark(String location) {
        return location.equals(selected) ? SELECTED : location.equals(hover) ? UiTheme.HOVER
              : location.equals(linked) ? UiTheme.MINT : null;
    }

    private void fill(Batch batch, float[] quads, Color color, float alpha) {
        float packed = Color.toFloatBits(color.r, color.g, color.b, color.a * alpha);
        int count = quads.length / 2;
        float[] vertices = vertices(count);
        for (int vertex = 0; vertex < count; vertex++) {
            put(vertices, vertex, quads[2 * vertex], quads[2 * vertex + 1], packed);
        }
        batch.draw(white, vertices, 0, count * 5);
    }

    private void line(Batch batch, Ring ring, float width, Color color, float alpha) {
        ring.build(width);
        float solid = Color.toFloatBits(color.r, color.g, color.b, color.a * alpha);
        float clear = Color.toFloatBits(color.r, color.g, color.b, 0);
        int count = ring.solid.length;
        float[] vertices = vertices(count);
        for (int vertex = 0; vertex < count; vertex++) {
            put(vertices, vertex, ring.ribbon[2 * vertex], ring.ribbon[2 * vertex + 1],
                  ring.solid[vertex] ? solid : clear);
        }
        batch.draw(white, vertices, 0, count * 5);
    }

    private float[] vertices(int count) {
        if (scratch.length < count * 5) {
            scratch = new float[count * 5];
        }
        return scratch;
    }

    private void put(float[] vertices, int vertex, float x, float y, float color) {
        vertices[5 * vertex] = getX() + x;
        vertices[5 * vertex + 1] = getY() + y;
        vertices[5 * vertex + 2] = color;
        vertices[5 * vertex + 3] = .5f;
        vertices[5 * vertex + 4] = .5f;
    }

    /**
     * A line in local units, closed or open, and its ribbon for the last width drawn: quads centred on the line, a core
     * of that width with a feathered edge on each side (the window has no multisampling), mitred corners limited to
     * {@code MITER_LIMIT} half widths.
     */
    private static final class Ring {
        private final float[] points;
        private final boolean closed;
        private float width = -1;
        private float[] ribbon;
        private boolean[] solid;

        Ring(float[] points, boolean closed) {
            this.points = points;
            this.closed = closed;
        }

        void build(float nextWidth) {
            if (nextWidth == width) {
                return;
            }
            width = nextWidth;
            int count = points.length / 2;
            int edges = closed ? count : count - 1;
            float[] miterX = new float[count];
            float[] miterY = new float[count];
            for (int index = 0; index < count; index++) {
                float[] before = normal(closed || index > 0 ? (index + count - 1) % count : index, index);
                float[] after = normal(index, closed || index < count - 1 ? (index + 1) % count : index);
                if (before == null) {
                    before = after;
                }
                if (after == null) {
                    after = before;
                }
                if (before == null) {
                    continue;
                }
                float sumX = before[0] + after[0];
                float sumY = before[1] + after[1];
                float length = (float) Math.hypot(sumX, sumY);
                if (length < 1e-4f) {
                    miterX[index] = after[0];
                    miterY[index] = after[1];
                } else {
                    float factor = Math.min(MITER_LIMIT, 1 / Math.max(1e-4f,
                          (sumX * after[0] + sumY * after[1]) / length));
                    miterX[index] = sumX / length * factor;
                    miterY[index] = sumY / length * factor;
                }
            }
            float half = width / 2;
            float[] offsets = { -half - FEATHER, -half, half, half + FEATHER };
            boolean[] solidRow = { false, true, true, false };
            ribbon = new float[edges * 3 * 4 * 2];
            solid = new boolean[edges * 3 * 4];
            int at = 0;
            for (int edge = 0; edge < edges; edge++) {
                int from = edge;
                int to = (edge + 1) % count;
                for (int band = 0; band < 3; band++) {
                    int[] corners = { from, from, to, to };
                    int[] rows = { band, band + 1, band + 1, band };
                    for (int corner = 0; corner < 4; corner++) {
                        int vertex = corners[corner];
                        float offset = offsets[rows[corner]];
                        ribbon[2 * at] = points[2 * vertex] + miterX[vertex] * offset;
                        ribbon[2 * at + 1] = points[2 * vertex + 1] + miterY[vertex] * offset;
                        solid[at++] = solidRow[rows[corner]];
                    }
                }
            }
        }

        /** The unit normal of the edge from one point to another, null for a point or an empty edge. */
        private float[] normal(int from, int to) {
            float dx = points[2 * to] - points[2 * from];
            float dy = points[2 * to + 1] - points[2 * from + 1];
            float length = (float) Math.hypot(dx, dy);
            return length < 1e-6f ? null : new float[] { -dy / length, dx / length };
        }
    }
}
