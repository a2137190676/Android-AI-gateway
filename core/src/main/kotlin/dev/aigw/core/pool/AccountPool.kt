package dev.aigw.core.pool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.objOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** 账号对外暴露的状态（不含凭证）。 */
data class AccountStatus(
    val providerId: String,
    val uid: String,
    val nickname: String,
    /** 权益包/积分余额；[creditsKnown] 为 false 时该值无意义。 */
    val credits: Long,
    val creditsKnown: Boolean,
    /** provider 给出的额度补充说明（如「来源=团队，当日剩余=…」）。 */
    val detail: String,
    /** 上次失败原因（凭证失效或手动停用的说明）；空串表示一切正常。 */
    val reason: String,
    /** 凭证失效导致的硬禁用，需要重新登录。 */
    val disabled: Boolean,
    /** 用户手动软开关。 */
    val enabled: Boolean,
) {
    val usable: Boolean get() = !disabled && enabled
}

/** 账号池摘要（可整体或按 provider 统计）。 */
data class PoolSummary(
    val total: Int,
    val usable: Int,
    val disabled: Int,
    val disabledByUser: Int,
    val totalCredits: Long,
    /** 有多少个账号的额度是已知的。 */
    val creditsKnown: Int,
)

private class Entry(var account: ProviderAccount) {
    var credits: Long = 0
    var creditsKnown: Boolean = false
    var detail: String = ""
    var disabled: Boolean = false
    var enabled: Boolean = true
    var reason: String = ""

    fun healthy(): Boolean = !disabled && enabled
}

/**
 * 多 provider 账号池：单实例内部按 `providerId` 分片。
 *
 * 选号与额度都按 provider 独立，互不影响；新增供应商不需要改这个类。
 * 挑选策略：该 provider 内可用账号中取余额最高者；同一请求内换号重试用 [pick] 的 exclude 排除。
 *
 * 不做本地冷却：上游每次报什么就是什么，由调用方（换号重试/跨供应商 Failover）应对，
 * 避免本地状态把明明可用的账号挡在门外。
 */
class AccountPool(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, Entry>()

    /** 按 provider 分组的条目索引：选号只需扫该供应商的账号，不必遍历整个池。 */
    private val byProvider = HashMap<String, MutableList<Entry>>()

    /**
     * 每个账号一把落盘串行锁（键 = providerId/uid）：同一账号的凭证/状态落盘与删除在这里互斥。
     *
     * 内存状态在 [lock] 写锁内更新后，落盘被移到锁外执行 `store.write`：Android 侧一次 write
     * 就是整文件的加密 commit，持写锁落盘会把整个账号池的读（含选号 [pick]）全堵住，批量并发时
     * 线程越多等锁越久，拿不到线性收益。
     *
     * 锁外写会引入「同一账号多次落盘相互乱序」的风险（旧值覆盖新值）。这里用「同账号串行 + 落盘
     * 前重取最新内存状态」消除它：同账号写盘不会并行交错；每次写盘重新读当前内存里的状态/凭证，
     * 而不是用调用时捕获的旧快照，故最终写出去的一定是最新值（详见 [persistState]）。
     */
    private val persistLocks = ConcurrentHashMap<String, Any>()

    init {
        load()
    }

    private fun accountIoLock(providerId: String, uid: String): Any =
        persistLocks.computeIfAbsent(keyOf(providerId, uid)) { Any() }

    // ---------------------------------------------------------------- 账号

    fun upsert(account: ProviderAccount) {
        // 锁内只改内存，落盘移到锁外（见 [persistLocks] 的说明）。
        lock.write {
            val key = keyOf(account.providerId, account.uid)
            val existing = entries[key]
            if (existing != null) {
                existing.account = account
                // 重新登录/刷新凭证意味着用户提供了新凭证：清除硬禁用状态。
                // 否则会出现「重新登录后依然 503」——旧凭证的失效状态沾在新凭证上。
                existing.disabled = false
                existing.reason = ""
            } else {
                val entry = Entry(account)
                entries[key] = entry
                byProvider.getOrPut(account.providerId) { ArrayList() }.add(entry)
            }
        }
        persistAccount(account)
        persistState(account.providerId, account.uid)
    }

    fun remove(providerId: String, uid: String): Boolean {
        val removed = lock.write {
            val entry = entries.remove(keyOf(providerId, uid)) ?: return@write false
            val group = byProvider[providerId]
            group?.remove(entry)
            if (group != null && group.isEmpty()) byProvider.remove(providerId)
            true
        }
        if (!removed) return false
        // 删除同样放到锁外，并与该账号的落盘串行：[persistState] 落盘前会重读内存，若删除先完成
        // 它会读到「条目已不在」而直接跳过，不会把刚删掉的键又写回来。
        synchronized(accountIoLock(providerId, uid)) {
            store.delete(accountKey(providerId, uid))
            store.delete(stateKey(providerId, uid))
            // 同 uid 重新登录时若旧格式文件还留着该账号状态，会与新建的默认状态冲突，故一并摘除。
            purgeLegacyState(providerId, uid)
        }
        return true
    }

    fun account(providerId: String, uid: String): ProviderAccount? =
        lock.read { entries[keyOf(providerId, uid)]?.account }

    /** [providerId] 为空时返回全部 provider 的账号。 */
    fun accounts(providerId: String? = null): List<ProviderAccount> = lock.read {
        entries.values
            .filter { providerId == null || it.account.providerId == providerId }
            .map { it.account }
    }

    fun statuses(providerId: String? = null): List<AccountStatus> = lock.read {
        entries.entries
            .filter { providerId == null || it.value.account.providerId == providerId }
            .sortedBy { it.key }
            .map { statusOf(it.value) }
    }

    fun status(providerId: String, uid: String): AccountStatus? =
        lock.read { entries[keyOf(providerId, uid)]?.let { statusOf(it) } }

    fun size(providerId: String? = null): Int = lock.read {
        if (providerId == null) entries.size
        else byProvider[providerId]?.size ?: 0
    }

    // ---------------------------------------------------------------- 状态

    fun setEnabled(providerId: String, uid: String, enabled: Boolean, reason: String = "") {
        val found = lock.write {
            val entry = entries[keyOf(providerId, uid)] ?: return@write false
            entry.enabled = enabled
            if (!enabled) {
                if (reason.isNotEmpty()) entry.reason = reason
            } else if (entry.reason.isNotEmpty() && !entry.disabled) {
                entry.reason = ""
            }
            true
        }
        if (found) persistState(providerId, uid)
    }

    /** 凭证失效：硬禁用，只能重新登录恢复。 */
    fun disable(providerId: String, uid: String, reason: String) {
        val found = lock.write {
            val entry = entries[keyOf(providerId, uid)] ?: return@write false
            entry.disabled = true
            entry.reason = reason
            true
        }
        if (found) persistState(providerId, uid)
    }

    /** 更新额度。 */
    fun updateCredits(
        providerId: String,
        uid: String,
        credits: Long,
        known: Boolean = true,
        detail: String = "",
    ) {
        val found = lock.write {
            val entry = entries[keyOf(providerId, uid)] ?: return@write false
            entry.credits = credits
            entry.creditsKnown = known
            entry.detail = detail
            true
        }
        if (found) persistState(providerId, uid)
    }

    /** 刷新凭证后落盘（refreshToken 会轮换，必须立刻写回）。 */
    fun saveAccount(account: ProviderAccount) {
        lock.write {
            entries[keyOf(account.providerId, account.uid)]?.account = account
        }
        persistAccount(account)
    }

    // ---------------------------------------------------------------- 选号

    /** 取该 provider 可用账号中余额最高者；[exclude] 用于同一请求内的换号重试。 */
    fun pick(providerId: String, exclude: Set<String> = emptySet()): ProviderAccount? = lock.read {
        val group = byProvider[providerId] ?: return@read null
        var best: Entry? = null
        for (entry in group) {
            if (entry.account.uid in exclude) continue
            if (!entry.healthy()) continue
            if (best == null || entry.credits > best.credits) best = entry
        }
        best?.account
    }

    /** 供界面展示的摘要；[providerId] 为空时统计全部。 */
    fun summary(providerId: String? = null): PoolSummary = lock.read {
        // 指定 provider 时直接复用 [byProvider] 分片索引（O(N) 而非遍历整池再过滤的 O(P×N)），
        // 该索引与 entries 同步维护，两者内容一致，结果不变。
        val source: Collection<Entry> = if (providerId == null) entries.values else byProvider[providerId].orEmpty()
        var total = 0
        var usable = 0
        var disabled = 0
        var disabledByUser = 0
        var totalCredits = 0L
        var creditsKnown = 0
        for (entry in source) {
            total++
            when {
                entry.disabled -> disabled++
                !entry.enabled -> disabledByUser++
                else -> usable++
            }
            if (entry.creditsKnown) {
                totalCredits += entry.credits
                creditsKnown++
            }
        }
        PoolSummary(
            total = total,
            usable = usable,
            disabled = disabled,
            disabledByUser = disabledByUser,
            totalCredits = totalCredits,
            creditsKnown = creditsKnown,
        )
    }

    // ---------------------------------------------------------------- 持久化

    private fun statusOf(entry: Entry): AccountStatus = AccountStatus(
        providerId = entry.account.providerId,
        uid = entry.account.uid,
        nickname = entry.account.nickname,
        credits = entry.credits,
        creditsKnown = entry.creditsKnown,
        detail = entry.detail,
        reason = entry.reason,
        disabled = entry.disabled,
        enabled = entry.enabled,
    )

    private fun keyOf(providerId: String, uid: String) = "$providerId/$uid"

    private fun accountKey(providerId: String, uid: String) = "$ACCOUNT_PREFIX$providerId/$uid"

    private fun persistAccount(account: ProviderAccount) {
        synchronized(accountIoLock(account.providerId, account.uid)) {
            // 重取当前内存里的最新凭证，避免并发写回时旧凭证覆盖新凭证（refreshToken 轮换最怕这个）。
            val latest = lock.read { entries[keyOf(account.providerId, account.uid)]?.account } ?: account
            store.write(accountKey(account.providerId, account.uid), latest.toJson().toString())
        }
    }

    /**
     * 按账号落盘：一个账号一个状态键。
     * 只重写单个账号意味着「改一个账号」的写放大从整包变成一条记录——
     * Android 侧每次 write 都是一整次文件提交，这里必须精确到 uid。
     */
    private fun persistState(providerId: String, uid: String) {
        // 同账号落盘串行（[accountIoLock]），并在此刻重读内存状态而非用调用时的旧快照：
        // 于是「排在后面的落盘」读到的必定是当前最新状态，绝不会把更旧的值覆盖到新值上——
        // 因为最后一次落盘执行时，所有内存更新都已完成（每个更新都会紧随一次落盘）。
        synchronized(accountIoLock(providerId, uid)) {
            val json = lock.read { entries[keyOf(providerId, uid)]?.let { stateJson(it).toString() } } ?: return
            store.write(stateKey(providerId, uid), json)
        }
    }

    private fun stateJson(entry: Entry): JsonObject = JsonObject().apply {
        addProperty("credits", entry.credits)
        addProperty("creditsKnown", entry.creditsKnown)
        addProperty("detail", entry.detail)
        addProperty("disabled", entry.disabled)
        addProperty("enabled", entry.enabled)
        addProperty("reason", entry.reason)
    }

    private fun load() {
        for (key in store.keys(ACCOUNT_PREFIX)) {
            val raw = store.read(key) ?: continue
            val account = runCatching { ProviderAccount.parse(raw) }.getOrNull() ?: continue
            val entry = Entry(account)
            readState(account.providerId, account.uid)?.let { state ->
                entry.credits = state.get("credits")?.asLong ?: 0L
                entry.creditsKnown = state.get("creditsKnown")?.asBoolean ?: false
                entry.detail = state.get("detail")?.asString.orEmpty()
                entry.disabled = state.get("disabled")?.asBoolean ?: false
                entry.enabled = state.get("enabled")?.asBoolean ?: true
                entry.reason = state.get("reason")?.asString.orEmpty()
            }
            entries[keyOf(account.providerId, account.uid)] = entry
        }
        rebuildIndex()
    }

    private fun rebuildIndex() {
        byProvider.clear()
        for (entry in entries.values) {
            byProvider.getOrPut(entry.account.providerId) { ArrayList() }.add(entry)
        }
    }

    /**
     * 读单个账号的状态；新键缺失时惰性迁移旧格式（provider 级整包文件）。
     *
     * 迁移策略：命中旧文件里的该 uid 后，先把状态写进新键、再把该 uid 从旧文件摘除，
     * 旧文件清空即删除。选「摘 uid」而不是「迁移完全部 uid 再删旧文件」，是因为：
     * 1) 迁移进度就存在旧文件自身（哪些 uid 还在就是还没迁），无需额外记账，天然可重复执行；
     * 2) 全程不存在「旧键仍持有数据而新键已写」的重叠窗口，新键一旦写入旧副本即被移除，
     *    读路径永远以新键为准，不会出现旧副本把状态改回去；
     * 3) 某 provider 只有部分账号有凭证时（其余 uid 无凭证、load 不会遍历到），旧文件里剩下的 uid
     *    会保留到其账号下次出现，避免一次性丢状态。
     */
    private fun readState(providerId: String, uid: String): JsonObject? {
        val raw = store.read(stateKey(providerId, uid))
        if (raw != null) {
            return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
        return migrateLegacyState(providerId, uid)
    }

    private fun migrateLegacyState(providerId: String, uid: String): JsonObject? {
        val legacy = readLegacyAccounts(providerId) ?: return null
        val saved = legacy.get(uid)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        // 先落新键再从旧文件摘除：两步之间即使崩溃，也只是旧文件里留了一份冗余副本，
        // 下次读取因新键已存在而直接命中新键，不依赖这次迁移是否完成。
        store.write(stateKey(providerId, uid), saved.toString())
        dropLegacyUid(providerId, legacy, uid)
        return saved
    }

    /** 删除账号时摘除其遗留的旧格式状态，避免同 uid 重新登录时旧状态被迁移回来。 */
    private fun purgeLegacyState(providerId: String, uid: String) {
        val legacy = readLegacyAccounts(providerId) ?: return
        if (legacy.get(uid) == null) return
        dropLegacyUid(providerId, legacy, uid)
    }

    private fun readLegacyAccounts(providerId: String): JsonObject? {
        val raw = store.read(legacyStateKey(providerId)) ?: return null
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.objOrNull("accounts")
        }.getOrNull()
    }

    private fun dropLegacyUid(providerId: String, legacy: JsonObject, uid: String) {
        legacy.remove(uid)
        if (legacy.size() == 0) {
            store.delete(legacyStateKey(providerId))
        } else {
            store.write(legacyStateKey(providerId), JsonObject().apply { add("accounts", legacy) }.toString())
        }
    }

    companion object {
        const val ACCOUNT_PREFIX = "account/"
        const val STATE_PREFIX = "pool/state/"

        /** 某个账号的状态文件键（测试与迁移脚本用）。 */
        fun stateKey(providerId: String, uid: String) = "$STATE_PREFIX$providerId/$uid.json"

        /** 旧版 provider 级状态文件键：整包格式 `{"accounts":{uid:{...}}}`，仅用于惰性迁移。 */
        fun legacyStateKey(providerId: String) = "$STATE_PREFIX$providerId.json"
    }
}
