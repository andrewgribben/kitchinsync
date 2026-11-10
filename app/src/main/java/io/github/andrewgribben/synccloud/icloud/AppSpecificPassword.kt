package io.github.andrewgribben.synccloud.icloud

object AppSpecificPassword {

    /**
     * Apple shows app-specific passwords as `abcd-efgh-ijkl-mnop`. People paste them with or without
     * the dashes (or with spaces), so accept any of those and return Apple's canonical form,
     * or null if it can't be an app-specific password.
     */
    fun normalize(input: String): String? {
        val letters = input.filterNot { it == '-' || it.isWhitespace() }.lowercase()
        if (letters.length != 16 || !letters.all { it in 'a'..'z' }) return null
        return letters.chunked(4).joinToString("-")
    }
}
