/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import java.util.Locale;
import java.util.Optional;

/**
 * The purposes the mod can send data to the SBS backend for. One scope = one thing the user is
 * asked about, and the unit everything else is keyed on: the network gate, the settings rows, the
 * persisted consent file and the backend's audit log.
 *
 * <p><b>The id is the contract.</b> {@link #id()} is persisted, sent to the backend and used to
 * build translation keys. It is written out by hand and must never be derived from the enum
 * constant, the display title or anything else that a refactor or a reword can move: renaming a
 * constant or retitling a row would otherwise silently reset every user's stored answer to "not
 * granted" (best case) or, worse, match a stored {@code true} onto a different purpose than the one
 * it was given for. Ids are append-only - a scope that goes away keeps its id reserved.
 *
 * <p><b>Everything defaults to off.</b> There is deliberately no per-scope default flag to set: the
 * absence of a stored grant is the only "no", and it is the state a fresh install, a wiped config
 * and an unrecognised id all land in. See {@link ConsentRegistry}.
 *
 * <p><b>Disclosure versions.</b> Each scope carries a {@link #disclosureVersion()}. A stored grant
 * only counts while it matches the current version, so materially changing what a scope covers -
 * new fields in the payload, a new recipient, a longer retention - is done by bumping the number
 * here, which drops that one scope back to off and re-asks. There is no global "accepted the
 * privacy notice once" flag, because that is exactly the construct that lets the meaning of an
 * old yes drift away from what the user actually agreed to.
 */
public enum ConsentScope {

    /**
     * Verifying the paid subscription: the licence token and the mod version, nothing else.
     *
     * <p>{@link Kind#CONTRACT}, so it has no switch. This is not a dark pattern, it is the honest
     * shape of the thing: the mod cannot check whether a subscription is valid without asking the
     * server, so a "no" here is identical to not using the paid features at all - and a toggle that
     * cannot be turned off without breaking the product is a lie dressed as a choice. It is
     * disclosed in the privacy tab as a plain row so that it is visible rather than hidden, and it
     * is the one scope {@link ConsentManager#isGranted} answers true for without a stored grant.
     */
    LICENCE_VALIDATION("licence_validation", 1, Kind.CONTRACT, Availability.AVAILABLE),

    /** Your uuid and Minecraft name attached to the messages you send in community chat. */
    IRC_IDENTITY("irc_identity", 1, Kind.CONSENT, Availability.AVAILABLE),

    /**
     * Publishing your uuid to every other SBS client so your badge renders above your name.
     *
     * <p>This is what the presence heartbeat actually does, and the reason it - not
     * {@link #PRESENCE_SELF} - is the scope guarding it. The disclosure has to say plainly that the
     * uuid goes to <b>every</b> SBS user, not only to friends or party members: the roster is what
     * lets any other client decide whether to draw a badge over your head, so there is no audience
     * to narrow it to.
     */
    BADGE_PUBLIC("badge_public", 1, Kind.CONSENT, Availability.AVAILABLE),

    /**
     * Reserved for online status and which lobby you are in, so friends and party members can see
     * you are online.
     *
     * <p>{@link Availability#PLANNED}: no such feature exists. The heartbeat that does exist
     * publishes a uuid for badges and nothing else - it sends no server or lobby id, and nothing
     * reads an online list. Shipping this as a live toggle would ask people to consent to sharing
     * their location in the game when no code sends it, which is a false disclosure in the
     * direction that flatters us. It becomes {@link Availability#AVAILABLE} when a feature actually
     * needs it, at which point the disclosure describes something real.
     */
    PRESENCE_SELF("presence_self", 1, Kind.CONSENT, Availability.PLANNED),

    /**
     * Reserved. Previously the uuids of everyone loaded around you, uploaded so the server could
     * answer which of them run SBS.
     *
     * <p><b>That upload no longer exists</b> - the payload was deleted rather than disabled,
     * because nearby players are third parties who by construction cannot consent, and a flag that
     * can be flipped back is not a fix. The feature it served (badging nearby SBS users) is being
     * rebuilt inverted: the client will download a roster of users who granted
     * {@link #BADGE_PUBLIC} and match it locally, so nothing about anyone else ever leaves the
     * machine.
     *
     * <p>Kept as {@link Availability#PLANNED} rather than removed so the id stays reserved, the row
     * stays visible while the old behaviour is still fresh in users' minds, and the gate keeps a
     * name to refuse under. It grants nothing and gates no traffic today.
     */
    PRESENCE_NEARBY("presence_nearby", 1, Kind.CONSENT, Availability.PLANNED),

    /** Party id, member uuids and the requirements/stats a party is matched and filtered on. */
    PARTY_FINDER("party_finder", 1, Kind.CONSENT, Availability.AVAILABLE),

    /**
     * Your Minecraft name published on the shared carry-ticket board, with the tickets you open,
     * claim or comment on.
     *
     * <p>Not in the original audit's list of known senders - {@code CarryApi} was found by walking
     * every call site rather than by trusting the list. It is its own scope rather than a corner of
     * {@link #PROFILE_DATA} because it is a different purpose with a different audience: profile
     * data is computed <i>for you</i> and comes back to you, whereas this puts your name in front
     * of other players. Folding the two together would mean someone who wanted item appraisals had
     * consented to being listed publicly.
     */
    CARRY_TICKETS("carry_tickets", 1, Kind.CONSENT, Availability.AVAILABLE),

    /** SkyBlock profile id plus inventory- and collection-derived data, for stats and appraisals. */
    PROFILE_DATA("profile_data", 1, Kind.CONSENT, Availability.AVAILABLE),

    /**
     * Crystal Hollows Structure Sharing: the lobby id you are in, the structures you walk into and
     * where they are, exchanged with every other SBS user in the same lobby.
     *
     * <p>Its own scope because of the lobby id. Sent next to a licence-derived ticket, it tells the
     * backend which lobby a licence is in, which is the datum {@link #PRESENCE_SELF} was reserved
     * for. Here it serves a different purpose with a different audience: no friend list, no online
     * status, only "which structures are known in this lobby". Folding it into
     * {@link #LICENCE_VALIDATION} would make it impossible to refuse.
     *
     * <p>{@link Availability#AVAILABLE}: the client code that sends exists
     * ({@code helper/map/logic/StructureSharing}). The server side is not deployed yet, which the
     * disclosure says plainly; until it is, the ticket request is refused and nothing else is sent.
     */
    HOLLOWS_STRUCTURES("hollows_structures", 1, Kind.CONSENT, Availability.AVAILABLE),

    /**
     * Crash diagnostics: stack traces and mod/Minecraft versions, no player data.
     *
     * <p>{@link Availability#PLANNED} - the mod has no crash reporter, so there is nothing behind
     * this switch yet. It ships visible but disabled rather than as a working toggle that sends
     * nothing, because a switch the user turns on and believes is doing something is worse than an
     * honest "not built yet".
     */
    DIAGNOSTICS("diagnostics", 1, Kind.CONSENT, Availability.PLANNED);

    /** Whether a scope is something the user chooses, or something the product cannot exist without. */
    public enum Kind {
        /** Asked for, defaults to off, revocable at any time. */
        CONSENT,
        /**
         * Necessary to perform the contract the user paid for. Disclosed, never toggled. Keeping
         * these in the same enum as the real choices is deliberate: it means the network gate has
         * one vocabulary, and every request still has to name what it is for.
         */
        CONTRACT
    }

    /** Whether the traffic a scope governs actually exists in this build. */
    public enum Availability {
        /** Wired to real call sites - granting it turns traffic on. */
        AVAILABLE,
        /**
         * Declared and gated, but no code path sends anything for it yet. The settings row renders
         * disabled with a "future update" tooltip. {@link ConsentRegistry} refuses to grant these
         * at all, so a stale consent file or a hand-edited {@code true} cannot arm one.
         */
        PLANNED
    }

    private final String id;
    private final int disclosureVersion;
    private final Kind kind;
    private final Availability availability;

    ConsentScope(String id, int disclosureVersion, Kind kind, Availability availability) {
        this.id = id;
        this.disclosureVersion = disclosureVersion;
        this.kind = kind;
        this.availability = availability;
    }

    /** The stable, persisted, wire-visible identifier. Never derived from display text. */
    public String id() {
        return id;
    }

    /**
     * The version of the disclosure this scope's stored grant must match to still count. Bump when
     * the disclosure changes materially; that resets this scope to off and re-asks.
     */
    public int disclosureVersion() {
        return disclosureVersion;
    }

    public Kind kind() {
        return kind;
    }

    public Availability availability() {
        return availability;
    }

    /** True when the user is offered a switch for this scope (everything except contract terms). */
    public boolean isToggleable() {
        return kind == Kind.CONSENT && availability == Availability.AVAILABLE;
    }

    /** True when the scope is declared but nothing sends anything for it in this build. */
    public boolean isPlanned() {
        return availability == Availability.PLANNED;
    }

    // ------------------------------------------------------------------
    // Translation keys - derived from the id, never from the display text
    // ------------------------------------------------------------------

    private String key(String suffix) {
        return "sbs.privacy.scope." + id + "." + suffix;
    }

    /** Short name of the purpose, e.g. "Community chat identity". */
    public String titleKey() {
        return key("title");
    }

    /** Exactly which fields leave the client. */
    public String dataKey() {
        return key("data");
    }

    /** Why they are sent, in the user's terms rather than the implementation's. */
    public String whyKey() {
        return key("why");
    }

    /** Who receives them - the SBS backend, other SBS users, a named processor. */
    public String recipientsKey() {
        return key("recipients");
    }

    /** How long they are kept. Must describe what the backend actually enforces today. */
    public String retentionKey() {
        return key("retention");
    }

    /** What stops working if the user says no. */
    public String declineKey() {
        return key("decline");
    }

    /**
     * Resolves a persisted id back to its scope, or empty when the file names one this build does
     * not know. Unknown ids are dropped rather than guessed at: an id we cannot map is an id whose
     * meaning we cannot vouch for, and the safe reading of "I do not know what this grant was for"
     * is that it was not given.
     */
    public static Optional<ConsentScope> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalised = id.trim().toLowerCase(Locale.ROOT);
        for (ConsentScope scope : values()) {
            if (scope.id.equals(normalised)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
