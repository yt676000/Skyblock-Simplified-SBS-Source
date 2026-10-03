/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Contract for anything that can be matched by the global search bar.
 *
 * <p>Categories and (later) individual modules and settings all implement this
 * interface. The search system therefore never needs to know the concrete type
 * of what it is filtering – it just asks {@link #matches(String)}. This keeps the
 * search architecture open for the large number of modules planned for the future.
 */
public interface Searchable {

    /**
     * Free-text terms describing this element (id, display name, keywords, ...).
     * The default {@link #matches(String)} implementation searches these.
     */
    Collection<String> searchTerms();

    /**
     * Returns {@code true} if this element should be shown for the given query.
     *
     * @param query the raw user query (may be empty / mixed case)
     */
    default boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (String term : searchTerms()) {
            if (term != null && term.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** Convenience helper for implementations that build term lists inline. */
    static Collection<String> terms(String... values) {
        return List.of(values);
    }
}
