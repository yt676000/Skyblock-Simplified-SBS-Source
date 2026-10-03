/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.npc.SkyblockNpcs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How each screen the Layout Recorder captures was opened: the player's own right-clicks, typed
 * command words, held-item uses and menu slot clicks, chained by {@link OpenerPath}. Developer tool,
 * active only while the recorder is on; it reads the player's actions on this client and never
 * sends, clicks or cancels anything.
 */
public final class ScreenOpeners {

    private static final ScreenOpeners INSTANCE = new ScreenOpeners();

    /** How far the catalogue position may be from the NPC seen before it is logged as moved. */
    private static final double MOVED_BLOCKS = 3.0;

    private final OpenerPath path = new OpenerPath();
    /** The context each open screen was opened in, so the capture a few ticks later uses it. */
    private final Map<Object, Context> contexts = new IdentityHashMap<>();
    private final Set<String> loggedNames = new HashSet<>();
    private Object currentScreen;
    private long pendingMenuClickAt = -1;

    /** What was known when a screen opened. */
    record Context(JsonArray path, String island, String zone, int[] playerBlock) {
    }

    private ScreenOpeners() {
    }

    public static ScreenOpeners getInstance() {
        return INSTANCE;
    }

    private static boolean on() {
        return LayoutRecorder.isOn();
    }

    // ------------------------------------------------------------------ root actions

    /** Right-click on an entity (MultiPlayerGameMode.interact). */
    public void onEntityInteract(Entity target) {
        if (!on() || target == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean playerModel = target instanceof Player;
        boolean realPlayer = playerModel && isRealPlayer((Player) target);
        String name = NpcNametags.resolveName(
                target.getCustomName() == null ? null : target.getCustomName().getString(), stands(target));
        String clean = name == null ? null : SkyblockNpcs.cleanName(name);
        List<SkyblockNpcs.Npc> named = clean == null ? List.of() : SkyblockNpcs.findAll(clean);
        NpcNametags.Kind kind = NpcNametags.classify(playerModel, realPlayer, !named.isEmpty());

        JsonObject step = new JsonObject();
        String island = SkyBlockLocation.island();
        switch (kind) {
            case PLAYER -> step.addProperty("via", "player");   // nothing about the player is stored
            case NPC -> {
                step.addProperty("via", "npc");
                List<NpcNametags.CatalogNpc> candidates = new ArrayList<>();
                named.forEach(n -> candidates.add(new NpcNametags.CatalogNpc(n.name(), n.island(), n.x(), n.y(), n.z())));
                NpcNametags.CatalogNpc match = NpcNametags.match(candidates, island,
                        target.getX(), target.getY(), target.getZ());
                if (match != null) {
                    step.addProperty("npc", match.name());
                    step.add("catalogPos", doubles(match.x(), match.y(), match.z()));
                    double off = Math.sqrt(sq(match.x() - target.getX()) + sq(match.y() - target.getY())
                            + sq(match.z() - target.getZ()));
                    if (off > MOVED_BLOCKS && loggedNames.add("moved|" + match.name() + "|" + island)) {
                        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Layouts] NPC moved \"{}\" catalogue {} {} {} -> seen {} {} {}",
                                match.name(), match.x(), match.y(), match.z(),
                                r2(target.getX()), r2(target.getY()), r2(target.getZ()));
                    }
                } else {
                    step.add("npc", com.google.gson.JsonNull.INSTANCE);
                    if (loggedNames.add("unmatched|" + clean + "|" + island)) {
                        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Layouts] unmatched NPC \"{}\" at {} {} {} {}",
                                clean, island, r2(target.getX()), r2(target.getY()), r2(target.getZ()));
                    }
                }
                if (clean != null) {
                    step.addProperty("name", LayoutRecorder.redactText(clean));
                }
                addNpcGeometry(step, target, player, island);
            }
            case ENTITY -> {
                step.addProperty("via", "entity");
                step.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
                if (clean != null) {
                    step.addProperty("name", LayoutRecorder.redactText(clean));
                }
                step.add("entityPos", doubles(target.getX(), target.getY(), target.getZ()));
            }
        }
        path.root(step, now);
    }

    /** Right-click on a block (MultiPlayerGameMode.useItemOn). */
    public void onBlockUse(BlockHitResult hit) {
        Minecraft mc = Minecraft.getInstance();
        if (!on() || hit == null || mc.level == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        JsonObject step = new JsonObject();
        step.addProperty("via", "block");
        step.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
        step.add("blockPos", LayoutOrigins.ints(new int[] {pos.getX(), pos.getY(), pos.getZ()}));
        path.root(step, System.currentTimeMillis());
    }

    /** Right-click with a held item (MultiPlayerGameMode.useItem). Only SkyBlock items count. */
    public void onItemUse(ItemStack held) {
        if (!on() || held == null || held.isEmpty()) {
            return;
        }
        String id = SkyblockItem.id(held);
        if (id == null || id.isEmpty()) {
            return;
        }
        JsonObject step = new JsonObject();
        step.addProperty("via", "item");
        step.addProperty("item", id);
        path.root(step, System.currentTimeMillis());
    }

    /** A command the player typed (ChatScreen.handleChatInput). Only the command word is kept. */
    public void onCommand(String message) {
        if (!on() || message == null || !message.startsWith("/")) {
            return;
        }
        String word = message.substring(1).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (word.isEmpty()) {
            return;
        }
        JsonObject step = new JsonObject();
        step.addProperty("via", "command");
        step.addProperty("command", word);
        path.root(step, System.currentTimeMillis());
    }

    /** A slot click in an open container (MultiPlayerGameMode.handleContainerInput, RETURN). */
    public void onSlotClick(String title, int slot, ItemStack stack) {
        if (!on() || slot < 0) {
            return;
        }
        JsonObject step = new JsonObject();
        step.addProperty("via", "menu");
        step.addProperty("title", LayoutRecorder.redactText(strip(title)));
        step.addProperty("slot", slot);
        if (stack != null && !stack.isEmpty()) {
            step.addProperty("item", LayoutRecorder.redactText(strip(stack.getHoverName().getString())));
        }
        long now = System.currentTimeMillis();
        path.menuClick(step, now);
        pendingMenuClickAt = now;
    }

    /** A chat line; attached to the running path's last step. */
    public void onChat(String plain) {
        if (!on() || plain == null || path.depth() == 0) {
            return;
        }
        path.chat(LayoutRecorder.redactText(strip(plain)), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ screens

    /** Every client tick from the recorder: notices screens opening and closing. */
    void tick(Object screen, boolean containerOpen) {
        long now = System.currentTimeMillis();
        // Only menus count as open: the chat box a command is typed in is a screen too.
        path.screenState(containerOpen, now);
        if (pendingMenuClickAt >= 0 && now - pendingMenuClickAt > OpenerPath.OPEN_WINDOW_MS) {
            path.menuClickStayed();
            pendingMenuClickAt = -1;
        }
        if (!containerOpen) {
            currentScreen = null;
            contexts.clear();
            return;
        }
        if (screen != currentScreen) {
            currentScreen = screen;
            contexts.clear();
            pendingMenuClickAt = -1;
            contexts.put(screen, new Context(path.screenOpened(now), SkyBlockLocation.island(),
                    SkyBlockLocation.zone(), playerBlock()));
        }
    }

    /** The origin of a captured screen, or {@code null} when it opened before the recorder ran. */
    JsonObject originFor(Object screen, long now) {
        Context context = contexts.get(screen);
        if (context == null) {
            return null;
        }
        return LayoutOrigins.build(context.island(), context.zone(), context.playerBlock(),
                context.path().deepCopy(), now);
    }

    // ------------------------------------------------------------------ helpers

    private static List<NpcNametags.Stand> stands(Entity target) {
        List<NpcNametags.Stand> out = new ArrayList<>();
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return out;
        }
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class,
                target.getBoundingBox().inflate(NpcNametags.HORIZONTAL, NpcNametags.ABOVE, NpcNametags.HORIZONTAL))) {
            if (stand == target || stand.getCustomName() == null) {
                continue;
            }
            out.add(new NpcNametags.Stand(stand.getX() - target.getX(), stand.getY() - target.getY(),
                    stand.getZ() - target.getZ(), stand.getCustomName().getString()));
        }
        return out;
    }

    /** Listed in the tab as a real player - Hypixel's widget rows and NPCs are not. */
    private static boolean isRealPlayer(Player target) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return false;
        }
        PlayerInfo info = connection.getPlayerInfo(target.getUUID());
        String name = info == null ? null : info.getProfile().name();
        return info != null && TabWidgets.isPlayer(info) && name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    private static void addNpcGeometry(JsonObject step, Entity npc, LocalPlayer player, String island) {
        step.add("npcPos", doubles(npc.getX(), npc.getY(), npc.getZ()));
        BlockPos npcBlock = npc.blockPosition();
        step.add("npcBlock", LayoutOrigins.ints(new int[] {npcBlock.getX(), npcBlock.getY(), npcBlock.getZ()}));
        step.add("playerPos", doubles(player.getX(), player.getY(), player.getZ()));
        BlockPos playerBlock = player.blockPosition();
        step.add("playerBlock", LayoutOrigins.ints(new int[] {playerBlock.getX(), playerBlock.getY(), playerBlock.getZ()}));
        step.addProperty("yaw", Math.round(player.getYRot() * 10) / 10.0);
        step.addProperty("pitch", Math.round(player.getXRot() * 10) / 10.0);
        step.addProperty("distance", r2(player.distanceTo(npc)));
        step.addProperty("island", island == null ? "" : island);
        String zone = SkyBlockLocation.zone();
        step.addProperty("zone", zone == null ? "" : zone);
    }

    private static int[] playerBlock() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new int[] {0, 0, 0};
        }
        BlockPos pos = player.blockPosition();
        return new int[] {pos.getX(), pos.getY(), pos.getZ()};
    }

    private static JsonArray doubles(double x, double y, double z) {
        JsonArray out = new JsonArray();
        out.add(r2(x));
        out.add(r2(y));
        out.add(r2(z));
        return out;
    }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll("(?i)§.", "").trim();
    }
}
