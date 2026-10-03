/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.font;

/**
 * The licence facts for a font we ship, recorded next to the font itself so the "Fonts &amp; Licences"
 * page cannot drift from what is actually in the jar.
 *
 * <p>This exists for {@link FontOrigin#BUNDLED} fonts only. A {@link FontOrigin#USER} font has no
 * {@code FontLicence} and never gets one invented for it: we did not obtain that file, we do not
 * redistribute it, and asserting a licence for someone else's font would be a claim we cannot back.
 *
 * @param name       SPDX-style identifier, e.g. {@code OFL-1.1}
 * @param author     the copyright holder as written in the font's own licence header
 * @param sourceUrl  the upstream repository the file was taken from - never an aggregator site
 * @param commit     the immutable commit the file was taken at, so the build is reproducible
 * @param sha256     hex digest of the file as it sits in the jar (after subsetting, if any)
 * @param subset     whether we modified the file by subsetting it; drives the reserved-font-name rule
 * @param licenceFile path of the verbatim licence text, relative to the repository root
 */
public record FontLicence(String name,
                          String author,
                          String sourceUrl,
                          String commit,
                          String sha256,
                          boolean subset,
                          String licenceFile) {

    /** Short form for the licences list: {@code "OFL-1.1 - Some Author"}. */
    public String summary() {
        return name + " - " + author;
    }
}
