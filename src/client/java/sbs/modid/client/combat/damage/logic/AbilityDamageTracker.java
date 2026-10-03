/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.logic;

import sbs.modid.client.combat.damage.model.AbilityDamageMode;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's ability-damage lines – {@code Your Implosion hit 1 enemy for 1,394,599.3 damage.}
 * – and decides what happens to them, driven by the single {@link AbilityDamageMode} setting.
 *
 * <p><b>Two hooks, two jobs.</b> {@link #onChat} runs from the shared chat-parse mixin (at the public
 * {@code ChatComponent} entry points, like every other SBS tracker) and records the hit;
 * {@link #shouldHide} runs later, at the display funnel, and tells Better Chat's mixin to drop the
 * line. Recording therefore always happens before hiding, so a hidden hit still reaches the card.
 *
 * <p>Hits older than the configured hold time are dropped lazily on read – there is no ticker, the
 * HUD's own render pass is the only thing that ever needs the list.
 */
public final class AbilityDamageTracker {

    private static final AbilityDamageTracker INSTANCE = new AbilityDamageTracker();

    /**
     * "Your <ability> hit <n> enem(y|ies) for <damage> damage." – the ability name is lazy so it
     * stops at the first " hit ", and the damage keeps Hypixel's thousands separators and decimal.
     * Gaps are {@code \s+} because stripping Hypixel's colour codes out of the line can leave a
     * double space behind ({@code hit §a3 §c enemies}).
     */
    private static final Pattern ABILITY_DAMAGE = Pattern.compile(
            "^Your\\s+(.+?)\\s+hit\\s+(\\d+)\\s+enem(?:y|ies)\\s+for\\s+([\\d,]+(?:\\.\\d+)?)\\s+damage\\.?$");

    /** Hard cap on retained hits, independent of the configured line count. */
    private static final int MAX_RETAINED = 20;

    /** Newest first. Guarded by {@code this} – written from chat, read from the render thread. */
    private final Deque<Hit> hits = new ArrayDeque<>();

    private AbilityDamageTracker() {
    }

    public static AbilityDamageTracker getInstance() {
        return INSTANCE;
    }

    /** One recorded ability hit. */
    public record Hit(String ability, int enemies, double damage, long time) {
    }

    private static SBSConfig.AbilityDamageSettings cfg() {
        return ConfigManager.getInstance().get().abilityDamage;
    }

    /**
     * Chat-parse hook: records the hit while the HUD card is the chosen destination. In
     * {@link AbilityDamageMode#HIDDEN} there is nothing to show, so nothing is kept either.
     */
    public void onChat(String text) {
        if (cfg().mode != AbilityDamageMode.HUD) {
            return;
        }
        Matcher matcher = ABILITY_DAMAGE.matcher(strip(text));
        if (!matcher.matches()) {
            return;
        }
        double damage;
        try {
            damage = Double.parseDouble(matcher.group(3).replace(",", ""));
        } catch (NumberFormatException e) {
            return; // an unexpected number format is not worth a broken card
        }
        int enemies;
        try {
            enemies = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            enemies = 1;
        }
        Hit hit = new Hit(matcher.group(1), enemies, damage, System.currentTimeMillis());
        synchronized (this) {
            hits.addFirst(hit);
            while (hits.size() > MAX_RETAINED) {
                hits.removeLast();
            }
        }
    }

    /** Display-funnel hook: whether this line is an ability-damage line that should not be shown. */
    public boolean shouldHide(String text) {
        return cfg().mode != AbilityDamageMode.CHAT && ABILITY_DAMAGE.matcher(strip(text)).matches();
    }

    /** The hits still inside the hold window, newest first, capped to the configured line count. */
    public List<Hit> recent() {
        SBSConfig.AbilityDamageSettings cfg = cfg();
        long cutoff = System.currentTimeMillis() - Math.max(1, cfg.hudHoldSeconds) * 1000L;
        int limit = Math.max(1, cfg.hudLines);
        List<Hit> recent = new ArrayList<>(limit);
        synchronized (this) {
            hits.removeIf(hit -> hit.time() < cutoff);
            for (Hit hit : hits) {
                if (recent.size() >= limit) {
                    break;
                }
                recent.add(hit);
            }
        }
        return recent;
    }

    /** Drops every recorded hit (leaving a lobby / turning the card off). */
    public synchronized void clear() {
        hits.clear();
    }

    /** Drops legacy section-sign formatting so the pattern sees Hypixel's plain sentence. */
    private static String strip(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll(String.valueOf((char) 0x00A7) + ".", "").trim();
    }
}
