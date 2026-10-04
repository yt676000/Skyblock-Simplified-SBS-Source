/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.chest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The reward chests in the room, outlined by what they are worth: green the best, yellow another that
 * profits, red a loss, and a grey {@code ?} on a chest nobody has looked into yet.
 *
 * <p><b>Where the numbers come from.</b> A chest's contents are unknown until its preview menu is
 * opened - a menu titled by the tier ("Wood", "Gold", ...) whose "Open Reward Chest" item lists the
 * Contents and the Cost. That item is valued by {@link DungeonChestValue} and ranked by
 * {@link DungeonChestRanking#marks}, exactly as the menu overlay does it; nothing is priced twice.
 *
 * <p><b>Which chest a preview belongs to.</b> The menu does not say. The only moment the client
 * knows is the click that opened it, and that click is on an <i>armor stand</i>, not the block:
 * during the 2026-10-04 F1 recording the client sent entity interacts and no block use, and the
 * Layout Recorder put every preview's origin on an armor stand. So the position comes from
 * {@code MultiPlayerGameMode.interact}, not {@code BlockInteractMixin}. A chest block in the stand's
 * column is preferred when there is one, so the box hugs the chest.
 *
 * <p><b>Bought</b> is read from Hypixel's "WOOD CHEST REWARDS" chat line, which followed the purchase
 * click in the recording; the menu closing alone is no signal, since every preview closes.
 *
 * <p>Display only: nothing here clicks, buys or sends anything.
 */
public final class RewardChestGlow {

    private static final RewardChestGlow INSTANCE = new RewardChestGlow();

    /** Menu titles of the previews. Obsidian and Bedrock are higher floors' and not yet recorded. */
    private static final Set<String> TIERS = Set.of("wood", "gold", "diamond", "emerald", "obsidian", "bedrock");
    /** The preview's buy button; its lore is the chest tooltip {@link DungeonChestValue} reads. */
    private static final String OPEN_ITEM = "Open Reward Chest";
    /** A preview may be attributed to a click this recent; matches {@code BlockInteractTracker}. */
    private static final long CLICK_FRESH_MS = 2_000L;
    private static final long SCAN_MS = 1_000L;
    /** Around the player: the reward room is small, and secret chests elsewhere must not show. */
    private static final int SCAN_RADIUS = 12;
    private static final double DRAW_DISTANCE_SQ = 64 * 64;

    private static final int GREEN = 0xFF57D977;
    private static final int YELLOW = 0xFFFFD24D;
    private static final int RED = 0xFFFF6B6B;
    private static final int UNKNOWN = 0xFFB0B0B0;

    private final RewardChestBoard board = new RewardChestBoard();

    private BlockPos clickedPos;
    private String clickedWhat;
    private long clickedAt;
    /** The preview menu currently open and the chest it was linked to, so a reroll updates it. */
    private AbstractContainerMenu openMenu;
    private BlockPos openKey;
    private int openStateId = Integer.MIN_VALUE;
    private ClientLevel level;
    private long scannedAt;

    private RewardChestGlow() {
        ChatPatternRegistry.getInstance().register(
                "^\\s*(WOOD|GOLD|DIAMOND|EMERALD|OBSIDIAN|BEDROCK) CHEST REWARDS\\s*$",
                matcher -> board.bought(matcher.group(1)), "reward chest bought");
    }

    public static RewardChestGlow getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** From {@code AttackTrackMixin} on every entity right-click; remembers where it was. */
    public void onEntityInteract(Entity target) {
        if (target == null || !cfg().rewardChestGlow || !DungeonStateManager.getInstance().inDungeon()) {
            return;
        }
        clickedPos = chestAt(target.level(), target.getX(), target.getY(), target.getZ());
        clickedAt = System.currentTimeMillis();
        clickedWhat = target.getType().toShortString()
                + (target.getCustomName() != null ? " '" + target.getCustomName().getString() + "'" : "")
                + (target instanceof ArmorStand stand ? " head=" + stand.getItemBySlot(EquipmentSlot.HEAD).getItem() : "")
                + String.format(Locale.ROOT, " at %.2f/%.2f/%.2f", target.getX(), target.getY(), target.getZ());
    }

    /** Client tick: links an open preview to its chest, finds unpreviewed ones, clears on leaving. */
    public void tick(Minecraft minecraft) {
        try (Perf.Section perf = Perf.tick("tick.RewardChestGlow")) {
            // Leaving the dungeon, or a new level (the next run is a new instance), ends the run.
            boolean active = cfg().rewardChestGlow && DungeonStateManager.getInstance().inDungeon();
            if (!active || minecraft.level != level) {
                board.clear();
                openMenu = null;
                clickedPos = null;
                level = minecraft.level;
                if (!active) {
                    return;
                }
            }
            readPreview(minecraft);
            long now = System.currentTimeMillis();
            if (now - scannedAt >= SCAN_MS && minecraft.player != null && level != null
                    && (DungeonStateManager.getInstance().phase() == DungeonEvents.Phase.BOSS || !board.chests().isEmpty())) {
                scannedAt = now;
                board.seen(scanRoom(level, minecraft.player.blockPosition()));
            }
        }
    }

    private void readPreview(Minecraft minecraft) {
        if (!(sbs.modid.client.core.api.ScreenAccess.current() instanceof AbstractContainerScreen<?> screen)) {
            openMenu = null;
            return;
        }
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString().replaceAll("§.", "").trim();
        if (!TIERS.contains(title.toLowerCase(Locale.ROOT))) {
            openMenu = null;
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (menu == openMenu && menu.getStateId() == openStateId) {
            return;
        }
        DungeonChestValue.Chest chest = null;
        int upper = Math.max(0, menu.slots.size() - 36);
        for (int i = 0; i < upper && chest == null; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && OPEN_ITEM.equals(stack.getHoverName().getString().replaceAll("§.", "").trim())) {
                chest = DungeonChestValue.of(stack);
            }
        }
        if (chest == null) {
            return;   // contents not arrived yet; try next tick
        }
        BlockPos key = menu == openMenu ? openKey : null;
        if (key == null) {
            if (clickedPos == null || System.currentTimeMillis() - clickedAt > CLICK_FRESH_MS) {
                return;   // opened some other way (a keybind, a menu): no chest to pin it on
            }
            key = clickedPos;
            clickedPos = null;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dungeon] reward chest {} linked to {} via {} - profit {}",
                    title, key.toShortString(), clickedWhat, chest.profit());
        }
        openMenu = menu;
        openStateId = menu.getStateId();
        openKey = board.preview(key, title, chest);
    }

    /**
     * The chest under a clicked or seen stand: a chest block in its column when there is one, else
     * the stand's own block.
     */
    private static BlockPos chestAt(net.minecraft.world.level.Level level, double x, double y, double z) {
        BlockPos column = BlockPos.containing(x, y, z);
        for (int dy = 0; dy >= -2; dy--) {
            BlockPos pos = column.offset(0, dy, 0);
            if (level.getBlockState(pos).getBlock() instanceof ChestBlock) {
                return pos;
            }
        }
        return column;
    }

    /**
     * Chests in the room nobody has previewed: chest blocks, and armor stands wearing a chest. Which
     * of the two Hypixel uses is not recorded yet - the clicked stand's head item is logged on every
     * link so the next run says - so both are looked for.
     */
    private static List<BlockPos> scanRoom(ClientLevel level, BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.getBlockState(pos).getBlock() instanceof ChestBlock) {
                        out.add(pos.immutable());
                    }
                }
            }
        }
        AABB area = new AABB(center).inflate(SCAN_RADIUS);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, area)) {
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head.is(Items.CHEST) || head.is(Items.TRAPPED_CHEST)) {
                out.add(chestAt(level, stand.getX(), stand.getY(), stand.getZ()));
            }
        }
        return out;
    }

    /** From the TAIL of {@code DebugRenderer.emitGizmos}: depth-tested boxes and labels. */
    public void emit(double camX, double camY, double camZ) {
        if (!cfg().rewardChestGlow || board.isEmpty()) {
            return;
        }
        for (var entry : board.chests().entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.distToCenterSqr(camX, camY, camZ) > DRAW_DISTANCE_SQ) {
                continue;
            }
            DungeonChestRanking.Mark mark = board.mark(pos);
            int color = mark == DungeonChestRanking.Mark.BEST ? GREEN
                    : mark == DungeonChestRanking.Mark.PROFIT ? YELLOW : RED;
            DungeonChestValue.Chest chest = entry.getValue().chest();
            draw(pos, color, DungeonChestOverlay.profitText(chest.profit(), chest.complete()));
        }
        for (BlockPos pos : board.unknown()) {
            if (pos.distToCenterSqr(camX, camY, camZ) <= DRAW_DISTANCE_SQ) {
                draw(pos, UNKNOWN, "?");
            }
        }
    }

    /** A chest-sized box (inset a sixteenth, 14/16 tall, like the model) and a label above it. */
    private static void draw(BlockPos pos, int color, String label) {
        AABB box = new AABB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625,
                pos.getX() + 0.9375, pos.getY() + 0.875, pos.getZ() + 0.9375);
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color, 2.0f, (color & 0x00FFFFFF) | 0x50000000));
        Gizmos.billboardText(label, new Vec3(pos.getX() + 0.5, pos.getY() + 1.25, pos.getZ() + 0.5),
                TextGizmo.Style.forColorAndCentered(color));
    }
}
