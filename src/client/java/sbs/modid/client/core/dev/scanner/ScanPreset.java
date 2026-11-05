/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static sbs.modid.client.core.dev.scanner.ScanChannel.ACTIONBAR;
import static sbs.modid.client.core.dev.scanner.ScanChannel.BLOCKS;
import static sbs.modid.client.core.dev.scanner.ScanChannel.CHAT;
import static sbs.modid.client.core.dev.scanner.ScanChannel.DAMAGE;
import static sbs.modid.client.core.dev.scanner.ScanChannel.ENTITY;
import static sbs.modid.client.core.dev.scanner.ScanChannel.INVENTORY;
import static sbs.modid.client.core.dev.scanner.ScanChannel.MOUSE;
import static sbs.modid.client.core.dev.scanner.ScanChannel.PARTICLES;
import static sbs.modid.client.core.dev.scanner.ScanChannel.SCOREBOARD;
import static sbs.modid.client.core.dev.scanner.ScanChannel.SOUND;
import static sbs.modid.client.core.dev.scanner.ScanChannel.TABLIST;
import static sbs.modid.client.core.dev.scanner.ScanChannel.TITLE;
import static sbs.modid.client.core.dev.scanner.ScanChannel.WORLD;

/**
 * {@code /sbs scan full [preset]}: which channels a full capture records and how far it looks.
 * The radius bounds entities, sounds, blocks and particles; {@code radius=<n>} on the command
 * overrides it. Pure.
 */
public enum ScanPreset {

    /**
     * Everything except the two volume channels: particles and blocks stay off because either can
     * outweigh the rest of the capture on a busy island. Ask for them by channel word.
     */
    FULL(32, false, text(ENTITY, DAMAGE, SOUND, TITLE, INVENTORY, WORLD, MOUSE)),

    /**
     * Fishing: the bite is a sound plus a particle splash at the bobber, the catch a sea-creature
     * spawn (entity) or an item pickup (inventory), the rare-drop line chat. Particles on, short
     * radius - the bobber is close, and a wider one fills the file with other players' splashes.
     */
    FISHING(24, false, text(ENTITY, DAMAGE, SOUND, TITLE, INVENTORY, WORLD, PARTICLES, MOUSE)),

    /**
     * Dungeons: rooms are big and the team matters, so a wide radius and other players kept. Blocks
     * on for doors, levers, chests and secrets; particles off (spell effects drown everything).
     */
    DUNGEON(64, true, text(ENTITY, DAMAGE, SOUND, TITLE, INVENTORY, WORLD, BLOCKS, MOUSE)),

    /**
     * Slayer: the boss is a nametag stand plus its mob, phases show in name changes, the boss bar
     * and titles; minibosses and the kill come through entity and damage.
     */
    SLAYER(32, false, text(ENTITY, DAMAGE, SOUND, TITLE, INVENTORY, WORLD, MOUSE)),

    /**
     * Mining: block updates are the subject (ores, gemstones, treasure chests), particles carry the
     * precision-mining and lockpick cues. Short radius, because a mine is full of other miners.
     */
    MINING(24, false, text(ENTITY, DAMAGE, SOUND, TITLE, INVENTORY, WORLD, BLOCKS, PARTICLES, MOUSE)),

    /**
     * Kuudra: an arena fight with a party, so a wide radius and other players kept; supplies and
     * pearls show as entities and pickups. Blocks and particles off.
     */
    KUUDRA(64, true, text(ENTITY, DAMAGE, SOUND, TITLE, INVENTORY, WORLD, MOUSE)),

    /**
     * Diana: burrows and the spade's guess are particles and sounds, the inquisitor an entity
     * spawn. A wide radius, because the burrow particles are what is being looked for.
     */
    DIANA(48, false, text(ENTITY, DAMAGE, SOUND, TITLE, INVENTORY, WORLD, PARTICLES, MOUSE));

    public final int radius;
    /** Whether other real players are recorded (a party matters there); otherwise they are noise. */
    public final boolean keepPlayers;
    private final Set<ScanChannel> channels;

    ScanPreset(int radius, boolean keepPlayers, Set<ScanChannel> channels) {
        this.radius = radius;
        this.keepPlayers = keepPlayers;
        this.channels = channels;
    }

    /** A copy the caller may extend. Never contains the always-on channels. */
    public Set<ScanChannel> channels() {
        return EnumSet.copyOf(channels);
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The preset named {@code key}, or {@code null}. */
    public static ScanPreset of(String key) {
        for (ScanPreset preset : values()) {
            if (preset.key().equals(key)) {
                return preset;
            }
        }
        return null;
    }

    /** {@code fishing|dungeon|...} for help text; {@code full} itself is the bare command. */
    public static String names() {
        StringBuilder out = new StringBuilder();
        for (ScanPreset preset : values()) {
            if (preset != FULL) {
                out.append(out.isEmpty() ? "" : "|").append(preset.key());
            }
        }
        return out.toString();
    }

    /** The four text channels every preset records, plus {@code more}. */
    private static Set<ScanChannel> text(ScanChannel... more) {
        Set<ScanChannel> out = EnumSet.of(CHAT, ACTIONBAR, SCOREBOARD, TABLIST);
        out.addAll(java.util.List.of(more));
        return out;
    }
}
