/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.overlayinspector;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.screen.SBSMainScreen;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Opens the settings of the mod the inspector just named – the second half of "which mod is this?",
 * because knowing the name still leaves you hunting for the screen.
 *
 * <p>There is no loader-level API for "open this mod's settings". What exists is a widely
 * implemented entrypoint that mods use to hand their config screen to the in-game mod list, and this
 * asks for it entirely through reflection: no compile-time dependency, no crash when it is absent,
 * and mods that never registered a screen simply report as much.
 *
 * <p>SBS itself is handled directly – its settings screen is right here.
 */
public final class ModConfigOpener {

    /** Entrypoint key and interface used by the in-game mod list to collect config screens. */
    private static final String ENTRYPOINT_KEY = "modmenu";
    private static final String API_CLASS = "com.terraformersmc.modmenu.api.ModMenuApi";

    private ModConfigOpener() {
    }

    /** Opens {@code info}'s settings screen, or explains in chat why it could not. */
    public static void open(ModIndex.ModInfo info) {
        Minecraft minecraft = Minecraft.getInstance();
        if (SkyblockSimplifiedSBS.MOD_ID.equals(info.id())) {
            minecraft.setScreenAndShow(new SBSMainScreen());
            return;
        }
        Screen screen = configScreen(info.id(), null);
        if (screen != null) {
            minecraft.setScreenAndShow(screen);
            return;
        }
        SBSChat.send(Component.literal(info.name())
                .withColor(SBSChat.WHITE)
                .append(Component.literal(" has no in-game settings screen - its options live in a "
                        + "config file or a command").withColor(0xFF8194B0)));
    }

    /**
     * The config screen a mod registered, or {@code null}.
     *
     * <p>Reflective throughout: the entrypoint interface, its factory map and the factory's own
     * {@code create} method are all resolved by name, so a client without the mod list installed
     * takes the {@code ClassNotFoundException} path and reports "no screen" instead of failing.
     */
    private static Screen configScreen(String modId, Screen parent) {
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            for (EntrypointContainer<?> container
                    : FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT_KEY, apiClass)) {
                Object api = container.getEntrypoint();
                String provider = container.getProvider().getMetadata().getId();
                if (provider.equals(modId)) {
                    Screen screen = fromFactory(apiClass, api, "getModConfigScreenFactory", parent);
                    if (screen != null) {
                        return screen;
                    }
                }
                // A mod may also register screens on behalf of others (config libraries do this).
                Screen provided = fromProvidedFactories(apiClass, api, modId, parent);
                if (provided != null) {
                    return provided;
                }
            }
        } catch (ClassNotFoundException e) {
            return null; // no mod list installed - nothing registered a screen anywhere
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Inspector] config screen lookup failed for {}", modId, t);
        }
        return null;
    }

    /**
     * Calls one of the entrypoint interface's own methods.
     *
     * <p>Resolved on the <i>interface</i>, never on the implementing class: implementations are
     * frequently package-private (or a lambda), and a method handle taken from such a class cannot
     * be invoked from here at all.
     */
    private static Screen fromFactory(Class<?> apiClass, Object api, String methodName, Screen parent)
            throws Exception {
        Method getter = apiClass.getMethod(methodName);
        return create(getter.invoke(api), parent);
    }

    private static Screen fromProvidedFactories(Class<?> apiClass, Object api, String modId, Screen parent)
            throws Exception {
        Method getter;
        try {
            getter = apiClass.getMethod("getProvidedConfigScreenFactories");
        } catch (NoSuchMethodException e) {
            return null;
        }
        Object provided = getter.invoke(api);
        if (!(provided instanceof Map<?, ?> map)) {
            return null;
        }
        return create(map.get(modId), parent);
    }

    /**
     * Calls a screen factory's single {@code Screen -> Screen} method, found on the public interface
     * the factory implements (a factory is usually a lambda, whose own class is not public).
     */
    private static Screen create(Object factory, Screen parent) throws Exception {
        if (factory == null) {
            return null;
        }
        for (Class<?> type : factory.getClass().getInterfaces()) {
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(Screen.class)
                        || !Screen.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Object screen = method.invoke(factory, parent);
                if (screen instanceof Screen result) {
                    return result;
                }
            }
        }
        return null;
    }
}
