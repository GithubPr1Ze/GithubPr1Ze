package fr.tachoperso.app

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.tachoperso.core.apdu.CardException
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DddFormat
import fr.tachoperso.core.tacho.TachoCardDownloader
import fr.tachoperso.core.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class UiState(
    val busy: Boolean = false,
    val status: String = "Branchez le lecteur, insérez la carte puis appuyez sur « Lire la carte ».",
    val progress: Float? = null,
    val readerName: String? = null,
    val report: CardReport? = null,
    val source: String? = null,
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val history: List<String> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val historyDir = File(app.filesDir, "lectures").apply { mkdirs() }
    private val logLines = mutableListOf<String>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val log = Log { message ->
        synchronized(logLines) { logLines += "${LocalTime.now().format(timeFormat)}  $message" }
    }

    init {
        refresh()
    }

    private fun logsSnapshot(): List<String> = synchronized(logLines) { logLines.toList() }

    fun refresh() {
        val device = UsbReaders.findReader(usb)
        val files = historyDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.map { it.name }
        _state.update { it.copy(readerName = device?.let(UsbReaders::describe), history = files ?: emptyList()) }
    }

    fun readCard() {
        if (_state.value.busy) return
        synchronized(logLines) { logLines.clear() }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, progress = 0f, status = "Recherche du lecteur…") }
            try {
                val device = UsbReaders.findReader(usb)
                    ?: throw CardException("Aucun lecteur de carte détecté. Vérifiez l'adaptateur USB-C (OTG) et le branchement.")
                if (!UsbReaders.requestPermission(getApplication(), usb, device)) {
                    throw CardException("Autorisation d'accès au lecteur USB refusée.")
                }
                _state.update { it.copy(status = "Mise sous tension de la carte…") }
                val dump = withContext(Dispatchers.IO) {
                    UsbReaders.open(usb, device, log).use { session ->
                        val channel = session.reader.openChannel()
                        TachoCardDownloader(channel, log) { step, fraction ->
                            _state.update { it.copy(progress = fraction, status = "Lecture : $step") }
                        }.download(atr = session.reader.atr?.raw)
                    }
                }
                val report = CardReport(dump)
                val name = withContext(Dispatchers.IO) {
                    val file = File(historyDir, report.suggestedFileName())
                    file.writeBytes(DddFormat.write(dump))
                    file.name
                }
                log.log("Enregistré : $name")
                _state.update {
                    it.copy(
                        report = report, source = name,
                        status = "Carte lue : ${dump.files.size} fichiers (${report.signedFiles} signés).",
                    )
                }
            } catch (e: Exception) {
                log.log("Erreur : $e")
                _state.update { it.copy(error = e.message ?: e.toString(), status = "Échec de la lecture.") }
            } finally {
                _state.update { it.copy(busy = false, progress = null, logs = logsSnapshot()) }
                refresh()
            }
        }
    }

    fun openHistory(name: String) = loadBytes(name) { File(historyDir, name).readBytes() }

    fun importDdd(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier.DDD"
        loadBytes(name) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw CardException("Impossible d'ouvrir le fichier")
        }
    }

    private fun loadBytes(name: String, read: () -> ByteArray) {
        viewModelScope.launch {
            try {
                val dump = withContext(Dispatchers.IO) { DddFormat.read(read()) }
                val report = CardReport(dump)
                _state.update {
                    it.copy(
                        report = report, source = name, error = null,
                        status = "Fichier ouvert : ${dump.files.size} fichiers.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Lecture du fichier impossible : ${e.message}") }
            }
        }
    }

    fun exportDdd(uri: Uri) {
        val report = _state.value.report ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(DddFormat.write(report.dump))
                    } ?: throw CardException("Impossible d'écrire le fichier")
                }
                _state.update { it.copy(status = "Fichier .DDD exporté.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Export impossible : ${e.message}") }
            }
        }
    }

    fun logText(): String = logsSnapshot().joinToString("\n")
}
