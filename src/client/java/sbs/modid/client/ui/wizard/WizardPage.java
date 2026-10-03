/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard;

import java.util.List;

/**
 * One page of the first-run wizard or the update showcase, registering itself.
 *
 * <p>Discovered through {@link java.util.ServiceLoader}, exactly like {@code SbsModule} and for the
 * same reason: adding a page must not mean editing the overlay, the navigation or the persistence.
 * A page is a new class plus one line in
 * {@code src/client/resources/META-INF/services/sbs.modid.client.ui.wizard.WizardPage}, and a
 * one-line conflict is one anybody can resolve.
 *
 * <p>Implementations must be stateless with a public no-arg constructor.
 */
public interface WizardPage {

    /**
     * Stable id, assigned once and <b>never renumbered</b>. The "already shown" record is keyed on
     * it, so changing an id re-shows the page to everyone who has seen it, and reusing a retired id
     * hides a new page from everyone who saw the old one.
     */
    String id();

    /** Heading, and the name the page indicator and the dev jump command use. */
    String title();

    /** Which flow this page belongs to. A page is in exactly one. */
    WizardMode mode();

    /**
     * The mod version this page was introduced in, {@code major.minor.patch}. Parsed once when the
     * registry is built; a value that does not parse drops the page with a warning rather than
     * quietly sorting it to one end.
     */
    String introducedIn();

    /** Explicit position within its mode. Not registration order; ties break on {@link #id()}. */
    int order();

    /**
     * The page's content, rebuilt on every call so an element may carry live text.
     *
     * <p>A {@link PageElement.Setting} naming an option that no longer exists is dropped and logged;
     * if that leaves the page with no settings at all it is skipped entirely. See
     * {@link WizardPages#resolve}.
     */
    List<PageElement> content();

    /**
     * Showcase cap selection - higher survives when there are more pages than the cap allows, with
     * the version as the tiebreak. Ignored for onboarding, which is never capped.
     *
     * <p>Explicit rather than derived from the version so that release order does not decide what a
     * returning player sees: without it, a trivial page from last month would push out the
     * significant one from three versions ago.
     */
    default int importance() {
        return 0;
    }

    /**
     * Whether this page applies right now. False skips it silently - that is an ordinary outcome,
     * not a fault, so it is deliberately not logged.
     */
    default boolean available() {
        return true;
    }

    /**
     * Whether the feature this page describes needs a licence. A page that says so renders a plain
     * label saying it, and is exactly as skippable as any other: a showcase that is quietly an
     * upsell is corrosive, one that is labelled is fine.
     */
    default Tier tier() {
        return Tier.INCLUDED;
    }
}
