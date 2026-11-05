/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import sbs.modid.SkyblockSimplifiedSBS;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Capture-only logger for the Bait Sack menu, under {@code [SBS][Fishing][BaitSackProbe]}.
 *
 * <p><b>Why it exists.</b> The Bait Sack replaced the Fishing Bag on 2026-09-21 and nothing of it has
 * been seen by this mod: its exact titles, the slot layout per size, how the favourite bait is shown
 * and whether the "Use Bait From Sacks" toggle sits where the bag's did are all the wiki's word.
 * {@link BaitSackReader} is written against that word; this logs the real menu so it can be
 * corrected - and the captures go into {@code docs/skyblock-ui/menus.md}.
 *
 * <p><b>What it logs</b>: for any container whose title <i>contains</i> "bait sack" or "fishing bag"
 * (looser than the reader on purpose - an unexpected title is exactly what it has to find), once per
 * title per session and only after the content has settled: every menu slot (index, item id, name,
 * stack size, glint, the first three lore lines), then the favourite-marker candidates - glinted
 * slots and every lore line with favourite wording - and what the reader made of it. Also every
 * "[Sacks]" hover line naming the Bait Sack, capped, which tells whether sack-fed bait consumption is
 * announced at all.
 *
 * <p>Always on: it costs one string check per tick while a container is open and logs a handful of
 * blocks per session. Menu items are the player's own baits - no names, purse or profile data.
 */
final class BaitSackProbe {

    private static final int SETTLE_TICKS = 4;
    private static final int LORE_LINES = 3;
    private static final int MAX_SACK_LINES = 20;

    private final Set<String> loggedTitles = new HashSet<>();
    private Map<String, Integer> lastContent;
    private int stableTicks;
    private String lastTitle;
    private int sackLinesLogged;

    /** Whether the probe wants this title at all - the loose net, see the class comment. */
    static boolean probes(String title) {
        String lower = BaitSackReader.normalise(title);
        return lower.contains("bait sack") || lower.contains("fishing bag");
    }

    /** One tick with a probed menu open; logs the layout once it has settled. */
    void onTick(String title, int menuSize, List<BaitSackReader.SlotView> slots) {
        if (loggedTitles.contains(title)) {
            return;
        }
        BaitSackReader.Reading reading = BaitSackReader.read(slots);
        if (!title.equals(lastTitle)) {
            lastTitle = title;
            lastContent = null;
            stableTicks = 0;
        }
        stableTicks = !reading.content().isEmpty() && reading.content().equals(lastContent) ? stableTicks + 1 : 0;
        lastContent = reading.content();
        if (stableTicks < SETTLE_TICKS) {
            return;
        }
        loggedTitles.add(title);
        var log = SkyblockSimplifiedSBS.LOGGER;
        log.info("[SBS][Fishing][BaitSackProbe] menu '{}' size={} menuSlots={} matched={}",
                title, menuSize, slots.size(), BaitSackReader.isBaitStoreTitle(title));
        for (BaitSackReader.SlotView slot : slots) {
            List<String> lore = slot.lore();
            log.info("[SBS][Fishing][BaitSackProbe]   slot {} id={} name='{}' count={} glint={} lore={}",
                    slot.index(), slot.itemId(), slot.name(), slot.count(), slot.glint(),
                    lore.subList(0, Math.min(LORE_LINES, lore.size())));
        }
        for (BaitSackReader.SlotView slot : slots) {
            if (slot.glint()) {
                log.info("[SBS][Fishing][BaitSackProbe]   favourite? slot {} '{}' has glint", slot.index(), slot.name());
            }
            if (BaitSackReader.mentionsFavourite(slot.name())) {
                log.info("[SBS][Fishing][BaitSackProbe]   favourite? slot {} name '{}'", slot.index(), slot.name());
            }
            for (int i = 0; i < slot.lore().size(); i++) {
                if (BaitSackReader.mentionsFavourite(slot.lore().get(i))) {
                    log.info("[SBS][Fishing][BaitSackProbe]   favourite? slot {} '{}' lore[{}] '{}'",
                            slot.index(), slot.name(), i, slot.lore().get(i));
                }
            }
        }
        log.info("[SBS][Fishing][BaitSackProbe] read: baits={} favourite={}", reading.baits(), reading.favourite());
    }

    /** One "[Sacks]" hover line; logged when it names the Bait Sack, up to a per-session cap. */
    void onSackLine(String line) {
        if (sackLinesLogged >= MAX_SACK_LINES || line == null
                || !line.toLowerCase(Locale.ROOT).contains("bait sack")) {
            return;
        }
        sackLinesLogged++;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Fishing][BaitSackProbe] sack line '{}'", line);
    }
}
