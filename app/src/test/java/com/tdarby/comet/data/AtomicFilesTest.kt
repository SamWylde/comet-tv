package com.tdarby.comet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFilesTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun writeReplacesTargetAndLeavesNoTempFile() {
        val f = File(dir.root, "a.json")
        AtomicFiles.writeText(f, "old")
        AtomicFiles.writeText(f, "new")
        assertEquals("new", f.readText())
        assertFalse(File(dir.root, "a.json.tmp").exists())
    }

    @Test
    fun unparsableFileIsSetAsideNotOverwritten() {
        val f = File(dir.root, "b.json")
        File(dir.root, "b.json.corrupt").writeText("older")
        f.writeText("{not json")
        val r = AtomicFiles.readOrSetAside(f, emptyList<String>()) { error("bad") }
        assertTrue(r.isEmpty())
        assertFalse(f.exists())
        assertEquals("{not json", File(dir.root, "b.json.corrupt").readText())
    }

    @Test
    fun goodAndMissingFilesAreNotMoved() {
        val f = File(dir.root, "c.json")
        assertEquals(listOf<String>(), AtomicFiles.readOrSetAside(f, emptyList()) { listOf(it) })
        f.writeText("x")
        assertEquals(listOf("x"), AtomicFiles.readOrSetAside(f, emptyList()) { listOf(it) })
        assertTrue(f.exists())
    }
}
