package io.github.andrewgribben.synccloud.sync

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyFactory
import at.bitfire.dav4jvm.PropertyRegistry
import at.bitfire.dav4jvm.property.carddav.AddressData
import at.bitfire.dav4jvm.property.carddav.CardDAV
import org.xmlpull.v1.XmlPullParser

/**
 * dav4jvm's own `AddressData.Factory` (`at.bitfire.dav4jvm.XmlReader.readText()`) keeps only the
 * *last* `XmlPullParser` `TEXT` event seen at a given depth instead of concatenating all of them:
 * ```
 * if (eventType == XmlPullParser.TEXT && parser.depth == depth)
 *     text = parser.text   // overwrites, doesn't append
 * ```
 * A `<card:address-data>` element's PCDATA almost always arrives as a single `TEXT` event and is
 * unaffected, but `XmlPullParser` implementations are free to split long text runs into several
 * consecutive `TEXT` events at the same depth (e.g. at an internal buffer boundary). iCloud vCards
 * with a `PHOTO` property (a long URL, or inline base64) are exactly the case long enough to hit
 * this: when the split lands inside or right after `PHOTO`'s value, everything in every `TEXT`
 * chunk except the last is silently discarded. The result: the downloaded vCard text has the next
 * property's name glued directly onto `PHOTO`'s value with no line break (e.g.
 * `.../card/<hash>X-SHARED-PHOTO-DISPLAY-PREF:IMPLICIT_AUTOUPDATE`), which ez-vcard then parses as
 * part of `PHOTO`'s own value - so it never resolves to a usable URL/data and the photo download
 * silently fails (see [ContactsSyncer]/`IcloudPhotoDownloader`).
 *
 * This factory is otherwise identical to `AddressData.Factory`, except it appends every `TEXT`
 * event instead of overwriting, so a multi-chunk `<address-data>` round-trips intact. [installOnce]
 * registers it to replace dav4jvm's built-in factory for the same property name (dav4jvm's
 * `PropertyRegistry.register` keys by name, so the last registration for a name wins).
 */
object FixedAddressDataFactory : PropertyFactory {
    override fun getName(): Property.Name = CardDAV.AddressData

    override fun create(parser: XmlPullParser): AddressData {
        val text = StringBuilder()
        val depth = parser.depth
        var eventType = parser.eventType
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.TEXT && parser.depth == depth) text.append(parser.text)
            eventType = parser.next()
        }
        return AddressData(text.toString())
    }

    @Volatile
    private var installed = false

    /** Idempotent: cheap to call before every CardDAV operation (see [RemoteAddressBookSync]). */
    fun installOnce() {
        if (installed) return
        PropertyRegistry.register(this)
        installed = true
    }
}
