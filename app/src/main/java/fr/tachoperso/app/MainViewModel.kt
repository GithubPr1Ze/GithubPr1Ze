package fr.tachoperso.app

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.tachoperso.core.apdu.CardException
import fr.tachoperso.core.rules.DriverHistory
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Tab(val label: String) { DASHBOARD("Tableau de bord"), CALENDAR("Calendrier"), DAYS("Jours"), PLACES("Lieux") }

/** Demande d'email affichée après une lecture de carte. */
data class EmailPrompt(val profile: DriverProfile, val newDriver: Boolean)

data class UiState(
    val busy: Boolean = false,
    val progress: Float? = null,
    val status: String? = null,
    val error: String? = null,
    val readerName: String? = null,
    val drivers: List<DriverProfile> = emptyList(),
    val selected: DriverProfile? = null,
    val history: DriverHistory? = null,
    val loadingHistory: Boolean = false,
    val tab: Tab = Tab.DASHBOARD,
    val openDay: LocalDate? = null,
    /** Écran « Ferry / train » (synthèse mensuelle) ouvert. */
    val openFerry: Boolean = false,
    /** Écran « Frais de route » ouvert. */
    val openExpenses: Boolean = false,
    /** Barème des frais en vigueur (intégré ou téléchargé). */
    val bareme: BaremeRepository.Bareme? = null,
    /** Majoration de 18 % à l'étranger (art. 13, à défaut d'accord d'entreprise). */
    val abroadMarkup: Boolean = false,
    /** Écran « Ma paye » ouvert. */
    val openPay: Boolean = false,
    val pay: PaySettings = PaySettings(),
    val payslips: Map<java.time.YearMonth, fr.tachoperso.core.rules.PayslipCounts> = emptyMap(),
    val dayOverrides: Map<LocalDate, fr.tachoperso.core.rules.DayOverride> = emptyMap(),
    /** Liste des repos à préciser ouverte. */
    val openClarify: Boolean = false,
    /** Mois ouvert dans l'éditeur des repas / découchers. */
    val openPayMonth: java.time.YearMonth? = null,
    /** Écran « Véhicules et sociétés » ouvert. */
    val openVehicles: Boolean = false,
    val vehicleCompany: Map<String, String> = emptyMap(),
    val emailPrompt: EmailPrompt? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())

    /** Écrans d'autres applications à ouvrir (messagerie pour le récapitulatif). */
    private val _launches = kotlinx.coroutines.flow.MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val launches: kotlinx.coroutines.flow.SharedFlow<android.content.Intent> = _launches

    fun reportError(msg: String) = _state.update { it.copy(error = msg) }

    /** Prépare l'e-mail du récapitulatif [month] (PDF + dernière lecture) pour le conducteur [key]. */
    fun sendMonthly(key: String, month: java.time.YearMonth) {
        viewModelScope.launch {
            try {
                val i = withContext(Dispatchers.IO) { MonthlyMail.intent(getApplication(), key, month) }
                _launches.emit(i)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Préparation de l'e-mail impossible : ${e.message}") }
            }
        }
    }
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val repo = DriverRepository(app.filesDir)
    private val baremes = BaremeRepository(app)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val logLines = mutableListOf<String>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val log = Log { message ->
        synchronized(logLines) {
            logLines += "${LocalTime.now().format(timeFormat)}  $message"
            if (logLines.size > 2000) logLines.removeAt(0)
        }
    }

    init {
        val tab = runCatching { Tab.valueOf(prefs.getString("tab", null) ?: "") }.getOrDefault(Tab.DASHBOARD)
        _state.update { it.copy(tab = tab) }
        viewModelScope.launch {
            val drivers = withContext(Dispatchers.IO) { repo.drivers() }
            val key = prefs.getString("driver", null)
            _state.update { it.copy(drivers = drivers) }
            (drivers.firstOrNull { it.key == key } ?: drivers.firstOrNull())?.let { select(it.key) }
        }
        refreshReader()
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { runCatching { baremes.local() }.getOrNull() }
            _state.update { it.copy(bareme = local) }
            val remote = withContext(Dispatchers.IO) { baremes.refresh() }
            if (remote != null) {
                log.log("Barème des frais à jour (${remote.updated})")
                _state.update { it.copy(bareme = remote) }
            } else {
                log.log("Barème des frais : pas de mise à jour (hors ligne ?), version locale utilisée")
            }
        }
    }

    fun refreshReader() {
        val device = UsbReaders.findReader(usb)
        _state.update { it.copy(readerName = device?.let(UsbReaders::describe)) }
    }

    fun setTab(tab: Tab) {
        prefs.edit().putString("tab", tab.name).apply()
        _state.update { it.copy(tab = tab, openDay = null, openFerry = false, openExpenses = false, openPay = false, openClarify = false, openPayMonth = null, openVehicles = false) }
    }

    fun openDay(date: LocalDate?) = _state.update { it.copy(openDay = date) }

    fun openFerry(open: Boolean) = _state.update { it.copy(openFerry = open, openExpenses = false, openPay = false, openDay = null) }

    fun openExpenses(open: Boolean) = _state.update { it.copy(openExpenses = open, openFerry = false, openPay = false, openDay = null) }

    fun openPay(open: Boolean) = _state.update { it.copy(openPay = open, openFerry = false, openExpenses = false, openDay = null) }

    fun setPay(pay: PaySettings) {
        val key = _state.value.selected?.key ?: return
        _state.update { it.copy(pay = pay) }
        viewModelScope.launch(Dispatchers.IO) { repo.setPay(key, pay) }
    }

    fun setPayslip(month: java.time.YearMonth, counts: fr.tachoperso.core.rules.PayslipCounts?) {
        val key = _state.value.selected?.key ?: return
        _state.update { s -> s.copy(payslips = s.payslips.toMutableMap().apply { if (counts == null) remove(month) else put(month, counts) }) }
        viewModelScope.launch(Dispatchers.IO) { repo.setPayslip(key, month, counts) }
    }

    fun setAbroadMarkup(on: Boolean) {
        val key = _state.value.selected?.key ?: return
        _state.update { it.copy(abroadMarkup = on) }
        viewModelScope.launch(Dispatchers.IO) { repo.setAbroadMarkup(key, on) }
    }

    /** Retour : ferme le jour ouvert, sinon l'écran ferry. */
    fun back() = _state.update {
        when {
            it.openDay != null -> it.copy(openDay = null)
            it.openPayMonth != null -> it.copy(openPayMonth = null)
            it.openClarify -> it.copy(openClarify = false)
            else -> it.copy(openFerry = false, openExpenses = false, openPay = false, openVehicles = false)
        }
    }

    fun openVehicles() = _state.update { it.copy(openVehicles = true, openDay = null) }

    fun setVehicleCompany(vehicle: String, company: String?) {
        val key = _state.value.selected?.key ?: return
        _state.update { s -> s.copy(vehicleCompany = s.vehicleCompany.toMutableMap().apply { if (company == null) remove(vehicle) else put(vehicle, company) }) }
        viewModelScope.launch(Dispatchers.IO) { repo.setVehicleCompany(key, vehicle, company) }
    }

    fun openClarify() = _state.update { it.copy(openClarify = true, openPayMonth = null, openDay = null) }

    fun openPayMonth(m: java.time.YearMonth) = _state.update { it.copy(openPayMonth = m, openDay = null) }

    fun setDayOverride(date: LocalDate, o: fr.tachoperso.core.rules.DayOverride?) {
        val key = _state.value.selected?.key ?: return
        _state.update { s -> s.copy(dayOverrides = s.dayOverrides.toMutableMap().apply { if (o == null) remove(date) else put(date, o) }) }
        viewModelScope.launch(Dispatchers.IO) { repo.setDay(key, date, o) }
    }

    fun dismissMessage() = _state.update { it.copy(error = null, status = null) }

    /** Correction manuelle d'une nuit (null = revenir au calcul automatique). */
    fun setNight(restStart: java.time.Instant, status: fr.tachoperso.core.rules.NightStatus?) =
        updateSettings { repo.setNight(it, restStart, status) }

    /** Domicile déclaré (null = détection automatique). */
    fun setHome(home: fr.tachoperso.core.tacho.GeoPoint?) = updateSettings { repo.setHome(it, home) }

    private fun updateSettings(change: (String) -> Unit) {
        val key = _state.value.selected?.key ?: return
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) {
                runCatching { change(key); repo.history(key) }.getOrNull()
            }
            if (history != null) _state.update { it.copy(history = history) }
        }
    }

    fun select(key: String) {
        prefs.edit().putString("driver", key).apply()
        viewModelScope.launch {
            _state.update { s -> s.copy(selected = s.drivers.firstOrNull { it.key == key }, loadingHistory = true, openDay = null, openFerry = false, openExpenses = false, openPay = false) }
            val history = withContext(Dispatchers.Default) { runCatching { repo.history(key) }.getOrNull() }
            val settings = withContext(Dispatchers.IO) { repo.settings(key) }
            _state.update {
                it.copy(history = history, loadingHistory = false, abroadMarkup = settings.abroadMarkup, pay = settings.pay, payslips = settings.payslips, dayOverrides = settings.days, vehicleCompany = settings.vehicleCompany)
            }
        }
    }

    private suspend fun reloadDrivers(select: String?) {
        val drivers = withContext(Dispatchers.IO) { repo.drivers() }
        _state.update { it.copy(drivers = drivers) }
        if (select != null) select(select)
    }

    fun readCard() {
        if (_state.value.busy) return
        synchronized(logLines) { logLines.clear() }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, status = null, progress = 0f) }
            try {
                val device = UsbReaders.findReader(usb)
                    ?: throw CardException("Aucun lecteur détecté. Vérifiez l'adaptateur USB‑C (OTG) et le branchement.")
                if (!UsbReaders.requestPermission(getApplication(), usb, device)) {
                    throw CardException("Autorisation d'accès au lecteur USB refusée.")
                }
                val dump = withContext(Dispatchers.IO) {
                    UsbReaders.open(usb, device, log).use { session ->
                        val channel = session.reader.openChannel()
                        TachoCardDownloader(channel, log) { _, fraction ->
                            _state.update { it.copy(progress = fraction) }
                        }.download(atr = session.reader.atr?.raw)
                    }
                }
                val known = _state.value.drivers.map { it.key }.toSet()
                val (profile, name) = withContext(Dispatchers.IO) { repo.save(dump) }
                log.log("Enregistré : $name")
                reloadDrivers(profile.key)
                val warn = if (dump.warnings.isNotEmpty()) " (${dump.warnings.size} avertissement(s), voir le journal)" else ""
                _state.update {
                    it.copy(
                        status = "Carte de ${profile.displayName} lue et enregistrée$warn.",
                        emailPrompt = if (profile.email.isBlank()) EmailPrompt(profile, profile.key !in known) else null,
                    )
                }
            } catch (e: Exception) {
                log.log("Erreur : $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false, progress = null) }
                refreshReader()
            }
        }
    }

    fun saveEmail(key: String, email: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setEmail(key, email) }
            _state.update { it.copy(emailPrompt = null) }
            val drivers = withContext(Dispatchers.IO) { repo.drivers() }
            _state.update { s -> s.copy(drivers = drivers, selected = drivers.firstOrNull { it.key == s.selected?.key } ?: s.selected) }
        }
    }

    fun editEmail() {
        val p = _state.value.selected ?: return
        _state.update { it.copy(emailPrompt = EmailPrompt(p, newDriver = false)) }
    }

    fun dismissEmailPrompt() = _state.update { it.copy(emailPrompt = null) }

    fun importDdd(uri: Uri) {
        viewModelScope.launch {
            try {
                val dump = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw CardException("Impossible d'ouvrir le fichier")
                    DddFormat.read(bytes)
                }
                val (profile, _) = withContext(Dispatchers.IO) { repo.save(dump) }
                reloadDrivers(profile.key)
                _state.update { it.copy(status = "Fichier importé pour ${profile.displayName}.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Import impossible : ${e.message}") }
            }
        }
    }

    /** Sauvegarde complète (lectures, réglages, corrections) dans un .zip choisi par l'utilisateur. */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    app.contentResolver.openOutputStream(uri)?.use { Backup.write(app.filesDir, it) }
                        ?: throw CardException("Impossible d'écrire le fichier")
                }
                _state.update { it.copy(status = "Sauvegarde enregistrée.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Sauvegarde impossible : ${e.message}") }
            }
        }
    }

    /** Restaure une sauvegarde complète (remplace les données actuelles). */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    app.contentResolver.openInputStream(uri)?.use { Backup.restore(app.filesDir, it) }
                        ?: throw CardException("Impossible d'ouvrir le fichier")
                }
                val drivers = withContext(Dispatchers.IO) { repo.drivers() }
                _state.update { it.copy(drivers = drivers, status = "Sauvegarde restaurée ($n fichiers).") }
                drivers.firstOrNull()?.let { select(it.key) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Restauration impossible : ${e.message}") }
            }
        }
    }

    /** Dernière lecture du conducteur affiché, à exporter. */
    fun latestRead(): File? = _state.value.selected?.let { repo.reads(it.key).firstOrNull() }

    /** Écrit le récapitulatif mensuel PDF du conducteur sélectionné. */
    fun exportPdf(uri: Uri, month: java.time.YearMonth) {
        val s = _state.value
        val history = s.history ?: return
        val profile = s.selected ?: return
        val rates = s.bareme?.rates ?: emptyList()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val report = fr.tachoperso.core.rules.MonthlyReport.build(history, history.expenses(rates, s.abroadMarkup), month)
                    val bareme = rates.lastOrNull()?.let { "${it.source} (en vigueur le ${it.effective})" } ?: "indisponible"
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        ReportPdf.write(it, report, profile.displayName, profile.cardNumber, bareme)
                    } ?: throw CardException("Impossible d'écrire le fichier")
                }
                _state.update { it.copy(status = "Récapitulatif PDF enregistré.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "PDF impossible : ${e.message}") }
            }
        }
    }

    fun exportDdd(uri: Uri) {
        val file = latestRead() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
                        ?: throw CardException("Impossible d'écrire le fichier")
                }
                _state.update { it.copy(status = "Fichier .DDD exporté.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Export impossible : ${e.message}") }
            }
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }
}
