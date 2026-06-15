package com.tdarby.comet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEnginesTest {

    @Test
    fun indexAndTemplateRoundTrip() {
        SearchEngines.templates.forEachIndexed { index, template ->
            assertEquals(index, SearchEngines.indexOf(template))
            assertEquals(template, SearchEngines.templateAt(index))
        }
    }

    @Test
    fun unknownTemplateFallsBackToFirst() {
        assertEquals(0, SearchEngines.indexOf("https://example.com/?q=%s"))
    }

    @Test
    fun outOfRangeIndexFallsBackToFirst() {
        assertEquals(SearchEngines.templates[0], SearchEngines.templateAt(999))
        assertEquals(SearchEngines.templates[0], SearchEngines.templateAt(-1))
    }

    @Test
    fun everyTemplateHasQueryPlaceholder() {
        assertTrue(SearchEngines.templates.all { it.contains("%s") })
    }
}
