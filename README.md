# Discord Voice Bridge

[![CI](https://github.com/gabrielbosse1/discord-voice-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/gabrielbosse1/discord-voice-bridge/actions/workflows/ci.yml)

Fabric mod for Minecraft 26.3 that bridges Discord voice chat with Simple Voice Chat (SVC). Runs server-side, no Discord commands, no message listeners.

## What it does

- On server start, launches a headless JDA bot from the config (`token`, `voiceChannelId`) and joins the configured Discord voice channel.
- Creates a persistent SVC group with the configured name, kept verbatim: spaces, symbols, emojis, Unicode, no trimming.
- Relays audio Discord to Minecraft: the Discord PCM is downmixed to mono, Opus-encoded with the SVC API, and sent through a `StaticAudioChannel` whose targets are exactly the current group members. It plays as full-volume, non-positional audio, anywhere in the world, in any dimension. No bot entity is spawned, tracked or followed, and nobody outside the group hears anything.
- Relays audio Minecraft to Discord: each group member's own mic is captured via `MicrophonePacketEvent`, decoded to PCM, and sent to the Discord voice channel. The event is only tapped, never cancelled, so normal group voice chat between members is unaffected.
- Configuration is done in-game with `/dvc config`, a clickable chat panel.

## Requirements

- Minecraft 26.3, Fabric Loader, Java 25
- Simple Voice Chat for 26.3 (2.6.27+26.3) on the server
- A Discord bot with the `guild_voice_states` intent (the bot account needs `voice_connect` and `voice_receive`)

The release jar nests the matching Fabric API dependency, so a separate Fabric API install is not required for this version. This release targets **Minecraft 26.3 only**; Minecraft, Fabric API, Java, and Simple Voice Chat APIs change between game versions, so a single jar cannot safely support every Minecraft version. Other versions need their own compatible port/build.

## Build

Java 25 is required. The Gradle wrapper is committed, so no Gradle install is needed:

```
./gradlew downloadSvc        # fetches the SVC jar into libs/ (see below)
./gradlew clean test build
```

Release artifact: `build/libs/discord_voice_bridge-1.0.0.jar`. JDA and its dependencies are relocated inside the jar so they do not collide with other mods.

### SVC jar

The `voicechat-fabric` jar is not redistributable with this project, so it is gitignored. `./gradlew downloadSvc` fetches the current 26.3 build from Modrinth into `libs/`. You can also place the jar there yourself if you prefer.

Once it is in `libs/`, dev runs work normally:

```
./gradlew runServer
```

Then connect a client to the server, join the Discord voice channel from another client, and talk.

## Configuration

`config/discord_voice_bridge/discord_voice_bridge.json`, created with defaults on first run. A `README.md` describing every option in the server's language is written next to it on first creation.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `token` | string | `""` | Discord bot token |
| `voiceChannelId` | string | `""` | Discord voice channel to join |
| `groupName` | string | `"Discord Voice Bridge"` | SVC group name, verbatim |
| `groupType` | string | `"NORMAL"` | `NORMAL` or `ISOLATED` |
| `groupPassword` | string | `""` | Password to join the group; empty = no password |
| `enabled` | boolean | `true` | Start the bridge on server boot |

Edit the file while the server runs and `/dvc config reload`, or just restart.

## Commands

`/dvc` (or `/dvc config`) is admin only (permission `discord_voice_bridge.admin` via fabric-permissions-api, or op level 4). It opens a clickable panel to toggle the bridge, reload the config, and check the status (group name, channel, connection state).

User-facing strings all go through translation keys. `pt_br.json` is the default, `en_us.json` the fallback.

## Testing

```
./gradlew test
```

`src/test/java/com/discordvoicebridge/audio/AudioPipelineTest.java` covers the PCM conversions, the JDA frame packing, and a mocked Opus encode/decode round trip. No Discord token needed.

### Local session testing

1. SVC 2.6.27+26.3 on both server and client.
2. `./gradlew runServer` in one terminal.
3. Start a client in another, connect to `localhost`.
4. Put a real token and channel ID in the config file, then `/dvc config reload`.
5. Join the Discord voice channel and speak. Audio should come out of SVC for the group members. Speaking in the SVC group in-game should reach Discord.

## Design notes

- Discord to Minecraft uses a `StaticAudioChannel` (`createStaticAudioChannel`) with per-member targets instead of a fake player: a player-anchored channel requires the client to have the entity tracked locally, so it is only audible near the bot. A static sound has no position at all, so it plays at full volume anywhere, in any dimension, with no entity to spawn, track or follow. Targets are kept in sync with group membership every tick, and `setBypassGroupIsolation(true)` is set because SVC's static channel would otherwise skip players sitting in an isolated group.
- SVC's `Group.Builder.setName()` runs the name through `convertGroupName`, which trims and truncates to 16 characters. `SvcBridge#setRawName` writes the raw name into the builder right before `build()`; that is the only reflection in the mod. Everything else stays on the public `de.maxhenkel.voicechat.api` surface, no access transformers.
- MC to Discord: the `MicrophonePacketEvent` (registered by the `voicechat` entrypoint `DvcVoicechatPlugin`) captures each bridge-group member's own microphone. The older `PlayerAudioListener` API only delivers audio a player *receives from others*, so it can never capture a speaker's own voice.

The mod only reads and writes public SVC API state, so it does not conflict with SVC addons like voice-chat-interaction, replay-voice-chat, enhanced-groups or voicechat-names.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build setup and review expectations, [GOALS.md](GOALS.md) for where the project is heading, and [AI_POLICY.md](AI_POLICY.md) for how AI-assisted work is handled. Code of conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

MIT
