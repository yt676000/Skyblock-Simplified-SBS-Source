/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.sendcoords;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.ChatTag;
import sbs.modid.client.social.chat.logic.ActiveChannel;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.social.chat.model.SendChannel;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code /sendcoords [party|guild|coop|all] [note]} - puts the player's block position into a chat
 * channel, in a shape another player's client can read back out.
 *
 * <p><b>It only ever runs when the player types it.</b> There is no tick, no listener and no retry:
 * one execution sends at most one message, which is what keeps this a convenience rather than a
 * broadcaster. The two-second spacing below is there so a held key or a fast repeat cannot turn it
 * into one anyway.
 *
 * <h2>Where it goes: the chat the player is already in</h2>
 *
 * <p>With no channel typed after it, the line goes wherever a message typed by hand at that moment
 * would go - {@link ActiveChannel#active()}, the same selection the chat input's indicator shows and
 * the same one {@code ChatChannelPrefixMixin} applies to everything else the player types. There is
 * no separate default to keep in step with it. A configured one used to exist and was removed: it
 * was a second answer to "which chat am I in", it was invisible at the moment of sending, and being
 * wrong about it puts a coordinate in front of people who were never meant to see it.
 *
 * <p>When nothing is selected - the channel feature is off, or it is set to
 * {@link SendChannel#PUBLIC} - the line is sent <b>untouched</b>, exactly as typing it would be, and
 * where the server puts it is the server's business. This client does not track Hypixel's own
 * {@code /chat} selection and does not claim to, which is why the confirmation for that case says
 * "the chat you are in" rather than naming one.
 *
 * <p>{@link SendChannel#OFFICER} is reachable this way and deliberately still not reachable from the
 * argument. A selected channel is a deliberate act with an indicator on screen the whole time; a
 * typed word is one letter away from being a different word.
 *
 * <h2>What is sent</h2>
 *
 * <p>Plain text, no {@code §} codes, no decoration: {@value #DEFAULT_FORMAT} with the placeholders
 * filled in. The point of the line is that someone else's waypoint feature can find three numbers in
 * it without knowing which client wrote it, and every ornament is one more thing their pattern has
 * to tolerate. The format is a setting because no such shape is standardised - what a given client
 * accepts can only be found out by trying it, and that trying must not need a mod update.
 *
 * <p>The {@code [SBS]} marker in front is {@link ChatTag}'s, the mod-wide switch for lines this
 * client puts into a channel other people read. It is plain text and sits before the coordinates, so
 * a pattern looking for the numbers still finds them; a player who would rather not carry it turns
 * it off in one place for the whole mod.
 *
 * <h2>Block position, not the camera</h2>
 *
 * <p>{@link LocalPlayer#blockPosition()} - the block being stood in, as three integers. The raw
 * doubles carry a precision nobody can act on, and rounding them at the receiving end is a decision
 * this client has no business leaving to somebody else's parser.
 *
 * <h2>Why the building is separated from the sending</h2>
 *
 * <p>Everything below the {@link #handle} method is pure: text in, text out, no client and no
 * config. That is what lets {@code SendCoordsTest} run the cases that matter - the wrong channel,
 * the over-long line, the colour code someone pasted into a note - in the ordinary test suite,
 * which is the same reasoning {@code SendChannel} is written under. A message that goes somewhere
 * unintended sends exactly like one that does not.
 */
public final class SendCoords {

    /**
     * The shipped format, and the reason the setting exists rather than a hard-coded string.
     *
     * <p>Three labelled integers with separators either side of them, so whatever a reader matches -
     * a label, a comma-separated triple, or bare numbers - is present. No other client's exact
     * wording is claimed here: none of it could be verified from this machine, and a format asserted
     * from a memory of one is worse than a format chosen to be easy to match.
     */
    public static final String DEFAULT_FORMAT = "x: %x%, y: %y%, z: %z%";

    /**
     * Spacing between two uses, client-side.
     *
     * <p>The same two seconds the party-command module waits, for the same reason: Hypixel throttles
     * a client that talks too quickly, and the message it drops is not always the one that was too
     * fast. Nothing is queued while it runs - a use inside the window is refused and told so, since
     * a queued coordinate arrives describing somewhere the player no longer is.
     */
    private static final long COOLDOWN_MS = 2000L;

    /**
     * Hard cap on the outgoing line, including the channel command and the tag.
     *
     * <p>Minecraft's chat packet holds {@value} characters and the server disconnects a client that
     * exceeds it. The note is what gives way; the coordinates never are. A shortened note is a
     * shorter sentence, while a shortened coordinate is a different place - and one nobody reading
     * it can tell is wrong.
     */
    private static final int CHAT_LIMIT = 256;

    /**
     * A legacy colour code: {@code §} and the character it applies to.
     *
     * <p>Both go, which is what every other chat parser in this mod does with them. Dropping only the
     * {@code §} would leave the code letter standing in the middle of the sentence - "§alook here"
     * would be sent as "alook here", and the player would read it as a typo of their own.
     */
    private static final Pattern FORMATTING = Pattern.compile("(?i)§.");

    /**
     * The newline that would split one message into two, and every other control character.
     *
     * <p>Replaced by a space rather than removed: a note typed across two lines is two words either
     * side of the break, and deleting the break silently welds them into one.
     */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** A {@code §} left over from a code with nothing after it. Removed outright - it separates nothing. */
    private static final Pattern STRAY_SECTION = Pattern.compile("§");

    /** Runs of whitespace, which a chat line has no use for and a parser has to allow for. */
    private static final Pattern RUNS = Pattern.compile("\\s{2,}");

    /** Colour of everything this command says to the player alone and never sends. */
    private static final int WARNING = 0xFFAA55;

    /** When the last message actually went out; 0 until the first one does. */
    private static long lastSentAt;

    /**
     * The last line this command sent, exactly as it left.
     *
     * <p><b>Deliberately not cleared on a world change.</b> Hypixel's duplicate filter is server-side
     * and this is only a guess at what it holds; nothing observed says a lobby hop resets it, and
     * clearing on a hop would drop the warning in the case it is most wanted - the player who warps,
     * comes back, and calls the same spot again. It costs one string either way.
     */
    private static String lastSent = "";

    private SendCoords() {
    }

    private static SBSConfig.SendCoordsSettings cfg() {
        return ConfigManager.getInstance().get().sendCoords;
    }

    /**
     * Runs the command: reads the world, the active channel and the config, hands the pure part the
     * numbers, and sends at most one line.
     *
     * @param argument everything typed after {@code /sendcoords}; empty when there was nothing
     */
    public static void handle(String argument) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) {
            warn("Not connected - nothing was sent.");
            return;
        }

        // The channel is the first word, or nothing at all - in which case it is the chat the player
        // is already talking in: the selection the chat input's indicator shows, and "untouched"
        // when there is none. An unrecognised first word is refused rather than folded into the
        // note: the two readings are indistinguishable from here, and reading a mistyped channel as
        // a note sends the coordinates somewhere nobody chose.
        String typed = argument == null ? "" : argument.trim();
        SendChannel channel = ActiveChannel.getInstance().active();
        boolean named = false;
        String note = "";
        if (!typed.isEmpty()) {
            String[] parts = typed.split("\\s+", 2);
            SendChannel picked = channelOf(parts[0]);
            if (picked == null) {
                usage(parts[0]);
                return;
            }
            channel = picked;
            named = true;
            note = parts.length > 1 ? parts[1] : "";
        }

        long remaining = COOLDOWN_MS - (System.currentTimeMillis() - lastSentAt);
        if (lastSentAt > 0 && remaining > 0) {
            warn(String.format(Locale.US, "Wait %.1fs before sending coordinates again.",
                    remaining / 1000.0));
            return;
        }

        String format = cfg().format;
        if (!usable(format)) {
            // A format that cannot hold all three coordinates is not a format this command can use.
            // The fallback is announced rather than silent, because the alternative is a line that
            // looks sent and carries nothing.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][SendCoords] format '{}' is missing %x%/%y%/%z% - "
                    + "using the default", format);
            warn("Your coordinate format has no %x%, %y% and %z% - using the default instead.");
            format = DEFAULT_FORMAT;
        }

        BlockPos pos = player.blockPosition();
        Fitted fitted = fit(coordinates(format, pos.getX(), pos.getY(), pos.getZ()),
                clean(note), room(channel));
        if (fitted == null) {
            warn("Your coordinate format is too long to send - shorten it in the SBS settings.");
            return;
        }
        if (fitted.noteTrimmed()) {
            warn("Your note did not fit in one chat message and has been shortened.");
        }

        String message = ChatTag.tag(fitted.message());
        String outgoing = channel.apply(message);
        boolean duplicate = outgoing.equals(lastSent);

        // The one send path: the same one every other feature types through, so a command shortcut,
        // the transfer cooldown and anything else hanging off it applies here too.
        SBSCommands.run(outgoing);
        lastSentAt = System.currentTimeMillis();
        lastSent = outgoing;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][SendCoords] sent to {}: {}", channel.name(), message);

        SBSChat.send(Component.literal(" Sent to " + destination(channel, named) + ": ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(message).withColor(SBSChat.PREFIX_COLOR)));
        if (duplicate) {
            // Hypixel drops a message identical to the one before it and tells the sender nothing, so
            // the only evidence is a line that never appears. This is the client guessing at the
            // server's state from its own history, and it is worded as a guess.
            SBSChat.send(Component.literal(" Same message as last time - Hypixel drops a repeat, so "
                    + "it probably did not arrive. Add a note after the channel to make it "
                    + "different.").withColor(WARNING));
        }
    }

    /** How much of a chat message is left once the channel command and the {@code [SBS]} tag are in it. */
    private static int room(SendChannel channel) {
        // Measured against the line as it will actually leave rather than against a remembered
        // length: the tag is a switch, and the channel command is four characters or none.
        return CHAT_LIMIT - (channel.apply(ChatTag.tag("x")).length() - 1);
    }

    // ------------------------------------------------------------------ the pure part

    /** What {@link #fit} decided: the line to send, and whether the note had to give way for it. */
    record Fitted(String message, boolean noteTrimmed) {
    }

    /** Whether a format can carry a coordinate at all. All three placeholders, or it is no format. */
    static boolean usable(String format) {
        return format != null && format.contains("%x%") && format.contains("%y%")
                && format.contains("%z%");
    }

    /** The block position through a format known to be {@link #usable}. */
    static String coordinates(String format, int x, int y, int z) {
        return clean(format)
                .replace("%x%", Integer.toString(x))
                .replace("%y%", Integer.toString(y))
                .replace("%z%", Integer.toString(z));
    }

    /**
     * Strips what a chat packet must not carry: control characters, {@code §} colour codes and the
     * newlines that would otherwise split one message into two. Leading slashes go as well, so
     * neither a note nor a custom format can turn the line into a command.
     */
    static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = FORMATTING.matcher(text).replaceAll("");
        stripped = STRAY_SECTION.matcher(stripped).replaceAll("");
        stripped = CONTROL.matcher(stripped).replaceAll(" ").trim();
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1).trim();
        }
        return RUNS.matcher(stripped).replaceAll(" ").trim();
    }

    /**
     * The message as it should be sent, within {@code room} characters, or {@code null} when nothing
     * can be.
     *
     * <p>The note gives way first. Coordinates that do not fit on their own mean the configured
     * format is longer than a chat message, and that is refused rather than cut: sending half a
     * coordinate is sending a different place, and the person reading it cannot tell.
     */
    static Fitted fit(String coordinates, String note, int room) {
        if (coordinates.length() > room) {
            return null;
        }
        if (note.isEmpty()) {
            return new Fitted(coordinates, false);
        }
        String message = coordinates + " " + note;
        if (message.length() <= room) {
            return new Fitted(message, false);
        }
        int keep = room - coordinates.length() - 1;
        // Below one character of note there is nothing worth keeping, and a trailing space is not a
        // note - the coordinates go on their own.
        return keep > 0
                ? new Fitted(coordinates + " " + note.substring(0, keep).trim(), true)
                : new Fitted(coordinates, true);
    }

    /**
     * The four destinations {@code /sendcoords} accepts, plus the short forms of the commands they
     * stand for. {@code null} for anything else.
     *
     * <p><b>Officer chat is not here</b> even though {@link SendChannel} has it: it was not asked
     * for, and it is the channel where a wrong guess puts a message in front of people it was not
     * meant for. Selecting it as the active channel does send there, which is a different act - see
     * the class doc. {@code all} is {@link SendChannel#PUBLIC}, which sends the line as ordinary
     * chat - what everyone on the island sees.
     */
    static SendChannel channelOf(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "party", "p", "pc" -> SendChannel.PARTY;
            case "guild", "g", "gc" -> SendChannel.GUILD;
            case "coop", "co", "cc" -> SendChannel.COOP;
            case "all", "a", "ac", "public", "chat" -> SendChannel.PUBLIC;
            default -> null;
        };
    }

    /**
     * How the local confirmation names where the line went.
     *
     * <p>A channel with a command was prefixed, so it is named outright, and so is one the player
     * typed - both are known. The remaining case is a message sent untouched with nobody having said
     * where it should land: the server decides that, this client never saw the decision, and naming
     * "public chat" there would be a guess printed as a fact to the one person who could still act
     * on it being wrong.
     *
     * @param named whether the player typed the channel after the command
     */
    static String destination(SendChannel channel, boolean named) {
        return channel.prefixes() || named ? channel.label() + " chat" : "the chat you are in";
    }

    // ------------------------------------------------------------------ talking to the player

    private static void usage(String typed) {
        SBSChat.send(Component.literal(" \"" + clean(typed) + "\" is not a channel. Use ")
                .withColor(WARNING)
                .append(Component.literal("/sendcoords [party|guild|coop|all] [note]")
                        .withColor(SBSChat.PREFIX_COLOR))
                .append(Component.literal(" - with no channel it goes to the chat you are in.")
                        .withColor(WARNING)));
    }

    private static void warn(String text) {
        SBSChat.send(Component.literal(" " + text).withColor(WARNING));
    }
}
