/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.util.UUID;

/**
 * The single source of truth for what this client is allowed to send.
 *
 * <p>Everything that talks to the SBS backend asks {@link #isGranted} - in practice through
 * {@code SbsApi.send}, which will not dispatch a request that has not named its
 * {@link ConsentScope}. This class adds the two things {@link ConsentRegistry} deliberately does not
 * know about: which Minecraft account is playing, and the best-effort mirror of each answer to the
 * backend's audit log.
 *
 * <p><b>Local state is authoritative.</b> The backend copy exists so we can evidence a grant, not
 * so we can look one up: a failed, refused or not-yet-deployed {@code /api/privacy/consent} never
 * changes what the client believes, never retries in a loop and never disables the feature the user
 * just switched on. The alternative - treating the server as the record - would mean a user who
 * withdrew consent while offline stays consented, which is precisely backwards.
 *
 * <p><b>Per account.</b> Consent is bound to the Minecraft uuid, so two people sharing a machine do
 * not inherit each other's answers, and it survives config-profile switches (see
 * {@link SBSFiles#privacyDir()}).
 */
public final class ConsentManager {

    private static final ConsentManager INSTANCE = new ConsentManager();

    /** Rebuilt when the logged-in account changes; {@code null} until first use. */
    private ConsentRegistry registry;
    private String boundAccount;

    private ConsentManager() {
    }

    public static ConsentManager getInstance() {
        return INSTANCE;
    }

    /**
     * The account the answers belong to: the Minecraft profile uuid.
     *
     * <p>Read from {@link User} rather than from {@code Minecraft#player} so consent is answerable
     * on the title screen, before any world is joined - the privacy screen has to work at exactly
     * the moment there is no player yet.
     */
    private static String currentAccount() {
        Minecraft minecraft = Minecraft.getInstance();
        User user = minecraft == null ? null : minecraft.getUser();
        UUID id = user == null ? null : user.getProfileId();
        // No account id (offline / very early startup): one shared "unknown" file. It fails safe -
        // an unknown account starts with nothing granted like any other fresh account.
        return id == null ? "unknown" : id.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** The registry for the account currently logged in, rebinding if that account changed. */
    public synchronized ConsentRegistry registry() {
        String account = currentAccount();
        if (registry == null || !account.equals(boundAccount)) {
            registry = new ConsentRegistry(new ConsentStore(SBSFiles.consentFile(account)), account);
            boundAccount = account;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Privacy] Consent loaded for account {}.", account);
        }
        return registry;
    }

    // ------------------------------------------------------------------
    // The question every sender asks
    // ------------------------------------------------------------------

    /**
     * Whether this client may send data for the given purpose right now.
     *
     * <p>A {@code null} scope is a "no". That is the undeclared-call case: a request that never
     * said what it was for cannot be checked, and an uncheckable request is not a permitted one.
     */
    public static boolean isGranted(ConsentScope scope) {
        return scope != null && getInstance().registry().isGranted(scope);
    }

    /** Records a grant and mirrors it to the backend, best-effort. */
    public void grant(ConsentScope scope, ConsentSource source) {
        if (registry().grant(scope, source)) {
            mirror(scope);
        }
    }

    /**
     * Withdraws consent, mirrors it, and asks the backend to delete what it holds for that scope.
     *
     * <p>The deletion request is best-effort in exactly the same way the grant mirror is, and for
     * the same reason: withdrawal has already taken effect locally - the feature is stopped and no
     * further request will pass the gate - so a backend that cannot be reached delays the server-side
     * cleanup but never delays the user's "stop".
     */
    public void revoke(ConsentScope scope, ConsentSource source) {
        if (registry().revoke(scope, source)) {
            mirror(scope);
            PrivacyBackend.requestScopeDeletion(scope);
        }
    }

    /** "Accept all" from the privacy screen. */
    public void grantAll(ConsentSource source) {
        registry().grantAll(source);
        mirrorAll();
    }

    /** "Decline all" from the privacy screen. */
    public void revokeAll(ConsentSource source) {
        registry().revokeAll(source);
        mirrorAll();
        for (ConsentScope scope : ConsentScope.values()) {
            if (scope.kind() != ConsentScope.Kind.CONTRACT) {
                PrivacyBackend.requestScopeDeletion(scope);
            }
        }
    }

    private void mirror(ConsentScope scope) {
        PrivacyBackend.recordConsent(registry().accountId(), scope, registry().state(scope));
    }

    private void mirrorAll() {
        for (ConsentScope scope : ConsentScope.values()) {
            if (scope.kind() != ConsentScope.Kind.CONTRACT) {
                mirror(scope);
            }
        }
    }

    /** Registers a feature start/stop hook against the current account's registry. */
    public void addListener(ConsentRegistry.Listener listener) {
        registry().addListener(listener);
    }
}
