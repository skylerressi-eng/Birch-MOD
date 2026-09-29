# Downloads

Built jars, committed so there is a direct download link.

One per Minecraft version — they are not interchangeable. Fabric reads the
target out of the jar and refuses to load one built for a different version,
so picking the wrong file fails at startup rather than misbehaving quietly.

| Minecraft | Jar | Fabric Loader |
|---|---|---|
| **26.2**   | `birchoptimizer-2.1.0.jar`  | 0.19.5+ |
| **26.1.2** | `birchoptimizer-1.19.1.jar` | 0.19.3+ |

Both need Fabric API and Java 25. Drop the jar in your `mods/` folder.

26.1.2 is frozen at 1.19.1 — development continues on 26.2.
