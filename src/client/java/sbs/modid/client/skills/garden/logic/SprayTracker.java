/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.skills.garden.model.SprayMaterial;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Sprayonator is loaded with, what is currently sprayed, for how much longer, and what was
 * sprayed before that.
 *
 * <p>Three sources, each for the thing it is actually authoritative about:
 * <ul>
 *   <li><b>The Pests widget's "Spray" row</b> ({@link PestTracker#statusLine}) – whether a spray is
 *       running and how long it has left. Server-owned, so it stays right across a relog, which a
 *       locally started countdown would not.</li>
 *   <li><b>The Sprayonator in your inventory</b> – the selected material, read off its
 *       "Selected Material:" lore line. This is what a spray you fire off <i>will</i> be.</li>
 *   <li><b>The config</b> – the previous spray, so "what did I last use" survives a restart. That is
 *       the whole reason to record it: within one session you remember anyway.</li>
 * </ul>
 *
 * <p>The widget row's exact wording is not assumed. Anything it carries is read out of it - a
 * material name, a plot number, a remaining time in either {@code mm:ss} or {@code 24m 12s} form -
 * and whatever it omits falls back: the material to the Sprayonator's current selection at the
 * moment the spray appeared, the duration to Hypixel's flat 30 minutes.
 */
public final class SprayTracker {

    private static final SprayTracker INSTANCE = new SprayTracker();

    /** The widget only moves once a second; matching that is plenty. */
    private static final long SCAN_INTERVAL_MS = 1_000L;
    /** A spray lasts 30 minutes - the fallback when the widget states no time. */
    private static final long SPRAY_DURATION_MS = 30L * 60_000L;

    /** "24:31" - a clock-style remainder. */
    private static final Pattern CLOCK = Pattern.compile("(\\d+):(\\d{2})");
    /** "24m 12s", "1h 5m" - a unit-style remainder, summed over every part present. */
    private static final Pattern UNITS = Pattern.compile("(?i)(\\d+)\\s*([hms])\\b");
    /** "Plot 4", "Plot - 4". */
    private static final Pattern PLOT = Pattern.compile("(?i)plot\\s*[-–]?\\s*(\\d+)");

    private volatile SprayMaterial selected;
    /** Whether a spray is running at all - kept apart from {@link #active}, which may be unknown. */
    private volatile boolean spraying;
    private volatile SprayMaterial active;
    private volatile String activePlot = "";
    private volatile long activeEndsAt;

    private long lastScanAt;

    private SprayTracker() {
    }

    public static SprayTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SprayonatorSettings cfg() {
        return ConfigManager.getInstance().get().sprayonator;
    }

    // ------------------------------------------------------------------ reading

    /** Called every client tick; does its work once a second while the card is on. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        selected = readSelectedMaterial();
        readSprayRow(now);
    }

    /**
     * The material the Sprayonator is set to, from the "Selected Material: Compost" lore line of the
     * first Sprayonator found in the inventory.
     *
     * <p>Matched on the id <i>fragment</i>, so the Juicy and Salty variants count as well without
     * listing them. The lore line is found by its material name rather than its label - the label is
     * the part that gets reworded, and the Sprayonator's lore names exactly one material.
     */
    private static SprayMaterial readSelectedMaterial() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = SkyblockItem.extraAttributes(stack).getStringOr("id", "")
                    .toUpperCase(Locale.ROOT);
            if (!id.contains("SPRAYONATOR")) {
                continue;
            }
            for (String line : FarmingText.lore(stack)) {
                SprayMaterial material = SprayMaterial.inText(line);
                if (material != null) {
                    return material;
                }
            }
        }
        return null;
    }

    /**
     * Folds the widget's spray row into the running-spray state: starts one when the row goes
     * active, keeps its remaining time in step with the server's, and retires it to "last sprayed"
     * when the row goes quiet or names a different material.
     */
    private void readSprayRow(long now) {
        String raw = PestTracker.getInstance().statusLine("spray");
        if (raw == null) {
            return;   // no widget in reach - keep what we know rather than dropping it
        }
        if (inactive(raw)) {
            retire(now);
            return;
        }
        // The row names the material on layouts that carry it; where it does not, the spray can only
        // be the one the Sprayonator was pointed at when it appeared.
        SprayMaterial material = SprayMaterial.inText(raw);
        if (material == null) {
            material = active != null ? active : selected;
        }
        if (active != null && material != null && material != active) {
            retire(now);   // re-sprayed with something else: the old one becomes the last one
        }
        long remaining = parseRemaining(raw);
        active = material;
        spraying = true;
        activeEndsAt = remaining > 0 ? now + remaining
                : (activeEndsAt > now ? activeEndsAt : now + SPRAY_DURATION_MS);
        Matcher plot = PLOT.matcher(raw);
        activePlot = plot.find() ? plot.group(1) : "";
    }

    /**
     * Ends the running spray: the material moves into the persisted "last sprayed" slot, if we ever
     * worked out what it was. A spray whose material stayed unknown is still cleared - it simply has
     * nothing worth remembering.
     */
    private void retire(long now) {
        if (active != null) {
            SBSConfig.SprayonatorSettings cfg = cfg();
            cfg.lastMaterial = active.itemId();
            cfg.lastSprayedAt = now;
            ConfigManager.getInstance().save();
        }
        active = null;
        spraying = false;
        activePlot = "";
        activeEndsAt = 0;
    }

    /** Whether the widget's spray row means "nothing is sprayed". */
    private static boolean inactive(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        return lower.isEmpty() || lower.contains("none") || lower.contains("inactive")
                || lower.contains("no spray");
    }

    /**
     * The remaining time the row states, in millis, or {@code 0} when it states none. Clock form
     * ({@code 24:31}) wins over unit form, since a row carrying both would only be writing the same
     * number twice.
     */
    private static long parseRemaining(String raw) {
        Matcher clock = CLOCK.matcher(raw);
        if (clock.find()) {
            return (Long.parseLong(clock.group(1)) * 60L + Long.parseLong(clock.group(2))) * 1000L;
        }
        long total = 0;
        Matcher units = UNITS.matcher(raw);
        while (units.find()) {
            long value = Long.parseLong(units.group(1));
            total += switch (units.group(2).toLowerCase(Locale.ROOT)) {
                case "h" -> value * 3_600_000L;
                case "m" -> value * 60_000L;
                default -> value * 1_000L;
            };
        }
        return total;
    }

    // ------------------------------------------------------------------ state

    /** The material the Sprayonator is loaded with, or {@code null} when none is carried. */
    public SprayMaterial selected() {
        return selected;
    }

    /**
     * Whether a spray is running, which is known even on the frames the material is not: the widget
     * row states the time either way, and the countdown is the number worth showing.
     */
    public boolean spraying() {
        return spraying;
    }

    /** The material currently sprayed on a plot, or {@code null} when nothing is (or it is unknown). */
    public SprayMaterial active() {
        return active;
    }

    /** The sprayed plot's number, or {@code ""} when the widget did not name one. */
    public String activePlot() {
        return activePlot;
    }

    /** Millis left on the running spray, {@code 0} when none is running. */
    public long remainingMs() {
        return spraying ? Math.max(0, activeEndsAt - System.currentTimeMillis()) : 0;
    }

    /** The previously sprayed material, or {@code null} when nothing was ever recorded. */
    public SprayMaterial lastMaterial() {
        return SprayMaterial.byId(cfg().lastMaterial);
    }

    /** When the previous spray was retired, as epoch millis ({@code 0} = never). */
    public long lastSprayedAt() {
        return cfg().lastSprayedAt;
    }

    /** Every material, for the settings page's reference list. */
    public static List<SprayMaterial> materials() {
        return SprayMaterial.all();
    }
}
