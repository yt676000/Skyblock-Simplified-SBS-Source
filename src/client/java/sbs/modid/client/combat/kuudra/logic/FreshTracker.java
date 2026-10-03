/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudra.render.KuudraAlert;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Fresh": the ten seconds after picking up Fresh Tools during which one player builds at double
 * speed.
 *
 * <p><b>Why anyone cares who is fresh.</b> The build phase is six piles and four people, and a fresh
 * player is worth two of everyone else for ten seconds. The team wants to know two things - that
 * somebody is fresh, so the others go and start a different pile, and how long is left, so the next
 * person can be at the tool spot before it runs out. Neither is shown anywhere in game.
 *
 * <p><b>Yours comes from Hypixel, everyone else's comes from them saying so.</b> The perk line is
 * only sent to the player who picked the tools up, so a teammate's fresh can only be known if their
 * client announced it. That is what the outgoing message is for, and why the incoming pattern is
 * loose about what surrounds the word - it has to match whatever wording another mod's user is
 * running, not just ours.
 */
public final class FreshTracker {

    private static final FreshTracker INSTANCE = new FreshTracker();

    /** Hypixel's line, sent only to the player who took the tools. */
    private static final String SELF_FRESH = "Your Fresh Tools Perk bonus doubles your building speed";

    /**
     * Somebody in the party announcing their own fresh. Anchored on the word so a sentence around it
     * ("FRESH! (60%)", "[IQ] FRESH", "im fresh") still lands, but not so loose that the word inside
     * another word counts.
     */
    private static final Pattern PARTY_FRESH = Pattern.compile(
            "^Party\\s*>\\s*(?:\\[[^]]*]\\s*)?(\\w{2,16})\\s*:\\s*.*\\bFRESH\\b",
            Pattern.CASE_INSENSITIVE);

    /** How long the perk lasts. Hypixel says so in the line itself. */
    private static final long FRESH_MS = 10_000L;

    /** Who is fresh, and when it runs out. Small, and read from the render thread. */
    private final Map<String, Long> until = new ConcurrentHashMap<>();

    private FreshTracker() {
    }

    public static FreshTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.KuudraSettings cfg() {
        return ConfigManager.getInstance().get().kuudra;
    }

    /** Milliseconds of fresh left for {@code player}, or {@code -1} when they are not fresh. */
    public long remaining(String player) {
        Long end = until.get(player.toLowerCase(Locale.ROOT));
        if (end == null) {
            return -1;
        }
        long left = end - System.currentTimeMillis();
        return left > 0 ? left : -1;
    }

    /** Whether anybody at all is fresh right now. */
    public boolean anyFresh() {
        long now = System.currentTimeMillis();
        for (Long end : until.values()) {
            if (end > now) {
                return true;
            }
        }
        return false;
    }

    /** Fresh left for the local player, or {@code -1}. */
    public long ownRemaining() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? -1 : remaining(player.getGameProfile().name());
    }

    void reset() {
        until.clear();
    }

    /** Drops entries that have run out, so the map never grows past the party size. */
    public void onClientTick() {
        if (until.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        until.values().removeIf(end -> end <= now);
    }

    /** One colour-stripped chat line, from the module's chat hook. */
    public void onChat(String text) {
        SBSConfig.KuudraSettings cfg = cfg();
        KuudraTracker tracker = KuudraTracker.getInstance();
        if (!cfg.enabled || !tracker.running()) {
            return;
        }
        if (text.contains(SELF_FRESH)) {
            onSelfFresh(cfg, tracker);
            return;
        }
        Matcher party = PARTY_FRESH.matcher(text);
        if (party.find()) {
            mark(party.group(1));
            tracker.log("{} is fresh (announced)", party.group(1));
        }
    }

    private void onSelfFresh(SBSConfig.KuudraSettings cfg, KuudraTracker tracker) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        String name = player.getGameProfile().name();
        mark(name);
        int percent = tracker.buildPercent();
        tracker.log("own fresh at {}% build", percent);
        if (cfg.freshAlert) {
            KuudraAlert.getInstance().flash("FRESH!", 0xFF7CFF6A, cfg.freshSound, 2.0f);
        }
        // Only worth telling the party while there is still a build to speed up.
        if (cfg.freshToParty && tracker.phase() == KuudraPhase.BUILD) {
            KuudraSay.party("FRESH! (" + percent + "%)");
        }
    }

    private void mark(String player) {
        until.put(player.toLowerCase(Locale.ROOT), System.currentTimeMillis() + FRESH_MS);
    }
}
