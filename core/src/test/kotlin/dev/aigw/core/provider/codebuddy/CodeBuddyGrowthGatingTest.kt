package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonObject
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 企业账号没有个人成长体系。
 *
 * 上游对企业号签到回 `400 code 10001`、成长中心回
 * `403 growth system is only available for personal users`；企业额度走
 * `get-enterprise-user-usage`。网关必须**直接短路、不发请求**，否则每次点签到都白撞一次。
 * 参照实现：`linguo2625469/workbuddy2api-panel`。
 */
class CodeBuddyGrowthGatingTest {

    private val provider = CodeBuddyProvider(region = { CodeBuddyProvider.REGION_CN })

    private fun enterpriseAccount() = assertNotNull(
        provider.importCredentials(
            """{"accessToken":"tok","uid":"u-ent","enterpriseId":"ent-1","domain":"www.codebuddy.cn"}""",
        ),
    )

    @Test
    fun `企业账号签到与成长任务直接失败且不发请求`() {
        val account = enterpriseAccount()
        val checkin = provider.performAction(account, ACTION_CHECKIN, JsonObject())
        assertFalse(checkin.ok)
        assertTrue(checkin.message.contains("企业"), "实际：${checkin.message}")

        val tasks = provider.performAction(account, ACTION_TASKS, JsonObject())
        assertFalse(tasks.ok)
        assertTrue(tasks.message.contains("企业"), "实际：${tasks.message}")
    }

    @Test
    fun `企业账号任务列表为空且不报错`() {
        val view = provider.taskList(enterpriseAccount())
        assertNotNull(view)
        assertTrue(view.tasks.isEmpty())
        assertTrue(view.error.isNullOrEmpty(), "短路不应产生错误：${view.error}")
    }
}
