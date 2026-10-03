/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.lookingat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Optional;

/**
 * The "Looking At" chip: a small self-hiding card under the crosshair naming whatever the crosshair
 * ray hits - the block type, or the mob / player - long before you are close enough to read a
 * nametag in the world.
 *
 * <p><b>Why the entity name works at range.</b> Vanilla only <i>renders</i> nametags within a few
 * dozen blocks, but the name data itself rides on the entity and is synced for as long as the server
 * tracks it - so reading it off the entity (or off its floating nametag stand, for SkyBlock mobs)
 * works at any distance the entity still exists client-side. SkyBlock mobs are named the way the
 * rest of the mod names them: the armor-stand tag above the mob, found through the same stand→mob
 * association {@link MobHighlightTracker#mobBelow} uses, so the chip shows Hypixel's own coloured
 * line ("[Lv5] ⚔ Lapis Zombie 30/30❤") rather than "Zombie".
 *
 * <p><b>Occlusion is natural.</b> One block ray and one entity sweep share the ray; whichever hit is
 * closer wins. A mob behind a wall loses to the wall, so this never names anything you could not see
 * - it only reads at a distance what you could walk up to.
 *
 * <p>The scan is throttled (a fresh resolve every {@link #SCAN_INTERVAL_MS}); only the distance line
 * is recomputed per frame, from the cached hit point. Render-thread only, like every other card.
 */
public final class LookingAtHud {

    private static final int PAD = 5;

    /** How often the ray is re-resolved. Identification does not need to be frame-perfect. */
    private static final long SCAN_INTERVAL_MS = 100;

    /**
     * Slack when the entity hit is marginally behind the block hit: a mob standing flush against a
     * wall has an (inflated) bounding box whose ray entry point can sit just past the wall plane,
     * and without the slack the wall would steal the label from a mob that is plainly visible.
     */
    private static final double OCCLUSION_SLACK = 0.5;

    /** The resolved target: what to name, an optional muted detail, and where the ray hit. */
    private record Target(Component name, String detail, Vec3 point) {
    }

    private static long lastScanAt;
    private static Target target;

    private LookingAtHud() {
    }

    private static SBSConfig.LookingAtSettings cfg() {
        return ConfigManager.getInstance().get().lookingAt;
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.LookingAtSettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!cfg.enabled || player == null || level == null
                || HudLayout.isHidden(HudElement.LOOKING_AT)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanAt >= SCAN_INTERVAL_MS) {
            lastScanAt = now;
            target = resolve(cfg, level, player);
        }
        if (target == null) {
            return;
        }

        Font font = minecraft.font;
        int lineH = font.lineHeight + 2;
        String secondLine = secondLine(cfg, player);
        int contentW = font.width(target.name());
        if (secondLine != null) {
            contentW = Math.max(contentW, font.width(secondLine));
        }
        int width = Math.max(60, contentW + PAD * 2);
        int height = PAD * 2 + lineH * (secondLine == null ? 1 : 2) - 2;

        HudElement.Bounds b = HudElement.LOOKING_AT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.LOOKING_AT, x, y, width, height);

        HudLayout.begin(g, HudElement.LOOKING_AT);
        HudCard.draw(g, x, y, width, height);

        int iy = y + PAD;
        g.text(font, target.name(), x + (width - font.width(target.name())) / 2, iy, 0xFFF0F4FF);
        if (secondLine != null) {
            iy += lineH;
            g.text(font, Component.literal(secondLine),
                    x + (width - font.width(secondLine)) / 2, iy, SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }

    /**
     * The muted line under the name: entity type / block coordinates, and the live distance. The
     * distance is measured fresh every frame from the cached hit point, so it keeps counting down
     * while you approach even between scans. {@code null} collapses the card to the name alone.
     */
    private static String secondLine(SBSConfig.LookingAtSettings cfg, LocalPlayer player) {
        StringBuilder line = new StringBuilder();
        if (target.detail() != null) {
            line.append(target.detail());
        }
        if (cfg.showDistance) {
            if (line.length() > 0) {
                line.append("  ·  ");
            }
            line.append(Math.round(target.point().distanceTo(player.getEyePosition()))).append("m");
        }
        return line.length() == 0 ? null : line.toString();
    }

    /** One block clip + one entity sweep along the view ray; the closer hit becomes the target. */
    private static Target resolve(SBSConfig.LookingAtSettings cfg, ClientLevel level,
                                  LocalPlayer player) {
        if (!cfg.showBlocks && !cfg.showEntities) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(cfg.range));

        // The block clip always runs, even with block naming off: it is what occludes entities
        // behind walls. OUTLINE (not COLLIDER) so torches, crops and other pass-through decoration
        // are identifiable targets, the same shapes the vanilla crosshair picks.
        BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double blockDist = blockHit.getType() == HitResult.Type.BLOCK
                ? blockHit.getLocation().distanceTo(eye) : Double.MAX_VALUE;

        Entity bestEntity = null;
        Vec3 bestPoint = null;
        double bestDist = Double.MAX_VALUE;
        if (cfg.showEntities) {
            double reachSq = (cfg.range + 2.0) * (cfg.range + 2.0);
            for (Entity entity : level.entitiesForRendering()) {
                // Nametag stands are labels, not targets - aiming "at" one means aiming at its mob.
                if (entity == player || entity == player.getVehicle()
                        || entity instanceof ArmorStand || entity.isSpectator()) {
                    continue;
                }
                if (entity.distanceToSqr(eye.x, eye.y, eye.z) > reachSq) {
                    continue;
                }
                Optional<Vec3> clip = entity.getBoundingBox().inflate(0.25).clip(eye, end);
                if (clip.isEmpty()) {
                    continue;
                }
                double dist = clip.get().distanceTo(eye);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestEntity = entity;
                    bestPoint = clip.get();
                }
            }
        }

        if (bestEntity != null && bestDist <= blockDist + OCCLUSION_SLACK) {
            return entityTarget(level, bestEntity, bestPoint);
        }
        if (cfg.showBlocks && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockTarget(cfg, level, blockHit);
        }
        return null;
    }

    /**
     * Names a block the way SkyBlock names it. Hypixel's custom ores are ordinary vanilla blocks -
     * a Mithril vein really is gray wool - so where {@link SkyblockBlockNames} knows better for the
     * island you are on, its name is the headline and the vanilla block drops to the muted line.
     * Everywhere else the block is simply itself and nothing is invented.
     */
    private static Target blockTarget(SBSConfig.LookingAtSettings cfg, ClientLevel level,
                                      BlockHitResult blockHit) {
        BlockPos pos = blockHit.getBlockPos();
        var state = level.getBlockState(pos);
        Component vanillaName = state.getBlock().getName();
        String skyblockName = cfg.skyblockBlockNames ? SkyblockBlockNames.nameFor(state) : null;

        StringBuilder detail = new StringBuilder();
        if (skyblockName != null) {
            detail.append(vanillaName.getString());
        }
        if (cfg.showCoords) {
            if (detail.length() > 0) {
                detail.append("  ·  ");
            }
            detail.append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ());
        }
        return new Target(skyblockName != null ? Component.literal(skyblockName) : vanillaName,
                detail.length() == 0 ? null : detail.toString(), blockHit.getLocation());
    }

    /**
     * Names an entity the way the player knows it: the SkyBlock nametag line when the mob carries
     * one (level, name and health, in Hypixel's own colours), its own custom name otherwise, and
     * the plain type / player name as the fallback. When the nametag names it, the vanilla type
     * goes into the detail line - "what is under that costume" is half the question at range.
     */
    private static Target entityTarget(ClientLevel level, Entity entity, Vec3 point) {
        String detail = null;
        Component name = null;
        ArmorStand tag = nametagAbove(level, entity);
        if (tag != null) {
            name = tag.getCustomName();
            detail = entity.getType().getDescription().getString();
        }
        if (name == null && entity.hasCustomName()) {
            name = entity.getCustomName();
        }
        if (name == null) {
            name = entity.getName();
        }
        return new Target(name, detail, point);
    }

    /**
     * The nametag armor stand floating over this entity, or {@code null}.
     *
     * <p>This deliberately does <b>not</b> reuse {@code MobHighlightTracker.mobBelow}: that helper
     * excludes {@link net.minecraft.world.entity.player.Player} instances, and SkyBlock's NPCs are
     * player entities - so every shopkeeper failed the association and fell back to its profile
     * name, which is the login-code-looking string rather than the name written above its head.
     * Ownership is resolved here instead, over every living entity including players.
     *
     * <p>A stand counts as this entity's when no other living entity stands horizontally closer to
     * it, which is what stops a tag being borrowed from a neighbour in a crowd. Of several own lines
     * (an NPC's name plus its click hint, a boss stacking two or three) the highest is the title.
     */
    private static ArmorStand nametagAbove(ClientLevel level, Entity entity) {
        var box = entity.getBoundingBox();
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class,
                box.inflate(0.6, 0, 0.6).expandTowards(0, 3.0, 0),
                stand -> stand.hasCustomName() && stand.getY() >= box.minY);
        ArmorStand best = null;
        for (ArmorStand stand : stands) {
            if (!ownsStand(level, entity, stand) ) {
                continue;
            }
            if (best == null || stand.getY() > best.getY()) {
                best = stand;
            }
        }
        return best;
    }

    /** Whether {@code entity} is the nearest thing under {@code stand} - i.e. whose tag it is. */
    private static boolean ownsStand(ClientLevel level, Entity entity, ArmorStand stand) {
        double mine = horizontalDistanceSq(stand, entity);
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                stand.getBoundingBox().inflate(1.5, 4.0, 1.5),
                candidate -> !(candidate instanceof ArmorStand) && candidate.isAlive())) {
            if (other != entity && horizontalDistanceSq(stand, other) < mine - 1.0e-6) {
                return false;
            }
        }
        return true;
    }

    /** Horizontal distance only: a tag sits directly above its owner, however tall the owner is. */
    private static double horizontalDistanceSq(Entity a, Entity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
