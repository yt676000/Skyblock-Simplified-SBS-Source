/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.prerender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Bazaar screen as it was last seen: its identity, its slots, and when it was taken.
 *
 * <p>Slots are stored as SNBT through {@code ItemStack.OPTIONAL_CODEC} — the same round trip
 * {@code helper/storage/StorageIndex} uses, chosen so the item's components survive rather than a
 * name and a texture. That matters here because the lore <i>is</i> the payload: the whole price
 * question in this feature is about lines inside it.
 *
 * <p><b>Everything on this record is what was observed, never what was inferred.</b> The timestamp
 * is the capture time and is the only honest basis for the age shown in the disclaimer; nothing here
 * is refreshed in place, because a captured screen that quietly updated would be indistinguishable
 * from a live one and the entire point is that the player can tell.
 */
public final class CapturedScreen {

    /** Written into the file so a build reading someone else's format discards it instead of guessing. */
    public int formatVersion = BazaarScreenKey.FORMAT_VERSION;

    /** The key this was stored under - see {@link BazaarScreenKey}. */
    public String key = "";

    /** The container title as displayed, formatting intact, so a preview can draw the real header. */
    public String title = "";

    /** The menu's own slot count, excluding the player inventory. */
    public int slotCount;

    /** Epoch millis at capture. The age in the disclaimer is derived from this and nothing else. */
    public long capturedAtMs;

    /**
     * Slot index to SNBT. Empty slots are absent rather than stored as empty strings — a Bazaar
     * screen is mostly filler, and absent-means-empty is both smaller and unambiguous.
     */
    public Map<String, String> slots = new LinkedHashMap<>();

    /**
     * What clicking a slot on this screen was observed to open: slot index to the key that arrived.
     *
     * <p>This is the part the brief did not ask for and the feature does not work without. At the
     * moment of a click there is no arriving title to look a cache up by — the screen does not exist
     * yet, and that gap is exactly the latency being hidden. So the lookup has to be "what did this
     * click open last time", which is only knowable by having watched it. Recording it during phase 1
     * is what makes a phase 2 preview possible without a storage-format change.
     */
    public Map<String, String> opensOnClick = new LinkedHashMap<>();

    /** Gson needs this; nothing else should use it. */
    public CapturedScreen() {
    }

    public CapturedScreen(String key, String title, int slotCount, long capturedAtMs) {
        this.key = key;
        this.title = title;
        this.slotCount = slotCount;
        this.capturedAtMs = capturedAtMs;
    }

    /** Age of this capture right now, in milliseconds. */
    public long ageMs() {
        return Math.max(0L, System.currentTimeMillis() - capturedAtMs);
    }

    /** The SNBT at a slot, or {@code null} when that slot was empty when captured. */
    public String slot(int index) {
        return slots.get(Integer.toString(index));
    }

    public void putSlot(int index, String snbt) {
        if (snbt != null && !snbt.isEmpty()) {
            slots.put(Integer.toString(index), snbt);
        }
    }

    /** Records that clicking {@code slotIndex} here was seen to open {@code targetKey}. */
    public void recordOpens(int slotIndex, String targetKey) {
        if (targetKey != null && !targetKey.isEmpty()) {
            opensOnClick.put(Integer.toString(slotIndex), targetKey);
        }
    }

    /** The screen key a click on this slot opened last time, or {@code null} if never observed. */
    public String opensFrom(int slotIndex) {
        return opensOnClick.get(Integer.toString(slotIndex));
    }

    /**
     * Slot indices where this capture and {@code other} disagree, for the match-rate diagnostic.
     *
     * <p>Compared on the stored SNBT, so a lore line that changed by one digit counts as a
     * difference. That is deliberately strict: the question phase 1 has to answer is whether a
     * cached layout can stand in for an arriving one, and "the prices moved" is precisely the way it
     * cannot. The counts it produces are meant to be read alongside {@link #structuralDiff}, which
     * asks the weaker question.
     */
    public List<Integer> diff(CapturedScreen other) {
        List<Integer> differing = new ArrayList<>();
        if (other == null) {
            return differing;
        }
        int slots = Math.max(this.slotCount, other.slotCount);
        for (int i = 0; i < slots; i++) {
            String mine = slot(i);
            String theirs = other.slot(i);
            if (mine == null ? theirs != null : !mine.equals(theirs)) {
                differing.add(i);
            }
        }
        return differing;
    }

    /**
     * Slot indices where the two differ in whether a slot is filled at all.
     *
     * <p>The layout question as opposed to the price question: if this is empty while {@link #diff}
     * is large, the layout really is static and only the numbers inside it moved — which is the
     * premise the whole feature rests on, and the one thing phase 1 exists to confirm or kill.
     */
    public List<Integer> structuralDiff(CapturedScreen other) {
        List<Integer> differing = new ArrayList<>();
        if (other == null) {
            return differing;
        }
        int slots = Math.max(this.slotCount, other.slotCount);
        for (int i = 0; i < slots; i++) {
            if ((slot(i) == null) != (other.slot(i) == null)) {
                differing.add(i);
            }
        }
        return differing;
    }

    /** Rough stored size in bytes, for the on-disk footprint diagnostic. */
    public int approximateBytes() {
        int bytes = title.length() + key.length() + 64;
        for (Map.Entry<String, String> entry : slots.entrySet()) {
            bytes += entry.getKey().length() + entry.getValue().length() + 8;
        }
        for (Map.Entry<String, String> entry : opensOnClick.entrySet()) {
            bytes += entry.getKey().length() + entry.getValue().length() + 8;
        }
        return bytes;
    }
}
