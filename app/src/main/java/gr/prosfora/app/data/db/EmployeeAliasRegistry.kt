package gr.prosfora.app.data.db

import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only in-memory view of employee aliases for lightweight UI/domain
 * objects such as DebtEntity.title that do not have a Context/DAO available.
 * The canonical key is the normalized AM IKA.
 */
object EmployeeAliasRegistry {
    private val aliases = ConcurrentHashMap<String, String>()

    fun refresh(employees: Collection<EmployeeEntity>) {
        aliases.clear()
        employees.forEach { employee ->
            val ika = EmployeeEntity.normalizeIka(employee.amIka)
            val alias = employee.alias.trim()
            if (ika.isNotBlank() && alias.isNotBlank()) {
                aliases[ika] = alias
            }
        }
    }

    fun aliasFor(amIka: String): String =
        aliases[EmployeeEntity.normalizeIka(amIka)].orEmpty()
}
