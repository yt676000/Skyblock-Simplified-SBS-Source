/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Keys and lookups for the pet heads harvested from the Pets menu - pure, so the rules are tested.
 *
 * <p>Two kinds of key live in one map: the <b>full</b> key {@code name|level|rarity} (unique per pet
 * you own) and the <b>name-only</b> key (the old format, still read from older caches). Keying by name
 * alone let a default-skin Golden Dragon overwrite the skinned one whenever the unskinned one came
 * later in the menu, and the card then showed the plain head. Now the name-only entry is never
 * downgraded from a skinned stack to an unskinned one, and a lookup tries name + level first,
 * preferring a skinned stack.
 *
 * <p>"Skinned" is Hypixel's own mark: a pet with an applied skin carries {@value #SKIN_MARK} after
 * its name ("Golden Dragon ✦").
 */
final class PetStackIndex {

    static final char SKIN_MARK = '✦';

    private PetStackIndex() {
    }

    /** Comparable pet name: the loadout overlay's rule (ASCII letters, digits, ' and -; lower case). */
    static String cleanName(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9' -]", " ").replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    static String fullKey(String name, int level, String rarity) {
        return cleanName(name) + "|" + level + "|" + (rarity == null ? "" : rarity.toLowerCase(Locale.ROOT));
    }

    /** Whether a pet's displayed name carries the applied-skin mark. */
    static boolean skinned(String rawName) {
        return rawName != null && rawName.indexOf(SKIN_MARK) >= 0;
    }

    /**
     * Stores {@code value} under its full key and, unless that would replace a skinned stack with an
     * unskinned one, under the name-only key. Returns whether the map changed.
     *
     * @param isSkinned tells whether a stored value carries a skin
     */
    static <T> boolean put(Map<String, T> map, String name, int level, String rarity, T value,
                           Predicate<T> isSkinned, java.util.function.BiPredicate<T, T> same) {
        boolean changed = false;
        String full = fullKey(name, level, rarity);
        T known = map.get(full);
        if (known == null || !same.test(known, value)) {
            map.put(full, value);
            changed = true;
        }
        String byName = cleanName(name);
        T named = map.get(byName);
        boolean downgrade = named != null && isSkinned.test(named) && !isSkinned.test(value);
        if (!byName.isEmpty() && !downgrade && (named == null || !same.test(named, value))) {
            map.put(byName, value);
            changed = true;
        }
        return changed;
    }

    /**
     * The best stack for a loadout's pet line: name + level (any rarity, a skinned one first), then
     * the name-only entry. {@code null} when nothing matches.
     */
    static <T> T lookup(Map<String, T> map, String name, int level, Predicate<T> isSkinned) {
        String prefix = cleanName(name) + "|" + level + "|";
        T fallback = null;
        if (level > 0) {
            for (Map.Entry<String, T> entry : map.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    if (isSkinned.test(entry.getValue())) {
                        return entry.getValue();
                    }
                    if (fallback == null) {
                        fallback = entry.getValue();
                    }
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        return map.get(cleanName(name));
    }

    /**
     * Whether the loadout's pet is the active pet PetTracker knows: same cleaned name and the same
     * level (a level of 0 or less on either side means unknown, and does not match).
     */
    static boolean isActivePet(String name, int level, String activeName, int activeLevel) {
        return level > 0 && level == activeLevel && !cleanName(name).isEmpty()
                && cleanName(name).equals(cleanName(activeName));
    }
}
