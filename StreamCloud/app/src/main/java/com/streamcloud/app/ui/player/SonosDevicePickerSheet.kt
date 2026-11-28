package com.streamcloud.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.streamcloud.app.cast.MusicRemoteCast
import com.streamcloud.app.cast.dlna.DlnaDevice
import com.streamcloud.app.cast.dlna.DlnaRepository
import com.streamcloud.app.data.sonos.SonosGroup
import com.streamcloud.app.data.sonos.SonosRepository
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup

private val PickerBackground = Color(0xFF101318)
private val PickerRowBackground = Color(0xFF030405)
private val PickerIconBackground = Color(0xFF263345)
private val PickerMuted = Color(0xFFADB8C9)
private val PickerAccent = Color(0xFF8DA9D1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonosDevicePickerSheet(
    videoId: String,
    title: String,
    watchUrl: String,
    artist: String = "",
    album: String = "",
    artworkUrl: String? = null,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sonosState by SonosRepository.castState.collectAsState()
    val sonosDevices by SonosRepository.devices.collectAsState()
    val sonosVolume by SonosRepository.sonosVolume.collectAsState()
    val remoteState by MusicRemoteCast.state.collectAsState()
    val dlnaState by DlnaRepository.state.collectAsState()
    val selectedDlnaDevice by DlnaRepository.selectedDevice.collectAsState()
    val dlnaDevices =
        (dlnaState as? DlnaRepository.State.Ready)?.devices.orEmpty()
    val castContext = remember(context) {
        runCatching {
            CastContext.getSharedInstance(context.applicationContext)
        }.getOrNull()
    }
    val mediaRouter = remember(context) {
        MediaRouter.getInstance(context.applicationContext)
    }
    val routeSelector = remember(castContext) {
        castContext?.mergedSelector ?: MediaRouteSelector.EMPTY
    }
    val mediaRoutes = remember { mutableStateListOf<MediaRouter.RouteInfo>() }
    var selectedRouteId by remember { mutableStateOf<String?>(null) }
    var volumeSliderValue by remember(sonosVolume) {
        mutableFloatStateOf(sonosVolume / 100f)
    }

    LaunchedEffect(Unit) {
        if (sonosState !is SonosRepository.CastState.Casting) {
            SonosRepository.startDiscovery(context)
        }
        DlnaRepository.discover(context)
    }

    DisposableEffect(routeSelector) {
        fun updateSelectedRoute() {
            val route = mediaRouter.selectedRoute
            selectedRouteId = route.id.takeUnless { route.isDefault }
        }

        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refreshDeviceRoutes(router, routeSelector, mediaRoutes)
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refreshDeviceRoutes(router, routeSelector, mediaRoutes)
                updateSelectedRoute()
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refreshDeviceRoutes(router, routeSelector, mediaRoutes)
            }

            override fun onRouteSelected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) {
                selectedRouteId = route.id.takeUnless { route.isDefault }
            }

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) {
                if (selectedRouteId == route.id) updateSelectedRoute()
            }
        }
        mediaRouter.addCallback(
            routeSelector,
            callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
        )
        refreshDeviceRoutes(mediaRouter, routeSelector, mediaRoutes)
        updateSelectedRoute()
        onDispose { mediaRouter.removeCallback(callback) }
    }

    val googleRoutes = mediaRoutes.filter {
        !it.isBluetooth && it.matchesSelector(routeSelector)
    }
    val bluetoothRoutes = mediaRoutes.filter { it.isBluetooth }
    val selectedRoute = mediaRoutes.firstOrNull { it.id == selectedRouteId }
        ?: mediaRouter.selectedRoute.takeUnless { it.isDefault }
    val sonosCasting = sonosState as? SonosRepository.CastState.Casting
    val sonosConnecting = sonosState is SonosRepository.CastState.Connecting
    val remoteCasting = remoteState as? MusicRemoteCast.State.Casting
    val remoteConnecting = remoteState as? MusicRemoteCast.State.Connecting
    val connecting = sonosConnecting || remoteConnecting != null
    val connectedName = when {
        sonosConnecting -> "Connecting to Sonos"
        remoteConnecting != null -> remoteConnecting.name
        sonosCasting != null -> sonosCasting.displayName
        remoteCasting != null -> remoteCasting.name
        selectedDlnaDevice != null -> selectedDlnaDevice?.name
        selectedRoute != null -> selectedRoute.name.toString()
        else -> null
    }
    val connectedProvider = when {
        sonosCasting != null || sonosConnecting -> "Sonos"
        remoteConnecting?.destination == MusicRemoteCast.DestinationType.GoogleCast -> "Google Cast"
        remoteConnecting?.destination == MusicRemoteCast.DestinationType.Dlna -> "DLNA"
        remoteCasting?.destination == MusicRemoteCast.DestinationType.GoogleCast -> "Google Cast"
        remoteCasting?.destination == MusicRemoteCast.DestinationType.Dlna -> "DLNA"
        selectedDlnaDevice != null -> "DLNA"
        selectedRoute?.isBluetooth == true -> "Bluetooth"
        selectedRoute != null -> "Google Cast"
        else -> null
    }
    val connectedSubtitle = when {
        connecting -> "Connecting"
        sonosCasting != null -> sonosCasting.title
        remoteCasting != null -> remoteCasting.title
        connectedProvider != null -> connectedProvider
        else -> null
    }
    val connectedIcon = when (connectedProvider) {
        "Sonos" -> Icons.Default.SpeakerGroup
        "DLNA" -> Icons.Default.Tv
        "Bluetooth" -> Icons.Default.Speaker
        "Google Cast" -> Icons.Default.CastConnected
        else -> Icons.Default.Cast
    }
    val sonosGroups =
        (sonosState as? SonosRepository.CastState.DevicesFound)?.groups.orEmpty()
    val sonosRefreshing =
        sonosState is SonosRepository.CastState.Discovering ||
            (sonosState as? SonosRepository.CastState.DevicesFound)?.isRefreshing == true
    val sonosWarning =
        (sonosState as? SonosRepository.CastState.DevicesFound)?.refreshWarning
            ?: (sonosState as? SonosRepository.CastState.Error)?.message
    val dlnaDiscovering = dlnaState is DlnaRepository.State.Discovering
    val activeSonosUdn = sonosCasting?.device?.udn
    val activeGoogleRouteId = selectedRouteId?.takeIf { selectedRoute?.isBluetooth == false }
    val activeDlnaName = remoteCasting
        ?.takeIf { it.destination == MusicRemoteCast.DestinationType.Dlna }
        ?.name

    fun refreshAllDevices() {
        SonosRepository.startDiscovery(context)
        DlnaRepository.discover(context)
        refreshDeviceRoutes(mediaRouter, routeSelector, mediaRoutes)
    }

    fun clearOtherDestination() {
        when (sonosState) {
            is SonosRepository.CastState.Casting ->
                SonosRepository.disconnect(resumeOnPhone = false)
            SonosRepository.CastState.Connecting ->
                SonosRepository.cancelConnection(context)
            else -> Unit
        }
    }

    fun disconnectCurrent() {
        when {
            sonosCasting != null -> SonosRepository.disconnect()
            sonosConnecting -> SonosRepository.cancelConnection(context)
            remoteCasting != null || remoteConnecting != null ->
                MusicRemoteCast.disconnect()
            selectedDlnaDevice != null -> DlnaRepository.selectDevice(null)
            selectedRoute != null ->
                mediaRouter.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
        }
    }

    fun connectSonos(group: SonosGroup) {
        MusicRemoteCast.handOffToSonos {
            SonosRepository.connect(
                context = context,
                device = group.coordinatorDevice,
                videoId = videoId,
                title = title,
                watchUrl = watchUrl,
                displayName = group.displayName,
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PickerBackground,
        contentColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Connected devices",
                    color = Color(0xFFE3E8F1),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refreshAllDevices() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh devices",
                        tint = PickerMuted,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = PickerMuted,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .tvFocusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
            ) {
                item(key = "connected_header") {
                    PickerSectionTitle("Connected")
                }
                item(key = "connected_device") {
                    PickerDeviceRow(
                        name = connectedName ?: "No device connected",
                        subtitle = connectedSubtitle,
                        icon = connectedIcon,
                        isConnected = connectedName != null && !connecting,
                        isLoading = connecting,
                        onClick = null,
                    )
                }
                if (sonosCasting != null) {
                    item(key = "sonos_volume") {
                        SonosVolumeControl(
                            volume = sonosVolume,
                            value = volumeSliderValue,
                            onValueChange = { volumeSliderValue = it },
                            onValueChangeFinished = {
                                SonosRepository.setVolume((volumeSliderValue * 100).toInt())
                            },
                        )
                    }
                }
                if (connectedName != null || connecting) {
                    item(key = "disconnect") {
                        TextButton(
                            onClick = { disconnectCurrent() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (connecting) "Cancel connection" else "Disconnect")
                        }
                    }
                }
                remoteState.let { state ->
                    if (state is MusicRemoteCast.State.Error) {
                        item(key = "remote_error") {
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                sonosWarning?.let { warning ->
                    item(key = "sonos_warning") {
                        Text(
                            warning,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                item(key = "available_header") {
                    Spacer(Modifier.height(6.dp))
                    PickerSectionTitle("Available devices")
                }

                if (sonosRefreshing) {
                    item(key = "sonos_refreshing") {
                        ScanStatus("Showing saved Sonos speakers while refreshing")
                    }
                }
                if (dlnaDiscovering) {
                    item(key = "dlna_refreshing") {
                        ScanStatus("Scanning your Wi-Fi for TVs and speakers")
                    }
                }

                items(
                    items = sonosGroups.filter {
                        it.coordinatorDevice.udn != activeSonosUdn &&
                            it.displayName != sonosCasting?.displayName
                    },
                    key = { "sonos_group_${it.id}" },
                ) { group ->
                    PickerDeviceRow(
                        name = group.displayName,
                        subtitle = "Sonos group · ${group.memberNames.joinToString(" · ")}",
                        icon = Icons.Default.SpeakerGroup,
                        onClick = { connectSonos(group) },
                    )
                }

                items(
                    items = sonosDevices.filter { device ->
                        device.udn != activeSonosUdn &&
                            device.name != sonosCasting?.displayName
                    },
                    key = { "sonos_${it.udn}" },
                ) { device ->
                    PickerDeviceRow(
                        name = device.name,
                        subtitle = "Sonos",
                        icon = Icons.Default.Speaker,
                        onClick = {
                            MusicRemoteCast.handOffToSonos {
                                SonosRepository.connect(
                                    context = context,
                                    device = device,
                                    videoId = videoId,
                                    title = title,
                                    watchUrl = watchUrl,
                                )
                            }
                        },
                    )
                }

                items(
                    items = googleRoutes.filter {
                        it.id != activeGoogleRouteId &&
                            it.name.toString() != remoteCasting?.name
                    },
                    key = { "cast_${it.id}" },
                ) { route ->
                    PickerDeviceRow(
                        name = route.name.toString(),
                        subtitle = "Google Cast",
                        icon = Icons.Default.Tv,
                        onClick = {
                            clearOtherDestination()
                            MusicRemoteCast.connectGoogle(
                                context = context,
                                route = route,
                                videoId = videoId,
                                title = title,
                                watchUrl = watchUrl,
                                artist = artist,
                                album = album,
                                artworkUrl = artworkUrl,
                            )
                        },
                    )
                }

                items(
                    items = dlnaDevices.filter {
                        it.udn != selectedDlnaDevice?.udn &&
                            it.name != activeDlnaName
                    },
                    key = { "dlna_${it.udn}" },
                ) { device ->
                    PickerDeviceRow(
                        name = device.name,
                        subtitle = "DLNA · ${device.host}",
                        icon = Icons.Default.Tv,
                        onClick = {
                            clearOtherDestination()
                            MusicRemoteCast.connectDlna(
                                context = context,
                                device = device,
                                videoId = videoId,
                                title = title,
                                watchUrl = watchUrl,
                                artist = artist,
                                album = album,
                                artworkUrl = artworkUrl,
                            )
                        },
                    )
                }

                items(
                    items = bluetoothRoutes.filter { it.id != selectedRouteId },
                    key = { "bluetooth_${it.id}" },
                ) { route ->
                    PickerDeviceRow(
                        name = route.name.toString(),
                        subtitle = "Bluetooth",
                        icon = Icons.Default.Speaker,
                        onClick = {
                            clearOtherDestination()
                            MusicRemoteCast.switchToBluetooth(context, route, onDismiss)
                        },
                    )
                }

                if (
                    sonosDevices.isEmpty() && sonosGroups.isEmpty() &&
                    googleRoutes.isEmpty() && dlnaDevices.isEmpty() &&
                    bluetoothRoutes.isEmpty()
                ) {
                    item(key = "no_devices") {
                        Text(
                            if (sonosRefreshing || dlnaDiscovering) {
                                "Searching for nearby devices…"
                            } else {
                                "No available devices found. Make sure they’re powered on and on the same network."
                            },
                            color = PickerMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }

                item(key = "pairing_hint") {
                    Text(
                        "To pair a new device, open Integrations in Settings. Bluetooth devices must be paired in Android Settings first.",
                        color = PickerMuted.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerSectionTitle(text: String) {
    Text(
        text,
        color = PickerAccent,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerDeviceRow(
    name: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isConnected: Boolean = false,
    isLoading: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = PickerRowBackground,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusBorder(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = PickerIconBackground,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = PickerMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = Color(0xFFE5E9F1),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = PickerMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = PickerAccent,
                    strokeWidth = 2.dp,
                )
                isConnected -> Text(
                    "Connected",
                    color = PickerAccent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ScanStatus(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = PickerAccent,
            strokeWidth = 1.6.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = PickerMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SonosVolumeControl(
    volume: Int,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Volume",
                color = PickerMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text("$volume%", color = PickerMuted, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = PickerAccent,
                activeTrackColor = PickerAccent,
                inactiveTrackColor = PickerMuted.copy(alpha = 0.25f),
            ),
        )
    }
}

private fun refreshDeviceRoutes(
    router: MediaRouter,
    selector: MediaRouteSelector,
    out: androidx.compose.runtime.snapshots.SnapshotStateList<MediaRouter.RouteInfo>,
) {
    val visible = router.routes.filter { route ->
        !route.isDefault && (route.isBluetooth || route.matchesSelector(selector))
    }
    if (visible.map { it.id } != out.map { it.id }) {
        out.clear()
        out.addAll(visible)
    }
}
