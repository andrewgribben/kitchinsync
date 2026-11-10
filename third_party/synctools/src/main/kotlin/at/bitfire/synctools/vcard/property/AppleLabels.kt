/*
 * KitchinSync modification (not upstream). GPL-3.0-or-later.
 */

package at.bitfire.synctools.vcard.property

/**
 * Apple stores built-in X-ABLabel values as tokens like `_$!<Other>!$_`, which iCloud/iOS
 * localise. Show them as plain text on Android and turn them back into tokens on upload.
 */
object AppleLabels {

    private val token = Regex("""^_\${'$'}!<(.+)>!\${'$'}_${'$'}""")

    private val names = mapOf(
        "Mobile" to "Mobile", "Home" to "Home", "Work" to "Work", "Main" to "Main",
        "HomeFAX" to "Home fax", "WorkFAX" to "Work fax", "OtherFAX" to "Other fax",
        "Pager" to "Pager", "HomePage" to "Home page", "School" to "School"
    )

    /** `_$!<Other>!$_` → null (Android's default "Other" type), other tokens → readable text. */
    fun fromApple(label: String?): String? {
        val inner = label?.let { token.matchEntire(it) }?.groupValues?.get(1) ?: return label
        if (inner == "Other") return null
        return names[inner] ?: inner
    }

    /** Readable text from [fromApple] → Apple token, so iPhones show their localised label. */
    fun toApple(label: String?): String? =
        names.entries.firstOrNull { it.value == label }?.let { "_\$!<${it.key}>!\$_" } ?: label

}
