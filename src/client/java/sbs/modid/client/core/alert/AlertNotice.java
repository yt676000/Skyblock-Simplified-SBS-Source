/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.chat.logic.SBSChat;

/**
 * The one-time explanation shown to a player whose desktop notifications were migrated away.
 *
 * <p>A setting that silently changes shape during an update is how a working feature comes to look
 * broken: the toggle they remember is gone, and nothing says where it went. So the migration arms
 * this, and the first time they are actually in game it says what happened, what it was replaced
 * with, and where the two new channels live. Once - the flag persists, so it does not greet them
 * again on the next launch.
 */
public final class AlertNotice {

    /** Wait until the player is properly in a world; a chat line during the loading screen is lost. */
    private static final long DELAY_MS = 5_000L;

    private static long inWorldSince;
    private static boolean done;

    private AlertNotice() {
    }

    /** Called every client tick. Cheap: one boolean read once the notice is behind us. */
    public static void tick() {
        if (done) {
            return;
        }
        var config = ConfigManager.getInstance().get();
        if (config.alerts.desktopRemovalNoticeShown) {
            done = true;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            inWorldSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (inWorldSince == 0) {
            inWorldSince = now;
            return;
        }
        if (now - inWorldSince < DELAY_MS) {
            return;
        }
        done = true;
        config.alerts.desktopRemovalNoticeShown = true;
        ConfigManager.getInstance().save();

        SBSChat.send(Component.literal(" Desktop notifications have been replaced.")
                .withColor(0xFFD65A));
        SBSChat.send(Component.literal(
                " Your alerts now show on screen and play the mod's own ping, which stays audible "
                        + "with Minecraft muted.").withColor(SBSChat.WHITE));
        SBSChat.send(Component.literal(
                " There is also a new Narrator channel that speaks alerts out loud - off by "
                        + "default. Pick channels per alert on each feature's settings page, or run "
                        + "/sbs testnotify to hear what you have now.").withColor(0x9AA4B2));
    }
}
