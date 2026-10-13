# Downloads

Built jars, committed so there is a direct download link.

| Minecraft | Jar | Fabric Loader |
|---|---|---|
| **26.2**   | `birchoptimizer-2.2.0+26.2.jar`   | 0.19.5+ |
| **26.1.2** | `birchoptimizer-2.2.0+26.1.2.jar` | 0.19.3+ |

Both need Fabric API and Java 25. Drop the jar in your `mods/` folder, and
delete any older Birch Optimizer jar first — two copies of the same mod id stop
Fabric from starting.

They are not interchangeable. Fabric reads the target out of the jar and
refuses to load one built for a different version, so picking the wrong file
fails at startup rather than misbehaving quietly.

## Same mod, both of them

`2.2.0` is the mod version and the suffix is the game it targets, which is
Fabric's own convention. Both jars are built from one source tree at the same
commit and run the same checks, so a fix is in both or in neither — there is no
"current" and "behind" any more.

That was not true before. Moving between the two versions meant editing the
same nine call sites by hand, which left the two lines of source quietly
diverging, so the 26.2 jar sat several features behind. The renames now live in
one file per version under `src/mc26_1` and `src/mc26_2`, and `./switch-mc`
picks one.
