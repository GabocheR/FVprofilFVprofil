package com.fvprofile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.fvprofile.core.*
import com.fvprofile.data.Store
import java.io.File

sealed class Screen {
    object Athletes : Screen()
    object Protocol : Screen()
    object Glossary : Screen()
    object References : Screen()
    data class AthleteEdit(val id: String?) : Screen()
    data class AthleteDetail(val id: String) : Screen()
    data class Setup(val athleteId: String) : Screen()
    data class Hub(val sessionId: String) : Screen()
    data class Record(val sessionId: String, val setId: String) : Screen()
    data class Analysis(val sessionId: String, val setId: String, val autoStart: Boolean) : Screen()
    data class Results(val sessionId: String) : Screen()
    data class Reco(val sessionId: String) : Screen()
}

class AppVM(app: Application) : AndroidViewModel(app) {
    val store = Store(File(app.filesDir, "data.json")).also { it.load() }
    val stack = mutableStateListOf<Screen>(Screen.Athletes)
    private val prefs = app.getSharedPreferences("fv", 0)
    var camKey by mutableStateOf(prefs.getString("cam", "") ?: "")
        private set
    fun setCam(k: String) { camKey = k; prefs.edit().putString("cam", k).apply() }

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun replace(s: Screen) { stack[stack.size - 1] = s }
    fun goRoot(s: Screen) { stack.clear(); stack.add(s) }

    fun athlete(id: String) = store.athletes.firstOrNull { it.id == id }
    fun session(id: String) = store.sessions.firstOrNull { it.id == id }
    fun sessionsOf(athleteId: String) = store.sessions.filter { it.athleteId == athleteId }.sortedBy { it.date }

    fun saveAthlete(a: Athlete) {
        val i = store.athletes.indexOfFirst { it.id == a.id }
        if (i >= 0) store.athletes[i] = a else store.athletes.add(a)
        store.save()
    }
    /** Ajoute une pesée à l'historique du poids ; le poids courant de l'athlète = pesée la plus récente. */
    fun addWeight(athleteId: String, kg: Double, date: Long = System.currentTimeMillis()) {
        val a = athlete(athleteId) ?: return
        val list = (a.weights + WeightEntry(date, kg)).sortedBy { it.date }
        saveAthlete(a.copy(weights = list, massKg = list.last().kg))
    }
    fun deleteWeight(athleteId: String, date: Long) {
        val a = athlete(athleteId) ?: return
        val list = a.weights.filter { it.date != date }
        if (list.isEmpty()) return
        saveAthlete(a.copy(weights = list, massKg = list.last().kg))
    }
    fun renameSession(id: String, name: String) { session(id)?.let { saveSession(it.copy(name = name.trim())) } }
    fun deleteAthlete(id: String) {
        store.sessions.filter { it.athleteId == id }.forEach { deleteSession(it.id) }
        store.athletes.removeAll { it.id == id }; store.save()
    }
    fun saveSession(s: Session) {
        val i = store.sessions.indexOfFirst { it.id == s.id }
        if (i >= 0) store.sessions[i] = s else store.sessions.add(s)
        store.save()
    }
    fun deleteSession(id: String) {
        session(id)?.sets?.forEach { it.videoPath?.let { p -> File(p).delete() } }
        store.sessions.removeAll { it.id == id }; store.save()
    }
    fun updateSet(sid: String, setId: String, f: (LoadSet) -> LoadSet) {
        val s = session(sid) ?: return
        saveSession(s.copy(sets = s.sets.map { if (it.id == setId) f(it) else it }))
    }
    /** Supprime les fichiers vidéo d'une séance (marqueurs, profil et résultats sont conservés). Renvoie l'espace libéré en octets. */
    fun deleteVideos(sid: String): Long {
        val s = session(sid) ?: return 0L
        var freed = 0L
        s.sets.forEach { st -> st.videoPath?.let { val f = File(it); freed += f.length(); f.delete() } }
        saveSession(s.copy(sets = s.sets.map { it.copy(videoPath = null) }))
        return freed
    }
    fun videoBytes(sid: String): Long = session(sid)?.sets?.sumOf { st -> st.videoPath?.let { File(it).length() } ?: 0L } ?: 0L
    fun newId() = java.util.UUID.randomUUID().toString().take(8)
}
