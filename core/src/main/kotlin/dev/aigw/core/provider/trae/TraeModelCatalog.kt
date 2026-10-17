package dev.aigw.core.provider.trae

/**
 * 上游模型目录。
 *
 * 优先向 `get_detail_param` 拉取；成功后缓存 [TTL_MILLIS]，失败做 [FAIL_TTL_MILLIS] 负缓存，
 * 避免每次进「模型」页都打上游。拉不到时返回本次运行内缓存的旧目录（没有则空列表）并置
 * [fromFallback]，绝不展示打包的硬编码清单。
 */
class TraeModelCatalog(
    private val client: TraeChatClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var cached: List<TraeModel>? = null
    private var fetchedAt = 0L
    private var lastFailureAt = 0L

    /** 本次返回的清单是否不是来自本次成功实时拉取（即用了旧缓存或拉取失败）。 */
    var fromFallback: Boolean = false
        private set

    /** 某个具体错误（如 4001）导致拉取失败时留给界面展示。 */
    var lastError: String = ""
        private set

    /**
     * 取模型清单。没有可用账号时返回空列表，不发请求。
     */
    fun models(account: TraeAccount?): List<TraeModel> {
        // 两个标记描述的是「本次返回的清单怎么来的」，每次调用先重置，
        // 否则上一次的失败会被后面命中 TTL 缓存的调用沿用，界面会一直标「缓存」。
        fromFallback = false
        lastError = ""
        val now = nowMillis()
        cached?.let { if (now - fetchedAt < TTL_MILLIS) return it }

        if (account == null) {
            // 没有账号不算失败：登录前本就不该有模型。
            fromFallback = false
            lastError = ""
            return emptyList()
        }
        if (lastFailureAt != 0L && now - lastFailureAt < FAIL_TTL_MILLIS) {
            fromFallback = true
            return cached ?: emptyList()
        }

        return try {
            val fetched = client.fetchModels(account)
            if (fetched.isEmpty()) {
                lastFailureAt = now
                lastError = "上游返回空模型列表"
                fromFallback = true
                cached ?: emptyList()
            } else {
                cached = fetched
                fetchedAt = now
                lastFailureAt = 0L
                lastError = ""
                fromFallback = false
                fetched
            }
        } catch (e: Exception) {
            lastFailureAt = now
            lastError = e.message.orEmpty()
            fromFallback = true
            cached ?: emptyList()
        }
    }

    /** 强制下次重新拉取（界面的刷新按钮用）。 */
    fun invalidate() {
        fetchedAt = 0L
        lastFailureAt = 0L
    }

    companion object {
        const val TTL_MILLIS = 60L * 60 * 1000
        const val FAIL_TTL_MILLIS = 5L * 60 * 1000

        /** 非对话用途的内部条目，开启「只看可用模型」时隐藏。 */
        private val INTERNAL_IDS = setOf(
            "summary",
            "browser_use_subagent",
            "explore_sub_agent_v2",
            "explore_sub_agent_v13",
            "custom_model_placeholder",
        )

        fun isInternal(id: String): Boolean = id in INTERNAL_IDS
    }
}
