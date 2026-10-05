# Downloads

Built jars, committed so there is a direct download link.

One per Minecraft version — they are not interchangeable. Fabric reads the
target out of the jar and refuses to load one built for a different version,
so picking the wrong file fails at startup rather than misbehaving quietly.

| Minecraft | Jar | Fabric Loader | |
|---|---|---|---|
| **26.1.2** | `birchoptimizer-1.21.0.jar` | 0.19.3+ | current |
| **26.2**   | `birchoptimizer-2.1.0.jar`  | 0.19.5+ | behind |

Both need Fabric API and Java 25. Drop the jar in your `mods/` folder, and
delete any older Birch Optimizer jar first — two copies of the same mod id
stop Fabric from starting.

## About the 26.2 jar

It is an older build. Development is on 26.1.2, which is what is being played,
and the two versions need different source for the client rendering and screen
calls — so keeping both current means porting every change twice, for a jar
nobody is running.

The 26.2 jar therefore does not have: the Stats tab, collection tiers, route
splits, or the fixes that came with them. Its source is kept on the
`mc26.2-line` tag and it can be brought forward on request; everything since is
version-independent and ports mechanically.
