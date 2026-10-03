/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.enchants.EnchantPoolCache;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.EnchantmentRecipeProvider;
import sbs.modid.client.helper.visual.render.ChromaText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Item Overlay tooltip extras, hooked into {@code Screen.getTooltipFromItem} (runs every frame,
 * so the chroma animation is alive):
 *
 * <ul>
 *   <li><b>Chroma Glint on Maxed Enchantments</b>: enchant names at their highest obtainable
 *       level (known from the bazaar product list, endcaps included) are recoloured per character
 *       with a moving rainbow - visually a chroma plate scrolling behind the text, visible only
 *       through the letters of the maxed enchants. All other styling of the line is preserved.</li>
 *   <li><b>Hold Shift: Missing Enchantments</b>: while SHIFT is held, appends the enchants this
 *       item id can carry (data-driven: every enchant the SBS database has seen on this exact
 *       item, so only genuinely applicable ones) but currently lacks.</li>
 * </ul>
 */
@Mixin(Screen.class)
public abstract class EnchantTooltipMixin {

    private static final int MISSING_HEADER = 0xFF3FB4FF;
    private static final int MISSING_TEXT = 0xFF8FA9C8;

    @Inject(method = "getTooltipFromItem", at = @At("RETURN"), cancellable = true)
    private static void skyblockSimplified$enchantTooltip(Minecraft minecraft, ItemStack stack,
                                                          CallbackInfoReturnable<List<Component>> cir) {
        // Tooltip extras must NEVER crash the render thread - fail silently instead.
        try {
            SBSConfig.ItemOverlaySettings cfg = ConfigManager.getInstance().get().itemOverlay;
            boolean chroma = cfg.chromaMaxedEnchants || cfg.chromaUltimateEnchants;
            boolean missing = cfg.shiftMissingEnchants && sbs$shiftDown();
            boolean dedupe = cfg.hideVanillaEnchantLines;
            if (!chroma && !missing && !dedupe) {
                return;
            }
            List<Component> current = cir.getReturnValue();
            if (current == null || current.isEmpty() || stack == null || stack.isEmpty()) {
                return;
            }
            CompoundTag extra = SkyblockItem.extraAttributes(stack);
            CompoundTag enchants = extra.getCompoundOrEmpty("enchantments");

            List<Component> lines = new ArrayList<>(current);
            boolean changed = false;
            if (dedupe && !extra.isEmpty()) {
                changed |= sbs$dropVanillaEnchantLines(lines, stack);
            }
            if (chroma && !enchants.isEmpty()) {
                changed |= sbs$applyChroma(lines, enchants, cfg, stack);
            }
            if (missing) {
                changed |= sbs$appendMissing(lines, extra, enchants);
            }
            if (changed) {
                cir.setReturnValue(lines);
            }
        } catch (Throwable t) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.debug("[SBS] enchant tooltip failed: {}", t.toString());
        }
    }

    /**
     * Removes the enchantment lines <b>vanilla</b> prints directly under the item name on a SkyBlock
     * item, because Hypixel already lists the same enchant in its own lore further down.
     *
     * <p>This is not an SBS line and not a Hypixel duplicate - it is Minecraft doing exactly what it
     * should. Some SkyBlock enchants are real vanilla ones (Depth Strider, Growth, Protection,
     * Feather Falling), so Hypixel puts a genuine {@code enchantments} component on the item to make
     * it work, and vanilla's tooltip dutifully renders it above the stats. The result is the enchant
     * showing twice, once in the wrong place: an item's own attributes belong under the stats, which
     * is where Hypixel's lore already has it.
     *
     * <p>Only exact matches of what the component would render are dropped, and only the FIRST
     * occurrence of each - so Hypixel's own copy in the enchant block always survives, and a line
     * that merely happens to read the same is untouched.
     */
    private static boolean sbs$dropVanillaEnchantLines(List<Component> lines, ItemStack stack) {
        ItemEnchantments component = stack.get(DataComponents.ENCHANTMENTS);
        if (component == null || component.isEmpty()) {
            return false;
        }
        Set<String> vanillaLines = new LinkedHashSet<>();
        for (var entry : component.entrySet()) {
            vanillaLines.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString().trim());
        }
        boolean changed = false;
        for (String text : vanillaLines) {
            // Skip index 0: that is the item's name, which must never be removed even in the
            // (nonsensical) case of an item named after one of its own enchantments.
            for (int i = 1; i < lines.size(); i++) {
                if (text.equals(lines.get(i).getString().trim())) {
                    lines.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    private static boolean sbs$shiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    // ------------------------------------------------------------------
    // Chroma glint
    // ------------------------------------------------------------------

    /** Display names the lore may use for an enchant NBT key - the shared mapping
     *  (EnchantNames) covers renames like Duplex/Gravity and ultimate-prefix dropping. */
    private static Set<String> sbs$displayNames(String key) {
        return sbs.modid.client.helper.enchants.EnchantNames.displayCandidates(key);
    }

    /** Mark values: which chroma group a character belongs to. */
    private static final byte GROUP_NONE = 0;
    private static final byte GROUP_NORMAL = 1;
    private static final byte GROUP_ULTIMATE = 2;

    /**
     * Single-entry cache of the <b>text analysis</b> (which characters of which lines are part of a
     * maxed enchant name). This method runs at {@code getTooltipFromItem} RETURN, i.e. once per
     * frame for as long as an item is hovered, and the analysis is the expensive half: a
     * lower-casing plus a substring sweep of every enchant name over every lore line, every frame,
     * for a result that cannot change while the same stack is hovered. Only the recolouring - which
     * genuinely does change per frame, that is the animation - runs unconditionally.
     *
     * <p>Identity comparison is deliberate: while a slot is hovered the very same {@code ItemStack}
     * object comes back each frame, and moving to any other item swaps the reference, so identity
     * is both the cheapest and the most accurate invalidation signal available here.
     */
    private static ItemStack sbs$markCacheStack;
    private static int sbs$markCacheFingerprint;
    private static byte[][] sbs$markCache;

    private static boolean sbs$applyChroma(List<Component> lines, CompoundTag enchants,
                                           SBSConfig.ItemOverlaySettings cfg, ItemStack stack) {
        // Which groups are live this frame - also the cache key, so toggling a group in the
        // settings while an item is hovered re-analyses instead of showing a stale marking.
        int fingerprint = (cfg.chromaMaxedEnchants ? 1 : 0) | (cfg.chromaUltimateEnchants ? 2 : 0)
                | (lines.size() << 2);

        byte[][] marks;
        if (sbs$markCacheStack == stack && sbs$markCacheFingerprint == fingerprint
                && sbs$markCache != null && sbs$markCache.length == lines.size()) {
            marks = sbs$markCache;
        } else {
            marks = sbs$analyse(lines, enchants, cfg);
            sbs$markCacheStack = stack;
            sbs$markCacheFingerprint = fingerprint;
            sbs$markCache = marks;
        }
        if (marks == null) {
            return false;
        }

        ChromaText.Params normal = new ChromaText.Params(
                cfg.chromaEnchantSpread, cfg.chromaEnchantSaturation, cfg.chromaFlowAcrossLines);
        ChromaText.Params ultimate = new ChromaText.Params(
                cfg.chromaUltimateSpread, cfg.chromaUltimateSaturation, cfg.chromaFlowAcrossLines);

        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            if (marks[i] == null) {
                continue;
            }
            lines.set(i, sbs$rebuildWithChroma(lines.get(i), marks[i], i, normal, ultimate));
            changed = true;
        }
        return changed;
    }

    /**
     * Finds the maxed-enchant names in the lore and tags their characters with the group they
     * belong to. Returns {@code null} when nothing is marked at all.
     *
     * <p><b>Ultimate classification does not read the lore.</b> The enchants are taken straight
     * from the item's {@code ExtraAttributes.enchantments} compound, whose keys carry Hypixel's own
     * {@code ultimate_} prefix ({@link sbs.modid.client.helper.enchants.EnchantNames#isUltimate}) -
     * so the split is exact and needs no guessing at colours or bold runs in rendered text, which
     * would break the moment a resource pack or a Hypixel restyle changed them.
     */
    private static byte[][] sbs$analyse(List<Component> lines, CompoundTag enchants,
                                        SBSConfig.ItemOverlaySettings cfg) {
        // Display name -> the item's ACTUAL level, for every enchant at its known max, split by
        // group so a disabled group is never even searched for.
        Map<String, Integer> maxedNames = new java.util.LinkedHashMap<>();
        Set<String> ultimateNames = new java.util.HashSet<>();
        for (String key : enchants.keySet()) {
            boolean isUltimate = sbs.modid.client.helper.enchants.EnchantNames.isUltimate(key);
            if (isUltimate ? !cfg.chromaUltimateEnchants : !cfg.chromaMaxedEnchants) {
                continue;
            }
            int level = enchants.getIntOr(key, 0);
            int max = EnchantmentRecipeProvider.maxLevelOf(key);
            if (max > 0 && level >= max) {
                for (String name : sbs$displayNames(key)) {
                    maxedNames.put(name, level);
                    if (isUltimate) {
                        ultimateNames.add(name);
                    }
                }
            }
        }
        if (maxedNames.isEmpty()) {
            return null;
        }
        byte[][] marks = new byte[lines.size()][];
        boolean anyLine = false;
        for (int i = 0; i < lines.size(); i++) {
            String plain = lines.get(i).getString();
            String lower = plain.toLowerCase(Locale.ROOT);
            byte[] mark = new byte[plain.length()];
            boolean any = false;
            for (Map.Entry<String, Integer> entry : maxedNames.entrySet()) {
                String name = entry.getKey();
                int expectedLevel = entry.getValue();
                byte group = ultimateNames.contains(name) ? GROUP_ULTIMATE : GROUP_NORMAL;
                int from = 0;
                int at;
                while ((at = lower.indexOf(name, from)) >= 0) {
                    from = at + 1;
                    // Word boundary before the match ("Chance" must not fire in "Crit Chance"
                    // stat text is filtered below via the level check; "enchance" here).
                    if (at > 0 && Character.isLetter(plain.charAt(at - 1))) {
                        continue;
                    }
                    // REQUIRE the enchant's actual level right after the name ("Duplex V"),
                    // as Roman numerals or digits - stat lines ("Crit Chance: +50%") and
                    // ability text ("50% chance to...") carry no matching level token, so
                    // they can never be recoloured by accident.
                    int end = at + name.length();
                    if (end >= plain.length() || plain.charAt(end) != ' ') {
                        continue;
                    }
                    int tokenEnd = end + 1;
                    while (tokenEnd < plain.length()
                            && "IVXLCivxlc0123456789".indexOf(plain.charAt(tokenEnd)) >= 0) {
                        tokenEnd++;
                    }
                    String token = plain.substring(end + 1, tokenEnd);
                    if (token.isEmpty() || sbs$levelOf(token) != expectedLevel) {
                        continue;
                    }
                    // Ultimate enchants render a trailing period ("Duplex V.") - chroma it too.
                    if (tokenEnd < plain.length() && plain.charAt(tokenEnd) == '.') {
                        tokenEnd++;
                    }
                    for (int p = at; p < tokenEnd && p < mark.length; p++) {
                        mark[p] = group;
                    }
                    any = true;
                }
            }
            if (any) {
                marks[i] = mark;
                anyLine = true;
            }
        }
        return anyLine ? marks : null;
    }

    /** "VII" / "vii" / "7" -> 7; unknown tokens -> -1. */
    private static int sbs$levelOf(String token) {
        String t = token.toUpperCase(Locale.ROOT);
        if (t.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return switch (t) {
            case "I" -> 1; case "II" -> 2; case "III" -> 3; case "IV" -> 4; case "V" -> 5;
            case "VI" -> 6; case "VII" -> 7; case "VIII" -> 8; case "IX" -> 9; case "X" -> 10;
            default -> -1;
        };
    }

    /**
     * Rebuilds one line preserving every original style, but recolouring the marked character
     * ranges with the moving rainbow. The hue advances per character and scrolls with time -
     * the "chroma plate behind the letters" look.
     */
    /**
     * Rebuilds one line preserving every original style, but recolouring the marked character
     * ranges with the moving gradient of whichever group marked them.
     *
     * <p>The sweep is indexed by the character's position in the <i>whole line</i>, not within the
     * matched name, so a name split across two styled segments (Hypixel does this freely) keeps one
     * continuous gradient rather than restarting mid-word.
     */
    private static Component sbs$rebuildWithChroma(Component line, byte[] mark, int lineIndex,
                                                   ChromaText.Params normal,
                                                   ChromaText.Params ultimate) {
        List<String> texts = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        line.visit((style, text) -> {
            texts.add(text);
            styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);

        MutableComponent out = Component.empty();
        int pos = 0;
        for (int s = 0; s < texts.size(); s++) {
            String text = texts.get(s);
            Style style = styles.get(s);
            StringBuilder run = new StringBuilder();
            byte runGroup = GROUP_NONE;
            int runStart = pos;
            for (int c = 0; c < text.length(); c++) {
                byte group = pos < mark.length ? mark[pos] : GROUP_NONE;
                if (c == 0) {
                    runGroup = group;
                } else if (group != runGroup) {
                    sbs$appendRun(out, run.toString(), style, runGroup, runStart, lineIndex,
                            normal, ultimate);
                    run.setLength(0);
                    runStart = pos;
                    runGroup = group;
                }
                run.append(text.charAt(c));
                pos++;
            }
            if (run.length() > 0) {
                sbs$appendRun(out, run.toString(), style, runGroup, runStart, lineIndex,
                        normal, ultimate);
            }
        }
        return out;
    }

    /** Appends one run: untouched when unmarked, otherwise its group's animated gradient. */
    private static void sbs$appendRun(MutableComponent out, String text, Style style, byte group,
                                      int startPos, int lineIndex, ChromaText.Params normal,
                                      ChromaText.Params ultimate) {
        if (group == GROUP_NONE) {
            out.append(Component.literal(text).withStyle(style));
            return;
        }
        ChromaText.appendGradient(out, text, style, startPos, lineIndex,
                group == GROUP_ULTIMATE ? ultimate : normal);
    }

    // ------------------------------------------------------------------
    // Missing enchantments (SHIFT held)
    // ------------------------------------------------------------------

    private static boolean sbs$appendMissing(List<Component> lines, CompoundTag extra,
                                             CompoundTag enchants) {
        String itemId = extra.getStringOr("id", "");
        if (itemId.isEmpty() || "ENCHANTED_BOOK".equalsIgnoreCase(itemId)
                || "PET".equalsIgnoreCase(itemId) || "RUNE".equalsIgnoreCase(itemId)) {
            return false;
        }
        Map<String, Integer> pool = EnchantPoolCache.getInstance().get(itemId);
        lines.add(Component.empty());
        if (pool == null) {
            lines.add(Component.literal("Missing Enchants: loading...")
                    .withStyle(style -> style.withColor(TextColor.fromRgb(MISSING_TEXT & 0xFFFFFF))));
            return true;
        }
        // Only one ultimate fits per item: once any ultimate is present, other ultimates are
        // not "missing" but alternatives - keep them out of the list.
        boolean hasUltimate = false;
        for (String key : enchants.keySet()) {
            if (sbs.modid.client.helper.enchants.EnchantNames.isUltimate(key)) {
                hasUltimate = true;
                break;
            }
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> e : pool.entrySet()) {
            if (enchants.contains(e.getKey())) {
                continue;
            }
            if (hasUltimate && sbs.modid.client.helper.enchants.EnchantNames.isUltimate(e.getKey())) {
                continue;
            }
            // Show the enchant's real max level (bazaar registry, endcaps included) - the pool's
            // "max seen on this item" undershoots on thin data ("Fatal Tempo 4" vs real max 5).
            int level = Math.max(e.getValue(),
                    EnchantmentRecipeProvider.maxLevelOf(e.getKey()));
            missing.add(sbs.modid.client.helper.enchants.EnchantNames.displayName(e.getKey()) + " " + level);
        }
        if (missing.isEmpty()) {
            lines.add(Component.literal("Missing Enchants: none - fully enchanted")
                    .withStyle(style -> style.withColor(TextColor.fromRgb(0x57D977))));
            return true;
        }
        lines.add(Component.literal("Missing Enchants (" + missing.size() + ", max seen):")
                .withStyle(style -> style.withColor(TextColor.fromRgb(MISSING_HEADER & 0xFFFFFF))));
        StringBuilder row = new StringBuilder();
        for (String entry : missing) {
            if (row.length() > 0 && row.length() + entry.length() > 44) {
                sbs$addMuted(lines, row.toString());
                row.setLength(0);
            }
            if (row.length() > 0) {
                row.append(", ");
            }
            row.append(entry);
        }
        if (row.length() > 0) {
            sbs$addMuted(lines, row.toString());
        }
        return true;
    }

    private static void sbs$addMuted(List<Component> lines, String text) {
        lines.add(Component.literal("  " + text)
                .withStyle(style -> style.withColor(TextColor.fromRgb(MISSING_TEXT & 0xFFFFFF))));
    }

}
