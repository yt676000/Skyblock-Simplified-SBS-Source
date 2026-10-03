/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import sbs.modid.client.ui.theme.style.BugStyle;
import sbs.modid.client.ui.theme.style.ClassicStyle;
import sbs.modid.client.ui.theme.style.FuturisticStyle;
import sbs.modid.client.ui.theme.style.GlassStyle;
import sbs.modid.client.ui.theme.style.MagicalStyle;
import sbs.modid.client.ui.theme.style.MedievalStyle;
import sbs.modid.client.ui.theme.style.OGStyle;
import sbs.modid.client.ui.theme.style.SteampunkStyle;
import sbs.modid.client.ui.theme.style.StyleDefinition;
import sbs.modid.client.ui.theme.style.VanillaStyle;

/**
 * The mod's visual languages. Picked in the Theme module; applied by {@link SBSTheme#applyStyle} on
 * top of whatever colours the theme engine derived.
 *
 * <p>The enum is only a registry - everything a style actually <i>is</i> lives in its
 * {@link StyleDefinition}. Adding a ninth look is one new class and one constant here; no screen,
 * renderer or setting needs to know it happened.
 *
 * <p>Two kinds of style share this list, and the difference matters:
 * <ul>
 *   <li><b>Geometry styles</b> (Classic, Futuristic, Glass) carry no palette. They are transforms over
 *       the player's own three colours, so they look right whatever accent is picked.
 *   <li><b>Material styles</b> (Medieval, Vanilla, Steampunk, Magical, Bug, OG) have a colour identity -
 *       oak is not oak in cyan. They supply base colours as a <i>default</i>, used only while the
 *       player has not picked their own, and add a {@code SurfaceMaterial} that paints grain, rivets
 *       or runes on every surface in the mod at once.
 * </ul>
 */
public enum UiStyle {

    CLASSIC(new ClassicStyle()),
    FUTURISTIC(new FuturisticStyle()),
    MEDIEVAL(new MedievalStyle()),
    VANILLA(new VanillaStyle()),
    STEAMPUNK(new SteampunkStyle()),
    MAGICAL(new MagicalStyle()),
    BUG(new BugStyle()),
    OG(new OGStyle()),
    GLASS(new GlassStyle());

    private final StyleDefinition definition;

    UiStyle(StyleDefinition definition) {
        this.definition = definition;
    }

    public StyleDefinition definition() {
        return definition;
    }

    public String displayName() {
        return definition.displayName();
    }

    public String tagline() {
        return definition.tagline();
    }

    /** The next style in the list, wrapping - what the settings row cycles through. */
    public UiStyle next() {
        UiStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * Parses a persisted name, falling back to {@link #CLASSIC} for anything unknown.
     *
     * <p>{@code JUST_IDEA} is accepted as the old name of {@link #FUTURISTIC}, so a config written
     * before the rename keeps the look its owner chose instead of silently reverting to Classic.
     */
    public static UiStyle parse(String name) {
        if (name != null) {
            if (name.equalsIgnoreCase("JUST_IDEA")) {
                return FUTURISTIC;
            }
            for (UiStyle style : values()) {
                if (style.name().equalsIgnoreCase(name)) {
                    return style;
                }
            }
        }
        return CLASSIC;
    }
}
