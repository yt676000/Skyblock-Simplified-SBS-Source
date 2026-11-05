/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.performance.logic;

import sbs.modid.client.core.entity.NameClass;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Nametag distance by class: the pure decision behind the opt-in "Nametag Distance by Type"
 * setting. Which render-distance class a label stand's name falls into, and the distance that
 * class is allowed.
 *
 * <p>The class is derived from {@link NameClass}, which {@code EntityIndex} already caches per name
 * change - this is not a second classification of the text, only a mapping of that one onto three
 * distance buckets, plus the catalogue and exception lookups. Everything here is computed once per
 * name change by the caller and cached; nothing in this class runs per frame.
 */
public final class NametagDistance {

    /** The render-distance bucket of a label. */
    public enum LabelClass {
        /** Never distance-culled: NPC names, holograms, quest text, bosses, the exception list. */
        ALWAYS,
        /** Uses the normal armor-stand distance: unknown mobs, starred mobs, damage splashes. */
        NORMAL,
        /** Common catalogued mobs: the short distance. */
        TRASH
    }

    /** Distance of a class that is never culled. */
    public static final int UNLIMITED = 0;

    private NametagDistance() {
    }

    /**
     * The class of a name. {@code mobName} is the extracted mob name (null when none), {@code
     * plainLower} the colour-stripped name lower-cased, {@code exceptionsLower} the lower-cased
     * exception keywords (substring match on the whole name), {@code isCommonMob} the catalogue
     * lookup.
     */
    public static LabelClass classify(NameClass nameClass, String mobName, String plainLower,
            String[] exceptionsLower, Predicate<String> isCommonMob) {
        for (String keyword : exceptionsLower) {
            if (!keyword.isEmpty() && plainLower.contains(keyword)) {
                return LabelClass.ALWAYS;
            }
        }
        return switch (nameClass) {
            case HEALTH_TAG -> mobName != null && isCommonMob.test(mobName)
                    ? LabelClass.TRASH : LabelClass.NORMAL;
            case STARRED, DAMAGE_TAG -> LabelClass.NORMAL;
            // NPC names and "CLICK" labels, holograms, quest text, pets: never thinned.
            case NPC_LABEL, PET, PLAIN, NONE -> LabelClass.ALWAYS;
        };
    }

    /**
     * The allowed distance of a class in blocks, {@link #UNLIMITED} for none. {@code normal} is the
     * existing armor-stand distance; a {@code trash} of 0 falls back to it.
     */
    public static int distance(LabelClass cls, int trash, int normal) {
        return switch (cls) {
            case ALWAYS -> UNLIMITED;
            case NORMAL -> normal;
            case TRASH -> trash > 0 && (normal <= 0 || trash < normal) ? trash : normal;
        };
    }

    /** Whether a label {@code distanceSqr} away is beyond its class's distance. */
    public static boolean beyond(LabelClass cls, double distanceSqr, int trash, int normal) {
        int limit = distance(cls, trash, normal);
        return limit != UNLIMITED && distanceSqr > (double) limit * limit;
    }

    /** The comma-separated exception setting as lower-cased, trimmed keywords. Config-change only. */
    public static String[] parseExceptions(String csv) {
        if (csv == null || csv.isBlank()) {
            return new String[0];
        }
        String[] parts = csv.split(",");
        int n = 0;
        for (String part : parts) {
            String keyword = part.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty()) {
                parts[n++] = keyword;
            }
        }
        return java.util.Arrays.copyOf(parts, n);
    }
}
