package com.evelorion.phone.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

class DialNumberTest {

    @Test
    fun `paste removes formatting and keeps international plus`() {
        assertEquals("+14155552671", DialNumber.sanitizeInput("+1 (415) 555-2671"))
    }

    @Test
    fun `china mobile removes plus 86`() {
        assertEquals("13800138000", DialNumber.sanitizeInput("+86 138-0013-8000"))
    }

    @Test
    fun `china mobile removes 0086 and bare 86`() {
        assertEquals("13800138000", DialNumber.forDialing("0086 13800138000"))
        assertEquals("13800138000", DialNumber.forDialing("8613800138000"))
    }

    @Test
    fun `international and china landline country codes remain`() {
        assertEquals("+442079460018", DialNumber.forDialing("+44 20 7946 0018"))
        assertEquals("+861012345678", DialNumber.forDialing("+86 10 1234 5678"))
    }

    @Test
    fun `service symbols and full width digits are normalized`() {
        assertEquals("*#06#", DialNumber.sanitizeInput("＊＃０６＃"))
    }
}
