/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.texture.logic.HypixelPackKeeper;

/**
 * The Texture Pack module's two server-pack settings, both hooked on the single pack-push funnel:
 *
 * <ul>
 *   <li><b>Ignore Enforced Packs</b> swallows the push (Hypixel Skyblock marks its pack as required)
 *       while replying with the full success handshake (ACCEPTED → DOWNLOADED → SUCCESSFULLY_LOADED),
 *       so the server never kicks the player and the client keeps its own texture packs active.
 *       Cancelling at HEAD consumes the packet on the network thread before the main-thread
 *       re-dispatch, so the pack is never downloaded and the reply is sent exactly once.</li>
 *   <li><b>Keep Hypixel Pack Loaded</b> (only when the above is off) answers a SkyBlock push whose
 *       hash matches the pack already loaded with the same handshake and drops it, and ignores the
 *       pop of a SkyBlock pack - see {@link HypixelPackKeeper}.</li>
 *   <li><b>Auto-Accept Server Packs</b> (for whatever the above let through) does exactly what clicking
 *       Proceed on the prompt does, before vanilla reads either piece of state: it marks the pack as
 *       enabled for this server <i>and</i> allows server packs on the pack manager, so vanilla
 *       downloads the pack straight away instead of opening the screen. Both halves are needed -
 *       the first only decides whether the screen opens, the second is what accepts the pack.</li>
 * </ul>
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ServerPackBypassMixin {

    @Shadow
    @Final
    protected ServerData serverData;

    @Shadow
    public abstract void send(Packet<?> packet);

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$onPackPush(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        SBSConfig.TexturePackSettings settings = ConfigManager.getInstance().get().texturePack;
        // Ignore Enforced wins over Keep Loaded: it answers every push, so the keeper has nothing to add.
        //
        // The handler runs TWICE for a push it lets through: once on the network thread, where
        // ensureRunningOnSameThread re-schedules it, then again on the client thread. The keeper
        // decides once, on the network pass. Deciding again on the second pass saw its own "vanilla
        // holds this hash" note from the first and skipped the push - so with nothing cached the pack
        // was never downloaded, never cached, and items stayed missing on every start.
        boolean networkPass = !Minecraft.getInstance().isSameThread();
        if (settings.ignoreEnforcedPacks || (networkPass
                && HypixelPackKeeper.onPush(packet.id(), packet.url(), packet.hash()) == HypixelPackKeeper.PushDecision.SKIP)) {
            // Nothing is downloaded here. The bottom fallback pack is whatever copy Minecraft cached on
            // an earlier join with the pack accepted (see HypixelPackFallback). Vanilla's own order
            // (26.2 ServerPackManager): ACCEPTED, DOWNLOADED, then SUCCESSFULLY_LOADED after the reload.
            send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
            send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
            send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
            ci.cancel();
            return;
        }
        if (!settings.autoAcceptServerPacks) {
            return;
        }
        // Vanilla only prompts while the status is PROMPT (or a required pack while DISABLED); flipping
        // it to ENABLED here sends the push down the download branch, prompt-free. Singleplayer and
        // other server-less connections have no ServerData to flip, so they still prompt as before.
        if (serverData != null && serverData.getResourcePackStatus() != ServerData.ServerPackStatus.ENABLED) {
            serverData.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
        }
        // The other half of what clicking Proceed does, and the half that actually answers the server:
        // ConnectScreen locks the pack manager's prompt status in at connect time, so a pack pushed
        // later while it still reads PENDING is queued but never accepted - no ACCEPTED reply, no
        // download, no reload, and the server kicks us for a configuration phase that never ends.
        // Order against vanilla's pushPack does not matter: allowServerPacks also accepts packs that
        // are already queued. Hopping to the client thread keeps the manager off the network thread.
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.getDownloadedPackSource().allowServerPacks());
    }

    /**
     * Keep Loaded: leaving SkyBlock pops its pack, which is a full reload. The pack stays instead.
     * Vanilla sends no reply to a pop ({@code RemovalReason.SERVER_REMOVED} carries none), so neither
     * do we. Pops of any other pack, and pop-all, run as usual.
     */
    @Inject(method = "handleResourcePackPop", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$onPackPop(ClientboundResourcePackPopPacket packet, CallbackInfo ci) {
        if (HypixelPackKeeper.ignorePop(packet.id())) {
            ci.cancel();
        }
    }
}
