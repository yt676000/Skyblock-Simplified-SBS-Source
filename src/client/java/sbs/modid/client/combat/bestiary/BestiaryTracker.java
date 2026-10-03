/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks how many kills remain to <b>max</b> the Bestiary of a chosen mob, read straight from the
 * in-game <b>Bestiary menu</b> (there is no chat/API signal per kill, so the menu lore is the source).
 *
 * <p>While a container whose title contains "Bestiary" is open, every mob item's lore is parsed for
 * its total kills and the kills required to max it, keyed by the (normalized) mob name. The module's
 * pinned mob is then shown on the HUD as "N kills to max". Re-opening the menu refreshes the numbers.
 *
 * <p><b>Best-guess lore parsing.</b> Hypixel does not document the lore shape, so the regexes here are
 * a first pass. The whole lore of an unparsed mob item is logged (throttled, {@code [SBS][Bestiary]})
 * so the exact "kills / needed" line can be pinned down from a real menu and the patterns tuned in one
 * pass, exactly like the mod's other menu-scraping trackers.
 */
public final class BestiaryTracker {

    private static final BestiaryTracker INSTANCE = new BestiaryTracker();

    /** "Overall Progress: 45% (12,345/50,000)" – total kills toward max and the max requirement. */
    private static final Pattern OVERALL = Pattern.compile(
            "(?i)overall progress[^0-9]*([0-9,]+)\\s*/\\s*([0-9,]+)");
    /** A generic "12,345/50,000" progress pair (fallback when there is no Overall Progress line). */
    private static final Pattern PAIR = Pattern.compile("([0-9,]+)\\s*/\\s*([0-9,]+)");
    /** "Kills: 12,345" – the mob's total kills. */
    private static final Pattern KILLS = Pattern.compile("(?i)\\bkills?:?\\s*([0-9,]+)");
    /** A trailing tier marker on a mob item name ("Zombie 5" / "Zombie IX" -> "Zombie"). */
    private static final Pattern TRAILING_TIER = Pattern.compile("\\s+([IVXLC]+|\\d+)$");

    private static final char SECTION_SIGN = (char) 0x00A7;
    private static final long SCAN_INTERVAL_MS = 500;

    /** One tracked mob: total kills, the kills needed to max it (-1 unknown), and the maxed flag. */
    public record Mob(String name, long kills, long maxKills, boolean maxed) {
        /** Kills remaining to max, or 0 when maxed / already past. {@code -1} when the max is unknown. */
        public long remaining() {
            if (maxed) {
                return 0;
            }
            return maxKills < 0 ? -1 : Math.max(0, maxKills - kills);
        }
    }

    /** Kills counted live since the last menu read, per normalized mob name (runtime only, not saved). */
    private final Map<String, Integer> sessionKills = new HashMap<>();
    /** Pinned-mob instances currently near the player: entity id -> when it was last seen close. */
    private final Map<Integer, Long> nearby = new HashMap<>();

    /** Blocks around the player to watch for the pinned mob dying. */
    private static final double KILL_RADIUS = 16.0;
    /** A gone entity counts as a kill only if it was seen close within this window (else it walked off). */
    private static final long KILL_FRESH_MS = 1_200L;
    /** Drop a tracked mob we have not seen for this long without counting it (walked away / unloaded). */
    private static final long TRACK_STALE_MS = 5_000L;

    private long lastScanAt;
    private long lastLogAt;

    private BestiaryTracker() {
    }

    public static BestiaryTracker getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.BestiarySettings cfg() {
        return ConfigManager.getInstance().get().bestiary;
    }

    /** Upper-cased, alphanumeric-only key so "Zombie", "zombie", "ZOMBIE" all match. */
    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    // ------------------------------------------------------------------ menu scan

    /**
     * Called every client tick. Counts kills of the pinned mob near the player (so the HUD keeps
     * ticking up between menu opens) and, throttled, re-reads the open Bestiary menu to re-anchor the
     * saved baseline.
     */
    public void onClientTick() {
        if (!cfg().enabled || Minecraft.getInstance().player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        detectKills(now);   // every tick - kills happen faster than the menu-scan throttle
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        scanMenu(now);
    }

    private void scanMenu(long now) {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = strip(screen.getTitle() != null ? screen.getTitle().getString() : "");
        if (!title.toLowerCase(Locale.ROOT).contains("bestiary")) {
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        int matched = 0;
        boolean changed = false;
        String unparsedSample = null;
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Mob mob = parse(stack);
            if (mob != null) {
                changed |= saveBaseline(mob);
                matched++;
            } else if (unparsedSample == null) {
                String lore = fullLore(stack);
                if (lore.toLowerCase(Locale.ROOT).contains("kill")) {
                    unparsedSample = strip(stack.getHoverName().getString()) + " :: " + lore;
                }
            }
        }
        if (changed) {
            ConfigManager.getInstance().save();   // persist the baseline so it survives a relog
        }
        // Tuning aid, ALWAYS logged (throttled) while a Bestiary menu is open: how many mobs parsed,
        // plus - when something kill-related failed to parse - one sample lore to fix the regexes on.
        if (now - lastLogAt > 5_000L) {
            lastLogAt = now;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bestiary] menu '{}': {} mobs parsed, {} saved total{}",
                    title, matched, cfg().saved.size(),
                    unparsedSample == null ? "" : " | unparsed sample: " + unparsedSample.replace('\n', '|'));
        }
    }

    /**
     * Writes a mob's menu numbers into the saved baseline (normalized name -> entry). The menu total
     * is authoritative, so the live self-count for that mob restarts from zero here. Returns whether
     * anything actually changed (so the config is only written when needed).
     */
    private boolean saveBaseline(Mob mob) {
        String key = normalize(mob.name());
        SBSConfig.BestiaryEntry entry = cfg().saved.computeIfAbsent(key, k -> new SBSConfig.BestiaryEntry());
        boolean changed = entry.kills != mob.kills() || entry.maxKills != mob.maxKills()
                || entry.maxed != mob.maxed() || !mob.name().equals(entry.name);
        entry.name = mob.name();
        entry.kills = mob.kills();
        entry.maxKills = mob.maxKills();
        entry.maxed = mob.maxed();
        sessionKills.put(key, 0);   // baseline just re-anchored - the menu already counts these kills
        return changed;
    }

    /**
     * Best-effort self-count: while a pinned mob is set, watch for instances of it dying near the
     * player and add each to the live count. A kill is "an instance we were tracking close is now
     * gone" (removed / dead); a mob that simply walked away is pruned without counting. Fuzzy by
     * nature (client-side proximity), but it self-corrects every time the Bestiary menu is re-opened.
     */
    private void detectKills(long now) {
        String pin = normalize(cfg().pinnedMob);
        if (pin.isEmpty()) {
            nearby.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        // Find pinned-mob instances via their health nametag stands near the player.
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class,
                player.getBoundingBox().inflate(KILL_RADIUS), ArmorStand::hasCustomName);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (ArmorStand stand : stands) {
            String name = stand.getCustomName() == null ? null
                    : MobHighlightTracker.mobNameInNametag(stand.getCustomName().getString());
            if (name == null || !normalize(name).equals(pin)) {
                continue;
            }
            LivingEntity mob = MobHighlightTracker.mobBelow(level, stand);
            if (mob != null) {
                seen.add(mob.getId());
                nearby.put(mob.getId(), now);
            }
        }
        int counted = 0;
        Iterator<Map.Entry<Integer, Long>> it = nearby.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (seen.contains(e.getKey())) {
                continue;   // still here this tick
            }
            Entity ent = level.getEntity(e.getKey());
            boolean gone = !(ent instanceof LivingEntity le) || !le.isAlive() || le.isDeadOrDying();
            if (gone && now - e.getValue() <= KILL_FRESH_MS) {
                it.remove();
                counted++;
            } else if (now - e.getValue() > TRACK_STALE_MS || gone) {
                it.remove();   // walked off / unloaded, or gone but too stale to trust as a kill
            }
        }
        if (counted > 0) {
            sessionKills.merge(pin, counted, Integer::sum);
            if (now - lastLogAt > 3_000L) {
                lastLogAt = now;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bestiary] +{} {} (session {})",
                        counted, cfg().pinnedMob, sessionKills.getOrDefault(pin, 0));
            }
        }
    }

    /** Parses one Bestiary mob item, or {@code null} when its lore carries no kill numbers. */
    private Mob parse(ItemStack stack) {
        String lore = fullLore(stack);
        if (lore.isEmpty()) {
            return null;
        }
        boolean maxed = lore.toLowerCase(Locale.ROOT).contains("maxed");

        long kills = -1;
        long maxKills = -1;
        Matcher overall = OVERALL.matcher(lore);
        if (overall.find()) {
            kills = parseNum(overall.group(1));
            maxKills = parseNum(overall.group(2));
        } else {
            Matcher killsMatcher = KILLS.matcher(lore);
            if (killsMatcher.find()) {
                kills = parseNum(killsMatcher.group(1));
            }
            // Fallback max: the largest denominator of any "x/y" progress pair on the item.
            Matcher pair = PAIR.matcher(lore);
            while (pair.find()) {
                maxKills = Math.max(maxKills, parseNum(pair.group(2)));
            }
        }
        if (kills < 0 && !maxed) {
            return null; // nothing usable on this item
        }
        String name = cleanName(strip(stack.getHoverName().getString()));
        if (name.isEmpty()) {
            return null;
        }
        return new Mob(name, Math.max(0, kills), maxKills, maxed);
    }

    // ------------------------------------------------------------------ HUD read model

    /**
     * The pinned mob's snapshot - the saved menu baseline plus any kills self-counted since - or
     * {@code null} when nothing is pinned / it was never read from the menu.
     */
    public Mob pinned() {
        String pin = cfg().pinnedMob;
        if (pin == null || pin.isBlank()) {
            return null;
        }
        String key = normalize(pin);
        SBSConfig.BestiaryEntry entry = cfg().saved.get(key);
        if (entry == null) {
            return null;
        }
        long kills = entry.kills + sessionKills.getOrDefault(key, 0);
        boolean maxed = entry.maxed || (entry.maxKills > 0 && kills >= entry.maxKills);
        return new Mob(entry.name.isEmpty() ? pin.trim() : entry.name, kills, entry.maxKills, maxed);
    }

    /** The pinned mob name (raw), for the HUD's "not loaded yet" prompt. Empty when unset. */
    public String pinnedName() {
        String pin = cfg().pinnedMob;
        return pin == null ? "" : pin.trim();
    }

    // ------------------------------------------------------------------ helpers

    /** Item display name → mob name: drop a trailing tier (roman or number). */
    private static String cleanName(String raw) {
        return TRAILING_TIER.matcher(raw.trim()).replaceAll("").trim();
    }

    private static String fullLore(ItemStack stack) {
        Object component = stack.get(DataComponents.LORE);
        if (!(component instanceof ItemLore lore)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Object line : lore.lines()) {
            if (line instanceof Component text) {
                out.append(strip(text.getString())).append('\n');
            }
        }
        return out.toString();
    }

    private static long parseNum(String raw) {
        try {
            return Long.parseLong(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
