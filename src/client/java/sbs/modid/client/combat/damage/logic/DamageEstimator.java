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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.damage.model.MobCombatCatalog;
import sbs.modid.client.combat.damage.model.MobFamily;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.DamageOverlaySettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Damage Overlay's brain: finds the mob under the crosshair, reads its nametag (name, level,
 * current/max ❤), and runs the {@link DamageCalculator} against the held weapon and the captured
 * player stats – so Execute / Prosecute / Giant Killer react live to the mob's HP bar.
 *
 * <p><b>Self-calibration.</b> Predicted and real damage drift apart through everything the client
 * cannot see (the mob's Defense, pet perks, armor passives, potions). Whenever Damage Attribution
 * confirms one of your own splashes on the estimated mob, the ratio real/predicted feeds a per-mob
 * rolling median that multiplies future estimates – the same "learn from provably-own hits" idea
 * that module already uses, applied to the prediction instead of the matcher.
 */
public final class DamageEstimator {

    private static final DamageEstimator INSTANCE = new DamageEstimator();

    /** "[Lv750]" (any bracket style Hypixel uses around the level). */
    private static final Pattern LEVEL = Pattern.compile("\\[Lv\\.?\\s*(\\d+)]");
    /** "cur/max❤" or a lone "max❤" (a full mob's tag sometimes omits the pair). */
    private static final Pattern NAMETAG_HP =
            Pattern.compile("(?:([\\d.,]+[kKmMbB]?)/)?([\\d.,]+[kKmMbB]?)\\s*❤");

    private static final long SCAN_INTERVAL_MS = 100;
    private static final long PRUNE_INTERVAL_MS = 5_000;
    /** How long a prediction may back a splash-calibration sample. */
    private static final long PENDING_FRESH_MS = 1_500;
    private static final int CALIB_KEEP = 16;
    /** Vertical reach from a mob's position up to its floating nametag stand(s). */
    private static final double NAMETAG_UP = 4.0;

    /** What the HUD card draws – one immutable, consistent read. */
    public record Estimate(String mobName, int level, double currentHp, double maxHp, int defense,
                           double calibration, boolean calibrated, boolean firstHit,
                           boolean statsMissing, DamageCalculator.Result result) {
    }

    /** The last clean (no first/triple-strike) prediction, for splash-ratio calibration. */
    private record Pending(String calibKey, int entityId, double normal, double crit, long at) {
    }

    private volatile Estimate estimate;
    private volatile Pending pending;
    /** Entity id -> melee attacks landed on it so far (First Strike / Triple-Strike state). */
    private final Map<Integer, Integer> hitCounts = new HashMap<>();
    /** Calibration key (name#level) -> rolling real/predicted ratios. */
    private final Map<String, Deque<Double>> calibrations = new HashMap<>();

    private long lastScanAt;
    private long lastPruneAt;
    private long lastDebugAt;
    /** Parsed {@code mobDefenseOverrides} plus the CSV it was parsed from (cheap change check). */
    private Map<String, Integer> overrides = Map.of();
    private String overridesSource = null;

    private DamageEstimator() {
    }

    public static DamageEstimator getInstance() {
        return INSTANCE;
    }

    private static DamageOverlaySettings cfg() {
        return ConfigManager.getInstance().get().damageOverlay;
    }

    /** The current estimate for the HUD card, or {@code null} (card hides). */
    public Estimate current() {
        return estimate;
    }

    // ------------------------------------------------------------------ feeds

    /** Every melee attack (from {@code AttackTrackMixin}): advances the mob's hit counter. */
    public void onMeleeAttack(Entity target) {
        if (target != null && cfg().enabled) {
            hitCounts.merge(target.getId(), 1, Integer::sum);
        }
    }

    /**
     * One of YOUR splashes, confirmed by Damage Attribution on a mob nametag. When it lands on the
     * mob the overlay is currently predicting for, the real/predicted ratio becomes a calibration
     * sample for that mob.
     */
    public void onOwnSplash(ArmorStand mobTag, long value, boolean crit) {
        DamageOverlaySettings cfg = cfg();
        Pending p = pending;
        if (!cfg.enabled || !cfg.autoCalibrate || p == null || value <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - p.at() > PENDING_FRESH_MS) {
            return;
        }
        double predicted = crit ? p.crit() : p.normal();
        if (predicted <= 0) {
            return;
        }
        double ratio = value / predicted;
        if (ratio < 0.05 || ratio > 20) {
            return;   // not a plain hit of this weapon (ability proc, wrong mob) - never learn it
        }
        Deque<Double> samples = calibrations.computeIfAbsent(p.calibKey(), k -> new ArrayDeque<>());
        samples.addLast(ratio);
        while (samples.size() > CALIB_KEEP) {
            samples.removeFirst();
        }
        if (cfg.debugLog && now - lastDebugAt > 1_000) {
            lastDebugAt = now;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "§8[§bSBS§8]§8[DmgEst] §7calib %s: real %s / pred %s = ×%.2f (median ×%.2f, %d)",
                        p.calibKey(), format(value), format(predicted), ratio,
                        calibration(p.calibKey()), samples.size())));
            }
        }
    }

    // ------------------------------------------------------------------ tick

    /** Called once per client tick; does its own throttling. */
    public void onClientTick() {
        PlayerStatsTracker.getInstance().onClientTick();
        DamageOverlaySettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!cfg.enabled || player == null || level == null) {
            estimate = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        if (now - lastPruneAt > PRUNE_INTERVAL_MS) {
            lastPruneAt = now;
            hitCounts.keySet().removeIf(id -> level.getEntity(id) == null);
        }

        LivingEntity target = pickTarget(level, player, cfg.range);
        if (target == null) {
            estimate = null;
            return;
        }
        estimate = evaluate(cfg, level, player, target, now);
    }

    /** The nearest living mob whose box the view ray passes through, within {@code range} blocks. */
    private static LivingEntity pickTarget(ClientLevel level, LocalPlayer player, int range) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(range));
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand
                    || entity instanceof Player || !living.isAlive()) {
                continue;
            }
            if (living.distanceToSqr(eye) > (double) range * range + 4) {
                continue;
            }
            var hit = living.getBoundingBox().inflate(0.25).clip(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double dist = eye.distanceToSqr(hit.get());
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }

    private Estimate evaluate(DamageOverlaySettings cfg, ClientLevel level, LocalPlayer player,
                              LivingEntity target, long now) {
        // Identity + live HP come from the floating nametag; entity attributes are the fallback
        // (Hypixel mirrors real HP into them for most mobs, scaled or not - better than nothing).
        String name = null;
        int mobLevel = 0;
        double currentHp = 0;
        double maxHp = 0;
        ArmorStand tag = nearestNametag(level, target);
        if (tag != null && tag.getCustomName() != null) {
            String stripped = DamageAttribution.stripText(tag.getCustomName().getString());
            Matcher levelMatcher = LEVEL.matcher(stripped);
            if (levelMatcher.find()) {
                mobLevel = Integer.parseInt(levelMatcher.group(1));
            }
            Matcher hp = NAMETAG_HP.matcher(stripped);
            if (hp.find()) {
                maxHp = DamageAttribution.parseValue(hp.group(2));
                currentHp = hp.group(1) != null ? DamageAttribution.parseValue(hp.group(1)) : maxHp;
            }
            name = DamageAttribution.mobDisplayName(tag);
        }
        if (name == null || name.isEmpty()) {
            name = target.getName() != null
                    ? DamageAttribution.stripText(target.getName().getString()) : "?";
        }

        MobCombatCatalog.Variant variant = MobCombatCatalog.find(name, mobLevel);
        if (maxHp <= 0 && variant != null && variant.hp() > 0) {
            maxHp = variant.hp();
            currentHp = currentHp > 0 ? currentHp : maxHp;
        }
        if (maxHp <= 0 && target.getMaxHealth() > 0) {
            maxHp = target.getMaxHealth();
            currentHp = target.getHealth();
        }

        int defense = defenseFor(cfg, name, variant);
        MobFamily family = variant != null && variant.family() != null
                ? variant.family() : MobFamily.of(target);

        WeaponInfo weapon = WeaponInfo.of(player.getMainHandItem());
        if (weapon == null || weapon.damage() <= 0) {
            return null;   // not holding a weapon - no card
        }
        DamageCalculator.PlayerStats stats = PlayerStatsTracker.getInstance().effectiveStats(player);
        if (stats == null) {
            return new Estimate(name, mobLevel, currentHp, maxHp, defense, 1, false,
                    false, true, null);
        }

        String calibKey = MobCombatCatalog.key(name) + "#" + mobLevel;
        double calibration = calibration(calibKey);
        boolean calibrated = calibrations.containsKey(calibKey)
                && calibrations.get(calibKey).size() >= 3;
        int hitNumber = hitCounts.getOrDefault(target.getId(), 0);

        DamageCalculator.MobState mob =
                new DamageCalculator.MobState(family, currentHp, maxHp, defense);
        DamageCalculator.Result result = DamageCalculator.compute(stats, weapon, mob, hitNumber,
                calibrated ? calibration : 1);

        // The calibration sample is matched against a CLEAN prediction (no first/triple-strike
        // bonus): those bonuses expire mid-fight and would poison the learned ratio.
        DamageCalculator.Result clean = hitNumber >= 3 ? result
                : DamageCalculator.compute(stats, weapon, mob, 3, 1);
        pending = new Pending(calibKey, target.getId(),
                hitNumber >= 3 && calibrated ? clean.normal() / calibration : clean.normal(),
                hitNumber >= 3 && calibrated ? clean.crit() / calibration : clean.crit(), now);

        if (cfg.debugLog && now - lastDebugAt > 3_000) {
            lastDebugAt = now;
            logBreakdown(player, stats, weapon, mob, hitNumber, result, calibrated ? calibration : 1);
        }
        return new Estimate(name, mobLevel, currentHp, maxHp, defense,
                calibration, calibrated, hitNumber == 0, false, result);
    }

    /**
     * One chat line naming every factor of the current estimate. This is the tuning instrument:
     * when the predicted number is wrong, it says which term is responsible instead of leaving the
     * whole formula suspect.
     */
    private static void logBreakdown(LocalPlayer player, DamageCalculator.PlayerStats stats,
                                     WeaponInfo weapon, DamageCalculator.MobState mob,
                                     int hitNumber, DamageCalculator.Result result,
                                     double calibration) {
        StringBuilder additive = new StringBuilder();
        DamageCalculator.additiveParts(stats, weapon, mob, mob.currentHp(), hitNumber)
                .forEach((name, value) -> additive.append(additive.isEmpty() ? "" : " + ")
                        .append(name).append(' ').append(Math.round(value)).append('%'));
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "§8[§bSBS§8]§8[DmgEst] §7(5+%.0f dmg) ×%.2f str ×%.2f cd ×%.2f add ×%.2f def ×%.2f cal "
                        + "= §f%s §7crit",
                weapon.damage(), 1 + stats.strength() / 100, 1 + stats.critDamage() / 100,
                1 + result.additivePct() / 100, result.defenseFactor(), calibration,
                format(result.crit()))));
        player.sendSystemMessage(Component.literal("§8[§bSBS§8]§8[DmgEst] §7additive: §f"
                + (additive.isEmpty() ? "none" : additive.toString())));
    }

    /** The ❤-nametag armor stand floating over {@code mob}, or {@code null}. */
    private static ArmorStand nearestNametag(ClientLevel level, LivingEntity mob) {
        Vec3 pos = mob.position();
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class,
                new AABB(pos, pos).inflate(1.5, NAMETAG_UP, 1.5),
                s -> s.hasCustomName() && s.getCustomName() != null
                        && s.getCustomName().getString().contains("❤"));
        ArmorStand best = null;
        double bestDist = Double.MAX_VALUE;
        for (ArmorStand stand : stands) {
            if (stand.getY() < mob.getY() - 0.5) {
                continue;   // a nametag never floats below its mob
            }
            double dist = stand.position().distanceToSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = stand;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ defense + calibration

    private int defenseFor(DamageOverlaySettings cfg, String name, MobCombatCatalog.Variant variant) {
        String csv = cfg.mobDefenseOverrides == null ? "" : cfg.mobDefenseOverrides;
        if (!csv.equals(overridesSource)) {
            overridesSource = csv;
            Map<String, Integer> parsed = new HashMap<>();
            for (String pair : csv.split(",")) {
                int colon = pair.lastIndexOf(':');
                if (colon <= 0) {
                    continue;
                }
                try {
                    parsed.put(MobCombatCatalog.key(pair.substring(0, colon)),
                            Integer.parseInt(pair.substring(colon + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // half-typed override - the row re-parses on the next change
                }
            }
            overrides = parsed;
        }
        Integer override = overrides.get(MobCombatCatalog.key(name));
        if (override != null) {
            return override;
        }
        return variant != null ? variant.defense() : 0;
    }

    /** The learned real/predicted median for a mob, 1.0 while unknown. */
    private double calibration(String calibKey) {
        Deque<Double> samples = calibrations.get(calibKey);
        if (samples == null || samples.size() < 3) {
            return 1;
        }
        List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(Double::compare);
        return sorted.get(sorted.size() / 2);
    }

    private static String format(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
