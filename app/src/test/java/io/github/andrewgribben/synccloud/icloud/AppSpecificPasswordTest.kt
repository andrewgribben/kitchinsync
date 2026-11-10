package io.github.andrewgribben.synccloud.icloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSpecificPasswordTest {

    @Test
    fun `accepts dashed, undashed and spaced forms`() {
        for (input in listOf("abcd-efgh-ijkl-mnop", "abcdefghijklmnop", " ABCD efgh IJKL mnop "))
            assertEquals("abcd-efgh-ijkl-mnop", AppSpecificPassword.normalize(input))
    }

    @Test
    fun `rejects anything that isn't 16 letters`() {
        for (input in listOf("", "abcd-efgh-ijkl", "abcd-efgh-ijkl-mnopq", "abcd-efgh-ijkl-mn0p", "MyApplePassword1"))
            assertNull(input, AppSpecificPassword.normalize(input))
    }
}
