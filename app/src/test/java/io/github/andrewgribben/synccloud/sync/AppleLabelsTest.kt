package io.github.andrewgribben.synccloud.sync

import at.bitfire.synctools.vcard.property.AppleLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleLabelsTest {

    @Test
    fun otherTokenBecomesDefaultType() = assertNull(AppleLabels.fromApple("_\$!<Other>!\$_"))

    @Test
    fun knownTokensBecomeReadableAndRoundTrip() {
        assertEquals("Home page", AppleLabels.fromApple("_\$!<HomePage>!\$_"))
        assertEquals("_\$!<HomePage>!\$_", AppleLabels.toApple("Home page"))
    }

    @Test
    fun unknownTokenIsUnwrappedAndCustomLabelsUntouched() {
        assertEquals("Garage", AppleLabels.fromApple("_\$!<Garage>!\$_"))
        assertEquals("Gran's", AppleLabels.fromApple("Gran's"))
        assertEquals("Gran's", AppleLabels.toApple("Gran's"))
    }
}
