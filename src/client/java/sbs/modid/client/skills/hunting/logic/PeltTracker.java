/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Trapper's pelt hunt: knows when Trevor has sent you after an animal, finds that animal among
 * the desert's ordinary livestock, and hands the renderer one target to box and trace.
 *
 * <p><b>The quest is chat-driven.</b> Trevor announces every hunt ("You can find your TRACKABLE
 * animal near the Oasis!") and that line carries the two facts that matter: that a hunt is on, and
 * which rarity the animal is. The quest ends on the pelt payout, on Trevor's next announcement, or
 * on its own 10-minute limit - Hypixel's, not ours: the animal despawns then whatever chat said.
 * Nothing is scanned while no hunt is active, so the whole feature costs nothing outside one.
 *
 * <p><b>The rarity is the fingerprint.</b> The quest animal is an ordinary species - Cow, Pig,
 * Sheep, Rabbit, Chicken or Horse - dressed with the standard SkyBlock nametag, standing among real
 * livestock of the same species. What tells it apart is its <b>max health</b>: each rarity tier has
 * a fixed one, and no ambient desert animal carries those values. So the scan reads every animal
 * nametag near the player, parses the max health, and accepts the one matching the active rarity's
 * health - falling back to any known trapper health when the rarity line was reworded, and logging
 * what it saw either way ({@code [SBS][Pelt]}) so the numbers can be re-pinned from the log when
 * Hypixel retunes them.
 *
 * <p><b>Invisibility is expected.</b> Higher tiers vanish (that is their gimmick), but the nametag
 * stand stays - so the target is remembered by its stand, and when no mob body is resolvable under
 * it the renderer boxes the stand's own position instead. The tracker never goes blind mid-hunt.
 */
public final class PeltTracker {

    private static final PeltTracker INSTANCE = new PeltTracker();

    /** Hypixel's own hunt window; the animal runs away after this whatever chat said. */
    private static final long QUEST_LIMIT_MS = 10 * 60_000L;

    /** How far around the player nametags are scanned. */
    private static final double SCAN_RADIUS = 64.0;

    /** Ticks between scans while the target is not yet found (once found it is just re-validated). */
    private static final int SCAN_INTERVAL_TICKS = 10;

    private static final long LOG_INTERVAL_MS = 5_000L;

    /**
     * Trevor's announcement. The rarity word and the area are captured loosely - Hypixel has
     * reworded NPC lines before, and the sentence shape ("find your X animal near Y") is the part
     * that has held still.
     */
    private static final Pattern QUEST_START = Pattern.compile(
            "(?i)\\[NPC]\\s*Trevor\\s*:.*?find your\\s+(\\w+)\\s+animal\\s+near\\s+(?:the\\s+)?([^.!]+)");

    /** The payout line ("+4 Pelts") - the one signal that always means the hunt is over. */
    private static final Pattern PELTS_GAINED = Pattern.compile("(?i)\\+\\s*\\d+\\s+pelts?\\b");

    /**
     * Trevor stating the cooldown himself. Authoritative: he knows the real remaining time, so this
     * overrides whatever the local countdown assumed.
     *
     * <p>Matched loosely on purpose - the first version required the literal shape
     * {@code "coming back in 14s"} and never fired on the real line, which both words the wait
     * differently and used to arrive with colour codes still embedded (see {@link #onChat}). Now any
     * Trevor line about coming back / waiting / cooldown counts, and the time is read separately by
     * {@link #COOLDOWN_TIME} so "1m 30s", "90 seconds" and "in about 30s" all parse. Trevor lines
     * that mention none of this are logged verbatim, so a wording this still misses can be pinned
     * from the log alone.
     */
    private static final Pattern COOLDOWN_HINT = Pattern.compile(
            "(?i)\\b(?:com(?:e|ing)\\s+back|cooldown|wait|another\\s+animal|new\\s+animal)\\b");

    /** Any run of "Nm" / "Ns" / "N minutes" / "N seconds" in the line; all found runs are summed. */
    private static final Pattern COOLDOWN_TIME = Pattern.compile(
            "(?i)(\\d+)\\s*(m(?:in(?:ute)?s?)?|s(?:ec(?:ond)?s?)?)\\b");

    /** A line spoken by Trevor - the only lines the cooldown may ever be read from. */
    private static final Pattern TREVOR_LINE = Pattern.compile("(?i)\\[NPC]\\s*Trevor\\s*:");

    /**
     * The quest animal's nametag, e.g. {@code [Lv2] ✿ Untrackable Horse 955/1,000❤}.
     *
     * <p><b>The rarity word IS the fingerprint.</b> Hypixel writes the tier straight into the tag,
     * and no ambient animal is called "Untrackable" - so the word alone identifies the target, on
     * every tier and every species, with nothing to keep in sync.
     *
     * <p>Everything else in the tag is deliberately not required. An earlier version demanded the
     * species immediately after the level and matched nothing at all, because the real tag carries a
     * marker glyph and the rarity word in between; and it fingerprinted by max health against a
     * table that turned out wrong (an Untrackable Horse reads 1,000, where the table said 500).
     * Both were things that could drift - the rarity word has not.
     */
    private static final Pattern RARITY_TAG = Pattern.compile(
            "(?i)\\[Lv\\s*\\d+].*?\\b(TRACKABLE|UNTRACKABLE|UNDETECTED|ENDANGERED|ELUSIVE)\\b");

    /** The species, read for the label only - a new animal type must never stop detection. */
    private static final Pattern SPECIES =
            Pattern.compile("(?i)\\b(Cow|Pig|Sheep|Rabbit|Chicken|Horse)\\b");

    /** {@code 955/1,000❤} - current and max, for the label only. */
    private static final Pattern HEALTH =
            Pattern.compile("([\\d,.]+)\\s*/\\s*([\\d,.]+[km]?)\\s*❤", Pattern.CASE_INSENSITIVE);

    /**
     * One found target: the entity carrying the nametag (always present - a stand, or the animal
     * itself when Hypixel names it directly) and the animal body under it, which is {@code null}
     * while an elusive tier has faded out.
     */
    public record Target(Entity tag, LivingEntity mob, String rarity, String species) {

        /** The box to draw: the animal's own when there is a body, else one at the nametag. */
        public AABB box() {
            if (mob != null && mob.isAlive()) {
                return mob.getBoundingBox();
            }
            Vec3 pos = tag.position();
            return new AABB(pos.x - 0.5, pos.y - 1.5, pos.z - 0.5, pos.x + 0.5, pos.y, pos.z + 0.5);
        }

        /** {@code UNTRACKABLE Horse}, or just the rarity when the species was not in the tag. */
        public String label() {
            String tier = rarity == null ? "?" : rarity.toUpperCase(Locale.ROOT);
            return species == null ? tier : tier + " " + species;
        }

        boolean alive() {
            return tag.isAlive();
        }
    }

    private volatile String questRarity;
    private volatile String questArea = "";
    private volatile long questStartedAt;
    private volatile Target target;
    private boolean foundAnnounced;

    /** When Trevor will hand out the next hunt; 0 when nothing is on cooldown. */
    private volatile long cooldownEndsAt;

    private int tickCounter;
    private long lastLogAt;

    private PeltTracker() {
    }

    public static PeltTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.PeltSettings cfg() {
        return ConfigManager.getInstance().get().pelt;
    }

    // ---- read by the renderer -------------------------------------------------------------------

    /** Whether a hunt is currently active (within Hypixel's own 10-minute window). */
    public boolean questActive() {
        return questStartedAt != 0
                && System.currentTimeMillis() - questStartedAt < QUEST_LIMIT_MS;
    }

    /**
     * The detected quest animal, or {@code null} while none is in range.
     *
     * <p>Deliberately NOT gated on {@link #questActive()}. The rarity nametag is proof enough on its
     * own, and requiring the chat line meant an animal standing in front of you went unboxed whenever
     * the announcement was missed - after a rejoin, a chat clear, or simply a reworded line.
     */
    public Target target() {
        return target;
    }

    /** The rarity Trevor announced (lowercase), or {@code null} outside a hunt. */
    public String questRarity() {
        return questActive() ? questRarity : null;
    }

    /** The area Trevor named, as he said it - display only, never used for detection. */
    public String questArea() {
        return questArea;
    }

    // ---- chat -----------------------------------------------------------------------------------

    /**
     * Called for every chat line: starts a hunt on Trevor's announcement, ends it on the payout,
     * and reads the cooldown off anything Trevor says about waiting.
     *
     * <p><b>Colour codes are stripped first.</b> Hypixel intersperses §-codes mid-line
     * ({@code "§e[NPC] Trevor§f: §r…"}), so every pattern here silently failed against the raw
     * text - which is why the trapper readout stayed on "Talk to check" even while the cooldown
     * stood right there in chat. The same trap the slayer SLAIN line already paid for once.
     */
    public void onChat(String text) {
        if (text == null || !cfg().enabled) {
            return;
        }
        String line = text.replaceAll("§.", "");
        Matcher start = QUEST_START.matcher(line);
        if (start.find()) {
            questRarity = start.group(1).toLowerCase(Locale.ROOT);
            questArea = start.group(2).trim();
            questStartedAt = System.currentTimeMillis();
            target = null;
            foundAnnounced = false;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pelt] hunt started: rarity={} area={}",
                    questRarity, questArea);
            return;
        }
        if (TREVOR_LINE.matcher(line).find() && readTrevorCooldown(line)) {
            return;
        }
        if (questStartedAt != 0 && PELTS_GAINED.matcher(line).find()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pelt] hunt over (pelts paid out)");
            // Assume the configured length; the moment Trevor states the real remaining time the
            // line above replaces this. Marked as an assumption so the readout can say so - the
            // default is a guess, and presenting a guess as a countdown is what made this useless.
            cooldownEndsAt = System.currentTimeMillis() + cfg().cooldownSeconds * 1000L;
            cooldownStated = false;
            clearQuest();
        }
    }

    /**
     * Reads a stated cooldown out of one of Trevor's lines, {@code true} when it booked one.
     *
     * <p>Wait-flavoured lines with a time become the authoritative countdown, minutes and seconds
     * summed so "1m 30s" and "90 seconds" mean the same thing. Every OTHER Trevor line is logged
     * verbatim: his exact wording is the one thing that cannot be known from here, so the log is
     * how a phrasing this still misses gets pinned - the reason the readout ever stuck at
     * "Talk to check" was a pattern nobody could check against reality.
     */
    private boolean readTrevorCooldown(String line) {
        boolean waitFlavoured = COOLDOWN_HINT.matcher(line).find();
        if (waitFlavoured) {
            long totalSeconds = 0;
            Matcher time = COOLDOWN_TIME.matcher(line);
            while (time.find()) {
                long value = Long.parseLong(time.group(1));
                boolean minutes = Character.toLowerCase(time.group(2).charAt(0)) == 'm';
                totalSeconds += minutes ? value * 60 : value;
            }
            if (totalSeconds > 0) {
                cooldownEndsAt = System.currentTimeMillis() + totalSeconds * 1000L;
                cooldownStated = true;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pelt] Trevor stated cooldown: {}s ('{}')",
                        totalSeconds, line);
                return true;
            }
        }
        // A Trevor line that booked nothing - the raw material for fixing the patterns.
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pelt] Trevor line not understood: '{}'", line);
        return false;
    }

    /** Milliseconds until Trevor hands out the next hunt, or 0 when he is ready now. */
    public long cooldownRemainingMs() {
        return Math.max(0, cooldownEndsAt - System.currentTimeMillis());
    }

    /**
     * What the trapper readout is allowed to claim.
     *
     * <p>{@link #UNKNOWN} exists because the client cannot see Trevor's cooldown - it only learns
     * about it by watching chat. Before anything has been observed there is genuinely no information,
     * and the readout used to render that as <b>"Ready"</b>: {@code cooldownEndsAt} sits at 0, so the
     * remaining time was 0, so it said ready from the moment you logged in whether he was or not.
     * An unknown state has to be its own thing rather than a zero that reads as good news.
     */
    public enum TrapperState {
        /** Nothing observed yet this session - talking to Trevor is what reveals it. */
        UNKNOWN,
        /** A hunt is running, so he has nothing to hand out regardless of any cooldown. */
        HUNTING,
        /** A cooldown is counting down. */
        COOLING,
        /** A cooldown we actually tracked has run out. */
        READY
    }

    /**
     * The trapper readout: the state, the milliseconds left while {@link TrapperState#COOLING}, and
     * whether that number came from Trevor or from the configured assumption.
     */
    public record TrapperStatus(TrapperState state, long remainingMs, boolean stated) {
    }

    /** Whether the current countdown length came from Trevor's own line rather than the setting. */
    private volatile boolean cooldownStated;

    /**
     * What to show for Trevor right now.
     *
     * <p>Deliberately conservative: "Ready" is only ever claimed off a cooldown this client watched
     * start and finish. Everything else the readout might want to say - he is probably ready, it has
     * been a while - would be a guess dressed as a fact, and a wrong "Ready" is the one failure that
     * makes the whole feature useless.
     */
    public TrapperStatus trapperStatus() {
        if (questActive()) {
            return new TrapperStatus(TrapperState.HUNTING, 0, false);
        }
        if (cooldownEndsAt == 0) {
            return new TrapperStatus(TrapperState.UNKNOWN, 0, false);
        }
        long remaining = cooldownRemainingMs();
        return remaining > 0
                ? new TrapperStatus(TrapperState.COOLING, remaining, cooldownStated)
                : new TrapperStatus(TrapperState.READY, 0, cooldownStated);
    }

    private void clearQuest() {
        questRarity = null;
        questArea = "";
        questStartedAt = 0;
        target = null;
        foundAnnounced = false;
    }

    // ---- tick -----------------------------------------------------------------------------------

    /**
     * Called every client tick; the scan itself runs on the {@value #SCAN_INTERVAL_TICKS} throttle.
     *
     * <p>The scan runs whenever the module is on, not only during a known hunt: a rarity nametag is
     * self-identifying, so an animal in front of you is boxed whether or not the announcement was
     * seen. The quest state still expires on its own so the HUD rarity does not go stale.
     */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        if (questStartedAt != 0 && !questActive()) {
            clearQuest();   // the 10-minute window ran out with no payout line
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || !onFarmingIslands()) {
            // Warped away mid-hunt: the quest stays armed (you can come back), but a Cow in the
            // Hub must never be boxed as the trapper animal.
            target = null;
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        scan(level, player);
    }

    /**
     * Whether the player is on the Farming Islands (where the whole hunt lives). An unknown location -
     * neither the tab list nor the scoreboard readable this moment - counts as "still there": the hunt
     * gate above already limits scanning to an active quest, and going dark on a hiccup would drop the
     * box mid-chase.
     */
    private static boolean onFarmingIslands() {
        return SkyBlockLocation.island().isEmpty()
                || SkyBlockLocation.onIsland("The Farming Islands");
    }

    /**
     * One sweep over the nearby animal nametags. The current target is kept while its stand is
     * still alive (no re-scan churn); otherwise the best-matching candidate becomes the target:
     * exact rarity health first, any known trapper health as the fallback.
     */
    private void scan(ClientLevel level, LocalPlayer player) {
        Target current = target;
        if (current != null && current.alive()) {
            // Re-resolve the body under the kept tag: an elusive animal fades in and out, and the
            // box should follow the body whenever there is one.
            target = new Target(current.tag(), bodyOf(level, current.tag()),
                    current.rarity(), current.species());
            return;
        }
        target = null;

        // Every custom-named entity, not just armor stands: Hypixel names some of these animals
        // directly, and a stand-only sweep simply never saw those.
        AABB area = player.getBoundingBox().inflate(SCAN_RADIUS);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, area,
                e -> e.isAlive() && e.hasCustomName())) {
            String plain = plainName(entity);
            Matcher rarity = RARITY_TAG.matcher(plain);
            if (!rarity.find()) {
                continue;
            }
            Matcher species = SPECIES.matcher(plain);
            target = new Target(entity, bodyOf(level, entity),
                    rarity.group(1).toLowerCase(Locale.ROOT),
                    species.find() ? species.group(1) : null);
            announceFound(player, plain);
            return;
        }
    }

    /**
     * The animal body a nametag belongs to: the mob under it when the tag is a separate stand,
     * otherwise the tagged entity itself.
     */
    private static LivingEntity bodyOf(ClientLevel level, Entity tag) {
        if (tag instanceof ArmorStand stand) {
            return MobHighlightTracker.mobBelow(level, stand);
        }
        return tag instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    private static String plainName(Entity entity) {
        Component name = entity.getCustomName();
        return name == null ? "" : name.getString().replaceAll("§.", "");
    }

    /** First detection of this animal: one chat line + ping, then never again for this hunt. */
    private void announceFound(LocalPlayer player, String tagText) {
        if (foundAnnounced) {
            return;
        }
        foundAnnounced = true;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pelt] animal found: {}", tagText);
        if (!cfg().foundPing) {
            return;
        }
        player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
        player.sendSystemMessage(Component.literal("§8[§bSBS§8]§r §6Animal found §7- " + tagText));
    }
}
