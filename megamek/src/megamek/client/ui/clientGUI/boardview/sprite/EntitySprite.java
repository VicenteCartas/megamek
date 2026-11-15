/*
 * Copyright (C) 2014-2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.client.ui.clientGUI.boardview.sprite;

import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;

import megamek.client.ui.clientGUI.boardview.BoardGlyphContext;
import megamek.client.ui.clientGUI.boardview.UnitAnnotations;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/** Legacy 2D placement of the shared unit annotations. */
public class EntitySprite extends Sprite {
    private final UnitAnnotations annotations;

    public EntitySprite(BoardGlyphContext context, Entity entity, int part, Image radarBlip) {
        super(context);
        annotations = new UnitAnnotations(bv, entity, part);
        bounds = annotations.getBounds();
    }

    @Override
    public void prepare() {
        annotations.prepare();
        image = annotations.image();
        bounds = annotations.getBounds();
    }

    @Override
    public Rectangle getBounds() { return bounds = annotations.getBounds(); }

    @Override
    public boolean isInside(Point point) { return annotations.isInside(point); }

    public Entity getEntity() { return annotations.getEntity(); }
    public Coords getPosition() { return annotations.getPosition(); }
    public boolean onlyDetectedBySensors() { return annotations.onlyDetectedBySensors(); }
    public boolean isAffectedByECM() { return annotations.isAffectedByECM(); }
    public boolean getSelected() { return annotations.getSelected(); }

    public void setAffectedByECM(boolean affected) {
        annotations.setAffectedByECM(affected);
        image = annotations.image();
    }

    public void setSelected(boolean selected) {
        annotations.setSelected(selected);
        image = annotations.image();
    }

    @Override
    protected int getSpritePriority() { return getEntity().getSpriteDrawPriority(); }
    @Override
    public boolean isUnitVisual() { return true; }

}
