/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ratgenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import jakarta.annotation.Nullable;
import megamek.common.eras.Era;
import megamek.common.eras.Eras;

/**
 * A unit's availability to the factions of the Force Generator, era by era, as the unit selector's Faction
 * Availability tab shows it. No toolkit: the Swing panel and the native lobby readout each draw it their own way.
 *
 * @param eras      the eras to show, from the first to the last one any faction has the unit in
 * @param factions  the codes of the factions the unit's record names, in its order
 * @param texts     per era, per faction code, the availability text, empty when the faction has none then
 * @param <E>       the era type
 */
public record UnitAvailability<E>(List<E> eras, List<String> factions, Map<E, Map<String, String>> texts) {
    private static volatile Integer[] generatorEras;

    public UnitAvailability {
        eras = List.copyOf(eras);
        factions = List.copyOf(factions);
        texts = Map.copyOf(texts);
    }

    /** The availability text of {@code faction} in {@code era}, empty when it has none. */
    public String text(E era, String faction) {
        return texts.getOrDefault(era, Map.of()).getOrDefault(faction, "");
    }

    /**
     * The availability of the unit {@code chassis} {@code model}, or null while the Force Generator is still loading
     * or when it has no record of the unit. Reads the Force Generator's eras first if needed, which takes a while.
     */
    public static @Nullable UnitAvailability<Era> of(String chassis, String model) {
        RATGenerator generator = RATGenerator.getInstance();
        if (!generator.isInitialized()) {
            return null;
        }
        return of(generator, generator.getModelRecord((chassis + " " + model).trim()));
    }

    /**
     * The availability that {@code record} gives, or null while {@code generator} is still loading or when there is
     * no record. With no record and a loaded generator, still reads its eras, as the unit selector did on opening.
     */
    public static @Nullable UnitAvailability<Era> of(RATGenerator generator, @Nullable ModelRecord record) {
        if (!generator.isInitialized()) {
            return null;
        }
        Integer[] years = generatorEras(generator);
        if (record == null) {
            return null;
        }
        return of(Eras.getEras(), record.getIncludedFactions(), (era, faction) -> {
            List<AvailabilityRating> ratings = new ArrayList<>();
            FactionRecord factionRecord = generator.getFaction(faction);
            for (Integer year : years) {
                if ((Eras.getEra(year) != era) || (factionRecord != null && !factionRecord.isActiveInYear(year))) {
                    continue;
                }
                // Gate on the year as well as the era. An availability code can name the year a faction starts
                // getting the unit, and a Force Generator era can straddle two of the eras shown here: the 3060 era
                // runs to 3066, so a unit the Periphery only gets from 3063 was being listed under the Clan
                // Invasion, which ends in 3061.
                AvailabilityRating rating = generator.findModelAvailabilityRecord(year, record.getKey(), faction,
                      year);
                if (rating != null) {
                    ratings.add(rating);
                }
            }
            if (ratings.isEmpty()) {
                return "";
            }
            AvailabilityRating merged = generator.mergeFactionAvailability(faction, ratings);
            return (merged == null) ? "" : merged.formatAvailability(factionRecord);
        });
    }

    /**
     * The availability of {@code factions} over {@code allEras} as {@code availability} reads it (empty for none),
     * trimmed to the eras from the first to the last that any faction has text in.
     */
    public static <E> UnitAvailability<E> of(List<E> allEras, Collection<String> factions,
          BiFunction<E, String, String> availability) {
        Map<E, Map<String, String>> texts = new HashMap<>();
        int first = -1;
        int last = -1;
        for (int i = 0; i < allEras.size(); i++) {
            E era = allEras.get(i);
            Map<String, String> eraTexts = new HashMap<>();
            for (String faction : factions) {
                String text = availability.apply(era, faction);
                text = (text == null) ? "" : text;
                eraTexts.put(faction, text);
                if (!text.isEmpty()) {
                    first = (first == -1) ? i : first;
                    last = i;
                }
            }
            texts.put(era, eraTexts);
        }
        List<E> shown = (first == -1) ? List.of() : allEras.subList(first, last + 1);
        Map<E, Map<String, String>> shownTexts = new HashMap<>();
        shown.forEach(era -> shownTexts.put(era, texts.get(era)));
        return new UnitAvailability<>(shown, new ArrayList<>(factions), shownTexts);
    }

    /** The Force Generator's years with data, each loaded, read once. */
    private static Integer[] generatorEras(RATGenerator generator) {
        Integer[] years = generatorEras;
        if (years == null) {
            synchronized (UnitAvailability.class) {
                years = generatorEras;
                if (years == null) {
                    generator.getEraSet().forEach(generator::loadYear);
                    years = generator.getEraSet().toArray(new Integer[0]);
                    generatorEras = years;
                }
            }
        }
        return years;
    }
}
