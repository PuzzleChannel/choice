# Daily Playtime Rewards

Fabric mod for Minecraft 26.2. Tracks active server playtime and offers four server-authoritative daily rewards.

## Requirements
- Java 25
- Minecraft 26.2 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.2
- VMTrade is needed when a reward uses `vmtrade:vmcoin`

## Default rewards
1. 30 minutes — 12 bread
2. 1 hour — water bucket
3. 2 hours — golden apple
4. 4 hours — `vmtrade:vmcoin`

## Build
On Windows, run `gradlew.bat build` from the project root. On Linux/macOS, run `./gradlew build`. Java 25 is required. The first build downloads Gradle and dependencies. The mod jar and sources jar are written to `build/libs/`. GitHub Actions builds the project and uploads the jar as an artifact when the build succeeds.

## In-game commands
All commands below require operator permission.

- `/playtimerewards set <1-4> <count>` — set the selected reward to the item held in the main hand. The amount must fit into one stack.
- `/playtimerewards threshold <1-4> <seconds>` — change a playtime threshold. Thresholds must stay in ascending order; minimum is 60 seconds and maximum is 86400.
- `/playtimerewards timezone <zone>` — set the daily reset time zone, e.g. `Europe/Stockholm` or `UTC`.
- `/playtimerewards status` — show the reset time zone, thresholds, and playtime/claimed-reward counts for online players.
- `/playtimerewards save` — save current data.
- `/playtimerewards reload` — reload the saved data.

Press **P** to open the reward screen. When a reward becomes available, the player receives a chat notification with a clickable **[ОТКРЫТЬ НАГРАДЫ]** link that opens the same screen. Each card displays the actual item icon and quantity (including VMTrade coin), required playtime, progress, and claim status. Only `vmtrade:vmcoin` has a description: “Используется для обмена на Маркете”. The top of the screen shows a countdown to the next daily reset. Time is shown compactly without leading zeroes, for example `1ч 25м 15с`.

## Automated tests

Run `gradlew.bat test` on Windows or `./gradlew test` on Linux/macOS. JUnit tests cover countdown calculation in the configured time zone (including daylight-saving transitions), threshold validation, and duration formatting. The full `build` task runs these tests automatically.

## Data and safety
Data is stored in `config/daily-playtime-rewards.json`. The file contains the time zone, thresholds, reward definitions, and player records. Before each successful replacement, the previous file is copied to `daily-playtime-rewards.json.bak`.

Playtime is counted on the server while a player is online. Daily progress and claims reset using the configured time zone. The server validates every claim and checks inventory space; a claim is persisted before inventory delivery to prevent duplicate claims. If the data JSON cannot be parsed, saving is blocked rather than overwriting it with empty data. Fix the JSON or restore the `.bak` file, then use `/playtimerewards reload`.

The server saves periodically, on disconnect, and on shutdown. Claim packets are rate-limited on the network thread, reward buttons debounce rapid clicks, and the server rechecks claim eligibility, item availability, stack size, and inventory capacity for every request. If an inventory insertion unexpectedly fails partway through, the mod restores the original main inventory before allowing a retry. Do not remove the data JSON while the server is running.
