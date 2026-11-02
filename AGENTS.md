# AGENTS.md - Discord Voice Bridge

Notes for working in this repo. Minecraft 1.21.1, Fabric, Java 21. Mod id: `discord_voice_bridge`.

## Rules

1. Server-side only. All runtime code lives in `com.discordvoicebridge`, `fabric.mod.json` is `environment: "server"`.
2. English in code, config and comments. User-facing strings go through `Lang.get(key, args...)`; `pt_br.json` is the default, `en_us.json` the fallback. Every key must exist in both files.
3. No Discord slash commands or listeners. The JDA bot is headless audio, `GUILD_VOICE_STATES` intent only. Admin config is the in-game `/dvc config` panel, gated by `discord_voice_bridge.admin` (fabric-permissions-api).
4. Public `de.maxhenkel.voicechat.api` only. The one exception is `SvcBridge.setRawName`, which pokes at the group builder because the public API truncates names to 16 chars. Do not add more reflection on SVC.
5. No new dependencies without a good reason. JDA is shadowed into `discordvoicebridge.shaded.*` so its Jackson/OkHttp/Okio do not collide with other mods. slf4j stays out (Fabric provides it), fabric-permissions-api ships nested via `include`.

## Classes

- `DiscordVoiceBridgeMod` - plain class (no initializer interface available in this setup); lifecycle is driven from `MinecraftServerMixin`. Holds config, bot and SVC plugin singletons; `applyConfig`/`toggleEnabled`/`reloadConfig` wire the admin panel to the live connection.
- `DiscordBotManager` - JDA 5.5.1. `start()` is non-blocking (JDA has no ready callback, only blocking `awaitReady()`), the wait and channel connect run on a daemon thread. `playToDiscord(short[])` is the MC to Discord entry point.
- `SvcBridge` - persistent group, fake player with name + skin, `EntityAudioChannel` for Discord to MC audio, one `PlayerAudioListener` per in-group player for MC to Discord audio.
- `SvcVoicechatPlugin` - interface so the SVC side only loads when SVC is present.
- `MinecraftServerMixin` - lifecycle: `runServer` registers the command, `runTask` TAIL does one-time init on the first tick (dedicated only), `stop(Z)`/`shutdown` tear down.
- `PlayerManagerMixin` - TAB hiding: cancels `onPlayerConnect` for the bot UUID so it can never be in the player list packet.
- `ConfigCommand` - `/dvc config` panel (ClickEvent/HoverEvent), permission checked with `Permissions.require`.
- `AudioPipeline` - stereo/mono conversion, JDA frame packing, bounded send queue.
- `Lang` - `{0}`-style key resolution, pt_br first, en_us fallback.
- `DiscordVoiceBridgeConfig` - thread-safe JSON config (read/write lock).

## Why things are the way they are

- `AudioSender` instead of the spec's direct send: it only routes for players connected to SVC voice, a bot UUID is dropped. `EntityAudioChannel` on the fake player is the public-API equivalent.
- `PlayerAudioListener` per player: `MicrophonePacket` has no sender in the public API.
- The TAB mixin targets `PlayerManager.onPlayerConnect` because that is where the 1.21.1 join path builds the `ADD_PLAYER` packet; the older `sendPlayerList` does not exist in this yarn version.
- `net.fabricmc.api.*` (ModInitializer etc.) does not compile in this toolchain, so lifecycle runs through the `MinecraftServerMixin` instead of an initializer entrypoint. `fabric.mod.json` has empty `entrypoints`.

## Build notes

- loom 1.8-SNAPSHOT (1.8.13) on Gradle 8.14.3, yarn 1.21.1+build.1 v2, JDA 5.5.1, SVC api 2.6.27, fabric-permissions-api 0.3.1.
- There is no gradle wrapper here. Use the system gradle 8.14.3 with a Java 21 `JAVA_HOME`, e.g.:

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\<hash>\gradle-8.14.3\bin\gradle.bat" clean test build --no-daemon
```

- `sponge-mixin` is compileOnly (annotations only; loom does not inject it) and must never end up in the jar.
- SVC jar for dev runs: `libs/voicechat-fabric-1.21.1-2.6.27.jar` (not redistributable, must be provided).
- The `remapJar` block in `build.gradle` reorders loom's task actions. loom 1.8.13 on Gradle 8.14 runs the inherited Jar archive action after the remap worker and overwrites the release jar with an empty spec (result: a ~260 byte jar). Do not delete that block; if you upgrade loom, check the release jar size and the mixin refmap.
- Mixin list lives in `discord_voice_bridge.mixins.json`; keep it in sync with `fabric.mod.json` and the classes.

## Paths

- Config at runtime: `config/discord_voice_bridge/discord_voice_bridge.json`
- Release jar: `build/libs/discord_voice_bridge-1.0.0.jar`
