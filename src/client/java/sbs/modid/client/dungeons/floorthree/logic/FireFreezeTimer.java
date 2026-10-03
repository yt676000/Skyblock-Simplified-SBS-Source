/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorthree.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.events.DungeonAlert;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.Locale;

/**
 * The F3 / M3 Fire Freeze call-out: when to cast the Fire Freeze Staff on the Professor so he is
 * frozen the instant he becomes fightable again.
 *
 * <p><b>Why this needs a timer at all.</b> The staff does not freeze when it is cast - it drops a
 * circle and freezes whatever stands in that circle {@value #WINDUP_MS} ms later. The Professor's
 * second phase is exactly the moment that is worth spending on: he arrives moving, and a freeze that
 * catches him on arrival holds him in place for the whole {@value #FROZEN_MS} ms instead of chasing
 * him around the arena. So the cast has to happen <i>before</i> the thing you want frozen exists,
 * which is not something a player can eyeball - hence a clock.
 *
 * <p><b>What starts it.</b> The Professor's own line about his barrier coming down, which is what he
 * says as the phase turns over. Matched on the speaker plus the distinctive half of the sentence
 * rather than the whole thing: the wording around it is Hypixel's to reword, "barrier down" is what
 * carries the meaning, and a timer pinned to a full sentence dies silently on the next tweak.
 *
 * <p><b>The wait is a setting, not a constant.</b> Roughly three and a half seconds after that line
 * is where the community lands, but the reports range over a second, and your ping moves it further.
 * A number that is wrong by half a second wastes the cast entirely, so it is exposed
 * ({@link SBSConfig.DungeonsSettings#fireFreezeCastMs}) instead of being buried here - tune it once
 * against your own runs and it stays tuned.
 *
 * <p>This is a call-out and a clock. It never casts anything, holds no item and presses no key.
 */
public final class FireFreezeTimer {

    private static final FireFreezeTimer INSTANCE = new FireFreezeTimer();

    /** The staff's own delay: the circle freezes this long after it is cast. */
    private static final long WINDUP_MS = 5_000L;

    /** How long the freeze holds once it lands. */
    private static final long FROZEN_MS = 10_000L;

    /** The card lingers this long after the freeze ends, then gets out of the way. */
    private static final long LINGER_MS = 3_000L;

    /** The call-out colour: the staff's own ice blue, so it is not confused with a blood-room "go". */
    private static final int ALERT_COLOR = 0xFF6ED8FF;

    /** What the timer is counting down to right now. */
    public enum Stage {
        /** Waiting for the moment to cast. */
        CAST,
        /** Cast made (or missed): the staff's circle is winding up. */
        WINDUP,
        /** The freeze has landed and is holding. */
        FROZEN,
        /** Over - the card is on its way out. */
        DONE
    }

    private long startedAt;
    private boolean announced;

    private FireFreezeTimer() {
        // The phase-turn line. Speaker + the half of the sentence that carries the meaning.
        ChatPatternRegistry.getInstance().register(
                "(?i)The Professor\\s*:.*barrier down",
                matcher -> onPhaseTurn(), "f3 fire freeze: barrier down");
    }

    public static FireFreezeTimer getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    // ---- read by the HUD ----------------------------------------------------------------------

    /** Whether there is anything to show at all. */
    public boolean active() {
        return startedAt != 0 && stage() != Stage.DONE;
    }

    /** Milliseconds since the Professor's line. */
    public long elapsedMs() {
        return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    /** When to cast, measured from the line - the one number worth tuning. */
    public long castAtMs() {
        return Math.max(0, cfg().fireFreezeCastMs);
    }

    /** What the clock is counting down to right now. */
    public Stage stage() {
        if (startedAt == 0) {
            return Stage.DONE;
        }
        long elapsed = elapsedMs();
        if (elapsed < castAtMs()) {
            return Stage.CAST;
        }
        if (elapsed < castAtMs() + WINDUP_MS) {
            return Stage.WINDUP;
        }
        if (elapsed < castAtMs() + WINDUP_MS + FROZEN_MS + LINGER_MS) {
            return Stage.FROZEN;
        }
        return Stage.DONE;
    }

    /**
     * Milliseconds left in the current stage - the number on the card. Negative once the frozen
     * window is over and only the linger is left, so the card can say so instead of counting past it.
     */
    public long remainingMs() {
        long elapsed = elapsedMs();
        return switch (stage()) {
            case CAST -> castAtMs() - elapsed;
            case WINDUP -> castAtMs() + WINDUP_MS - elapsed;
            case FROZEN -> castAtMs() + WINDUP_MS + FROZEN_MS - elapsed;
            case DONE -> 0;
        };
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Called every client tick: fires the cast call-out once, and forgets the fight on the way out. */
    public void onClientTick() {
        if (startedAt == 0) {
            return;
        }
        if (!DungeonStateManager.getInstance().inDungeon()) {
            reset();
            return;
        }
        if (announced || elapsedMs() < castAtMs()) {
            return;
        }
        announced = true;
        if (!cfg().fireFreezeAlert) {
            return;
        }
        DungeonAlert.getInstance().trigger("USE FIRE FREEZE", ALERT_COLOR, true, 1.7f);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(
                    "§8[§bSBS§8]§r §bCast Fire Freeze §7- freezes in " + seconds(WINDUP_MS)));
        }
    }

    private void reset() {
        startedAt = 0;
        announced = false;
    }

    /**
     * The Professor's phase turned over. Only inside a dungeon, and only on floor 3 when the floor is
     * known at all - an unknown floor is not a reason to stay quiet, since nobody else says this line.
     */
    private void onPhaseTurn() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        int floor = state.floorNumber();
        if (!cfg().fireFreezeTimer || !state.inDungeon() || (floor != 0 && floor != 3)) {
            return;
        }
        startedAt = System.currentTimeMillis();
        announced = false;
    }

    /** {@code 3.5s} - the whole feature is about tenths, so tenths are what it shows. */
    public static String seconds(long ms) {
        return String.format(Locale.US, "%.1fs", Math.max(0, ms) / 1000.0);
    }
}
