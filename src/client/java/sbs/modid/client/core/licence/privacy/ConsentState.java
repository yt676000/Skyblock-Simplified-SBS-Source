/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

/**
 * The stored answer for one {@link ConsentScope}: whether it was granted, when, against which
 * disclosure, and how the answer was given.
 *
 * <p>All four fields are the record of consent, not bookkeeping around it. {@code grantedAt} and
 * {@code source} are what makes a grant evidenced rather than merely asserted, and
 * {@code disclosureVersion} is what pins a yes to the specific text it was a yes to - a grant whose
 * version no longer matches the scope's current one is not a weaker yes, it is not a yes at all
 * (see {@link #countsFor}).
 *
 * <p>Immutable: {@link ConsentRegistry} swaps whole states rather than mutating them, so a reader on
 * another thread either sees the old answer or the new one and never a half-written mix of the two.
 *
 * @param granted           whether the user said yes
 * @param grantedAt         epoch millis of the answer, or {@code 0} when never answered
 * @param disclosureVersion the {@link ConsentScope#disclosureVersion()} in force at the time
 * @param source            how the answer was given
 */
public record ConsentState(boolean granted, long grantedAt, int disclosureVersion,
                           ConsentSource source) {

    /**
     * The state every scope starts in and falls back to: not granted, never answered. Used for a
     * fresh install, a missing file, an unparseable file and an unknown id alike - all of which
     * mean the same thing, that nobody has agreed to anything.
     */
    public static ConsentState none() {
        return new ConsentState(false, 0L, 0, ConsentSource.DEFAULT);
    }

    /** A grant recorded now, against the scope's current disclosure. */
    public static ConsentState granted(ConsentScope scope, ConsentSource source, long now) {
        return new ConsentState(true, now, scope.disclosureVersion(), source);
    }

    /** A withdrawal recorded now. Kept rather than deleted so the audit trail shows the change. */
    public static ConsentState revoked(ConsentScope scope, ConsentSource source, long now) {
        return new ConsentState(false, now, scope.disclosureVersion(), source);
    }

    /**
     * Whether this stored answer still authorises the given scope.
     *
     * <p>The version comparison is {@code !=} rather than {@code <} on purpose. A file claiming a
     * <i>higher</i> version than the build knows about is not "even more consented" - it is a file
     * from a future build, or an edited one, describing a disclosure this code cannot show the user.
     * Treating it as a grant would send data under terms the running client cannot display.
     */
    public boolean countsFor(ConsentScope scope) {
        return granted && disclosureVersion == scope.disclosureVersion();
    }
}
