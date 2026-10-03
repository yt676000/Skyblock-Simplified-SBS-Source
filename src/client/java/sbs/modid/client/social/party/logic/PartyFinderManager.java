/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Owns the client's current SBS party state and the live chat/roster poll loop.
 *
 * <p>Once the player creates or joins a party this starts a long-poll cycle against
 * {@link PartyFinderApi}; incoming party chat is mirrored into the game chat (when the
 * "Show Party Chat in Game" toggle is on), exactly like the IRC module. {@code /sbs party
 * <message>} routes here. The rich in-overlay chat field / member management is built on
 * top of this in the next increment.
 */
public final class PartyFinderManager {

    private static final PartyFinderManager INSTANCE = new PartyFinderManager();

    private volatile String currentPartyId;
    private volatile int since;
    /** Guards against two overlapping poll loops after a quick leave/join. */
    private volatile int loopEpoch;

    /** Last poll's party object (roster, note, reqs) – what the My-Party panel renders. */
    private volatile JsonObject partySnapshot;
    /** Recent party chat lines, newest last (ring, cap {@value #CHAT_KEEP}). */
    private final java.util.ArrayDeque<String> chatLines = new java.util.ArrayDeque<>();
    private static final int CHAT_KEEP = 100;

    private PartyFinderManager() {
    }

    public static PartyFinderManager getInstance() {
        return INSTANCE;
    }

    public String currentPartyId() {
        return currentPartyId;
    }

    public boolean inParty() {
        return currentPartyId != null;
    }

    /** Roster/meta of the current party from the last poll, or {@code null}. */
    public JsonObject partySnapshot() {
        return partySnapshot;
    }

    /** Immutable copy of the recent chat lines (already display-formatted). */
    public synchronized java.util.List<String> chatLines() {
        return java.util.List.copyOf(chatLines);
    }

    private synchronized void addChatLine(String line) {
        chatLines.addLast(line);
        while (chatLines.size() > CHAT_KEEP) {
            chatLines.removeFirst();
        }
    }

    // ------------------------------------------------------------------
    // Membership transitions
    // ------------------------------------------------------------------

    /** Enters a party (from create/join success) and starts the live poll loop. */
    public synchronized void enter(String partyId) {
        if (partyId == null || partyId.equals(currentPartyId)) {
            return;
        }
        currentPartyId = partyId;
        since = 0;
        partySnapshot = null;
        knownMembers = java.util.Set.of();
        synchronized (this) {
            chatLines.clear();
        }
        int epoch = ++loopEpoch;
        pollNext(epoch);
    }

    /** Leaves the current party (server-side + local), stopping the poll loop. */
    public synchronized void leave() {
        String id = currentPartyId;
        loopEpoch++;            // invalidate any in-flight poll
        currentPartyId = null;
        partySnapshot = null;
        if (id != null) {
            PartyFinderApi.getInstance().leave(id, (r, e) -> { });
        }
    }

    /** Sends a chat line to the current party ({@code /sbs party <message>}). */
    public void sendChat(String message) {
        String id = currentPartyId;
        if (id == null) {
            info("You are not in an SBS party. Open the Party Finder to join or create one.");
            return;
        }
        if (message == null || message.isBlank()) {
            info("Usage: /sbs party <message>");
            return;
        }
        PartyFinderApi.getInstance().chat(id, message.trim(), (result, error) -> {
            if (error != null) {
                info("Party chat failed: " + error);
            }
        });
    }

    // ------------------------------------------------------------------
    // Poll loop
    // ------------------------------------------------------------------

    private void pollNext(int epoch) {
        String id = currentPartyId;
        if (id == null || epoch != loopEpoch) {
            return; // left, or superseded by a newer loop
        }
        // Withdrawal stops the loop rather than letting each poll be refused at the gate. Both are
        // safe - nothing is sent either way - but a loop that keeps re-arming to be told "no" every
        // 32 seconds is a retry loop in all but name, and this feature is meant to be off.
        if (!sbs.modid.client.core.licence.privacy.ConsentManager.isGranted(
                sbs.modid.client.core.licence.privacy.ConsentScope.PARTY_FINDER)) {
            currentPartyId = null;
            return;
        }
        PartyFinderApi.getInstance().poll(id, since, (result, error) -> {
            if (epoch != loopEpoch) {
                return; // stale loop
            }
            if (error != null) {
                if ("no_such_party".equals(error) || "party_closed".equals(error)) {
                    currentPartyId = null;
                    info("Your SBS party was closed.");
                    return;
                }
                // transient network error: back off a touch, then keep polling
                sleep();
                pollNext(epoch);
                return;
            }
            handlePoll(result);
            pollNext(epoch);
        });
    }

    /** Roster names of the previous poll – the auto-invite diff base. */
    private java.util.Set<String> knownMembers = java.util.Set.of();

    private void handlePoll(JsonObject result) {
        if (result.has("party") && result.get("party").isJsonObject()) {
            JsonObject party = result.getAsJsonObject("party");
            autoInviteNewMembers(party);
            partySnapshot = party;
        }
        JsonArray messages = result.has("messages") && result.get("messages").isJsonArray()
                ? result.getAsJsonArray("messages") : new JsonArray();
        boolean mirror = ConfigManager.getInstance().get().partyFinder.chatInGame;
        for (var element : messages) {
            JsonArray entry = element.getAsJsonArray();  // [seq, ts, name, msg]
            int seq = entry.get(0).getAsInt();
            if (seq > since) {
                since = seq;
            }
            String name = entry.get(2).getAsString();
            String msg = entry.get(3).getAsString();
            String line = name.isEmpty() ? "§7" + msg : "§f" + name + "§7: §f" + msg;
            addChatLine(line);
            if (mirror) {
                show(SBSChat.line(Component.literal(" §d[Party] " + line)));
            }
        }
    }

    /**
     * Leader convenience (config {@code partyFinder.autoInvite}): whenever the roster gains a
     * member, the leader's client fires the real {@code /party invite <name>} for them – joining
     * the SBS party automatically pulls people into the Hypixel party too.
     */
    private void autoInviteNewMembers(JsonObject party) {
        java.util.Set<String> current = new java.util.HashSet<>();
        for (var element : party.getAsJsonArray("members")) {
            current.add(element.getAsJsonObject().get("name").getAsString());
        }
        java.util.Set<String> previous = knownMembers;
        knownMembers = current;
        if (previous.isEmpty()) {
            return; // first snapshot after joining – nobody is "new"
        }
        if (!ConfigManager.getInstance().get().partyFinder.autoInvite) {
            return;
        }
        String self = PartyFinderApi.selfName();
        String leaderUuid = party.getAsJsonObject("leader").get("uuid").getAsString();
        String selfUuid = PartyFinderApi.selfUuid();
        boolean leader = selfUuid != null
                && leaderUuid.equals(selfUuid.replace("-", "").toLowerCase(java.util.Locale.ROOT));
        if (!leader) {
            return;
        }
        for (String name : current) {
            if (!previous.contains(name) && !name.equalsIgnoreCase(self)) {
                sbs.modid.client.core.command.SBSCommands.run("/party invite " + name);
            }
        }
    }

    // ------------------------------------------------------------------
    // Chat output (client thread)
    // ------------------------------------------------------------------

    private void info(String text) {
        show(SBSChat.line(Component.literal(" §7" + text)));
    }

    private static void show(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(component);
            }
        });
    }

    private static void sleep() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
