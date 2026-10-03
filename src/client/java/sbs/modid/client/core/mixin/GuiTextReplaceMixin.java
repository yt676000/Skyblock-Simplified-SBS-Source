/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sbs.modid.client.core.util.DisplayedText;

/**
 * The displayed-text pipeline everywhere text is <b>drawn</b>: the single GUI text-submission point
 * every overlay goes through, ours and the game's alike. What actually runs here - the Text Editor's
 * rules, Streamer Mode's redaction - and in what order is {@link DisplayedText}'s business, not this
 * class's; this only supplies the hook.
 *
 * <p>The rules used to reach only chat, tooltips and item names, because each of those had its own
 * hook. Everything else that shows text - the SBS HUD cards and panels, Hypixel's sidebar, the tab
 * list - had no hook and so no rules. Rather than adding a dozen more, this catches the text on its
 * way to the screen, which is by definition all of it. That is also why it is the right place to
 * redact a name from: a feature that has to cover <i>everywhere</i> cannot be a list of hooks
 * somebody has to remember to extend.
 *
 * <p><b>Colours survive.</b> The component overloads hand {@link DisplayedText} the real component,
 * so every run that nothing touched keeps its own style, and a replacement inherits the style it was
 * spliced into. Nothing is flattened to a plain string on the way through.
 *
 * <p><b>Cost.</b> Each stage opens with its own cheap enabled test - a field read while the feature
 * is off, and both are off by default. With one on, the text is rewritten per draw; that is the
 * price of a feature being global, and it is paid only by players who switched it on.
 */
@Mixin(GuiGraphicsExtractor.class)
public class GuiTextReplaceMixin {

    /** The funnel the 5-arg component overload delegates into, so this covers both. */
    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private Component skyblockSimplified$replaceText(Component text) {
        return DisplayedText.filter(text);
    }

    /** Its own hook: centeredText turns the component into a sequence before it calls text(). */
    @ModifyVariable(method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true)
    private Component skyblockSimplified$replaceCenteredText(Component text) {
        return DisplayedText.filter(text);
    }

    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private String skyblockSimplified$replaceString(String text) {
        return DisplayedText.filter(text);
    }

    @ModifyVariable(method = "centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true)
    private String skyblockSimplified$replaceCenteredString(String text) {
        return DisplayedText.filter(text);
    }

    /**
     * The tab list draws part of its rows as pre-decomposed sequences, so without this the rules
     * would visibly apply to some lines of it and not others.
     */
    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence skyblockSimplified$replaceSequence(FormattedCharSequence text) {
        return DisplayedText.filter(text);
    }

    @ModifyVariable(method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence skyblockSimplified$replaceCenteredSequence(FormattedCharSequence text) {
        return DisplayedText.filter(text);
    }
}
