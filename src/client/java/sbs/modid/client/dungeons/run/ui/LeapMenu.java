/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Leap Menu (Spirit Leap): colours each teammate's slot by their dungeon class and adds a <b>per-class
 * slot keybind</b> - while the Spirit Leap menu is open, pressing the class key clicks that class's
 * player. This is a slot hotkey, not automation: the menu must already be open and you press the key,
 * exactly like the existing Slot Hotkeys module. It never opens the menu or leaps on its own.
 *
 * <p>Player → class comes from {@link DungeonTeamClasses}; the slot's player name is read from the head's
 * skin profile first and its display name second ({@link LeapNames}), because the display name can be
 * rewritten by the item renamer and text replacements. Opening the menu asks for one class re-parse,
 * so a tag that arrived without moving any revision is still picked up. Fed from the shared container
 * key/render hooks.
 */
public final class LeapMenu {

    private static final LeapMenu INSTANCE = new LeapMenu();

    private static final String SECTION = String.valueOf((char) 0x00A7);
    private static final Pattern FORMATTING = Pattern.compile(SECTION + ".");

    /** Head stack → raw names, per stack identity (ItemStack has no equals), so render does no regex. */
    private final Map<ItemStack, String[]> names = new WeakHashMap<>();
    /** Container id of the leap menu last seen, to refresh + log once per open. */
    private int seenContainer = Integer.MIN_VALUE;
    /** {@link DungeonTeamClasses#refreshCount()} at the last probe. */
    private int seenRefresh = -1;
    /** Signature of the last probe line, so it is logged on change only. */
    private String lastProbe = "";

    private LeapMenu() {
    }

    public static LeapMenu getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** True while a Spirit Leap menu is the open screen. */
    private static boolean isLeapMenu(AbstractContainerScreen<?> screen) {
        String title = screen.getTitle() == null ? "" : FORMATTING.matcher(screen.getTitle().getString()).replaceAll("");
        return title.toLowerCase(Locale.ROOT).contains("spirit leap");
    }

    /** The class letter a pressed key is bound to, or 0. */
    private static char classForKey(int key) {
        SBSConfig.DungeonsSettings cfg = cfg();
        if (key == 0) {
            return 0;
        }
        if (key == cfg.leapMageKey) {
            return 'M';
        }
        if (key == cfg.leapArcherKey) {
            return 'A';
        }
        if (key == cfg.leapBerserkKey) {
            return 'B';
        }
        if (key == cfg.leapTankKey) {
            return 'T';
        }
        if (key == cfg.leapHealerKey) {
            return 'H';
        }
        return 0;
    }

    /**
     * Handles a key press while a container is open (from {@code SlotHotkeyMixin}). When the Spirit
     * Leap menu is open and the key is a bound class key, clicks the slot of that class's player.
     * Returns {@code true} when it consumed the key.
     */
    public boolean handleKey(AbstractContainerScreen<?> screen, int key) {
        if (!cfg().leapMenuEnabled || !isLeapMenu(screen)) {
            return false;
        }
        char want = classForKey(key);
        if (want == 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < upper; i++) {
            String player = playerOf(menu.getSlot(i).getItem());
            if (player != null && DungeonTeamClasses.classOf(player) == want) {
                // A plain left-click = clicking the head, i.e. the leap. Same call the Slot Hotkeys
                // module uses; the server turns it into the leap action.
                mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.PICKUP, mc.player);
                return true;
            }
        }
        return false; // no teammate of that class in the menu - leave the key for vanilla
    }

    /** Draws a class-colour outline + class letter on each teammate's slot in the Spirit Leap menu. */
    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!cfg().leapMenuEnabled || !isLeapMenu(screen)) {
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        Font font = Minecraft.getInstance().font;
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        // Once per open (after the heads arrived) and again after each class re-parse while open.
        if (menu.containerId != seenContainer || DungeonTeamClasses.refreshCount() != seenRefresh) {
            if (menu.containerId != seenContainer) {
                DungeonTeamClasses.requestRefresh("leap menu opened");
            }
            seenRefresh = DungeonTeamClasses.refreshCount();
            if (probe(screen, menu, upper)) {
                seenContainer = menu.containerId;
            }
        }
        for (int i = 0; i < upper; i++) {
            Slot slot = menu.getSlot(i);
            String player = playerOf(slot.getItem());
            if (player == null) {
                continue;
            }
            char cls = DungeonTeamClasses.classOf(player);
            if (cls == 0) {
                continue;
            }
            int color = DungeonTeamClasses.colorOf(player, 0xFFFFFFFF);
            int x = left + slot.x;
            int y = top + slot.y;
            outline(g, x, y, color);
            g.text(font, Component.literal(String.valueOf(cls)), x + 1, y + 1, color);
        }
    }

    /** The player name a leap-menu head belongs to, or null when not a head. */
    private String playerOf(ItemStack stack) {
        String[] raw = rawNames(stack);
        return raw == null ? null : LeapNames.choose(raw[0], raw[1], DungeonTeamClasses::classOf);
    }

    /** {profile name, stripped display name} for a player head, cached per stack; null otherwise. */
    private String[] rawNames(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("player_head")) {
            return null;
        }
        String[] cached = names.get(stack);
        if (cached == null) {
            String profile = null;
            ResolvableProfile resolvable = stack.get(DataComponents.PROFILE);
            if (resolvable != null) {
                profile = resolvable.name().orElse(null);
                if (profile == null && resolvable.partialProfile() != null) {
                    profile = resolvable.partialProfile().name();
                }
            }
            String display = FORMATTING.matcher(stack.getHoverName().getString()).replaceAll("").trim();
            cached = new String[] {profile, display};
            names.put(stack, cached);
        }
        return cached;
    }

    /**
     * One probe line per menu open ([SBS][Leap]): the title, each head's raw/stripped/profile name and
     * its resolved class, the class-table sizes and why the tables were last rebuilt. Logged on
     * change only. Returns whether any head was in the menu yet (contents arrive after the open).
     */
    private boolean probe(AbstractContainerScreen<?> screen, AbstractContainerMenu menu, int upper) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            String[] raw = rawNames(stack);
            if (raw == null) {
                continue;
            }
            String player = playerOf(stack);
            char cls = DungeonTeamClasses.classOf(player);
            line.append(" | slot ").append(i).append(" raw='").append(stack.getHoverName().getString())
                    .append("' stripped='").append(raw[1]).append("' profile='").append(raw[0])
                    .append("' -> ").append(player).append('=').append(cls == 0 ? '-' : cls);
        }
        if (line.isEmpty()) {
            return false;
        }
        String probe = line.toString();
        if (probe.equals(lastProbe)) {
            return true;
        }
        lastProbe = probe;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Leap] title='{}' live={} latched={} lastRefresh={}{}",
                screen.getTitle().getString(), DungeonTeamClasses.liveCount(),
                DungeonTeamClasses.latchedCount(), DungeonTeamClasses.lastRefreshReason(), probe);
        return true;
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 17, y + 1, color);
        g.fill(x - 1, y + 15, x + 17, y + 17, color);
        g.fill(x - 1, y - 1, x + 1, y + 17, color);
        g.fill(x + 15, y - 1, x + 17, y + 17, color);
    }
}
