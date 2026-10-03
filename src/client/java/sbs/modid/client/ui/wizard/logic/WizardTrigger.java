/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.ui.wizard.ModVersionSource;
import sbs.modid.client.ui.wizard.WizardMode;
import sbs.modid.client.ui.wizard.WizardPage;
import sbs.modid.client.ui.wizard.WizardPages;
import sbs.modid.client.ui.wizard.WizardScreen;
import sbs.modid.client.ui.wizard.model.ModVersion;

import java.util.List;

/**
 * Decides when the overlay may open, and opens it.
 *
 * <p><b>Defer, never drop.</b> Every condition below is re-checked on the next tick, so a moment
 * that is wrong - a screen open, a dungeon run, the client still settling - postpones the wizard
 * rather than consuming it. The only thing that ends the attempt is actually showing it.
 *
 * <p><b>"Joined and settled" is deliberately conservative.</b> A player in a world with no other
 * screen up, out of a dungeon, and on SkyBlock rather than in a lobby - because every setting this
 * wizard offers is a SkyBlock setting, and the tab list and scoreboard signals it defers on do not
 * exist off it. The settle delay exists because the first seconds after a join are exactly when
 * Hypixel is still sending the tab list, the scoreboard and its own title.
 */
public final class WizardTrigger {

    /** Ticks in-world before the overlay may open, so it never lands during join spam. */
    private static final int SETTLE_TICKS = 100;

    private static int ticksInWorld;
    private static boolean shownThisSession;

    private WizardTrigger() {
    }

    /** Called every client tick. Cheap once the overlay has been shown or is not due. */
    public static void tick() {
        if (shownThisSession) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            ticksInWorld = 0;
            return;
        }
        if (++ticksInWorld < SETTLE_TICKS) {
            return;
        }
        // Deferrals. None of these mark anything - the next tick tries again.
        // 26.2 exposes no public `screen` field; GuiStateManager is what tracks it for the mod.
        if (GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;   // another screen has the player's attention
        }
        if (SkyBlockLocation.island().isEmpty() || SkyBlockLocation.inDungeon()) {
            return;   // not on SkyBlock yet, or mid-run
        }

        try {
            open();
        } catch (Throwable t) {
            // A failure here must not retry forever on every tick.
            shownThisSession = true;
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Wizard] Could not open the overlay.", t);
        }
    }

    private static void open() {
        WizardState state = WizardAccount.state();

        if (!state.onboardingCompleted()) {
            List<WizardPage> due = WizardPages.onboarding(state::seen);
            if (!due.isEmpty()) {
                show(new WizardScreen(WizardMode.ONBOARDING, due, 0, null));
                return;
            }
            // Nothing to show, but the flow is over - record it so this is not recomputed forever.
            state.markOnboardingCompleted();
        }

        if (state.showcaseOptOut()) {
            shownThisSession = true;
            return;
        }
        ModVersion current = ModVersionSource.current().orElse(null);
        if (current == null) {
            // The running build does not declare a parseable version, so "what is new since" has no
            // answer. Logged once rather than guessed at - see ModVersion.
            shownThisSession = true;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Wizard] No parseable mod version, so no update pages can be selected.");
            return;
        }
        List<WizardPage> due = WizardPages.showcaseDue(state.showcaseBaseline(current), current);
        if (due.isEmpty()) {
            shownThisSession = true;
            return;
        }
        List<WizardPage> capped = WizardPages.capShowcase(due);
        show(new WizardScreen(WizardMode.SHOWCASE, capped, due.size() - capped.size(), current));
    }

    private static void show(WizardScreen screen) {
        shownThisSession = true;
        Minecraft.getInstance().setScreenAndShow(screen);
    }

    // ------------------------------------------------------------------
    // Dev tooling
    // ------------------------------------------------------------------

    /** Lets the trigger fire again this session, after a dev reset. */
    public static void rearm() {
        shownThisSession = false;
        ticksInWorld = SETTLE_TICKS;
    }

    /**
     * Opens a flow immediately, ignoring every gate. For {@code /sbs wizard}; without it, testing
     * means wiping a config between every iteration.
     *
     * @return false when that flow has no pages to show
     */
    public static boolean forceOpen(WizardMode mode) {
        List<WizardPage> due = new java.util.ArrayList<>();
        for (WizardPage page : WizardPages.all()) {
            if (page.mode() == mode && !WizardPages.resolve(page).isEmpty()) {
                due.add(page);
            }
        }
        if (due.isEmpty()) {
            return false;
        }
        due.sort(java.util.Comparator.comparingInt(WizardPage::order).thenComparing(WizardPage::id));
        shownThisSession = true;
        Minecraft.getInstance().setScreenAndShow(new WizardScreen(mode, due, 0, null));
        return true;
    }

    /** Opens one named page on its own, for {@code /sbs wizard page <id>}. */
    public static boolean forcePage(String pageId) {
        WizardPage page = WizardPages.byId(pageId).orElse(null);
        if (page == null || WizardPages.resolve(page).isEmpty()) {
            return false;
        }
        shownThisSession = true;
        Minecraft.getInstance().setScreenAndShow(new WizardScreen(page.mode(), List.of(page), 0, null));
        return true;
    }
}
