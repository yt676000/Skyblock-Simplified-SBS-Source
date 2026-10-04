/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.model.HealthBarMode;
import sbs.modid.client.ui.hud.logic.HypixelHudState;
import sbs.modid.client.ui.hud.model.ManaBarMode;
import sbs.modid.client.ui.hud.render.SBSHudRenderer;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.helper.rarity.RarityOverlay;

/**
 * Hooks the player HUD for the GUI module. It targets the granular vanilla sub-renderers so each HUD
 * element can be handled independently (the armor / XP / air-bubble HUD is otherwise left as vanilla):
 * <ul>
 *   <li><b>Health / Mana bars</b>: {@code extractHearts} / {@code extractFood} are cancelled and the
 *       SBS rounded bars drawn when their mode is <i>SBS</i> / <i>SBS Outlined</i>.</li>
 *   <li><b>Hide toggles</b>: {@code extractArmor} / {@code extractEffects} are cancelled when the
 *       corresponding "Hide" setting is on.</li>
 *   <li><b>GUI editor</b>: each editable element's render call is wrapped in {@link HudLayout#begin}/
 *       {@link HudLayout#end} (a position + scale matrix on the shared pose stack), and cancelled
 *       entirely when the element was hidden via its editor minus button. The Hypixel stat texts are
 *       re-rendered at {@code extractHotbarAndDecorations}. The crosshair is intentionally left
 *       untouched (static / non-editable).</li>
 *   <li>{@code setOverlayMessage} feeds the Hypixel action bar into {@link HypixelHudState}.</li>
 * </ul>
 *
 * <p>Every {@code begin} at {@code HEAD} is balanced by an {@code end} at {@code RETURN}; when a HEAD
 * handler cancels the method instead, the RETURN injection never runs, so the pose stack stays
 * balanced in all paths.
 */
@Mixin(Hud.class)
public class HudMixin {

    private static SBSConfig.HypixelGuiSettings sbs$cfg() {
        return ConfigManager.getInstance().get().hypixelGui;
    }

    /**
     * Shared HEAD behaviour for a purely editor-transformed vanilla element: cancel the render when the
     * element is hidden, otherwise push its transform. Returns {@code true} when the caller should stop.
     */
    private static boolean sbs$beginOrHide(GuiGraphicsExtractor g, HudElement element, CallbackInfo ci) {
        if (HudLayout.isHidden(element)) {
            ci.cancel();
            return true;
        }
        HudLayout.begin(g, element);
        return false;
    }

    /**
     * Opens the HUD frame for {@link HudLayout#isVisible}. {@code extractRenderState} is the single
     * entry point every other extract call nests under, so a HEAD injection here is guaranteed to run
     * before any element stamps itself as drawn this frame.
     */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void skyblockSimplified$beginHudFrame(GuiGraphicsExtractor g, DeltaTracker deltaTracker,
                                                  CallbackInfo ci) {
        // Closes the previous frame's measurement window before anything of this frame is drawn.
        sbs.modid.client.core.perf.Perf.endFrame();
        HudLayout.beginFrame();
    }

    /**
     * Overlay Inspector – last of everything, so its outline and card sit above the very overlays it
     * describes. {@code RETURN} rather than {@code TAIL}: the HUD has early exits, and the pointer
     * has to stay on screen on those frames too or it would blink out at the worst moment.
     */
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void skyblockSimplified$overlayInspector(GuiGraphicsExtractor g, DeltaTracker deltaTracker,
                                                     CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.overlayInspector")) {
            sbs.modid.client.helper.overlayinspector.InspectorOverlay.render(g);
        }
    }

    // ------------------------------------------------------------------
    // Health bar (SBS replacement) / vanilla hearts (wrapped for the editor)
    // ------------------------------------------------------------------

    @Inject(method = "extractHearts", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$heartsHead(GuiGraphicsExtractor g, Player player,
                                               int a, int b, int c, int d, float e,
                                               int f, int h, int i, boolean j, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.heartsHead")) {
            HealthBarMode mode = sbs$cfg().healthBar;
            if (player != null && mode.rendersBar()) {
                if (!HudLayout.isHidden(HudElement.SBS_HEALTH_BAR)) {
                    HudLayout.begin(g, HudElement.SBS_HEALTH_BAR);
                    SBSHudRenderer.renderHealthBar(g, player, mode.outlined());
                    HudLayout.end(g);
                }
                ci.cancel();
                return;
            }
            sbs$beginOrHide(g, HudElement.HEARTS, ci);
        }
    }

    @Inject(method = "extractHearts", at = @At("RETURN"))
    private void skyblockSimplified$heartsReturn(GuiGraphicsExtractor g, Player player,
                                                 int a, int b, int c, int d, float e,
                                                 int f, int h, int i, boolean j, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.heartsReturn")) {
            HudLayout.end(g);
        }
    }

    // ------------------------------------------------------------------
    // Mana bar (SBS replacement) / vanilla hunger (wrapped for the editor)
    // ------------------------------------------------------------------

    @Inject(method = "extractFood", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$foodHead(GuiGraphicsExtractor g, Player player, int x, int y, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.foodHead")) {
            ManaBarMode mode = sbs$cfg().manaBar;
            if (mode.rendersBar()) {
                if (!HudLayout.isHidden(HudElement.SBS_MANA_BAR)) {
                    HudLayout.begin(g, HudElement.SBS_MANA_BAR);
                    SBSHudRenderer.renderManaBar(g, mode.outlined());
                    HudLayout.end(g);
                }
                ci.cancel();
                return;
            }
            sbs$beginOrHide(g, HudElement.HUNGER, ci);
        }
    }

    @Inject(method = "extractFood", at = @At("RETURN"))
    private void skyblockSimplified$foodReturn(GuiGraphicsExtractor g, Player player, int x, int y, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.foodReturn")) {
            HudLayout.end(g);
        }
    }

    // ------------------------------------------------------------------
    // Armor bar: hide toggle (setting) + hide (editor) + editor transform (static vanilla method)
    // ------------------------------------------------------------------

    @Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$armorHead(GuiGraphicsExtractor g, Player player,
                                                     int a, int b, int c, int d, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.armorHead")) {
            if (sbs$cfg().hideArmorBar || HudLayout.isHidden(HudElement.ARMOR)) {
                ci.cancel();
                return;
            }
            HudLayout.begin(g, HudElement.ARMOR);
        }
    }

    @Inject(method = "extractArmor", at = @At("RETURN"))
    private static void skyblockSimplified$armorReturn(GuiGraphicsExtractor g, Player player,
                                                       int a, int b, int c, int d, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.armorReturn")) {
            HudLayout.end(g);
        }
    }

    // ------------------------------------------------------------------
    // Potion effects: hide toggle (setting) + hide (editor) + editor transform
    // ------------------------------------------------------------------

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$effectsHead(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.effectsHead")) {
            if (sbs$cfg().hidePotionEffects || HudLayout.isHidden(HudElement.EFFECTS)) {
                ci.cancel();
                return;
            }
            HudLayout.begin(g, HudElement.EFFECTS);
        }
    }

    @Inject(method = "extractEffects", at = @At("RETURN"))
    private void skyblockSimplified$effectsReturn(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.effectsReturn")) {
            HudLayout.end(g);
        }
    }

    // ------------------------------------------------------------------
    // Editor-transformed / hideable vanilla elements (hotbar / chat / scoreboard / boss bar)
    // ------------------------------------------------------------------

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hotbarHead(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.hotbarHead")) {
            if (!sbs$beginOrHide(g, HudElement.HOTBAR, ci)) {
                // SBS hotbar theme + rarity tint: painted before the items so both sit UNDER the icons.
                SBSHudRenderer.renderHotbarTheme(g);
                RarityOverlay.renderHotbar(g);
            }
        }
    }

    /**
     * The actual SBS hotbar restyle: while the Minecraft Overlay theme is on, every hotbar sprite
     * (background, selection frame, offhand) is swallowed here, so the SBS design painted in
     * {@code renderHotbarTheme} – dark rounded slot cells with an accent ring on the selected slot –
     * IS the hotbar instead of just a box glued behind the vanilla texture. Item icons are untouched
     * (they render through {@code extractSlot}, not through these sprites).
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "extractItemHotbar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void skyblockSimplified$hotbarSprites(GuiGraphicsExtractor g,
                                                  com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
                                                  net.minecraft.resources.Identifier sprite,
                                                  int x, int y, int width, int height) {
        if (!ConfigManager.getInstance().get().minecraftOverlay.enabled) {
            g.blitSprite(pipeline, sprite, x, y, width, height);
        }
    }

    /** Cooldown drain on the live HUD hotbar – at TAIL so it stays over the item icons. */
    @Inject(method = "extractItemHotbar", at = @At("TAIL"))
    private void skyblockSimplified$hotbarCooldown(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.hotbarCooldown")) {
            sbs.modid.client.combat.cooldowns.CooldownOverlay.renderHotbar(g);
        }
    }

    /** Item Stack Tips (pet level, minion tier...) on the live HUD hotbar, over the item icons. */
    @Inject(method = "extractItemHotbar", at = @At("TAIL"))
    private void skyblockSimplified$hotbarStackTips(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.hotbarStackTips")) {
            sbs.modid.client.helper.stacktips.StackTips.renderHotbar(g);
        }
    }

    /** Padlock over locked hotbar slots, so a lock is visible without opening the inventory. */
    @Inject(method = "extractItemHotbar", at = @At("TAIL"))
    private void skyblockSimplified$hotbarSlotLocks(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.hotbarSlotLocks")) {
            sbs.modid.client.helper.inventory.render.SlotLockHud.renderHotbar(g);
        }
    }

    /** Ring around the Blaze dagger the Inferno fight needs – over the icons, like the locks. */
    @Inject(method = "extractItemHotbar", at = @At("TAIL"))
    private void skyblockSimplified$blazeDagger(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.blazeDagger")) {
            sbs.modid.client.combat.slayer.render.BlazeDaggerHighlight.renderHotbar(g);
        }
    }

    /** SBS HUD extras (Active Pet card, cooldown text), each its own GUI-editor element. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$hudExtras(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.hudExtras")) {
            SBSHudRenderer.renderHudExtras(g);
        }
    }

    /** Dev: /sbs perf - the KPI overlay. Not measured itself: it would only measure its own reading. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$perfOverlay(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        sbs.modid.client.core.perf.PerfOverlay.render(g);
    }

    /** Quest Guide overlay – self-hiding: draws only while a quest is being tracked. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$questGuide(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.questGuide")) {
            sbs.modid.client.helper.quest.ui.QuestOverlay.render(g);
        }
    }

    /** Skill Progress overlay – self-hiding: draws only while a skill is actively earning XP. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$skillProgress(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.skillProgress")) {
            sbs.modid.client.skills.progress.SkillProgressHud.render(g);
        }
    }

    /** Slayer Carry Counter: boss highlight boxes (world) plus the self-hiding HUD overlay. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$carryCounter(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.carryCounter")) {
            sbs.modid.client.combat.carry.render.CarryHighlight.render(g);
            sbs.modid.client.combat.carry.ui.CarryCounterOverlay.render(g);
        }
    }

    /** Bazaar Orders card – the Manage Orders panel on the HUD; self-hiding with no open orders. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$bazaarOrders(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.bazaarOrders")) {
            sbs.modid.client.economy.bazaar.ui.ManageOrdersPanel.renderHud(g);
        }
    }

    /** Rejoin Timer – centred countdown banner, self-hiding except while waiting out a kick. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$rejoinTimer(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.rejoinTimer")) {
            sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().render(g);
        }
    }

    /** Recipe Viewer NPC locator panel – self-hiding; shows in-world after the recipe screen closes. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$npcLocator(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.npcLocator")) {
            sbs.modid.client.helper.npc.NpcLocator.getInstance().render(g);
        }
    }

    /** Composter status card – self-hiding: draws only while fresh Composter data exists. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$composter(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.composter")) {
            sbs.modid.client.skills.garden.ui.ComposterOverlay.getInstance().render(g);
        }
    }

    /** Visitor Timer card – Garden only. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$visitorTimer(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.visitorTimer")) {
            sbs.modid.client.skills.garden.ui.VisitorTimerHud.render(g);
        }
    }

    /** Visitor shopping list card – self-hiding: Garden only, and only with known offers. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$visitorShopping(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.visitorShopping")) {
            sbs.modid.client.skills.garden.ui.VisitorShoppingHud.render(g);
        }
    }

    /** Farming Tracker card – self-hiding: draws only while farming stat widgets are being served. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$farmingTracker(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.farmingTracker")) {
            sbs.modid.client.skills.garden.logic.FarmingTracker.getInstance().render(g);
            // Pest Profit card: self-gating, on the Garden once a pest was killed this session.
            sbs.modid.client.skills.garden.pests.PestProfitTracker.getInstance().render(g);
        }
    }

    /** Farm Drops card – farming islands only, once a drop has been counted. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$farmDrops(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.farmDrops")) {
            sbs.modid.client.skills.farming.render.FarmDropHud.render(g);
        }
    }

    /** Crop Milestone card – self-hiding: draws only while the tool counter is moving. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$cropMilestone(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.cropMilestone")) {
            sbs.modid.client.skills.farming.logic.CropMilestoneTracker.getInstance().render(g);
            // Jacob's Contest card: self-gating, only while a contest runs.
            sbs.modid.client.skills.farming.contest.JacobContestHud.render(g);
            // Farming Speed card: self-gating, only shortly after a crop was broken.
            sbs.modid.client.skills.farming.logic.FarmingSpeed.getInstance().render(g);
        }
    }

    /** Farming Fortune card – draws only while a farming tool is held. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$farmingFortune(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.farmingFortune")) {
            sbs.modid.client.skills.farming.render.FarmingFortuneDisplay.getInstance().render(g);
        }
    }

    /** Sweep card (Foraging) – draws only while a chop has been reported and is not hidden. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$foragingSweep(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.foragingSweep")) {
            sbs.modid.client.skills.foraging.render.SweepHud.render(g);
            sbs.modid.client.skills.foraging.render.HoneyTimerHud.render(g);
            // Forge Timers card - self-gating.
            sbs.modid.client.economy.forge.render.ForgeTimerHud.render(g);
            // Coins per Hour card - self-gating.
            sbs.modid.client.economy.coinsperhour.render.CoinsPerHourHud.render(g);
            // Hide Nearby Players: the "hidden player in the way" hint under the crosshair - self-gating.
            sbs.modid.client.helper.hideplayers.logic.PlayerHiding.renderHint(g);
            // Fallen Star line - self-gating, Dwarven Mines only.
            sbs.modid.client.skills.mining.fallenstar.render.FallenStarHud.render(g);
            // Lobby Day card - self-gating, Crystal Hollows only.
            sbs.modid.client.helper.reminder.render.LobbyDayHud.render(g);
            // Mining Events card - self-gating, Dwarven Mines and Crystal Hollows only.
            sbs.modid.client.skills.mining.events.render.MiningEventsHud.render(g);
            // Crystal Hollows minimap and map-target heading - self-gating, Hollows only.
            sbs.modid.client.helper.map.render.HollowsMinimapHud.render(g);
            sbs.modid.client.helper.map.render.HollowsTargetHud.render(g);
            // Ability Ready countdown - self-gating, only while an ability is cooling down.
            sbs.modid.client.combat.cooldowns.AbilityReadyAlert.getInstance().render(g);
        }
    }

    /** Hoe Level card – draws only while a levelled farming tool is held. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$hoeLevels(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.hoeLevels")) {
            sbs.modid.client.skills.farming.model.HoeLevels.getInstance().render(g);
        }
    }

    /** Garden Level card – self-hiding: draws only while the Garden widget is being served. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$gardenLevel(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.gardenLevel")) {
            sbs.modid.client.skills.garden.model.GardenLevel.getInstance().render(g);
        }
    }

    /** Pest card and its spawn title – self-hiding away from the Garden. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$pests(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.pests")) {
            sbs.modid.client.helper.buffs.BuffHud.render(g);
            sbs.modid.client.helper.buffs.consumables.render.ConsumableHud.render(g);
            sbs.modid.client.helper.timers.TimerHud.render(g);
            sbs.modid.client.helper.milestone.MilestoneHud.render(g);
            sbs.modid.client.skills.garden.render.PestStatusHud.render(g);
            sbs.modid.client.skills.foraging.beacon.render.BeaconTunerHud.render(g);
            sbs.modid.client.skills.garden.render.SprayonatorHud.render(g);
            // Rift clock - self-hiding outside the Rift.
            sbs.modid.client.helper.rift.render.RiftTimeHud.render(g);
            // Diana's two cards - self-hiding outside the Hub and while the ritual is not running.
            sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.HUD, "Diana HUD",
                    () -> sbs.modid.client.combat.diana.render.DianaHud.render(g));
            sbs.modid.client.skills.garden.logic.PestTracker.getInstance().render(g);
            sbs.modid.client.skills.garden.logic.PestTracker.getInstance().renderCooldown(g);
            sbs.modid.client.skills.garden.logic.PestTracker.getInstance().renderTitle(g);
            // The shared alert title, for any feature using the TITLE channel. Self-hiding.
            sbs.modid.client.core.alert.AlertTitle.render(g);
        }
    }

    /** Collection Tracker card – self-hiding: draws only while a collection counter is tracked. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$collectionTracker(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.collectionTracker")) {
            sbs.modid.client.skills.collection.CollectionTrackerHud.render(g);
        }
    }

    /** Safari summary card – self-hiding: draws after a Safari trip (and, if on, during one). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$safariSummary(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.safariSummary")) {
            sbs.modid.client.skills.hunting.render.SafariSummaryHud.render(g);
            sbs.modid.client.skills.hunting.render.HuntingSessionHud.render(g);
        }
    }

    /** Ability Damage card – draws only while the HUD mode is picked and a hit is still fresh. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$abilityDamage(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.abilityDamage")) {
            sbs.modid.client.combat.damage.render.AbilityDamageHud.render(g);
        }
    }

    /** Melee Damage card – draws only while a melee splash attributed to you is still fresh. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$meleeDamage(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.meleeDamage")) {
            sbs.modid.client.combat.damage.render.MeleeDamageHud.render(g);
        }
    }

    /** Damage Estimate card – draws only while the overlay is on and a mob is under the crosshair. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$damageEstimate(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.damageEstimate")) {
            sbs.modid.client.combat.damage.render.DamageEstimateHud.render(g);
        }
    }

    /** Bestiary Tracker card – draws only while a mob is pinned (kills-to-max from the menu). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$bestiaryTracker(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.bestiaryTracker")) {
            sbs.modid.client.combat.bestiary.BestiaryHud.render(g);
        }
    }

    /** Route List card – the active routes, one line each; only while two or more exist. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$routeList(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.routeList")) {
            sbs.modid.client.helper.routes.render.RouteListHud.render(g);
        }
    }

    /** Garden visitor highlight – valuable/unchecked visitors, only on the Garden. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$visitorHighlight(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.visitorHighlight")) {
            sbs.modid.client.skills.garden.render.VisitorHighlightRenderer.render(g);
        }
    }

    /** Year of the Pig – pig/orb world markers, the session card and the live orb countdown. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$yearOfThePig(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.yearOfThePig")) {
            sbs.modid.client.helper.yearofthepig.render.ShinyPigHighlight.render(g);
            sbs.modid.client.helper.yearofthepig.render.ShinyPigHud.render(g);
            sbs.modid.client.helper.yearofthepig.render.ShinyOrbTimerHud.render(g);
        }
    }

    /** Tank Range – a translucent green ground bubble in the tank's 30-block Diversion radius. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$tankRange(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.tankRange")) {
            sbs.modid.client.dungeons.run.render.TankRangeRender.render(g);
        }
    }

    /** Slayer module – world boxes (boss/beacon/heads), the tracker card and the flash alerts. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$slayer(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.slayer")) {
            sbs.modid.client.combat.slayer.render.SlayerHighlight.render(g);
            sbs.modid.client.combat.slayer.render.SlayerHud.render(g);
            sbs.modid.client.combat.slayer.render.BlazeAttunementHud.render(g);
            sbs.modid.client.combat.slayer.render.SlayerAlerts.getInstance().render(g);
        }
    }

    /**
     * Kuudra – the per-phase world markers (piles, crates, build progress, pods, pearls, the boss
     * hitbox), the run card and the module's own flash call-out. All three self-gate on the module
     * toggle and on being in an actual run.
     */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$kuudra(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.kuudra")) {
            sbs.modid.client.combat.kuudra.render.KuudraHighlight.render(g);
            sbs.modid.client.combat.kuudra.render.KuudraHud.render(g);
            sbs.modid.client.combat.kuudra.render.KuudraAlert.getInstance().render(g);
        }
    }

    /** Blood Helper – blood-room boxes, the spawn markers/tracers and the blood card. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$blood(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.blood")) {
            sbs.modid.client.dungeons.blood.render.BloodHighlight.render(g);
            sbs.modid.client.dungeons.blood.render.BloodHud.render(g);
        }
    }

    /**
     * Spirit Bear (F4/M4 boss room) – the bear box/tracer, the beam on the Spirit Bow it drops and
     * the card, plus the shared dungeon flash alert. The alert is drawn here, once, for every dungeon
     * helper that raises one.
     */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$spiritBear(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.spiritBear")) {
            sbs.modid.client.dungeons.spiritbear.render.SpiritBearHighlight.render(g);
            sbs.modid.client.dungeons.spiritbear.render.SpiritBowHighlight.render(g);
            sbs.modid.client.dungeons.spiritbear.render.SpiritBearHud.render(g);
            sbs.modid.client.dungeons.events.DungeonAlert.getInstance().render(g);
        }
    }

    /**
     * Pelt Tracker – box + tracer on the trapper's quest animal and Trevor's cooldown line
     * (detection runs on the tick), plus the Trapper Crest's collected count over the hotbar item.
     */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$peltTracker(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.peltTracker")) {
            sbs.modid.client.skills.hunting.render.PeltHighlight.render(g);
            sbs.modid.client.skills.hunting.render.PeltCrestOverlay.render(g);
        }
    }

    /**
     * Critter Finder – box, name and tracer on each hiding critter the Safari sweep found. Pure
     * view: the detection runs on the tick, so this only walks the handful of cached sightings.
     */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$critterFinder(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.critterFinder")) {
            sbs.modid.client.skills.hunting.render.HidingCritterHighlight.render(g);
        }
    }

    /**
     * Floor Drops – box, label and tracer on each drop lying on the ground in the Galatea region.
     * Pure view: the sweep runs on the tick, so this only walks the handful of cached drops, and it
     * re-checks each one so a collected drop loses its box on the pickup frame.
     */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$floorDrops(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.floorDrops")) {
            sbs.modid.client.helper.floordrop.render.FloorDropHighlight.render(g);
        }
    }

    /** Death-Save Timers – Bonzo / Spirit / Phoenix cooldown card (chat-driven, drawn from cache). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$abilityTimers(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.abilityTimers")) {
            sbs.modid.client.combat.abilitytimers.render.AbilityCooldownHud.render(g);
        }
    }

    /** Party highlight – through-wall box + nametag on each party member on the island. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$partyHighlight(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.partyHighlight")) {
            sbs.modid.client.social.party.render.PartyHighlight.render(g);
        }
    }

    /** Transparent inventory overlay above the hotbar – its own GUI-editor element (view only). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$inventoryOverlay(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.inventoryOverlay")) {
            sbs.modid.client.helper.inventory.render.InventoryOverlayHud.render(g);
        }
    }

    /** SBS Dungeon Map minimap (Dungeons module) – its own GUI-editor element, block-event gated. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$dungeonMap(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.dungeonMap")) {
            sbs.modid.client.dungeons.run.render.DungeonMapRenderer.render(g);
        }
    }

    /** Dungeon waypoint/highlight rendering only – detection runs on the client tick, this just draws cache. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$dungeonRooms(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.dungeonRooms")) {
            sbs.modid.client.dungeons.run.render.DungeonHighlight.getInstance().render(g);
        }
    }

    /** Livid Tracker – box + tracer on the real Livid and its card; identification runs on the tick. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$livid(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.livid")) {
            sbs.modid.client.dungeons.livid.render.LividHighlight.render(g);
            sbs.modid.client.dungeons.livid.render.LividHud.render(g);
        }
    }

    /** F7/M7 phase splits – the card only reads the chat-driven timer, it measures nothing itself. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$phaseTimer(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.phaseTimer")) {
            sbs.modid.client.dungeons.floorseven.render.FloorSevenPhaseHud.render(g);
            sbs.modid.client.dungeons.floorseven.render.NecronHealerWaypoint.render(g);
            // Simon Says: the order the device's lights came on in, drawn on the buttons themselves.
            sbs.modid.client.dungeons.floorseven.render.SimonSaysWaypoints.render(g);
            // Goldor gate: terminals / devices / levers done, straight off the server's own counter.
            sbs.modid.client.dungeons.floorseven.render.TerminalProgressHud.render(g);
            // M7 dragons: the statue boxes first (world), then the health card over them.
            sbs.modid.client.dungeons.floorseven.render.DragonHighlight.render(g);
            sbs.modid.client.dungeons.floorseven.render.DragonHud.render(g);
        }
    }

    /** F3/M3 Fire Freeze card – reads the chat-armed clock; the flash comes from the shared alert. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$fireFreeze(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.fireFreeze")) {
            sbs.modid.client.dungeons.floorthree.render.FireFreezeHud.render(g);
        }
    }

    /** F6/M6 terracotta respawn boxes – the spots are booked on the tick, this only draws them. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$terracotta(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.terracotta")) {
            sbs.modid.client.dungeons.floorsix.render.TerracottaHighlight.render(g);
        }
    }

    /** F6/M6 giant health – the card plus the world numbers; the tags are read on the tick. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$giantHp(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.giantHp")) {
            sbs.modid.client.dungeons.floorsix.render.GiantHpHud.render(g);
        }
    }

    /** F3/M3 guardian health – the card plus the world numbers; the tags are read on the tick. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$guardianHp(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.guardianHp")) {
            sbs.modid.client.dungeons.floorthree.render.GuardianHpHud.render(g);
        }
    }

    /** Mob Highlight boxes – targets collected on the client tick, drawn from cache here. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$mobHighlight(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.mobHighlight")) {
            sbs.modid.client.combat.mobhighlight.render.MobHighlightRenderer.getInstance().render(g);
        }
    }

    /** Ghost Hunter – the chosen ghost's box + tracer; self-gated, targets come from the tick scan. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$ghostHunter(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.ghostHunter")) {
            sbs.modid.client.combat.ghost.render.GhostHighlight.render(g);
            // Sea Creature Announcer: a short-lived box on the creature that just spawned from your rod.
            sbs.modid.client.skills.fishing.render.SeaCreatureSpawnHighlight.render(g);
            // Metal Detector: the solved treasure block, sized by how sure the readings are.
            sbs.modid.client.skills.mining.metaldetector.render.MetalDetectorRender.render(g);
            // Treasure chests: the uncovered chest's outline and the lockpick spot on it.
            sbs.modid.client.skills.mining.treasurechest.render.TreasureChestRender.render(g);
        }
    }

    /** Fairy Souls – markers for the island's souls; self-gated and island-scoped inside. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$fairySouls(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.fairySouls")) {
            sbs.modid.client.helper.fairysouls.render.FairySoulRenderer.render(g);
            // Enigma Soul waypoints - self-hiding outside the Rift.
            sbs.modid.client.helper.rift.render.EnigmaSoulRenderer.render(g);
            // Blood Effigy markers - self-hiding away from Stillgore Château.
            sbs.modid.client.combat.vampire.render.BloodEffigyRenderer.render(g);
        }
    }

    /** Looking At chip – names the crosshair target; self-hiding, the ray scan is throttled inside. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$lookingAt(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.lookingAt")) {
            sbs.modid.client.helper.lookingat.LookingAtHud.render(g);
        }
    }

    /** Mining Routes: custom waypoint routes + connecting lines, drawn in the world. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$miningRoutes(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.miningRoutes")) {
            sbs.modid.client.skills.mining.logic.MiningRoutesManager.getInstance().render(g);
        }
    }

    /** Lane End Warning: marked lane-area outlines, end arrows and the corner-2 preview. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$laneAreas(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.laneAreas")) {
            sbs.modid.client.skills.farming.logic.LaneEndWarning.getInstance().render(g);
        }
    }

    /** Pickobolus: the blocks the pickaxe-throw ability would break, previewed where you aim. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$pickobolus(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.pickobolus")) {
            sbs.modid.client.skills.mining.render.PickobolusHighlight.render(g);
        }
    }

    /** Chat coordinate waypoints: box + beam every position shared in chat (self-gating on toggle). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$chatWaypoints(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.chatWaypoints")) {
            sbs.modid.client.social.chat.logic.ChatWaypoints.getInstance().render(g);
        }
    }

    /** Frozen Corpse highlight: box Glacite-Mineshaft corpses once seen (self-gating on toggle + area). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$frozenCorpses(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.frozenCorpses")) {
            sbs.modid.client.skills.mining.render.FrozenCorpseHighlight.render(g);
        }
    }

    /** Stopped minions: box + reason over each one the island scan flagged (self-gating on toggle + island). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$stoppedMinions(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.stoppedMinions")) {
            sbs.modid.client.economy.minions.render.MinionStopHighlight.render(g);
        }
    }

    /** Trap Highlighter: dungeon tripwire runs and dispenser boxes, drawn from the indexed set. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$dungeonTraps(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.dungeonTraps")) {
            sbs.modid.client.dungeons.traps.render.TrapEsp.render(g);
        }
    }

    /** Mining Helpers: commission, Heart of the Mountain and tool-uses cards (each self-hiding). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$miningHelpers(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.miningHelpers")) {
            sbs.modid.client.skills.mining.render.MiningHud.render(g);
        }
    }

    /** Gemstone Profit: coins from this session's gemstones (self-hiding, own element). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$gemstoneProfit(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.gemstoneProfit")) {
            sbs.modid.client.skills.mining.render.GemstoneHud.render(g);
        }
    }

    /** Nucleus Run: the run in progress, the last run and the totals (self-hiding, own element). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$nucleusRun(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.nucleusRun")) {
            sbs.modid.client.skills.mining.nucleus.render.NucleusRunHud.render(g);
        }
    }

    /** Glacite Cold card (self-hiding: Glacite zones with a known reading only). */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$glacitCold(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.glacitCold")) {
            sbs.modid.client.skills.mining.render.ColdHud.render(g);
        }
    }

    /** Pathfinding waypoints + route (dev only) – draws the cached path, never computes here. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$pathfinding(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.pathfinding")) {
            sbs.modid.client.core.pathfinding.PathRenderer.render(g);
        }
    }

    /** Secret Routes: waypoints, walk trail, breaker blocks and the pearl/AOTV aim indicator. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$secretRoutes(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.secretRoutes")) {
            sbs.modid.client.dungeons.secretroutes.render.SecretRoutesRenderer.render(g);
        }
    }

    /** Dungeon puzzle solver: boxes the solution in the world (blaze target, ...) – draws from cache. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$dungeonPuzzles(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.dungeonPuzzles")) {
            sbs.modid.client.dungeons.puzzle.PuzzleSolver.getInstance().render(g);
            sbs.modid.client.dungeons.puzzle.render.PuzzleRenderer.render(g);
        }
    }

    /** Ether Warp target highlight – re-raycast every frame so the box tracks the crosshair live. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$etherWarp(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.etherWarp")) {
            sbs.modid.client.helper.etherwarp.EtherWarpRenderer.render(g,
                    deltaTracker.getGameTimeDeltaPartialTick(true));
        }
    }

    /**
     * The build hologram's outlines (Garden Blueprint and Build Tools share it) and Garden Blueprint's
     * custom-area box - self-gated: draw only while a hologram is shown or corners are being picked.
     */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$gardenBlueprint(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.gardenBlueprint")) {
            sbs.modid.client.core.build.render.HologramRenderer.render(g);
            sbs.modid.client.skills.garden.render.GardenBlueprintSelection.render(g);
            sbs.modid.client.helper.build.render.BuildSelectionRenderer.render(g);
            sbs.modid.client.helper.build.render.BuildHud.render(g);
            sbs.modid.client.helper.build.render.BuildHelpHud.render(g);
        }
    }

    /** Pest highlight + infested-plot highlight – self-gated: only in the Garden, only when toggled. */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$pestHighlight(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.pestHighlight")) {
            sbs.modid.client.skills.garden.render.PestHighlight.render(g);
        }
    }

    /**
     * The chat channel tabs. Deliberately NOT on {@code extractChat}: vanilla skips that hook while
     * the chat is focused, which is precisely when the tabs have to be there to be clicked. This
     * hook runs whenever the HUD does, open screen or not.
     */
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void skyblockSimplified$chatTabs(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.chatTabs")) {
            sbs.modid.client.social.chat.render.ChatTabBar.render(g);
            // Where the next typed message is going. Drawn after the tab strip and independently
            // of it: the strip can be switched off or hidden by the HUD editor, and this must not
            // be - it is the only thing standing between a party message and a lobby.
            sbs.modid.client.social.chat.render.ChannelIndicator.render(g);
        }
    }

    @Inject(method = "extractItemHotbar", at = @At("RETURN"))
    private void skyblockSimplified$hotbarReturn(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.hotbarReturn")) {
            HudLayout.end(g);
        }
    }

    @Inject(method = "extractChat", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$chatHead(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.chatHead")) {
            sbs$beginOrHide(g, HudElement.CHAT, ci);
        }
    }

    @Inject(method = "extractChat", at = @At("RETURN"))
    private void skyblockSimplified$chatReturn(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.chatReturn")) {
            HudLayout.end(g);
            // After end(), i.e. in untransformed HUD space - the same space ChatGeometry hit-tests in,
            // so the highlight cannot land anywhere a click would not.
            sbs.modid.client.social.chat.render.ChatSelectionRenderer.render(g);
        }
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$scoreboardHead(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.scoreboardHead")) {
            // Custom Scoreboard: swallow the vanilla sidebar and draw the SBS one instead. Its render()
            // does its own begin/end, so cancelling here (which also skips the RETURN end()) stays balanced.
            if (sbs.modid.client.helper.scoreboard.CustomScoreboardRenderer.active()) {
                sbs.modid.client.helper.scoreboard.CustomScoreboardRenderer.render(g);
                ci.cancel();
                return;
            }
            sbs$beginOrHide(g, HudElement.SCOREBOARD, ci);
        }
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("RETURN"))
    private void skyblockSimplified$scoreboardReturn(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.scoreboardReturn")) {
            HudLayout.end(g);
        }
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$bossHead(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.bossHead")) {
            sbs$beginOrHide(g, HudElement.BOSS_BAR, ci);
        }
    }

    @Inject(method = "extractBossOverlay", at = @At("RETURN"))
    private void skyblockSimplified$bossReturn(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.bossReturn")) {
            HudLayout.end(g);
        }
    }

    // ------------------------------------------------------------------
    // Real Hypixel stats: transform / hide the actual action-bar render (no re-drawn text)
    // ------------------------------------------------------------------

    @Inject(method = "extractOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$actionBarHead(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.actionBarHead")) {
            sbs$beginOrHide(g, HudElement.HYPIXEL_ACTION_BAR, ci);
        }
    }

    @Inject(method = "extractOverlayMessage", at = @At("RETURN"))
    private void skyblockSimplified$actionBarReturn(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.actionBarReturn")) {
            HudLayout.end(g);
        }
    }

    // ------------------------------------------------------------------
    // Hypixel mana data source (unchanged)
    // ------------------------------------------------------------------

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void skyblockSimplified$parseActionBar(Component message, boolean animate, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.parseActionBar")) {
            if (message != null) {
                HypixelHudState.getInstance().parseActionBar(message.getString());
                // Layout Recorder (dev, default off): the action bar's shape.
                sbs.modid.client.core.dev.LayoutRecorder.getInstance().onActionBar(message.getString());
                // Server Scanner (dev): action-bar changes while its channel is on.
                sbs.modid.client.core.dev.scanner.ServerScanner.onActionBar(message);
                // Glacite Cold: capture log + reading (which HUD line carries it is unverified).
                sbs.modid.client.skills.mining.logic.ColdTracker.getInstance().onActionBar(message.getString());
                // Crop Analyzer capture (research, off by default).
                sbs.modid.client.skills.garden.logic.CropAnalyzerCapture.getInstance()
                        .onText("actionbar", message.getString());
            }
        }
    }

    /**
     * Titles / subtitles are read for one line only: "NOT ENOUGH MANA". Hypixel has shown it both as
     * a title and in the action bar, and either way it hides the mana ratio the bar reads – so the
     * bar needs to know it is drawing stale numbers.
     */
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void skyblockSimplified$parseTitle(Component title, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.parseTitle")) {
            if (title != null) {
                HypixelHudState.getInstance().parseTitle(title.getString());
                sbs.modid.client.skills.garden.logic.CropAnalyzerCapture.getInstance()
                        .onText("title", title.getString());
            }
        }
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void skyblockSimplified$parseSubtitle(Component subtitle, CallbackInfo ci) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.parseSubtitle")) {
            if (subtitle != null) {
                HypixelHudState.getInstance().parseTitle(subtitle.getString());
                sbs.modid.client.skills.garden.logic.CropAnalyzerCapture.getInstance()
                        .onText("subtitle", subtitle.getString());
            }
        }
    }
}
