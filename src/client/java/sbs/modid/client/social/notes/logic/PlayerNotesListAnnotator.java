/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.logic;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.streamer.logic.StreamerNames;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.social.notes.logic.ListLineParser.Kind;
import sbs.modid.client.social.notes.model.PlayerNote;
import sbs.modid.client.social.notes.model.PlayerNoteBook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Puts your notes into Hypixel's {@code /friend list} and {@code /ignore list}: after each listed
 * player you have a note on, a grey {@code ✎ <note>} (full text on hover), and on every listed line a
 * {@code [+note]} / {@code [edit]} button.
 *
 * <p><b>Display only.</b> Called from {@code ChatDisplayRewrites}, below every chat parser, so no
 * parser ever sees the added text. Hypixel's pieces are copied with their own style - colour, click
 * and hover - and the marker is inserted <i>between</i> them, never over them. The buttons only fill
 * the chat box with the {@code /sbs note} command, which never leaves the client.
 *
 * <p>Hypixel may send the list as one message with line breaks or as one message per line; both
 * work, because a block is opened by its header and stays open across messages until the closing
 * rule or {@link #BLOCK_MS}.
 */
public final class PlayerNotesListAnnotator {

    private static final PlayerNotesListAnnotator INSTANCE = new PlayerNotesListAnnotator();

    /** A block not closed by its rule is over this long after its last line. */
    private static final long BLOCK_MS = 2000L;
    /** A friends block this long after the previous one starts a new "last list". */
    private static final long NEW_LIST_MS = 60_000L;
    private static final int PREVIEW_CHARS = 30;
    private static final int GREY = 0xAAAAAA;

    private Kind kind;
    private long blockAt;
    private long probeUntil;
    private long friendsAt;
    /** Noted friends from the last friend list output, in list order - for {@code /sbs notes friends}. */
    private final Set<String> notedFriends = new LinkedHashSet<>();

    private PlayerNotesListAnnotator() {
    }

    public static PlayerNotesListAnnotator getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.PlayerNotesSettings cfg() {
        return ConfigManager.getInstance().get().playerNotes;
    }

    /** Noted players seen in the last friend list, in the order Hypixel listed them. */
    public List<String> notedFriends() {
        return new ArrayList<>(notedFriends);
    }

    /** The line as it should be shown, or {@code message} itself when it is no list line. */
    public Component decorate(Component message) {
        String text = message.getString();
        long now = System.currentTimeMillis();
        probe(message, text, now);

        if (kind != null && now - blockAt > BLOCK_MS) {
            kind = null;
        }
        if (kind == null && text.indexOf('(') < 0) {
            return message;   // the common case: no block open and no header possible on this line
        }
        SBSConfig.PlayerNotesSettings cfg = cfg();
        String[] lines = PlainText.strip(text).split("\n", -1);
        String[] names = new String[lines.length];
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            Kind opened = ListLineParser.header(lines[i]);
            if (opened != null) {
                kind = opened;
                blockAt = now;
                if (opened == Kind.FRIENDS) {
                    if (now - friendsAt > NEW_LIST_MS) {
                        notedFriends.clear();
                    }
                    friendsAt = now;
                }
                continue;
            }
            if (kind == null) {
                continue;
            }
            if (ListLineParser.isSeparator(lines[i])) {
                kind = null;   // the closing rule
                continue;
            }
            String name = ListLineParser.playerName(kind, lines[i]);
            if (name != null && PlayerNoteBook.isValidName(name)) {
                blockAt = now;
                names[i] = name;
                any = true;
                if (kind == Kind.FRIENDS && note(name) != null) {
                    notedFriends.add(name);
                }
            }
        }
        if (!any || !cfg.enabled || (!cfg.listNotes && !cfg.listButtons)) {
            return message;
        }
        return rebuild(message, names, cfg);
    }

    // ------------------------------------------------------------------ rebuilding the line

    /**
     * Copies the message piece by piece, keeping each piece's style, and inserts the additions at the
     * end of every line that named a player. A line break inside a piece splits it there.
     */
    private Component rebuild(Component message, String[] names, SBSConfig.PlayerNotesSettings cfg) {
        MutableComponent out = Component.empty();
        int[] line = {0};
        message.visit((style, piece) -> {
            int start = 0;
            int nl;
            while ((nl = piece.indexOf('\n', start)) >= 0) {
                out.append(Component.literal(piece.substring(start, nl)).setStyle(style));
                appendAdditions(out, names, line[0]++, cfg);
                out.append(Component.literal("\n"));
                start = nl + 1;
            }
            if (start < piece.length()) {
                out.append(Component.literal(piece.substring(start)).setStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        appendAdditions(out, names, line[0], cfg);
        return out;
    }

    private void appendAdditions(MutableComponent out, String[] names, int line,
                                 SBSConfig.PlayerNotesSettings cfg) {
        if (line >= names.length || names[line] == null) {
            return;
        }
        String name = names[line];
        PlayerNote note = note(name);
        if (cfg.listNotes && note != null) {
            out.append(marker(note));
        }
        if (cfg.listButtons) {
            boolean edit = note != null;
            String command = edit ? PlayerNotesCommands.editCommand(note) : "/sbs note " + name + " ";
            out.append(Component.literal(" "))
                    .append(Component.literal(edit ? "[edit]" : "[+note]").withColor(SBSChat.PREFIX_COLOR)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent.SuggestCommand(command))
                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                            (edit ? "Edit your note on " : "Add a note on ") + name
                                                    + ": fills in the chat box, nothing is sent")))));
        }
    }

    /** {@code  ✎ left at Maxor twice…}, coloured by tag; the text is withheld in Streamer Mode. */
    static MutableComponent marker(PlayerNote note) {
        boolean showText = !note.note.isEmpty() && !StreamerNames.getInstance().active();
        String preview = !showText ? note.tag.label()
                : note.note.length() <= PREVIEW_CHARS ? note.note
                : note.note.substring(0, PREVIEW_CHARS) + "…";
        String hover = note.name + " - " + note.tag.label() + (showText ? "\n" + note.note : "");
        return Component.literal(" ✎ ").withColor(note.tag.color())
                .append(Component.literal(preview).withColor(GREY))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static PlayerNote note(String name) {
        return PlayerNotesStore.getInstance().book().match(name, PlayerLookup.uuidOf(name));
    }

    // ------------------------------------------------------------------ probe

    /**
     * STEP 1 probe: logs a list block whole - each line plain and with its styles, click and hover -
     * so the real shapes can be recorded in the spec. Starts on any line naming one of the list
     * words and keeps logging for {@link #BLOCK_MS} after the last line. Remove once the shapes are
     * CONFIRMED in {@code docs/features/player-notes.md}.
     */
    private void probe(Component message, String text, long now) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("friends") || lower.contains("freunde")
                || lower.contains("ignored") || lower.contains("ignoriert")) {
            probeUntil = now + BLOCK_MS;
        }
        if (now > probeUntil) {
            return;
        }
        probeUntil = now + BLOCK_MS;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][NotesLists] plain='{}'", PlainText.strip(text));
        message.visit((style, piece) -> {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][NotesLists]   piece='{}' color={} click={} hover={}",
                    piece, style.getColor(), style.getClickEvent(), style.getHoverEvent());
            return Optional.empty();
        }, Style.EMPTY);
    }
}
