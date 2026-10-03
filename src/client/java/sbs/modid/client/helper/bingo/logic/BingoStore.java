/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.bingo.logic;

import com.google.gson.JsonSyntaxException;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.helper.bingo.model.BingoCard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last Bingo card read on this account + profile, kept in {@code bingo.json} so the HUD survives a
 * restart. A card is one profile's, so it lives in the profile's directory.
 *
 * <p>Follows {@code MuseumStore}'s rules: nothing is latched as loaded while the profile is still
 * unknown, nothing is written under the placeholder, a file that could not be read is never
 * overwritten, and readers see nothing until the profile is known.
 *
 * <p><b>Ticking goals off between visits</b> uses an {@code ESTIMATED} chat pattern - no completion
 * line has ever been captured. It can only mark a goal the stored card already names, so a wrong
 * guess ticks nothing off rather than something wrong. Any other chat line mentioning Bingo is logged
 * once per session so the first real completion shows its wording.
 */
public final class BingoStore implements ProfileScopedStore {

    private static final BingoStore INSTANCE = new BingoStore();

    private static final String FILE = "bingo.json";
    private static final int SCHEMA_VERSION = 1;

    /** ESTIMATED: "BINGO GOAL COMPLETE! Kill 100 Zombies" - wording never seen. */
    static final Pattern GOAL_COMPLETE = Pattern.compile("(?i)^\\s*BINGO GOAL COMPLETE!?\\s*(.+?)\\s*$");
    private static final Pattern MENTIONS_BINGO = Pattern.compile("(?i)\\bbingo\\b");

    /** The file, as Gson sees it. Field names are the file format. */
    static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        BingoCard card;
    }

    private Data data = new Data();
    private boolean loaded;
    private boolean unreadable;
    private final java.util.Set<String> loggedChat = new java.util.HashSet<>();

    private BingoStore() {
        ProfileContext.getInstance().register(this);
        ChatPatternRegistry registry = ChatPatternRegistry.getInstance();
        registry.register(GOAL_COMPLETE, (matcher, raw) -> onGoalComplete(matcher), "bingo: goal complete");
        registry.register(MENTIONS_BINGO, (matcher, raw) -> logUnmatched(raw == null ? "" : raw.getString()),
                "bingo: wording capture");
    }

    public static BingoStore getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ reads

    /** The stored card, or null when none has been read on this profile (or it is not known yet). */
    public synchronized BingoCard card() {
        return ready() ? data.card : null;
    }

    private boolean ready() {
        if (!loaded) {
            reloadProfile();
        }
        return loaded && ProfileContext.getInstance().known();
    }

    // ------------------------------------------------------------------ writes

    /** A card just read from the menu: replaces the stored one whole. */
    public synchronized void record(BingoCard card) {
        if (!ready() || card == null) {
            return;
        }
        data.card = card;
        save();
    }

    private synchronized void onGoalComplete(Matcher matcher) {
        if (!ConfigManager.getInstance().get().bingo.enabled || !ready() || data.card == null) {
            return;
        }
        String name = matcher.group(1);
        if (data.card.markDone(name)) {
            save();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bingo] goal ticked off from chat: {}", name);
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bingo] completion line names no open goal: {}", name);
        }
    }

    private void logUnmatched(String rawLine) {
        if (!ConfigManager.getInstance().get().bingo.enabled || GOAL_COMPLETE.matcher(rawLine).find()) {
            return;
        }
        String key = rawLine.toLowerCase(Locale.ROOT);
        if (loggedChat.size() < 50 && loggedChat.add(key)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bingo] unmatched chat: {}", rawLine);
        }
    }

    // ------------------------------------------------------------------ persistence

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    @Override
    public synchronized void reloadProfile() {
        data = new Data();
        unreadable = false;
        loaded = false;
        if (!ProfileContext.getInstance().known()) {
            return;   // the path is the placeholder's, not this profile's: retry once it is known
        }
        Path path = file();
        if (!Files.isRegularFile(path)) {
            loaded = true;
            return;
        }
        try {
            Data read = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.card != null && read.card.goals == null) {
                    read.card.goals = new ArrayList<>();
                }
                if (read.schemaVersion > SCHEMA_VERSION) {
                    unreadable = true;   // a newer client's file: use it, never overwrite it
                }
                data = read;
            }
            loaded = true;
        } catch (JsonSyntaxException corrupt) {
            unreadable = true;
            loaded = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Bingo] {} is unreadable ({}) - left as it is, the "
                    + "card will be re-read but not saved", FILE, corrupt.toString());
        } catch (Exception transientFailure) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bingo] could not read {} yet ({})", FILE,
                    transientFailure.toString());
        }
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private void save() {
        if (!loaded || unreadable || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            data.schemaVersion = SCHEMA_VERSION;
            Files.writeString(path, SBSFiles.GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Bingo] could not write {}: {}", FILE, e.toString());
        }
    }
}
