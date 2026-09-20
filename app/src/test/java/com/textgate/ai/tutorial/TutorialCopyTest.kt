package com.textgate.ai.tutorial

import com.textgate.ai.model.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialCopyTest {

    @Test
    fun everySupportedLanguageHasExplicitTutorialCopy() {
        Languages.ALL.forEach { language ->
            assertTrue(
                "Missing tutorial copy for ${language.code}",
                TutorialCopyProvider.hasExplicitCopy(language.code)
            )
            assertEquals(6, TutorialCopyProvider.forCode(language.code).slides.size)
        }
    }
}
