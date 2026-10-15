package dev.aigw.core.failover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型级冷却表。
 *
 * 上游的「该模型已超限」类错误（CodeBuddy 6004 / Trae 1005）换账号无用，
 * 所以按 `供应商|模型` 冷却：冷却期内该模型直接跳过，到期自然恢复。
 */
class ModelCooldownTrackerTest {

    private var now = 1_000_000L
    private val tracker = ModelCooldownTracker { now }

    @Test
    fun `标记后进入冷却`() {
        assertFalse(tracker.isCooling("codebuddy", "glm-5.3"))
        tracker.markCooling("codebuddy", "glm-5.3", 60_000L)
        assertTrue(tracker.isCooling("codebuddy", "glm-5.3"))
        // 只影响该模型、该供应商
        assertFalse(tracker.isCooling("codebuddy", "kimi-k2"))
        assertFalse(tracker.isCooling("trae", "glm-5.3"))
    }

    @Test
    fun `到期后自动失效`() {
        tracker.markCooling("codebuddy", "m", 60_000L)
        now += 59_999L
        assertTrue(tracker.isCooling("codebuddy", "m"))
        now += 1L
        assertFalse(tracker.isCooling("codebuddy", "m"), "到点即恢复")
    }

    @Test
    fun `成功后立即解除冷却`() {
        tracker.markCooling("codebuddy", "m", 600_000L)
        assertTrue(tracker.isCooling("codebuddy", "m"))
        tracker.clear("codebuddy", "m")
        assertFalse(tracker.isCooling("codebuddy", "m"))
    }

    @Test
    fun `非正时长不记冷却`() {
        tracker.markCooling("codebuddy", "m", 0L)
        assertFalse(tracker.isCooling("codebuddy", "m"))
    }

    @Test
    fun `快照只列出仍在冷却中的条目`() {
        tracker.markCooling("a", "m1", 60_000L)
        tracker.markCooling("b", "m2", 10_000L)
        now += 20_000L
        val snap = tracker.snapshot()
        assertEquals(setOf("a|m1"), snap.keys, "过期项不应出现：$snap")
    }
}
