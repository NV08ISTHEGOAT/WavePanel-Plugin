# WavePanel.jar 3.3 - source code

The complete source code of **WavePanel.jar 3.3**, the plugin / mod that connects a Minecraft server to
[WavePanel](https://wavepanel.xyz). Nothing is left out: every file inside the jar is built from this folder.

| Official jar | https://wavepanel.xyz/assets/downloads/WavePanel.jar |
|---|---|
| Same file on GitHub | [Release v3.3](https://github.com/NV08ISTHEGOAT/WavePanel-Plugin/releases/tag/v3.3) |
| Size | 174,611 bytes |
| SHA-256 | `0e2d9588bd98541e134a5bc26b15f723e146ddf0ce0231e9a8007eed0816f6f9` |

## What's inside the jar

Only WavePanel's own code - 51 class files under `me/nv08/wavepanel/`, not obfuscated - plus the small descriptor
file each kind of server looks for (`plugin.yml`, `bungee.yml`, `velocity-plugin.json`, `fabric.mod.json`,
`META-INF/mods.toml`, `META-INF/neoforge.mods.toml`). No third-party libraries are packed in.

| Folder | What it is |
|---|---|
| `core/` | Everything that isn't a game API: config, `/wavepanel connect`, the connection to WavePanel, files / plugins / backups, the version installer, Split (Beta) and its host mode. Java 8, no dependencies. |
| `bukkit/` | Paper, Spigot, Purpur, Folia and their forks |
| `bungee/` | BungeeCord, Waterfall |
| `velocity/` | Velocity |
| `mod/` | Fabric, Quilt, Forge, NeoForge - reads Minecraft through reflection (`Bridge.java`), so there's no Minecraft dependency |
| `agent/` | `java -javaagent:WavePanel.jar -jar server.jar` for vanilla and anything else |
| `stubs/` | The mod loaders' entry-point types, only to compile against - not in the jar |
| `dist/` | Packs it all into the one `WavePanel.jar` (`dist/pom.xml`) |

## Build it yourself and compare

You need **JDK 25** and **Maven 3.9**. The official jar was built with Eclipse Temurin 25.0.3+9 and Maven 3.9.16; another
JDK version builds it too, but its compiler can produce slightly different class files.

```
java tools/FetchLibs.java      # the 3 jars it compiles against, from their official sites, SHA-256 checked (libs/README.md)
mvn -B package                 # builds dist/target/WavePanel.jar
java tools/CompareJars.java dist/target/WavePanel.jar WavePanel.jar
```

`CompareJars` checks every file **inside** the two jars and should end with `0 different`. The SHA-256 of the jar files
themselves won't match: a jar is a zip, and a zip stores the time each file was packed, so every build gets a new
checksum while everything inside stays identical. You can also open the official jar in any Java decompiler and read it
next to this source.

## What it does - and what it doesn't

**Where it connects.** Only to the WavePanel it's connected to:

- `/wavepanel connect WP-XXXX-XXXX` sends the code to `panel:` in its config (default `https://wavepanel.xyz`) and gets
  back this server's link address + secret (`core/.../Core.java`).
- Every 2 seconds it pushes the server's numbers and the new console lines to that address and gets back the console
  commands to run (`RemoteLink.java`). File / plugin / backup jobs come over the same address (`JobLink.java`).
- Normal HTTPS certificate checks. Only for a WavePanel with a self-signed certificate (`remote.cert-sha256` in the
  config) it accepts exactly that one certificate, by its fingerprint.
- The version installer - only when someone uses the Version tab in WavePanel - downloads a server jar over HTTPS from a
  fixed list of official sites (PaperMC, PurpurMC, FabricMC, Mojang) and checks its checksum before using it
  (`Installer.java`, `HOSTS`).
- Split (Beta) - only when the server's owner splits it in the Version tab - downloads the same way: Velocity and the new
  parts' server jars from that list, and for Fabric the FabricProxy-Lite and Fabric API mods from Modrinth
  (`cdn.modrinth.com`), each checked against its SHA-256 / SHA-512 before it's used (`Split.java`, `HOSTS`).
- That's all: no telemetry, no analytics, no update check, and it never downloads code for itself (a split's new parts
  get a copy of this same jar, not a download).
- It opens no port. Only `local: true` (off by default, for a WavePanel running on the same machine) serves read-only
  stats on 127.0.0.1.
- Vanilla (`-javaagent`): it pings its own server for the player list and, if RCON is on, sends commands to 127.0.0.1
  with the password from that server's `server.properties` (`agent/`). `Agent.java` loads WavePanel's own classes a
  second time next to the game's (a child-first class loader over WavePanel.jar itself), so the mod code can see
  Minecraft's classes.

**What it sends:** the server software and version, TPS / MSPT, players online and the player list (name, UUID, world,
position, game mode, ping, health, food, op or not), worlds (players, entities, loaded chunks), the machine's CPU / RAM /
disk use, core count, CPU model and OS, the server's own memory, CPU and threads, the memory limit of its container (the
cgroup limit, so a split can share it out), the Java version - and the new console
lines, exactly as the console shows them (on most servers that includes the IP address of players joining, just like
`logs/latest.log`).

**What it can do on the server** - the point of a control panel, and only for people you give access in WavePanel:

- run console commands;
- with `remote.allow-files: true` (the default): the file manager, the plugins / mods page, backups and the version
  installer. All of it is locked to the server's own folder - every path is checked on its real location, so `../` and
  symlinks can't get out (`JobLink.java`). `allow-files: false` switches all of that off: stats, console and players only.
- The programs it starts: on Windows, after the version installer or a split has run, a small helper
  (`java -cp WavePanel.jar me.nv08.wavepanel.core.InstallFinisher`) that swaps the server jar once the server has
  stopped - Windows can't replace a jar that is still running. And only on a server that was split: there the host's
  start command runs WavePanel.jar itself (`java -jar`, host mode - `Supervisor.java`), which starts the parts (Java,
  each in its own folder inside the server's folder) and a Velocity proxy on the server's port, passes the console
  through, and starts a part again when it crashes. Undo split in WavePanel puts the original server jar back.
  `java -jar WavePanel.jar` on a server that isn't split only prints a hint and exits.

## License

Copyright (c) 2026 NV08. Licensed under
[Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International (CC BY-NC-ND 4.0)](https://creativecommons.org/licenses/by-nc-nd/4.0/):

- **Share** - you may copy and pass it on, unchanged, in any medium or format;
- **Attribution** - credit NV08 and link to the license;
- **NonCommercial** - not for commercial purposes;
- **NoDerivatives** - you may not share modified versions of it.

Full legal text in [LICENSE](LICENSE).
