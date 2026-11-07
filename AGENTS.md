# AGENTS.md - Discord Voice Bridge

Notes for working in this repo. Minecraft 26.3, Fabric, Java 25. Mod id: `discord_voice_bridge`.

## Rules

1. Server-side only. All runtime code lives in `com.discordvoicebridge`, `fabric.mod.json` is `environment: "server"`, the entrypoint is a `DedicatedServerModInitializer`.
2. English in code, config and comments. User-facing strings go through `Lang.get(key, args...)`; `pt_br.json` is the default, `en_us.json` the fallback. Every key must exist in both files.
3. No Discord slash commands or listeners. The JDA bot is headless audio, `GUILD_VOICE_STATES` intent only. Admin config is the in-game `/dvc config` panel, gated by `discord_voice_bridge.admin` (fabric-permissions-api).
4. Public `de.maxhenkel.voicechat.api` only. The one exception is `SvcBridge.setRawName`, which pokes at the group builder because the public API truncates names to 16 chars. Do not add more reflection on SVC.
5. No new dependencies without a good reason. JDA (plus the jdave/libdave bindings) is shadowed into `discordvoicebridge.jda` / `discordvoicebridge.shaded.*` so its Jackson/OkHttp/Okio/Kotlin/Tink do not collide with other mods. slf4j stays out (Fabric provides it), fabric-permissions-api ships nested via `include`.

## Why 26.3, and why there is no yarn

Mojang moved to year-based versioning (last 1.x was 1.21.11, then 26.1, 26.2, 26.3). Starting with the 26.x line the server jars ship with Mojang's official names, and the Fabric ecosystem followed: yarn and intermediary stopped at 1.21.11, loom 1.19 uses the official mappings directly (no `mappings` line), and everything ships with `Fabric-Mapping-Namespace: official`. So this repo compiles against Mojang names (`ServerPlayer`, `ServerLevel`, `PlayerList`, `Component`, ...) and there is no yarn coordinate anywhere.

## Classes

- `DiscordVoiceBridgeMod` - `DedicatedServerModInitializer`. Registers the command callback and `SERVER_STARTED`/`SERVER_STOPPING` lifecycle events. Holds config, bot and SVC plugin singletons; `applyConfig`/`toggleEnabled`/`reloadConfig` wire the admin panel to the live connection.
- `DiscordBotManager` - JDA 6.7.0 with the DAVE session factory from jdave (Discord now enforces E2EE/DAVE encrypted voice; without it the voice server closes every audio session with "E2EE/DAVE protocol required"). `start()` is non-blocking (JDA has no ready callback, only blocking `awaitReady()`), the wait and channel connect run on a daemon thread. `playToDiscord(short[])` is the MC to Discord entry point. A `LegacyAudioPassthrough` (config `legacyUserIds`) adaptively switches the native per-user DAVE pass-through flag (5 s quiet → ON for plaintext senders; 3 s of undecodable packets → OFF once Discord migrates the sender to E2EE) so both encrypted and legacy senders stay audible; it taps the receive handler (`handleEncodedAudio`/`handleUserAudio`) for the observation.
- `SvcBridge` - persistent group, Discord to MC audio via a `StaticAudioChannel` whose targets are exactly the current bridge-group members (synced every tick in `refreshPlayers`; `setBypassGroupIsolation(true)` so isolated-group members still receive it), `MicrophonePacketEvent` (via `DvcVoicechatPlugin`, a `VoicechatPlugin` entrypoint) for MC to Discord audio. No bot entity exists.
- `SvcVoicechatPlugin` - interface so the SVC side only loads when SVC is present (loaded reflectively, so the mod never hard-depends on SVC classes).
- `ConfigCommand` - `/dvc config` panel (record-based `ClickEvent.RunCommand` / `HoverEvent.ShowText` on 26.3), permission checked with `Permissions.require`.
- `AudioPipeline` - stereo/mono conversion, JDA frame packing, bounded send queue.
- `Lang` - `{0}`-style key resolution, pt_br first, en_us fallback.
- `DiscordVoiceBridgeConfig` - thread-safe JSON config (read/write lock).

## Why things are the way they are

- Discord to MC audio uses a `StaticAudioChannel` (a plain `GroupSoundPacket` to explicit targets) instead of an `EntityAudioChannel` on a fake player: the SVC client anchors player-sound audio on the tracked entity via `ClientLevel.getPlayerByUUID()` and **returns silence if the entity is not in the local level** — the server only sends entities within the viewer's render distance, so an entity-based channel is only audible near the bot. A static sound has no position, so it plays at full volume anywhere; targets = current bridge-group members (synced in `refreshPlayers`) keep it invisible to everyone else, and `setBypassGroupIsolation(true)` is required because `StaticAudioChannelImpl.broadcast` skips targets sitting in an isolated group. There is no bot entity, so nothing to track, follow, hide or mixin.
- MC to Discord uses the `MicrophonePacketEvent` (fired per mic packet with the sender, before group routing) because `PlayerAudioListener` only delivers audio a player *receives from others* (as Static/Entity sound, never their own `MicrophonePacket`), so it can never capture a speaker's own voice (a solo member would be dead silent).
- Lifecycle uses `ServerLifecycleEvents` instead of mixin injection into `MinecraftServer`: with loom 1.19 the `DedicatedServerModInitializer` entrypoint compiles normally, so the old `MinecraftServerMixin` was deleted when porting to 26.3.
- 26.3 specifics that will bite when editing: `GameProfile` is an immutable record (skin goes in via a third `PropertyMap` constructor arg), `ChatFormatting` lives in `net.minecraft.ChatFormatting`, `ChatEvent`/`HoverEvent` are interfaces with record implementations, `Level` no longer has `spawnEntity` (use `ServerLevel.addFreshEntity`), and the respawn point is `getLevelData().getRespawnData().pos()`.

## Build notes

- loom 1.19.3 (plugin id `net.fabricmc.fabric-loom`), Gradle 9.8.2 (wrapper committed), Java 25. No mappings line: 26.3 jars are pre-named with Mojang's official mappings.
- Versions: JDA 6.7.0, jdave (libdave) 0.1.8 (api + all 4 platform natives), SVC api 2.6.27, SVC fabric jar 2.6.27+26.3, fabric-api 0.165.0+26.3 (nested in the release jar so the `fabric` mod dependency is present), fabric-loader 0.20.1, fabric-permissions-api 0.7.0, sponge-mixin 0.17.5+mixin.0.8.7 (compileOnly, annotations only; must never end up in the jar).
- This artifact targets Minecraft 26.3 only. Cross-version support requires separate compatibility ports/builds because Minecraft, Fabric API, and SVC APIs and Java requirements vary by game version.
- Build:

```
$env:JAVA_HOME="<path to a JDK 25>"
.\gradlew.bat clean test build
```

On Linux/macOS: `./gradlew clean test build`.

- The `downloadSvc` task fetches `libs/voicechat-fabric-2.6.27+26.3.jar` from Modrinth. That jar is not redistributable and is gitignored; CI does the same fetch in `.github/workflows/ci.yml`.
- The release jar must be ~31 MB (shaded JDA 6.7.0 tree incl. Kotlin runtime/Tink + the 4 platform `libdave` natives + nested permissions jar). If it comes back tiny, something broke in the jar/remap wiring: check the `jar` task (folds `shadowJar` output) and the manifest `Fabric-Mapping-Namespace` (must be `official`).
- jdave relocation: `club.minnced.discord` → `discordvoicebridge.shaded.jdave` is declared **before** `club.minnced` → `discordvoicebridge.shaded.opusjava` (order matters: the specific prefix must win). The platform natives load from an absolute classpath path (`/natives/{os}/{arch}/dave.{ext}`) via `NativeLibraryLoader.class.getResourceAsStream`, so the relocation does not break them. `LegacyAudioPassthrough` references jdave classes only by type (`DaveSessionManager`, `DaveDecryptor`) and finds private fields by type, never by name, so the relocation cannot break it either.
- jdave loads `dave.dll` through the Java FFM API (`SymbolLookup.libraryLookup`), a restricted method: the server launch should pass `--enable-native-access=ALL-UNNAMED` (see `start-server.bat`) so it loads without the restricted-method warning.
- Mixin list lives in `discord_voice_bridge.mixins.json`; keep it in sync with `fabric.mod.json` and the classes. `compatibilityLevel` must be `JAVA_25`.
- When bumping Minecraft/SVC versions: verify every MC and SVC symbol against the new jars (javap), re-run `clean test build`, and re-check the release jar size.

## Paths

- Config at runtime: `config/discord_voice_bridge/discord_voice_bridge.json`
- Release jar: `build/libs/discord_voice_bridge-1.0.0.jar`
