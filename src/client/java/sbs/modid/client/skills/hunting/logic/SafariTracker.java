/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tracker.TrackerStore;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Safari trip: what you caught while you were in there, handed over as a summary the moment you
 * come back out.
 *
 * <p><b>Why a trip and not a session.</b> Shards go straight to the Hunting Box, so nothing about a
 * Safari run is visible afterwards - the box shows a lifetime total, and the chat lines that named
 * each catch have long scrolled away by the time you are back. The one moment the question "what did
 * that run actually give me" can still be answered is the moment you leave, which is exactly when
 * this fires.
 *
 * <p><b>Where you are comes from the scoreboard.</b> {@link SkyBlockLocation} is the only reader of
 * the {@code ⏣} zone line, and the trip is bounded by it. The instance is recognised by name, exactly
 * ({@link SkyBlockLocation#isCritterSafari}); the "Critter Safari Entrance" zone is refused outright,
 * because it belongs to Torrhus Canyon and carries the Safari's whole name; and only behind both does
 * the configured word - "Safari" by default - still get a say. The word is a setting and matched
 * case-insensitively on purpose: Hypixel renames and re-flavours its areas, and a feature that dies
 * on a rename nobody can fix from the config is a feature that dies for good. What it may no longer
 * do is claim the entrance, which is how everything gated here once drew the Safari's own coordinate
 * space onto the canyon.
 *
 * <p><b>Leaving is confirmed, not assumed.</b> Warping blanks the sidebar for a moment and the tab
 * list lags behind it, so an unreadable location never ends a trip - it is simply "not known yet".
 * Only a readable location that disagrees for {@value #LEAVE_CONFIRM_MS} ms does. That also means the
 * catch lines still arriving in the first seconds after a warp are counted into the trip they belong
 * to, not dropped.
 *
 * <p><b>Counting is chat-driven</b>, the same three line shapes the fishing tracker books shards
 * from, for the same reason: a shard never touches the inventory, so chat is the only place it can be
 * counted at all. The Hunting Box transfer line is the authority on the amount (Hunter Fortune extras
 * only show up there), so a box line following its own catch line tops the booking up to what the box
 * really received instead of counting the shard twice. Shard-flavoured lines that match nothing are
 * logged as {@code [SBS][Safari]} - the wording of a new zone is the one thing that cannot be known
 * from here, and the log is how a missed line gets pinned.
 */
public final class SafariTracker {

    private static final SafariTracker INSTANCE = new SafariTracker();

    /** What the zone filter falls back to when the setting is left empty. */
    public static final String DEFAULT_ZONE = "Safari";

    /** How often the location is re-read; it only changes when you travel. */
    private static final long POLL_MS = 250L;

    /** A readable location has to disagree for this long before the trip counts as over. */
    private static final long LEAVE_CONFIRM_MS = 2_000L;

    /** A trip shorter than this that caught nothing says nothing - stepping in and out is not a run. */
    private static final long MIN_REPORT_MS = 30_000L;

    /** Two shard lines of different kinds for the same shard inside this window are one shard. */
    private static final long SHARD_DEDUPE_MS = 10_000L;

    /** Throttle for the "line not understood" log, so a chatty zone cannot flood the log file. */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** How many shard kinds the chat summary lists before collapsing the rest into one line. */
    private static final int MAX_CHAT_ROWS = 15;

    // ------------------------------------------------------------------
    // Chat line shapes (matched with colour codes already stripped)
    // ------------------------------------------------------------------

    /**
     * "You caught a Lapis Zombie Shard!" - the catch itself. The verb is deliberately a list: the
     * fishing variant says "caught", the hunting ones "found" and "obtained", and a zone whose
     * wording cannot be checked from here is better served by a pattern that survives one. Amount
     * forms "2", "x2" and "2x" all parse.
     */
    private static final Pattern SHARD_GAINED = Pattern.compile(
            "You (?:caught|found|obtained|got|collected) (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards?[!.]");

    /** "LOOT SHARE You received a Bal Shard for assisting ..." - a party member's kill still pays you. */
    private static final Pattern SHARD_SHARED = Pattern.compile(
            "You received (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? for assisting");

    /**
     * "You sent 3 Tide Shards to your Hunting Box." - the authority on how many shards a catch
     * produced, and therefore always tried last: it tops its own catch line up rather than opening a
     * booking of its own.
     */
    private static final Pattern SHARD_BOXED = Pattern.compile(
            "You sent (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? to your Hunting Box");

    /** Anything shard-flavoured that matched none of the above is worth a log line. */
    private static final Pattern SHARD_ISH = Pattern.compile("(?i)\\bshards?\\b");

    /** A player-written line ("Name: gg shard"); never a server announcement, never a catch. */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Guild|Party|Co-op|Officer|G|P|O) > )?(?:\\[[^\\]]+\\] )*[A-Za-z0-9_]{2,16}[^:]{0,4}: ");

    /** Legacy colour code -> RGB, for painting a shard row in the rarity Hypixel wrote it in. */
    private static final Map<Character, Integer> CODE_COLORS = Map.ofEntries(
            Map.entry('0', 0x000000), Map.entry('1', 0x0000AA), Map.entry('2', 0x00AA00),
            Map.entry('3', 0x00AAAA), Map.entry('4', 0xAA0000), Map.entry('5', 0xAA00AA),
            Map.entry('6', 0xFFAA00), Map.entry('7', 0xAAAAAA), Map.entry('8', 0x555555),
            Map.entry('9', 0x5555FF), Map.entry('a', 0x55FF55), Map.entry('b', 0x55FFFF),
            Map.entry('c', 0xFF5555), Map.entry('d', 0xFF55FF), Map.entry('e', 0xFFFF55),
            Map.entry('f', 0xFFFFFF));

    /** One shard kind of a trip: its Bazaar id, the name as chat wrote it, and its rarity colour. */
    public record Entry(String id, String name, int count, int color) {
    }

    /**
     * A finished trip. The value is captured when the trip ends rather than read live - it is a
     * record of what that run was worth, and a card whose total drifts while you read it is a card
     * that cannot be trusted.
     */
    public record Summary(List<Entry> entries, int totalShards, long durationMs, long endedAt,
                          double value) {

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    // ---- trip state -----------------------------------------------------------------------------

    private boolean inside;
    private long enteredAt;
    /** When the location first disagreed while inside; 0 while it agrees (or cannot be read). */
    private long leftPendingSince;

    /** Bazaar id -> count for the running trip, plus the display name and colour of each id. */
    private final Map<String, Integer> shards = new LinkedHashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Integer> colors = new HashMap<>();

    /** The last booked shard line, for the box-line top-up. */
    private String lastShardName = "";
    private long lastShardAt;
    private boolean lastShardBoxed;
    private int lastShardAmount;

    private volatile Summary lastSummary;

    private long lastPollAt;
    private long lastLogAt;

    private SafariTracker() {
    }

    public static SafariTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SafariSettings cfg() {
        return ConfigManager.getInstance().get().safari;
    }

    // ---- read by the renderer -------------------------------------------------------------------

    /** Whether the player is currently on a Safari trip. */
    public boolean inside() {
        return inside;
    }

    /** How long the running trip has lasted, or 0 outside one. */
    public long tripMillis() {
        return inside ? System.currentTimeMillis() - enteredAt : 0;
    }

    /** The running trip's shards, most-caught first - what the optional live counter draws. */
    public List<Entry> liveEntries() {
        return sortedEntries();
    }

    /** How many shards the running trip has booked in total. */
    public int liveTotal() {
        int total = 0;
        for (int count : shards.values()) {
            total += count;
        }
        return total;
    }

    /** The last finished trip, or {@code null} when none has finished this session. */
    public Summary lastSummary() {
        return lastSummary;
    }

    /** Whether the summary card is still within its display window. */
    public boolean summaryVisible() {
        Summary summary = lastSummary;
        if (summary == null || summary.isEmpty() || !cfg().enabled || !cfg().hudSummary) {
            return false;
        }
        return System.currentTimeMillis() - summary.endedAt() < cfg().hudSeconds * 1000L;
    }

    // ---- location -------------------------------------------------------------------------------

    /**
     * Called every client tick; the location itself is read on the {@value #POLL_MS} ms throttle.
     *
     * <p>An unreadable location (mid-warp, no sidebar served yet) is never treated as having left:
     * it is the normal state for the seconds around a warp, and ending the trip there would cut the
     * summary short of the catches still arriving.
     */
    public void onClientTick() {
        if (!cfg().enabled) {
            // Switched off mid-trip: forget it rather than keep it, or turning the module back on
            // somewhere else would publish a summary for a trip nobody is on any more.
            if (inside) {
                inside = false;
                leftPendingSince = 0;
                shards.clear();
                names.clear();
                colors.clear();
            }
            return;
        }
        if (Minecraft.getInstance().player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPollAt < POLL_MS) {
            return;
        }
        lastPollAt = now;

        String zone = SkyBlockLocation.zone();
        String island = SkyBlockLocation.island();
        if (matchesSafari(zone, island)) {
            leftPendingSince = 0;
            if (!inside) {
                begin(zone.isEmpty() ? island : zone);
            }
            return;
        }
        if (!inside || (zone.isEmpty() && island.isEmpty())) {
            return;   // not on a trip, or simply nothing readable this moment
        }
        if (leftPendingSince == 0) {
            leftPendingSince = now;
        } else if (now - leftPendingSince >= LEAVE_CONFIRM_MS) {
            finish();
        }
    }

    /**
     * Whether the player is standing in the Safari <b>right now</b>, by the same test this tracker
     * bounds a trip with: the instance recognised exactly, the entrance refused, and the configured
     * word behind both as the rename fallback.
     *
     * <p>Public because it is the Safari gate for the whole island, not only for the trip summary:
     * {@link HidingCritterTracker} asks it too, so one setting covers both and a Hypixel
     * rename stays a one-field fix. Deliberately independent of {@link SBSConfig.SafariSettings#enabled}
     * - switching the trip summary off must not silently switch another feature's area gate off with
     * it.
     *
     * <p>Note what this does <i>not</i> share with {@link #onClientTick()}: an unreadable location
     * answers {@code false} here and never starts the leave-confirmation dance. A trip must survive
     * the blank sidebar around a warp; anything <i>drawn</i> must not, because a box that outlives the
     * instance it was found in is a stale highlight.
     */
    public static boolean inSafariArea() {
        return matchesSafari(SkyBlockLocation.zone(), SkyBlockLocation.island());
    }

    /**
     * Whether either half of the location says the player is in the Safari.
     *
     * <p><b>The instance is recognised exactly, and the entrance is refused before anything else is
     * asked.</b> "Critter Safari Entrance" is a zone of <i>Torrhus Canyon</i> and it contains the
     * Safari's whole name, so the configured-word test below answered yes while the player stood on
     * the canyon - and everything gated here draws in the Safari's own coordinate space, which on the
     * canyon is hundreds of blocks of unrelated terrain. Ordering the refusal first is the whole fix:
     * no later rule, configured or otherwise, can talk its way back in.
     *
     * <p><b>The configured word survives, and still runs last.</b> It is the reason a Hypixel rename
     * is a one-field fix rather than a release, and giving that up to fix the entrance would trade a
     * wrong place for no place at all. It cannot reach the entrance any more, because
     * {@link #ENTRANCE_ZONE} has already returned.
     */
    private static boolean matchesSafari(String zone, String island) {
        String spot = zone == null ? "" : zone.trim();
        String area = island == null ? "" : island.trim();
        if (ENTRANCE_ZONE.matcher(spot).find()) {
            return false;
        }
        if (SkyBlockLocation.isCritterSafari(spot, area)) {
            return true;
        }
        String needle = cfg().zoneName == null ? "" : cfg().zoneName.trim();
        if (needle.isEmpty()) {
            needle = DEFAULT_ZONE;
        }
        String lower = needle.toLowerCase(Locale.ROOT);
        return spot.toLowerCase(Locale.ROOT).contains(lower)
                || area.toLowerCase(Locale.ROOT).contains(lower);
    }

    /**
     * A zone naming the way <i>in</i> to an instance, which is by definition outside it.
     *
     * <p>Anchored to the end of the zone the way {@code IslandCatalog}'s Garden-plot pattern is
     * anchored to the start, and for the same reason: the loose version of this test is what caused
     * the bug. It is reached only from {@link #matchesSafari}, so it can never subtract from anything
     * but a Safari match - a Catacombs "Entrance" is not asked about here.
     */
    private static final Pattern ENTRANCE_ZONE = Pattern.compile("(?i)\\bentrance\\s*$");

    private void begin(String where) {
        inside = true;
        enteredAt = System.currentTimeMillis();
        leftPendingSince = 0;
        shards.clear();
        names.clear();
        colors.clear();
        lastShardName = "";
        lastShardAt = 0;
        lastShardAmount = 0;
        lastShardBoxed = false;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Safari] trip started in '{}'", where);
    }

    /**
     * Ends the trip and publishes its summary. A trip that caught nothing only reports when it was
     * long enough to have been a real run - walking in and straight back out is not worth a line.
     */
    private void finish() {
        long duration = System.currentTimeMillis() - enteredAt;
        List<Entry> entries = sortedEntries();
        int total = liveTotal();
        double value = 0;
        for (Entry entry : entries) {
            value += price(entry.id()) * entry.count();
        }
        inside = false;
        leftPendingSince = 0;
        shards.clear();
        names.clear();
        colors.clear();

        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Safari] trip over: {} shards in {} kinds after {}",
                total, entries.size(), duration(duration));

        if (entries.isEmpty()) {
            lastSummary = null;
            if (duration >= MIN_REPORT_MS && cfg().chatSummary) {
                SBSChat.send(Component.literal(" Safari: no shards this trip §7(" + duration(duration) + ")")
                        .withColor(0xE0A14D));
            }
            return;
        }
        lastSummary = new Summary(entries, total, duration, System.currentTimeMillis(), value);
        if (cfg().chatSummary) {
            announce(lastSummary);
        }
        if (cfg().sound) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
            }
        }
    }

    // ---- chat intake ----------------------------------------------------------------------------

    /**
     * Called for every chat line. Only ever books while a trip is running, so the same shard lines
     * elsewhere in SkyBlock - fishing one up, fusing one - never land in a Safari summary.
     */
    public void onChat(String text) {
        if (text == null || !inside || !cfg().enabled) {
            return;
        }
        String line = text.replaceAll("§.", "");
        if (PLAYER_CHAT.matcher(line).find()) {
            return;   // "Name: caught a Tide Shard lol" is a player talking, not a catch
        }
        if (book(SHARD_GAINED, line, text, false)
                || book(SHARD_SHARED, line, text, false)
                || book(SHARD_BOXED, line, text, true)) {
            return;
        }
        logUnmatched(line);
    }

    /**
     * Books one shard line, {@code false} when the pattern does not match.
     *
     * <p>A box line following its own catch line within {@value #SHARD_DEDUPE_MS} ms is a top-up, not
     * a second shard: the catch line says "a Tide Shard" while the box line carries the real total
     * once Hunter Fortune multiplied it, so only the shortfall is added. Two catch lines in a row are
     * always two catches - the pair rule deliberately only collapses lines of different kinds.
     */
    private boolean book(Pattern pattern, String line, String raw, boolean boxed) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return false;
        }
        String name = matcher.group(2).trim();
        if (name.isEmpty()) {
            return true;
        }
        int amount = parseCount(matcher.group(1));
        long now = System.currentTimeMillis();
        boolean topUp = boxed && !lastShardBoxed && name.equalsIgnoreCase(lastShardName)
                && now - lastShardAt < SHARD_DEDUPE_MS;
        if (topUp) {
            lastShardAt = now;
            if (amount > lastShardAmount) {
                add(name, amount - lastShardAmount, raw);
                lastShardAmount = amount;
            }
            return true;
        }
        lastShardName = name;
        lastShardAt = now;
        lastShardBoxed = boxed;
        lastShardAmount = amount;
        add(name, amount, raw);
        return true;
    }

    /** Adds a booked shard to the trip and to the all-time Safari drop log. */
    private void add(String name, int amount, String raw) {
        if (amount <= 0) {
            return;
        }
        String id = "SHARD_" + name.toUpperCase(Locale.ROOT).replace(' ', '_')
                .replaceAll("[^A-Z0-9_]", "");
        shards.merge(id, amount, Integer::sum);
        names.putIfAbsent(id, name + " Shard");
        colors.computeIfAbsent(id, key -> rarityColor(raw, name));
        TrackerStore.record("safaritracker", id, amount);
    }

    /**
     * The colour Hypixel wrote the shard name in - which is its rarity, and free information: the
     * line arrives with its colour codes still in it, so the last code before the name is the shard's
     * own. Falls back to 0 (the card's default text colour) when the raw line carries no codes.
     */
    private static int rarityColor(String raw, String name) {
        int at = raw.indexOf(name);
        if (at < 0) {
            return 0;
        }
        for (int i = at - 1; i > 0; i--) {
            if (raw.charAt(i - 1) == '§') {
                Integer color = CODE_COLORS.get(Character.toLowerCase(raw.charAt(i)));
                if (color != null) {
                    return 0xFF000000 | color;
                }
            }
        }
        return 0;
    }

    /**
     * Logs a shard-flavoured line none of the patterns understood, throttled. This is the raw
     * material for fixing them: what a brand-new zone calls a catch is exactly the thing that cannot
     * be verified from inside the client.
     */
    private void logUnmatched(String line) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS || line.length() > 140
                || !SHARD_ISH.matcher(line).find()) {
            return;
        }
        lastLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Safari] shard line not understood: '{}'", line);
    }

    private static int parseCount(String group) {
        if (group == null || group.isEmpty()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(group.replace(",", "")));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ---- output ---------------------------------------------------------------------------------

    /** The trip's shards, most-caught first and the more valuable of two equal counts above. */
    private List<Entry> sortedEntries() {
        List<Entry> entries = new ArrayList<>(shards.size());
        for (Map.Entry<String, Integer> entry : shards.entrySet()) {
            String id = entry.getKey();
            entries.add(new Entry(id, names.getOrDefault(id, id), entry.getValue(),
                    colors.getOrDefault(id, 0)));
        }
        entries.sort(Comparator.comparingInt(Entry::count).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /** The chat half of the summary: a header, one line per shard kind, and the total. */
    private void announce(Summary summary) {
        boolean values = cfg().showValues;
        SBSChat.send(Component.literal(" Safari trip: §f" + summary.totalShards() + " shard"
                        + (summary.totalShards() == 1 ? "" : "s") + " §7in "
                        + duration(summary.durationMs()))
                .withColor(0x8FD14D));
        int shown = Math.min(summary.entries().size(), MAX_CHAT_ROWS);
        for (int i = 0; i < shown; i++) {
            Entry entry = summary.entries().get(i);
            String row = " §7- §f" + entry.name() + " §7x" + entry.count();
            if (values) {
                double worth = price(entry.id()) * entry.count();
                if (worth > 0) {
                    row += " §e" + NumberDisplay.format(worth);
                }
            }
            SBSChat.send(Component.literal(row).withColor(SBSChat.WHITE));
        }
        if (summary.entries().size() > shown) {
            SBSChat.send(Component.literal(" §7  +" + (summary.entries().size() - shown)
                    + " more kinds").withColor(0x8FA9C8));
        }
        if (values && summary.value() > 0) {
            SBSChat.send(Component.literal(" §7Total value: §e" + NumberDisplay.format(summary.value()))
                    .withColor(0x8FA9C8));
        }
    }

    /**
     * Value of one shard: the Bazaar <b>instasell</b> price, falling back to the lowest BIN. The
     * instasell side for the same reason the fishing tracker uses it - it is what the shard is worth
     * to you right now, without waiting for a sell offer to fill.
     */
    public double price(String itemId) {
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(itemId);
        if (value != null && value.sell() > 0) {
            return value.sell();
        }
        Long lbin = sbs.modid.client.economy.prices.LbinCache.getInstance().getLbin(itemId);
        return lbin == null ? 0 : lbin;
    }

    /** "4m 12s" / "1h 3m" trip clock. */
    public static String duration(long millis) {
        long total = millis / 1000;
        if (total < 3600) {
            return (total / 60) + "m " + (total % 60) + "s";
        }
        return (total / 3600) + "h " + ((total % 3600) / 60) + "m";
    }
}
