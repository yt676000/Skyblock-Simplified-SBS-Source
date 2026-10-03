/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.model;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;

/**
 * The mob families the family-bound weapon enchants key on (Smite, Bane of Arthropods, Ender
 * Slayer, Cubism, Impaling). SkyBlock mobs are dressed-up vanilla entities, so the vanilla entity
 * class is a reliable family signal for almost every mob – the {@code MobCombatCatalog} can still
 * override it by name for the Hypixel-specific exceptions (e.g. Endermites count as <b>Ender</b>
 * mobs on Hypixel, not arthropods).
 */
public enum MobFamily {
    UNDEAD, ARTHROPOD, ENDER, CUBIC, AQUATIC, NONE;

    /** The family of a live entity by its vanilla class, {@link #NONE} when no enchant targets it. */
    public static MobFamily of(Entity entity) {
        if (entity == null) {
            return NONE;
        }
        // Hypixel counts Endermites as Ender mobs ("Ender Slayer now works on all Ender mobs"),
        // so the Ender checks run before the vanilla-arthropod ones.
        if (entity instanceof EnderMan || entity instanceof Endermite
                || entity instanceof EnderDragon || entity instanceof Shulker) {
            return ENDER;
        }
        if (entity instanceof Zombie || entity instanceof AbstractSkeleton
                || entity instanceof WitherBoss || entity instanceof Phantom
                || entity instanceof Zoglin || entity instanceof Ghast) {
            return UNDEAD;
        }
        if (entity instanceof Spider || entity instanceof CaveSpider
                || entity instanceof Silverfish || entity instanceof Bee) {
            return ARTHROPOD;
        }
        // MagmaCube extends Slime; Creepers have counted as Cubism targets since the Cubic rework.
        if (entity instanceof Slime || entity instanceof MagmaCube || entity instanceof Creeper) {
            return CUBIC;
        }
        if (entity instanceof Squid || entity instanceof Guardian
                || entity instanceof AbstractFish || entity instanceof Dolphin) {
            return AQUATIC;
        }
        return NONE;
    }
}
