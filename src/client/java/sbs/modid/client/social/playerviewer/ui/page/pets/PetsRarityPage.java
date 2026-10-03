/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.pets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pets ▸ By Rarity – the collection split into rarity columns, each an icon grid with a count.
 * Answers "how many legendaries does this player actually have" without scrolling the full list.
 */
public final class PetsRarityPage implements PvPage {

    private static final int GAP = 6;

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonArray pets = PvDraw.arr(ctx.profile, "pets");
        if (pets.isEmpty()) {
            PvDraw.empty(g, ctx, "No pets.");
            return;
        }
        // Bucket by tier, keeping the canonical rarity order and dropping tiers nobody owns.
        Map<String, List<JsonObject>> byTier = new LinkedHashMap<>();
        for (String tier : PvDraw.RARITIES) {
            byTier.put(tier, new ArrayList<>());
        }
        for (JsonElement element : pets) {
            JsonObject pet = element.getAsJsonObject();
            byTier.computeIfAbsent(PvDraw.str(pet, "tier"), t -> new ArrayList<>()).add(pet);
        }
        byTier.values().removeIf(List::isEmpty);
        if (byTier.isEmpty()) {
            PvDraw.empty(g, ctx, "No pets.");
            return;
        }

        int cols = byTier.size();
        int colW = (ctx.width - GAP * (cols - 1)) / cols;
        int i = 0;
        for (var entry : byTier.entrySet()) {
            drawColumn(g, ctx, ctx.x + i * (colW + GAP), colW, entry.getKey(), entry.getValue());
            i++;
        }
    }

    private void drawColumn(GuiGraphicsExtractor g, PvContext ctx, int x, int w,
                            String tier, List<JsonObject> pets) {
        String color = PvDraw.TIER_COLOR.getOrDefault(tier, "§f");
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w,
                color + PvDraw.pretty(tier) + " §8" + pets.size());
        int perRow = Math.max(1, w / PvDraw.CELL);
        for (int i = 0; i < pets.size(); i++) {
            int cx = x + (i % perRow) * PvDraw.CELL;
            int cy = y + (i / perRow) * PvDraw.CELL;
            if (cy + PvDraw.CELL > ctx.bottom()) {
                break;
            }
            JsonObject pet = pets.get(i);
            PvDraw.cell(g, cx, cy);
            g.item(PvDraw.petIcon(PvDraw.str(pet, "type")), cx + 1, cy + 1);
            if (pet.has("active") && pet.get("active").getAsBoolean()) {
                g.fill(cx, cy, cx + 2, cy + 2, SBSTheme.TOGGLE_ON);
            }
            if (ctx.hovered(cx, cy, PvDraw.CELL, PvDraw.CELL)) {
                PvDraw.tooltip(g, ctx.font, List.of(
                        Component.literal(color + PvDraw.pretty(PvDraw.str(pet, "type"))),
                        Component.literal("§7Level §f" + pet.get("level").getAsInt()),
                        Component.literal("§7Tier " + color + PvDraw.pretty(tier))),
                        ctx.mouseX, ctx.mouseY);
            }
        }
    }
}
