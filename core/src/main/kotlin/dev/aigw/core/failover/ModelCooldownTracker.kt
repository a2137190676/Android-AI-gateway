package dev.aigw.core.failover

import java.util.concurrent.ConcurrentHashMap

/**
 * 模型级冷却表。
 *
 * 某些上游错误是**模型级**的：CodeBuddy `429 code=6004`（「该模型已超限」）、
 * Trae `1005`（套餐权益拒绝）。这类错误换个账号仍然撞同一道墙——换号既浪费时间又
 * 会把重试配额耗光，而且它们**不代表供应商故障**，不该计入供应商熔断。
 *
 * 所以按 `供应商|模型` 记一段冷却：冷却期内直接跳过该模型（转下一候选供应商），
 * 冷却结束后自然恢复。
 *
 * 与熔断器/健康指标一致：**仅内存**。冷却本就是瞬态状态，重启即清空。
 */
class ModelCooldownTracker(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 键：`providerId|model`；值：冷却截止毫秒。 */
    private val until = ConcurrentHashMap<String, Long>()

    private fun key(providerId: String, model: String) = "$providerId|$model"

    /** 该 (供应商, 模型) 是否正在冷却。到期项会被顺手清掉。 */
    fun isCooling(providerId: String, model: String): Boolean {
        val k = key(providerId, model)
        val deadline = until[k] ?: return false
        if (nowMillis() >= deadline) {
            until.remove(k, deadline)
            return false
        }
        return true
    }

    /** 标记冷却 [durationMs] 毫秒；[durationMs] <= 0 时不记（由调用方用配置值）。 */
    fun markCooling(providerId: String, model: String, durationMs: Long) {
        if (durationMs <= 0) return
        until[key(providerId, model)] = nowMillis() + durationMs
    }

    /** 成功一次即解除该模型的冷却（上游已恢复可用）。 */
    fun clear(providerId: String, model: String) {
        until.remove(key(providerId, model))
    }

    /** 当前仍在冷却中的条目：`providerId|model` → 剩余毫秒。供界面展示。 */
    fun snapshot(): Map<String, Long> {
        val now = nowMillis()
        val out = LinkedHashMap<String, Long>()
        for ((k, deadline) in until) {
            val remaining = deadline - now
            if (remaining > 0) out[k] = remaining else until.remove(k, deadline)
        }
        return out
    }
}
