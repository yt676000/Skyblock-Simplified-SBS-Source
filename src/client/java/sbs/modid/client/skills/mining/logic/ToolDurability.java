/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much is left of a limited-use tool you are holding – the Pickonimbus 2000 and the foraging axes
 * that wear out the same way.
 *
 * <p>These are not vanilla durability. Hypixel writes the remaining uses into the item's own lore
 * ("Pickonimbus 2000: 4,213/5,000"), because the underlying item has no damage value to read, so the
 * only honest source is that line. It is matched by <b>shape</b> rather than by a list of tool names:
 * "&lt;label&gt;: &lt;left&gt;/&lt;max&gt;" is the format Hypixel uses for every one of them, so a
 * tool added next update is covered without a code change – which a name list would not be.
 *
 * <p>Vanilla damage is read as a fallback, so an ordinary damaged tool still gets a bar instead of
 * nothing. The warning fires once per tool per threshold crossing, not once per frame.
 */
public final class ToolDurability {

    private static final ToolDurability INSTANCE = new ToolDurability();

    /** The lore line every limited-use tool carries: a label, then "left/max". */
    private static final Pattern USES_LEFT = Pattern.compile(
            "^(.{2,40}?):\\s*([\\d,.]+)\\s*/\\s*([\\d,.]+)$");

    /**
     * Lore labels that look like the shape above but are stats, not durability. Without this a tool
     * whose lore happens to print a fraction would be drawn as a nearly-empty tool.
     */
    private static final List<String> NOT_DURABILITY = List.of(
            "mana", "health", "defense", "strength", "damage", "speed", "intelligence",
            "crit", "ferocity", "attack", "cooldown", "xp", "experience", "progress");

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** One tool's remaining uses: what the lore called it, how many are left and out of how many. */
    public record Uses(String label, long left, long max) {

        public float fraction() {
            return max <= 0 ? 0f : (float) Math.max(0.0, Math.min(1.0, (double) left / max));
        }

        public int percent() {
            return Math.round(fraction() * 100f);
        }
    }

    private volatile Uses current;
    /** The item the reading belongs to, so a swap re-reads instead of showing the previous tool. */
    private int lastSlot = -1;
    private String lastItemKey = "";
    /** Whether the low-uses warning has already fired for the tool currently in hand. */
    private boolean warned;
    private boolean loggedOnce;

    private ToolDurability() {
    }

    public static ToolDurability getInstance() {
        return INSTANCE;
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    /** Called every client tick. Cheap: it only re-reads when the held item actually changed. */
    public void onClientTick() {
        MiningHelpersSettings settings = cfg();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!settings.enabled || !settings.toolDurability || player == null) {
            current = null;
            return;
        }
        ItemStack held = player.getInventory().getSelectedItem();
        int slot = player.getInventory().getSelectedSlot();
        String key = held.isEmpty() ? "" : held.getHoverName().getString();
        if (slot == lastSlot && key.equals(lastItemKey) && current != null) {
            return;   // same tool as last tick - the lore is re-read only when something changed
        }
        boolean switched = slot != lastSlot || !key.equals(lastItemKey);
        lastSlot = slot;
        lastItemKey = key;
        if (switched) {
            warned = false;
        }
        current = read(held);
        maybeWarn(settings, player);
    }

    /** The held tool's remaining uses, or {@code null} when it has none to report. */
    public Uses current() {
        return current;
    }

    private void maybeWarn(MiningHelpersSettings settings, LocalPlayer player) {
        Uses uses = current;
        if (!settings.toolWarn || uses == null || warned) {
            return;
        }
        if (uses.percent() > Math.max(1, settings.toolWarnPercent)) {
            return;
        }
        warned = true;
        player.sendOverlayMessage(Component.literal(
                "§c" + uses.label() + " §7is at §c" + uses.percent() + "% §7- " + uses.left() + " left"));
        player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 0.7f);
    }

    /**
     * The remaining uses of one stack: its lore's "left/max" line first, then vanilla damage. Returns
     * {@code null} for an item that reports neither.
     */
    private Uses read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (String line : lore(stack)) {
            String plain = strip(line).trim();
            Matcher matcher = USES_LEFT.matcher(plain);
            if (!matcher.matches()) {
                continue;
            }
            String label = matcher.group(1).trim();
            if (isStatLine(label)) {
                continue;
            }
            long left = parse(matcher.group(2));
            long max = parse(matcher.group(3));
            if (left < 0 || max <= 0 || left > max) {
                continue;
            }
            logOnce(plain);
            return new Uses(label, left, max);
        }
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            long max = stack.getMaxDamage();
            return new Uses(stack.getHoverName().getString(), max - stack.getDamageValue(), max);
        }
        return null;
    }

    private static boolean isStatLine(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        for (String stat : NOT_DURABILITY) {
            if (lower.contains(stat)) {
                return true;
            }
        }
        return false;
    }

    /** The stack's lore lines, or empty when it carries none. */
    private static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            out.add(line.getString());
        }
        return out;
    }

    /** One line the first time a durability lore line is recognised, so the shape can be confirmed. */
    private void logOnce(String line) {
        if (loggedOnce) {
            return;
        }
        loggedOnce = true;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Mining] tool uses read from lore line: '{}'", line);
    }

    private static long parse(String raw) {
        try {
            return Long.parseLong(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1L;
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
