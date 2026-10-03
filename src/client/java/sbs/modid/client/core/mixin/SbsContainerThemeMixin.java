/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.RecipeBookButtonHolder;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Minecraft Overlay module: reskins every container GUI (player inventory, chests – and with them
 * every SkyBlock menu, which are all chest screens) in the SBS design while the master toggle is on.
 *
 * <p>Injects at the HEAD of {@code extractSlots} – the one point that runs after the vanilla
 * background texture but before the slot items, on every container screen and on both supported
 * versions. The vanilla grey texture is simply painted over with the SBS rounded, blue-toned panel
 * plus one subtle cell per slot; items, tooltips and all interaction render on top untouched. Uses
 * the slot-relative coordinate space this method runs in (pose already translated to
 * leftPos/topPos – the convention verified by the working Bazaar highlighter).
 *
 * <p><b>The labels are the exception, and they have to be redrawn here.</b> {@code extractContents}
 * calls {@code extractLabels} <i>before</i> {@code extractSlots}, so the menu's own title and the
 * "Inventory" heading are already on screen when the panel goes down on top of them - which is why
 * a reskinned Bazaar had no title at all. They are painted again after the panel, in the theme's
 * text colour rather than vanilla's near-black, which would be unreadable on a dark panel anyway.
 *
 * <p>The same applies to anything vanilla draws earlier in the frame and inside the GUI rectangle:
 * the player preview (drawn in the background pass) and the recipe-book toggle button (a widget, so
 * drawn at the very top of {@code extractContents}) are both put back at the end of this hook. The
 * button is the one that actually costs the player something when it goes missing - it stays
 * clickable under the panel, so it is not merely invisible, it is a control you have to remember the
 * position of. Each of these is restored by name; nothing here restores a screen we have not looked
 * at, for the reason the allowlist below exists.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SbsContainerThemeMixin {

    // Panel base / slot colours live in SBSTheme (mutable, themed) - referenced directly so the
    // container reskin follows the Theme module. NEVER copy them into local `final` fields here:
    // javac would constant-fold the literal and the theme would stop reaching the container.

    @Shadow
    public abstract AbstractContainerMenu getMenu();

    // The label anchors, in the same slot-relative space this mixin draws in.
    @Shadow
    protected int titleLabelX;
    @Shadow
    protected int titleLabelY;
    @Shadow
    protected int inventoryLabelX;
    @Shadow
    protected int inventoryLabelY;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    protected net.minecraft.network.chat.Component playerInventoryTitle;

    /** The recipe-book toggle's icon. Built on first draw - see the draw method for why. */
    @org.spongepowered.asm.mixin.Unique
    private static net.minecraft.world.item.ItemStack skyblockSimplified$recipeIcon;

    /**
     * Whether this screen is a plain grid of slots and nothing else - the only kind the reskin may
     * paint over.
     *
     * <p>The panel covers the entire GUI rectangle. On a chest that hides nothing but grey texture,
     * which is the point. On a screen that draws its own <b>working parts</b> in the background pass
     * it hides those too: an anvil's name field, its hammer and its level cost all vanished under the
     * panel and the menu became unusable - you could not see what you were typing.
     *
     * <p>An allowlist, not a blocklist. Missing a screen here costs a bit of styling; guessing wrong
     * the other way costs a menu you cannot operate, and Hypixel keeps inventing new ones. Every
     * SkyBlock menu is a chest screen, so the list covers effectively all of them anyway.
     */
    private boolean skyblockSimplified$isPlainGrid() {
        Object self = this;
        return self instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen
                || self instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                || self instanceof net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen
                || self instanceof net.minecraft.client.gui.screens.inventory.HopperScreen
                || self instanceof net.minecraft.client.gui.screens.inventory.DispenserScreen;
    }

    /** Applies the Inventory Overlay opacity to a panel colour, or leaves it untouched. */
    private static int skyblockSimplified$fade(int color, boolean seeThrough) {
        return seeThrough ? sbs.modid.client.helper.inventory.ui.InventoryOverlay.withOpacity(color) : color;
    }

    /**
     * Puts the menu's title and the "Inventory" heading back on top of the panel that just buried
     * them, at the anchors vanilla drew them at.
     *
     * <p>Only the two labels the base class draws. A screen that paints something else of its own in
     * {@code extractLabels} is not in the allowlist above, so there is nothing else to restore - and
     * inventing a redraw for a screen we have not seen is how the anvil got covered in the first
     * place.
     */
    private void skyblockSimplified$redrawLabels(GuiGraphicsExtractor g) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        var self = (AbstractContainerScreen<?>) (Object) this;
        g.text(font, self.getTitle(), titleLabelX, titleLabelY, SBSTheme.TEXT, false);
        if (playerInventoryTitle != null) {
            g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                    SBSTheme.TEXT_MUTED, false);
        }
    }

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void skyblockSimplified$sbsContainerTheme(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                      CallbackInfo ci) {
        // Nothing here may paint over a screen that draws its own working parts - checked before the
        // phone reskin too, which covers the GUI rectangle in exactly the same way.
        if (!skyblockSimplified$isPlainGrid()) {
            return;
        }
        // Abiphone menus get the phone reskin instead of the generic theme (its own toggle, always).
        if (sbs.modid.client.helper.abiphone.AbiphoneOverlay.getInstance()
                .tryRender((AbstractContainerScreen<?>) (Object) this, g)) {
            return;
        }
        if (!ConfigManager.getInstance().get().minecraftOverlay.enabled) {
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) this;
        int w = bounds.skyblockSimplified$imageWidth();
        int h = bounds.skyblockSimplified$imageHeight();

        // Inventory Overlay's transparent mode: fade the panel so the world shows through it. The
        // opaque base is skipped entirely then - it exists to hide the vanilla texture, and letting
        // it through IS the point here.
        boolean seeThrough = sbs.modid.client.helper.inventory.ui.InventoryOverlay.screenActive();

        // Rounded SBS panel painted over the whole vanilla texture (2px past its edges so the grey
        // corners vanish; our rounded corners reveal the blurred background instead).
        SciFiRender.roundedRect(g, -2, -2, w + 4, h + 4, SBSTheme.PANEL_CORNER,
                skyblockSimplified$fade(SBSTheme.PANEL_BORDER, seeThrough));
        if (!seeThrough) {
            SciFiRender.roundedRect(g, -1, -1, w + 2, h + 2, SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_BASE);
        }
        SciFiRender.roundedRectGradient(g, -1, -1, w + 2, h + 2, SBSTheme.PANEL_CORNER - 1,
                skyblockSimplified$fade(SBSTheme.PANEL_FILL_TOP, seeThrough),
                skyblockSimplified$fade(SBSTheme.PANEL_FILL_BOTTOM, seeThrough));

        // One subtle rounded cell per slot so the inventory structure stays readable.
        AbstractContainerMenu menu = getMenu();
        int slotCount = menu.getItems().size();
        for (int i = 0; i < slotCount; i++) {
            Slot slot = menu.getSlot(i);
            SciFiRender.roundedRectWithBorder(g, slot.x - 1, slot.y - 1, 18, 18,
                    SBSTheme.SLOT_CORNER, skyblockSimplified$fade(SBSTheme.SLOT_BG, seeThrough),
                    skyblockSimplified$fade(SBSTheme.CARD_BORDER, seeThrough));
        }

        skyblockSimplified$redrawLabels(g);

        // The survival inventory's player preview is drawn by vanilla BEFORE this hook (inside the
        // background pass), so the panel above just buried it. Give it an SBS-framed box and draw
        // the player again on top – same coords and scale as vanilla (26,8 – 75,78, scale 30).
        if ((Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                && net.minecraft.client.Minecraft.getInstance().player != null) {
            SciFiRender.roundedRectWithBorder(g, 25, 7, 51, 72,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
            int left = bounds.skyblockSimplified$leftPos();
            int top = bounds.skyblockSimplified$topPos();
            // The pose is slot-relative here, but the helper scissors in absolute screen space –
            // step back to absolute coordinates for the call.
            g.pose().pushMatrix();
            g.pose().translate(-left, -top);
            net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(
                    g, left + 26, top + 8, left + 75, top + 78, 30, 0.0625F, mouseX, mouseY,
                    net.minecraft.client.Minecraft.getInstance().player);
            g.pose().popMatrix();
        }

        // Same story for the vanilla recipe-book toggle, and it is worse: a widget is not just
        // hidden, it stays clickable. Widgets are drawn at the top of extractContents – before the
        // labels, long before this hook – so the button ends up under the panel and the player has
        // to remember where it is to hit it. Draw it again on top, in the SBS design.
        if ((Object) this instanceof RecipeBookButtonHolder holder) {
            AbstractWidget recipeButton = holder.skyblockSimplified$recipeBookButton();
            if (recipeButton != null && recipeButton.visible) {
                skyblockSimplified$drawRecipeButton(g, recipeButton,
                        bounds.skyblockSimplified$leftPos(), bounds.skyblockSimplified$topPos());
            }
        }
    }

    /**
     * Paints the recipe-book toggle as an SBS card with a knowledge book on it.
     *
     * <p>Re-running the widget's own render would put a vanilla grey button on the panel, and
     * {@code SbsWidgetThemeMixin} will not help: it reskins small icon buttons only when it can
     * recover the icon, which it can for a {@code SpriteIconButton} and cannot here. An
     * {@code ImageButton}'s sprite <b>is</b> the whole button, background included, so there is no
     * glyph to lift out of it - drawing it inside a card just nests one button in another.
     *
     * <p>So the icon is fetched rather than extracted: a knowledge book, which is what vanilla's
     * own sprite depicts, at the item size the card is built around. Deliberately drawn here and not
     * in the generic widget theme - this is the reskin repairing what its own panel covered, and the
     * same button on a screen the allowlist skips (a real crafting table, a furnace) must stay
     * vanilla rather than wear an SBS card on a vanilla grey GUI.
     */
    private void skyblockSimplified$drawRecipeButton(GuiGraphicsExtractor g, AbstractWidget button,
                                                     int left, int top) {
        // Widget coordinates are absolute; this hook draws slot-relative. Shift once, here, rather
        // than pushing a pose - every helper below takes the same space.
        int x = button.getX() - left;
        int y = button.getY() - top;
        int w = button.getWidth();
        int h = button.getHeight();

        // Vanilla's own widget pass ran earlier this frame with these mouse coordinates, so the
        // hover flag is already current - no need to hit-test the rectangle again.
        boolean hovered = button.isHoveredOrFocused() && button.active;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                !button.active ? SBSTheme.CARD_BG_DISABLED
                        : (hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG),
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

        // Built on first use, never in a static initialiser: this class loads with the first
        // container screen, and an ItemStack made before the item registry is bound is a crash.
        if (skyblockSimplified$recipeIcon == null) {
            skyblockSimplified$recipeIcon =
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.KNOWLEDGE_BOOK);
        }
        g.item(skyblockSimplified$recipeIcon, x + (w - 16) / 2, y + (h - 16) / 2);
    }
}
