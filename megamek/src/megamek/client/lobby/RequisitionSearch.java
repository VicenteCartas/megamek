/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.annotation.Nullable;
import megamek.common.loaders.MekSummary;
import megamek.common.loaders.MekSummaryCache;
import megamek.logging.MMLogger;

/**
 * The Requisition view's search, run away from the view's thread: each query goes to {@code executor}, a newer query
 * makes an older one that has not finished give no result, and the catalogue is read and indexed once, on the first
 * query, from {@code catalogue} (which may block until the unit cache has loaded). Results come back on the executor's
 * thread; the view hands them to its own.
 */
public final class RequisitionSearch {
    private static final MMLogger LOGGER = MMLogger.create(RequisitionSearch.class);

    /** What to look for: the game's rules, a category, the Commander's filters and the search text. */
    public record Query(RequisitionCatalog.Rules rules, RequisitionCatalog.Category category,
          RequisitionCatalog.Filters filters, String search) {
        public Query {
            Objects.requireNonNull(rules);
            Objects.requireNonNull(category);
            Objects.requireNonNull(filters);
            search = (search == null) ? "" : search;
        }
    }

    /**
     * What a query found.
     *
     * @param query    the query
     * @param rows     the units found, in name order
     * @param total    how many units the catalogue holds
     * @param sources  the source books of the units the rules allow, for the Source filter
     * @param millis   how long the query took, the catalogue's loading aside
     * @param loadMillis how long reading and indexing the catalogue took, when this query did it, else 0
     * @param types    the exact types holding a unit the rules allow, for the Type filter
     */
    public record Result(Query query, List<RequisitionRow> rows, int total, List<String> sources, long millis,
          long loadMillis, Set<RequisitionCatalog.ExactType> types) {
        public Result {
            rows = List.copyOf(rows);
            sources = List.copyOf(sources);
            types = Set.copyOf(types);
        }

        /** A result that offers every type. */
        public Result(Query query, List<RequisitionRow> rows, int total, List<String> sources, long millis,
              long loadMillis) {
            this(query, rows, total, sources, millis, loadMillis,
                  EnumSet.allOf(RequisitionCatalog.ExactType.class));
        }
    }

    private final Supplier<RequisitionCatalog.Index> catalogue;
    private final Executor executor;
    private final AtomicLong generation = new AtomicLong();
    /** Worked on by the executor's thread alone. */
    private RequisitionCatalog.Index index;
    private final Map<MekSummary, RequisitionRow> rows = new IdentityHashMap<>();
    private RequisitionCatalog.Rules sourcesFor;
    private List<String> sources = List.of();
    private Set<RequisitionCatalog.ExactType> types = Set.of();
    /** How long reading the catalogue took, until a result that says so is handed on; a stale query's is kept. */
    private long unreportedLoad;

    /**
     * @param catalogue reads and indexes the unit cache; called once, on the executor's thread
     * @param executor  runs the queries one at a time, in order
     */
    public RequisitionSearch(Supplier<RequisitionCatalog.Index> catalogue, Executor executor) {
        this.catalogue = catalogue;
        this.executor = executor;
    }

    /**
     * Runs {@code query} on the executor and gives its result to {@code done}, unless a newer query came first.
     *
     * @return the query's number, which grows with each query
     */
    public long search(Query query, Consumer<Result> done) {
        long number = generation.incrementAndGet();
        executor.execute(() -> {
            if (number != generation.get()) {
                return;
            }
            Result result;
            try {
                result = run(query);
            } catch (RuntimeException failure) {
                LOGGER.error(failure, "The Requisition search failed");
                return;
            }
            if (number == generation.get()) {
                unreportedLoad = 0;
                done.accept(result);
            }
        });
        return number;
    }

    /** Forgets the indexed catalogue, so the next query reads the unit cache again, as after it was refreshed. */
    public void reload() {
        executor.execute(() -> {
            index = null;
            rows.clear();
            sourcesFor = null;
        });
    }

    private Result run(Query query) {
        if (index == null) {
            long start = System.nanoTime();
            RequisitionCatalog.Index read = catalogue.get();
            index = (read == null) ? RequisitionCatalog.EMPTY : read;
            unreportedLoad = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        }
        long loadMillis = unreportedLoad;
        long start = System.nanoTime();
        List<MekSummary> found = RequisitionCatalog.offer(index, query.rules(), query.category(), query.filters(),
              query.search());
        List<RequisitionRow> shown = new ArrayList<>(found.size());
        for (MekSummary unit : found) {
            shown.add(rows.computeIfAbsent(unit, RequisitionRow::of));
        }
        if (!query.rules().equals(sourcesFor)) {
            sourcesFor = query.rules();
            sources = RequisitionCatalog.sources(index, query.rules());
            types = RequisitionCatalog.types(index, query.rules());
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Result(query, shown, index.size(), sources, millis, loadMillis, types);
    }

    /**
     * Reads the unit cache and readies it for searching, as the unit selector dialog lists it: an asset with a base
     * unit shows as its base unit, whose search also reads the asset's card. Blocks until the cache has loaded.
     */
    public static RequisitionCatalog.Index fromCache() {
        MekSummaryCache cache = MekSummaryCache.getInstance();
        return RequisitionCatalog.index(cache.getAllMeks(),
              unit -> unit.isBattlefieldSupportAsset() && (cache.getLinkedBaseUnit(unit) != null),
              unit -> unit.isBattlefieldSupportAsset() ? unit : cache.getLinkedAsset(unit));
    }

    /** The row of {@code unit} as a result shows it, made once. Call it on the executor's thread. */
    public @Nullable RequisitionRow row(MekSummary unit) {
        return rows.get(unit);
    }
}
