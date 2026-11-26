/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.ChatTag;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.model.FishingShareMode;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fishing Share: offers to tell the party (or everyone) where a hotspot is, and announces a rare sea
 * creature with a title, a sound and an optional party share.
 *
 * <p><b>Nothing leaves the client without a click or an explicit opt-in.</b> A hotspot only ever
 * produces a local clickable line; a rare creature does the same unless the player set its mode to
 * {@link FishingShareMode#AUTO}. The text that is sent is composed here and parked under a short id,
 * so the click command carries an id, never a message - a forged click cannot make the player say
 * anything this class did not write. Every send goes through {@link ChatTag} and a global gap.
 *
 * <p>Hotspot detection reads nametags and is <b>unverified</b>; see {@link HotspotParser}.
 */
public final class FishingShare {

    private static final FishingShare INSTANCE = new FishingShare();

    /** Root command the clickable lines run (handled locally by {@code SBSCommands}). */
    public static final String COMMAND = "sbsfishshare";

    /** A line with a {@code "<sender>: "} prefix is a player talking, never a spawn sentence. */
    private static final Pattern PLAYER_CHAT =
            Pattern.compile("^(?:\\[[^]]*]\\s*)?[A-Za-z0-9_]{1,16}\\s*:\\s");

    private static final double SCAN_RADIUS = 30.0;
    private static final int SCAN_EVERY_TICKS = 20;
    private static final long MIN_SEND_GAP_MS = 2_500L;
    private static final long PENDING_TTL_MS = 5 * 60_000L;
    private static final int MAX_PENDING = 16;
    private static final long RARE_DEDUPE_MS = 4_000L;

    private int tickCounter;
    private int nextId = 1;
    private long lastSendAt;
    private final Set<Long> hotspotsOffered = new HashSet<>();
    private final Set<String> unknownLogged = new HashSet<>();
    private final Map<String, Long> rareSeenAt = new HashMap<>();
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();

    private record Pending(String message, long createdAt) {
    }

    private FishingShare() {
    }

    public static FishingShare getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FishingShareSettings cfg() {
        return ConfigManager.getInstance().get().fishingShare;
    }

    /** Tick gate: the hotspot scan has nothing to do while switched off. */
    public static boolean hotspotScanOn() {
        SBSConfig.FishingShareSettings cfg = cfg();
        return cfg != null && cfg.enabled && cfg.hotspotShare;
    }

    // ------------------------------------------------------------------ hotspots

    /** Called per tick while fishing; scans once a second. */
    public void onClientTick() {
        if (!hotspotScanOn() || ++tickCounter < SCAN_EVERY_TICKS) {
            return;
        }
        tickCounter = 0;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        AABB box = player.getBoundingBox().inflate(SCAN_RADIUS, 12.0, SCAN_RADIUS);
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class, box, ArmorStand::hasCustomName);
        for (ArmorStand label : stands) {
            if (!HotspotParser.isHotspotLabel(plainName(label))) {
                continue;
            }
            int x = (int) Math.floor(label.getX());
            int y = (int) Math.floor(label.getY());
            int z = (int) Math.floor(label.getZ());
            long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
            if (!hotspotsOffered.add(key)) {
                continue;
            }
            HotspotParser.Buff buff = buffNear(label, stands);
            offerHotspot(HotspotParser.hotspotName(buff), x, y, z);
        }
    }

    /** The buff stand next to a {@code HOTSPOT} label; logs neighbours it cannot read. */
    private HotspotParser.Buff buffNear(ArmorStand label, List<ArmorStand> stands) {
        for (ArmorStand other : stands) {
            if (other == label
                    || Math.abs(other.getX() - label.getX()) > 2.0
                    || Math.abs(other.getZ() - label.getZ()) > 2.0
                    || Math.abs(other.getY() - label.getY()) > 3.0) {
                continue;
            }
            String name = plainName(other);
            HotspotParser.Buff buff = HotspotParser.parseBuff(name);
            if (buff != null) {
                return buff;
            }
            if (name != null && !name.isBlank() && unknownLogged.add(name)) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Fishing] unparsed nametag next to a HOTSPOT label: '{}'", name);
            }
        }
        return null;
    }

    private static String plainName(ArmorStand stand) {
        Component custom = stand.getCustomName();
        return custom == null ? null : PlainText.strip(custom.getString()).trim();
    }

    private void offerHotspot(String name, int x, int y, int z) {
        String message = HotspotParser.shareMessage(name, x, y, z, SkyBlockLocation.island());
        int id = park(message);
        MutableComponent line = Component.literal(" Share " + name + ": ").withColor(SBSChat.WHITE)
                .append(button("[To PARTY chat]", 0x57D977, "party", id, message))
                .append(Component.literal(" or ").withColor(0x8FA9C8))
                .append(button("[To ALL chat]", 0xFFAA00, "all", id, message));
        SBSChat.send("FishingShare", line);
    }

    // ------------------------------------------------------------------ rare sea creatures

    /** Every chat line. Never modifies it. */
    public void onChat(String raw) {
        SBSConfig.FishingShareSettings cfg = cfg();
        if (cfg == null || !cfg.enabled || raw == null || raw.isEmpty()) {
            return;
        }
        String plain = PlainText.strip(raw).trim();
        if (plain.isEmpty() || PLAYER_CHAT.matcher(plain).find()) {
            return;
        }
        String creature = FishingData.seaCreatureFor(plain);
        if (creature == null || !HotspotParser.parseCreatureList(cfg.rareCreatures)
                .contains(creature.toLowerCase(Locale.ROOT))) {
            return;
        }
        long now = System.currentTimeMillis();
        Long seen = rareSeenAt.get(creature);
        if (seen != null && now - seen < RARE_DEDUPE_MS) {
            return;
        }
        rareSeenAt.put(creature, now);
        onRareCreature(creature, cfg);
    }

    private void onRareCreature(String creature, SBSConfig.FishingShareSettings cfg) {
        int mask = (cfg.rareTitle ? AlertChannel.TITLE.bit() : 0)
                | (cfg.rareSound ? AlertChannel.SOUND.bit() : 0);
        if (mask != 0) {
            Alerts.send(new Alerts.Alert(creature, "Rare sea creature!", SbsAudio.Tone.ALARM, null), mask);
        }
        FishingShareMode mode = cfg.rareShareMode == null ? FishingShareMode.CLICK : cfg.rareShareMode;
        LocalPlayer player = Minecraft.getInstance().player;
        if (mode == FishingShareMode.OFF || player == null) {
            return;
        }
        String message = HotspotParser.shareMessage(creature,
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()) - 1,
                (int) Math.floor(player.getZ()), SkyBlockLocation.island());
        if (mode == FishingShareMode.AUTO) {
            send("party", message);
            return;
        }
        int id = park(message);
        SBSChat.send("FishingShare", Component.literal(" Share " + creature + ": ").withColor(SBSChat.WHITE)
                .append(button("[To PARTY chat]", 0x57D977, "party", id, message)));
    }

    // ------------------------------------------------------------------ sending

    private static MutableComponent button(String text, int color, String channel, int id, String message) {
        String command = "/" + COMMAND + " " + channel + " " + id;
        return Component.literal(text).withColor(color).withStyle(style -> style
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Sends: " + message))));
    }

    private int park(String message) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.createdAt() > PENDING_TTL_MS);
        while (pending.size() >= MAX_PENDING) {
            pending.remove(pending.keySet().iterator().next());
        }
        int id = nextId++;
        pending.put(id, new Pending(message, now));
        return id;
    }

    /** {@code /sbsfishshare <party|all> <id>}, run by a click. */
    public static void handleCommand(String args) {
        String[] parts = args == null ? new String[0] : args.trim().split("\\s+");
        if (parts.length != 2) {
            SBSChat.send("Usage: /" + COMMAND + " <party|all> <id> - used by the share buttons.");
            return;
        }
        String channel = parts[0].toLowerCase(Locale.ROOT);
        if (!channel.equals("party") && !channel.equals("all")) {
            SBSChat.send("Unknown channel: " + parts[0]);
            return;
        }
        int id;
        try {
            id = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            SBSChat.send("That share link is not valid.");
            return;
        }
        Pending p = INSTANCE.pending.get(id);
        if (p == null || System.currentTimeMillis() - p.createdAt() > PENDING_TTL_MS) {
            SBSChat.send("That share has expired.");
            return;
        }
        if (INSTANCE.send(channel, p.message())) {
            INSTANCE.pending.remove(id);
        }
    }

    private boolean send(String channel, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSendAt < MIN_SEND_GAP_MS) {
            SBSChat.send("Slow down - try again in a moment.");
            return false;
        }
        lastSendAt = now;
        String prefix = channel.equals("all") ? "ac " : "pc ";
        try {
            minecraft.player.connection.sendCommand(prefix + ChatTag.tag(message));
            return true;
        } catch (RuntimeException e) {
            // Best-effort and never retried: a retry loop is a bot however small each step is.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Fishing] share not sent: {}", e.toString());
            return false;
        }
    }

    /** World leave: hotspots and pending shares belong to the lobby they were seen in. */
    public void onWorldChange() {
        hotspotsOffered.clear();
        rareSeenAt.clear();
        pending.clear();
        tickCounter = 0;
    }
}
