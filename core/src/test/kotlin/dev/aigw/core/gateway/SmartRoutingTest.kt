package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmartRoutingTest {

    private inner class DummyProvider(override val id: String) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()
    }

    /**
     * 区域型供应商替身：内置 CodeBuddyProvider 已不再回退内置快照，拉不到真实目录就为空，
     * 无法在无网的单测里产生模型；这里用固定目录锁定「按区域拆成多组」的行为。
     */
    private inner class RegionStubProvider(
        override val id: String,
        private val regions: List<String>,
    ) : Provider, dev.aigw.core.provider.RegionAwareSupport {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(dev.aigw.core.provider.ProviderModel("glm-5.2", "GLM-5.2")), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()
        override fun regionOf(account: ProviderAccount) =
            regions.firstOrNull { account.uid.endsWith("-$it") } ?: regions.first()

        override fun regions() = regions
    }

    /**
     * 带固定模型目录的桩：启发式跨供应商回退靠目录里的同名模型匹配，
     * 用空目录的桩永远匹配不到，等于测不到候选产生。
     */
    private inner class CatalogProvider(
        override val id: String,
        private val modelIds: List<String>,
    ) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(modelIds.map { dev.aigw.core.provider.ProviderModel(it, it) }, false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()
    }

    @Test
    fun `带别名前缀能正确路由且不区分大小写`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("antigravity"))

        assertEquals("antigravity", engine.resolveRoute("google/gemini-2.5-flash")?.providerId)
        assertEquals("gemini-2.5-flash", engine.resolveRoute("google/gemini-2.5-flash")?.model)

        assertEquals("antigravity", engine.resolveRoute("Gemini/gemini-3.8-flash")?.providerId)
        assertEquals("antigravity", engine.resolveRoute("Antigravity/claude-sonnet-4-6")?.providerId)
        assertEquals("antigravity", engine.resolveRoute("agy/gemini-3.8-flash-high")?.providerId)
    }

    @Test
    fun `WorkBuddy 区域前缀能正确识别区域约束并在模型列表拆为两组`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 内置 CodeBuddyProvider 已实现 RegionAwareSupport；这里用替身覆盖同名 id，
        // 因为模型目录只在登录后拉取，无内置快照后单测里拿不到真实目录。
        engine.registry.register(RegionStubProvider("codebuddy", listOf("cn", "global")))
        // 路由解析支持 codebuddy-cn 与 codebuddy-global，且带出正确的 region 约束
        val routeCn = engine.resolveRoute("codebuddy-cn/deepseek-v3")
        assertEquals("codebuddy", routeCn?.providerId)
        assertEquals("deepseek-v3", routeCn?.model)
        assertEquals("cn", routeCn?.region)

        val routeGlobal = engine.resolveRoute("codebuddy-global/claude-3-5-sonnet")
        assertEquals("codebuddy", routeGlobal?.providerId)
        assertEquals("claude-3-5-sonnet", routeGlobal?.model)
        assertEquals("global", routeGlobal?.region)

        // 别名 workbuddy-cn 与 workbuddy-global 同样生效
        assertEquals("cn", engine.resolveRoute("workbuddy-cn/deepseek-v3")?.region)
        assertEquals("global", engine.resolveRoute("workbuddy-global/deepseek-v3")?.region)

        // 模型列表里针对区域型供应商拆出两个独立前缀组（每个区域各用本区域账号拉一次）
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-global", "国际号", "{}"))
        val models = engine.models().filter { it.providerId == "codebuddy" }
        val prefixes = models.map { it.routePrefix }.distinct()
        assertTrue(prefixes.contains("codebuddy-cn"), "应包含国内组前缀：$prefixes")
        assertTrue(prefixes.contains("codebuddy-global"), "应包含国外组前缀：$prefixes")

        // 组标题明确区分国内和国外
        val titles = models.map { it.providerName }.distinct()
        assertTrue(titles.any { it.contains("国内") }, "应包含国内标题：$titles")
        assertTrue(titles.any { it.contains("国外") }, "应包含国外标题：$titles")
    }

    @Test
    fun `无前缀时根据模型特征智能路由到 antigravity`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        // 反重力有账号，trae 没有
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "谷歌账号", "{}"))

        val route1 = engine.resolveRoute("gemini-3.8-flash-high")
        assertNotNull(route1)
        assertEquals("antigravity", route1.providerId)
        assertEquals("gemini-3.8-flash-high", route1.model)

        val route2 = engine.resolveRoute("claude-opus-4-6-thinking")
        assertNotNull(route2)
        assertEquals("antigravity", route2.providerId)
    }

    @Test
    fun `用户仅配置反重力一个供应商时所有无前缀请求由反重力接管`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        engine.registry.register(DummyProvider("codebuddy"))
        // 只有 antigravity 有账号
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "唯一账号", "{}"))

        // 即使用户请求 auto 或随意模型名，也直接由反重力接管，而不是退回到 trae 报 503
        val route = engine.resolveRoute("auto")
        assertNotNull(route)
        assertEquals("antigravity", route.providerId)
    }

    @Test
    fun `关闭故障转移后不再产生跨供应商候选`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 两家都提供同名模型且都有账号 —— 满足启发式回退产生候选的条件
        engine.registry.register(CatalogProvider("trae", listOf("gemini-2.5-flash")))
        engine.registry.register(CatalogProvider("antigravity", listOf("gemini-2.5-flash")))
        engine.pool.upsert(ProviderAccount("trae", "t1", "trae 账号", "{}"))
        engine.pool.upsert(ProviderAccount("antigravity", "a1", "反重力账号", "{}"))

        val candidates = engine.resolveCandidateRoutes("trae/gemini-2.5-flash")
        assertTrue(candidates.size > 1, "开关开启时应能产生跨供应商候选：$candidates")

        // 关闭后只允许首选路由：此前 enabled 只在「显式路由表」分支生效，
        // 启发式回退照跑，表现为「界面关了故障转移、实际仍在自动换供应商」
        engine.updateFailoverSettings(engine.failoverSettings().copy(enabled = false))
        val disabled = engine.resolveCandidateRoutes("trae/gemini-2.5-flash")
        assertEquals(1, disabled.size, "关闭后不得有跨供应商候选：$disabled")
        assertEquals("trae", disabled.single().providerId)
    }
}
