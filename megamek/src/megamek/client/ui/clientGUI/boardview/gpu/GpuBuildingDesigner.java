/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Doors;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.BoardDoors;
import megamek.common.board.Coords;

/**
 * The selected building's designer, on the building itself: while the editor's Select tool has a building hex, what
 * the building stores per wall is edited where it stands. Each kind of option gives handles, controls that keep to a
 * point of the building in either camera view and send the editor's own commands. Today its large doors
 * ({@link Doors}): every wall of every hex of the building has a handle at its foot, to add a door or, at a door, to
 * raise, lower or remove it; where two doors meet at a corner a handle links them into one opening or parts them; and
 * each door is outlined where it is drawn ({@link KitDoors#cuts}), a wide door as one. Handles of walls that face
 * away, and all of them when the building is small on screen, are left out; the outlines stay.
 */
final class GpuBuildingDesigner extends Group {
    /** Below this many pixels a hex across, handles would cover the building: only the outlines show. */
    private static final float MIN_HEX_PIXELS = 70;
    private static final float LINE = 2.5f;
    /** A wall's handle stands this far out of its foot on screen, clear of the door's outline. */
    private static final float CLEAR = 16;

    /** A control at {@code anchor}, pushed out of its wall on screen along {@code out} (zero: centred on it). */
    private record Handle(Actor actor, Vector3 anchor, Vector3 out, float push) { }

    /** A door's outline: its foot from a to b on the ground, its top, and whether the same door goes on past each end. */
    private record Outline(Vector3 a, Vector3 b, float top, boolean onA, boolean onB) { }

    private final UiKit ui;
    private final Texture white;
    private final List<Handle> handles = new ArrayList<>();
    private final List<Outline> outlines = new ArrayList<>();
    private final List<float[]> lines = new ArrayList<>();
    private Doors shown = Doors.NONE;
    private BoardScene shownScene;
    private Consumer<Command> send = command -> { };

    GpuBuildingDesigner(UiKit ui) {
        this.ui = ui;
        white = ui.skin.get("white", Texture.class);
        setName("editor-building-designer");
        setTransform(false);
        setTouchable(Touchable.childrenOnly);
    }

    /** Each frame: the editor's state, where its handles stand in {@code camera}'s view, and where commands go. */
    void update(BoardScene scene, BoardEditorSession.Snapshot state, BoardCamera camera, Consumer<Command> send) {
        this.send = send;
        Doors doors = scene == null || state == null || state.tool() != BoardEditorSession.Tool.SELECT ? Doors.NONE : state.doors();
        if (!doors.equals(shown) || scene != shownScene) {
            shown = doors;
            shownScene = scene;
            build(scene);
        }
        lines.clear();
        if (handles.isEmpty() && outlines.isEmpty()) { return; }
        float scale = getStage().getWidth() / camera.camera.viewportWidth;
        float hexPixels = 0;
        for (Outline outline : outlines) {
            Vector2 a = GpuNameplates.project(camera.camera, outline.a()), b = GpuNameplates.project(camera.camera, outline.b());
            Vector3 topA = new Vector3(outline.a()), topB = new Vector3(outline.b());
            topA.z = outline.top();
            topB.z = outline.top();
            Vector2 c = GpuNameplates.project(camera.camera, topA), d = GpuNameplates.project(camera.camera, topB);
            if (a == null || b == null || c == null || d == null) { continue; }
            a.scl(scale); b.scl(scale); c.scl(scale); d.scl(scale);
            lines.add(new float[] { a.x, a.y, b.x, b.y });
            lines.add(new float[] { c.x, c.y, d.x, d.y });
            if (!outline.onA()) { lines.add(new float[] { a.x, a.y, c.x, c.y }); }
            if (!outline.onB()) { lines.add(new float[] { b.x, b.y, d.x, d.y }); }
        }
        for (Handle handle : handles) {
            Vector2 at = GpuNameplates.project(camera.camera, handle.anchor());
            Vector2 away = GpuNameplates.project(camera.camera, new Vector3(handle.anchor()).mulAdd(handle.out(), BoardGeometry.width()));
            // A wall that faces away from the view has its handles behind the building.
            boolean facing = handle.out().isZero() || handle.out().dot(camera.camera.direction) < .2f;
            if (at == null || away == null || !facing) { handle.actor().setVisible(false); continue; }
            at.scl(scale);
            away.scl(scale).sub(at);
            hexPixels = Math.max(hexPixels, away.len());
            if (!away.isZero(.001f)) { away.nor(); }
            Actor actor = handle.actor();
            actor.setVisible(true);
            actor.setPosition(Math.round(at.x + away.x * handle.push() - actor.getWidth() / 2),
                  Math.round(at.y + away.y * handle.push() - actor.getHeight() / 2));
        }
        if (hexPixels < MIN_HEX_PIXELS) { handles.forEach(handle -> handle.actor().setVisible(false)); }
    }

    private void build(BoardScene scene) {
        clearChildren();
        handles.clear();
        outlines.clear();
        shown.sides().stream().filter(side -> side.height() > 0).map(Doors.Side::hex).distinct().forEach(hex -> outline(scene, hex));
        for (Doors.Side side : shown.sides()) {
            Vector3[] edge = edge(scene, side.hex(), side.side());
            if (edge == null) { continue; }
            Vector3 middle = new Vector3(edge[0]).add(edge[1]).scl(.5f);
            Vector3 out = out(scene, side.hex(), side.side());
            if (side.height() == 0) {
                // As high as a door it would stand beside, to link at once; else as the hex.
                int height = side.storeys();
                for (Doors.Side other : shown.sides()) {
                    if (other.height() > 0 && BoardDoors.touch(new BoardDoors.Door(side.hex(), side.side()),
                          new BoardDoors.Door(other.hex(), other.side()))) { height = Math.min(height, other.height()); }
                }
                String value = Integer.toString(height);
                Table add = chip("designer-add-" + side.target());
                add.add(button("add", "BoardEditor.designer.addDoor", () -> send(Action.BUILDING_DOOR, side.target(), value)));
                handle(add, middle, out, CLEAR);
                continue;
            }
            Table chip = chip("designer-door-" + side.target());
            UiButton lower = button("remove", "BoardEditor.designer.lower", () -> send(Action.BUILDING_DOOR, side.target(),
                  Integer.toString(side.height() - 1)));
            lower.setDisabled(side.height() <= 1);
            UiButton raise = button("add", "BoardEditor.designer.raise", () -> send(Action.BUILDING_DOOR, side.target(),
                  Integer.toString(side.height() + 1)));
            raise.setDisabled(side.height() >= side.storeys());
            var height = ui.label(Integer.toString(side.height()), "hud-title", 14, side.group() > 0 ? UiTheme.MINT : UiTheme.TEXT);
            chip.add(lower);
            chip.add(height).minWidth(18).padLeft(3).padRight(3);
            chip.add(raise);
            chip.add(button("close", "BoardEditor.designer.removeDoor", () -> send(Action.BUILDING_DOOR, side.target(), "0"))).padLeft(4);
            handle(chip, middle, out, CLEAR);
        }
        for (Doors.Joint joint : shown.joints()) {
            Vector3 corner = corner(scene, joint);
            if (corner == null) { continue; }
            // Where both its doors' walls face: a corner between two hexes points along neither.
            Vector3 out = out(scene, joint.hex(), joint.side()).add(out(scene, joint.otherHex(), joint.otherSide())).nor();
            UiButton link = button(joint.linked() ? "link" : "link-off", joint.linked() ? "BoardEditor.designer.unlink" : "BoardEditor.designer.link",
                  () -> send(Action.BUILDING_DOOR_LINK, joint.target(), Boolean.toString(!joint.linked())));
            link.setName("designer-link");
            link.setChecked(joint.linked());
            Table chip = chip("designer-joint-" + joint.target());
            chip.add(link);
            handle(chip, corner, out, 0);
        }
    }

    private void send(Action action, String target, String value) { send.accept(new Command(action, target, value)); }

    /** A handle's dark plate, which keeps its controls readable over any ground. */
    private Table chip(String name) {
        Table chip = new Table();
        chip.setName(name);
        chip.setBackground(ui.skin.newDrawable("white", new Color(.06f, .08f, .09f, .88f)));
        chip.pad(2);
        return chip;
    }

    /**
     * The outlines of the doors the hex draws, from its kit's frame (x east, y north, its hex's middle the origin): the
     * hex's own sides give the frame's axes in the world.
     */
    private void outline(BoardScene scene, Coords hex) {
        BoardScene.Tile tile = scene.tile(hex);
        BoardScene.Feature building = tile == null ? null : KitDoors.building(tile);
        Vector3[] north = edge(scene, hex, 0), northEast = edge(scene, hex, 1), southEast = edge(scene, hex, 2);
        if (building == null || north == null) { return; }
        Vector3 centre = BoardGeometry.center(hex, 0);
        centre.z = north[0].z;
        Vector3 y = new Vector3(north[0]).add(north[1]).scl(.5f).sub(centre).scl((float) (1 / KitOutline.H));
        Vector3 x = new Vector3(northEast[0]).add(northEast[1]).add(southEast[0]).add(southEast[1]).scl(.5f).mulAdd(centre, -2)
              .scl((float) (1 / (1.5 * KitOutline.R)));
        for (KitDoors.Cut cut : KitDoors.cuts(scene, tile, building)) {
            Vector3 a = new Vector3(centre).mulAdd(x, cut.px()).mulAdd(y, cut.py());
            Vector3 b = new Vector3(centre).mulAdd(x, cut.qx()).mulAdd(y, cut.qy());
            outlines.add(new Outline(a, b, centre.z + cut.levels() * BoardGeometry.level(), cut.before(), cut.after()));
        }
    }

    private void handle(Actor actor, Vector3 anchor, Vector3 out, float push) {
        if (actor instanceof Table table) { table.pack(); }
        addActor(actor);
        actor.setVisible(false);
        handles.add(new Handle(actor, anchor, out, push));
    }

    private UiButton button(String icon, String tip, Runnable action) {
        UiButton button = ui.button("hud-mini", icon, null, null);
        button.pad(2);
        button.pack();
        UiKit.onChange(button, () -> {
            if (getStage() != null) { getStage().setKeyboardFocus(null); }
            action.run();
        });
        ui.tip(button).getActor().setText(UiKit.text(tip));
        return UiKit.ownEvents(button);
    }

    /** The hexside's two ends on its hex's ground, in the order of its corners; null off the board. */
    private static Vector3[] edge(BoardScene scene, Coords hex, int side) {
        BoardScene.Tile tile = scene.tile(hex);
        if (tile == null) { return null; }
        for (int edge = 0; edge < 6; edge++) {
            if (BoardGeometry.edgeDirection(edge) != side) { continue; }
            Vector3 a = BoardGeometry.corner(hex, 0, edge), b = BoardGeometry.corner(hex, 0, edge + 1);
            a.z = BoardGeometry.surfaceZ(tile);
            b.z = a.z;
            return new Vector3[] { a, b };
        }
        return null;
    }

    /** Out of the hex through the middle of its side, level. */
    private static Vector3 out(BoardScene scene, Coords hex, int side) {
        Vector3[] edge = edge(scene, hex, side);
        return new Vector3(edge[0]).add(edge[1]).scl(.5f).sub(BoardGeometry.center(hex, 0)).scl(1, 1, 0).nor();
    }

    /** The corner where a joint's two doors meet, on the first one's ground. */
    private static Vector3 corner(BoardScene scene, Doors.Joint joint) {
        Vector3[] first = edge(scene, joint.hex(), joint.side()), second = edge(scene, joint.otherHex(), joint.otherSide());
        if (first == null || second == null) { return null; }
        for (Vector3 a : first) {
            for (Vector3 b : second) {
                if (Vector2.dst2(a.x, a.y, b.x, b.y) < 1) { return a; }
            }
        }
        return null;
    }

    @Override public void draw(Batch batch, float parentAlpha) {
        float previous = batch.getPackedColor();
        for (int pass = 0; pass < 2; pass++) {
            Color color = pass == 0 ? Color.BLACK : UiTheme.MINT;
            batch.setColor(color.r, color.g, color.b, parentAlpha * getColor().a * (pass == 0 ? .6f : 1));
            float width = pass == 0 ? LINE + 2 : LINE;
            for (float[] line : lines) {
                float dx = line[2] - line[0], dy = line[3] - line[1];
                batch.draw(white, line[0], line[1] - width / 2, 0, width / 2, (float) Math.hypot(dx, dy), width, 1, 1,
                      (float) Math.toDegrees(Math.atan2(dy, dx)), 0, 0, 1, 1, false, false);
            }
        }
        batch.setPackedColor(previous);
        super.draw(batch, parentAlpha);
    }
}
