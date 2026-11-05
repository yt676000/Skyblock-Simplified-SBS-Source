/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.economy.crafthelper.model.CraftNode;
import sbs.modid.client.economy.recipe.logic.RecipeCostResolver;
import sbs.modid.client.economy.recipe.logic.RecipeRegistry;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Craft Helper's state: the counts it measures against, the trees it shows, and the collapse
 * toggles. Display only - it never clicks, crafts or opens a menu (unlike the Recipe Viewer's
 * Supercraft button, which is a separate, explicit action).
 *
 * <p><b>When it rebuilds.</b> Counts are re-read on a throttled tick while a tree is in use and
 * compared by value; a tree and its draw model are rebuilt only when the counts, the pins, a
 * collapse toggle or a setting changed, plus a slow refresh so recipes that finished loading after
 * the first build show up. A frame only reads the finished {@link Model}.
 *
 * <p>Client thread only.
 */
public final class CraftHelper {

    private static final CraftHelper INSTANCE = new CraftHelper();

    /** Ticks between count checks while a tree is in use. */
    private static final int COUNT_INTERVAL = 10;
    /** Ticks between unconditional rebuilds (late-loading recipe data). */
    private static final int REFRESH_INTERVAL = 100;

    /** Colour of the tick and of held counts. */
    public static final int DONE = 0xFF55FF55;
    /** Colour of the cross and of missing counts. */
    public static final int MISSING = 0xFFFF5555;

    /**
     * One drawable row: the flattened row plus its texts, measured once.
     *
     * @param markerX where the expand marker sits; the other x values follow it
     */
    public record RenderRow(CraftRows.Row row, Component connectors, Component marker, int markerX,
                            Component mark, int markX, int markColor, Component counts, int countsX,
                            int countsColor, Component name, int nameX, int nameColor,
                            Component recipeTag, int recipeTagX, int width) {
    }

    /**
     * A tree ready to draw.
     *
     * @param version changes whenever anything drawn changes, so a surface can tell
     */
    public record Model(String id, ItemRef icon, Component title, int titleColor, int quantity,
                        CraftNode root, List<RenderRow> rows, int width, long version) {
    }

    private final CraftCounts counts = new CraftCounts();
    private Map<String, Long> available = Map.of();
    private int countsVersion;
    private int tickCounter;

    /** Per item id, the node paths whose default collapse state the player flipped. */
    private final Map<String, Set<String>> toggled = new HashMap<>();
    private int toggleVersion;

    /** The last models built: the HUD's and the viewer's, and room for a switch between pins. */
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private int nextSlot;
    private long modelVersion;

    /** Set by any surface that drew a tree this tick, so counts are only polled while in use. */
    private boolean inUse;

    private CraftHelper() {
    }

    public static CraftHelper getInstance() {
        return INSTANCE;
    }

    public static SBSConfig.CraftHelperSettings cfg() {
        return ConfigManager.getInstance().get().craftHelper;
    }

    // ------------------------------------------------------------------ tick

    /** Throttled count check; registered in {@code TickTasks}. */
    public void tick() {
        if (!cfg().enabled) {
            return;
        }
        tickCounter++;
        boolean wanted = inUse || CraftPins.getInstance().shown() != null;
        inUse = false;
        if (!wanted) {
            return;
        }
        if (tickCounter % REFRESH_INTERVAL == 0) {
            counts.invalidate();
            countsVersion++;
        }
        if (tickCounter % COUNT_INTERVAL == 0 || available.isEmpty()) {
            refreshCounts();
        }
    }

    private void refreshCounts() {
        Map<String, Long> next = counts.collect(cfg());
        if (!next.equals(available)) {
            available = next;
            countsVersion++;
        }
    }

    /** Clears the counts on a world change, so a stale lobby's inventory is never shown. */
    public void onWorldChange() {
        available = Map.of();
        counts.invalidate();
        countsVersion++;
    }

    // ------------------------------------------------------------------ queries

    /**
     * The model for {@code id} at {@code quantity}, rebuilt only when an input changed. Cheap to call
     * every frame: the key comparison is a handful of ints.
     */
    public Model model(String id, int quantity) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        inUse = true;
        if (available.isEmpty() && countsVersion == 0) {
            refreshCounts();
        }
        SBSConfig.CraftHelperSettings cfg = cfg();
        int pins = CraftPins.getInstance().revision();
        Slot free = null;
        for (Slot slot : slots) {
            if (slot.model != null && slot.matches(id, quantity, countsVersion, pins, toggleVersion, cfg)) {
                return slot.model;
            }
            if (slot.model != null && slot.model.id().equalsIgnoreCase(id)) {
                free = slot;    // same tree, stale inputs: rebuild in place
            }
        }
        if (free == null) {
            free = slots[nextSlot];
            nextSlot = (nextSlot + 1) % slots.length;
        }
        free.model = build(id.toUpperCase(Locale.ROOT), quantity, cfg);
        free.quantity = quantity;
        free.counts = countsVersion;
        free.pins = pins;
        free.toggles = toggleVersion;
        free.depth = cfg.maxDepth;
        free.collapse = cfg.collapseCompleted;
        return free.model;
    }

    /** One cached model and the inputs it was built from, compared field by field. */
    private static final class Slot {
        private Model model;
        private int quantity;
        private int counts;
        private int pins;
        private int toggles;
        private int depth;
        private boolean collapse;

        private boolean matches(String id, int quantity, int counts, int pins, int toggles,
                                SBSConfig.CraftHelperSettings cfg) {
            return model.id().equalsIgnoreCase(id) && this.quantity == quantity && this.counts == counts
                    && this.pins == pins && this.toggles == toggles && depth == cfg.maxDepth
                    && collapse == cfg.collapseCompleted;
        }
    }

    /** The usable recipes for {@code id}, in the order the tree numbers them. */
    public List<SbsRecipe> candidates(String id) {
        return CraftTree.candidates(id, inputs(cfg()));
    }

    /** Flips the collapse state of one row of {@code rootId}'s tree. */
    public void toggle(String rootId, String path) {
        Set<String> set = toggled.computeIfAbsent(rootId.toUpperCase(Locale.ROOT), k -> new HashSet<>());
        if (!set.remove(path)) {
            set.add(path);
        }
        toggleVersion++;
    }

    // ------------------------------------------------------------------ build

    private CraftTree.Inputs inputs(SBSConfig.CraftHelperSettings cfg) {
        RecipeRegistry registry = RecipeRegistry.getInstance();
        RecipeCostResolver resolver = RecipeCostResolver.getInstance();
        return new CraftTree.Inputs(registry::recipesFor, available, CraftPins.getInstance().recipeChoices(),
                Math.max(1, cfg.maxDepth), resolver::unitCost);
    }

    private Model build(String id, int quantity, SBSConfig.CraftHelperSettings cfg) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(id);
        String name = entry != null && entry.name != null ? entry.name : id;
        CraftNode root = CraftTree.build(id, name, quantity, inputs(cfg));
        List<CraftRows.Row> rows = CraftRows.flatten(root,
                toggled.getOrDefault(id, Set.of()), cfg.collapseCompleted);

        Font font = Minecraft.getInstance().font;
        List<RenderRow> out = new ArrayList<>(rows.size());
        int width = 0;
        for (CraftRows.Row row : rows) {
            RenderRow rendered = render(font, row);
            out.add(rendered);
            width = Math.max(width, rendered.width());
        }
        ItemRef icon = new ItemRef(id, entry != null ? entry.material : null, name, 1);
        Component title = Component.literal(name);
        return new Model(id, icon, title, rarityColor(id), quantity, root, List.copyOf(out), width,
                ++modelVersion);
    }

    private static RenderRow render(Font font, CraftRows.Row row) {
        CraftNode node = row.node();
        int x = font.width(row.connectors());
        Component connectors = Component.literal(row.connectors());
        Component marker = Component.literal(row.expandable() ? (row.expanded() ? "▾" : "▸") : "");
        int markerX = x;
        x += 8;
        Component mark = Component.literal(row.mark());
        int markX = x;
        x += font.width(row.mark()) + 3;
        Component countsText = Component.literal(row.counts());
        int countsX = x;
        x += font.width(row.counts()) + 4;
        Component name = Component.literal(node.name());
        int nameX = x;
        x += font.width(node.name());
        Component recipeTag = null;
        int recipeTagX = 0;
        String tag = tag(node);
        if (tag != null) {
            x += 4;
            recipeTagX = x;
            recipeTag = Component.literal(tag);
            x += font.width(tag);
        }
        int color = node.done() ? DONE : MISSING;
        return new RenderRow(row, connectors, marker, markerX, mark, markX, color, countsText, countsX, color,
                name, nameX, rarityColor(node.id()), recipeTag, recipeTagX, x);
    }

    /** The suffix that explains a stop or offers a recipe switch, or {@code null}. */
    private static String tag(CraftNode node) {
        if (node.recipeCount() > 1) {
            return "[recipe " + (node.recipeIndex() + 1) + "/" + node.recipeCount() + "]";
        }
        return switch (node.stop()) {
            case CYCLE -> "(loops back)";
            case DEPTH -> "(depth limit)";
            case LIMIT -> "(too large)";
            default -> null;
        };
    }

    /** The item's rarity colour from the catalogue, plain text colour when unknown. */
    public static int rarityColor(String id) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(id);
        if (entry == null || entry.tier == null) {
            return SBSTheme.TEXT;
        }
        return switch (entry.tier.toUpperCase(Locale.ROOT)) {
            case "SUPREME", "DIVINE" -> Rarity.DIVINE.color();
            case "SPECIAL", "VERY_SPECIAL" -> Rarity.SPECIAL.color();
            case "MYTHIC" -> Rarity.MYTHIC.color();
            case "LEGENDARY" -> Rarity.LEGENDARY.color();
            case "EPIC" -> Rarity.EPIC.color();
            case "RARE" -> Rarity.RARE.color();
            case "UNCOMMON" -> Rarity.UNCOMMON.color();
            default -> Rarity.COMMON.color();
        };
    }
}
