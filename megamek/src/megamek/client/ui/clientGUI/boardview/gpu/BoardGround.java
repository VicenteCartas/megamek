/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

/**
 * One tile list's ground: the tiles as ground builders read them, and the small data derived from those (the concrete
 * fit, the road grades). Without history they are the tiles themselves. In a game they are the tiles as their ground
 * was made ({@link BoardScene.Tile#asMade}), so a structure's death leaves the ground, its fitted outline and its
 * grades as they were. Nothing is kept here that the tiles do not say.
 */
final class BoardGround {
    /** The latest tile list this ground was derived for ({@link #since}); GL thread only. */
    private List<BoardScene.Tile> source;
    private final List<BoardScene.Tile> tiles;
    private volatile BoardConcrete concrete;
    private volatile BoardRoadGrade.Cache roadGrades;

    /** A ground without history: the tiles shape it as they stand (the editor, previews, tests). */
    BoardGround(List<BoardScene.Tile> tiles) { this(tiles, tiles); }

    private BoardGround(List<BoardScene.Tile> source, List<BoardScene.Tile> tiles) {
        this.source = source;
        this.tiles = tiles;
    }

    /** The tiles ground builders read; the scene's own list while they are the same ({@link BoardScene#groundScene}). */
    List<BoardScene.Tile> tiles() { return tiles; }

    /** The outline belongs to this snapshot, independently of renderer and query-cache lifetimes. */
    BoardConcrete concrete(BoardScene scene) {
        float current = BoardGeometry.hexScale();
        BoardConcrete ready = concrete;
        if (ready != null && ready.scale == current) { return ready; }
        synchronized (this) {
            if (concrete == null || concrete.scale != current) { concrete = new BoardConcrete(scene.groundScene()); }
            return concrete;
        }
    }

    /**
     * A ground whose concrete inputs equal an earlier ground's takes that fit over instead of fitting the board again:
     * an edit that moves no wall or bank.
     */
    BoardConcrete adopt(BoardConcrete fit) {
        synchronized (this) {
            if (concrete == null || concrete.scale != BoardGeometry.hexScale()) { concrete = fit; }
            return concrete;
        }
    }

    /** This ground's road grades and road surfaces, at the caller's scale ({@link BoardRoadGrade.Cache}). */
    BoardRoadGrade.Cache roadGrades() {
        BoardRoadGrade.Cache ready = roadGrades;
        if (ready != null && ready.current()) { return ready; }
        synchronized (this) {
            if (roadGrades == null || !roadGrades.current()) { roadGrades = new BoardRoadGrade.Cache(); }
            return roadGrades;
        }
    }

    /** The ground of {@code tiles} without history: {@code last} while it is theirs, so one tile list has one ground. */
    static BoardGround of(BoardGround last, List<BoardScene.Tile> tiles) {
        return last != null && last.source == tiles && last.tiles == tiles ? last : new BoardGround(tiles);
    }

    /**
     * A game's ground of {@code live}, after {@code last} (null for a new board): each hex as its ground was made. A hex
     * whose ground inputs are still equal keeps its earlier tile, so every identity shortcut sees unchanged ground as
     * unchanged; while no hex differs the ground is {@code last} itself, with its fit and grades, and a ground whose
     * hexes changed their finish only takes those over.
     */
    static BoardGround since(BoardGround last, List<BoardScene.Tile> live) {
        if (last != null && last.source == live) { return last; }
        List<BoardScene.Tile> before = last == null || last.tiles.size() != live.size() ? null : last.tiles;
        List<BoardScene.Tile> base = before == null ? live : before;
        BoardScene.Tile[] next = null;
        boolean formed = before == null;
        for (int index = 0; index < live.size(); index++) {
            BoardScene.Tile tile = live.get(index), made;
            if (before != null && last.source.get(index) == tile) {
                made = before.get(index);
            } else {
                made = tile.asMade();
                if (before != null && sameInputs(before.get(index), made)) { made = before.get(index); }
            }
            if (made != base.get(index) && next == null) { next = base.toArray(new BoardScene.Tile[0]); }
            if (next != null) { next[index] = made; }
            formed |= before != null && made != before.get(index) && !sameForm(before.get(index), made);
        }
        if (next == null && before != null) {
            last.source = live;
            return last;
        }
        BoardGround ground = new BoardGround(live, next == null ? live : List.of(next));
        if (!formed) {
            ground.concrete = last.concrete;
            ground.roadGrades = last.roadGrades;
        }
        return ground;
    }

    /**
     * Whether two tiles of a hex have the same ground form: the ground's own equality decides
     * ({@link BoardScene.Tile.Ground}), with the features that are part of it and what shaped it.
     */
    static boolean sameForm(BoardScene.Tile a, BoardScene.Tile b) {
        return a == b || a.coords().equals(b.coords()) && a.ground().equals(b.ground())
              && a.footing().equals(b.footing()) && groundFeatures(a).equals(groundFeatures(b));
    }

    /** Whether two tiles of a hex are the same ground: its form ({@link #sameForm}) and the black ice that coats it. */
    static boolean sameInputs(BoardScene.Tile a, BoardScene.Tile b) {
        return sameForm(a, b) && a.blackIce() == b.blackIce();
    }

    private static List<BoardScene.Feature> groundFeatures(BoardScene.Tile tile) {
        return tile.features().stream().filter(BoardScene.Feature::groundKind).toList();
    }
}
