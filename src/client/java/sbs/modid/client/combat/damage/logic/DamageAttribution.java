/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.DamageAttributionSettings;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Damage Attribution + LootShare tracker.
 *
 * <p>SkyBlock damage splashes are ownerless armor-stand text entities – the client is never told who
 * caused which splash. This module attributes them heuristically via <b>click + timer + value</b>:
 * every attack (melee swing, bow release, ability cast) opens a short window on the hit mob; a new
 * splash inside the window, near the target, whose value fits the weapon's <b>learned damage
 * profile</b>, is yours (plus up to {@code maxFerocityProcs} extra cluster hits for Ferocity).
 * Splashes near a whitelisted mob that match no window are foreign and get hidden (client-side
 * {@code setCustomNameVisible(false)} – purely cosmetic, no packets, nothing sent to the server).
 *
 * <p><b>Learned profile instead of a stat formula.</b> The client cannot read Strength / Crit Damage /
 * enchant multipliers live (they only exist in menu lore), so instead of predicting damage from a
 * formula the module <b>calibrates itself</b>: whenever you hit a whitelisted mob with no other player
 * within {@link #CONTEST_RADIUS} blocks, the splashes in your window are provably yours and their
 * values feed a per-weapon rolling profile (crit / non-crit medians). That profile then acts as the
 * expected damage {@code D_exp} with the configured tolerance band – and it automatically includes
 * every modifier a formula would have to model (enchants, armor bonuses, buffs, Minos-Inquisitor
 * stat debuffs re-learn within a few hits).
 *
 * <p><b>Confidence.</b> High while a profile exists for the held weapon (values discriminate);
 * without one, a splash in the window "could be yours", so foreign hiding backs off (configurable):
 * better to show a stranger's splash than to hide your own.
 *
 * <p><b>LootShare.</b> Own-attributed damage accumulates per mob (identified by its floating
 * nametag); crossing the eligibility share (wiki: 1% of max HP for mobs, 10% for slayer bosses,
 * within 30 blocks) fires one client-only chat alert (+ optional pling).
 */
public final class DamageAttribution {

    private static final DamageAttribution INSTANCE = new DamageAttribution();

    /** A splash value after colour-strip: optional crit stars around digits with , . k/M/B. */
    private static final Pattern SPLASH_NAME = Pattern.compile("^[✧✯]{0,2}([\\d.,]+[kKmMbB]?)[✧✯]{0,2}$");
    /** The "current/max❤" (or "max❤") tail of a mob nametag. */
    private static final Pattern NAMETAG_MAX_HP = Pattern.compile("(?:([\\d.,]+[kKmMbB]?)/)?([\\d.,]+[kKmMbB]?)\\s*❤");

    /** Search radius from a splash to the mob nametag it belongs to. */
    private static final double MOB_RADIUS = 8.0;
    /** A splash must appear within this of the clicked target to be matchable. */
    private static final double TARGET_RADIUS = 7.0;
    /** Another player inside this radius of the mob = contested (no calibration, lower confidence). */
    private static final double CONTEST_RADIUS = 12.0;
    /** Rolling calibration depth per weapon and hit kind. */
    private static final int CALIB_KEEP = 12;
    /** Forget a lootshare accumulator after this long without a new own hit. */
    private static final long LOOTSHARE_STALE_MS = 60_000L;

    /** Diana / Mythological creatures (floating-nametag fragments, case-insensitive). */
    private static final List<String> DIANA_MOBS = List.of(
            "Minos Inquisitor", "Minos Champion", "Minos Hunter", "Minotaur",
            "Gaia Construct", "Siamese Lynx", "Exalted Minos Inquisitor");

    /** Slayer bosses (floating-nametag fragments). */
    private static final List<String> SLAYER_BOSSES = List.of(
            "Revenant Horror", "Atoned Horror", "Tarantula Broodfather", "Sven Packmaster",
            "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend", "Bloodfiend");

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** One open attack window: who was clicked, when, with what, and how many hits it accepted. */
    private static final class AttackWindow {
        final long t0;
        final Entity target;          // null = untargeted (bow release / ability into the air)
        final Vec3 targetPos;         // position at click time (survives the entity dying)
        final String weaponId;
        /** Melee swing vs bow release / ability cast – the Melee Damage card only wants the former. */
        final boolean melee;
        int accepted;

        AttackWindow(long t0, Entity target, Vec3 targetPos, String weaponId, boolean melee) {
            this.t0 = t0;
            this.target = target;
            this.targetPos = targetPos;
            this.weaponId = weaponId;
            this.melee = melee;
        }
    }

    /** Rolling learned damage profile of one weapon. */
    private static final class Calibration {
        final Deque<Long> nonCrit = new ArrayDeque<>();
        final Deque<Long> crit = new ArrayDeque<>();
    }

    /** LootShare accumulator for one mob (keyed by its nametag armor stand id). */
    private static final class LootShare {
        String name = "";
        long maxHp;
        boolean slayer;
        long damage;
        boolean notified;
        long lastHitAt;
    }

    private final List<AttackWindow> windows = new ArrayList<>();
    private final Set<Integer> knownStands = new HashSet<>();
    private final Set<Integer> hiddenIds = new HashSet<>();
    private final Map<String, Calibration> calibrations = new HashMap<>();
    private final Map<Integer, LootShare> lootshare = new HashMap<>();
    private boolean wasEnabled;

    private DamageAttribution() {
    }

    public static DamageAttribution getInstance() {
        return INSTANCE;
    }

    private static DamageAttributionSettings cfg() {
        return ConfigManager.getInstance().get().damageAttribution;
    }

    // ------------------------------------------------------------------ input hooks

    /** The master-toggle keybind (from {@code CommandKeyMixin}). */
    public void onKeyPressed(int key) {
        DamageAttributionSettings cfg = cfg();
        if (cfg.toggleKey == 0 || key != cfg.toggleKey) {
            return;
        }
        cfg.enabled = !cfg.enabled;
        ConfigManager.getInstance().save();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal(cfg.enabled
                    ? "§aDamage Attribution ON" : "§7Damage Attribution off"));
        }
    }

    /** A melee attack on {@code target} (from the {@code attack} mixin hook). */
    public void onMeleeAttack(Entity target) {
        openWindow(target, true);
    }

    /** A bow release / ability cast – target is whatever the crosshair points at (may be nothing). */
    public void onRangedTrigger() {
        openWindow(Minecraft.getInstance().crosshairPickEntity, false);
    }

    private void openWindow(Entity target, boolean melee) {
        DamageAttributionSettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!cfg.enabled || player == null || minecraft.level == null) {
            return;
        }
        String weapon = SkyblockItem.id(player.getMainHandItem());
        if (weapon == null) {
            return;   // not a SkyBlock item - eating, vanilla junk, empty hand
        }
        Vec3 pos = target != null ? target.position() : null;
        windows.add(new AttackWindow(System.currentTimeMillis(), target, pos, weapon, melee));
        if (windows.size() > 8) {
            windows.remove(0);   // spam-click cap; oldest windows are expired anyway
        }
    }

    // ------------------------------------------------------------------ tick

    /** Called once per client tick: classifies new splashes, re-hides, prunes. */
    public void onClientTick(Minecraft minecraft) {
        DamageAttributionSettings cfg = cfg();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!cfg.enabled || level == null || player == null) {
            if (wasEnabled) {
                restoreAndClear(level);
            }
            return;
        }
        wasEnabled = true;
        long now = System.currentTimeMillis();
        windows.removeIf(w -> now - w.t0 > cfg.windowMs + 250L);
        pruneLootshare(level, now);

        // Re-assert hidden names each tick: a server metadata update would otherwise re-show them.
        for (Iterator<Integer> it = hiddenIds.iterator(); it.hasNext(); ) {
            Entity entity = level.getEntity(it.next());
            if (entity == null) {
                it.remove();
            } else {
                entity.setCustomNameVisible(false);
            }
        }
        knownStands.removeIf(id -> level.getEntity(id) == null);

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()
                    || !knownStands.add(stand.getId())) {
                continue;
            }
            classify(level, player, stand, now, cfg);
        }
    }

    /** One NEW armor stand: is it a damage splash, and if so - ours, foreign, or none of our business? */
    private void classify(ClientLevel level, LocalPlayer player, ArmorStand stand, long now,
                          DamageAttributionSettings cfg) {
        String raw = stand.getCustomName() != null ? stand.getCustomName().getString() : "";
        String clean = strip(raw).trim();
        Matcher splash = SPLASH_NAME.matcher(clean);
        if (!splash.matches()) {
            return;   // a mob nametag, a shop hologram, ... - not a damage splash
        }
        long value = parseValue(splash.group(1));
        if (value <= 0) {
            return;
        }
        boolean crit = clean.indexOf('✧') >= 0 || clean.indexOf('✯') >= 0;

        // The mob this splash belongs to - only whitelisted mobs are ever touched.
        ArmorStand mobTag = nearestWhitelistedMob(level, stand.position(), cfg);
        if (mobTag == null) {
            // Off the whitelist nothing may be hidden, marked, calibrated or counted towards
            // LootShare - those all rest on the whitelist. The Melee Damage card is read-only
            // though, so it still gets its number when the splash matches a melee window: that is
            // what makes the card work while grinding any mob, not just Diana / Slayer bosses.
            if (cfg.meleeHud) {
                AttackWindow window = bestWindow(stand.position(), value, crit, now, cfg);
                if (window != null && window.melee) {
                    window.accepted++;
                    MeleeDamageTracker.getInstance().record(value, crit);
                }
            }
            return;
        }
        boolean contested = isContested(level, player, mobTag.position());

        AttackWindow window = bestWindow(stand.position(), value, crit, now, cfg);
        if (window != null) {
            window.accepted++;
            onOwnSplash(player, stand, mobTag, value, crit, window, contested, now, cfg);
            return;
        }
        // Foreign - but only hide when we can actually tell ours apart (a profile for the held
        // weapon exists), or the user opted into hiding despite low confidence.
        boolean discriminating = hasProfile(SkyblockItem.id(player.getMainHandItem()));
        boolean uncontestedIdle = windows.isEmpty();   // we did not even click - clearly not ours
        if (cfg.hideForeign && (discriminating || uncontestedIdle || cfg.hideOnLowConfidence)) {
            hiddenIds.add(stand.getId());
            stand.setCustomNameVisible(false);
            debug(player, cfg, "§8hid foreign " + format(value) + " on " + mobName(mobTag));
        }
    }

    private void onOwnSplash(LocalPlayer player, ArmorStand stand, ArmorStand mobTag, long value,
                             boolean crit, AttackWindow window, boolean contested, long now,
                             DamageAttributionSettings cfg) {
        if (!contested) {
            calibrate(window.weaponId, value, crit);
        }
        if (cfg.markOwn && stand.getCustomName() != null) {
            stand.setCustomName(stand.getCustomName().copy().append(Component.literal(" §a✔")));
        }
        if (window.melee) {
            MeleeDamageTracker.getInstance().record(value, crit);
        }
        // A confirmed own hit with its real value - exactly what the Damage Overlay's
        // predicted-vs-real calibration needs.
        DamageEstimator.getInstance().onOwnSplash(mobTag, value, crit);
        trackLootshare(player, mobTag, value, now, cfg);
        debug(player, cfg, "§aown " + (crit ? "✧" : "") + format(value) + " §7on " + mobName(mobTag)
                + " (" + (now - window.t0) + "ms, hit " + window.accepted
                + (contested ? ", contested" : "") + ")");
    }

    // ------------------------------------------------------------------ window matching

    /**
     * The best-scoring open window this splash can belong to, or {@code null}. A window qualifies
     * when it is inside its time span, has cluster budget left (1 + Ferocity procs), the splash is
     * near the clicked target, and the value fits the weapon's learned profile (unknown profile =
     * value cannot refute). Score = timeWeight * timeScore + (1-timeWeight) * valueScore, per spec.
     */
    private AttackWindow bestWindow(Vec3 splashPos, long value, boolean crit, long now,
                                    DamageAttributionSettings cfg) {
        AttackWindow best = null;
        double bestScore = -1;
        double wTime = cfg.timeWeightPct / 100.0;
        for (AttackWindow window : windows) {
            long age = now - window.t0;
            if (age < 0 || age > cfg.windowMs || window.accepted > cfg.maxFerocityProcs) {
                continue;
            }
            Vec3 anchor = window.target != null && window.target.isAlive()
                    ? window.target.position() : window.targetPos;
            if (anchor != null && anchor.distanceTo(splashPos) > TARGET_RADIUS) {
                continue;
            }
            Double valueScore = valueScore(window.weaponId, value, crit, cfg);
            if (valueScore == null) {
                continue;   // profile exists and the value is clearly not ours
            }
            double score = wTime * (1.0 - age / (double) cfg.windowMs) + (1.0 - wTime) * valueScore;
            if (score > bestScore) {
                bestScore = score;
                best = window;
            }
        }
        return best;
    }

    /**
     * How well the value fits the weapon's learned profile: 1 = spot on, 0 = at the tolerance edge,
     * {@code null} = outside the band (refuted). With no profile yet the value cannot discriminate,
     * so it scores a neutral 0.5.
     */
    private Double valueScore(String weaponId, long value, boolean crit, DamageAttributionSettings cfg) {
        long expected = expectedDamage(weaponId, crit);
        if (expected <= 0) {
            return 0.5;
        }
        double deviation = Math.abs(value - expected) / (double) expected;
        double tolerance = cfg.tolerancePct / 100.0;
        return deviation <= tolerance ? 1.0 - deviation / tolerance : null;
    }

    // ------------------------------------------------------------------ calibration

    private void calibrate(String weaponId, long value, boolean crit) {
        if (weaponId == null) {
            return;
        }
        Calibration calib = calibrations.computeIfAbsent(weaponId, k -> new Calibration());
        Deque<Long> values = crit ? calib.crit : calib.nonCrit;
        values.addLast(value);
        while (values.size() > CALIB_KEEP) {
            values.removeFirst();
        }
    }

    private boolean hasProfile(String weaponId) {
        Calibration calib = weaponId == null ? null : calibrations.get(weaponId);
        return calib != null && (calib.nonCrit.size() >= 3 || calib.crit.size() >= 3);
    }

    /** The learned expected damage (median) for this weapon and hit kind, or 0 when unknown. */
    private long expectedDamage(String weaponId, boolean crit) {
        Calibration calib = weaponId == null ? null : calibrations.get(weaponId);
        if (calib == null) {
            return 0;
        }
        Deque<Long> primary = crit ? calib.crit : calib.nonCrit;
        Deque<Long> fallback = crit ? calib.nonCrit : calib.crit;
        Deque<Long> use = primary.size() >= 3 ? primary : (fallback.size() >= 3 ? fallback : primary);
        if (use.size() < 3) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(use);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    // ------------------------------------------------------------------ mobs + whitelist

    /** The nearest whitelisted mob nametag within {@link #MOB_RADIUS} of the splash, or null. */
    private static ArmorStand nearestWhitelistedMob(ClientLevel level, Vec3 pos,
                                                    DamageAttributionSettings cfg) {
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class,
                new AABB(pos, pos).inflate(MOB_RADIUS, 4, MOB_RADIUS),
                s -> s.hasCustomName() && s.getCustomName() != null
                        && s.getCustomName().getString().contains("❤"));
        ArmorStand best = null;
        double bestDist = Double.MAX_VALUE;
        for (ArmorStand stand : stands) {
            String name = strip(stand.getCustomName().getString());
            if (!isWhitelisted(name, cfg)) {
                continue;
            }
            double dist = stand.position().distanceToSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = stand;
            }
        }
        return best;
    }

    private static boolean isWhitelisted(String nametag, DamageAttributionSettings cfg) {
        String lower = nametag.toLowerCase(Locale.ROOT);
        if (cfg.dianaMobs && matchesAny(lower, DIANA_MOBS)) {
            return true;
        }
        if (cfg.slayerBosses && matchesAny(lower, SLAYER_BOSSES)) {
            return true;
        }
        if (cfg.extraMobs != null && !cfg.extraMobs.isBlank()) {
            for (String extra : cfg.extraMobs.split(",")) {
                String trimmed = extra.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && lower.contains(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesAny(String lower, List<String> names) {
        for (String name : names) {
            if (lower.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSlayer(String nametag) {
        return matchesAny(nametag.toLowerCase(Locale.ROOT), SLAYER_BOSSES);
    }

    /** Whether any OTHER player stands near the mob – then splashes there are ambiguous. */
    private static boolean isContested(ClientLevel level, LocalPlayer self, Vec3 mobPos) {
        for (var other : level.players()) {
            if (other != self && !other.isSpectator()
                    && other.position().distanceTo(mobPos) <= CONTEST_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static String mobName(ArmorStand mobTag) {
        String name = strip(mobTag.getCustomName() != null ? mobTag.getCustomName().getString() : "");
        // "[Lv750] Minos Inquisitor 40M/40M❤" -> "Minos Inquisitor"
        return name.replaceAll("\\[[^]]*]", "").replaceAll("[\\d.,]+[kKmMbB]?(/[\\d.,]+[kKmMbB]?)?\\s*❤", "")
                .replace("❤", "").trim();
    }

    /** The clean mob name of a nametag stand, for the Damage Overlay (shares one parser). */
    static String mobDisplayName(ArmorStand mobTag) {
        return mobName(mobTag);
    }

    /** Formatting-code strip, shared with the Damage Overlay's nametag parsing. */
    static String stripText(String text) {
        return strip(text);
    }

    // ------------------------------------------------------------------ lootshare

    private void trackLootshare(LocalPlayer player, ArmorStand mobTag, long value, long now,
                                DamageAttributionSettings cfg) {
        if (!cfg.lootshareEnabled) {
            return;
        }
        String nametag = strip(mobTag.getCustomName() != null ? mobTag.getCustomName().getString() : "");
        LootShare acc = lootshare.computeIfAbsent(mobTag.getId(), k -> new LootShare());
        acc.name = mobName(mobTag);
        acc.slayer = isSlayer(nametag);
        acc.lastHitAt = now;
        acc.damage += value;
        Matcher hp = NAMETAG_MAX_HP.matcher(nametag);
        if (hp.find()) {
            acc.maxHp = Math.max(acc.maxHp, parseValue(hp.group(2)));
        }
        int pct = acc.slayer ? cfg.lootsharePctSlayer : cfg.lootsharePctMobs;
        if (!acc.notified && acc.maxHp > 0 && acc.damage >= acc.maxHp * (long) pct / 100L) {
            acc.notified = true;
            player.sendSystemMessage(Component.literal("§8[§bSBS§8]§r §6[LootShare]§r threshold reached on §e"
                    + acc.name + "§r (" + format(acc.damage) + "/" + format(acc.maxHp) + ", " + pct + "%)"));
            if (cfg.lootshareSound) {
                player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.4f);
            }
        }
    }

    private void pruneLootshare(ClientLevel level, long now) {
        lootshare.entrySet().removeIf(entry -> {
            Entity mob = level.getEntity(entry.getKey());
            return mob == null || !mob.isAlive() || now - entry.getValue().lastHitAt > LOOTSHARE_STALE_MS;
        });
    }

    // ------------------------------------------------------------------ housekeeping

    /** Toggle off / world gone: re-show everything we hid and drop all per-session state. */
    private void restoreAndClear(ClientLevel level) {
        if (level != null) {
            for (int id : hiddenIds) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.setCustomNameVisible(true);
                }
            }
        }
        hiddenIds.clear();
        knownStands.clear();
        windows.clear();
        lootshare.clear();
        wasEnabled = false;
    }

    private static void debug(LocalPlayer player, DamageAttributionSettings cfg, String message) {
        if (cfg.debugLog) {
            player.sendSystemMessage(Component.literal("§8[§bSBS§8]§8[DmgAttr] " + message));
        }
    }

    // ------------------------------------------------------------------ parsing

    /** "1,234,567" / "1.2M" / "425k" → the long value (0 when unparseable). */
    static long parseValue(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        char last = text.charAt(text.length() - 1);
        double scale = switch (Character.toLowerCase(last)) {
            case 'k' -> 1_000.0;
            case 'm' -> 1_000_000.0;
            case 'b' -> 1_000_000_000.0;
            default -> 1.0;
        };
        String number = scale > 1.0 ? text.substring(0, text.length() - 1) : text;
        try {
            if (scale > 1.0) {
                return (long) (Double.parseDouble(number.replace(",", "")) * scale);
            }
            return Long.parseLong(number.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String format(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

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
}
