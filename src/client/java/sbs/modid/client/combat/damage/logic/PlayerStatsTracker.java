/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.damage.model.GearStats;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.DamageOverlaySettings;
import sbs.modid.client.skills.progress.SkillTracker;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures the player's combat stats for the Damage Overlay, and keeps them valid while gear
 * changes. The client is never told its SkyBlock Strength / Crit Damage / … – they only exist as
 * menu lore – so this tracker watches the stat-bearing menus (SkyBlock Menu, /stats, the Equipment
 * screen) and snapshots any item that prints the stat <b>totals</b>.
 *
 * <p><b>Totals, not gear bonuses – and no glyphs.</b> Hypixel's current menus prefix each stat with
 * a custom icon glyph that varies by menu generation, so matching is by stat <i>name</i> with any
 * leading glyph stripped. The line shape is the discriminator: a total reads
 * {@code "Strength 945.1"}, while gear-bonus lore reads {@code "Strength: +50"} – the colon/plus
 * means it is NOT a total and never captured. An item only counts at all when Health, Strength and
 * Crit Damage totals appear together (the profile head / "Combat Stats" item); whichever stats such
 * an item does not print (the SkyBlock Menu head hides Attack Speed and Ferocity behind
 * "and more…") keep their previously captured values instead of being wiped.
 *
 * <p><b>Base stats vs. the current setup.</b> Those totals include whatever was worn and held at
 * capture time, so the gear stats of that moment ({@link #liveGear}: four armor pieces + main hand)
 * are stored alongside them. The base – skills, accessories / magical power, pets, potions – is
 * {@code totals − gear at capture}, and the live total is {@code base + gear right now}. Swapping
 * armor, weapons or a whole loadout therefore updates the estimate immediately; only SkyBlock
 * <i>equipment</i> (necklace / cloak / belt / gloves) and accessories are invisible to the client
 * and stay inside the base, so changing those is the one case that wants a fresh menu open.
 *
 * <p><b>Combat level</b> needs no menu at all: the action bar names the skill on every XP gain, so
 * {@link SkillTracker} already knows the Combat level while you fight, and it is mirrored into the
 * config here. This matters more than it looks – the Warrior bonus is up to <b>+210% additive</b>,
 * by far the largest single term in the formula.
 */
public final class PlayerStatsTracker {

    private static final PlayerStatsTracker INSTANCE = new PlayerStatsTracker();

    /** Bump when the snapshot's field layout changes; older snapshots then ask to be re-taken. */
    private static final int STATS_VERSION = 2;

    // One stat total per lore line: leading icon glyph(s) stripped, then "Name 1,234.5" with
    // nothing between name and number - "Strength: +50" (gear bonus) must not match.
    private static final Pattern HEALTH = totalPattern("Health");
    private static final Pattern STRENGTH = totalPattern("Strength");
    private static final Pattern CRIT_CHANCE = totalPattern("Crit Chance");
    private static final Pattern CRIT_DAMAGE = totalPattern("Crit Damage");
    private static final Pattern ATTACK_SPEED = totalPattern("(?:Bonus )?Attack Speed");
    private static final Pattern FEROCITY = totalPattern("Ferocity");
    /** "Combat 42" anywhere in a menu item (skills item, profile lore). Values above 60 are noise. */
    private static final Pattern COMBAT_LEVEL = Pattern.compile("\\bCombat\\s+(\\d{1,2})\\b");

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final long SCAN_INTERVAL_MS = 750;
    private static final long LOG_INTERVAL_MS = 5_000;
    private static final char SECTION_SIGN = (char) 0x00A7;

    /** The stat totals one menu item carries; {@code null} = the item does not print that stat. */
    private record Totals(Double health, Double strength, Double critChance, Double critDamage,
                          Double attackSpeed, Double ferocity) {

        /** Only an item with the core trio is a stats item at all (armor lore never qualifies). */
        boolean isStatsItem() {
            return health != null && strength != null && critDamage != null;
        }
    }

    private long lastScanAt;
    private long lastLogAt;

    private PlayerStatsTracker() {
    }

    public static PlayerStatsTracker getInstance() {
        return INSTANCE;
    }

    private static Pattern totalPattern(String name) {
        return Pattern.compile("^" + name + "\\s+([\\d,.]+)");
    }

    private static DamageOverlaySettings cfg() {
        return ConfigManager.getInstance().get().damageOverlay;
    }

    /** Whether a usable snapshot exists (the overlay shows a capture hint until one does). */
    public boolean hasSnapshot() {
        DamageOverlaySettings cfg = cfg();
        return cfg.statsCapturedAt > 0 && cfg.statsVersion == STATS_VERSION;
    }

    /** The gear stats the client can see live: the four worn armor pieces plus the main hand. */
    public static GearStats liveGear(LocalPlayer player) {
        GearStats sum = GearStats.ZERO;
        if (player == null) {
            return sum;
        }
        for (EquipmentSlot slot : ARMOR) {
            sum = sum.plus(GearStats.of(player.getItemBySlot(slot)));
        }
        return sum.plus(GearStats.of(player.getMainHandItem()));
    }

    /**
     * The snapshot re-based onto the gear worn and held right now, or {@code null} without a
     * usable snapshot.
     */
    public DamageCalculator.PlayerStats effectiveStats(LocalPlayer player) {
        if (!hasSnapshot()) {
            return null;
        }
        DamageOverlaySettings cfg = cfg();
        GearStats now = liveGear(player);
        return new DamageCalculator.PlayerStats(
                cfg.statStrength - cfg.gearStrength + now.strength(),
                cfg.statCritChance - cfg.gearCritChance + now.critChance(),
                cfg.statCritDamage - cfg.gearCritDamage + now.critDamage(),
                cfg.statAttackSpeed - cfg.gearAttackSpeed + now.attackSpeed(),
                cfg.statFerocity - cfg.gearFerocity + now.ferocity(),
                cfg.statHealth - cfg.gearHealth + now.health(),
                cfg.combatLevel);
    }

    /** Called once per client tick: mirrors the Combat level, and (throttled) scans stat menus. */
    public void onClientTick() {
        DamageOverlaySettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        if (captureCombatLevelFromSkills(cfg)) {
            ConfigManager.getInstance().save();
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = strip(screen.getTitle() != null ? screen.getTitle().getString() : "")
                .toLowerCase(Locale.ROOT);
        if (!title.contains("skyblock menu") && !title.contains("stats")
                && !title.contains("equipment")) {
            return;
        }
        scan(container.getMenu(), title, now);
    }

    /** The action bar names the skill on every XP gain, so fighting alone reveals the level. */
    private boolean captureCombatLevelFromSkills(DamageOverlaySettings cfg) {
        SkillTracker skills = SkillTracker.getInstance();
        if (!"Combat".equalsIgnoreCase(skills.skill())) {
            return false;
        }
        int level = skills.level();
        if (level <= 0 || level <= cfg.combatLevel) {
            return false;
        }
        cfg.combatLevel = level;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][DmgEst] combat level {} from the action bar", level);
        return true;
    }

    private void scan(AbstractContainerMenu menu, String title, long now) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        DamageOverlaySettings cfg = cfg();
        boolean changed = false;
        boolean captured = false;
        String unparsedSample = null;
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String text = fullText(stack);
            changed |= captureCombatLevel(cfg, text);
            Totals totals = parseTotals(text);
            if (totals.isStatsItem()) {
                changed |= snapshot(cfg, player, totals);
                captured = true;
            } else if (unparsedSample == null && text.contains("Strength")) {
                unparsedSample = strip(stack.getHoverName().getString()) + " :: "
                        + text.replace('\n', '|');
            }
        }
        if (changed) {
            ConfigManager.getInstance().save();
        }
        // Tuning aid while a stats-looking menu is open but nothing captures: one sample lore of a
        // Strength-mentioning item, so the patterns can be fixed against the real format.
        if (!captured && unparsedSample != null && now - lastLogAt > LOG_INTERVAL_MS) {
            lastLogAt = now;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][DmgEst] menu '{}': no stats item parsed | sample: {}",
                    title, unparsedSample);
            if (cfg.debugLog) {
                player.sendSystemMessage(Component.literal(
                        "§8[§bSBS§8]§8[DmgEst] §7no stats item parsed in '" + title
                                + "' - sample logged to latest.log"));
            }
        }
    }

    /** The stat totals of one item: each lore line de-glyphed, then name-anchored number matching. */
    private static Totals parseTotals(String text) {
        Double health = null;
        Double strength = null;
        Double critChance = null;
        Double critDamage = null;
        Double attackSpeed = null;
        Double ferocity = null;
        for (String rawLine : text.split("\n")) {
            // "🗡 Strength 945.1" -> "Strength 945.1": whatever icon glyph Hypixel uses this
            // year, it is not a letter - strip everything up to the first one.
            String line = rawLine.replaceFirst("^[^A-Za-z]+", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (health == null) {
                health = firstNumber(HEALTH, line);
            }
            if (strength == null) {
                strength = firstNumber(STRENGTH, line);
            }
            if (critChance == null) {
                critChance = firstNumber(CRIT_CHANCE, line);
            }
            if (critDamage == null) {
                critDamage = firstNumber(CRIT_DAMAGE, line);
            }
            if (attackSpeed == null) {
                attackSpeed = firstNumber(ATTACK_SPEED, line);
            }
            if (ferocity == null) {
                ferocity = firstNumber(FEROCITY, line);
            }
        }
        return new Totals(health, strength, critChance, critDamage, attackSpeed, ferocity);
    }

    /**
     * Writes one stats item into the snapshot, together with the gear share of the moment. Stats
     * the item does not print keep their previous value (the SkyBlock Menu head hides Attack Speed
     * / Ferocity behind "and more…" – wiping them to 0 there would undo a fuller capture from the
     * Equipment menu's Combat Stats item).
     */
    private boolean snapshot(DamageOverlaySettings cfg, LocalPlayer player, Totals totals) {
        boolean first = !hasSnapshot();
        double health = totals.health();
        double strength = totals.strength();
        double critDamage = totals.critDamage();
        double critChance = totals.critChance() != null ? totals.critChance()
                : first ? 30 : cfg.statCritChance;
        double attackSpeed = totals.attackSpeed() != null ? totals.attackSpeed()
                : first ? 0 : cfg.statAttackSpeed;
        double ferocity = totals.ferocity() != null ? totals.ferocity()
                : first ? 0 : cfg.statFerocity;
        GearStats gear = liveGear(player);
        boolean changed = first || cfg.statHealth != health || cfg.statStrength != strength
                || cfg.statCritDamage != critDamage || cfg.statCritChance != critChance
                || cfg.statAttackSpeed != attackSpeed || cfg.statFerocity != ferocity
                || cfg.gearStrength != gear.strength();
        cfg.statHealth = health;
        cfg.statStrength = strength;
        cfg.statCritChance = critChance;
        cfg.statCritDamage = critDamage;
        cfg.statAttackSpeed = attackSpeed;
        cfg.statFerocity = ferocity;
        cfg.gearStrength = gear.strength();
        cfg.gearCritChance = gear.critChance();
        cfg.gearCritDamage = gear.critDamage();
        cfg.gearAttackSpeed = gear.attackSpeed();
        cfg.gearFerocity = gear.ferocity();
        cfg.gearHealth = gear.health();
        cfg.statsCapturedAt = System.currentTimeMillis();
        cfg.statsVersion = STATS_VERSION;
        if (changed) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][DmgEst] stats captured: str {}, cc {}, cd {}, as {}, fero {}, hp {} "
                            + "| gear share: str {}, cd {} | combat {}",
                    strength, critChance, critDamage, attackSpeed, ferocity, health,
                    gear.strength(), gear.critDamage(), cfg.combatLevel);
            if (cfg.debugLog) {
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "§8[§bSBS§8]§8[DmgEst] §7captured: §cStr %.0f §9CD %.0f%% §9CC %.0f%% "
                                + "§eAS %.0f%% §dFero %.0f §7| gear §cStr %.0f §9CD %.0f%% §7| Combat %d",
                        strength, critDamage, critChance, attackSpeed, ferocity,
                        gear.strength(), gear.critDamage(), cfg.combatLevel)));
            }
        }
        return changed;
    }

    private boolean captureCombatLevel(DamageOverlaySettings cfg, String text) {
        Matcher matcher = COMBAT_LEVEL.matcher(text);
        int best = -1;
        while (matcher.find()) {
            int level = Integer.parseInt(matcher.group(1));
            if (level <= 60) {
                best = Math.max(best, level);
            }
        }
        if (best > cfg.combatLevel) {
            cfg.combatLevel = best;
            return true;
        }
        return false;
    }

    /** Name + full lore of a stack as one stripped string. */
    private static String fullText(ItemStack stack) {
        StringBuilder out = new StringBuilder(strip(stack.getHoverName().getString()));
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                out.append('\n').append(strip(line.getString()));
            }
        }
        return out.toString();
    }

    private static Double firstNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
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
