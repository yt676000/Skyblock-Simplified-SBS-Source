/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.helper.inventory.logic.AccessoryIndex;
import sbs.modid.client.helper.inventory.logic.AccessoryProgress;
import sbs.modid.client.helper.inventory.ui.MissingAccessoriesScreen;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * A chip above the Accessory Bag menu that opens the missing-accessory screen.
 *
 * <p>Placed here rather than only behind a keybind because the moment a player wonders what they
 * are missing is the moment they are looking at the bag. It sits <i>above</i> the container, never
 * over it, so it can never swallow a click meant for a slot.
 *
 * <p>The count on the chip is recomputed at most a few times a second: it walks the whole catalogue,
 * which is cheap but not free, and this draws on every frame the bag is open.
 */
public final class AccessoryBagButton {

    private static final int HEIGHT = 14;
    private static final int PAD = 6;

    /** Recompute interval for the chip's count, in milliseconds. */
    private static final long REFRESH_MS = 500;

    private static long lastCountAt;
    private static int cachedMissing = -1;

    private AccessoryBagButton() {
    }

    private static SBSConfig.AccessoryBagSettings cfg() {
        return ConfigManager.getInstance().get().accessoryBag;
    }

    /** Whether the chip should be drawn on this screen at all. */
    private static boolean active(AbstractContainerScreen<?> screen) {
        return cfg().bagButton && AccessoryIndex.isBagMenu(MenuFrame.of(screen));
    }

    /** The chip's bounds on this screen, as {@code {x, y, width, height}}. */
    private static int[] bounds(AbstractContainerScreen<?> screen) {
        AbstractContainerScreenAccessor access = (AbstractContainerScreenAccessor) screen;
        int label = Minecraft.getInstance().font.width(text());
        int width = label + PAD * 2;
        int x = access.skyblockSimplified$leftPos() + access.skyblockSimplified$imageWidth() - width;
        int y = access.skyblockSimplified$topPos() - HEIGHT - 2;
        return new int[]{x, y, width, HEIGHT};
    }

    /** The chip label: the missing count when it is known, else a plain invitation to look. */
    private static String text() {
        int missing = missingCount();
        return missing < 0 ? "Accessories" : "Missing " + missing;
    }

    /**
     * How many accessories are missing under the current scope, or {@code -1} while nothing has been
     * read yet. Throttled, because this is called from the draw path.
     */
    private static int missingCount() {
        long now = System.currentTimeMillis();
        if (now - lastCountAt < REFRESH_MS) {
            return cachedMissing;
        }
        lastCountAt = now;
        if (AccessoryIndex.getInstance().pagesSeen() == 0) {
            cachedMissing = -1;
            return cachedMissing;
        }
        var scope = new AccessoryProgress.Scope(cfg().includeRift, cfg().includeSuperseded);
        cachedMissing = AccessoryProgress.summarise(AccessoryProgress.rows(scope)).missing();
        return cachedMissing;
    }

    /** Draws the chip. Called from the container overlay hook, in absolute screen coordinates. */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!active(screen)) {
            return;
        }
        int[] box = bounds(screen);
        boolean hover = mouseX >= box[0] && mouseX < box[0] + box[2]
                && mouseY >= box[1] && mouseY < box[1] + box[3];
        SciFiRender.roundedRectWithBorder(g, box[0], box[1], box[2], box[3], SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        var font = Minecraft.getInstance().font;
        g.centeredText(font, Component.literal(text()), box[0] + box[2] / 2,
                box[1] + (box[3] - font.lineHeight) / 2,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
    }

    /** Opens the screen when the chip was clicked. Returns whether the click was consumed. */
    public static boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active(screen)) {
            return false;
        }
        int[] box = bounds(screen);
        if (event.x() < box[0] || event.x() >= box[0] + box[2]
                || event.y() < box[1] || event.y() >= box[1] + box[3]) {
            return false;
        }
        MissingAccessoriesScreen.open();
        return true;
    }
}
