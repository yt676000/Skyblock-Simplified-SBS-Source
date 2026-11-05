/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.springboots.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.audio.SbsSounds;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.item.ItemAnalysis;
import sbs.modid.client.core.tick.TickContext;

import java.util.Locale;

/**
 * Client wiring for the Spring Boots charge meter: feeds {@link SpringBootsCharge} once per tick
 * from the feet slot and the sneak state, prepares what {@code SpringBootsHud} draws, and runs the
 * in-game probe that is meant to replace the ESTIMATED step timing with measured values.
 *
 * <p><b>Display only.</b> Nothing here sneaks, jumps or presses anything; it reads the player's own
 * sneak and draws how long it has lasted.
 *
 * <p><b>Probe (developer mode only).</b> While a charge runs and for {@link #PROBE_AFTER_TICKS}
 * after it, every sound the engine plays within {@link #PROBE_RADIUS} blocks of the player is
 * logged as {@code [SBS][SpringBoots] t=<ticks> sound=<id> pitch=<p> volume=<v>}, and every jump as
 * {@code [SBS][SpringBoots] released after <n> ticks, steps=<k>, peak +<dy>}. The measured peak per
 * step count is also kept for the session, so "Show Jump Height" can show a height that was seen,
 * never one that was guessed.
 *
 * <p>Game thread only: tick, sound hook and HUD all run there.
 */
public final class SpringBootsTracker {

    private static final String LOG = "[SBS][SpringBoots] ";

    /** The SkyBlock id in the feet slot. CONFIRMED in the bundled item lore. */
    public static final String BOOTS_ID = "SPRING_BOOTS";

    /** Sounds further than this from the player are not the boots'. */
    private static final double PROBE_RADIUS = 3.0;

    /** How long after a release the probe keeps listening and tracking the jump's height. */
    private static final int PROBE_AFTER_TICKS = 80;

    private static final SpringBootsTracker INSTANCE = new SpringBootsTracker();

    private final SpringBootsCharge charge = new SpringBootsCharge();

    /** Session-only measured peaks, indexed by the step count of the jump; NaN = never seen. */
    private final float[] peakBySteps = new float[SpringBootsCharge.MAX_STEPS + 1];

    private int worldEpoch = Integer.MIN_VALUE;
    private boolean fullAlerted;

    /** Ticks since the last release while the jump is still being followed; -1 = not following. */
    private int afterRelease = -1;
    private double releaseY;
    private double peakY;
    private double peakVy;
    private boolean leftGround;
    private SpringBootsCharge.Release followed;

    /** Prepared for the HUD: rebuilt only when level, max, certainty or the height text change. */
    private int shownLevel = -1;
    private boolean shownConfirmed;
    private boolean shownHeight;
    private Component label = Component.empty();
    private int labelWidth;

    private SpringBootsTracker() {
        java.util.Arrays.fill(peakBySteps, Float.NaN);
    }

    public static SpringBootsTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SpringBootsSettings cfg() {
        return ConfigManager.getInstance().get().springBoots;
    }

    /** The tick gate: SkyBlock, feature on. Boots and sneak are checked inside, every tick. */
    public static boolean gate(TickContext context) {
        return context.inSkyBlock() && cfg().enabled;
    }

    public void onClientTick() {
        TickContext context = sbs.modid.client.core.tick.TickContexts.current();
        if (context.worldEpoch() != worldEpoch) {
            worldEpoch = context.worldEpoch();
            resetAll();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !cfg().enabled) {
            resetAll();
            return;
        }
        boolean sneaking = player.isShiftKeyDown();
        // The feet slot is only read while it can matter: a sneak, or a charge to end.
        boolean worn = (sneaking || charge.charging()) && wearingBoots(player);

        SpringBootsCharge.Event event = charge.update(worn, sneaking);
        switch (event) {
            case STARTED -> {
                fullAlerted = false;
                afterRelease = -1;
                if (probing()) {
                    SkyblockSimplifiedSBS.LOGGER.info(LOG + "sneak start (ESTIMATED {} ticks/step, max {})",
                            SpringBootsCharge.TICKS_PER_STEP, SpringBootsCharge.MAX_STEPS);
                }
            }
            case RELEASED -> startFollowing(player);
            case RESET -> {
                if (probing()) {
                    SkyblockSimplifiedSBS.LOGGER.info(LOG + "charge dropped: boots taken off");
                }
            }
            default -> { }
        }

        if (charge.full() && !fullAlerted) {
            fullAlerted = true;
            if (cfg().fullChargeSound) {
                SbsSounds.play(SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 2.0f);
            }
        }
        follow(player);
        prepareLabel();
    }

    private static boolean wearingBoots(LocalPlayer player) {
        return BOOTS_ID.equals(ItemAnalysis.of(player.getItemBySlot(EquipmentSlot.FEET)).id());
    }

    // ------------------------------------------------------------------ probe: the jump

    private void startFollowing(LocalPlayer player) {
        followed = charge.lastRelease();
        afterRelease = 0;
        releaseY = player.getY();
        peakY = releaseY;
        peakVy = player.getDeltaMovement().y;
        leftGround = false;
        if (probing()) {
            SkyblockSimplifiedSBS.LOGGER.info(LOG + "sneak end t={} vy={}", followed.ticks(),
                    fmt(peakVy));
        }
    }

    private void follow(LocalPlayer player) {
        if (afterRelease < 0) {
            return;
        }
        afterRelease++;
        peakY = Math.max(peakY, player.getY());
        peakVy = Math.max(peakVy, player.getDeltaMovement().y);
        if (!player.onGround()) {
            leftGround = true;
        }
        boolean landed = leftGround && player.onGround();
        if (!landed && afterRelease < PROBE_AFTER_TICKS && !charge.charging()) {
            return;
        }
        double dy = peakY - releaseY;
        if (leftGround && followed != null) {
            int steps = Math.min(followed.steps(), peakBySteps.length - 1);
            peakBySteps[steps] = (float) dy;
        }
        if (probing() && followed != null) {
            SkyblockSimplifiedSBS.LOGGER.info(LOG + "released after {} ticks, steps={}{}, peak +{}, max vy {}, airborne={}",
                    followed.ticks(), followed.steps(), followed.soundConfirmed() ? " (sound)" : " (ESTIMATED)",
                    fmt(dy), fmt(peakVy), leftGround);
        }
        afterRelease = -1;
        followed = null;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // ------------------------------------------------------------------ probe: sounds

    /**
     * Every sound the engine is about to play, after it was resolved (called from the volume wrap in
     * {@code SoundControlMixin}, where pitch and volume are readable). Returns at once unless a charge
     * runs or a jump is being followed, so the cost outside a charge is one branch.
     */
    public void onSound(SoundInstance instance, float volume) {
        if ((!charge.charging() && afterRelease < 0) || instance == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !minecraft.isSameThread()) {
            return;
        }
        double dx = instance.getX() - player.getX();
        double dy = instance.getY() - player.getY();
        double dz = instance.getZ() - player.getZ();
        if (dx * dx + dy * dy + dz * dz > PROBE_RADIUS * PROBE_RADIUS) {
            return;
        }
        String id = instance.getIdentifier().toString();
        String wanted = cfg().chargeSound;
        if (charge.charging() && wanted != null && !wanted.isBlank()
                && id.contains(wanted.trim().toLowerCase(Locale.ROOT))) {
            charge.onStepSound();
        }
        if (probing()) {
            SkyblockSimplifiedSBS.LOGGER.info(LOG + "t={} sound={} pitch={} volume={}{}",
                    charge.charging() ? charge.ticks() : -afterRelease, id, fmt(instance.getPitch()),
                    fmt(volume), charge.charging() ? "" : " (after release)");
        }
    }

    /** Whether the probe writes to the log. */
    private static boolean probing() {
        // DEV-ONLY: the probe only adds log lines; the meter works the same without it.
        return DevMode.ACTIVE;
    }

    // ------------------------------------------------------------------ display model

    private void prepareLabel() {
        int level = charge.level();
        boolean confirmed = charge.soundConfirmed();
        boolean height = cfg().showHeight;
        if (level == shownLevel && confirmed == shownConfirmed && height == shownHeight) {
            return;
        }
        shownLevel = level;
        shownConfirmed = confirmed;
        shownHeight = height;
        StringBuilder text = new StringBuilder("Charge ").append(level).append('/').append(charge.max());
        if (!confirmed) {
            text.append(" ~");
        }
        if (height) {
            float peak = peakBySteps[Math.min(level, peakBySteps.length - 1)];
            if (!Float.isNaN(peak)) {
                text.append("  +").append(String.format(Locale.ROOT, "%.1f", peak)).append('m');
            }
        }
        label = Component.literal(text.toString());
        labelWidth = Minecraft.getInstance().font.width(label);
    }

    private void resetAll() {
        charge.reset();
        afterRelease = -1;
        followed = null;
        fullAlerted = false;
    }

    // ------------------------------------------------------------------ read by the HUD

    /** Whether the meter is drawn: a charge is running. */
    public boolean active() {
        return charge.charging();
    }

    public int level() {
        return charge.level();
    }

    public int max() {
        return charge.max();
    }

    public boolean full() {
        return charge.full();
    }

    /** "Charge 4/6 ~" ({@code ~} = ESTIMATED from time), plus a measured height when known. */
    public Component label() {
        return label;
    }

    /** {@link #label()}'s width in GUI pixels, measured when the label was built. */
    public int labelWidth() {
        return labelWidth;
    }
}
