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
import android.provider.Settings
import android.provider.ContactsContract
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
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
import org.osmdroid.util.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

private val Ink = Color(0xFF071A18)
private val Forest = Color(0xFF0C2925)
private val Mint = Color(0xFF67E8B6)
private val Cloud = Color(0xFFF2F7F5)
private val Muted = Color(0xFF9CB4AE)

private object AppLockCoordinator { @Volatile var externalActivityInProgress = false }
private object MapRenderMemory {
    var initialized = false
    var userAdjustedViewport = false
    var applyingAutoFit = false
    var center: OsmPoint? = null
    var zoom = 14.5
    var overviewKey = "__pending__"
}

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
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && store.hasPin() && !AppLockCoordinator.externalActivityInProgress) unlocked = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (unlocked) { content(); return }
    val submitPin = {
        if (pin.length !in 4..8) error = "El PIN debe tener entre 4 y 8 cifras"
        else if (creating && firstPin.isEmpty()) { firstPin = pin; pin = "" }
        else if (creating && pin != firstPin) { error = "Los PIN no coinciden"; pin = ""; firstPin = "" }
        else if (creating) { store.setPin(pin); creating = false; unlocked = true; pin = "" }
        else if (store.verify(pin)) { unlocked = true; pin = "" } else { error = "PIN incorrecto"; pin = "" }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Forest, onPrimary = Ink, onBackground = Cloud, onSurface = Cloud)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(68.dp).background(Mint, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, tint = Ink, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.height(24.dp)); Text(if (creating) if (firstPin.isEmpty()) "Crea tu PIN" else "Confirma tu PIN" else "ShareToLocate", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text(if (creating) "Usa entre 4 y 8 números" else "Introduce tu PIN para continuar", color = Muted)
                Spacer(Modifier.height(24.dp)); OutlinedTextField(
                    value = pin, onValueChange = { value -> if (value.length <= 8 && value.all(Char::isDigit)) { pin = value; error = null } },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitPin() }), visualTransformation = PasswordVisualTransformation(),
                    label = { Text("PIN") }, supportingText = error?.let { message -> { Text(message) } }
                )
                Spacer(Modifier.height(12.dp)); Button(onClick = submitPin, Modifier.fillMaxWidth()) { Text(if (creating && firstPin.isEmpty()) "Continuar" else if (creating) "Guardar PIN" else "Desbloquear") }
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
    LaunchedEffect(state.onboardingComplete) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startSharing(activity)
        } else {
            permission.launch(runtimePermissions())
        }
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
                Tab.PEOPLE -> PeopleScreen(state, repository) { peerId ->
                    repository.follow(FollowTarget.FRIEND, peerId)
                    tab = Tab.MAP
                }
                Tab.SETTINGS -> SettingsScreen(state, repository) { tab = Tab.PEOPLE }
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
            Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) { Icon(if(page == 0) Icons.Default.NearMe else Icons.Default.PrivacyTip, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(26.dp))
            Text(if(page == 0) "Tu ubicación, solo con quien eliges" else "Permisos transparentes", fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(if(page == 0) "Se creará una identidad P2P en este teléfono. No necesitas cuenta, correo ni servidor central." else "La ubicación se usa para compartirla con contactos verificados. Las notificaciones mantienen visible cualquier sesión activa.", color = Muted)
            Spacer(Modifier.height(24.dp))
            if(page == 0) OutlinedTextField(name, { name = it }, label = { Text("Cómo quieres aparecer") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Badge, null) })
            else Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) { PermissionLine(Icons.Default.LocationOn, "Ubicación precisa", "Para obtener y compartir tu posición"); PermissionLine(Icons.Default.Notifications, "Notificaciones", "Para mostrar cuándo compartes en segundo plano") } }
            Spacer(Modifier.height(28.dp))
            Button(onClick = { if(page == 0) page = 1 else permissions.launch(runtimePermissions()) }, enabled = page == 1 || name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if(page == 0) "Continuar" else "Conceder y empezar") }
        }
    }
}

@Composable private fun PermissionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) { Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } } }

@Composable private fun Header(state: AppState) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.NearMe, null, tint = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("ShareToLocate", fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(state.transportStatus, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
        Surface(color = if (state.sharing) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.White.copy(alpha = .06f), shape = RoundedCornerShape(50)) { Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).background(if (state.sharing) MaterialTheme.colorScheme.primary else Muted, CircleShape)); Spacer(Modifier.width(6.dp)); Text(if (state.sharing) "EN VIVO" else "PAUSADO", fontSize = 11.sp, color = if (state.sharing) MaterialTheme.colorScheme.primary else Muted, fontWeight = FontWeight.Bold) } }
    }
}

@Composable private fun MapScreen(state: AppState, repository: ShareRepository, toggleSharing: () -> Unit) {
    var showPause by remember { mutableStateOf(false) }
    var mapRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { repository.requestPeerLocations() }
    Box(Modifier.fillMaxSize()) {
        SafeOsmMap(state, Modifier.fillMaxSize(), mapRefresh)
        Column(Modifier.align(Alignment.TopCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FollowSelector(state, repository)
        }
        SmallFloatingActionButton(
            onClick = { repository.updateSettings { it.copy(mapStyle = if (it.mapStyle == MapStyle.STREETS) MapStyle.SATELLITE else MapStyle.STREETS) } },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) { Icon(if (state.settings.mapStyle == MapStyle.SATELLITE) Icons.Default.Map else Icons.Default.SatelliteAlt, "Cambiar tipo de mapa") }
        SmallFloatingActionButton(
            onClick = {
                MapRenderMemory.initialized = false
                MapRenderMemory.userAdjustedViewport = false
                MapRenderMemory.overviewKey = "__pending__"
                mapRefresh++
                repository.requestPeerLocations()
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.ZoomOutMap, "Mostrar todas las ubicaciones") }
        Card(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .97f)), shape = RoundedCornerShape(26.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(if (state.sharing) MaterialTheme.colorScheme.primary.copy(.15f) else MaterialTheme.colorScheme.onSurface.copy(.06f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (state.sharing) Icons.Default.LocationOn else Icons.Default.LocationOff, null, tint = if (state.sharing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(if (state.sharingPausedUntil != null) "Compartición pausada" else if (state.sharing) "Ubicación compartida" else "Compartición detenida", fontWeight = FontWeight.SemiBold); Text(if (state.sharingPausedUntil != null) "Se reanudará automáticamente" else if (state.sharing) "${state.peers.count { it.online }} contactos conectados" else "Tú decides cuándo aparecer", color = Muted, fontSize = 13.sp) }
                if (state.sharing) IconButton({ if (state.sharingPausedUntil != null) repository.resumeSharing() else showPause = true }) { Icon(if (state.sharingPausedUntil != null) Icons.Default.PlayArrow else Icons.Default.Pause, if (state.sharingPausedUntil != null) "Reanudar" else "Pausar") }
                Button(onClick = toggleSharing, colors = ButtonDefaults.buttonColors(containerColor = if (state.sharing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, contentColor = if (state.sharing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary)) { Icon(if (state.sharing) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (state.sharing) "Parar" else "Compartir") }
            }
        }
    }
    if (showPause) AlertDialog(onDismissRequest = { showPause = false }, title = { Text("Pausar temporalmente") }, text = { Column { listOf(15 to "15 min", 60 to "1 hora", 180 to "3 horas", 360 to "6 horas", 720 to "12 horas", 1440 to "24 horas").forEach { (minutes, label) -> TextButton({ repository.pauseSharing(minutes); showPause = false }, Modifier.fillMaxWidth()) { Text(label) } } } }, confirmButton = {}, dismissButton = { TextButton({ showPause = false }) { Text("Cancelar") } })
}

private data class ValidatedMapLocations(val own: GeoPoint?, val peers: Map<String, GeoPoint>)

@Composable private fun SafeOsmMap(state: AppState, modifier: Modifier, refreshKey: Int) {
    var lastOverviewKey by remember { mutableStateOf(MapRenderMemory.overviewKey) }
    var mapPrepared by remember { mutableStateOf(MapRenderMemory.initialized) }
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            lastOverviewKey = "__pending__"
            mapPrepared = false
        }
    }
    val locationSnapshot = state.ownLocation to state.peers.map { it.id to it.location }
    val validatedLocations by produceState<ValidatedMapLocations?>(null, locationSnapshot) {
        value = null
        value = withContext(Dispatchers.Default) {
            ValidatedMapLocations(
                own = state.ownLocation?.takeIf(::isValidMapLocation),
                peers = state.peers.mapNotNull { peer ->
                    peer.location?.takeIf(::isValidMapLocation)?.let { peer.id to it }
                }.toMap()
            )
        }
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(MapRenderMemory.zoom)
                    val preferredCenter = state.settings.defaultMapCenter ?: state.ownLocation
                    controller.setCenter(MapRenderMemory.center ?: preferredCenter?.let { OsmPoint(it.latitude, it.longitude) } ?: OsmPoint(40.4168, -3.7038))
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean { MapRenderMemory.center = mapCenter as? OsmPoint; MapRenderMemory.initialized = true; if (!MapRenderMemory.applyingAutoFit) MapRenderMemory.userAdjustedViewport = true; return false }
                        override fun onZoom(event: ZoomEvent?): Boolean { MapRenderMemory.zoom = zoomLevelDouble; MapRenderMemory.center = mapCenter as? OsmPoint; MapRenderMemory.initialized = true; if (!MapRenderMemory.applyingAutoFit) MapRenderMemory.userAdjustedViewport = true; return false }
                    })
                }
            },
            update = update@{ map ->
                val locations = validatedLocations ?: return@update
                val wanted = if (state.settings.mapStyle == MapStyle.SATELLITE) EsriWorldImagery else TileSourceFactory.MAPNIK
                if (map.tileProvider.tileSource.name() != wanted.name()) map.setTileSource(wanted)
                map.overlays.removeAll { it is Marker }
                locations.own?.let { marker(map, it, "Tu ubicacion", android.R.drawable.ic_menu_mylocation) }
                val activePeers = state.peers.filter { it.online && it.sharing }.mapNotNull { peer -> locations.peers[peer.id]?.let { peer to it } }
                activePeers.forEach { (peer, point) ->
                    marker(map, point, peer.displayName, android.R.drawable.ic_menu_mylocation, peer.accent)
                }
                val focus = when (state.followTarget) {
                    FollowTarget.ME -> locations.own
                    FollowTarget.FRIEND -> locations.peers[state.followedPeerId]
                    else -> null
                }
                focus?.let { map.controller.animateTo(OsmPoint(it.latitude, it.longitude)) }
                if (state.followTarget == FollowTarget.NONE) {
                    val overview = buildList {
                        locations.own?.let(::add)
                        activePeers.mapTo(this) { it.second }
                    }
                    val overviewKey = overview.joinToString("|") { "${it.latitude},${it.longitude}" }
                    if (overviewKey != lastOverviewKey) {
                        lastOverviewKey = overviewKey
                        MapRenderMemory.overviewKey = overviewKey
                        val points = overview.map { OsmPoint(it.latitude, it.longitude) }
                        map.post {
                            if (!MapRenderMemory.initialized && !MapRenderMemory.userAdjustedViewport) {
                                MapRenderMemory.applyingAutoFit = true
                                when (points.size) {
                                    0 -> Unit
                                    1 -> {
                                        map.controller.setZoom(16.0)
                                        map.controller.setCenter(points.first())
                                    }
                                    else -> fitMapToPointsSafely(map, points, 96)
                                }
                                MapRenderMemory.applyingAutoFit = false
                            }
                            MapRenderMemory.center = map.mapCenter as? OsmPoint
                            MapRenderMemory.zoom = map.zoomLevelDouble
                            MapRenderMemory.initialized = true
                            mapPrepared = true
                            map.invalidate()
                        }
                    }
                } else {
                    mapPrepared = true
                }
                map.invalidate()
            }
        )
        if (!mapPrepared) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Preparando ubicaciones...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun isValidMapLocation(point: GeoPoint): Boolean =
    point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

private fun fitMapToPointsSafely(map: MapView, points: List<OsmPoint>, paddingPx: Int) {
    if (map.width <= 0 || map.height <= 0) {
        map.controller.setZoom(3.0)
        map.controller.setCenter(points.first())
        return
    }
    val minLat = points.minOf { it.latitude }.coerceIn(-85.05112878, 85.05112878)
    val maxLat = points.maxOf { it.latitude }.coerceIn(-85.05112878, 85.05112878)
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val lonSpan = maxLon - minLon
    if (!lonSpan.isFinite() || lonSpan > 180.0) {
        map.controller.setZoom(2.0)
        map.controller.setCenter(OsmPoint((minLat + maxLat) / 2.0, 0.0))
        return
    }
    val usableWidth = max(1, map.width - paddingPx * 2).toDouble()
    val usableHeight = max(1, map.height - paddingPx * 2).toDouble()
    val lonFraction = max(lonSpan / 360.0, 1e-9)
    fun mercatorY(latitude: Double): Double {
        val radians = latitude * PI / 180.0
        return ln(tan(PI / 4.0 + radians / 2.0))
    }
    val latFraction = max((mercatorY(maxLat) - mercatorY(minLat)) / (2.0 * PI), 1e-9)
    val lonZoom = log2(usableWidth / 256.0 / lonFraction)
    val latZoom = log2(usableHeight / 256.0 / latFraction)
    map.controller.setZoom(min(lonZoom, latZoom).coerceIn(2.0, 18.0))
    map.controller.setCenter(OsmPoint((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0))
}

@Composable private fun OsmMap(state: AppState, modifier: Modifier) {
    var lastOverviewKey by remember { mutableStateOf("") }
    AndroidView(modifier = modifier, factory = { context -> MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(14.5); controller.setCenter(OsmPoint(40.4168, -3.7038)) } }, update = { map ->
        val wanted = if (state.settings.mapStyle == MapStyle.SATELLITE) EsriWorldImagery else TileSourceFactory.MAPNIK
        if (map.tileProvider.tileSource.name() != wanted.name()) map.setTileSource(wanted)
        map.overlays.removeAll { it is Marker }
        state.ownLocation?.let { point -> marker(map, point, "Tu ubicación", android.R.drawable.ic_menu_mylocation) }
        val activePeers = state.peers.filter { it.location != null }
        activePeers.forEach { peer -> peer.location?.let { marker(map, it, peer.displayName + if (peer.sharing) "" else " · última ubicación", android.R.drawable.ic_menu_mylocation) } }
        val focus = when (state.followTarget) { FollowTarget.ME -> state.ownLocation; FollowTarget.FRIEND -> state.peers.firstOrNull { it.id == state.followedPeerId }?.location; else -> null }
        focus?.let { map.controller.animateTo(OsmPoint(it.latitude, it.longitude)) }
        if (state.followTarget == FollowTarget.NONE) {
            val overview = buildList { state.ownLocation?.let(::add); activePeers.mapNotNullTo(this) { it.location } }
            val overviewKey = overview.joinToString("|") { "${it.latitude},${it.longitude}" }
            if (overview.isNotEmpty() && overviewKey != lastOverviewKey) {
                lastOverviewKey = overviewKey
                val points = overview.map { OsmPoint(it.latitude, it.longitude) }
                if (points.size == 1) { map.controller.setZoom(16.0); map.controller.animateTo(points.first()) }
                else map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points), true, 96)
            }
        }
        map.invalidate()
    })
}

private fun marker(map: MapView, point: GeoPoint, title: String, icon: Int, tint: Long? = null) { map.overlays.add(Marker(map).apply { position = OsmPoint(point.latitude, point.longitude); this.title = title; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); this.icon = ContextCompat.getDrawable(map.context, icon)?.mutate()?.also { drawable -> tint?.let { drawable.setTint(it.toInt()) } } }) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FollowSelector(state: AppState, repository: ShareRepository) {
    var expanded by remember { mutableStateOf(false) }
    val selectedPeer = state.peers.firstOrNull { it.id == state.followedPeerId }
    val label = when (state.followTarget) { FollowTarget.NONE -> "Libre · ver todos"; FollowTarget.ME -> "Seguirme a mí"; FollowTarget.FRIEND -> "Seguir a ${selectedPeer?.displayName ?: "contacto"}" }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        Surface(modifier = Modifier.widthIn(min = 250.dp).menuAnchor(MenuAnchorType.PrimaryNotEditable), color = MaterialTheme.colorScheme.surface.copy(alpha = .97f), shape = RoundedCornerShape(22.dp), shadowElevation = 10.dp, tonalElevation = 4.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .22f))) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary.copy(.16f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.GpsFixed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("SEGUIR A", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }; ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        }
        ExposedDropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem({ Text("Libre · ver todos") }, { repository.follow(FollowTarget.NONE); expanded = false }, leadingIcon = { Icon(Icons.Default.Explore, null) })
            DropdownMenuItem({ Text("Seguirme a mí") }, { repository.follow(FollowTarget.ME); expanded = false }, leadingIcon = { Icon(Icons.Default.MyLocation, null) })
            state.peers.filter { it.location != null && it.showInFollowMenu }.forEach { peer -> DropdownMenuItem({ Text("Seguir a ${peer.displayName}") }, { repository.follow(FollowTarget.FRIEND, peer.id); expanded = false }, leadingIcon = { Icon(Icons.Default.PersonPinCircle, null) }) }
        }
    }
}

@Composable private fun PeopleScreen(state: AppState, repository: ShareRepository, viewOnMap: (String) -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scanner = remember { GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tu círculo", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Solo estas personas pueden recibir tu posición.", color = Muted); Spacer(Modifier.height(10.dp)) }
        item { IdentityCard(state, { showQr = true }, {
            AppLockCoordinator.externalActivityInProgress = true
            scanner.startScan().addOnSuccessListener { code -> code.rawValue?.let { scanned = it } }
                .addOnCompleteListener { AppLockCoordinator.externalActivityInProgress = false }
        }) }
        if (state.pendingRequests.isNotEmpty()) item { Text("Solicitudes pendientes · ${state.pendingRequests.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        items(state.peers, key = { it.id }) { peer -> PeerCard(peer, repository, { viewOnMap(peer.id) }) { repository.removePeer(peer.id) } }
        item { Spacer(Modifier.height(70.dp)) }
    }
    if (showQr) IdentityQrDialog(state, { showQr = false })
    scanned?.let { payload -> AddScannedPeerDialog(payload, repository) { scanned = null } }
}

@Composable private fun IdentityCard(state: AppState, showQr: () -> Unit, scan: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(.10f)), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.onPrimary) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Mi identidad", fontWeight = FontWeight.Bold); Text(if(state.identityReady) "Lista para compartir" else "Generando…", color = if(state.identityReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; IconButton(showQr, enabled = state.identityReady) { Icon(Icons.Default.QrCode2, "Mostrar QR", tint = MaterialTheme.colorScheme.primary) } }
            Spacer(Modifier.height(12.dp)); Text(state.ownId.chunked(4).joinToString(" "), fontSize = 11.sp, color = Muted, maxLines = 3)
            Spacer(Modifier.height(12.dp)); Row { OutlinedButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ShareToLocate ID", state.ownId)) }, enabled = state.identityReady, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copiar") }; Spacer(Modifier.width(8.dp)); Button(onClick = scan, modifier = Modifier.weight(1f)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Escanear") } }
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
    AlertDialog(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface, title = { Text(if(error) "QR no válido" else "Conectar con $suggested") }, text = { if(error) Text("Este código no pertenece a ShareToLocate.", color = MaterialTheme.colorScheme.error) else Column { Text("Se enviará una solicitud P2P. La ubicación no se compartirá hasta verificar el código.", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) } }, confirmButton = { if(!error) Button(onClick = { if(repository.addPeer(id, name, nonce)) close() else error = true }) { Text("Enviar solicitud") } }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
}

@Composable private fun RequestDialog(request: ContactRequest, repository: ShareRepository) { AlertDialog(onDismissRequest = {}, containerColor = MaterialTheme.colorScheme.surface, icon = { Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) }, title = { Text("Solicitud de ${request.name}") }, text = { Text("Quiere conectar contigo. Aún no podrá ver tu ubicación; primero confirmaréis un código en ambos teléfonos.", color = MaterialTheme.colorScheme.onSurfaceVariant) }, confirmButton = { Button(onClick = { repository.acceptRequest(request) }) { Text("Aceptar") } }, dismissButton = { TextButton(onClick = { repository.rejectRequest(request) }) { Text("Rechazar") } }) }

@Composable private fun VerificationDialog(peer: Peer, repository: ShareRepository) { AlertDialog(onDismissRequest = {}, containerColor = MaterialTheme.colorScheme.surface, icon = { Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary) }, title = { Text("Verifica a ${peer.name}") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Comprueba en persona que ambos teléfonos muestran exactamente este código:", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(18.dp)); Text(peer.verificationCode ?: "", fontSize = 34.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text("Si no coincide, rechaza la conexión.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }, confirmButton = { Button(onClick = { repository.confirmVerification(peer.id) }, enabled = !peer.localConfirmed) { Text(if(peer.localConfirmed) "Esperando al otro móvil" else "El código coincide") } }, dismissButton = { TextButton(onClick = { repository.removePeer(peer.id) }) { Text("No coincide") } }) }

private fun qrBitmap(content: String, size: Int): Bitmap { val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size); return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { image -> for(y in 0 until size) for(x in 0 until size) image.setPixel(x, y, if(matrix[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) } }

@Composable private fun PeerCard(peer: Peer, repository: ShareRepository, viewOnMap: () -> Unit, remove: () -> Unit) {
    val context = LocalContext.current
    var editNickname by remember { mutableStateOf(false) }
    var showRingDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(peer.id) { mutableStateOf(false) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { contact ->
                if (!contact.moveToFirst()) return@use null
                val name = contact.getString(0)
                name to contact.getString(1)
            }
        }.getOrNull()?.let { (name, phone) -> repository.setPeerLinkedContact(peer.id, name, phone) }
    }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(peer.ringRequestedAt, peer.ringAcknowledgedAt) {
        while (peer.ringRequestedAt != null && peer.ringAcknowledgedAt == null && clock - peer.ringRequestedAt < 60_000L) {
            delay(1_000L); clock = System.currentTimeMillis()
        }
    }
    val canRing = peer.verified && peer.online && peer.remoteAllowsRing
    val cooldownSeconds = peer.ringRequestedAt?.let { requestedAt -> ((60_000L - (clock - requestedAt)).coerceAtLeast(0L) + 999L) / 1_000L } ?: 0L
    val cooldownActive = cooldownSeconds > 0L && peer.ringAcknowledgedAt == null
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(50.dp).background(MaterialTheme.colorScheme.primary.copy(.18f), CircleShape), contentAlignment = Alignment.Center) { Text(peer.displayName.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 20.sp) }; Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(peer.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.size(7.dp).background(if (peer.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)) }; if (peer.nickname != null) Text("Nombre original: ${peer.name}", color = Muted, fontSize = 11.sp); Text(if (peer.sharing) "Compartiendo ahora" else if (peer.location != null) "Última ubicación guardada" else if (peer.online) "En línea" else "Sin conexión", color = if (peer.sharing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); Text(peer.id, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(.65f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; IconButton({ editNickname = true }) { Icon(Icons.Default.Edit, "Editar alias") }; IconButton(onClick = remove) { Icon(Icons.Default.DeleteOutline, "Eliminar", tint = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Ocultar opciones" else "Mostrar opciones") }
        Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedButton(onClick = { if (repository.ringPeer(peer.id)) Toast.makeText(context, "Toque enviado", Toast.LENGTH_SHORT).show() }, enabled = canRing && !cooldownActive, modifier = Modifier.weight(1f)) { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(7.dp)); Text("Hacer sonar") }; OutlinedIconButton(onClick = { showRingDialog = true }, enabled = canRing && !cooldownActive) { Icon(Icons.Default.MoreHoriz, "Mensaje personalizado") } }
        if (cooldownActive) Text("Podrás enviar otro toque en ${cooldownSeconds} s", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        else if (peer.ringRequestedAt != null && peer.ringAcknowledgedAt != null) Text("Respondido en ${((peer.ringAcknowledgedAt - peer.ringRequestedAt).coerceAtLeast(0) / 1000.0)} s", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        AnimatedVisibility(expanded) { Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Permitir hacer sonar", Modifier.weight(1f), fontSize = 13.sp); Switch(peer.allowRing, { repository.setPeerRingAllowed(peer.id, it) }, enabled = peer.verified) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Mostrar en el menu Seguir a", Modifier.weight(1f), fontSize = 13.sp); Switch(peer.showInFollowMenu, { repository.setPeerFollowMenuVisible(peer.id, it) }, enabled = peer.location != null) }
        TextButton(onClick = viewOnMap, enabled = peer.location != null, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PersonPinCircle, null); Spacer(Modifier.width(7.dp)); Text(if (peer.sharing) "Ver en el mapa" else "Ver última ubicación") }
        TextButton(onClick = { peer.location?.let { openInGoogleMaps(context, it, peer.displayName) } }, enabled = peer.location != null, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(7.dp)); Text("Abrir coordenadas en Google Maps") }
        TextButton(onClick = { contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(7.dp)); Text(peer.linkedContactName?.let { "Contacto vinculado: $it" } ?: "Vincular contacto para llamar o WhatsApp") }
        TextButton(onClick = { showColorDialog = true }, modifier = Modifier.fillMaxWidth()) { Box(Modifier.size(18.dp).background(Color(peer.accent), CircleShape)); Spacer(Modifier.width(9.dp)); Text("Color del cursor") }
        if (peer.linkedContactPhone != null) TextButton(onClick = { repository.setPeerLinkedContact(peer.id, null, null) }, modifier = Modifier.align(Alignment.End)) { Text("Quitar contacto vinculado") }
        if (!canRing) Text(when { !peer.online -> "Disponible cuando esté en línea"; !peer.remoteAllowsRing -> "${peer.displayName} no te ha dado permiso"; else -> "Contacto pendiente de verificación" }, color = Muted, fontSize = 11.sp)
        } }
    } }
    if (editNickname) NicknameDialog(peer, repository) { editNickname = false }
    if (showRingDialog) RingMessageDialog(peer, repository) { showRingDialog = false }
    if (showColorDialog) PeerColorDialog(peer, repository) { showColorDialog = false }
}

@Composable private fun PeerColorDialog(peer: Peer, repository: ShareRepository, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Color de ${peer.displayName}") }, text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { AccentColor.entries.forEach { option -> val selected = peer.accent == option.argb; Surface(onClick = { repository.setPeerAccent(peer.id, option.argb); close() }, modifier = Modifier.size(40.dp), shape = CircleShape, color = Color(option.argb), border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null) { if (selected) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Ink) } } } } }, confirmButton = { TextButton(close) { Text("Cerrar") } })
}

@Composable private fun RingMessageDialog(peer: Peer, repository: ShareRepository, close: () -> Unit) {
    val context = LocalContext.current
    var message by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Hacer sonar a ${peer.displayName}") },
        text = { Column { Text("Puedes añadir un mensaje que aparecerá en su notificación.", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); OutlinedTextField(value = message, onValueChange = { message = it.take(140) }, label = { Text("Mensaje opcional") }, placeholder = { Text("Ej.: Llámame cuando puedas") }, supportingText = { Text("${message.length}/140") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()) } },
        confirmButton = { Button(onClick = { if (repository.ringPeer(peer.id, message)) { Toast.makeText(context, "Toque enviado", Toast.LENGTH_SHORT).show(); close() } }) { Text("Enviar toque") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }
    )
}

@Composable private fun NicknameDialog(peer: Peer, repository: ShareRepository, close: () -> Unit) { var value by remember { mutableStateOf(peer.nickname.orEmpty()) }; AlertDialog(onDismissRequest = close, title = { Text("Editar alias") }, text = { Column { Text("Nombre original: ${peer.name}", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); OutlinedTextField(value, { value = it.take(40) }, label = { Text("Nick local") }, singleLine = true) } }, confirmButton = { Button({ repository.setPeerNickname(peer.id, value); close() }) { Text("Guardar") } }, dismissButton = { TextButton(close) { Text("Cancelar") } }) }

@Composable private fun SettingsScreen(state: AppState, repository: ShareRepository, openPeople: () -> Unit) {
    val context = LocalContext.current
    var changePin by remember { mutableStateOf(false) }
    var confirmDisableStartLocate by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ajustes", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Mapa, apariencia y privacidad", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { ExpandableSettingsSection("Apariencia", Icons.Default.Palette, initiallyExpanded = true) { AppearanceSettings(state, repository) } }
        item { ExpandableSettingsSection("Localización", Icons.Default.LocationOn) {
            SettingsGroup { SettingSwitch(Icons.Default.RestartAlt, "Start Locate", "Activa automáticamente la ubicación al arrancar el móvil", state.settings.startLocateOnBoot) { enabled -> if (enabled) repository.updateSettings { it.copy(startLocateOnBoot = true) } else confirmDisableStartLocate = true }; SettingSwitch(Icons.Default.AutoAwesomeMotion, "Suavizar mi movimiento", "Anima saltos entre lecturas GPS", state.settings.smoothMine) { repository.updateSettings { s -> s.copy(smoothMine = it) } }; SettingSwitch(Icons.Default.Groups, "Suavizar contactos", "Movimiento más natural en el mapa", state.settings.smoothFriends) { repository.updateSettings { s -> s.copy(smoothFriends = it) } } }
            LocationIntervalCard(state, repository)
            MapHomeCard(state, repository)
            QuickTileCard()
        } }
        item { ExpandableSettingsSection("Hacer sonar", Icons.Default.NotificationsActive) { RingSettingsCard(state, repository, openPeople) } }
        item { ExpandableSettingsSection("General", Icons.Default.Tune) {
            SettingsGroup { SettingSwitch(Icons.Default.Security, "Bloquear capturas", "Oculta la app en capturas y recientes", state.settings.preventScreenshots) { repository.updateSettings { s -> s.copy(preventScreenshots = it) } }; SettingSwitch(Icons.Default.ScreenLockPortrait, "Mantener pantalla activa", "Útil durante trayectos", state.settings.keepScreenOn) { repository.updateSettings { s -> s.copy(keepScreenOn = it) } }; TextButton(onClick = { changePin = true }, modifier = Modifier.fillMaxWidth().padding(6.dp)) { Icon(Icons.Default.Password, null); Spacer(Modifier.width(8.dp)); Text("Cambiar PIN de acceso") } }
            LocationNotificationCard()
            BackupCard(repository)
            MigrationCard(repository)
        } }
        item { Spacer(Modifier.height(60.dp)) }
    }
    if (changePin) ChangePinDialog(AppLockStore(context)) { changePin = false }
    if (confirmDisableStartLocate) AlertDialog(onDismissRequest = { confirmDisableStartLocate = false }, icon = { Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error) }, title = { Text("Desactivar Start Locate") }, text = { Text("Después de reiniciar el móvil, ShareToLocate no activará la ubicación automáticamente. Tendrás que abrir la aplicación y activarla manualmente.") }, confirmButton = { Button(onClick = { repository.updateSettings { it.copy(startLocateOnBoot = false) }; confirmDisableStartLocate = false; Toast.makeText(context, "Start Locate desactivado: deberás activarlo manualmente", Toast.LENGTH_LONG).show() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Desactivar") } }, dismissButton = { TextButton({ confirmDisableStartLocate = false }) { Text("Cancelar") } })
}

@Composable private fun BackupCard(repository: ShareRepository) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var importedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        AppLockCoordinator.externalActivityInProgress = false
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportBytes ?: byteArrayOf()) } }.onSuccess { message = "Copia cifrada guardada" }.onFailure { message = it.message }
        exportBytes = null
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AppLockCoordinator.externalActivityInProgress = false
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer el archivo") }.onSuccess { importedBytes = it; password = ""; mode = "import" }.onFailure { message = it.message }
    }
    SettingsGroup { Column(Modifier.padding(16.dp)) {
        Text("Copia de seguridad cifrada", fontWeight = FontWeight.Bold); Text("Guarda identidad, contactos y ajustes en un archivo protegido por contraseña.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp)); Button({ password = ""; mode = "export" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Crear copia") }
        OutlinedButton({ AppLockCoordinator.externalActivityInProgress = true; openFile.launch(arrayOf("application/octet-stream", "application/*")) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(7.dp)); Text("Restaurar copia") }
    } }
    if (mode != null) AlertDialog(onDismissRequest = { mode = null }, title = { Text(if (mode == "export") "Proteger copia" else "Desbloquear copia") }, text = { Column { Text("Usa una contraseña de 6 caracteres como mínimo. Si la pierdes, la copia no podrá recuperarse.", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); OutlinedTextField(password, { password = it }, label = { Text("Contraseña de la copia") }, visualTransformation = PasswordVisualTransformation(), singleLine = true) } }, confirmButton = { Button({
        if (mode == "export") runCatching { repository.exportEncryptedBackup(password) }.onSuccess { exportBytes = it; mode = null; AppLockCoordinator.externalActivityInProgress = true; createFile.launch("ShareToLocate-backup.stlb") }.onFailure { message = it.message }
        else runCatching { repository.importEncryptedBackup(importedBytes ?: error("Falta el archivo"), password) }.onSuccess { mode = null; message = "Copia restaurada. Reinicia la aplicación." }.onFailure { message = it.message }
    }, enabled = password.length >= 6) { Text(if (mode == "export") "Continuar" else "Restaurar") } }, dismissButton = { TextButton({ mode = null }) { Text("Cancelar") } })
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("Copia de seguridad") }, text = { Text(it) }, confirmButton = { TextButton({ message = null }) { Text("Aceptar") } }) }
}

@Composable private fun LocationIntervalCard(state: AppState, repository: ShareRepository) { SettingsGroup { Text("AHORRO DE BATERÍA", Modifier.padding(start = 16.dp, top = 16.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Intervalo entre actualizaciones GPS. Por defecto: 15 minutos.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Muted, fontSize = 12.sp); Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1,3,6,12,15,20,30,60).forEach { minutes -> FilterChip(state.settings.locationIntervalMinutes == minutes, { repository.setLocationInterval(minutes) }, { Text("$minutes min") }) } }; Spacer(Modifier.height(10.dp)) } }

@Composable private fun DeliveryStatsCard(stats: DeliveryStats) {
    SettingsGroup { Column(Modifier.padding(16.dp)) {
        Text("Actividad en segundo plano", fontWeight = FontWeight.Bold)
        Text("Trafico de ubicaciones y envios repetidos evitados.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatValue("Capturas", stats.locationsCaptured)
            StatValue("Enviadas", stats.locationsSent)
            StatValue("Ahorradas", stats.locationsSuppressed)
            StatValue("Recibidas", stats.locationsReceived)
        }
        if (stats.refreshRequestsSent > 0) Text("Solicitudes de refresco: ${stats.refreshRequestsSent}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
    } }
}

@Composable private fun StatValue(label: String, value: Long) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(label, color = Muted, fontSize = 10.sp) } }

@Composable private fun LocationNotificationCard() {
    val context = LocalContext.current
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Notificación de ubicación", fontWeight = FontWeight.Bold)
                Text("Android exige este aviso mientras ShareToLocate comparte en segundo plano.", color = Muted, fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = { openLocationNotificationSettings(context) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(Icons.Default.LocationOn, null); Spacer(Modifier.width(8.dp)); Text("Aviso de ubicacion")
        }
        Button(onClick = { openAppNotificationSettings(context) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("Configurar todas las notificaciones")
        }
    }
}

@Composable private fun RingSettingsCard(state: AppState, repository: ShareRepository, openPeople: () -> Unit) {
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Permitir ‘Hacer sonar’", fontWeight = FontWeight.Bold); Text("Autoriza contactos concretos para darte un toque", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; Switch(state.settings.allowRing, { repository.updateSettings { s -> s.copy(allowRing = it) } }, enabled = state.sharing || state.settings.allowRingWithoutLocation) }
        SettingSwitch(Icons.Default.LocationOff, "Permitir con ubicación desactivada", "Mantiene Hacer sonar disponible sin compartir tu posición", state.settings.allowRingWithoutLocation) { enabled -> repository.updateSettings { it.copy(allowRingWithoutLocation = enabled) } }
        AnimatedVisibility(state.settings.allowRing) { Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("COMPORTAMIENTO", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            RingBehavior.entries.forEach { behavior -> Row(Modifier.fillMaxWidth().clickable { repository.updateSettings { it.copy(ringBehavior = behavior) } }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(state.settings.ringBehavior == behavior, { repository.updateSettings { it.copy(ringBehavior = behavior) } }); Text(when (behavior) { RingBehavior.NOTIFICATION_ONLY -> "Solo notificación"; RingBehavior.NOTIFICATION_WITH_SOUND -> "Notificación con sonido"; RingBehavior.SOUND_ONLY -> "Solo sonido" }) } }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Los permisos por contacto se administran desde Personas.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f)); TextButton(onClick = openPeople) { Text("Ir a Personas") } }
            Text("Los sonidos se silencian automáticamente cuando No molestar está activo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
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

@Composable private fun ExpandableSettingsSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Cerrar" else "Abrir")
            }
            AnimatedVisibility(expanded) { Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), content = content) }
        }
    }
}

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

@Composable private fun MapHomeCard(state: AppState, repository: ShareRepository) {
    val currentLocation = state.ownLocation?.takeIf(::isValidMapLocation)
    val defaultLocation = state.settings.defaultMapCenter
    SettingsGroup {
        Column(Modifier.padding(16.dp)) {
            Text("Inicio del mapa", fontWeight = FontWeight.Bold)
            Text(
                if (defaultLocation == null) "Sin punto fijo: se muestra el encuadre de las ubicaciones activas."
                else "Ubicación predeterminada configurada para abrir el mapa cuando aún no hay posiciones activas.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    repository.setDefaultMapCenter(currentLocation)
                    MapRenderMemory.center = currentLocation?.let { OsmPoint(it.latitude, it.longitude) }
                    MapRenderMemory.zoom = 14.5
                    MapRenderMemory.initialized = false
                    MapRenderMemory.userAdjustedViewport = false
                    MapRenderMemory.overviewKey = "__pending__"
                },
                enabled = currentLocation != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.MyLocation, null)
                Spacer(Modifier.width(8.dp))
                Text("Usar mi ubicación actual")
            }
            if (defaultLocation != null) {
                TextButton(
                    onClick = {
                        repository.setDefaultMapCenter(null)
                        MapRenderMemory.center = null
                        MapRenderMemory.initialized = false
                        MapRenderMemory.userAdjustedViewport = false
                        MapRenderMemory.overviewKey = "__pending__"
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Quitar ubicación predeterminada") }
            }
        }
    }
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(.10f)), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp)) {
        Text("Migrar a otro móvil", fontWeight = FontWeight.Bold); Text("Transfiere identidad, contactos y preferencias con cifrado de extremo a extremo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp)); Button({ runCatching { repository.createMigrationOffer() }.onSuccess { offer = it }.onFailure { error = it.message } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(7.dp)); Text("Mostrar QR de migración") }
        OutlinedButton({
            AppLockCoordinator.externalActivityInProgress = true
            scanner.startScan().addOnSuccessListener { code -> code.rawValue?.let { raw -> scope.launch { runCatching { repository.importMigration(raw) }.onSuccess { Toast.makeText(context, "Migración completada. Abre de nuevo la app.", Toast.LENGTH_LONG).show(); kotlinx.coroutines.delay(1800); android.os.Process.killProcess(android.os.Process.myPid()) }.onFailure { error = it.message } } } }
                .addOnCompleteListener { AppLockCoordinator.externalActivityInProgress = false }
        }, Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(7.dp)); Text("Escanear en el móvil nuevo") }
        Text("Los dos móviles deben estar en la misma Wi‑Fi. El QR caduca en 3 minutos.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    } }
    offer?.let { current -> AlertDialog(onDismissRequest = { current.close(); offer = null }, title = { Text("Escanea con el móvil nuevo") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Image(qrBitmap(current.payload, 760).asImageBitmap(), "QR de migración", Modifier.fillMaxWidth().aspectRatio(1f)); Text("No compartas este código: autoriza una única transferencia.", fontSize = 12.sp) } }, confirmButton = { TextButton({ current.close(); offer = null }) { Text("Cerrar") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("No se pudo migrar") }, text = { Text(message ?: "Error desconocido") }, confirmButton = { TextButton({ error = null }) { Text("Aceptar") } }) }
}

@Composable private fun SettingSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; Switch(checked, change) } }

@Composable private fun AddPeerDialog(repository: ShareRepository, close: () -> Unit) { var id by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var error by remember { mutableStateOf(false) }; AlertDialog(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface, title = { Text("Añadir persona") }, text = { Column { Text("Pega su identificador seguro. La solicitud se enviará directamente.", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true); Spacer(Modifier.height(8.dp)); OutlinedTextField(id, { id = it; error = false }, label = { Text("ID de ShareToLocate") }, isError = error, supportingText = if (error) {{ Text("El identificador no es válido") }} else null, singleLine = true) } }, confirmButton = { Button(onClick = { if (repository.addPeer(id, name)) close() else error = true }) { Text("Enviar solicitud") } }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }) }

@Composable private fun RowScope.NavItem(item: Tab, current: Tab, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, click: () -> Unit) { NavigationBarItem(selected = item == current, onClick = click, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.onPrimary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary, unselectedIconColor = Muted, unselectedTextColor = Muted)) }

private fun startSharing(activity: MainActivity) { ContextCompat.startForegroundService(activity, Intent(activity, LocationSharingService::class.java)) }
private fun stopSharing(activity: MainActivity) { activity.startService(Intent(activity, LocationSharingService::class.java).setAction(LocationSharingService.ACTION_STOP)) }
private fun openLocationNotificationSettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, LocationSharingService.CHANNEL)
    })
}
private fun openAppNotificationSettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    })
}
private fun openInGoogleMaps(context: android.content.Context, point: GeoPoint, label: String) {
    val query = "${point.latitude},${point.longitude}(${label})"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).setPackage("com.google.android.apps.maps")
    runCatching { context.startActivity(intent) }.getOrElse {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")))
    }
}
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
