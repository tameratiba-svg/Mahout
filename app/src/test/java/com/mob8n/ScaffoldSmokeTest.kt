package com.mob8n

import com.mob8n.core.Scope
import com.mob8n.core.Template
import com.mob8n.core.item
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Proves testDebugUnitTest runs and the core package loads on the JVM without Android. Lanes own their real tests. */
class ScaffoldSmokeTest {
    @Test fun templateRendersItemField() {
        val scope = Scope(item("title" to "Song", "artist" to "Band"), 0, 1, emptyMap(), emptyMap(), 0L, ZoneId.of("UTC"), "run", "wf")
        assertEquals("Song by Band", Template.render("{{title}} by {{artist ?? \"?\"}}", scope))
        assertEquals("2", Template.render("{{missing ?? 2}}", scope))
    }
}
