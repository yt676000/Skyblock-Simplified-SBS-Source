/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;

/**
 * Tells a player, once per account, that the privacy screen exists and that everything starts off.
 *
 * <p><b>Why anyone has to be told.</b> Before this change the presence heartbeat, community chat
 * and the party finder ran as soon as their module toggles were on. After it they do not, because
 * an existing user counts as having consented to nothing - a consent regime that begins after the
 * collection did cannot read "they were already using it" as agreement. That is the right default
 * and it is also a silent feature regression, so it gets announced rather than left for someone to
 * discover by noticing their badge is gone.
 *
 * <p>Shown in chat rather than as a toast: it must survive long enough to be read and scrolled back
 * to, and a toast that expires in five seconds while the player is mid-fight is a notice in name
 * only. Once per account, recorded in the same file as the answers themselves.
 *
 * <p>The same tick also announces a policy update: when the user's answers were given against a
 * {@link PrivacyPolicy#VERSION} other than this build's, one notice per new version, recorded in
 * the consent file ({@link ConsentRegistry#policyNoticeDue}).
 */
public final class PrivacyNotice {

    /** Ticks to wait after joining before speaking, so the message is not buried by join spam. */
    private static final int DELAY_TICKS = 100;

    private static int ticksInWorld;
    private static boolean doneThisSession;

    private PrivacyNotice() {
    }

    /**
     * Called every client tick. Cheap: after the notice has been shown - which for most players is
     * on the first world they join after updating - this returns on a boolean.
     */
    public static void tick() {
        if (doneThisSession) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            ticksInWorld = 0;
            return;
        }
        if (++ticksInWorld < DELAY_TICKS) {
            return;
        }
        doneThisSession = true;

        ConsentRegistry registry = ConsentManager.getInstance().registry();
        if (!registry.noticeShown()) {
            // The first notice already points at the screen, so it covers this policy version too.
            registry.markNoticeShown();
            registry.markPolicyNoticeShown();
            announce("sbs.privacy.migration.title", "sbs.privacy.migration.body");
            return;
        }
        if (registry.policyNoticeDue()) {
            registry.markPolicyNoticeShown();
            announce("sbs.privacy.policy_update.title", "sbs.privacy.policy_update.body");
        }
    }

    /**
     * Two plain chat lines. No click event: the policy button lives on the settings screen, behind
     * the confirm prompt that names the host.
     */
    private static void announce(String titleKey, String bodyKey) {
        SBSChat.send(Component.literal(I18n.get(titleKey)).withColor(0xFFD64D));
        SBSChat.send(Component.literal(I18n.get(bodyKey)).withColor(0xFFFFFF));
    }
}
