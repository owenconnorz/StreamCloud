package com.streamcloud.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.streamcloud.app.data.sonos.SonosDevice
import com.streamcloud.app.data.sonos.SonosGroup
import com.streamcloud.app.data.sonos.SonosRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonosDevicePickerSheet(
    videoId: String,
    title: String,
    watchUrl: String,
    onDismiss: () -> Unit,
) {
    val context   = LocalContext.current
    val castState by SonosRepository.castState.collectAsState()
    val remoteCastState by MusicRemoteCast.state.collectAsState()
    val dlnaState by DlnaRepository.state.collectAsState()
    val dlnaDevices = (dlnaState as? DlnaRepository.State.Ready)?.devices.orEmpty()
    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull()
    }
    val mediaRouter = remember(context) { MediaRouter.getInstance(context.applicationContext) }
    val routeSelector = remember(castContext) {
        castContext?.mergedSelector ?: MediaRouteSelector.EMPTY
    }
    val mediaRoutes = remember { mutableStateListOf<MediaRouter.RouteInfo>() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (castState !is SonosRepository.CastState.Casting) {
            SonosRepository.startDiscovery(context)
        }
        DlnaRepository.discover(context)
    }

    DisposableEffect(routeSelector) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) =
                refreshDeviceRoutes(router, routeSelector, mediaRoutes)

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) =
                refreshDeviceRoutes(router, routeSelector, mediaRoutes)

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) =
                refreshDeviceRoutes(router, routeSelector, mediaRoutes)
        }
        mediaRouter.addCallback(
            routeSelector,
            callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
        )
        refreshDeviceRoutes(mediaRouter, routeSelector, mediaRoutes)
        onDispose { mediaRouter.removeCallback(callback) }
    }

    val googleRoutes = mediaRoutes.filter { route ->
        !route.isBluetooth && route.matchesSelector(routeSelector)
    }
    val bluetoothRoutes = mediaRoutes.filter { it.isBluetooth }

    fun clearOtherDestination() {
        if (castState is SonosRepository.CastState.Casting) {
            SonosRepository.disconnect(resumeOnPhone = false)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1A1A1A),
        tonalElevation  = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SpeakerGroup,
                    contentDescription = null,
                    tint = Color(0xFF4FC3F7),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Cast to devices",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White.copy(alpha = 0.6f))
                }
            }

            Spacer(Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color(0xFF4FC3F7),
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Sonos") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Cast & TV") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Bluetooth") })
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> when (val state = castState) {

                is SonosRepository.CastState.Discovering ->
                    SheetStatus("Scanning for Sonos speakers…", showSpinner = true)

                is SonosRepository.CastState.DevicesFound -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Groups section
                        if (state.groups.isNotEmpty()) {
                            item(key = "groups_header") {
                                SectionLabel("Groups")
                                Spacer(Modifier.height(6.dp))
                            }
                            items(state.groups, key = { "g_${it.id}" }) { group ->
                                GroupRow(group = group) {
                                    MusicRemoteCast.handOffToSonos {
                                        SonosRepository.connect(
                                            context     = context,
                                            device      = group.coordinatorDevice,
                                            videoId     = videoId,
                                            title       = title,
                                            watchUrl    = watchUrl,
                                            displayName = group.displayName,
                                        )
                                    }
                                }
                            }
                            item(key = "speakers_header") {
                                Spacer(Modifier.height(10.dp))
                                SectionLabel("Speakers")
                                Spacer(Modifier.height(6.dp))
                            }
                        } else {
                            item(key = "speakers_only_header") {
                                SectionLabel("Choose a speaker")
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        // Individual speakers
                        items(state.devices, key = { "d_${it.udn}" }) { device ->
                            DeviceRow(device = device) {
                                MusicRemoteCast.handOffToSonos {
                                    SonosRepository.connect(
                                        context  = context,
                                        device   = device,
                                        videoId  = videoId,
                                        title    = title,
                                        watchUrl = watchUrl,
                                    )
                                }
                            }
                        }
                    }
                }

                is SonosRepository.CastState.Connecting ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SheetStatus("Connecting…", showSpinner = true)
                        OutlinedButton(
                            onClick = { SonosRepository.cancelConnection(context) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Cancel", color = Color.White)
                        }
                    }

                is SonosRepository.CastState.Casting -> {
                    val vol by SonosRepository.sonosVolume.collectAsState()
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.SpeakerGroup,
                            contentDescription = null,
                            tint = Color(0xFF4FC3F7),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Now casting to",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            state.displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.title,
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(24.dp))

                        Text(
                            "Volume",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.Start),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.VolumeDown,
                                contentDescription = "Volume down",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                            Slider(
                                value = vol / 100f,
                                onValueChange = { SonosRepository.setVolume((it * 100).toInt()) },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor        = Color(0xFF4FC3F7),
                                    activeTrackColor  = Color(0xFF4FC3F7),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                                ),
                            )
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = "Volume up",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            "$vol%",
                            color = Color.White.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { SonosRepository.disconnect(); onDismiss() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF992222)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Disconnect")
                        }
                    }
                }

                is SonosRepository.CastState.Error ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.message,
                            color = Color(0xFFFF6B6B),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { SonosRepository.startDiscovery(context) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Try Again", color = Color.White)
                        }
                    }

                SonosRepository.CastState.Idle ->
                    SheetStatus("Opening scanner…", showSpinner = true)
                }

                1 -> NetworkDestinationTab(
                    remoteState = remoteCastState,
                    googleRoutes = googleRoutes,
                    dlnaDevices = dlnaDevices,
                    isDlnaDiscovering = dlnaState is DlnaRepository.State.Discovering,
                    onGoogleRoute = { route ->
                        clearOtherDestination()
                        MusicRemoteCast.connectGoogle(
                            context = context,
                            route = route,
                            videoId = videoId,
                            title = title,
                            watchUrl = watchUrl,
                        )
                    },
                    onDlnaDevice = { device ->
                        clearOtherDestination()
                        MusicRemoteCast.connectDlna(
                            context = context,
                            device = device,
                            videoId = videoId,
                            title = title,
                            watchUrl = watchUrl,
                        )
                    },
                    onRescan = {
                        DlnaRepository.discover(context)
                        refreshDeviceRoutes(mediaRouter, routeSelector, mediaRoutes)
                    },
                    onDisconnect = { MusicRemoteCast.disconnect() },
                )

                else -> BluetoothDestinationTab(
                    routes = bluetoothRoutes,
                    onPickRoute = { route ->
                        clearOtherDestination()
                        MusicRemoteCast.switchToBluetooth(context, route, onDismiss)
                    },
                )
            }
        }
    }
}

@Composable
private fun NetworkDestinationTab(
    remoteState: MusicRemoteCast.State,
    googleRoutes: List<MediaRouter.RouteInfo>,
    dlnaDevices: List<DlnaDevice>,
    isDlnaDiscovering: Boolean,
    onGoogleRoute: (MediaRouter.RouteInfo) -> Unit,
    onDlnaDevice: (DlnaDevice) -> Unit,
    onRescan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    when (remoteState) {
        is MusicRemoteCast.State.Connecting -> SheetStatus(
            "Connecting to ${remoteState.name}…",
            showSpinner = true,
        )

        is MusicRemoteCast.State.Casting -> {
            Text(
                "Now playing on ${remoteState.name}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                remoteState.title,
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect", color = Color.White)
            }
        }

        else -> {
            Text("Google Cast / Google TV", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
            if (googleRoutes.isEmpty()) {
                Text(
                    "No Google Cast devices found. Check that the device is on the same Wi-Fi.",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                googleRoutes.forEach { route ->
                    DestinationRouteRow(
                        name = route.name.toString(),
                        subtitle = route.description?.toString(),
                        icon = Icons.Default.Tv,
                        onClick = { onGoogleRoute(route) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Network speakers & TVs (DLNA)", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
            when {
                isDlnaDiscovering && dlnaDevices.isEmpty() ->
                    SheetStatus("Scanning your Wi-Fi network…", showSpinner = true)

                dlnaDevices.isEmpty() -> {
                    Text(
                        "No compatible network devices found.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                else -> dlnaDevices.forEach { device ->
                    DestinationRouteRow(
                        name = device.name,
                        subtitle = device.host,
                        icon = Icons.Default.Speaker,
                        onClick = { onDlnaDevice(device) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Alexa/Echo devices only appear if they expose a standard Cast or DLNA route. Direct Alexa playback requires Amazon account integration.",
                color = Color.White.copy(alpha = 0.42f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                Text("Scan again", color = Color.White)
            }
        }
    }
}

@Composable
private fun BluetoothDestinationTab(
    routes: List<MediaRouter.RouteInfo>,
    onPickRoute: (MediaRouter.RouteInfo) -> Unit,
) {
    Text(
        "Bluetooth audio",
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        "Connected Bluetooth speakers, headphones, and car audio appear here. Pair new devices in Android's Bluetooth settings first.",
        color = Color.White.copy(alpha = 0.48f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )
    if (routes.isEmpty()) {
        Text(
            "No Bluetooth audio devices are currently connected.",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 14.dp),
        )
    } else {
        routes.forEach { route ->
            DestinationRouteRow(
                name = route.name.toString(),
                subtitle = route.description?.toString() ?: "Bluetooth audio",
                icon = Icons.Default.Speaker,
                onClick = { onPickRoute(route) },
            )
        }
    }
}

@Composable
private fun DestinationRouteRow(
    name: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.07f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun refreshDeviceRoutes(
    router: MediaRouter,
    selector: MediaRouteSelector,
    out: SnapshotStateList<MediaRouter.RouteInfo>,
) {
    val visible = router.routes.filter { route ->
        !route.isDefault && (route.isBluetooth || route.matchesSelector(selector))
    }
    if (visible.map { it.id } != out.map { it.id }) {
        out.clear()
        out.addAll(visible)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun GroupRow(group: SonosGroup, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF4FC3F7).copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SpeakerGroup,
                contentDescription = null,
                tint = Color(0xFF4FC3F7),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    group.memberNames.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: SonosDevice, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.07f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Speaker,
                contentDescription = null,
                tint = Color(0xFF4FC3F7),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    device.host,
                    color = Color.White.copy(alpha = 0.35f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                )
            }
        }
    }
}

@Composable
private fun SheetStatus(text: String, showSpinner: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF4FC3F7),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
    }
}
