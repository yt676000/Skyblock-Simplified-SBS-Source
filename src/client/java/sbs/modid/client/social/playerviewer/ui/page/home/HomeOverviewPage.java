/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.home;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.social.playerviewer.ui.PvSkinRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Map;

/**
 * Home ▸ Overview – the landing page: where the player is right now across the top, then the
 * player's own model and SkyBlock level on the left, skills in the middle, dungeons and slayers on
 * the right.
 *
 * <p>The model is draggable (click and spin), which is why this page keeps its own
 * {@link PvSkinRender} – rebuilt whenever the payload's uuid changes, so opening another player
 * from the recent strip never shows the previous player's skin.
 */
public final class HomeOverviewPage implements PvPage {

    /** Skill id -> vanilla item used as its row icon. */
    private static final Map<String, Item> SKILL_ICON = Map.ofEntries(
            Map.entry("farming", Items.GOLDEN_HOE),
            Map.entry("mining", Items.STONE_PICKAXE),
            Map.entry("combat", Items.IRON_SWORD),
            Map.entry("foraging", Items.JUNGLE_SAPLING),
            Map.entry("fishing", Items.FISHING_ROD),
            Map.entry("enchanting", Items.ENCHANTING_TABLE),
            Map.entry("alchemy", Items.BREWING_STAND),
            Map.entry("taming", Items.LEAD),
            Map.entry("carpentry", Items.CRAFTING_TABLE));
    private static final Map<String, Item> SLAYER_ICON = Map.of(
            "zombie", Items.ROTTEN_FLESH,
            "spider", Items.SPIDER_EYE,
            "wolf", Items.BONE,
            "enderman", Items.ENDER_PEARL,
            "blaze", Items.BLAZE_ROD,
            "vampire", Items.REDSTONE);
    private static final Map<String, String> SLAYER_NAME = Map.of(
            "zombie", "Rev", "spider", "Tara", "wolf", "Sven",
            "enderman", "Ender", "blaze", "Blaze", "vampire", "Vampire");
    private static final Map<String, Item> CLASS_ICON = Map.of(
            "healer", Items.POTION,
            "mage", Items.BLAZE_ROD,
            "berserk", Items.IRON_SWORD,
            "archer", Items.BOW,
            "tank", Items.SHIELD);
    private static final List<String> SKILL_ORDER = List.of("farming", "mining", "combat",
            "foraging", "fishing", "enchanting", "alchemy", "taming", "carpentry");
    private static final List<String> SLAYER_ORDER = List.of("zombie", "spider", "wolf",
            "enderman", "blaze", "vampire");

    /** Height of the status banner across the top of the page. */
    private static final int STATUS_H = 20;
    /** Height reserved under the model for the SkyBlock-level card. */
    private static final int LEVEL_CARD_H = 62;

    private PvSkinRender model;
    /** The uuid {@link #model} was built for, so a new player rebuilds it. */
    private String modelUuid;
    /** Model box from the last frame, so drags only spin when they start on the model. */
    private int boxX;
    private int boxY;
    private int boxW;
    private int boxH;

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        int top = drawStatus(g, ctx);
        int gap = 8;
        int third = (ctx.width - gap * 2) / 3;
        drawPlayerColumn(g, ctx, ctx.x, top, third);
        drawSkillsColumn(g, ctx, ctx.x + third + gap, top, third);
        drawCombatColumn(g, ctx, ctx.x + (third + gap) * 2, top,
                ctx.right() - (ctx.x + (third + gap) * 2));
    }

    // ------------------------------------------------------------------
    // Status banner
    // ------------------------------------------------------------------

    /**
     * Whether the player is online and, if so, where. The backend's {@code location} rides on the
     * payload root (it is a per-player Hypixel status, not per profile), so it stays correct while
     * you cycle profiles.
     */
    private int drawStatus(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject loc = PvDraw.obj(ctx.root, "location");
        if (loc == null) {
            return ctx.y;
        }
        SciFiRender.roundedRectWithBorder(g, ctx.x, ctx.y, ctx.width, STATUS_H - 4,
                SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        boolean online = loc.has("online") && loc.get("online").getAsBoolean();
        int textY = ctx.y + (STATUS_H - 4 - ctx.font.lineHeight) / 2;
        int dotY = ctx.y + (STATUS_H - 4) / 2 - 1;
        g.fill(ctx.x + 6, dotY, ctx.x + 9, dotY + 3, online ? SBSTheme.TOGGLE_ON : SBSTheme.TEXT_MUTED);

        String left = online ? "§aOnline" : "§8Offline";
        g.text(ctx.font, Component.literal(left), ctx.x + 13, textY, SBSTheme.TEXT);
        int leftEnd = ctx.x + 13 + ctx.font.width(left) + 8;

        // The location reads right-aligned and is trimmed against the space the online marker
        // leaves, so a long area name can never run into it.
        String right = online ? locationText(loc) : lastSeenText(loc);
        int space = ctx.right() - 6 - leftEnd;
        if (!right.isEmpty() && space > 12) {
            String shown = PvDraw.trim(ctx.font, right, space);
            g.text(ctx.font, Component.literal(shown),
                    ctx.right() - 6 - ctx.font.width(shown), textY, SBSTheme.TEXT);
        }
        return ctx.y + STATUS_H;
    }

    /** "The Park §8• §7SkyBlock" from the status payload, skipping the parts Hypixel left out. */
    private String locationText(JsonObject loc) {
        String mode = PvDraw.str(loc, "mode");
        String map = PvDraw.str(loc, "map");
        String game = PvDraw.str(loc, "game");
        StringBuilder sb = new StringBuilder();
        if (!map.isEmpty()) {
            sb.append("§f").append(map);
        } else if (!mode.isEmpty()) {
            sb.append("§f").append(PvDraw.pretty(mode));
        }
        if (!game.isEmpty() && !game.equalsIgnoreCase("SKYBLOCK")) {
            sb.append(sb.isEmpty() ? "§f" : " §8• §7").append(PvDraw.pretty(game));
        }
        return sb.toString();
    }

    private String lastSeenText(JsonObject loc) {
        long last = PvDraw.num(loc, "last_seen_min");
        if (last <= 0) {
            return "";
        }
        if (last < 60) {
            return "§7last seen §f" + last + "m §7ago";
        }
        if (last < 60 * 24) {
            return "§7last seen §f" + (last / 60) + "h §7ago";
        }
        return "§7last seen §f" + (last / 1440) + "d §7ago";
    }

    // ------------------------------------------------------------------
    // Left column: the player themselves
    // ------------------------------------------------------------------

    private void drawPlayerColumn(GuiGraphicsExtractor g, PvContext ctx, int x, int top, int w) {
        int available = ctx.bottom() - top;
        // The model takes what the level card leaves; below a certain height it is not worth
        // drawing at all and the card simply gets the column.
        boxH = Math.max(0, available - LEVEL_CARD_H - 6);
        boxX = x;
        boxY = top;
        boxW = w;
        if (boxH >= 40) {
            drawModel(g, ctx);
        } else {
            boxH = 0;
        }
        drawLevelCard(g, ctx, x, top + (boxH > 0 ? boxH + 6 : 0), w);
    }

    /** The framed 3D model of the viewed player. */
    private void drawModel(GuiGraphicsExtractor g, PvContext ctx) {
        SciFiRender.roundedRectWithBorder(g, boxX, boxY, boxW, boxH, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG_DISABLED, SBSTheme.CARD_BORDER);
        String uuid = PvDraw.str(ctx.root, "uuid");
        if (!uuid.equals(modelUuid)) {
            model = PvSkinRender.of(uuid, PvDraw.str(ctx.root, "name"));
            modelUuid = uuid;
        }
        if (model == null) {
            g.centeredText(ctx.font, Component.literal("§8no skin"), boxX + boxW / 2,
                    boxY + boxH / 2, SBSTheme.TEXT_MUTED);
            return;
        }
        // Inset by the border so the model never paints over the frame.
        model.render(g, boxX + 2, boxY + 2, boxW - 4, boxH - 4);
        if (ctx.hovered(boxX, boxY, boxW, boxH)) {
            g.centeredText(ctx.font, Component.literal("§8drag to rotate"), boxX + boxW / 2,
                    boxY + boxH - ctx.font.lineHeight - 3, SBSTheme.TEXT_MUTED);
        }
    }

    /** SkyBlock level with its progress bar, plus the two numbers people quote about a profile. */
    private void drawLevelCard(GuiGraphicsExtractor g, PvContext ctx, int x, int y, int w) {
        JsonObject profile = ctx.profile;
        y = PvDraw.heading(g, ctx.font, x, y, w, "SkyBlock Level");
        if (profile.has("sb_bar")) {
            int lvl = profile.getAsJsonArray("sb_bar").get(0).getAsInt();
            // The one site with a level and no Hypixel colour to preserve, so the table simply
            // applies; with Level Colours off this stays the aqua it has always been.
            Integer tint = sbs.modid.client.core.level.LevelColors.colorOf(lvl);
            Component level = tint == null
                    ? Component.literal("§b" + lvl)
                    : Component.literal(String.valueOf(lvl))
                            .withStyle(style -> style.withColor(
                                    net.minecraft.network.chat.TextColor.fromRgb(tint)));
            g.centeredText(ctx.font, level, x + w / 2, y + 1, SBSTheme.ACCENT_BRIGHT);
            int barY = y + ctx.font.lineHeight + 4;
            int prom = profile.getAsJsonArray("sb_bar").get(1).getAsInt();
            PvDraw.bar(g, x, barY, w - 4, prom / 1000.0, SBSTheme.ACCENT);
            y = barY + 7;
        }
        if (profile.has("magical_power")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Magical Power",
                    "§d" + PvDraw.num(profile, "magical_power"));
        }
        if (profile.has("skill_average")) {
            PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Skill Average",
                    "§b" + profile.get("skill_average").getAsDouble());
        }
    }

    // ------------------------------------------------------------------
    // Middle + right columns
    // ------------------------------------------------------------------

    private void drawSkillsColumn(GuiGraphicsExtractor g, PvContext ctx, int x, int top, int w) {
        int y = PvDraw.heading(g, ctx.font, x, top, w, "Skills");
        JsonObject skills = PvDraw.obj(ctx.profile, "skills");
        if (skills == null) {
            return;
        }
        for (String s : SKILL_ORDER) {
            if (skills.has(s) && y + PvDraw.CELL <= ctx.bottom()) {
                y = PvDraw.iconRow(g, ctx.font, x, y, w, SKILL_ICON.get(s),
                        PvDraw.pretty(s), skills.getAsJsonArray(s));
            }
        }
    }

    /** Dungeons over slayers: the two combat ladders, with the detail a click away under Combat. */
    private void drawCombatColumn(GuiGraphicsExtractor g, PvContext ctx, int x, int top, int w) {
        int y = PvDraw.heading(g, ctx.font, x, top, w, "Dungeons");
        JsonObject profile = ctx.profile;
        if (profile.has("catacombs_bar")) {
            y = PvDraw.iconRow(g, ctx.font, x, y, w, Items.DEEPSLATE_BRICKS,
                    "Catacombs", profile.getAsJsonArray("catacombs_bar"));
        }
        JsonObject classes = PvDraw.obj(profile, "classes");
        if (classes != null) {
            for (var entry : classes.entrySet()) {
                if (y + PvDraw.CELL > ctx.bottom()) {
                    break;
                }
                y = PvDraw.iconRow(g, ctx.font, x, y, w, CLASS_ICON.get(entry.getKey()),
                        PvDraw.pretty(entry.getKey()), entry.getValue().getAsJsonArray());
            }
        }
        JsonObject slayers = PvDraw.obj(profile, "slayers");
        if (slayers == null || slayers.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, x, y + 4, w, "Slayers");
        for (String s : SLAYER_ORDER) {
            if (slayers.has(s) && y + PvDraw.CELL <= ctx.bottom()) {
                y = PvDraw.iconRow(g, ctx.font, x, y, w, SLAYER_ICON.get(s),
                        SLAYER_NAME.getOrDefault(s, PvDraw.pretty(s)), slayers.getAsJsonArray(s));
            }
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseDragged(PvContext ctx, double mouseX, double mouseY,
                                double dragX, double dragY) {
        if (model == null || boxH <= 0) {
            return false;
        }
        // Only spin when the drag is over the model box, so dragging elsewhere stays free for
        // whatever the shell wants to do with it.
        if (mouseX < boxX || mouseX >= boxX + boxW || mouseY < boxY || mouseY >= boxY + boxH) {
            return false;
        }
        model.drag(dragX, dragY);
        return true;
    }

    @Override
    public void reset() {
        if (model != null) {
            model.resetRotation();
        }
    }
}
