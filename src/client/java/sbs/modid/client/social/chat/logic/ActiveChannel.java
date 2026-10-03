/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.social.chat.model.SendChannel;

/**
 * Which channel typed messages go to, and every rule for giving that up again.
 *
 * <p><b>The resets are the feature.</b> Prefixing a message is three lines; the part that decides
 * whether this is safe to ship is when the selection stops applying. A party channel that outlives
 * the party is how a message meant for four people ends up in front of a lobby, and nothing about
 * that failure announces itself — the send looks exactly like every other send.
 *
 * <h2>What resets it, and what cannot</h2>
 *
 * <ul>
 *   <li><b>World change, server hop, disconnect</b> — anything that ends the instance. Wired from
 *       the same tick edge every other cross-instance reset in this mod uses.</li>
 *   <li><b>Losing the party</b>, while party is the active channel. {@code PartyTracker} parses the
 *       leave and disband lines already, so this transition is genuinely observable.</li>
 *   <li><b>Guild and co-op: not detectable.</b> Nothing in this mod knows whether the player is in a
 *       guild or on a co-op profile — inbound lines are classified, membership is not tracked, and
 *       silence proves nothing. So a stale guild or co-op selection can only be caught by the player
 *       reading the indicator. That is a real gap and it is why the indicator is not optional.</li>
 * </ul>
 *
 * <h2>Sticky within a session — decided, not left open</h2>
 *
 * <p>The brief asked for a choice between an inactivity timeout and resetting when the chat closes.
 * Neither is used. Resetting on close makes the feature pointless — the chat closes after every
 * message, so the channel would have to be picked again every time, which is what typing the prefix
 * already does. An inactivity timeout is worse than either: the selection would expire at a moment
 * the player has no reason to notice, so the indicator they last saw and the channel they are about
 * to send to would silently disagree, and the whole safety argument rests on those two matching.
 *
 * <p>So it is sticky until something real ends it, and the cost of that is paid by the indicator
 * being on screen the entire time it is set.
 */
public final class ActiveChannel {

    private static final ActiveChannel INSTANCE = new ActiveChannel();

    /** Session state. Only mirrored into the config when the player asked for it to persist. */
    private volatile SendChannel active = SendChannel.PUBLIC;

    private ActiveChannel() {
    }

    public static ActiveChannel getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ChannelSendSettings cfg() {
        return ConfigManager.getInstance().get().channelSend;
    }

    /** Whether the whole behaviour is switched on. Off by default; off means nothing is prefixed. */
    public static boolean enabled() {
        return cfg().enabled;
    }

    /**
     * The channel a message typed right now would go to.
     *
     * <p>{@link SendChannel#PUBLIC} whenever the feature is off, so every caller — the prefixer and
     * the indicator alike — gets "send untouched, show nothing" from one check rather than each
     * remembering to test the toggle.
     */
    public SendChannel active() {
        return enabled() ? active : SendChannel.PUBLIC;
    }

    /** Whether anything is being added to what the player types. */
    public boolean prefixing() {
        return active().prefixes();
    }

    /**
     * Selects a channel.
     *
     * <p>Persisted only when the player switched persistence on. The default is not to: a channel
     * surviving a restart is the least predictable version of this, because the selection was made
     * in a session whose context — the party, the guild conversation — is gone by the time the
     * player types again.
     */
    public void set(SendChannel channel) {
        SendChannel next = channel == null ? SendChannel.PUBLIC : channel;
        if (next == active) {
            return;
        }
        active = next;
        if (cfg().persist) {
            cfg().active = next;
            ConfigManager.getInstance().save();
        }
    }

    /** Restores a persisted selection at startup, or leaves the default in place. */
    public void load() {
        SBSConfig.ChannelSendSettings settings = cfg();
        active = settings.persist && settings.active != null ? settings.active : SendChannel.PUBLIC;
    }

    /** Back to sending untouched. */
    public void reset(String reason) {
        if (active == SendChannel.PUBLIC) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chat] send channel {} -> Public ({})", active, reason);
        active = SendChannel.PUBLIC;
        if (cfg().persist) {
            cfg().active = SendChannel.PUBLIC;
            ConfigManager.getInstance().save();
        }
    }

    /**
     * The instance ended: a world change, a server hop, a disconnect.
     *
     * <p>Unconditional, and it ignores the persistence setting on purpose. Persistence is about
     * surviving a restart the player chose; it is not a reason to carry a party channel into a lobby
     * the party is not in.
     */
    public void onWorldChange() {
        reset("world change");
    }

    /**
     * The party ended or the player left it.
     *
     * <p>Only acts while party is the active channel, so an unrelated party line cannot clear a
     * guild selection the player is mid-conversation in.
     */
    public void onPartyLost() {
        if (active == SendChannel.PARTY) {
            reset("no longer in a party");
        }
    }
}
