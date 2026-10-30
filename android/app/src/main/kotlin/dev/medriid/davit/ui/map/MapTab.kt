package dev.medriid.davit.ui.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalView
import dev.medriid.davit.map.Progress
import dev.medriid.davit.map.Guide
import android.Manifest
import android.annotation.SuppressLint
import androidx.core.location.LocationManagerCompat
import android.provider.Settings
import android.os.Build
import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.planFor
import dev.medriid.davit.data.weekOf
import dev.medriid.davit.design.CardShape
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LoadingPlate
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.loop
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.design.rememberMotionEnabled
import dev.medriid.davit.map.Graph
import dev.medriid.davit.map.Mode
import dev.medriid.davit.map.Places
import dev.medriid.davit.map.PrpBlock
import dev.medriid.davit.map.Route
import dev.medriid.davit.search.Fuzzy
import dev.medriid.davit.ui.ErrorNote
import dev.medriid.davit.ui.Gutter
import dev.medriid.davit.ui.LocalRepo
import dev.medriid.davit.ui.highlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.sin
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing

/** The Navigation tab's place in the bottom bar. */
internal const val MAP_TAB = 4

/** Shows a venue ("SJT-401", "PRP 327", a cabin) on the map tab. */
internal fun dev.medriid.davit.ui.Nav.showOnMap(repo: dev.medriid.davit.data.Repo, venue: String) {
    repo.mapFocus.value = venue
    tab(MAP_TAB)
}

/** Soaks up leftover scrolls, so the search list never pulls the tabs' sync drum. */
private val KeepScrolls = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}

/** The camera as five floats, so the map opens where you left it. */
private val CameraSaver = androidx.compose.runtime.saveable.Saver<Camera, FloatArray>(
    save = { floatArrayOf(it.x, it.y, it.scale, it.rot, it.tilt) },
    restore = { Camera(it[0], it[1], it[2], it[3], it[4]) },
)

/** The projected campus, kept while the app runs: building it (archways and all) isn't free, and tabs come and go. */
private var sceneCache: Scene? = null

private fun sceneFor(map: dev.medriid.davit.map.CampusMap): Scene = sceneCache?.takeIf { it.map === map } ?: Scene(map).also { sceneCache = it }

/** What's picked on the map. */
private sealed interface Pick {
    val title: String
    val x: Float
    val y: Float

    class Place(val p: ScenePoi, override val title: String = p.poi.name) : Pick {
        override val x get() = p.x
        override val y get() = p.y
    }

    class Block(val b: SceneBuilding) : Pick {
        override val title get() = b.name.ifBlank { "Building" }
        override val x get() = b.shape.cx
        override val y get() = b.shape.cy
    }

    class Prp(val block: PrpBlock, val shape: Shape, val room: String? = null) : Pick {
        override val title get() = block.name
        override val x get() = shape.cx
        override val y get() = shape.cy
    }

    /** A timetable venue or a room typed into search. */
    class Venue(val spot: Places.Spot, override val x: Float, override val y: Float) : Pick {
        override val title get() = spot.title
    }
}

/** Search groups on the chip row: the categories each one shows pins for. */
private val ChipCats = listOf(
    "Classes" to setOf("academic", "lecture", "prp", "admin"),
    "Food" to setOf("mess", "canteen", "shop", "vending"),
    "Hostels" to setOf("hostel"),
    "Gates" to setOf("gate", "transport"),
    "ATMs" to setOf("atm"),
    "Print" to setOf("print"),
    "Laundry" to setOf("laundry"),
    "Health" to setOf("health", "toilet"),
    "Sports" to setOf("sports", "green"),
    "Bikes" to setOf("cycle", "parking"),
)

/**
 * The Navigation tab: the campus in 2.5D, printed in the app's two inks. Search a place or
 * a room ("PRP 327", "SJT 401"), tap a building or pin, and get a walking or cycling route
 * from where you are. Your next class is a tap away.
 */
@Composable
internal fun MapTab(snapshot: Snapshot) {
    val repo = LocalRepo.current
    val st by repo.maps.state.collectAsState()
    LaunchedEffect(Unit) { repo.maps.ensure() }
    val map = st.map
    if (map == null) {
        Column(Modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.Center) {
            Heading("Campus map", 28.sp)
            Spacer(Modifier.height(14.dp))
            if (st.error != null) {
                ErrorNote(st.error!!) { repo.maps.refresh() }
            } else {
                InkText("Fetching the campus map once (about 600 KB). After that it works offline.", style = RisoType.small)
                Spacer(Modifier.height(14.dp))
                LoadingPlate(lines = 4)
            }
        }
        return
    }
    val scene = remember(map) { sceneFor(map) }
    MapScreen(scene, snapshot)
}

@SuppressLint("MissingPermission")
@Composable
private fun MapScreen(scene: Scene, snapshot: Snapshot) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val inks = LocalInks.current
    val density = LocalDensity.current.density
    val motion = rememberMotionEnabled()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val painter = remember(scene, inks, density) { MapPainter(context, scene, inks, density) }

    var pick by remember { mutableStateOf<Pick?>(null) }
    var route by remember { mutableStateOf<Route?>(null) }
    var mode by rememberSaveable { mutableStateOf(Mode.Walk) }
    var routeFrom by remember { mutableStateOf("") }
    /** The route starts at the main gate only until a fix comes in. */
    var routeWaiting by remember { mutableStateOf(false) }
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var flat by rememberSaveable { mutableStateOf(false) }
    var me by remember { mutableStateOf<Location?>(null) }
    // Following a route: the camera rides on you, turned the way you're going, until you move the map yourself.
    var navigating by rememberSaveable { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var arrived by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        onDispose { view.keepScreenOn = false }
    }
    val pinCats = chip?.let { c -> ChipCats.firstOrNull { it.first == c }?.second }

    // Location: only while this tab is showing, and only once you've allowed it.
    var located by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var wantRouteAfterPermission by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        located = r.values.any { it }
    }
    val lm = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    // The phone's own Location switch, which permission alone doesn't turn on. Watched live,
    // so coming back from Settings with it on starts the fix straight away.
    var locationOn by remember { mutableStateOf(LocationManagerCompat.isLocationEnabled(lm)) }
    DisposableEffect(Unit) {
        val watch = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                locationOn = LocationManagerCompat.isLocationEnabled(lm)
            }
        }
        ContextCompat.registerReceiver(context, watch, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(watch) } }
    }
    val turnOnLocation = {
        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }
    DisposableEffect(located, locationOn, navigating) {
        // Keeps the better of two fixes: a fresh one, or a much more accurate older one. While
        // following a route, every good fix, so the map keeps up with you.
        val listener = LocationListener { l ->
            val old = me
            if (old == null || (navigating && l.accuracy <= 50f && l.time >= old.time) || l.time - old.time > 20_000 || l.accuracy <= old.accuracy * 1.5f) me = l
        }
        if (located && locationOn) {
            // Fused first where Android has it (fast indoors), then GPS and the network.
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }.filter { it in lm.allProviders }
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
                .minByOrNull { it.accuracy }
                ?.let { if (me == null) me = it }
            providers.forEach { p ->
                runCatching { lm.requestLocationUpdates(p, if (navigating) 1000L else 2000L, if (navigating) 0f else 2f, listener, Looper.getMainLooper()) }
            }
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    val meXY = me?.let { floatArrayOf(scene.x(it.longitude), scene.y(it.latitude), it.accuracy) }
    val onCampus = meXY != null && meXY[0] in scene.extent[0] - 400f..scene.extent[2] + 400f && meXY[1] in scene.extent[1] - 400f..scene.extent[3] + 400f

    BoxWithConstraints(Modifier.fillMaxSize().nestedScroll(KeepScrolls)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val fit = remember(scene, w, h) {
            val s = min(w / scene.width, h / (scene.height * 0.62f)) * 0.92f
            Camera(scene.midX, scene.midY, s, 0f, 1f)
        }
        val minScale = fit.scale * 0.55f
        val maxScale = 3.4f * density
        // Opens on the teaching blocks, about 650 m across: buildings big enough to read their names.
        val start = remember(scene, w, h) {
            val core = scene.pois.filter { it.poi.cat in setOf("academic", "lecture", "admin") }
            if (core.isEmpty()) fit else Camera(core.map { it.x }.average().toFloat(), core.map { it.y }.average().toFloat(), max(fit.scale, w / 650f), 0f, 1f)
        }
        // Where you left it: kept across tabs and screens (and when Android restores the app).
        var camera by rememberSaveable(stateSaver = CameraSaver) { mutableStateOf(start) }
        // Moved the map yourself, or picked something: the map won't fly off on its own after that.
        var touched by rememberSaveable { mutableStateOf(false) }
        var cameToYou by rememberSaveable { mutableStateOf(false) }
        val tiltAnim = remember { Animatable(1f) }
        LaunchedEffect(flat) {
            if (motion) tiltAnim.animateTo(if (flat) 0f else 1f, spring(dampingRatio = 0.85f, stiffness = 300f)) else tiltAnim.snapTo(if (flat) 0f else 1f)
        }
        val fly = remember { Animatable(0f) }

        /**
         * Glides the camera to a point (and a zoom and a heading), the way the app's other moves
         * spring. [cinematic] takes its time and, on a long hop, pulls back mid-flight like a crane.
         */
        fun flyTo(x: Float, y: Float, scale: Float? = null, rot: Float? = null, at: Float = 0.5f, cinematic: Boolean = false) {
            val from = camera
            val s = (scale ?: max(from.scale, 0.85f * density)).coerceIn(minScale, maxScale)
            val heading = rot ?: from.rot
            // Centre so the point lands [at] of the way down the map, clear of the card at the bottom.
            val aimed = Camera(x, y, s, heading, tiltAnim.value)
            val (gx, gy) = painter.ground(w / 2, h * at, aimed, w, h)
            val to = Camera(scene.clampX(2 * x - gx), scene.clampY(2 * y - gy), s, heading, from.tilt)
            // The short way round.
            var turn = (to.rot - from.rot) % (2 * PI.toFloat())
            if (turn > PI.toFloat()) turn -= 2 * PI.toFloat()
            if (turn < -PI.toFloat()) turn += 2 * PI.toFloat()
            val far = hypot(to.x - from.x, to.y - from.y) * max(from.scale, to.scale) > w * 0.6f
            scope.launch {
                if (!motion) {
                    camera = to
                    return@launch
                }
                fly.snapTo(0f)
                val spec: androidx.compose.animation.core.AnimationSpec<Float> =
                    if (cinematic) tween(1800, easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)) else spring(dampingRatio = 0.9f, stiffness = 140f)
                fly.animateTo(1f, spec) {
                    val t = value
                    val pull = if (cinematic && far) 1f - 0.4f * sin(PI.toFloat() * t.coerceIn(0f, 1f)) else 1f
                    camera = Camera(
                        from.x + (to.x - from.x) * t,
                        from.y + (to.y - from.y) * t,
                        (from.scale + (to.scale - from.scale) * t) * pull,
                        from.rot + turn * t,
                        camera.tilt,
                    )
                }
            }
        }

        fun select(p: Pick?, zoom: Boolean = true) {
            pick = p
            route = null
            if (p != null) {
                touched = true
                haptics.tick()
                if (zoom) flyTo(p.x, p.y, at = 0.32f)
            }
        }

        // The bulk of the campus, by built-up area: what the camera turns to face.
        val heart = remember(scene) {
            var ax = 0.0; var ay = 0.0; var a = 0.0
            for (b in scene.buildings) { ax += b.shape.cx * b.footprint; ay += b.shape.cy * b.footprint; a += b.footprint }
            if (a > 0) (ax / a).toFloat() to (ay / a).toFloat() else scene.midX to scene.midY
        }
        // First good fix on campus, once per visit: glide to the building you're in, turned so
        // the rest of the campus lies ahead of you, up the screen.
        val goodFix = meXY != null && onCampus && (me?.accuracy ?: 999f) <= 80f
        LaunchedEffect(goodFix) {
            val m = meXY
            if (!goodFix || m == null || cameToYou || touched || pick != null) return@LaunchedEffect
            cameToYou = true
            val b = scene.buildingAt(m[0], m[1])
            val tx = b?.shape?.cx ?: m[0]
            val ty = b?.shape?.cy ?: m[1]
            val dx = heart.first - tx
            val dy = heart.second - ty
            val face = if (hypot(dx, dy) > 80f) atan2(dx, dy) else camera.rot
            flyTo(tx, ty, scale = 1.5f * density, rot = face, at = 0.62f, cinematic = true)
        }

        fun routeTo(p: Pick, fit: Boolean = true) {
            val g = scene.map.graph ?: return
            val m = me
            val start: Pair<Double, Double>
            when {
                m != null && m.accuracy <= 300f && onCampus -> {
                    start = m.latitude to m.longitude
                    routeFrom = "from where you are" + if (m.accuracy > 60f) " (±${m.accuracy.toInt()} m)" else ""
                    // A rough first fix (the network's) is redone once GPS narrows it down.
                    routeWaiting = m.accuracy > 60f
                }
                m != null && m.accuracy <= 300f -> {
                    // Off campus: in through the gate closest to you, not always the main one.
                    val gate = scene.pois.filter { it.poi.cat == "gate" && !it.poi.unnamed }
                        .minByOrNull { Graph.metresBetween(m.latitude, m.longitude, it.poi.lat, it.poi.lon) }?.poi
                    start = gate?.let { it.lat to it.lon } ?: (scene.map.meta.centreLat to scene.map.meta.centreLon)
                    routeFrom = "from ${gate?.name ?: "the campus"}, the gate nearest you"
                    routeWaiting = false
                }
                else -> {
                    val gate = Places.findPoi(scene.map, "Gate 1")
                    start = gate?.let { it.lat to it.lon } ?: (scene.map.meta.centreLat to scene.map.meta.centreLon)
                    routeFrom = when {
                        !located -> "from Gate 1 · allow location to start from you"
                        !locationOn -> "from Gate 1 · your phone's location is off"
                        m != null -> "from Gate 1 for now · your location is only rough (±${m.accuracy.toInt()} m)"
                        else -> "from Gate 1 for now · finding you…"
                    }
                    routeWaiting = true
                }
            }
            scope.launch {
                val r = withContext(Dispatchers.Default) { g.route(start.first, start.second, scene.lat(p.y), scene.lon(p.x), mode) }
                route = r
                if (r != null && !fit) return@launch
                if (r != null) {
                    haptics.confirm()
                    // Fit the whole route.
                    var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                    for (i in r.lat.indices) {
                        val x = scene.x(r.lon[i]); val y = scene.y(r.lat[i])
                        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                    }
                    val s = min(w / max(60f, x1 - x0), h * 0.42f / max(60f, (y1 - y0) * 0.62f)) * 0.85f
                    flyTo((x0 + x1) / 2, (y0 + y1) / 2, s, 0f, at = 0.36f)
                } else {
                    haptics.reject()
                }
            }
        }

        // Asked for from elsewhere (Today's class, a faculty cabin): a venue to show.
        val focus by repo.mapFocus.collectAsState()
        LaunchedEffect(focus) {
            val q = focus ?: return@LaunchedEffect
            repo.mapFocus.value = null
            val spot = Places.resolve(scene.map, q)
            if (spot != null) select(venuePick(scene, spot)) else searching = true
        }
        // A route that had to start at the gate starts from you once a fix arrives (or location
        // comes on); the label also follows the switch while you wait.
        LaunchedEffect(me?.let { if (it.accuracy <= 60f) 0 else if (it.accuracy <= 300f) 1 else 2 }, onCampus, locationOn, located) {
            val p = pick ?: return@LaunchedEffect
            if (route != null && routeWaiting) routeTo(p)
        }

        val routePts = remember(route) {
            route?.let { r -> FloatArray(r.lat.size * 2) { i -> if (i % 2 == 0) scene.x(r.lon[i / 2]) else scene.y(r.lat[i / 2]) } }
        }

        // Following the route: where you are on it, the next turn, and what's left.
        val progress = remember(routePts, meXY?.get(0), meXY?.get(1), navigating) {
            val m = meXY
            if (navigating && routePts != null && m != null) Guide.progress(routePts, m[0], m[1]) else null
        }
        // The camera rides on you, close and tilted, turned the way you're heading: your own
        // bearing when you're moving, else the way the route goes from where you are.
        LaunchedEffect(navigating, follow, meXY?.get(0), meXY?.get(1)) {
            val m = meXY ?: return@LaunchedEffect
            if (!navigating || !follow) return@LaunchedEffect
            val fix = me
            val heading = if (fix != null && fix.hasBearing() && fix.hasSpeed() && fix.speed > 0.8f) {
                fix.bearing * PI.toFloat() / 180f
            } else {
                progress?.heading ?: camera.rot
            }
            flyTo(m[0], m[1], scale = max(camera.scale, 2.6f * density), rot = heading, at = 0.72f)
        }
        // Off the route by more than a path's width: a new one from where you are, without zooming out.
        LaunchedEffect((progress?.off ?: 0f) > 35f) {
            val p = pick
            if (navigating && p != null && (progress?.off ?: 0f) > 35f) {
                haptics.tick()
                routeTo(p, fit = false)
            }
        }
        // There.
        LaunchedEffect((progress?.remaining ?: Float.MAX_VALUE) < 12f) {
            if (navigating && (progress?.remaining ?: Float.MAX_VALUE) < 12f) {
                haptics.confirm()
                navigating = false
                arrived = true
            }
        }
        fun startFollowing() {
            navigating = true
            follow = true
            arrived = false
            flat = false
            touched = true
            haptics.heavy()
            val m = meXY
            if (m == null) {
                if (!located) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else if (!locationOn) turnOnLocation()
            }
        }
        val phase = loop(motion && route != null, 1400, "route")
        val pulse = loop(motion && meXY != null, 1800, "me")

        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(scene, w, h) {
                    detectTransformGestures { centroid, pan, zoom, rotation ->
                        touched = true
                        // Moving the map yourself stops it following you; Re-centre brings it back.
                        if (navigating) follow = false
                        val c = camera
                        val (gx, gy) = painter.ground(centroid.x, centroid.y, c, w, h)
                        var n = c.copy(scale = (c.scale * zoom).coerceIn(minScale, maxScale), rot = c.rot - rotation * PI.toFloat() / 180f)
                        val (nx, ny) = painter.ground(centroid.x + pan.x, centroid.y + pan.y, n, w, h)
                        n = n.copy(x = scene.clampX(n.x + gx - nx), y = scene.clampY(n.y + gy - ny))
                        camera = n
                    }
                }
                .pointerInput(scene, w, h, pinCats) {
                    detectTapGestures(
                        onTap = { pos ->
                            val hit = painter.hit(pos.x, pos.y, camera.copy(tilt = tiltAnim.value), w, h)
                            select(
                                when (hit) {
                                    is ScenePoi -> Pick.Place(hit)
                                    is SceneBuilding -> buildingPick(scene, hit)
                                    is Triple<*, *, *> -> Pick.Prp(hit.first as PrpBlock, hit.second as Shape)
                                    else -> null
                                },
                                zoom = false,
                            )
                        },
                        onDoubleTap = { pos ->
                            touched = true
                            val (gx, gy) = painter.ground(pos.x, pos.y, camera, w, h)
                            flyTo(gx, gy, camera.scale * 1.8f)
                        },
                    )
                },
        ) {
            val o = Overlay(
                selectedBuilding = (pick as? Pick.Block)?.b?.index ?: (pick as? Pick.Place)?.let { p -> scene.buildingAt(p.x, p.y)?.index },
                selectedPrp = (pick as? Pick.Prp)?.block?.id ?: (pick as? Pick.Venue)?.spot?.prp?.id,
                selectedPoi = (pick as? Pick.Place)?.p?.poi?.id ?: (pick as? Pick.Venue)?.spot?.poi?.id,
                route = progress?.let { p -> routePts?.let { Guide.ahead(it, p) } } ?: routePts,
                routePhase = phase.value,
                me = meXY,
                mePulse = pulse.value,
                pinCats = pinCats,
            )
            drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, camera.copy(tilt = tiltAnim.value), o) }
        }

        // Following a route: the next turn up top, what's left and End at the bottom, Re-centre after you move the map.
        if (navigating) {
            NavBanner(progress, meXY != null, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp))
            if (!follow) {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                    RoundButton("Re-centre", { follow = true; haptics.tick() }) {
                        Canvas(Modifier.size(20.dp)) {
                            drawCircle(inks.a, size.minDimension * 0.22f)
                            drawCircle(inks.text, size.minDimension * 0.42f, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
            NavCard(
                progress, route, mode, pick?.title.orEmpty(),
                onEnd = { navigating = false; follow = true; haptics.tick() },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            )
            BackHandler { navigating = false }
            return@BoxWithConstraints
        }

        // Top: search and the place-type chips.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(CardShape)
                        .background(inks.paper)
                        .border(2.dp, inks.text, CardShape)
                        .clickable(role = Role.Button) { searching = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InkText("Search places, rooms (PRP 327)…", style = RisoType.small.copy(color = inks.rule), maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipCats.forEach { (name, _) ->
                    Box(Modifier.clickable(role = Role.Checkbox) { chip = if (chip == name) null else name; haptics.tick() }) {
                        Box(Modifier.clip(CardShape).background(inks.paper)) { InkChip(name, solid = chip == name) }
                    }
                }
            }
        }

        // Right: compass, 2.5D/flat, and you.
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton("North up", { flyTo(camera.x, camera.y, camera.scale, 0f) }) {
                val rot = camera.rot
                Canvas(Modifier.size(22.dp)) {
                    rotate(-rot * 180f / PI.toFloat()) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(c.x, 0f); lineTo(c.x + size.width * 0.28f, c.y); lineTo(c.x - size.width * 0.28f, c.y); close()
                        }
                        drawPath(p, inks.a)
                        val q = androidx.compose.ui.graphics.Path().apply {
                            moveTo(c.x, size.height); lineTo(c.x + size.width * 0.28f, c.y); lineTo(c.x - size.width * 0.28f, c.y); close()
                        }
                        drawPath(q, inks.text, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
            RoundButton(if (flat) "Show in 2.5D" else "Show flat", { flat = !flat; haptics.tick() }) {
                BasicText(if (flat) "2D" else "3D", style = RisoType.label.copy(color = inks.text))
            }
            RoundButton("Find me", {
                if (!located) {
                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else if (!locationOn) {
                    turnOnLocation()
                } else {
                    val m = me
                    if (m != null) flyTo(scene.x(m.longitude), scene.y(m.latitude), max(camera.scale, 1.2f * density), at = 0.55f, cinematic = true)
                }
                haptics.tick()
            }) {
                Canvas(Modifier.size(20.dp)) {
                    drawCircle(if (meXY != null) inks.a else inks.text, size.minDimension * 0.22f)
                    drawCircle(inks.text, size.minDimension * 0.42f, style = Stroke(2.dp.toPx()))
                }
            }
        }

        // Bottom: the picked place, a route, or your next class.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            AnimatedVisibility(pick == null && route == null, enter = fadeIn(), exit = fadeOut()) {
                NextClass(scene, snapshot) { p -> select(p) }
            }
            AnimatedVisibility(pick != null, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
                pick?.let { p ->
                    PickCard(scene, p, route, mode, routeFrom,
                        onMode = { m -> mode = m; routeTo(p) },
                        onRoute = {
                            if (!located && !wantRouteAfterPermission) {
                                wantRouteAfterPermission = true
                                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                            routeTo(p)
                        },
                        onClose = { pick = null; route = null; arrived = false },
                        onPick = { q -> select(q) },
                        onStart = ::startFollowing,
                        arrived = arrived,
                        // The one thing that would start the route from you, as a button on the card.
                        onLocate = when {
                            !routeWaiting && route != null -> null
                            !located -> ({ askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } to "Allow location")
                            !locationOn -> (turnOnLocation to "Turn on location")
                            else -> null
                        },
                    )
                }
            }
        }

        if (searching) {
            SearchPanel(scene, me, onClose = { searching = false }) { p ->
                searching = false
                select(p)
            }
        }
        BackHandler(enabled = pick != null || searching) {
            if (searching) searching = false else { pick = null; route = null }
        }
    }
}

private fun venuePick(scene: Scene, spot: Places.Spot): Pick = Pick.Venue(spot, scene.x(spot.lon), scene.y(spot.lat))

@Composable
private fun RoundButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val inks = LocalInks.current
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(inks.paper)
            .border(2.dp, inks.text, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** "Next: Calculus · SJT 401, 4th floor · 6 min walk" with a tap to show it. */
@Composable
private fun NextClass(scene: Scene, snapshot: Snapshot, onShow: (Pick) -> Unit) {
    val now = remember { LocalDateTime.now() }
    val plan = remember(snapshot) { planFor(weekOf(snapshot), now, snapshot.calendar) }
    val slot = plan.now ?: plan.next ?: return
    val venue = slot.course.venue.ifBlank { return }
    val spot = remember(venue) { Places.resolve(scene.map, venue) }
    val inks = LocalInks.current
    val day = when {
        plan.now != null -> "Now"
        plan.nextInDays == 0 -> "Next · " + dev.medriid.davit.data.clock(slot.meeting.start)
        plan.nextInDays == 1 -> "Tomorrow · " + dev.medriid.davit.data.clock(slot.meeting.start)
        else -> (plan.nextDate?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) ?: "") + " · " + dev.medriid.davit.data.clock(slot.meeting.start)
    }
    InkCard(Modifier.fillMaxWidth(), onClick = { spot?.let { onShow(venuePick(scene, it)) } }, plate = inks.b, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(day, label = true)
                InkText(slot.course.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                InkText(
                    if (spot != null) listOfNotNull(venue, spot.floor).joinToString(" · ") else "$venue isn't on the map yet",
                    style = RisoType.small, maxLines = 1,
                )
            }
            if (spot != null) {
                Spacer(Modifier.width(8.dp))
                InkChip("Show", solid = true)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickCard(
    scene: Scene,
    p: Pick,
    route: Route?,
    mode: Mode,
    from: String,
    onMode: (Mode) -> Unit,
    onRoute: () -> Unit,
    onClose: () -> Unit,
    onPick: (Pick) -> Unit = {},
    onLocate: Pair<() -> Unit, String>? = null,
    onStart: () -> Unit = {},
    arrived: Boolean = false,
) {
    val inks = LocalInks.current
    val map = scene.map
    val poi = when (p) {
        is Pick.Place -> p.p.poi
        is Pick.Venue -> p.spot.poi
        else -> null
    }
    val prp = (p as? Pick.Prp)?.block ?: (p as? Pick.Venue)?.spot?.prp
    val room = (p as? Pick.Venue)?.spot?.room ?: (p as? Pick.Prp)?.room
    InkCard(Modifier.fillMaxWidth().heightIn(max = 360.dp), plateOffset = 5.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                InkText(p.title, style = RisoType.title, maxLines = 2)
                val sub = listOfNotNull(
                    poi?.cat?.let(map::category) ?: (p as? Pick.Block)?.b?.cat?.ifBlank { null }?.let(map::category) ?: prp?.let { "PRP classrooms" } ?: (p as? Pick.Block)?.let { "Building" },
                    (p as? Pick.Venue)?.spot?.floor,
                    poi?.near?.ifBlank { null }?.let { "in $it" } ?: poi?.anchor?.ifBlank { null }?.let { "in $it" },
                    (p as? Pick.Block)?.b?.levels?.takeIf { it > 0 }?.let { if (it == 1) "1 floor" else "$it floors" },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) InkText(sub, style = RisoType.small, maxLines = 2)
            }
            Box(Modifier.clickable(role = Role.Button, onClick = onClose).padding(start = 8.dp, bottom = 8.dp)) { InkChip("Close") }
        }
        // The details scroll; the route and its button stay put under them. With a route up, only the route shows.
        if (route == null) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // A PRP block's description only lists its room ranges, which the floors below show better.
            poi?.desc?.ifBlank { null }?.takeIf { prp == null }?.let {
                Spacer(Modifier.height(6.dp))
                InkText(it, style = RisoType.small, maxLines = 4)
            }
            poi?.hours?.ifBlank { null }?.let {
                Spacer(Modifier.height(4.dp))
                InkText("Open $it", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
            }
            // The PRP maze: which floor holds which rooms; the one you're after printed solid.
            prp?.let { b ->
                Spacer(Modifier.height(10.dp))
                InkText("Floors", label = true)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.floors.forEach { f ->
                        val here = room != null && Places.roomInRange(f.rooms, room)
                        val level = if (f.label.startsWith("Ground", ignoreCase = true)) "G" else f.label.takeWhile(Char::isDigit).ifEmpty { f.level }
                        InkChip("$level · ${f.rooms ?: "–"}", solid = here)
                    }
                }
            }
            // What else is in the building: offices, counters, shops. Tap one to pick it.
            val shell = (p as? Pick.Block)?.b ?: if (poi != null && prp == null) scene.buildingAt(p.x, p.y) else null
            val inside = remember(p) {
                shell?.let { b -> scene.pois.filter { !it.poi.unnamed && it.poi.id != poi?.id && b.shape.contains(it.x, it.y) } }.orEmpty()
            }
            if (inside.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                InkText(if (p is Pick.Block) "Inside" else "Also in this building", label = true)
                inside.take(10).forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(Pick.Place(q)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InkText(q.poi.name, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1)
                        if (q.poi.cat != poi?.cat) InkText(map.category(q.poi.cat), style = RisoType.small, maxLines = 1)
                    }
                }
                if (inside.size > 10) InkText("+ ${inside.size - 10} more", style = RisoType.small)
            }
            // Today's menu, for a mess.
            if (poi?.cat == "mess") {
                val today = java.time.LocalDate.now()
                val menus = remember(poi.name) { map.menusFor(poi.name, today.toString(), today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) }
                if (menus.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    InkText("Today's menu" + if (menus.first().date != today.toString()) " (from ${menus.first().date})" else "", label = true)
                    menus.forEach { m ->
                        Spacer(Modifier.height(4.dp))
                        InkText(m.meal, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                        InkText(m.menu, style = RisoType.small)
                    }
                    InkText("Menus can change at the counter.", style = RisoType.small.copy(color = inks.rule, fontSize = 11.sp))
                }
            }
        }
        if (map.graph != null) {
            Spacer(Modifier.height(12.dp))
            if (route != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Heading(minutes(route.seconds), 28.sp)
                            Spacer(Modifier.width(10.dp))
                            InkText(distance(route.metres), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                        }
                        val notes = listOfNotNull("has steps".takeIf { route.steps }, "through a corridor".takeIf { route.indoor }, "unpaved bit".takeIf { route.unpaved }, from)
                        InkText(notes.joinToString(" · "), style = RisoType.small, maxLines = 2)
                        onLocate?.let { (go, label) ->
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.clickable(role = Role.Button, onClick = go)) { InkChip(label, solid = true) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clickable(role = Role.RadioButton) { onMode(Mode.Walk) }.padding(2.dp)) { InkChip("Walk", solid = mode == Mode.Walk) }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clickable(role = Role.RadioButton) { onMode(Mode.Cycle) }.padding(2.dp)) { InkChip("Cycle", solid = mode == Mode.Cycle) }
                }
                Spacer(Modifier.height(10.dp))
                if (arrived) InkText("You've arrived.", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                else InkButton("Start", onStart, modifier = Modifier.fillMaxWidth(), fillWidth = true)
            } else {
                InkButton("Route here", onRoute, modifier = Modifier.fillMaxWidth(), fillWidth = true)
            }
        }
    }
}

/** While following a route: the next turn, big, with an arrow that points the way. */
@Composable
private fun NavBanner(p: Progress?, located: Boolean, modifier: Modifier) {
    val inks = LocalInks.current
    InkCard(modifier, plateOffset = 4.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val turn = p?.next
            // The arrow: up for straight on, leaning the way of the turn.
            Canvas(Modifier.size(40.dp)) {
                rotate(turn?.degrees?.coerceIn(-135f, 135f) ?: 0f) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val stem = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x, size.height * 0.92f); lineTo(c.x, size.height * 0.22f)
                    }
                    drawPath(stem, inks.text, style = Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val head = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x, size.height * 0.04f); lineTo(c.x + size.width * 0.3f, size.height * 0.36f); lineTo(c.x - size.width * 0.3f, size.height * 0.36f); close()
                    }
                    drawPath(head, inks.a)
                    drawPath(head, inks.text, style = Stroke(1.5.dp.toPx()))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                when {
                    !located -> InkText("Finding you…", style = RisoType.title)
                    p == null -> InkText("Head to the route", style = RisoType.title)
                    turn == null -> {
                        InkText("Straight on", style = RisoType.title)
                        InkText("then you're there", style = RisoType.small)
                    }
                    else -> {
                        InkText("${turn.words} in ${distance(roundTo5(turn.metres))}", style = RisoType.title, maxLines = 2)
                        if (p.off > 15f) InkText("${p.off.toInt()} m off the path", style = RisoType.small)
                    }
                }
            }
        }
    }
}

/** While following a route: what's left and the way out. */
@Composable
private fun NavCard(p: Progress?, route: Route?, mode: Mode, to: String, onEnd: () -> Unit, modifier: Modifier) {
    InkCard(modifier, plateOffset = 5.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val left = p?.remaining ?: route?.metres?.toFloat()
                // Time left at the route's own pace.
                val pace = if (route != null && route.seconds > 0) route.metres.toFloat() / route.seconds else if (mode == Mode.Walk) 1.3f else 4.5f
                Row(verticalAlignment = Alignment.Bottom) {
                    Heading(left?.let { minutes((it / pace).toInt()) } ?: "–", 26.sp)
                    Spacer(Modifier.width(10.dp))
                    InkText(left?.let { distance(roundTo5(it)) } ?: "", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                }
                InkText("to $to", style = RisoType.small, maxLines = 1)
            }
            InkButton("End", onEnd)
        }
    }
}

private fun roundTo5(m: Float): Int = ((m / 5f).toInt() * 5).coerceAtLeast(5)

private fun minutes(seconds: Int): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "${(seconds + 30) / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600 + 30) / 60} min"
}

private fun distance(m: Int): String = if (m < 1000) "$m m" else "%.1f km".format(Locale.ENGLISH, m / 1000.0)

/** A search result: what to show, and where it goes. */
private class Found(val title: String, val sub: String, val pick: Pick, val ranges: List<IntRange> = emptyList(), val metres: Int? = null)

/**
 * Search across places (names, aliases like "MH A", categories, descriptions) and rooms:
 * "327" or "PRP 327" finds the PRP block and floor; "SJT 401" the building and floor.
 */
@Composable
private fun SearchPanel(scene: Scene, me: Location?, onClose: () -> Unit, onPick: (Pick) -> Unit) {
    val inks = LocalInks.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val index = remember(scene) {
        Fuzzy.Index(scene.pois.filter { !it.poi.unnamed }, listOf(Fuzzy.Field("name", 3f), Fuzzy.Field("alias", 2.5f), Fuzzy.Field("kind", 1.5f), Fuzzy.Field("desc", 0.8f))) { p ->
            listOf(p.poi.name, p.poi.aliases.joinToString(" "), listOf(scene.map.category(p.poi.cat), p.poi.kind, p.poi.near).joinToString(" "), p.poi.desc)
        }
    }
    val codes = remember(scene) { Places.codes(scene.map) }
    val results by produceState(emptyList<Found>(), query) {
        val q = query.trim()
        if (q.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val out = ArrayList<Found>()
            // A room or a venue first: "327", "G29", "PRP 327", "SJT 401", "TT G04".
            val room = Regex("""^[Gg]?\d{2,4}$""").matches(q)
            val spot = Places.resolve(scene.map, if (room) "PRP$q" else q, codes)
            if (spot != null && (room || q.any(Char::isDigit))) {
                out += Found(spot.title, listOfNotNull(spot.building, spot.floor).joinToString(" · "), venuePick(scene, spot))
            }
            index.search(q, limit = 40).forEach { h ->
                val p = h.item
                val d = me?.let { Graph.metresBetween(it.latitude, it.longitude, p.poi.lat, p.poi.lon).toInt() }
                out += Found(
                    p.poi.name,
                    listOfNotNull(scene.map.category(p.poi.cat), p.poi.near.ifBlank { null }?.let { "in $it" }).joinToString(" · "),
                    Pick.Place(p),
                    h.ranges["name"].orEmpty(),
                    d,
                )
            }
            // Named buildings that no place stands for.
            val have = out.map { it.title.lowercase() }.toSet()
            scene.buildings.filter { it.name.isNotBlank() && it.name.lowercase() !in have && it.name.contains(q, ignoreCase = true) }
                .take(6).forEach { b -> out += Found(b.name, b.cat.ifBlank { null }?.let(scene.map::category) ?: "Building", Pick.Block(b)) }
            // A name you typed exactly beats a near miss ("Foodys" before "Woodys"); a room stays on top.
            fun norm(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
            val nq = norm(q)
            out.sortedBy { r ->
                val t = norm(r.title)
                when {
                    r.pick is Pick.Venue -> 0
                    t == nq -> 1
                    t.startsWith(nq) -> 2
                    nq in t -> 3
                    else -> 4
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(inks.paper).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).defaultMinSize(minHeight = 50.dp).clip(CardShape).border(3.dp, inks.a, CardShape).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    query, { query = it },
                    singleLine = true,
                    textStyle = RisoType.body.copy(fontSize = 18.sp, color = inks.text),
                    cursorBrush = SolidColor(inks.a),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); results.firstOrNull()?.let { onPick(it.pick) } }),
                    modifier = Modifier.weight(1f).padding(vertical = 13.dp).focusRequester(focus),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clickable(role = Role.Button, onClick = onClose).padding(8.dp)) { InkChip("Close") }
        }
        Spacer(Modifier.height(8.dp))
        if (query.isBlank()) {
            InkText("Try a block (SJT, TT, MH A), a room (PRP 327, G29, SJT 401), a mess, an ATM, a gate…", style = RisoType.small)
        } else if (results.isEmpty()) {
            InkText("Nothing by that name. Try fewer letters.", style = RisoType.small)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(results) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { keyboard?.hide(); onPick(r.pick) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(highlight(r.title, r.ranges, inks.a), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold, color = inks.text), maxLines = 2)
                        if (r.sub.isNotBlank()) InkText(r.sub, style = RisoType.small, maxLines = 1)
                    }
                    r.metres?.let { InkText(distance(it), style = RisoType.small) }
                }
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(inks.rule))
            }
        }
        // The map's licence asks for this line; it lives here, out of the map's way.
        BasicText("Campus map by Jeman · map data © OpenStreetMap contributors", style = RisoType.small.copy(fontSize = 10.sp, color = inks.rule), modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * A tapped building: the place that is the building itself (same name, or the one place
 * inside it) when there is one, so you get its hours and description; else the building.
 */
private fun buildingPick(scene: Scene, b: SceneBuilding): Pick {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    val inside = scene.pois.filter { b.shape.contains(it.x, it.y) && !it.poi.unnamed }
    val same = inside.firstOrNull { b.name.isNotBlank() && norm(it.poi.name) == norm(b.name) }
        ?: inside.firstOrNull { b.name.isNotBlank() && it.poi.aliases.any { a -> norm(a) == norm(b.name) } }
        ?: inside.singleOrNull()?.takeIf { b.name.isBlank() || norm(b.name) in norm(it.poi.name) }
    return same?.let { Pick.Place(it) } ?: Pick.Block(b)
}

/** A room or cabin as a chip with the map glyph: tap to see it on the Navigation tab. */
@Composable
internal fun MapChip(venue: String, modifier: Modifier = Modifier, label: String = venue) {
    val inks = LocalInks.current
    val nav = dev.medriid.davit.ui.LocalNav.current
    val repo = LocalRepo.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Row(
        modifier
            .clip(shape)
            .border(1.5.dp, inks.text, shape)
            .clickable(role = Role.Button, onClickLabel = "Show on the map") { nav.showOnMap(repo, venue) }
            .padding(start = 7.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dev.medriid.davit.design.IconGlyph(dev.medriid.davit.design.RisoIcon.Map, inks.a, Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        BasicText(label, maxLines = 1, softWrap = false, style = RisoType.label.copy(color = inks.text, letterSpacing = RisoType.label.letterSpacing * 0.5f))
    }
}
