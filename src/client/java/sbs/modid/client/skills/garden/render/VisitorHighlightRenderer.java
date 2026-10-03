/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.render;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.player.RealPlayers;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.garden.logic.GardenBlueprintManager;
import sbs.modid.client.skills.garden.logic.VisitorHighlights;
import sbs.modid.client.skills.garden.logic.VisitorHighlights.Kind;
import sbs.modid.client.skills.garden.logic.VisitorHighlights.Rarity;
import sbs.modid.client.skills.garden.logic.VisitorHighlights.Verdict;
import sbs.modid.client.skills.garden.logic.VisitorOfferStore;
import sbs.modid.client.skills.garden.logic.VisitorShoppingList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Garden visitor highlight: a gold box and "★ Visitor · reward" over a visitor whose recorded offer
 * includes a valuable reward, a small "?" over one whose menu was never opened, and optionally a mark
 * on Legendary-and-up visitors by the colour of their name.
 *
 * <p>Rendering only - nothing is clicked, targeted or sent. The entity walk runs on a
 * {@link #SCAN_MS} throttle, never per frame; the frame only draws the cached list.
 *
 * <p><b>Unverified</b>: which entity carries a visitor's name. A visitor is a player-shaped NPC
 * (no tab entry, {@link RealPlayers}); its own name and every named armor stand just above it are
 * offered to {@link VisitorHighlights#matchVisitor}, which accepts only names the tab's Visitors
 * widget lists. Each first match is logged under {@code [SBS][Visitor]} with where the name came from,
 * which is what settles the question from a play session's log.
 */
public final class VisitorHighlightRenderer {

    private static final long SCAN_MS = 250L;
    private static final int DEFAULT_RGB = 0xFFB300;
    private static final int UNKNOWN_RGB = 0xB0B0B0;
    private static final int PLAIN_RGB = 0x7FD9FF;

    private record Target(Entity entity, Verdict verdict, Rarity rarity) {
    }

    private static List<Target> targets = List.of();
    private static long lastScan;
    /** Visitors already logged this session, so the log says each mapping once. */
    private static final Set<String> logged = new HashSet<>();

    private VisitorHighlightRenderer() {
    }

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.GardenHelpersSettings cfg = cfg();
        if (!cfg.visitorHighlight && !cfg.visitorHighlightRarity) {
            targets = List.of();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScan >= SCAN_MS) {
            lastScan = now;
            targets = GardenBlueprintManager.inGarden() ? scan(minecraft.level, minecraft.player, cfg) : List.of();
        }
        if (targets.isEmpty()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        Integer custom = OverlayColor.parseHex(cfg.visitorHighlightColorHex);
        int valuableRgb = custom != null ? custom & 0xFFFFFF : DEFAULT_RGB;

        for (Target target : targets) {
            Entity entity = target.entity();
            if (!entity.isAlive()) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            Verdict verdict = target.verdict();
            boolean valuable = cfg.visitorHighlight && verdict.kind() == Kind.VALUABLE;
            boolean unknown = cfg.visitorHighlight && cfg.visitorHighlightUnknown && verdict.kind() == Kind.UNKNOWN;
            boolean rare = cfg.visitorHighlightRarity && target.rarity() != null && target.rarity().notable();
            if (valuable) {
                // The glow: a translucent fill under a bold outline, so it reads from across the plot.
                WorldRender.fillBox(g, viewProjection, camPos, box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ, 0x30000000 | valuableRgb);
                WorldRender.boxEdges(g, viewProjection, camPos, box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ, 0xFF000000 | valuableRgb, 3);
                label(g, font, viewProjection, camPos, box, VisitorHighlights.label(verdict), valuableRgb);
            } else if (rare) {
                WorldRender.boxEdges(g, viewProjection, camPos, box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ, 0xC0000000 | PLAIN_RGB, 2);
                label(g, font, viewProjection, camPos, box,
                        verdict.visitor() + " · " + pretty(target.rarity()), PLAIN_RGB);
            } else if (unknown) {
                label(g, font, viewProjection, camPos, box, VisitorHighlights.label(verdict), UNKNOWN_RGB);
            }
        }
    }

    /** Walks the loaded entities once: every player-shaped NPC whose name is a listed visitor. */
    private static List<Target> scan(ClientLevel level, Player self, SBSConfig.GardenHelpersSettings cfg) {
        VisitorOfferStore store = VisitorOfferStore.getInstance();
        List<String> tab = store.tabVisitors();
        if (tab.isEmpty()) {
            return List.of();
        }
        Map<String, VisitorShoppingList.Offer> offers = store.offersByKey();
        List<Target> out = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof Player) || entity == self || RealPlayers.isRealPlayer(entity)) {
                continue;
            }
            List<String> raw = new ArrayList<>();
            List<String> sources = new ArrayList<>();
            raw.add(legacy(entity.getName()));
            sources.add("own name");
            if (entity.getCustomName() != null) {
                raw.add(legacy(entity.getCustomName()));
                sources.add("custom name");
            }
            AABB above = entity.getBoundingBox().inflate(0.6, 0, 0.6).expandTowards(0, 3.0, 0);
            for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, above,
                    s -> s.getCustomName() != null)) {
                raw.add(legacy(stand.getCustomName()));
                sources.add("armor stand +" + String.format(java.util.Locale.ROOT, "%.1f",
                        stand.getY() - entity.getY()));
            }
            for (int i = 0; i < raw.size(); i++) {
                String visitor = VisitorHighlights.matchVisitor(List.of(raw.get(i)), tab);
                if (visitor == null) {
                    continue;
                }
                Verdict verdict = VisitorHighlights.classify(visitor, offers);
                Rarity rarity = VisitorHighlights.rarityOf(raw.get(i));
                out.add(new Target(entity, verdict, rarity));
                if (logged.add(visitor)) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] highlight matched '{}' via {} raw='{}' "
                            + "all={} kind={} rarity={}", visitor, sources.get(i), raw.get(i), raw,
                            verdict.kind(), rarity);
                }
                break;
            }
        }
        return out;
    }

    /**
     * A component as a §-coded string, so the rarity colour survives: literal § codes are kept as
     * sent, and a style colour on a segment becomes its code.
     */
    private static String legacy(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((Style style, String text) -> {
            if (!text.isEmpty() && style.getColor() != null) {
                // 26.2's ChatFormatting has no getChar(); match the colour against each code instead.
                for (char code : "0123456789abcdef".toCharArray()) {
                    ChatFormatting format = ChatFormatting.getByCode(code);
                    if (format != null && style.getColor().equals(TextColor.fromLegacyFormat(format))) {
                        out.append('§').append(code);
                        break;
                    }
                }
            }
            out.append(text);
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static String pretty(Rarity rarity) {
        String name = rarity.name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static void label(GuiGraphicsExtractor g, Font font, Matrix4f viewProjection, Vec3 camPos,
                              AABB box, String text, int rgb) {
        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.9, (box.minZ + box.maxZ) / 2);
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(font, Component.literal(text), screen[0], screen[1], 0xFF000000 | rgb);
        }
    }
}
