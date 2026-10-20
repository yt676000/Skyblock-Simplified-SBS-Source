# Skyblock Simplified (SBS)

Open source Quality of Life mod for Hypixel SkyBlock on Fabric. Dungeon maps and puzzle helpers, garden and mining tools, slayer helpers, Bazaar pricing, waypoints, trackers, a profile viewer and a fully configurable HUD.

SBS only displays information and improves menus and UI. It never automates gameplay or sends actions on the player's behalf.

## Download
- CurseForge: https://www.curseforge.com/minecraft/mc-mods/skyblock-simplified-sbs
- Website: https://skyblocksimplified.de
- Discord: support, sneak peeks and the Mod Tester Program

## Architecture & Hypixel API usage
- The mod itself never contains or uses a Hypixel API key, and players never enter their own key.
- Features that need the API (e.g. the profile viewer) talk to our own backend, which is the only place the key is used. The backend serves processed data to SBS only and is not a public proxy.
- Profile lookups are cached for 5 minutes on our server and on the client.
- Auction and Bazaar data comes from Hypixel's public, keyless endpoints and is fetched once per API update.
- The backend is closed source; this repository contains the full client source.

## Building
```
./gradlew build
```
The built jar is located in `build/libs`.

## License
GPL-3.0 with an additional permission to link with Minecraft and Minecraft mods. See LICENSE.

## Disclaimer
Skyblock Simplified is not affiliated with or endorsed by Hypixel Inc., Mojang or Microsoft.
Developed by CodeCreative (IGN: Panikstrange).
