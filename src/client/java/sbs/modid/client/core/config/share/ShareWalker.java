/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that decides which config fields exist as far as sharing is concerned.
 *
 * <p>Walks {@link SBSConfig} reflectively and collects every field carrying {@link Shareable}, keyed
 * by its dotted path ({@code theme.accentHex}). Both directions use this same set: the exporter
 * writes what is in it, the importer accepts only what is in it. One source of truth, so export and
 * import cannot drift into disagreeing about what a key means - which is the failure mode a separate
 * accept-list has, and it is silent.
 *
 * <p><b>The walk's only input is the in-memory {@link SBSConfig}.</b> It opens no files. That is what
 * structurally keeps the live licence token, the per-profile stores and everything under
 * {@code core/tracker} out of an export: they are not in the object being walked, so no annotation
 * mistake can reach them.
 *
 * <p>Nested settings objects are descended into; anything else is a leaf. A field with no annotation
 * is skipped without being read, so an un-annotated secret is never even loaded into a local.
 */
public final class ShareWalker {

    /** How deep the settings tree may nest. Today's deepest legitimate structure is 4. */
    private static final int MAX_DEPTH = 12;

    /** Only these packages are descended into - a walk must never wander into the JDK or Minecraft. */
    private static final String OWN_PACKAGE = "sbs.modid.client";

    private ShareWalker() {
    }

    /** One shareable field: where it lives, what it is, and how to read or write it. */
    public record Entry(String path, Field field, Object owner, Shareable spec) {

        /** The field's current value on the instance it was found on. */
        public Object read() {
            try {
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Share] cannot read {}: {}",
                        path, unreadable.toString());
                return null;
            }
        }

        /** Writes a validated value back. Only ever called from the atomic apply. */
        public boolean write(Object value) {
            try {
                field.setAccessible(true);
                field.set(owner, value);
                return true;
            } catch (ReflectiveOperationException | RuntimeException unwritable) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Share] cannot write {}: {}",
                        path, unwritable.toString());
                return false;
            }
        }
    }

    /**
     * Every shareable field of {@code config}, by dotted path, in declaration order.
     *
     * <p>Built fresh per call rather than cached: an export runs when a player presses a button, the
     * config object is swapped wholesale by a profile switch, and a cache keyed on nothing would
     * hand back entries bound to the previous profile's instances.
     */
    public static Map<String, Entry> collect(SBSConfig config) {
        Map<String, Entry> found = new LinkedHashMap<>();
        if (config != null) {
            walk(config, "", found, 0);
        }
        return found;
    }

    private static void walk(Object owner, String prefix, Map<String, Entry> found, int depth) {
        if (depth > MAX_DEPTH) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Share] settings nest deeper than {} at '{}' - "
                    + "not descending further", MAX_DEPTH, prefix);
            return;
        }
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
            Shareable spec = field.getAnnotation(Shareable.class);
            if (spec != null) {
                found.put(path, new Entry(path, field, owner, spec));
                continue;   // a leaf: never descend into an annotated field
            }
            if (isOwnSettingsObject(field.getType())) {
                Object nested = valueOf(field, owner);
                if (nested != null) {
                    walk(nested, path, found, depth + 1);
                }
            }
        }
    }

    /**
     * Whether a field's type is one of our own nested settings classes.
     *
     * <p>Restricted to this mod's packages on purpose. Descending by "is it a plain object" would
     * walk into whatever a field happens to hold - a Minecraft type, a JDK collection's internals -
     * and a walker that can reach arbitrary objects is one annotation mistake away from reading
     * something it has no business reading.
     */
    private static boolean isOwnSettingsObject(Class<?> type) {
        return !type.isPrimitive() && !type.isEnum() && !type.isArray()
                && type.getPackageName().startsWith(OWN_PACKAGE);
    }

    private static Object valueOf(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The paths of every shareable field, for the tests and the preview's "N of M settings" line.
     */
    public static List<String> paths(SBSConfig config) {
        return new ArrayList<>(collect(config).keySet());
    }
}
