package io.github.zsozso01.platen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zsozso01.platen.platform.render.DocumentOpenException
import io.github.zsozso01.platen.ui.AddPrinterDialog
import io.github.zsozso01.platen.ui.AppViewModel
import io.github.zsozso01.platen.ui.HomeScreen
import io.github.zsozso01.platen.ui.JobBanner
import io.github.zsozso01.platen.ui.JobDialog
import io.github.zsozso01.platen.ui.OpenResult
import io.github.zsozso01.platen.ui.PrintScreen
import io.github.zsozso01.platen.ui.jobStatusText
import io.github.zsozso01.platen.ui.theme.PlatenTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory((application as PlatenApp).container) }

    /** Set when a share or "open with" arrives before any printer exists, so the UI can ask for one. */
    private var needsPrinterFirst by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            PlatenTheme {
                PlatenApp(viewModel, needsPrinterFirst, onNeedsPrinterHandled = { needsPrinterFirst = false }, onOpen = ::open)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun open(uri: Uri) {
        if (viewModel.openDocument(uri) == OpenResult.NoPrinter) needsPrinterFirst = true
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                // A printer was plugged in while Platen was closed (or Android offered it to Platen): offer to add it.
                if (viewModel.hasUnsavedUsbPrinter()) needsPrinterFirst = true
                null
            }
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) open(uri)
    }
}

@Composable
private fun PlatenApp(vm: AppViewModel, needsPrinterFirst: Boolean, onNeedsPrinterHandled: () -> Unit, onOpen: (Uri) -> Unit) {
    val printers by vm.printers.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val selectedId by vm.selectedPrinterId.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val openError by vm.openError.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val usbPrinters by vm.usbPrinters.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // The user chooses where the diagnostics text goes; Platen itself sends it nowhere.
    val shareDiagnostics: () -> Unit = {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.diagnosticsReport(context))
        context.startActivity(Intent.createChooser(send, null))
    }

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var jobHidden by rememberSaveable { mutableStateOf(false) }
    var pendingPrint by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onOpen(uri) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Print whether or not the user allowed notifications: they only show progress.
        if (pendingPrint) {
            pendingPrint = false
            if (vm.session.value?.print() == true) jobHidden = false
        }
    }

    if (needsPrinterFirst) {
        showAdd = true
        onNeedsPrinterHandled()
    }

    val currentSession = session
    if (currentSession != null) {
        BackHandler { vm.closeSession() }
        val state by currentSession.state.collectAsStateWithLifecycle()
        PrintScreen(
            state = state,
            session = currentSession,
            jobRunning = job?.isRunning == true,
            onBack = vm::closeSession,
            onPrint = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingPrint = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (currentSession.print()) {
                    jobHidden = false
                }
            },
        )
    } else {
        HomeScreen(
            printers = printers,
            statuses = statuses,
            selectedId = selectedId,
            onSelect = vm::selectPrinter,
            onAddPrinter = { showAdd = true },
            onRemove = { vm.removePrinter(it.id) },
            onAllowUsb = vm::allowUsb,
            onPickDocument = { picker.launch(arrayOf("application/pdf", "image/*")) },
            onShareDiagnostics = shareDiagnostics,
            runningJob = job?.takeIf { jobHidden || it.finished != null }?.let { JobBanner(it.documentName, if (it.finished != null) stringResource(R.string.job_done_title) else jobStatusText(it)) },
            onOpenJob = { jobHidden = false },
        )
    }

    DisposableEffect(showAdd) {
        if (showAdd) vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    if (showAdd) AddPrinterDialog(discovered = discovered, usbPrinters = usbPrinters, onDismiss = { showAdd = false }, add = { vm.addPrinter(it) }, addUsb = { vm.addUsbPrinter(it) })

    job?.let { current ->
        if (!jobHidden || current.finished != null || current.waitingForReload) {
            JobDialog(
                job = current,
                onCancel = { vm.cancelJob() },
                onReloaded = { vm.confirmReloaded() },
                onHide = { jobHidden = true },
                onDone = { vm.dismissJob(); jobHidden = false },
                onShareDiagnostics = shareDiagnostics,
            )
        }
    }

    openError?.let { reason ->
        AlertDialog(
            onDismissRequest = vm::clearOpenError,
            text = {
                Text(
                    stringResource(
                        when (reason) {
                            DocumentOpenException.Reason.UNREADABLE -> R.string.open_error_unreadable
                            DocumentOpenException.Reason.PASSWORD_PROTECTED -> R.string.open_error_password
                            DocumentOpenException.Reason.UNSUPPORTED_TYPE -> R.string.open_error_unsupported
                            DocumentOpenException.Reason.CORRUPT -> R.string.open_error_corrupt
                            DocumentOpenException.Reason.TOO_LARGE -> R.string.open_error_large
                        },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = vm::clearOpenError) { Text(stringResource(R.string.close)) } },
        )
    }
}
