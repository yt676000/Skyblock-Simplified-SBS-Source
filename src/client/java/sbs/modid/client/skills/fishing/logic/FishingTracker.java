/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.render.FishingAlert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows what this fishing session produced: caught sea creatures, loot items,
 * shards, and what all of it is worth.
 *
 * <p>Multiple sources, because Hypixel gives no "you looted X" event and fishing loot does not all
 * land in the same place, so it is booked from several channels:
 * <ul>
 *   <li><b>Sea creatures</b> count when you <b>catch</b> them: Hypixel sends the spawn message only
 *       to the fisher who hooked the creature, so that message is both the detection and the proof
 *       that the catch is yours – a busy pond never fills your list with the neighbours' creatures.
 *       A "Double Hook!" line right before it means the one message stands for two.</li>
 *   <li><b>Inventory items</b> come from diffing the player's inventory each tick – a stack that
 *       grew is a drop. Only gains that fishing can explain are booked: anything arriving right
 *       after a bite or a catch message (the reeled-in item, a quick kill's drops), plus recognised
 *       fishing-loot ids while the session is demonstrably still fishing. Gains while a container
 *       screen is open (that is moving items around, not fishing) and everything else the inventory
 *       picks up never become "profit". Equipment – rods, armor, minions, sacks
 *       ({@link FishingData#isNeverLoot}) – is refused on this channel entirely: it is carried and
 *       worn, never fished up, and the held rod or worn suit streaming back in after a lobby swap
 *       used to book as a catch. Announced equipment <b>drops</b> (Yeti Rod, Squid Boots) still
 *       count via their "RARE DROP!" chat line.</li>
 *   <li><b>Sack items</b> never touch the inventory – with sacks, most of a fishing session goes
 *       straight past the diff, which is why the tracker looked dead for sack owners. The
 *       "[Sacks] +N items" chat line carries the per-item breakdown in its <i>hover text</i>
 *       ("+24 Raw Fish (Fishing Sack)"); that hover is parsed here.</li>
 *   <li><b>Attribute shards</b> go straight to the Hunting Box, announced only in chat. The
 *       catch line books immediately – it is the only line guaranteed to accompany a fishing
 *       catch – and a "You sent N ... Shards to your Hunting Box." line naming the same shard
 *       shortly after tops the booking up to N, so Hunter Fortune extras count without any line
 *       counting twice. Priced via their Bazaar ids ({@code SHARD_*}).</li>
 *   <li><b>RNG drops</b> are announced in chat ("RARE DROP! ..."): booked from the line and
 *       <i>deducted</i> from the next inventory gain of the same item, so a drop that is also
 *       picked up is never counted twice. <b>Treasure item lines</b> ("GOOD CATCH! You
 *       found/caught ...") are recognised but never booked – the item always arrives physically
 *       (inventory or sack) and that channel books it once; treasure <b>coins</b> have no
 *       physical arrival and stay booked from their chat line.</li>
 *   <li><b>Loot share</b>: "LOOT SHARE You received loot for assisting ..." names no item; it opens
 *       a {@link LootShareWindow} in which the sack summary, the inventory and the shard lines book
 *       the share's loot, tagged for the HUD's "LS" marker.</li>
 *   <li><b>Baits</b> are the cost side, never loot: the inventory diff books a bait as used when
 *       its stack <b>actually shrinks</b> while fishing, and its Bazaar value is subtracted from
 *       the session profit. Counting "one per bite" instead would overstate the cost – bait-save
 *       effects (the Caster enchant, Stingy Sinker, the Spiked Atrocity talisman, shard buffs)
 *       regularly keep the bait on a cast, and a saved bait costs nothing. Only small decreases
 *       count (a cast consumes one); a whole stack vanishing is stashing or a lobby swap, never
 *       consumption. The SkyBlock Menu item is excluded from loot entirely – opening the menu
 *       re-hands it to the inventory, which is not a catch.</li>
 * </ul>
 *
 * <p>The inventory diff only counts <b>increases</b>: spending, selling or stashing an item must
 * never subtract from a session's catch, or the tracker would read as if the drop never happened.
 *
 * <p>Prices: Bazaar instasell where the item is bazaar-tradeable, otherwise the Auction House
 * lowest BIN – so sea creature drops like armor pieces and rare AH-only items are valued too.
 */
public final class FishingTracker {

    private static final Pattern COLOUR_CODE = Pattern.compile("§.");
    private static final Pattern NON_ID_CHARS = Pattern.compile("[^A-Z0-9_]");
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    private static final FishingTracker INSTANCE = new FishingTracker();

    /** How long after the last cast / catch recognised fishing-loot ids still count. */
    private static final long ACTIVITY_WINDOW_MS = 5 * 60_000L;

    /**
     * How long after the last cast / catch the tracker panels stay on screen once the rod is put
     * away. Separate from {@link #ACTIVITY_WINDOW_MS} on purpose: that one decides what counts as
     * fishing <i>loot</i>, this one decides when the panels stop being in the way, and the two must
     * be free to move apart. The session itself is untouched either way – the panels come back with
     * every number intact on the next cast; only the drawing stops.
     */
    private static final long IDLE_HIDE_MS = 5 * 60_000L;

    /**
     * How long after a bite or catch message <b>any</b> gained item counts as fishing loot. Long
     * enough to reel in and walk over a quick kill's drops, short enough that whatever else the
     * inventory picks up between casts stays out of the profit.
     */
    private static final long LOOT_PICKUP_WINDOW_MS = 10_000L;

    /** How long after a "Double Hook!" line the following catch still counts as the doubled one. */
    private static final long DOUBLE_HOOK_WINDOW_MS = 5_000L;

    /**
     * How long a channel's recent booking stays available for cross-channel dedup. Generous, because
     * the "[Sacks]" summary can lag the individual rare-drop line by many seconds - one physical drop
     * reported by both must still collapse to one. Same-channel repeats are NEVER deduped, so a long
     * window can only ever cancel a genuine duplicate report, never a real double.
     */
    private static final long LEDGER_TTL_MS = 30_000L;

    /**
     * Cross-channel TTL specifically for the INVENTORY↔SACK pair. The "[Sacks]" summary is
     * AGGREGATED by a user-chosen interval and can lag the physical pickup by minutes – far beyond
     * the 30s general ledger – which re-booked a flexbone that had already been counted from the
     * inventory (the "still counts double" bug). A real double (two flexbones, one to inventory and
     * one to sack, minutes apart) is rare enough that the long window is the right trade.
     */
    private static final long SACK_INV_TTL_MS = 5 * 60_000L;

    /**
     * TTL for the inventory-REMOVAL ledger: an item that leaves the inventory shortly before a
     * "[Sacks] +N" line of the same item is a TRANSFER into the sack (auto-absorb or a manual
     * stash), not new loot – the sack line must not re-book it.
     */
    private static final long REMOVAL_TTL_MS = 90_000L;

    /**
     * Ticks the open Fishing Bag's content must stay non-empty and unchanged before it is believed.
     * The server streams a container's content in AFTER the screen itself opens – on the open tick
     * every slot is still air, and Hypixel fills its menus progressively over several ticks – so
     * reading (or worse, reconciling against) the bag too early read the whole not-yet-arrived
     * reserve as "consumed" and booked thousands of phantom baits into the session cost.
     */
    private static final int BAG_SETTLE_TICKS = 4;

    /**
     * After (re)joining a world - which every Hypixel lobby / island swap is - the server streams
     * the inventory back in over several ticks. For this long the diff only rebuilds its baseline
     * and books nothing, so the returning items never read as one giant catch.
     */
    private static final long JOIN_SETTLE_MS = 4_000L;

    /** How long a purchase line keeps suppressing the matching inventory gain (bought item lands late). */
    private static final long PURCHASE_TTL_MS = 5 * 60_000L;

    /**
     * Largest per-tick bait-stack decrease still booked as consumption. A cast consumes at most one
     * bait (the allowance covers a laggy sync batching a few casts); anything larger is the player
     * stashing or dropping the stack, which is never a cost.
     */
    private static final int MAX_BAIT_CONSUMED_PER_TICK = 4;

    /**
     * The "Baits Remaining: N" lore line Hypixel writes on the rod's held-bait display (hotbar slot 9
     * while a fishing rod is held). Tolerant of colour codes (stripped before matching), an optional
     * colon and thousands separators, so a reworded "Baits Remaining 1,234" still parses.
     */
    private static final Pattern BAITS_REMAINING = Pattern.compile(
            "Baits?\\s+Remaining:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    /** How many cast edges of a session are logged one by one before the log thins out. */
    private static final int CAST_LOG_FIRST = 5;
    /** After {@link #CAST_LOG_FIRST}, every this-many-th cast edge is logged. */
    private static final int CAST_LOG_EVERY = 50;

    // ------------------------------------------------------------------
    // Chat loot patterns (matched on plain text, colour codes already stripped).
    // The shapes match the Hypixel lines they parse. Shard lines live in ShardLines.
    // ------------------------------------------------------------------

    /**
     * An item-stash withdrawal ("/pickupstash"): the items land in the inventory like a pickup, but
     * they are old items, never a drop. Wording NOT verified in game (no stash line in any captured
     * log), so the match is a tolerant "stash" phrase and only ever suppresses, never books.
     */
    private static final Pattern STASH_PICKUP = Pattern.compile(
            "(?i)(?:picked up|from your) .*stash");

    /** How long a stash line keeps inventory gains from counting. */
    private static final long STASH_SETTLE_MS = 3_000L;

    /** "GOOD CATCH! You found a Fish Bait!" / "GOOD CATCH! You caught a Flexbone!" (also GREAT /
     *  OUTSTANDING). Recognition only – the physical arrival (inventory or sack) books the item. */
    private static final Pattern TREASURE_ITEM = Pattern.compile(
            "(?:GOOD|GREAT|OUTSTANDING) CATCH! You (?:found|caught) (?:an? )?(?:(\\d+)x )?(.+?)!?$");
    /** "⛃ GOOD CATCH! You caught 1,542 Coins!" – treasure coins are profit too, and having no
     *  physical arrival they are the one treasure that books from its chat line. */
    private static final Pattern TREASURE_COINS = Pattern.compile(
            "CATCH! You (?:caught|found) ([\\d,]+) Coins!");

    /** One hover line of the "[Sacks]" message: "+24 Raw Fish (Fishing Sack)". */
    private static final Pattern SACK_LINE = Pattern.compile(
            "([+-][\\d,.]+) (.+?) \\((.+)\\)");

    /**
     * A player-chat line: an optional guild/party channel + optional [rank] tags, a 2-16 char IGN,
     * then "<something>: ". Sea-creature spawn lines never carry a "name: " sender, so matching this
     * lets a player named after a creature ("Nessie", "Squid") not trip the catch detector.
     */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Guild|Party|Co-op|Officer|G|P|O) > )?(?:\\[[^\\]]+\\] )*[A-Za-z0-9_]{2,16}[^:]{0,4}: ");

    /**
     * Purchase lines: a bought item lands in the inventory the same as a fished drop, so without this
     * a Bazaar / Auction House / NPC buy would count as fishing profit. Each binds (count?, name).
     */
    private static final Pattern[] PURCHASE_LINES = {
            Pattern.compile("\\[Bazaar] Bought (?:([\\d,]+)x )?(.+?) for [\\d,.]+ coins!"),
            Pattern.compile("\\[Bazaar] Claimed (?:([\\d,]+)x )?(.+?) worth [\\d,.]+ coins"),
            Pattern.compile("You purchased (?:([\\d,]+)x )?(.+?) for [\\d,.]+ coins!"),
            Pattern.compile("You bought (?:([\\d,]+)x )?(.+?) for [\\d,.]+ coins!"),
            // Auction House: the item is CLAIMED from the "manage auctions" menu, often minutes
            // after the purchase line - a Water Hydra Head bought on AH arrived this way and counted.
            Pattern.compile("You claimed (.+?) from .+?(?:auction|Auction)"),
            Pattern.compile("You (?:bought|purchased) (.+?)!"),
    };

    /** Pseudo item id for treasure coins; {@link #price} values it at 1 coin apiece. */
    private static final String COIN_ID = "SKYBLOCK_COIN";

    /** When the last "Double Hook!" line arrived, or 0 once its catch has claimed it. */
    private long doubleHookAt;

    /** Bumped on every change to what the HUD shows - its rebuild key, compared for equality only. */
    private volatile int revision;

    /** Rare sea-creature drops, item id -> count, booked only behind an eligible kill. */
    private final Map<String, Integer> rareDrops = new LinkedHashMap<>();
    /** Rare-drop item id -> the creature the latest booking was attributed to (for the HUD). */
    private final Map<String, String> rareDropSource = new HashMap<>();

    /** Times Fished, the session clock and the Reel Timer - everything the bobber edge drives. */
    private final CastSession castSession = new CastSession();
    /** Shard chat lines and their once-only rule. */
    private final ShardLines shardLines = new ShardLines();
    /** When the latest "LOOT SHARE You received loot for assisting" line arrived. */
    private final LootShareWindow lootShare = new LootShareWindow();
    /** Item id -> how many of its booked count arrived as loot-share loot (the HUD's "LS" marker). */
    private final Map<String, Integer> lootShareCounts = new HashMap<>();
    /** Whether onTick has run since the last time it was found idle - for the "tick running" log. */
    private boolean tickRunningLogged;
    /** Optional per-tick readers switched off after a failure, so the cast counters keep running. */
    // A constant, not PART_NAMES.length: INSTANCE is built before the later static arrays exist.
    private final boolean[] partOff = new boolean[PART_COUNT];
    private static final int PART_COUNT = 5;
    private static final String[] PART_NAMES =
            {"bite marker", "rod bait display", "bait sack", "sea creature deaths", "golden fish"};
    private static final int PART_BITE = 0;
    private static final int PART_BAIT_DISPLAY = 1;
    private static final int PART_BAIT_SACK = 2;
    private static final int PART_DEATHS = 3;
    private static final int PART_GOLDEN = 4;
    /** Whether a cast's fluid contact has been logged once this session ([SBS][Fishing] reel). */
    private boolean landLogged;
    private boolean noLandLogged;
    /** Throttle on the unexplained-pickup log. */
    private long lastUnexplainedLogAt;

    /** Until this moment inventory gains are a stash withdrawal, never loot. */
    private long stashSettleUntil;

    /**
     * The three channels a physical drop can reach the tracker through. The same drop can be
     * reported by more than one - a rare-drop chat line AND the "[Sacks]" summary for the catch that
     * went into the sack; a rare-drop line AND the inventory pickup - and must then be counted ONCE.
     * A repeat from the SAME channel (two rare-drop lines, inventory +2, sack +2) is a genuine double
     * and is kept. {@link #bookLoot} enforces exactly this: it deducts against the OTHER channels'
     * recent bookings, never its own.
     */
    private enum Channel { CHAT, INVENTORY, SACK }

    /** Per-channel recent bookings (id -> count, id -> time), for cross-channel dedup. */
    private final Map<Channel, Map<String, Integer>> recentByChannel = new java.util.EnumMap<>(Channel.class);
    private final Map<Channel, Map<String, Long>> recentAtByChannel = new java.util.EnumMap<>(Channel.class);

    /** Bought items (Bazaar / AH / NPC) still waiting to cancel the matching inventory gain. */
    private final Map<String, Integer> pendingPurchase = new HashMap<>();
    private final Map<String, Long> pendingPurchaseAt = new HashMap<>();

    /** Items that recently LEFT the inventory – a following sack "+N" of the same id is a transfer. */
    private final Map<String, Integer> recentRemoved = new HashMap<>();
    private final Map<String, Long> recentRemovedAt = new HashMap<>();

    /** Until this moment the inventory diff only rebuilds its baseline (post-join settle). */
    private long settleUntil;

    /**
     * How long after a container screen CLOSES gains still count as menu movement, not loot.
     * Hypixel finishes menu-driven item moves (wardrobe / equipment / loadout swaps, storage) a
     * beat after the screen is already gone, so the container-open guard alone missed them – a
     * loadout swap's returning Wither Goggles landed post-close inside the just-fished window and
     * booked as a 7m "catch". Chat-announced drops are unaffected (their channel is not gated).
     */
    private static final long CONTAINER_SETTLE_MS = 2_000L;

    /** Whether the previous tick had a container screen open (close-edge detection). */
    private boolean containerWasOpen;

    /** Until this moment gains are still the just-closed menu settling, never loot. */
    private long containerClosedSettleUntil;

    /** Sea creature name -> how many YOU caught this session. */
    private final Map<String, Integer> creatures = new LinkedHashMap<>();
    /** Item id -> how many were gained this session. */
    private final Map<String, Integer> loot = new LinkedHashMap<>();
    /** Bait id -> how many catches consumed one – the cost the profit subtracts, one per bite. */
    private final Map<String, Integer> baitsUsed = new LinkedHashMap<>();
    /**
     * Bait id -> how many the last-seen Bait Sack (or old Fishing Bag) held. Baits kept in the bag never touch the
     * inventory, so the inventory scan alone reads them as zero; refreshed by {@link #captureBaitSack}
     * whenever the Bait Sack menu is open, and added to the loose baits by {@link #remainingBaits()}.
     * Kept across lobby swaps like the storage index, since the bag is account-wide and rarely open.
     */
    private final Map<String, Integer> baitSackReserve = new LinkedHashMap<>();
    /** The bait most recently consumed – the "bait you're currently using" for the counter HUD. */
    private String lastConsumedBaitId;
    /**
     * Bait id -> casts booked as ESTIMATED consumption since the Fishing Bag was last seen. Bag-fed
     * baits are consumed server-side with no client-visible change at all, so each cast books one
     * bait on credit – the HUD chip and the session cost move immediately – and the next look into
     * the bag replaces the guesses with the bag's real counts ({@link #reconcileBaitConsumption}).
     */
    private final Map<String, Integer> pendingBaitEstimate = new LinkedHashMap<>();

    /**
     * The rod's slot-9 bait display ({@link #readBaitDisplay}) – Hypixel's own "Baits Remaining: N"
     * readout, which is the AUTHORITY on how many of the loaded bait are left (it already sums the
     * inventory and the Fishing Bag). {@code displayBaitsRemaining} is -1 when no display is visible.
     * Read from the render thread by the HUD, so kept volatile.
     */
    private volatile int displayBaitsRemaining = -1;
    private volatile String displayBaitId;
    /** Whether a slot-9 bait display was read THIS tick – while true it is the sole bait source. */
    private volatile boolean baitDisplayActive;
    /**
     * Whether a bait display has been seen at all this session. Once true the display is authoritative
     * and the estimate paths (per-cast credit, inventory-diff shrink, bag reconcile) stop booking, so
     * consumption is never counted twice. You can only consume bait while holding the rod – which is
     * exactly when the display shows – so nothing real is lost while it is briefly absent.
     */
    private boolean baitDisplaySeen;
    /** Previous tick's display reading, for the between-tick drop that is the real consumption. */
    private int lastDisplayRemaining = -1;
    private String lastDisplayBaitId;
    /** Throttle for the {@code [SBS][Fishing] bait display} tuning log. */
    private long lastBaitDisplayLogAt;

    /** Whether the Fishing Bag screen was open last tick – a closed->open edge arms a reconcile. */
    private boolean baitBagWasOpen;
    /** Whether THIS opening of the bag has reconciled yet – armed on the open edge, done once the
     *  content settles; from then on the open bag is mirrored live, tick by tick. */
    private boolean baitBagReconciled;
    /** Full content of the open bag last tick (every item, not just baits) – the settle check:
     *  only a non-empty content that stops changing is the real, fully-streamed-in bag. */
    private Map<String, Integer> lastBagContent;
    /** Consecutive ticks the open bag's content has been non-empty and unchanged. */
    private int bagStableTicks;
    /** Set by {@link #reset()}: the next bag look only re-baselines, it must not book the old
     *  session's consumption into the fresh one. */
    private boolean skipNextBaitReconcile;
    /** The container screen whose title was last classified, so the title is checked once per screen. */
    private net.minecraft.client.gui.screens.Screen lastBaitScreen;
    private String lastBaitTitle = "";
    /** Whether {@link #lastBaitScreen} is the bait store ({@link BaitSackReader#isBaitStoreTitle}). */
    private boolean lastBaitTitleIsStore;
    /** Whether {@link #lastBaitScreen} is one the {@link BaitSackProbe} logs. */
    private boolean lastBaitTitleProbed;
    private final BaitSackProbe baitSackProbe = new BaitSackProbe();
    /**
     * When a bait was last seen being consumed (rod display, loose stack shrink, sack reconcile). An
     * observation newer than the favourite's read that names another bait means the favourite is no
     * longer what is being used (unfavourited in between) - observations always beat the rule.
     */
    private long lastObservedBaitAt;
    /** The bait the last cast booked on credit, and when - a loose shrink right after takes it back. */
    private String lastEstimateBait;
    private long lastEstimateAt;
    /** How long after a cast's estimate a loose-stack shrink is still that same cast's bait. */
    private static final long ESTIMATE_UNDO_MS = 3_000L;

    /** Last seen count per item id, the baseline the diff works against. */
    private Map<String, Integer> lastCounts = new HashMap<>();
    /** The other half of the double buffer {@link #countInventory} fills, so a diff allocates no map. */
    private Map<String, Integer> scratchCounts = new HashMap<>();
    private boolean baselineReady;
    /** {@link sbs.modid.client.core.tick.InventoryChanges#revision} the baseline was read at. */
    private int countedRevision = -1;
    /** When {@link #onTick} last ran; a gap means the gate skipped ticks and the baseline is stale. */
    private long lastTickAt;
    /** Longer than this between ticks and the next tick rebases instead of diffing. */
    private static final long TICK_GAP_REBASE_MS = 1_000L;

    /** Last moment the player demonstrably fished: cast, bite, creature caught. A bobber merely
     *  sitting in the water is deliberately NOT activity – it must not hold the session clock's
     *  window open while the player waits afk. The session clock keeps its own events
     *  ({@link CastSession}). */
    private volatile long lastActivityAt;

    /** Last bite or catch message – the moments fishing actually hands the player an item. */
    private volatile long lastLootEventAt;

    private FishingTracker() {
        for (Channel channel : Channel.values()) {
            recentByChannel.put(channel, new HashMap<>());
            recentAtByChannel.put(channel, new HashMap<>());
        }
    }

    public static FishingTracker getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().fishing.enabled;
    }

    // ------------------------------------------------------------------
    // Intake
    // ------------------------------------------------------------------

    /**
     * Called for every chat line. Sea creature catches, attribute shards, RNG drops and the sack
     * breakdown all arrive here; the structured lines are tried first because the creature
     * detection is a loose contains-match that lines like "You caught a Thunder Shard!" would
     * otherwise trip.
     *
     * @param message   the plain text (colour codes already stripped)
     * @param component the full component, for the sack message whose items hide in the hover text
     */
    public void onChat(String message, Component component) {
        if (!enabled()) {
            return;
        }
        GoldenFishTracker.getInstance().onChat(message);
        // Checked first: the double hook line announces no creature of its own, it doubles the
        // catch that follows it.
        if (FishingData.isDoubleHook(message)) {
            lastActivityAt = System.currentTimeMillis();
            doubleHookAt = lastActivityAt;
            castSession.onActivity(lastActivityAt, pauseMs());
            return;
        }
        if (LootShareWindow.isLootShareLine(message)) {
            onLootShareLine();
            return;
        }
        if (component != null && message.startsWith("[Sacks]")) {
            onSackMessage(message, component);
            return;
        }
        // Purchases are booked as suppression only (never loot): the bought item lands in the
        // inventory just like a drop, and would otherwise count as profit.
        if (onPurchaseLine(message)) {
            return;
        }
        if (STASH_PICKUP.matcher(message).find()) {
            stashSettleUntil = System.currentTimeMillis() + STASH_SETTLE_MS;
            return;
        }
        if (onShardLine(message) || onDropLine(message)) {
            return;
        }
        // Sea-creature spawn lines are SERVER flavour text, never a player message - so a player
        // named after a creature ("Nessie: gg") must not book a catch. Player chat carries a
        // "<name>: " sender; spawn lines never do.
        if (isPlayerChat(message)) {
            return;
        }
        String creature = FishingData.seaCreatureFor(message);
        if (creature != null) {
            lastActivityAt = System.currentTimeMillis();
            lastLootEventAt = lastActivityAt;
            castSession.onActivity(lastActivityAt, pauseMs());
            creatures.merge(creature, consumeDoubleHook() ? 2 : 1, Integer::sum);
            revision++;
            FishingAlert.getInstance().trigger(creature);
        }
    }

    // ------------------------------------------------------------------
    // Chat loot intake
    // ------------------------------------------------------------------

    /**
     * Books an attribute shard line, or returns false when {@code message} is none. Shards go
     * straight to the Hunting Box, so chat is the only place they can be counted at all.
     *
     * <p>The catch line books immediately: it is the only line guaranteed to arrive the moment a
     * shard is fished – the Hunting Box transfer line can lag by seconds or, for some sources,
     * never come at all, and waiting for it made the tracker miss most shards. When the box line
     * DOES follow within the window it is the authority on the amount: with Hunter Fortune the
     * catch line stays "You caught a Tide Shard!" while "You sent 2 Tide Shards to your Hunting
     * Box." carries the real total – so the pair-dedup books exactly the shortfall on top, and
     * the tracker ends up showing what actually landed in the box.
     */
    private boolean onShardLine(String message) {
        long now = System.currentTimeMillis();
        // A shard charmed on land is hunting, not fishing - recognised but not booked. Inside a
        // loot share's window the shard is that share's loot, fishing recently or not.
        boolean inWindow = lootShare.covers(now);
        // The shard variant of the loot share line is itself loot-share loot (booked only while
        // fishing, like any shard line outside a window).
        boolean sharedLoot = inWindow || message.startsWith("LOOT SHARE");
        ShardLines.Booking booking = shardLines.onLine(message, now, inWindow || recentFishingActivity());
        if (booking == null) {
            return false;
        }
        if (booking.amount() > 0) {
            bookLoot("SHARD_" + normalizeId(booking.shardName()), booking.amount(), Channel.CHAT, sharedLoot);
        }
        return true;
    }

    /**
     * "LOOT SHARE You received loot for assisting &lt;name&gt;!": the loot follows through the sack
     * summary, the inventory or a Hunting Box line, and {@link LootShareWindow} lets those book it.
     * Only while fishing - a loot share at a slayer boss is not this tracker's - which is the recent
     * activity window or a rod in the main hand.
     */
    private void onLootShareLine() {
        Player player = Minecraft.getInstance().player;
        boolean rodInHand = player != null
                && player.getMainHandItem().getItem() == net.minecraft.world.item.Items.FISHING_ROD;
        if (!recentFishingActivity() && !rodInHand) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] loot share while not fishing - not booked to the fishing session");
            return;
        }
        long now = System.currentTimeMillis();
        lootShare.open(now);
        // Activity, so the session clock runs - but not a loot event: the own-catch window would let
        // any gain through, where a loot share's window only takes sea-creature drops.
        lastActivityAt = now;
        castSession.onActivity(now, pauseMs());
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Fishing] loot share - drops for the next {}s count as loot-share loot",
                LootShareWindow.WINDOW_MS / 1000);
    }

    /** Books an RNG drop / treasure line, or returns false when {@code message} is none. */
    private boolean onDropLine(String message) {
        Matcher coins = TREASURE_COINS.matcher(message);
        if (coins.find()) {
            if (recentFishingActivity()) {
                bookLoot(COIN_ID, parseCount(coins.group(1)), Channel.CHAT);
            }
            return true;
        }
        // Treasure ITEMS are recognition only: the item always arrives physically (inventory
        // pickup or sack summary) and that channel books the one real item. Booking the
        // announcement too is how "GOOD CATCH! You caught a Flexbone!" counted 2 - once from
        // this line, once from the pickup - whenever the two resolved to different ids or the
        // sack summary outlived the cross-channel dedup window.
        if (TREASURE_ITEM.matcher(message).find()) {
            return true;
        }
        // "RARE DROP! Squid Boots (+4% Magic Find)" and the rest of the family: one shared parser.
        sbs.modid.client.core.util.RareDropLine.Drop drop =
                sbs.modid.client.core.util.RareDropLine.parse(message);
        if (drop == null) {
            return false;
        }
        if (recentFishingActivity()) {
            String itemId = resolveItemId(drop.item());
            if (itemId != null) {
                bookLoot(itemId, drop.count(), Channel.CHAT);
            }
        }
        return true;
    }

    /**
     * Books the per-item breakdown of a "[Sacks]" message. The visible line only says
     * "+227 items" – the actual items sit in the hover text, one "+24 Raw Fish (Fishing Sack)"
     * line each.
     *
     * <p>A summary whose period ("Last Ns") reaches back to a loot share holds that share's loot:
     * every gained item in it books, tagged as loot share, whatever its id. The summary cannot say
     * which of its items came from the share and which from the player's own catches in the same
     * period, so the tag means "includes loot-share loot".
     */
    private void onSackMessage(String message, Component component) {
        if (!recentFishingActivity()) {
            return;   // sacks also fill while farming or mining - not this tracker's business
        }
        StringBuilder hover = new StringBuilder();
        collectHoverText(component, hover);
        boolean justFished = justFished();
        long now = System.currentTimeMillis();
        boolean sharedLoot = lootShare.coversSack(now, LootShareWindow.sackPeriodSeconds(message));
        // ONE amount per (item, sack) within a single message, collapsed by MAX: the hover can sit
        // on more than one styled segment of the chat line, so collectHoverText may deliver the same
        // breakdown twice - summing would book every sacked item double (the flexbone x2 bug).
        // Hypixel itself aggregates one line per item+sack, so max loses nothing real.
        Map<String, Integer> perItem = new LinkedHashMap<>();
        Map<String, String> sackOf = new HashMap<>();
        for (String line : hover.toString().split("\n")) {
            baitSackProbe.onSackLine(line);   // does sack-fed bait consumption show up here at all?
            Matcher matcher = SACK_LINE.matcher(COLOUR_CODE.matcher(line).replaceAll(""));
            if (!matcher.find() || matcher.group(1).startsWith("-")) {
                continue;   // removals are spending, and spending never lowers a session's catch
            }
            String itemId = resolveItemId(matcher.group(2));
            if (itemId == null || FishingData.isNeverLoot(itemId)) {
                continue;   // a bait stack stashed INTO a sack is not a catch
            }
            String key = itemId + "|" + matcher.group(3);
            perItem.merge(key, parseCount(matcher.group(1)), Math::max);
            sackOf.put(key, matcher.group(3));
        }
        for (Map.Entry<String, Integer> entry : perItem.entrySet()) {
            String itemId = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            int amount = entry.getValue();
            // A recent inventory REMOVAL of the same item means this "+N" is (partly) a transfer
            // into the sack - the item was already counted when it entered the inventory.
            int transferred = consumeLedger(recentRemoved, recentRemovedAt, itemId, amount,
                    REMOVAL_TTL_MS, now);
            amount -= transferred;
            if (transferred > 0 && itemId.contains("FLEXBONE")) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Fishing] {} sack +{} suppressed as transfer ({} left)",
                        itemId, transferred, amount);
            }
            if (amount <= 0) {
                continue;
            }
            // Same two-tier rule as the inventory diff: right after a bite/catch everything is
            // fishing's doing; otherwise the item must look like fishing loot (or sit in a
            // fishing sack), so a farming sack filling mid-session stays out.
            boolean fishingSack = sackOf.get(entry.getKey()).toLowerCase(Locale.ROOT).contains("fishing");
            if (sharedLoot) {
                bookLoot(itemId, amount, Channel.SACK, true);
            } else if (justFished || fishingSack || FishingData.isFishingLoot(itemId)) {
                bookLoot(itemId, amount, Channel.SACK);
            }
        }
    }

    /** Every hover ShowText in the component tree, one line per component, appended to {@code out}. */
    private static void collectHoverText(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }

    /**
     * Books a Bazaar / Auction House / NPC purchase for suppression (never as loot), so the bought
     * item that is about to land in the inventory is cancelled out of the profit. Returns false when
     * {@code message} is not a purchase line.
     */
    private boolean onPurchaseLine(String message) {
        for (Pattern pattern : PURCHASE_LINES) {
            Matcher matcher = pattern.matcher(message);
            if (!matcher.find()) {
                continue;
            }
            // The two-group patterns bind (count, name); the one-group AH patterns bind just (name).
            String name = matcher.groupCount() >= 2 ? matcher.group(2) : matcher.group(1);
            int count = matcher.groupCount() >= 2 ? parseCount(matcher.group(1)) : 1;
            String itemId = resolveItemId(name);
            if (itemId != null) {
                suppressPurchase(itemId, count);
            }
            return true;
        }
        return false;
    }

    /**
     * Cancels a bought item BOTH ways: it un-books any of it the inventory diff already counted (the
     * item arrived before its purchase line), and suppresses what is still to arrive (an AH item is
     * claimed minutes after the purchase line - a Water Hydra Head bought on AH was counting).
     */
    private void suppressPurchase(String itemId, int count) {
        long now = System.currentTimeMillis();
        int booked = consumeLedger(recentByChannel.get(Channel.INVENTORY),
                recentAtByChannel.get(Channel.INVENTORY), itemId, count, PURCHASE_TTL_MS, now);
        if (booked > 0) {
            int left = Math.max(0, loot.getOrDefault(itemId, 0) - booked);
            if (left == 0) {
                loot.remove(itemId);
            } else {
                loot.put(itemId, left);
            }
        }
        if (booked > 0) {
            revision++;
        }
        int remaining = count - booked;
        if (remaining > 0) {
            pendingPurchase.merge(itemId, remaining, Integer::sum);
            pendingPurchaseAt.put(itemId, now);
        }
    }

    /**
     * Books {@code count} of {@code itemId} to the session on a given {@code channel}, deduping
     * ACROSS channels: any of this drop already reported by a DIFFERENT channel is cancelled (one
     * physical drop, reported twice), and only the remainder is booked and remembered under this
     * channel. A repeat on the SAME channel is never touched, so real doubles stay doubled.
     */
    private void bookLoot(String itemId, int count, Channel channel) {
        bookLoot(itemId, count, channel, false);
    }

    /** {@link #bookLoot(String, int, Channel)}, remembering a loot-share booking for the HUD's marker. */
    private void bookLoot(String itemId, int count, Channel channel, boolean sharedLoot) {
        if (itemId == null || itemId.isEmpty() || count <= 0) {
            return;
        }
        // Trophy fish are their own collection, never sold – the profit tracker ignores them whatever
        // channel they arrive on (inventory diff, sack breakdown, RNG-drop line).
        if (FishingData.isTrophyFish(itemId)) {
            return;
        }
        long now = System.currentTimeMillis();
        int net = count;
        for (Channel other : Channel.values()) {
            if (other != channel && net > 0) {
                // The sack summary is user-aggregated and can lag the inventory pickup by minutes,
                // so the INVENTORY<->SACK pair dedups over a much longer window than chat does.
                boolean sackInvPair = (channel == Channel.SACK && other == Channel.INVENTORY)
                        || (channel == Channel.INVENTORY && other == Channel.SACK);
                net -= consumeLedger(recentByChannel.get(other), recentAtByChannel.get(other),
                        itemId, net, sackInvPair ? SACK_INV_TTL_MS : LEDGER_TTL_MS, now);
            }
        }
        if (itemId.contains("FLEXBONE")) {
            // Deliberate targeted diagnostic for the long-lived "flexbone counts double" report:
            // one line per booking shows exactly which channel booked how much.
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] {} book request {} via {} -> net {}", itemId, count, channel, net);
        }
        if (net <= 0) {
            return;   // every unit was already booked by another channel - same physical drop
        }
        if (sbs.modid.client.skills.fishing.model.SeaCreatureDropTable.isRareDrop(itemId)) {
            // A creature-specific rare drop counts only behind a sea creature that can drop it and
            // died next to the player inside the window - whichever channel reported it.
            String creature = SeaCreatureDrops.getInstance().attribute(itemId, now);
            if (creature == null) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Fishing][Drop] {} x{} via {} not booked: no eligible sea creature died "
                                + "within {}s", itemId, net, channel, SeaCreatureDrops.windowMs() / 1000);
                return;
            }
            rareDrops.merge(itemId, net, Integer::sum);
            rareDropSource.put(itemId, creature);
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing][Drop] {} x{} via {} booked as a {} drop", itemId, net, channel, creature);
        } else {
            loot.merge(itemId, net, Integer::sum);
        }
        if (sharedLoot) {
            lootShareCounts.merge(itemId, net, Integer::sum);
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] loot share: {} x{} via {} booked", itemId, net, channel);
        }
        revision++;
        recentByChannel.get(channel).merge(itemId, net, Integer::sum);
        recentAtByChannel.get(channel).put(itemId, now);
        // Lifetime drop log: config/sbs/tracker/fishingtracker.txt.
        sbs.modid.client.core.tracker.TrackerStore.record("fishingtracker", itemId, net);
    }

    /**
     * Deducts up to {@code amount} of {@code itemId} from a timestamped ledger, consuming what it
     * returns and dropping the entry once emptied or stale. The one primitive behind every dedup:
     * cross-channel loot and purchase-vs-inventory.
     */
    private static int consumeLedger(Map<String, Integer> ledger, Map<String, Long> at,
                                     String itemId, int amount, long ttl, long now) {
        Long bookedAt = at.get(itemId);
        if (bookedAt == null) {
            return 0;
        }
        if (now - bookedAt > ttl) {
            at.remove(itemId);
            ledger.remove(itemId);
            return 0;
        }
        int have = ledger.getOrDefault(itemId, 0);
        int used = Math.min(have, amount);
        if (used >= have) {
            at.remove(itemId);
            ledger.remove(itemId);
        } else {
            ledger.put(itemId, have - used);
        }
        return used;
    }

    /**
     * The SkyBlock id behind a chat display name: the official item catalogue first (exact, then
     * reforge/level-stripped), a mechanical UPPER_SNAKE guess as the last resort – a guessed id
     * still renders fine and often even prices, an unresolved one would book nothing.
     */
    private static String resolveItemId(String displayName) {
        if (displayName == null) {
            return null;
        }
        String name = COLOUR_CODE.matcher(displayName).replaceAll("").trim();
        if (name.isEmpty()) {
            return null;
        }
        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        SkyBlockItemCatalog.Entry entry = catalog.byName(name);
        if (entry == null) {
            entry = catalog.byNormalizedName(name);
        }
        if (entry != null) {
            return entry.id;
        }
        String constructed = normalizeId(name);
        return constructed.isEmpty() ? null : constructed;
    }

    /** Whether {@code message} is a player chat line (has a "name: " sender), not server flavour. */
    private static boolean isPlayerChat(String message) {
        return message != null && PLAYER_CHAT.matcher(message).find();
    }

    /** "Abyssal Lanternfish" -> "ABYSSAL_LANTERNFISH". */
    private static String normalizeId(String name) {
        return NON_ID_CHARS.matcher(name.toUpperCase(Locale.ROOT).replace(' ', '_')).replaceAll("");
    }

    /** Removes {@code §x} colour codes without the per-call regex compile of String.replaceAll –
     *  used on the per-tick paths (the rod bait display lore). */
    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7 && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** "1,542" -> 1542; a missing/broken group counts as 1 (the lines omit "x1"). */
    private static int parseCount(String group) {
        if (group == null) {
            return 1;
        }
        try {
            return Integer.parseInt(NON_DIGITS.matcher(group).replaceAll(""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Whether this catch belongs to a double hook, clearing the flag either way.
     *
     * <p>The window is short because the double hook line lands immediately before its catch: a
     * stale flag would otherwise double an unrelated catch minutes later.
     */
    private boolean consumeDoubleHook() {
        boolean doubled = doubleHookAt > 0
                && System.currentTimeMillis() - doubleHookAt < DOUBLE_HOOK_WINDOW_MS;
        doubleHookAt = 0;
        return doubled;
    }

    /**
     * Called by {@link BiteIndicator} on each new bite. Opens the loot window and refines the Reel
     * Timer; Times Fished counts casts ({@link CastSession}), so it never depends on this marker.
     */
    public void onBite() {
        if (!enabled()) {
            return;
        }
        revision++;
        lastActivityAt = System.currentTimeMillis();
        lastLootEventAt = lastActivityAt;
        castSession.onBite(lastActivityAt, pauseMs());
        // Bait cost is NOT booked here: bait-save effects (the Caster enchant, Stingy Sinker, the
        // Spiked Atrocity talisman, shard buffs) keep the bait on many casts, so "one per bite"
        // would overstate the cost. The inventory diff in onTick books exactly the baits that
        // actually left the stack - a saved bait never shrinks it and so never costs anything.
    }

    /** Called by {@link BiteIndicator} when the bite marker goes away while the bobber stays out. */
    void onBiteEnd() {
        castSession.onBiteEnd(System.currentTimeMillis());
    }

    /** How many casts this session made ("Times Fished"). */
    public int timesFished() {
        return castSession.timesFished();
    }

    /** The session clock's pause length from the settings, in milliseconds. */
    private static long pauseMs() {
        return Math.max(10, ConfigManager.getInstance().get().fishing.sessionPauseSec) * 1000L;
    }

    /**
     * Total baits still available for the "Baits Left" HUD: loose baits carried in the inventory plus
     * the last-seen Fishing Bag reserve ({@link #captureBaitSack}), minus the casts made since that
     * look ({@link #onCast} – one estimated bait each). So the count ticks DOWN live while fishing
     * and snaps back to the bag's real numbers on every bag open.
     */
    public int remainingBaits() {
        int total = 0;
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && FishingData.isBait(SkyblockItem.id(stack))) {
                    total += stack.getCount();
                }
            }
        }
        for (Map.Entry<String, Integer> entry : baitSackReserve.entrySet()) {
            total += Math.max(0,
                    entry.getValue() - pendingBaitEstimate.getOrDefault(entry.getKey(), 0));
        }
        return total;
    }

    /**
     * The bait the player is currently fishing with: the rod display's, else the Bait Sack's favourite
     * while any of it is left (Hypixel consumes it first), else the one most recently consumed, or –
     * before the first cast this session – whatever bait is visible loose in the inventory, then in
     * the sack. Drives the bait-counter HUD icon; null when no bait can be found at all.
     */
    public String currentBaitId() {
        if (baitDisplayActive && displayBaitId != null) {
            return displayBaitId;   // the rod display names exactly the bait now loaded
        }
        String favourite = activeFavourite();
        if (favourite != null && remainingOf(favourite) > 0) {
            return favourite;
        }
        if (lastConsumedBaitId != null) {
            return lastConsumedBaitId;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    String id = SkyblockItem.id(stack);
                    if (FishingData.isBait(id)) {
                        return id;
                    }
                }
            }
        }
        for (String id : baitSackReserve.keySet()) {
            return id;
        }
        return null;
    }

    /**
     * How many of one specific bait remain: the last-seen Fishing Bag count minus the casts made
     * since that look (live countdown), plus any loose stack in the inventory.
     */
    public int remainingOf(String baitId) {
        if (baitId == null) {
            return 0;
        }
        // Hypixel's own readout wins when the rod display shows this exact bait – it already sums the
        // loose inventory and the Fishing Bag, so no estimate can beat it.
        if (baitDisplayActive && baitId.equals(displayBaitId) && displayBaitsRemaining >= 0) {
            return displayBaitsRemaining;
        }
        int bag = Math.max(0, baitSackReserve.getOrDefault(baitId, 0)
                - pendingBaitEstimate.getOrDefault(baitId, 0));
        return bag + inventoryCountOf(baitId);
    }

    /** Loose count of one item id in the player inventory. */
    private static int inventoryCountOf(String itemId) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        int total = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && itemId.equals(SkyblockItem.id(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The bobber just deployed – one cast. Baits fed from the Fishing Bag are consumed server-side
     * with no inventory change the diff could ever see, so the cast books ONE of the current bait
     * on credit: the HUD chip counts down and the session cost grows immediately, cast by cast.
     * The next look into the bag corrects the guess ({@link #reconcileBaitConsumption}) – bait-save
     * effects make the estimate run slightly hot, and the reconcile hands that back. Baits carried
     * loose stay out of this: their stack shrink is booked exactly by the inventory diff in onTick.
     */
    private void onCast() {
        if (baitDisplaySeen) {
            return;   // the rod bait display books consumption authoritatively – no estimate needed
        }
        // The sack's favourite is consumed first while the sack holds it; otherwise the old rule:
        // a loose stack is consumed first, then the bait in use, then whatever the sack still holds.
        String favourite = activeFavourite();
        boolean favouriteFirst = favourite != null && bagRemainingOf(favourite) > 0;
        String inUse = favouriteFirst ? favourite : currentBaitId();
        if (inUse == null || inventoryCountOf(inUse) > 0) {
            return;   // no bait at all, or a loose stack the inventory diff books exactly
        }
        String bait = BaitAttribution.castBait(favourite, inUse, baitSackReserve.keySet(),
                id -> inventoryCountOf(id) > 0 ? 0 : bagRemainingOf(id));
        if (bait == null) {
            return;   // the bag (as last seen) is out of baits entirely
        }
        pendingBaitEstimate.merge(bait, 1, Integer::sum);
        baitsUsed.merge(bait, 1, Integer::sum);
        lastConsumedBaitId = bait;
        lastEstimateBait = bait;
        lastEstimateAt = System.currentTimeMillis();
    }

    /**
     * The stored Bait Sack favourite, unless an observation since it was read named another bait
     * being consumed (it was unfavourited, or the rule is wrong) – then null, and the old rule decides.
     */
    private String activeFavourite() {
        FavouriteBaitStore store = FavouriteBaitStore.getInstance();
        String favourite = store.baitId();
        if (favourite == null) {
            return null;
        }
        boolean contradicted = lastObservedBaitAt > store.readAt() && lastConsumedBaitId != null
                && !favourite.equals(lastConsumedBaitId);
        return contradicted ? null : favourite;
    }

    /**
     * A loose stack just shrank by {@code lost} of {@code baitId}: that is what the last cast really
     * consumed, so an estimate the cast booked on credit moments ago is taken back – the diff is the
     * truth, the estimate only a guess for when the diff sees nothing.
     */
    private void undoRecentEstimate(int lost, long now) {
        if (lastEstimateBait == null || now - lastEstimateAt > ESTIMATE_UNDO_MS) {
            return;
        }
        String id = lastEstimateBait;
        lastEstimateBait = null;
        int pending = pendingBaitEstimate.getOrDefault(id, 0);
        int undo = Math.min(lost, pending);
        if (undo <= 0) {
            return;
        }
        if (pending - undo == 0) {
            pendingBaitEstimate.remove(id);
        } else {
            pendingBaitEstimate.put(id, pending - undo);
        }
        int used = baitsUsed.getOrDefault(id, 0) - undo;
        if (used <= 0) {
            baitsUsed.remove(id);
        } else {
            baitsUsed.put(id, used);
        }
    }

    /** What the bag (as last seen) still holds of one bait, minus the casts made since that look. */
    private int bagRemainingOf(String baitId) {
        return baitSackReserve.getOrDefault(baitId, 0) - pendingBaitEstimate.getOrDefault(baitId, 0);
    }

    /**
     * Reads Hypixel's rod bait display: while a fishing rod is held, the loaded bait sits in hotbar
     * slot 9 with a "Baits Remaining: N" lore line that is the AUTHORITY on how many of that bait are
     * left (it already sums the loose inventory and the Fishing Bag). Scanned every tick; a decrease
     * of that number between ticks is booked as consumption of the shown bait – far more reliable than
     * the old inventory-diff / bag-reconcile estimate, which only ever saw <i>loose</i> baits shrink
     * and had to guess at bag-fed ones.
     *
     * <p>Once any display is seen this session ({@link #baitDisplaySeen}) it becomes the sole
     * consumption source and the estimate paths stand down, so nothing is double-booked. Bait can only
     * be consumed while the rod is held – exactly when the display is up – so its brief absence (rod
     * put away) loses no real consumption; the delta baseline just restarts on the next appearance.
     */
    private void readBaitDisplay(Player player) {
        baitDisplayActive = false;
        var inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {   // hotbar only; the display sits in slot 9 (index 8)
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Integer remaining = parseBaitsRemaining(stack);
            if (remaining == null) {
                continue;
            }
            String baitId = SkyblockItem.id(stack);
            if (!FishingData.isBait(baitId)) {
                // A display item may not carry the bait's NBT id – resolve it from the shown name.
                String byName = resolveItemId(stack.getHoverName().getString());
                if (FishingData.isBait(byName)) {
                    baitId = byName;
                }
            }
            displayBaitsRemaining = remaining;
            if (FishingData.isBait(baitId)) {
                displayBaitId = baitId;   // keep the last known id if the display item is unrecognised
            }
            baitDisplayActive = true;
            baitDisplaySeen = true;
            break;
        }
        if (!baitDisplayActive) {
            // No display this tick (rod not held): break delta continuity so a later re-appearance
            // re-baselines instead of booking the whole gap as one phantom drop.
            lastDisplayRemaining = -1;
            lastDisplayBaitId = null;
            return;
        }
        // The between-tick drop of the SAME bait is the real consumption: small and positive while
        // fishing. A rise is a refill and a bait-type switch changes the id – both only re-baseline.
        if (lastDisplayRemaining >= 0 && displayBaitId != null
                && displayBaitId.equals(lastDisplayBaitId)) {
            int used = lastDisplayRemaining - displayBaitsRemaining;
            if (used > 0 && used <= MAX_BAIT_CONSUMED_PER_TICK && recentFishingActivity()) {
                baitsUsed.merge(displayBaitId, used, Integer::sum);
                lastConsumedBaitId = displayBaitId;
                lastObservedBaitAt = System.currentTimeMillis();
            }
        }
        lastDisplayRemaining = displayBaitsRemaining;
        lastDisplayBaitId = displayBaitId;

        long now = System.currentTimeMillis();
        if (now - lastBaitDisplayLogAt > 5_000L) {
            lastBaitDisplayLogAt = now;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] bait display: {} x{} (authoritative)",
                    displayBaitId, displayBaitsRemaining);
        }
    }

    /** The "Baits Remaining: N" number on an item's lore, or null when the line is absent. */
    private static Integer parseBaitsRemaining(ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (var line : lore.lines()) {
            // strip() is the allocation-light colour-code remover; String.replaceAll would
            // recompile its pattern per lore line per tick.
            Matcher matcher = BAITS_REMAINING.matcher(strip(line.getString()));
            if (matcher.find()) {
                return parseCount(matcher.group(1));
            }
        }
        return null;
    }

    /**
     * Refreshes the {@link #baitSackReserve} from the open <b>Bait Sack</b> (Small / Medium / Large,
     * which replaced the Fishing Bag on 2026-09-21) or an old <b>Fishing Bag</b> - recognised and read
     * by {@link BaitSackReader}, which keeps only the bait slots, takes a sack's amount from its
     * {@code Stored:} lore, and finds the favourite bait ({@link FavouriteBaitStore}). Baits stored
     * here never pass through the inventory, so without this "Baits Left" would only ever show the
     * loose baits in hand. A no-op for every other screen; the bottom 36 menu slots are the player
     * inventory and are skipped so held baits are not counted twice. {@link BaitSackProbe} logs the
     * menu's real layout while it is still uncaptured.
     *
     * <p>The bag is read EVERY tick it is open, but what is read is only believed once it has
     * SETTLED: the server streams the content in after the screen opens (the open tick shows all
     * air, Hypixel fills menus progressively), so the first look waits until the content is
     * non-empty and has stopped changing for {@link #BAG_SETTLE_TICKS} ticks. That settled first
     * look is the reconcile moment – a closed bag cannot be withdrawn from, so whatever each bait
     * dropped by against the remembered baseline is the closed period's TRUE consumption
     * ({@link #reconcileBaitConsumption}). From then on the open bag is mirrored live, tick by
     * tick, so depositing or withdrawing bait moves the HUD the moment it happens. Reconciling on
     * the open edge instead (the old behaviour) compared the baseline against the still-empty
     * slots and booked the entire reserve as phantom consumption.
     */
    private void captureBaitSack(net.minecraft.client.gui.screens.Screen screen) {
        boolean bagOpen = false;
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container) {
            if (screen != lastBaitScreen) {
                // Title checks once per screen, not per tick: most open containers are not the sack.
                lastBaitScreen = screen;
                lastBaitTitle = container.getTitle() == null ? "" : container.getTitle().getString();
                lastBaitTitleIsStore = BaitSackReader.isBaitStoreTitle(lastBaitTitle);
                lastBaitTitleProbed = BaitSackProbe.probes(lastBaitTitle);
            }
            if (lastBaitTitleIsStore || lastBaitTitleProbed) {
                net.minecraft.world.inventory.AbstractContainerMenu menu = container.getMenu();
                List<BaitSackReader.SlotView> slots = slotViews(menu);
                if (lastBaitTitleProbed) {
                    baitSackProbe.onTick(lastBaitTitle, menu.slots.size(), slots);
                }
                // Deliberately ONLY the exact store titles (BaitSackReader): a looser match (any
                // title containing "bait") also caught Bazaar / AH product pages named after a
                // bait, whose display stacks then clobbered the reserve with garbage counts.
                if (lastBaitTitleIsStore) {
                    bagOpen = true;
                    readBaitStore(BaitSackReader.read(slots));
                }
            }
        } else {
            lastBaitScreen = null;
        }
        baitBagWasOpen = bagOpen;
    }

    /** One tick's look into the open store: settle, then reconcile once, then mirror live. */
    private void readBaitStore(BaitSackReader.Reading reading) {
        if (!baitBagWasOpen) {
            baitBagReconciled = false;   // fresh opening: a reconcile is due once settled
            bagStableTicks = 0;
            lastBagContent = null;
        }
        if (baitBagReconciled) {
            // Settled earlier this opening: mirror the bag live, favourite included (shift-click).
            baitSackReserve.clear();
            baitSackReserve.putAll(reading.baits());
            rememberFavourite(reading);
            return;
        }
        bagStableTicks = !reading.content().isEmpty() && reading.content().equals(lastBagContent)
                ? bagStableTicks + 1 : 0;
        lastBagContent = reading.content();
        if (bagStableTicks >= BAG_SETTLE_TICKS) {
            reconcileBaitConsumption(reading.baits(), reading.favourite());
            baitSackReserve.clear();
            baitSackReserve.putAll(reading.baits());
            rememberFavourite(reading);
            baitBagReconciled = true;
        }
    }

    /**
     * Stores the favourite the settled sack shows. A sack with no recognisable marker keeps the stored
     * one - the marker is unverified, and "none found" may be "marker not understood" - unless that
     * bait is no longer in the sack at all, in which case it can no longer be consumed first.
     */
    private void rememberFavourite(BaitSackReader.Reading reading) {
        FavouriteBaitStore store = FavouriteBaitStore.getInstance();
        String favourite = reading.favourite();
        if (favourite == null) {
            String stored = store.baitId();
            if (stored == null || reading.baits().containsKey(stored)) {
                return;
            }
        }
        store.set(favourite, System.currentTimeMillis());
    }

    /** The menu's own slots (the player inventory below left out) as the reader's plain views. */
    private static List<BaitSackReader.SlotView> slotViews(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        List<BaitSackReader.SlotView> out = new ArrayList<>();
        int containerSlots = Math.max(0, menu.slots.size() - 36);
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (loreComponent != null) {
                for (Component line : loreComponent.lines()) {
                    lore.add(strip(line.getString()));
                }
            }
            out.add(new BaitSackReader.SlotView(i, SkyblockItem.id(stack),
                    strip(stack.getHoverName().getString()), stack.getCount(), lore, stack.hasFoil()));
        }
        return out;
    }

    /**
     * First SETTLED look into the Fishing Bag after a closed period ({@link #captureBaitSack}
     * decides when that is): each bait's drop against the remembered baseline is the closed
     * period's true consumption. The per-cast estimates ({@link #onCast})
     * already booked their guess into {@link #baitsUsed}, so only the difference is applied –
     * upward when more was consumed than casts were seen, downward when bait-save effects (Caster,
     * Stingy Sinker, Spiked Atrocity) made the estimate run hot. A count that GREW is a refill and
     * only re-baselines. The estimate ledger starts fresh either way, against the new baseline.
     *
     * <p>The per-bait drops are exact, so the favourite changes no count here; it only names the bait
     * "in use" when it shrank at all - the sack consumes it first, so it is the one being used now even
     * when a bait that ran out earlier in the closed period dropped by more.
     */
    private void reconcileBaitConsumption(Map<String, Integer> found, String favourite) {
        if (skipNextBaitReconcile) {
            skipNextBaitReconcile = false;
            pendingBaitEstimate.clear();
            return;   // a reset happened: the old session's consumption must not book into this one
        }
        if (baitDisplaySeen) {
            pendingBaitEstimate.clear();
            return;   // the rod bait display already books consumption – the bag delta would double it
        }
        int biggestConsumed = 0;
        for (Map.Entry<String, Integer> prev : baitSackReserve.entrySet()) {
            String id = prev.getKey();
            int consumed = Math.max(0, prev.getValue() - found.getOrDefault(id, 0));
            int estimated = pendingBaitEstimate.getOrDefault(id, 0);
            if (consumed > biggestConsumed) {
                biggestConsumed = consumed;
                lastConsumedBaitId = id;   // the bait that actually shrank is the one in use
            }
            if (consumed > 0 && id.equals(favourite)) {
                biggestConsumed = Integer.MAX_VALUE;   // the favourite, once it shrank, names the bait in use
                lastConsumedBaitId = id;
            }
            int diff = consumed - estimated;
            if (diff > 0) {
                baitsUsed.merge(id, diff, Integer::sum);
            } else if (diff < 0) {
                int corrected = Math.max(0, baitsUsed.getOrDefault(id, 0) + diff);
                if (corrected == 0) {
                    baitsUsed.remove(id);
                } else {
                    baitsUsed.put(id, corrected);
                }
            }
        }
        if (biggestConsumed > 0) {
            lastObservedBaitAt = System.currentTimeMillis();
        }
        pendingBaitEstimate.clear();
        lastEstimateBait = null;
    }

    /** Whether the player fished recently enough for item gains to count as fishing loot. */
    public boolean recentFishingActivity() {
        return System.currentTimeMillis() - lastActivityAt < ACTIVITY_WINDOW_MS;
    }

    /**
     * Whether the session has gone quiet long enough for its panels to get out of the way. A session
     * that collected something keeps that data for as long as the game runs, so "has this session
     * caught anything" is true forever after the first catch – on its own it is a reason to draw the
     * panels, never a reason to stop, which is why they used to sit there all day.
     */
    public boolean sessionIdle() {
        return lastActivityAt == 0 || System.currentTimeMillis() - lastActivityAt > IDLE_HIDE_MS;
    }

    /**
     * The tick gate's question beyond "bobber out / rod in hand / menu open": whether {@link #onTick}
     * could change anything right now - fishing activity inside {@link #ACTIVITY_WINDOW_MS} or a
     * Golden Fish clock running. Slot changes alone are NOT a reason: Hypixel resends inventory slots
     * constantly, and outside the activity window gains are never booked and the removal ledger is
     * never read ({@link #onSackMessage} returns early), so the tick has nothing to do. The window
     * outlives putting the rod away, so a sack line right after still finds its removals; a tick
     * after a skipped stretch rebases the baseline instead of diffing ({@link #TICK_GAP_REBASE_MS}).
     */
    public boolean needsTick() {
        return recentFishingActivity() || GoldenFishTracker.getInstance().busy();
    }

    /** Whether a bite/catch just happened – the moments fishing actually hands the player items. */
    private boolean justFished() {
        return System.currentTimeMillis() - lastLootEventAt < LOOT_PICKUP_WINDOW_MS;
    }

    /**
     * Called every client tick: diffs the inventory and books anything that grew.
     *
     * <p>The first pass after joining only records the baseline – otherwise your whole existing
     * inventory would count as one enormous catch.
     */
    public void onTick() {
        if (!enabled()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            baselineReady = false;   // left the world; the next baseline is a fresh one
            lastCounts.clear();
            GoldenFishTracker.getInstance().onNoPlayer();
            return;
        }
        long now = System.currentTimeMillis();
        // Once per game session, and again whenever the gate let the task resume after a minute off.
        if (!tickRunningLogged || now - lastTickAt > 60_000L) {
            tickRunningLogged = true;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] tick running (bobber {}, timesFished={}, session {}s)",
                    player.fishing != null ? "out" : "in", castSession.timesFished(),
                    castSession.sessionMillis(now, pauseMs()) / 1000);
        }
        // The cast counters come FIRST and depend on nothing but the bobber: Times Fished, the session
        // clock and the Reel Timer used to run after five other readers, so one failing reader (or a
        // bite marker never seen) left all of them at 0 while chat-booked catches kept counting.
        boolean bobber = player.fishing != null;
        boolean wasOut = castSession.bobberOut();
        var hook = player.fishing;
        boolean inFluid = hook != null && (hook.isInWater() || hook.isInLava());
        CastSession.Edge edge = castSession.onTick(bobber, inFluid, now, pauseMs(),
                ConfigManager.getInstance().get().fishing.reelRollingCasts);
        if (edge == CastSession.Edge.CAST) {
            // Only the CAST marks activity, not the bobber merely being out: a bobber left in the
            // water is waiting or afk. Bites and catches refresh activity on their own.
            lastActivityAt = now;
            revision++;
            logCastEdge(now);
            if (!partOff[PART_BAIT_SACK]) {
                try {
                    onCast();   // one bag-fed bait consumed on credit
                } catch (RuntimeException e) {
                    partFailed(PART_BAIT_SACK, e);
                }
            }
        } else if (edge == CastSession.Edge.REEL) {
            revision++;
            long sample = castSession.lastReelSample();
            ReelTimer reel = castSession.reelTimer();
            if (sample >= 0 && !reel.landed() && !noLandLogged) {
                noLandLogged = true;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Fishing] reel: no water/lava contact seen on this cast - wait timed from "
                                + "the bobber's spawn ({} ms)", sample);
            }
        }
        if (castSession.landedThisTick() && !landLogged) {
            landLogged = true;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] reel: bobber reported {} contact - waits are timed from landing",
                    hook != null && hook.isInLava() ? "lava" : "water");
        }
        // The optional readers. Each is fenced on its own: a failure switches that one reader off and
        // logs it, and the counters above and the loot diff below keep running.
        if (!partOff[PART_BITE]) {
            try {
                BiteIndicator.getInstance().onTick(player);
            } catch (RuntimeException e) {
                partFailed(PART_BITE, e);
            }
        }
        // Hypixel's rod bait display (slot 9 "Baits Remaining: N") is the authority on the bait
        // count; once seen it drives consumption so the estimate paths stand down.
        if (!partOff[PART_BAIT_DISPLAY]) {
            try {
                readBaitDisplay(player);
            } catch (RuntimeException e) {
                partFailed(PART_BAIT_DISPLAY, e);
            }
        }
        // The Bait Sack whenever its menu is open, so "Baits Left" counts the bulk reserve.
        if (!partOff[PART_BAIT_SACK]) {
            try {
                captureBaitSack(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen());
            } catch (RuntimeException e) {
                partFailed(PART_BAIT_SACK, e);
            }
        }
        if (!partOff[PART_DEATHS]) {
            try {
                SeaCreatureDrops.getInstance().onTick(player, now);
            } catch (RuntimeException e) {
                partFailed(PART_DEATHS, e);
            }
        }
        if (!partOff[PART_GOLDEN]) {
            try {
                GoldenFishTracker.getInstance().onTick(player, bobber, wasOut);
            } catch (RuntimeException e) {
                partFailed(PART_GOLDEN, e);
            }
        }
        // Gains inside a container screen are the player moving items (storage, bazaar, trades),
        // never a drop - they are absorbed into the baseline below without counting. The guard
        // extends CONTAINER_SETTLE_MS past the close: the server finishes menu-driven moves after
        // the screen is gone, and those late arrivals are still the menu's items, not loot.
        boolean containerOpen = sbs.modid.client.core.api.GuiStateManager.getInstance()
                .getCurrentScreen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        if (containerWasOpen && !containerOpen) {
            containerClosedSettleUntil = now + CONTAINER_SETTLE_MS;
        }
        containerWasOpen = containerOpen;
        // The gate skipped ticks (not fishing): whatever moved meanwhile was not fishing's doing, and
        // diffing against that stale baseline would book it the moment the next cast reopens the gate.
        boolean rebase = now - lastTickAt > TICK_GAP_REBASE_MS;
        lastTickAt = now;
        int revision = sbs.modid.client.core.tick.InventoryChanges.revision();
        if (baselineReady && !rebase && revision == countedRevision) {
            return;   // no slot packet since the last diff: the inventory and so the diff are unchanged
        }
        countedRevision = revision;
        Map<String, Integer> current = countInventory(player);
        if (!baselineReady) {
            swapBaseline(current);
            baselineReady = true;
            settleUntil = now + JOIN_SETTLE_MS;   // the inventory is still streaming in after the join
            return;
        }
        // Post-join settle: a lobby / island swap re-sends the whole inventory over several ticks;
        // absorb it into the baseline without counting so it never reads as one enormous catch.
        if (now < settleUntil || rebase) {
            swapBaseline(current);
            return;
        }
        boolean menuSettling = containerOpen || now < containerClosedSettleUntil || now < stashSettleUntil;
        boolean fishingActive = recentFishingActivity();
        boolean justFished = justFished();
        boolean sharedLoot = lootShare.covers(now);
        for (Map.Entry<String, Integer> entry : current.entrySet()) {
            int before = lastCounts.getOrDefault(entry.getKey(), 0);
            int gained = entry.getValue() - before;
            if (gained > 0) {
                // A bought item (Bazaar / AH / NPC) lands like a drop - cancel it out entirely.
                gained -= consumeLedger(pendingPurchase, pendingPurchaseAt, entry.getKey(),
                        gained, PURCHASE_TTL_MS, now);
            }
            // Right after a bite or catch, everything gained is fishing's doing: the reeled-in
            // item, a quick kill's drops. Beyond that, only recognised fishing-loot ids count
            // (a slow kill's drop picked up late), and only while the session is still fishing -
            // whatever else lands in the inventory between casts never becomes "profit".
            // bookLoot dedups against the chat / sack channels, so a drop announced there AND
            // picked up here counts once.
            if (gained <= 0 || menuSettling) {
                continue;
            }
            String gainedId = entry.getKey();
            if (sbs.modid.client.skills.fishing.model.SeaCreatureDropTable.isRareDrop(gainedId)) {
                // A rare drop is judged by the kill behind it, not by the bite window: the Magma Lord
                // Fragment of 2026-10-08 was picked up 35s after the last spawn line, mid-fight with
                // the rod away, and the old two-tier rule refused it. bookLoot applies the gate.
                bookLoot(gainedId, gained, Channel.INVENTORY, sharedLoot);
            } else if (sharedLoot && FishingData.isFishingLoot(gainedId)) {
                // A loot share's window takes known sea-creature drops only: the share names no item,
                // so anything else picked up in those seconds is not provably its loot.
                bookLoot(gainedId, gained, Channel.INVENTORY, true);
            } else if (!FishingData.isNeverLoot(gainedId)
                    && (justFished || (fishingActive && FishingData.isFishingLoot(gainedId)))) {
                bookLoot(gainedId, gained, Channel.INVENTORY);
            } else {
                logUnexplainedPickup(gainedId, gained, now);
            }
        }
        // Inventory REMOVALS feed the transfer ledger: an item that leaves the inventory (auto-
        // absorbed into a sack on pickup, or stashed via the sack GUI) and then shows up as a
        // "[Sacks] +N" line is the SAME item moving, not new loot - onSackMessage deducts against
        // this ledger before booking. Recording every loss is safe: the ledger is only ever
        // consumed by sack "+" lines of the same id, within its short TTL.
        for (Map.Entry<String, Integer> entry : lastCounts.entrySet()) {
            int lost = entry.getValue() - current.getOrDefault(entry.getKey(), 0);
            if (lost > 0) {
                recentRemoved.merge(entry.getKey(), lost, Integer::sum);
                recentRemovedAt.put(entry.getKey(), now);
            }
        }
        // Bait consumption: a bait stack that SHRANK while fishing is the session's cost side.
        // Diffing the stack (instead of booking one bait per bite) is what makes bait-save effects
        // free: a cast whose bait was saved by Caster / Stingy Sinker / Spiked Atrocity produces no
        // shrink and therefore no cost. Container moves are reorganising, not consumption, and a
        // large drop is the stack being stashed or thrown, so both stay unbooked.
        // Skipped once the rod bait display is the source ({@link #baitDisplaySeen}): the display's
        // count already includes loose baits, so booking their shrink here too would double the cost.
        if (fishingActive && !menuSettling && !baitDisplaySeen) {
            for (Map.Entry<String, Integer> entry : lastCounts.entrySet()) {
                if (!FishingData.isBait(entry.getKey())) {
                    continue;
                }
                int lost = entry.getValue() - current.getOrDefault(entry.getKey(), 0);
                if (lost > 0 && lost <= MAX_BAIT_CONSUMED_PER_TICK) {
                    undoRecentEstimate(lost, now);   // the cast's credit guess was wrong, this is real
                    baitsUsed.merge(entry.getKey(), lost, Integer::sum);
                    lastConsumedBaitId = entry.getKey();   // the bait actually in use right now
                    lastObservedBaitAt = now;
                }
            }
        }
        // Replace wholesale: an item that left the inventory must lower the baseline too, or
        // picking it back up would count a second time.
        swapBaseline(current);
    }

    /**
     * The debug trail for the cast counters: the first {@link #CAST_LOG_FIRST} cast edges of a
     * session one by one, then every {@link #CAST_LOG_EVERY}-th, so a log shows at a glance whether
     * casts are seen at all without one line per cast all day.
     */
    private void logCastEdge(long now) {
        int casts = castSession.timesFished();
        if (casts <= CAST_LOG_FIRST || casts % CAST_LOG_EVERY == 0) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] cast edge #{} timesFished={} session={}s reelSamples={}",
                    casts, casts, castSession.sessionMillis(now, pauseMs()) / 1000,
                    castSession.reelTimer().samples());
        }
    }

    /** One optional per-tick reader threw: switch it off for the game session, say so once. */
    private void partFailed(int part, RuntimeException e) {
        partOff[part] = true;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.error(
                "[SBS][Fishing] {} failed and is off until restart; the fishing counters keep running: {}",
                PART_NAMES[part], e.toString(), e);
    }

    /**
     * A gain nothing booked, right after an eligible sea-creature death: the drop table may be
     * missing this creature's drop. Logged (throttled) so one trip fills the table - never booked.
     */
    private void logUnexplainedPickup(String itemId, int gained, long now) {
        if (now - lastUnexplainedLogAt < 2_000L) {
            return;
        }
        String creature = SeaCreatureDrops.getInstance().recentDeath(now);
        if (creature != null) {
            lastUnexplainedLogAt = now;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing][Drop] unexplained pickup {} x{} after a {} died nearby - not booked",
                    itemId, gained, creature);
        }
    }

    /** Makes {@code current} (the scratch map) the baseline; the old baseline becomes the scratch. */
    private void swapBaseline(Map<String, Integer> current) {
        scratchCounts = lastCounts;
        lastCounts = current;
    }

    /** Every SkyBlock item currently in the player's inventory, id -> total count, in the scratch map. */
    private Map<String, Integer> countInventory(Player player) {
        Map<String, Integer> counts = scratchCounts;
        counts.clear();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = SkyblockItem.id(stack);
            if (id != null) {
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // Read-out
    // ------------------------------------------------------------------

    public Map<String, Integer> creatures() {
        return creatures;
    }

    public Map<String, Integer> loot() {
        return loot;
    }

    /** The baits this session consumed, id -> count. */
    public Map<String, Integer> baitsUsed() {
        return baitsUsed;
    }

    /** The shards this session actually produced, in the order they were first caught. */
    public Map<String, Integer> shards() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : loot.entrySet()) {
            if (FishingData.isShard(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /**
     * Value of one item id: Bazaar first, Auction House second, 0 when neither knows it.
     *
     * <p>Bazaar uses the <b>instasell</b> side: that is what the loot is actually worth to you right
     * now, without waiting for a sell offer to fill. Quoting the higher instabuy price would inflate
     * every session's profit. Items the Bazaar does not trade (armor drops, rare AH-only loot) fall
     * back to the lowest BIN.
     */
    public double price(String itemId) {
        if (COIN_ID.equals(itemId)) {
            return 1;   // treasure coins are already coins
        }
        double npc = npcPrice(itemId);
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(itemId);
        if (value != null && value.sell() > 0) {
            // Never below the NPC: Lump of Magma, Moogma Pelt and Pyroclastic Scale instasell for a
            // few coins against a 50-coin NPC price (quick_status, 2026-10-08), and the NPC is the
            // better sale for those.
            return Math.max(value.sell(), npc);
        }
        Long lbin = sbs.modid.client.economy.prices.LbinCache.getInstance().getLbin(itemId);
        if (lbin != null && lbin > 0) {
            return Math.max(lbin, npc);
        }
        // No Bazaar buyer and no auction: Chum's instasell is 0 (no buy orders), so the NPC is the
        // only price there is.
        return npc;
    }

    /** The NPC sell price from the item catalogue, 0 when the item has none. */
    private static double npcPrice(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        return entry == null || !(entry.npcSellPrice > 0) ? 0 : entry.npcSellPrice;
    }

    /**
     * Cost price of one bait: the current Bazaar <b>instabuy</b> ({@code quick_status.buyPrice} – the
     * cheapest sell offer, i.e. what you actually pay to restock the bait), falling back to instasell
     * then the lowest BIN. Loot uses instasell ({@link #price}) because that is what you get selling
     * it; a consumed bait is the opposite side of the book, so it is valued at what it cost to buy.
     */
    public double baitPrice(String baitId) {
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(baitId);
        if (value != null) {
            if (value.buy() > 0) {
                return value.buy();
            }
            if (value.sell() > 0) {
                return value.sell();
            }
        }
        Long lbin = sbs.modid.client.economy.prices.LbinCache.getInstance().getLbin(baitId);
        return lbin == null ? 0 : lbin;
    }

    /** What one id's stack is worth this session. */
    public double valueOf(String itemId) {
        return price(itemId) * loot.getOrDefault(itemId, 0);
    }

    /** What the consumed baits cost this session, at the current Bazaar buy price – the cost side. */
    public double baitCost() {
        double total = 0;
        for (Map.Entry<String, Integer> entry : baitsUsed.entrySet()) {
            total += baitPrice(entry.getKey()) * entry.getValue();
        }
        return total;
    }

    /** Every tracked item's value added up, minus the consumed baits – the session profit. */
    public double totalProfit() {
        double total = 0;
        for (Map.Entry<String, Integer> entry : loot.entrySet()) {
            total += price(entry.getKey()) * entry.getValue();
        }
        for (Map.Entry<String, Integer> entry : rareDrops.entrySet()) {
            total += price(entry.getKey()) * entry.getValue();
        }
        return total - baitCost();
    }

    /** Rare sea-creature drops this session, item id -> count. */
    public Map<String, Integer> rareDrops() {
        return rareDrops;
    }

    /** The creature the latest booking of {@code itemId} was attributed to, or null. */
    public String rareDropSource(String itemId) {
        return rareDropSource.get(itemId);
    }

    /** What one rare drop's count is worth this session. */
    public double rareValueOf(String itemId) {
        return price(itemId) * rareDrops.getOrDefault(itemId, 0);
    }

    /** The cast-to-bite timer. */
    public ReelTimer reelTimer() {
        return castSession.reelTimer();
    }

    /** How many of {@code itemId}'s booked count were loot-share loot (0: none - no "LS" marker). */
    public int lootShareCount(String itemId) {
        return lootShareCounts.getOrDefault(itemId, 0);
    }

    /** Changes whenever a count the HUD shows changes; compare for equality only. */
    public int revision() {
        return revision;
    }

    /**
     * Session length in milliseconds, counting only the time the player was actually fishing: gaps
     * between casts, reels, bites and catches up to the "Session Pause" setting
     * ({@link FishingSessionClock}). Computed from event timestamps, so it is right even on a frame
     * where the tick task did not run.
     */
    public long sessionMillis() {
        return castSession.sessionMillis(System.currentTimeMillis(), pauseMs());
    }

    /** Profit extrapolated to an hour of fished time; 0 until the session is long enough to mean anything. */
    public double profitPerHour() {
        return castSession.perHour(totalProfit(), System.currentTimeMillis(), pauseMs());
    }

    public int totalCreatures() {
        int total = 0;
        for (int count : creatures.values()) {
            total += count;
        }
        return total;
    }

    /** Clears the session (counts, value and the clock). */
    public void reset() {
        creatures.clear();
        rareDrops.clear();
        rareDropSource.clear();
        castSession.reset();
        shardLines.reset();
        lootShare.reset();
        lootShareCounts.clear();
        SeaCreatureDrops.getInstance().resetSession();
        revision++;
        doubleHookAt = 0;
        loot.clear();
        baitsUsed.clear();
        for (Channel channel : Channel.values()) {
            recentByChannel.get(channel).clear();
            recentAtByChannel.get(channel).clear();
        }
        pendingPurchase.clear();
        pendingPurchaseAt.clear();
        recentRemoved.clear();
        recentRemovedAt.clear();
        lastCounts.clear();
        baselineReady = false;
        // The bag baseline (last-seen counts) survives - it is a real-world snapshot, not session
        // data - but the cast estimates belong to the cleared session, and the next bag look must
        // only re-baseline instead of booking the old session's consumption into the fresh one.
        pendingBaitEstimate.clear();
        skipNextBaitReconcile = true;
        lastEstimateBait = null;
        lastObservedBaitAt = 0;
        // The rod display baseline belongs to the old session too: drop it so the drop across the
        // reset is never booked, and re-detect the display fresh on the next tick.
        baitDisplaySeen = false;
        baitDisplayActive = false;
        displayBaitsRemaining = -1;
        displayBaitId = null;
        lastDisplayRemaining = -1;
        lastDisplayBaitId = null;
    }
}
