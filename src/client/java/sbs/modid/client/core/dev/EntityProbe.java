/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A <b>capture-only</b> probe for nearby entities and the nametags above them, written for the
 * Torrhus Canyon critter overlays.
 *
 * <p><b>Why it exists.</b> Every critter feature turns on the same question: does Hypixel put the
 * capture state somewhere a client can read? A Blue Jay's startled warning, a Drybark's three stamina
 * bars, a Dustybit's remaining jumps - each is either a nametag (readable today, the mob highlighter
 * already parses them), a boss bar, or a purely visual effect that is not readable at all. Nothing
 * outside the game can tell which, and an overlay written against the wrong assumption is an overlay
 * written twice.
 *
 * <p><b>Two modes, because the questions are two shapes.</b> A <b>snapshot</b> answers "what is
 * standing here and what does it say" - the right tool for cataloguing a critter or finding out
 * whether stamina appears in a name at all. <b>Armed</b> mode instead records only <i>changes</i> to
 * those names over time, which is the shape of every timed question: when the Blue Jay's warning
 * appears and how long it lasts, whether a stamina bar ticks down in the nametag, whether a jump
 * count is written anywhere.
 *
 * <p>Names are recorded twice on purpose - once with the § codes stripped for reading, once raw. The
 * colours are frequently the state ("§c" for a warning, "§a" for calm), so a capture that only kept
 * the plain text would throw away the very signal the feature needs.
 *
 * <p><b>What it does not do.</b> Nothing is highlighted, nothing is targeted, no entity is touched.
 * Armed mode walks the entity list on a throttle, never per frame.
 */
public final class EntityProbe {

    private static final EntityProbe INSTANCE = new EntityProbe();

    /** Default radius for both modes. Generous enough to cover a critter across a clearing. */
    private static final double RADIUS = 24.0;

    /** Armed mode re-walks the entity list this often. Nametags do not change 20 times a second. */
    private static final long SCAN_INTERVAL_MS = 100L;

    private static final int MAX_RECORDS = 20_000;
    private static final long MAX_DURATION_MS = 15 * 60 * 1000L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private boolean armed;
    private long armedAt;
    private long lastScanAt;
    private long scans;
    private boolean truncated;

    /** One line per observed change, in order. */
    private final List<String> records = new ArrayList<>();

    /** Entity id -> the last name seen on it, so only changes are recorded. */
    private final Map<Integer, String> lastNames = new HashMap<>();

    private EntityProbe() {
    }

    public static EntityProbe getInstance() {
        return INSTANCE;
    }

    /** {@code /sbs entityprobe [arm|off|status]}; the bare form takes a snapshot. */
    public void handleCommand(String argument) {
        if (!DevMode.ACTIVE) { // DEV-ONLY: defence in depth behind the command gate
            return;
        }
        switch (argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT)) {
            case "arm", "on", "watch" -> arm();
            case "off", "stop", "disarm" -> disarm();
            case "status" -> status();
            default -> snapshot();
        }
    }

    // ------------------------------------------------------------------
    // Snapshot: what is standing here right now
    // ------------------------------------------------------------------

    private void snapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            say("§7Not in a world.");
            return;
        }
        List<Entity> nearby = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity != player && entity.distanceTo(player) <= RADIUS) {
                nearby.add(entity);
            }
        }
        nearby.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));

        StringBuilder out = new StringBuilder(1 << 14);
        header(out, "entity snapshot");
        out.append(nearby.size()).append(" entities within ").append((int) RADIUS).append(" blocks\n");
        out.append("""

                Reading this file:
                  name / raw    -> the nametag, stripped and with its colour codes. The colours are
                                   often the state itself, so both are kept.
                  stand=true    -> an armor stand, i.e. Hypixel's usual floating-nametag carrier
                                   rather than the creature.
                  health        -> only meaningful when the server actually syncs it; many SkyBlock
                                   mobs carry their real health in the nametag instead.
                """);
        out.append("\n--- entities ---\n");
        for (Entity entity : nearby) {
            out.append(describe(entity, player)).append('\n');
        }
        write(out.toString(), "entities");
        say("§aWrote a snapshot of §f" + nearby.size() + "§a entit(ies).");
    }

    /** One entity on one line: distance, type, id, position, stand/invisible flags, name (plain and raw). */
    public static String describe(Entity entity, Player player) {
        StringBuilder line = new StringBuilder(200);
        line.append(String.format(Locale.ROOT, "dist=%5.1f", entity.distanceTo(player)))
                .append(" type=").append(typeId(entity))
                .append(" id=").append(entity.getId())
                .append(String.format(Locale.ROOT, " pos=%.2f,%.2f,%.2f",
                        entity.getX(), entity.getY(), entity.getZ()));
        if (entity instanceof ArmorStand) {
            line.append(" stand=true");
        }
        if (entity.isInvisible()) {
            line.append(" invisible=true");
        }
        Component custom = entity.getCustomName();
        if (custom != null) {
            String raw = custom.getString();
            line.append(" name=\"").append(PlainText.strip(raw)).append('"')
                    .append(" raw=\"").append(raw.replace('§', '&')).append('"');
        }
        if (entity instanceof LivingEntity living) {
            line.append(String.format(Locale.ROOT, " health=%.1f/%.1f",
                    living.getHealth(), living.getMaxHealth()));
        }
        if (!entity.getPassengers().isEmpty()) {
            line.append(" passengers=").append(entity.getPassengers().size());
        }
        return line.toString();
    }

    // ------------------------------------------------------------------
    // Armed: what changes, and when
    // ------------------------------------------------------------------

    private void arm() {
        records.clear();
        lastNames.clear();
        scans = 0;
        truncated = false;
        armedAt = System.currentTimeMillis();
        lastScanAt = 0;
        armed = true;
        say("§aEntity probe armed §7- every nametag CHANGE within " + (int) RADIUS
                + " blocks is recorded until §f/sbs entityprobe off§7.");
        say("§7Approach the critter, let it react, then disarm.");
    }

    private void disarm() {
        if (!armed && records.isEmpty()) {
            say("§7The entity probe was not armed.");
            return;
        }
        armed = false;
        if (records.isEmpty()) {
            say("§7Disarmed. No nametag changed while armed, so nothing was written.");
            return;
        }
        StringBuilder out = new StringBuilder(1 << 16);
        header(out, "entity nametag changes");
        out.append("armed for ").append((System.currentTimeMillis() - armedAt) / 1000).append("s, ")
                .append(scans).append(" scan(s), ").append(records.size()).append(" change(s)\n");
        if (truncated) {
            out.append("DETAIL TRUNCATED at ").append(MAX_RECORDS).append(" records\n");
        }
        out.append("""

                Reading this file:
                  Each line is a nametag that CHANGED, with the milliseconds since arming. Two lines
                  for the same entity id are how long that state lasted - which is where a timer such
                  as the Blue Jay's comes from. "gone" means the entity left range or despawned.
                """);
        out.append("\n--- changes ---\n");
        records.forEach(line -> out.append(line).append('\n'));
        write(out.toString(), "entity-changes");
        say("§aWrote §f" + records.size() + "§a nametag change(s).");
    }

    private void status() {
        if (!armed) {
            say("§7Entity probe is off. §f/sbs entityprobe§7 snapshots, §f/sbs entityprobe arm§7 watches.");
            return;
        }
        say("§aArmed §7for §f" + ((System.currentTimeMillis() - armedAt) / 1000) + "s§7, §f"
                + records.size() + "§7 change(s) over §f" + scans + "§7 scan(s).");
    }

    /** Ticked from the client tick; does nothing at all unless armed. */
    public void tick(Minecraft minecraft) {
        if (!armed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - armedAt > MAX_DURATION_MS) {
            say("§7Entity probe stopped itself after " + (MAX_DURATION_MS / 60_000) + " minutes.");
            disarm();
            return;
        }
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        scans++;

        Map<Integer, String> seen = new HashMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == player || entity.distanceTo(player) > RADIUS) {
                continue;
            }
            Component custom = entity.getCustomName();
            if (custom == null) {
                continue;
            }
            String raw = custom.getString();
            seen.put(entity.getId(), raw);
            String previous = lastNames.get(entity.getId());
            if (!raw.equals(previous)) {
                record(now, entity, raw, previous == null ? "new" : "changed");
            }
        }
        // Anything that had a name last scan and does not now has left, which is itself a state
        // change worth timing - a critter that flees is exactly what the Blue Jay overlay is about.
        lastNames.keySet().removeIf(id -> {
            if (!seen.containsKey(id)) {
                if (records.size() < MAX_RECORDS) {
                    records.add("t=" + (now - armedAt) + " id=" + id + " gone");
                }
                return true;
            }
            return false;
        });
        lastNames.putAll(seen);
    }

    private void record(long now, Entity entity, String raw, String kind) {
        if (records.size() >= MAX_RECORDS) {
            truncated = true;
            return;
        }
        Player player = Minecraft.getInstance().player;
        StringBuilder line = new StringBuilder(200);
        line.append("t=").append(now - armedAt)
                .append(' ').append(kind)
                .append(" id=").append(entity.getId())
                .append(" type=").append(typeId(entity))
                .append(" name=\"").append(PlainText.strip(raw)).append('"')
                .append(" raw=\"").append(raw.replace('§', '&')).append('"')
                .append(String.format(Locale.ROOT, " pos=%.2f,%.2f,%.2f",
                        entity.getX(), entity.getY(), entity.getZ()));
        if (player != null) {
            // Player movement is half of the Blue Jay question ("stand still for 8 seconds"), so it
            // rides along with every change rather than needing a second capture to correlate.
            line.append(String.format(Locale.ROOT, " dist=%.2f playerMoved=%.3f",
                    entity.distanceTo(player),
                    Math.sqrt(sq(player.getX() - player.xOld) + sq(player.getY() - player.yOld)
                            + sq(player.getZ() - player.zOld))));
        }
        records.add(line.toString());
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private static String typeId(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null ? key.toString() : entity.getType().toString();
    }

    private void header(StringBuilder out, String what) {
        out.append("SkyBlock Simplified - ").append(what).append('\n');
        out.append("written ").append(LocalDateTime.now()).append('\n');
        out.append("location: ")
                .append(sbs.modid.client.core.location.SkyBlockLocation.describe()).append('\n');
    }

    private void write(String text, String prefix) {
        try {
            Path file = freeFile(prefix);
            SBSFiles.ensureParent(file);
            Files.writeString(file, text);
            say("§7" + file);
        } catch (IOException | RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Probe] Writing the entity capture failed", e);
            say("§cWriting the capture failed - see the log.");
        }
    }

    private static Path freeFile(String prefix) {
        String name = prefix + "-" + LocalDateTime.now().format(STAMP);
        Path file = SBSFiles.probeFile(name);
        for (int i = 2; Files.exists(file) && i < 100; i++) {
            file = SBSFiles.probeFile(name + "-" + i);
        }
        return file;
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
