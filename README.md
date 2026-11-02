# Discord Voice Bridge

Fabric mod for Minecraft 1.21.1 that bridges Discord voice chat with Simple Voice Chat (SVC). Runs server-side, no Discord commands, no message listeners.

## What it does

- On server start, launches a headless JDA bot from the config (`token`, `voice_channel_id`) and joins the configured Discord voice channel.
- Creates a persistent SVC group with the configured name, kept verbatim: spaces, symbols, emojis, Unicode, no trimming.
- Relays audio Discord to Minecraft: the Discord PCM is downmixed to mono, Opus-encoded with the SVC API, and played through an `EntityAudioChannel` bound to a `FakePlayer`, so it sounds like a real player in the group. Display name and skin head come from the config.
- Relays audio Minecraft to Discord: mic packets from group members are decoded to PCM and sent to the Discord voice channel.
- The bot does not show up in the TAB player list: `PlayerManagerMixin` cancels the bot's UUID in the player list join path, and the fake player is only spawned as a world entity, never added to `PlayerManager.players`.
- Configuration is done in-game with `/dvc config`, a clickable chat panel.

## Requirements

- Minecraft 1.21.1, Fabric Loader, Java 21
- Simple Voice Chat 1.21.1 (2.6.27) on the server
- A Discord bot with the `guild_voice_states` intent (the bot account needs `voice_connect` and `voice_receive`)

## Build

Build with Gradle 8.x and a Java 21 JDK:

```
gradle clean build
```

Release artifact: `build/libs/discord_voice_bridge-1.0.0.jar`. JDA and its dependencies are relocated inside the jar so they do not collide with other mods.

### SVC jar for dev runs

The `voicechat-fabric` jar is not redistributable with this project, so dev runs resolve it from `libs/`:

1. Get `voicechat-fabric-1.21.1-2.6.27.jar` (Modrinth or CurseForge).
2. Put it in `libs/`.
3. Dev runs:

```
gradle runServer
gradle runClient
```

Then connect the client to `localhost`, join the Discord voice channel from another client, and talk.

## Configuration

`config/discord_voice_bridge/discord_voice_bridge.json`, created with defaults on first run.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `token` | string | `""` | Discord bot token |
| `voice_channel_id` | string | `""` | Discord voice channel to join |
| `group_name` | string | `"Discord Voice Bridge"` | SVC group name, verbatim |
| `bot_display_name` | string | `"Discord Bot"` | In-game name of the bot player |
| `bot_skin_url` | string | `""` | Skin URL for the bot player (Mojang signed URLs) |
| `group_type` | string | `"NORMAL"` | `NORMAL` or `ISOLATED` |
| `enabled` | boolean | `true` | Start the bridge on server boot |

Edit the file while the server runs and `/dvc config reload`, or just restart.

## Commands

`/dvc config` is admin only (permission `discord_voice_bridge.admin` via fabric-permissions-api, or op level 4). It opens a clickable panel to toggle the bridge, reload the config, and check the status (group name, bot name, channel, connection state).

User-facing strings all go through translation keys. `pt_br.json` is the default, `en_us.json` the fallback.

## Testing

```
gradle test
```

`src/test/java/com/discordvoicebridge/audio/AudioPipelineTest.java` covers the PCM conversions, the JDA frame packing, and a mocked Opus encode/decode round trip. No Discord token needed.

### Local session testing

1. SVC 1.21.1-2.6.27 on both server and client.
2. `gradle runServer` in one terminal.
3. `gradle runClient` in another, connect to `localhost`.
4. Put a real token and channel ID in the config file, then `/dvc config reload`.
5. Join the Discord voice channel and speak. Audio should come out of SVC from the bot player. Speaking in the SVC group in-game should reach Discord.

## Design notes

- Discord to Minecraft uses an `EntityAudioChannel` on the fake player instead of `AudioSender.send()`: `AudioSender` only routes audio for players actually connected to SVC voice, so a bot UUID gets dropped. The entity channel gives the same "real player" result with positional audio, using only public API.
- SVC's `Group.Builder.setName()` runs the name through `convertGroupName`, which trims and truncates to 16 characters. `SvcBridge#setRawName` writes the raw name into the builder right before `build()`; that is the only reflection in the mod. Everything else stays on the public `de.maxhenkel.voicechat.api` surface, no access transformers.
- TAB hiding: `PlayerManagerMixin` injects at `PlayerManager.onPlayerConnect` (the join path that builds the `ADD_PLAYER` player list packet on 1.21.1) and cancels it for the bot UUID. The fake player is also never registered in `PlayerManager.players`, so this is covered twice.
- MC to Discord: one `PlayerAudioListener` per player in the group, refreshed every tick. `MicrophonePacket` has no sender accessor in the public API, so the listener is registered per UUID and the group membership is checked inside the callback.

The mod only reads and writes public SVC API state, so it does not conflict with SVC addons like voice-chat-interaction, replay-voice-chat, enhanced-groups or voicechat-names.

## License

MIT
