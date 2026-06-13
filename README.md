## Commands & Permissions

All commands require the `bacappoints.admin` permission (or server OP status) to execute.

| Command | Description |
| :--- | :--- |
| `/bacap` | Shows plugin debugging status, configuration details, and your personal point breakdown. |
| `/bacap reload` | Reloads `config.yml`, `advancements.yml`, and `points.yml` on-the-fly and scans player progress. |
| `/bacap recalculate all` | Forces a complete scan of all online and offline players' progress from disk and updates the leaderboard. |
| `/bacap recalculate <player>` | Forces a recalculation of a specific online player's points. |
| `/bacap set <player> <points>` | Set a player's points to a specific value (automatically adjusts bonus differences). |
| `/bacap add <player> <points>` | Add points to a player's score. |

---

## Configuration

### `config.yml`
```yaml
# Which namespaces should be counted for the points calculation?
# Default is 'blazeandcave' for the BlazeandCaves Advancements Pack.
namespaces:
  - "blazeandcave"

# Specific points overrides for individual advancements.
# Format: "namespace:category/name": points
overrides:
  "blazeandcave:bacap/root": 0

# Should the custom scoreboard sidebar be enabled?
enable-sidebar: true

# Should the scoreboard be shown as a global leaderboard (top players list)?
sidebar-leaderboard: true

# How many players should be displayed in the leaderboard? (Default is 10)
leaderboard-size: 10

# Title of the scoreboard in the sidebar (supports color codes with &)
sidebar-title: "&6&l★ BAC LEADERBOARD ★"
```

### `advancements.yml`
This file is generated automatically in your plugin folder upon first launch. It maps every single one of the 1,197 BlazeandCaves advancements directly to its exact XP value (e.g., `"blazeandcave:adventure/sponge_miner": 50`). You can edit this file directly to customize any advancement's point value!


