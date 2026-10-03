/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the glyph provider behind a {@link Font}, so the Fonts feature can build a second
 * {@code Font} that draws in a different font without disturbing the first one.
 *
 * <p><b>This reads and nothing else.</b> No {@code @Inject}, no {@code @Redirect}, no
 * {@code @Mutable} and no setter: vanilla's own {@code Font} is left exactly as it was, and a mod
 * that never calls {@code sbs$provider()} cannot tell this mixin exists. Changing how the game
 * resolves glyphs from inside {@code Font} or {@code FontManager} is deliberately not done here -
 * it would fight every other mod that draws text, and it is not needed: {@code new Font(provider)}
 * is public, and 26.2 lets a {@code Screen} be handed its own {@code Font} at construction.
 *
 * <p><b>Why a private field at all.</b> {@code Font}'s only constructor takes a
 * {@code Font.Provider}, and the only implementation of that interface in the game is
 * {@code FontManager$CachedFontProvider}, which is a private inner class reachable through no
 * public factory anywhere in the client jar. Reading the field is the shortest honest route to one.
 * Delegating to it also means our font resolves through the real pipeline, so the merged resource
 * stack - Hypixel's server pack included - still supplies any glyph our font lacks.
 *
 * <p><b>Version coupling.</b> The field name {@code provider} is read against <b>Minecraft
 * 26.2</b> (Mojang official mappings). It is the sole private-field dependency of the Fonts
 * feature; if a future version renames it, this class is the one place to look, and
 * {@code SbsFonts} already degrades to the game font when the accessor yields nothing.
 */
@Mixin(Font.class)
public interface FontProviderAccessor {

    @Accessor("provider")
    Font.Provider skyblockSimplified$provider();
}
