/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleEntry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleStatus;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.economy.prices.BazaarPriceCache;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * After a puzzle fails, tells the player which room failed and that an Architect's First Draft can
 * reset it, with how many they hold; optionally keeps a line under the dungeon map until the room is
 * reset, solved, or the run ends.
 *
 * <p><b>Display only.</b> Nothing here uses, moves or buys a draft, or sends anything to the server.
 * The player uses the draft themselves; this only reminds them it exists.
 *
 * <p>The chat parsing and the failed-room state live in the pure {@link PuzzleFailWatch}; this class
 * is the glue - the inventory count, the sack count, the alert and the cached card line.
 *
 * <p><b>The sack count is learned, not queried.</b> A sack's contents are only readable while a
 * sack menu is open ({@code helper/sacks/SackOverlay} reports what it reads through
 * {@link #observeSack}); after that, the "Moved N ... from your Sacks" line keeps it current. It is
 * in memory only, tied to the SkyBlock profile it was read on, and shown as "not read yet" until then
 * - never as zero.
 */
public final class ArchitectDraftReminder {


    /** The draft's SkyBlock item id, as in the bundled item catalogue. */
    public static final String DRAFT_ID = "ARCHITECT_FIRST_DRAFT";

    /** First characters of every line {@link PuzzleFailWatch#parse} accepts - a cheap prefilter. */
    static final Pattern LINES = Pattern.compile(
            "^(?:" + PuzzleResultLine.PREFIX + "PUZZLE |You used the Architect|Moved \\d)");

    /** Inventory re-count interval while a reminder is showing. */
    private static final int COUNT_EVERY_TICKS = 20;

    /** Width of the map strip's text row in unscaled font pixels: (157 - 6) / 0.6. */
    private static final int CARD_WIDTH_PX = 250;

    private final PuzzleFailWatch watch = new PuzzleFailWatch();

    private long ticks;
    private int inventoryCount;
    private Long sackCount;
    private String sackProfile;

    /** The card line, rebuilt only when its text changes. {@code null} when nothing to show. */
    private Component cardLine;
    private String cardText;

    // Declared after every static field: the constructor registers patterns, and a field
    // initialised below this line is still null when it runs (the puzzle chat was dead for it).
    private static final ArchitectDraftReminder INSTANCE = new ArchitectDraftReminder();

    private ArchitectDraftReminder() {
        ChatPatternRegistry.getInstance().register(LINES,
                (matcher, component) -> onChat(component == null ? null : component.getString()),
                "architect draft reminder");
    }

    public static ArchitectDraftReminder getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private void onChat(String raw) {
        if (raw == null) {
            return;
        }
        PuzzleFailWatch.Event event = PuzzleFailWatch.parse(PlainText.strip(raw).trim());
        if (event == null) {
            return;
        }
        if (event instanceof PuzzleFailWatch.SackMove move) {
            // Outside a run too: the sack count is per profile, not per run.
            if (sackCount != null && Objects.equals(sackProfile, currentProfile())) {
                sackCount = Math.max(0, sackCount - move.count());
            }
            return;
        }
        if (!cfg().architectDraftReminder
                || !sbs.modid.client.dungeons.run.logic.DungeonStateManager.getInstance().inDungeon()) {
            return;
        }
        switch (event) {
            case PuzzleFailWatch.Fail fail -> {
                PuzzleFailWatch.FailOutcome outcome = watch.onFail(fail, ticks);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] draft reminder: fail by {} room={} -> {}",
                        fail.player(), fail.room(), outcome);
                if (outcome == PuzzleFailWatch.FailOutcome.NAMED) {
                    remind(fail.room());
                }
            }
            case PuzzleFailWatch.Reset reset -> {
                watch.onReset(reset);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] draft reminder: reset '{}' -> {}",
                        reset.name(), reset.room());
            }
            case PuzzleFailWatch.Solved solved -> watch.onSolved(solved.room());
            default -> {
            }
        }
        rebuildCard();
    }

    /**
     * A sack menu's row, as {@code SackOverlay} read it. Only the draft's row is kept; an unread
     * amount ({@code < 0}) is ignored rather than stored as zero.
     */
    public void observeSack(String id, long stored) {
        if (stored < 0 || !DRAFT_ID.equals(id)) {
            return;
        }
        sackCount = stored;
        sackProfile = currentProfile();
    }

    private static String currentProfile() {
        try {
            return ProfileContext.getInstance().profile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Tick - called by PuzzleCoordinator while in a dungeon
    // ------------------------------------------------------------------

    public void tick(Minecraft minecraft) {
        ticks++;
        if (!cfg().architectDraftReminder) {
            if (watch.active() || watch.pending()) {
                clear();
            }
            return;
        }
        PuzzleTabReader tab = PuzzleTabReader.getInstance();
        if (watch.pending()) {
            PuzzleFailWatch.Resolution resolved = watch.tick(ticks, tabNewlyFailed(tab), currentPuzzleRoom());
            if (resolved != null) {
                remind(resolved.room());
                rebuildCard();
            }
        }
        if (!watch.active()) {
            return;
        }
        // A room the tab now shows as solved needs no draft any more.
        boolean changed = false;
        for (PuzzleType type : PuzzleType.values()) {
            if (watch.failed().contains(type) && tab.statusOf(type) == PuzzleStatus.COMPLETE) {
                watch.onSolved(type);
                changed = true;
            }
        }
        if (changed || ticks % COUNT_EVERY_TICKS == 0) {
            inventoryCount = countInventory(minecraft);
            rebuildCard();
        }
    }

    /** Run end, world change, leaving the dungeon. */
    public void clear() {
        watch.clear();
        cardLine = null;
        cardText = null;
    }

    private PuzzleType tabNewlyFailed(PuzzleTabReader tab) {
        for (PuzzleEntry entry : tab.entries()) {
            if (entry.type() != null && entry.status() == PuzzleStatus.FAILED
                    && !watch.failed().contains(entry.type())) {
                return entry.type();
            }
        }
        return null;
    }

    private static PuzzleType currentPuzzleRoom() {
        return PuzzleType.fromName(DungeonRoomTracker.getInstance().activeRoomName());
    }

    private static int countInventory(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null) {
            return 0;
        }
        Inventory inventory = minecraft.player.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && DRAFT_ID.equals(SkyblockItem.id(stack))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private Long knownSacks() {
        return sackCount != null && Objects.equals(sackProfile, currentProfile()) ? sackCount : null;
    }

    private String priceText() {
        if (!cfg().architectDraftShowPrice) {
            return null;
        }
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().priceOf(DRAFT_ID);
        return price == null || price.buy() <= 0 ? null : NumberDisplay.format(price.buy());
    }

    private void remind(PuzzleType room) {
        inventoryCount = countInventory(Minecraft.getInstance());
        Long sacks = knownSacks();
        long total = inventoryCount + (sacks == null ? 0 : sacks);
        String price = total > 0 ? null : priceText();
        String title = "Puzzle failed: " + PuzzleFailWatch.roomName(room);
        String detail = PuzzleFailWatch.detail(inventoryCount, sacks, price);
        Alerts.send(new Alerts.Alert(title, detail, SbsAudio.Tone.CHIME, null),
                cfg().architectDraftChannels);
    }

    private void rebuildCard() {
        String text = null;
        if (cfg().architectDraftCardLine && watch.active()) {
            PuzzleType first = watch.failed().isEmpty() ? null : watch.failed().iterator().next();
            int rooms = watch.failed().size() + watch.unnamed();
            Long sacks = knownSacks();
            boolean have = inventoryCount > 0 || (sacks != null && sacks > 0);
            String state = have ? "draft available" : sacks == null ? "draft?" : "no draft";
            text = "⚠ " + PuzzleFailWatch.roomName(first) + (rooms > 1 ? " +" + (rooms - 1) : "")
                    + " failed - " + state;
        }
        if (!Objects.equals(text, cardText)) {
            cardText = text;
            cardLine = text == null ? null : Component.literal(fit(text));
        }
    }

    /** Cut to the strip's width in unscaled font pixels (the strip draws at 0.6 scale). */
    private static String fit(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.font == null ? text
                : minecraft.font.plainSubstrByWidth(text, CARD_WIDTH_PX);
    }

    /** The line for the map strip, or {@code null}. Render thread; no allocation. */
    public Component cardLine() {
        return cfg().architectDraftCardLine ? cardLine : null;
    }
}
