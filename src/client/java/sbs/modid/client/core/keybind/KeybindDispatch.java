/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.dev.DevKeybinds;

/**
 * The one fan-out from "a bound input was pressed in the world" to every feature holding a hotkey.
 *
 * <p>Both input mixins feed this: {@code CommandKeyMixin} with a GLFW key code and
 * {@code CommandMouseMixin} with a mouse-button or wheel code from {@link Keys}. The two code spaces
 * cannot collide (see {@link Keys}), so every feature below compares one {@code int} against its
 * config field and needs to know nothing about where the press came from — which is what makes a
 * mouse button bindable everywhere a key is, without touching thirty features.
 *
 * <p>The gate is the same for both: a fresh press, in-world, with no screen open, so a hotkey never
 * fires while typing in chat or browsing an SBS overlay. Neither mixin cancels anything, so a
 * keybind on Mouse Left still swings, and a keybind on the wheel still changes the hotbar slot.
 */
public final class KeybindDispatch {

    /** Whole turns of the wheel, so a fractional-scroll device does not fire a bind ten times. */
    private static final WheelNotches WHEEL = new WheelNotches();

    private KeybindDispatch() {
    }

    /**
     * Runs every feature hotkey bound to the wheel direction {@code yOffset} points in — once per
     * notch, however many events the hardware split that notch into.
     *
     * @param modifiers the GLFW modifier bits held at the time; GLFW's scroll callback carries none
     *                  of its own, so the caller reads them off the keyboard
     */
    public static void wheel(double yOffset, int modifiers) {
        press(WHEEL.step(yOffset), modifiers);
    }

    /**
     * Runs every feature hotkey bound to {@code code}.
     *
     * @param code      a {@link Keys} bind code: a GLFW key, a mouse button or a wheel direction
     * @param modifiers the GLFW modifier bits held at the time of the press
     */
    public static void press(int code, int modifiers) {
        if (code == 0) {
            return;   // unbound: nothing can match it, and a feature left at 0 must not fire
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return; // not in a world
        }
        if (GuiStateManager.getInstance().getCurrentScreen() != null) {
            return; // a screen (chat, inventory, SBS overlay, ...) is open
        }
        CommandKeybindManager.getInstance().onPressed(code, modifiers);
        // Route-waypoint hotkeys (Dungeons module, available to everyone; unbound by default).
        sbs.modid.client.dungeons.run.command.RouteKeybinds.onKeyPressed(code);
        // Item Price History hotkey (opens the fixed search view; unbound by default).
        sbs.modid.client.economy.pricehistory.command.PriceHistoryKeybind.onKeyPressed(code);
        // Hidden dev tools: no-op unless dev mode is active (never fires for normal players).
        DevKeybinds.onKeyPressed(code);
        // Farming: Mouse Lock toggle (unbound by default).
        sbs.modid.client.skills.farming.logic.MouseLock.onKeyPressed(code);
        // Farming: save where you are looking as the held crop's Mousemat angle (unbound by default).
        sbs.modid.client.skills.farming.logic.MousematAngles.onKeyPressed(code);
        // Ether Warp: sensitivity reduction toggle (unbound by default).
        sbs.modid.client.helper.etherwarp.EtherWarpSensitivity.onKeyPressed(code);
        // Fullbright toggle (unbound by default).
        sbs.modid.client.helper.visual.logic.Fullbright.onKeyPressed(code);
        // Hide Nearby Players: pause / resume hiding without opening the settings.
        sbs.modid.client.helper.hideplayers.logic.PlayerHiding.onKeyPressed(code);
        // Skyblock Menu "Full UI": the central item search (default O, only in Full UI mode).
        sbs.modid.client.helper.storage.StorageSearchKeybind.onKeyPressed(code);
        // Missing accessories (unbound by default).
        sbs.modid.client.helper.inventory.logic.AccessoryKeybind.onKeyPressed(code);
        // GUI editor: move/scale HUD elements (unbound by default).
        sbs.modid.client.ui.hud.edit.command.HudEditorKeybind.onKeyPressed(code);

        sbs.modid.client.economy.forge.command.ForgeKeybinds.onKeyPressed(code);

        sbs.modid.client.skills.hunting.HuntingKeybinds.onKeyPressed(code);

        // Garden Blueprint: copy the current plot / toggle its 3D ghost (both unbound by default).
        sbs.modid.client.skills.garden.command.GardenBlueprintKeybinds.onKeyPressed(code);

        // Build Tools: corner at look 1 / 2 and the Quick Paste grid (all unbound by default).
        sbs.modid.client.helper.build.command.BuildKeybinds.onKeyPressed(code);

        // Cinematic Camera: its freecam key (unbound by default; works with Build Tools off).
        sbs.modid.client.helper.camera.CinematicCameraModule.onKeyPressed(code);

        // Garden Plots: open the plot grid (unbound by default).
        sbs.modid.client.skills.garden.command.GardenPlotsKeybinds.onKeyPressed(code);

        // Web Browser: open the movable browser window (unbound by default).
        sbs.modid.client.helper.browser.BrowserKeybinds.onKeyPressed(code);

        // Carry Tickets: open the ticket screen (unbound by default).
        sbs.modid.client.combat.carry.command.CarryKeybinds.onKeyPressed(code);

        // Party Overlay: open the party-command panel (unbound by default).
        sbs.modid.client.social.party.command.PartyOverlayKeybinds.onKeyPressed(code);

        // Custom Skin: open the skin picker for the held item (unbound by default).
        sbs.modid.client.helper.customskin.command.CustomSkinKeybinds.onKeyPressed(code);

        // Lane End Warning: mark lane-area corners (unbound by default).
        sbs.modid.client.skills.farming.logic.LaneEndWarning.getInstance().onKeyPressed(code);

        // Mining Routes: add a waypoint at your feet / open the routes screen (unbound by default).
        sbs.modid.client.skills.mining.command.MiningRoutesKeybinds.onKeyPressed(code);
        // Commission Route: pin the route to the next commission (and unpin past the last).
        sbs.modid.client.skills.mining.command.CommissionKeybinds.onKeyPressed(code);
        // Objective Route: dismiss the current objective's route (a display choice, sends nothing).
        int dismiss = sbs.modid.client.core.config.ConfigManager.getInstance().get().npc.dismissKey;
        if (dismiss != 0 && code == dismiss) {
            sbs.modid.client.helper.npc.ObjectiveNpcTracker.getInstance().dismiss();
        }

        // Secret Routes: place a waypoint at your feet / open the editor (unbound by default).
        sbs.modid.client.dungeons.secretroutes.command.SecretRoutesKeybinds.onKeyPressed(code);

        // SkyBlock Map: open the island map (unbound by default).
        sbs.modid.client.helper.map.command.MapKeybinds.onKeyPressed(code);

        // Mimic: manually announce the mimic to party chat (unbound by default).
        sbs.modid.client.dungeons.run.logic.MimicDetector.getInstance().onKeyPressed(code);

        // Terminal Simulator: open the practice screen (unbound by default).
        int termKey = sbs.modid.client.core.config.ConfigManager.getInstance().get().dungeons.terminalSimKey;
        if (termKey != 0 && code == termKey) {
            minecraft.setScreenAndShow(new sbs.modid.client.dungeons.terminal.TerminalSimulatorScreen());
        }

        // Damage Attribution: master-toggle hotkey (unbound by default).
        sbs.modid.client.combat.damage.logic.DamageAttribution.getInstance().onKeyPressed(code);

        // Better Chat: hide-chat toggle hotkey (unbound by default).
        sbs.modid.client.social.chat.logic.BetterChat.getInstance().onKeyPressed(code);

        // Chat Tabs: step to the next chat channel (unbound by default).
        sbs.modid.client.social.chat.logic.ChatTabs.getInstance().onKeyPressed(code);

        // Ping Marker: drop (or take back) a temporary marker on whatever you are looking at. The
        // one feature hotkey here that ships bound - middle mouse, which vanilla also uses for Pick
        // Block. Nothing is cancelled, so both actions run; see PingKeybinds.
        sbs.modid.client.helper.ping.PingKeybinds.onKeyPressed(code);

        // Overlay Inspector: free pointer that names the mod behind each overlay (unbound by default).
        sbs.modid.client.helper.overlayinspector.OverlayInspector.getInstance().onKeyPressed(code);
    }
}
