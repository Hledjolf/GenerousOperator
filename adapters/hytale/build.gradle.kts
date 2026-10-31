// The Hytale plugin. The server jar and CommandDeck's jar are APIs: compile-only, never bundled.
// The game-agnostic core is merged into the plugin jar, so server owners install a single file.
val hytaleServerJar = (findProperty("hytaleServerJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"
// Built against the released CommandDeck 1.1.0, the version the manifest requires.
val commandDeckJar = (findProperty("commandDeckJar") as String?)
    ?: rootProject.file("../CommandDeck/release/1.1.0/CommandDeck-1.1.0.jar").path
// The shared power curve (Reference Code's generated power.json). Read where it is, never copied into the repository:
// the build turns it into a small item-to-zone table inside the jar. Without it, every item is unrated.
val powerJson = (findProperty("powerJson") as String?)
    ?: rootProject.file("../UtilityModEcosystem/reference/hytale/generated/power.json").path

dependencies {
    "implementation"(project(":core"))
    "compileOnly"(files(hytaleServerJar, commandDeckJar))
    "testImplementation"(files(hytaleServerJar, commandDeckJar))
}

// powerTable: generousoperator/power-zones.tsv, one "<item id> TAB <zone>" line per item the curve rates
// (status "rated"), under a header naming the curve and the Hytale version it came from (ZoneTable reads it).
val powerTableDir = layout.buildDirectory.dir("generated/power")
val powerTable = tasks.register("powerTable") {
    val source = file(powerJson)
    if (source.exists()) inputs.file(source)
    outputs.dir(powerTableDir)
    doLast {
        val out = powerTableDir.get().file("generousoperator/power-zones.tsv").asFile
        out.parentFile.mkdirs()
        if (!source.exists()) {
            out.delete()
            logger.warn("No power curve at $source: every item will be unrated (pass -PpowerJson=<path>).")
            return@doLast
        }
        @Suppress("UNCHECKED_CAST")
        val curve = groovy.json.JsonSlurper().parse(source) as Map<String, Any?>
        if (curve["format"] != "ume.hytale.power" || curve["format_version"].toString() != "1") {
            throw GradleException("$source is ${curve["format"]} ${curve["format_version"]}; this build reads ume.hytale.power 1")
        }
        @Suppress("UNCHECKED_CAST")
        val items = curve["items"] as Map<String, Map<String, Any?>>
        val hytale = (curve["hytale"] as Map<*, *>)["version"]
        val lines = items.filter { it.value["status"] == "rated" && it.value["zone"] is Number }
            .toSortedMap().map { "${it.key}\t${(it.value["zone"] as Number).toInt()}" }
        out.writeText("generousoperator.power\t1\tume.hytale.power 1, Hytale $hytale\n" + lines.joinToString("\n") + "\n")
    }
}
extensions.getByType<SourceSetContainer>()["main"].resources.srcDir(powerTable)

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", rootProject.version.toString())
    filesMatching("manifest.json") { expand("version" to rootProject.version.toString()) }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("GenerousOperator")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("mods"))
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    if (rootProject.file("LICENSE").exists()) from(rootProject.file("LICENSE"))
    // The playtest plans are for dev builds only; a release jar never carries them.
    if (rootProject.hasProperty("releaseBuild")) exclude("playtest/**")
}
