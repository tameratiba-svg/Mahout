package com.mob8n.ai

import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import com.mob8n.core.asTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiCompatTest {
    private fun fixture(name: String): JsonObject = JSON.parseToJsonElement(javaClass.getResourceAsStream("/ai/$name.json")!!.bufferedReader().readText()) as JsonObject
    private val openai = Providers.byId("openai")!!
    private val minimax = Providers.byId("minimax")!!
    private val groq = Providers.byId("groq")!!
    private val deepseek = Providers.byId("deepseek")!!
    private val key = "sk-test-1234567890abcdef"
    private fun obj(e: Any?) = e as JsonObject
    private fun arr(e: Any?) = e as JsonArray
    private fun s(e: Any?) = (e as? JsonPrimitive)?.content

    // ---------------------------------------------------------------- toOpenAiMessages

    @Test fun systemFirstUserTextPlusImageBecomesParts() {
        val out = OpenAiCompat.toOpenAiMessages("sys", listOf(ClaudeClient.userMessage("hello", "QUJD"), ClaudeClient.userMessage("plain")))
        assertEquals(3, out.size)
        assertEquals("system", s(obj(out[0])["role"])); assertEquals("sys", s(obj(out[0])["content"]))
        val parts = arr(obj(out[1])["content"])
        assertEquals(listOf("image_url", "text"), parts.map { s(obj(it)["type"]) })
        assertEquals("data:image/jpeg;base64,QUJD", s(obj(obj(parts[0])["image_url"])["url"]))
        assertEquals("hello", s(obj(parts[1])["text"]))
        assertEquals("plain", s(obj(out[2])["content"]))                        // text-only user = plain string
    }

    @Test fun assistantToolUseBecomesToolCallsWithStringArguments() {
        val turn = Fakes.turn("tool_use", Fakes.textBlock("doing"), Fakes.toolUse("t1", "data_http", buildJsonObject { put("url", "https://a"); put("timeout", 30) }))
        val out = OpenAiCompat.toOpenAiMessages(null, listOf(turn.asMessage()))
        val a = obj(out[0])
        assertEquals("assistant", s(a["role"])); assertEquals("doing", s(a["content"]))
        val tc = obj(arr(a["tool_calls"])[0])
        assertEquals("t1", s(tc["id"])); assertEquals("function", s(tc["type"]))
        assertEquals("data_http", s(obj(tc["function"])["name"]))
        val args = obj(tc["function"])["arguments"] as JsonPrimitive
        assertTrue(args.isString)
        assertEquals(JsonPrimitive(30), obj(JSON.parseToJsonElement(args.content))["timeout"])
        // tool_use only -> content null
        val onlyTools = OpenAiCompat.toOpenAiMessages(null, listOf(Fakes.turn("tool_use", Fakes.toolUse("t2", "x", buildJsonObject {})).asMessage()))
        assertEquals(JsonNull, obj(onlyTools[0])["content"])
    }

    @Test fun toolResultsBecomeConsecutiveToolMessagesWithoutNameAndErrorPrefix() {
        val results = ClaudeClient.userMessage(listOf(
            ClaudeClient.toolResultBlock("t1", "[{\"ok\":true}]", false),
            ClaudeClient.toolResultBlock("t2", "connection refused", true),
            ClaudeClient.toolResultBlock("t3", "[{\"uri\":\"content://x\"}]", false, "QUJD"),
        ))
        val out = OpenAiCompat.toOpenAiMessages(null, listOf(results))
        assertEquals(4, out.size)
        assertEquals(listOf("tool", "tool", "tool", "user"), out.map { s(obj(it)["role"]) })
        assertEquals(listOf("t1", "t2", "t3"), out.take(3).map { s(obj(it)["tool_call_id"]) })
        assertEquals("[{\"ok\":true}]", s(obj(out[0])["content"]))
        assertEquals("ERROR: connection refused", s(obj(out[1])["content"]))
        assertTrue(s(obj(out[2])["content"])!!.endsWith("(screenshot attached in the next message)"))
        assertTrue(out.take(3).none { obj(it).containsKey("name") })
        val follow = arr(obj(out[3])["content"])
        assertEquals("Screenshot from tool call t3", s(obj(follow[0])["text"]))
        assertEquals("data:image/jpeg;base64,QUJD", s(obj(obj(follow[1])["image_url"])["url"]))
    }

    @Test fun rawProviderMessageIsEchoedVerbatimMinusNulls() {
        val turn = OpenAiCompat.parseResponse(minimax, fixture("minimax_think"), "MiniMax-M2.7")
        val msg = turn.asMessage()
        assertTrue(msg.containsKey(OpenAiCompat.RAW_KEY))
        val out = OpenAiCompat.toOpenAiMessages(null, listOf(ClaudeClient.userMessage("q"), msg))
        val a = obj(out[1])
        assertEquals("assistant", s(a["role"]))
        assertTrue(a.containsKey("reasoning_details"))                                   // MiniMax reasoning chain kept
        assertTrue(s(a["content"])!!.startsWith("<think>"))                                // provider content echoed verbatim
        assertFalse(a.containsKey("tool_calls"))                                           // null field dropped
        // the Claude-shaped view is clean
        assertEquals("{\"label\":\"work\"}", turn.text)
        assertEquals("MiniMax-M2.7", turn.model)
    }

    // ---------------------------------------------------------------- toolDef / buildBody

    @Test fun toolDefKeepsInputSchemaVerbatimStrictOnlyWhenAsked() {
        val def = Fakes.http.toolDef()
        val strict = OpenAiCompat.toolDefToOpenAi(def, true)
        val loose = OpenAiCompat.toolDefToOpenAi(def, false)
        assertEquals("function", s(strict["type"]))
        val f = obj(strict["function"])
        assertEquals("data_http", s(f["name"])); assertEquals(def["description"], f["description"])
        assertEquals(def["input_schema"], f["parameters"])
        assertEquals(JsonPrimitive(true), f["strict"])
        assertNull(obj(loose["function"])["strict"])
    }

    /** DESIGN3 §4.4: MCP defs carry strict:false and are never sent strict, even on OpenAI; their schema goes through verbatim. */
    @Test fun strictFalseDefIsNeverSentStrict() {
        val schema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) }); put("required", JsonArray(listOf(JsonPrimitive("q")))) }
        val def = buildJsonObject { put("name", "mcp__srv__search"); put("description", "[MCP srv] search"); put("strict", false); put("input_schema", schema) }
        val f = obj(OpenAiCompat.toolDefToOpenAi(def, true)["function"])
        assertNull(f["strict"]); assertEquals(schema, f["parameters"]); assertEquals("mcp__srv__search", s(f["name"]))
        val body = OpenAiCompat.buildBody(openai, "gpt-6-sol", 100, null, OpenAiCompat.toOpenAiMessages(null, listOf(ClaudeClient.userMessage("hi"))), listOf(def, Fakes.http.toolDef()), true, null, false, emptySet())
        val tools = arr(body["tools"])
        assertNull(obj(obj(tools[0])["function"])["strict"]); assertEquals(JsonPrimitive(true), obj(obj(tools[1])["function"])["strict"])
    }

    @Test fun bodyNeverCarriesForbiddenParamsAndPicksMaxTokensParam() {
        val msgs = OpenAiCompat.toOpenAiMessages("s", listOf(ClaudeClient.userMessage("hi")))
        val tools = listOf(Fakes.http.toolDef(), AgentNode.finishDef())
        val o = OpenAiCompat.buildBody(openai, "gpt-6-sol", 1000, 0.3, msgs, tools, true, null, false, emptySet())
        for (k in listOf("tool_choice", "n", "logprobs", "logit_bias", "parallel_tool_calls", "reasoning_effort", "max_tokens")) assertFalse(k, o.containsKey(k))
        assertEquals(JsonPrimitive(1000), o["max_completion_tokens"]); assertEquals(JsonPrimitive(false), o["stream"]); assertEquals(JsonPrimitive(0.3), o["temperature"])
        assertEquals(JsonPrimitive(true), obj(obj(arr(o["tools"])[0])["function"])["strict"])   // openai strictTools
        assertTrue(arr(o["messages"]).none { obj(it).containsKey("name") })

        val g = OpenAiCompat.buildBody(groq, "llama", 1000, null, msgs, tools, true, null, false, emptySet())
        assertEquals(JsonPrimitive(1000), g["max_tokens"]); assertFalse(g.containsKey("temperature"))
        assertNull(obj(obj(arr(g["tools"])[0])["function"])["strict"])                        // strict never sent where the table says no

        val m = OpenAiCompat.buildBody(minimax, "MiniMax-M2.7", 4096, 0.0, msgs, tools, true, null, false, emptySet())
        assertEquals(JsonPrimitive(4096), m["max_completion_tokens"]); assertEquals(JsonPrimitive(true), m["reasoning_split"])
        assertEquals(JsonPrimitive(0.01), m["temperature"])                                    // MiniMax temperature must be > 0
        assertFalse(m.containsKey("response_format"))
    }

    @Test fun structuredOutputTiersFollowTableFlagsAndQuirks() {
        val schema = Llm.objectSchema(mapOf("label" to buildJsonObject { put("type", "string") }))
        val msgs = OpenAiCompat.toOpenAiMessages(null, listOf(ClaudeClient.userMessage("classify")))
        val t1 = OpenAiCompat.buildBody(openai, "m", 100, null, msgs, emptyList(), false, schema, false, emptySet())
        val rf = obj(t1["response_format"])
        assertEquals("json_schema", s(rf["type"])); assertEquals(schema, obj(rf["json_schema"])["schema"]); assertEquals(JsonPrimitive(true), obj(rf["json_schema"])["strict"])
        assertEquals(1, OpenAiCompat.tier(openai, schema, false, emptySet()))
        // quirk downgrades one tier
        val t2 = OpenAiCompat.buildBody(openai, "m", 100, null, msgs, emptyList(), false, schema, false, setOf("no_json_schema"))
        assertEquals("json_object", s(obj(t2["response_format"])["type"]))
        assertEquals(2, OpenAiCompat.tier(deepseek, schema, false, emptySet()))                 // deepseek: no json_schema -> json_object
        assertEquals(3, OpenAiCompat.tier(minimax, schema, false, emptySet()))                  // minimax: prompt-only
        assertEquals(3, OpenAiCompat.tier(openai, schema, false, setOf("no_json_schema", "no_json_object")))
        assertEquals(0, OpenAiCompat.tier(openai, null, false, emptySet()))
        assertEquals(2, OpenAiCompat.tier(openai, null, true, emptySet()))                      // jsonMode without schema = json_object tier
        assertFalse(OpenAiCompat.buildBody(minimax, "m", 100, null, msgs, emptyList(), false, schema, false, emptySet()).containsKey("response_format"))
        // prompt suffix carries the key list and lands on the LAST user text block
        val suffix = OpenAiCompat.jsonSuffix(schema)
        assertTrue(suffix.contains("exactly these keys") && suffix.contains("\"label\": string"))
        val withS = OpenAiCompat.withSuffix(listOf(ClaudeClient.userMessage("classify", "QUJD")), suffix)
        val blocks = Fakes.blocks(withS[0])
        assertEquals("image", s(blocks[0]["type"])); assertTrue(s(blocks[1]["text"])!!.startsWith("classify\n\nRespond with ONLY a JSON object"))
        // quirk swaps the max tokens param and drops temperature/strict
        val q = OpenAiCompat.buildBody(openai, "m", 5, 0.5, msgs, listOf(Fakes.http.toolDef()), true, null, false, setOf("max_tokens_swap", "no_temperature", "no_strict"))
        assertEquals(JsonPrimitive(5), q["max_tokens"]); assertFalse(q.containsKey("max_completion_tokens")); assertFalse(q.containsKey("temperature"))
        assertNull(obj(obj(arr(q["tools"])[0])["function"])["strict"])
    }

    // ---------------------------------------------------------------- parseResponse

    @Test fun stopJoinsTextAndReportsModel() {
        val t = OpenAiCompat.parseResponse(openai, fixture("oai_stop"), "gpt-6-sol")
        assertEquals("end_turn", t.stopReason); assertEquals("Hello there", t.text); assertTrue(t.toolUses.isEmpty()); assertEquals("gpt-6-sol-2026", t.model)
    }

    @Test fun parallelToolCallsParseArgumentsAsJson() {
        val t = OpenAiCompat.parseResponse(openai, fixture("oai_tool_calls_parallel"), "m")
        assertEquals("tool_use", t.stopReason)
        val uses = t.toolUses
        assertEquals(listOf("call_a1", "call_b2"), uses.map { it.id })
        assertEquals(listOf("action_notify", "data_http"), uses.map { it.name })
        assertEquals(JsonPrimitive(30), uses[1].input["timeout"]); assertEquals(JsonPrimitive(true), uses[1].input["json"])
        assertEquals("https://example.com/a", uses[1].input["url"].asTextOrNull())
        assertEquals("", t.text)
    }

    @Test fun stopWithToolCallsIsToolUseAndLengthIsMaxTokens() {
        assertEquals("tool_use", OpenAiCompat.parseResponse(Providers.byId("gemini")!!, fixture("oai_stop_with_tool_calls"), "m").stopReason)
        val l = OpenAiCompat.parseResponse(openai, fixture("oai_length"), "m")
        assertEquals("max_tokens", l.stopReason); assertEquals(1, l.toolUses.size); assertTrue(l.text.startsWith("Here is the first part"))
    }

    @Test fun contentFilterAndRefusalThrow() {
        try { OpenAiCompat.parseResponse(openai, fixture("oai_content_filter"), "m"); fail() } catch (e: NodeException) { assertTrue(e.message!!.contains("content filter")) }
        try { OpenAiCompat.parseResponse(openai, fixture("oai_refusal"), "m"); fail() } catch (e: NodeException) { assertEquals("OpenAI declined: I can't help with that request.", e.message) }
    }

    @Test fun badArgumentsBecomeEmptyInputNeverDroppedAndObjectArgumentsAccepted() {
        val uses = OpenAiCompat.parseResponse(openai, fixture("oai_bad_arguments"), "m").toolUses
        assertEquals(3, uses.size)
        assertEquals(JsonObject(emptyMap()), uses[0].input)
        assertEquals("https://obj", uses[1].input["url"].asTextOrNull())
        assertEquals(JsonObject(emptyMap()), uses[2].input)
        // {} then flows into paramsFromToolInput -> "Invalid tool input" (Title is required)
        try { Fakes.notify.paramsFromToolInput(uses[0].input); fail() } catch (e: NodeException) { assertTrue(e.message!!.startsWith("Invalid tool input")) }
    }

    @Test fun missingIdsAreSynthesised() {
        assertEquals(listOf("call_1", "call_2"), OpenAiCompat.parseResponse(openai, fixture("oai_missing_ids"), "m").toolUses.map { it.id })
    }

    /** DESIGN4P P6: Turn.withUniqueToolIds — blank -> call_<n>, repeats -> <id>_2/_3, raw.tool_calls rewritten in lock-step, same instance when already unique. */
    @Test fun uniqueToolIdsAreEnforced() {
        fun tu(id: String) = buildJsonObject { put("type", "tool_use"); put("id", id); put("name", "run_shell"); put("input", buildJsonObject {}) }
        val raw = buildJsonObject { put("role", "assistant"); put("tool_calls", JsonArray(listOf("a", "a", "", "a").map { buildJsonObject { put("id", it); put("type", "function") } })) }
        val t = Turn("tool_use", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "hi") }, tu("a"), tu("a"), tu(""), tu("a"))), raw = raw)
        val u = t.withUniqueToolIds()
        assertEquals(listOf("a", "a_2", "call_3", "a_3"), u.toolUses.map { it.id })
        assertEquals(listOf("a", "a_2", "call_3", "a_3"), (u.raw!!["tool_calls"] as JsonArray).map { (it as JsonObject)["id"].asTextOrNull() })
        assertEquals("hi", u.text); assertEquals("tool_use", u.stopReason)
        val fine = Turn("tool_use", JsonArray(listOf(tu("x"), tu("y"))))
        assertTrue(fine === fine.withUniqueToolIds())
        assertEquals(listOf("call_1", "call_2"), Turn("tool_use", JsonArray(listOf(tu(""), tu("")))).withUniqueToolIds().toolUses.map { it.id })
        assertTrue(t.withUniqueToolIds().asMessage().toString().contains("a_3"))
    }

    @Test fun minimaxThinkStrippedAndBaseRespErrorOn200() {
        val t = OpenAiCompat.parseResponse(minimax, fixture("minimax_think"), "MiniMax-M2.7")
        assertEquals("{\"label\":\"work\"}", t.text); assertEquals("end_turn", t.stopReason)
        assertEquals("work", Llm.parseJsonObject(t.text)!!["label"].asTextOrNull())
        try { OpenAiCompat.parseResponse(minimax, fixture("minimax_base_resp_1008"), "MiniMax-M2.7"); fail() } catch (e: NodeException) {
            assertEquals("MiniMax: insufficient credits/balance — top up your account", e.message)
        }
        assertEquals("answer", OpenAiCompat.stripThink("<think>a\nb</think>\n answer "))
        assertEquals("x y", OpenAiCompat.stripThink("x <think>1</think>y"))
        assertEquals("Disabled it.", OpenAiCompat.stripThink("</think>\nDisabled it."))
        assertEquals("From notes", OpenAiCompat.stripThink("</mm:think>\nFrom notes"))
        assertEquals("", OpenAiCompat.stripThink("<think>cut off by max_tokens"))                  // truncated inline thinking is never an answer
        assertEquals("Use a <think> tag", OpenAiCompat.stripThink("Use a <think> tag"))              // a mid-text opener without a closer stays
    }

    // ---------------------------------------------------------------- M1: MiniMax reasoning_split setting

    @Test fun minimaxReasoningSplitIsASettingNotABuildConstant() {
        val msgs = OpenAiCompat.toOpenAiMessages(null, listOf(ClaudeClient.userMessage("hi")))
        fun body(p: Provider) = OpenAiCompat.buildBody(p, "MiniMax-M2.7", 100, null, msgs, emptyList(), false, null, false, emptySet())
        assertEquals(JsonPrimitive(false), body(Providers.withReasoningSplit(minimax, false))["reasoning_split"])
        assertEquals(JsonPrimitive(true), body(Providers.withReasoningSplit(minimax, true))["reasoning_split"])
        assertEquals(openai, Providers.withReasoningSplit(openai, false))                              // other providers untouched
        assertEquals(false, Providers.MINIMAX_SPLIT_DEFAULT)                                         // device A/B: inline <think> keeps the answer intact
        assertEquals(false, Providers.parseFlag(false, true)); assertEquals(false, Providers.parseFlag(" False ", true))
        assertEquals(true, Providers.parseFlag(null, true)); assertEquals(true, Providers.parseFlag("maybe", true)); assertEquals(true, Providers.parseFlag(1, true))
    }

    @Test fun minimaxInlineThinkNonStreamedStrippedForDisplayRawInTheEcho() {
        val body = JSON.parseToJsonElement("""{"model":"MiniMax-M2.7","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"<think>\nThe user wants a list.\n</think>\n\nHere are your 8 workflows."}}],
            "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30},"base_resp":{"status_code":0,"status_msg":""}}""") as JsonObject
        val t = OpenAiCompat.parseResponse(minimax, body, "MiniMax-M2.7")
        assertEquals("Here are your 8 workflows.", t.text)
        val echoed = obj(arr(OpenAiCompat.toOpenAiMessages(null, listOf(t.asMessage())))[0])
        assertEquals("<think>\nThe user wants a list.\n</think>\n\nHere are your 8 workflows.", s(echoed["content"]))   // MiniMax needs its chain back verbatim
        assertEquals(Usage.fromOpenAi(obj(body["usage"])), t.usage)
    }

    @Test fun minimaxGluedFirstTokenIsMovedBackIntoTheAnswer() {
        // Shapes captured on the device (2026-09-26, MiniMax-M3), both reasoning modes.
        fun split(reasoning: String, content: String) = JSON.parseToJsonElement(buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", 0); put("finish_reason", "stop")
                put("message", buildJsonObject {
                    put("role", "assistant"); put("content", content)
                    put("reasoning_details", JsonArray(listOf(buildJsonObject { put("type", "reasoning.text"); put("text", reasoning) })))
                })
            })))
        }.toString()) as JsonObject
        assertEquals("Two common reasons a workflow run fails.", OpenAiCompat.parseResponse(minimax, split("Keep it to two concise reasons.Two", " common reasons a workflow run fails."), "m").text)
        assertEquals("Let me check the recent run history.", OpenAiCompat.parseResponse(minimax, split("to get authoritative info.Let", " me check the recent run history."), "m").text)
        assertEquals("I don't have a \"Porch tile\" workflow.", OpenAiCompat.parseResponse(minimax, split("so I shouldn't guess.I", " don't have a \"Porch tile\" workflow."), "m").text)
        assertEquals("Torch tile\" is off.", OpenAiCompat.parseResponse(minimax, split("the information.\"T", "orch tile\" is off."), "m").text)   // word piece: no space
        assertEquals("Octopuses have three hearts.", OpenAiCompat.parseResponse(minimax, split("three hearts and blue blood.", "Octopuses have three hearts."), "m").text)   // normal: untouched
        val inline = JSON.parseToJsonElement(buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", 0); put("finish_reason", "stop")
                put("message", buildJsonObject { put("role", "assistant"); put("content", "<think>\nPosts a notification. One sentence.Every\n</think>\n\nday at 08:00 it pulls your calendar.") })
            })))
        }.toString()) as JsonObject
        val t = OpenAiCompat.parseResponse(minimax, inline, "m")
        assertEquals("Every day at 08:00 it pulls your calendar.", t.text)
        assertTrue(s(obj(arr(OpenAiCompat.toOpenAiMessages(null, listOf(t.asMessage())))[0])["content"])!!.contains("sentence.Every\n</think>"))   // echo stays verbatim
        // the reasoning's own final period after </think> before a tool call is not an answer
        assertEquals("", OpenAiCompat.repairGluedAnswer("I'll use the list_workflows tool", "."))
        assertEquals("lowercase start, reasoning ends normally.", OpenAiCompat.repairGluedAnswer("Done thinking.", "lowercase start, reasoning ends normally."))
        assertEquals("iPhone works.", OpenAiCompat.repairGluedAnswer("", "iPhone works."))
        // split form as captured on the tablet (v6.1): MiniMax drops the content's leading space, so length decides
        assertEquals("You have 11 workflows.", OpenAiCompat.repairGluedAnswer("present them concisely.You", "have 11 workflows."))
        assertEquals("Torch tile is off.", OpenAiCompat.repairGluedAnswer("the information.T", "orch tile is off."))
        assertEquals("I'm on it.", OpenAiCompat.repairGluedAnswer("Answer briefly.I", "'m on it."))
        assertEquals("I don't know.", OpenAiCompat.repairGluedAnswer("so I shouldn't guess.I", " don't know."))
        // a quoted name closing the reasoning is not a glued fragment
        assertEquals("e.g. enable it.", OpenAiCompat.repairGluedAnswer("I'll call it 'Backup'", "e.g. enable it."))
        assertEquals("the same", OpenAiCompat.parseResponse(openai, split("x.Two", "the same"), "m").text)          // MiniMax only
    }

    @Test fun minimaxDebugLineCarriesTheModeAndFirst40VisibleCharsOnly() {
        val b = buildJsonObject { put("reasoning_split", false) }
        assertEquals("minimax split=false first40=Here are your 8 workflows:\\n1. Morning di", OpenAiCompat.minimaxDebugLine(b, "Here are your 8 workflows:\n1. Morning digest and more text"))
        assertEquals("minimax split=unset first40=", OpenAiCompat.minimaxDebugLine(JsonObject(emptyMap()), ""))
    }

    // ---------------------------------------------------------------- errors

    @Test fun errorMessagesAreMappedAndNeverContainTheKey() {
        val body = "{\"error\":{\"message\":\"Incorrect API key provided: $key\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"
        assertEquals("Invalid OpenAI API key (Settings > AI)", OpenAiCompat.errorMessage(openai, 401, body, "m", key))
        val m400 = OpenAiCompat.errorMessage(openai, 400, body, "m", key)
        assertTrue(m400.startsWith("OpenAI rejected the request: Incorrect API key provided: ***")); assertFalse(m400.contains(key))
        assertEquals("OpenAI: insufficient credits/balance — top up your account", OpenAiCompat.errorMessage(openai, 402, "{}", "m", key))
        assertEquals("Model not found on Groq: llama-9 (use Fetch models, check base URL)", OpenAiCompat.errorMessage(groq, 404, "", "llama-9", key))
        assertEquals("Groq: rate limited, retry later", OpenAiCompat.errorMessage(groq, 429, "{\"error\":{\"message\":\"slow down\"}}", "m", key))
        assertEquals("OpenAI error 500: boom", OpenAiCompat.errorMessage(openai, 500, "{\"error\":{\"message\":\"boom\"}}", "m", key))
        assertEquals("OpenRouter error 503: no provider available for m", OpenAiCompat.errorMessage(Providers.byId("openrouter")!!, 503, "", "m", key))
        assertEquals("MiniMax: rate limited, retry later", OpenAiCompat.errorMessage(minimax, 200, "{\"base_resp\":{\"status_code\":1002,\"status_msg\":\"rate limit\"}}", "m", key))
        assertEquals("Invalid MiniMax API key (Settings > AI)", OpenAiCompat.errorMessage(minimax, 401, "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"login fail\"}}", "m", key))
        assertEquals("vLLM detail", OpenAiCompat.bodyMessage("{\"detail\":\"vLLM detail\"}"))
        assertEquals("plain text", OpenAiCompat.bodyMessage("plain text"))
    }

    @Test fun cleanMasksEveryKeyShape() {
        assertEquals("Authorization: *** failed", OpenAiCompat.clean("Authorization: Bearer sk-abcdefgh12345 failed", null))
        assertEquals("k=***", OpenAiCompat.clean("k=$key", key))
        assertEquals("*** *** *** *** ***", OpenAiCompat.clean("sk-or-v1-abc gsk_abc xai-abc AIzaSyabc sk-ant-api03-xyz", null))
        assertEquals(300, OpenAiCompat.clean("x".repeat(1000), null).length)
        assertEquals("", OpenAiCompat.clean(null, key))
    }

    @Test fun quirkFor400MapsOnlySentFeatures() {
        assertEquals("no_strict", OpenAiCompat.quirkFor400("{\"error\":{\"message\":\"'strict' is not supported\"}}", setOf("strict", "json_schema")))
        assertEquals("no_json_schema", OpenAiCompat.quirkFor400("response_format json_schema unsupported", setOf("json_schema")))
        assertEquals("no_json_object", OpenAiCompat.quirkFor400("response_format is not supported", setOf("json_object")))
        assertNull(OpenAiCompat.quirkFor400("response_format is not supported", emptySet()))
        assertEquals("max_tokens_swap", OpenAiCompat.quirkFor400("Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.", setOf("max_tokens")))
        assertEquals("no_temperature", OpenAiCompat.quirkFor400("temperature does not support 0.2 with this model", setOf("temperature")))
        assertNull(OpenAiCompat.quirkFor400("invalid api key", setOf("strict", "temperature")))
        val body = OpenAiCompat.buildBody(openai, "m", 10, 0.1, JsonArray(emptyList()), listOf(Fakes.http.toolDef()), true, null, true, emptySet())
        assertEquals(setOf("strict", "json_object", "max_completion_tokens", "temperature"), OpenAiCompat.sentFeatures(body))
    }
}
