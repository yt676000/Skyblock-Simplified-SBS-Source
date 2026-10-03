/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.font;

import net.minecraft.resources.Identifier;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every font the player can pick, in the order they are offered.
 *
 * <p>Three sources fill this: the fonts Minecraft already ships (always present, we distribute
 * nothing), the ones bundled in our jar, and whatever {@code .ttf} files the player has dropped into
 * their config folder. The registry does not care which is which beyond {@link FontOrigin} - a
 * selection is a {@link SbsFont#id()} and resolving it is the same work either way.
 *
 * <p><b>The default is {@code minecraft:default} and stays that way.</b> A mod that changes how the
 * game's text looks before being asked is a mod that gets uninstalled, and it is also the only entry
 * that cannot fail to resolve.
 *
 * <p><b>Ids are permanent.</b> They are config keys. See {@link SbsFont}.
 */
public final class SbsFontRegistry {

    /** {@code minecraft:default} - the font the game draws with, and this feature's default. */
    public static final String VANILLA_DEFAULT = "vanilla_default";

    private static final Map<String, SbsFont> FONTS = new LinkedHashMap<>();

    private static boolean vanillaRegistered;

    private SbsFontRegistry() {
    }

    /**
     * Registers a font, replacing any earlier entry with the same id.
     *
     * <p>Replacement rather than rejection is deliberate: a rescan of the user font folder
     * re-registers the same ids, and the second pass must win so a file the player edited is picked
     * up instead of the copy from startup.
     */
    public static synchronized void register(SbsFont font) {
        FONTS.put(font.id(), font);
    }

    /** Drops every font of one origin - how a rescan clears the previous {@link FontOrigin#USER} set. */
    public static synchronized void clear(FontOrigin origin) {
        FONTS.values().removeIf(font -> font.origin() == origin);
    }

    /** Every registered font, in registration order. */
    public static synchronized List<SbsFont> all() {
        ensureVanilla();
        return List.copyOf(FONTS.values());
    }

    /** The registered fonts of one origin, in registration order. */
    public static synchronized List<SbsFont> of(FontOrigin origin) {
        ensureVanilla();
        List<SbsFont> out = new ArrayList<>();
        for (SbsFont font : FONTS.values()) {
            if (font.origin() == origin) {
                out.add(font);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The font with this id, or {@code null} when nothing is registered under it. */
    public static synchronized SbsFont byId(String id) {
        ensureVanilla();
        return id == null ? null : FONTS.get(id);
    }

    /**
     * The font with this id, falling back to {@link #VANILLA_DEFAULT}.
     *
     * <p>This is the path taken when a player uninstalls a font they had selected, or edits the
     * config by hand. It must never return {@code null} and never throw: an unknown id means the
     * game draws in its own font, which is a visible but harmless outcome. Whether a fallback
     * happened is a separate question - see {@link #isMissing(String)}, which is what the settings
     * page uses to say so out loud rather than leaving the player wondering.
     */
    public static synchronized SbsFont orDefault(String id) {
        SbsFont font = byId(id);
        return font != null ? font : FONTS.get(VANILLA_DEFAULT);
    }

    /** True when {@code id} is set to something that is no longer registered. */
    public static synchronized boolean isMissing(String id) {
        ensureVanilla();
        return id != null && !id.isEmpty() && !FONTS.containsKey(id);
    }

    /**
     * Registers the fonts Minecraft itself ships, on first access.
     *
     * <p>All four are part of the vanilla resource pack on 26.2 - verified against
     * {@code assets/minecraft/font/} in the client jar rather than assumed - so none of them can go
     * missing, and none of them costs us a byte of jar space or a line of licence notice.
     *
     * <p>{@code alt} (the enchanting-table runes) and {@code illageralt} are genuinely unreadable as
     * an interface font. They are here because they cost nothing and somebody will want their HUD in
     * runes; the settings row says what they are.
     */
    private static void ensureVanilla() {
        if (vanillaRegistered) {
            return;
        }
        vanillaRegistered = true;
        registerVanilla(VANILLA_DEFAULT, "default", "Minecraft (default)", 1);
        registerVanilla("vanilla_uniform", "uniform", "Minecraft Unicode", 2);
        registerVanilla("vanilla_alt", "alt", "Enchanting Runes", 2);
        registerVanilla("vanilla_illageralt", "illageralt", "Illager Runes", 2);
    }

    private static void registerVanilla(String id, String path, String displayName, int minGuiScale) {
        FONTS.put(id, new SbsFont(id, Identifier.withDefaultNamespace(path), displayName,
                FontOrigin.VANILLA, null, minGuiScale));
    }

    /** An {@link Identifier} for a font definition inside our own namespace. */
    public static Identifier ownFont(String name) {
        return SkyblockSimplifiedSBS.id(name);
    }
}
