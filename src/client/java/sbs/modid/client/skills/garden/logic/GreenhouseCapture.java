/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Capture-only logger for the Greenhouse and its mutations, under {@code [SBS][Greenhouse]}.
 *
 * <p><b>Why it exists.</b> Nothing about the Greenhouse has been seen by this mod: the play logs hold
 * no greenhouse menu, tab row or mechanic chat line (only other players mentioning it, and an
 * attribute page titled "Greenhouse Speed"). Every mechanic in
 * {@code docs/features/greenhouse.md} is from the community wiki and ESTIMATED. Helpers get written
 * against what this logs, not against the wiki.
 *
 * <p><b>What it logs</b>, only while on the Garden and only with dev mode or the "Greenhouse Capture"
 * toggle on:
 * <ul>
 *   <li>every chat line with greenhouse / mutation wording ({@link #matches}), raw and stripped;</li>
 *   <li>every tab row with that wording, once per distinct text (a ticking timer logs each change);</li>
 *   <li>every menu title opened on the Garden - the greenhouse menus' names are unknown, so all of
 *       them, once per title per session;</li>
 *   <li>every zone change, which is how the Greenhouse's scoreboard zone name gets found.</li>
 * </ul>
 * Full menu contents are the Layout Recorder's and {@code /sbs probe arm}'s job; this does not
 * duplicate them - except the Crop Analyzer, which {@link CropAnalyzerCapture} logs frame by frame. Off, {@link #tick} and {@link #onChat} cost one boolean read.
 */
public final class GreenhouseCapture {

    private static final GreenhouseCapture INSTANCE = new GreenhouseCapture();

    private static final long TAB_MS = 1_000L;

    /**
     * Wording that marks a line as greenhouse business. Lower case, matched as substrings. The
     * mechanic words come from the wiki; the mutation names are the wiki's list (2026-09-27) and only
     * decide what gets logged, so a wrong or missing one costs a log line, not a feature.
     */
    static final String[] KEYWORDS = {
            "greenhouse", "mutation", "mutated", "crop analyzer", "plant diagnostic", "growth stage",
            "water level", "watering", "ethereal vine", "harvest bounty", "crop effect", "dna analysis",
            "unique crop", "compost bundle",
            "ashwreath", "choconut", "dustgrain", "gloomgourd", "lonelily", "scourroot", "shadevine",
            "veilshroom", "witherbloom", "chocoberry", "cindershade", "coalroot", "creambloom",
            "duskbloom", "thornshade", "blastberry", "cheesebite", "chloronite", "do-not-eat-shroom",
            "fleshtrap", "magic jellybean", "noctilume", "snoozling", "soggybud", "turtlellini",
            "chorus fruit", "plantboy advance", "puffercloud", "shellfruit", "startlevine",
            "stoplight petal", "thunderling", "zombud", "all-in aloe", "devourer", "glasscorn",
            "godseed", "jerryflower", "phantomleaf", "timestalk",
    };

    private long lastTabAt;
    private final Set<String> seenRows = new HashSet<>();
    private final Set<String> seenTitles = new HashSet<>();
    private Screen lastScreen;
    private String lastZone = "";

    private GreenhouseCapture() {
    }

    public static GreenhouseCapture getInstance() {
        return INSTANCE;
    }

    /** Whether a (colour-stripped or raw) line carries greenhouse wording. Pure, for the tests. */
    static boolean matches(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.replaceAll("§.", "").toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** The tab rows worth logging: matching ones not logged before. Pure over {@code seen}. */
    static List<String> newRows(List<String> tab, Set<String> seen) {
        List<String> out = new ArrayList<>();
        for (String row : tab) {
            if (matches(row) && seen.add(row)) {
                out.add(row);
            }
        }
        return out;
    }

    private static boolean enabled() {
        return DevMode.ACTIVE || ConfigManager.getInstance().get().gardenHelpers.greenhouseCapture;
    }

    /** The shared switch, also read by {@link CropAnalyzerCapture}. */
    static boolean enabledForCapture() {
        return enabled();
    }

    private static boolean onGarden() {
        return GardenBlueprintManager.inGarden();
    }

    /** Every chat line, as the plain text the chat fan-out hands over. */
    public void onChat(String raw) {
        if (!enabled() || !matches(raw) || !onGarden()) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Greenhouse] chat zone='{}' text='{}'",
                SkyBlockLocation.zone(), raw);
    }

    /** Client tick: zone changes, new menu titles, and (1 s throttle) tab rows. */
    public void tick() {
        if (!enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !onGarden()) {
            lastZone = "";
            return;
        }
        String zone = SkyBlockLocation.zone();
        if (!zone.equals(lastZone)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Greenhouse] zone '{}' -> '{}' island='{}' at {}",
                    lastZone, zone, SkyBlockLocation.island(), minecraft.player.blockPosition().toShortString());
            lastZone = zone;
        }
        Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        if (screen != lastScreen) {
            lastScreen = screen;
            if (screen != null) {
                String title = screen.getTitle().getString();
                if (seenTitles.add(title)) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Greenhouse] menu '{}' zone='{}'", title, zone);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastTabAt < TAB_MS) {
            return;
        }
        lastTabAt = now;
        for (String row : newRows(TabWidgets.lines(), seenRows)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Greenhouse] tab row '{}' zone='{}'", row, zone);
        }
    }
}
