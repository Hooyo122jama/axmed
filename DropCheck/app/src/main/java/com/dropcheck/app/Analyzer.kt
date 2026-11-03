package com.dropcheck.app

import android.content.Context
import java.util.Locale

data class Profile(
    val cons: Double, val fuel: Double, val wear: Double,
    val fixedM: Double, val kmM: Double, val targetH: Double, val minKm: Double
) {
    companion object {
        val KEYS = listOf("cons", "fuel", "wear", "fixedM", "kmM", "targetH", "minKm")
        val DEFAULTS = mapOf("cons" to 6.5, "fuel" to 1.85, "wear" to 0.08, "fixedM" to 300.0,
            "kmM" to 2000.0, "targetH" to 15.0, "minKm" to 0.9)

        fun load(c: Context): Profile {
            val s = c.getSharedPreferences("profile", Context.MODE_PRIVATE)
            fun g(k: String) = s.getFloat(k, DEFAULTS[k]!!.toFloat()).toDouble()
            return Profile(g("cons"), g("fuel"), g("wear"), g("fixedM"), g("kmM"), g("targetH"), g("minKm"))
        }

        fun save(c: Context, v: Map<String, Double>) {
            val e = c.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            v.forEach { (k, x) -> e.putFloat(k, x.toFloat()) }
            e.apply()
        }
    }
}

enum class Verdict { ACCEPT, REVIEW, REJECT }
data class Offer(val pay: Double, val pickKm: Double, val dropKm: Double, val mins: Double)
data class Result(val verdict: Verdict, val score: Double, val net: Double,
                  val perKm: Double, val perH: Double, val reasons: List<String>)

fun eur(x: Double) = String.format(Locale.FRANCE, "%.2f €", x)

object Analyzer {
    fun analyze(o: Offer, p: Profile): Result {
        val km = o.pickKm + o.dropKm
        val fixedKm = if (p.kmM > 0) p.fixedM / p.kmM else 0.0
        val cost = km * (p.cons / 100 * p.fuel + p.wear + fixedKm)
        val net = o.pay - cost
        val perKm = net / km
        val perH = net / (o.mins / 60)
        val rH = perH / p.targetH
        val rK = perKm / p.minKm
        val score = ((maxOf(rH, 0.0) + maxOf(rK, 0.0)) / 2 * 7).coerceIn(0.0, 10.0)
        val verdict = when {
            net <= 0 || rH < 0.7 || rK < 0.7 -> Verdict.REJECT
            rH >= 1 && rK >= 1 -> Verdict.ACCEPT
            else -> Verdict.REVIEW
        }
        val why = mutableListOf<String>()
        why += (if (rH >= 1) "✓ " else "✕ ") + "${eur(perH)}/h vs target ${eur(p.targetH)}"
        why += (if (rK >= 1) "✓ " else "✕ ") + "${eur(perKm)}/km vs minimum ${eur(p.minKm)}"
        if (net <= 0) why += "✕ You lose money after costs"
        if (o.dropKm > 0 && o.pickKm > o.dropKm * 0.8) why += "✕ Long trip to the restaurant"
        return Result(verdict, score, net, perKm, perH, why)
    }

    /** Reads an Uber Eats offer from OCR text. Adjust the regexes if your screen differs. */
    fun parse(text: String): Offer? {
        val t = text.replace('\u00A0', ' ')
        val money = Regex("""€\s*(\d{1,3}(?:[.,]\d{1,2})?)|(\d{1,3}(?:[.,]\d{1,2})?)\s*€""")
            .findAll(t).mapNotNull { m ->
                m.groupValues[1].ifEmpty { m.groupValues[2] }.replace(',', '.').toDoubleOrNull()
            }.toList()
        val km = Regex("""(\d+(?:[.,]\d+)?)\s*km""", RegexOption.IGNORE_CASE)
            .findAll(t).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }.toList()
        val mins = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
            .findAll(t).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (money.isEmpty() || km.isEmpty() || mins.isEmpty()) return null
        // First distance = to the restaurant, second = to the customer. One distance = treated as delivery.
        val pick = if (km.size >= 2) km[0] else 0.0
        val drop = if (km.size >= 2) km[1] else km[0]
        return Offer(money.max(), pick, drop, mins.take(2).sum())
    }
}
