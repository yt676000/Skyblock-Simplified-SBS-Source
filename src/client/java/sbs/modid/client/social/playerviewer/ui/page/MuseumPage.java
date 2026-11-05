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
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.helper.museum.model.MuseumCatalog;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.theme.SBSTheme;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Museum ▸ Overview – the museum's appraised value, how far each category is, and every donation as
 * its real icon: the individual items, the armour sets and the Special bucket.
 *
 * <p>Categories come from the Museum Helper's {@link MuseumCatalog} (Hypixel's keyless items
 * resource, {@code museum_data}): seven categories plus Special, and armour counted <b>per set</b>,
 * never per piece. The museum API names a donated set by its set id, which is not an item, so a set
 * is drawn with its first piece's icon. A donated key the catalogue does not know is still drawn and
 * counted under "not catalogued", never dropped.
 *
 * <p>The museum is a separate relay endpoint, requested only when this page is opened (once per
 * profile per viewer session).
 */
public final class MuseumPage implements PvPage {

    private static final int GRID_SLOT = PvDraw.CELL;

    /** One cell of the grid: the icon's item id, its name and the line under it. */
    private record Entry(String iconId, String name, String detail) {
    }

    /** What the page derived from one museum view (identity), so nothing is re-derived per frame. */
    private JsonObject derivedFor;
    private MuseumCatalog derivedWith;
    private List<Entry> entries = List.of();
    private Map<MuseumCatalog.Category, int[]> perCategory = Map.of();
    private int uncatalogued;

    @Override
    public void reset() {
        derivedFor = null;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject museum = PvDraw.obj(ctx.profile, "museum");
        if (museum == null) {
            String state = PvDraw.str(ctx.profile, "museum_state");
            PvDraw.empty(g, ctx, !state.isEmpty() ? state
                    : "No museum data - the museum API may be off for this player.");
            return;
        }
        derive(museum);
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Value", "§6" + PvDraw.fmt(PvDraw.num(museum, "value")), "appraised"},
                {"Donated", "§b" + PvDraw.num(museum, "donated"), "items + sets"},
                {"Special", "§b" + PvDraw.num(museum, "special")},
                {"Appraisal", museum.has("appraisal") && museum.get("appraisal").getAsBoolean()
                        ? "§adone" : "§8no"}});

        y = drawCategories(g, ctx, y + 4);

        if (entries.isEmpty()) {
            return;
        }
        String borrowed = museum.has("borrowing") && PvDraw.num(museum, "borrowing") > 0
                ? " §8• " + PvDraw.num(museum, "borrowing") + " borrowed out" : "";
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 3, ctx.width, "Donations §8" + entries.size() + borrowed);
        int gridW = ctx.width - SciFiScrollbar.WIDTH - 4;
        int cols = Math.max(1, gridW / GRID_SLOT);
        int visibleRows = Math.max(1, (ctx.bottom() - y) / GRID_SLOT);
        int totalRows = (entries.size() + cols - 1) / cols;
        int scroll = ctx.rows(totalRows, visibleRows);
        for (int row = scroll; row < totalRows && row < scroll + visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= entries.size()) {
                    break;
                }
                Entry entry = entries.get(idx);
                int x = ctx.x + col * GRID_SLOT;
                int cy = y + (row - scroll) * GRID_SLOT;
                PvDraw.cell(g, x, cy);
                ItemStack icon = SkyBlockItemIcons.getInstance().icon(entry.iconId(), null, 1);
                g.item(icon.is(Items.BARRIER) ? new ItemStack(Items.PAPER) : icon, x + 1, cy + 1);
                if (ctx.hovered(x, cy, GRID_SLOT, GRID_SLOT)) {
                    PvDraw.tooltip(g, ctx.font, List.of(Component.literal("§f" + entry.name()),
                            Component.literal(entry.detail())), ctx.mouseX, ctx.mouseY);
                }
            }
        }
        if (totalRows > visibleRows) {
            PvDraw.scrollbar(g, ctx.right() - SciFiScrollbar.WIDTH, y, visibleRows * GRID_SLOT,
                    totalRows, visibleRows, scroll);
        }
    }

    /** "Combat 143/245" per category, flowed over as many lines as the width needs. */
    private int drawCategories(GuiGraphicsExtractor g, PvContext ctx, int y) {
        if (perCategory.isEmpty()) {
            g.text(ctx.font, Component.literal("§8Categories appear once the item catalogue has loaded."),
                    ctx.x, y, SBSTheme.TEXT_MUTED);
            return y + ctx.font.lineHeight + 2;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<MuseumCatalog.Category, int[]> e : perCategory.entrySet()) {
            int[] c = e.getValue();
            parts.add((c[0] >= c[1] && c[1] > 0 ? "§a" : "§7") + e.getKey().label() + " §f" + c[0] + "§8/" + c[1]);
        }
        if (uncatalogued > 0) {
            parts.add("§8not catalogued §7" + uncatalogued);
        }
        int x = ctx.x;
        for (String part : parts) {
            int w = ctx.font.width(part);
            if (x > ctx.x && x + w > ctx.right()) {
                x = ctx.x;
                y += ctx.font.lineHeight + 2;
            }
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, part, ctx.width)), x, y, SBSTheme.TEXT);
            x += w + 8;
        }
        return y + ctx.font.lineHeight + 2;
    }

    /** Resolves every donated key against the catalogue; reruns when the catalogue is replaced. */
    private void derive(JsonObject museum) {
        MuseumCatalog catalog = MuseumCatalog.current();
        if (museum == derivedFor && catalog == derivedWith) {
            return;
        }
        derivedFor = museum;
        derivedWith = catalog;
        List<Entry> out = new ArrayList<>();
        Map<MuseumCatalog.Category, int[]> counts = new EnumMap<>(MuseumCatalog.Category.class);
        boolean known = catalog != null && !catalog.isEmpty();
        if (known) {
            for (MuseumCatalog.Donation d : catalog.all()) {
                counts.computeIfAbsent(d.category(), c -> new int[2])[1]++;
            }
        }
        int unknown = 0;
        for (JsonElement el : PvDraw.arr(museum, "items")) {
            String key = el.getAsString();
            MuseumCatalog.Donation d = known ? catalog.donation(key) : null;
            if (d == null && known) {
                d = catalog.donation(MuseumCatalog.SET_PREFIX + key);
            }
            if (d == null) {
                unknown++;
                out.add(new Entry(key, PvDraw.pretty(key), "§8donated §7• not in the museum catalogue"));
                continue;
            }
            counts.computeIfAbsent(d.category(), c -> new int[2])[0]++;
            String icon = d.set() && !d.pieces().isEmpty() ? d.pieces().get(0) : key;
            out.add(new Entry(icon, d.displayName(), "§8donated §7• " + d.category().label()
                    + (d.set() ? " armour set" : "") + (d.xp() > 0 ? " §8• §b" + d.xp() + " XP" : "")));
        }
        for (JsonElement el : PvDraw.arr(museum, "special_items")) {
            String id = el.getAsString();
            MuseumCatalog.Donation d = known ? catalog.donation(id) : null;
            if (d != null) {
                counts.computeIfAbsent(d.category(), c -> new int[2])[0]++;
            }
            out.add(new Entry(id, d != null ? d.displayName() : PvDraw.pretty(id), "§8donated §7• Special"));
        }
        entries = out;
        perCategory = known ? counts : Map.of();
        uncatalogued = unknown;
    }
}
