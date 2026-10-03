/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.helper.loadouts;

/**
 * What the Equipped Loadout widget knows about <b>this session</b>. Pure: time is passed in.
 *
 * <p>The rule (maintainer, 2026-10-02): once this session has confirmed which loadout is on the
 * body - a Loadouts menu read naming it, an overlay equip whose capture landed, or the worn set
 * identified by its helmet - the widget draws the live body and never says "changed", across server
 * hops and island changes. A piece swapped by hand simply shows as worn. The cached card and its
 * hint exist only between a restart and the first confirmation. Cleared at client start (a new
 * instance) and on a profile switch ({@code reloadProfile}).
 *
 * <p>Before confirmation the hint is not allowed to flicker: a difference has to hold for
 * {@value #CHANGE_PERSIST_MS} ms, and nothing is compared while an overlay equip's capture window
 * is still running (the body is still wearing the set being left).
 */
final class WidgetSession {

    /** How long the worn set must keep differing before the "from last session" hint shows. */
    static final long CHANGE_PERSIST_MS = 5_000L;

    private boolean confirmed;
    private String confirmedBy = "";
    private long differsSince = -1;
    private long quietUntil;

    boolean confirmed() {
        return confirmed;
    }

    String confirmedBy() {
        return confirmedBy;
    }

    /** Records a confirmation; returns true the first time this session. */
    boolean confirm(String why) {
        if (confirmed) {
            return false;
        }
        confirmed = true;
        confirmedBy = why;
        differsSince = -1;
        return true;
    }

    /** Back to "nothing confirmed" - a profile switch. */
    void reset() {
        confirmed = false;
        confirmedBy = "";
        differsSince = -1;
        quietUntil = 0;
    }

    /** No comparison before {@code until} (an overlay equip's capture window). */
    void quietUntil(long until) {
        quietUntil = Math.max(quietUntil, until);
        differsSince = -1;
    }

    /**
     * Whether the hint shows now, given whether the worn set differs from the card right now.
     * Never once confirmed; never during the quiet window; only after the difference has held for
     * {@link #CHANGE_PERSIST_MS}.
     */
    boolean hint(boolean differs, long now) {
        if (confirmed || now < quietUntil || !differs) {
            differsSince = -1;
            return false;
        }
        if (differsSince < 0) {
            differsSince = now;
        }
        return now - differsSince >= CHANGE_PERSIST_MS;
    }

    /**
     * Whether the widget draws the live body rather than the cached pieces: once confirmed; when
     * the cached entry is incomplete (it would draw holes); and whenever there is no hint (the body
     * matches, so the live stacks are the same pieces).
     */
    static boolean showsLiveBody(boolean confirmed, boolean entryComplete, boolean hint) {
        return confirmed || !entryComplete || !hint;
    }
}
