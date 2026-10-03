/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.ui.wizard.model.ModVersion;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one account's wizard has already shown, and the rules about what that means.
 *
 * <p><b>The invariant this class exists to hold: absence of a record means show.</b> Nothing is
 * suppressed by a default value, only by a record that was written. That is why there is no
 * "firstLaunch" flag here - {@code SBSConfig.firstLaunch} was exactly that construct, defaulted to
 * {@code true}, never written by anything, and therefore dead in every config ever produced. A flag
 * whose default is the interesting state is a flag nobody notices is broken.
 *
 * <p><b>Three independent records</b>, so resetting one cannot reset another: whether onboarding is
 * finished, which pages have been shown, and how far the showcase has got. The acknowledgement of
 * the privacy notice deliberately does <i>not</i> live here - it stays in the consent file next to
 * the answers it describes, because it is a statement about what the person was told, not about
 * what this overlay has drawn.
 *
 * <p><b>A failed write leaves the in-memory answer alone</b> and is logged rather than thrown, the
 * same asymmetry {@code ConsentRegistry} chose: a player who just finished the wizard has finished
 * it for this session, and an unwritable config directory costs them a repeat next launch. That is
 * the safe direction, and it is the same direction as every other rule here.
 */
public final class WizardState {

    private final WizardStore store;
    private final String accountId;

    private boolean onboardingCompleted;
    private Set<String> seenPages;
    private String lastShowcaseSeen;
    private boolean showcaseOptOut;

    public WizardState(WizardStore store, String accountId) {
        this.store = store;
        this.accountId = accountId == null ? "" : accountId;
        WizardStore.Snapshot snapshot = store.load();
        this.onboardingCompleted = snapshot.onboardingCompleted();
        this.seenPages = new LinkedHashSet<>(snapshot.seenPages());
        this.lastShowcaseSeen = snapshot.lastShowcaseSeen();
        this.showcaseOptOut = snapshot.showcaseOptOut();
    }

    // ------------------------------------------------------------------
    // Onboarding
    // ------------------------------------------------------------------

    /** Whether the first-run flow has been finished or skipped. False on a fresh install. */
    public synchronized boolean onboardingCompleted() {
        return onboardingCompleted;
    }

    /**
     * Records that onboarding is done. Called on <b>finish or skip</b>, never on open - a crash
     * halfway through must not consume the flow the player never got to the end of.
     */
    public synchronized void markOnboardingCompleted() {
        if (!onboardingCompleted) {
            onboardingCompleted = true;
            persist();
        }
    }

    /**
     * Marks onboarding done for an install that predates the wizard, so an existing player is not
     * walked through settings they configured months ago.
     *
     * <p>Separate from {@link #markOnboardingCompleted} so the two cannot be confused at the call
     * site: this one is a migration and runs once, from the branch of the config loader that knows
     * it just found an existing {@code config.json}. It deliberately does not touch
     * {@link #seen(String)} - a page added later still reaches these players, which is the whole
     * reason the seen-set is a separate record.
     */
    public synchronized void seedExistingInstall() {
        if (!onboardingCompleted) {
            onboardingCompleted = true;
            persist();
        }
    }

    // ------------------------------------------------------------------
    // Per-page
    // ------------------------------------------------------------------

    /** Whether a page has already been shown. False for any id never recorded. */
    public synchronized boolean seen(String pageId) {
        return pageId != null && seenPages.contains(pageId);
    }

    /** Records that a page was shown. Called on finish or skip, like every other mark here. */
    public synchronized void markSeen(String pageId) {
        if (pageId != null && !pageId.isBlank() && seenPages.add(pageId)) {
            persist();
        }
    }

    /** Every page id recorded, for the dev command that lists and resets them. */
    public synchronized Set<String> seenPages() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(seenPages));
    }

    /** Forgets one page, so it shows again. The dev command's per-page reset. */
    public synchronized void forget(String pageId) {
        if (seenPages.remove(pageId)) {
            persist();
        }
    }

    /** Forgets every page and the onboarding flag. Does not touch the showcase or the opt-out. */
    public synchronized void resetOnboarding() {
        onboardingCompleted = false;
        seenPages = new LinkedHashSet<>();
        persist();
    }

    // ------------------------------------------------------------------
    // Showcase
    // ------------------------------------------------------------------

    /**
     * The version to show changes <i>since</i>. This is the one record whose unknown state does not
     * mean "show".
     *
     * <p>If absent or unreadable meant version zero, a player whose file is missing, truncated or
     * written by a build that spelled versions differently would be handed every showcase page ever
     * written. Missing one showcase costs a player nothing; fifteen pages at once is the harm the
     * cap exists to prevent, and a corrupt file must not be able to cause the exact outcome the
     * feature is designed around. So unknown reads as {@code current}: nothing is due.
     *
     * <p>A stored version <i>ahead</i> of the running one is a downgrade. It is clamped to current
     * rather than rewritten: nothing is due now, and if the player upgrades past it again later the
     * pages they genuinely have not seen are still correctly due.
     *
     * @param current the running mod version
     */
    public synchronized ModVersion showcaseBaseline(ModVersion current) {
        if (lastShowcaseSeen == null) {
            return current;
        }
        ModVersion stored = ModVersion.parse(lastShowcaseSeen).orElse(null);
        if (stored == null) {
            // Repairable: the value is meaningless, and leaving it would re-run this every launch.
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Wizard] Stored showcase version '{}' is not a version - treating it as {} "
                            + "and rewriting. No update pages are due this launch.",
                    lastShowcaseSeen, current);
            lastShowcaseSeen = current.toString();
            persist();
            return current;
        }
        return stored.isAfter(current) ? current : stored;
    }

    /** The raw stored value, for the dev command. {@code null} when never written. */
    public synchronized String storedShowcaseVersion() {
        return lastShowcaseSeen;
    }

    /**
     * Records the showcase as seen up to a version. Called on finish or skip - and on the cap path
     * too, so a player who was shown five of eight pages is not asked again about the other three.
     */
    public synchronized void markShowcaseSeen(ModVersion version) {
        if (version == null) {
            return;
        }
        String next = version.toString();
        if (!next.equals(lastShowcaseSeen)) {
            lastShowcaseSeen = next;
            persist();
        }
    }

    /** Clears the showcase record only. Onboarding and the opt-out are untouched. */
    public synchronized void resetShowcase() {
        lastShowcaseSeen = null;
        persist();
    }

    // ------------------------------------------------------------------
    // Opt-out
    // ------------------------------------------------------------------

    /**
     * The permanent "don't show update notices" choice.
     *
     * <p>Stored here and nowhere else. It is offered in two places - on the showcase screen itself
     * and in the SBS config - and both read and write through this one field rather than each
     * keeping a copy, because the config is per config-profile while this is per account: two homes
     * for one value would disagree the first time someone switched profile.
     */
    public synchronized boolean showcaseOptOut() {
        return showcaseOptOut;
    }

    public synchronized void setShowcaseOptOut(boolean value) {
        if (showcaseOptOut != value) {
            showcaseOptOut = value;
            persist();
        }
    }

    // ------------------------------------------------------------------

    private void persist() {
        try {
            store.save(accountId, new WizardStore.Snapshot(
                    onboardingCompleted, seenPages, lastShowcaseSeen, showcaseOptOut));
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error(
                    "[SBS][Wizard] Could not write {} - this session is correct, but the wizard may "
                            + "show again next launch.", store.file(), e);
        }
    }
}
