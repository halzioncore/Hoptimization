# Hoptimization

Minecraft 1.8.9 Forge (11.15.1.2318) combat optimization mod template, built with Architectury Loom (`gg.essential.loom`), SpongePowered Mixin, and Forge Access Transformers.

## Environment & Toolchains

- **Gradle Wrapper**: Gradle 8.8 (`.\gradlew.bat`)
- **Gradle JVM**: Java 21 (or Java 17)
- **Mod Compile & Runtime Toolchain**: Java 8 (`JavaLanguageVersion.of(8)`)
- **Minecraft / Mappings / Forge**: `1.8.9` / `mcp_stable:22-1.8.9` / `net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9`

## Common Commands

```powershell
# Full build (output mod JAR: build/libs/hoptimization-0.1.0.jar)
.\gradlew.bat build

# Fast offline build using cached dependencies
.\gradlew.bat build --offline

# Launch Minecraft 1.8.9 dev client
.\gradlew.bat runClient

# Launch dev client with real Microsoft account authentication via DevAuth
.\gradlew.bat runClient -Ddevauth.enabled=true

# Generate/refresh decompiled Minecraft 1.8.9 & Forge sources
.\gradlew.bat genSources

# Generate IDE launch configurations
.\gradlew.bat genIntelliJRuns
.\gradlew.bat vscode
```

> Note: Distribute `build/libs/hoptimization-<version>.jar` only. Files under `build/intermediates/` are intermediate artifacts and will not load in standard Forge.

## Project Structure

- `src/main/java/com/hoptimization/HoptimizationMod.java` — Forge `@Mod` entrypoint (`modid = "hoptimization"`).
- `src/main/java/com/hoptimization/init/AutoDiscoveryMixinPlugin.java` — Automatically discovers and registers all top-level Mixin classes under `com.hoptimization.mixin`.
- `src/main/java/com/hoptimization/mixin/` — Place Mixin classes here (e.g. `MixinMinecraft.java`).
- `src/main/resources/mixins.hoptimization.json` — Mixin configuration (`JAVA_8`, refmap `mixins.hoptimization.refmap.json`).
- `src/main/resources/accesstransformer.cfg` — Optional Forge Access Transformer rules (use SRG names; rebuild with `--refresh-dependencies` after modifying).
- `src/main/resources/mcmod.info` — Forge mod metadata.
