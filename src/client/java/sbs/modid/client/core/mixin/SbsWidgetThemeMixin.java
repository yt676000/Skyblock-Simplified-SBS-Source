/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.pausemenu.PauseButtonStyle;
import sbs.modid.client.ui.pausemenu.PauseMenuStyles;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Minecraft Overlay module: repaints every vanilla button-like widget in the SBS design language when
 * the master toggle is on, so the whole vanilla UI is uniform (no mix of grey and themed widgets).
 *
 * <p>Injects at the head of {@code AbstractWidget#extractRenderState} (the single draw hook all
 * widgets go through) and fully redraws {@link Button}, {@link AbstractSliderButton} and
 * {@link CycleButton} as the SBS rounded, dark-mode, blue-accent card, then cancels the vanilla render.
 * Sliders keep a functional accent handle at their value; every widget's message is drawn centred and
 * clipped to its own bounds, so nothing shifts or overlaps. Text fields, the mod's own SBS widgets and
 * anything else are left untouched.
 */
@Mixin(AbstractWidget.class)
public abstract class SbsWidgetThemeMixin {

    private static final int SLIDER_HANDLE_WIDTH = 8;

    /**
     * Other-mod path: EVERY third-party widget with a text label inherits the SBS theme (config
     * screens, ... – regardless of whether it extends {@code Button}, {@code AbstractButton} or
     * {@code AbstractWidget} directly), so mod screens stop mixing grey vanilla-style widgets into
     * the themed UI. Two exceptions always keep their native look: text-input widgets
     * ({@code EditBox} subclasses – repainting would erase cursor/selection) and icon-only widgets
     * without any label (their art IS the widget; a themed card would erase it). Vanilla non-Button
     * button-likes (checkboxes etc.) stay vanilla as before.
     *
     * <p><b>Off by default.</b> Repainting a widget means guessing that a rounded card with a
     * centred label is a fair substitute for whatever the other mod drew - and for some mods it is
     * not: their screens came out unusable. We can vouch for the look of vanilla's widgets and our
     * own, not for every mod's, so this stays something the player opts into after seeing that
     * their particular mods survive it.
     */
    private static boolean sbs$isThemableModButton(Object widget) {
        if (!ConfigManager.getInstance().get().minecraftOverlay.themeOtherMods) {
            return false;
        }
        if (widget.getClass().getName().startsWith("net.minecraft.")) {
            return false;
        }
        if (widget instanceof net.minecraft.client.gui.components.EditBox) {
            return false;
        }
        return widget instanceof AbstractWidget w && !w.getMessage().getString().isBlank();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$reskin(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
                                           CallbackInfo ci) {
        Object self = this;
        // A pause button given its own corner radius is repainted even with the theme switched off:
        // the shape was set deliberately per button, and silently ignoring it would read as broken.
        PauseButtonStyle pauseStyle = PauseMenuStyles.forWidget(self);
        boolean shaped = pauseStyle != null && pauseStyle.corner >= 0;
        if (!shaped && !ConfigManager.getInstance().get().minecraftOverlay.enabled) {
            return;
        }
        int corner = shaped ? pauseStyle.corner : SBSTheme.CORNER_RADIUS;
        boolean supported = self instanceof Button || self instanceof AbstractSliderButton
                || self instanceof CycleButton || self instanceof TabButton
                || sbs$isThemableModButton(self);
        if (!supported || self.getClass().getName().startsWith("sbs.modid")) {
            return;
        }

        AbstractWidget widget = (AbstractWidget) self;
        Font font = Minecraft.getInstance().font;
        int x = widget.getX();
        int y = widget.getY();
        int w = widget.getWidth();
        int h = widget.getHeight();
        int textY = y + (h - font.lineHeight) / 2;

        // Small icon/utility buttons (accessibility, feedback, language, ...): sprite icon buttons
        // get the SBS card with their REAL icon redrawn centred inside it (description stays in the
        // native hover tooltip). Small buttons whose icon we cannot recover stay fully vanilla.
        if (w <= 24 && h <= 24 && Math.abs(w - h) <= 8) {
            if (self instanceof SpriteIconButton iconButton) {
                boolean iconHovered = widget.isHoveredOrFocused() && widget.active;
                int iconBg = !widget.active
                        ? SBSTheme.CARD_BG_DISABLED
                        : (iconHovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                int iconBorder = iconHovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
                SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS, iconBg, iconBorder);

                SpriteIconButtonAccessor icon = (SpriteIconButtonAccessor) iconButton;
                int sw = icon.skyblockSimplified$spriteWidth();
                int sh = icon.skyblockSimplified$spriteHeight();
                g.blitSprite(RenderPipelines.GUI_TEXTURED,
                        icon.skyblockSimplified$sprite().get(widget.active, widget.isHoveredOrFocused()),
                        x + (w - sw) / 2, y + (h - sh) / 2, sw, sh);
                ci.cancel();
            }
            return;
        }

        // A vanilla widget whose art IS its sprite has nothing to put on a card. The card path
        // below draws a rounded box and centres the widget's message in it, so a widget with no
        // message becomes an empty box and its icon is gone - which is what happened to the recipe
        // book's category tabs and its filter button. The tabs slipped through because
        // RecipeBookTabButton is an ImageButton at 35x27: too big for the small-icon branch above,
        // and an ImageButton by definition, so the branch that could have recovered an icon never
        // ran. Same rule as that branch states, applied at any size: an icon we cannot recover
        // stays fully vanilla.
        if (self instanceof net.minecraft.client.gui.components.ImageButton
                || widget.getMessage().getString().isBlank()) {
            return;
        }

        boolean hovered = widget.isHoveredOrFocused() && widget.active;
        Component message = widget.getMessage();
        int textColor = widget.active ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;

        // Tab headers (vanilla TabNavigationBar and mod screens built on it, e.g. config UIs):
        // SBS card per tab, the selected one marked with a bright border and an accent underline.
        if (self instanceof TabButton tabButton) {
            boolean selected = tabButton.isSelected();
            SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    selected || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    selected || hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            if (selected) {
                g.fill(x + 3, y + h - 2, x + w - 3, y + h, SBSTheme.ACCENT);
            }
            g.centeredText(font, message, x + w / 2, textY, selected ? SBSTheme.TEXT : textColor);
            ci.cancel();
            return;
        }

        int bg = !widget.active
                ? SBSTheme.CARD_BG_DISABLED
                : (hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
        int border = hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, corner, bg, border);

        if (self instanceof AbstractSliderButton) {
            // Draw the accent handle at the slider's normalised value so it stays usable.
            double value = ((AbstractSliderButtonAccessor) self).skyblockSimplified$value();
            int handleX = x + (int) Math.round(value * (w - SLIDER_HANDLE_WIDTH));
            SciFiRender.roundedRectWithBorder(g, handleX, y + 1, SLIDER_HANDLE_WIDTH, h - 2,
                    SBSTheme.CORNER_RADIUS, SBSTheme.ACCENT, SBSTheme.ACCENT_BRIGHT);
            g.centeredText(font, message, x + w / 2, textY, textColor);
        } else {
            if (hovered) {
                g.fill(x + 2, y + 3, x + 4, y + h - 3, SBSTheme.ACCENT);
            }
            g.centeredText(font, message, x + w / 2, textY, textColor);
        }

        ci.cancel();
    }
}
