/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.mining.logic.HotmCatalog;
import sbs.modid.client.skills.foraging.logic.HotfCatalog;
import sbs.modid.client.skills.foraging.model.HotfData;
import sbs.modid.client.skills.mining.model.HotmData;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mining ▸ HotM and Foraging ▸ HotF: a read-only view of the viewed player's Heart of the Mountain
 * and Heart of the Forest, from {@code skill_tree}.
 *
 * <p><b>Heart of the Mountain</b> is drawn as a tree, placing each perk at the (tier, menu column)
 * the HotM perk advisor's catalogue ({@link HotmCatalog}) recorded from the real menu - tiers 1-9
 * captured, tier 10 still {@code ESTIMATED} as a block with no columns, so it is drawn as a separate
 * row and labelled. A perk id the catalogue does not know is listed under the tree with its level,
 * never guessed onto a position.
 *
 * <p><b>Heart of the Forest</b> is drawn the same way from {@link HotfCatalog}: tiers 1-8 recorded
 * from the real menu. Its perk ids are derived from the perk names and marked as such; an API id
 * that matches no node is listed under the tree, never placed.
 *
 * <p>Both trees have up to five presets ({@code mining}, {@code mining_2}...); the preset chips
 * switch between them, and the one the player has selected is marked.
 */
public final class HeartTreePage implements PvPage {

    public enum Mode { MOUNTAIN, FOREST }

    private static final int CHIP = 16;
    private static final int COLUMNS = 9;
    private static final int MAX_CELL = 18;
    private static final int LIST_ROW = 11;
    private static final int MAXED = 0xFFFFAA00;
    private static final int LEVELLED = 0xFF55FF55;
    private static final int OFF = 0xFFFF5555;

    private final Mode mode;
    /** The selected ability of the tree being drawn this frame. */
    private String ability = "";
    /** Preset shown, 1-based; 0 = the player's selected one. */
    private int preset;

    public HeartTreePage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void reset() {
        preset = 0;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject tree = PvDraw.obj(PvDraw.obj(ctx.profile, "heart_trees"), mode == Mode.MOUNTAIN ? "mountain" : "forest");
        String name = mode == Mode.MOUNTAIN ? "Heart of the Mountain" : "Heart of the Forest";
        if (tree == null) {
            PvDraw.empty(g, ctx, "No " + name + " data.");
            return;
        }
        JsonArray presets = PvDraw.arr(tree, "presets");
        int selected = PvDraw.has(tree, "selected_slot") ? (int) PvDraw.num(tree, "selected_slot") : 1;
        int shown = preset > 0 ? preset : selected;
        JsonObject current = presetObject(presets, shown);
        ability = PvDraw.has(tree, "ability") ? PvDraw.str(tree, "ability") : "";

        int y = ctx.y;
        y = drawChips(g, ctx, presets, shown, selected, y);

        // Summary line(s): ability, tokens, XP - wrapped rather than run off the panel.
        List<String> facts = new ArrayList<>();
        if (PvDraw.has(tree, "ability")) {
            facts.add("§7Ability §f" + PvDraw.pretty(PvDraw.str(tree, "ability")));
        }
        if (current != null && PvDraw.has(current, "tokens_spent")) {
            facts.add("§7Tokens spent §f" + PvDraw.fmt(PvDraw.num(current, "tokens_spent")));
        }
        if (PvDraw.has(tree, "xp")) {
            facts.add(tierFact(PvDraw.num(tree, "xp")));
        }
        if (mode == Mode.MOUNTAIN) {
            facts.add("§7Powder: §8Mining ▸ Powder");
        }
        y = wrapFacts(g, ctx, facts, y) + 3;

        if (current == null) {
            g.text(ctx.font, Component.literal("§8Preset " + shown + " has no perks."), ctx.x, y, SBSTheme.TEXT_MUTED);
            return;
        }
        drawTree(g, ctx, current, y, nodes());
    }

    /**
     * Cumulative XP at which each tier is reached (index = tier - 1). The API stores the lifetime
     * total, which reads like a balance on its own, so the page shows the tier and the progress to
     * the next one instead - the way the menu does.
     *
     * <p>HotM: the community wiki's table (WIKI); its tier 10 total, 1,247,000, is exactly what a
     * maxed profile returned (2026-10-08). HotF: tiers 1-7 are assumed to share it (ESTIMATED);
     * the step to tier 8, 200,000, is CONFIRMED from the menu ("0/200k" at 347,000). Higher HotF
     * tiers are unknown and are not shown.
     */
    private static final long[] HOTM_TIERS = {0, 3_000, 12_000, 37_000, 97_000, 197_000, 347_000,
            557_000, 847_000, 1_247_000};
    private static final long[] HOTF_TIERS = {0, 3_000, 12_000, 37_000, 97_000, 197_000, 347_000, 547_000};

    private String tierFact(long xp) {
        long[] tiers = mode == Mode.MOUNTAIN ? HOTM_TIERS : HOTF_TIERS;
        int tier = 1;
        while (tier < tiers.length && xp >= tiers[tier]) {
            tier++;
        }
        String total = " §8(" + PvDraw.fmt(xp) + " XP total)";
        if (tier >= tiers.length && mode == Mode.MOUNTAIN) {
            return "§7Tier §f" + tier + " §a(max)" + total;
        }
        if (tier >= tiers.length) {
            return "§7Tier §f" + tier + "+" + total;
        }
        long into = xp - tiers[tier - 1];
        long need = tiers[tier] - tiers[tier - 1];
        return "§7Tier §f" + tier + " §8• §f" + PvDraw.fmt(into) + "§8/" + PvDraw.fmt(need) + " §7to " + (tier + 1);
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    private static JsonObject presetObject(JsonArray presets, int slot) {
        for (JsonElement el : presets) {
            if (PvDraw.num(el.getAsJsonObject(), "slot") == slot) {
                return el.getAsJsonObject();
            }
        }
        return null;
    }

    /** One chip per preset that has data; the selected preset carries a dot. Returns the next y. */
    private int drawChips(GuiGraphicsExtractor g, PvContext ctx, JsonArray presets, int shown, int selected, int y) {
        if (presets.size() <= 1) {
            return y;
        }
        g.text(ctx.font, Component.literal("§7Preset"), ctx.x, y + (CHIP - ctx.font.lineHeight) / 2, SBSTheme.TEXT);
        int x = ctx.x + ctx.font.width("Preset") + 6;
        for (int i = 0; i < presets.size(); i++) {
            int slot = (int) PvDraw.num(presets.get(i).getAsJsonObject(), "slot");
            int cx = x + i * (CHIP + 3);
            boolean sel = slot == shown;
            boolean hov = ctx.hovered(cx, y, CHIP, CHIP);
            SciFiRender.roundedRectWithBorder(g, cx, y, CHIP, CHIP, 3,
                    sel || hov ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    sel ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            g.centeredText(ctx.font, Component.literal(String.valueOf(slot)), cx + CHIP / 2,
                    y + (CHIP - ctx.font.lineHeight) / 2, sel ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            if (slot == selected) {
                g.fill(cx + CHIP - 4, y + 1, cx + CHIP - 1, y + 4, LEVELLED);
            }
            if (hov) {
                PvDraw.tooltip(g, ctx.font, List.of(Component.literal("§fPreset " + slot),
                        Component.literal(slot == selected ? "§aselected by the player" : "§8not selected")),
                        ctx.mouseX, ctx.mouseY);
            }
        }
        return y + CHIP + 4;
    }

    @Override
    public boolean mouseClicked(PvContext ctx, double mouseX, double mouseY) {
        JsonObject tree = PvDraw.obj(PvDraw.obj(ctx.profile, "heart_trees"), mode == Mode.MOUNTAIN ? "mountain" : "forest");
        JsonArray presets = PvDraw.arr(tree, "presets");
        if (presets.size() <= 1) {
            return false;
        }
        int x = ctx.x + ctx.font.width("Preset") + 6;
        for (int i = 0; i < presets.size(); i++) {
            int cx = x + i * (CHIP + 3);
            if (mouseX >= cx && mouseX < cx + CHIP && mouseY >= ctx.y && mouseY < ctx.y + CHIP) {
                preset = (int) PvDraw.num(presets.get(i).getAsJsonObject(), "slot");
                return true;
            }
        }
        return false;
    }

    private static int wrapFacts(GuiGraphicsExtractor g, PvContext ctx, List<String> facts, int y) {
        int x = ctx.x;
        String sep = "  ";
        for (String fact : facts) {
            int w = ctx.font.width(fact);
            if (x > ctx.x && x + w > ctx.right()) {
                x = ctx.x;
                y += ctx.font.lineHeight + 2;
            }
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, fact, ctx.width)), x, y, SBSTheme.TEXT);
            x += w + ctx.font.width(sep);
        }
        return y + ctx.font.lineHeight + 2;
    }

    // ------------------------------------------------------------------
    // Heart of the Mountain tree
    // ------------------------------------------------------------------

    /**
     * One node of either tree. {@code column < 0} = position not captured (HotM tier 10);
     * {@code certainty} is how sure the node itself is, {@code idCertainty} whether its id is the
     * one the profile API uses.
     */
    private record Node(String id, String name, int tier, int column, int maxLevel, Certainty certainty,
                        Certainty idCertainty, String kind) {
    }

    private List<Node> nodes() {
        List<Node> out = new ArrayList<>();
        if (mode == Mode.MOUNTAIN) {
            for (HotmData.Perk p : HotmCatalog.perks()) {
                out.add(new Node(p.id, p.name, p.tier, p.column, p.maxLevel, p.certainty(), Certainty.CONFIRMED,
                        p.kind == null ? "" : p.kind.name()));
            }
        } else {
            for (HotfData.Perk p : HotfCatalog.perks()) {
                out.add(new Node(p.id, p.name, p.tier, p.column, p.maxLevel, Certainty.CONFIRMED, p.idCertainty(), ""));
            }
        }
        return out;
    }

    private void drawTree(GuiGraphicsExtractor g, PvContext ctx, JsonObject current, int top, List<Node> nodes) {
        JsonObject perks = PvDraw.obj(current, "perks");
        Set<String> disabled = new HashSet<>();
        PvDraw.arr(current, "disabled").forEach(e -> disabled.add(e.getAsString().toLowerCase(java.util.Locale.ROOT)));

        // Tiers from the catalogue; tier 10's perks have no column and go in a row of their own.
        int maxTier = 0;
        List<Node> unplacedTier = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Node perk : nodes) {
            known.add(perk.id());
            if (perk.column() < 0) {
                unplacedTier.add(perk);
            } else {
                maxTier = Math.max(maxTier, perk.tier());
            }
        }
        // API perks the catalogue has no entry for: listed, never placed.
        List<String> unknown = new ArrayList<>();
        if (perks != null) {
            for (Map.Entry<String, JsonElement> e : perks.entrySet()) {
                if (!known.contains(e.getKey())) {
                    unknown.add(PvDraw.pretty(e.getKey()) + " §f" + e.getValue().getAsLong());
                }
            }
        }
        if (maxTier == 0) {
            drawRawList(g, ctx, current, top, "§8The tree layout is not loaded - perk levels as the API stores them:");
            return;
        }

        int gridW = ctx.width - SciFiScrollbar.WIDTH - 4;
        int cell = Math.max(8, Math.min(MAX_CELL, gridW / COLUMNS));
        int treeW = cell * COLUMNS;
        int left = ctx.x + (gridW - treeW) / 2;
        int legendH = ctx.font.lineHeight + 3;
        // Content in cell-tall rows: the tier rows (top = highest tier), the estimated tier-10 row,
        // the legend, and one row per unknown perk line.
        int rows = maxTier + (unplacedTier.isEmpty() ? 0 : 2) + 1 + (unknown.isEmpty() ? 0 : 1 + unknown.size());
        int visible = Math.max(1, (ctx.bottom() - top) / cell);
        int scroll = ctx.rows(rows, visible);
        if (rows > visible) {
            PvDraw.scrollbar(g, ctx.right() - SciFiScrollbar.WIDTH, top, visible * cell, rows, visible, scroll);
        }
        List<Component> tip = null;
        g.enableScissor(ctx.x, top, ctx.x + gridW, ctx.bottom());
        int row = 0;
        // The menu reads bottom-up: tier 1 at the bottom, so the highest tier is the first row.
        if (!unplacedTier.isEmpty()) {
            int y = top + (row - scroll) * cell;
            int tier10 = unplacedTier.get(0).tier();
            g.text(ctx.font, Component.literal("§8Tier " + tier10 + " - positions " + Certainty.ESTIMATED.displayName()),
                    left, y + (cell - ctx.font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
            row++;
            y = top + (row - scroll) * cell;
            for (int i = 0; i < unplacedTier.size() && i < COLUMNS; i++) {
                List<Component> t = node(g, ctx, unplacedTier.get(i), perks, disabled, left + i * cell, y, cell, top);
                tip = t != null ? t : tip;
            }
            row++;
        }
        for (int tier = maxTier; tier >= 1; tier--, row++) {
            int y = top + (row - scroll) * cell;
            g.fill(left, y + cell / 2, left + treeW, y + cell / 2 + 1, 0x22FFFFFF);
            for (Node perk : nodes) {
                if (perk.tier() == tier && perk.column() >= 0 && perk.column() < COLUMNS) {
                    List<Component> t = node(g, ctx, perk, perks, disabled, left + perk.column() * cell, y, cell, top);
                    tip = t != null ? t : tip;
                }
            }
        }
        int legendY = top + (row - scroll) * cell + (cell - legendH) / 2 + 2;
        g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font,
                "§6■ §7maxed  §a■ §7levelled  §c■ §7disabled  §8■ locked  §7corner: unconfirmed", gridW)), left, legendY, SBSTheme.TEXT);
        row++;
        if (!unknown.isEmpty()) {
            int y = top + (row - scroll) * cell;
            g.text(ctx.font, Component.literal("§8Not in the verified layout (API id, level):"), ctx.x, y + 2, SBSTheme.TEXT_MUTED);
            row++;
            for (String line : unknown) {
                y = top + (row - scroll) * cell;
                g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, "§7" + line, gridW)), ctx.x + 4, y + 2, SBSTheme.TEXT);
                row++;
            }
        }
        g.disableScissor();
        if (tip != null) {
            PvDraw.tooltip(g, ctx.font, tip, ctx.mouseX, ctx.mouseY);
        }
    }

    /** One perk node; returns its tooltip when hovered. */
    private List<Component> node(GuiGraphicsExtractor g, PvContext ctx, Node perk, JsonObject perks,
                                        Set<String> disabled, int x, int y, int cell, int clipTop) {
        long level = perks != null && perks.has(perk.id()) ? perks.get(perk.id()).getAsLong() : 0;
        boolean off = level > 0 && disabled.contains(perk.id());
        boolean maxed = level > 0 && perk.maxLevel() > 0 && level >= perk.maxLevel();
        int border = level == 0 ? SBSTheme.CARD_BORDER : off ? OFF : maxed ? MAXED : LEVELLED;
        int size = cell - 2;
        SciFiRender.roundedRectWithBorder(g, x + 1, y + 1, size, size, 2,
                level == 0 ? SBSTheme.CARD_BG_DISABLED : SBSTheme.CARD_BG, border);
        g.item(menuIcon(perk, level, maxed), x + (cell - 16) / 2, y + (cell - 16) / 2);
        if (level > 0 && perk.maxLevel() > 1) {
            // The level as a stack count would sit: small, bottom-right, over the icon.
            String text = String.valueOf(level);
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + cell - 1, y + cell - 1 - ctx.font.lineHeight * 0.5f);
            pose.scale(0.5f, 0.5f);
            g.text(ctx.font, Component.literal(text), -ctx.font.width(text), 0, off ? OFF : maxed ? MAXED : 0xFFFFFFFF);
            pose.popMatrix();
        }
        if (perk.certainty() != Certainty.CONFIRMED || perk.idCertainty() != Certainty.CONFIRMED) {
            g.fill(x + 1, y + 1, x + 3, y + 3, 0xFF888888);   // grey corner: not fully confirmed
        }
        boolean over = ctx.mouseY >= clipTop && ctx.mouseY < ctx.bottom() && ctx.hovered(x, y, cell, cell);
        if (!over) {
            return null;
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal("§f" + perk.name()));
        tip.add(Component.literal("§7Tier " + perk.tier() + (perk.maxLevel() > 0 ? " §8• §7Level §f" + level + "§8/" + perk.maxLevel()
                : " §8• §7" + (level > 0 ? "§aunlocked" : "§8locked"))));
        if (level > 0) {
            tip.add(Component.literal(off ? "§cdisabled" : "§aenabled"));
        }
        if (perk.certainty() != Certainty.CONFIRMED) {
            tip.add(Component.literal(perk.certainty().colorCode() + "Catalogue entry " + perk.certainty().displayName()));
        }
        if (perk.idCertainty() != Certainty.CONFIRMED) {
            tip.add(Component.literal("§8API id " + perk.idCertainty().displayName() + ": " + perk.id()));
        }
        return tip;
    }

    /**
     * The item Hypixel's own menu draws for a node, read from the maintainer's captures of both
     * menus (2026-10-08). Heart of the Mountain: coal for a perk not bought yet, emerald while it
     * levels, diamond once maxed, coal block for an ability and emerald block for the selected one,
     * redstone block for Core of the Mountain.
     *
     * <p>Heart of the Forest draws its nodes through a model override the recorder did not save
     * before 2026-10-08, so this is read off the maintainer's screenshot of the menu plus the two
     * real materials the capture did hold: oak log for an owned single-level or maxed perk and oak
     * sapling for an owned ability (CONFIRMED), stripped oak log while a perk levels and dark oak
     * log for Center of the Forest (from the picture), and a pale oak button for a perk not bought
     * (CONFIRMED in game 2026-10-08: a paper item drawn as that button). The two abilities' plant
     * models are not identified yet; they draw as oak sapling until they are.
     */
    private ItemStack menuIcon(Node perk, long level, boolean maxed) {
        if (mode == Mode.FOREST) {
            if (perk.id().equals("center_of_the_forest")) {
                return icon(Items.DARK_OAK_LOG);
            }
            if (level <= 0) {
                return icon(Items.PALE_OAK_BUTTON);
            }
            if (isForestAbility(perk)) {
                return icon(Items.OAK_SAPLING);
            }
            return icon(maxed || perk.maxLevel() <= 1 ? Items.OAK_LOG : Items.STRIPPED_OAK_LOG);
        }
        return switch (perk.kind()) {
            case "CORE" -> icon(Items.REDSTONE_BLOCK);
            case "ABILITY" -> icon(perk.id().equals(ability) ? Items.EMERALD_BLOCK : Items.COAL_BLOCK);
            default -> icon(level <= 0 ? Items.COAL : maxed || perk.maxLevel() <= 1 ? Items.DIAMOND : Items.EMERALD);
        };
    }

    /** The Heart of the Forest abilities, by name as the menu lists them. */
    private static boolean isForestAbility(Node perk) {
        String n = perk.name().toLowerCase(java.util.Locale.ROOT);
        return n.equals("axe toss") || n.equals("damage boost") || n.equals("maniac slicer");
    }

    /** One shared stack per item: built lazily (components are not bound while classes load). */
    private static final java.util.Map<net.minecraft.world.item.Item, ItemStack> ICONS = new java.util.HashMap<>();

    private static ItemStack icon(net.minecraft.world.item.Item item) {
        return ICONS.computeIfAbsent(item, ItemStack::new);
    }

    // ------------------------------------------------------------------
    // Raw list (Heart of the Forest, or no catalogue)
    // ------------------------------------------------------------------

    private static void drawRawList(GuiGraphicsExtractor g, PvContext ctx, JsonObject current, int top, String note) {
        List<String> lines = new ArrayList<>();
        JsonObject perks = PvDraw.obj(current, "perks");
        Set<String> disabled = new HashSet<>();
        PvDraw.arr(current, "disabled").forEach(e -> disabled.add(e.getAsString()));
        if (perks != null) {
            for (Map.Entry<String, JsonElement> e : perks.entrySet()) {
                lines.add("§7" + PvDraw.pretty(e.getKey()) + " §f" + e.getValue().getAsLong()
                        + (disabled.contains(e.getKey()) ? " §c(disabled)" : ""));
            }
        }
        if (lines.isEmpty()) {
            lines.add("§8No perks.");
        }
        g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, note, ctx.width)), ctx.x, top, SBSTheme.TEXT_MUTED);
        int listTop = top + ctx.font.lineHeight + 4;
        int w = ctx.width - SciFiScrollbar.WIDTH - 4;
        int visible = Math.max(1, (ctx.bottom() - listTop) / LIST_ROW);
        int scroll = ctx.rows(lines.size(), visible);
        for (int i = scroll; i < lines.size() && i < scroll + visible; i++) {
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, lines.get(i), w)), ctx.x + 4,
                    listTop + (i - scroll) * LIST_ROW, SBSTheme.TEXT);
        }
        if (lines.size() > visible) {
            PvDraw.scrollbar(g, ctx.right() - SciFiScrollbar.WIDTH, listTop, visible * LIST_ROW,
                    lines.size(), visible, scroll);
        }
    }
}
