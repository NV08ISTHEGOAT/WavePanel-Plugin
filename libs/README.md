# libs/ - jars it is compiled against (not included)

WavePanel compiles against the APIs of three server programs. Their code is **not** inside WavePanel.jar, and the jars
aren't in this zip (they are other people's software, 43 MB). Get exactly the same files with:

```
java tools/FetchLibs.java
```

It downloads them from their official sites into this folder and checks each SHA-256:

| File | Official source | SHA-256 |
|---|---|---|
| `BungeeCord.jar` | BungeeCord build 2107 - https://hub.spigotmc.org/jenkins/job/BungeeCord/2107/artifact/bootstrap/target/BungeeCord.jar | `9f178e6b6db2b09fbc25407f872f30a007ee0a171622787407318662377e9b48` |
| `velocity.jar` | Velocity 3.5.1 build 615 - https://fill-data.papermc.io/v1/objects/b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3/velocity-3.5.1-615.jar | `b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3` |
| `brigadier-1.3.10.jar` | Mojang's Brigadier 1.3.10 - https://libraries.minecraft.net/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar | `c8ee4136e474ac7723ca2b432ec8d1a2bc88ef7d1ec57c314ba9e33cdc83dd75` |

The Bukkit API (`org.bukkit:bukkit:1.13.1-R0.1-SNAPSHOT`, from SpigotMC's Maven repository) and the build plugins
(Maven Central) are downloaded by Maven itself.
