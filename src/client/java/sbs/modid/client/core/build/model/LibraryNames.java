/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.model;

import java.util.function.Predicate;

/**
 * Names for new library entries that never overwrite an existing one: a duplicate becomes
 * {@code "<name> (copy)"}, then {@code "(copy 2)"}, ...; a rename onto a taken name gets
 * {@code " (2)"}, {@code " (3)"}, ... Pure - "is this name taken" is a predicate (the store in play,
 * a set in tests).
 */
public final class LibraryNames {

    /** Past this many tries something is wrong; the caller reports it instead of looping. */
    static final int MAX_TRIES = 1000;

    private LibraryNames() {
    }

    public static String duplicateName(String base, Predicate<String> taken) {
        String first = base + " (copy)";
        if (!taken.test(first)) {
            return first;
        }
        for (int n = 2; n < MAX_TRIES; n++) {
            String candidate = base + " (copy " + n + ")";
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free name for a copy of \"" + base + "\"");
    }

    /** {@code wanted} if free, else the first free {@code "wanted (n)"}. */
    public static String unique(String wanted, Predicate<String> taken) {
        if (!taken.test(wanted)) {
            return wanted;
        }
        for (int n = 2; n < MAX_TRIES; n++) {
            String candidate = wanted + " (" + n + ")";
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free name like \"" + wanted + "\"");
    }
}
