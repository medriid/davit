package dev.medriid.davit.vtop

/**
 * VIT's slot timetable: which weekday and time each slot code meets. Bundled in
 * `slot_times.txt`, which isn't in the repository (see README); without it the week is empty. Theory slots are single codes (A1, TA1);
 * labs are consecutive pairs (L31+L32), so a registered "L31+L32+L45+L46" is two pairs.
 */
object SlotTimes {
    private val map: Map<String, List<Meeting>> by lazy { load() }

    /** Every weekly meeting for a registered slot string such as "A1+TA1". */
    fun meetings(slot: String): List<Meeting> = splitSlots(slot).flatMap { map[it].orEmpty() }

    /** "A1+TA1" → [A1, TA1]; "L31+L32+L45+L46" → [L31+L32, L45+L46]. */
    fun splitSlots(slot: String): List<String> {
        val parts = slot.split('+').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || !parts.all { it.startsWith("L") }) return parts
        return parts.chunked(2).map { it.joinToString("+") }
    }

    private fun load(): Map<String, List<Meeting>> {
        val text = SlotTimes::class.java.getResourceAsStream("/dev/medriid/davit/vtop/slot_times.txt")
            ?.bufferedReader()?.use { it.readText() }
            ?: return emptyMap()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val fields = line.split(' ')
                val slot = fields[0]
                slot to fields.drop(1).map { f ->
                    val (day, range) = f.split(':')
                    val (start, end) = range.split('-')
                    Meeting(day.toInt(), toMinutes(start), toMinutes(end), slot)
                }
            }
    }

    private fun toMinutes(hhmm: String) = hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(2).toInt()
}
