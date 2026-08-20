package com.textgate.ai.security

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The single, central security gate that stands between the accessibility
 * service and [AccessibilityNodeInfo.getText]. Every code path in this app
 * that could end up reading the contents of a text field MUST call
 * [isSensitiveInput] first and MUST NOT read `node.text` if it returns
 * `true`, or if the node fails [isEditableTextField].
 *
 * Design rule enforced throughout this file and its caller
 * (TextGateAccessibilityService): inspect node PROPERTIES first; only read
 * `node.text` AFTER every check below has passed. Never do
 * `val text = node.text` and decide afterwards — that ordering is exactly
 * what this object exists to prevent.
 *
 * Every branch below that cannot conclusively prove a field is safe returns
 * "sensitive" / "not editable". Uncertainty always resolves to NOT reading
 * the field ("fail closed").
 */
object SensitiveInputGuard {

    /**
     * True if [node] is a field this app must never read: a password field
     * by the platform's own signal, a field whose declared input type is a
     * password variant, a field whose accessibility class name suggests it
     * is a password widget, or a field whose hint text suggests sensitive
     * content (PIN, seed phrase, CVV, one-time code, etc). Also true — by
     * design — for any node we cannot fully inspect, or if inspection
     * throws for any reason.
     */
    @JvmStatic
    fun isSensitiveInput(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return true // fail closed: nothing to safely check

        return try {
            // 1) The platform's own authoritative signal. If the field or
            // its IME editor info marks this as a password field, that is
            // the end of the discussion — never read it.
            if (node.isPassword) return true

            // 2) inputType variation/class bits. These are checked
            // independently of isPassword because some custom widgets set
            // a password-shaped inputType without ever setting isPassword
            // to true. Any of the four variations named in the app's
            // security spec is blocked.
            val inputType = node.inputType
            val typeClass = inputType and InputType.TYPE_MASK_CLASS
            val typeVariation = inputType and InputType.TYPE_MASK_VARIATION

            if (typeClass == InputType.TYPE_CLASS_TEXT) {
                when (typeVariation) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return true
                }
            }
            if (typeClass == InputType.TYPE_CLASS_NUMBER) {
                if (typeVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
            }

            // 3) Defense in depth: className heuristic. Some third-party
            // keyboards / custom widgets implement their own password field
            // without ever setting isPassword or a password inputType. If
            // the accessibility class name itself mentions "password", we
            // still refuse.
            val className = node.className?.toString()?.lowercase().orEmpty()
            if (className.contains("password")) return true

            // 4) Defense in depth: hint-text heuristic. Reading a field's
            // hint (placeholder) is not reading its content, but a hint
            // that says "PIN", "seed phrase", "CVV", "security code" etc.
            // is a strong enough signal that we still refuse to read the
            // actual value, even if none of the structural checks above
            // caught it.
            val hint = node.hintText?.toString()?.lowercase().orEmpty()
            if (SENSITIVE_HINT_KEYWORDS.any { hint.contains(it) }) return true

            false
        } catch (_: Exception) {
            // Any exception while inspecting node properties is treated as
            // "cannot prove this is safe" -> fail closed.
            true
        }
    }

    /**
     * True only if [node] is non-null, currently editable, and is a text
     * entry class of widget. This is checked BEFORE [isSensitiveInput] in
     * the caller so that non-editable / non-text nodes (buttons, labels,
     * images, etc.) are never even considered for the sensitivity check or
     * a text read.
     */
    @JvmStatic
    fun isEditableTextField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.isEditable
        } catch (_: Exception) {
            false
        }
    }

    private val SENSITIVE_HINT_KEYWORDS = listOf(
        "password",
        "passcode",
        "pin",
        "seed phrase",
        "recovery phrase",
        "mnemonic",
        "cvv",
        "cvc",
        "security code",
        "one-time code",
        "one time code",
        "otp",
        "card number",
        "iban",
        "routing number",
        "ssn",
        "social security"
    )
}
