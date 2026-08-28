package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.debt.DebtImporter
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Υπολογίζει το κόστος ενσήμων την ίδια στιγμή που εισάγεται η μισθοδοσία.
 * Χρησιμοποιεί το OCR κείμενο που έχει ήδη παραχθεί από το DebtImporter — δεν
 * κάνει δεύτερο OCR — και αποθηκεύει το αποτέλεσμα στο κοινό Sheet.
 */
object PayrollCostIndexer {
    private val amountRegex = Regex("[0-9][0-9.]*,[0-9]{2}")

    suspend fun index(context: Context, accessToken: String, found: DebtImporter.Found) = withContext(Dispatchers.IO) {
        val payroll = found.debts.filter { it.kind.perPerson && it.personName.isNotBlank() }
        if (payroll.isEmpty() || found.ocrText.isBlank()) return@withContext
        val settings = GoogleSettings(context.applicationContext)
        val spreadsheetId = settings.spreadsheetId ?: return@withContext
        val client = SheetsClient(accessToken)
        val existingTabs = client.sheetTitles(spreadsheetId)
        if (SheetSync.TAB_EMPLOYEE_COSTS !in existingTabs) {
            client.addSheet(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS)
            client.replaceRows(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS, listOf(SheetSync.EMPLOYEE_COST_HEADER))
        }
        val current = client.readRows(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS)
        val data = current.drop(1).filter { it.firstOrNull()?.isNotBlank() == true }
            .map { row -> List(SheetSync.EMPLOYEE_COST_HEADER.size) { i -> row.getOrElse(i) { "" } } }
            .toMutableList()

        payroll.groupBy { it.personName to it.personCode }.forEach { (person, personRows) ->
            val employee = EmployeeEntity(
                id = EmployeeEntity.idFor(person.first),
                name = person.first,
                code = person.second,
            )
            val cost = findCost(found.ocrText, employee)
            val year = personRows.firstOrNull()?.periodYear ?: return@forEach
            val month = personRows.firstOrNull()?.periodMonth ?: return@forEach
            if (year <= 0 || month !in 1..12) return@forEach
            val key = "$year-$month"
            val replacement = listOf(
                employee.id,
                employee.name,
                year.toString(),
                month.toString(),
                personRows.sumOf { it.amount }.toString(),
                cost.toString(),
                found.driveFileId,
                System.currentTimeMillis().toString(),
            )
            val index = data.indexOfFirst {
                it.getOrElse(0) { "" } == employee.id &&
                    "${it.getOrElse(2) { "" }}-${it.getOrElse(3) { "" }}" == key
            }
            if (index >= 0) data[index] = replacement else data += replacement
        }
        client.replaceRows(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS, listOf(SheetSync.EMPLOYEE_COST_HEADER) + data)
    }

    private fun findCost(text: String, employee: EmployeeEntity): Double {
        val wanted = employee.name.trim().split(Regex("\\s+")).filter { it.length >= 2 }
        val lines = text.lines()
        val start = lines.indexOfFirst { line ->
            line.contains(employee.code) && wanted.all { token -> line.contains(token, ignoreCase = true) }
        }
        if (start < 0) return 0.0
        for (index in start + 1 until lines.size) {
            val line = lines[index].trim()
            if (line.isBlank()) continue
            if (Regex("^\\d{1,3}\\s+[A-ZΑ-Ω0-9]{2,6}\\s+").containsMatchIn(line)) break
            if (line.any { it.isLetter() }) continue
            val numbers = line.split(Regex("\\s+")).mapNotNull {
                amountRegex.matchEntire(it)?.value?.replace(".", "")?.replace(',', '.')?.toDoubleOrNull()
            }
            if (numbers.size >= 8) return numbers.getOrNull(3) ?: 0.0
        }
        return 0.0
    }
}
