package dev.aigw.core.provider.trae

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/** 上游模型目录的一项。 */
data class TraeModel(
    val id: String,
    val name: String,
    /** 上游 `max_input_tokens`；上游未给时为 0（界面据此不展示上下文），不编造默认值。 */
    val contextWindow: Long = 0,
)

/**
 * `get_detail_param` 的请求体构造与响应解析。
 * 响应结构：`{"config_info_list":[{"config_name","max_input_tokens","display_config":{"display_name"}}]}`
 */
internal object TraePayloadModels {

    fun requestBody(): String = JsonObject().apply {
        addProperty("function", TraeConstants.FUNCTION)
        add("config_names", null)
        addProperty("need_prompt", false)
        add("current_config_info", null)
        addProperty("poly_prompt", true)
        add("mode_type", null)
        add("agent_type", null)
    }.toString()

    fun parse(raw: String): List<TraeModel> {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
        val list = root.get("config_info_list") as? JsonArray ?: return emptyList()
        val out = ArrayList<TraeModel>(list.size())
        for (element in list) {
            val cfg = element as? JsonObject ?: continue
            val id = cfg.str("config_name")
            if (id.isEmpty()) continue
            val display = cfg.objOrNull("display_config")?.str("display_name").orEmpty()
            val context = cfg.long("max_input_tokens").takeIf { it > 0 }
                ?: cfg.long("maxInputTokens").takeIf { it > 0 }
                ?: 0L
            out.add(TraeModel(id = id, name = display.ifEmpty { id }, contextWindow = context))
        }
        return out
    }
}
