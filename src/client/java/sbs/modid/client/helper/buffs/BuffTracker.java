/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.buffs.consumables.logic.ConsumableCapture;
import sbs.modid.client.helper.buffs.consumables.logic.ConsumableStore;
import sbs.modid.client.helper.buffs.consumables.logic.EffectsFooter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The God Potion and Booster Cookie timers, read off the tab list.
 *
 * <p><b>Why the text is shown verbatim.</b> Hypixel publishes these as coarse prose - "4 hours",
 * "3 years, 2 months" - and never a precise expiry. Counting down from that would invent seconds the
 * server never sent, and a timer that is confidently wrong is worse than one that is honestly vague.
 * So the widget's own wording is what the HUD shows, refreshed as the server changes it.
 *
 * <p><b>Where they actually are.</b> Not in the fake player entries the Garden widgets use, but in
 * the tab list's <b>footer</b> - the block below the player columns that reads "Active Effects / You
 * have a God Potion active! 2 hours / ... / Cookie Buff / 3 years, 2 months". Scanning only the
 * widget lines is why these cards used to stay empty forever. The widget lines are still scanned
 * too: they cost nothing and keep the reader working should Hypixel ever move the block.
 */
public final class BuffTracker {

    private static final BuffTracker INSTANCE = new BuffTracker();

    /** The tab list barely changes; twice a second is already generous. */
    private static final long SCAN_INTERVAL_MS = 2_000L;
    /** Drop the values once the tab has not shown them for this long (left SkyBlock, say). */
    private static final long HIDE_AFTER_MS = 30_000L;

    private volatile String godPotion;
    private volatile String cookieBuff;
    private volatile long dataSeenAt;

    private long lastScanAt;
    private long lastLogAt;

    private BuffTracker() {
    }

    public static BuffTracker getInstance() {
        return INSTANCE;
    }

    /** Called every client tick; throttles itself. */
    public void onClientTick() {
        // Cake buff timers: expiry + alert. Consumable timers: their clock and alerts, and the
        // capture of the footer, the Active Effects menu and used items' lore.
        CakeBuffStore.getInstance().onClientTick();
        ConsumableCapture.getInstance().onClientTick();
        ConsumableStore.getInstance().onClientTick();
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        scanTab(now);
    }

    private void scanTab(long now) {
        // Footer first: it is where Hypixel puts both buffs, and it keeps the "header line, then its
        // value" adjacency nextValue() relies on intact within each source.
        List<String> footer = TabWidgets.footerLines();
        List<String> lines = new ArrayList<>(footer);
        lines.addAll(TabWidgets.lines());
        // One parser for this footer: the consumable timers read it through the same class. They
        // get the footer alone - the widget lines carry nothing they could use.
        ConsumableStore.getInstance().onFooter(EffectsFooter.parse(footer), now);
        EffectsFooter.Reading reading = EffectsFooter.parse(lines);
        String god = reading.godPotionText();
        String cookie = reading.cookieText();

        if (god != null || cookie != null) {
            godPotion = god;
            cookieBuff = cookie;
            dataSeenAt = now;
        } else if (dataSeenAt != 0 && now - dataSeenAt > HIDE_AFTER_MS) {
            godPotion = null;
            cookieBuff = null;
        }

        // Tuning aid, same idea as the pest widget log: only complain when the tab clearly has an
        // effects widget but neither value could be read, and never more than once every 30s.
        if (god == null && cookie == null && now - lastLogAt > 30_000L) {
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("effect") || lower.contains("cookie") || lower.contains("potion")) {
                    lastLogAt = now;
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Buffs] unparsed tab lines={}", lines);
                    break;
                }
            }
        }
    }

    /** The God Potion's remaining time as the tab words it, or {@code null} when none is active. */
    public String godPotion() {
        return fresh() ? godPotion : null;
    }

    /** The Booster Cookie's remaining time as the tab words it, or {@code null} when none is active. */
    public String cookieBuff() {
        return fresh() ? cookieBuff : null;
    }

    private boolean fresh() {
        return dataSeenAt != 0 && System.currentTimeMillis() - dataSeenAt <= HIDE_AFTER_MS;
    }
}
