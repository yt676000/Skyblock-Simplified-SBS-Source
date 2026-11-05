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
import sbs.modid.client.economy.itemvalue.ValueSum;
import sbs.modid.client.helper.storage.StoragePageCard;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.theme.SBSTheme;
import java.util.ArrayList;
import java.util.List;

/**
 * Inventory ▸ Ender Chest / Backpacks: every page as a card of the SBS storage workspace - the same
 * {@link StoragePageCard}, title, per-page value and "All pages" total - laid out to fit the viewer's
 * panel. Cards keep their real size while one fits across; on a panel narrower than one card the
 * whole grid is scaled down rather than clipped.
 *
 * <p>Values come from the offline appraisal ({@link ValueSum}, warm local price caches only, nothing
 * fetched per item) and are computed once per profile, not per frame. A total that is a floor (an
 * item unpriced, or an icon still resolving) is re-checked every few seconds until it settles.
 */
public final class StoragePagesPage implements PvPage {

    public enum Mode { ENDER_CHEST, BACKPACKS }

    /** One Ender Chest page holds five rows; the API stores all pages as one list. */
    private static final int EC_PAGE_SLOTS = 45;
    private static final int GAP = 6;
    /** Scroll step in card-space pixels: one item row. */
    private static final int STEP = StoragePageCard.MINI;
    private static final long RECHECK_MS = 3_000;

    private final Mode mode;

    private record Page(String label, JsonArray slots) {
    }

    /** Pages and values of the profile view they were built from (identity). */
    private JsonObject builtFor;
    private List<Page> pages = List.of();
    private ValueSum.Total[] values = new ValueSum.Total[0];
    private ValueSum.Total allPages = ValueSum.Total.ZERO;
    private boolean unsettled;
    private long valuedAt;

    public StoragePagesPage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void reset() {
        builtFor = null;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject invs = PvDraw.obj(ctx.profile, "inventories");
        String key = mode == Mode.ENDER_CHEST ? PvDraw.containerKey(invs, "ender_chest") : "backpacks";
        if (invs == null || !invs.has(key) || !invs.get(key).isJsonArray()) {
            boolean apiOff = ctx.profile.has("inventory_api") && !ctx.profile.get("inventory_api").getAsBoolean();
            PvDraw.empty(g, ctx, apiOff ? "This player's Inventory API is disabled."
                    : mode == Mode.ENDER_CHEST ? "No Ender Chest data." : "No Backpacks data.");
            return;
        }
        refresh(ctx.profile, invs.getAsJsonArray(key));

        // Header: the all-pages total left, the page count right; the count yields when it does not fit.
        int headerH = ctx.font.lineHeight + 4;
        String total = StoragePageCard.showValue() && allPages.any() ? "§7All pages §6" + allPages.label() : "";
        String count = "§8" + pages.size() + (pages.size() == 1 ? " page" : " pages");
        g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, total, ctx.width)), ctx.x, ctx.y, SBSTheme.TEXT);
        if (ctx.font.width(total) + 8 + ctx.font.width(count) <= ctx.width) {
            g.text(ctx.font, Component.literal(count), ctx.right() - ctx.font.width(count), ctx.y, SBSTheme.TEXT);
        }

        // The scrollbar strip is reserved unconditionally, so the grid never reflows when it appears.
        int top = ctx.y + headerH;
        int viewH = ctx.bottom() - top;
        int gridW = ctx.width - SciFiScrollbar.WIDTH - 4;
        float scale = Math.min(1f, gridW / (float) (StoragePageCard.CARD_W + 2));
        int virtualW = (int) (gridW / scale);
        int cols = Math.max(1, (virtualW + GAP) / (StoragePageCard.CARD_W + GAP));
        int usedW = cols * StoragePageCard.CARD_W + (cols - 1) * GAP;
        int left = (virtualW - usedW) / 2;

        int contentH = 0;
        for (int i = 0; i < pages.size(); i += cols) {
            contentH += rowHeight(i, cols) + GAP;
        }
        int visibleSteps = Math.max(1, (int) (viewH / (STEP * scale)));
        int totalSteps = (contentH + STEP - 1) / STEP;
        int scroll = ctx.rows(totalSteps, visibleSteps);

        // Card space: origin at the grid's top-left, scaled; the mouse is mapped into it.
        double vmx = (ctx.mouseX - ctx.x) / scale;
        double vmy = (ctx.mouseY - top) / scale + scroll * STEP;
        boolean mouseInBand = ctx.mouseY >= top && ctx.mouseY < ctx.bottom()
                && ctx.mouseX >= ctx.x && ctx.mouseX < ctx.x + gridW;
        JsonObject hovered = null;
        String hoveredWhere = null;

        g.enableScissor(ctx.x, top, ctx.x + gridW, ctx.bottom());
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(ctx.x, top);
        pose.scale(scale, scale);
        pose.translate(0, -scroll * STEP);
        int bandTop = scroll * STEP;
        int bandBottom = bandTop + (int) (viewH / scale);
        int y = 0;
        for (int i = 0; i < pages.size(); i += cols) {
            int rowH = rowHeight(i, cols);
            if (y + rowH >= bandTop && y <= bandBottom) {
                for (int j = i; j < Math.min(i + cols, pages.size()); j++) {
                    Page page = pages.get(j);
                    int x = left + (j - i) * (StoragePageCard.CARD_W + GAP);
                    int cardH = StoragePageCard.height(StoragePageCard.rowsFor(page.slots().size()));
                    String value = StoragePageCard.showValue() && values[j].any() ? values[j].label() : "";
                    StoragePageCard.frame(g, ctx.font, x, y, cardH, "§f", page.label(), "", value, false);
                    JsonArray slots = page.slots();
                    for (int s = 0; s < slots.size(); s++) {
                        JsonElement el = slots.get(s);
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        int cx = StoragePageCard.cellX(x, s);
                        int cy = StoragePageCard.cellY(y, s);
                        if (cy + StoragePageCard.MINI < bandTop || cy > bandBottom) {
                            continue;
                        }
                        StoragePageCard.item(g, ctx.font, PvDraw.stack(ctx.profile, el.getAsJsonObject()), cx, cy);
                        if (mouseInBand && vmx >= cx && vmx < cx + StoragePageCard.MINI
                                && vmy >= cy && vmy < cy + StoragePageCard.MINI) {
                            g.fill(cx, cy, cx + StoragePageCard.MINI - 1, cy + StoragePageCard.MINI - 1, 0x44FFE24B);
                            hovered = el.getAsJsonObject();
                            hoveredWhere = page.label();
                        }
                    }
                }
            }
            y += rowH + GAP;
        }
        pose.popMatrix();
        g.disableScissor();

        if (totalSteps > visibleSteps) {
            PvDraw.scrollbar(g, ctx.right() - SciFiScrollbar.WIDTH, top, viewH, totalSteps, visibleSteps, scroll);
        }
        if (hovered != null) {
            PvDraw.tooltip(g, ctx.font, PvDraw.slotTooltip(hovered, "§8" + hoveredWhere), ctx.mouseX, ctx.mouseY);
        }
    }

    /** Height of the card row starting at page {@code first}: its tallest card. */
    private int rowHeight(int first, int cols) {
        int h = 0;
        for (int j = first; j < Math.min(first + cols, pages.size()); j++) {
            h = Math.max(h, StoragePageCard.height(StoragePageCard.rowsFor(pages.get(j).slots().size())));
        }
        return h;
    }

    /** Rebuilds the page list for a new profile view, and re-values while a total is unsettled. */
    private void refresh(JsonObject profile, JsonArray source) {
        long now = System.currentTimeMillis();
        if (profile == builtFor && !(unsettled && now - valuedAt >= RECHECK_MS)) {
            return;
        }
        if (profile != builtFor) {
            pages = mode == Mode.ENDER_CHEST ? enderChestPages(source)
                    : backpackPages(source, PvDraw.arr(PvDraw.obj(profile, "inventories"), "backpack_numbers"));
            builtFor = profile;
        }
        values = new ValueSum.Total[pages.size()];
        ValueSum.Total all = ValueSum.Total.ZERO;
        boolean pending = false;
        for (int i = 0; i < pages.size(); i++) {
            List<ItemStack> stacks = new ArrayList<>();
            for (JsonElement el : pages.get(i).slots()) {
                if (el != null && el.isJsonObject()) {
                    ItemStack stack = PvDraw.stack(profile, el.getAsJsonObject());
                    pending |= stack.is(Items.BARRIER);
                    stacks.add(stack);
                }
            }
            values[i] = ValueSum.of(stacks);
            all = all.plus(values[i]);
        }
        allPages = all;
        unsettled = pending || all.floor();
        valuedAt = now;
    }

    private static List<Page> enderChestPages(JsonArray all) {
        List<Page> out = new ArrayList<>();
        for (int start = 0; start < all.size(); start += EC_PAGE_SLOTS) {
            JsonArray slots = new JsonArray();
            for (int i = start; i < Math.min(start + EC_PAGE_SLOTS, all.size()); i++) {
                slots.add(all.get(i));
            }
            out.add(new Page("Ender Chest Page " + (start / EC_PAGE_SLOTS + 1), slots));
        }
        return out;
    }

    private static List<Page> backpackPages(JsonArray packs, JsonArray numbers) {
        List<Page> out = new ArrayList<>();
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i).isJsonArray()) {
                long number = i < numbers.size() ? numbers.get(i).getAsLong() : i + 1;
                out.add(new Page("Backpack " + number, packs.get(i).getAsJsonArray()));
            }
        }
        return out;
    }
}
