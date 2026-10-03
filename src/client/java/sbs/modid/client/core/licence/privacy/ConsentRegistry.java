/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One account's consent, in memory: the rules, the stored answers and the change notifications.
 *
 * <p>This is the half of the consent system with no Minecraft in it, which is the point - the
 * "nothing is sent without consent" guarantee is only worth as much as the tests behind it, and a
 * class that needs a launched game to instantiate does not get tested. {@link ConsentManager} adds
 * the account identity and the backend mirror on top.
 *
 * <p><b>Threading.</b> Grants are made on the render thread (the settings screen) and read on every
 * background thread that sends anything (presence, the IRC poll loop, the flip pollers). The state
 * map is an immutable snapshot behind a {@code volatile} field, replaced wholesale on every change,
 * so a reader sees either the answer before a change or the answer after it - never a map being
 * mutated underneath it. Writes are {@code synchronized} against each other so two rapid clicks
 * cannot interleave into a lost update.
 */
public final class ConsentRegistry {

    /** Notified after a scope's effective answer changes, so features can start or stop. */
    @FunctionalInterface
    public interface Listener {
        void onConsentChanged(ConsentScope scope, boolean granted);
    }

    private final ConsentStore store;
    private final String accountId;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Immutable snapshot; replaced, never mutated. See the threading note above. */
    private volatile Map<ConsentScope, ConsentState> states;
    private volatile boolean noticeShown;

    public ConsentRegistry(ConsentStore store, String accountId) {
        this.store = store;
        this.accountId = accountId == null ? "" : accountId;
        ConsentStore.Snapshot snapshot = store.load();
        this.states = Collections.unmodifiableMap(snapshot.states());
        this.noticeShown = snapshot.noticeShown();
    }

    /** Whether this account has already been told the privacy screen exists. */
    public boolean noticeShown() {
        return noticeShown;
    }

    /** Records that the one-time notice has been shown, so it is not repeated next launch. */
    public synchronized void markNoticeShown() {
        if (!noticeShown) {
            noticeShown = true;
            persist();
        }
    }

    public String accountId() {
        return accountId;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * The one question the network gate asks. True only for a scope the user actually granted,
     * against the disclosure currently in force.
     *
     * <p>The two special cases are both refusals-by-construction rather than lookups:
     * {@link ConsentScope.Kind#CONTRACT} is always true because it describes the paid service
     * itself and has no switch to consult, and {@link ConsentScope.Availability#PLANNED} is always
     * false because nothing in this build is allowed to send for it - not even if the file on disk
     * says otherwise, which is what makes a hand-edited {@code true} or a consent file from a
     * future build unable to arm a payload path this version cannot show the user.
     */
    public boolean isGranted(ConsentScope scope) {
        if (scope == null) {
            return false;
        }
        if (scope.kind() == ConsentScope.Kind.CONTRACT) {
            return true;
        }
        if (scope.isPlanned()) {
            return false;
        }
        return states.get(scope).countsFor(scope);
    }

    /** The raw stored answer, for the settings UI and the audit mirror. Never {@code null}. */
    public ConsentState state(ConsentScope scope) {
        ConsentState state = states.get(scope);
        return state == null ? ConsentState.none() : state;
    }

    /**
     * True when a scope was granted against an older disclosure and has therefore lapsed. The
     * settings screen surfaces these so a reset reads as "this changed, please look again" rather
     * than as a toggle that mysteriously turned itself off.
     */
    public boolean lapsed(ConsentScope scope) {
        ConsentState state = state(scope);
        return state.granted() && state.disclosureVersion() != scope.disclosureVersion();
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Records a grant. Refused for anything not {@link ConsentScope#isToggleable()} - a contract
     * term has no answer to store, and a planned scope has no traffic to authorise, so accepting a
     * grant for either would write a record of consent to something that does not exist.
     *
     * @return whether the answer changed
     */
    public boolean grant(ConsentScope scope, ConsentSource source) {
        if (scope == null || !scope.isToggleable()) {
            return false;
        }
        return set(scope, ConsentState.granted(scope, source, System.currentTimeMillis()));
    }

    /**
     * Withdraws consent. Unlike {@link #grant}, this is allowed for any scope with a stored answer,
     * including one that has since become {@link ConsentScope.Availability#PLANNED}: withdrawal
     * must never be the operation that is unavailable.
     *
     * @return whether the answer changed
     */
    public boolean revoke(ConsentScope scope, ConsentSource source) {
        if (scope == null || scope.kind() == ConsentScope.Kind.CONTRACT) {
            return false;
        }
        return set(scope, ConsentState.revoked(scope, source, System.currentTimeMillis()));
    }

    /** Grants every toggleable scope at once ("Accept all"). */
    public void grantAll(ConsentSource source) {
        for (ConsentScope scope : ConsentScope.values()) {
            grant(scope, source);
        }
    }

    /** Withdraws every scope at once ("Decline all"). */
    public void revokeAll(ConsentSource source) {
        for (ConsentScope scope : ConsentScope.values()) {
            revoke(scope, source);
        }
    }

    private synchronized boolean set(ConsentScope scope, ConsentState next) {
        boolean before = isGranted(scope);

        Map<ConsentScope, ConsentState> updated = new EnumMap<>(ConsentScope.class);
        updated.putAll(states);
        updated.put(scope, next);
        states = Collections.unmodifiableMap(updated);

        boolean after = isGranted(scope);
        persist();
        if (before != after) {
            for (Listener listener : listeners) {
                listener.onConsentChanged(scope, after);
            }
        }
        return before != after;
    }

    /**
     * Writes the file. A failure here is logged by the caller but never reverts the in-memory
     * answer: a user who just withdrew consent has withdrawn it, and a disk error must not leave
     * the feature running on the grounds that the "no" could not be saved. The cost of the
     * asymmetry is that an unwritable config directory re-asks on next launch, which is the safe
     * direction to fail in.
     */
    private void persist() {
        try {
            store.save(accountId, states, noticeShown);
        } catch (IOException e) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.error(
                    "[SBS][Privacy] Could not write the consent file at {} - the choice applies for "
                            + "this session but will be asked again next launch.", store.file(), e);
        }
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    /** Registers a feature's start/stop hook. Not called for no-op writes. */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Re-announces the current answer for every scope. Called once after the listeners are wired at
     * startup so a feature whose scope is off is stopped from the beginning, rather than running
     * until the first time the user happens to touch the toggle.
     */
    public void announceAll() {
        for (ConsentScope scope : ConsentScope.values()) {
            boolean granted = isGranted(scope);
            for (Listener listener : listeners) {
                listener.onConsentChanged(scope, granted);
            }
        }
    }
}
