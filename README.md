# SMPBridge - one Paper plugin (.jar)

Connects your Paper server to the Discord bot. One jar, nothing else to install.

| Feature | What you get in Discord |
|---|---|
| Console | live console in `#console`, run commands from Discord |
| Chat | game chat in `#smp-chat`; Discord messages show in game in bright **cyan / red / light green** |
| Joins + staff commands | `#join-leave`, ban/kick/mute/warn commands in `#punishment-logs` |
| **AutoTotem detector** (built in) | `#alerts`: switch time vs the player's real ping, mean, deviation, TPS |
| **Death logs** | exact coordinates, biome (`Void` if they fell out), every lost item incl. shulker contents, `/smpbridge refund <id>` |
| **Backups** | the plugin streams the whole server as `.tar.gz` straight to Discord in numbered parts - nothing is stored |

## 1. Build the jar on GitHub (no tools, works from a phone)
1. Open your GitHub repo (or create one).
2. **Add file -> Upload files** -> upload `smp-bridge-plugins.zip` (same name, don't unzip). If the old one is there, upload over it.
3. Open `.github/workflows/build.yml` -> pencil icon -> **replace everything** with the new `build.yml` -> **Commit**.
4. **Actions** tab -> wait for the green tick -> open the run -> **Artifacts -> SMPBridge-plugin** -> unzip -> `SMPBridge-Paper.jar`.
   Red? Open the run, tap the failed step and send me its last 20 lines.

## 2. Install
Panel -> Files -> `plugins` -> Upload `SMPBridge-Paper.jar` (replace the old one) -> restart.
Then edit `plugins/SMPBridge/config.yml`: `bot-url`, `token` (from `/bridge info`), `id: "smp"` -> `smpbridge reload`.
New options appear automatically with safe defaults; to see them all, rename `config.yml` to `config-old.yml` and restart.

## Commands (console or permission `smpbridge.admin`)
`smpbridge status` · `reload` · `test` · `test death` · `alert <player> <check> [vl]` · `punish <ban|kick|mute|warn|unban|unmute> <player> [reason]` ·
`refund <deathId> [force]` · `totem <player>` (their last re-totem timings)

## AutoTotem detector - how it decides
After a totem pop the server measures `total` = pop -> new totem in the off hand. `total = ping + reaction`, so the plugin subtracts the player's
(lowest recent) ping and judges the **reaction**:
* **A - impossible reaction:** under 60 ms. Humans cannot do that.
* **B - robotic consistency:** the last 8 reactions have a standard deviation under 12 ms *and* a spread under 40 ms. Humans vary far more.
* **C - rapid chain:** 3 re-totems in 12 s, each under 100 ms.

Every alert shows `Switch` (the reaction), `total`, the player's `Ping`, mean, standard deviation and TPS. No checks while TPS < 18 or ping > 350.
The bot counts alerts per player and per anticheat and **auto-bans at 10 in 24 h** (`/configure set alert_limit`, `auto_ban`, `auto_ban_exempt`).
Tested on simulated players: off-tick fixed-delay hacks flagged ~99.9% of pops, fast human players flagged under 0.1%.
Limits (honest): a hack that waits a *random* 100-300 ms looks like a human to any timing check, and no server-side check can see the player's mouse.
Tune the numbers in `config.yml` (`autototem:`); give trusted players `smpbridge.bypass`.

## Death refunds
Every death with dropped items is saved exactly (enchants, names, shulker contents) under `plugins/SMPBridge/deaths/` (newest 300).
`smpbridge refund d-xxxxx` gives everything back; if the player is offline it is handed over when they join; a death can be refunded once (add `force` to repeat).

## Backups
Triggered by the bot. The plugin saves the worlds, pauses auto-save, streams everything under the server folder (symlinks and unreadable files are skipped and counted),
then turns auto-save back on. Needs no disk space and no panel permissions. Extra: `backup-exclude: [...]` if you ever want to skip folders.
Restoring: join the parts, stop the server, upload the `.tar.gz` in the panel -> **Unarchive** (steps are in the Discord embed).
