/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the game knows about your crop milestones, read straight out of the <b>Crop Milestones</b>
 * menu and then carried forward live by the tool counter.
 *
 * <p><b>Why the menu and not a bundled table.</b> Shipping the per-tier crop requirements as data
 * means being wrong for as long as it takes to update that data after Hypixel touches the
 * milestones. The menu is the game's own answer and is never out of date: opening it
 * once per session anchors every crop, and {@link #advance} then counts along from the hoe's
 * counter, exactly like the Collection Tracker anchors on the Collection widget.
 *
 * <p><b>What survives an unparsable menu.</b> Each field is captured independently and unknown
 * fields stay {@code -1}, so a lore rewording that hides the absolute counts still leaves the tier
 * and the percentage usable – the milestone card simply drops the ETA row. Every capture logs a
 * {@code [SBS][Milestone]} line with the raw lore so the patterns can be tuned against the live
 * menu.
 */
public final class CropMilestones {

    private static final CropMilestones INSTANCE = new CropMilestones();

    private static final long SCAN_INTERVAL_MS = 250L;

    /** "Tier 25", "Tier IV" is never used by Hypixel here – always digits. */
    private static final Pattern TIER = Pattern.compile("(?i)tier\\s+(\\d+)");
    /** "45.7%" anywhere in a lore line. */
    private static final Pattern PERCENT = Pattern.compile("([\\d.,]+)\\s*%");
    /** "12,345/50,000" or "1.2K / 3.4K". */
    private static final Pattern FRACTION = Pattern.compile(
            "([\\d][\\d.,]*[kKmMbB]?)\\s*/\\s*([\\d][\\d.,]*[kKmMbB]?)");

    /** One crop's milestone state. {@code -1} on a field means "the menu did not say". */
    public record Progress(int tier, double percent, double have, double need, long capturedAt) {

        /** The fraction into the current tier, 0-1, or {@code -1} when nothing is known. */
        public double fraction() {
            if (have >= 0 && need > 0) {
                return Math.min(1.0, have / need);
            }
            return percent >= 0 ? Math.min(1.0, percent / 100.0) : -1;
        }

        /** Crops still needed for the next tier, or {@code -1} when the counts are unknown. */
        public double remaining() {
            return have >= 0 && need > 0 ? Math.max(0, need - have) : -1;
        }
    }

    private final Map<CropType, Progress> progress = new EnumMap<>(CropType.class);
    private long lastScanAt;
    private long lastLogAt;

    private CropMilestones() {
    }

    public static CropMilestones getInstance() {
        return INSTANCE;
    }

    /** The captured state for a crop, or {@code null} while the menu has never been opened. */
    public synchronized Progress get(CropType crop) {
        return crop == null ? null : progress.get(crop);
    }

    /**
     * Counts {@code crops} more towards the crop's current tier, so the card keeps moving between
     * menu visits. A tier that fills up rolls over locally; the next menu open re-anchors it.
     */
    public synchronized void advance(CropType crop, double crops) {
        Progress current = progress.get(crop);
        if (current == null || crops <= 0 || current.have() < 0 || current.need() <= 0) {
            return;
        }
        double have = current.have() + crops;
        int tier = current.tier();
        // A local roll-over keeps the percentage honest; the tier size of the NEXT tier is unknown
        // until the menu is reopened, so the size is carried over rather than guessed.
        while (have >= current.need() && current.need() > 0) {
            have -= current.need();
            tier++;
        }
        progress.put(crop, new Progress(tier, have / current.need() * 100.0, have, current.need(),
                current.capturedAt()));
    }

    // ------------------------------------------------------------------ capture

    /** Called every client tick; reads the open Crop Milestones menu (throttled). */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        if (!(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()
                instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        String title = screen.getTitle() == null ? ""
                : FarmingText.strip(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        if (!title.contains("milestone")) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            CropType crop = CropType.forText(FarmingText.name(stack));
            if (crop == null) {
                continue;
            }
            Progress parsed = parse(crop, stack, now);
            if (parsed != null) {
                synchronized (this) {
                    progress.put(crop, parsed);
                }
                log.append(crop.displayName()).append("=T").append(parsed.tier());
                if (parsed.have() >= 0) {
                    log.append(' ').append((long) parsed.have()).append('/').append((long) parsed.need());
                } else if (parsed.percent() >= 0) {
                    log.append(' ').append(parsed.percent()).append('%');
                }
                log.append("  ");
            }
        }
        if (log.length() > 0 && now - lastLogAt > 5_000L) {
            lastLogAt = now;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Milestone] {}", log.toString().trim());
        }
    }

    /**
     * Reads one crop's slot. The tier comes from the stack count when Hypixel sets it (it is the
     * tier there) and from the lore otherwise; progress is taken from an explicit {@code a/b} when
     * present, and from the percentage when not.
     */
    private static Progress parse(CropType crop, ItemStack stack, long now) {
        int tier = -1;
        double percent = -1;
        double have = -1;
        double need = -1;

        String name = FarmingText.name(stack);
        Matcher nameTier = TIER.matcher(name);
        if (nameTier.find()) {
            tier = Integer.parseInt(nameTier.group(1));
        }
        List<String> lore = FarmingText.lore(stack);
        for (String line : lore) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (tier < 0 || lower.contains("progress to tier")) {
                Matcher m = TIER.matcher(line);
                if (m.find()) {
                    // "Progress to Tier N" names the NEXT tier; the current one is N-1.
                    int found = Integer.parseInt(m.group(1));
                    tier = lower.contains("progress to tier") ? found - 1 : found;
                }
            }
            if (have < 0) {
                Matcher m = FRACTION.matcher(line);
                if (m.find()) {
                    have = FarmingText.parseNumber(m.group(1));
                    need = FarmingText.parseNumber(m.group(2));
                }
            }
            if (percent < 0) {
                Matcher m = PERCENT.matcher(line);
                if (m.find()) {
                    percent = FarmingText.parseNumber(m.group(1));
                }
            }
        }
        if (tier < 0 && stack.getCount() > 1) {
            tier = stack.getCount();   // Hypixel stacks the milestone item to its tier
        }
        if (tier < 0 && percent < 0 && have < 0) {
            return null;   // nothing readable - keep whatever was captured before
        }
        return new Progress(Math.max(0, tier), percent, have, need, now);
    }
}
