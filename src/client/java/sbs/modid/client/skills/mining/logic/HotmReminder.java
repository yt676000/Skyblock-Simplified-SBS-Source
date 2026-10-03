/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.model.HotmData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The HotM Upgrade Reminder at runtime: feeds {@link HotmReminders} the cached tree, the live powder
 * and the island edge once a second, delivers what it says through {@link Alerts}, and keeps the HUD
 * line's text.
 *
 * <p><b>Reads only.</b> Chat, the tab list and the HotM tree cache in; alerts and a HUD line out.
 * Nothing is clicked, opened or sent.
 *
 * <p><b>Capture.</b> Until the tier-up and purchase wordings are known (see {@link HotmChatLines}),
 * every chat line that looks related is logged once under {@code [SBS][Hotm] chat:}, and the tab's
 * powder totals under {@code [SBS][Hotm] powder:} when they change, at most every 10 seconds.
 */
public final class HotmReminder implements ProfileScopedStore {

    private static final HotmReminder INSTANCE = new HotmReminder();

    private static final long TICK_INTERVAL_MS = 1_000L;
    private static final long POWDER_LOG_INTERVAL_MS = 10_000L;
    /** The chat capture stops after this many distinct lines per session. */
    private static final int MAX_CAPTURED_LINES = 200;

    private final HotmReminders rules = new HotmReminders();
    private final Set<String> capturedLines = new HashSet<>();

    private long lastTickAt;
    private boolean wasOnMiningIsland;
    private Map<String, Long> lastLoggedPowder = Map.of();
    private long lastPowderLogAt;

    private volatile String hudLine = "";

    private HotmReminder() {
        // Registered from the constructor, per the profile-scoped-store contract.
        ProfileContext.getInstance().register(this);
    }

    public static HotmReminder getInstance() {
        return INSTANCE;
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick; does its work once a second. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < TICK_INTERVAL_MS) {
            return;
        }
        lastTickAt = now;
        MiningHelpersSettings settings = cfg();
        if (!settings.enabled || !settings.hotmReminder || Minecraft.getInstance().player == null) {
            hudLine = "";
            return;
        }
        HotmTreeStore store = HotmTreeStore.getInstance();
        Map<String, Long> tab = MiningTracker.getInstance().powder();
        if (!tab.isEmpty()) {
            store.notePowder(tab);
            logPowder(tab, now);
        }
        Map<String, Long> powder = HotmReminders.currentPowder(tab, store.lastPowder());

        boolean onMining = SkillIslands.onMiningIsland();
        if (onMining && !wasOnMiningIsland && settings.hotmReminderTokens) {
            deliver(rules.onEnterMiningIsland(store.tokens(), store.stale()));
        }
        wasOnMiningIsland = onMining;

        List<HotmReminders.Perk> perks = perks(store);
        if (SkillIslands.miningAllowed()) {
            for (HotmReminders.Notice notice : rules.evaluate(perks, store.watched(), powder, store.stale(),
                    settings.hotmReminderSummary, HotmTreeReader.menuOpen(), now)) {
                deliver(notice);
            }
        }
        hudLine = onMining ? hudText(store, perks, powder) : "";
    }

    /** The HUD line as of the last tick; empty when there is nothing to show. */
    public String hudLine() {
        return hudLine;
    }

    // ------------------------------------------------------------------ chat

    /** Every chat line, raw from the funnel. */
    public void onChat(String raw) {
        String plain = raw == null ? null : StyledText.strip(raw).trim();
        if (plain == null || plain.isEmpty()) {
            return;
        }
        if (HotmChatLines.isCaptureCandidate(plain) && capturedLines.size() < MAX_CAPTURED_LINES
                && capturedLines.add(plain)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] chat: \"{}\"", plain);
        }
        if (!HotmChatLines.isTierUp(plain)) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] tier-up line: \"{}\"", plain);
        HotmTreeStore.getInstance().invalidate("tier up: " + plain);
        MiningHelpersSettings settings = cfg();
        if (settings.enabled && settings.hotmReminder && settings.hotmReminderTokens) {
            deliver(rules.onTierUp());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The cached tree as the rules want it, by tier then name. Names come from the catalogue; a perk
     * the catalogue does not carry keeps the reader's slug.
     */
    public static List<HotmReminders.Perk> perks(HotmTreeStore store) {
        List<HotmReminders.Perk> out = new ArrayList<>(store.nodes().size());
        for (Map.Entry<String, HotmTreeStore.NodeState> entry : store.nodes().entrySet()) {
            HotmTreeStore.NodeState node = entry.getValue();
            out.add(new HotmReminders.Perk(entry.getKey(), displayName(entry.getKey()), node.level,
                    node.maxLevel, node.nextCost, node.nextPowder));
        }
        Map<String, Integer> tiers = new LinkedHashMap<>();
        store.nodes().forEach((id, node) -> tiers.put(id, node.tier));
        out.sort(Comparator.<HotmReminders.Perk>comparingInt(p -> tiers.getOrDefault(p.id(), 0))
                .thenComparing(HotmReminders.Perk::name));
        return out;
    }

    /** A perk's display name from the catalogue, or its id made readable. */
    public static String displayName(String perkId) {
        HotmData.Perk perk = HotmCatalog.byId(perkId);
        if (perk != null && perk.name != null && !perk.name.isBlank()) {
            return perk.name;
        }
        String spaced = perkId.replace('_', ' ');
        return spaced.isEmpty() ? perkId : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private String hudText(HotmTreeStore store, List<HotmReminders.Perk> perks, Map<String, Long> powder) {
        if (!store.known()) {
            return "";
        }
        if (store.stale()) {
            return "HotM: open /hotm to refresh";
        }
        int affordable = HotmReminders.affordableCount(perks, powder);
        int tokens = store.tokens();
        return "HotM: " + affordable + " affordable"
                + (tokens >= 0 ? " · " + tokens + " token" + (tokens == 1 ? "" : "s") : "");
    }

    private void logPowder(Map<String, Long> tab, long now) {
        if (tab.equals(lastLoggedPowder) || now - lastPowderLogAt < POWDER_LOG_INTERVAL_MS) {
            return;
        }
        lastPowderLogAt = now;
        lastLoggedPowder = Map.copyOf(tab);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] powder: {}", tab);
    }

    private void deliver(HotmReminders.Notice notice) {
        if (notice == null) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] reminder {}: {}", notice.kind(), notice.detail());
        Alerts.send(Alerts.Alert.of(notice.title(), notice.detail()), cfg().hotmReminderChannels);
    }

    @Override
    public void flushProfile() {
        // Nothing persisted here; the watched set and powder live in HotmTreeStore.
    }

    @Override
    public void reloadProfile() {
        rules.reset();
        wasOnMiningIsland = false;
        hudLine = "";
    }
}
