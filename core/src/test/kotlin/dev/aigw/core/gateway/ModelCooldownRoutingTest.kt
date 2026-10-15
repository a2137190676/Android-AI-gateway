package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.failover.BreakerState
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 模型级限额的路由行为。
 *
 * 上游报「该模型已超限」（CodeBuddy `6004` / Trae `1005`）时，网关必须：
 * ① 只冷却该 (供应商, 模型)；② **不换账号**（换号撞同一道墙）；③ **不计入供应商熔断**
 * （否则单个模型的限额会把整个供应商熔断，其它模型一起不可用）。
 */
class ModelCooldownRoutingTest {

    /** 每次调用都回「该模型已超限」；记录被调用次数。 */
    private inner class ModelLimitProvider : Provider {
        var calls = 0
        override val id = "ml"
        override val displayName = "模型限额桩"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(ProviderModel("hot", "hot")), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.MODEL_LIMIT, "该模型已超限")
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            calls++
            return StreamFailureChatCall(200, UpstreamError(ErrorKind.MODEL_LIMIT, "该模型已超限，请稍后再试"))
        }
    }

    private fun post(port: Int): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.write(
            """{"model":"ml/hot","messages":[{"role":"user","content":"hi"}]}""".toByteArray(Charsets.UTF_8),
        )
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        return code to text
    }

    @Test
    fun `模型级限额不换号、不计熔断，并在冷却期内直接跳过`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = ModelLimitProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount(provider.id, "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount(provider.id, "u2", "账号2", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            val (code1, _) = post(server.listeningPort)
            assertTrue(code1 >= 400, "该模型不可用时应明确失败，实际 $code1")
            assertEquals(1, provider.calls, "模型级错误不得换号重试（只应打一次上游）")

            // 熔断阈值默认 5：若模型级错误被计入，连续几次就会把整个供应商熔断
            assertEquals(
                BreakerState.CLOSED,
                engine.circuitBreakerOf(provider.id).currentState(),
                "模型级限额不代表供应商故障，不得计入熔断",
            )
            repeat(6) { post(server.listeningPort) }
            assertEquals(BreakerState.CLOSED, engine.circuitBreakerOf(provider.id).currentState())
            assertEquals(1, provider.calls, "冷却期内必须直接跳过，不再打上游")

            val (_, body) = post(server.listeningPort)
            assertTrue(body.contains("冷却"), "应给出「冷却中」的原因，实际：$body")
        } finally {
            server.stop()
        }
    }
}
