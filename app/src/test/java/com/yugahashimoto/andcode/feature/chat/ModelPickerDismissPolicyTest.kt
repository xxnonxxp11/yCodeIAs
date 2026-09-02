package com.yugahashimoto.andcode.feature.chat

import androidx.compose.material3.SheetValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerDismissPolicyTest {
    @Test
    fun `rejects hiding while the model list is scrolled`() {
        assertFalse(isModelPickerDismissAllowed(SheetValue.Hidden, isListAtTop = false))
    }

    @Test
    fun `allows hiding when the model list is at the top`() {
        assertTrue(isModelPickerDismissAllowed(SheetValue.Hidden, isListAtTop = true))
    }

    @Test
    fun `allows non-hidden sheet transitions regardless of list position`() {
        assertTrue(isModelPickerDismissAllowed(SheetValue.Expanded, isListAtTop = false))
    }
}
