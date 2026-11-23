# Player Log

Server-side Fabric mod that logs what players do: containers, joins, deaths, kills, chat, signs and more, with in-game search.

## Features

- Container access and item changes, with `/log chest` inspection.
- Joins with the client mod list, shared addresses (alt detection).
- Deaths, kills, PvP hits, fire and explosives.
- Chat, signs, books and anvil renames.
- Position tracks and return-to-place helpers.

## Commands

Everything lives under the `/log` command (`chest`, `player`, `joins`, `deaths`, `events`, `items`, `mods`, `alts`, `inspect`, `return`, `reload`).

## Compatibility

Minecraft 1.21.1, Fabric Loader and Fabric API. Runs on the server; players need no client mod.

## Build

```bash
./gradlew build
```

The jar appears in `build/libs/`.

## License

All Rights Reserved.
