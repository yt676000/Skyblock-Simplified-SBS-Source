/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.client.combat.diana.model.MythCreature;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Boxing the rare mythological creatures, by handing them to the highlighter that already exists.
 *
 * <h2>Why this is set arithmetic and not a renderer</h2>
 *
 * <p>The mod already boxes any SkyBlock mob the player picks, by nametag name, only in line of
 * sight, with its own colour and its own settings page: {@code combat/mobhighlight}. A second
 * implementation living in the Diana package would be a second thing to configure, a second thing to
 * keep in step with the renderer, and a second answer to "why is this mob not boxed".
 *
 * <p>So this does the only thing that was actually missing. The four names were added to the
 * highlighter's catalogue when the toolkit was built, which made them <i>pickable</i> - and then
 * nothing put them in anybody's selection and nothing said they had to be picked. From the player's
 * side that is a Diana module that does not highlight Inquisitors, which is exactly how it was
 * reported.
 *
 * <h2>It writes into another module's settings, on purpose and visibly</h2>
 *
 * <p>Unusual enough to say out loud. The alternative - a Diana-side flag the highlighter consults -
 * would mean a mob being boxed for a reason that appears nowhere on the highlighter's own page, so
 * the player reads the list of what is boxed and it is wrong. Writing the names in makes the state
 * true wherever it is read from, and switching the row off leaves them exactly where they started.
 *
 * <p><b>It only ever touches its own four names.</b> Whatever else is selected is left alone in both
 * directions, so this can never clear a selection somebody built by hand.
 */
public final class CreatureHighlight {

    private CreatureHighlight() {
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    private static SBSConfig.MobHighlightSettings highlight() {
        return ConfigManager.getInstance().get().mobHighlight;
    }

    /**
     * Whether every watched creature is currently in the highlighter's selection.
     *
     * <p>Reads the highlighter rather than a flag of our own, so the row tells the truth even when
     * the selection was changed from the other page.
     */
    public static boolean active() {
        List<MythCreature> watched = watched();
        if (watched.isEmpty()) {
            return false;
        }
        Set<String> selected = highlight().selectedMobs;
        for (MythCreature creature : watched) {
            if (!contains(selected, nameOf(creature))) {
                return false;
            }
        }
        return true;
    }

    /** Adds the watched creatures to the highlighter's selection and switches that module on. */
    public static void enable() {
        SBSConfig.MobHighlightSettings settings = highlight();
        for (MythCreature creature : watched()) {
            String name = nameOf(creature);
            if (!contains(settings.selectedMobs, name)) {
                settings.selectedMobs.add(name);
            }
        }
        // A selection nothing draws is not a highlight. Switching the module on is the other half of
        // what was asked for, and leaving it off would make this row silently do nothing.
        settings.enabled = true;
    }

    /** Takes them back out, leaving every other selection the player made untouched. */
    public static void disable() {
        Set<String> selected = highlight().selectedMobs;
        for (MythCreature creature : MythCreature.values()) {
            String name = nameOf(creature);
            selected.removeIf(entry -> entry != null && entry.equalsIgnoreCase(name));
            // The default too, in case the player set an override after switching this on - what
            // went in under the old name has to come back out under it.
            selected.removeIf(entry -> entry != null
                    && entry.equalsIgnoreCase(creature.defaultName()));
        }
    }

    /** The creatures the Diana page is currently watching. */
    private static List<MythCreature> watched() {
        List<MythCreature> out = new ArrayList<>(MythCreature.values().length);
        for (MythCreature creature : MythCreature.values()) {
            if (MythMobTracker.watched(creature)) {
                out.add(creature);
            }
        }
        return out;
    }

    /**
     * The name to hand the highlighter: the player's override when they set one, else the default.
     *
     * <p>The same name the tracker matches on, so the two cannot disagree about what a creature is
     * called - which matters here because all four are hypotheses, and someone who corrects one on
     * the Diana page expects the box to follow it.
     */
    private static String nameOf(MythCreature creature) {
        String override = cfg().creatureNames.get(creature.name());
        return override == null || override.isBlank() ? creature.defaultName() : override.trim();
    }

    private static boolean contains(Set<String> selection, String name) {
        for (String entry : selection) {
            if (entry != null && entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** One line for the settings row and the readout - including the reason it may be doing nothing. */
    public static String status() {
        if (!highlight().enabled) {
            return "off - the Mob Highlight module is switched off";
        }
        return active() ? "boxed by the Mob Highlighter" : "not in the highlighter's selection";
    }
}
