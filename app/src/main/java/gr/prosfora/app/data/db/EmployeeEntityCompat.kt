package gr.prosfora.app.data.db

/**
 * Compatibility helper for older UI call sites that still have only a name.
 * New payroll data must use EmployeeEntity.idForAmIka(amIka).
 */
fun EmployeeEntity.Companion.idFor(name: String): String = EmployeeEntity.legacyIdFor(name)
