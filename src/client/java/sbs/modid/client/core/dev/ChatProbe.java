/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A <b>capture-only</b> probe for chat: while armed it writes down every line the client receives,
 * exactly as it arrived, and changes nothing.
 *
 * <p><b>Why it exists.</b> {@link MenuProbe} does this for container menus and its argument applies
 * word for word to chat: every chat-driven feature in this mod is written against wording that
 * cannot be checked from outside the game, and a parser written against a guess is a parser that
 * silently reads nothing while looking like it works. The immediate customer is the honey lather
 * timer, whose entire detection rests on a message nobody here has seen - but the same file answers
 * "what does Hypixel actually say" for any feature, which is why this is a general tool and not a
 * honey one.
 *
 * <p><b>Every line is timestamped twice</b>: wall clock, and elapsed since the probe was armed. The
 * second one is the point. A capture session exists to measure intervals - lather to spawn, harvest
 * to respawn - and an elapsed column turns the file itself into the measurement instead of leaving
 * it to be reconstructed from clock times afterwards.
 *
 * <p><b>Both forms of every line are recorded</b>: the flattened text, which is what
 * {@code onChat(String)} consumers actually match against, and the full component JSON, which
 * carries the styling and any hover text the flattened form throws away. A wording question is
 * usually answered by the first and a "where is the number hiding" question only ever by the second.
 *
 * <p><b>What it does not do.</b> No parsing, no matching, no filtering by content - a filter is a
 * guess about what matters, and the whole reason this exists is that the guess is what we do not
 * have yet. It appends one file per armed session and stops itself after {@link #MAX_LINES}.
 */
public final class ChatProbe {

    private static final ChatProbe INSTANCE = new ChatProbe();

    /**
     * Lines after which an armed probe stops itself, announcing it. A probe left armed overnight in
     * a busy lobby must not be able to fill a disk, and a capture that long is not being read anyway.
     */
    private static final int MAX_LINES = 20_000;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    /** Read on every chat line, so it is the one field the disarmed cost is measured in. */
    private volatile boolean armed;

    /** The session file, or {@code null} while disarmed. */
    private Path file;

    private long armedAt;
    private int lines;

    private ChatProbe() {
    }

    public static ChatProbe getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Command surface
    // ------------------------------------------------------------------

    /** {@code /sbs chatprobe [arm|off|status]} - the bare form reports status rather than guessing. */
    public void handleCommand(String argument) {
        if (!DevMode.ACTIVE) { // DEV-ONLY: defence in depth behind the command gate
            return;
        }
        String arg = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        switch (arg) {
            case "arm", "on", "watch" -> arm();
            case "off", "stop", "disarm" -> disarm(true);
            default -> status();
        }
    }

    private synchronized void arm() {
        if (armed) {
            say("§7Chat probe is already armed §8-> §f" + file);
            return;
        }
        Path path = freeFile();
        try {
            SBSFiles.ensureParent(path);
            Files.writeString(path, header(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            say("§cChat probe could not create its file - see the log.");
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][ChatProbe] Could not create {}", path, e);
            return;
        }
        file = path;
        armedAt = System.currentTimeMillis();
        lines = 0;
        armed = true;
        say("§aChat probe armed §8-> §f" + path);
        say("§7Every line is recorded verbatim, with the time since arming. "
                + "Run §f/sbs chatprobe off§7 when done.");
    }

    private synchronized void disarm(boolean announce) {
        if (!armed) {
            if (announce) {
                say("§7Chat probe is not armed.");
            }
            return;
        }
        armed = false;
        append("-- disarmed after " + lines + " line(s) --");
        if (announce) {
            say("§7Chat probe disarmed after §f" + lines + "§7 line(s) §8-> §f" + file);
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][ChatProbe] Disarmed after {} line(s) -> {}",
                lines, file);
        file = null;
    }

    private void status() {
        if (armed) {
            say("§aChat probe is armed §7(" + lines + " line(s)) §8-> §f" + file);
        } else {
            say("§7Chat probe is off. §f/sbs chatprobe arm§7 starts a capture.");
        }
    }

    // ------------------------------------------------------------------
    // Intake
    // ------------------------------------------------------------------

    /**
     * Every chat line, armed or not. Costs one volatile boolean read while disarmed, which is what
     * lets this sit in the chat funnel beside the real consumers without being a cost.
     *
     * @param text    the flattened line, exactly as the {@code onChat(String)} consumers receive it
     * @param message the full component, for the styling and hover text {@code text} discards
     */
    public void onChat(String text, Component message) {
        // The Server Scanner's chat channel shares this hook; one boolean read while dev mode is off.
        sbs.modid.client.core.dev.scanner.ServerScanner.onChat(text, message);
        if (!armed) {
            return;
        }
        record(text, message);
    }

    private synchronized void record(String text, Component message) {
        if (!armed) {
            return;   // disarmed between the check and the lock
        }
        if (lines >= MAX_LINES) {
            say("§eChat probe stopped itself after " + MAX_LINES + " lines.");
            disarm(false);
            return;
        }
        lines++;
        long elapsed = System.currentTimeMillis() - armedAt;
        StringBuilder out = new StringBuilder(256);
        out.append('[').append(LocalDateTime.now().format(CLOCK)).append("]  +")
                .append(elapsed(elapsed)).append('\n');
        out.append("  text: ").append(text == null ? "<null>" : text).append('\n');
        out.append("  json: ").append(json(message)).append('\n');
        append(out.toString());
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Appends per line rather than buffering: a capture session that crashes must keep what it saw. */
    private void append(String text) {
        Path path = file;
        if (path == null) {
            return;
        }
        try {
            Files.writeString(path, text + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Losing the probe is not losing the session - disarm rather than log once per line.
            armed = false;
            file = null;
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][ChatProbe] Write failed, probe disarmed", e);
        }
    }

    private String header() {
        return """
                SBS chat probe
                ==============
                Every chat line this client received while the probe was armed, verbatim.
                Nothing here is parsed, filtered or interpreted - that is the point.

                  [wall clock]  +elapsed since arming
                    text: the flattened line, as the onChat(String) consumers see it
                    json: the full component, carrying styling and hover text

                Armed at %s
                """.formatted(LocalDateTime.now());
    }

    /** {@code +00:14:58.123} - readable at the scale a spawn interval is measured on. */
    private static String elapsed(long millis) {
        long total = Math.max(0L, millis);
        long hours = total / 3_600_000L;
        long minutes = total / 60_000L % 60L;
        long seconds = total / 1_000L % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, total % 1000L);
    }

    /**
     * The component as JSON, or a note saying why not. Never throws: a capture is a diagnostic and
     * must not be the thing that breaks a chat line from being displayed.
     */
    private static String json(Component message) {
        if (message == null) {
            return "<null>";
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return "<no level - registry access unavailable>";
        }
        try {
            RegistryOps<JsonElement> ops =
                    RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
            return ComponentSerialization.CODEC.encodeStart(ops, message)
                    .result().map(JsonElement::toString).orElse("<not encodable>");
        } catch (Throwable t) {
            return "<encode failed: " + t.getClass().getSimpleName() + ">";
        }
    }

    /** A fresh session file; the stamp resolves to the second, so a second arm cannot overwrite. */
    private static Path freeFile() {
        String stamp = LocalDateTime.now().format(STAMP);
        Path path = SBSFiles.chatProbeFile(stamp);
        for (int i = 2; Files.exists(path) && i < 100; i++) {
            path = SBSFiles.chatProbeFile(stamp + "-" + i);
        }
        return path;
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
