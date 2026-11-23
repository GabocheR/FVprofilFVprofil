@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.fvprofile

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fvprofile.core.*
import com.fvprofile.ui.*
import com.fvprofile.video.AutoDetect
import com.fvprofile.video.PoseTool
import com.fvprofile.video.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val markNames = listOf("Départ", "Décollage", "Réception")
private val ptNames = listOf("Épaule", "Hanche", "Genou", "Cheville")
private val ptColors = listOf(Color(0xFFFFB74D), Color(0xFFFF5252), Color(0xFF64B5F6), Color(0xFF81C784))

/** Zoom/déplacement partagés entre la vue normale et le plein écran. */
class ZoomState {
    var z by mutableFloatStateOf(1f)
    var tx by mutableFloatStateOf(0f)
    var ty by mutableFloatStateOf(0f)
    fun reset() { z = 1f; tx = 0f; ty = 0f }
}

@Composable
fun FrameView(bmp: Bitmap?, zs: ZoomState, pts: List<Offset?>, active: Int, measuring: Boolean, isFull: Boolean,
              modifier: Modifier, onToggleFull: () -> Unit, onTap: (Offset) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun base(): Triple<Float, Float, Float>? {
        val b = bmp ?: return null
        if (box.width == 0 || box.height == 0) return null
        val s = min(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
        return Triple(s, (box.width - b.width * s) / 2f, (box.height - b.height * s) / 2f)
    }
    fun toScreen(o: Offset): Offset {
        val g = base() ?: return o
        val cx = box.width / 2f; val cy = box.height / 2f
        val px = o.x * g.first + g.second; val py = o.y * g.first + g.third
        return Offset(cx + (px - cx) * zs.z + zs.tx, cy + (py - cy) * zs.z + zs.ty)
    }
    fun toBitmap(q: Offset): Offset? {
        val g = base() ?: return null
        val cx = box.width / 2f; val cy = box.height / 2f
        val px = cx + (q.x - cx - zs.tx) / zs.z; val py = cy + (q.y - cy - zs.ty) / zs.z
        return Offset((px - g.second) / g.first, (py - g.third) / g.first)
    }
    fun zoomBy(k: Float) {
        val nz = (zs.z * k).coerceIn(1f, 10f); val r = nz / zs.z
        val mx = (nz - 1f) * box.width / 2f; val my = (nz - 1f) * box.height / 2f
        zs.z = nz; zs.tx = (zs.tx * r).coerceIn(-mx, mx); zs.ty = (zs.ty * r).coerceIn(-my, my)
    }
    Box(modifier.background(Color.Black).clipToBounds().onSizeChanged { box = it }
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val nz = (zs.z * zoom).coerceIn(1f, 10f); val k = nz / zs.z
                val cx = size.width / 2f; val cy = size.height / 2f
                val ntx = (centroid.x - cx) - k * (centroid.x - cx - zs.tx) + pan.x
                val nty = (centroid.y - cy) - k * (centroid.y - cy - zs.ty) + pan.y
                val mx = (nz - 1f) * size.width / 2f; val my = (nz - 1f) * size.height / 2f
                zs.z = nz; zs.tx = ntx.coerceIn(-mx, mx); zs.ty = nty.coerceIn(-my, my)
            }
        }
        .pointerInput(measuring, bmp) { if (measuring) detectTapGestures { o -> toBitmap(o)?.let { onTap(it) } } }) {
        bmp?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(img, null, Modifier.fillMaxSize().graphicsLayer { scaleX = zs.z; scaleY = zs.z; translationX = zs.tx; translationY = zs.ty }, contentScale = ContentScale.Fit)
        } ?: CircularProgressIndicator(color = RedBright, modifier = Modifier.align(Alignment.Center))
        if (measuring) Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until pts.size - 1) { val p = pts[i]; val q = pts[i + 1]; if (p != null && q != null) drawLine(Color.White.copy(alpha = 0.8f), toScreen(p), toScreen(q), 3f) }
            pts.forEachIndexed { i, p ->
                if (p != null) { val s = toScreen(p)
                    drawCircle(ptColors[i], if (i == active) 20f else 14f, s, style = Stroke(if (i == active) 4f else 3f))
                    drawCircle(ptColors[i], 3f, s) }
            }
        }
        Text("×${"%.1f".format(zs.z)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).clickable { onToggleFull() }.padding(6.dp)) {
            Icon(if (isFull) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, if (isFull) "Quitter le plein écran" else "Plein écran", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("−" to { zoomBy(1f / 1.5f) }, "+" to { zoomBy(1.5f) }, "1×" to { zs.reset() }).forEach { (l, f) ->
                Box(Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).clickable { f() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(l, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AnalysisScreen(vm: AppVM, sid: String, setId: String, autoStart: Boolean, onMenu: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = vm.session(sid) ?: return
    val set0 = session.sets.firstOrNull { it.id == setId } ?: return
    val path = set0.videoPath
    if (path == null) {
        PageV(vm, "Analyse — ${fmt(set0.loadKg, 1)} kg", onMenu) {
            Card2 {
                H("Vidéo supprimée")
                Body("Les marqueurs et le profil sont conservés ; seule la relecture image par image n'est plus possible.")
                set0.trials.forEachIndexed { t, d ->
                    val tA = d.tA(set0.fps)
                    Body("Essai ${t + 1} : " + (if (tA != null) "tA ${fmt(tA, 3)} s → ${fmt(Mech.heightFromFlight(tA) * 100, 1)} cm" else "non mesuré") +
                        " · genou ${d.kneeDeg?.let { fmt(it, 0) + "°" } ?: "—"} · hanche ${d.hipDeg?.let { fmt(it, 0) + "°" } ?: "—"}", TextHi)
                }
                BtnPrimary("Refilmer cette charge", { vm.replace(Screen.Record(sid, setId)) })
            }
        }
        return
    }
    val holder = remember { arrayOfNulls<VideoSource>(1) }
    val zs = remember { ZoomState() }
    var src by remember { mutableStateOf<VideoSource?>(null) }
    var fps by remember { mutableDoubleStateOf(set0.fps) }
    var frame by remember { mutableIntStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var trials by remember { mutableStateOf(set0.trials) }
    var target by remember { mutableIntStateOf(set0.trials.flatMap { listOf(it.startFrame, it.takeoff, it.landing) }.indexOfFirst { it == null }.let { if (it < 0) 1 else it }) }
    var measuring by remember { mutableStateOf<Int?>(null) }
    var pts by remember { mutableStateOf<List<Offset?>>(listOf(null, null, null, null)) }
    var activePt by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressMsg by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var fpsDialog by remember { mutableStateOf(false) }
    var fpsText by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val pose = remember { PoseTool() }
    var autoDone by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { holder[0]?.close(); pose.close() } }
    LaunchedEffect(path) {
        val s = withContext(Dispatchers.IO) { runCatching { VideoSource(path) }.getOrNull() }
        holder[0] = s; src = s
        if (s != null && fps <= 0) fps = s.fpsDetected
        if (s == null) info = "Impossible d'ouvrir la vidéo."
        else {
            val m = trials.flatMap { listOf(it.startFrame, it.takeoff, it.landing) }
            m.getOrNull(target)?.let { frame = it } ?: m.filterNotNull().firstOrNull()?.let { frame = max(0, it - 2) }
        }
    }
    LaunchedEffect(src, frame) {
        val s = src ?: return@LaunchedEffect
        delay(30)
        val b = withContext(Dispatchers.IO) { s.frame(frame, 1280) }
        if (b != null) bmp = b
    }
    LaunchedEffect(trials, fps) { vm.updateSet(sid, setId) { it.copy(trials = trials, fps = fps) } }

    fun getMark(t: Int, k: Int): Int? = trials[t].let { when (k) { 0 -> it.startFrame; 1 -> it.takeoff; else -> it.landing } }
    fun setMark(t: Int, k: Int, v: Int) {
        trials = trials.toMutableList().also { l -> val d = l[t]; l[t] = when (k) { 0 -> d.copy(startFrame = v); 1 -> d.copy(takeoff = v); else -> d.copy(landing = v) } }
    }
    fun markHere() {
        val cur = target
        setMark(cur / 3, cur % 3, frame)
        val next = (1..6).map { (cur + it) % 6 }.firstOrNull { getMark(it / 3, it % 3) == null }
        if (next != null) target = next
    }
    fun ang(a: Offset?, v: Offset?, b: Offset?): Double? =
        if (a == null || v == null || b == null) null else angleAt(P(a.x.toDouble(), a.y.toDouble()), P(v.x.toDouble(), v.y.toDouble()), P(b.x.toDouble(), b.y.toDouble()))
    val kneeNow = ang(pts[1], pts[2], pts[3])
    val hipNow = ang(pts[0], pts[1], pts[2])
    fun placePoint(o: Offset) { pts = pts.toMutableList().also { it[activePt] = o } }   // pas de passage automatique au point suivant
    fun validateAngles() {
        val t = measuring ?: return
        trials = trials.toMutableList().also { l -> l[t] = l[t].copy(startFrame = frame, kneeDeg = kneeNow ?: l[t].kneeDeg, hipDeg = hipNow ?: l[t].hipDeg) }
        measuring = null
    }
    fun detectAI() {
        val b = bmp ?: return
        scope.launch {
            val lm = withContext(Dispatchers.Default) { pose.detect(b) }
            if (lm != null) pts = listOf(lm.shoulder, lm.hip, lm.knee, lm.ankle).map { Offset(it.x.toFloat(), it.y.toFloat()) }
            else info = "Posture non détectée sur cette image."
        }
    }
    fun runAuto() {
        val s = src ?: return
        running = true; info = ""
        job = scope.launch {
            try {
                val res = withContext(Dispatchers.Default) { AutoDetect.run(s, fps, pose) { p, m -> progress = p; progressMsg = m } }
                trials = res.trials; info = res.message
                res.trials.firstNotNullOfOrNull { it.takeoff }?.let { frame = max(0, it - 3) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { info = "Échec de l'analyse automatique : ${e.message}" }
            finally { running = false }
        }
    }
    LaunchedEffect(src) { if (autoStart && src != null && !autoDone) { autoDone = true; runAuto() } }

    val frames = src?.frameCount ?: 0
    val tCur = target / 3; val kCur = target % 3

    @Composable fun MarkButton() {
        val existing = getMark(tCur, kCur)
        Button(onClick = { markHere() }, enabled = src != null && measuring == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp), shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White, disabledContainerColor = Surface2, disabledContentColor = TextMid)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ESSAI ${tCur + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Marquer ${markNames[kCur]}", fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (existing != null) Text("actuel : image $existing — rappuyez pour corriger", fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
    @Composable fun Scrubber() {
        Slider(frame.toFloat(), { frame = it.toInt() }, valueRange = 0f..max(1, frames - 1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = RedBright, activeTrackColor = Red, inactiveTrackColor = Outline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("−10" to -10, "−1" to -1, "+1" to 1, "+10" to 10).forEach { (l, d) ->
                BtnOutline(l, { frame = (frame + d).coerceIn(0, max(0, frames - 1)) }, Modifier.weight(1f))
            }
        }
        Text("Image $frame / $frames · t = ${fmt(if (fps > 0) frame / fps else 0.0, 3)} s · ${fmt(fps, 1)} ips", color = TextMid, fontSize = 12.sp)
    }
    @Composable fun MeasureControls() {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ptNames.forEachIndexed { i, n -> FilterChip(activePt == i, { activePt = i }, { Text((if (pts[i] != null) "✓ " else "") + n, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red, selectedLabelColor = Color.White, labelColor = TextHi)) }
        }
        Text("Point actif : ${ptNames[activePt]} — touchez l'image pour le placer ou le déplacer", color = TextMid, fontSize = 12.sp)
        Text("Genou ${kneeNow?.let { fmt(it, 0) + "°" } ?: "—"}   Hanche ${hipNow?.let { fmt(it, 0) + "°" } ?: "—"}", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BtnOutline("Détection IA", { detectAI() }, icon = Icons.Filled.AutoAwesome)
            BtnOutline("Effacer", { pts = listOf(null, null, null, null); activePt = 0 })
            BtnOutline("Annuler", { measuring = null })
            BtnPrimary("Valider", { validateAngles() }, enabled = kneeNow != null || hipNow != null)
        }
    }

    PageV(vm, "Analyse — ${fmt(set0.loadKg, 1)} kg", onMenu, header = {
        MarkButton()
        FrameView(bmp, zs, pts, activePt, measuring != null, false, Modifier.fillMaxWidth().height(230.dp), { fullscreen = true }) { placePoint(it) }
        Scrubber()
    }) {
        TextButton({ fpsText = fmt(fps, 1); fpsDialog = true }) { Text("Modifier la cadence (ips)", color = RedBright) }
        if (running) Card2 {
            LinearProgressIndicator(progress = { progress }, color = RedBright, trackColor = Outline, modifier = Modifier.fillMaxWidth())
            Body(progressMsg, size = 13)
            BtnOutline("Annuler", { job?.cancel(); running = false })
        } else {
            BtnOutline("Détection automatique des sauts et des angles", { runAuto() }, Modifier.fillMaxWidth(), enabled = src != null, icon = Icons.Filled.AutoAwesome)
            if (info.isNotEmpty()) Body(info, if (info.startsWith("Échec") || info.contains("Aucun") || info.contains("non détecté")) Warn else Ok, 13)
        }
        if (measuring == null) {
            Card2 {
                H("Marqueurs")
                Body("Le gros bouton rouge marque l'image affichée. Départ = position basse avant la poussée ; Décollage = dernière image avec contact des pieds ; Réception = première image avec contact. Touchez un marqueur ci-dessous pour y revenir, ajustez l'image (−1/+1), puis marquez à nouveau pour le corriger. Zoom : pincez à 2 doigts ou boutons − / +, plein écran en haut à droite de la vidéo.", size = 13)
                RefTag(Refs.MYJ)
            }
            for (t in 0..1) Card2 {
                H("Essai ${t + 1}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in 0..2) {
                        val m = getMark(t, k)
                        FilterChip(target == t * 3 + k, { target = t * 3 + k; m?.let { frame = it } }, { Text("${markNames[k]}${m?.let { " · $it" } ?: ""}", fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red, selectedLabelColor = Color.White, labelColor = TextHi))
                    }
                }
                val tA = trials[t].tA(fps)
                if (tA != null) {
                    val h = Mech.heightFromFlight(tA)
                    Body("Temps de vol ${fmt(tA, 3)} s → hauteur ${fmt(h * 100, 1)} cm (±${fmt(2.0 / (fps * tA) * 100, 1)} % pour ±1 image)", TextHi)
                } else Body("Marquer décollage puis réception.", Warn, 13)
                Body("Angles au départ : genou ${trials[t].kneeDeg?.let { fmt(it, 0) + "°" } ?: "—"} · hanche ${trials[t].hipDeg?.let { fmt(it, 0) + "°" } ?: "—"}", size = 13)
                BtnOutline("Mesurer les angles (genou/hanche)", {
                    if (trials[t].startFrame == null) setMark(t, 0, frame)
                    measuring = t; pts = listOf(null, null, null, null); activePt = 0
                    trials[t].startFrame?.let { frame = it }
                }, Modifier.fillMaxWidth(), icon = Icons.Filled.Edit)
            }
        } else {
            val t = measuring!!
            Card2 {
                H("Angles — essai ${t + 1} (image de départ ${trials[t].startFrame ?: frame})")
                Body("Placez l'image sur la position de départ (genoux ≈ 90°). Choisissez vous-même le point à placer avec les boutons ci-dessous ; zoomez pour plus de précision.", size = 13)
                Body("Repères : épaule = acromion ; hanche = grand trochanter (saillie osseuse latérale de la hanche, repère de Samozino 2008 ; le centre réel de l'articulation, la tête fémorale, n'est pas visible de l'extérieur) ; genou = épicondyle latéral ; cheville = malléole latérale. Des pastilles adhésives collées avant de filmer facilitent beaucoup le pointage.", size = 12)
                RefTag(Refs.S08)
                MeasureControls()
            }
        }
        BtnPrimary("Terminer l'analyse de cette charge", { vm.pop() }, Modifier.fillMaxWidth(), icon = Icons.Filled.Check)
        Spacer(Modifier.height(24.dp))
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FrameView(bmp, zs, pts, activePt, measuring != null, true, Modifier.fillMaxSize(), { fullscreen = false }) { placePoint(it) }
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth(0.82f).background(Color(0xB30A0A0C)).padding(8.dp)) { MarkButton() }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0A0A0C)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (measuring != null) MeasureControls()
                Scrubber()
            }
        }
    }
    if (fpsDialog) AlertDialog(onDismissRequest = { fpsDialog = false }, containerColor = Surface2,
        title = { Text("Cadence réelle de capture", color = TextHi) },
        text = { Column { Body("À corriger si la vidéo importée est un ralenti dont la cadence détectée est fausse (ex. 240 ips enregistrés, lus à 30 ips).", size = 13); NumField("Images par seconde", fpsText, { fpsText = it }) } },
        confirmButton = { TextButton({ parseD(fpsText)?.takeIf { it in 10.0..1000.0 }?.let { fps = it }; fpsDialog = false }) { Text("Valider", color = RedBright) } },
        dismissButton = { TextButton({ fpsDialog = false }) { Text("Annuler", color = TextHi) } })
}
