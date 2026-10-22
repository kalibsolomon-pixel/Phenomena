# Development log

## Initial inventory — 2026-10-21
Workspace C:\Dev\Kwamster was empty, including hidden files. No Git repository, HEAD, tracked files, untracked files, or existing work. No ancestor AGENTS.md found. Initialized main locally. Installed Java 25 only; no global Gradle. Downloaded checksum-verified Temurin Java 21 into ignored .tools.

Dependencies verified against Fabric metadata: Minecraft 1.21.11, stable Loader 0.19.5, Fabric API 0.141.6+1.21.11, Loom 1.14.10. Gradle 9.2.1. Official Mojang mappings. See https://www.fabricmc.net/2025/12/05/12111.html and https://meta.fabricmc.net/v2/versions/loader/1.21.11 and https://maven.fabricmc.net/.

Architecture: immutable raw events -> bounded ordered correlator -> fight lifecycle -> incremental statistics. Minecraft state is sampled only on the client thread. Persistence receives immutable finalized snapshots on a bounded single writer. Rendering consumes cached summaries. No server mod or packet modifications.

## Phase 0
Gradle build and 3 foundation tests pass on Java 21. Custom K dashboard compiled using 1.21.11 renderContents API. Live launch pending. Configuration schema v1 preserves invalid originals. No previous schema exists to migrate.
