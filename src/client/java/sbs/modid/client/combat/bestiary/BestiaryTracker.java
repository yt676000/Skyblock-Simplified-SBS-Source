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
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.core.util.NumberParse;
import sbs.modid.client.combat.damage.logic.DamageSplash;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the Bestiary progress of the pinned mobs, read straight from the in-game <b>Bestiary
 * menu</b> and kept live from the tab list's Bestiary widget (or, for a mob the widget does not
 * list, a client-side kill count).
 *
 * <p>While a container whose title contains "Bestiary" is open, every mob item's lore is parsed for
 * its total kills, the kills required to max it and the progress inside its tier, keyed by the
 * (normalized) mob name. The pins themselves live in {@link BestiaryPins}, per profile, toggled by
 * the ★ {@link BestiaryStarDecorator} draws on those items.
 *
 * <p><b>Best-guess lore parsing.</b> Hypixel does not document the lore shape, so the regexes here are
 * a first pass. The whole lore of an unparsed mob item is logged (throttled, {@code [SBS][Bestiary]})
 * so the exact "kills / needed" line can be pinned down from a real menu and the patterns tuned in one
 * pass, exactly like the mod's other menu-scraping trackers. The tab widget rows are logged the same
 * way, once per change ({@code [SBS][Bestiary] tab: …}).
 */
public final class BestiaryTracker {

    private static final BestiaryTracker INSTANCE = new BestiaryTracker();

    /**
     * "Overall Progress: 45.1%" then "11,267/25k" – total kills toward max and the max requirement.
     * The live menu abbreviates (25k, 2.5k) and puts a decimal percentage first, so the pair is the
     * first number directly followed by a slash, and both sides keep their suffix.
     */
    private static final Pattern OVERALL = Pattern.compile(
            "(?i)overall progress[^/]*?([0-9][0-9.,]*[kKmMbB]?)\\s*/\\s*([0-9][0-9.,]*[kKmMbB]?)");
    /** A generic "1,267/2.5k" progress pair (fallback when there is no Overall Progress line). */
    private static final Pattern PAIR = Pattern.compile(
            "([0-9][0-9.,]*[kKmMbB]?)\\s*/\\s*([0-9][0-9.,]*[kKmMbB]?)");
    /** "Kills: 12,345" – the mob's total kills. */
    private static final Pattern KILLS = Pattern.compile("(?i)\\bkills?:?\\s*([0-9,]+)");
    /** A trailing tier marker on a mob item name ("Zombie 5" / "Zombie IX" -> "Zombie"). */
    private static final Pattern TRAILING_TIER = Pattern.compile("\\s+([IVXLC]+|\\d+)$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");

    private static final char SECTION_SIGN = (char) 0x00A7;
    private static final long SCAN_INTERVAL_MS = 500;
    /** The player inventory under every chest menu: never Bestiary items (Milestones has "bestiary" too). */
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    /** One mob read from the menu: totals, tier and tier progress (-1 = unknown), maxed flag. */
    public record Mob(String name, long kills, long maxKills, boolean maxed,
                      int tier, long tierKills, long tierNeeded) {
    }

    /** Kills counted live since the last menu read, per normalized mob name (runtime only, not saved). */
    private final Map<String, Long> sessionKills = new HashMap<>();
    /** Pinned-mob instances currently near the player: entity id -> when it was last seen close. */
    private final Map<Integer, Long> nearby = new HashMap<>();
    /** The tab widget's latest row per normalized mob name - only mobs it lists right now. */
    private final Map<String, BestiaryTab.Row> tabRows = new HashMap<>();

    /** Blocks around the player to watch for the pinned mob dying. */
    private static final double KILL_RADIUS = 32.0;
    /**
     * A gone stand counts as a kill only if it was seen close within this window (else it walked off).
     * Hypixel rewrites the nametag on death and drops the stand a moment later, so this covers that gap.
     */
    private static final long KILL_FRESH_MS = 3_000L;
    /** Stand id -> the mob it labelled when first tracked; that mob dying is a kill even if the stand lingers. */
    private final Map<Integer, Integer> standMob = new HashMap<>();
    /** Stands already counted whose entity still exists, so they are not tracked and counted again. */
    private final java.util.Set<Integer> counted = new java.util.HashSet<>();
    /** Drop a tracked mob we have not seen for this long without counting it (walked away / unloaded). */
    private static final long TRACK_STALE_MS = 5_000L;
    /** A jump larger than this between two tab reads is a different mob or a reset, not kills. */
    private static final long MAX_TAB_STEP = 10_000L;

    /** Bumped whenever a number the HUD shows may have changed. */
    private int revision;
    /** Scratch for {@link #detectKills}: instances seen this tick (client thread only). */
    private final Map<Integer, String> seen = new HashMap<>();

    private long lastScanAt;
    private long lastLogAt;
    private int tabRevision = -1;
    private List<String> lastTabSection = List.of();
    /** Pinned mobs' lore as last logged, so each is written once per change, not per scan. */
    private final Map<String, String> lastPinnedLore = new HashMap<>();

    /** Mob slots of the open Bestiary island page (slot index -> mob name), for the ★. */
    private final Map<Integer, String> starSlots = new HashMap<>();
    private int starContainerId = Integer.MIN_VALUE;

    /** Bumped on every pin change; Mob Highlight re-reads {@link #highlightNames} only then. */
    private int pinsVersion;
    private String pinsProfile;
    private int highlightVersion = -1;
    private Set<String> highlightNames = Set.of();

    private BestiaryTracker() {
    }

    public static BestiaryTracker getInstance() {
        return INSTANCE;
    }

    /**
     * World leave: the tracked instances vanish with the world, and one seen moments before the hop
     * would be counted as a kill. The live session counts are not world state and survive.
     */
    public void onWorldChange() {
        nearby.clear();
        standMob.clear();
        counted.clear();
        splashes.clear();
        standPos.clear();
        tabRows.clear();
        tabRevision = -1;
    }

    private static SBSConfig.BestiarySettings cfg() {
        return ConfigManager.getInstance().get().bestiary;
    }

    /** Upper-cased, alphanumeric-only key so "Zombie", "zombie", "ZOMBIE" all match. */
    static String normalize(String value) {
        return value == null ? "" : NON_ALNUM.matcher(value.toUpperCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * {@code normalize(raw).equals(key)} without building the normalized copy - this runs per
     * nametag stand per tick. Non-ASCII text takes the regex path, because
     * {@code toUpperCase} can turn one such character into several letters ({@code ß} → {@code SS}).
     */
    static boolean normalizedEquals(String raw, String key) {
        int j = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x80) {
                return normalize(raw).equals(key);
            }
            if (c >= 'a' && c <= 'z') {
                c -= 'a' - 'A';
            }
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                if (j >= key.length() || key.charAt(j) != c) {
                    return false;
                }
                j++;
            }
        }
        return j == key.length();
    }

    // ------------------------------------------------------------------ pins

    private static String profile() {
        String profile = ProfileContext.getInstance().profile();
        return profile == null ? "" : profile;
    }

    /** The current profile's pins, in HUD order. Mutate only through the methods below. */
    public List<SBSConfig.BestiaryPin> pins() {
        String profile = profile();
        if (!profile.equals(pinsProfile)) {
            pinsProfile = profile;
            pinsVersion++;
        }
        return BestiaryPins.of(cfg(), profile);
    }

    /** Allocation-free: the ★ asks this per mob slot per frame. */
    public boolean isPinned(String name) {
        return pinnedKey(pins(), name) != null;
    }

    /** ★ click: pins or unpins {@code name} on the current profile and saves. */
    public boolean togglePin(String name) {
        boolean pinned = BestiaryPins.toggle(cfg(), profile(), name);
        pinsChanged();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bestiary] {} '{}' ({} pinned)",
                pinned ? "pinned" : "unpinned", name, pins().size());
        return pinned;
    }

    /** Saves after the pin list was edited (order, highlight, unpin) and lets Mob Highlight re-read it. */
    public void pinsChanged() {
        pinsVersion++;
        ConfigManager.getInstance().save();
    }

    /**
     * Lower-cased names of the pinned mobs with "Highlight in world" on, for Mob Highlight. The same
     * instance until a pin changes, so the caller can compare by identity.
     */
    public Set<String> highlightNames() {
        if (!cfg().enabled) {
            return Set.of();
        }
        List<SBSConfig.BestiaryPin> pins = pins();
        if (highlightVersion != pinsVersion) {
            highlightVersion = pinsVersion;
            Set<String> names = new HashSet<>();
            for (SBSConfig.BestiaryPin pin : pins) {
                if (pin.highlight && !pin.name.isBlank()) {
                    names.add(pin.name.trim().toLowerCase(Locale.ROOT));
                }
            }
            highlightNames = Set.copyOf(names);
        }
        return highlightNames;
    }

    // ------------------------------------------------------------------ tick

    /**
     * Called every client tick. Keeps the pinned mobs live (tab widget, else a nearby kill count)
     * and, throttled, re-reads the open Bestiary menu to re-anchor the saved baseline.
     */
    public void onClientTick() {
        if (!cfg().enabled || Minecraft.getInstance().player == null) {
            return;
        }
        if (ProfileContext.getInstance().known() && BestiaryPins.migrate(cfg(), profile())) {
            pinsChanged();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bestiary] moved the old Pin Mob text into the pin list");
        }
        long now = System.currentTimeMillis();
        readTab();
        detectKills(now);   // every tick - kills happen faster than the menu-scan throttle
        if (now - lastScanAt >= SCAN_INTERVAL_MS) {
            lastScanAt = now;
            scanMenu(now);
        }
        BestiaryHud.refresh(revision, pinsVersion);
    }

    /**
     * The tab widget, re-read only when the tab changed. Kills between two reads of the same mob are
     * added to its live count; a tier-up adds the rest of the old tier plus the new tier's count.
     */
    private void readTab() {
        int revision = TabWidgets.revision();
        if (revision == tabRevision) {
            return;
        }
        tabRevision = revision;
        List<String> section = BestiaryTab.section(TabWidgets.lines());
        if (!section.equals(lastTabSection)) {
            lastTabSection = List.copyOf(section);
            revision++;
            if (!section.isEmpty()) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bestiary] tab: {}", String.join(" | ", section));
            }
        }
        Map<String, BestiaryTab.Row> next = new HashMap<>();
        for (String line : section) {
            BestiaryTab.Row row = BestiaryTab.parseRow(line);
            if (row == null) {
                continue;
            }
            String key = normalize(row.name());
            BestiaryTab.Row previous = tabRows.get(key);
            long gained = tabStep(previous, row);
            if (gained > 0) {
                sessionKills.merge(key, gained, Long::sum);
                revision++;
            }
            next.put(key, row);
        }
        tabRows.clear();
        tabRows.putAll(next);
    }

    /** Kills between two tab reads of one mob; 0 when they cannot be compared. */
    static long tabStep(BestiaryTab.Row previous, BestiaryTab.Row row) {
        if (previous == null || previous.maxed()) {
            return 0;
        }
        long gained;
        if (row.maxed()) {
            gained = previous.needed() - previous.kills();
        } else if (row.tier() == previous.tier()) {
            gained = row.kills() - previous.kills();
        } else if (row.tier() == previous.tier() + 1) {
            gained = previous.needed() - previous.kills() + row.kills();
        } else {
            return 0;
        }
        return gained > 0 && gained <= MAX_TAB_STEP ? gained : 0;
    }

    private void scanMenu(long now) {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            starSlots.clear();
            return;
        }
        String title = strip(screen.getTitle() != null ? screen.getTitle().getString() : "");
        if (!title.toLowerCase(Locale.ROOT).contains("bestiary")) {
            starSlots.clear();
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        boolean islandPage = BestiaryStarDecorator.isIslandPage(title);
        starSlots.clear();
        starContainerId = menu.containerId;
        int matched = 0;
        boolean changed = false;
        String unparsedSample = null;
        int top = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOTS);
        for (int i = 0; i < top; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Mob mob = parse(stack);
            if (mob != null) {
                changed |= saveBaseline(mob, now);
                matched++;
                if (islandPage) {
                    starSlots.put(i, mob.name());
                }
            } else if (unparsedSample == null) {
                String lore = fullLore(stack);
                if (lore.toLowerCase(Locale.ROOT).contains("kill")) {
                    unparsedSample = strip(stack.getHoverName().getString()) + " :: " + lore;
                }
            }
        }
        if (matched > 0) {
            revision++;   // read times moved (and session counts re-anchored) even when no total did
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

    /** The mob slots of the open island page (slot index -> mob name); empty for any other menu. */
    public Map<Integer, String> starSlots(int containerId) {
        return containerId == starContainerId ? starSlotsView : Map.of();
    }

    private final Map<Integer, String> starSlotsView = java.util.Collections.unmodifiableMap(starSlots);

    /**
     * Writes a mob's menu numbers into the saved baseline (normalized name -> entry). The menu total
     * is authoritative, so the live self-count for that mob restarts from zero here. Returns whether
     * anything actually changed (so the config is only written when needed).
     */
    private boolean saveBaseline(Mob mob, long now) {
        String key = normalize(mob.name());
        SBSConfig.BestiaryEntry entry = cfg().saved.computeIfAbsent(key, k -> new SBSConfig.BestiaryEntry());
        boolean changed = entry.kills != mob.kills() || entry.maxKills != mob.maxKills()
                || entry.maxed != mob.maxed() || !mob.name().equals(entry.name) || entry.tier != mob.tier()
                || entry.tierKills != mob.tierKills() || entry.tierNeeded != mob.tierNeeded();
        entry.name = mob.name();
        entry.kills = mob.kills();
        entry.maxKills = mob.maxKills();
        entry.maxed = mob.maxed();
        entry.tier = mob.tier();
        entry.tierKills = mob.tierKills();
        entry.tierNeeded = mob.tierNeeded();
        // Not a "change": the read time rides along with the next save instead of forcing one per scan.
        entry.readAt = now;
        sessionKills.put(key, 0L);   // baseline just re-anchored - the menu already counts these kills
        return changed;
    }

    /**
     * Best-effort self-count for pinned mobs the tab widget does not list: watch for instances dying
     * near the player and add each to the live count. A kill is "an instance we were tracking close
     * is now gone" (removed / dead); a mob that simply walked away is pruned without counting. Fuzzy
     * by nature (client-side proximity), but it self-corrects every time the Bestiary menu is re-opened.
     */
    private void detectKills(long now) {
        List<SBSConfig.BestiaryPin> pins = pins();
        if (pins.isEmpty()) {
            nearby.clear();
            standMob.clear();
            counted.clear();
            splashes.clear();
            standPos.clear();
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
        seen.clear();
        splashes.values().removeIf(s -> now - s.at() > OWN_HIT_MS);
        for (ArmorStand stand : stands) {
            String plain = stand.getCustomName() == null ? null : stand.getCustomName().getString();
            // Damage splashes are only shown for the player's own damage: they prove whose kill it is.
            if (plain != null && !splashes.containsKey(stand.getId())
                    && DamageSplash.value(plain) != DamageSplash.NOT_A_SPLASH) {
                splashes.put(stand.getId(), new Splash(stand.getX(), stand.getZ(), now));
                continue;
            }
            String name = plain == null ? null : MobHighlightTracker.mobNameInNametag(plain);
            String key = name == null ? null : pinnedKey(pins, name);
            if (key == null || tabRows.containsKey(key)) {
                continue;   // not pinned, or the tab widget already counts it exactly
            }
            // Track the nametag stand, not the mob under it: in a clump several stands resolve to the
            // same nearest mob, which made a multi-kill count once. Every mob has its own stand.
            int id = stand.getId();
            if (counted.contains(id)) {
                continue;   // already counted; the stand is only lingering
            }
            standPos.put(id, new Splash(stand.getX(), stand.getZ(), now));
            if (nearby.containsKey(id)) {
                seen.put(id, key);
                nearby.put(id, now);
            } else {
                LivingEntity mob = MobHighlightTracker.mobBelow(level, stand,
                        new it.unimi.dsi.fastutil.ints.IntOpenHashSet(standMob.values()));
                if (mob != null) {
                    seen.put(id, key);
                    nearby.put(id, now);
                    nearbyKey.put(id, key);
                    standMob.put(id, mob.getId());
                }
            }
        }
        counted.removeIf(id -> {
            Entity ent = level.getEntity(id);
            return ent == null || ent.isRemoved();
        });
        Map<String, Integer> gainedNow = null;
        Iterator<Map.Entry<Integer, Long>> it = nearby.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            Entity ent = level.getEntity(e.getKey());
            // The stand is removed when its mob dies; one that merely left the radius still exists.
            boolean standGone = ent == null || ent.isRemoved();
            Integer mobId = standMob.get(e.getKey());
            Entity mob = mobId == null ? null : level.getEntity(mobId);
            boolean mobDied = mob instanceof LivingEntity le && (le.isDeadOrDying() || !le.isAlive());
            if (seen.containsKey(e.getKey()) && !mobDied) {
                continue;   // still here this tick
            }
            boolean gone = standGone || mobDied;
            if (gone && now - e.getValue() <= KILL_FRESH_MS) {
                it.remove();
                standMob.remove(e.getKey());
                if (!standGone) {
                    counted.add(e.getKey());
                }
                String key = nearbyKey.remove(e.getKey());
                Splash pos = standPos.remove(e.getKey());
                if (key != null && !hitByPlayer(pos)) {
                    unowned++;   // died without the player's damage on it: someone else's kill
                    key = null;
                }
                if (key != null) {
                    sessionKills.merge(key, 1L, Long::sum);
                    revision++;
                    if (gainedNow == null) {
                        gainedNow = new HashMap<>();
                    }
                    gainedNow.merge(key, 1, Integer::sum);
                }
            } else if (now - e.getValue() > TRACK_STALE_MS || gone) {
                it.remove();   // walked off / unloaded, or gone but too stale to trust as a kill
                nearbyKey.remove(e.getKey());
                standMob.remove(e.getKey());
                standPos.remove(e.getKey());
            }
        }
        standPos.keySet().retainAll(nearby.keySet());
        // One line per tick that counted, with how many were counted at once and how many instances
        // were still tracked: the line that tells a real multi-kill from one counted mob.
        if (gainedNow != null) {
            for (Map.Entry<String, Integer> g : gainedNow.entrySet()) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bestiary] +{} {} (session {}, {} still tracked, {} stands seen, {} not own)",
                        g.getValue(), g.getKey(), sessionKills.get(g.getKey()), nearby.size(), seen.size(), unowned);
            }
        }
    }

    /** A point and when it was seen: a damage splash, or a tracked stand's last position. */
    private record Splash(double x, double z, long at) {
    }

    /** How long a damage splash marks its spot as hit by the player. */
    private static final long OWN_HIT_MS = 5_000L;
    /** Horizontal distance (squared) a splash may be from the mob's stand to count as hitting it. */
    private static final double OWN_HIT_DIST_SQ = 4.0 * 4.0;
    /** Recent damage splashes, by stand id (each splash recorded once, when first seen). */
    private final Map<Integer, Splash> splashes = new HashMap<>();
    /** Last position of each tracked nametag stand. */
    private final Map<Integer, Splash> standPos = new HashMap<>();
    /** Kills dropped because the player did not hit the mob (log only). */
    private long unowned;

    /** Whether one of the player's damage splashes appeared near {@code pos} recently. */
    private boolean hitByPlayer(Splash pos) {
        if (pos == null) {
            return false;
        }
        for (Splash s : splashes.values()) {
            double dx = s.x() - pos.x();
            double dz = s.z() - pos.z();
            if (dx * dx + dz * dz <= OWN_HIT_DIST_SQ) {
                return true;
            }
        }
        return false;
    }

    /** Tracked instance -> the pin it belongs to. */
    private final Map<Integer, String> nearbyKey = new HashMap<>();

    /** The key of the pin {@code name} belongs to, or {@code null}. */
    private static String pinnedKey(List<SBSConfig.BestiaryPin> pins, String name) {
        for (int i = 0, n = pins.size(); i < n; i++) {
            String key = pins.get(i).key;
            if (normalizedEquals(name, key)) {
                return key;
            }
        }
        return null;
    }

    /** Parses one Bestiary mob item, or {@code null} when its lore carries no kill numbers. */
    private Mob parse(ItemStack stack) {
        String lore = fullLore(stack);
        if (lore.isEmpty()) {
            return null;
        }
        boolean maxed = lore.toLowerCase(Locale.ROOT).contains("maxed");

        long[] read = readNumbers(lore);
        long kills = read[0];
        long maxKills = read[1];
        long tierKills = read[2];
        long tierNeeded = read[3];
        boolean viaOverall = read[4] == 1;
        if (kills < 0 && !maxed) {
            return null; // nothing usable on this item
        }
        String rawName = strip(stack.getHoverName().getString()).trim();
        String name = cleanName(rawName);
        if (name.isEmpty()) {
            return null;
        }
        Matcher tierMatcher = TRAILING_TIER.matcher(rawName);
        int tier = tierMatcher.find() ? BestiaryTab.tier(tierMatcher.group(1)) : 0;
        // A pinned mob's whole lore and what was read from it, once per change: the lore shape is
        // not on record, and this line is what the patterns get tuned on.
        String key = normalize(name);
        if (isPinnedKey(key) && !lore.equals(lastPinnedLore.get(key))) {
            lastPinnedLore.put(key, lore);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bestiary] pinned '{}' read tier={} kills={} max={} tier={}/{} "
                            + "maxed={} via {} | lore: {}", name, tier, kills, maxKills, tierKills, tierNeeded,
                    maxed, viaOverall ? "Overall Progress" : "fallback", lore.replace('\n', '|'));
        }
        return new Mob(name, Math.max(0, kills), maxKills, maxed, tier, tierKills, tierNeeded);
    }

    /**
     * The numbers of one mob's lore: {kills, maxKills, tierKills, tierNeeded, viaOverall (1/0)},
     * -1 where a number is missing.
     */
    static long[] readNumbers(String lore) {
        long kills = -1;
        long maxKills = -1;
        long tierKills = -1;
        long tierNeeded = -1;
        Matcher overall = OVERALL.matcher(lore);
        boolean viaOverall = overall.find();
        Matcher pair = PAIR.matcher(lore);
        if (viaOverall) {
            kills = parseNum(lore, overall.start(1), overall.end(1));
            maxKills = parseNum(lore, overall.start(2), overall.end(2));
            // The tier pair: the first progress pair that is not the overall one.
            while (pair.find()) {
                if (pair.start() < overall.start(1) || pair.start() >= overall.end(2)) {
                    tierKills = parseNum(lore, pair.start(1), pair.end(1));
                    tierNeeded = parseNum(lore, pair.start(2), pair.end(2));
                    break;
                }
            }
        } else {
            Matcher killsMatcher = KILLS.matcher(lore);
            if (killsMatcher.find()) {
                kills = parseNum(lore, killsMatcher.start(1), killsMatcher.end(1));
            }
            // Fallback max: the largest denominator of any "x/y" progress pair on the item.
            while (pair.find()) {
                maxKills = Math.max(maxKills, parseNum(lore, pair.start(2), pair.end(2)));
            }
        }
        return new long[]{kills, maxKills, tierKills, tierNeeded, viaOverall ? 1 : 0};
    }

    private boolean isPinnedKey(String key) {
        for (SBSConfig.BestiaryPin pin : pins()) {
            if (pin.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ HUD read model

    /** Progress of one pin: the saved menu baseline, kept live by the tab widget or the kill count. */
    public BestiaryProgress progress(SBSConfig.BestiaryPin pin) {
        SBSConfig.BestiaryEntry entry = cfg().saved.get(pin.key);
        String name = entry != null && !entry.name.isEmpty() ? entry.name : pin.name;
        BestiaryTab.Row tab = tabRows.get(pin.key);
        if (entry == null) {
            if (tab == null) {
                return new BestiaryProgress(name, 0, -1, -1, -1, -1, false);
            }
            return new BestiaryProgress(name, tab.tier(), tab.kills(), tab.needed(), -1, -1, tab.maxed());
        }
        long session = sessionKills.getOrDefault(pin.key, 0L);
        long kills = entry.kills + session;
        if (tab != null) {
            return new BestiaryProgress(name, tab.tier(), tab.kills(), tab.needed(), kills, entry.maxKills,
                    entry.maxed || tab.maxed());
        }
        long tierKills = entry.tierKills < 0 ? -1
                : entry.tierNeeded > 0 ? Math.min(entry.tierNeeded, entry.tierKills + session) : entry.tierKills;
        return new BestiaryProgress(name, entry.tier, tierKills, entry.tierNeeded, kills, entry.maxKills,
                entry.maxed);
    }

    /** Whether the tab widget lists this pin right now (its numbers are live, not from the last menu read). */
    public boolean isLive(SBSConfig.BestiaryPin pin) {
        return tabRows.containsKey(pin.key);
    }

    /** When the menu last read this pin, 0 if never. */
    public long readAt(SBSConfig.BestiaryPin pin) {
        SBSConfig.BestiaryEntry entry = cfg().saved.get(pin.key);
        return entry == null ? 0 : entry.readAt;
    }

    /** For tests and the settings list: the pins' current rows, in order. */
    public List<BestiaryProgress> rows() {
        List<BestiaryProgress> out = new ArrayList<>();
        for (SBSConfig.BestiaryPin pin : pins()) {
            out.add(progress(pin));
        }
        return out;
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

    /** The digits of {@code [start, end)} as one number, separators ignored (0 when none). */
    private static long parseNum(String text, int start, int end) {
        // parseLong, not parseDigits: "2.5k" is 2,500, not 25.
        return NumberParse.parseLong(text, start, end, 0);
    }

    static String strip(String text) {
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
