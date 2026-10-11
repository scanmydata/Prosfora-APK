package gr.prosfora.app.google

/**
 * Το τελευταίο access token που πήρε η εφαρμογή, όσο είναι ακόμη έγκυρο.
 *
 * Υπάρχει για να μπορεί να γράψει στη βάση **και** όποιος δεν έχει οθόνη να
 * δείξει. Η `Identity.authorize` επιστρέφει κάποιες φορές «θέλω έγκριση από τον
 * χρήστη» — στο παρασκήνιο αυτό σημαίνει ότι δεν γίνεται τίποτα, και εκεί
 * κολλούσε ο άμεσος συγχρονισμός: μια πληρωμή που σημειωνόταν περίμενε να
 * πατήσει κάποιος το κουμπί συγχρονισμού.
 *
 * Τα tokens της Google ζουν περίπου μία ώρα· εδώ κρατιούνται για 45 λεπτά ώστε
 * να μη χρησιμοποιηθεί ποτέ ένα που έληξε στο μεταξύ. Μένει στη μνήμη και μόνο:
 * δεν γράφεται πουθενά.
 */
object AccessTokenCache {
    private const val LIFETIME_MS = 45L * 60L * 1000L

    @Volatile private var token: String? = null
    @Volatile private var validUntil: Long = 0L

    fun remember(fresh: String?) {
        if (fresh.isNullOrBlank()) return
        token = fresh
        validUntil = System.currentTimeMillis() + LIFETIME_MS
    }

    /** Το token, αν υπάρχει και δεν έχει λήξει. */
    fun valid(): String? =
        token?.takeIf { System.currentTimeMillis() < validUntil }

    /** Μετά από απόρριψη της Google: δεν ξαναδοκιμάζεται το ίδιο. */
    fun forget() {
        token = null
        validUntil = 0L
    }
}
