package gr.prosfora.app.notify

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import gr.prosfora.app.debt.AadeInstallmentParser
import gr.prosfora.app.debt.DebtImporter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Προσωρινή ουρά για νέες οφειλές ΑΑΔΕ που έχουν πλάνο δόσεων.
 *
 * Τα δεδομένα μένουν εκτός Room μέχρι ο χρήστης να επιλέξει από την ειδοποίηση
 * αν θέλει μία συνολική οφειλή ή ξεχωριστές δόσεις.
 */
object PendingDebtNotificationStore {
    private const val PREFS = "pending_debt_notifications"
    private const val KEY = "queue"

    fun enqueue(context: Context, found: List<DebtImporter.Found>) {
        if (found.isEmpty()) return
        val merged = (load(context) + found)
            .distinctBy { it.driveFileId }
        save(context, merged)
    }

    fun peek(context: Context): DebtImporter.Found? = load(context).firstOrNull()

    fun consumeFirst(context: Context) {
        val current = load(context)
        save(context, current.drop(1))
    }

    fun fileIds(context: Context): Set<String> =
        load(context).map { it.driveFileId }.filter { it.isNotBlank() }.toSet()

    private fun load(context: Context): List<DebtImporter.Found> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val debtsJson = item.optJSONArray("debts") ?: JSONArray()
                    val debts = buildList {
                        for (j in 0 until debtsJson.length()) {
                            add(debtFromJson(debtsJson.getJSONObject(j)))
                        }
                    }
                    val planJson = item.optJSONObject("installmentPlan")
                    val plan = planJson?.let {
                        AadeInstallmentParser.Info(
                            totalAmount = it.getDouble("totalAmount"),
                            installmentAmount = it.getDouble("installmentAmount"),
                            installmentCount = it.getInt("installmentCount"),
                            firstDueDay = it.getLong("firstDueDay"),
                        )
                    }
                    add(
                        DebtImporter.Found(
                            fileName = item.optString("fileName"),
                            driveFileId = item.optString("driveFileId"),
                            debts = debts,
                            note = item.optString("note"),
                            installmentPlan = plan,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, found: List<DebtImporter.Found>) {
        val array = JSONArray()
        found.forEach { item ->
            val json = JSONObject()
                .put("fileName", item.fileName)
                .put("driveFileId", item.driveFileId)
                .put("note", item.note)

            val debts = JSONArray()
            item.debts.forEach { debts.put(debtToJson(it)) }
            json.put("debts", debts)

            item.installmentPlan?.let { plan ->
                json.put(
                    "installmentPlan",
                    JSONObject()
                        .put("totalAmount", plan.totalAmount)
                        .put("installmentAmount", plan.installmentAmount)
                        .put("installmentCount", plan.installmentCount)
                        .put("firstDueDay", plan.firstDueDay),
                )
            }
            array.put(json)
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun debtToJson(debt: DebtEntity): JSONObject = JSONObject()
        .put("id", debt.id)
        .put("kind", debt.kind.name)
        .put("periodMonth", debt.periodMonth)
        .put("periodYear", debt.periodYear)
        .putNullableLong("dueDay", debt.dueDay)
        .put("amount", debt.amount)
        .put("reference", debt.reference)
        .put("description", debt.description)
        .put("personName", debt.personName)
        .put("personCode", debt.personCode)
        .put("paid", debt.paid)
        .putNullableLong("paidAt", debt.paidAt)
        .putNullableLong("paidDay", debt.paidDay)
        .put("source", debt.source)
        .put("createdBy", debt.createdBy)
        .put("driveFileId", debt.driveFileId)
        .put("createdAt", debt.createdAt)
        .put("updatedAt", debt.updatedAt)
        .put("deleted", debt.deleted)

    private fun debtFromJson(json: JSONObject): DebtEntity = DebtEntity(
        id = json.optString("id"),
        kind = runCatching { DebtKind.valueOf(json.optString("kind")) }.getOrDefault(DebtKind.AADE),
        periodMonth = json.optInt("periodMonth"),
        periodYear = json.optInt("periodYear"),
        dueDay = json.optNullableLong("dueDay"),
        amount = json.optDouble("amount", 0.0),
        reference = json.optString("reference"),
        description = json.optString("description"),
        personName = json.optString("personName"),
        personCode = json.optString("personCode"),
        paid = json.optBoolean("paid", false),
        paidAt = json.optNullableLong("paidAt"),
        paidDay = json.optNullableLong("paidDay"),
        source = json.optString("source"),
        createdBy = json.optString("createdBy"),
        driveFileId = json.optString("driveFileId"),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        deleted = json.optBoolean("deleted", false),
    )

    private fun JSONObject.putNullableLong(key: String, value: Long?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key)) null else optLong(key)
}
