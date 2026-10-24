package com.luumlabs.lumen

/** Release filenames always include the tag; old published releases used stable names. */
internal object ReleaseAssetNames {
    fun candidates(tag: String, guard: Boolean): List<String> {
        val prefix = if (guard) "Lumen-guard" else "Lumen"
        return listOf("$prefix-$tag.apk", "$prefix.apk")
    }

    fun select(tag: String, guard: Boolean, available: List<String>): String? =
        candidates(tag, guard).firstOrNull { it in available }
}
