/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.farming.model.CropMilestones;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.economy.prices.BazaarPriceCache;

/**
 * The Crop Milestone card: which crop you are farming, the tier you are on, how far into it you
 * are, how fast you are going and when the next milestone lands.
 *
 * <p><b>Where the numbers come from.</b> The tier and the position inside it are anchored by
 * {@link CropMilestones} from the Crop Milestones menu. The <i>speed</i> comes from the tool's own
 * counter – the {@code mined_crops} tag a Mathematical Hoe keeps, or the Cultivating enchantment's
 * {@code farmed_cultivating} – read once per tick and diffed. That is why full accuracy needs a
 * counter or a Cultivating tool: without one there is no exact, cheap, lag-immune source for "how
 * many crops did I just break", and guessing from block breaks would silently under-count every
 * Dicer and Chopper multi-drop.
 *
 * <p>The card hides itself when no counter has moved recently, so it is only on screen while you
 * are actually farming.
 */
public final class CropMilestoneTracker {

    private static final CropMilestoneTracker INSTANCE = new CropMilestoneTracker();

    /** Hide once the counter has been still this long – you stopped farming. */
    private static final long IDLE_HIDE_MS = 30_000L;
    /** Rate window: long enough to smooth a lag spike, short enough to react to a speed change. */
    private static final long RATE_WINDOW_MS = 60_000L;
    /** A jump larger than this is a tool swap or a server correction, not crops you broke. */
    private static final long SANE_DELTA = 100_000L;

    private static final int SAMPLES = 64;

    /** Ring buffer of (timestamp, cumulative session crops) used for the crops/min figure. */
    private final long[] sampleTime = new long[SAMPLES];
    private final double[] sampleCrops = new double[SAMPLES];
    private int sampleHead = -1;
    private int sampleCount;

    private volatile CropType crop;
    private volatile double sessionCrops;
    private volatile long lastGainAt;
    /** The tool the baseline belongs to; a different tool re-anchors instead of counting the gap. */
    private String counterToolKey = "";
    private long lastCounter = -1;

    private CropMilestoneTracker() {
    }

    public static CropMilestoneTracker getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    /** The crop currently being farmed, or {@code null}. Shared with the fortune / hoe displays. */
    public CropType currentCrop() {
        return crop;
    }

    /** Crops broken this session, as counted off the tool counter. */
    public double sessionCrops() {
        return sessionCrops;
    }

    /** Crops per minute over the last minute, or {@code 0} when there is not enough history. */
    public double cropsPerMinute() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (sampleCount < 2) {
                return 0;
            }
            long oldestTime = 0;
            double oldestCrops = 0;
            boolean found = false;
            for (int i = 0; i < sampleCount; i++) {
                int index = Math.floorMod(sampleHead - i, SAMPLES);
                if (now - sampleTime[index] <= RATE_WINDOW_MS) {
                    oldestTime = sampleTime[index];
                    oldestCrops = sampleCrops[index];
                    found = true;
                } else {
                    break;
                }
            }
            if (!found) {
                return 0;
            }
            double elapsedMinutes = (sampleTime[sampleHead] - oldestTime) / 60_000.0;
            if (elapsedMinutes <= 0.05) {
                return 0;   // under three seconds of history is noise, not a rate
            }
            return (sampleCrops[sampleHead] - oldestCrops) / elapsedMinutes;
        }
    }

    // ------------------------------------------------------------------ capture

    /** Called every client tick: diff the held tool's crop counter. */
    public void onClientTick() {
        CropMilestones.getInstance().onClientTick();
        if (!cfg().cropMilestone && !cfg().farmingFortune) {
            return;   // both displays are off - nothing needs the counter
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        CropType heldCrop = CropType.forTool(held);
        if (heldCrop != null) {
            crop = heldCrop;
        }
        long counter = counterOf(held);
        if (counter < 0) {
            lastCounter = -1;   // no counter on this tool; re-anchor when one comes back
            counterToolKey = "";
            return;
        }
        String key = toolKey(held);
        if (!key.equals(counterToolKey)) {
            counterToolKey = key;
            lastCounter = counter;   // new tool: anchor, never count its lifetime total as a gain
            return;
        }
        long delta = counter - lastCounter;
        lastCounter = counter;
        if (delta <= 0 || delta > SANE_DELTA) {
            return;
        }
        long now = System.currentTimeMillis();
        sessionCrops += delta;
        lastGainAt = now;
        if (heldCrop != null) {
            CropMilestones.getInstance().advance(heldCrop, delta);
        }
        synchronized (this) {
            sampleHead = sampleHead < 0 ? 0 : (sampleHead + 1) % SAMPLES;
            sampleTime[sampleHead] = now;
            sampleCrops[sampleHead] = sessionCrops;
            sampleCount = Math.min(SAMPLES, sampleCount + 1);
        }
    }

    /** The tool's crop counter: the hoe's own counter, or the Cultivating enchantment's. */
    private static long counterOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        CompoundTag extra = sbs.modid.client.core.item.SkyblockItem.extraAttributes(stack);
        long mined = extra.getLongOr("mined_crops", -1);
        long cultivating = extra.getLongOr("farmed_cultivating", -1);
        return Math.max(mined, cultivating);
    }

    /** Identity of the held tool, so a swap re-anchors the counter instead of counting the gap. */
    private static String toolKey(ItemStack stack) {
        CompoundTag extra = sbs.modid.client.core.item.SkyblockItem.extraAttributes(stack);
        String uuid = extra.getStringOr("uuid", "");
        return uuid.isEmpty() ? extra.getStringOr("id", "") : uuid;
    }

    // ------------------------------------------------------------------ render

    /** Drawn from the HUD pass; self-hiding while the counter is idle. */
    public void render(GuiGraphicsExtractor g) {
        if (!cfg().cropMilestone || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.CROP_MILESTONE)
                || !sbs.modid.client.skills.SkillIslands.farmingAllowed()) {
            return;
        }
        CropType active = crop;
        long now = System.currentTimeMillis();
        if (active == null || lastGainAt == 0 || now - lastGainAt > IDLE_HIDE_MS) {
            return;
        }
        CropMilestones.Progress state = CropMilestones.getInstance().get(active);
        double perMinute = cropsPerMinute();

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        int barH = 4;

        String header = state != null && state.tier() > 0
                ? active.displayName() + "  Tier " + state.tier()
                : active.displayName();
        double fraction = state == null ? -1 : state.fraction();
        String percentText = fraction >= 0 ? String.format(java.util.Locale.ROOT, "%.1f%%", fraction * 100) : null;

        java.util.List<String[]> rows = new java.util.ArrayList<>(4);
        if (state != null && state.remaining() >= 0) {
            rows.add(new String[]{"Left", FarmingText.shortNumber(state.remaining())});
        }
        rows.add(new String[]{"Crops/min", FarmingText.shortNumber(perMinute)});
        if (cfg().milestoneEta && state != null && state.remaining() >= 0 && perMinute > 0) {
            long etaMs = (long) (state.remaining() / perMinute * 60_000.0);
            rows.add(new String[]{"ETA", FarmingText.duration(etaMs)});
        }
        if (cfg().milestoneProfit) {
            BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().get(active.bazaarId());
            if (price != null && price.sell() > 0 && perMinute > 0) {
                rows.add(new String[]{"Coins/h", FarmingText.coins(perMinute * 60 * price.sell())});
            }
        }

        int contentW = font.width(header);
        if (percentText != null) {
            contentW = Math.max(contentW, font.width(header) + 10 + font.width(percentText));
        }
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(126, contentW + pad * 2);
        int height = pad * 2 + lineH * (1 + rows.size()) + (fraction >= 0 ? barH + 3 : 0) - 2;

        HudElement.Bounds b = HudElement.CROP_MILESTONE.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.CROP_MILESTONE, x, y, width, height);

        HudLayout.begin(g, HudElement.CROP_MILESTONE);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        if (percentText != null) {
            g.text(font, Component.literal(percentText), right - font.width(percentText), iy,
                    SBSTheme.TEXT_MUTED);
        }
        iy += lineH;
        if (fraction >= 0) {
            int barW = width - pad * 2;
            SciFiRender.roundedRect(g, ix, iy, barW, barH, 1, SBSTheme.PANEL_BORDER);
            int filled = (int) Math.round(barW * Math.max(0, Math.min(1, fraction)));
            if (filled > 0) {
                SciFiRender.roundedRect(g, ix, iy, filled, barH, 1, SBSTheme.ACCENT_BRIGHT);
            }
            iy += barH + 3;
        }
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy,
                    row[0].startsWith("Coins") ? 0xFF57D977 : SBSTheme.TEXT);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** Drops the session counters (used by the "Reset Session" button). */
    public synchronized void reset() {
        sessionCrops = 0;
        lastGainAt = 0;
        sampleHead = -1;
        sampleCount = 0;
        lastCounter = -1;
        counterToolKey = "";
    }
}
