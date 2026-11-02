/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.DragListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;

/**
 * A vertical list of rows (.list; the prototype's draggable .or rows): any actors, each as wide as the list at its
 * preferred height, from the top in the order its view adds them. Drag-to-reorder is off until {@link #reorderable}.
 * Then a press on a row's handle that moves a few units lifts the row: it follows the pointer above every other actor,
 * bounded to the list (and to a scroll pane's view of it), raised with a soft shadow and slightly translucent, while a
 * slot opens where it would drop and the other rows slide out of its way (in a scroll pane the list scrolls while the
 * pointer nears its edges). A drop glides the row into the slot; {@link #cancel} (Esc) glides it home. A keyboard move
 * ({@link #move}) flies the same
 * way. Once every row rests, the view hears the move once; the list keeps the new order until the view gives it new
 * rows, and glides back when none come (the view's model refused the move).
 */
public final class UiList extends WidgetGroup {
    /** A press becomes a drag once the pointer moves this far (a platform drag threshold). */
    private static final float DRAG_THRESHOLD = 5;
    /** The springs' rate per second: rows settle visibly in about 150 ms and keep their speed when retargeted. */
    private static final float RATE = 30;
    /** A position this close to its target, in units, has arrived. */
    private static final float SETTLED = .05f;
    /** A move its view has not answered with new rows after this many seconds goes back (the model refused it). */
    private static final float HOLD = 1;
    /** The lifted row's content keeps this much less alpha; its shadow lies this far below it. */
    private static final float GHOST_FADE = .08f;
    private static final float SHADOW_DROP = 5;
    /** The slot's inset in the dropped row's place, beside and above or below it. */
    private static final float SLOT_SIDE = 4;
    private static final float SLOT_END = 2;
    /** Auto-scroll: the band along a scroll pane's edges where the lifted row scrolls it, and the top speed. */
    private static final float SCROLL_EDGE = 28;
    private static final float SCROLL_SPEED = 480;

    private final Drawable slot;
    private final Drawable surface;
    private final Drawable shadow;
    private final Drag drag = new Drag();
    private final Map<Actor, Actor> handles = new HashMap<>();
    /** The rows as they show; the view's order is the children's, which a move changes only once the view answers. */
    private final List<Actor> order = new ArrayList<>();
    /** Each resting or sliding row's top, in units below the list's top. */
    private final Map<Actor, Spring> tops = new HashMap<>();
    private final Spring slotTop = new Spring(SETTLED);
    private final Spring ghostTop = new Spring(SETTLED);
    /** 0 for a row in its place, 1 for a lifted one: the shadow, raised surface and slot fade with it. */
    private final Spring elevation = new Spring(.005f);
    /** The pointer's stage position while it holds the lifted row. */
    private final Vector2 pointer = new Vector2();
    private BiConsumer<Integer, Integer> moved;
    /** The flying row (lifted by a drag or a keyboard move), its layer above everything, and its index before. */
    private Actor lifted;
    private Ghost ghost;
    private int from;
    private boolean dragging;
    /** The pointer's depth below the lifted row's top. */
    private float grab;
    /** Seconds since the view heard a move it has not answered yet; negative while none waits. */
    private float held = -1;
    private boolean reparenting;
    private boolean snap = true;
    private float measured;

    /** An empty list of the kit's skin; its view adds rows with {@link #add}. */
    public UiList(UiKit kit) {
        this(kit, null);
    }

    /**
     * An empty list as {@link #UiList(UiKit)}; with {@link UiTheme#TOKEN}, its flying row and slot take the Token v0
     * look, for rows in the {@link UiTheme#TOKEN_ROW} style.
     */
    public UiList(UiKit kit, String style) {
        String prefix = UiTheme.TOKEN.equals(style) ? "token-" : "";
        slot = kit.skin.getDrawable(prefix + "row-slot");
        surface = kit.skin.getDrawable(prefix + "row-lifted");
        shadow = kit.skin.getDrawable("shadow");
        setTransform(false);
        setTouchable(Touchable.childrenOnly);
        addListener(drag);
    }

    /**
     * Adds a row below the others. While the list is reorderable, a press on {@code handle} (part of the row, or the
     * row itself) drags it; a row without one (null) stays put, though others may move past it.
     */
    public UiList add(Actor row, Actor handle) {
        addActor(row);
        if (handle != null) {
            handles.put(row, handle);
        }
        return this;
    }

    /**
     * Turns on drag-to-reorder. {@code moved} hears each completed move once, when the rows rest: the row's index
     * before and after the move, in the order its view added the rows.
     */
    public UiList reorderable(BiConsumer<Integer, Integer> moved) {
        this.moved = moved;
        return this;
    }

    /**
     * Moves the row at {@code index} (in its view's order) {@code delta} places, as a keyboard does: it lifts, flies
     * there while the others slide, and lands, then the view hears the move. Pressed again before it lands, the same
     * row flies on; true when it flies. A row without a handle, a drag and a move still waiting for its view refuse.
     */
    public boolean move(int index, int delta) {
        Actor row = lifted != null ? (index == from ? lifted : null)
              : index >= 0 && index < getChildren().size ? getChildren().get(index) : null;
        if (moved == null || dragging || held >= 0 || row == null || !handles.containsKey(row) || getStage() == null) {
            return false;
        }
        int at = order.indexOf(row);
        int to = MathUtils.clamp(at + delta, 0, order.size() - 1);
        if (to == at) {
            return false;
        }
        if (lifted == null) {
            lift(row);
        }
        order.remove(row);
        order.add(to, row);
        aim(false);
        return true;
    }

    /** Esc during a drag: the row glides home and its view hears nothing. True when a drag was cancelled. */
    public boolean cancel() {
        if (!dragging) {
            return false;
        }
        drag.cancel();
        dragging = false;
        home();
        return true;
    }

    /**
     * Whether a row is dragged, flies or slides. Its view keeps the rows until the list rests, so a model change that
     * arrives meanwhile never cuts a move short; a list that waits for its view's answer is at rest.
     */
    public boolean busy() {
        return lifted != null || tops.values().stream().anyMatch(spring -> !spring.settled());
    }

    // ------------------------------------------------------------------ layout

    @Override
    public float getPrefWidth() {
        float width = 0;
        for (Actor row : order) {
            width = Math.max(width, row instanceof Layout layout ? layout.getPrefWidth() : row.getWidth());
        }
        return width;
    }

    @Override
    public float getMinWidth() {
        float width = 0;
        for (Actor row : order) {
            width = Math.max(width, row instanceof Layout layout ? layout.getMinWidth() : row.getWidth());
        }
        return width;
    }

    @Override
    public float getPrefHeight() {
        float height = 0;
        for (Actor row : order) {
            height += height(row);
        }
        return height;
    }

    /** A row's height on whole units, as a Table rounds its cells. */
    private static float height(Actor row) {
        return Math.round(row instanceof Layout layout ? layout.getPrefHeight() : row.getHeight());
    }

    @Override
    public void layout() {
        float total = 0;
        for (Actor row : order) {
            row.setWidth(getWidth());
            if (row instanceof Layout layout) {
                // Wrapped text takes the width first, then the row measures its height at that width.
                layout.validate();
                layout.invalidate();
            }
            row.setHeight(height(row));
            if (row instanceof Layout layout) {
                // Descendant coordinates must use the final height before a caller scrolls to or hits them.
                layout.validate();
            }
            total += row.getHeight();
        }
        aim(snap);
        snap = false;
        place();
        if (total != measured) {
            // The rows measured themselves at the list's width: the parent lays the list out again.
            measured = total;
            invalidateHierarchy();
        }
    }

    /** Every spring's target: the rows in their order, the flying row's slot open at its index. */
    private void aim(boolean now) {
        float top = 0;
        for (Actor row : order) {
            if (row == lifted) {
                slotTop.target = top;
                if (!dragging) {
                    ghostTop.target = top;
                }
            } else {
                Spring spring = tops.get(row);
                if (spring == null || now) {
                    spring = new Spring(SETTLED);
                    spring.snap(top);
                    tops.put(row, spring);
                }
                spring.target = top;
            }
            top += row.getHeight();
        }
    }

    /** Puts the rows where their springs are, and the flying row's layer over its place in the stage. */
    private void place() {
        for (Actor row : order) {
            Spring spring = tops.get(row);
            if (row != lifted && spring != null) {
                row.setPosition(0, getHeight() - spring.value - row.getHeight());
            }
        }
        if (lifted != null) {
            // A held row follows the pointer inside the list (follow bounds it); a flying one flies in the list.
            Vector2 corner = localToStageCoordinates(new Vector2(0, getHeight() - ghostTop.value - lifted.getHeight()));
            ghost.setBounds(corner.x, corner.y, getWidth(), lifted.getHeight());
            lifted.setBounds(0, 0, getWidth(), lifted.getHeight());
        }
    }

    // ------------------------------------------------------------------ motion

    @Override
    public void act(float delta) {
        super.act(delta);
        if (dragging) {
            follow(delta);
        } else if (lifted != null) {
            // A flying row rises as it leaves its place and settles as it reaches the slot.
            float away = Math.abs(ghostTop.value - ghostTop.target);
            elevation.target = Math.min(1, away / Math.max(1, lifted.getHeight() / 2));
            ghostTop.step(delta);
        }
        tops.values().forEach(spring -> spring.step(delta));
        slotTop.step(delta);
        elevation.step(delta);
        if (lifted != null && !dragging && ghostTop.settled() && elevation.value == 0 && elevation.settled()
              && !busyRows()) {
            land();
        } else if (lifted == null && held >= 0) {
            held += delta;
            if (held >= HOLD) {
                // No new rows came: the view's model refused the move, so the rows show its order again.
                held = -1;
                order.clear();
                getChildren().forEach(order::add);
                aim(false);
            }
        }
        place();
    }

    private boolean busyRows() {
        return !slotTop.settled() || tops.values().stream().anyMatch(spring -> !spring.settled());
    }

    /**
     * While the pointer holds the row: scrolls a scroll pane the row nears the edge of, keeps the row under the
     * pointer at its grab, bounded to the list, and moves the slot to the index
     * it reaches.
     */
    private void follow(float delta) {
        scroll(delta);
        Vector2 local = stageToLocalCoordinates(new Vector2(pointer));
        ghostTop.snap(bounded(getHeight() - local.y - grab));
        elevation.target = 1;
        int index = index(ghostTop.value + lifted.getHeight() / 2);
        if (index != order.indexOf(lifted)) {
            order.remove(lifted);
            order.add(index, lifted);
            aim(false);
        }
        place();
    }

    /**
     * The lifted row's top {@code top} units below the list's top, kept inside the list and inside a scroll pane's view
     * of it, so that the row never leaves the list on the screen.
     */
    private float bounded(float top) {
        float min = 0;
        float max = getHeight() - lifted.getHeight();
        ScrollPane pane = firstAscendant(ScrollPane.class);
        if (pane != null) {
            Vector2 low = stageToLocalCoordinates(pane.localToStageCoordinates(new Vector2(0, 0)));
            Vector2 high = stageToLocalCoordinates(pane.localToStageCoordinates(new Vector2(0, pane.getHeight())));
            min = Math.max(min, getHeight() - high.y);
            max = Math.min(max, getHeight() - low.y - lifted.getHeight());
        }
        return MathUtils.clamp(top, min, Math.max(min, max));
    }

    /**
     * The flying row's index for its centre {@code centre} units below the list's top. It passes a neighbour once its
     * centre reaches the neighbour's middle, and passes back only from the far side of it, so a pointer resting at a
     * boundary never makes the slot jitter.
     */
    private int index(float centre) {
        List<Actor> others = new ArrayList<>(order);
        others.remove(lifted);
        float[] tops = new float[others.size() + 1];
        for (int at = 0; at < others.size(); at++) {
            tops[at + 1] = tops[at] + others.get(at).getHeight();
        }
        float height = lifted.getHeight();
        int index = order.indexOf(lifted);
        while (index < others.size() && centre >= tops[index] + height + others.get(index).getHeight() / 2) {
            index++;
        }
        while (index > 0 && centre <= tops[index - 1] + others.get(index - 1).getHeight() / 2) {
            index--;
        }
        return index;
    }

    /** Scrolls the scroll pane around the list while the lifted row nears its top or bottom, faster the nearer. */
    private void scroll(float delta) {
        ScrollPane pane = firstAscendant(ScrollPane.class);
        if (pane == null || delta <= 0) {
            return;
        }
        float bottom = pane.localToStageCoordinates(new Vector2()).y;
        float top = bottom + pane.getHeight();
        float rowTop = pointer.y + grab;
        float rowBottom = rowTop - lifted.getHeight();
        float speed = 0;
        if (rowTop > top - SCROLL_EDGE) {
            speed = -Math.min(1, (rowTop - top + SCROLL_EDGE) / SCROLL_EDGE);
        } else if (rowBottom < bottom + SCROLL_EDGE) {
            speed = Math.min(1, (bottom + SCROLL_EDGE - rowBottom) / SCROLL_EDGE);
        }
        if (speed != 0) {
            pane.setScrollY(pane.getScrollY() + speed * SCROLL_SPEED * delta);
            pane.updateVisualScroll();
            // The pane moves the list now, so that the pointer's place in the list and the slot agree this frame.
            pane.layout();
        }
    }

    /** Lifts a row out of the list onto its layer above the stage, where it starts in its place. */
    private void lift(Actor row) {
        from = getChildren().indexOf(row, true);
        Spring spring = tops.remove(row);
        float top = spring == null ? 0 : spring.value;
        ghostTop.snap(top);
        slotTop.snap(top);
        elevation.snap(0);
        lifted = row;
        ghost = new Ghost();
        getStage().addActor(ghost);
        // Reparenting keeps the keyboard focus, such as a grip's.
        reparenting = true;
        ghost.addActor(row);
        reparenting = false;
        place();
    }

    /** The slot goes back to the row's own place, where the row then lands. */
    private void home() {
        order.remove(lifted);
        order.add(from, lifted);
        aim(false);
    }

    /** The row is in its slot: back among the rows, then the view hears the move, if the row moved. */
    private void land() {
        Actor row = lifted;
        int to = order.indexOf(row);
        Spring spring = new Spring(SETTLED);
        spring.snap(slotTop.target);
        tops.put(row, spring);
        reparenting = true;
        addActorAt(from, row);
        reparenting = false;
        ghost.remove();
        ghost = null;
        lifted = null;
        place();
        if (to != from) {
            // Until the view answers with new rows, which it may do at once, the rows keep this order.
            held = 0;
            moved.accept(from, to);
        }
    }

    /** Ends a flight at once without a move, the row back in its place: the list left the stage or lost its rows. */
    private void abandon() {
        drag.cancel();
        dragging = false;
        reparenting = true;
        addActorAt(from, lifted);
        reparenting = false;
        ghost.remove();
        ghost = null;
        lifted = null;
        snap = true;
        invalidate();
    }

    @Override
    protected void childrenChanged() {
        super.childrenChanged();
        if (reparenting) {
            return;
        }
        // The view changed the rows (its answer to a move, or new content): they show in its order, at rest.
        if (lifted != null) {
            abandon();
        }
        order.clear();
        getChildren().forEach(order::add);
        tops.keySet().retainAll(order);
        handles.keySet().retainAll(order);
        held = -1;
        snap = true;
    }

    @Override
    protected void setStage(Stage stage) {
        if (stage == null && lifted != null) {
            abandon();
            order.clear();
            getChildren().forEach(order::add);
        }
        super.setStage(stage);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        float alpha = parentAlpha * getColor().a * elevation.value;
        if (lifted != null && alpha > 0) {
            // The slot where the flying row will land, recessed and outlined in mint.
            float previous = batch.getPackedColor();
            batch.setColor(1, 1, 1, alpha);
            slot.draw(batch, getX() + SLOT_SIDE, getY() + getHeight() - slotTop.value - lifted.getHeight() + SLOT_END,
                  getWidth() - 2 * SLOT_SIDE, lifted.getHeight() - 2 * SLOT_END);
            batch.setPackedColor(previous);
        }
        super.draw(batch, parentAlpha);
    }

    /**
     * The flying row's layer on the stage: its soft shadow and raised face under it, the row slightly translucent. The
     * row stays under the pointer for the stage's hover, so it keeps the look its view gives a hovered row.
     */
    private final class Ghost extends Group {
        Ghost() {
            setTransform(false);
            setTouchable(Touchable.childrenOnly);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float alpha = parentAlpha * elevation.value;
            if (alpha > 0) {
                float previous = batch.getPackedColor();
                batch.setColor(1, 1, 1, alpha);
                // The shadow's drawable reaches its blur beyond the box on every side; a row too small for its
                // corners goes without.
                float width = getWidth() + shadow.getLeftWidth() + shadow.getRightWidth();
                float height = getHeight() + shadow.getBottomHeight() + shadow.getTopHeight();
                if (width >= shadow.getMinWidth() && height >= shadow.getMinHeight()) {
                    shadow.draw(batch, getX() - shadow.getLeftWidth(), getY() - SHADOW_DROP - shadow.getBottomHeight(),
                          width, height);
                }
                surface.draw(batch, getX(), getY(), getWidth(), getHeight());
                batch.setPackedColor(previous);
            }
            super.draw(batch, parentAlpha * (1 - GHOST_FADE * elevation.value));
        }
    }

    /** Lifts a row whose handle the pointer pressed, once it moves past the drag threshold. */
    private final class Drag extends DragListener {
        private Actor pressed;

        Drag() {
            setTapSquareSize(DRAG_THRESHOLD);
        }

        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
            pressed = moved == null || lifted != null || held >= 0 ? null : handled(event.getTarget());
            return pressed != null && super.touchDown(event, x, y, pointer, button);
        }

        @Override
        public void dragStart(InputEvent event, float x, float y, int pointer) {
            if (pressed == null || pressed.getParent() != UiList.this || lifted != null || getStage() == null) {
                cancel();
                return;
            }
            // A drag is no click: the row's and its ancestors' other listeners let go.
            event.getStage().cancelTouchFocusExcept(this, UiList.this);
            dragging = true;
            lift(pressed);
            grab = getHeight() - getTouchDownY() - ghostTop.value;
            follow(event);
        }

        @Override
        public void drag(InputEvent event, float x, float y, int pointer) {
            if (dragging) {
                follow(event);
            }
        }

        @Override
        public void dragStop(InputEvent event, float x, float y, int pointer) {
            if (!dragging) {
                return;
            }
            boolean cancelled = event.isTouchFocusCancel();
            if (!cancelled) {
                follow(event);
            }
            dragging = false;
            if (cancelled) {
                home();
            } else {
                aim(false);
            }
        }

        private void follow(InputEvent event) {
            UiList.this.pointer.set(event.getStageX(), event.getStageY());
            UiList.this.follow(0);
        }

        /** The row whose handle holds {@code target}, or null. */
        private Actor handled(Actor target) {
            for (Actor actor = target; actor != null && actor != UiList.this; actor = actor.getParent()) {
                if (actor.getParent() == UiList.this) {
                    Actor handle = handles.get(actor);
                    return handle != null && (target == handle || target.isDescendantOf(handle)) ? actor : null;
                }
            }
            return null;
        }
    }

    /**
     * A critically damped spring: a value that eases to its target without overshoot and keeps its speed when the
     * target moves, so an interrupted slide turns smoothly.
     */
    private static final class Spring {
        private final float precision;
        private float value;
        private float velocity;
        private float target;

        Spring(float precision) {
            this.precision = precision;
        }

        void snap(float to) {
            value = to;
            target = to;
            velocity = 0;
        }

        /** Advances by {@code delta} seconds along the exact solution, which holds for any frame time. */
        void step(float delta) {
            if (settled()) {
                return;
            }
            float offset = value - target;
            float drift = velocity + RATE * offset;
            float decay = (float) Math.exp(-RATE * delta);
            value = target + (offset + drift * delta) * decay;
            velocity = (velocity - RATE * drift * delta) * decay;
            if (Math.abs(value - target) < precision && Math.abs(velocity) < precision * RATE) {
                snap(target);
            }
        }

        boolean settled() {
            return value == target && velocity == 0;
        }
    }
}
