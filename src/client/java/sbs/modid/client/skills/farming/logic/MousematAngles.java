/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.Locale;
import java.util.Map;

/**
 * Farming module: the <b>saved camera angle per crop</b> behind the Squeaky Mousemat helper.
 *
 * <p>The Mousemat aims you at an exact yaw and pitch, which is what makes a straight farming lane
 * reproducible - but the angle it wants is typed by hand into a sign, one crop at a time, and every
 * crop needs a different one. Remembering ten pairs of signed decimals is the whole difficulty of
 * the item, so this remembers them instead.
 *
 * <p><b>Why the crop comes from the last tool held.</b> The Mousemat is in your hand while its sign
 * is open, so the tool that would name the crop has just been swapped out. Every tick therefore
 * records the crop of the farming tool in the main hand, and {@link #activeCrop()} answers with the
 * last one seen. That is the crop you were farming a second ago, which is exactly the crop you are
 * opening the Mousemat for; {@link MousematSign} lets you correct it when it is not.
 *
 * <p>Angles are stored as the player-facing pair: yaw wrapped to (-180, 180] and pitch clamped to
 * +/-90, both to one decimal - the same shape Hypixel's sign accepts, so what is saved is exactly
 * what gets typed back.
 */
public final class MousematAngles {

    private static final MousematAngles INSTANCE = new MousematAngles();

    /** One crop's aim. */
    public record Angle(double yaw, double pitch) {

        /** "90.0/-58.5" - the form the sign takes and the form the settings row edits. */
        public String text() {
            return format(yaw) + "/" + format(pitch);
        }
    }

    /**
     * The crop of the last farming tool held. Volatile because the tick writes it and the sign
     * screen's render/key handlers read it.
     */
    private volatile CropType lastCrop;

    private MousematAngles() {
    }

    public static MousematAngles getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ state

    private static SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    /** Whether the Mousemat helper may do anything at all right now. */
    public static boolean enabled() {
        return cfg().mousematHelper && SkillIslands.farmingAllowed();
    }

    /** Called every client tick: remember which crop the held tool is for. */
    public void onClientTick() {
        CropType held = CropType.forHeldTool();
        if (held != null) {
            // Only ever overwritten by another crop tool - putting the Mousemat itself in your hand
            // must not erase the answer, since that is precisely when it gets asked.
            lastCrop = held;
        }
    }

    /** The crop the helper is currently about, or {@code null} while no crop tool has been held. */
    public CropType activeCrop() {
        return lastCrop;
    }

    /** Points the helper at another crop (the sign panel's up/down and click). */
    public void setActiveCrop(CropType crop) {
        if (crop != null) {
            lastCrop = crop;
        }
    }

    // ------------------------------------------------------------------ store

    /** The angle saved for a crop, or {@code null} when none ever was. */
    public Angle angleFor(CropType crop) {
        if (crop == null) {
            return null;
        }
        double[] pair = cfg().mousematAngles.get(crop.name());
        // Length is checked rather than assumed: the map is plain JSON on disk and a hand-edited
        // config should degrade to "not set yet" instead of throwing on every frame.
        return pair == null || pair.length < 2 ? null : new Angle(pair[0], pair[1]);
    }

    /** Saves a crop's angle, normalised. Passing {@code null} for the crop is a no-op. */
    public void save(CropType crop, double yaw, double pitch) {
        if (crop == null) {
            return;
        }
        cfg().mousematAngles.put(crop.name(),
                new double[] {Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0, 90.0)});
        ConfigManager.getInstance().save();
    }

    /** Forgets a crop's angle. */
    public void clear(CropType crop) {
        if (crop != null && cfg().mousematAngles.remove(crop.name()) != null) {
            ConfigManager.getInstance().save();
        }
    }

    /** Forgets every saved angle. */
    public void clearAll() {
        Map<String, double[]> saved = cfg().mousematAngles;
        if (!saved.isEmpty()) {
            saved.clear();
            ConfigManager.getInstance().save();
        }
    }

    // ------------------------------------------------------------------ capture

    /**
     * Keybind: saves where the player is looking right now as the held crop's angle.
     *
     * <p>The point of aiming by hand first and pressing a key second is that the numbers never have
     * to be read off anything - line the row up the way it should look, press the key, and the pair
     * that produced that view is what the Mousemat gets typed later.
     */
    public static void onKeyPressed(int keyCode) {
        int bound = cfg().mousematCaptureKey;
        if (bound == -1 || keyCode != bound || !enabled()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        MousematAngles angles = getInstance();
        // The held tool wins over the remembered crop: pressing the key is a deliberate "save this
        // for what I am holding", and the tick's memory is only a stand-in for when nothing is.
        CropType crop = CropType.forHeldTool();
        if (crop == null) {
            crop = angles.activeCrop();
        }
        if (crop == null) {
            SBSChat.send(Component.literal("Hold the crop's farming tool to save an angle for it.")
                    .withColor(SBSChat.WHITE));
            return;
        }
        angles.save(crop, player.getYRot(), player.getXRot());
        Angle saved = angles.angleFor(crop);
        SBSChat.send(Component.literal("Saved ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(crop.displayName()).withColor(0x57D977))
                .append(Component.literal(" angle " + (saved == null ? "?" : saved.text()))
                        .withColor(SBSChat.WHITE)));
    }

    // ------------------------------------------------------------------ text

    /** One angle as the sign wants it: one decimal, always a dot, never scientific notation. */
    public static String format(double degrees) {
        return String.format(Locale.ROOT, "%.1f", degrees);
    }

    /**
     * Reads one typed angle, or {@code NaN} when the text is not one.
     *
     * <p>A comma is accepted as the decimal point even though nothing writes one: a player on a
     * German keyboard types "58,5" without thinking about it, and rejecting that would look like the
     * feature ignoring perfectly good input.
     */
    public static double parse(String text) {
        if (text == null) {
            return Double.NaN;
        }
        String cleaned = text.trim().replace(',', '.');
        if (cleaned.isEmpty()) {
            return Double.NaN;
        }
        try {
            double value = Double.parseDouble(cleaned);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    /**
     * Reads a "yaw/pitch" pair as the settings row and the chat listing write it, or {@code null}
     * when either half is missing or unreadable. Blank input clears the crop, so the row can be
     * emptied to mean "not set".
     */
    public static Angle parsePair(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] halves = text.split("[/;]", 2);
        if (halves.length < 2) {
            return null;
        }
        double yaw = parse(halves[0]);
        double pitch = parse(halves[1]);
        if (Double.isNaN(yaw) || Double.isNaN(pitch)) {
            return null;
        }
        return new Angle(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0, 90.0));
    }

    // ------------------------------------------------------------------ command

    /** {@code /sbs mousemat}: the saved angles, one crop per line. */
    public void listToChat() {
        SBSChat.send(Component.literal("Mousemat angles (yaw/pitch)").withColor(SBSChat.WHITE));
        for (CropType crop : CropType.withTools()) {
            Angle angle = angleFor(crop);
            SBSChat.send(Component.literal(" " + label(crop) + " - ")
                    .withColor(SBSChat.WHITE)
                    .append(angle == null
                            ? Component.literal("not set").withColor(0x8A8A8A)
                            : Component.literal(angle.text()).withColor(0x57D977)));
        }
        CropType active = activeCrop();
        SBSChat.send(Component.literal(active == null
                        ? "Hold a farming tool, then open the Mousemat to fill its sign."
                        : "Opening the Mousemat now fills in " + label(active) + ".")
                .withColor(0x8A8A8A));
    }

    /**
     * The crop's name as the helper labels it. Sunflower is spelled out as covering Moonflower too:
     * one Eclipse Sickle cuts both, so the pair share a single tool and therefore a single angle,
     * and a row reading only "Sunflower" would look like Moonflower had been left out.
     */
    public static String label(CropType crop) {
        return crop == CropType.SUNFLOWER ? "Sunflower & Moonflower" : crop.displayName();
    }
}
