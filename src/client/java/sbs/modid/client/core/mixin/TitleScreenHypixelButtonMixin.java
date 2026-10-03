/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.HypixelServerEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a "Hypixel" row to the title screen's menu, directly under Multiplayer, that connects to
 * {@link HypixelServerEntry#ADDRESS} without going through the server list.
 *
 * <p>The row is inserted rather than overlaid, so the buttons below it move down to make space and
 * the whole block shifts up by half a row to stay centred - the menu grows symmetrically instead of
 * drifting towards the bottom of the screen.
 *
 * <p>Only widgets near the horizontal centre are moved. That is the same rule
 * {@link sbs.modid.client.ui.titlescreen.TitleBranding} measures the menu with, and for the same
 * reason: vanilla's copyright button sits in a corner and other mods put whole columns down the
 * sides, and none of those belong to the row of buttons being re-laid out.
 *
 * <p>Anchoring on the Multiplayer button rather than on vanilla's layout constants means the demo
 * menu (which has no Multiplayer row) is left alone by construction, and a mod that has already
 * moved the menu is followed rather than fought.
 *
 * <p><b>Not everything on this screen is a widget.</b> The Realms notification icons are blitted
 * straight from screen metrics, so re-laying out the widgets leaves them behind; see
 * {@link #skyblockSimplified$shiftRealmsIcons}.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenHypixelButtonMixin extends Screen {

    /** How far off centre a widget may sit and still count as part of the menu column. */
    private static final float MENU_CENTRE_BAND = 0.25F;

    /**
     * How far the rows below the inserted one were pushed down, or {@code 0} when the menu was left
     * untouched. Read by the render hook so screen decorations that are not widgets can follow.
     */
    @Unique
    private int skyblockSimplified$rowShift;

    protected TitleScreenHypixelButtonMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void skyblockSimplified$addHypixelButton(CallbackInfo ci) {
        // Cleared first: init runs again on every resize, and a screen that no longer moves the menu
        // (feature switched off, demo menu) must not keep shifting decorations by the old amount.
        skyblockSimplified$rowShift = 0;
        if (!ConfigManager.getInstance().get().convenience.hypixelMenuButton) {
            return;
        }
        AbstractWidget multiplayer = skyblockSimplified$findMultiplayer();
        if (multiplayer == null) {
            return; // demo menu, or a mod replaced the row - leave the layout alone
        }
        int rowHeight = multiplayer.getHeight();
        int step = rowHeight + 4;   // vanilla's row pitch: a 20 px button every 24 px
        int half = step / 2;
        int insertBelow = multiplayer.getY();

        // Collected before anything moves, so the decisions are all made on the original layout.
        List<AbstractWidget> column = skyblockSimplified$menuColumn();
        for (AbstractWidget widget : column) {
            int y = widget.getY() - half;
            if (widget.getY() > insertBelow) {
                y += step;
            }
            widget.setY(y);
        }

        addRenderableWidget(Button.builder(Component.literal(HypixelServerEntry.NAME),
                        b -> HypixelServerEntry.connect(this))
                .bounds(multiplayer.getX(), insertBelow + half, multiplayer.getWidth(), rowHeight)
                .build());

        skyblockSimplified$rowShift = step - half;
    }

    /**
     * Moves the Realms notification icons down with the menu rows they belong to.
     *
     * <p>They are the one part of this screen that re-laying out the widgets cannot reach: they are
     * not widgets at all but blits inside {@code RealmsNotificationsScreen}, positioned from raw
     * screen metrics ({@code height / 4 + 48 + 50}) that spell out where vanilla's third menu row
     * sits. Insert a row and they stay behind, floating over the menu next to nothing.
     *
     * <p>Translating the whole call rather than patching that arithmetic keeps this independent of
     * how many icons there are and where each one goes - and the icons sit on the row <i>below</i>
     * the inserted one, so the shift is exactly what those rows got.
     */
    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/realmsclient/gui/screens/RealmsNotificationsScreen;"
                            + "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void skyblockSimplified$shiftRealmsIcons(RealmsNotificationsScreen notifications,
                                                     GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                     float partialTick) {
        if (skyblockSimplified$rowShift == 0) {
            notifications.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(0.0F, skyblockSimplified$rowShift);
        notifications.extractRenderState(g, mouseX, mouseY, partialTick);
        g.pose().popMatrix();
    }

    /** The Multiplayer row, found by its translation key so the client's language does not matter. */
    private AbstractWidget skyblockSimplified$findMultiplayer() {
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget widget
                    && widget.getMessage().getContents() instanceof TranslatableContents contents
                    && "menu.multiplayer".equals(contents.getKey())) {
                return widget;
            }
        }
        return null;
    }

    /**
     * Every widget belonging to the centred menu column, <b>visible or not</b>.
     *
     * <p>Hidden widgets have to move too. The small icon buttons sitting beside a menu row are not
     * visible while {@code init} runs - they are switched on later, once the asynchronous
     * availability check for their row comes back - so skipping them left them behind at the old
     * row's position, floating over the menu once they appeared. Visibility says nothing about
     * whether something has a place in the layout.
     */
    private List<AbstractWidget> skyblockSimplified$menuColumn() {
        List<AbstractWidget> column = new ArrayList<>();
        int centre = this.width / 2;
        int band = Math.round(this.width * MENU_CENTRE_BAND);
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget widget
                    && Math.abs(widget.getX() + widget.getWidth() / 2 - centre) <= band) {
                column.add(widget);
            }
        }
        return column;
    }
}
