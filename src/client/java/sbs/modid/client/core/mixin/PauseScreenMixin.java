/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.ui.pausemenu.PauseMenuButton;
import sbs.modid.client.ui.pausemenu.PauseMenuEditor;
import sbs.modid.client.ui.pausemenu.PauseMenuLayout;
import sbs.modid.client.ui.pausemenu.PauseMenuStyles;
import sbs.modid.client.ui.screen.SBSMainScreen;

/**
 * Adds the SBS button to the vanilla pause menu, opening the SBS main menu directly so the config is
 * reachable without typing {@code /sbs}, and applies the per-button layout the pause-menu editor
 * produces.
 *
 * <p>The SBS button is free-floating (drag it anywhere, scroll to resize it, see
 * {@link PauseMenuButton}) rather than a cell in the centered vanilla button grid: injecting into
 * {@code createPauseMenu}'s {@code GridLayout} would need fragile local capture, fix the button to one
 * cell and shift the vanilla layout. Added at the tail of {@code init()} and skipped for the F3+Esc
 * variant, which shows no pause menu at all.
 *
 * <p>{@link PauseMenuLayout} then repositions and resizes every child - the heading and the SBS button
 * included - because the layout has already put all of them on the screen by the time {@code init}
 * ends. {@link PauseMenuEditor} is the mode that edits them, and the only thing that takes the mouse
 * away from the vanilla buttons; outside it every click reaches the menu exactly as vanilla intends.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    @Unique
    private final PauseMenuLayout skyblockSimplified$layout = new PauseMenuLayout();

    /** Non-null while this pause screen is in edit mode – see {@link PauseMenuEditor}. */
    @Unique
    private PauseMenuEditor skyblockSimplified$editor;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void skyblockSimplified$addSbsButton(CallbackInfo ci) {
        if (!((PauseScreen) (Object) this).showsPauseMenu()) {
            return;
        }
        // The SBS button is added BEFORE the capture and parked at its default corner, so the layout
        // records that corner as its base and offsets it like any other button. It stays in edit mode
        // too - it is one of the buttons being edited.
        if (PauseMenuButton.enabled()) {
            PauseMenuButton button = new PauseMenuButton(
                    () -> Minecraft.getInstance().setScreenAndShow(new SBSMainScreen()));
            button.applyDefaultPosition(this.width, this.height);
            addRenderableWidget(button);
        }
        skyblockSimplified$layout.capture(this.children());
        skyblockSimplified$layout.apply(this.width, this.height);

        // Edit mode is decided once, on the screen the editor asked for, but rebuilt on every init so
        // it survives a window resize (which re-runs init on the same screen instance).
        if (PauseMenuEditor.consumeArmed()) {
            skyblockSimplified$editor = new PauseMenuEditor(skyblockSimplified$layout);
        }
        if (skyblockSimplified$editor != null) {
            PauseMenuEditor editor = skyblockSimplified$editor;
            // Boxes first, then the editor's buttons, so the chrome stays on top of them.
            addRenderableOnly((g, mouseX, mouseY, partialTick) ->
                    editor.render(g, mouseX, mouseY, this.width, this.height));
            editor.createChrome(this.width, this.height, this::addRenderableWidget);
        }
    }

    /** Persists the per-button layout however the editor is left – Escape included. */
    @Override
    public void removed() {
        if (skyblockSimplified$editor != null) {
            PauseMenuStyles.save();
        }
        super.removed();
    }

    // The three mouse methods below are ADDED OVERRIDES, not @Inject: neither PauseScreen nor Screen
    // declares them in this version - they exist only as ContainerEventHandler defaults, and an
    // @Inject naming a method the target class does not have fails at mixin-apply time.

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // In edit mode the editor gets first refusal, so a left-click on a vanilla button moves it
        // instead of pressing it. It only claims clicks that land ON a menu button, which is what
        // leaves the editor's own Reset / Align / Save & Exit reachable through normal dispatch.
        if (skyblockSimplified$editor != null
                && skyblockSimplified$editor.mouseClicked(event.x(), event.y(), event.button())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (skyblockSimplified$editor != null && skyblockSimplified$editor.mouseDragged(
                event.x(), event.y(), this.width, this.height)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (skyblockSimplified$editor != null && skyblockSimplified$editor.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (skyblockSimplified$editor != null
                && skyblockSimplified$editor.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
