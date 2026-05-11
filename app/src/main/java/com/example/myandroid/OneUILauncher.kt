package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppItem(val name: String, val pkg: String, val icon: ImageBitmap?, val isSystem: Boolean)

data class MenuState(val app: AppItem, val source: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OneUILauncher() {
    val context = LocalContext.current
    var isDrawerOpen by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    
    var activeMenu by remember { mutableStateOf<MenuState?>(null) }
    val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    var homeAppPkgs by remember { mutableStateOf(prefs.getStringSet("home_apps", emptySet()) ?: emptySet()) }
    val defaultDock = setOf("com.android.dialer", "com.samsung.android.dialer", "com.android.chrome", "com.whatsapp", "com.android.messaging")
    var dockAppPkgs by remember { mutableStateOf(prefs.getStringSet("dock_apps", defaultDock) ?: defaultDock) }
    
    val allApps by produceState<List<AppItem>>(initialValue = AppCache.cachedApps) {
        value = withContext(Dispatchers.IO) { AppCache.getApps(context) }
    }

    val wallpaperBitmap by produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "launcher_wallpaper.jpg")
            if (file.exists()) {
                try {
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    
                    var scale = 1
                    while (options.outWidth / scale > 1200 || options.outHeight / scale > 2500) {
                        scale *= 2
                    }
                    
                    val finalOptions = android.graphics.BitmapFactory.Options()
                    finalOptions.inSampleSize = scale
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, finalOptions)?.asImageBitmap()
                } catch (e: Exception) { null }
            } else null
        }
    }

    // Physics Animations
    val drawerProgress by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "DrawerAnimation"
    )
    
    val editScale by animateFloatAsState(
        targetValue = if (isEditing) 0.75f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "EditScale"
    )
    
    val editCorner by animateDpAsState(
        targetValue = if (isEditing) 32.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "EditCorner"
    )

    val dockAlpha by animateFloatAsState(targetValue = if (isEditing) 0f else 1f, label = "DockAlpha")

    val pageCount = if (isEditing) 2 else 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = isDrawerOpen || isEditing) {
        if (isDrawerOpen) isDrawerOpen = false
        else if (isEditing) {
            scope.launch {
                if (pagerState.currentPage > 0) pagerState.animateScrollToPage(0)
                isEditing = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isEditing) {
                if (!isEditing) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            if (!isDrawerOpen && totalDrag < -40) isDrawerOpen = true
                            else if (isDrawerOpen && (totalDrag > 40 || totalDrag < -40)) isDrawerOpen = false
                        }
                    )
                }
            }
    ) {
        // 1. Darkened Base Background (Visible during Edit Mode)
        if (wallpaperBitmap != null) {
            Image(
                bitmap = wallpaperBitmap!!,
                contentDescription = "Background Base",
                modifier = Modifier.fillMaxSize().alpha(0.3f),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // 2. Home Workspace (Pages & Dock)
        HomeWorkspace(
            apps = allApps,
            homeAppPkgs = homeAppPkgs,
            dockAppPkgs = dockAppPkgs,
            wallpaperBitmap = wallpaperBitmap,
            isEditing = isEditing,
            pagerState = pagerState,
            editScale = editScale,
            editCorner = editCorner,
            dockAlpha = dockAlpha,
            onLongPress = { isEditing = true },
            onAppLongPress = { app, source -> activeMenu = MenuState(app, source) },
            onTap = { 
                if (isEditing) {
                    scope.launch {
                        if (pagerState.currentPage > 0) pagerState.animateScrollToPage(0)
                        isEditing = false
                    }
                }
            },
            drawerProgress = drawerProgress
        )

        // 3. Edit Mode Actions (Bottom Bar)
        AnimatedVisibility(
            visible = isEditing,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp)
        ) {
            EditBottomBar()
        }

        // 4. App Drawer Overlay
        if (drawerProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (drawerProgress * 0.7f).coerceIn(0f, 1f)))
            )
            AppDrawer(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - drawerProgress) * size.height
                        alpha = drawerProgress.coerceIn(0f, 1f)
                    },
                allApps = allApps,
                onAppLongPress = { app -> activeMenu = MenuState(app, "DRAWER") }
            )
        }

        // 5. Context Menu Overlay
        if (activeMenu != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { activeMenu = null } },
                contentAlignment = Alignment.Center
            ) {
                AppContextMenu(
                    menuState = activeMenu!!,
                    onDismiss = { activeMenu = null },
                    onAddToHome = { app -> 
                        val updated = homeAppPkgs + app.pkg
                        prefs.edit().putStringSet("home_apps", updated).apply()
                        homeAppPkgs = updated
                        activeMenu = null
                        isDrawerOpen = false // Close drawer to show home
                    },
                    onRemove = { app, source ->
                        if (source == "HOME") {
                            val updated = homeAppPkgs - app.pkg
                            prefs.edit().putStringSet("home_apps", updated).apply()
                            homeAppPkgs = updated
                        } else if (source == "DOCK") {
                            val updated = dockAppPkgs - app.pkg
                            prefs.edit().putStringSet("dock_apps", updated).apply()
                            dockAppPkgs = updated
                        }
                        activeMenu = null
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeWorkspace(
    apps: List<AppItem>,
    homeAppPkgs: Set<String>,
    dockAppPkgs: Set<String>,
    wallpaperBitmap: ImageBitmap?,
    isEditing: Boolean,
    pagerState: androidx.compose.foundation.pager.PagerState,
    editScale: Float,
    editCorner: androidx.compose.ui.unit.Dp,
    dockAlpha: Float,
    onLongPress: () -> Unit,
    onAppLongPress: (AppItem, String) -> Unit,
    onTap: () -> Unit,
    drawerProgress: Float
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Push entire workspace up when drawer opens
                translationY = -drawerProgress * 200f
                alpha = (1f - drawerProgress).coerceIn(0f, 1f)
            }
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = if (isEditing) PaddingValues(horizontal = 48.dp) else PaddingValues(0.dp),
            pageSpacing = if (isEditing) 16.dp else 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Lift the pager up slightly in edit mode to make room for bottom buttons
                    translationY = if (isEditing) -100f else 0f
                }
        ) {
            page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = editScale
                        scaleY = editScale
                        clip = true
                        shape = RoundedCornerShape(editCorner.coerceAtLeast(0.dp))
                    }
                    .pointerInput(isEditing) {
                        detectTapGestures(
                            onLongPress = { onLongPress() },
                            onTap = { onTap() }
                        )
                    }
            ) {
                // Card-specific Wallpaper (Crisp)
                if (wallpaperBitmap != null) {
                    Image(
                        bitmap = wallpaperBitmap,
                        contentDescription = "Card Wallpaper",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
                }

                if (page == 0) {
                    // Main Page Content
                    Column(modifier = Modifier.fillMaxSize()) {
                        HomeClock()
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            val homeApps = apps.filter { homeAppPkgs.contains(it.pkg) }
                            items(homeApps) { app ->
                                AppIcon(
                                    item = app, 
                                    onClick = { launchApp(context, app.pkg) }, 
                                    onLongClick = { onAppLongPress(app, "HOME") }
                                )
                            }
                        }
                    }
                    
                    // Edit Overlays for Main Page
                    if (isEditing) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Page",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 24.dp)
                                .size(28.dp)
                        )
                    }
                } else {
                    // Add Page (+)
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Page",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
            }
        }

        // The Dock (Fades out in Edit Mode)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                .alpha(dockAlpha),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            val dockApps = apps.filter { app -> dockAppPkgs.any { dp -> app.pkg.contains(dp, true) } }.take(5)
            dockApps.forEach { app ->
                AppIcon(
                    item = app, 
                    showLabel = false, 
                    onClick = { launchApp(context, app.pkg) },
                    onLongClick = { onAppLongPress(app, "DOCK") }
                )
            }
        }
        
        // Dash indicator above dock
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 110.dp) // Sits above dock
                .alpha(dockAlpha)
                .size(width = 16.dp, height = 5.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.8f))
        )
    }
}

@Composable
fun HomeClock() {
    var time by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            time = SimpleDateFormat("h:mm", Locale.US).format(Date())
            date = SimpleDateFormat("EEE, MMMM d", Locale.US).format(Date())
            delay(1000)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, 
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 40.dp)
    ) {
        Text(time, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
        Text(date, color = Color.White, fontSize = 16.sp, modifier = Modifier.offset(y = (-8).dp))
    }
}

@Composable
fun EditBottomBar() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        EditAction("Wallpaper and\nstyle", Icons.Default.Add)
        EditAction("Themes", Icons.Default.Build)
        EditAction("Widgets", Icons.Default.List)
        EditAction("Settings", Icons.Default.Settings)
    }
}

@Composable
fun EditAction(label: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { }) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(modifier: Modifier = Modifier, allApps: List<AppItem>, onAppLongPress: (AppItem) -> Unit) {
    val context = LocalContext.current
    val pages = allApps.chunked(24) // Precise 6x4 Pagination limits
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // The Search Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Search", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("⋮", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        // App Grid Carousel
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val pageApps = pages.getOrNull(page) ?: emptyList()
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = false
            ) {
                items(pageApps) { app ->
                    AppIcon(
                        item = app, 
                        onClick = { launchApp(context, app.pkg) },
                        onLongClick = { onAppLongPress(app) }
                    )
                }
            }
        }

        // Paging indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in pages.indices) {
                val isActive = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isActive) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (isActive) 0.8f else 0.4f))
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(item: AppItem, showLabel: Boolean = true, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClick?.invoke() }
            )
            .padding(4.dp)
    ) {
        if (item.icon != null) {
            Image(
                bitmap = item.icon,
                contentDescription = item.name,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(20)) // The Perfect Squircle Ratio
                    .background(Color.White.copy(alpha = 0.1f)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(20)).background(Color.Gray.copy(alpha = 0.5f))
            )
        }
        
        if (showLabel) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 12.sp,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.8f),
                        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                        blurRadius = 4f
                    )
                ),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

object AppCache {
    var cachedApps: List<AppItem> = emptyList()
    private var lastPackageCount: Int = -1

    fun getApps(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        
        // Dynamic & Reliable: Return instantly if installed app count hasn't changed
        if (cachedApps.isNotEmpty() && resolveInfos.size == lastPackageCount) {
            return cachedApps
        }
        
        lastPackageCount = resolveInfos.size
        val newApps = resolveInfos.distinctBy { it.activityInfo.packageName }.map { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            val name = resolveInfo.loadLabel(pm).toString()
            val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val drawable = resolveInfo.loadIcon(pm)
            val bitmap = drawableToBitmap(drawable)
            AppItem(name, pkg, bitmap.asImageBitmap(), isSystem)
        }.sortedBy { it.name }
        
        cachedApps = newApps
        return newApps
    }
}

fun drawableToBitmap(drawable: Drawable): Bitmap {
    val size = 150
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
}

fun launchApp(ctx: Context, pkg: String) {
    try {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    } catch (e: Exception) {}
}

@Composable
fun AppContextMenu(
    menuState: MenuState,
    onDismiss: () -> Unit,
    onAddToHome: (AppItem) -> Unit,
    onRemove: (AppItem, String) -> Unit
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF21C1C1E)),
        modifier = Modifier.width(320.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    text = menuState.app.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive,
                    modifier = Modifier.align(Alignment.Center)
                )
                IconButton(
                    onClick = { 
                        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${menuState.app.pkg}"))
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = "App Info", tint = Color.White.copy(alpha = 0.8f))
                }
            }
            
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = Color.White.copy(alpha = 0.1f)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ContextMenuAction(Icons.Default.CheckCircle, "Select") { /* Placeholder */ }
                
                if (menuState.source == "DRAWER") {
                    ContextMenuAction(Icons.Default.Home, "Add to Home") { onAddToHome(menuState.app) }
                    if (!menuState.app.isSystem) {
                        ContextMenuAction(Icons.Default.Close, "Uninstall") {
                            val i = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${menuState.app.pkg}"))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                            onDismiss()
                        }
                    }
                } else {
                    ContextMenuAction(Icons.Default.Close, "Remove") { onRemove(menuState.app, menuState.source) }
                }
            }
        }
    }
}

@Composable
fun ContextMenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label, 
            color = Color.White, 
            fontSize = 11.sp, 
            maxLines = 1,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive
        )
    }
}
