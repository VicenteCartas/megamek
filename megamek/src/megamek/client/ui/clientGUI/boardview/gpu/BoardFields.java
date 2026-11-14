/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;

/**
 * Planted fields: the board's FIELD ground divided into straight-edged parcels, each with its own crop and row
 * direction. The world splits into north-south strips, and each strip into parallelograms by cuts at 30 degrees to the
 * x axis, rising or falling per strip: every parcel edge runs along one of the board's road directions. Strip widths
 * and cut spacings are jittered, so parcels range from about one to four hexes across. A parcel's look depends only on
 * where it is; the FIELD hexes decide only where planted ground lies. Neighbouring parcels meet at a grass headland.
 * Visual only; the game's field terrain is unchanged. The shaders draw the layout (terrain-crops.glsl); this mirror of
 * it says which crops a piece of ground can grow.
 */
final class BoardFields {
    /** The crop a field grows: every planted field stands full. */
    enum Crop { WHEAT, MAIZE }

    /** Metres from a parcel's edge that belong to the grass headland between parcels. */
    static final float HEADLAND = 1.2f;
    private static final float STRIP_METRES = 90, CUT_METRES = 75, JITTER = .5f;
    // The cuts' unit normal: (-+.5, sqrt(3) / 2), as their direction is 30 degrees up or down from the x axis.
    private static final float CUT_NORMAL_Y = .8660254f;

    private BoardFields() { }

    /** The parcel at (x, y) in metres: its strip in the high 32 bits, its cut in the low. */
    static long parcel(float x, float y) {
        int strip = strip(x);
        return (long) strip << 32 | cut(strip, x * cutNormalX(strip) + y * CUT_NORMAL_Y) & 0xffffffffL;
    }

    /**
     * Every parcel that may reach the polygon with these corners (metres): the cuts between the polygon's lowest and
     * highest cut coordinate in each strip it spans. A superset; a parcel that misses it grows nothing there.
     */
    static long[] parcels(float[] xs, float[] ys) {
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (float x : xs) { low = Math.min(low, x); high = Math.max(high, x); }
        var result = new ArrayList<Long>();
        for (int strip = strip(low); strip <= strip(high); strip++) {
            float first = Float.POSITIVE_INFINITY, last = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < xs.length; i++) {
                float c = xs[i] * cutNormalX(strip) + ys[i] * CUT_NORMAL_Y;
                first = Math.min(first, c);
                last = Math.max(last, c);
            }
            for (int cut = cut(strip, first); cut <= cut(strip, last); cut++) {
                result.add((long) strip << 32 | cut & 0xffffffffL);
            }
        }
        return result.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * The parcel's field code: its crop in bit 0 and its rows' direction in bits 1-2, as an index of 60 degree turns
     * of the vector across them. Rows run along the strip (0) or along its cuts (1 or 2, by their slope).
     */
    static int code(long parcel) {
        int strip = (int) (parcel >> 32), cut = (int) parcel;
        float pick = BoardRelief.hash(strip * 13 + 1, cut * 7 - 3);
        Crop crop = pick < .5f ? Crop.WHEAT : Crop.MAIZE;
        int direction = BoardRelief.hash(strip * 3 + 11, cut * 5 + 1) < .5f ? 0 : cutNormalX(strip) > 0 ? 1 : 2;
        return crop.ordinal() | direction << 1;
    }

    static Crop crop(int code) { return Crop.values()[code & 1]; }

    private static float stripEdge(int strip) {
        return STRIP_METRES * (strip + JITTER * (BoardRelief.hash(strip * 7 + 3, 41) - .5f));
    }

    private static int strip(float x) {
        int strip = (int) Math.floor(x / STRIP_METRES);
        return x < stripEdge(strip) ? strip - 1 : x >= stripEdge(strip + 1) ? strip + 1 : strip;
    }

    private static float cutNormalX(int strip) { return BoardRelief.hash(strip * 5 - 2, 17) < .5f ? -.5f : .5f; }

    private static float cutSpacing(int strip) { return CUT_METRES * (.75f + .5f * BoardRelief.hash(strip * 3 - 1, 9)); }

    private static float cutEdge(int strip, int cut) {
        return cutSpacing(strip) * (cut + JITTER * (BoardRelief.hash(strip * 5 + 2, cut * 3 - 7) - .5f));
    }

    private static int cut(int strip, float c) {
        int cut = (int) Math.floor(c / cutSpacing(strip));
        return c < cutEdge(strip, cut) ? cut - 1 : c >= cutEdge(strip, cut + 1) ? cut + 1 : cut;
    }
}
