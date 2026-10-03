/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Hypixel SkyBlock item rarities, each with the colour Hypixel paints it in — used to render the
 * overlay marker and, read backwards, to recover a rarity from a coloured item name.
 *
 * <p>Detection has two steps, tried in this order:
 *
 * <ol>
 *   <li><b>The rarity lore line.</b> Hypixel spells the rarity out in block capitals on the item's
 *       last lore line ("LEGENDARY SWORD", "MYTHIC DUNGEON ITEM", "VERY SPECIAL"). The scan runs
 *       bottom-up and matches <b>case-sensitively and anchored at the start of the line</b>. That
 *       casing is the whole discriminator: an ability that reads "Grants a Rare drop chance" is
 *       prose, and an upper-cased {@code contains("RARE")} test reads it as a RARE item — which is
 *       how a Common material ended up wearing a rare-blue marker.</li>
 *   <li><b>The name colour.</b> An item whose lore carries no rarity line still wears its rarity as
 *       the colour of its display name ("§6[Lvl 63] Ender Dragon" → gold → LEGENDARY). Pets are the
 *       standard case, but the fallback is general: every SkyBlock item's name is painted in its
 *       rarity's colour.</li>
 * </ol>
 *
 * <p>The colour fallback applies only to <b>real SkyBlock items</b> (ones carrying an
 * {@code ExtraAttributes.id}, plus pets, whose "[Lvl n]" name is their own proof). Menu furniture is
 * coloured too — "§aGo Back", "§cClose" — and without that gate every button in every Hypixel menu
 * would be tinted as an Uncommon or Special item.
 */
public enum Rarity {

    DIVINE(0xFF55FFFF, 'b'),
    SPECIAL(0xFFFF5555, 'c'),   // also covers "VERY SPECIAL"
    MYTHIC(0xFFFF55FF, 'd'),
    LEGENDARY(0xFFFFAA00, '6'),
    EPIC(0xFFAA00AA, '5'),
    RARE(0xFF5555FF, '9'),
    UNCOMMON(0xFF55FF55, 'a'),
    COMMON(0xFFFFFFFF, 'f');

    /** A legacy § marker and the code character after it, stripped before any text is matched. */
    private static final Pattern FORMATTING = Pattern.compile("\\u00a7.");

    /** A legacy colour code, e.g. {@code §6}. Formatting codes (k-o, r) are not colours. */
    private static final Pattern COLOR_CODE = Pattern.compile("\\u00a7([0-9a-fA-F])");

    /** A pet's variable level prefix — the one item whose name proves it is a SkyBlock item. */
    private static final Pattern PET_LEVEL = Pattern.compile("(?i)\\[Lvl\\s*[0-9]+]");

    /**
     * The rarity line: optional leading symbols (a shiny "❈", a dungeon star), the optional "VERY "
     * of VERY SPECIAL, then the rarity word itself, in capitals, not followed by another letter.
     * What comes after is the item type ("SWORD", "DUNGEON HELMET") and, on a recombobulated item,
     * the obfuscated marker character — neither of which needs matching.
     */
    private static final Pattern RARITY_LINE = rarityLinePattern();

    /** Hypixel's menu grey. Not a rarity colour, but a grey name has never been above Common. */
    private static final int GREY_RGB = 0xAAAAAA;

    private static final int RGB_MASK = 0xFFFFFF;

    private final int color;
    private final char code;

    Rarity(int color, char code) {
        this.color = color;
        this.code = code;
    }

    /** ARGB color for the rarity marker. */
    public int color() {
        return color;
    }

    /** Detects an item's rarity from its lore, falling back to the colour of its name. */
    public static Rarity detect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            // The rarity is on the last lore line; scan bottom-up so the lines an auction or menu
            // appends below it (seller, price, "Click to inspect") are passed over first.
            for (int i = lore.lines().size() - 1; i >= 0; i--) {
                Rarity rarity = fromLoreLine(lore.lines().get(i).getString());
                if (rarity != null) {
                    return rarity;
                }
            }
        }
        return fromNameColor(stack);
    }

    /**
     * The rarity an item's name colour implies, or {@code null} — for items with no rarity lore line
     * (pets) and for the ones whose line Hypixel words in a way this build does not know.
     *
     * <p>Reads the name component the server sent rather than {@code getHoverName}, so the item
     * renamer's display hook cannot feed its own text back in here.
     */
    public static Rarity fromNameColor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) {
            name = stack.get(DataComponents.ITEM_NAME);
        }
        if (name == null) {
            return null;   // a vanilla item: no Hypixel name, so no rarity to read
        }
        // Hypixel sends its names with legacy § codes in the text; a renamed one keeps only the
        // component's own style, so either side may be the one carrying the colour.
        String raw = name.getString();
        Rarity colour = fromColorCodes(raw);
        if (colour == null) {
            colour = fromStyleColor(name);
        }
        return colour != null && isSkyblockItem(stack, raw) ? colour : null;
    }

    /**
     * Whether a coloured name may be read as a rarity at all. Menu furniture is coloured too, so
     * without this every "§aGo Back" and "§cClose" in every Hypixel menu would wear a rarity marker.
     *
     * <p>The component lookup is asked first because reading the SkyBlock id copies the whole
     * custom-data tag, and this runs once per slot per frame — furniture usually carries no custom
     * data at all and is rejected without the copy.
     */
    private static boolean isSkyblockItem(ItemStack stack, String rawName) {
        if (stack.has(DataComponents.CUSTOM_DATA) && SkyblockItem.id(stack) != null) {
            return true;
        }
        return PET_LEVEL.matcher(rawName).find();   // a pet proves itself by its level prefix
    }

    /** The rarity one lore line states, or {@code null} when the line is not a rarity line. */
    static Rarity fromLoreLine(String line) {
        if (line == null) {
            return null;
        }
        String text = FORMATTING.matcher(line).replaceAll("").trim();
        Matcher matcher = RARITY_LINE.matcher(text);
        return matcher.find() ? valueOf(matcher.group(1)) : null;
    }

    /**
     * The rarity of the first legacy colour code in a name that maps to one. Later codes are tried
     * because a name may open with a colourless prefix ("§8✪✪✪ §6Necron's Blade"); grey is only
     * honoured once nothing else has matched.
     */
    static Rarity fromColorCodes(String rawName) {
        if (rawName == null) {
            return null;
        }
        boolean grey = false;
        Matcher matcher = COLOR_CODE.matcher(rawName);
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            if (code == '7') {
                grey = true;
                continue;
            }
            Rarity rarity = fromColorCode(code);
            if (rarity != null) {
                return rarity;
            }
        }
        return grey ? COMMON : null;
    }

    /** The rarity a legacy colour code stands for, or {@code null} if it is not a rarity colour. */
    static Rarity fromColorCode(char code) {
        char lower = Character.toLowerCase(code);
        for (Rarity rarity : values()) {
            if (rarity.code == lower) {
                return rarity;
            }
        }
        return null;
    }

    /** The rarity an RGB value stands for, or {@code null} — the same table, as the client sees it. */
    static Rarity fromRgb(int rgb) {
        int color = rgb & RGB_MASK;
        for (Rarity rarity : values()) {
            if ((rarity.color & RGB_MASK) == color) {
                return rarity;
            }
        }
        return null;
    }

    /** The colour carried by the name's style rather than by § codes (a renamed item's case). */
    private static Rarity fromStyleColor(Component name) {
        Rarity[] found = new Rarity[1];
        boolean[] grey = new boolean[1];
        name.visit((style, text) -> {
            TextColor color = style.getColor();
            if (text.isEmpty() || color == null) {
                return Optional.empty();
            }
            if ((color.getValue() & RGB_MASK) == GREY_RGB) {
                grey[0] = true;
                return Optional.empty();
            }
            Rarity rarity = fromRgb(color.getValue());
            if (rarity == null) {
                return Optional.empty();
            }
            found[0] = rarity;
            return Optional.of(Boolean.TRUE);   // a non-empty result stops the walk
        }, Style.EMPTY);
        if (found[0] != null) {
            return found[0];
        }
        return grey[0] ? COMMON : null;
    }

    /** Builds {@link #RARITY_LINE} from the constants, so a new rarity is one line in this enum. */
    private static Pattern rarityLinePattern() {
        StringBuilder words = new StringBuilder();
        for (Rarity rarity : values()) {
            if (words.length() > 0) {
                words.append('|');
            }
            words.append(rarity.name());
        }
        return Pattern.compile("^[^A-Za-z]*(?:VERY )?(" + words + ")(?![A-Za-z])");
    }
}
