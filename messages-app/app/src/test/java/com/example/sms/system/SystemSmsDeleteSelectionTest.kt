package com.example.sms.system

import com.example.sms.data.system.SystemSmsDeleteSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemSmsDeleteSelectionTest {

    @Test
    fun emptyOrInvalidIdsDoNotCreateDeleteQuery() {
        assertNull(SystemSmsDeleteSelection.byIds(emptyList()))
        assertNull(SystemSmsDeleteSelection.byIds(listOf(-1L, -2L)))
    }

    @Test
    fun deleteQueryUsesOnlyDistinctSystemIds() {
        val selection = SystemSmsDeleteSelection.byIds(listOf(9L, 3L, 9L))!!

        assertEquals("_id IN (?,?)", selection.where)
        assertEquals(listOf("9", "3"), selection.args.toList())
    }
}
