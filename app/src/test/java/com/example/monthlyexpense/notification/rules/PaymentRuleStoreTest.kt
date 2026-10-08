package com.example.monthlyexpense.notification.rules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.decision.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentRuleStoreTest {
    @Test fun snapshotsReuseParsedRulesUntilSuccessfulChange() {
        val store = PaymentRuleStore(context)
        val original = store.snapshot()
        repeat(100) { assertSame(original, store.snapshot()) }
        assertFalse(store.activate(store.preview("invalid")))
        assertSame(original, store.snapshot())
        assertTrue(store.activate(store.preview(pack().put("ruleSetVersion", "cached-next").toString())))
        assertEquals("cached-next", store.snapshot().version)
        assertNotSame(original, store.snapshot())
        store.reset()
        assertEquals(PaymentRules.builtIn.version, store.snapshot().version)
    }
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun clear() { context.filesDir.resolve("payment-rules.json").delete(); context.filesDir.resolve("payment-rules.json.bak").delete() }
    private fun pack() = JSONObject(context.assets.open("payment-rules/default.json").bufferedReader().use { it.readText() })

    @Test fun importPreviewActivateAndRollback() {
        val store = PaymentRuleStore(context)
        val raw = RawNotification(PaymentPackages.WECHAT, "微信支付", "消费已完成20元", "", emptyList(), 10000, "k", null)
        assertNotEquals(DecisionAction.AUTO, PaymentDecisionEngine(store.snapshot()).evaluate(raw).action)
        val json = pack().put("ruleSetVersion", "custom-2")
        json.getJSONArray("successPhrases").put("消费已完成")
        val preview = store.preview(json.toString())
        assertTrue(preview.failures.toString(), preview.valid)
        assertTrue(store.activate(preview))
        assertEquals(2000L, PaymentDecisionEngine(store.snapshot()).evaluate(raw).amountCents)
        assertEquals(DecisionAction.AUTO, PaymentDecisionEngine(store.snapshot()).evaluate(raw).action)
        assertTrue(store.rollback())
        assertEquals(context.filesDir.resolve("payment-rules.json").readText(), PaymentRules.builtIn.version, store.snapshot().version)
        assertNotEquals(DecisionAction.AUTO, PaymentDecisionEngine(store.snapshot()).evaluate(raw).action)
    }

    @Test fun invalidAndRegressingRulesCannotReplaceActiveRules() {
        val store = PaymentRuleStore(context)
        assertFalse(store.preview(pack().put("schemaVersion", 99).toString()).valid)
        assertFalse(store.preview(pack().put("successPhrases", org.json.JSONArray().put(".*")).toString()).valid)
        assertFalse(store.preview(pack().put("paidLabels", org.json.JSONArray().put("余额")).toString()).valid)
        assertFalse(store.preview(pack().put("successPhrases", org.json.JSONArray().put("待支付")).toString()).valid)
        assertFalse(store.activate(store.preview("not json")))
        assertEquals(PaymentRules.builtIn.version, store.snapshot().version)
    }

    @Test fun bundledRegressionCasesRunAgainstCandidate() {
        val store = PaymentRuleStore(context)
        val json = pack()
        json.put("samples", org.json.JSONArray().put(JSONObject()
            .put("id", "bad-expectation").put("source", "WECHAT").put("title", "微信支付")
            .put("text", "退款成功18元").put("action", "AUTO").put("amountCents", 1800)))
        val preview = store.preview(json.toString())
        assertFalse(preview.valid)
        assertTrue(preview.failures.any { it.contains("bad-expectation") })
    }

    @Test fun fallbackKeepsLastValidVersionAsRollbackTarget() {
        val store = PaymentRuleStore(context)
        val previous = pack().put("ruleSetVersion", "last-good").toString()
        context.filesDir.resolve("payment-rules.json").writeText(JSONObject().put("active", "broken").put("previous", previous).toString())
        assertEquals("last-good", store.snapshot().version)
        assertTrue(store.activate(store.preview(pack().put("ruleSetVersion", "next-good").toString())))
        assertTrue(store.rollback())
        assertEquals("last-good", store.snapshot().version)
    }

    @Test fun vocabularyCanBeNarrowedWithoutChangingEngine() {
        val store = PaymentRuleStore(context)
        val json = pack()
        val words = json.getJSONArray("successPhrases")
        val reduced = org.json.JSONArray((0 until words.length()).map(words::getString).filterNot { it == "自动扣费" })
        json.put("successPhrases", reduced)
        assertTrue(store.activate(store.preview(json.toString())))
        val raw = RawNotification(PaymentPackages.WECHAT, "微信支付", "自动扣费20元", "", emptyList(), 10000, "k", null)
        assertEquals(DecisionAction.REVIEW, PaymentDecisionEngine(store.snapshot()).evaluate(raw).action)
    }
}
