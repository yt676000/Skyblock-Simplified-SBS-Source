/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.abilitytimers.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.cooldowns.AbilityCooldowns;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.texture.logic.SkyblockItemModels;

import java.util.Locale;

/**
 * Feeds {@link TacTimer} from the game - the use hook, the action bar and chat - and owns the text
 * the HUD draws. Client thread only.
 *
 * <p><b>The probe.</b> The use signal and the real delay to the return are not yet confirmed in
 * game (see {@code docs/features/tac-timer.md}). Every Tactical Insertion click logs the first
 * action bar and chat line within 500 ms, and the delay from the click to the hit line, as
 * {@code [SBS][Tac]} - one session settles both. Only a click with this one item arms it.
 */
public final class TacTimerTracker {

    private static final TacTimerTracker INSTANCE = new TacTimerTracker();

    private static final long PROBE_MS = 500L;
    /** The display text is rebuilt at most this often - 0.1 s is all it shows. */
    private static final long TEXT_STEP_MS = 100L;
    /** After this long a click has nothing left to wait for: action bar and chat stop being read. */
    private static final long LISTEN_MS = 6_000L;

    private final TacTimer timer = new TacTimer();

    private long probeAt = -1;
    private String probeBar;
    private String probeChat;
    private long probeAbilityBarMs = -1;
    private long lastUseAt = -1;

    private long textStep = -1;
    private String text;

    private TacTimerTracker() {
    }

    public static TacTimerTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.AbilityTimerSettings cfg() {
        return ConfigManager.getInstance().get().abilityTimers;
    }

    /**
     * Called from the use hook <b>before</b> {@link AbilityCooldowns#onItemUse}, which starts the
     * 20 s cooldown on this very click - asked afterwards it would always say "on cooldown".
     */
    public void onItemUse(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !TacTimer.ITEM_ID.equals(SkyblockItemModels.skyblockId(stack))) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean onCooldown = AbilityCooldowns.getInstance().remainingSeconds(stack) > 0;
        flushProbe();
        probeAt = now;
        probeBar = null;
        probeChat = null;
        probeAbilityBarMs = -1;
        lastUseAt = now;
        boolean started = cfg().tacTimer && timer.onUse(now, onCooldown);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Tac] use onCooldown={} started={}", onCooldown, started);
        textStep = -1;
    }

    /** Every action-bar message, colour-stripped (from {@code HypixelHudState.parseActionBar}). */
    public void onActionBar(String text) {
        long now = System.currentTimeMillis();
        if (lastUseAt < 0 || now - lastUseAt > LISTEN_MS) {
            return; // no recent click - the common case
        }
        if (probeAt >= 0 && now - probeAt <= PROBE_MS) {
            if (probeBar == null) {
                probeBar = text;
            }
            if (probeAbilityBarMs < 0 && text.contains("Gorilla")) {
                probeAbilityBarMs = now - probeAt;
            }
        }
        timer.onActionBar(text, now);
    }

    /** Every chat line; read only in the few seconds after a click. */
    public void onChat(String raw) {
        long now = System.currentTimeMillis();
        if (lastUseAt < 0 || now - lastUseAt > LISTEN_MS) {
            return;
        }
        if (probeAt >= 0 && now - probeAt <= PROBE_MS && probeChat == null) {
            probeChat = raw;
        }
        if (TacTimer.parseHits(raw) >= 0) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Tac] hit line {} ms after use: '{}'", now - lastUseAt, raw);
        }
        timer.onChat(raw, now);
        textStep = -1;
    }

    /** Client tick: writes the probe line once its window closed, forgets everything off-world. */
    public void onClientTick() {
        if (probeAt >= 0 && System.currentTimeMillis() - probeAt > PROBE_MS) {
            flushProbe();
        }
        if (Minecraft.getInstance().level == null && lastUseAt >= 0) {
            timer.reset();
            lastUseAt = -1;
            text = null;
        }
    }

    private void flushProbe() {
        if (probeAt < 0) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Tac] use → actionbar='{}' chat='{}' gorillaBarAfter={}ms",
                probeBar, probeChat, probeAbilityBarMs);
        probeAt = -1;
    }

    // ---- read by the HUD ----------------------------------------------------------------------

    /**
     * "Tac: 2.4s" while the clock runs, "Tac: hit 3" for a moment after the return (when enabled),
     * otherwise {@code null}. Rebuilt at most every 100 ms, not per frame.
     */
    public String text() {
        if (lastUseAt < 0 || !cfg().tacTimer) {
            return null;
        }
        long now = System.currentTimeMillis();
        long step = now / TEXT_STEP_MS;
        if (step != textStep) {
            textStep = step;
            text = build(now);
        }
        return text;
    }

    private String build(long now) {
        long left = timer.remainingMs(now);
        if (left > 0) {
            return String.format(Locale.US, "Tac: %.1fs", left / 1000.0);
        }
        int hits = timer.hitsShown(now);
        if (hits >= 0 && cfg().tacShowHits) {
            return "Tac: hit " + hits;
        }
        return null;
    }

    public void reset() {
        timer.reset();
        text = null;
        textStep = -1;
    }
}
