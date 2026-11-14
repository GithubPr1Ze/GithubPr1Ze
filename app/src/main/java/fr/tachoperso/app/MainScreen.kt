package fr.tachoperso.app

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.tachoperso.app.ui.CalendarTab
import fr.tachoperso.app.ui.DashboardTab
import fr.tachoperso.app.ui.DayDetail
import fr.tachoperso.app.ui.ExpensesScreen
import fr.tachoperso.app.ui.FerryScreen
import fr.tachoperso.app.ui.DaysTab
import fr.tachoperso.app.ui.Panel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var driverMenu by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDdd(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportDdd(uri)
    }
    var pdfMonth by remember { mutableStateOf<java.time.YearMonth?>(null) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val m = pdfMonth
        if (uri != null && m != null) vm.exportPdf(uri, m)
    }

    LaunchedEffect(state.status, state.error) {
        val msg = state.error ?: state.status
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.dismissMessage()
        }
    }

    val inDetail = state.openDay != null || state.openFerry || state.openExpenses || state.openPay || state.openClarify || state.openPayMonth != null
    BackHandler(enabled = inDetail) { vm.back() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = {
                        if (inDetail) {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                            }
                        }
                    },
                    title = {
                        val selected = state.selected
                        if (selected == null) {
                            Text("Tacho Perso")
                        } else {
                            Box {
                                TextButton(onClick = { driverMenu = true }, enabled = state.drivers.size > 1) {
                                    Text(selected.displayName, style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    if (state.drivers.size > 1) Icon(Icons.Filled.ArrowDropDown, contentDescription = "Changer de conducteur")
                                }
                                DropdownMenu(expanded = driverMenu, onDismissRequest = { driverMenu = false }) {
                                    state.drivers.forEach { d ->
                                        DropdownMenuItem(
                                            text = { Text(d.displayName) },
                                            onClick = { driverMenu = false; vm.select(d.key) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        TextButton(onClick = vm::readCard, enabled = !state.busy) { Text("Lire la carte") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Importer un fichier .DDD") }, onClick = {
                                    menu = false; importLauncher.launch(arrayOf("*/*"))
                                })
                                DropdownMenuItem(
                                    text = { Text("Exporter la dernière lecture") },
                                    enabled = state.selected != null,
                                    onClick = {
                                        menu = false
                                        vm.latestRead()?.let { exportLauncher.launch(it.name) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Email du conducteur") },
                                    enabled = state.selected != null,
                                    onClick = { menu = false; vm.editEmail() },
                                )
                                DropdownMenuItem(text = { Text("Journal technique") }, onClick = { menu = false; showLog = true })
                            }
                        }
                    },
                )
                if (state.busy) {
                    val p = state.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        bottomBar = {
            if (state.history != null && !inDetail) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { vm.setTab(tab) },
                            icon = {
                                Icon(
                                    when (tab) {
                                        Tab.DASHBOARD -> Icons.Filled.Home
                                        Tab.CALENDAR -> Icons.Filled.DateRange
                                        Tab.DAYS -> Icons.AutoMirrored.Filled.List
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val history = state.history
            val profile = state.selected
            val rates = state.bareme?.rates ?: emptyList()
            val expenses = androidx.compose.runtime.remember(history, rates, state.abroadMarkup) {
                history?.expenses(rates, state.abroadMarkup) ?: emptyList()
            }
            when {
                state.loadingHistory -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                history == null || profile == null -> EmptyState(state, vm::readCard)
                state.openDay != null -> DayDetail(history, state.openDay!!, vm::setNight, expenses)
                state.openFerry -> FerryScreen(history, vm::openDay)
                state.openPayMonth != null -> fr.tachoperso.app.ui.MonthDaysEditor(
                    history, rates, state.pay.markupPercent, state.dayOverrides, state.openPayMonth!!, vm::setDayOverride, vm::openDay,
                )
                state.openClarify -> fr.tachoperso.app.ui.ClarifyScreen(history, vm::setNight, vm::openDay)
                state.openPay -> fr.tachoperso.app.ui.PayScreen(
                    history, rates, state.pay, state.payslips, state.abroadMarkup, vm::setPay, vm::setPayslip,
                    state.dayOverrides, vm::openPayMonth, vm::openClarify,
                )
                state.openExpenses -> ExpensesScreen(expenses, state.bareme, state.abroadMarkup, vm::setAbroadMarkup, vm::openDay)
                else -> when (state.tab) {
                    Tab.DASHBOARD -> DashboardTab(profile, history, vm::openDay, vm::editEmail, { vm.openFerry(true) }, vm::setHome, expenses, { vm.openExpenses(true) }, { m -> pdfMonth = m; pdfLauncher.launch("TachoPerso_recap_$m.pdf") }, rates, state.pay, { vm.openPay(true) }, state.dayOverrides, vm::openClarify)
                    Tab.CALENDAR -> CalendarTab(history, vm::openDay)
                    Tab.DAYS -> DaysTab(history, vm::openDay)
                }
            }
        }
    }

    state.emailPrompt?.let { prompt -> EmailDialog(prompt, onSave = { vm.saveEmail(prompt.profile.key, it) }, onDismiss = vm::dismissEmailPrompt) }

    if (showLog) {
        val clipboard = LocalClipboardManager.current
        val text = remember { vm.logText() }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Journal technique") },
            text = {
                Text(
                    text.ifBlank { "Aucune lecture depuis l'ouverture de l'application." },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copier") } },
            dismissButton = { TextButton(onClick = { showLog = false }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun EmptyState(state: UiState, onRead: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Panel(title = "Première lecture") {
            Text(
                "1. Branchez le lecteur de carte sur le téléphone (adaptateur USB‑C si besoin).\n" +
                    "2. Insérez la carte conducteur, puce vers les contacts.\n" +
                    "3. Appuyez sur « Lire la carte ».",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                state.readerName?.let { "Lecteur détecté : $it" } ?: "Aucun lecteur détecté pour le moment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRead, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) "Lecture en cours…" else "Lire la carte")
            }
        }
    }
}

@Composable
private fun EmailDialog(prompt: EmailPrompt, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var email by remember(prompt) { mutableStateOf(prompt.profile.email) }
    val valid = email.isBlank() || Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prompt.newDriver) "Nouveau conducteur" else "Email du conducteur") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (prompt.newDriver) {
                        "Carte de ${prompt.profile.displayName} enregistrée. Ajouter une adresse email pour l'envoi des rapports ?"
                    } else if (prompt.profile.email.isBlank()) {
                        "Aucune adresse email n'est renseignée pour ${prompt.profile.displayName}."
                    } else {
                        prompt.profile.displayName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Adresse email") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text("Adresse invalide") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(email) }, enabled = valid && (email.isNotBlank() || prompt.profile.email.isNotBlank())) {
                Text("Enregistrer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Plus tard") } },
    )
}
