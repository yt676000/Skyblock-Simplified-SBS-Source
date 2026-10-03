/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.livid.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the one real Livid among the eight clones in the F5 / M5 boss room, once per client tick.
 * The renderer ({@code LividHighlight}) and the HUD card ({@code LividHud}) only read the cache this
 * builds - entity iteration never happens in the render pass, the same split the dungeon starred-mob
 * boxes and the Mob Highlight use.
 *
 * <p><b>How the real one is told apart.</b> Three signals, strongest first - the pick carries which
 * one produced it ({@link Identification#source()}) so the HUD and the box can say how sure they are:
 *
 * <ol>
 *   <li><b>Ceiling wool.</b> The arena names the real Livid itself: the wool in the boss-room ceiling
 *       is dyed the colour that Livid wears ({@link LividWool}). Available before anyone has landed a
 *       hit, which is exactly when it is wanted, and the only signal that works at all on the masks -
 *       see the health note below. Matched by <i>colour</i> rather than by looking a variant name up,
 *       and it only counts when it resolves a single Livid in the room: a ceiling that has moved, has
 *       not loaded, or names nobody present resolves nothing instead of pointing at the wrong clone.
 *       Once it has named someone the pick follows that entity, because in M5 the real Livid can
 *       change colour mid-fight.</li>
 *   <li><b>Damage.</b> The clones are invulnerable while the real one lives, so the first Livid whose
 *       health drops below its maximum <i>is</i> the real one. Proof, not a guess - once it fires the
 *       pick is locked in for the fight, and it overrides the ceiling.</li>
 *   <li><b>Odd health out.</b> Before anyone has hit anything, the clones all carry the same maximum
 *       health and the real Livid does not. When exactly one candidate's max health differs from the
 *       shared value of the rest, that one is shown - as a <i>guess</i>, which the HUD says out loud
 *       ({@link Identification#confirmed()}), so nobody dumps a Wither Impact into a clone believing
 *       the mod was sure.</li>
 * </ol>
 *
 * <p>Both health signals need an entity that carries the boss's health ({@link #bossHealth}); on the
 * NPC-player masks, which report a player's ten hearts, they say nothing and stay quiet rather than
 * inventing a pick. The order they are tried in is damage, then wool, then health - damage first
 * because it is the only one that cannot be wrong.
 *
 * <p>Until one of the three resolves, nothing is boxed and the card reads "searching". Label and
 * colour come off the entity's own name where that name says anything ("§c§lVendetta Livid"), so the
 * box wears the Livid's own colour. The Livids proper are NPC <i>players</i>, though, and a profile
 * name carries no formatting - for those the colour comes from {@link #MASK_COLORS}, the one place
 * variant names are written down and the table the ceiling wool is dyed to match.
 */
public final class LividTracker {

    private static final LividTracker INSTANCE = new LividTracker();

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** The floor Livid is the boss of (F5 and M5 share it). */
    private static final int LIVID_FLOOR = 5;

    /** How close a nametag colour has to be to the ceiling wool to be that wool's Livid. */
    private static final int MAX_COLOR_DISTANCE = 160;

    /** How far the runner-up has to be behind, so a near-tie names nobody. */
    private static final int MIN_COLOR_MARGIN = 80;

    /** Gap between ceiling sweeps while none has named a Livid (chunk still loading, wool not set). */
    private static final long WOOL_SCAN_INTERVAL_MS = 1000L;

    /** How long the fight's opening blindness has to be past before the ceiling is believed. */
    private static final long WOOL_SETTLE_MS = 2000L;

    /** Light purple: Livid's own colour, and never a clone's - worn when nothing else says. */
    private static final int DEFAULT_COLOR = 0xFFFF55FF;

    /** Each mask and the chat colour it is written in; see {@link #maskColor(String)}. */
    private static final Map<String, Character> MASK_COLORS = Map.of(
            "vendetta", 'f',
            "crossed", 'd',
            "purple", '5',
            "doctor", '7',
            "frog", '2',
            "smile", 'a',
            "arcade", 'e',
            "scream", '9',
            "hockey", 'c');

    /** One Livid in the room: the entity to draw on plus what its nametag said. */
    public record Candidate(LivingEntity entity, String label, int color, float health, float maxHealth) {
    }

    /** What produced the pick, strongest first. Only {@link #HEALTH} is a guess. */
    public enum Source {
        /** The candidate took damage: only the real Livid can. */
        DAMAGE,
        /** The ceiling wool is dyed this candidate's nametag colour. */
        WOOL,
        /** The candidate is the only one whose maximum health breaks the clones' shared value. */
        HEALTH
    }

    /** The current pick: which candidate is real, and what that is based on. */
    public record Identification(Candidate candidate, Source source) {

        /** Whether the pick is proof (damage or the ceiling wool) rather than a guess. */
        public boolean confirmed() {
            return source != Source.HEALTH;
        }
    }

    private volatile List<Candidate> candidates = List.of();
    private volatile Identification identification;

    /** Entity id of the confirmed (damage-proven) Livid; {@code -1} until one is proven. */
    private int confirmedId = -1;

    /** Max health each candidate was first seen with, so a hit shows up as a drop below it. */
    private final Map<Integer, Float> peakHealth = new HashMap<>();

    /** Entity id of the Livid the ceiling named; {@code -1} until the wool has resolved one. */
    private int woolPickId = -1;

    /** When the ceiling may be swept again, so an unloaded chunk is retried rather than polled. */
    private long nextWoolScanMs;

    /** When the fight's opening blindness was first seen, 0 if it never was (joined mid-fight). */
    private long blindnessAtMs;

    /** Fight clock: when the first Livid appeared, 0 while none has. */
    private long fightStartMs;

    /** The label already announced in chat, so the message fires once per fight. */
    private String announced;

    private LividTracker() {
    }

    public static LividTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    // ---- read by the renderer / HUD ----------------------------------------------------------

    /** The identified Livid, or {@code null} while the fight has not resolved one. */
    public Identification identification() {
        return identification;
    }

    /** Every Livid currently in the room (empty outside the fight). */
    public List<Candidate> candidates() {
        return candidates;
    }

    /** Seconds since the first Livid appeared, or 0 outside the fight. */
    public int fightSeconds() {
        return fightStartMs == 0 ? 0 : (int) ((System.currentTimeMillis() - fightStartMs) / 1000L);
    }

    /** Whether the tracker is live right now (toggle on, in the Livid boss room, Livids present). */
    public boolean active() {
        return !candidates.isEmpty();
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Called every client tick; refreshes the candidate list and the pick. */
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!cfg().lividTracker || level == null || player == null || !inLividRoom()) {
            reset();
            return;
        }

        List<Candidate> found = collect(level, player);
        if (found.isEmpty()) {
            reset();
            return;
        }
        if (fightStartMs == 0) {
            fightStartMs = System.currentTimeMillis();
        }
        if (blindnessAtMs == 0 && player.hasEffect(MobEffects.BLINDNESS)) {
            blindnessAtMs = System.currentTimeMillis();
        }
        candidates = found;
        identification = identify(found, level);
        announce(player);
    }

    /** Clears the fight state (left the room, fight over, toggle off). */
    private void reset() {
        if (candidates.isEmpty() && fightStartMs == 0 && identification == null) {
            return;
        }
        candidates = List.of();
        identification = null;
        confirmedId = -1;
        peakHealth.clear();
        woolPickId = -1;
        nextWoolScanMs = 0;
        blindnessAtMs = 0;
        fightStartMs = 0;
        announced = null;
    }

    /** In the Catacombs F5/M5 boss room: floor 5 and the state manager's BOSS phase. */
    private static boolean inLividRoom() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return state.inDungeon() && state.floorNumber() == LIVID_FLOOR
                && state.phase() == sbs.modid.client.dungeons.events.DungeonEvents.Phase.BOSS;
    }

    /**
     * Every living Livid in the room. Three encodings are handled, because Hypixel uses more than one:
     * the name sitting on the mob itself, a floating nametag stand above it (the same stand→mob
     * association the Mob Highlight uses, reused rather than copied), and - the one the Livids
     * themselves come in - an NPC <i>player</i> entity carrying the mask name as its profile name.
     */
    private static List<Candidate> collect(ClientLevel level, LocalPlayer self) {
        List<Candidate> found = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();

        // The masks: player entities with no custom name and no colour codes on them at all.
        for (Player player : level.players()) {
            String name = player.getName().getString();
            if (player == self || !isLividNpc(name) || !player.isAlive() || seen.contains(player.getId())) {
                continue;
            }
            seen.add(player.getId());
            found.add(new Candidate(player, label(name), color(name), player.getHealth(), player.getMaxHealth()));
        }

        // Nametagged mobs and floating nametag stands, for anything the room labels the usual way.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living instanceof Player
                    || !living.hasCustomName()) {
                continue;
            }
            Component custom = living.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            if (!isLividTag(raw)) {
                continue;
            }
            LivingEntity target = living instanceof ArmorStand stand
                    ? sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker.mobBelow(level, stand)
                    : living;
            if (target == null || !target.isAlive() || seen.contains(target.getId())) {
                continue;
            }
            seen.add(target.getId());
            found.add(new Candidate(target, label(raw), color(raw), target.getHealth(), target.getMaxHealth()));
        }
        return found;
    }

    private static boolean isLividTag(String raw) {
        return raw.toLowerCase(Locale.ROOT).contains("livid");
    }

    /**
     * Whether a player entity is one of the Livids rather than somebody in the party. The mask name
     * arrives as a profile name, and a profile name is the one thing a real account cannot fake: they
     * are a single word, so "Vendetta Livid" belongs to nobody. The bare "Livid" the boss wears is let
     * through as well - it costs a party member who is actually called that a box, which is a cheaper
     * mistake than losing the boss the moment it drops its mask.
     */
    private static boolean isLividNpc(String name) {
        return isLividTag(name) && (name.indexOf(' ') >= 0 || name.equalsIgnoreCase("livid"));
    }

    /**
     * Picks the real Livid: the damage-proven one, else the one the ceiling wool names, else the
     * single candidate whose maximum health differs from the value all the others share.
     */
    private Identification identify(List<Candidate> found, ClientLevel level) {
        // 1. Damage proof - permanent for the fight once seen.
        for (Candidate candidate : found) {
            if (!bossHealth(candidate)) {
                continue;
            }
            int id = candidate.entity().getId();
            float peak = peakHealth.merge(id, candidate.maxHealth(), Math::max);
            if (confirmedId == -1 && candidate.health() < peak - 0.5f) {
                confirmedId = id;
            }
        }
        if (confirmedId != -1) {
            for (Candidate candidate : found) {
                if (candidate.entity().getId() == confirmedId) {
                    return new Identification(candidate, Source.DAMAGE);
                }
            }
            confirmedId = -1; // the proven one is gone (dead / despawned): fall through to the guess
        }

        // 2. Ceiling wool - the arena's own tell, available from the first tick of the fight.
        Candidate wool = byWool(found, level);
        if (wool != null) {
            return new Identification(wool, Source.WOOL);
        }

        // 3. Odd health out - only when exactly one candidate breaks an otherwise shared value.
        List<Candidate> scored = new ArrayList<>(found.size());
        for (Candidate candidate : found) {
            if (bossHealth(candidate)) {
                scored.add(candidate);
            }
        }
        if (scored.size() < 3) {
            return null; // too few to have a majority to differ from
        }
        Map<Float, Integer> counts = new HashMap<>();
        for (Candidate candidate : scored) {
            counts.merge(candidate.maxHealth(), 1, Integer::sum);
        }
        Candidate odd = null;
        for (Candidate candidate : scored) {
            if (counts.get(candidate.maxHealth()) == 1) {
                if (odd != null) {
                    return null; // more than one outlier: no honest pick
                }
                odd = candidate;
            }
        }
        return odd == null ? null : new Identification(odd, Source.HEALTH);
    }

    /**
     * The candidate the ceiling wool names: the one whose nametag colour is closest to a wool colour
     * up there, and only when it is closest by a clear margin.
     *
     * <p>Nearest-with-a-margin rather than an exact colour equality on purpose. A dye and a chat
     * colour are the same colour by eye but not to the bit (grey wool against §7, lime wool against
     * §a), so demanding equality would throw away good reads; demanding a clear winner keeps the
     * near-misses honest - two Livids about equally close to the ceiling means "no idea", not a coin
     * flip. A wool colour no Livid in the room wears lands outside {@link #MAX_COLOR_DISTANCE} and
     * resolves nothing, which is how a moved or misread ceiling stays harmless.
     *
     * <p>Once the ceiling has named someone the pick follows that <i>entity</i>, not the colour: in M5
     * the real Livid can change colour mid-fight, so the wool is the introduction, not a live readout.
     */
    private Candidate byWool(List<Candidate> found, ClientLevel level) {
        if (woolPickId != -1) {
            for (Candidate candidate : found) {
                if (candidate.entity().getId() == woolPickId) {
                    return candidate;
                }
            }
            woolPickId = -1; // the named one is gone: read the ceiling again
        }

        long now = System.currentTimeMillis();
        if (now < nextWoolScanMs || !ceilingSettled(now)) {
            return null;
        }
        nextWoolScanMs = now + WOOL_SCAN_INTERVAL_MS;
        List<Integer> woolColors = LividWool.ceilingColors(level);
        if (woolColors.isEmpty()) {
            return null;
        }

        Candidate best = null;
        int bestDistance = Integer.MAX_VALUE;
        int runnerUpDistance = Integer.MAX_VALUE;
        for (Candidate candidate : found) {
            int distance = Integer.MAX_VALUE;
            for (int wool : woolColors) {
                distance = Math.min(distance, colorDistance(wool, candidate.color()));
            }
            if (distance < bestDistance) {
                runnerUpDistance = bestDistance;
                bestDistance = distance;
                best = candidate;
            } else if (distance < runnerUpDistance) {
                runnerUpDistance = distance;
            }
        }
        boolean clear = bestDistance <= MAX_COLOR_DISTANCE
                && runnerUpDistance - bestDistance >= MIN_COLOR_MARGIN;
        if (!clear) {
            return null; // nothing up there names anyone yet - sweep again in a second
        }
        woolPickId = best.entity().getId();
        return best;
    }

    /**
     * Whether the ceiling is worth reading yet. The fight opens with blindness and the wool only
     * becomes the current fight's answer a moment later - read too early and the block still holds
     * the previous run's colour, which would be cached as gospel. So: nothing in the first couple of
     * seconds, and nothing until the opening blindness is that far behind either.
     */
    private boolean ceilingSettled(long now) {
        if (fightStartMs != 0 && now - fightStartMs < WOOL_SETTLE_MS) {
            return false;
        }
        return blindnessAtMs == 0 || now - blindnessAtMs >= WOOL_SETTLE_MS;
    }

    /**
     * Whether a candidate's health is the boss's own rather than a player's twenty. Both health
     * signals hang off this: an NPC player reports ten hearts like everyone else, so a Livid wearing
     * one has no health to read - "took damage" and "odd one out" would both be noise on it, and noise
     * that outranks the ceiling is worse than no signal at all.
     */
    private static boolean bossHealth(Candidate candidate) {
        return candidate.maxHealth() > 20f;
    }

    /** Straight-line distance between two ARGB colours in RGB space (0 = identical, 441 = max). */
    private static int colorDistance(int first, int second) {
        int dr = ((first >> 16) & 0xFF) - ((second >> 16) & 0xFF);
        int dg = ((first >> 8) & 0xFF) - ((second >> 8) & 0xFF);
        int db = (first & 0xFF) - (second & 0xFF);
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** One client-side chat line per fight, the moment a Livid is picked (nothing is ever sent). */
    private void announce(LocalPlayer player) {
        Identification current = identification;
        if (!cfg().lividChatMessage || current == null) {
            return;
        }
        String label = current.candidate().label();
        if (label.equals(announced)) {
            return;
        }
        announced = label;
        String note = switch (current.source()) {
            case DAMAGE -> " §a(confirmed)";
            case WOOL -> " §a(ceiling wool)";
            case HEALTH -> " §e(best guess)";
        };
        player.sendSystemMessage(Component.literal("§8[§bSBS§8]§r §dLivid: §f" + label + note));
    }

    // ---- nametag parsing ----------------------------------------------------------------------

    /** The readable name in a Livid nametag ("§c§lVendetta Livid§r §a4M❤" → "Vendetta Livid"). */
    public static String label(String raw) {
        String core = raw.replaceAll(SECTION_SIGN + ".", "");
        core = core.replaceAll("[^A-Za-z ]", " ").replaceAll("\\s+", " ").trim();
        int index = core.toLowerCase(Locale.ROOT).indexOf("livid");
        if (index < 0) {
            return "Livid";
        }
        return core.substring(0, index + "livid".length()).trim();
    }

    /**
     * The colour a Livid wears, as ARGB: the last colour code before "Livid" when the name is
     * formatted, the mask's own colour when it is not. Reading the tag first means a renamed or newly
     * added clone still draws in its own colour rather than a wrong one; the mask table below only
     * has to answer for the plain names.
     */
    public static int color(String raw) {
        int name = raw.toLowerCase(Locale.ROOT).indexOf("livid");
        int argb = 0;
        for (int i = 0; i + 1 < raw.length() && (name < 0 || i < name); i++) {
            if (raw.charAt(i) == SECTION_SIGN) {
                int code = chatColor(Character.toLowerCase(raw.charAt(i + 1)));
                if (code != 0) {
                    argb = code;
                }
            }
        }
        return argb != 0 ? argb : maskColor(raw);
    }

    /**
     * The colour each mask is written in. Needed because the Livids are NPC players: a profile name
     * carries no formatting, so there is nothing on the entity to read and the nine masks have to be
     * spelled out once. This is the table the ceiling wool is dyed to match - it is what makes the
     * wool worth reading at all - and it is the only place variant names appear; everything else works
     * off the colour it produces, so a mask that is not in here still tracks, just without the wool.
     */
    private static int maskColor(String raw) {
        String name = raw.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Character> mask : MASK_COLORS.entrySet()) {
            if (name.contains(mask.getKey())) {
                return chatColor(mask.getValue());
            }
        }
        return DEFAULT_COLOR;
    }

    /**
     * Vanilla chat colour code → ARGB, or 0 for a formatting code (§l, §r, …). Shared with
     * {@link LividWool} so a dyed ceiling and a nametag are compared in one and the same palette.
     */
    static int chatColor(char code) {
        return switch (code) {
            case '0' -> 0xFF000000;
            case '1' -> 0xFF0000AA;
            case '2' -> 0xFF00AA00;
            case '3' -> 0xFF00AAAA;
            case '4' -> 0xFFAA0000;
            case '5' -> 0xFFAA00AA;
            case '6' -> 0xFFFFAA00;
            case '7' -> 0xFFAAAAAA;
            case '8' -> 0xFF555555;
            case '9' -> 0xFF5555FF;
            case 'a' -> 0xFF55FF55;
            case 'b' -> 0xFF55FFFF;
            case 'c' -> 0xFFFF5555;
            case 'd' -> 0xFFFF55FF;
            case 'e' -> 0xFFFFFF55;
            case 'f' -> 0xFFFFFFFF;
            default -> 0;
        };
    }
}
