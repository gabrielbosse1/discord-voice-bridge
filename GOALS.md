# GOALS

Where this project is going, so a new contributor can tell what "done" means.

## The one-line goal

A low-maintenance voice bridge that a server owner can put on a production
Minecraft server, leave running, and trust. Not a platform. A tool that works.

## What done looks like

Concrete, checkable:

1. **One command to build.** A fresh clone builds and passes tests with
   `./gradlew downloadSvc && ./gradlew clean test build`. No local install of
   Gradle, no manual jar hunting, no IDE required. (Gradle wrapper is committed;
   the SVC jar is fetched by `downloadSvc` because it is not redistributable.)
2. **CI is the source of truth.** Every push and PR runs the same build +
   tests GitHub runs. A red build blocks merge, a green build means the jar in
   `build/libs/` is safe to ship.
3. **The jar works on a real 26.3 server.** Bot joins the configured Discord
   channel, the SVC group exists with the exact configured name, audio flows
   both directions, the bot stays out of TAB, and `/dvc config` toggles,
   reloads and reports state.
4. **It costs nothing to maintain.** Few files, few dependencies, no
   background services, no database. A contributor who has never seen the code
   can find the bug in ten minutes.
5. **It does not fight other mods.** Public SVC API only, JDA shaded into its
   own namespace, no access transformers, no packet handlers for its own
   protocol.

## Where it is now

Done:

- Full bridge: headless JDA bot, persistent SVC group with verbatim name,
  fake player with name and skin, bidirectional audio, TAB hiding, in-game
  admin panel, pt_br/en_us strings.
- Ported to Minecraft 26.3 (official mappings, Java 25, Loom 1.19).
- JUnit tests for the audio pipeline (PCM conversion, JDA frame packing,
  Opus round trip through a mock).
- CI, wrapper, contributing docs, AI policy, license.

Not done, in priority order:

- **Live 26.3 verification.** The build is green and every MC/SVC symbol is
  verified against the 26.3 jars, but the bridge has not been run on a real
  26.3 server yet with real Discord audio. That is the next milestone and the
  one that turns "compiles" into "done looks like #3".
- **Reconnect behavior.** The bot starts, stops and reloads; extended
  uptime behavior (Discord disconnects, SVC restart, long sessions) is not
  explicitly tested.
- **Docs for operators.** A short runbook (token setup, intents, common
  errors) for the person who installs this on a real server, not the person
  who builds it.

## Non-goals (things we will not build)

- Discord slash commands, message listeners, or any Discord presence beyond a
  voice channel.
- Client-side anything. This is a dedicated-server mod.
- Multi-loader support (Forge/NeoForge/Paper). Fabric only.
- A general plugin API for other mods. The SVC integration stays internal.
- Tracking every Minecraft patch day. The mod targets the current stable
  release (26.3 today); snapshot support is a no unless someone needs it.
