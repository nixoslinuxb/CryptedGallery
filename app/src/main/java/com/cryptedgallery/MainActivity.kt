package com.cryptedgallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cryptedgallery.security.SecurityManager
import java.util.Locale
import android.animation.ObjectAnimator
import android.view.animation.AnticipateInterpolator
import androidx.core.animation.doOnEnd
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.cryptedgallery.data.MediaItem
import com.cryptedgallery.ui.*
import com.cryptedgallery.ui.PdfEditScreen
import com.cryptedgallery.ui.theme.MyApplicationTheme
import java.util.ArrayList

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Immediate dismissal to handover to Stage 2 Compose Splash
        splashScreen.setKeepOnScreenCondition { false }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: GalleryViewModel = viewModel()
                MainEntrance(viewModel)
            }
        }
    }
}

@Composable
fun MainEntrance(viewModel: GalleryViewModel) {
    var showBloomSplash by remember { mutableStateOf(true) }

    // CONTENT REVEAL UNBLUR
    val contentBlur by animateDpAsState(
        targetValue = if (showBloomSplash) 30.dp else 0.dp,
        animationSpec = tween(1200, easing = LinearOutSlowInEasing),
        label = "contentBlur"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 0. AGSL MESH BACKGROUND (PERMANENT)
        AnimatedMeshGradient()

        // 1. MAIN CONTENT (LOGIN OR APP)
        Box(modifier = Modifier.fillMaxSize().blur(contentBlur)) {
            MainContent(viewModel)
        }

        // 2. ULTRA-MODERN BLOOM OVERLAY
        AnimatedVisibility(
            visible = showBloomSplash,
            exit = fadeOut(tween(800)) + scaleOut(tween(800), targetScale = 2.5f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                var startAnimation by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    targetValue = if (startAnimation) 1.2f else 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "scale"
                )

                LaunchedEffect(Unit) {
                    startAnimation = true
                    delay(1500)
                    showBloomSplash = false
                }

                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(scale)
                        .background(Brush.radialGradient(listOf(Color.Cyan.copy(alpha = 0.4f), Color.Transparent)))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Photo, null, tint = Color.White, modifier = Modifier.size(64.dp))
                }
            }
        }
    }
}

// Time without any user interaction after which the unlocked app locks itself again
private const val IDLE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L

@Composable
fun MainContent(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val securityManager = remember { SecurityManager(context) }

    var isAppLocked by remember { mutableStateOf(securityManager.isPasswordSet()) }
    var isFirstTime by remember { mutableStateOf(!securityManager.isPasswordSet()) }
    var isLockedOut by remember { mutableStateOf(securityManager.isLockedOut()) }
    var lockoutRemaining by remember { mutableLongStateOf(securityManager.getRemainingLockoutTimeMs()) }

    // AUTO-LOCK LOGIC
    // Sending the app to the background no longer locks it. The unlocked session is
    // kept and the app only locks again after 5 minutes without user interaction.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var lastInteractionAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isInForeground by remember {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    isInForeground = true
                    // Lock on return only if the idle timeout already elapsed
                    if (securityManager.isPasswordSet() &&
                        System.currentTimeMillis() - lastInteractionAt >= IDLE_LOCK_TIMEOUT_MS
                    ) {
                        isAppLocked = true
                    }
                }

                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    isInForeground = false
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Foreground idle watcher: locks the app after the timeout without any interaction
    LaunchedEffect(isInForeground, isAppLocked, isFirstTime) {
        if (!isInForeground || isAppLocked || isFirstTime) return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (System.currentTimeMillis() - lastInteractionAt >= IDLE_LOCK_TIMEOUT_MS) {
                isAppLocked = true
                break
            }
        }
    }

    val loading by viewModel.isLoading.collectAsState()

    // UNLOCK REVEAL ANIMATION
    val loginBlur by animateDpAsState(
        targetValue = if (isAppLocked || isFirstTime) 40.dp else 0.dp,
        animationSpec = tween(1000, easing = LinearOutSlowInEasing),
        label = "loginBlur"
    )
    val loginAlpha by animateFloatAsState(
        targetValue = if (isAppLocked || isFirstTime) 1f else 0f,
        animationSpec = tween(800),
        label = "loginAlpha"
    )

    // Lockout Timer Thread
    LaunchedEffect(isLockedOut) {
        if (isLockedOut) {
            while (lockoutRemaining > 0) {
                delay(1000)
                lockoutRemaining = securityManager.getRemainingLockoutTimeMs()
            }
            isLockedOut = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Any touch counts as interaction and postpones the idle auto-lock
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteractionAt = System.currentTimeMillis()
                    }
                }
            }
    ) {
        // BACKGROUND GALLERY (BLURRED IF LOCKED)
        Box(modifier = Modifier.fillMaxSize().blur(loginBlur)) {
            val permissions = arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )

            val contextToCheck = LocalContext.current
            val hasBasicPermissionsNow = permissions.all {
                ContextCompat.checkSelfPermission(contextToCheck, it) == PackageManager.PERMISSION_GRANTED
            }

            var hasBasicPermissions by remember { mutableStateOf(hasBasicPermissionsNow) }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                hasBasicPermissions = results.values.all { it }
                if (hasBasicPermissions) {
                    viewModel.loadAll()
                }
            }

            LaunchedEffect(hasBasicPermissions) {
                if (hasBasicPermissions) {
                    viewModel.loadAll()
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (hasBasicPermissions) {
                    GalleryApp(viewModel)
                } else {
                    PermissionRequestScreen(
                        onRequestPermissions = { launcher.launch(permissions) }
                    )
                }
            }
        }

        // LOGIN LAYER
        if (loginAlpha > 0.01f) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = loginAlpha }) {
                // If no permissions, dismiss splash early in VM
                if (loading && (isAppLocked || isFirstTime)) {
                    SideEffect { viewModel.setLoading(false) }
                }

                LoginScreen(
                    isLockedOut = isLockedOut,
                    lockoutTimeMs = lockoutRemaining,
                    totalLockoutTimeMs = securityManager.getLastLockoutDurationMs(),
                    attemptsLeft = securityManager.getAttemptsLeftInCycle(),
                    isFirstTime = isFirstTime,
                    onUnlock = { pwd ->
                        if (securityManager.verifyPassword(pwd)) {
                            lastInteractionAt = System.currentTimeMillis()
                            isAppLocked = false
                        } else {
                            if (securityManager.isLockedOut()) {
                                isLockedOut = true
                                lockoutRemaining = securityManager.getRemainingLockoutTimeMs()
                            }
                        }
                    },
                    onSetPassword = { pwd ->
                        securityManager.setPassword(pwd)
                        lastInteractionAt = System.currentTimeMillis()
                        isFirstTime = false
                        isAppLocked = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(viewModel: GalleryViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current

    // CONTENT REVEAL SETTLE
    val settleScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "settleScale"
    )

    val currentTrack by viewModel.currentPlayingAudio.collectAsState()
    val isPlaying by viewModel.isAudioPlaying.collectAsState()

    // Update permission status when returning to app
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.updateFullFileAccessStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.loadAll()
        }
    }

    val showBottomBar = currentDestination?.route in listOf("photos", "videos", "docs", "music")

    Scaffold(
        modifier = Modifier.scale(settleScale),
        bottomBar = {
            if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Persistent Mini Player
                        if (currentTrack != null && currentDestination?.route != "music_player") {
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                MiniPlayer(
                                    item = currentTrack!!,
                                    isPlaying = isPlaying,
                                    onToggle = { viewModel.toggleAudioPlayback() },
                                    onClick = { navController.navigate("music_player") }
                                )
                            }
                        }

                        ModernFloatingNavBar(
                            items = listOf(
                                NavigationItem("Photos", "photos", Icons.Default.Photo),
                                NavigationItem("Videos", "videos", Icons.Default.Movie),
                                NavigationItem("Docs", "docs", Icons.Default.Description),
                                NavigationItem("Music", "music", Icons.Default.MusicNote)
                            ),
                            currentRoute = currentDestination?.route,
                            onItemClick = { route ->
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "photos",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("photos") {
                GalleryScreen(
                    viewModel = viewModel,
                    onPhotoClick = { item -> navController.navigate("detail/${item.id}") },
                    onShareMultiple = { items ->
                        if (items.isNotEmpty()) {
                            val uris = ArrayList(items.map { it.uri })
                            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Photos"))
                        }
                    },
                    onDeleteMultiple = { items ->
                        if (items.isNotEmpty()) {
                            val pendingIntent = viewModel.createDeleteRequest(items.map { it.uri })
                            pendingIntent?.let {
                                val request = IntentSenderRequest.Builder(it.intentSender).build()
                                deleteLauncher.launch(request)
                            }
                        }
                    }
                )
            }
            composable("videos") {
                val scope = rememberCoroutineScope()
                VideoGalleryScreen(
                    viewModel = viewModel,
                    onVideoClick = { item -> navController.navigate("video_player/${item.id}") },
                    onShareMultiple = { items ->
                        if (items.isNotEmpty()) {
                            val uris = ArrayList(items.map { it.uri })
                            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "video/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Videos"))
                        }
                    },
                    onDeleteMultiple = { items ->
                        scope.launch {
                            val remainingUris = mutableListOf<Uri>()
                            for (item in items) {
                                val success = viewModel.deleteMediaDirectly(item)
                                if (!success) {
                                    remainingUris.add(item.uri)
                                }
                            }
                            if (remainingUris.isNotEmpty()) {
                                val pendingIntent = viewModel.createDeleteRequest(remainingUris)
                                pendingIntent?.let {
                                    val request = IntentSenderRequest.Builder(it.intentSender).build()
                                    deleteLauncher.launch(request)
                                }
                            }
                        }
                    }
                )
            }
            composable("docs") {
                DocumentLibraryScreen(
                    viewModel = viewModel,
                    onDocumentClick = { item ->
                        val ext = item.displayName.substringAfterLast(".").lowercase()
                        if (ext == "svg") {
                            navController.navigate("svg_viewer/${item.id}")
                        } else {
                            navController.navigate("document_viewer/${item.id}")
                        }
                    },
                    onRequestFullAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                )
            }
            composable("music") {
                MusicLibraryScreen(
                    viewModel = viewModel,
                    onTrackClick = { item ->
                        viewModel.playAudio(item, autoPlay = false)
                        navController.navigate("music_player")
                    }
                )
            }
            composable("music_player") {
                MusicPlayerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "svg_viewer/{docId}",
                arguments = listOf(navArgument("docId") { type = NavType.LongType })
            ) { backStackEntry ->
                val docId = backStackEntry.arguments?.getLong("docId") ?: -1L
                val documents by viewModel.documents.collectAsState()
                val document = documents.find { it.id == docId }
                if (document != null) {
                    SvgViewerScreen(
                        item = document,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                route = "document_viewer/{docId}",
                arguments = listOf(navArgument("docId") { type = NavType.LongType })
            ) { backStackEntry ->
                val docId = backStackEntry.arguments?.getLong("docId") ?: -1L
                val documents by viewModel.documents.collectAsState()
                val document = documents.find { it.id == docId }

                if (document != null) {
                    DocumentViewerScreen(
                        viewModel = viewModel,
                        item = document,
                        onBack = { navController.popBackStack() },
                        onEditPdf = { item -> navController.navigate("pdf_edit/${item.id}") }
                    )
                }
            }
            composable(
                route = "pdf_edit/{docId}",
                arguments = listOf(navArgument("docId") { type = NavType.LongType })
            ) { backStackEntry ->
                val docId = backStackEntry.arguments?.getLong("docId") ?: -1L
                val documents by viewModel.documents.collectAsState()
                val document = documents.find { it.id == docId }

                if (document != null) {
                    PdfEditScreen(
                        viewModel = viewModel,
                        item = document,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                route = "video_player/{videoId}",
                arguments = listOf(navArgument("videoId") { type = NavType.LongType })
            ) { backStackEntry ->
                val videoId = backStackEntry.arguments?.getLong("videoId") ?: -1L
                val videos by viewModel.videos.collectAsState()
                val scope = rememberCoroutineScope()

                VideoPlayerScreen(
                    videos = videos,
                    initialVideoId = videoId,
                    onBack = { navController.popBackStack() },
                    onDelete = { video ->
                        scope.launch {
                            val success = viewModel.deleteMediaDirectly(video)
                            if (success) {
                                navController.popBackStack()
                            } else {
                                // Fallback for scoped storage restrictions
                                val pendingIntent = viewModel.createDeleteRequest(listOf(video.uri))
                                pendingIntent?.let {
                                    val request = IntentSenderRequest.Builder(it.intentSender).build()
                                    deleteLauncher.launch(request)
                                    navController.popBackStack() // Still close the player since system dialog will handle deletion
                                }
                            }
                        }
                    }
                )
            }
            composable(
                route = "detail/{photoId}",
                arguments = listOf(navArgument("photoId") { type = NavType.LongType })
            ) { backStackEntry ->
                val photoId = backStackEntry.arguments?.getLong("photoId") ?: -1L
                val photos by viewModel.photos.collectAsState()

                PhotoDetailScreen(
                    viewModel = viewModel,
                    photos = photos,
                    initialPhotoId = photoId,
                    onBack = { navController.popBackStack() },
                    onEdit = { photo -> navController.navigate("edit/${photo.id}") },
                    onDelete = { photo ->
                        val pendingIntent = viewModel.createDeleteRequest(listOf(photo.uri))
                        pendingIntent?.let {
                            val request = IntentSenderRequest.Builder(it.intentSender).build()
                            deleteLauncher.launch(request)
                        }
                    },
                    onShare = { photo ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, photo.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Photo"))
                    }
                )
            }
            composable(
                route = "edit/{photoId}",
                arguments = listOf(navArgument("photoId") { type = NavType.LongType })
            ) { backStackEntry ->
                val photoId = backStackEntry.arguments?.getLong("photoId") ?: -1L
                val photos by viewModel.photos.collectAsState()
                val photo = photos.find { it.id == photoId }

                if (photo != null) {
                    PhotoEditScreen(
                        viewModel = viewModel,
                        item = photo,
                        onCancel = { navController.popBackStack() },
                        onSaved = {
                            navController.navigate("photos") {
                                popUpTo("photos") { inclusive = true }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(
    item: MediaItem,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Playing now",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onToggle) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
        }
    }
}

@Composable
fun ModernFloatingNavBar(
    items: List<NavigationItem>,
    currentRoute: String?,
    onItemClick: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .height(64.dp)
            .widthIn(max = 400.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        // Blur effect for Android 12+
        Box(modifier = Modifier.fillMaxSize()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Box(modifier = Modifier.fillMaxSize().blur(20.dp))
            }

            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    val scale by animateFloatAsState(
                        targetValue = if (selected) 1.2f else 1.0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "iconScale"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onItemClick(item.route) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(24.dp).scale(scale)
                            )
                            AnimatedVisibility(
                                visible = selected,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class NavigationItem(val label: String, val route: String, val icon: ImageVector)

@Composable
fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Access to media is required",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}
