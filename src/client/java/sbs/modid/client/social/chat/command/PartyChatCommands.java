/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.command;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;
import sbs.modid.client.social.party.logic.PartyTracker;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Party Commands module (Party &amp; Chat): reacts to {@code !commands} OTHER party members type
 * into the Hypixel party chat, following the common {@code !command} convention.
 *
 * <p>Two kinds of commands:
 * <ul>
 *   <li><b>Actions</b> run a real command for you ({@code !warp} → {@code /party warp}). If you are
 *       not the leader Hypixel simply rejects it, so no permission check is needed on our side.</li>
 *   <li><b>Replies</b> answer back into the party chat via {@code /pc} ({@code !coords},
 *       {@code !ping}, {@code !8ball}, ...).</li>
 * </ul>
 *
 * <p>Fed from the chat listener mixin with every displayed line. Only lines matching Hypixel's party
 * chat format ("Party > [RANK] Name: message") trigger; the player's own messages never do (you can
 * just run the command yourself). A 2-second cooldown stops chat spam from firing commands
 * repeatedly, and every command group has its own toggle in the module settings.
 *
 * <p>{@link #onClientTick()} drains the small delayed-command queue (used by {@code !reinvite},
 * which has to kick before it can invite again).
 */
public final class PartyChatCommands {

    private static final PartyChatCommands INSTANCE = new PartyChatCommands();

    /** "Party > [MVP+] Name: message" (rank optional). */
    private static final Pattern PARTY_LINE =
            Pattern.compile("^Party > (?:\\[[^]]+] )?([A-Za-z0-9_]{1,16}): (.+)$");

    /** Legacy formatting codes ({@code §} + any char, which also covers Hypixel's {@code §x} hex runs). */
    private static final Pattern FORMATTING = Pattern.compile("(?i)§.");

    /** "!f3", "!m7", "!t5" – the dungeon / Kuudra queue shortcuts. */
    private static final Pattern QUEUE = Pattern.compile("^([fmt])([0-7])$");

    private static final String[] FLOOR_NAMES = {
            "ENTRANCE", "FLOOR_ONE", "FLOOR_TWO", "FLOOR_THREE",
            "FLOOR_FOUR", "FLOOR_FIVE", "FLOOR_SIX", "FLOOR_SEVEN"};

    /** Kuudra tiers 1-5 as Hypixel names them; index 0 is unused. */
    private static final String[] KUUDRA_TIERS = {
            "", "NORMAL", "HOT", "BURNING", "FIERY", "INFERNAL"};

    private static final String[] EIGHT_BALL = {
            "It is certain", "Without a doubt", "You may rely on it", "Yes, definitely",
            "Most likely", "Outlook good", "Signs point to yes", "Reply hazy, try again",
            "Ask again later", "Better not tell you now", "Don't count on it", "My reply is no",
            "My sources say no", "Outlook not so good", "Very doubtful"};

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** How long ONE party member must wait between commands. */
    private static final long COOLDOWN_MS = 2000;

    /**
     * Minimum spacing between any two commands, whoever sent them. The per-sender cooldown keeps
     * one member from using every slot; this floor keeps the total outgoing rate below what the
     * server accepts from one client.
     */
    private static final long GLOBAL_FLOOR_MS = 500;

    /**
     * Hard cap on anything this module sends: Minecraft's chat packet allows 256 characters and
     * the server disconnects a client that exceeds it. Nothing here produces a longer line.
     */
    private static final int MAX_COMMAND_LENGTH = 256;

    /** A party member's name as Minecraft allows it - the only thing accepted as a command target. */
    private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** {@code !reinvite} has to kick first, so the invite is sent a few seconds later. */
    private static final long REINVITE_DELAY_MS = 5000;

    private long lastRun;

    /** Last accepted command per sender (lower-case IGN), so one spammer cannot starve the rest. */
    private final Map<String, Long> lastRunBySender = new LinkedHashMap<>();

    /** A single queued command and the moment it may run (0 = nothing queued). */
    private String delayedCommand;
    private long delayedAt;

    /** {@code !downtime} notes, keyed by lower-case IGN, announced when the run ends. */
    private final Map<String, String> downtime = new LinkedHashMap<>();

    private PartyChatCommands() {
    }

    public static PartyChatCommands getInstance() {
        return INSTANCE;
    }

    /**
     * Called with every displayed chat line, exactly as the chat component holds it – Hypixel leaves
     * legacy {@code §} colour codes in the literal text, so they are dropped here before anything is
     * matched. (Nothing upstream strips them; every other chat parser in the mod does its own.)
     */
    public void parseChat(String rawText) {
        SBSConfig.PartyCommandsSettings cfg = ConfigManager.getInstance().get().partyCommands;
        if (!cfg.enabled || rawText == null) {
            return;
        }
        String text = FORMATTING.matcher(rawText).replaceAll("").trim();
        if (!text.startsWith("Party > ")) {
            announceDowntimeOnRunEnd(text);
            return;
        }
        Matcher matcher = PARTY_LINE.matcher(text);
        if (!matcher.matches()) {
            return;
        }
        String sender = matcher.group(1);
        String message = matcher.group(2).trim();
        if (!message.startsWith("!")) {
            return;
        }
        // Your own !commands count too (switchable): the leader can use them, and the module can
        // be tried without a second player.
        if (!cfg.reactToOwn && sender.equalsIgnoreCase(selfName())) {
            return;
        }
        String[] words = message.split("\\s+");
        String name = words[0].substring(1).toLowerCase(Locale.ROOT);
        String argument = words.length > 1 ? words[1] : null;
        String rest = message.substring(words[0].length()).trim();

        long now = System.currentTimeMillis();
        // A cooldown per sender, so each member gets a fair share, plus the global floor below
        // that bounds the combined rate.
        String senderKey = sender.toLowerCase(Locale.ROOT);
        Long senderLast = lastRunBySender.get(senderKey);
        if (senderLast != null && now - senderLast < COOLDOWN_MS) {
            return;
        }
        if (now - lastRun < GLOBAL_FLOOR_MS) {
            return;
        }
        if (!handle(cfg, name, argument, rest, sender)) {
            // Logged so an unresponsive command can be told apart from an unparsed chat line: if this
            // shows up, the line WAS read and the command is just unknown or its group switched off.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PartyCmd] ignored '{}' from {} (unknown or disabled)",
                    message, sender);
            return;
        }
        lastRun = now;
        lastRunBySender.put(senderKey, now);
        if (lastRunBySender.size() > 64) {
            // The map is keyed by whoever talks in party chat, so it must not grow without bound over
            // a long session; the oldest entry is the least likely to be mid-cooldown.
            var oldest = lastRunBySender.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][PartyCmd] {} triggered '{}'", sender, message);
    }

    /**
     * Runs the command {@code name} stands for. Returns {@code false} when nothing matched or the
     * matching group is switched off, so the cooldown is only spent on commands that actually fired.
     */
    private boolean handle(SBSConfig.PartyCommandsSettings cfg, String name, String argument,
                           String rest, String sender) {
        Matcher queue = QUEUE.matcher(name);
        if (queue.matches()) {
            return cfg.queue && joinInstance(queue.group(1), Integer.parseInt(queue.group(2)));
        }
        return switch (name) {
            // ---------------------------------------------------------------- party actions
            case "warp", "w" -> cfg.warp && run("/party warp");
            case "allinv", "allinvite" -> cfg.allinv && run("/party settings allinvite");
            case "transfer", "ptme", "pt" -> cfg.transfer && runOn("/party transfer ", target(argument, sender));
            case "promote" -> cfg.promote && runOn("/party promote ", target(argument, sender));
            case "demote" -> cfg.promote && runOn("/party demote ", target(argument, sender));
            case "kick", "k" -> cfg.kick && runOn("/party kick ", target(argument, sender));
            case "kickoffline", "ko" -> cfg.kick && run("/party kickoffline");
            case "invite", "inv" -> cfg.invite && runOn("/party invite ", target(argument, sender));
            case "reinv", "reinvite" -> cfg.invite && reinvite(target(argument, sender));

            // ---------------------------------------------------------------- info replies
            case "coords", "co" -> cfg.info && reply(coords());
            case "location", "loc" -> cfg.info && reply("Location: "
                    + sbs.modid.client.core.location.SkyBlockLocation.describe());
            case "ping" -> cfg.info && replyPing();
            case "tps" -> cfg.info && reply(tps());
            case "fps" -> cfg.info && reply("FPS: " + Math.max(0, Minecraft.getInstance().getFps()));
            case "holding" -> cfg.info && reply("Holding: " + holding());
            case "time" -> cfg.info && reply("Time: " + LocalTime.now().format(CLOCK));

            // ---------------------------------------------------------------- fun
            case "cf", "coin", "coinflip" ->
                    cfg.fun && reply("Coinflip: " + (random(2) == 0 ? "Heads" : "Tails"));
            case "8ball" -> cfg.fun && reply(EIGHT_BALL[random(EIGHT_BALL.length)]);
            case "dice", "roll" -> cfg.fun && reply("Dice: " + (random(6) + 1));

            // ---------------------------------------------------------------- misc
            case "boop" -> cfg.boop && runOn("/boop ", target(argument, sender));
            case "downtime", "dt" -> cfg.downtime && addDowntime(sender, rest);
            case "undowntime", "undt" -> cfg.downtime && removeDowntime(sender);
            case "help", "h" -> cfg.help && reply(helpLine(cfg));
            default -> false;
        };
    }

    // ------------------------------------------------------------------ command helpers

    /** Runs a real command/message for the player on the main thread. Always "succeeds". */
    /**
     * Sends a command, never longer than the chat packet allows.
     *
     * <p>Minecraft's chat limit is {@value #MAX_COMMAND_LENGTH} characters and the server
     * disconnects a client that goes over it. Some of what this module sends includes text other
     * players typed, so every line is truncated to the limit before it is sent.
     */
    private static boolean run(String command) {
        String safe = command.length() > MAX_COMMAND_LENGTH
                ? command.substring(0, MAX_COMMAND_LENGTH) : command;
        Minecraft.getInstance().execute(() -> SBSCommands.run(safe));
        return true;
    }

    /**
     * Answers into the party chat, marked as coming from the mod (see
     * {@link sbs.modid.client.core.util.ChatTag}) - these lines are the party's main encounter with
     * SBS, and an unmarked "Coords: ..." is indistinguishable from one somebody typed. The tag goes on
     * before {@link #run}, so the length cap there still bounds the whole line.
     */
    private static boolean reply(String message) {
        return run("/pc " + sbs.modid.client.core.util.ChatTag.tag(message));
    }

    /**
     * Cleans a fragment of someone else's chat message before this client repeats it.
     *
     * <p>Text typed by another player is never echoed as-is: it is shortened and reduced to plain
     * printable ASCII, which is what the chat packet accepts and what reads correctly in chat.
     */
    private static String sanitizeEcho(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), ECHO_LIMIT));
        for (int i = 0; i < text.length() && sb.length() < ECHO_LIMIT; i++) {
            char c = text.charAt(i);
            if (c >= ' ' && c <= '~' && c != '&' && c != '§') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** How much of another player's text may be repeated - enough to be useful, far short of the cap. */
    private static final int ECHO_LIMIT = 48;

    /** {@code !f3} / {@code !m7} / {@code !t5} → {@code /joininstance <ID>}. */
    private static boolean joinInstance(String kind, int number) {
        String instance = switch (kind) {
            case "f" -> "CATACOMBS_" + FLOOR_NAMES[number];
            case "m" -> number == 0 ? null : "MASTER_CATACOMBS_" + FLOOR_NAMES[number];
            case "t" -> number == 0 || number > 5 ? null : "KUUDRA_" + KUUDRA_TIERS[number];
            default -> null;
        };
        return instance != null && run("/joininstance " + instance);
    }

    /**
     * A member whose party slot is stuck (usually after a server switch) only comes back when the
     * party drops them first, so the invite is queued a few seconds behind the kick.
     */
    private boolean reinvite(String member) {
        run("/party kick " + member);
        delayedCommand = "/party invite " + member;
        delayedAt = System.currentTimeMillis() + REINVITE_DELAY_MS;
        return true;
    }

    /**
     * Resolves the optional {@code <name>} argument against the tracked party roster (a prefix is
     * enough, so "!kick ste" finds "Steve"), falling back to the argument as typed and finally to
     * the member who sent the command – exactly like typing the command with no name.
     */
    private static String target(String argument, String sender) {
        if (argument == null || argument.isEmpty()) {
            return sender;
        }
        String wanted = argument.toLowerCase(Locale.ROOT);
        for (String member : PartyTracker.getInstance().members()) {
            if (member.toLowerCase(Locale.ROOT).startsWith(wanted)) {
                return member;
            }
        }
        // Not a known member: only a valid Minecraft username is passed on as a command target.
        return IGN.matcher(argument).matches() ? argument : null;
    }

    /**
     * Runs {@code prefix + target}, or nothing when the target was rejected. Every command that
     * takes a name goes through here, so a refused argument can never reach {@link #run}.
     */
    private static boolean runOn(String prefix, String target) {
        return target != null && run(prefix + target);
    }

    // ------------------------------------------------------------------ downtime

    /**
     * The one command whose text comes from another player. The note is stored and echoed
     * <b>sanitised</b> (see {@link #sanitizeEcho}), within the length cap.
     */
    private boolean addDowntime(String sender, String reason) {
        String note = sanitizeEcho(reason);
        if (note.isEmpty()) {
            note = "downtime";
        }
        downtime.put(sender.toLowerCase(Locale.ROOT), note);
        return reply(sender + " needs a break after this run (" + note + ")");
    }

    private boolean removeDowntime(String sender) {
        return downtime.remove(sender.toLowerCase(Locale.ROOT)) != null
                && reply(sender + " is good to go again");
    }

    /**
     * Reminds the party of every pending {@code !downtime} once the run is over – that is the moment
     * the note was left for. Hypixel prints the stat block for dungeons and the defeat line for
     * Kuudra, so either one ends a run.
     */
    private void announceDowntimeOnRunEnd(String text) {
        if (downtime.isEmpty()
                || !(text.contains("> EXTRA STATS <") || text.contains("KUUDRA DOWN!"))) {
            return;
        }
        String pending = String.join(", ", downtime.values());
        downtime.clear();
        reply("Downtime: " + pending);
    }

    // ------------------------------------------------------------------ replies

    private static String coords() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return "Coords: unknown";
        }
        var pos = player.blockPosition();
        return "Coords: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * Measures the ping and answers into the party chat once the pong lands, rather than reporting
     * whatever the HUD card happened to have. The card drives the ping cadence and is off by
     * default, so reading its value here used to answer "unknown" for anyone who never enabled it.
     *
     * <p>Returns true immediately: the command <i>was</i> handled: the reply just follows a moment
     * later, from the client tick that settles the measurement.
     */
    private static boolean replyPing() {
        ServerStatsTracker.getInstance().measurePing(
                ping -> reply(ping < 0 ? "Ping: unknown" : "Ping: " + ping + "ms"));
        return true;
    }

    private static String tps() {
        double tps = ServerStatsTracker.getInstance().tps();
        return tps < 0 ? "TPS: measuring" : String.format(Locale.US, "TPS: %.1f", tps);
    }

    private static String holding() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return "nothing";
        }
        var held = player.getMainHandItem();
        return held.isEmpty() ? "nothing" : held.getHoverName().getString();
    }

    /** One line, because Hypixel caps a chat message at 256 characters. */
    private static String helpLine(SBSConfig.PartyCommandsSettings cfg) {
        StringBuilder builder = new StringBuilder("Commands:");
        if (cfg.warp) {
            builder.append(" !warp");
        }
        if (cfg.allinv) {
            builder.append(" !allinv");
        }
        if (cfg.transfer) {
            builder.append(" !ptme");
        }
        if (cfg.promote) {
            builder.append(" !promote !demote");
        }
        if (cfg.kick) {
            builder.append(" !kick !ko");
        }
        if (cfg.invite) {
            builder.append(" !inv !reinv");
        }
        if (cfg.queue) {
            builder.append(" !f1-!f7 !m1-!m7 !t1-!t5");
        }
        if (cfg.info) {
            builder.append(" !coords !loc !ping !tps !fps !holding !time");
        }
        if (cfg.fun) {
            builder.append(" !cf !8ball !dice");
        }
        if (cfg.downtime) {
            builder.append(" !dt !undt");
        }
        if (cfg.boop) {
            builder.append(" !boop");
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Fires the queued {@code !reinvite} follow-up once its delay has elapsed. */
    public void onClientTick() {
        if (delayedCommand == null || System.currentTimeMillis() < delayedAt) {
            return;
        }
        String command = delayedCommand;
        delayedCommand = null;
        SBSCommands.run(command);
    }

    private static int random(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    private static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name();
    }
}
