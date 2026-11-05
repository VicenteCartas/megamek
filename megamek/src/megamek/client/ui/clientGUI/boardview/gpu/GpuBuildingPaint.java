/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Attribute;

/** An explicitly paintable building surface, with its placement's linear paint multiplier. */
final class GpuBuildingPaint extends Attribute {
    static final long TYPE = register("buildingPaint");
    final Color colour;

    GpuBuildingPaint(Color colour) {
        super(TYPE);
        this.colour = new Color(colour.r, colour.g, colour.b, 1);
    }

    @Override
    public Attribute copy() { return new GpuBuildingPaint(colour); }

    @Override
    public int hashCode() { return 31 * super.hashCode() + colour.hashCode(); }

    @Override
    protected boolean equals(Attribute other) { return compareTo(other) == 0; }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        var paint = (GpuBuildingPaint) other;
        int result = Float.compare(colour.r, paint.colour.r);
        if (result == 0) { result = Float.compare(colour.g, paint.colour.g); }
        if (result == 0) { result = Float.compare(colour.b, paint.colour.b); }
        return result;
    }
}
