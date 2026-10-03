/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.util.Arrays;

/**
 * Makes the embedded MCEF browser pass standard web verification challenges (Cloudflare &amp; co.),
 * which otherwise reject even a <b>manually solved</b> challenge because the environment looks
 * non-standard. Two MCEF defaults cause that:
 * <ul>
 *   <li>{@code CefSettings.user_agent_product = "MCEF-Modern/0"} – replaces the {@code Chrome/146.x}
 *       token in the user agent with an unknown product, so the browser no longer identifies as the
 *       Chromium it actually is. Nulling it restores the real default Chromium UA.</li>
 *   <li>{@code --disable-web-security} – disables the same-origin policy; challenge scripts detect
 *       that (and it is a security hole for arbitrary sites anyway). The flag is dropped; the other
 *       two switches (autoplay, widevine) stay.</li>
 * </ul>
 * Cookies already persist across sessions via MCEF's own {@code config/mcef-modern/cache} Chromium
 * profile, so a solved clearance survives restarts once the environment is accepted.
 *
 * <p><b>DRM video (Crunchyroll, Netflix &amp; co).</b> MCEF passes {@code --enable-widevine-cdm},
 * but since CEF M93 the Widevine CDM binary itself is fetched by Chromium's <b>component updater</b>
 * after startup – on its own leisurely schedule, which in a game session effectively means never.
 * {@code --component-updater=fast-update} forces that download right after startup (into the
 * {@code WidevineCdm} directory of the cache profile); on Windows DRM playback becomes available a
 * few seconds after the first install, on other platforms after the next restart.
 */
@Mixin(targets = "net.dimaskama.mcef.impl.MCEFApiImpl", remap = false)
public abstract class McefBrowserEnvironmentMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(stringValue = "MCEF-Modern/0"), remap = false)
    private String skyblockSimplified$realChromiumProduct(String original) {
        return null; // unset -> CEF builds the default UA with the real Chrome/<version> token
    }

    @ModifyArg(method = "<init>", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lme/friwi/jcefmaven/CefAppBuilder;addJcefArgs([Ljava/lang/String;)V"))
    private String[] skyblockSimplified$fixBrowserArgs(String[] args) {
        String[] cleaned = Arrays.stream(args)
                .filter(arg -> !"--disable-web-security".equals(arg))
                .toArray(String[]::new);
        // Append the component-updater switch that actually downloads the Widevine CDM.
        String[] out = Arrays.copyOf(cleaned, cleaned.length + 1);
        out[cleaned.length] = "--component-updater=fast-update";
        return out;
    }
}
