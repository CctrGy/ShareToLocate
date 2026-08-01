package com.sharetolocate.app

import android.Manifest
import android.content.Intent
import android.app.StatusBarManager
import android.content.ComponentName
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Bitmap
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sharetolocate.app.data.ShareRepository
import com.sharetolocate.app.location.LocationSharingService
import com.sharetolocate.app.quick.LocationQuickTileService
import com.sharetolocate.app.security.AppLockStore
import com.sharetolocate.app.model.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private val Ink = Color(0xFF071A18)
private val Forest = Color(0xFF0C2925)
private val Mint = Color(0xFF67E8B6)
private val Cloud = Color(0xFFF2F7F5)
private val Muted = Color(0xFF9CB4AE)

private val EsriWorldImagery = object : OnlineTileSourceBase(
    "Esri World Imagery", 0, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Tiles © Esri"
) {
    override fun getTileURLString(index: Long): String =
        baseUrl + "${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            AppLockGate {
                val repository = remember { ShareRepository.get(this) }
                val state by repository.state.collectAsStateWithLifecycle()
                ShareToLocateTheme(state.settings) { ShareApp(repository) }
            }
        }
    }
}

@Composable private fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppLockStore(context) }
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(!store.hasPin()) }
    var firstPin by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && store.hasPin()) unlocked = false }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (unlocked) { content(); return }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Forest, onPrimary = Ink, onBackground = Cloud, onSurface = Cloud)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(68.dp).background(Mint, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, tint = Ink, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.height(24.dp)); Text(if (creating) if (firstPin.isEmpty()) "Crea tu PIN" else "Confirma tu PIN" else "ShareToLocate", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text(if (creating) "Usa entre 4 y 8 números" else "Introduce tu PIN para continuar", color = Muted)
                Spacer(Modifier.height(24.dp)); OutlinedTextField(
                    value = pin, onValueChange = { value -> if (value.length <= 8 && value.all(Char::isDigit)) { pin = value; error = null } },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(),
                    label = { Text("PIN") }, supportingText = error?.let { message -> { Text(message) } }
                )
                Spacer(Modifier.height(12.dp)); Button(onClick = {
                    if (pin.length !in 4..8) error = "El PIN debe tener entre 4 y 8 cifras"
                    else if (creating && firstPin.isEmpty()) { firstPin = pin; pin = "" }
                    else if (creating && pin != firstPin) { error = "Los PIN no coinciden"; pin = ""; firstPin = "" }
                    else if (creating) { store.setPin(pin); creating = false; unlocked = true; pin = "" }
                    else if (store.verify(pin)) { unlocked = true; pin = "" } else { error = "PIN incorrecto"; pin = "" }
                }, Modifier.fillMaxWidth()) { Text(if (creating && firstPin.isEmpty()) "Continuar" else if (creating) "Guardar PIN" else "Desbloquear") }
            }
        }
    }
}

@Composable private fun ShareToLocateTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val accent = Color(settings.accentColor.argb)
    val colors = if (settings.themeMode == ThemeMode.DARK) darkColorScheme(primary = accent, background = Ink, surface = Forest, onPrimary = Ink, onBackground = Cloud, onSurface = Cloud) else lightColorScheme(primary = accent, background = Color(0xFFF7F9F8), surface = Color.White, onPrimary = Ink, onBackground = Color(0xFF10201B), onSurface = Color(0xFF10201B))
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab { MAP, PEOPLE, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ShareApp(repository: ShareRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.MAP) }
    var addDialog by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as? MainActivity ?: return
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startSharing(activity)
    }
    if (!state.onboardingComplete) {
        OnboardingScreen(repository)
        return
    }
    LaunchedEffect(state.settings.preventScreenshots) {
        if (state.settings.preventScreenshots) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LaunchedEffect(state.settings.keepScreenOn) {
        if (state.settings.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Header(state) },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavItem(Tab.MAP, tab, Icons.Default.Map, "Mapa") { tab = Tab.MAP }
            NavItem(Tab.PEOPLE, tab, Icons.Default.Group, "Personas") { tab = Tab.PEOPLE }
            NavItem(Tab.SETTINGS, tab, Icons.Default.Tune, "Ajustes") { tab = Tab.SETTINGS }
        } },
        floatingActionButton = {
            if (tab == Tab.PEOPLE) FloatingActionButton(onClick = { addDialog = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Default.PersonAdd, null) }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            when (tab) {
                Tab.MAP -> MapScreen(state, repository) { if (state.sharing) stopSharing(activity) else if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startSharing(activity) else permission.launch(runtimePermissions()) }
                Tab.PEOPLE -> PeopleScreen(state, repository)
                Tab.SETTINGS -> SettingsScreen(state, repository)
            }
        }
    }
    if (addDialog) AddPeerDialog(repository) { addDialog = false }
    state.pendingRequests.firstOrNull()?.let { request -> RequestDialog(request, repository) }
    state.peers.firstOrNull { it.verificationCode != null && !it.verified }?.let { peer -> VerificationDialog(peer, repository) }
}

@Composable private fun OnboardingScreen(repository: ShareRepository) {
    val activity = LocalActivity.current as? MainActivity ?: return
    var name by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { repository.completeOnboarding(name) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).background(Mint, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) { Icon(if(page == 0) Icons.Default.NearMe else Icons.Default.PrivacyTip, null, tint = Ink, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(26.dp))
            Text(if(page == 0) "Tu ubicación, solo con quien eliges" else "Permisos transparentes", fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(if(page == 0) "Se creará una identidad P2P en este teléfono. No necesitas cuenta, correo ni servidor central." else "La ubicación se usa para compartirla con contactos verificados. Las notificaciones mantienen visible cualquier sesión activa.", color = Muted)
            Spacer(Modifier.height(24.dp))
            if(page == 0) OutlinedTextField(name, { name = it }, label = { Text("Cómo quieres aparecer") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Badge, null) })
            else Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) { PermissionLine(Icons.Default.LocationOn, "Ubicación precisa", "Para obtener y compartir tu posición"); PermissionLine(Icons.Default.Notifications, "Notificaciones", "Para mostrar cuándo compartes en segundo plano") } }
            Spacer(Modifier.height(28.dp))
            Button(onClick = { if(page == 0) page = 1 else permissions.launch(runtimePermissions()) }, enabled = page == 1 || name.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) { Text(if(page == 0) "Continuar" else "Conceder y empezar") }
        }
    }
}

@Composable private fun PermissionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) { Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Mint); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(text, color = Muted, fontSize = 12.sp) } } }

@Composable private fun Header(state: AppState) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.NearMe, null, tint = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("ShareToLocate", fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(state.transportStatus, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
        Surface(color = if (state.sharing) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.White.copy(alpha = .06f), shape = RoundedCornerShape(50)) { Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).background(if (state.sharing) MaterialTheme.colorScheme.primary else Muted, CircleShape)); Spacer(Modifier.width(6.dp)); Text(if (state.sharing) "EN VIVO" else "PAUSADO", fontSize = 11.sp, color = if (state.sharing) MaterialTheme.colorScheme.primary else Muted, fontWeight = FontWeight.Bold) } }
    }
}

@Composable private fun MapScreen(state: AppState, repository: ShareRepository, toggleSharing: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        OsmMap(state, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Ink.copy(alpha = .91f), shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FollowChip("Libre", Icons.Default.Explore, state.followTarget == FollowTarget.NONE) { repository.follow(FollowTarget.NONE) }
                    FollowChip("Yo", Icons.Default.MyLocation, state.followTarget == FollowTarget.ME) { repository.follow(FollowTarget.ME) }
                    state.peers.filter { it.location != null }.take(2).forEach { peer -> FollowChip(peer.name, Icons.Default.PersonPinCircle, state.followedPeerId == peer.id) { repository.follow(FollowTarget.FRIEND, peer.id) } }
                }
            }
        }
        SmallFloatingActionButton(
            onClick = { repository.updateSettings { it.copy(mapStyle = if (it.mapStyle == MapStyle.STREETS) MapStyle.SATELLITE else MapStyle.STREETS) } },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) { Icon(if (state.settings.mapStyle == MapStyle.SATELLITE) Icons.Default.Map else Icons.Default.SatelliteAlt, "Cambiar tipo de mapa") }
        Card(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Forest.copy(alpha = .97f)), shape = RoundedCornerShape(26.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(if (state.sharing) Mint.copy(.15f) else Color.White.copy(.06f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (state.sharing) Icons.Default.LocationOn else Icons.Default.LocationOff, null, tint = if (state.sharing) Mint else Muted) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(if (state.sharing) "Ubicación compartida" else "Compartición detenida", fontWeight = FontWeight.SemiBold); Text(if (state.sharing) "${state.peers.count { it.online }} contactos conectados" else "Tú decides cuándo aparecer", color = Muted, fontSize = 13.sp) }
                Button(onClick = toggleSharing, colors = ButtonDefaults.buttonColors(containerColor = if (state.sharing) Color(0xFFFF8A80) else Mint, contentColor = Ink)) { Icon(if (state.sharing) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (state.sharing) "Parar" else "Compartir") }
            }
        }
    }
}

@Composable private fun OsmMap(state: AppState, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context -> MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(14.5); controller.setCenter(OsmPoint(40.4168, -3.7038)) } }, update = { map ->
        val wanted = if (state.settings.mapStyle == MapStyle.SATELLITE) EsriWorldImagery else TileSourceFactory.MAPNIK
        if (map.tileProvider.tileSource.name() != wanted.name()) map.setTileSource(wanted)
        map.overlays.removeAll { it is Marker }
        state.ownLocation?.let { point -> marker(map, point, "Tu ubicación", android.R.drawable.ic_menu_mylocation) }
        state.peers.forEach { peer -> peer.location?.let { marker(map, it, peer.name, android.R.drawable.ic_menu_mylocation) } }
        val focus = when (state.followTarget) { FollowTarget.ME -> state.ownLocation; FollowTarget.FRIEND -> state.peers.firstOrNull { it.id == state.followedPeerId }?.location; else -> null }
        focus?.let { map.controller.animateTo(OsmPoint(it.latitude, it.longitude)) }; map.invalidate()
    })
}

private fun marker(map: MapView, point: GeoPoint, title: String, icon: Int) { map.overlays.add(Marker(map).apply { position = OsmPoint(point.latitude, point.longitude); this.title = title; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); this.icon = ContextCompat.getDrawable(map.context, icon) }) }

@Composable private fun FollowChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, click: () -> Unit) { TextButton(onClick = click, colors = ButtonDefaults.textButtonColors(containerColor = if (active) Mint else Color.Transparent, contentColor = if (active) Ink else Cloud), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(label, maxLines = 1, fontSize = 12.sp) } }

@Composable private fun PeopleScreen(state: AppState, repository: ShareRepository) {
    var showQr by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scanner = remember { GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tu círculo", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Solo estas personas pueden recibir tu posición.", color = Muted); Spacer(Modifier.height(10.dp)) }
        item { IdentityCard(state, { showQr = true }, { scanner.startScan().addOnSuccessListener { code -> code.rawValue?.let { scanned = it } } }) }
        if (state.pendingRequests.isNotEmpty()) item { Text("Solicitudes pendientes · ${state.pendingRequests.size}", color = Mint, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        items(state.peers, key = { it.id }) { peer -> PeerCard(peer, repository) { repository.removePeer(peer.id) } }
        item { Spacer(Modifier.height(70.dp)) }
    }
    if (showQr) IdentityQrDialog(state, { showQr = false })
    scanned?.let { payload -> AddScannedPeerDialog(payload, repository) { scanned = null } }
}

@Composable private fun IdentityCard(state: AppState, showQr: () -> Unit, scan: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Mint.copy(.10f)), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Fingerprint, null, tint = Ink) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Mi identidad", fontWeight = FontWeight.Bold); Text(if(state.identityReady) "Lista para compartir" else "Generando…", color = if(state.identityReady) Mint else Muted, fontSize = 12.sp) }; IconButton(showQr, enabled = state.identityReady) { Icon(Icons.Default.QrCode2, "Mostrar QR", tint = Mint) } }
            Spacer(Modifier.height(12.dp)); Text(state.ownId.chunked(4).joinToString(" "), fontSize = 11.sp, color = Muted, maxLines = 3)
            Spacer(Modifier.height(12.dp)); Row { OutlinedButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ShareToLocate ID", state.ownId)) }, enabled = state.identityReady, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copiar") }; Spacer(Modifier.width(8.dp)); Button(onClick = scan, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), modifier = Modifier.weight(1f)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Escanear") } }
        }
    }
}

@Composable private fun IdentityQrDialog(state: AppState, close: () -> Unit) {
    val nonce = remember { com.sharetolocate.app.p2p.ToxEngine.nonce() }
    val payload = remember(state.ownId) { "sharetolocate://pair?v=1&id=${state.ownId}&nonce=$nonce&name=${Uri.encode(state.displayName)}" }
    val bitmap = remember(payload) { qrBitmap(payload, 760) }
    AlertDialog(onDismissRequest = close, containerColor = Cloud, titleContentColor = Ink, textContentColor = Ink, title = { Text("Escanea para conectar") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Image(bitmap.asImageBitmap(), "QR de identidad", Modifier.fillMaxWidth().aspectRatio(1f)); Text("Comprueba después el código en ambos teléfonos.", color = Color(0xFF49605A), fontSize = 13.sp) } }, confirmButton = { Button(onClick = close) { Text("Listo") } })
}

@Composable private fun AddScannedPeerDialog(payload: String, repository: ShareRepository, close: () -> Unit) {
    val uri = remember(payload) { runCatching { Uri.parse(payload) }.getOrNull() }
    val id = uri?.getQueryParameter("id").orEmpty(); val nonce = uri?.getQueryParameter("nonce").orEmpty(); val suggested = uri?.getQueryParameter("name").orEmpty()
    var name by remember { mutableStateOf(suggested) }; var error by remember { mutableStateOf(uri?.scheme != "sharetolocate" || id.length != 76) }
    AlertDialog(onDismissRequest = close, containerColor = Forest, title = { Text(if(error) "QR no válido" else "Conectar con $suggested") }, text = { if(error) Text("Este código no pertenece a ShareToLocate.", color = Color(0xFFFF8A80)) else Column { Text("Se enviará una solicitud P2P. La ubicación no se compartirá hasta verificar el código.", color = Muted); Spacer(Modifier.height(12.dp)); OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) } }, confirmButton = { if(!error) Button(onClick = { if(repository.addPeer(id, name, nonce)) close() else error = true }) { Text("Enviar solicitud") } }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
}

@Composable private fun RequestDialog(request: ContactRequest, repository: ShareRepository) { AlertDialog(onDismissRequest = {}, containerColor = Forest, icon = { Icon(Icons.Default.PersonAdd, null, tint = Mint) }, title = { Text("Solicitud de ${request.name}") }, text = { Text("Quiere conectar contigo. Aún no podrá ver tu ubicación; primero confirmaréis un código en ambos teléfonos.", color = Muted) }, confirmButton = { Button(onClick = { repository.acceptRequest(request) }) { Text("Aceptar") } }, dismissButton = { TextButton(onClick = { repository.rejectRequest(request) }) { Text("Rechazar") } }) }

@Composable private fun VerificationDialog(peer: Peer, repository: ShareRepository) { AlertDialog(onDismissRequest = {}, containerColor = Forest, icon = { Icon(Icons.Default.VerifiedUser, null, tint = Mint) }, title = { Text("Verifica a ${peer.name}") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Comprueba en persona que ambos teléfonos muestran exactamente este código:", color = Muted); Spacer(Modifier.height(18.dp)); Text(peer.verificationCode ?: "", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Mint); Spacer(Modifier.height(12.dp)); Text("Si no coincide, rechaza la conexión.", color = Color(0xFFFFB4AB), fontSize = 12.sp) } }, confirmButton = { Button(onClick = { repository.confirmVerification(peer.id) }, enabled = !peer.localConfirmed) { Text(if(peer.localConfirmed) "Esperando al otro móvil" else "El código coincide") } }, dismissButton = { TextButton(onClick = { repository.removePeer(peer.id) }) { Text("No coincide") } }) }

private fun qrBitmap(content: String, size: Int): Bitmap { val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size); return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { image -> for(y in 0 until size) for(x in 0 until size) image.setPixel(x, y, if(matrix[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) } }

@Composable private fun PeerCard(peer: Peer, repository: ShareRepository, remove: () -> Unit) {
    val context = LocalContext.current
    val canRing = peer.verified && peer.online && peer.sharing && peer.remoteAllowsRing
    Card(colors = CardDefaults.cardColors(containerColor = Forest), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(50.dp).background(Color(peer.accent).copy(.18f), CircleShape), contentAlignment = Alignment.Center) { Text(peer.name.take(1).uppercase(), color = Color(peer.accent), fontWeight = FontWeight.Bold, fontSize = 20.sp) }; Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.size(7.dp).background(if (peer.online) Mint else Muted, CircleShape)) }; Text(if (peer.online && peer.sharing) "Compartiendo ahora" else if (peer.online) "En línea" else "Sin conexión", color = if (peer.sharing) Mint else Muted, fontSize = 13.sp); Text(peer.id, color = Muted.copy(.65f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; IconButton(onClick = remove) { Icon(Icons.Default.DeleteOutline, "Eliminar", tint = Muted) } }
        Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = { if (repository.ringPeer(peer.id)) Toast.makeText(context, "Toque enviado a ${peer.name}", Toast.LENGTH_SHORT).show() }, enabled = canRing, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(7.dp)); Text("Hacer sonar") }
        if (!canRing) Text(when { !peer.online -> "Disponible cuando esté en línea"; !peer.sharing -> "Disponible mientras comparta ubicación"; !peer.remoteAllowsRing -> "${peer.name} no te ha dado permiso"; else -> "Contacto pendiente de verificación" }, color = Muted, fontSize = 11.sp)
    } }
}

@Composable private fun SettingsScreen(state: AppState, repository: ShareRepository) {
    val context = LocalContext.current
    var changePin by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ajustes", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Mapa, apariencia y privacidad", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { AppearanceSettings(state, repository) }
        item { SettingsGroup { SettingSwitch(Icons.Default.Security, "Bloquear capturas", "Oculta la app en capturas y recientes", state.settings.preventScreenshots) { repository.updateSettings { s -> s.copy(preventScreenshots = it) } }; SettingSwitch(Icons.Default.ScreenLockPortrait, "Mantener pantalla activa", "Útil durante trayectos", state.settings.keepScreenOn) { repository.updateSettings { s -> s.copy(keepScreenOn = it) } }; SettingSwitch(Icons.Default.AutoAwesomeMotion, "Suavizar mi movimiento", "Anima saltos entre lecturas GPS", state.settings.smoothMine) { repository.updateSettings { s -> s.copy(smoothMine = it) } }; SettingSwitch(Icons.Default.Groups, "Suavizar contactos", "Movimiento más natural en el mapa", state.settings.smoothFriends) { repository.updateSettings { s -> s.copy(smoothFriends = it) } } } }
        item { RingSettingsCard(state, repository) }
        item { SettingsGroup { TextButton(onClick = { changePin = true }, modifier = Modifier.fillMaxWidth().padding(6.dp)) { Icon(Icons.Default.Password, null); Spacer(Modifier.width(8.dp)); Text("Cambiar PIN de acceso") } } }
        item { QuickTileCard() }
        item { MigrationCard(repository) }
        item { Spacer(Modifier.height(60.dp)) }
    }
    if (changePin) ChangePinDialog(AppLockStore(context)) { changePin = false }
}

@Composable private fun RingSettingsCard(state: AppState, repository: ShareRepository) {
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsActive, null, tint = Mint); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Permitir ‘Hacer sonar’", fontWeight = FontWeight.Bold); Text(if (state.sharing) "Autoriza contactos concretos para darte un toque" else "Activa primero Ubicación compartida", color = Muted, fontSize = 12.sp) }; Switch(state.settings.allowRing, { repository.updateSettings { s -> s.copy(allowRing = it) } }, enabled = state.sharing) }
        AnimatedVisibility(state.settings.allowRing) { Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("COMPORTAMIENTO", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            RingBehavior.entries.forEach { behavior -> Row(Modifier.fillMaxWidth().clickable { repository.updateSettings { it.copy(ringBehavior = behavior) } }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(state.settings.ringBehavior == behavior, { repository.updateSettings { it.copy(ringBehavior = behavior) } }); Text(when (behavior) { RingBehavior.NOTIFICATION_ONLY -> "Solo notificación"; RingBehavior.NOTIFICATION_WITH_SOUND -> "Notificación con sonido"; RingBehavior.SOUND_ONLY -> "Solo sonido" }) } }
            HorizontalDivider(Modifier.padding(vertical = 10.dp)); Text("PERSONAS AUTORIZADAS", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (state.peers.none { it.verified }) Text("Todavía no hay contactos verificados", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            state.peers.filter { it.verified }.forEach { peer -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(peer.name, Modifier.weight(1f)); Switch(peer.allowRing, { repository.setPeerRingAllowed(peer.id, it) }) } }
            Text("Los sonidos se silencian automáticamente cuando No molestar está activo.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        } }
    }
}

@Composable private fun ChangePinDialog(store: AppLockStore, close: () -> Unit) {
    var current by remember { mutableStateOf("") }; var next by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("Cambiar PIN") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(current, { if (it.length <= 8 && it.all(Char::isDigit)) { current = it; error = null } }, label = { Text("PIN actual") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(next, { if (it.length <= 8 && it.all(Char::isDigit)) { next = it; error = null } }, label = { Text("PIN nuevo") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(confirm, { if (it.length <= 8 && it.all(Char::isDigit)) { confirm = it; error = null } }, label = { Text("Confirmar PIN nuevo") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { Button({ when { !store.verify(current) -> error = "El PIN actual no es correcto"; next.length !in 4..8 -> error = "El PIN nuevo debe tener entre 4 y 8 cifras"; next != confirm -> error = "Los PIN nuevos no coinciden"; else -> { store.setPin(next); close() } } }) { Text("Guardar") } }, dismissButton = { TextButton(close) { Text("Cancelar") } })
}

@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content) } }

@Composable private fun AppearanceSettings(state: AppState, repository: ShareRepository) {
    var showPalette by remember { mutableStateOf(false) }
    SettingsGroup {
        Text("APARIENCIA", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(state.settings.themeMode == ThemeMode.LIGHT, { repository.updateSettings { it.copy(themeMode = ThemeMode.LIGHT) } }, { Text("Claro") }, leadingIcon = { Icon(Icons.Default.LightMode, null) })
            FilterChip(state.settings.themeMode == ThemeMode.DARK, { repository.updateSettings { it.copy(themeMode = ThemeMode.DARK) } }, { Text("Oscuro") }, leadingIcon = { Icon(Icons.Default.DarkMode, null) })
            FilterChip(false, { showPalette = true }, { Text("Color") }, leadingIcon = { Box(Modifier.size(18.dp).background(Color(state.settings.accentColor.argb), CircleShape)) })
        }
        TextButton(onClick = { repository.updateSettings { it.copy(mapStyle = if (it.mapStyle == MapStyle.STREETS) MapStyle.SATELLITE else MapStyle.STREETS) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Default.Layers, null); Spacer(Modifier.width(8.dp)); Text("Mapa: ${if (state.settings.mapStyle == MapStyle.STREETS) "Calles" else "Satélite"}") }
    }
    if (showPalette) AlertDialog(onDismissRequest = { showPalette = false }, title = { Text("Elige un color") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { AccentColor.entries.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { row.forEach { option -> val selected = option == state.settings.accentColor; Surface(onClick = { repository.updateSettings { it.copy(accentColor = option) }; showPalette = false }, modifier = Modifier.size(52.dp), shape = CircleShape, color = Color(option.argb), border = if (selected) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null) { if (selected) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Ink) } } } } } }
    }, confirmButton = { TextButton({ showPalette = false }) { Text("Cerrar") } })
}
@Composable private fun QuickTileCard() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp)) {
        Text("Añadir a Ajustes rápidos", fontWeight = FontWeight.Bold); Text("Controla ‘Ubicación compartida’ desde el panel superior sin abrir la aplicación.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp)); OutlinedButton({ addQuickTile(context) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.ToggleOn, null); Spacer(Modifier.width(7.dp)); Text("Añadir control") }
    } }
}

@Composable private fun MigrationCard(repository: ShareRepository) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<com.sharetolocate.app.migration.MigrationManager.Offer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scanner = remember { GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()) }
    DisposableEffect(offer) { onDispose { offer?.close?.invoke() } }
    Card(colors = CardDefaults.cardColors(containerColor = Mint.copy(.10f)), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp)) {
        Text("Migrar a otro móvil", fontWeight = FontWeight.Bold); Text("Transfiere identidad, contactos y preferencias con cifrado de extremo a extremo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp)); Button({ runCatching { repository.createMigrationOffer() }.onSuccess { offer = it }.onFailure { error = it.message } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(7.dp)); Text("Mostrar QR de migración") }
        OutlinedButton({ scanner.startScan().addOnSuccessListener { code -> code.rawValue?.let { raw -> scope.launch { runCatching { repository.importMigration(raw) }.onSuccess { Toast.makeText(context, "Migración completada. Abre de nuevo la app.", Toast.LENGTH_LONG).show(); kotlinx.coroutines.delay(1800); android.os.Process.killProcess(android.os.Process.myPid()) }.onFailure { error = it.message } } } } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(7.dp)); Text("Escanear en el móvil nuevo") }
        Text("Los dos móviles deben estar en la misma Wi‑Fi. El QR caduca en 3 minutos.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    } }
    offer?.let { current -> AlertDialog(onDismissRequest = { current.close(); offer = null }, title = { Text("Escanea con el móvil nuevo") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Image(qrBitmap(current.payload, 760).asImageBitmap(), "QR de migración", Modifier.fillMaxWidth().aspectRatio(1f)); Text("No compartas este código: autoriza una única transferencia.", fontSize = 12.sp) } }, confirmButton = { TextButton({ current.close(); offer = null }) { Text("Cerrar") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("No se pudo migrar") }, text = { Text(message ?: "Error desconocido") }, confirmButton = { TextButton({ error = null }) { Text("Aceptar") } }) }
}

@Composable private fun SettingSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; Switch(checked, change) } }

@Composable private fun AddPeerDialog(repository: ShareRepository, close: () -> Unit) { var id by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var error by remember { mutableStateOf(false) }; AlertDialog(onDismissRequest = close, containerColor = Forest, title = { Text("Añadir persona") }, text = { Column { Text("Pega su identificador seguro. La solicitud se enviará directamente.", color = Muted); Spacer(Modifier.height(14.dp)); OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true); Spacer(Modifier.height(8.dp)); OutlinedTextField(id, { id = it; error = false }, label = { Text("ID de ShareToLocate") }, isError = error, supportingText = if (error) {{ Text("El identificador no es válido") }} else null, singleLine = true) } }, confirmButton = { Button(onClick = { if (repository.addPeer(id, name)) close() else error = true }) { Text("Enviar solicitud") } }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }) }

@Composable private fun RowScope.NavItem(item: Tab, current: Tab, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, click: () -> Unit) { NavigationBarItem(selected = item == current, onClick = click, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.onPrimary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary, unselectedIconColor = Muted, unselectedTextColor = Muted)) }

private fun startSharing(activity: MainActivity) { ContextCompat.startForegroundService(activity, Intent(activity, LocationSharingService::class.java)) }
private fun stopSharing(activity: MainActivity) { activity.startService(Intent(activity, LocationSharingService::class.java).setAction(LocationSharingService.ACTION_STOP)) }
private fun runtimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun addQuickTile(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, LocationQuickTileService::class.java), "Ubicación compartida · ShareToLocate",
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_quick_location), context.mainExecutor
        ) { result -> Toast.makeText(context, if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) "Control de ShareToLocate añadido" else "Edita los Ajustes rápidos y elige ‘Ubicación compartida · ShareToLocate’", Toast.LENGTH_LONG).show() }
    } else Toast.makeText(context, "Edita los Ajustes rápidos y arrastra ‘Ubicación compartida · ShareToLocate’. No elijas ‘Ubicación’, que es el GPS de Android.", Toast.LENGTH_LONG).show()
}
