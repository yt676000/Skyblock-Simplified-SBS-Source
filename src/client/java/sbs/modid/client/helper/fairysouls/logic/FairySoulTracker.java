/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.keybind.IslandCatalog;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.fairysouls.model.FairySoul;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches for Fairy Souls being collected and keeps the per-profile record honest.
 *
 * <p><b>The matching rule: certainty or nothing.</b> Hypixel's message says a soul was found, never
 * <i>which</i> one, so the soul has to be identified by position: by what the player just clicked,
 * else by a clearly-nearest soul ({@link SoulAttribution} has the rules). Anything less certain
 * records nothing at all - a false positive permanently hides a soul the player still needs, and there is no
 * signal that would ever correct it. Every unresolved collection is logged instead, so a coordinate
 * this build lacks shows up as data to fix rather than as silence.
 *
 * <p><b>Reconciliation, not pretence.</b> An existing player's local record starts empty while their
 * profile may already have hundreds collected. Rather than draw every untracked soul as uncollected
 * and call it progress, {@link #reconciliation()} states both numbers plainly, and opening the Quest
 * Log settles every island that is already finished - see {@link FairySoulMenu}. The manual
 * "mark this island collected" button remains for the islands that are not.
 */
public final class FairySoulTracker {

    private static final FairySoulTracker INSTANCE = new FairySoulTracker();

    /**
     * How far from the player a head may be to count as a lesson for the soul skin. Attribution of
     * the collection itself is {@link SoulAttribution}'s, with its own tighter radii.
     */
    private static final double LEARN_RADIUS = 6.0;

    /** "Fairy Souls: 12/24" in the tab list. */
    private static final Pattern TAB_COUNT = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** The island last seen, so a change can reset per-island state exactly once. */
    private String lastIsland = "";

    /** The tab list's last readable "collected/total" pair, or {@code null}. */
    private int[] tabCounts;

    /** Whether the tab pair is the current island's (rather than account-wide) - see {@link #tick}. */
    private boolean tabIsPerIsland;

    private FairySoulTracker() {
    }

    public static FairySoulTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FairySoulSettings cfg() {
        return ConfigManager.getInstance().get().fairySouls;
    }

    // ------------------------------------------------------------------ chat

    /**
     * Hypixel's collection lines.
     *
     * <p>Both wordings are useful: "you found a Fairy Soul" is a fresh collection, and "you have
     * already found that Fairy Soul" is just as certain a statement that the soul under the player is
     * collected - it is how a returning player's record fills in without any guessing at all.
     */
    public void onChat(String text) {
        if (!cfg().enabled || text == null) {
            return;
        }
        // Hypixel puts formatting codes MID-line: the collection line is literally
        // "§d§lSOUL! §fYou found a §dFairy Soul§r§f!", so a contains() on the raw text never sees
        // "you found a fairy soul" - the §d sits inside it. That is why collecting a soul used to
        // do nothing at all: no match, no record, so the marker stayed and the route never moved on.
        String lower = strip(text).toLowerCase(Locale.ROOT);
        if (!lower.contains("fairy soul")) {
            return;
        }
        boolean found = lower.contains("you found a fairy soul");
        boolean already = lower.contains("already found") || lower.contains("already collected");
        if (!found && !already) {
            return;
        }
        resolveAndMark(already);
    }

    /**
     * Identifies the soul the player is standing on and records it, or explains why it could not.
     *
     * @param already whether this was an "already found" line rather than a fresh collection
     */
    private void resolveAndMark(boolean already) {
        Player player = Minecraft.getInstance().player;
        String island = SkyBlockLocation.island();
        if (player == null || island.isEmpty()) {
            return;
        }
        // The collection line is also a lesson: the skull beside the player IS a soul, certainly.
        // With an empty curated file this is the only way the catalogue ever gets its first entry -
        // and the first entry is what arms the sight scanner for all the rest. Never on a
        // per-player instance: a head there is one player's decoration.
        if (!IslandCatalog.isInstanced(island)) {
            learnFromCollection(player, island);
        }
        Vec3 at = player.position();
        List<double[]> clicked = System.currentTimeMillis() - lastClickAt
                <= SoulAttribution.CLICK_WINDOW_MS ? lastClick : null;
        // A soul line at a learned candidate confirms it - then it is drawn (and recordable below).
        pendingCandidate = null;
        FairySoulLearned learned = FairySoulLearned.getInstance();
        boolean confirmed = false;
        if (clicked != null) {
            for (double[] point : clicked) {
                confirmed |= learned.confirmNear(island, point);
            }
        }
        if (!confirmed) {
            learned.confirmNear(island, new double[]{at.x, at.y + 1, at.z});
        }
        SoulAttribution.Result pick = SoulAttribution.pick(FairySoulDatabase.forIsland(island),
                new double[]{at.x, at.y, at.z}, clicked);
        if (pick.soul() != null) {
            FairySoul soul = pick.soul();
            if (FairySoulStore.getInstance().markCollected(soul.id)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] recorded {} on {} (by {})",
                        soul.summary(), island, pick.via() == SoulAttribution.Via.CLICK
                                ? "the clicked target" : "clearly nearest");
                FairySoulRouting.getInstance().onSoulCollected(soul);
            }
            return;
        }
        // A refusal to guess. Logged with the position so a coordinate can be added or corrected in
        // the data file - that is the fix, not a looser rule.
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][FairySouls] {} at ({}, {}, {}) on {}{} - {}, nothing recorded",
                already ? "already-found line" : "collection",
                (int) at.x, (int) at.y, (int) at.z, island,
                clicked == null ? "" : " (clicked target not on a soul)", pick.why());
        if (cfg().reportUnmatched && !already) {
            SBSChat.send(Component.literal(" Fairy Soul collected, but it could not be told apart "
                    + "from its neighbours - nothing recorded.").withColor(SBSChat.WHITE));
        }
    }

    /** The last thing the local player clicked (block centre, or an entity's feet and head). */
    private volatile List<double[]> lastClick;
    private volatile long lastClickAt;

    /** Fed by the interaction mixins: a block the player used or hit. */
    public void onBlockClicked(BlockPos pos) {
        if (pos != null) {
            lastClick = List.of(new double[]{pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5});
            lastClickAt = System.currentTimeMillis();
            watchCandidate(lastClick);
        }
    }

    /** A clicked learned candidate waiting for its soul line, and when it was clicked. */
    private volatile int[] pendingCandidate;
    private volatile String pendingIsland;
    private volatile long pendingAt;

    /** How long a clicked candidate may wait for "You found" / "already found" before it is dropped. */
    private static final long CANDIDATE_ANSWER_MS = 2_000L;

    private void watchCandidate(List<double[]> points) {
        if (!cfg().enabled) {
            return;
        }
        String island = SkyBlockLocation.island();
        for (double[] point : points) {
            int[] entry = FairySoulLearned.getInstance().unconfirmedNear(island, point);
            if (entry != null) {
                pendingCandidate = entry;
                pendingIsland = island;
                pendingAt = System.currentTimeMillis();
                return;
            }
        }
    }

    /** Drops a clicked candidate that no soul line answered. */
    private void expireCandidate() {
        int[] entry = pendingCandidate;
        if (entry != null && System.currentTimeMillis() - pendingAt > CANDIDATE_ANSWER_MS) {
            pendingCandidate = null;
            FairySoulLearned.getInstance().removeCandidate(pendingIsland, entry);
        }
    }

    /** Fed by the interaction mixins: an entity the player attacked or interacted with. */
    public void onEntityClicked(Entity entity) {
        if (entity != null) {
            BlockPos head = headPos(entity);
            lastClick = List.of(new double[]{entity.getX(), entity.getY(), entity.getZ()},
                    new double[]{head.getX() + 0.5, head.getY() + 0.5, head.getZ() + 0.5});
            lastClickAt = System.currentTimeMillis();
            watchCandidate(lastClick);
        }
    }

    // ------------------------------------------------------------------ learning by sight

    /** How far around the player the sight scanner looks, in chunks. */
    private static final int SCAN_CHUNKS = 6;

    /** Scan cadence in ticks - block entities move rarely, two seconds is plenty. */
    private static final int SCAN_INTERVAL = 40;

    private int scanCountdown;

    /**
     * The learning step a collection line triggers. The heads within reach are narrowed to the
     * plausible ones (near a curated soul, where the island has any), then handed to the
     * two-collection rule in {@link SoulSkinLessons}: one collection records a lesson, a second
     * agreeing one at another site adopts the skin. Once the skin is known, the one matching head is
     * catalogued directly.
     */
    private void learnFromCollection(Player player, String island) {
        FairySoulLearned learned = FairySoulLearned.getInstance();
        List<FairySoul> curated = FairySoulDatabase.curatedForIsland(island);
        List<SoulSkinLessons.Candidate> heads = new ArrayList<>();
        for (SoulSkinLessons.Candidate head : nearbyHeads(player, LEARN_RADIUS)) {
            if (FairySoulLearned.plausible(head.x(), head.y(), head.z(), curated)) {
                heads.add(head);
            }
        }
        if (learned.textureKnown()) {
            heads.removeIf(h -> !learned.texture().equals(h.texture()));
            if (heads.size() == 1) {
                SoulSkinLessons.Candidate head = heads.getFirst();
                BlockPos pos = new BlockPos(head.x(), head.y(), head.z());
                if (learned.add(island, pos, curated)) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] catalogued the collected "
                            + "soul at {} on {} by sight", pos.toShortString(), island);
                }
                learned.saveIfDirty();
            }
            return;
        }
        SoulSkinLessons.Outcome outcome = learned.observeCollection(heads);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] collection on {}: {} plausible head(s) "
                + "within reach - {}", island, heads.size(), switch (outcome) {
                    case NONE -> "nothing to learn from";
                    case AMBIGUOUS -> "ambiguous, nothing learned";
                    case LESSON_RECORDED -> "lesson recorded, waiting for a second soul to agree";
                    case ADOPTED -> "skin adopted";
                });
    }

    /**
     * Every armor stand within {@code radius} of the player wearing a player head, as a skin plus
     * the block its head occupies.
     *
     * <p><b>A Fairy Soul is an invisible armor stand wearing the soul head - probed 2026-09-26</b>
     * with {@code /sbs soulprobe} at hub-14 (-64, 77, -72), gm-4 (-37, 78, -308) and dc-3
     * (3, 182, 50): each an invisible {@code armor_stand}, feet at the soul's Y - 1.45, helmet
     * {@code player_head}, skin {@code textures.minecraft.net/texture/299ea120bd83d0c81a3c4627f5bce1b12fb03bcb57779c63dcc77e3f4ae8a793},
     * and NO skull block. The old skull-block scan could never see a soul; the only skulls it found
     * near a collection were decoration. Entities only exist in really loaded chunks, so Far
     * Terrain's translated scenery cannot leak in here.
     */
    private static List<SoulSkinLessons.Candidate> nearbyHeads(Player player, double radius) {
        List<SoulSkinLessons.Candidate> out = new ArrayList<>();
        Vec3 at = player.position();
        AABB box = new AABB(at, at).inflate(radius);
        for (ArmorStand stand : player.level().getEntitiesOfClass(ArmorStand.class, box,
                e -> !e.isRemoved())) {
            String texture = headTextureOf(stand);
            if (texture != null && at.distanceTo(stand.position()) <= radius) {
                BlockPos p = headPos(stand);
                out.add(new SoulSkinLessons.Candidate(texture, p.getX(), p.getY(), p.getZ()));
            }
        }
        return out;
    }

    /** The skin on an armor stand's helmet slot, if it is a head. */
    private static String headTextureOf(Entity entity) {
        if (!(entity instanceof ArmorStand stand)) {
            return null;
        }
        ItemStack stack = stand.getItemBySlot(EquipmentSlot.HEAD);
        if (stack.isEmpty()) {
            return null;
        }
        return textureOf(stack.get(DataComponents.PROFILE));
    }

    /**
     * The block a worn head occupies. A stand's head sits ~1.6 blocks above its feet (a small stand,
     * about half that), which is where the curated coordinates put the soul if it is a stand.
     */
    private static BlockPos headPos(Entity entity) {
        if (entity instanceof ArmorStand stand) {
            return BlockPos.containing(stand.getX(), stand.getY() + (stand.isSmall() ? 0.8 : 1.6),
                    stand.getZ());
        }
        return entity.blockPosition();
    }

    /** A profile's skin texture value, or {@code null} when it has none. */
    private static String textureOf(net.minecraft.world.item.component.ResolvableProfile profile) {
        try {
            if (profile == null) {
                return null;
            }
            var gameProfile = profile.partialProfile();
            if (gameProfile == null) {
                return null;
            }
            for (var property : gameProfile.properties().get("textures")) {
                String value = property.value();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
            // A malformed profile is just not a soul.
        }
        return null;
    }

    /**
     * The background pass: every couple of seconds, sweep around the player for soul stands with
     * the learned skin and catalogue any new ones. Each goes through the same plausibility check as
     * a lesson, so a decoration sharing the skin is logged, not boxed.
     */
    private void scanBySight(Minecraft minecraft, String island) {
        FairySoulLearned learned = FairySoulLearned.getInstance();
        if (!learned.textureKnown() || island.isEmpty() || minecraft.player == null) {
            return;
        }
        if (--scanCountdown > 0) {
            return;
        }
        scanCountdown = SCAN_INTERVAL;
        String texture = learned.texture();
        List<FairySoul> curated = FairySoulDatabase.curatedForIsland(island);
        int found = 0;
        for (SoulSkinLessons.Candidate head : nearbyHeads(minecraft.player, SCAN_CHUNKS * 16)) {
            if (texture.equals(head.texture())
                    && learned.add(island, new BlockPos(head.x(), head.y(), head.z()), curated)) {
                found++;
            }
        }
        if (found > 0) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][FairySouls] {} new soul(s) catalogued by sight on {}", found, island);
            learned.saveIfDirty();
        }
    }

    // ------------------------------------------------------------------ probe

    /**
     * {@code /sbs soulprobe}: stand at a soul and log every head within 3 blocks - skull blocks AND
     * entities wearing one (all entities, head or not, so an unexpected carrier still shows up) -
     * with position, type and the skin's texture URL, plus the nearest curated soul. Capture only.
     * Run at two or more different souls: the soul is whatever appears at every one with the SAME
     * skin. This is the open question from 2026-09-26 (block or armor stand?).
     */
    public void probe() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        String island = SkyBlockLocation.island();
        Vec3 at = player.position();
        FairySoul nearest = null;
        for (FairySoul soul : FairySoulDatabase.curatedForIsland(island)) {
            if (nearest == null || soul.centre().distanceTo(at) < nearest.centre().distanceTo(at)) {
                nearest = soul;
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] probe at {} on {} - nearest curated "
                + "soul {} ({} blocks)", BlockPos.containing(at).toShortString(), island,
                nearest == null ? "none" : nearest.summary(),
                nearest == null ? "-" : String.format(Locale.ROOT, "%.1f",
                        nearest.centre().distanceTo(at)));
        int heads = 0;
        for (SoulSkinLessons.Candidate head : nearbyHeads(player, 3.0)) {
            heads++;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] probe head at ({}, {}, {}) skin={}",
                    head.x(), head.y(), head.z(), skinUrl(head.texture()));
        }
        AABB box = new AABB(at, at).inflate(3.0);
        int entities = 0;
        for (Entity entity : minecraft.level.getEntities(player, box, e -> !e.isRemoved())) {
            entities++;
            String worn = entity instanceof ArmorStand stand
                    ? stand.getItemBySlot(EquipmentSlot.HEAD).toString()
                    : entity instanceof Display.ItemDisplay display
                            ? display.getItemStack().toString() : "-";
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] probe entity {} at {} "
                            + "invisible={} head/item={} skin={}",
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(entity.getType()),
                    entity.position(), entity.isInvisible(), worn,
                    skinUrl(headTextureOf(entity)));
        }
        SBSChat.send(Component.literal(" Soul probe: " + heads + " head(s), " + entities
                + " entit(ies) within 3 blocks - written to the log under [SBS][FairySouls].")
                .withColor(SBSChat.WHITE));
    }

    /** The texture URL inside a skin's base64 value, for a log a person can read. */
    private static String skinUrl(String texture) {
        if (texture == null) {
            return "-";
        }
        try {
            String json = new String(java.util.Base64.getDecoder().decode(texture),
                    java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"url\"\s*:\s*\"([^\"]+)\"").matcher(json);
            return m.find() ? m.group(1) : json;
        } catch (IllegalArgumentException e) {
            return texture;
        }
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick: notices island changes and keeps the tab-list read current. */
    public void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            lastIsland = "";
            tabCounts = null;
            return;
        }
        String island = SkyBlockLocation.island();
        if (!island.equals(lastIsland)) {
            lastIsland = island;
            tabCounts = null;
            // The island's coordinate space changed - anything targeting the old island's souls is
            // now pointing at nothing. The router re-picks on its own next tick.
            FairySoulRouting.getInstance().onIslandChanged();
        }
        readTabCounts(island);
        if (cfg().enabled) {
            expireCandidate();
            if (!IslandCatalog.isInstanced(island)) {
                scanBySight(minecraft, island);
            }
            // The Quest Log states per-island progress outright - the only signal that can retire a
            // whole island's markers without guessing at individual souls.
            FairySoulMenu.getInstance().tick();
        }
    }

    /**
     * Reads the tab list's "Fairy Souls: X/Y" line, and works out what it is counting.
     *
     * <p>Whether Hypixel puts the island's own progress there or an account-wide total is not
     * something to assume, so it is <b>derived</b>: if the denominator equals the number of souls the
     * data file has for this island, it is this island's; otherwise it is treated as account-wide.
     * That self-corrects if Hypixel changes the line, and needs no build to know the answer up front.
     */
    private void readTabCounts(String island) {
        for (String line : TabWidgets.lines()) {
            if (!line.toLowerCase(Locale.ROOT).contains("fairy soul")) {
                continue;
            }
            Matcher matcher = TAB_COUNT.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int collected = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            tabCounts = new int[]{collected, total};
            tabIsPerIsland = total > 0 && total == FairySoulDatabase.forIsland(island).size();
            return;
        }
        tabCounts = null;
    }

    // ------------------------------------------------------------------ reconciliation

    /** Formatting codes out, so a {@code contains()} sees the sentence Hypixel actually wrote. */
    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The tab list's collected count for the current island, or {@code -1} when unavailable. */
    public int islandCollectedFromTab() {
        int[] counts = tabCounts;
        return counts != null && tabIsPerIsland ? counts[0] : -1;
    }

    /**
     * The plain-language state of the record: what the game says versus what has been tracked.
     *
     * <p>Deliberately states both numbers rather than papering over the gap. A player with 180
     * collected and 12 tracked is not 12/273 done, and showing them 261 "uncollected" markers would
     * be a lie the feature never recovers from.
     *
     * <p>The Quest Log wins when it has been read: it is the only source that is both per-island and
     * stated by the server. The tab list is the fallback, and "open the Quest Log" is the answer when
     * neither has been seen.
     */
    public String reconciliation() {
        FairySoulStore store = FairySoulStore.getInstance();
        int tracked = store.trackedCount();
        int islandsDone = store.islandsDone().size();

        StringBuilder out = new StringBuilder();
        if (store.menuReadAt() > 0) {
            out.append("Quest Log: ").append(store.menuFoundOnIslands())
                    .append('/').append(store.menuTotalOnIslands()).append(" on islands");
        } else {
            int[] counts = tabCounts;
            if (counts != null) {
                out.append(tabIsPerIsland ? "This island: " : "Game says: ")
                        .append(counts[0]).append('/').append(counts[1]);
            } else {
                out.append("Game total: unknown");
            }
        }
        out.append("  ·  tracked locally: ").append(tracked);
        if (islandsDone > 0) {
            out.append(" (+").append(islandsDone).append(" island(s) complete)");
        }
        return out.toString();
    }

    /** Whether the Fairy Souls Guide has ever been read on this profile. */
    public boolean questLogRead() {
        return FairySoulStore.getInstance().menuReadAt() > 0;
    }

    /**
     * Whether the local record is meaningfully behind what the game reports, which is the state that
     * makes uncollected markers untrustworthy and is worth saying out loud in the settings.
     *
     * <p>Asked of the island underfoot, against the Quest Log's number for it where there is one. An
     * island the guide reported complete is already marked done, so it never reaches the comparison.
     */
    public boolean recordIncomplete() {
        String island = SkyBlockLocation.island();
        FairySoulStore store = FairySoulStore.getInstance();
        if (!island.isEmpty() && store.isIslandDone(island)) {
            return false;
        }
        FairySoulStore.Progress progress = store.menuProgress(island);
        if (progress != null) {
            return progress.found > collectedHereCount(island);
        }
        int[] counts = tabCounts;
        if (counts == null) {
            return false;
        }
        if (tabIsPerIsland) {
            return counts[0] > collectedHereCount(island);
        }
        return counts[0] > store.trackedCount();
    }

    /** How many souls on {@code island} are on record as collected. */
    public int collectedHereCount(String island) {
        FairySoulStore store = FairySoulStore.getInstance();
        int count = 0;
        for (FairySoul soul : FairySoulDatabase.forIsland(island)) {
            if (store.isCollected(soul.id, island)) {
                count++;
            }
        }
        return count;
    }

    /** The "I have already done this island" action, from the settings page. */
    public void markCurrentIslandDone(boolean done) {
        String island = SkyBlockLocation.island();
        if (island.isEmpty()) {
            SBSChat.send(Component.literal(" Not on a known island right now.").withColor(0xFF6B6B));
            return;
        }
        FairySoulStore.getInstance().markIslandDone(island, done);
        SBSChat.send(Component.literal(" " + island + (done
                ? " marked as fully collected." : " no longer marked as collected."))
                .withColor(SBSChat.PREFIX_COLOR));
        FairySoulRouting.getInstance().onIslandChanged();
    }
}
