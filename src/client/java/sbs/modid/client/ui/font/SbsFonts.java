/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.font;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.mixin.FontProviderAccessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@link FontSlot} to the font the player picked, and stamps it onto the text SBS draws.
 *
 * <p><b>Scope.</b> Everything here applies to our own text and nothing else. A {@link Style} carries
 * the font per component, so a line SBS renders through one of these helpers changes and the line
 * Minecraft renders beside it does not. Changing the game's own font is a separate, opt-in thing.
 *
 * <p><b>Switching is immediate.</b> Every font a player can select is already loaded - the font
 * definitions are ordinary resources, baked lazily per glyph - so picking a different one is a
 * config write and the next frame draws with it. No resource reload, no restart.
 *
 * <p><b>Legacy colour codes are unaffected.</b> Section-sign codes inside a string are read by the
 * text renderer as it walks the characters and only touch colour and decoration; the font comes from
 * the component's style and survives them. {@code text(slot, "§7Owned")} works.
 */
public final class SbsFonts {

    private SbsFonts() {
    }

    /** The font selected for this slot, falling back to Minecraft's own if it went missing. */
    public static SbsFont fontFor(FontSlot slot) {
        return SbsFontRegistry.orDefault(slot.stored().get());
    }

    /** How the selected font is named inside a {@link Style}. */
    public static FontDescription descriptionFor(FontSlot slot) {
        return fontFor(slot).description();
    }

    /** An otherwise empty style carrying the selected font. */
    public static Style styleFor(FontSlot slot) {
        return Style.EMPTY.withFont(descriptionFor(slot));
    }

    /** A literal in the slot's font. The workhorse - most SBS text is a plain string. */
    public static MutableComponent text(FontSlot slot, String literal) {
        return Component.literal(literal).setStyle(styleFor(slot));
    }

    /**
     * Copies {@code component} with the slot's font applied to its root style.
     *
     * <p>A component that already names a font keeps it. That exemption is what stops this from
     * eating the SBS badge and Hypixel's icon glyphs, which are pinned to a font on purpose; a
     * helper that overwrote every font it was handed would silently break them.
     */
    public static Component apply(FontSlot slot, Component component) {
        FontDescription wanted = descriptionFor(slot);
        return component.copy().withStyle(style -> hasOwnFont(style) ? style : style.withFont(wanted));
    }

    private static boolean hasOwnFont(Style style) {
        FontDescription font = style.getFont();
        return font != null && !font.equals(FontDescription.DEFAULT);
    }

    // ---------------------------------------------------------------------------------------------
    // The facade. Call sites name the KIND of text they draw, never a font.
    // ---------------------------------------------------------------------------------------------

    /** The font for SBS screens, windows and settings rows. */
    public static Font ui() {
        return of(FontSlot.UI);
    }

    /** The font for HUD cards and trackers drawn over the world. */
    public static Font hud() {
        return of(FontSlot.HUD);
    }

    /** The font for SBS lines in chat and the chat overlays. */
    public static Font chatOverlay() {
        return of(FontSlot.CHAT_OVERLAY);
    }

    /** The font for tooltip lines SBS adds to items. */
    public static Font tooltip() {
        return of(FontSlot.TOOLTIP);
    }

    /**
     * A {@link Font} that draws this slot's selection.
     *
     * <p>Handing back a {@code Font} rather than asking every call site to style its components is
     * what keeps measurement honest: {@code font.width(...)} and the draw call then agree, because
     * they are the same object. Text laid out with one font and drawn in another misaligns every
     * column in the mod, and that failure is silent.
     *
     * <p>Never returns {@code null}. When anything about the wrapper cannot be built the game's own
     * font comes back, so the worst outcome is text in the wrong face.
     */
    public static Font of(FontSlot slot) {
        Font vanilla = Minecraft.getInstance().font;
        SbsFont selected = fontFor(slot);
        if (vanilla == null || selected.id().equals(SbsFontRegistry.VANILLA_DEFAULT)) {
            return vanilla;
        }
        Font wrapped = CACHE.get(selected.fontId());
        return wrapped != null ? wrapped : build(vanilla, selected);
    }

    /**
     * Wrapper fonts, keyed by the font they draw in.
     *
     * <p>Keyed by font rather than by slot so two slots set to the same font share one instance, and
     * so switching a slot is a map lookup rather than a rebuild.
     *
     * <p>These survive a resource reload and are deliberately not invalidated on one.
     * {@code Minecraft.font} and {@code Font.provider} are both {@code final}, and the provider
     * resolves its {@code FontSet} through {@code FontManager} on <i>every</i> call - so after a
     * reload the same wrapper is already serving the newly loaded glyphs. A cache that threw these
     * away would only rebuild identical objects.
     */
    private static final Map<Identifier, Font> CACHE = new ConcurrentHashMap<>();

    /** Logged at most once: a wrapper that cannot be built is a curiosity, not a log flood. */
    private static boolean providerFailureLogged;

    private static Font build(Font vanilla, SbsFont selected) {
        Font.Provider provider = providerOf(vanilla);
        if (provider == null) {
            return vanilla;
        }
        FontDescription wanted = selected.description();
        Font wrapped = new Font(new Font.Provider() {
            @Override
            public GlyphSource glyphs(FontDescription requested) {
                // Substitute ONLY for text that asked for no particular font. A component that names
                // its own - the SBS badge, Hypixel's icon glyphs, a server-sent tooltip, a player
                // sprite - keeps it. Forcing ours on those would break the very glyphs the fallback
                // chain exists to preserve.
                return provider.glyphs(FontDescription.DEFAULT.equals(requested) ? wanted : requested);
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
                return provider.effect();
            }
        });
        CACHE.put(selected.fontId(), wrapped);
        return wrapped;
    }

    /**
     * Reads the glyph provider out of a live {@link Font}.
     *
     * <p>Read lazily, here, rather than at mod init: {@code Minecraft.font} does not exist until the
     * client has built its {@code FontManager}, and touching it early would fail for every player
     * rather than none.
     */
    private static Font.Provider providerOf(Font vanilla) {
        try {
            return ((FontProviderAccessor) (Object) vanilla).skyblockSimplified$provider();
        } catch (RuntimeException | LinkageError failure) {
            if (!providerFailureLogged) {
                providerFailureLogged = true;
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Fonts] Could not read the font provider - "
                        + "SBS text stays in the game font.", failure);
            }
            return null;
        }
    }
}
