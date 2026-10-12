# Daily Playtime Rewards

Fabric mod for Minecraft 26.2. Tracks active server playtime and offers four server-authoritative daily rewards.

## Requirements
- Java 25
- Minecraft 26.2 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.2
- VMTrade is only needed if a reward uses `vmtrade:vmutcoin`

## Default rewards
1. 30 minutes — 12 bread
2. 1 hour — water bucket
3. 2 hours — golden apple
4. 4 hours — `vmtrade:vmutcoin`

## Build
On Windows, run `gradlew.bat build` from the project root. On Linux/macOS, run `./gradlew build`. Java 25 is required. The first build downloads Gradle and dependencies. The mod jar and sources jar are written to `build/libs/`. GitHub Actions builds the project and uploads the jar as an artifact when the build succeeds.

## In-game commands
All commands require operator permission.

- `/playtimerewards set <1-4> <count>` — set the selected reward to the item held in the main hand, with the chosen amount. The amount must fit into one stack.
- `/playtimerewards threshold <1-4> <seconds>` — change a playtime threshold. Thresholds must stay in ascending order; minimum is 60 seconds and maximum is 86400.
- `/playtimerewards timezone <zone>` — set the daily reset time zone, e.g. `Europe/Stockholm` or `UTC`.
- `/playtimerewards status` — show the reset time zone, thresholds, and playtime/claimed-reward counts for online players.
- `/playtimerewards save` — save current data.
- `/playtimerewards reload` — reload the saved data.

The reward screen opens with **P** by default (change it in Controls). Reward item names/counts and thresholds are synchronized from the server, so changing a reward updates the UI without resetting player timers or claims.

## Data and safety
Data is stored in `config/daily-playtime-rewards.json`. The file contains the time zone, thresholds, reward definitions, and player records. Before each successful replacement, the previous file is copied to `daily-playtime-rewards.json.bak`.

Playtime is counted on the server while a player is online. Daily progress and claims reset using the configured time zone. The server validates every claim and checks inventory space; a claim is persisted before inventory delivery to prevent duplicate claims. If the data JSON cannot be parsed, saving is blocked rather than overwriting it with empty data. Fix the JSON or restore the `.bak` file, then use `/playtimerewards reload`.

The server saves periodically, on disconnect, and on shutdown. Do not remove the data JSON while the server is running.
