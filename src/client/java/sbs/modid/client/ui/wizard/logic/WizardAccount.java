/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import sbs.modid.client.core.config.SBSFiles;

import java.util.Locale;
import java.util.UUID;

/**
 * The {@link WizardState} for whichever account is logged in, rebound when that changes.
 *
 * <p>The Minecraft half of the wizard's persistence, exactly as {@code ConsentManager} is for
 * consent: {@link WizardState} deliberately knows nothing about Minecraft so its rules can be tested
 * without launching a game, and this class supplies the one thing it is missing - who is playing.
 *
 * <p>Read from {@link User} rather than from {@code Minecraft#player}, so the account is known
 * before any world is joined. Keyed on the profile uuid, never on a name.
 */
public final class WizardAccount {

    private static final WizardAccount INSTANCE = new WizardAccount();

    private WizardState state;
    private String boundAccount;

    private WizardAccount() {
    }

    public static WizardAccount getInstance() {
        return INSTANCE;
    }

    /** Shorthand for the common case. */
    public static WizardState state() {
        return getInstance().current();
    }

    public synchronized WizardState current() {
        String account = currentAccount();
        if (state == null || !account.equals(boundAccount)) {
            state = new WizardState(new WizardStore(SBSFiles.wizardFile(account)), account);
            boundAccount = account;
        }
        return state;
    }

    private static String currentAccount() {
        Minecraft minecraft = Minecraft.getInstance();
        User user = minecraft == null ? null : minecraft.getUser();
        UUID id = user == null ? null : user.getProfileId();
        // No account id (offline / very early startup): one shared "unknown" record. It fails towards
        // showing, like every other unknown here.
        return id == null ? "unknown" : id.toString().toLowerCase(Locale.ROOT);
    }
}
