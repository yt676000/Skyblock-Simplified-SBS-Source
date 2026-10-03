/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.inventory.logic.EquipmentMenu.Piece;
import sbs.modid.client.helper.loadouts.LoadoutsOverlay;
import sbs.modid.client.ui.render.MenuFrame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The last read of the four equipment pieces, per account and SkyBlock profile, with when it was
 * read. Equipment is never sent to the client outside the Equipment menu, so this is all the
 * inventory column can ever show - which is why it always says how old it is.
 *
 * <p>Stacks are kept whole as SNBT through {@code ItemStack.OPTIONAL_CODEC} - the storage cache's
 * round trip - so icon, skin and tooltip survive a restart unchanged.
 *
 * <p>Also remembers which loadout the body was proven to wear at capture: a loadout can carry its
 * own equipment, so a different loadout on the body now makes the capture suspect.
 *
 * <p>Profile-store rules as in the museum store: nothing is written while the profile is unknown,
 * and a file that could not be read is never overwritten.
 */
public final class EquipmentStore implements ProfileScopedStore {

    private static final EquipmentStore INSTANCE = new EquipmentStore();

    private static final String FILE = "equipment.json";
    private static final int SCHEMA_VERSION = 1;
    /** Re-read throttle while the menu is open, on top of the state-id gate. */
    private static final long SCAN_INTERVAL_MS = 150;
    /** How often an unchanged re-read refreshes the timestamp on disk. */
    private static final long SAVE_UNCHANGED_MS = 60_000;

    /** The file, as Gson sees it. Field names are the file format. */
    static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        long capturedAt;
        /** Loadout proven worn at capture, or -1 when unknown. */
        int loadoutSlot = -1;
        /** SNBT per {@link Piece}, {@code ""} for an empty piece. */
        List<String> pieces = new ArrayList<>();
    }

    private Data data = new Data();
    private boolean loaded;
    private boolean unreadable;
    /** Decoded {@link Data#pieces}; {@code null} until a level exists to decode against. */
    private ItemStack[] decoded;

    private AbstractContainerScreen<?> lastScreen;
    private int lastState = -1;
    private long lastScanAt;
    private boolean loggedUnreadMenu;
    private long lastSavedAt;

    private EquipmentStore() {
        ProfileContext.getInstance().register(this);
    }

    public static EquipmentStore getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ capture

    /** Game tick: re-reads the Equipment menu whenever the server changes its contents. */
    public void tick(Minecraft minecraft) {
        if (!ConfigManager.getInstance().get().equipmentDisplay.enabled
                || !(GuiStateManager.getInstance().getCurrentScreen()
                        instanceof AbstractContainerScreen<?> screen)) {
            lastScreen = null;
            return;
        }
        int state = screen.getMenu().getStateId();
        long now = System.currentTimeMillis();
        if ((screen == lastScreen && state == lastState) || now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        if (!EquipmentMenu.isEquipmentMenu(MenuFrame.of(screen).normalised())) {
            lastScreen = screen;
            lastState = state;
            return;
        }
        lastScanAt = now;
        List<EquipmentMenu.SlotView> views = new ArrayList<>();
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container instanceof Inventory) {
                continue;   // a spare necklace in your own inventory is not the one you wear
            }
            views.add(view(i, slot.getItem()));
        }
        EquipmentMenu.Mapping mapping = EquipmentMenu.map(views);
        if (mapping == null) {
            // Hypixel fills a menu over several packets; only give up on it once it has settled.
            if (!loggedUnreadMenu && screen == lastScreen) {
                loggedUnreadMenu = true;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Equipment] menu '{}' not read - could not "
                        + "place all four pieces. Run /sbs probe here and fix EquipmentMenu.",
                        MenuFrame.of(screen).normalised());
            }
            lastScreen = screen;
            lastState = state;
            return;
        }
        lastScreen = screen;
        lastState = state;
        ItemStack[] stacks = new ItemStack[4];
        for (Piece piece : Piece.values()) {
            stacks[piece.ordinal()] = mapping.empty(piece) ? ItemStack.EMPTY
                    : slots.get(mapping.slot(piece)).getItem().copy();
        }
        record(stacks);
    }

    private static EquipmentMenu.SlotView view(int index, ItemStack stack) {
        if (stack.isEmpty()) {
            return new EquipmentMenu.SlotView(index, "", "", List.of());
        }
        List<String> lore = new ArrayList<>();
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null) {
            for (Component line : itemLore.lines()) {
                lore.add(PlainText.strip(line.getString()));
            }
        }
        return new EquipmentMenu.SlotView(index,
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                PlainText.strip(stack.getHoverName().getString()), lore);
    }

    private synchronized void record(ItemStack[] stacks) {
        if (!ready()) {
            return;
        }
        List<String> encoded = new ArrayList<>(4);
        for (ItemStack stack : stacks) {
            encoded.add(stack.isEmpty() ? "" : encode(stack));
        }
        boolean same = encoded.equals(data.pieces);
        data.pieces = encoded;
        data.capturedAt = System.currentTimeMillis();
        data.loadoutSlot = LoadoutsOverlay.getInstance().provenSlot();
        decoded = stacks;
        if (!same) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Equipment] captured {} piece(s)",
                    encoded.stream().filter(s -> !s.isEmpty()).count());
        }
        // A menu that re-sends itself every second would otherwise rewrite the file every second;
        // an unchanged read only needs its timestamp on disk now and then.
        if (!same || data.capturedAt - lastSavedAt > SAVE_UNCHANGED_MS) {
            lastSavedAt = data.capturedAt;
            save();
        }
    }

    // ------------------------------------------------------------------ reads

    /** When the pieces were last read, or {@code 0} when never (for this profile). */
    public synchronized long capturedAt() {
        return ready() ? data.capturedAt : 0L;
    }

    /** The loadout proven worn at capture, or -1. */
    public synchronized int loadoutSlot() {
        return ready() ? data.loadoutSlot : -1;
    }

    /**
     * The captured piece, {@link ItemStack#EMPTY} for an empty slot, or {@code null} when nothing was
     * ever captured (or it cannot be decoded yet). Shared - never mutate.
     */
    public synchronized ItemStack piece(Piece piece) {
        if (!ready() || data.capturedAt <= 0 || data.pieces.size() != 4) {
            return null;
        }
        if (decoded == null) {
            if (Minecraft.getInstance().level == null) {
                return null;
            }
            ItemStack[] out = new ItemStack[4];
            for (int i = 0; i < 4; i++) {
                String snbt = data.pieces.get(i);
                out[i] = snbt == null || snbt.isEmpty() ? ItemStack.EMPTY : decode(snbt);
            }
            decoded = out;
        }
        return decoded[piece.ordinal()];
    }

    private boolean ready() {
        if (!loaded) {
            reloadProfile();
        }
        return loaded && ProfileContext.getInstance().known();
    }

    // ------------------------------------------------------------------ persistence

    private static ItemStack decode(String snbt) {
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            var ops = Minecraft.getInstance().level.registryAccess()
                    .createSerializationContext(NbtOps.INSTANCE);
            return ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static String encode(ItemStack stack) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return "";
            }
            var ops = minecraft.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result()
                    .map(Object::toString).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    @Override
    public synchronized void reloadProfile() {
        data = new Data();
        decoded = null;
        unreadable = false;
        loaded = false;
        if (!ProfileContext.getInstance().known()) {
            return;   // the placeholder's path, not this profile's: retry once it is known
        }
        Path path = file();
        if (!Files.isRegularFile(path)) {
            loaded = true;
            return;
        }
        try {
            Data read = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.pieces == null) {
                    read.pieces = new ArrayList<>();
                }
                if (read.schemaVersion > SCHEMA_VERSION) {
                    unreadable = true;   // a newer client's file: use it, never overwrite it
                }
                data = read;
            }
            loaded = true;
        } catch (JsonSyntaxException corrupt) {
            unreadable = true;
            loaded = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Equipment] {} is unreadable ({}) - left as it is",
                    FILE, corrupt.toString());
        } catch (Exception transientFailure) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Equipment] could not read {} yet ({})", FILE,
                    transientFailure.toString());
        }
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private void save() {
        if (!loaded || unreadable || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            data.schemaVersion = SCHEMA_VERSION;
            Files.writeString(path, SBSFiles.GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Equipment] could not write {}: {}", FILE, e.toString());
        }
    }
}
