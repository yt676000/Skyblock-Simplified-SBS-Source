/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.presence.SbsPresence;

/**
 * Every rewrite a chat line goes through on its way to the screen - the Text Editor's replacements
 * and Streamer Mode's redaction ({@link DisplayedText}), the Number Format
 * ({@link NumberTextFormat}), and the SBS badge - in one place and in one order.
 *
 * <p><b>Where this is called from, and why that matters more than what it does.</b> These three were
 * a {@code @ModifyVariable} each on the HEAD of {@code ChatComponent}'s three public add methods -
 * the same instruction where the SBS chat parsers read the line. Mixin promises no ordering between
 * two injectors at one instruction, and the rewrites won: every parser in the mod was handed a line
 * whose figures had already been shortened to {@code 30.2M}, while their patterns match the wording
 * <i>Hypixel</i> sends. With Number Format on, "[Bazaar] Buy Order Setup! 1x Third Master Star for
 * 30,176,132 coins." arrived as "… for 30.2M coins.", matched nothing, and new Bazaar orders stopped
 * being tracked at all - silently, because an unmatched line is not an error.
 *
 * <p>So this is called from {@code BetterChat}'s display-funnel handler instead, as ordinary Java.
 * The public add methods run the parsers on the untouched line and then call the private funnel,
 * which is where this runs: the order is a fact of the call chain rather than of injector
 * application, and cannot silently invert again.
 *
 * <p><b>Order within the pipeline.</b> The player's own text rules and redaction first - they are
 * about the server's words. The values next. The badge last, so a decoration this mod added is never
 * input to either of the other two.
 *
 * <p>Every stage returns its input unchanged when it has nothing to do, so an untouched line comes
 * back as the same object and the caller can tell "nothing happened" by identity.
 */
public final class ChatDisplayRewrites {

    private ChatDisplayRewrites() {
    }

    /** The line as it should be displayed, or {@code message} itself when no rewrite applied. */
    public static Component apply(Component message) {
        if (message == null) {
            return null;
        }
        Component out = DisplayedText.filter(message);
        out = NumberTextFormat.orServer(ConfigManager.getInstance().get().chatOptions.numberFormat)
                .apply(out);
        return SbsPresence.getInstance().decorateChat(out);
    }
}
