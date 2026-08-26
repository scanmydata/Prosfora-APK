package gr.prosfora.app.debt

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import kotlinx.coroutines.flow.Flow

class DebtRepository(context: Context) {

    private val debts = ProsforaDatabase.get(context).debtDao()

    fun observeAll(): Flow<List<DebtEntity>> = debts.observeAll()

    suspend fun save(debt: DebtEntity) =
        debts.upsert(debt.copy(updatedAt = System.currentTimeMillis()))

    /**
     * Αποθηκεύει ό,τι διάβασε η σάρωση. Ό,τι υπάρχει ήδη κρατάει την κατάσταση
     * πληρωμής του: ένα παραστατικό που ξαναδιαβάστηκε δεν σημαίνει ότι η
     * οφειλή ξαναγεννήθηκε απλήρωτη.
     */
    suspend fun saveAll(items: List<DebtEntity>) {
        val now = System.currentTimeMillis()
        val merged = items.map { incoming ->
            val existing = debts.getById(incoming.id)
            if (existing == null) {
                incoming.copy(updatedAt = now)
            } else {
                incoming.copy(
                    paid = existing.paid,
                    paidAt = existing.paidAt,
                    createdAt = existing.createdAt,
                    updatedAt = now,
                    deleted = false,
                )
            }
        }
        debts.upsertAll(merged)
    }

    suspend fun setPaid(id: String, paid: Boolean) {
        val now = System.currentTimeMillis()
        debts.markPaid(id, paid, if (paid) now else null, now)
    }

    suspend fun delete(id: String) = debts.softDelete(id, System.currentTimeMillis())

    /** Ποια αρχεία του Drive έχουν ήδη διαβαστεί — η σάρωση τα προσπερνάει. */
    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()
}
