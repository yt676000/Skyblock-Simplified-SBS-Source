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
import java.util.concurrent.Executor;

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
 *
 * <p><b>File writes</b> run on the {@link Executor} handed in: {@code SbsExecutors.io()} in the
 * game, so a click on the settings screen does not wait for the disk; the calling thread in tests.
 * The content is captured before the hand-off, so a write never sees a later state than the one
 * that triggered it, and the single ordered IO thread keeps two writes from overtaking each other.
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
    private final Executor writer;

    /** Immutable snapshot; replaced, never mutated. See the threading note above. */
    private volatile Map<ConsentScope, ConsentState> states;
    private volatile boolean noticeShown;
    /** The {@link PrivacyPolicy#VERSION} the policy-update notice was last shown for. */
    private volatile String policyNoticeShown;
    /**
     * True while the file on disk came from a newer build. Nothing is written over it until the
     * user answers again; the notice flags stay in memory only.
     */
    private volatile boolean keepFile;

    /** A registry that writes on the calling thread. Used by tests. */
    public ConsentRegistry(ConsentStore store, String accountId) {
        this(store, accountId, Runnable::run);
    }

    public ConsentRegistry(ConsentStore store, String accountId, Executor writer) {
        this.store = store;
        this.accountId = accountId == null ? "" : accountId;
        this.writer = writer;
        ConsentStore.Snapshot snapshot = store.load();
        this.states = Collections.unmodifiableMap(snapshot.states());
        this.noticeShown = snapshot.noticeShown();
        this.policyNoticeShown = snapshot.policyNoticeShown();
        this.keepFile = snapshot.refused();
    }

    /** Whether this account has already been told the privacy screen exists. */
    public boolean noticeShown() {
        return noticeShown;
    }

    /** Records that the one-time notice has been shown, so it is not repeated next launch. */
    public synchronized void markNoticeShown() {
        if (!noticeShown) {
            noticeShown = true;
            persist(false);
        }
    }

    /**
     * The policy version of the most recent answer to a {@link ConsentScope.Kind#CONSENT} scope, or
     * {@code ""} when no such scope was ever answered.
     */
    public String acceptedPolicyVersion() {
        ConsentState latest = latestAnswer();
        return latest == null ? "" : latest.policyVersion();
    }

    /** Epoch millis of the most recent answer to a consent scope, {@code 0} when none. */
    public long lastAnsweredAt() {
        ConsentState latest = latestAnswer();
        return latest == null ? 0L : latest.grantedAt();
    }

    private ConsentState latestAnswer() {
        ConsentState latest = null;
        for (ConsentScope scope : ConsentScope.values()) {
            if (scope.kind() != ConsentScope.Kind.CONSENT) {
                continue;
            }
            ConsentState state = state(scope);
            if (state.grantedAt() > 0L && (latest == null || state.grantedAt() > latest.grantedAt())) {
                latest = state;
            }
        }
        return latest;
    }

    /**
     * Whether the policy-update notice is due: the user has answered at least once, the latest
     * answer was given against a policy version other than this build's (including {@code ""} for
     * answers migrated from a version 1 file), and the notice has not yet been shown for this
     * build's version.
     */
    public boolean policyNoticeDue() {
        ConsentState latest = latestAnswer();
        return latest != null
                && !PrivacyPolicy.VERSION.equals(latest.policyVersion())
                && !PrivacyPolicy.VERSION.equals(policyNoticeShown);
    }

    /** Records that the policy-update notice was shown for this build's policy version. */
    public synchronized void markPolicyNoticeShown() {
        if (!PrivacyPolicy.VERSION.equals(policyNoticeShown)) {
            policyNoticeShown = PrivacyPolicy.VERSION;
            persist(false);
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
     * True when a scope was granted against another disclosure or policy version and has therefore
     * lapsed. The
     * settings screen surfaces these so a reset reads as "this changed, please look again" rather
     * than as a toggle that mysteriously turned itself off.
     */
    public boolean lapsed(ConsentScope scope) {
        ConsentState state = state(scope);
        return state.granted() && !state.countsFor(scope);
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
        persist(true);
        if (before != after) {
            for (Listener listener : listeners) {
                listener.onConsentChanged(scope, after);
            }
        }
        return before != after;
    }

    /**
     * Writes the file. A failure here is logged but never reverts the in-memory answer: a user who
     * just withdrew consent has withdrawn it, and a disk error must not leave the feature running
     * on the grounds that the "no" could not be saved. The cost of the asymmetry is that an
     * unwritable config directory re-asks on next launch, which is the safe direction to fail in.
     *
     * @param userAnswer true for a grant or withdrawal. Only a user answer replaces a file written
     *                   by a newer build; a notice flag on its own does not.
     */
    private void persist(boolean userAnswer) {
        if (userAnswer) {
            keepFile = false;
        } else if (keepFile) {
            return;
        }
        Map<ConsentScope, ConsentState> content = states;
        boolean notice = noticeShown;
        String policyNotice = policyNoticeShown;
        writer.execute(() -> {
            try {
                store.save(accountId, content, notice, policyNotice);
            } catch (IOException e) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.error(
                        "[SBS][Privacy] Could not write the consent file at {} - the choice applies for "
                                + "this session but will be asked again next launch.", store.file(), e);
            }
        });
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
