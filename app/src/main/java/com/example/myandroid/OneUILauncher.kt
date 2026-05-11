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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Check
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

data class FolderData(val id: String, val name: String, val pkgs: List<String>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OneUILauncher() {
    val context = LocalContext.current
    var isDrawerOpen by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    
    var activeMenu by remember { mutableStateOf<MenuState?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPkgs by remember { mutableStateOf(setOf<String>()) }
    var activeFolder by remember { mutableStateOf<FolderData?>(null) }
    val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    
    val rawPages = prefs.getString("home_pages", null)
    val initialPages = if (rawPages != null) {
        val arr = org.json.JSONArray(rawPages)
        List(arr.length()) { i -> 
            val pageArr = arr.getJSONArray(i)
            List(pageArr.length()) { j -> pageArr.getString(j) }
        }
    } else {
        val oldSet = prefs.getStringSet("home_apps", null)
        if (oldSet != null) listOf(oldSet.toList().chunked(24).firstOrNull() ?: emptyList()) else listOf(emptyList())
    }
    var homePages by remember { mutableStateOf(initialPages.ifEmpty { listOf(emptyList()) }) }
    var homePageIndex by remember { mutableIntStateOf(prefs.getInt("home_page_index", 0).coerceIn(0, (homePages.size - 1).coerceAtLeast(0))) }
    var highlightedApp by remember { mutableStateOf<String?>(null) }

    val defaultDock = setOf("com.android.dialer", "com.samsung.android.dialer", "com.android.chrome", "com.whatsapp", "com.android.messaging")
    var dockAppPkgs by remember { mutableStateOf(prefs.getStringSet("dock_apps", defaultDock) ?: defaultDock) }

    fun saveHomePages(pages: List<List<String>>) {
        val arr = org.json.JSONArray()
        pages.forEach { page ->
            val pageArr = org.json.JSONArray()
            page.forEach { pageArr.put(it) }
            arr.put(pageArr)
        }
        prefs.edit().putString("home_pages", arr.toString()).apply()
    }

    val rawFolders = prefs.getString("drawer_folders", "[]")
    val initialFolders = mutableListOf<FolderData>()
    try {
        val arr = org.json.JSONArray(rawFolders)
        for (i in 0 until arr.length()) {
            val fObj = arr.getJSONObject(i)
            val pkgs = mutableListOf<String>()
            val pkgsArr = fObj.getJSONArray("pkgs")
            for (j in 0 until pkgsArr.length()) pkgs.add(pkgsArr.getString(j))
            initialFolders.add(FolderData(fObj.getString("id"), fObj.getString("name"), pkgs))
        }
    } catch(e: Exception){}
    var drawerFolders by remember { mutableStateOf(initialFolders.toList()) }

    fun saveDrawerFolders(folders: List<FolderData>) {
        val arr = org.json.JSONArray()
        folders.forEach { f ->
            val fObj = org.json.JSONObject()
            fObj.put("id", f.id)
            fObj.put("name", f.name)
            val pkgsArr = org.json.JSONArray()
            f.pkgs.forEach { pkgsArr.put(it) }
            fObj.put("pkgs", pkgsArr)
            arr.put(fObj)
        }
        prefs.edit().putString("drawer_folders", arr.toString()).apply()
    }
    
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

    val pageCount = homePages.size + if (isEditing) 1 else 0
    val pagerState = rememberPagerState(initialPage = homePageIndex, pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = isDrawerOpen || isEditing) {
        if (isDrawerOpen) isDrawerOpen = false
        else if (isEditing) {
            scope.launch {
                pagerState.animateScrollToPage(homePageIndex)
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

        // (Selection Mode Top Bar dynamically reordered below to utilize natural Box layering)

        // 2. Home Workspace (Pages & Dock)
        HomeWorkspace(
            apps = allApps,
            homePages = homePages,
            homePageIndex = homePageIndex,
            highlightedApp = highlightedApp,
            dockAppPkgs = dockAppPkgs,
            wallpaperBitmap = wallpaperBitmap,
            isEditing = isEditing,
            isSelectionMode = isSelectionMode,
            selectedPkgs = selectedPkgs,
            pagerState = pagerState,
            editScale = editScale,
            editCorner = editCorner,
            dockAlpha = dockAlpha,
            onLongPress = { isEditing = true },
            onAppLongPress = { app, source -> activeMenu = MenuState(app, source) },
            onTap = { 
                if (isEditing) {
                    scope.launch {
                        pagerState.animateScrollToPage(homePageIndex)
                        isEditing = false
                    }
                }
            },
            onDeletePage = { pageIdx ->
                val mutablePages = homePages.toMutableList()
                mutablePages.removeAt(pageIdx)
                if (mutablePages.isEmpty()) mutablePages.add(emptyList())
                homePages = mutablePages
                saveHomePages(mutablePages)
                
                if (homePageIndex == pageIdx) {
                    homePageIndex = 0
                    prefs.edit().putInt("home_page_index", 0).apply()
                } else if (homePageIndex > pageIdx) {
                    homePageIndex--
                    prefs.edit().putInt("home_page_index", homePageIndex).apply()
                }
            },
            onSetHome = { pageIdx ->
                homePageIndex = pageIdx
                prefs.edit().putInt("home_page_index", pageIdx).apply()
            },
            onToggleSelect = { pkg ->
                selectedPkgs = if (selectedPkgs.contains(pkg)) selectedPkgs - pkg else selectedPkgs + pkg
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
                drawerFolders = drawerFolders,
                isSelectionMode = isSelectionMode,
                selectedPkgs = selectedPkgs,
                onToggleSelect = {
                    selectedPkgs = if (selectedPkgs.contains(it)) selectedPkgs - it else selectedPkgs + it
                },
                onOpenFolder = { activeFolder = it },
                onAppLongPress = { app -> activeMenu = MenuState(app, "DRAWER") }
            )
        }

        // Selection Mode Top Bar
        if (isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .statusBarsPadding()
                    .padding(vertical = 16.dp, horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                    selectedPkgs.forEach { pkg ->
                        val app = allApps.find { it.pkg == pkg }
                        if (app != null && !app.isSystem) {
                            val i = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }
                    }
                    isSelectionMode = false
                    selectedPkgs = emptySet()
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Disable", tint = Color.Gray, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Disable", color = Color.Gray, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                    if (selectedPkgs.isNotEmpty()) {
                        val newFolder = FolderData(java.util.UUID.randomUUID().toString(), "Folder", selectedPkgs.toList())
                        val updated = mutableListOf(newFolder)
                        updated.addAll(drawerFolders)
                        drawerFolders = updated
                        saveDrawerFolders(updated)
                    }
                    isSelectionMode = false
                    selectedPkgs = emptySet()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Create folder", tint = Color.Gray, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Create folder", color = Color.Gray, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive)
                }
            }
        }

        // 5. Folder Content Overlay
        if (activeFolder != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .pointerInput(Unit) { detectTapGestures { activeFolder = null } }
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = activeFolder!!.name, 
                        color = Color.White, 
                        fontSize = 32.sp, 
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive,
                        modifier = Modifier.padding(bottom = 32.dp)
                    )
                    
                    val folderApps = activeFolder!!.pkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(folderApps) { app ->
                            AppIcon(
                                item = app,
                                onClick = { launchApp(context, app.pkg) }
                            )
                        }
                    }
                }
            }
        }

        // 6. Context Menu Overlay
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
                        val mutablePages = homePages.map { it.toMutableList() }.toMutableList()
                        var addedPageIdx = -1
                        for (i in mutablePages.indices) {
                            if (mutablePages[i].size < 24) {
                                mutablePages[i].add(app.pkg)
                                addedPageIdx = i
                                break
                            }
                        }
                        if (addedPageIdx == -1) {
                            mutablePages.add(mutableListOf(app.pkg))
                            addedPageIdx = mutablePages.size - 1
                        }
                        homePages = mutablePages
                        saveHomePages(mutablePages)
                        activeMenu = null
                        isDrawerOpen = false
                        
                        highlightedApp = app.pkg
                        scope.launch {
                            delay(300) // Wait for drawer to close
                            pagerState.animateScrollToPage(addedPageIdx)
                            delay(1500)
                            if (highlightedApp == app.pkg) highlightedApp = null
                        }
                    },
                    onRemove = { app, source ->
                        if (source == "HOME") {
                            val mutablePages = homePages.map { it.toMutableList() }.toMutableList()
                            for (i in mutablePages.indices) {
                                if (mutablePages[i].remove(app.pkg)) break
                            }
                            homePages = mutablePages
                            saveHomePages(mutablePages)
                        } else if (source == "DOCK") {
                            val updated = dockAppPkgs - app.pkg
                            prefs.edit().putStringSet("dock_apps", updated).apply()
                            dockAppPkgs = updated
                        }
                        activeMenu = null
                    },
                    onSelect = {
                        isSelectionMode = true
                        selectedPkgs = setOf(activeMenu!!.app.pkg)
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
    homePages: List<List<String>>,
    homePageIndex: Int,
    highlightedApp: String?,
    dockAppPkgs: Set<String>,
    wallpaperBitmap: ImageBitmap?,
    isEditing: Boolean,
    isSelectionMode: Boolean,
    selectedPkgs: Set<String>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    editScale: Float,
    editCorner: androidx.compose.ui.unit.Dp,
    dockAlpha: Float,
    onLongPress: () -> Unit,
    onAppLongPress: (AppItem, String) -> Unit,
    onTap: () -> Unit,
    onDeletePage: (Int) -> Unit,
    onSetHome: (Int) -> Unit,
    onToggleSelect: (String) -> Unit,
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

                if (page < homePages.size) {
                    // Main Page Content
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (page == homePageIndex) {
                            HomeClock()
                        } else {
                            // Spacer to maintain grid alignment when clock is absent
                            Spacer(modifier = Modifier.height(120.dp))
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            userScrollEnabled = false
                        ) {
                            val pagePkgs = homePages[page]
                            val pageApps = pagePkgs.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
                            items(pageApps) { app ->
                                AppIcon(
                                    item = app, 
                                    isHighlighted = app.pkg == highlightedApp,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedPkgs.contains(app.pkg),
                                    onClick = { 
                                        if (isSelectionMode) onToggleSelect(app.pkg)
                                        else launchApp(context, app.pkg) 
                                    }, 
                                    onLongClick = { if (!isSelectionMode) onAppLongPress(app, "HOME") }
                                )
                            }
                        }
                    }
                    
                    // Edit Overlays for Main Pages
                    if (isEditing) {
                        IconButton(
                            onClick = { onSetHome(page) },
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Set Home",
                                tint = if (page == homePageIndex) Color.White else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = { onDeletePage(page) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Page",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
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
                    isSelectionMode = isSelectionMode,
                    isSelected = selectedPkgs.contains(app.pkg),
                    onClick = { 
                        if (isSelectionMode) onToggleSelect(app.pkg)
                        else launchApp(context, app.pkg) 
                    },
                    onLongClick = { if (!isSelectionMode) onAppLongPress(app, "DOCK") }
                )
            }
        }
        
        // Page indicators above dock
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 110.dp) // Sits above dock
                .alpha(dockAlpha),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in homePages.indices) {
                val isActive = pagerState.currentPage == i
                val isHome = i == homePageIndex
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isActive) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color.White else if (isHome) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.4f))
                )
            }
        }
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
fun AppDrawer(
    modifier: Modifier = Modifier, 
    allApps: List<AppItem>, 
    drawerFolders: List<FolderData>,
    isSelectionMode: Boolean,
    selectedPkgs: Set<String>,
    onToggleSelect: (String) -> Unit,
    onOpenFolder: (FolderData) -> Unit,
    onAppLongPress: (AppItem) -> Unit
) {
    val context = LocalContext.current
    val folderPkgs = drawerFolders.flatMap { it.pkgs }.toSet()
    val drawerApps = allApps.filter { !folderPkgs.contains(it.pkg) }
    val drawerItems = mutableListOf<Any>()
    drawerItems.addAll(drawerFolders)
    drawerItems.addAll(drawerApps)
    
    val pages = drawerItems.chunked(24) // Precise 6x4 Pagination limits
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        if (!isSelectionMode) {
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
        } else {
            Spacer(modifier = Modifier.height(72.dp))
        }

        // App Grid Carousel
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val pageItems = pages.getOrNull(page) ?: emptyList()
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = false
            ) {
                items(pageItems.size) { idx ->
                    val item = pageItems[idx]
                    if (item is FolderData) {
                        FolderIcon(folder = item, allApps = allApps, onClick = { onOpenFolder(item) })
                    } else if (item is AppItem) {
                        AppIcon(
                            item = item, 
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedPkgs.contains(item.pkg),
                            onClick = { 
                                if (isSelectionMode) onToggleSelect(item.pkg)
                                else launchApp(context, item.pkg) 
                            },
                            onLongClick = { if (!isSelectionMode) onAppLongPress(item) }
                        )
                    }
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
fun AppIcon(
    item: AppItem, 
    showLabel: Boolean = true, 
    isHighlighted: Boolean = false, 
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit, 
    onLongClick: (() -> Unit)? = null
) {
    val scale by animateFloatAsState(
        targetValue = if (isHighlighted) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow),
        label = "iconScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClick?.invoke() }
            )
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.size(46.dp)) {
            if (isHighlighted) {
                Box(modifier = Modifier.matchParentSize().clip(RoundedCornerShape(20)).background(Color.White.copy(alpha = 0.4f)))
            }
            if (item.icon != null) {
                Image(
                    bitmap = item.icon,
                    contentDescription = item.name,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(20)) // The Perfect Squircle Ratio
                        .background(Color.White.copy(alpha = 0.1f)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.matchParentSize().clip(RoundedCornerShape(20)).background(Color.Gray.copy(alpha = 0.5f))
                )
            }
            
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color.Gray else Color.White.copy(alpha = 0.2f))
                        .border(1.dp, if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                    }
                }
            }
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
fun FolderIcon(folder: FolderData, allApps: List<AppItem>, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(20))
                .background(Color.White.copy(alpha = 0.2f))
                .padding(6.dp)
        ) {
            val folderApps = folder.pkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }.take(9)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in 0..2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (col in 0..2) {
                            val idx = row * 3 + col
                            if (idx < folderApps.size) {
                                val app = folderApps[idx]
                                if (app.icon != null) {
                                    Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(10.dp), contentScale = ContentScale.Crop)
                                } else {
                                    Box(modifier = Modifier.size(10.dp).background(Color.Gray))
                                }
                            } else {
                                Box(modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = folder.name,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp
        )
    }
}

@Composable
fun AppContextMenu(
    menuState: MenuState,
    onDismiss: () -> Unit,
    onAddToHome: (AppItem) -> Unit,
    onRemove: (AppItem, String) -> Unit,
    onSelect: () -> Unit
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
                        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${menuState.app.pkg}"))
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
                ContextMenuAction(Icons.Default.CheckCircle, "Select") { 
                    onSelect()
                    onDismiss()
                }
                
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
