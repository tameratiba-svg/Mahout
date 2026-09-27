package com.mob8n.ai

import com.mob8n.core.JSON
import com.mob8n.core.NodeException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DESIGN5 §4 wire protocol against the fixtures in resources/s1 (laya_request/laya_reply = the REAL laya-serve 0.3.20 capture). */
class SystemOneTest {
    private fun text(name: String): String = javaClass.getResourceAsStream("/s1/$name")!!.bufferedReader().readText()
    private fun obj(name: String): JsonObject = JSON.parseToJsonElement(text(name)) as JsonObject

    private val dept = S1Question.Choice("dept", "Which team should handle this?", linkedMapOf("billing" to "refunds, invoices", "tech" to "bugs, outages"))
    private val urgency = S1Question.Score("urgency", "How urgent is this?", listOf("low", "medium", "high", "critical"))
    private val spam = S1Question.Noul("spam", "Is this message spam?", "unsolicited bulk", "a real request")
    private val three = listOf(dept, urgency, spam)
    private val jev = S1Target("jev", SystemOne.JEV_URL, "sk-jevFAKEKEY1234567890", SystemOne.JEV_MODEL)
    private val laya = S1Target("laya", "http://10.0.0.61:8000/v1/systemone", null, null)

    /** The real capture's three questions, rebuilt from the criteria grammar. */
    private val layaQs = listOf(
        SystemOne.parseCriteria("choice", "billing: invoices, payments, refunds\ntechnical: bugs, outages\nother: everything else", "department", "Which department should handle this?"),
        SystemOne.parseCriteria("score", "not urgent, soon, critical", "urgency", "How urgent is this?"),
        SystemOne.parseCriteria("noul", "", "churn_risk", "Does the user threaten to cancel or leave?"),
    )

    private fun enc(o: JsonObject) = JSON.encodeToString(JsonObject.serializer(), o)

    @Test fun requestIsByteEqualToTheFixtureAndModelIsOmittedForLaya() {
        val state = JsonPrimitive("I was billed twice, refund or I cancel today")
        assertEquals(text("request_3q.json"), enc(SystemOne.request(state, three, SystemOne.JEV_MODEL)))
        val noModel = SystemOne.request(state, three, null)
        assertFalse(noModel.containsKey("model"))
        assertEquals(listOf("state", "questions"), noModel.keys.toList())
        // object and array states pass through as JSON, never stringified
        val objState = buildJsonObject { put("text", "hi") }
        assertEquals(objState, SystemOne.request(objState, three, null)["state"])
        val arrState = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        assertEquals(arrState, SystemOne.request(arrState, three, null)["state"])
        // the real Laya request: object state, no model, noul without criteria
        val real = obj("laya_request.json")
        assertEquals(real, SystemOne.request(real["state"]!!, layaQs, null))
    }

    @Test fun criteriaShapesAndBlankDescriptionRepeatsLabel() {
        val q = SystemOne.parseCriteria("choice", "urgent\nlater: can wait", "label", "x")
        assertEquals(buildJsonObject { put("urgent", "urgent"); put("later", "can wait") }, q.toJson()["criteria"])
        assertNull(S1Question.Noul("n", "x").toJson()["criteria"])
        assertEquals(JsonArray(listOf(JsonPrimitive("low"), JsonPrimitive("high"))), SystemOne.parseCriteria("score", "low\nhigh", "s", "x").toJson()["criteria"])
    }

    @Test fun validateRejectsBadNamesAndCounts() {
        assertNull(SystemOne.validate(three))
        assertNotNull(SystemOne.validate(emptyList()))
        assertTrue(SystemOne.validate(listOf(dept, dept))!!.contains("duplicate"))
        assertNotNull(SystemOne.validate(listOf(S1Question.Noul("", "x"))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Noul("1abc", "x"))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Noul("has space", "x"))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Noul("a".repeat(65), "x"))))
        assertNull(SystemOne.validate(listOf(S1Question.Noul("_a".repeat(32), "x"))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Noul("n", " "))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Choice("c", "x", linkedMapOf("a" to "")))))
        assertNull(SystemOne.validate(listOf(S1Question.Choice("c", "x", LinkedHashMap((1..100).associate { "o$it" to "" })))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Choice("c", "x", LinkedHashMap((1..101).associate { "o$it" to "" })))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Score("s", "x", listOf("one")))))
        assertNull(SystemOne.validate(listOf(S1Question.Score("s", "x", (1..10).map { "l$it" }))))
        assertNotNull(SystemOne.validate(listOf(S1Question.Score("s", "x", (1..11).map { "l$it" }))))
        assertNull(SystemOne.validate((1..64).map { S1Question.Noul("q$it", "x") }))
        assertNotNull(SystemOne.validate((1..65).map { S1Question.Noul("q$it", "x") }))
    }

    /** The binding shape: real laya-serve 0.3.20 reply (model laya-rl-agent, routing english, score = expected value 1.4035). */
    @Test fun parsesTheRealLayaReply() {
        val r = SystemOne.parse(obj("laya_reply.json"), layaQs, "laya", 123)
        assertEquals("laya-rl-agent", r.model); assertEquals("english", r.displayModel); assertEquals("english", r.routing!!["model"]!!.let { (it as JsonPrimitive).content })
        assertEquals(TokenUsage(158, 0), r.usage); assertEquals(123L, r.latencyMs); assertEquals("laya", r.engine)
        val c = r.answers["department"] as S1Answer.Choice
        assertEquals("billing", c.choice); assertEquals(0.9862, c.confidence!!, 1e-9)
        assertEquals(mapOf("billing" to 0.9981, "technical" to 0.0009, "other" to 0.001), c.probabilities)
        val s = r.answers["urgency"] as S1Answer.Score
        assertEquals(1.4035, s.score, 1e-9); assertEquals(1, s.level); assertEquals("soon", s.levelLabel)   // rounded expected level, label from legend
        assertEquals(0.4787, s.probabilities["2"]!!, 1e-9); assertEquals(0.1743, s.confidence!!, 1e-9)
        val n = r.answers["churn_risk"] as S1Answer.Noul
        assertEquals(0.8263, n.p, 1e-9); assertTrue(n.value); assertEquals(0.8263, n.confidence!!, 1e-9)
        assertEquals(0.8263, n.probabilities["true"]!!, 1e-9); assertEquals(1 - 0.8263, n.probabilities["false"]!!, 1e-9)
    }

    @Test fun parsesJevFixtures() {
        val c = SystemOne.parse(obj("jev_choice.json"), listOf(dept), "jev", 187)
        assertEquals("jev-1.13.0", c.model); assertNull(c.routing); assertEquals(TokenUsage(42, 3), c.usage)
        assertEquals("billing", (c.answers["dept"] as S1Answer.Choice).choice)
        val s = SystemOne.parse(obj("jev_score_legend.json"), listOf(urgency), "jev", 1).answers["urgency"] as S1Answer.Score
        assertEquals(1.05, s.score, 1e-9); assertEquals(1, s.level); assertEquals("medium", s.levelLabel)
        val n = SystemOne.parse(obj("jev_noul.json"), listOf(spam), "jev", 1).answers["spam"] as S1Answer.Noul
        assertEquals(0.08, n.p, 1e-9); assertFalse(n.value); assertNull(n.confidence)
        assertEquals(0.92, n.probabilities["false"]!!, 1e-9)
        // model absent everywhere -> the engine default
        assertEquals(SystemOne.JEV_MODEL, SystemOne.parse(JsonObject(obj("jev_noul.json") - "model"), listOf(spam), "jev", 1).model)
    }

    @Test fun arrayProbabilitiesDistributionAliasAndRoutingModel() {
        val r = SystemOne.parse(obj("laya_array_probs.json"), layaQs, "laya", 5)
        assertEquals("multilingual", r.model)                                              // no top-level model -> routing.model
        val c = r.answers["department"] as S1Answer.Choice
        assertEquals("technical", c.choice)                                                // no `choice` -> argmax over the option-ordered array
        assertEquals(0.8, c.probabilities["technical"]!!, 1e-9); assertNull(c.confidence)
        val s = r.answers["urgency"] as S1Answer.Score
        assertEquals(1.6, s.score, 1e-9); assertEquals(2, s.level); assertEquals("critical", s.levelLabel)   // no score -> Σ i·p_i; levels label (no legend)
        assertEquals(0.31, (r.answers["churn_risk"] as S1Answer.Noul).p, 1e-9)
        assertNull(r.usage)
    }

    @Test fun missingAnswerNamesTheQuestion() {
        try { SystemOne.parse(obj("laya_missing_answer.json"), layaQs.take(2), "laya", 1); fail() } catch (e: NodeException) {
            assertTrue(e.message, e.message!!.contains("answered 1 of 2")); assertTrue(e.message!!.contains("'urgency'"))
        }
        // extra, unrequested answers are ignored
        assertEquals(1, SystemOne.parse(obj("laya_reply.json"), layaQs.take(1), "laya", 1).answers.size)
    }

    @Test fun errorTableCleansKeysAndReadsFastApiDetail() {
        val e401 = SystemOne.errorMessage(jev, 401, text("jev_err_401.json"))
        assertEquals("Invalid Jev API key (Settings > AI > Decision engine)", e401)
        val e422 = SystemOne.errorMessage(jev, 422, text("jev_err_422.json"))
        assertTrue(e422, e422.startsWith("Jev rejected the questions: questions.urgency.criteria")); assertFalse(e422.contains("FAKEKEY"))
        assertEquals("Jev: rate limited, retry later", SystemOne.errorMessage(jev, 429, text("jev_err_429.json")))
        assertEquals("Jev is overloaded (529), retry later", SystemOne.errorMessage(jev, 529, text("jev_err_529.json")))
        assertEquals("Jev endpoint not found (client out of date?)", SystemOne.errorMessage(jev, 404, ""))
        assertEquals("Laya rejected the request: state is required", SystemOne.errorMessage(laya, 400, text("laya_err_400.json")))
        assertTrue(SystemOne.errorMessage(laya, 401, text("laya_err_401.json")).startsWith("Laya refused the token"))
        assertTrue(SystemOne.errorMessage(laya, 413, text("laya_err_413.json")).startsWith("Laya: too large"))
        assertEquals("Laya rejected the questions: question 'urgency': score criteria must list 2-32 levels", SystemOne.errorMessage(laya, 422, text("laya_err_422.json")))
        assertTrue(SystemOne.errorMessage(laya, 503, text("laya_err_503.json")).startsWith("Laya is busy (503"))
        assertEquals("No /v1/systemone at 10.0.0.61:8000 — is this laya-serve?", SystemOne.errorMessage(laya, 404, "{\"detail\":\"Not Found\"}"))
        assertTrue(SystemOne.networkMessage(laya).startsWith("Cannot reach Laya at 10.0.0.61:8000"))
        // a laya key injected into the body never survives
        val keyed = laya.copy(key = "laya-secret-value-123")
        val m = SystemOne.errorMessage(keyed, 400, "{\"detail\":\"bad token laya-secret-value-123 and Bearer abc.def\"}")
        assertFalse(m, m.contains("laya-secret-value-123")); assertFalse(m.contains("abc.def"))
        assertTrue(SystemOne.errorMessage(jev, 500, "x".repeat(2000)).length <= 300)
    }

    @Test fun retryAfterIsCappedAtTwoSeconds() {
        assertEquals(1_000L, SystemOne.retryAfterMs("1")); assertEquals(2_000L, SystemOne.retryAfterMs("30")); assertEquals(0L, SystemOne.retryAfterMs("0"))
        assertNull(SystemOne.retryAfterMs(null)); assertNull(SystemOne.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT")); assertNull(SystemOne.retryAfterMs("-3"))
    }

    @Test fun resolveTargetMatrixNeverFallsBack() {
        fun msg(block: () -> Unit): String? = try { block(); null } catch (e: NodeException) { e.message }
        assertEquals(SystemOne.ERR_NOT_CONFIGURED, msg { SystemOne.resolveTarget(null, "none", "k", "http://10.0.0.61:8000", null) })
        assertEquals(SystemOne.ERR_NOT_CONFIGURED, msg { SystemOne.resolveTarget("default", "none", "k", "http://10.0.0.61:8000", null) })
        assertEquals(SystemOne.ERR_NO_JEV_KEY, msg { SystemOne.resolveTarget("default", "jev", null, "http://10.0.0.61:8000", null) })   // never falls back to laya
        assertEquals(SystemOne.ERR_NO_JEV_KEY, msg { SystemOne.resolveTarget("jev", "laya", " ", "http://10.0.0.61:8000", null) })
        assertEquals(SystemOne.ERR_NO_LAYA_URL, msg { SystemOne.resolveTarget(null, "laya", "k", null, null) })
        assertEquals(SystemOne.ERR_NO_LAYA_URL, msg { SystemOne.resolveTarget("laya", "jev", "k", "", null) })
        val j = SystemOne.resolveTarget("jev", "none", "k", null, null)
        assertEquals(S1Target("jev", SystemOne.JEV_URL, "k", "jev-latest"), j); assertEquals("Jev", j.label)
        val l = SystemOne.resolveTarget(null, "laya", null, "http://10.0.0.61:8000/", "lk")
        assertEquals(S1Target("laya", "http://10.0.0.61:8000/v1/systemone", "lk", null), l); assertEquals("Laya", l.label)
    }

    @Test fun layaUrlNormalisesAndKeepsTheLanRule() {
        assertEquals("http://10.0.0.61:8000", S1Prefs.normalizeLayaUrl(" http://10.0.0.61:8000/v1/systemone/ "))
        assertEquals("http://mac.local:8000", S1Prefs.normalizeLayaUrl("http://mac.local:8000"))
        try { S1Prefs.normalizeLayaUrl("http://8.8.8.8:8000"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("local-network")) }
    }

    @Test fun stateOfJsonVersusTextVersusCap() {
        assertEquals(buildJsonObject { put("a", 1) }, SystemOne.stateOf(" {\"a\":1} "))
        assertTrue(SystemOne.stateOf("[1,2]") is JsonArray)
        assertEquals(JsonPrimitive("hello {world"), SystemOne.stateOf("hello {world"))
        assertEquals(JsonPrimitive("{not json"), SystemOne.stateOf("{not json"))
        assertEquals(JsonPrimitive("42"), SystemOne.stateOf("42"))
        val big = SystemOne.stateOf("x".repeat(50_001)) as JsonPrimitive
        assertEquals(SystemOne.MAX_STATE_CHARS, big.content.length)
    }

    @Test fun criteriaGrammarErrorsNameTheQuestion() {
        fun err(type: String, t: String): String = try { SystemOne.parseCriteria(type, t, "q1", "x"); "" } catch (e: NodeException) { e.message!! }
        assertTrue(err("choice", "only").startsWith("AI Decide: question q1: choice needs 2"))
        assertTrue(err("choice", "a\na: dup").contains("duplicate option 'a'"))
        assertTrue(err("score", "low").contains("score needs 2–10 levels"))
        assertTrue(err("noul", "maybe: sometimes").contains("'true: …' or 'false: …'"))
        assertTrue(err("bogus", "").contains("type must be choice, score or noul"))
        val n = SystemOne.parseCriteria("noul", "true: yes it is\nFALSE: no", "q1", "x") as S1Question.Noul
        assertEquals("yes it is", n.trueDesc); assertEquals("no", n.falseDesc)
        val s = SystemOne.parseCriteria("score", "low, medium\nhigh", "q1", "x") as S1Question.Score
        assertEquals(listOf("low", "medium", "high"), s.levels)
    }

    @Test fun questionsFromRowsAreRowNumbered() {
        val rows = listOf(
            buildJsonObject { put("name", "dept"); put("type", "choice"); put("instructions", "Which team?"); put("criteria", "billing: refunds\ntech: bugs") },
            buildJsonObject { put("name", "spam"); put("type", "noul"); put("instructions", "Spam?") },
        )
        val qs = SystemOne.questionsFromRows(rows)
        assertEquals(listOf("dept", "spam"), qs.map { it.name }); assertTrue(qs[0] is S1Question.Choice); assertTrue(qs[1] is S1Question.Noul)
        try { SystemOne.questionsFromRows(listOf(rows[0], buildJsonObject { put("type", "noul") })); fail() } catch (e: NodeException) { assertEquals("AI Decide: question row 2: name is required", e.message) }
        // an agent may pass criteria as an array of lines
        val arr = SystemOne.questionsFromRows(listOf(buildJsonObject { put("name", "s"); put("type", "score"); put("instructions", "x"); put("criteria", JsonArray(listOf(JsonPrimitive("lo"), JsonPrimitive("hi")))) }))
        assertEquals(listOf("lo", "hi"), (arr[0] as S1Question.Score).levels)
    }

    @Test fun testSummaryLine() {
        val r = SystemOne.parse(obj("jev_choice.json"), listOf(dept), "jev", 187)
        assertEquals("dept=billing (0.93)", SystemOne.summary(r.answers.getValue("dept")))
    }

    // ---- F2 (v5 device phase): connect vs read phase, the one retry, keep-alive ----
    private fun fake(vararg outcomes: Any): Pair<suspend (S1Target, String, Long, Int) -> SystemOne.Response, IntArray> {
        val n = intArrayOf(0)
        return Pair({ _, _, _, _ -> when (val o = outcomes[n[0]++]) { is Throwable -> throw o; else -> SystemOne.Response(200, o as String, null) } }, n)
    }
    private fun exMsg(block: suspend () -> Unit): String = try { kotlinx.coroutines.runBlocking { block() }; fail("expected NodeException"); "" } catch (e: NodeException) { e.message!! }

    @Test fun connectTimeoutIsRetriedOnceAndNamedAsConnect() {
        val cto = { SystemOne.ConnectFailed(java.net.SocketTimeoutException("failed to connect to /10.0.0.61 (port 8000) after 3000ms")) }
        val (twice, n) = fake(cto(), cto())
        assertEquals("Laya not reachable at 10.0.0.61:8000 (connect timed out after 3 s)", exMsg { SystemOne.exchange(laya, "{}", 5_000, twice) })
        assertEquals(2, n[0])
        val (thenOk, m) = fake(cto(), "{\"ok\":1}")
        assertEquals(200 to "{\"ok\":1}", kotlinx.coroutines.runBlocking { SystemOne.exchange(laya, "{}", 5_000, thenOk) })
        assertEquals(2, m[0])
        // refused / unknown host in the connect phase keeps the "Cannot reach" text
        val (refused, _) = fake(SystemOne.ConnectFailed(java.net.ConnectException("refused")), SystemOne.ConnectFailed(java.net.ConnectException("refused")))
        assertEquals(SystemOne.networkMessage(laya), exMsg { SystemOne.exchange(laya, "{}", 5_000, refused) })
    }

    @Test fun firstConnectFitsTheCallBudgetAndTlsIsNamed() {
        assertEquals(1_000, SystemOne.connectMs(1, 3_000)); assertEquals(1_666, SystemOne.connectMs(1, 5_000))   // SecondOpinion 3 s / triage 5 s
        assertEquals(750, SystemOne.connectMs(1, 1_000)); assertEquals(3_000, SystemOne.connectMs(1, 30_000)); assertEquals(3_000, SystemOne.connectMs(2, 3_000))
        val seen = mutableListOf<Int>()
        val cto = SystemOne.ConnectFailed(java.net.SocketTimeoutException("connect"), 1_000)
        val t0 = System.nanoTime()
        kotlinx.coroutines.runBlocking { SystemOne.exchange(laya, "{}", 3_000, { _, _, _, c -> seen += c; if (seen.size == 1) throw cto else SystemOne.Response(200, "{}", null) }) }
        assertEquals(listOf(1_000, 3_000), seen)
        assertTrue("connect retry is immediate", (System.nanoTime() - t0) / 1_000_000 < 500)
        val (tls, n) = fake(javax.net.ssl.SSLHandshakeException("cert"), "{}")
        assertEquals("${jev.label}: TLS handshake failed (SSLHandshakeException)", exMsg { SystemOne.exchange(jev, "{}", 5_000, tls) })
        assertEquals(1, n[0])
        val (hs, _) = fake(SystemOne.ConnectFailed(java.net.SocketTimeoutException("x")), SystemOne.ConnectFailed(java.net.SocketTimeoutException("x")))
        val m = exMsg { SystemOne.exchange(jev, "{}", 5_000, hs) }   // https: the handshake shares connect(), bounded by the read timeout
        assertTrue(m, m.startsWith("Jev not reachable at ") && m.endsWith("(connect or TLS handshake timed out)"))
    }

    @Test fun readTimeoutIsNotRetriedAndKeepsItsMessage() {
        val (rto, n) = fake(java.net.SocketTimeoutException("Read timed out"), "{}")
        assertEquals("Laya timed out after 5 s", exMsg { SystemOne.exchange(laya, "{}", 5_000, rto) })
        assertEquals(1, n[0])
        val (io, k) = fake(java.io.IOException("unexpected end of stream"), "{}")   // stale keep-alive socket: one retry
        assertEquals(200 to "{}", kotlinx.coroutines.runBlocking { SystemOne.exchange(laya, "{}", 5_000, io) })
        assertEquals(2, k[0])
    }

    @Test fun realTransportWrapsARefusedConnectAndReusesTheConnection() {
        val closed = S1Target("laya", "http://127.0.0.1:1/v1/systemone", null, null)
        assertEquals(SystemOne.networkMessage(closed), exMsg { SystemOne.exchange(closed, "{}", 2_000) })
        // a minimal keep-alive HTTP/1.1 server (android.jar has no com.sun.net.httpserver): counts TCP accepts, echoes Content-Length
        val srv = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val accepts = java.util.concurrent.atomic.AtomicInteger()
        Thread {
            while (!srv.isClosed) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                accepts.incrementAndGet()
                Thread {
                    runCatching {
                        val inp = java.io.BufferedInputStream(sock.getInputStream()); val out = sock.getOutputStream()
                        while (true) {
                            val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) { val c = inp.read(); if (c < 0) return@runCatching; head.append(c.toChar()) }
                            val len = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)
                            repeat(len?.toInt() ?: 0) { inp.read() }
                            val body = "{\"len\":\"$len\"}"
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray()); out.flush()
                        }
                    }
                    runCatching { sock.close() }
                }.start()
            }
        }.start()
        try {
            val t = S1Target("laya", "http://127.0.0.1:${srv.localPort}/v1/systemone", null, null)
            repeat(3) { assertEquals(200 to "{\"len\":\"7\"}", kotlinx.coroutines.runBlocking { SystemOne.exchange(t, "{\"a\":1}", 2_000) }) }
            assertEquals("keep-alive: one TCP connection for three calls", 1, accepts.get())
        } finally { srv.close() }
    }
}
