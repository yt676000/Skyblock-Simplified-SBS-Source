/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.model;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The combat stats one piece of gear grants, read off its lore – the <b>bonus</b> form
 * ({@code "Strength: +150"}), as opposed to the totals form the stat menus print
 * ({@code "Strength 945.1"}).
 *
 * <p>This is what lets the Damage Overlay follow gear changes without re-opening a menu: the menu
 * totals include whatever was worn and held at capture time, so subtracting the gear stats of that
 * moment leaves the <b>base</b> (skills, accessories / magical power, pets, potions – everything
 * not swappable in place), and adding the gear stats of the current setup back gives the live
 * total. Swap armor or weapons, or load a different loadout, and the estimate follows.
 *
 * <p>Reforge and gem bonuses are printed as parenthesised additions after the main value
 * ({@code "Strength: +150 (+30)"}); every {@code (+n)} on the line is summed into the stat, so a
 * reforged, gemmed piece reports its true contribution. A leading icon glyph (Hypixel's menus have
 * changed glyph sets more than once) is stripped before matching.
 */
public record GearStats(double damage, double strength, double critChance, double critDamage,
                        double attackSpeed, double ferocity, double health) {

    public static final GearStats ZERO = new GearStats(0, 0, 0, 0, 0, 0, 0);

    /**
     * One stat bonus line. "Bonus Attack Speed" precedes "Attack Speed" because regex alternation
     * is ordered and the shorter name would otherwise match first and leave "Bonus " unconsumed.
     */
    private static final Pattern STAT_LINE = Pattern.compile(
            "^(Damage|Strength|Crit Chance|Crit Damage|Bonus Attack Speed|Attack Speed|Ferocity|Health)"
                    + ":\\s*([+-]?[\\d,.]+)");
    /** A parenthesised reforge / gem addition on the same line: "(+30)". */
    private static final Pattern EXTRA = Pattern.compile("\\(\\s*\\+([\\d,.]+)");

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** The stats of one item, {@link #ZERO} for an empty stack or one with no stat lines. */
    public static GearStats of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ZERO;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return ZERO;
        }
        double damage = 0;
        double strength = 0;
        double critChance = 0;
        double critDamage = 0;
        double attackSpeed = 0;
        double ferocity = 0;
        double health = 0;
        for (Component component : lore.lines()) {
            // Strip formatting, then any leading icon glyph - the stat name must start the line.
            String line = strip(component.getString()).replaceFirst("^[^A-Za-z]+", "").trim();
            Matcher matcher = STAT_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            double value = number(matcher.group(2));
            Matcher extra = EXTRA.matcher(line.substring(matcher.end()));
            while (extra.find()) {
                value += number(extra.group(1));
            }
            switch (matcher.group(1)) {
                case "Damage" -> damage += value;
                case "Strength" -> strength += value;
                case "Crit Chance" -> critChance += value;
                case "Crit Damage" -> critDamage += value;
                case "Bonus Attack Speed", "Attack Speed" -> attackSpeed += value;
                case "Ferocity" -> ferocity += value;
                case "Health" -> health += value;
                default -> { }
            }
        }
        return new GearStats(damage, strength, critChance, critDamage, attackSpeed, ferocity, health);
    }

    public GearStats plus(GearStats other) {
        return new GearStats(damage + other.damage, strength + other.strength,
                critChance + other.critChance, critDamage + other.critDamage,
                attackSpeed + other.attackSpeed, ferocity + other.ferocity, health + other.health);
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text.replace(",", ""));
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
