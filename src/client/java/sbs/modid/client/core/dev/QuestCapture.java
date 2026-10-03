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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Dev-mode recorder for questline data: NPC dialogue, quest chat, and the places you mark by hand.
 *
 * <p><b>What it is for.</b> A questline guide is mostly coordinates, and coordinates can only come
 * from standing on them. Community wikis have most of the Crimson Isle Mage branch and almost none of
 * the Barbarian branch, so that half has to be walked. Notes taken by hand during a playthrough
 * arrive incomplete and in the wrong shape; this produces one file that is already the schema the
 * dataset needs.
 *
 * <p><b>It only ever reads.</b> No input is synthesised, nothing is clicked, no command is sent, and
 * nothing is written outside the capture file. That is the mod's standing rule and it is not relaxed
 * for dev tooling.
 *
 * <p><b>Flat event list, appended as you play.</b> A session that ends early is still usable and two
 * sessions merge by concatenating their {@code events}. Dialogue and quest chat record themselves;
 * the three interesting captures - the objective you are looking at, an open menu, and the raw
 * scoreboard plus tab list - are driven by keys, because only a person knows which NPC is the
 * current objective.
 *
 * <p>Written on {@code stop}, and also every {@value #AUTOSAVE_EVERY} events, so a crash mid-run
 * costs the last few lines rather than the whole session.
 */
public final class QuestCapture {

    private static final QuestCapture INSTANCE = new QuestCapture();

    /** Schema version of the produced file. Bump if the shape changes. */
    private static final int SCHEMA_VERSION = 1;

    /** Events between automatic writes. A crash should not cost a whole playthrough. */
    private static final int AUTOSAVE_EVERY = 25;

    /** Hypixel prefixes NPC dialogue with this. */
    private static final String NPC_PREFIX = "[NPC]";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withLocale(Locale.ROOT);

    private boolean recording;
    private long startedAt;
    private Path file;
    private JsonArray events = new JsonArray();
    private int sinceWrite;
    private String faction = "";
    private String note = "";

    private QuestCapture() {
    }

    public static QuestCapture getInstance() {
        return INSTANCE;
    }

    public boolean recording() {
        return recording;
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    /** {@code /sbs questcapture [start|stop|status|note <text>|faction <name>]}. */
    public void handleCommand(String argument) {
        String[] parts = argument == null || argument.isBlank()
                ? new String[0] : argument.trim().split("\\s+", 2);
        String action = parts.length == 0 ? "status" : parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";

        switch (action) {
            case "start", "on" -> start();
            case "stop", "off" -> stop();
            case "note" -> {
                if (rest.isEmpty()) {
                    say("§7Usage: §f/sbs questcapture note <text>");
                    return;
                }
                note = note.isEmpty() ? rest : note + " | " + rest;
                record("note", event -> event.addProperty("text", rest));
                say("§aNoted.");
            }
            case "faction" -> {
                faction = rest.toLowerCase(Locale.ROOT);
                say("§aFaction recorded as §f" + (faction.isEmpty() ? "(none)" : faction));
            }
            default -> status();
        }
    }

    private void start() {
        if (recording) {
            say("§eAlready recording §8-> §f" + file);
            return;
        }
        recording = true;
        startedAt = System.currentTimeMillis();
        events = new JsonArray();
        sinceWrite = 0;
        note = "";
        file = SBSFiles.questCaptureDir().resolve(
                "questcapture-" + STAMP.format(ZonedDateTime.now()) + ".json");
        say("§aQuest capture started §8-> §f" + file);
        say("§7Dialogue and quest chat record themselves. Use the three dev keys for "
                + "§fobjective§7, §fmenu§7 and §fstate§7.");
        // A first state capture costs nothing and answers "where did this session begin".
        captureState();
    }

    private void stop() {
        if (!recording) {
            say("§7Not recording.");
            return;
        }
        recording = false;
        if (write()) {
            say("§aQuest capture written §8-> §f" + file);
        }
    }

    private void status() {
        if (!recording) {
            say("§7Quest capture is §foff§7. §f/sbs questcapture start§7 to begin.");
            return;
        }
        say("§aRecording§7: " + events.size() + " event(s), faction "
                + (faction.isEmpty() ? "§8unset" : "§f" + faction) + " §8-> §f" + file);
    }

    // ------------------------------------------------------------------
    // Automatic: chat
    // ------------------------------------------------------------------

    /**
     * Every incoming chat line, from the shared listener.
     *
     * <p>Two kinds are kept. An {@code [NPC]} line is dialogue and carries the speaker; anything that
     * looks like quest bookkeeping - a reward, a reputation change, an item hand-in - is kept as a
     * {@code quest} event with a guessed {@code kind}. <b>The guess is recorded, not trusted</b>: the
     * raw text goes in beside it so a wrong classification can be corrected from the file rather than
     * costing another playthrough.
     */
    public void onChat(String stripped, Component raw) {
        if (!recording || stripped == null || stripped.isBlank()) {
            return;
        }
        String trimmed = stripped.trim();
        String rawText = raw == null ? trimmed : raw.getString();

        if (trimmed.startsWith(NPC_PREFIX)) {
            String body = trimmed.substring(NPC_PREFIX.length()).trim();
            int colon = body.indexOf(':');
            String npc = colon > 0 ? body.substring(0, colon).trim() : "";
            String line = colon > 0 ? body.substring(colon + 1).trim() : body;
            Entity speaker = nearestNamed(npc);
            record("dialogue", event -> {
                event.addProperty("npc", npc);
                event.addProperty("line", line);
                event.addProperty("stripped", trimmed);
                event.addProperty("raw", rawText);
                // Best guess only. The Abiphone question is explicitly unanswered - see the spec -
                // so this flags what to look at rather than claiming to know.
                event.addProperty("viaAbiphone", looksLikeCall(trimmed));
                if (speaker != null) {
                    event.add("npcPos", vec(speaker.position()));
                }
            });
            return;
        }

        String kind = questKind(trimmed);
        if (kind != null) {
            record("quest", event -> {
                event.addProperty("kind", kind);
                event.addProperty("stripped", trimmed);
                event.addProperty("raw", rawText);
            });
        }
    }

    /** Which quest-bookkeeping line this looks like, or {@code null} when it is ordinary chat. */
    private static String questKind(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("reputation")) {
            return "reputation";
        }
        if (lower.contains("quest complete") || lower.contains("objective complete")) {
            return "completion";
        }
        if (lower.contains("reward") || lower.startsWith("+")) {
            return "reward";
        }
        if (lower.contains("abiphone") || lower.contains("incoming call")
                || lower.contains("is calling")) {
            return "call";
        }
        return null;
    }

    private static boolean looksLikeCall(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("abiphone") || lower.contains("call");
    }

    /** The loaded entity whose name matches an NPC, so dialogue can carry where it was spoken. */
    private static Entity nearestNamed(String npc) {
        Minecraft minecraft = Minecraft.getInstance();
        if (npc == null || npc.isBlank() || minecraft.level == null || minecraft.player == null) {
            return null;
        }
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            Component name = entity.getCustomName();
            if (name == null || !name.getString().toLowerCase(Locale.ROOT)
                    .contains(npc.toLowerCase(Locale.ROOT))) {
                continue;
            }
            double distance = entity.distanceToSqr(minecraft.player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Key-driven captures
    // ------------------------------------------------------------------

    /**
     * "The thing I am looking at is the current objective."
     *
     * <p>Opens the name prompt first, because a coordinate without a label is a coordinate nobody can
     * place three days later. The crosshair target is resolved at the moment of the press, not at
     * submit, so turning to type does not move it.
     */
    public void markObjective() {
        if (!requireRecording()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        HitResult hit = minecraft.hitResult;
        Vec3 target = null;
        String targetKind = "area";
        String lookingAt = "";

        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            target = entity.position();
            targetKind = "entity";
            Component name = entity.getCustomName();
            lookingAt = name != null ? name.getString() : entity.getName().getString();
        } else if (hit instanceof BlockHitResult blockHit) {
            target = Vec3.atCenterOf(blockHit.getBlockPos());
            targetKind = "block";
            if (minecraft.level != null) {
                lookingAt = minecraft.level.getBlockState(blockHit.getBlockPos())
                        .getBlock().getDescriptionId();
            }
        } else if (minecraft.player != null) {
            // Nothing under the crosshair: the player's own feet are the honest answer, marked as an
            // area so nobody later reads it as a precise NPC position.
            target = minecraft.player.position();
        }

        Vec3 resolved = target;
        String kind = targetKind;
        String seen = lookingAt;
        minecraft.setScreenAndShow(new NameInputScreen(
                Component.literal("Objective label"),
                "e.g. Chief Scorn, Dojo entrance, Gris (basement)",
                label -> record("objective", event -> {
                    event.addProperty("label", label == null ? "" : label.trim());
                    event.addProperty("targetKind", kind);
                    event.addProperty("lookingAt", seen);
                    if (resolved != null) {
                        event.add("target", vec(resolved));
                    }
                })));
    }

    /** The open menu, slot by slot - for the Quest Log, the Town Board and the Dojo. */
    public void captureMenu() {
        if (!requireRecording()) {
            return;
        }
        // 26.2 exposes no public `screen` field; GuiStateManager is what tracks it for the mod.
        if (!(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()
                instanceof AbstractContainerScreen<?> container)) {
            say("§eNo menu open §7- open one first.");
            return;
        }
        String title = container.getTitle().getString();
        JsonArray slots = new JsonArray();
        var menu = container.getMenu();
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("name", stack.getHoverName().getString());
            JsonArray lore = new JsonArray();
            for (Component line : loreOf(stack)) {
                lore.add(line.getString());
            }
            slot.add("lore", lore);
            slots.add(slot);
        }
        record("menu", event -> {
            event.addProperty("title", title);
            event.add("slots", slots);
        });
        say("§aCaptured menu §f" + title + " §7(" + slots.size() + " slots)");
    }

    /**
     * The raw scoreboard and tab list.
     *
     * <p>This is the one that answers whether the chosen faction is readable at all. Press it once
     * after choosing a faction and again after the first reputation gain: if the faction appears
     * anywhere client-visible, it is in the difference between those two captures.
     */
    public void captureState() {
        if (!requireRecording()) {
            return;
        }
        JsonArray sidebar = new JsonArray();
        for (String line : SkyBlockLocation.sidebarLines()) {
            sidebar.add(line);
        }
        JsonArray tab = new JsonArray();
        for (String line : TabWidgets.lines()) {
            tab.add(line);
        }
        record("state", event -> {
            event.add("scoreboard", sidebar);
            event.add("tabWidgets", tab);
        });
        say("§aCaptured state §7(" + sidebar.size() + " scoreboard, " + tab.size() + " tab lines)");
    }

    private static List<Component> loreOf(ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        return lore == null ? List.of() : lore.lines();
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private interface Fill {
        void into(JsonObject event);
    }

    /** Adds one event with the location fields every event carries. */
    private void record(String type, Fill fill) {
        if (!recording) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("t", System.currentTimeMillis() - startedAt);
        event.addProperty("type", type);
        event.addProperty("island", SkyBlockLocation.island());
        event.addProperty("zone", SkyBlockLocation.zone());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            JsonObject player = vec(minecraft.player.position());
            player.addProperty("yaw", round(minecraft.player.getYRot()));
            player.addProperty("pitch", round(minecraft.player.getXRot()));
            event.add("player", player);
        }
        fill.into(event);
        events.add(event);

        if (++sinceWrite >= AUTOSAVE_EVERY) {
            sinceWrite = 0;
            write();
        }
    }

    private static JsonObject vec(Vec3 position) {
        JsonObject json = new JsonObject();
        json.addProperty("x", round(position.x));
        json.addProperty("y", round(position.y));
        json.addProperty("z", round(position.z));
        return json;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private boolean write() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", SCHEMA_VERSION);
            root.addProperty("capturedAt", Instant.ofEpochMilli(startedAt).toString());
            root.addProperty("modVersion", sbs.modid.client.ui.wizard.ModVersionSource.current()
                    .map(Object::toString).orElse("unparseable"));
            root.addProperty("profile", ProfileContext.getInstance().profile());
            root.addProperty("faction", faction);
            root.addProperty("notes", note);
            root.add("events", events);

            SBSFiles.ensureParent(file);
            Files.writeString(file, SBSFiles.GSON.toJson(root));
            return true;
        } catch (Exception e) {
            say("§cCould not write the capture - see the log.");
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][QuestCapture] Writing {} failed", file, e);
            return false;
        }
    }

    private boolean requireRecording() {
        if (!recording) {
            say("§eNot recording §7- §f/sbs questcapture start§7 first.");
            return false;
        }
        return true;
    }

    private static void say(String text) {
        SBSChat.send(Component.literal("§8[§bCapture§8] §r" + text));
    }
}
