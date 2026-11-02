/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import megamek.common.units.Entity;

/**
 * The order of a tractor-and-trailer train without a toolkit, as the Swing {@code TrainOrderDialog} asks it: the
 * tractor heads the train and the trailers follow in an order the player changes one step at a time.
 */
public final class TrainOrderForm {
    private final Entity head;
    private final List<Entity> trailers;

    public TrainOrderForm(Entity head, List<Entity> trailers) {
        this.head = Objects.requireNonNull(head);
        this.trailers = new ArrayList<>(trailers);
    }

    public Entity head() {
        return head;
    }

    /** @return the trailers, front to back */
    public List<Entity> trailers() {
        return List.copyOf(trailers);
    }

    /**
     * Moves the trailer at {@code index} by {@code offset} places, when it stays in the train.
     *
     * @return the trailer's index afterwards
     */
    public int move(int index, int offset) {
        int target = index + offset;
        if (index < 0 || index >= trailers.size() || target < 0 || target >= trailers.size()) {
            return index;
        }
        trailers.add(target, trailers.remove(index));
        return target;
    }

    /** @return the train as one line, "head -> first -> second" */
    public String line() {
        StringBuilder line = new StringBuilder(head.getShortName());
        for (Entity trailer : trailers) {
            line.append(" \u2192 ").append(trailer.getShortName());
        }
        return line.toString();
    }
}
