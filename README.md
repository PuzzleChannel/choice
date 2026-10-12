# Daily Playtime Rewards

Fabric mod for Minecraft 26.2. Tracks daily playtime and offers four server-authoritative rewards.

## Requirements
- Java 25
- Gradle Wrapper (included; Java 25 is required)
- Minecraft 26.2 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.2

## Default rewards
1. 30 minutes — 12 bread
2. 1 hour — water bucket
3. 2 hours — golden apple
4. 4 hours — `vmtrade:vmutcoin` (requires VMTrade)

## Build
On Windows, run `gradlew.bat build` from the project root. On Linux/macOS, run `./gradlew build`. Java 25 is required. The first build downloads the configured Gradle distribution and dependencies. GitHub Actions also builds the project on pushes and pull requests.

## Notes
The server is authoritative for playtime, reward claims, and persistence. Daily reset uses the server's configured system time zone. Reward claims are recorded before inventory delivery to prevent duplicate claims; delivery is rolled back if the item cannot be inserted. Operators hold the desired item in their main hand and use `/playtimerewards set <1-4> <count>`. This changes only the reward definition; existing player timers and claim records remain intact. `/playtimerewards save` forces a data save, and `/playtimerewards reload` reloads the saved data.
