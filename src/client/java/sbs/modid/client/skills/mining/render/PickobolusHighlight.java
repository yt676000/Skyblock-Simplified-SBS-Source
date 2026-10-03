/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pickobolus preview: while you hold a pickaxe carrying the Pickobolus ability, the blocks the throw
 * would break are highlighted where you are aiming, so you can line the throw up on a full vein
 * instead of guessing and burning the cooldown.
 *
 * <p><b>Why a preview and not an after-the-fact highlight.</b> The ability destroys the blocks it
 * hits, so marking them once it has fired would only ever draw on empty air. The useful moment is
 * before the throw, which means predicting the impact point: a ray is cast along your view up to
 * {@code aimRange} blocks and the block it lands on is treated as the centre of the explosion. That
 * matches a real throw closely for the straight shots people actually aim - the thrown pickaxe is a
 * projectile, so a long arcing throw will drift from the preview.
 *
 * <p><b>The numbers.</b> The ability mines every ore within a <b>3 block radius</b>, unchanged across
 * all three ability levels (only its cooldown scales, 60s/50s/40s). The radius is still read out of the
 * pickaxe's own lore when the lore states one, so a future rebalance is picked up without a code
 * change, and only falls back to the configured value when nothing is parseable.
 *
 * <p><b>What counts as a target is best-effort.</b> Hypixel's ores are ordinary blocks wearing a
 * texture pack - gemstones are stained glass, mithril is prismarine and wool - so there is no reliable
 * "is this an ore" test client-side. By default every solid block in range is highlighted, which is
 * both honest and what makes the affected volume readable underground. "Ores Only" narrows it to
 * blocks whose registry id looks like an ore; the distinct block ids found in range are logged under
 * {@code [SBS][Pickobolus]} so that list can be tuned against a real mine.
 */
public final class PickobolusHighlight {

    /** Both spellings are in circulation; the in-game one is "Pickobolus". */
    private static final Pattern ABILITY = Pattern.compile("(?i)Pickob[ou]lus");

    /** "...within a 3 block radius" - the lore states the real number, so prefer it over the config. */
    private static final Pattern LORE_RADIUS = Pattern.compile("(?i)within\\s+a?\\s*(\\d+)\\s*block\\s*radius");

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** Registry-id fragments that mark a block as ore-like, for the "Ores Only" mode. */
    private static final Set<String> ORE_HINTS = Set.of(
            "_ore", "ancient_debris", "stained_glass", "prismarine", "wool", "amethyst", "quartz_block");

    /** Never highlight these, they are scenery rather than anything a throw would clear. */
    private static final Set<String> NEVER = Set.of("barrier", "light", "air");

    /**
     * A radius-3 sphere holds ~123 blocks; drawing every one of them is both unreadable and a lot of
     * projected geometry. Only blocks touching air are drawn - the surface you can actually see - and
     * even that is capped so a pathological spot cannot stall a frame.
     */
    private static final int MAX_DRAWN = 220;

    /** The scan only re-runs when the aim moved to a new block, or after this long (blocks change). */
    private static final long RESCAN_MS = 250L;

    private static final long LOG_INTERVAL_MS = 10_000L;

    // Cached scan result, keyed by the centre it was computed for.
    private static BlockPos cachedCentre;
    private static long cachedAt;
    private static int cachedRadius;
    private static boolean cachedOresOnly;
    private static List<BlockPos> cachedBlocks = List.of();

    private static long lastLogAt;

    private PickobolusHighlight() {
    }

    /** Called from the HUD world-render pass once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.PickobolusSettings cfg = ConfigManager.getInstance().get().pickobolus;
        if (!cfg.enabled || !sbs.modid.client.skills.SkillIslands.miningAllowed()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        int radius = abilityRadius(held, cfg.radius);
        if (radius <= 0) {
            return;                                   // not holding a Pickobolus pickaxe
        }

        BlockPos centre = aimBlock(player, level, cfg.aimRange);
        if (centre == null) {
            return;                                   // aiming at open air / out of range
        }

        List<BlockPos> blocks = scan(level, centre, radius, cfg.oresOnly);
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int color = cfg.color.argb();

        if (cfg.showAreaOutline) {
            // The whole affected volume as one box, so the reach of the throw reads at a glance even
            // when the blocks inside it are hidden behind the face you are looking at.
            WorldRender.boxEdges(g, viewProjection, camPos,
                    centre.getX() - radius, centre.getY() - radius, centre.getZ() - radius,
                    centre.getX() + radius + 1.0, centre.getY() + radius + 1.0, centre.getZ() + radius + 1.0,
                    withAlpha(color, 0x55), 1);
        }
        for (BlockPos pos : blocks) {
            if (cfg.fillBlocks) {
                WorldRender.fillBox(g, viewProjection, camPos,
                        pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0, withAlpha(color, 0x40));
            }
            WorldRender.boxEdges(g, viewProjection, camPos,
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0, color, 1);
        }
    }

    // ------------------------------------------------------------------ the ability

    /**
     * The explosion radius for the held item, or {@code 0} when it does not carry the ability. The
     * lore's own number wins when it states one; {@code fallback} is the configured default.
     */
    private static int abilityRadius(ItemStack stack, int fallback) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        // Matched against the lore joined into one string, not line by line: the description is wrapped
        // to the tooltip width, so "within a" and "3 block radius" routinely land on separate lines and
        // a per-line match would silently never find the number.
        StringBuilder joined = new StringBuilder();
        for (Component line : lore.lines()) {
            joined.append(line.getString().replaceAll(SECTION_SIGN + ".", "")).append(' ');
        }
        String text = joined.toString();
        if (!ABILITY.matcher(text).find()) {
            return 0;
        }
        Matcher stated = LORE_RADIUS.matcher(text);
        int radius = stated.find() ? Integer.parseInt(stated.group(1)) : 0;
        return radius > 0 ? radius : Math.max(1, fallback);
    }

    /** The block the throw is predicted to land on, or {@code null} when the ray hits nothing. */
    private static BlockPos aimBlock(LocalPlayer player, ClientLevel level, int range) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(Math.max(1, range)));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    // ------------------------------------------------------------------ the affected blocks

    /**
     * The blocks inside the explosion that would be highlighted, cached: re-scanning ~123 positions
     * every frame while you sweep the crosshair is pure waste when the answer only changes as the aim
     * crosses into a new block.
     */
    private static List<BlockPos> scan(ClientLevel level, BlockPos centre, int radius, boolean oresOnly) {
        long now = System.currentTimeMillis();
        if (centre.equals(cachedCentre) && radius == cachedRadius && oresOnly == cachedOresOnly
                && now - cachedAt < RESCAN_MS) {
            return cachedBlocks;
        }
        cachedCentre = centre;
        cachedAt = now;
        cachedRadius = radius;
        cachedOresOnly = oresOnly;

        List<BlockPos> found = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        double limit = radius + 0.5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > limit * limit) {
                        continue;                     // the lore says radius, so the volume is a sphere
                    }
                    BlockPos pos = centre.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                            .toLowerCase(Locale.ROOT);
                    if (NEVER.contains(id)) {
                        continue;
                    }
                    seenIds.add(id);
                    if (oresOnly && !looksLikeOre(id)) {
                        continue;
                    }
                    if (!touchesAir(level, pos)) {
                        continue;                     // buried: nothing of it would be visible anyway
                    }
                    found.add(pos);
                    if (found.size() >= MAX_DRAWN) {
                        cachedBlocks = found;
                        return cachedBlocks;
                    }
                }
            }
        }
        logSeen(seenIds, now);
        cachedBlocks = found;
        return cachedBlocks;
    }

    private static boolean looksLikeOre(String id) {
        for (String hint : ORE_HINTS) {
            if (id.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any of the six neighbours is air - i.e. whether this block is on the visible surface. */
    private static boolean touchesAir(ClientLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs the distinct block ids seen inside the radius, throttled. "Ores Only" has to guess at what
     * Hypixel is using as an ore this patch; this is what turns that guess into something tunable from
     * a real mine rather than from memory.
     */
    private static void logSeen(Set<String> ids, long now) {
        if (ids.isEmpty() || now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Pickobolus] blocks in range: {}", ids);
    }

    /** Replaces the alpha byte of an ARGB colour, leaving the RGB alone. */
    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
