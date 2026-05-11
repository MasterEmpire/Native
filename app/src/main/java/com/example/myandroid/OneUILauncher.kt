package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AppItem(val name: String, val pkg: String, val icon: ImageBitmap?, val isSystem: Boolean)

@Composable
fun OneUILauncher() {
    val context = LocalContext.current
    var isDrawerOpen by remember { mutableStateOf(false) }
    
    // Harvest Apps in Background
    val allApps by produceState<List<AppItem>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { fetchApps(context) }
    }

    // Native Wallpaper Handling
    val wallpaperBitmap by produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "launcher_wallpaper.jpg")
            if (file.exists()) {
                try { android.graphics.BitmapFactory.decodeFile(file.absolutePath).asImageBitmap() }
                catch (e: Exception) { null }
            } else null
        }
    }

    // The Engine: Physics-based cross-fade and translation
    val drawerProgress by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "DrawerAnimation"
    )

    BackHandler(enabled = isDrawerOpen) {
        isDrawerOpen = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount < -15 && !isDrawerOpen) isDrawerOpen = true
                        else if (dragAmount > 15 && isDrawerOpen) isDrawerOpen = false
                    }
                )
            }
    ) {
        // 1. Base Layer: Wallpaper
        if (wallpaperBitmap != null) {
            Image(
                bitmap = wallpaperBitmap!!,
                contentDescription = "Wallpaper",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // 2. Translucent Filter (Darkens smoothly when swiping up)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f + (drawerProgress * 0.6f)))
        )

        // 3. True Home Screen
        HomeScreen(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -drawerProgress * 300f
                    alpha = 1f - drawerProgress
                },
            apps = allApps
        )

        // 4. App Drawer (Grid)
        AppDrawer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Rises up from beneath the screen boundary
                    translationY = (1f - drawerProgress) * size.height
                    alpha = drawerProgress
                },
            allApps = allApps
        )
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier, apps: List<AppItem>) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Spacer(modifier = Modifier.weight(1f))
        
        // Paging indicators (Samsung Dash + Dots)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(width = 16.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.8f)))
            Spacer(modifier = Modifier.width(6.dp))
            for (i in 0 until 4) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("+", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, modifier = Modifier.offset(y = (-1).dp))
        }

        // The Dock
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
            Text("⋮", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
