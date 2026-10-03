/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.model.FishingData.TrophyFish;
import sbs.modid.client.skills.trophyfish.model.TrophyTier;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feeds the Trophy Fish tracker: reads Odger's Trophy Fishing menu when it is open (read-only, never
 * clicks) and counts catches from chat, raising the new-tier / Diamond alerts.
 *
 * <p><b>Probe logging runs even with the feature off.</b> Both sources are unverified, so every
 * trophy-shaped line that does not parse, and every menu item that could not be read, is logged
 * once under {@code [SBS][Trophy]}. That costs a string check per chat line and nothing per frame,
 * and it turns the first real visit into the capture this feature is waiting for.
 */
public final class TrophyFishTracker {

    private static final TrophyFishTracker INSTANCE = new TrophyFishTracker();

    private final TrophyFishSession session = new TrophyFishSession();
    private final Set<String> loggedLines = new HashSet<>();
    private final Set<String> loggedMenuItems = new HashSet<>();

    private AbstractContainerScreen<?> lastScreen;
    private int lastState = -1;

    private TrophyFishTracker() {
    }

    public static TrophyFishTracker getInstance() {
        return INSTANCE;
    }

    public TrophyFishSession session() {
        return session;
    }

    private static SBSConfig.TrophyFishSettings cfg() {
        return ConfigManager.getInstance().get().trophyFish;
    }

    // ------------------------------------------------------------------ chat

    /** Every incoming chat line, as plain text. */
    public void onChat(String text) {
        String plain = PlainText.strip(text).trim();
        if (!TrophyCatchParser.looksLikeTrophyLine(plain)) {
            return;
        }
        TrophyCatchParser.Catch caught = TrophyCatchParser.parse(plain);
        if (caught == null) {
            if (loggedLines.size() < 50 && loggedLines.add(plain)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Trophy] trophy line not parsed: \"{}\"", plain);
            }
            return;
        }
        SBSConfig.TrophyFishSettings cfg = cfg();
        if (!cfg.enabled || !cfg.chatCounting) {
            return;
        }
        String key = caught.fish().apiKey();
        session.record(key, caught.tier(), System.currentTimeMillis());
        TrophyFishStore store = TrophyFishStore.getInstance();
        boolean synced = store.syncedAt() > 0;
        int before = store.recordCatch(key, caught.tier());
        alert(cfg, caught, synced && before == 0);
    }

    private static void alert(SBSConfig.TrophyFishSettings cfg, TrophyCatchParser.Catch caught,
                              boolean firstOfTier) {
        boolean highTier = caught.tier().ordinal() >= TrophyTier.GOLD.ordinal();
        String what = caught.fish().name() + " " + caught.tier().label();
        if (cfg.alertNewTier && firstOfTier && highTier) {
            Alerts.send(new Alerts.Alert("New trophy tier: " + what,
                    "First " + what + " on this profile.", SbsAudio.Tone.ALARM, null), cfg.alertChannels);
        } else if (cfg.alertAnyDiamond && caught.tier() == TrophyTier.DIAMOND) {
            Alerts.send(new Alerts.Alert("Diamond trophy: " + caught.fish().name(),
                    "You caught a Diamond " + caught.fish().name() + ".", SbsAudio.Tone.CHIME, null),
                    cfg.alertChannels);
        }
    }

    // ------------------------------------------------------------------ menu

    /** Game tick: re-reads the open Trophy Fishing menu whenever the server changes its contents. */
    public void tick(Minecraft minecraft) {
        if (!(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()
                instanceof AbstractContainerScreen<?> screen)) {
            lastScreen = null;
            return;
        }
        int state = screen.getMenu().getStateId();
        if (screen == lastScreen && state == lastState) {
            return;
        }
        lastScreen = screen;
        lastState = state;
        if (!TrophyMenuParser.isTrophyMenu(MenuFrame.of(screen).normalised())) {
            return;
        }
        Map<String, int[]> rows = read(screen);
        SBSConfig.TrophyFishSettings cfg = cfg();
        if (cfg.enabled && !rows.isEmpty()) {
            TrophyFishStore.getInstance().recordMenu(rows);
        }
    }

    private Map<String, int[]> read(AbstractContainerScreen<?> screen) {
        Map<String, int[]> rows = new LinkedHashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = PlainText.strip(stack.getHoverName().getString()).trim();
            TrophyFish fish = FishingData.trophyByName(name);
            List<String> lore = new ArrayList<>();
            for (String line : ItemPriceKey.lore(stack)) {
                lore.add(PlainText.strip(line).trim());
            }
            int[] counts = fish == null ? null : TrophyMenuParser.counts(lore);
            if (counts != null) {
                rows.put(fish.apiKey(), counts);
            } else if (!name.isEmpty() && loggedMenuItems.size() < 60 && loggedMenuItems.add(name)) {
                // Frame panes and buttons land here too; the log is the capture, so keep them.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Trophy] menu slot {} not read: name=\"{}\" "
                        + "fish={} lore={}", slot.index, name, fish == null ? "-" : fish.baseId(), lore);
            }
        }
        if (!rows.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Trophy] read {} fish from the Trophy Fishing menu",
                    rows.size());
        }
        return rows;
    }
}
