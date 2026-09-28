import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

version = "8.10.0"
description = "Beta backport of ComponentsV2"

android {
    namespace = "moe.lava.corenary.componentsv2"
}

aliucord {
    // Changelog of your plugin
    changelog.set("""
        TODO {fixed}
        ======================
        * File component
        * SelectV2: searching
        * SelectV2: showing selected items in chat list
        
        Changelog {added marginTop}
        ======================
        # 8.10.0
        * Show bot Components V2 in search results and other secondary message lists
        * Render forwarded bot messages from their message snapshots
        * Display and submit text fields in newer bot modal dialogs
        * Fix gallery images and videos with extensionless media URLs
        * Prevent ViewRaw crashes on new component types

        # 8.9.0
        * Support Components V2 link previews from regular user messages
        * Preserve link-preview components in the message cache and ViewRaw
        * Fix empty link previews on builds with contentScanVersion
        * Handle multiple component previews and preview content updates

        # 8.8.0
        * Fix a possible weird crash

        # 8.7.0
        * Prevent ViewRaw crash
        * Add a CV2 tag to distinguish new embeds (will not be in core)

        # 7.15.1
        * Fix broken reply preview >w<

        # 7.15.0
        * Initial release >w<
    """.trimIndent())

    deploy.set(true)
}

// Only use Shadow's relocation task; applying its JVM plugin conflicts with AGP.
val shadowDir = File(buildDir, "intermediates/shadowed")

tasks.register<ShadowJar>("relocateJar") {
    val task = tasks.findByName("compileDebugKotlin")!!
    from(task.outputs)
//    relocate("com.discord.api.botuikit", "moe.lava.awoocanary.componentsv2.botuikit") {
//        exclude("com.discord.api.botuikit.ComponentType")
//    }
    relocate("com.aliucord.coreplugins.componentsv2", "moe.lava.corenary.componentsv2")
    relocate("com.aliucord.coreplugins.ComponentsV2", "moe.lava.corenary.ComponentsV2")
    archiveClassifier.set("shadowed")
    destinationDirectory.set(File(buildDir, "intermediates"))
}

tasks.register<Sync>("copyShadowed") {
    val reloc = tasks.findByName("relocateJar")!! as ShadowJar
    dependsOn(reloc)
    from(zipTree(reloc.archiveFile))
    into(shadowDir)
}

project.afterEvaluate {
    tasks.compileDex {
        val copyShadowed = tasks.findByName("copyShadowed")!! as Sync
        dependsOn(copyShadowed)
        input.setFrom(shadowDir)
    }
}
