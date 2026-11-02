# Contributing

Thanks for looking at this. The mod is small on purpose: it bridges Discord voice
chat with Simple Voice Chat on a dedicated Minecraft server and tries to stay out
of the way of other mods.

## Ground rules

- Server-side only. The mod runs on dedicated servers, `fabric.mod.json` says
  `environment: "server"`. Do not add client code or client entrypoints.
- English in code, config and comments. User-facing strings go through
  `Lang.get(key, args...)`. Every key must exist in both `pt_br.json` and
  `en_us.json`.
- No Discord commands or listeners. The JDA bot is headless audio, one intent
  (`guild_voice_states`). All configuration is the in-game `/dvc config` panel.
- Public `de.maxhenkel.voicechat.api` only. There is exactly one documented
  reflection escape (`SvcBridge.setRawName`, needed because the public group
  builder truncates names to 16 chars). Do not add more.
- No new dependencies without a good reason. JDA is already shaded into its own
  namespace. slf4j is provided by Fabric and must not be bundled.
- AI assistance: see [AI_POLICY.md](AI_POLICY.md) for what is expected of
  contributors.

## Building

Requirements: Java 25, and the Simple Voice Chat jar in `libs/` (it is not
redistributable, so it is gitignored). Gradle is handled by the wrapper, no
local install needed.

```
# once, fetches voicechat-fabric-2.6.27+26.3.jar from Modrinth into libs/
./gradlew downloadSvc        # Windows: gradlew.bat downloadSvc

# build + tests
./gradlew clean test build
```

The release jar is `build/libs/discord_voice_bridge-1.0.0.jar`.

Tests run on a plain JVM, no Discord token and no Minecraft server needed:

```
./gradlew test
```

## What CI does

`.github/workflows/ci.yml` runs on every push and PR: sets up Java 25, fetches
the SVC jar from Modrinth (same step `downloadSvc` does locally), then runs
`./gradlew clean test build` and uploads the release jar as an artifact. Keep it
green; if CI is red on your PR, the run log is the first place to look.

## Pull requests

Small, focused changes are easier to review than big ones. A good PR:

1. Explains what it changes and why, in the description.
2. Builds locally (`./gradlew clean test build`) with all tests passing.
3. Keeps the version coordinates in `build.gradle` and `fabric.mod.json` in
   sync when it touches them.
4. Follows the existing structure. This repo is a single `com.discordvoicebridge`
   package tree with mixins, config, audio, discord and svc folders. Put new
   code where similar code already lives.

If you bump the Minecraft, Fabric API or Simple Voice Chat version, expect to
re-verify class and method names against the new jars (they change between
versions) and make sure the release jar in `build/libs/` is a full jar, not an
empty stub (it should be around 18 MB with the shaded JDA tree inside).
