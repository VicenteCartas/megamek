/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/**
 * The board's E(C)CM, one pass for both views (b11 design section 5). The 3D view shows faint ticks at the middle of
 * each field's edges; hovering or selecting an emitter opens its field: its outline, the hatch where it jams the
 * player's units, and a glow wave from the unit to the field's border that lights only the hexes no other field covers.
 * The Tactical View draws every outline (solid ECM, dashed ECCM) and the hatch on every jammed hex, as its Overlays
 * panel's Fields and Jamming settings choose; with Fields off it shows the 3D view's ticks and opens a field as the 3D
 * view does. Lines are mint for own and allied fields, coral for the enemy's.
 * <p>
 * It drapes over the terrain carriers of {@link GpuHexSurface}, one draw per covered chunk, and reads one texel per hex,
 * uploaded only when the snapshot or the open field changes; each frame sets uniforms only. The bits come from
 * {@link BoardElectronicWarfare}: it decides no rule. GL thread only; owned by GpuTactical.
 */
final class GpuElectronicWarfare implements Disposable {
    /** Whether the open field pulses; the Tuning panel switches it while the board runs. */
    static final boolean PULSE = true;
    /** The pulse's brightness; 0 shows none. */
    static final float PULSE_INTENSITY = 1;
    /** Seconds per cycle: the wave crosses the field in 60 % of it, flashes at the border for 17 %, then rests. */
    static final float PULSE_PERIOD = 2.4f;
    /** A selected emitter pulses this many cycles, then rests until another unit is selected; a hover repeats. */
    static final int SELECTION_CYCLES = 2;
    /** The pulse glows in the side colour with this much more chroma: the pastel side colours vanish on pavement. */
    private static final float PULSE_CHROMA = 1.6f;

    // The values in use: render-thread state that GpuBoardTuning's controls edit and its Defaults restores.
    static boolean pulse = PULSE;
    static float pulseIntensity = PULSE_INTENSITY;
    static float pulsePeriod = PULSE_PERIOD;

    /** The field shown in full, and whether the pointer opened it (it pulses on) or the selection did. */
    record Open(BoardElectronicWarfare.Source source, boolean hovered) { }

    private final GpuHexSurface.Cache carriers = new GpuHexSurface.Cache();
    private final Material material = new Material(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA));
    private BoardScene scene;
    private BoardElectronicWarfare shown = BoardElectronicWarfare.EMPTY;
    private Open open;
    private float border;
    private Texture cells;
    private Set<Coords> chunks = Set.of();
    private int tuning = -1;
    private int selected = Entity.NONE;
    private float hoverClock;
    private float selectionClock;
    private boolean tacticalView;
    private boolean fieldsAll;
    private boolean hatchAll;
    private boolean fillAll;
    private FieldShader shader;

    /**
     * Follows the scene's snapshot and the pointer. {@code finished} is the terrain's surface function when the terrain
     * changed (or on the first update), else null; {@code hover} and {@code hoveredUnit} are the pointed hex and unit.
     */
    void update(BoardScene next, @Nullable Function<Coords, BoardTacticalGeometry.Surface> finished,
          @Nullable Coords hover, int hoveredUnit) {
        BoardElectronicWarfare warfare = next.electronicWarfare();
        if (Gdx.gl30 == null || warfare.width() != next.width() || warfare.height() != next.height()) {
            warfare = BoardElectronicWarfare.EMPTY;
        }
        if (next.selectedId() != selected) {
            selected = next.selectedId();
            selectionClock = 0;
        }
        Open opened = open(warfare, hoveredUnit, hover, selected);
        if (opened != null && opened.hovered() && (open == null || !open.hovered()
              || !open.source().equals(opened.source()))) {
            hoverClock = 0;
        }
        boolean reset = scene == null || scene.boardId() != next.boardId() || scene.width() != next.width()
              || scene.height() != next.height() || tuning != BoardGeometry.revision();
        if (reset) { carriers.clear(); }
        if (reset || finished != null) {
            carriers.update(next, finished != null ? finished : BoardTacticalGeometry.surfaces(next), false);
        }
        scene = next;
        tuning = BoardGeometry.revision();
        if (warfare == shown && Objects.equals(opened, open) && !reset) { return; }
        shown = warfare;
        open = opened;
        border = open == null ? 0 : open.source().hexes().stream()
              .mapToInt(coords -> coords.distance(open.source().position())).max().orElse(0) + .5f;
        upload();
    }

    /**
     * The open field: the hovered unit's, else the one emitted from the hovered hex, else the selected unit's. Fields
     * without a unit (chaff, mines) and fields TacOps ECCM negated never open.
     */
    static @Nullable Open open(BoardElectronicWarfare warfare, int hoveredUnit, @Nullable Coords hover, int selectedUnit) {
        BoardElectronicWarfare.Source onHex = null, chosen = null;
        for (BoardElectronicWarfare.Source source : warfare.sources()) {
            if (source.entityId() == Entity.NONE || source.suppressed()) { continue; }
            if (source.entityId() == hoveredUnit) { return new Open(source, true); }
            if (onHex == null && source.position().equals(hover)) { onHex = source; }
            if (chosen == null && source.entityId() == selectedUnit) { chosen = source; }
        }
        return onHex != null ? new Open(onHex, true) : chosen == null ? null : new Open(chosen, false);
    }

    /**
     * One RGBA texel per hex, in column order: the coverage bits, the number of sources (the pulse lights only 1),
     * and whether the hex lies in the open field.
     */
    static int[] texels(BoardElectronicWarfare warfare, @Nullable BoardElectronicWarfare.Source open) {
        int[] texels = new int[warfare.hexes().size()];
        for (int x = 0; x < warfare.width(); x++) {
            for (int y = 0; y < warfare.height(); y++) {
                int hex = warfare.hexes().get(x * warfare.height() + y);
                boolean inside = open != null && open.hexes().contains(new Coords(x, y));
                texels[x * warfare.height() + y] = (hex & 63) << 24
                      | Math.min(255, BoardElectronicWarfare.count(hex)) << 16 | (inside ? 255 : 0) << 8 | 255;
            }
        }
        return texels;
    }

    /** Seconds into the pulse's cycle, or -1 while it rests: a hover repeats it, a selection runs SELECTION_CYCLES. */
    static float phase(boolean hovered, float clock, float period) {
        return !hovered && clock >= SELECTION_CYCLES * period ? -1 : clock % period;
    }

    /** The open field's pulse time now, or -1 when nothing pulses (for reviews and tests). */
    float pulseTime() {
        if (open == null || !pulse || pulseIntensity <= 0) { return -1; }
        return phase(open.hovered(), open.hovered() ? hoverClock : selectionClock, pulsePeriod);
    }

    @Nullable Open opened() {
        return open;
    }

    private void upload() {
        if (cells != null) { cells.dispose(); }
        cells = null;
        chunks = Set.of();
        if (shown.hexes().isEmpty()) {
            carriers.retain(chunks);
            return;
        }
        int[] texels = texels(shown, open == null ? null : open.source());
        Pixmap pixels = new Pixmap(shown.width(), shown.height(), Pixmap.Format.RGBA8888);
        Set<Coords> covered = new LinkedHashSet<>();
        try {
            pixels.setBlending(Pixmap.Blending.None);
            for (int x = 0; x < shown.width(); x++) {
                for (int y = 0; y < shown.height(); y++) {
                    int texel = texels[x * shown.height() + y];
                    pixels.drawPixel(x, y, texel);
                    if (texel >>> 24 != 0) {
                        covered.add(new Coords(x / GpuTerrain.CHUNK_SIZE, y / GpuTerrain.CHUNK_SIZE));
                    }
                }
            }
            carriers.retain(covered);
            if (covered.isEmpty()) { return; }
            cells = new Texture(pixels);
            cells.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            chunks = covered;
        } finally {
            pixels.dispose();
        }
    }

    /**
     * Advances the pulse clocks and submits the covered chunks in view. {@code fields} outlines every field, in either
     * view; in the Tactical View ({@code tactical}) {@code hatch} hatches every jammed hex, not only the open field's.
     * {@code fill} (deployment's ECM overlay) also tints every covered hex in its side's colour and hatches every
     * jammed hex in either view, so the fields read at a glance.
     */
    void submit(ModelBatch batch, Camera camera, float deltaSeconds, boolean tactical, boolean fields, boolean hatch,
          boolean fill) {
        float step = Math.min(Math.max(deltaSeconds, 0), .1f);
        hoverClock += step;
        selectionClock += step;
        fillAll = fill;
        if (cells == null) { return; }
        prepare();
        tacticalView = tactical;
        fieldsAll = fields;
        hatchAll = hatch;
        carriers.submit(batch, camera, shader, material, this, chunks);
    }

    boolean active() {
        return cells != null;
    }

    /** The kinds the open field draws in full: its side's ECM and ECCM bits. */
    private int openKinds() {
        if (open == null) { return 0; }
        var source = open.source();
        int ecm = source.own() ? BoardElectronicWarfare.OWN_ECM : BoardElectronicWarfare.ENEMY_ECM;
        int eccm = source.own() ? BoardElectronicWarfare.OWN_ECCM : BoardElectronicWarfare.ENEMY_ECCM;
        return (source.ecm() ? ecm : 0) | (source.eccm() ? eccm : 0);
    }

    /** The colour with its chroma scaled about its luminance, clamped. */
    static Color chroma(Color color, float factor) {
        float luma = .2126f * color.r + .7152f * color.g + .0722f * color.b;
        return new Color(Math.min(1, Math.max(0, luma + (color.r - luma) * factor)),
              Math.min(1, Math.max(0, luma + (color.g - luma) * factor)),
              Math.min(1, Math.max(0, luma + (color.b - luma) * factor)), 1);
    }

    /** Uses the same transparent sorting and GL-state owner as the hex masks and tactical pages. */
    private final class FieldShader implements Shader {
        private final Color ownPulse = chroma(UiTheme.MINT, PULSE_CHROMA);
        private final Color enemyPulse = chroma(UiTheme.CORAL, PULSE_CHROMA);
        private ShaderProgram program;

        FieldShader() {
            program = GpuShaderManager.program(() -> GpuGlsl.compile("GPU electronic warfare",
                  GpuShaderSource.read("ecm-field.vert"), GpuShaderSource.read("ecm-field.frag")), next -> program = next);
        }

        @Override
        public void init() { }

        @Override
        public void begin(Camera camera, RenderContext context) {
            program.bind();
            program.setUniformMatrix("u_projTrans", camera.combined);
            program.setUniformf("u_board", scene.width(), scene.height(), BoardGeometry.TILE_WIDTH, BoardGeometry.TILE_HEIGHT);
            program.setUniformf("u_hexScale", BoardGeometry.hexScale());
            program.setUniformi("u_cells", context.textureBinder.bind(cells));
            program.setUniformi("u_tactical", tacticalView ? 1 : 0);
            program.setUniformi("u_fieldsAll", fieldsAll ? 1 : 0);
            program.setUniformi("u_hatchAll", hatchAll ? 1 : 0);
            program.setUniformi("u_fill", fillAll ? 1 : 0);
            program.setUniformi("u_openKinds", openKinds());
            program.setUniformf("u_strength", shown.strength() / 128f);
            Coords centre = open == null ? new Coords(0, 0) : open.source().position();
            program.setUniformf("u_pulseCentre", (centre.getX() * .75f + .5f) * BoardGeometry.TILE_WIDTH,
                  (centre.getY() + (centre.getX() & 1) * .5f + .5f) * BoardGeometry.TILE_HEIGHT);
            program.setUniformf("u_pulseBorder", border);
            program.setUniformf("u_pulseTime", pulseTime());
            program.setUniformf("u_pulsePeriod", pulsePeriod);
            Color glow = open != null && open.source().own() ? ownPulse : enemyPulse;
            program.setUniformf("u_pulseColour", glow.r, glow.g, glow.b, pulseIntensity);
            program.setUniformf("u_own", UiTheme.MINT.r, UiTheme.MINT.g, UiTheme.MINT.b);
            program.setUniformf("u_enemy", UiTheme.CORAL.r, UiTheme.CORAL.g, UiTheme.CORAL.b);
            context.setDepthTest(GL20.GL_LEQUAL, 0, GpuTactical.COPLANAR_DEPTH_FAR);
            context.setDepthMask(false);
            context.setCullFace(GL20.GL_NONE);
            context.setBlending(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        }

        @Override
        public void render(Renderable renderable) {
            renderable.meshPart.render(program);
        }

        @Override
        public void end() { }

        @Override
        public boolean canRender(Renderable renderable) { return renderable.shader == this; }

        @Override
        public int compareTo(Shader other) { return 0; }

        @Override
        public void dispose() { GpuShaderManager.dispose(program); }
    }

    void prepare() {
        if (shader == null) { shader = new FieldShader(); }
    }

    @Override
    public void dispose() {
        if (cells != null) { cells.dispose(); }
        cells = null;
        chunks = Set.of();
        carriers.dispose();
        if (shader != null) { shader.dispose(); }
        shader = null;
        scene = null;
        shown = BoardElectronicWarfare.EMPTY;
        open = null;
    }
}
