/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudra.render.KuudraAlert;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * What the rest of the module does when the run moves on.
 *
 * <p>Kept apart from {@link KuudraTracker} on purpose: the tracker's job is to be <i>right</i> about
 * the state, and it should not also be the place that decides what a phase change sounds like. The
 * tracker calls in here and everything reactive - the call-out, the per-phase resets - lives on this
 * side, where it can be read in one go.
 */
public final class KuudraEvents {

    private static final KuudraEvents INSTANCE = new KuudraEvents();

    /** Phase-change flash colour: the same gold Hypixel writes its own Kuudra headlines in. */
    private static final int PHASE_COLOR = 0xFFFFC24A;

    private KuudraEvents() {
    }

    public static KuudraEvents getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.KuudraSettings cfg() {
        return ConfigManager.getInstance().get().kuudra;
    }

    /** The run has moved from one phase to the next. */
    void onPhaseChanged(KuudraPhase from, KuudraPhase to) {
        SBSConfig.KuudraSettings cfg = cfg();
        if (to == KuudraPhase.SUPPLIES) {
            SupplyTracker.getInstance().onSuppliesStarted();
        }
        if (to == KuudraPhase.BUILD) {
            // The supplies are in; whatever the no-pre watch was waiting for no longer matters.
            SupplyTracker.getInstance().onSuppliesDone();
        }
        if (from == KuudraPhase.BUILD) {
            FreshTracker.getInstance().reset();
        }
        if (cfg.enabled && cfg.phaseAlert && to.inRun()) {
            KuudraAlert.getInstance().flash(to.displayName().toUpperCase(java.util.Locale.ROOT),
                    PHASE_COLOR, cfg.phaseAlertSound, 1.4f);
        }
    }

    /** The run is over, for any reason - finished, wiped, or simply left. */
    void onRunEnd() {
        KuudraAlert.getInstance().clear();
        // Both of these outlive a phase by design (a pool lasts twenty seconds, the floor state
        // until it next changes), so both have to be told the run they belong to is gone.
        KuudraAbilities.getInstance().clear();
        DangerZone.getInstance().reset();
    }
}
