/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.core.render.ScreenForeground;
import sbs.modid.client.economy.recipe.ui.RecipeOverlay;
import sbs.modid.client.skills.hunting.logic.HuntingBoxScanner;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.MenuRenderPriority;
import sbs.modid.client.ui.render.RenderTier;

/**
 * Draws the Recipe Viewer overlay (search bar + item list) and the storage preview over every
 * container screen.
 *
 * <p>Injected <b>after the screen's own content but before the deferred-tooltip flush</b> (i.e.
 * right after the inner {@code extractRenderState} call inside the final
 * {@code extractRenderStateWithTooltipAndSubtitles}). That ordering is the whole point:
 * <ul>
 *   <li>the overlay paints above the screen content,</li>
 *   <li>every tooltip – the hovered inventory item's (with the injected price lines) and the
 *       overlay's own hover tooltip, both queued via the normal
 *       {@code setTooltipForNextFrame} – is flushed afterwards by vanilla and therefore renders on
 *       top of the overlay, never hidden behind it.</li>
 * </ul>
 * This replaces the earlier TAIL injection, which ran after the tooltip flush and made the overlay
 * cover open tooltips (and required a fragile manual re-flush for its own).
 *
 * <p><b>Every pass below goes through {@link MenuRenderPriority}, and the order of the list is the
 * priority order</b> — Minecraft first (this hook runs after vanilla has drawn, and never delays
 * it), then the features the open menu is about, then everything else. What a pass gets is decided
 * by the {@link RenderTier} it is registered with here, so the decision is visible in the list
 * rather than buried in each overlay. A tier is derived from the feature's <i>own</i> screen test
 * fed the cached title from {@link MenuFrame}: a second, cheaper copy of a feature's "is this my
 * menu" rule is how a feature silently stops drawing on a screen it was written for.
 */
@Mixin(Screen.class)
public abstract class OverlayRenderMixin {

    /**
     * While the container is {@linkplain sbs.modid.client.ui.window.FloatingWindows#isContainerRaised()
     * raised}, the floating windows render BEFORE the screen content – i.e. behind the container –
     * so clicking the inventory really pulls it in front of every window.
     */
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                    shift = At.Shift.BEFORE))
    private void skyblockSimplified$renderWindowsBehind(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                        float partialTick, CallbackInfo ci) {
        Object self = this;
        if (self instanceof AbstractContainerScreen<?> container
                && sbs.modid.client.ui.window.FloatingWindows.isContainerRaised()) {
            ScreenForeground.renderWindows(container, g, mouseX, mouseY);
        }
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                    shift = At.Shift.AFTER))
    private void skyblockSimplified$renderRecipeOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                        float partialTick, CallbackInfo ci) {
        Object self = this;
        if (self instanceof AbstractContainerScreen<?> container) {
            // This hook runs exactly once per frame per container screen, so it is where the frame's
            // budget is opened. Everything the mod draws over a menu - here, at TAIL, and the slot
            // decorations of the following frame - is measured against that one window.
            MenuFrame frame = MenuFrame.of(container);
            MenuRenderPriority.beginFrame(frame);

            // The storage workspace is exclusive: nothing else draws while it covers the screen.
            if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().isWorkspaceActive(container)) {
                return;
            }
            // Worked out once and handed to the three Bazaar passes below. Each of them used to ask
            // the same question of the same screen, separately, every frame.
            boolean bazaar = BazaarOrderTracker.isBazaarGui(frame.normalised());

            MenuRenderPriority.run("recipe.overlay", RenderTier.RELEVANT,
                    () -> RecipeOverlay.getInstance().render(container, g, mouseX, mouseY));
            // "What is this chest worth": totals for the open container and your own inventory,
            // above the menu. Before the tooltip flush, so a hovered item's lore covers it.
            // Not on a sack: that card totals each icon's stack size, which in a sack is 1 per item
            // and nothing to do with how much is stored. The Sack Overlay has the real amounts and
            // its own total, and two totals that disagree are worse than one.
            MenuRenderPriority.run("itemvalue.container_total",
                    RenderTier.when(!sbs.modid.client.helper.sacks.SackOverlay.getInstance()
                            .suppressesContainerValue(container)),
                    () -> sbs.modid.client.economy.itemvalue.ContainerValueOverlay.getInstance().render(container, g));
            // Fishing profit tracker: full scrollable list over the inventory (cheaper catches too).
            MenuRenderPriority.run("fishing.hud", RenderTier.RELEVANT,
                    () -> sbs.modid.client.skills.fishing.render.FishingHud.renderInContainer(container, g, mouseX, mouseY));
            // Bazaar "Manage Orders" side panel: your tracked orders + live status beside the menu.
            MenuRenderPriority.run("bazaar.manage_orders", RenderTier.when(bazaar),
                    () -> sbs.modid.client.economy.bazaar.ui.ManageOrdersPanel.getInstance().render(container, g, mouseX, mouseY));
            // Bazaar "Order History": what was left of the buy orders you cancelled, on the other
            // side of the same menu. Clickable, so its click handler lives in ContainerSearchBarMixin.
            MenuRenderPriority.run("bazaar.order_history", RenderTier.when(bazaar),
                    () -> sbs.modid.client.economy.bazaar.ui.OrderHistoryPanel.getInstance().render(container, g, mouseX, mouseY));
            // Hunting Box value panel: surplus first, whole-box total second. Self-gating on the title.
            MenuRenderPriority.run("hunting.box_panel",
                    RenderTier.when(HuntingBoxScanner.isBoxTitle(frame.title())),
                    () -> sbs.modid.client.skills.hunting.render.HuntingBoxPanel.getInstance().render(container, g));
            // Heart of the Mountain perk advisor: the next five steps for the chosen goal. Display only.
            MenuRenderPriority.run("hotm.advisor",
                    RenderTier.when(sbs.modid.client.skills.mining.render.HotmAdvisorPanel.isHotmMenu(frame.title())),
                    () -> sbs.modid.client.skills.mining.render.HotmAdvisorPanel.getInstance().render(container, g));
            // Bazaar pre-render cache (dev-only, off by default): records what Bazaar screens look
            // like as they open. Draws nothing - it is here because this is the one call that runs
            // every frame a container is up, and the server fills a container's slots in after the
            // screen exists, so a single capture on open would store an empty chest.
            MenuRenderPriority.run("bazaar.prerender", RenderTier.when(bazaar),
                    () -> sbs.modid.client.economy.bazaar.prerender.BazaarPrerender.getInstance()
                            .onContainerFrame(container));
            // Experimentation Table helper overlays (highlights / ghosts / numbers) - drawn above the
            // board items but below tooltips, so hovered-item tooltips still read normally.
            MenuRenderPriority.run("experiment.table", RenderTier.RELEVANT,
                    () -> sbs.modid.client.helper.experiment.logic.ExperimentationTable.getInstance().render(container, g, mouseX, mouseY));
            // Melody's Harp: timing cue + next notes (display only), and the [SBS][Harp] timing log.
            MenuRenderPriority.run("experiment.harp", RenderTier.RELEVANT,
                    () -> sbs.modid.client.helper.experiment.logic.HarpHelper.getInstance().render(container, g));
            // Dungeon terminal solver: green outline on the slots to click (display only, never clicks).
            MenuRenderPriority.run("dungeon.terminal_solver", RenderTier.RELEVANT,
                    () -> sbs.modid.client.dungeons.terminal.TerminalSolver.getInstance().render(container, g, mouseX, mouseY));
            // Beacon Tuning: rings the colour / speed / pitch matching the beat (display only).
            MenuRenderPriority.run("foraging.beacon_tuning", RenderTier.RELEVANT,
                    () -> sbs.modid.client.skills.foraging.logic.BeaconTuning.getInstance().render(container, g, mouseX, mouseY));
            // Leap Menu: class-colour outlines + letters on the Spirit Leap teammate slots.
            MenuRenderPriority.run("dungeon.leap_menu", RenderTier.RELEVANT,
                    () -> sbs.modid.client.dungeons.run.ui.LeapMenu.getInstance().render(container, g, mouseX, mouseY));
            // Inventory Buttons render here (before the deferred-tooltip flush), NOT at TAIL: at TAIL
            // their icons paint over the hovered item's tooltip ("shortcut icons through the item
            // display"), and their own hover tooltip - queued via setTooltipForNextFrame - would be
            // flushed before the next TAIL frame and painted over. Here both tooltips land on top.
            // Garden visitor: one Bazaar button per item the visitor wants, beside the menu. Same
            // stratum as the Inventory Buttons below, for the same tooltip-ordering reason.
            MenuRenderPriority.run("garden.visitor_bazaar", RenderTier.RELEVANT,
                    () -> sbs.modid.client.skills.garden.ui.VisitorBazaarButtons.getInstance()
                            .render(container, g, mouseX, mouseY));
            MenuRenderPriority.run("inventory.buttons", RenderTier.RELEVANT,
                    () -> sbs.modid.client.helper.inventorybuttons.ui.InventoryButtonsOverlay.render(container, g, mouseX, mouseY));
        }
    }

    /**
     * The mod's top layer, drawn at TAIL - i.e. AFTER the deferred-tooltip flush - <b>only while
     * {@link ScreenForeground#onTop()} is off</b>. With it on (the default) the same layer is drawn
     * from the end of the GUI frame by {@code GuiForegroundMixin} instead, which is later than any
     * other mod's injection into this method. Exactly one of the two runs; what they draw is one
     * method in {@link ScreenForeground}.
     *
     * <p>Here it sits above the menu and its tooltips but below anything a mod draws after us into
     * this same method - which is the whole difference the setting makes.
     */
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void skyblockSimplified$renderStoragePreview(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                         float partialTick, CallbackInfo ci) {
        Object self = this;
        if (self instanceof AbstractContainerScreen<?> container && !ScreenForeground.onTop()) {
            ScreenForeground.drawTopMost(container, g, mouseX, mouseY);
        }
    }
}
