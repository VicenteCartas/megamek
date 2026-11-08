/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.RATGenerator;
import megamek.client.ratgenerator.UnitAvailability;
import megamek.client.ui.entityreadout.EntityReadout.Row;
import megamek.common.eras.Era;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * A unit's availability to the Force Generator's factions as readout rows, as the unit selector's Faction Availability
 * tab shows it: per era a title, then a row for each faction that fields the unit then, by name, its name and its
 * availability.
 */
final class ReadoutAvailability {
    private static final MMLogger LOGGER = MMLogger.create(ReadoutAvailability.class);

    private ReadoutAvailability() {
    }

    /** The rows read so far, by chassis and model: a unit's availability does not change while the game runs. */
    private static final Map<String, List<Row>> READ = new ConcurrentHashMap<>();
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Readout availability");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * {@code entity}'s availability, read only once its page is first shown: the first read loads every Force
     * Generator era, which takes a while, so it runs on a thread of its own. Asked while the Force Generator is still
     * loading, the page keeps saying it is reading until the generator is ready.
     */
    static LobbyReadoutView.LaterRows later(Entity entity) {
        String chassis = entity.getFullChassis();
        String model = entity.getModel();
        return later(chassis + '\n' + model, RATGenerator::getInstance, () -> of(chassis, model));
    }

    /**
     * The rows kept under {@code key}, read by {@code reader} once {@code generators}' Force Generator has loaded.
     */
    static LobbyReadoutView.LaterRows later(String key, Supplier<RATGenerator> generators,
          Supplier<List<Row>> reader) {
        return new LobbyReadoutView.LaterRows() {
            private volatile @Nullable List<Row> read;
            /** What to run once the Force Generator has loaded, if the page asked while it was still loading. */
            private final AtomicReference<Runnable> waiting = new AtomicReference<>();

            @Override
            public @Nullable List<Row> rows() {
                List<Row> mine = read;
                return (mine != null) ? mine : READ.get(key);
            }

            @Override
            public void read(Runnable done) {
                READER.execute(() -> {
                    List<Row> rows = READ.get(key);
                    if (rows == null) {
                        RATGenerator generator = generators.get();
                        if (!generator.isInitialized()) {
                            // Still loading: the page keeps saying so and is read once the generator is ready
                            afterLoading(generator, done);
                            return;
                        }
                        rows = reader.get();
                        READ.put(key, rows);
                    }
                    read = rows;
                    done.run();
                });
            }

            private void afterLoading(RATGenerator generator, Runnable done) {
                if (waiting.getAndSet(done) != null) {
                    // Already waiting: only the latest page to ask is told
                    return;
                }
                ActionListener loaded = new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        if (generator.isInitialized()) {
                            generator.removeListener(this);
                            Runnable next = waiting.getAndSet(null);
                            if (next != null) {
                                read(next);
                            }
                        }
                    }
                };
                generator.registerListener(loaded);
                // It may have finished between the check and the registration
                loaded.actionPerformed(null);
            }
        };
    }

    /**
     * The rows of the unit {@code chassis} {@code model}'s availability, none while the Force Generator is still
     * loading or when it does not know the unit. Reads the Force Generator's eras at first, which takes a while.
     */
    private static List<Row> of(String chassis, String model) {
        try {
            UnitAvailability<Era> availability = UnitAvailability.of(chassis, model);
            if (availability == null) {
                return List.of();
            }
            RATGenerator generator = RATGenerator.getInstance();
            return rows(availability, Era::name, code -> name(generator.getFaction(code), code));
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The faction availability of {} {} could not be read", chassis, model);
            return List.of();
        }
    }

    /** The rows of {@code availability}, the eras and factions named by {@code eraName} and {@code factionName}. */
    static <E> List<Row> rows(UnitAvailability<E> availability, Function<E, String> eraName,
          Function<String, String> factionName) {
        List<Row> rows = new ArrayList<>();
        for (E era : availability.eras()) {
            List<Row> factions = new ArrayList<>();
            for (String faction : availability.factions()) {
                String text = availability.text(era, faction);
                if (!text.isEmpty()) {
                    factions.add(new Row(Row.Kind.TABLE_ROW,
                          List.of(factionName.apply(faction), text.replace("\n", ", "))));
                }
            }
            if (!factions.isEmpty()) {
                factions.sort(Comparator.comparing(row -> row.cells().get(0), String.CASE_INSENSITIVE_ORDER));
                rows.add(new Row(Row.Kind.TITLE, List.of(eraName.apply(era).replace("\n", " "))));
                rows.addAll(factions);
            }
        }
        return rows;
    }

    private static String name(@Nullable FactionRecord faction, String code) {
        return ((faction != null) && (faction.getName() != null)) ? faction.getName() : code;
    }
}
