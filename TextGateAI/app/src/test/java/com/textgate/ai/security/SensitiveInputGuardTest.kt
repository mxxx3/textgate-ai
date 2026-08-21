package com.textgate.ai.security

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers spec scenarios:
 *   #1  normal text field + ?en -> allowed (isEditableTextField / isSensitiveInput both pass)
 *   #3  isPassword == true -> zero read
 *   #4  TYPE_TEXT_VARIATION_PASSWORD -> zero read
 *   #5  TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> zero read
 *   #6  TYPE_TEXT_VARIATION_WEB_PASSWORD -> zero read
 *   #7  TYPE_NUMBER_VARIATION_PASSWORD -> zero read
 *   #11 exception while inspecting the node -> fail closed
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SensitiveInputGuardTest {

    private var node: AccessibilityNodeInfo? = null

    @After
    fun tearDown() {
        @Suppress("DEPRECATION")
        node?.recycle()
        node = null
    }

    @Test
    fun `normal editable non-password field is not sensitive`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            isPassword = false
            className = "android.widget.EditText"
        }
        node = n
        assertTrue(SensitiveInputGuard.isEditableTextField(n))
        assertFalse(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `isPassword true is always sensitive`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            isPassword = true
        }
        node = n
        assertTrue(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `TYPE_TEXT_VARIATION_PASSWORD is sensitive`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            isPassword = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        node = n
        assertTrue(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD is sensitive`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        node = n
        assertTrue(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `TYPE_TEXT_VARIATION_WEB_PASSWORD is sensitive`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        node = n
        assertTrue(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `TYPE_NUMBER_VARIATION_PASSWORD is sensitive`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        node = n
        assertTrue(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `className mentioning password is sensitive even without other signals`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            className = "com.example.CustomPasswordField"
        }
        node = n
        assertTrue(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `hint text mentioning sensitive keywords is sensitive`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "Enter your PIN"
        }
        node = n
        assertTrue(SensitiveInputGuard.isSensitiveInput(n))
    }

    @Test
    fun `null node is sensitive and not editable (fail closed)`() {
        assertTrue(SensitiveInputGuard.isSensitiveInput(null))
        assertFalse(SensitiveInputGuard.isEditableTextField(null))
    }

    @Test
    fun `non-editable node is rejected`() {
        val n = AccessibilityNodeInfo.obtain().apply {
            isEditable = false
        }
        node = n
        assertFalse(SensitiveInputGuard.isEditableTextField(n))
    }

    @Test
    fun `exception while inspecting node properties fails closed to sensitive`() {
        val mockNode = mock<AccessibilityNodeInfo> {
            on { isPassword } doThrow RuntimeException("simulated framework failure")
        }
        assertTrue(SensitiveInputGuard.isSensitiveInput(mockNode))
    }
}
