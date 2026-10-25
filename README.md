# Skyblock Simplified (SBS)

Open source Quality of Life mod for Hypixel SkyBlock on Fabric. Dungeon maps and puzzle helpers, garden and mining tools, slayer helpers, Bazaar pricing, waypoints, trackers, a profile viewer and a fully configurable HUD.

SBS only displays information and improves menus and UI. It never automates gameplay or sends actions on the player's behalf.

## Download
- CurseForge: https://www.curseforge.com/minecraft/mc-mods/skyblock-simplified-sbs
- Website: https://skyblocksimplified.de
- Discord: support, sneak peeks and the Mod Tester Program

## Free and premium features
- Free forever: all client-side features and selected server features, including the profile viewer (from v1.0.0-beta.13).
- Optional premium tier (planned, not available yet): flip calculations and price analytics based on our own historical price index, plus purely visual supporter cosmetics.

## Architecture & Hypixel API usage
- The mod never contains or uses a Hypixel API key, and players never enter one.
- Users authenticate with SBS licence tokens issued by us. These tokens only work against our own backend and are not Hypixel keys.
- Features that need the Hypixel API (profile viewer, party finder requirement checks) talk to our backend, which is the only place our Hypixel key is used. The backend returns processed data to SBS only, with per-token rate limits, and is not a public proxy.
- Profile lookups are cached for 15 minutes on our server, name lookups for 6 hours.
- Auction, Bazaar and resource data comes from Hypixel's public, keyless endpoints and is fetched once per API update.
- The backend is closed source; this repository contains the full client source.

## Building
```
./gradlew build
```
The built jar is located in `build/libs`.

## License
Copyright (C) 2026 CodeCreative GbR

GPL-3.0 with an additional permission to link with Minecraft and Minecraft mods. See LICENSE.

## Disclaimer
Skyblock Simplified is not affiliated with or endorsed by Hypixel Inc., Mojang or Microsoft.
Developed by CodeCreative GbR (IGN: Panikstrange).
