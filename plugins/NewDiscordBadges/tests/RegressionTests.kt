package com.github.yutaplug.newdiscordbadges

/** Run as a plain Kotlin/JVM program with ProfileBadges.kt and WeakIdentityMap.kt. */
fun main() {
    var checks = 0
    fun verify(value: Boolean, message: String) {
        check(value) { message }
        checks++
    }
    val silverHash = "4514fab914bdbfb4ad2fa23df76121a6"
    fun badge(id: String, vararg extra: Pair<String, Any?>) =
        mapOf("id" to id, "description" to "Badge $id", "icon" to silverHash) + extra
    fun profile(vararg badges: Map<String, Any?>) = mapOf("badges" to badges.toList())
    val simpleUrl = "https://cdn.discordapp.com/assets/content/silver.webp?size=64"
    val silver = ProfileBadges.parse(profile(badge("premium", "simple_icon_url" to simpleUrl))).single()
    verify(silver.images.first() == simpleUrl, "Use the supplied Silver image immediately")
    verify(silver.images[1] == "https://cdn.discordapp.com/badge-icons/$silverHash.png", "Keep hash as image fallback")
    verify(silver.id == "premium", "Do not rewrite API ids based on English tier labels")
    verify(ProfileBadges.parse(profile()).isEmpty(), "An empty visible list is authoritative")
    verify(ProfileBadges.parse(mapOf("badges" to null)).isEmpty(), "Null badges do not invent badges")
    verify(ProfileBadges.parse(null).isEmpty(), "Missing profiles are safe")
    verify(ProfileBadges.parse(listOf(badge("premium"))).isEmpty(), "Only profile badge lists are parsed")
    verify(ProfileBadges.parse(mapOf("badge_catalog" to listOf(badge("premium")))).isEmpty(), "Never render an owned catalog")
    verify(ProfileBadges.parse(profile(badge("premium", "hidden" to true))).isEmpty(), "Honor explicit hiding")
    verify(ProfileBadges.parse(profile(badge("premium", "visible" to false))).isEmpty(), "Honor explicit invisibility")
    val mixed = ProfileBadges.parse(profile(badge("staff"), badge("hypesquad_house_1"), badge("future_badge")))
    verify(mixed.map { it.id } == listOf("staff", "hypesquad_house_1", "future_badge"), "Native and future badges are included")
    val gifts = ProfileBadges.parse(profile(badge("gifting_patron"), badge("gifting_champion")))
    verify(gifts.size == 2, "Do not collapse independently visible gifting tiers")
    verify(ProfileBadges.parse(profile(badge("premium"), badge("premium"))).size == 1, "Deduplicate repeated ids")
    val keyed = ProfileBadges.parse(mapOf("badges" to mapOf("gifting" to mapOf("icon" to silverHash)))).single()
    verify(keyed.id == "gifting", "Support keyed visible badge lists")
    verify(keyed.description == "gifting", "Missing descriptions have an id fallback")
    verify(ProfileBadges.parse(profile(badge("bad", "icon" to "not-an-image"))).isEmpty(), "Ignore invalid image data")
    verify(ProfileBadges.parse(mapOf("badges" to listOf(null, 1, "premium", badge("valid")))).size == 1, "Skip malformed entries")
    verify(ProfileBadges.imageUrl(silverHash + ".png")?.endsWith(".png.png") == false, "Avoid doubled file extensions")
    verify(ProfileBadges.imageUrl("/badge-icons/$silverHash.png") == "https://cdn.discordapp.com/badge-icons/$silverHash.png", "Resolve relative CDN paths")
    verify(ProfileBadges.imageUrl("//cdn.discordapp.com/a.png") == "https://cdn.discordapp.com/a.png", "Resolve protocol-relative URLs")
    verify(ProfileBadges.imageUrl("http://example.com/a.png") == null, "Do not downgrade authenticated client images to HTTP")
    verify(ProfileBadges.parse(profile(badge("premium", "simpleIconUrl" to simpleUrl))).single().images.first() == simpleUrl, "Support newer RN model serialization")
    verify(ProfileBadges.imageUrl("") == null, "Empty image fields do not invoke Kotlin isBlank")
    verify(ProfileBadges.imageUrl(" \t\n") == null, "Blank image fields are ignored without primitive iterators")
    val padded = ProfileBadges.parse(profile(badge(" premium ", "icon" to " \t$silverHash\n"))).single()
    verify(padded.id == "premium" && padded.images.single().endsWith("$silverHash.png"), "Trim data with indexed characters")
    val noIcon = ProfileBadges.parse(profile(badge("premium", "icon" to "", "simple_icon_url" to simpleUrl))).single()
    verify(noIcon.images.single() == simpleUrl, "Accept badges with a URL and an empty hash")
    // Desktop stdlib tests alone cannot reproduce Discord's obfuscated iterator.
    // Guard the compiled parser as well, so reintroducing isBlank/trim fails here.
    val parserBytes = ProfileBadges::class.java.getResourceAsStream("ProfileBadges.class")!!.use { it.readBytes() }
    val parserBytecode = String(parserBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
    verify(!parserBytecode.contains("kotlin/text/StringsKt") &&
        !parserBytecode.contains("kotlin/collections/IntIterator") && !parserBytecode.contains("kotlin/ranges/"),
        "Badge parsing must not call incompatible Kotlin text/range helpers")

    data class EqualProfile(val id: Int)
    val first = EqualProfile(1)
    val second = EqualProfile(1)
    val cache = WeakIdentityMap<EqualProfile, String>()
    cache[first] = "visible"
    cache[second] = "hidden"
    verify(cache[first] == "visible", "Equal profiles must not overwrite each other's badge lists")
    verify(cache[second] == "hidden", "Each profile object has an independent lifetime")
    cache[first] = "updated"
    verify(cache[first] == "updated", "Updating the same profile identity works")
    cache.clear()
    verify(cache[first] == null && cache[second] == null, "Stop clears profile data")
    println("$checks regression checks passed")
}
