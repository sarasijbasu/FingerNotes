package app.fingernotes.core

/** Hybrid Logical Clock — same string format as extension/src/core/hlc.ts (docs/PROTOCOL.md §3). */
class Hlc(val node: String, last: String? = null, private val now: () -> Long = System::currentTimeMillis) {
    private var ms = 0L
    private var counter = 0

    init {
        if (last != null) parse(last).let { ms = it.first; counter = it.second }
    }

    @Synchronized
    fun tick(): String {
        val wall = now()
        if (wall > ms) { ms = wall; counter = 0 } else counter++
        return current()
    }

    @Synchronized
    fun receive(remote: String) {
        val (rms, rc) = parse(remote)
        val wall = now()
        val max = maxOf(wall, ms, rms)
        counter = when {
            max == ms && max == rms -> maxOf(counter, rc) + 1
            max == ms -> counter + 1
            max == rms -> rc + 1
            else -> 0
        }
        ms = max
    }

    @Synchronized
    fun current() = format(ms, counter, node)

    companion object {
        fun format(ms: Long, counter: Int, node: String) =
            ms.toString().padStart(15, '0') + "-" + minOf(counter, 99999).toString().padStart(5, '0') + "-" + node
        fun parse(s: String): Pair<Long, Int> {
            val p = s.split("-")
            return p[0].toLong() to p[1].toInt()
        }
    }
}
