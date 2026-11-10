package io.github.andrewgribben.synccloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.xmlpull.v1.XmlPullParser
import org.junit.Test

/**
 * A fake [XmlPullParser] that replays a scripted, fixed sequence of events: one START_TAG, then
 * one TEXT event per entry in [textChunks] (all at the same depth, as a real parser would emit
 * for one PCDATA run split across several internal buffers), then one END_TAG. Every method
 * [FixedAddressDataFactory.create] doesn't use throws, so this only needs to support depth/
 * eventType/text/next().
 */
private class ScriptedAddressDataParser(private val textChunks: List<String>) : XmlPullParser {
    // index -1 = START_TAG (initial position when a PropertyFactory.create is invoked),
    // 0..textChunks.lastIndex = TEXT chunks, textChunks.size = END_TAG
    private var index = -1

    override fun getDepth() = 1
    override fun getEventType() = when {
        index == -1 -> XmlPullParser.START_TAG
        index < textChunks.size -> XmlPullParser.TEXT
        else -> XmlPullParser.END_TAG
    }
    override fun getText(): String? = textChunks.getOrNull(index)
    override fun next(): Int {
        index++
        return eventType
    }

    // Nothing under test touches any of the below.
    override fun setFeature(name: String?, state: Boolean) = throw UnsupportedOperationException()
    override fun getFeature(name: String?) = throw UnsupportedOperationException()
    override fun setProperty(name: String?, value: Any?) = throw UnsupportedOperationException()
    override fun getProperty(name: String?) = throw UnsupportedOperationException()
    override fun setInput(inStream: java.io.Reader?) = throw UnsupportedOperationException()
    override fun setInput(inputStream: java.io.InputStream?, inputEncoding: String?) = throw UnsupportedOperationException()
    override fun getInputEncoding() = throw UnsupportedOperationException()
    override fun defineEntityReplacementText(entityName: String?, replacementText: String?) = throw UnsupportedOperationException()
    override fun getNamespaceCount(depth: Int) = throw UnsupportedOperationException()
    override fun getNamespacePrefix(pos: Int) = throw UnsupportedOperationException()
    override fun getNamespaceUri(pos: Int) = throw UnsupportedOperationException()
    override fun getNamespace(prefix: String?) = throw UnsupportedOperationException()
    override fun getPositionDescription() = throw UnsupportedOperationException()
    override fun getLineNumber() = throw UnsupportedOperationException()
    override fun getColumnNumber() = throw UnsupportedOperationException()
    override fun isWhitespace() = throw UnsupportedOperationException()
    override fun getTextCharacters(holderForStartAndLength: IntArray?) = throw UnsupportedOperationException()
    override fun getNamespace() = throw UnsupportedOperationException()
    override fun getName() = throw UnsupportedOperationException()
    override fun getPrefix() = throw UnsupportedOperationException()
    override fun isEmptyElementTag() = throw UnsupportedOperationException()
    override fun getAttributeCount() = throw UnsupportedOperationException()
    override fun getAttributeNamespace(index: Int) = throw UnsupportedOperationException()
    override fun getAttributeName(index: Int) = throw UnsupportedOperationException()
    override fun getAttributePrefix(index: Int) = throw UnsupportedOperationException()
    override fun getAttributeType(index: Int) = throw UnsupportedOperationException()
    override fun isAttributeDefault(index: Int) = throw UnsupportedOperationException()
    override fun getAttributeValue(index: Int) = throw UnsupportedOperationException()
    override fun getAttributeValue(namespace: String?, name: String?) = throw UnsupportedOperationException()
    override fun nextToken() = throw UnsupportedOperationException()
    override fun require(type: Int, namespace: String?, name: String?) = throw UnsupportedOperationException()
    override fun nextText() = throw UnsupportedOperationException()
    override fun nextTag() = throw UnsupportedOperationException()
}

class FixedAddressDataFactoryTest {

    @Test
    fun `a single TEXT event round-trips unchanged (the common case)`() {
        val vCard = "BEGIN:VCARD\r\nFN:Jane\r\nEND:VCARD"
        val parser = ScriptedAddressDataParser(listOf(vCard))

        assertEquals(vCard, FixedAddressDataFactory.create(parser).card)
    }

    @Test
    fun `a PHOTO value split across two TEXT events at the line break is reassembled with the break intact`() {
        // Anonymised reproduction of the real bug: dav4jvm's own AddressData.Factory keeps only
        // the LAST TEXT event (XmlReader.readText(): "text = parser.text" overwrites instead of
        // appending), so when the XmlPullParser splits one <address-data> PCDATA run into two
        // TEXT events right at the CRLF after PHOTO's value, the downloaded vCard text used to
        // come out as ".../card/REDACTEDHASH1234X-SHARED-PHOTO-DISPLAY-PREF:IMPLICIT_AUTOUPDATE"
        // - the next property's name glued straight onto PHOTO's value with the line break gone.
        val chunk1 = "BEGIN:VCARD\r\nFN:Jane\r\nPHOTO;VALUE=uri:https://p39-contacts.icloud.com/1/card/REDACTEDHASH1234"
        val chunk2 = "\r\nX-SHARED-PHOTO-DISPLAY-PREF:IMPLICIT_AUTOUPDATE\r\nEND:VCARD"
        val parser = ScriptedAddressDataParser(listOf(chunk1, chunk2))

        val card = FixedAddressDataFactory.create(parser).card!!

        assertEquals(chunk1 + chunk2, card)
        assertTrue("PHOTO's URI must end at the line break, not run into the next property",
            card.contains("REDACTEDHASH1234\r\nX-SHARED-PHOTO-DISPLAY-PREF"))
    }

    @Test
    fun `three TEXT events (an even longer split) are all preserved in order`() {
        val chunks = listOf("BEGIN:VCARD\r\n", "FN:Jane\r\nPHOTO:", "https://example/x\r\nEND:VCARD")
        val parser = ScriptedAddressDataParser(chunks)

        assertEquals(chunks.joinToString(""), FixedAddressDataFactory.create(parser).card)
    }
}
