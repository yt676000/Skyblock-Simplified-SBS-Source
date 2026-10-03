/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.economy.bazaar.logic.BazaarSearchHistory;

/**
 * Bazaar / Auction House <b>search history</b> on Hypixel's search sign.
 *
 * <p>Hypixel opens a client-side {@link AbstractSignEditScreen} (via an open-sign-editor packet) for
 * every Bazaar/AH search. This mixin marks such a sign as a search input when a Bazaar/AH GUI was up
 * right before it ({@link BazaarSearchHistory#recentSearchContext()}), then:
 * <ul>
 *   <li>draws the recent-searches panel over it ({@code extractRenderState}),</li>
 *   <li>records the typed query when the sign closes ({@code removed}),</li>
 *   <li>and lets ↑/↓ browse a past search into the input line ({@code keyPressed}).</li>
 * </ul>
 *
 * <p>Clicks cannot be used: {@code Screen} has no {@code mouseClicked} of its own (it is a default
 * from {@code ContainerEventHandler}) and the sign screen does not override it, so there is no mouse
 * method on the sign to inject into – hence the keyboard path. Every private-member access is shadowed
 * against the names verified for 26.2 and guarded, so a non-search sign is left untouched. The context
 * heuristic is a best-guess (like the other SBS detectors) and can be tuned live.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class SignSearchMixin {

    @Unique
    private static final int SBS_KEY_UP = 265;
    @Unique
    private static final int SBS_KEY_DOWN = 264;

    @Shadow
    private String[] messages;

    @Shadow
    private int line;

    @Shadow
    private void setMessage(String message) {
    }

    @Unique
    private boolean skyblockSimplified$searchSign;

    /** History entry currently browsed into the input line, or -1 for the player's own text. */
    @Unique
    private int skyblockSimplified$sel = -1;

    @Inject(method = "init", at = @At("TAIL"))
    private void skyblockSimplified$markSearchSign(CallbackInfo ci) {
        // A re-order armed from the Order History panel is waiting for the Bazaar's custom-amount
        // sign, and that sign opens under the same Bazaar context as a search sign - so it gets first
        // refusal here, and a sign it claims is never also treated as a search input.
        if (skyblockSimplified$fillReorderAmount()) {
            skyblockSimplified$searchSign = false;
            return;
        }
        BazaarSearchHistory history = BazaarSearchHistory.getInstance();
        skyblockSimplified$searchSign = history.enabled() && history.recentSearchContext();
        if (skyblockSimplified$searchSign) {
            // Freeze which shop this sign belongs to: the Bazaar and the Auction House keep separate
            // histories, and the sign has to read and write the one it was opened from.
            history.beginSign();
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void skyblockSimplified$renderHistory(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                  float partialTick, CallbackInfo ci) {
        if (!skyblockSimplified$searchSign) {
            return;
        }
        // Re-bound every frame: it carries the live typed text (so the suggestions follow what you
        // are typing) and re-asserts which sign a click should be handed to.
        BazaarSearchHistory history = BazaarSearchHistory.getInstance();
        history.bindSign(this::skyblockSimplified$runQuery, skyblockSimplified$typedQuery());
        history.render(g, Minecraft.getInstance().font, skyblockSimplified$sel, mouseX, mouseY);
    }

    /**
     * Types an armed re-order quantity into the Bazaar's custom-amount sign, and reports whether it
     * did.
     *
     * <p>The sign is <b>not</b> submitted. Filling the number is the whole of the automation; the
     * player still confirms the amount and then the order itself, which is what keeps a mis-detected
     * sign a cosmetic annoyance rather than an order nobody meant to place.
     *
     * <p>Detection is by hint line, not by slot or screen order: Hypixel labels the amount sign with
     * a line saying what it wants ("amount"/"quantity"), and matching that survives a GUI change that
     * moves the sign around. When it does not match, nothing is filled and the arm is left for the
     * next sign - the clipboard copy is still there as the manual path.
     */
    @Unique
    private boolean skyblockSimplified$fillReorderAmount() {
        sbs.modid.client.economy.bazaar.logic.BazaarReorder reorder =
                sbs.modid.client.economy.bazaar.logic.BazaarReorder.getInstance();
        if (!reorder.isArmed() || !skyblockSimplified$isAmountSign()) {
            return false;
        }
        int amount = reorder.consumeAmount();
        if (amount <= 0) {
            return false;
        }
        this.line = 0;
        setMessage(Integer.toString(amount));
        return true;
    }

    /** Whether this sign's hint lines say it is asking for a quantity. */
    @Unique
    private boolean skyblockSimplified$isAmountSign() {
        String[] lines = this.messages;
        if (lines == null) {
            return false;
        }
        // Line 0 is the input the player types into; the rest are Hypixel's own labelling.
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].toLowerCase(java.util.Locale.ROOT);
            if (line.contains("amount") || line.contains("quantity")) {
                return true;
            }
        }
        return false;
    }

    /** Puts a chosen query on the input line and closes the sign, which submits it to the server. */
    @Unique
    private void skyblockSimplified$runQuery(String query) {
        this.line = 0;
        setMessage(query);
        ((AbstractSignEditScreen) (Object) this).onClose();
    }

    /** ↑/↓ browse the recent searches into the input line (like shell history). */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$historyKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!skyblockSimplified$searchSign) {
            return;
        }
        int size = BazaarSearchHistory.getInstance().size();
        if (size == 0) {
            return;
        }
        int key = event.key();
        // The panel lists most-recent-first (index 0 on top), so UP must walk toward index 0 and
        // DOWN toward the older entries below - matching the highlight's on-screen direction.
        if (key == SBS_KEY_UP) {
            skyblockSimplified$sel = Math.max(skyblockSimplified$sel - 1, 0);
        } else if (key == SBS_KEY_DOWN) {
            skyblockSimplified$sel = Math.min(skyblockSimplified$sel + 1, size - 1);
        } else {
            return;
        }
        String query = BazaarSearchHistory.getInstance().recent().get(skyblockSimplified$sel);
        this.line = 0;
        setMessage(query);
        cir.setReturnValue(true);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void skyblockSimplified$captureQuery(CallbackInfo ci) {
        if (skyblockSimplified$searchSign) {
            BazaarSearchHistory.getInstance().record(skyblockSimplified$typedQuery());
            BazaarSearchHistory.getInstance().unbindSign();
        }
    }

    /** The player's typed query: the first non-blank sign line (line 0 is the input). */
    @Unique
    private String skyblockSimplified$typedQuery() {
        if (messages == null) {
            return null;
        }
        for (String message : messages) {
            if (message != null && !message.trim().isEmpty()) {
                return message;
            }
        }
        return null;
    }
}
