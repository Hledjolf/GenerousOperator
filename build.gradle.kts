// Generous Operator: a game-agnostic core plus one adapter per game.
// core/ is plain Java compiled for Java 17 (shareable with Necesse); adapters/hytale/ is the Hytale plugin on Java 25.
plugins { base }

val releaseVersion = "1.0.0"
version = if (hasProperty("releaseBuild")) releaseVersion else "$releaseVersion-dev"

subprojects {
    version = rootProject.version
    apply(plugin = "java")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

// ---------------------------------------------------------------------------------------------------------
// checkAdapterBoundary: core/ is game-agnostic and CommandDeck-agnostic. No file under core/ may name a game
// API, CommandDeck's API, or use reflection, so it translates to the C#, C++ and Lua cores.
// ---------------------------------------------------------------------------------------------------------
val checkAdapterBoundary = tasks.register("checkAdapterBoundary") {
    group = "verification"
    description = "Fails if any file under core/ uses a game API, CommandDeck's API or reflection."
    val sources = fileTree("core") { include("**/*.java"); exclude("build/**") }
    inputs.files(sources)
    doLast {
        val banned = listOf("com.hypixel", "commanddeck.api", "import commanddeck", "java.lang.reflect", "getDeclaredField", "getDeclaredMethod", "Class.forName")
        val offenders = sources.files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> banned.any { l.contains(it) } }
                .map { (i, l) -> " - ${f.relativeTo(projectDir)}:${i + 1}: ${l.trim()}" }
        }
        if (offenders.isNotEmpty()) throw GradleException("Game API, CommandDeck API or reflection in core:\n" + offenders.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------------------------------------
// The published set: what goes out on the mod sites and into the public repository. Everything else in this
// folder (design notes, messages, tools, tests, fixtures, playtest plans) stays private.
// ---------------------------------------------------------------------------------------------------------
val publishedRoots = listOf("core/src/main", "adapters/hytale/src/main", "gradle")
val publishedFiles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "core/build.gradle.kts", "adapters/hytale/build.gradle.kts", "LICENSE", "README.md", "CHANGELOG.md", "docs/catalog/README.md",
    ".gitattributes")
/** Not published although under a published root: the playtest plans ship only in dev builds. */
val unpublished = listOf("adapters/hytale/src/main/resources/playtest/")

fun publishedSet(): List<File> =
    publishedFiles.map { File(projectDir, it) }.filter { it.isFile } +
        publishedRoots.flatMap { r -> File(projectDir, r).walkTopDown().filter { it.isFile }.toList() }
            .filter { f -> unpublished.none { f.relativeTo(projectDir).invariantSeparatorsPath.startsWith(it) } }

// checkNoPrivateRefs: no published file names the author, a path on the build machine, or the private material.
// The words are assembled from parts so this file does not match itself.
val checkNoPrivateRefs = tasks.register("checkNoPrivateRefs") {
    group = "verification"
    description = "Fails if a published file names the author, a local path or the private notes."
    doLast {
        val words = listOf(Regex("\\b" + "J" + "im\\b", RegexOption.IGNORE_CASE), Regex("lue" + "bke", RegexOption.IGNORE_CASE),
            Regex("[A-Z]:[\\\\/](Tools|Users|GitHubRepos)", RegexOption.IGNORE_CASE), Regex("\\b" + "com" + "ms[\\\\/]"),
            Regex("\\b" + "des" + "ign[\\\\/]"), Regex("CLA" + "UDE\\.md"), Regex("PLAY" + "TEST\\.md"),
            Regex("\\b(GO|UME|CD|CS|QG|GTA)-D?[0-9]{3}\\b"))
        val textExt = setOf("java", "json", "md", "txt", "kts", "properties", "ui", "bat", "")
        val hits = publishedSet().filter { it.extension.lowercase() in textExt }.flatMap { f ->
            f.readLines(Charsets.UTF_8).withIndex().filter { (_, l) -> words.any { it.containsMatchIn(l) } }
                .map { (i, l) -> " - ${f.relativeTo(projectDir).invariantSeparatorsPath}:${i + 1}: ${l.trim()}" }
        }
        if (hits.isNotEmpty()) throw GradleException("Published files name private material:\n" + hits.joinToString("\n"))
    }
}

// exportPublic: copies the published set to build/public/, the tree the public repository is made from.
tasks.register<Sync>("exportPublic") {
    group = "distribution"
    description = "Copies only the published files to build/public/ (the public repository's tree)."
    dependsOn(checkNoPrivateRefs)
    into(layout.buildDirectory.dir("public"))
    from(projectDir) {
        include(publishedFiles)
        publishedRoots.forEach { include("$it/**") }
        unpublished.forEach { exclude("$it**") }
    }
    from(projectDir) {
        include(".gitignore-public")
        rename { ".gitignore" }
    }
    // Gradle leaves .gitattributes out of copies by default, so it's copied by hand.
    val target = layout.buildDirectory.dir("public")
    doLast { File(projectDir, ".gitattributes").copyTo(target.get().file(".gitattributes").asFile, overwrite = true) }
}

// ---------------------------------------------------------------------------------------------------------
// checkJarContents: the plugin jar holds Generous Operator's own classes, the manifest, the license, the window's
// documents, the settings sidecar, its own catalog and the power table. Playtest plans only in dev builds.
// ---------------------------------------------------------------------------------------------------------
val hytaleJar = project(":adapters:hytale").tasks.named<Jar>("jar")
val checkJarContents = tasks.register("checkJarContents") {
    group = "verification"
    description = "Fails if the plugin jar contains anything outside the allow list, or misses a required file."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    val release = !version.toString().endsWith("-dev")
    doLast {
        val allowed = Regex("""META-INF/.*|manifest\.json|LICENSE|generousoperator/(api|catalog|give|json|hytale|hytale/window)/[A-Za-z0-9_$]+\.class|"""
            + """Common/UI/Custom/GenerousOperator/[A-Za-z]+\.ui|Settings/Hledjolf_GenerousOperator\.settings\.json|"""
            + """generousoperator/catalog\.json|generousoperator/power-zones\.tsv""" + (if (release) "" else """|playtest/[a-z0-9-]+\.md"""))
        val names = java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        val bad = names.filter { !allowed.matches(it) }
        if (bad.isNotEmpty()) throw GradleException("Unexpected files in the jar:\n" + bad.joinToString("\n") { " - $it" })
        val required = listOf("manifest.json", "generousoperator/hytale/GenerousOperatorPlugin.class", "generousoperator/api/Gifts.class",
            "generousoperator/catalog.json", "Common/UI/Custom/GenerousOperator/Window.ui")
        required.filter { it !in names }.forEach { throw GradleException("The jar is missing $it") }
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { it.tasks.named("check") })
    dependsOn(checkAdapterBoundary, checkNoPrivateRefs, checkJarContents)
}

// ---------------------------------------------------------------------------------------------------------
// release: `gradlew release` runs the whole build again as version <releaseVersion> (no -dev), runs check on
// it, verifies the jar, and copies the upload files into release/<version>/: the jar (CurseForge) and a zip
// with the jar, README.md, LICENSE and CHANGELOG.md (Nexus). The normal build stays <releaseVersion>-dev.
// ---------------------------------------------------------------------------------------------------------
tasks.register<GradleBuild>("release") {
    group = "distribution"
    description = "Builds and checks version $releaseVersion and copies the jar and the zip to release/$releaseVersion/."
    dir = projectDir
    tasks = listOf("check", "releaseFiles")
    startParameter.projectProperties = gradle.startParameter.projectProperties + mapOf("releaseBuild" to "true")
}

val releaseDir = layout.projectDirectory.dir("release/$releaseVersion")

// checkReleaseJar: the jar says the release version, carries the license and a power table, has no development
// files, and its classes are the right level: the core Java 17 (major 61), the adapter the game's Java 25 (69).
val checkReleaseJar = tasks.register("checkReleaseJar") {
    group = "verification"
    description = "Fails if the release jar has the wrong version, no LICENSE or power table, development files or the wrong bytecode level."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    val expected = version.toString()
    doLast {
        if (expected.endsWith("-dev")) throw GradleException("checkReleaseJar runs only in a release build (gradlew release)")
        java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            val names = zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            for (n in listOf("LICENSE", "generousoperator/power-zones.tsv")) if (n !in names) throw GradleException("The release jar has no $n")
            val extra = names.filter { it.startsWith("playtest/") || it.startsWith("gotest/") }
            if (extra.isNotEmpty()) throw GradleException("The release jar has development files:\n" + extra.joinToString("\n") { " - $it" })
            val manifest = zf.getInputStream(zf.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
            if (!Regex(""""Version"\s*:\s*"${Regex.escape(expected)}"""").containsMatchIn(manifest)) {
                throw GradleException("manifest.json does not say Version $expected")
            }
            val coreDirs = listOf("generousoperator/api/", "generousoperator/catalog/", "generousoperator/give/", "generousoperator/json/")
            names.filter { it.endsWith(".class") }.forEach { n ->
                val b = zf.getInputStream(zf.getEntry(n)).use { it.readNBytes(8) }
                val major = ((b[6].toInt() and 0xff) shl 8) or (b[7].toInt() and 0xff)
                val want = if (coreDirs.any { n.startsWith(it) }) 61 else 69
                if (major != want) throw GradleException("$n is class file version $major, expected $want")
            }
        }
    }
}

val releaseZip = tasks.register<Zip>("releaseZip") {
    dependsOn(checkReleaseJar)
    archiveFileName.set("GenerousOperator-$version.zip")
    destinationDirectory.set(releaseDir)
    from(hytaleJar)
    from(layout.projectDirectory) { include("README.md", "LICENSE", "CHANGELOG.md") }
}

tasks.register<Copy>("releaseFiles") {
    group = "distribution"
    description = "Copies the checked release jar to release/<version>/ and makes the zip. Run through gradlew release."
    dependsOn(checkReleaseJar, releaseZip)
    mustRunAfter("check")
    from(hytaleJar)
    into(releaseDir)
}
