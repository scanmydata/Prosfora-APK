package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.google.GoogleSettings

/**
 * Το tab «Εργαζόμενοι» δεν επιτρέπεται να δημιουργεί εργαζόμενους.
 * Μετά το scan γράφεται ξανά αποκλειστικά από την canonical local βάση.
 */
object EmployeeSheetSanitizer {
    private val HEADER = listOf(
        "ID_Εργαζόμενου", "Όνομα", "Ψευδώνυμο", "Κωδικός",
        "Ενημερώθηκε", "Διαγραμμένο", "Αποχώρηση", "ΑΜ ΙΚΑ",
    )

    suspend fun sync(context: Context, accessToken: String) {
        val app = context.applicationContext
        val settings = GoogleSettings(app)
        val spreadsheetId = settings.spreadsheetId ?: return
        val client = SheetsClient(accessToken)
        val titles = client.sheetTitles(spreadsheetId)
        if (SheetSync.TAB_PEOPLE !in titles) client.addSheet(spreadsheetId, SheetSync.TAB_PEOPLE)

        val people = ProsforaDatabase.get(app).employeeDao().allForSync()
            .filter { !it.deleted }
            .sortedBy { it.name.uppercase() }

        val rows = listOf(HEADER) + people.map {
            listOf(
                it.id,
                it.name,
                it.alias,
                it.code,
                it.updatedAt.toString(),
                if (it.deleted) "1" else "0",
                it.leftDay?.toString().orEmpty(),
                it.amIka,
            )
        }
        client.replaceRows(spreadsheetId, SheetSync.TAB_PEOPLE, rows)
    }
}
