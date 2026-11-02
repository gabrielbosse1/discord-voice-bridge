# AGENTS.md - Discord Voice Bridge

Notes for working in this repo. Minecraft 26.3, Fabric, Java 25. Mod id: `discord_voice_bridge`.

## Rules

1. Server-side only. All runtime code lives in `com.discordvoicebridge`, `fabric.mod.json` is `environment: "server"`, the entrypoint is a `DedicatedServerModInitializer`.
2. English in code, config and comments. User-facing strings go through `Lang.get(key, args...)`; `pt_br.json` is the default, `en_us.json` the fallback. Every key must exist in both files.
3. No Discord slash commands or listeners. The JDA bot is headless audio, `GUILD_VOICE_STATES` intent only. Admin config is the in-game `/dvc config` panel, gated by `discord_voice_bridge.admin` (fabric-permissions-api).
4. Public `de.maxhenkel.voicechat.api` only. The one exception is `SvcBridge.setRawName`, which pokes at the group builder because the public API truncates names to 16 chars. Do not add more reflection on SVC.
5. No new dependencies without a good reason. JDA is shadowed into `discordvoicebridge.jda` / `discordvoicebridge.shaded.*` so its Jackson/OkHttp/Okio do not collide with other mods. slf4j stays out (Fabric provides it), fabric-permissions-api ships nested via `include`.

## Why 26.3, and why there is no yarn

Mojang moved to year-based versioning (last 1.x was 1.21.11, then 26.1, 26.2, 26.3). Starting with the 26.x line the server jars ship with Mojang's official names, and the Fabric ecosystem followed: yarn and intermediary stopped at 1.21.11, loom 1.19 uses the official mappings directly (no `mappings` line), and everything ships with `Fabric-Mapping-Namespace: official`. So this repo compiles against Mojang names (`ServerPlayer`, `ServerLevel`, `PlayerList`, `Component`, ...) and there is no yarn coordinate anywhere.

## Classes

- `DiscordVoiceBridgeMod` - `DedicatedServerModInitializer`. Registers the command callback and `SERVER_STARTED`/`SERVER_STOPPING` lifecycle events. Holds config, bot and SVC plugin singletons; `applyConfig`/`toggleEnabled`/`reloadConfig` wire the admin panel to the live connection.
- `DiscordBotManager` - JDA 5.5.1. `start()` is non-blocking (JDA has no ready callback, only blocking `awaitReady()`), the wait and channel connect run on a daemon thread. `playToDiscord(short[])` is the MC to Discord entry point.
- `SvcBridge` - persistent group, fake player with name + skin, `EntityAudioChannel` for Discord to MC audio, one `PlayerAudioListener` per in-group player for MC to Discord audio.
- `SvcVoicechatPlugin` - interface so the SVC side only loads when SVC is present (loaded reflectively, so the mod never hard-depends on SVC classes).
- `PlayerListMixin` - TAB hiding: cancels `placeNewPlayer` for the bot UUID so it can never reach the player info packet.
- `ConfigCommand` - `/dvc config` panel (record-based `ClickEvent.RunCommand` / `HoverEvent.ShowText` on 26.3), permission checked with `Permissions.require`.
- `AudioPipeline` - stereo/mono conversion, JDA frame packing, bounded send queue.
- `Lang` - `{0}`-style key resolution, pt_br first, en_us fallback.
- `DiscordVoiceBridgeConfig` - thread-safe JSON config (read/write lock).

## Why things are the way they are

- `AudioSender` instead of the spec's direct send: it only routes for players connected to SVC voice, a bot UUID is dropped. `EntityAudioChannel` on the fake player is the public-API equivalent.
- `PlayerAudioListener` per player: `MicrophonePacket` has no sender in the public API.
- The TAB mixin targets `PlayerList.placeNewPlayer` because that is where the 26.3 join path builds the `ClientboundPlayerInfoUpdatePacket` ADD_PLAYER entry.
- Lifecycle uses `ServerLifecycleEvents` instead of mixin injection into `MinecraftServer`: with loom 1.19 the `DedicatedServerModInitializer` entrypoint compiles normally, so the old `MinecraftServerMixin` was deleted when porting to 26.3.
- 26.3 specifics that will bite when editing: `GameProfile` is an immutable record (skin goes in via a third `PropertyMap` constructor arg), `ChatFormatting` lives in `net.minecraft.ChatFormatting`, `ChatEvent`/`HoverEvent` are interfaces with record implementations, `Level` no longer has `spawnEntity` (use `ServerLevel.addFreshEntity`), and the respawn point is `getLevelData().getRespawnData().pos()`.

## Build notes

- loom 1.19.3 (plugin id `net.fabricmc.fabric-loom`), Gradle 9.8.2 (wrapper committed), Java 25. No mappings line: 26.3 jars are pre-named with Mojang's official mappings.
- Versions: JDA 5.5.1, SVC api 2.6.27, SVC fabric jar 2.6.27+26.3, fabric-api 0.165.0+26.3 (nested in the release jar so the `fabric` mod dependency is present), fabric-loader 0.20.1, fabric-permissions-api 0.7.0, sponge-mixin 0.17.5+mixin.0.8.7 (compileOnly, annotations only; must never end up in the jar).
- This artifact targets Minecraft 26.3 only. Cross-version support requires separate compatibility ports/builds because Minecraft, Fabric API, and SVC APIs and Java requirements vary by game version.
- Build:

```
$env:JAVA_HOME="<path to a JDK 25>"
.\gradlew.bat clean test build
```

On Linux/macOS: `./gradlew clean test build`.

- The `downloadSvc` task fetches `libs/voicechat-fabric-2.6.27+26.3.jar` from Modrinth. That jar is not redistributable and is gitignored; CI does the same fetch in `.github/workflows/ci.yml`.
- The release jar must be ~18 MB (shaded JDA tree + nested permissions jar). If it comes back tiny, something broke in the jar/remap wiring: check the `jar` task (folds `shadowJar` output) and the manifest `Fabric-Mapping-Namespace` (must be `official`).
- Mixin list lives in `discord_voice_bridge.mixins.json`; keep it in sync with `fabric.mod.json` and the classes. `compatibilityLevel` must be `JAVA_25`.
- When bumping Minecraft/SVC versions: verify every MC and SVC symbol against the new jars (javap), re-run `clean test build`, and re-check the release jar size.

## Paths

- Config at runtime: `config/discord_voice_bridge/discord_voice_bridge.json`
- Release jar: `build/libs/discord_voice_bridge-1.0.0.jar`
