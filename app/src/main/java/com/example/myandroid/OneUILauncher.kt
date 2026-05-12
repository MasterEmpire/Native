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
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
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
    var drawerExitDir by remember { mutableFloatStateOf(1f) }
    var isEditing by remember { mutableStateOf(false) }
    
    var activeMenu by remember { mutableStateOf<MenuState?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPkgs by remember { mutableStateOf(setOf<String>()) }
    var activeFolder by remember { mutableStateOf<FolderData?>(null) }
    var isNewFolder by remember { mutableStateOf(false) }
    val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    val gridCols by remember { mutableIntStateOf(prefs.getInt("grid_cols", 4)) }
    val gridRows by remember { mutableIntStateOf(prefs.getInt("grid_rows", 6)) }
    val iconSizeDp by remember { mutableIntStateOf(prefs.getInt("icon_size", 56)) }
    val vGapDp by remember { mutableIntStateOf(prefs.getInt("v_gap", 22)) }
    val hGapDp by remember { mutableIntStateOf(prefs.getInt("h_gap", 16)) }
    val itemsPerPage = gridCols * gridRows
    
    val rawPages = prefs.getString("home_pages", null)
    val initialPages = if (rawPages != null) {
        val arr = org.json.JSONArray(rawPages)
        List(arr.length()) { i -> 
            val pageArr = arr.getJSONArray(i)
            List(pageArr.length()) { j -> pageArr.getString(j) }
        }
    } else {
        val oldSet = prefs.getStringSet("home_apps", null)
        if (oldSet != null) listOf(oldSet.toList().chunked(itemsPerPage).firstOrNull() ?: emptyList()) else listOf(emptyList())
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
                    while (options.outWidth / scale > 1200 || options.outHeight / scale > 2500) { scale *= 2 }
                    val finalOptions = android.graphics.BitmapFactory.Options()
                    finalOptions.inSampleSize = scale
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, finalOptions)?.asImageBitmap()
                } catch (e: Exception) { null }
            } else null
        }
    }

    val drawerProgress by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "DrawerAnimation"
    )
    
    val editScale by animateFloatAsState(
        targetValue = if (isEditing) 0.9f else 1f,
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

    BackHandler(enabled = activeMenu != null || activeFolder != null || isSelectionMode || isDrawerOpen || isEditing) {
        if (activeMenu != null) {
            activeMenu = null
        } else if (activeFolder != null) {
            activeFolder = null
        } else if (isSelectionMode) {
            isSelectionMode = false
            selectedPkgs = emptySet()
        } else if (isDrawerOpen) {
            drawerExitDir = 1f 
            isDrawerOpen = false
        } else if (isEditing) {
            isEditing = false
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
                        onVerticalDrag = { _, dragAmount -> totalDrag += dragAmount },
                        onDragEnd = {
                            if (!isDrawerOpen && totalDrag < -40) {
                                drawerExitDir = 1f
                                isDrawerOpen = true
                            } else if (isDrawerOpen) {
                                if (totalDrag > 40) {
                                    drawerExitDir = 1f
                                    isDrawerOpen = false
                                } else if (totalDrag < -40) {
                                    drawerExitDir = -1f
                                    isDrawerOpen = false
                                }
                            }
                        }
                    )
                }
            }
    ) {
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

        HomeWorkspace(
            apps = allApps,
            gridCols = gridCols,
            iconSize = iconSizeDp,
            vGap = vGapDp,
            hGap = hGapDp,
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
            onTap = { if (isEditing) isEditing = false },
            onAddPage = {
                val mutablePages = homePages.toMutableList()
                mutablePages.add(emptyList())
                homePages = mutablePages
                saveHomePages(mutablePages)
                scope.launch { pagerState.animateScrollToPage(mutablePages.size - 1) }
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
            drawerProgress = drawerProgress,
            drawerExitDir = drawerExitDir
        )

        AnimatedVisibility(
            visible = isEditing,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp)
        ) {
            EditBottomBar()
        }

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
                        translationY = (1f - drawerProgress) * size.height * drawerExitDir
                        alpha = drawerProgress.coerceIn(0f, 1f)
                    },
                allApps = allApps,
                gridCols = gridCols,
                itemsPerPage = itemsPerPage,
                iconSize = iconSizeDp,
                vGap = vGapDp,
                hGap = hGapDp,
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
                    Text("Disable", color = Color.Gray, fontSize = 13.sp)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                    if (selectedPkgs.isNotEmpty()) {
                        val newFolder = FolderData(java.util.UUID.randomUUID().toString(), "Folder", selectedPkgs.toList())
                        val updated = mutableListOf(newFolder)
                        updated.addAll(drawerFolders)
                        drawerFolders = updated
                        saveDrawerFolders(updated)
                        activeFolder = newFolder
                        isNewFolder = true
                    }
                    isSelectionMode = false
                    selectedPkgs = emptySet()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Create folder", tint = Color.Gray, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Create folder", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }

        val displayedFolder = remember { mutableStateOf<FolderData?>(null) }
        LaunchedEffect(activeFolder) { if (activeFolder != null) displayedFolder.value = activeFolder }

        AnimatedVisibility(
            visible = activeFolder != null,
            enter = fadeIn(tween(300)) + androidx.compose.animation.scaleIn(initialScale = 0.5f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(200)) + androidx.compose.animation.scaleOut(targetScale = 0.5f, animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            val folder = displayedFolder.value ?: return@AnimatedVisibility
            var folderName by remember(folder) { mutableStateOf(folder.name) }
            val focusRequester = remember { FocusRequester() }
            val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

            LaunchedEffect(folder) {
                if (isNewFolder) {
                    delay(150)
                    focusRequester.requestFocus()
                    keyboardController?.show()
                    isNewFolder = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { 
                        detectTapGestures { 
                            val updatedFolders = drawerFolders.map { if (it.id == folder.id) it.copy(name = folderName) else it }
                            drawerFolders = updatedFolders
                            saveDrawerFolders(updatedFolders)
                            keyboardController?.hide()
                            activeFolder = null 
                        } 
                    }
            ) {
                if (wallpaperBitmap != null) {
                    Image(
                        bitmap = wallpaperBitmap!!,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(radius = 40.dp)
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)))
                }

                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BasicTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 32.sp, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val updatedFolders = drawerFolders.map { if (it.id == folder.id) it.copy(name = folderName) else it }
                                drawerFolders = updatedFolders
                                saveDrawerFolders(updatedFolders)
                                keyboardController?.hide()
                            }
                        ),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.padding(bottom = 32.dp).fillMaxWidth().focusRequester(focusRequester)
                    )
                    
                    val folderApps = folder.pkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridCols),
                        verticalArrangement = Arrangement.spacedBy(vGapDp.dp),
                        horizontalArrangement = Arrangement.spacedBy(hGapDp.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(folderApps) { app ->
                            AppIcon(item = app, size = iconSizeDp, onClick = { launchApp(context, app.pkg) })
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = activeMenu != null,
            enter = fadeIn(tween(200)) + androidx.compose.animation.scaleIn(initialScale = 0.92f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(150)) + androidx.compose.animation.scaleOut(targetScale = 0.92f),
            modifier = Modifier.fillMaxSize()
        ) {
            val menu = activeMenu ?: return@AnimatedVisibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(Unit) { detectTapGestures { activeMenu = null } },
                contentAlignment = Alignment.Center
            ) {
                AppContextMenu(
                    menuState = menu,
                    onDismiss = { activeMenu = null },
                    onAddToHome = { app -> 
                        if (dockAppPkgs.size < 4) {
                            val updatedDock = dockAppPkgs + app.pkg
                            dockAppPkgs = updatedDock
                            prefs.edit().putStringSet("dock_apps", updatedDock).apply()
                            activeMenu = null
                            isDrawerOpen = false
                        } else {
                            val mutablePages = homePages.map { it.toMutableList() }.toMutableList()
                            var addedPageIdx = -1
                            for (i in mutablePages.indices) {
                                if (mutablePages[i].size < itemsPerPage) {
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
                                delay(300)
                                pagerState.animateScrollToPage(addedPageIdx)
                                delay(1500)
                                if (highlightedApp == app.pkg) highlightedApp = null
                            }
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
    gridCols: Int,
    iconSize: Int,
    vGap: Int,
    hGap: Int,
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
    onAddPage: () -> Unit,
    onDeletePage: (Int) -> Unit,
    onSetHome: (Int) -> Unit,
    onToggleSelect: (String) -> Unit,
    drawerProgress: Float,
    drawerExitDir: Float
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = -drawerProgress * 200f * drawerExitDir
                alpha = (1f - drawerProgress).coerceIn(0f, 1f)
            }
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = if (isEditing) PaddingValues(horizontal = 40.dp) else PaddingValues(0.dp),
            pageSpacing = if (isEditing) 16.dp else 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = if (isEditing) -40f else 0f
                }
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedVisibility(
                    visible = isEditing && page < homePages.size,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    IconButton(onClick = { onSetHome(page) }, modifier = Modifier.padding(bottom = 12.dp)) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Set Home",
                            tint = if (page == homePageIndex) Color.White else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight(if (isEditing) 0.92f else 1f)
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = editScale
                            scaleY = editScale
                            clip = true
                            shape = RoundedCornerShape(editCorner.coerceAtLeast(0.dp))
                        }
                        .pointerInput(isEditing, page, homePages.size) {
                            detectTapGestures(
                                onLongPress = { onLongPress() },
                                onTap = { 
                                    if (isEditing && page >= homePages.size) onAddPage() else onTap()
                                }
                            )
                        }
                        .border(
                            width = if (isEditing) 1.dp else 0.dp,
                            color = Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(editCorner.coerceAtLeast(0.dp))
                        )
                ) {
                    if (wallpaperBitmap != null) {
                        Image(bitmap = wallpaperBitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1C1E)))
                    }

                    if (page < homePages.size) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (isEditing) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    IconButton(onClick = { onDeletePage(page) }, modifier = Modifier.padding(top = 8.dp)) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Page", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(26.dp))
                                    }
                                    Box(modifier = Modifier.fillMaxWidth(0.85f).height(0.5.dp).background(Color.White.copy(alpha = 0.2f)))
                                }
                            }

                            if (page == homePageIndex && !isEditing) {
                                HomeClock()
                            } else if (!isEditing) {
                                Spacer(modifier = Modifier.height(120.dp))
                            } else {
                                Spacer(modifier = Modifier.height(20.dp))
                            }

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(gridCols),
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(vGap.dp),
                                horizontalArrangement = Arrangement.spacedBy(hGap.dp),
                                userScrollEnabled = false
                            ) {
                                val pagePkgs = homePages[page]
                                val pageApps = pagePkgs.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
                                items(pageApps) { app ->
                                    AppIcon(
                                        item = app, 
                                        size = iconSize,
                                        isHighlighted = app.pkg == highlightedApp,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = selectedPkgs.contains(app.pkg),
                                        onClick = { if (isSelectionMode) onToggleSelect(app.pkg) else launchApp(context, app.pkg) }, 
                                        onLongClick = { if (!isSelectionMode) onAppLongPress(app, "HOME") }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "Add Page", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(64.dp))
                        }
                    }
                }
            }
        }

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
                    size = iconSize,
                    showLabel = false, 
                    isSelectionMode = isSelectionMode,
                    isSelected = selectedPkgs.contains(app.pkg),
                    onClick = { if (isSelectionMode) onToggleSelect(app.pkg) else launchApp(context, app.pkg) },
                    onLongClick = { if (!isSelectionMode) onAppLongPress(app, "DOCK") }
                )
            }
        }
        
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 114.dp)
                .alpha(dockAlpha),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in homePages.indices) {
                val isActive = pagerState.currentPage == i
                val isHome = i == homePageIndex
                Box(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { scope.launch { pagerState.animateScrollToPage(i) } }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .size(if (isActive) 8.dp else 6.dp)
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        EditAction("Wallpaper and style", Icons.Default.Create)
        EditAction("Themes", Icons.Default.Star)
        EditAction("Widgets", Icons.Default.Build)
        EditAction("Settings", Icons.Default.Settings)
    }
}

@Composable
fun EditAction(label: String, icon: ImageVector) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, 
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { } 
            .padding(8.dp)
            .width(85.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 12.sp, maxLines = 2)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    modifier: Modifier = Modifier, 
    allApps: List<AppItem>, 
    gridCols: Int,
    itemsPerPage: Int,
    iconSize: Int,
    vGap: Int,
    hGap: Int,
    drawerFolders: List<FolderData>,
    isSelectionMode: Boolean,
    selectedPkgs: Set<String>,
    onToggleSelect: (String) -> Unit,
    onOpenFolder: (FolderData) -> Unit,
    onAppLongPress: (AppItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val drawerItems = mutableListOf<Any>()
    if (searchQuery.isEmpty()) {
        val folderPkgs = drawerFolders.flatMap { it.pkgs }.toSet()
        val drawerApps = allApps.filter { !folderPkgs.contains(it.pkg) }
        drawerItems.addAll(drawerFolders)
        drawerItems.addAll(drawerApps)
    } else {
        val filteredApps = allApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
        drawerItems.addAll(filteredApps)
    }
    
    val pages = drawerItems.chunked(itemsPerPage)
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        if (!isSelectionMode) {
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
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        scope.launch { pagerState.scrollToPage(0) }
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) Text("Search", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                        innerTextField()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Default.Close, 
                        contentDescription = "Clear", 
                        tint = Color.White.copy(alpha = 0.7f), 
                        modifier = Modifier.size(20.dp).clickable { searchQuery = "" }
                    )
                } else {
                    Text("⋮", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Spacer(modifier = Modifier.height(72.dp))
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val pageItems = pages.getOrNull(page) ?: emptyList()
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridCols),
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(vGap.dp),
                horizontalArrangement = Arrangement.spacedBy(hGap.dp),
                userScrollEnabled = false
            ) {
                items(pageItems.size) { idx ->
                    val item = pageItems[idx]
                    if (item is FolderData) {
                        FolderIcon(folder = item, allApps = allApps, onClick = { onOpenFolder(item) })
                    } else if (item is AppItem) {
                        AppIcon(
                            item = item, 
                            size = iconSize,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedPkgs.contains(item.pkg),
                            onClick = { if (isSelectionMode) onToggleSelect(item.pkg) else launchApp(context, item.pkg) },
                            onLongClick = { if (!isSelectionMode) onAppLongPress(item) }
                        )
                    }
                }
            }
        }

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
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { scope.launch { pagerState.animateScrollToPage(i) } }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .size(if (isActive) 8.dp else 6.dp)
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
    size: Int = 56,
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
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(onClick = { onClick() }, onLongClick = { onLongClick?.invoke() })
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.size(size.dp)) {
            if (isHighlighted) {
                Box(modifier = Modifier.matchParentSize().clip(RoundedCornerShape(24)).background(Color.White.copy(alpha = 0.4f)))
            }
            if (item.icon != null) {
                Image(
                    bitmap = item.icon,
                    contentDescription = item.name,
                    modifier = Modifier.matchParentSize().clip(RoundedCornerShape(24)).background(Color.White.copy(alpha = 0.1f)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.matchParentSize().clip(RoundedCornerShape(24)).background(Color.Gray.copy(alpha = 0.5f)))
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
                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
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

    fun invalidate() {
        lastPackageCount = -1
        cachedApps = emptyList()
    }

    fun getApps(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        
        val hiddenSet = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            .getStringSet("hidden_packages", emptySet()) ?: emptySet()

        if (cachedApps.isNotEmpty() && resolveInfos.size == lastPackageCount) return cachedApps
        
        lastPackageCount = resolveInfos.size
        val newApps = resolveInfos.distinctBy { it.activityInfo.packageName }
            .filter { !hiddenSet.contains(it.activityInfo.packageName) }
            .map { resolveInfo ->
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
                .size(56.dp)
                .clip(RoundedCornerShape(24))
                .background(Color.White.copy(alpha = 0.2f))
                .padding(8.dp)
        ) {
            val folderApps = folder.pkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }.take(9)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (row in 0..2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (col in 0..2) {
                            val idx = row * 3 + col
                            if (idx < folderApps.size) {
                                val app = folderApps[idx]
                                if (app.icon != null) {
                                    Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(11.dp), contentScale = ContentScale.Crop)
                                } else {
                                    Box(modifier = Modifier.size(11.dp).background(Color.Gray))
                                }
                            } else {
                                Box(modifier = Modifier.size(11.dp))
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
            fontSize = 12.sp,
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
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color(0xCC1A1C1E),
        modifier = Modifier
            .width(280.dp)
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
            .blur(radius = 0.dp)
    ) {
        Column(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(Modifier.width(24.dp))
                Text(
                    text = menuState.app.name,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                IconButton(
                    onClick = {
                        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${menuState.app.pkg}"))
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                        onDismiss()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = "App Info", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.15f)))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ContextMenuAction(Icons.Default.CheckCircle, "Select") { onSelect(); onDismiss() }

                if (menuState.source == "DRAWER") {
                    ContextMenuAction(Icons.Default.AddCircle, "Add to Home") { onAddToHome(menuState.app) }
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
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .width(75.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp,
            maxLines = 2
        )
    }
}
