/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One loadout card, drawn from plain data: the SBS Loadouts grid, its HUD widget and the Player
 * Viewer's Loadouts / Wardrobe pages all paint through this, so the three can never drift apart.
 *
 * <p>Layout, fixed by the grid it was designed in: label top-left, pet box (gently bobbing) with its
 * name to the right, the four detail boxes (Heart of the Forest, Heart of the Mountain, Power Stone,
 * Tuning) down the left, the four equipment boxes (Necklace, Cloak, Belt, Gloves) down the right, the
 * player model in the middle and the four armour pieces along the bottom. Where the data comes from
 * - the menu's lore, a harvested stack, an API blob - is the caller's business; this class only
 * draws what it is handed and reports which box the cursor is over.
 *
 * <p>The model is drawn by the caller through {@link Preview}, because who the model is and how it
 * is dressed differs per caller (your own preview on the HUD, the viewed player in the viewer).
 */
public final class LoadoutCard {

    /** Inventory-style hover veil laid over a hovered box. */
    public static final int HOVER_VEIL = 0x80FFFFFF;
    /** Detail-column letters: Heart of the Forest, Heart of the Mountain, Power Stone, Tuning. */
    public static final String[] DETAIL_LETTERS = {"F", "M", "P", "T"};
    /** Equipment-column letters: Necklace, Cloak, Belt, Gloves. */
    public static final String[] EQUIP_LETTERS = {"N", "C", "B", "G"};

    /** Model area inset from the card edges, and the y where the columns start. */
    private static final int COLUMNS_Y = 34;

    /**
     * One box on the card. {@code icon} empty draws {@code letter} instead; {@code present} decides
     * whether the box reads as filled; the tooltip is only built when the box is hovered.
     */
    public record Box(ItemStack icon, String letter, boolean present, Supplier<List<Component>> tooltip) {
    }

    /** The pet chip: its head (empty = the bone stand-in), the name drawn beside it, the tooltip. */
    public record Pet(ItemStack icon, String name, Supplier<List<Component>> tooltip) {
    }

    /**
     * Everything one card shows. {@code details} and {@code equipment} hold four boxes each, or are
     * {@code null} to leave that column out (a wardrobe slot has no detail column). {@code armor}
     * holds four stacks, empty for a missing piece; {@code armorTips} builds a piece's tooltip.
     * {@code empty} draws only the frame, the label and {@code emptyText}.
     */
    public record Model(String label, Pet pet, Box[] details, Box[] equipment, ItemStack[] armor,
                        java.util.function.IntFunction<List<Component>> armorTips, boolean empty,
                        String emptyText) {
    }

    /** Draws the player model into the box the card reserves for it. */
    @FunctionalInterface
    public interface Preview {
        void draw(int x1, int y1, int x2, int y2, int scale);
    }

    private LoadoutCard() {
    }

    /** The card's surface colours: grid, HUD widget, or the equipped highlight. */
    public static void frame(GuiGraphicsExtractor g, int cx, int cy, int cardW, int cardH,
                             boolean equipped, boolean hover, boolean onHud) {
        if (equipped) {
            // The active loadout gets a glow + strong border so it reads from across the grid.
            SciFiRender.glow(g, cx + 1, cy + 1, cardW - 2, cardH - 2, SBSTheme.CORNER_RADIUS, 0x5533FF66, 2);
        }
        // On the HUD the theme's HUD card colours; in a grid the translucent card that sits on the
        // panel behind it.
        int bg = equipped ? 0x8033AA44 : hover ? SBSTheme.CARD_BG_HOVER
                : onHud ? SBSTheme.HUD_CARD_BG : 0xA0102640;
        int border = equipped ? 0xFF55FF55 : hover ? SBSTheme.ACCENT_BRIGHT
                : onHud ? SBSTheme.HUD_CARD_BORDER : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, cx + 1, cy + 1, cardW - 2, cardH - 2,
                SBSTheme.CORNER_RADIUS, bg, border);
    }

    /**
     * The card's contents (everything but {@link #frame} and any caller-owned top-row controls).
     * The label is cut to end before {@code labelRight}.
     *
     * @return the tooltip of the box under the cursor, or {@code null} when none is hovered
     */
    public static List<Component> body(GuiGraphicsExtractor g, Font font, Model m, int cx, int cy,
                                       int cardW, int cardH, int labelRight, Preview preview,
                                       int mouseX, int mouseY) {
        List<Component> tip = null;
        String label = font.plainSubstrByWidth(m.label(), labelRight - (cx + 4) - 2, false);
        g.text(font, Component.literal(label), cx + 4, cy + 3, SBSTheme.TEXT_MUTED);

        if (m.empty()) {
            g.centeredText(font, Component.literal(m.emptyText()), cx + cardW / 2, cy + cardH / 2,
                    SBSTheme.TEXT_MUTED);
            return null;
        }

        int leftX = cx + 4;
        int columnsY = cy + COLUMNS_Y;

        // Pet box top-left, gently bobbing up and down; the hover test uses the resting position so
        // the tooltip does not flicker with the bob.
        if (m.pet() != null) {
            ItemStack petIcon = m.pet().icon().isEmpty() ? petFallbackIcon() : m.pet().icon();
            int bob = Math.round((float) Math.sin(System.currentTimeMillis() / 350.0) * 2.0f);
            int petY = columnsY + bob;
            boolean over = mouseX >= leftX && mouseX < leftX + 16 && mouseY >= columnsY && mouseY < columnsY + 16;
            SciFiRender.roundedRectWithBorder(g, leftX, petY, 16, 16, 2, SBSTheme.CARD_BG_HOVER, SBSTheme.ACCENT);
            g.item(petIcon, leftX, petY);
            String petName = font.plainSubstrByWidth(m.pet().name(), (cardW - 26) * 2, false);
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(leftX + 18, petY + 5);
            pose.scale(0.5f, 0.5f);
            int w = font.width(petName);
            g.fill(-2, -2, w + 2, font.lineHeight + 1, 0x66000000);
            g.text(font, Component.literal(petName), 0, 0, 0xFF6FD9FF);
            pose.popMatrix();
            if (over) {
                g.fill(leftX, petY, leftX + 16, petY + 16, HOVER_VEIL);
                tip = m.pet().tooltip().get();
            }
        }

        // Left column under the pet: the four detail boxes.
        if (m.details() != null) {
            for (int i = 0; i < m.details().length; i++) {
                List<Component> t = box(g, font, m.details()[i], leftX, columnsY + (i + 1) * 18, mouseX, mouseY);
                tip = t != null ? t : tip;
            }
        }
        // Right column: the equipment.
        if (m.equipment() != null) {
            for (int i = 0; i < m.equipment().length; i++) {
                List<Component> t = box(g, font, m.equipment()[i], cx + cardW - 20, columnsY + i * 18,
                        mouseX, mouseY);
                tip = t != null ? t : tip;
            }
        }

        if (preview != null) {
            preview.draw(cx + 26, cy + 24, cx + cardW - 26, cy + cardH - 26, Math.max(24, (cardH - 54) / 2));
        }

        // Bottom strip: the four armour pieces as inventory-like hoverable cells.
        if (m.armor() != null) {
            int sx = cx + (cardW - 4 * 18) / 2;
            int sy = cy + cardH - 22;
            for (int i = 0; i < 4; i++) {
                int bx = sx + i * 18;
                boolean over = mouseX >= bx && mouseX < bx + 18 && mouseY >= sy && mouseY < sy + 18;
                SciFiRender.roundedRectWithBorder(g, bx, sy, 17, 17, 2,
                        SBSTheme.CARD_BG, over ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                ItemStack piece = i < m.armor().length ? m.armor()[i] : ItemStack.EMPTY;
                if (!piece.isEmpty()) {
                    g.item(piece, bx + 1, sy + 1);
                }
                if (over) {
                    g.fill(bx + 1, sy + 1, bx + 16, sy + 16, HOVER_VEIL);
                    tip = m.armorTips().apply(i);
                }
            }
        }
        return tip;
    }

    private static List<Component> box(GuiGraphicsExtractor g, Font font, Box box, int bx, int by,
                                       int mouseX, int mouseY) {
        boolean over = mouseX >= bx && mouseX < bx + 16 && mouseY >= by && mouseY < by + 16;
        SciFiRender.roundedRectWithBorder(g, bx, by, 16, 16, 2,
                box.present() ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                box.present() ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
        if (!box.icon().isEmpty()) {
            g.item(box.icon(), bx, by);
        } else {
            g.centeredText(font, Component.literal((box.present() ? "§6" : "§8") + box.letter()),
                    bx + 8, by + 4, SBSTheme.TEXT);
        }
        if (!over) {
            return null;
        }
        g.fill(bx, by, bx + 16, by + 16, HOVER_VEIL);
        return box.tooltip().get();
    }

    // ------------------------------------------------------------------
    // Icons both callers need
    // ------------------------------------------------------------------

    /*
     * Vanilla stand-ins, built lazily - never in a static initialiser. new ItemStack(...) reads the
     * item's components, which are not bound until well into startup: as a static field this made
     * the whole loadouts overlay crash the game with "Components not bound yet" when anything that
     * runs early merely mentioned the class. Lazy, they cost one null check on a path that only runs
     * while a card is drawn.
     */
    private static ItemStack[] detailFallbackIcons;
    private static ItemStack petFallbackIcon;

    /** Stand-in per detail box, shown until a real item is known. */
    public static ItemStack[] detailFallbackIcons() {
        if (detailFallbackIcons == null) {
            detailFallbackIcons = new ItemStack[] {
                new ItemStack(Items.OAK_SAPLING),      // Heart of the Forest
                new ItemStack(Items.DIAMOND_PICKAXE),  // Heart of the Mountain
                new ItemStack(Items.NETHER_STAR),      // Power Stone
                new ItemStack(Items.REDSTONE),         // Stat Tuning
            };
        }
        return detailFallbackIcons;
    }

    /** Stand-in for a pet whose real head has not resolved yet (the Pets menu icon). */
    public static ItemStack petFallbackIcon() {
        if (petFallbackIcon == null) {
            petFallbackIcon = new ItemStack(Items.BONE);
        }
        return petFallbackIcon;
    }

    /**
     * Maxwell power (lower-case) -> the power stone item that unlocks it, extracted from the repo
     * item lores ("Combine 9x of this stone ... to permanently unlock the Silky power"). Lets every
     * card show its power's actual stone instead of one generic slot icon.
     */
    private static final Map<String, String> POWER_STONE_IDS = Map.ofEntries(
            Map.entry("forceful", "ACACIA_BIRDHOUSE"),
            Map.entry("bloody", "BEATING_HEART"),
            Map.entry("bubba", "BUBBA_BLISTER"),
            Map.entry("crumbly", "CHOCOLATE_CHIP"),
            Map.entry("shaded", "DARK_ORB"),
            Map.entry("sanguisuge", "DISPLACED_LEECH"),
            Map.entry("bizarre", "ECCENTRIC_PAINTING"),
            Map.entry("sighted", "ENDER_MONOCLE"),
            Map.entry("adept", "END_STONE_SHULKER"),
            Map.entry("itchy", "FURBALL"),
            Map.entry("frozen", "GLACITE_SHARD"),
            Map.entry("slender", "HAZMAT_ENDERMAN"),
            Map.entry("demonic", "HORNS_OF_TORMENT"),
            Map.entry("silky", "LUXURIOUS_SPOOL"),
            Map.entry("hurtful", "MAGMA_URCHIN"),
            Map.entry("strong", "MANDRAA"),
            Map.entry("mythical", "OBSIDIAN_TABLET"),
            Map.entry("pleasant", "PRECIOUS_PEARL"),
            Map.entry("sweet", "ROCK_CANDY"),
            Map.entry("scorching", "SCORCHED_BOOKS"),
            Map.entry("buttery", "SUNFLOWER_BUTTER"),
            Map.entry("healthy", "VITAMIN_DEATH"),
            Map.entry("unhealthy", "VITAMIN_LIFE"));

    /** Resolved power stone icons by power name; successes only (icons resolve async). */
    private static final Map<String, ItemStack> POWER_STONE_CACHE = new ConcurrentHashMap<>();

    /**
     * The stone item icon for a Maxwell power ("Silky", or the API's "silky") -> Luxurious Spool, or
     * empty while it is still loading or for a power the table does not know.
     */
    public static ItemStack powerStoneIcon(String power) {
        String key = power == null ? "" : power.strip().toLowerCase(Locale.ROOT);
        ItemStack cached = POWER_STONE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String id = POWER_STONE_IDS.get(key);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        ItemStack icon = SkyBlockItemIcons.getInstance().icon(id, power, 1);
        if (icon == null || icon.isEmpty() || icon.is(Items.BARRIER)) {
            return ItemStack.EMPTY;   // still loading - retry next frame
        }
        POWER_STONE_CACHE.put(key, icon);
        return icon;
    }
}
