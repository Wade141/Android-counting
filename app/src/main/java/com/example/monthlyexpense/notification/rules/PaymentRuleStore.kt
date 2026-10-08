package com.example.monthlyexpense.notification.rules

import android.content.Context
import com.example.monthlyexpense.notification.decision.DecisionAction
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RulePreview internal constructor(
    val rules: PaymentRules?, val checks: List<RuleCheck>, val failures: List<String>,
    internal val json: String, internal val base: String
) { val valid get() = rules != null && failures.isEmpty() }

/** Atomic envelope stores active + previous together. Imported phrases are data, never executable code. */
class PaymentRuleStore(context: Context) {
    private var cached: PaymentRules? = null
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "payment-rules.json")
    private fun builtInJson() = appContext.assets.open("payment-rules/default.json").bufferedReader().use { it.readText() }
    private fun envelope(): JSONObject? = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    private fun currentJson(): String {
        val stored = envelope()
        return listOfNotNull(stored?.optString("active"), stored?.optString("previous"), builtInJson())
            .first { runCatching { decode(it) }.isSuccess }
    }

    fun snapshot(): PaymentRules = synchronized(lock) {
        cached?.let { return@synchronized it }
        com.example.monthlyexpense.notification.NotificationMetrics.measure(com.example.monthlyexpense.notification.PipelineMetric.RULE_LOAD) {
            val stored = envelope()
            (listOfNotNull(stored?.optString("active"), stored?.optString("previous"), builtInJson())
                .firstNotNullOfOrNull { runCatching { decode(it).first }.getOrNull() } ?: PaymentRules.builtIn).also { cached = it }
        }
    }

    fun preview(json: String): RulePreview = synchronized(lock) {
        try {
            val (rules, extra) = decode(json)
            val checks = RuleRegressionSuite.check(rules, snapshot(), extra)
            RulePreview(rules, checks, checks.filterNot { it.passed }.map { "样本 ${it.id} 未通过：${it.expected} → ${it.actual}" }, json, currentJson())
        } catch (error: Exception) {
            RulePreview(null, emptyList(), listOf(error.message ?: "无法读取规则包"), json, currentJson())
        }
    }

    fun activate(preview: RulePreview): Boolean = synchronized(lock) {
        if (!preview.valid || preview.base != currentJson()) return@synchronized false
        val verified = preview(preview.json)
        if (!verified.valid) return@synchronized false
        write(JSONObject().put("active", preview.json).put("previous", currentJson()))
        true
    }

    fun rollback(): Boolean = synchronized(lock) {
        val previous = envelope()?.optString("previous")?.takeIf { it.isNotBlank() } ?: return@synchronized false
        if (!preview(previous).valid) return@synchronized false
        write(JSONObject().put("active", previous).put("previous", currentJson()))
        true
    }

    fun reset() = synchronized(lock) { write(JSONObject().put("active", builtInJson()).put("previous", currentJson())) }
    fun exportCurrent(): String = synchronized(lock) { currentJson() }

    private fun write(value: JSONObject) {
        val temporary = File(file.parentFile, "payment-rules.json.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(value.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            // Fail closed if the filesystem cannot replace atomically; keep the last valid package.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            cached = null
        } finally { temporary.delete() }
    }

    private fun decode(json: String): Pair<PaymentRules, List<RuleSample>> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "规则包超过128KB" }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1 && root.getInt("minEngineVersion") in 1..1) { "规则包需要不同版本的应用" }
        val keys = setOf("schemaVersion", "minEngineVersion", "ruleSetVersion", "successPhrases", "paidLabels", "weChatTitles", "alipayTitles", "samples")
        require(root.keys().asSequence().all { it in keys }) { "规则包包含不支持的字段" }
        val version = root.getString("ruleSetVersion")
        require(version.matches(Regex("[A-Za-z0-9._-]{1,48}"))) { "规则版本格式无效" }
        fun phrases(key: String): List<String> {
            val array = root.getJSONArray(key)
            require(array.length() in 1..64) { "$key 数量无效" }
            return (0 until array.length()).map { array.getString(it) }.also { values ->
                require(values.distinct().size == values.size && values.all { it.matches(Regex("[\\p{L}\\p{N} /]{2,32}")) }) { "$key 只允许有限长度的普通词组" }
            }
        }
        val built = PaymentRules.builtIn
        val success = phrases("successPhrases")
        val labels = phrases("paidLabels")
        require(labels.none { Regex("余额|优惠|红包|奖励|订单|原价|收入|退款").containsMatchIn(it) }) { "不能将余额、优惠或订单原价设为实付" }
        require(success.all { Regex("付款|支付|消费|扣款|扣费|续费|代扣|转账").containsMatchIn(it) } &&
            success.none { Regex("失败|预计|即将|提醒|领取|余额|退款|待|未|将|如果").containsMatchIn(it) } &&
            success.all { it in built.successPhrases || Regex("成功|完成|^已").containsMatchIn(it) }) { "付款完成词组无效" }
        val wx = phrases("weChatTitles")
        val ali = phrases("alipayTitles")
        require(wx.all { it in built.weChatTitles } && ali.all { it in built.alipayTitles }) { "规则包不能扩展通知身份白名单" }
        val rules = PaymentRules(version, success, labels, wx, ali)
        val samples = root.optJSONArray("samples")
        require((samples?.length() ?: 0) <= 100) { "样本数量超过100条" }
        val extra = (0 until (samples?.length() ?: 0)).map { index ->
            val s = samples!!.getJSONObject(index)
            val source = s.getString("source")
            require(source in listOf("WECHAT", "ALIPAY")) { "样本来源无效" }
            require(s.getString("text").length <= 4000 && s.getString("title").length <= 80) { "样本过长" }
            val lines = s.optJSONArray("textLines")
            require((lines?.length() ?: 0) <= 64) { "样本行数过多" }
            val textLines = (0 until (lines?.length() ?: 0)).map { lines!!.getString(it) }
            require(s.optString("bigText").length + s.optString("subText").length + textLines.sumOf { it.length } <= 8000) { "样本过长" }
            RuleSample(s.getString("id"), source, s.getString("title"), s.getString("text"),
                DecisionAction.valueOf(s.getString("action")), if (s.has("amountCents") && !s.isNull("amountCents")) s.getLong("amountCents") else null,
                s.optString("bigText"), textLines, s.optString("subText"), s.optBoolean("isGroupSummary"))
        }
        return rules to extra
    }

    companion object { const val MAX_BYTES = 128 * 1024; private val lock = Any() }
}
