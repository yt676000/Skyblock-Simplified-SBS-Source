/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import sbs.modid.client.helper.inventory.model.Accessory;
import sbs.modid.client.helper.inventory.model.MagicalPower;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The join between "what exists" ({@link AccessoryCatalog}) and "what you own"
 * ({@link AccessoryIndex}) - the answer the missing-accessory screen actually renders.
 *
 * <p><b>Three states, not two.</b> An accessory you do not have is not automatically missing: owning
 * the Wolf Ring is what you are supposed to do with the Wolf Talisman, and listing the talisman as
 * missing would send a player shopping for something they already outgrew. So a lower tier whose
 * higher tier is owned is {@link State#SUPERSEDED}, shown as its own state and hidden by default,
 * rather than being silently dropped - dropping it would make the catalogue totals stop adding up
 * and leave no way to check the ladder was right.
 *
 * <p><b>Magical Power counts once per ladder.</b> Only the best tier owned in a family contributes,
 * because that is how the bag scores it; summing every tier would flatter anyone mid-upgrade. The
 * same rule applies to the "available" figure, so it reads as what finishing the ladder is worth,
 * not what buying every rung would be worth.
 *
 * <p>Everything here is recomputed on demand from two cheap in-memory maps. It runs when the screen
 * refreshes, never per frame.
 */
public final class AccessoryProgress {

    /** Where one catalogued accessory stands for this profile. */
    public enum State {

        /** In the bag or the player's inventory. */
        OWNED("Owned"),

        /** Not held, but a higher tier of the same ladder is - so it is not a gap. */
        SUPERSEDED("Upgraded"),

        /** Not held, and nothing on its ladder replaces it. */
        MISSING("Missing");

        private final String displayName;

        State(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * One row of the screen.
     *
     * @param accessory     the catalogue entry
     * @param state         where it stands for this profile
     * @param effectiveTier the rarity that decides its Magical Power - one step up from the
     *                      catalogue rarity when the owned copy is recombobulated
     * @param power         the Magical Power for {@link #effectiveTier}, with its certainty
     * @param replacement   for {@link State#SUPERSEDED}, the owned higher tier; else {@code null}
     * @param upgrade       for {@link State#OWNED}, the next unowned tier up; else {@code null}
     * @param copies        how many copies sit in the bag - anything above one is dead weight
     */
    public record Row(Accessory accessory, State state, String effectiveTier,
                      MagicalPower.Value power, Accessory replacement, Accessory upgrade, int copies) {

        public boolean owned() {
            return state == State.OWNED;
        }

        /** Whether this owned accessory has a higher tier still to get. */
        public boolean upgradable() {
            return upgrade != null;
        }

        public boolean recombobulated() {
            return !effectiveTier.equalsIgnoreCase(accessory.tier);
        }
    }

    /**
     * The headline numbers.
     *
     * @param owned          catalogued accessories held
     * @param missing        real gaps ({@link State#MISSING})
     * @param superseded     lower tiers made redundant by an owned higher tier
     * @param total          the whole catalogue, after filtering
     * @param powerOwned     predicted Magical Power from what is held, best tier per ladder
     * @param powerAvailable predicted Magical Power still on the table
     * @param unknownPower   entries whose rarity yields no Magical Power figure at all
     */
    public record Summary(int owned, int missing, int superseded, int total,
                          int powerOwned, int powerAvailable, int unknownPower) {
    }

    /** What the screen is currently listing. */
    public enum Filter {
        MISSING("Missing"),
        OWNED("Owned"),
        UPGRADABLE("Upgradable"),
        ALL("All");

        private final String displayName;

        Filter(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public Filter next() {
            Filter[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /** How the rows are ordered. Labels stay short - they are drawn as segments, side by side. */
    public enum Sort {
        POWER("Power"),
        RARITY("Rarity"),
        NAME("Name");

        private final String displayName;

        Sort(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public Sort next() {
            Sort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        /** The persisted name, tolerating anything an older or newer build wrote. */
        public static Sort byName(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException bad) {
                return POWER;
            }
        }
    }

    /** Which slices of the catalogue are in scope at all. */
    public record Scope(boolean includeRift, boolean includeSuperseded) {
    }

    private AccessoryProgress() {
    }

    /**
     * Every catalogued accessory with its state resolved, unsorted and unfiltered beyond
     * {@code scope}. The single computation both {@link #summarise} and the screen read from, so the
     * counts on the header can never disagree with the grid under it.
     */
    public static List<Row> rows(Scope scope) {
        List<Accessory> catalogue = AccessoryCatalog.all();
        if (catalogue.isEmpty()) {
            return List.of();
        }
        Map<String, AccessoryIndex.Owned> owned = AccessoryIndex.getInstance().owned();
        Map<String, Integer> copies = AccessoryIndex.getInstance().counts();

        // Best owned step per ladder, so "is something better than this held" is one lookup.
        Map<String, Accessory> bestOwned = new HashMap<>();
        for (Accessory accessory : catalogue) {
            if (!accessory.hasFamily() || !owned.containsKey(accessory.id)) {
                continue;
            }
            Accessory best = bestOwned.get(accessory.family);
            if (best == null || accessory.step > best.step) {
                bestOwned.put(accessory.family, accessory);
            }
        }

        List<Row> rows = new ArrayList<>(catalogue.size());
        for (Accessory accessory : catalogue) {
            if (!scope.includeRift() && accessory.rift()) {
                continue;
            }
            AccessoryIndex.Owned held = owned.get(accessory.id);
            Accessory best = accessory.hasFamily() ? bestOwned.get(accessory.family) : null;

            State state;
            Accessory replacement = null;
            if (held != null) {
                state = State.OWNED;
            } else if (best != null && best.step > accessory.step) {
                state = State.SUPERSEDED;
                replacement = best;
            } else {
                state = State.MISSING;
            }
            if (state == State.SUPERSEDED && !scope.includeSuperseded()) {
                continue;
            }

            String tier = accessory.tier;
            if (held != null && held.recombobulated()) {
                tier = MagicalPower.upgraded(tier);
            }
            rows.add(new Row(accessory, state, tier == null ? "" : tier, MagicalPower.of(tier),
                    replacement, state == State.OWNED ? nextTier(accessory, owned) : null,
                    copies.getOrDefault(accessory.id, held == null ? 0 : 1)));
        }
        return rows;
    }

    /**
     * The next rung above an owned accessory that is not itself owned, or {@code null} when it is
     * already the best held or stands alone. This is what makes "Upgradable" a useful shopping list
     * rather than a restatement of "Missing".
     */
    private static Accessory nextTier(Accessory accessory, Map<String, AccessoryIndex.Owned> owned) {
        if (!accessory.hasFamily()) {
            return null;
        }
        for (Accessory member : AccessoryCatalog.family(accessory.family)) {
            if (member.step > accessory.step && !owned.containsKey(member.id)) {
                return member;
            }
        }
        return null;
    }

    /**
     * The headline numbers over {@code rows}.
     *
     * <p>Magical Power is counted once per ladder on both sides: the best owned tier for what is
     * held, and the best missing tier for what is still available. Entries whose rarity yields no
     * figure are counted separately instead of being folded in as zero - a total that quietly
     * swallowed 38 unknowns would be wrong in a way nobody could see.
     */
    public static Summary summarise(List<Row> rows) {
        int owned = 0;
        int missing = 0;
        int superseded = 0;
        int unknown = 0;

        // family -> best contribution seen, so a ladder is only ever counted once on each side.
        Map<String, Integer> ownedByFamily = new HashMap<>();
        Map<String, Integer> missingByFamily = new HashMap<>();
        int ownedLoose = 0;
        int missingLoose = 0;

        for (Row row : rows) {
            switch (row.state()) {
                case OWNED -> owned++;
                case SUPERSEDED -> superseded++;
                case MISSING -> missing++;
                default -> {
                }
            }
            if (row.state() == State.SUPERSEDED) {
                continue;   // already represented by the tier that replaced it
            }
            if (!row.power().known()) {
                unknown++;
                continue;
            }
            String family = row.accessory().hasFamily() ? row.accessory().family : null;
            Map<String, Integer> byFamily = row.owned() ? ownedByFamily : missingByFamily;
            if (family == null) {
                if (row.owned()) {
                    ownedLoose += row.power().power();
                } else {
                    missingLoose += row.power().power();
                }
            } else {
                byFamily.merge(family, row.power().power(), Math::max);
            }
        }

        int powerOwned = ownedLoose + ownedByFamily.values().stream().mapToInt(Integer::intValue).sum();
        int powerAvailable = missingLoose;
        for (Map.Entry<String, Integer> entry : missingByFamily.entrySet()) {
            // A ladder you are already on is worth only the step up from what you hold.
            powerAvailable += Math.max(0, entry.getValue() - ownedByFamily.getOrDefault(entry.getKey(), 0));
        }
        return new Summary(owned, missing, superseded, rows.size(),
                powerOwned, powerAvailable, unknown);
    }

    /** {@code rows} narrowed to {@code filter} and matched against {@code query}. */
    public static List<Row> filter(List<Row> rows, Filter filter, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>(rows.size());
        for (Row row : rows) {
            boolean keep = switch (filter) {
                case MISSING -> row.state() == State.MISSING;
                case OWNED -> row.state() == State.OWNED;
                case UPGRADABLE -> row.upgradable();
                case ALL -> true;
            };
            if (!keep) {
                continue;
            }
            if (!needle.isEmpty()
                    && !row.accessory().displayName().toLowerCase(Locale.ROOT).contains(needle)
                    && !row.accessory().id.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    /** Orders {@code rows} in place. Ties always break on name, so the grid never reshuffles itself. */
    public static void sort(List<Row> rows, Sort sort) {
        rows.sort((a, b) -> {
            int primary = switch (sort) {
                // Unknown power sorts last rather than as zero-and-therefore-first.
                case POWER -> Integer.compare(b.power().known() ? b.power().power() : -1,
                        a.power().known() ? a.power().power() : -1);
                case RARITY -> Integer.compare(MagicalPower.rank(b.effectiveTier()),
                        MagicalPower.rank(a.effectiveTier()));
                case NAME -> 0;
            };
            return primary != 0 ? primary
                    : a.accessory().displayName().compareToIgnoreCase(b.accessory().displayName());
        });
    }
}
