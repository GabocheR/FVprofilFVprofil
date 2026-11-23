package com.fvprofile.video

import com.fvprofile.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

/**
 * Détection automatique des 2 sauts d'une vidéo :
 * 1) balayage grossier (~20 Hz) de la hauteur des orteils par détection de posture ;
 * 2) pour chaque vol, échantillonnage fin puis ajustement d'une parabole z(t) sur la trajectoire des orteils ;
 *    décollage/réception = intersections de la parabole avec le sol (indépendant de l'échelle, corrige le seuil de détection) ;
 * 3) position de départ = dernier instant où la hanche est la plus basse avant la poussée → angles genou/hanche.
 * Moins précis que le marquage image par image.
 */
object AutoDetect {
    class Result(val trials: List<TrialData>, val message: String)
    private class S(val idx: Int, val lm: Landmarks?)

    private fun footY(l: Landmarks) = (l.toe ?: l.ankle).y
    private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)
    private fun median(l: List<Double>) = l.sorted().let { it[it.size / 2] }

    private fun quadFit(t: List<Double>, z: List<Double>): DoubleArray? {
        val n = t.size
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var z0 = 0.0; var z1 = 0.0; var z2 = 0.0
        for (i in 0 until n) { val x = t[i]; s1 += x; s2 += x * x; s3 += x * x * x; s4 += x * x * x * x; z0 += z[i]; z1 += z[i] * x; z2 += z[i] * x * x }
        val m = arrayOf(doubleArrayOf(s4, s3, s2, z2), doubleArrayOf(s3, s2, s1, z1), doubleArrayOf(s2, s1, n.toDouble(), z0))
        for (c in 0 until 3) {
            var piv = c
            for (r in c + 1 until 3) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-12) return null
            val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
            for (r in 0 until 3) if (r != c) { val f = m[r][c] / m[c][c]; for (k in c until 4) m[r][k] -= f * m[c][k] }
        }
        return doubleArrayOf(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]) // A, B, C
    }

    suspend fun run(src: VideoSource, fps: Double, pose: PoseTool, progress: (Float, String) -> Unit): Result {
        val empty = listOf(TrialData(), TrialData())
        val n = src.frameCount
        if (n < 10 || fps <= 0) return Result(empty, "Vidéo invalide.")
        val coarse = max(1, (fps * 0.05).roundToInt())
        val total = (n + coarse - 1) / coarse
        val samples = ArrayList<S>()
        var i = 0
        while (i < n) {
            currentCoroutineContext().ensureActive()
            val b = src.frame(i, 640)
            samples.add(S(i, if (b != null) pose.detect(b) else null))
            progress(0.7f * samples.size / total, "Analyse de la posture… ${samples.size}/$total")
            i += coarse
        }
        val valid = samples.filter { it.lm != null }
        if (valid.size < 8) return Result(empty, "Athlète non détecté : cadrez le corps entier, de profil, bien éclairé.")
        val ys = valid.map { footY(it.lm!!) }.sorted()
        val ground = ys[(ys.size * 0.85).toInt().coerceAtMost(ys.size - 1)]
        val shank = median(valid.map { dist(it.lm!!.knee, it.lm.ankle) })
        val thr = 0.12 * shank
        val air = samples.map { s -> s.lm?.let { footY(it) < ground - thr } ?: false }

        val segs = ArrayList<IntRange>()
        var k = 0
        while (k < samples.size) {
            if (!air[k]) { k++; continue }
            var e = k
            while (true) {
                val n1 = e + 1
                if (n1 >= samples.size) break
                if (air[n1]) e = n1 else if (samples[n1].lm == null && n1 + 1 < samples.size && air[n1 + 1]) e = n1 + 1 else break
            }
            if ((e - k + 1) * coarse / fps >= 0.10) segs.add(k..e)
            k = e + 1
        }
        if (segs.isEmpty()) return Result(empty, "Aucun saut détecté. Passez en mode manuel (image par image).")
        fun peak(r: IntRange) = r.maxOf { j -> samples[j].lm?.let { ground - footY(it) } ?: 0.0 }
        val chosen = segs.sortedByDescending { peak(it) }.take(2).sortedBy { it.first }
        val notes = ArrayList<String>()
        if (segs.size > 2) notes.add("${segs.size} vols détectés, les 2 plus hauts conservés")
        if (chosen.size < 2) notes.add("1 seul saut détecté sur 2")

        val trials = ArrayList<TrialData>()
        var prevLanding = 0.0
        val step = max(1, (fps * 0.012).roundToInt())
        chosen.forEachIndexed { ci, seg ->
            val a0 = samples[seg.first].idx; val a1 = samples[seg.last].idx
            val t0 = a0 / fps; val t1 = a1 / fps
            val base = valid.filter { s -> val t = s.idx / fps; (t in (t0 - 0.8)..(t0 - 0.15)) || (t in (t1 + 0.15)..(t1 + 0.6)) }
                .filter { s -> footY(s.lm!!) > ground - thr }.map { footY(it.lm!!) }
            val gl = if (base.size >= 3) median(base) else ground
            val pts = ArrayList<Pair<Double, Double>>()
            var f = max(0, a0 - coarse)
            val to = min(n - 1, a1 + coarse)
            while (f <= to) {
                currentCoroutineContext().ensureActive()
                val lm = src.frame(f, 640)?.let { pose.detect(it) }
                if (lm != null) pts.add(f / fps to gl - footY(lm))
                f += step
            }
            progress(0.7f + 0.3f * (ci + 1) / chosen.size, "Affinage du saut ${ci + 1}…")
            val up = pts.filter { it.second > 0.8 * thr }
            var tTake: Double; var tLand: Double
            val tRef = if (up.isNotEmpty()) up.map { it.first }.average() else (t0 + t1) / 2
            val q = if (up.size >= 6) quadFit(up.map { it.first - tRef }, up.map { it.second }) else null
            var ok = false; tTake = t0; tLand = t1
            if (q != null && q[0] < 0) {
                val disc = q[1] * q[1] - 4 * q[0] * q[2]
                if (disc > 0) {
                    val r1 = (-q[1] + sqrt(disc)) / (2 * q[0]) + tRef; val r2 = (-q[1] - sqrt(disc)) / (2 * q[0]) + tRef
                    val lo = min(r1, r2); val hi = max(r1, r2)
                    if (hi - lo in 0.1..1.2 && lo > up.first().first - 0.2 && hi < up.last().first + 0.2) { tTake = lo; tLand = hi; ok = true }
                }
            }
            if (!ok) { tTake = up.firstOrNull()?.first ?: t0; tLand = up.lastOrNull()?.first ?: t1; notes.add("saut ${ci + 1} : estimation par seuil (moins précise)") }
            // Position de départ + angles
            val win = valid.filter { s -> val t = s.idx / fps; t >= prevLanding + 0.5 && t <= tTake - 0.05 && footY(s.lm!!) > ground - thr }
            var start: Int? = null; var knee: Double? = null; var hip: Double? = null
            if (win.size >= 2) {
                val maxHip = win.maxOf { it.lm!!.hip.y }
                val low = win.filter { it.lm!!.hip.y >= maxHip - 0.1 * shank }
                val use = low.takeLast(5)
                start = low.last().idx
                knee = use.map { kneeAngle(it.lm!!) }.average(); hip = use.map { hipAngle(it.lm!!) }.average()
            } else notes.add("saut ${ci + 1} : position de départ non trouvée")
            trials.add(TrialData(start, (tTake * fps).roundToInt(), (tLand * fps).roundToInt(), knee, hip))
            prevLanding = tLand
        }
        while (trials.size < 2) trials.add(TrialData())
        val tas = trials.mapNotNull { it.tA(fps) }.joinToString(" / ") { "%.3f s".format(it) }
        return Result(trials, "Détection automatique terminée (tA : $tas)." + if (notes.isNotEmpty()) " " + notes.joinToString(" ; ") + "." else "")
    }
}
