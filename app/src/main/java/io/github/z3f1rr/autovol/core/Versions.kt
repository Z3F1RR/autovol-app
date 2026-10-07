package io.github.z3f1rr.autovol.core

/**
 * Version names: "1.2.3", tags "v1.2.3", CI builds "0.1.0-dev.42", local "0.1.0-local".
 * A pre-release ("-dev.N", "-local") is older than the same base without suffix.
 */
object Versions {
    private data class Parsed(val base: List<Int>, val pre: String?)

    private fun parse(v: String): Parsed {
        val s = v.trim().removePrefix("v").removePrefix("V")
        val dash = s.indexOf('-')
        val basePart = if (dash >= 0) s.substring(0, dash) else s
        val pre = if (dash >= 0) s.substring(dash + 1).ifEmpty { null } else null
        val base = basePart.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return Parsed(base, pre)
    }

    private fun comparePre(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return -1
            val y = pb.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val c = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.base.size, pb.base.size)) {
            val c = (pa.base.getOrNull(i) ?: 0).compareTo(pb.base.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return when {
            pa.pre == null && pb.pre == null -> 0
            pa.pre == null -> 1
            pb.pre == null -> -1
            else -> comparePre(pa.pre, pb.pre)
        }
    }

    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0
}
