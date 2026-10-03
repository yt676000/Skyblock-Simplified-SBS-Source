/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.wizard.WizardMode;

/**
 * What the SBS Settings buttons do, so the config rows stay one line each.
 *
 * <p>Both open a screen, so both defer through {@code minecraft.execute} exactly like every other
 * command and row that opens one - the settings screen the button was clicked on is still closing
 * when this runs, and replacing a screen from inside its own click handler is how a screen ends up
 * drawn over the one that replaced it.
 *
 * <p><b>Replaying does not reset anything.</b> Opening the setup again shows the same pages against
 * the player's current settings; it does not put a single value back to its default. That is the
 * whole difference between this and the dev command's {@code reset}, and it is why the row can be
 * pressed by anyone without a confirmation.
 */
public final class WizardActions {

    private WizardActions() {
    }

    /** "Show The Setup Again" - the first-run flow, on demand. */
    public static void replayOnboarding() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (!WizardTrigger.forceOpen(WizardMode.ONBOARDING)) {
                // Only reachable if every onboarding page has been removed or none resolves, which
                // is a broken build rather than a player state - but saying so beats a dead button.
                say("There are no setup pages to show.");
            }
        });
    }

    /** "Show What Changed" - the showcase for this version, whether or not it has been seen. */
    public static void replayShowcase() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (!WizardTrigger.forceOpen(WizardMode.SHOWCASE)) {
                // The ordinary answer today: the mechanism ships before any version has update
                // pages to put in it. Told plainly rather than opening an empty screen.
                say("Nothing new to show for this version.");
            }
        });
    }

    private static void say(String text) {
        SBSChat.send(Component.literal("§8[§bSBS§8] §7" + text));
    }
}
