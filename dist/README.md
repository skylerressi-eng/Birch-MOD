# Downloads

Built jars, committed so there is a direct download link.

One per Minecraft version — they are not interchangeable. Fabric reads the
target out of the jar and refuses to load one built for a different version,
so picking the wrong file fails at startup rather than misbehaving quietly.

| Minecraft | Jar | Fabric Loader |
|---|---|---|
| **26.1.2** | `birchoptimizer-1.20.0.jar` | 0.19.3+ |
| **26.2**   | `birchoptimizer-2.1.0.jar`  | 0.19.5+ |

Both need Fabric API and Java 25. Drop the jar in your `mods/` folder.

Both jars are cut from the same source tree at the same point, so they have
the same features and the same fixes. They differ only where Minecraft renamed
things between the two versions: the screen and HUD calls, and how geometry is
handed to the renderer. The 26.2 form of those files is kept on the
`mc26.2-line` tag.
