// Game-agnostic plain Java: the catalog format, random tables, the give rules (limits, trust, once per
// player), the gift log and the public API. No game API and no CommandDeck API is on this project's classpath:
// the adapter connects both through the ports in generousoperator.give.
// Compiled for Java 17 so the same jar can run on Necesse.
tasks.withType<JavaCompile> { options.release.set(17) }
