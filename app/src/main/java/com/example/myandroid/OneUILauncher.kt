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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
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
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppItem(val name: String, val pkg: String, val icon: ImageBitmap?, val isSystem: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OneUILauncher() {
    val context = LocalContext.current
    var isDrawerOpen by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    
    val allApps by produceState<List<AppItem>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { fetchApps(context) }
    }

    val wallpaperBitmap by produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "launcher_wallpaper.jpg")
            if (file.exists()) {
                try { android.graphics.BitmapFactory.decodeFile(file.absolutePath).asImageBitmap() }
                catch (e: Exception) { null }
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

    BackHandler(enabled = isDrawerOpen || isEditing) {
        if (isDrawerOpen) isDrawerOpen = false
        else if (isEditing) isEditing = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isEditing) {
                if (!isEditing) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            if (dragAmount < -15 && !isDrawerOpen) isDrawerOpen = true
                            else if (dragAmount > 15 && isDrawerOpen) isDrawerOpen = false
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
            wallpaperBitmap = wallpaperBitmap,
            isEditing = isEditing,
            editScale = editScale,
            editCorner = editCorner,
            dockAlpha = dockAlpha,
            onLongPress = { isEditing = true },
            onTap = { if (isEditing) isEditing = false },
            drawerProgress = drawerProgress
        )

        // 3. Edit Mode Actions (Bottom Bar)
        AnimatedVisibility(
            visible = isEditing,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) {
            EditBottomBar()
        }

        // 4. App Drawer Overlay
        if (drawerProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = drawerProgress * 0.7f))
            )
            AppDrawer(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - drawerProgress) * size.height
                        alpha = drawerProgress
                    },
                allApps = allApps
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeWorkspace(
    apps: List<AppItem>,
    wallpaperBitmap: ImageBitmap?,
    isEditing: Boolean,
    editScale: Float,
    editCorner: androidx.compose.ui.unit.Dp,
    dockAlpha: Float,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    drawerProgress: Float
) {
    // Manage dynamic pages. If editing, we append a "+" page.
    val pageCount = if (isEditing) 2 else 1
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Push entire workspace up when drawer opens
                translationY = -drawerProgress * 200f
                alpha = 1f - drawerProgress
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
                        shape = RoundedCornerShape(editCorner)
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
                        // Future: Render Grid apps here
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
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                .alpha(dockAlpha),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            val defaultPkgs = listOf("com.android.dialer", "com.samsung.android.dialer", "com.android.chrome", "com.whatsapp", "com.android.messaging")
            val dockApps = apps.filter { defaultPkgs.any { dp -> it.pkg.contains(dp, true) } }.take(4).let {
                if (it.size < 4) apps.take(4) else it
            }
            dockApps.forEach { app ->
                AppIcon(item = app, showLabel = false, onClick = { launchApp(LocalContext.current, app.pkg) })
            }
        }
        
        // Dash indicator above dock
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
            .padding(top = 90.dp)
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
fun AppDrawer(modifier: Modifier = Modifier, allApps: List<AppItem>) {
    val pages = allApps.chunked(24) // Precise 6x4 Pagination limits
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
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
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pageApps) { app ->
                    AppIcon(item = app, onClick = { launchApp(LocalContext.current, app.pkg) })
                }
            }
        }

        // Paging indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
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

@Composable
fun AppIcon(item: AppItem, showLabel: Boolean = true, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        if (item.icon != null) {
            Image(
                bitmap = item.icon,
                contentDescription = item.name,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(26)) // The Perfect Squircle Ratio
                    .background(Color.White.copy(alpha = 0.1f)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(26)).background(Color.Gray.copy(alpha = 0.5f))
            )
        }
        
        if (showLabel) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp,
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

fun fetchApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val resolveInfos = pm.queryIntentActivities(intent, 0)
    
    return resolveInfos.distinctBy { it.activityInfo.packageName }.map { resolveInfo ->
        val pkg = resolveInfo.activityInfo.packageName
        val name = resolveInfo.loadLabel(pm).toString()
        val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val drawable = resolveInfo.loadIcon(pm)
        val bitmap = drawableToBitmap(drawable)
        AppItem(name, pkg, bitmap.asImageBitmap(), isSystem)
    }.sortedBy { it.name }
}

fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
    val width = if (drawable.intrinsicWidth <= 0) 100 else drawable.intrinsicWidth
    val height = if (drawable.intrinsicHeight <= 0) 100 else drawable.intrinsicHeight
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
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
