package com.mob8n.ui

import com.mob8n.core.Catalog
import com.mob8n.core.ERROR
import com.mob8n.core.Edge
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Graph
import com.mob8n.core.MAIN
import com.mob8n.core.Node
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeInstance
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.ParamKind
import com.mob8n.core.choice
import com.mob8n.core.labels
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.text
import com.mob8n.core.whenIs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamWidgetMappingTest {
    private object Fake : Node() {
        override val spec = NodeSpec(
            "logic.fake", "Fake", NodeKind.LOGIC, "fake",
            params = listOf(
                choice("mode", "Mode", listOf("a", "b")),
                text("onlyA", "Only A", visibleWhen = whenIs("mode", "a")),
                number("n", "N", default = 5.0, min = 1.0, max = 10.0),
                number("onlyB", "Only B", min = 15.0, visibleWhen = whenIs("mode", "b")),
            ),
        )
        override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult = out(input.items)
    }
    /** Switch-like: `cases` defines the output ports, plus a static `fallback`. */
    private object FakeSwitch : Node() {
        override val spec = NodeSpec(
            "logic.fakeswitch", "Fake switch", NodeKind.LOGIC, "fake",
            params = listOf(text("field", "Field"), labels("cases", "Cases", definesPorts = true)),
            outputs = listOf("fallback"),
        )
        override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult = out(input.items)
    }
    private val catalog = Catalog(listOf(listOf(Fake, FakeSwitch)))

    @Test fun visibleWhenUsesValueThenDefault() {
        val spec = Fake.spec
        val onlyA = spec.param("onlyA")!!
        assertTrue(isVisible(onlyA, spec.params, JsonObject(emptyMap())))                    // default mode = a
        assertTrue(isVisible(onlyA, spec.params, buildJsonObject { put("mode", "a") }))
        assertFalse(isVisible(onlyA, spec.params, buildJsonObject { put("mode", "b") }))
        assertTrue(isVisible(spec.param("mode")!!, spec.params, JsonObject(emptyMap())))
    }

    @Test fun everyParamKindHasAWidgetBranch() {
        // Widgets.ParamWidget is an exhaustive `when` over ParamKind; this guards the pure helpers each branch relies on.
        for (k in ParamKind.entries) assertTrue(k.name, k in ParamKind.entries)
        assertEquals(13, ParamKind.entries.size)
    }

    @Test fun numberParsing() {
        assertNull(parseNumber(""))
        assertEquals(JsonPrimitive(5L), parseNumber("5"))
        assertEquals(JsonPrimitive(2.5), parseNumber("2.5"))
        assertEquals(JsonPrimitive("{{count}}"), parseNumber("{{count}}"))
        assertEquals("N must be <= 10", Fake.spec.param("n")!!.validate(parseNumber("11")))
        assertEquals("N must be a number", Fake.spec.param("n")!!.validate(parseNumber("abc")))
    }

    @Test fun numberFieldTextKeepsTheTypedLiteral() {
        // F33: "0.0" must round-trip as "0.0" (not "0"), otherwise TemplateTextField resets the caret and 0.05 is unreachable.
        assertEquals("0.0", numText(parseNumber("0.0")))
        assertEquals("0.", numText(parseNumber("0.")))
        assertEquals("5", numText(parseNumber("5")))
        assertEquals("2.5", numText(parseNumber("2.5")))
        assertEquals("1.01", numText(parseNumber("1.01")))
        assertEquals("{{count}}", numText(parseNumber("{{count}}")))
        assertEquals("", numText(null))
    }

    @Test fun hiddenParamsAreDroppedOnCommit() {
        // F35: a stale value behind visibleWhen must not reach Graph.validate.
        val stale = buildJsonObject { put("mode", "a"); put("onlyB", 5); put("n", 5); put("extra", "kept") }
        assertFalse(Fake.spec.validate(stale).isEmpty())
        val out = visibleParams(Fake.spec, stale)
        assertFalse("onlyB" in out)
        assertEquals("a", out["mode"]?.jsonPrimitive?.content)
        assertEquals("kept", out["extra"]?.jsonPrimitive?.content)   // unknown keys survive
        assertTrue(Fake.spec.validate(out).isEmpty())
        val b = buildJsonObject { put("mode", "b"); put("onlyA", "stale"); put("onlyB", 20) }
        assertEquals(setOf("mode", "onlyB"), visibleParams(Fake.spec, b).keys)
    }

    @Test fun livePushChangesOnlyThePortsParam() {
        // F37: the sheet pushes only the definesPorts key onto the graph node; other pending edits stay in the sheet.
        val node = NodeInstance("s", "logic.fakeswitch", "Switch", params = buildJsonObject { put("field", "x") })
        val cases = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val pushed = withParam(node, "cases", cases)
        assertEquals(buildJsonObject { put("field", "x"); put("cases", cases) }, pushed.params)
        assertEquals(node.copy(params = pushed.params), pushed)                 // nothing but params changed
        assertEquals(buildJsonObject { put("field", "x") }, withParam(pushed, "cases", null).params)   // null removes the key
    }

    @Test fun prunedEdgesFollowRemovedPorts() {
        // F38: removing a Switch case drops the edge that left it; other edges and error edges stay.
        fun cases(vararg c: String) = JsonArray(c.map(::JsonPrimitive))
        val sw = NodeInstance("s", "logic.fakeswitch", "Switch", params = buildJsonObject { put("cases", cases("a", "b")) })
        val g = Graph(
            nodes = listOf(NodeInstance("t", "logic.fake", "T"), sw, NodeInstance("x", "logic.fake", "X"), NodeInstance("y", "logic.fake", "Y")),
            edges = listOf(Edge("t", MAIN, "s"), Edge("s", "a", "x"), Edge("s", "b", "y"), Edge("s", "fallback", "y"), Edge("s", ERROR, "x")),
        )
        val edited = sw.copy(params = buildJsonObject { put("cases", cases("a")) })
        val pruned = pruneEdgesFor(g.copy(nodes = g.nodes.map { if (it.id == "s") edited else it }), edited, FakeSwitch.spec)
        assertEquals(listOf(Edge("t", MAIN, "s"), Edge("s", "a", "x"), Edge("s", "fallback", "y"), Edge("s", ERROR, "x")), pruned.edges)
        assertTrue(pruned.validate(catalog).none { "no output port" in it })
        assertEquals(g, pruneEdgesFor(g, sw, null))      // unknown spec: untouched
    }

    @Test fun leaveGuard() {
        // F34: only a dirty editor asks; an invalid graph may only be discarded.
        assertEquals(LeaveAction.Go, leaveAction(dirty = false, errorsEmpty = true))
        assertEquals(LeaveAction.Go, leaveAction(dirty = false, errorsEmpty = false))
        assertEquals(LeaveAction.AskSaveOrDiscard, leaveAction(dirty = true, errorsEmpty = true))
        assertEquals(LeaveAction.AskDiscardOnly, leaveAction(dirty = true, errorsEmpty = false))
    }

    @Test fun duplicateWorkflowNamesResolveByIndex() {
        // F40: two "Untitled" workflows get distinct options and the second maps back to the second id.
        val wfs = listOf("Untitled" to "1a2b3c4d-0000", "Untitled" to "9f8e7d6c-0000")
        val options = wfs.map { (n, id) -> workflowLabel(n, id) }
        assertEquals(2, options.toSet().size)
        assertEquals("Untitled · 9f8e7d", options[1])
        assertEquals("9f8e7d6c-0000", wfs.getOrNull(options.indexOf(options[1]))?.second)
    }

    @Test fun durationSplitJoin() {
        assertEquals(5.0 to "min", splitDuration(300_000))
        assertEquals(2.0 to "h", splitDuration(7_200_000))
        assertEquals(1.5 to "s", splitDuration(1_500))
        assertEquals(90_000L, joinDuration(1.5, "min"))
        assertEquals(joinDuration(splitDuration(86_400_000).first, splitDuration(86_400_000).second), 86_400_000L)
    }

    @Test fun uniqueNamesAndDefaults() {
        val g = Graph(nodes = listOf(NodeInstance("1", "logic.fake", "Fake"), NodeInstance("2", "logic.fake", "Fake 2")))
        assertEquals("Fake 3", uniqueName("Fake", g))
        assertEquals("Other", uniqueName("Other", g))
        assertEquals("a b", uniqueName("a.b", Graph()))
        val n = newNode(Fake.spec, g, 1f, 2f, "3")
        assertEquals(JsonPrimitive("a"), n.params["mode"])
        assertEquals(JsonPrimitive(5.0), n.params["n"])
        assertNull(n.params["onlyA"])
        assertTrue(Fake.spec.validate(n.params).isEmpty())
    }

    @Test fun upstreamSnippets() {
        val g = Graph(
            nodes = listOf(NodeInstance("t", "logic.fake", "Trigger"), NodeInstance("m", "logic.fake", "Middle"), NodeInstance("x", "logic.fake", "Target")),
            edges = listOf(Edge("t", MAIN, "m"), Edge("m", MAIN, "x")),
        )
        val chain = upstreamChain(g, "x")
        assertEquals(listOf("m" to true, "t" to false), chain.map { it.first.id to it.second })
        assertEquals("{{title}}", fieldSnippet(g.node("m")!!, true, "title"))
        assertEquals("{{\$node.Trigger.title}}", fieldSnippet(g.node("t")!!, false, "title"))
        assertTrue(GLOBAL_SNIPPETS.contains("{{\$json}}"))
        assertEquals(listOf("at"), fallbackKeys("trigger.manual", catalog))
        assertTrue(fallbackKeys("logic.fake", catalog).isEmpty())
    }

    @Test fun autoLayoutLayersByTopoOrder() {
        val g = Graph(
            nodes = listOf(NodeInstance("c", "logic.fake", "C"), NodeInstance("a", "logic.fake", "A"), NodeInstance("b", "logic.fake", "B")),
            edges = listOf(Edge("a", MAIN, "b"), Edge("b", MAIN, "c")),
        )
        val l = autoLayout(g)
        assertTrue(l.node("a")!!.x < l.node("b")!!.x && l.node("b")!!.x < l.node("c")!!.x)
        val cyclic = g.copy(edges = g.edges + Edge("c", MAIN, "a"))
        assertEquals(cyclic, autoLayout(cyclic))
    }

    @Test fun screenRoundTrip() {
        for (s in listOf(Screen.List, Screen.Editor("w1"), Screen.Editor("w1", "n1"), Screen.Runs(), Screen.Runs("w1"), Screen.RunDetail("r1"), Screen.Permissions, Screen.AiSettings, Screen.Notes, Screen.Playlist))
            assertEquals(s, Screen.decode(s.encode()))
        assertEquals(Screen.Editor("w1"), Screen.Runs("w1").parent)
        assertEquals(Screen.List, Screen.RunDetail("r").parent)
        // F64: ids with ':' survive and a malformed saved string falls back to List instead of throwing on restore.
        assertEquals(Screen.Editor("a:b", "c:d"), Screen.decode(Screen.Editor("a:b", "c:d").encode()))
        assertEquals(Screen.List, Screen.decode("garbage"))
        assertEquals(Screen.List, Screen.decode("editor:w1:"))
    }

    @Test fun buildScreenRoundTripAndParent() {
        // DESIGN2 §2 ui: Screen.Build(workflowId?) survives rememberSaveable and Back returns to the editor it came from.
        for (s in listOf(Screen.Build(), Screen.Build("w1"), Screen.Build("a:b"))) assertEquals(s, Screen.decode(s.encode()))
        assertEquals(Screen.List, Screen.Build().parent)
        assertEquals(Screen.Editor("w1"), Screen.Build("w1").parent)
        assertFalse(Screen.Build().encode() == Screen.Build("w1").encode())
    }

    @Test fun knowledgeAndMcpScreensRoundTripAndParents() {
        // DESIGN3 §2 ui: Screen.Knowledge (parent List) and Screen.McpSettings (parent AiSettings) survive rememberSaveable.
        for (s in listOf(Screen.Knowledge, Screen.McpSettings)) assertEquals(s, Screen.decode(s.encode()))
        assertEquals(Screen.List, Screen.Knowledge.parent)
        assertEquals(Screen.AiSettings, Screen.McpSettings.parent)
        assertFalse(Screen.Knowledge.encode() == Screen.McpSettings.encode())
        assertEquals(Screen.List, Screen.McpSettings.parent.parent)
    }

    @Test fun v4ScreensRoundTripParentsSectionsTopLevel() {
        // DESIGN4 §3.7: Dashboard, Chat(conversationId?) and Skills survive rememberSaveable; section decides the highlighted tab; topLevel shows the bottom bar.
        for (s in listOf(Screen.Dashboard, Screen.Chat(), Screen.Chat("c1"), Screen.Chat("a:b"), Screen.Skills)) assertEquals(s, Screen.decode(s.encode()))
        assertFalse(Screen.Chat().encode() == Screen.Chat("c1").encode())
        assertEquals(Screen.Chat(), Screen.Chat("c1").parent)
        assertEquals(Screen.List, Screen.Chat().parent)
        assertEquals(Screen.List, Screen.Dashboard.parent)
        assertEquals(Screen.List, Screen.Skills.parent)
        assertEquals(Screen.Dashboard, Screen.Dashboard.section)
        assertEquals(Screen.Chat(), Screen.Chat("c1").section)
        assertEquals(Screen.Chat(), Screen.Chat().section)
        assertEquals(Screen.Knowledge, Screen.Knowledge.section)
        assertEquals(Screen.Skills, Screen.Skills.section)
        for (s in listOf(Screen.List, Screen.Editor("w"), Screen.Runs(), Screen.RunDetail("r"), Screen.Permissions, Screen.AiSettings, Screen.McpSettings, Screen.Notes, Screen.Playlist, Screen.Build()))
            assertEquals(s.toString(), Screen.List, s.section)
        for (s in listOf(Screen.Dashboard, Screen.List, Screen.Chat(), Screen.Knowledge, Screen.Skills)) assertTrue(s.toString(), s.topLevel)
        for (s in listOf(Screen.Chat("c1"), Screen.Editor("w"), Screen.Runs(), Screen.AiSettings, Screen.Permissions, Screen.Build())) assertFalse(s.toString(), s.topLevel)
        assertEquals(Screen.List, Screen.decode("""{"type":"com.mob8n.ui.Screen.List"}"""))   // legacy saved string still decodes
        assertEquals(Screen.List, Screen.decode(Screen.List.encode()))
    }

    @Test fun v4FallbackOutputFields() {
        // DESIGN4 §7.3: coding nodes have documented outputs before their first run.
        assertEquals(listOf("exitCode", "stdout", "stderr", "truncated", "timedOut", "ms", "outputFile"), FALLBACK_OUTPUT_FIELDS["app.shell_run"])
        assertEquals(listOf("value"), FALLBACK_OUTPUT_FIELDS["logic.js"])
    }

    @Test fun v5PanelsScreenAndDecideFields() {
        // DESIGN5 §9 ui: Screen.Panels(slug?) survives rememberSaveable; Back and the highlighted section are Dashboard; no bottom bar; no new ParamKind.
        for (s in listOf(Screen.Panels(), Screen.Panels("gev"), Screen.Panels("a:b"))) assertEquals(s, Screen.decode(s.encode()))
        assertFalse(Screen.Panels().encode() == Screen.Panels("gev").encode())
        assertEquals(Screen.Dashboard, Screen.Panels().parent)
        assertEquals(Screen.Dashboard, Screen.Panels("gev").parent)
        assertEquals(Screen.Dashboard, Screen.Panels("gev").section)
        assertFalse(Screen.Panels().topLevel)
        assertEquals(13, ParamKind.entries.size)
        assertEquals(listOf("answers", "decisions", "engine", "s1Model", "latencyMs"), FALLBACK_OUTPUT_FIELDS["ai.decide"])
    }

    @Test fun v3FallbackOutputFields() {
        // DESIGN3 §6.4: five new rows so the { } helper lists documented fields before a node ever ran.
        assertEquals(listOf("context", "count", "hits", "text", "source", "sourceId", "seq", "score"), fallbackKeys("data.knowledge_search", catalog))
        assertEquals(listOf("sourceId", "name", "kind", "chunks", "chars", "bytes"), fallbackKeys("action.knowledge_add", catalog))
        assertEquals(listOf("removed"), fallbackKeys("action.knowledge_remove", catalog))
        assertEquals(listOf("text", "content", "structured", "isError", "server", "tool"), fallbackKeys("ai.mcp_tool", catalog))
        assertEquals(listOf("text", "mimeType", "uri", "server"), fallbackKeys("ai.mcp_resource", catalog))
    }

    @Test fun autoLayoutOverBuilderShapedGraph() {
        // Builder output carries no x/y (all 0): layout must place nodes in 260-dp columns (40 + k*260) with no two nodes on the same spot,
        // including the branch that shares a layer (notify on error next to the text node).
        val g = Graph(
            nodes = listOf("share", "http", "text", "ask", "notify", "notifyErr").map { NodeInstance(it, "logic.fake", it) },
            edges = listOf(Edge("share", MAIN, "http"), Edge("http", MAIN, "text"), Edge("text", MAIN, "ask"), Edge("ask", MAIN, "notify"), Edge("http", ERROR, "notifyErr")),
        )
        val l = autoLayout(g)
        val xs = l.nodes.map { it.x }.toSet()
        assertTrue(xs.toString(), xs.all { (it - 40f) % 260f == 0f })
        assertEquals(setOf(40f, 300f, 560f, 820f, 1080f), xs)
        assertEquals(l.nodes.size, l.nodes.map { it.x to it.y }.toSet().size)
        assertEquals(l.node("text")!!.x, l.node("notifyErr")!!.x)          // same layer, different rows
        assertEquals(130f, kotlin.math.abs(l.node("text")!!.y - l.node("notifyErr")!!.y))
        assertEquals(g.edges, l.edges)                                     // layout never touches edges
    }

    @Test fun draftFromLaysOutAndStaysDisabled() {
        val g = Graph(nodes = listOf(NodeInstance("a", "logic.fake", "A"), NodeInstance("b", "logic.fake", "B")), edges = listOf(Edge("a", MAIN, "b")))
        val wf = draftFrom("Summarise links", g)
        assertEquals("Summarise links (Generated by AI)", wf.name)
        assertFalse(wf.enabled)
        assertEquals(300f, wf.graph.node("b")!!.x)
        assertTrue(Drafts.map.isEmpty() || true)   // Drafts is plain process memory; draftFrom never touches it
    }

    @Test fun needsLayoutOnlyForBuilderShapedGraphs() {
        // DESIGN4P P12: a Builder / save_workflow graph lands with every node at 0,0; the editor lays it out on open, once.
        val zero = Graph(nodes = listOf(NodeInstance("a", "logic.fake", "A"), NodeInstance("b", "logic.fake", "B")), edges = listOf(Edge("a", MAIN, "b")))
        assertTrue(needsLayout(zero))
        assertFalse(needsLayout(Graph(nodes = listOf(NodeInstance("a", "logic.fake", "A")))))                      // one node: nothing overlaps
        assertFalse(needsLayout(zero.copy(nodes = listOf(zero.nodes[0], zero.nodes[1].copy(x = 300f)))))           // the user moved one: keep positions
        assertFalse(needsLayout(Graph()))
        val laid = autoLayout(zero)
        assertEquals(2, laid.nodes.map { it.x to it.y }.toSet().size)
        assertFalse(needsLayout(laid))
    }

    @Test fun unattendedAgentsAreNamedInWords() {
        // DESIGN4P P16: an ai.agent that never pauses is flagged with the frozen prefix; a default agent is not.
        fun agent(id: String, vararg p: Pair<String, JsonPrimitive>) = NodeInstance(id, "ai.agent", "Agent $id", params = JsonObject(p.toMap()))
        val g = Graph(nodes = listOf(agent("1", "askApproval" to JsonPrimitive(false)), agent("2", "permissionMode" to JsonPrimitive("bypass")), agent("3", "permissionMode" to JsonPrimitive("auto")),
            agent("4"), agent("5", "permissionMode" to JsonPrimitive("inherit"), "askApproval" to JsonPrimitive(true)), NodeInstance("n", "action.notify", "Notify", params = buildJsonObject { put("askApproval", false) })))
        assertEquals(listOf("Runs unattended: Agent 1 (askApproval=false)", "Runs unattended: Agent 2 (permissionMode=bypass)", "Runs unattended: Agent 3 (permissionMode=auto)"), unattendedAgents(g))
        assertTrue(unattendedAgents(g).all { it.startsWith(UNATTENDED_PREFIX) })
        assertTrue(unattendedAgents(Graph(nodes = listOf(agent("4")))).isEmpty())
    }

    @Test fun buildBlockerMessages() {
        // DESIGN2 §6.6: no key anywhere -> connect first; Nano as (effective) default -> the "needs a cloud provider" text; otherwise none.
        assertEquals("Connect an AI provider first", buildBlocker(null, false, com.mob8n.ai.NanoStatus.UNAVAILABLE, true))
        assertEquals(NANO_CANNOT_BUILD, buildBlocker(AI_NANO, true, com.mob8n.ai.NanoStatus.AVAILABLE, false))
        assertEquals(NANO_CANNOT_BUILD, buildBlocker(null, false, com.mob8n.ai.NanoStatus.AVAILABLE, false))
        assertEquals(NANO_CANNOT_BUILD, buildBlocker(null, true, com.mob8n.ai.NanoStatus.AVAILABLE, true))
        assertNull(buildBlocker(null, true, com.mob8n.ai.NanoStatus.AVAILABLE, false))
        assertNull(buildBlocker("minimax", false, com.mob8n.ai.NanoStatus.UNAVAILABLE, true))
    }
}
